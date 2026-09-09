package io.github.belzenn.androidlinuxbridge.connection

import android.os.Handler
import android.os.Looper
import io.github.belzenn.androidlinuxbridge.protocol.MessageRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLHandshakeException
import java.security.cert.X509Certificate
import kotlinx.coroutines.CompletableDeferred
import java.net.Socket

enum class ConnectionStatus {
    CONNECTING,
    AWAITING_APPROVAL,
    CONNECTED,
    DISCONNECTED,
    RECONNECT_REQUIRED
}

class ConnectionManager(
    private val host: String,
    private val port: Int,
    private val deviceId: String,
    private val deviceModel: String,
    private var pairingToken: String?,
    private val onPairingTokenReceived: (String) -> Unit,
    private val messageRouter: MessageRouter,
    private val onStatusChanged: (ConnectionStatus) -> Unit,
    private val onLog: (String) -> Unit,
    private var pinnedKey: String? = null,
    private val onTrustReceived: (String, String) -> Unit = { _, _ -> },
    private val onFingerprint: (String?, ((Boolean) -> Unit)?) -> Unit = { _, _ -> }
) {
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val writerLock = Any()

    @Volatile
    private var running = false

    @Volatile
    private var socket: Socket? = null

    private var writer: BufferedWriter? = null
    @Volatile private var authenticated = false
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var stopped = false
    @Volatile private var confirmation: CompletableDeferred<Boolean>? = null
    private val events = Channel<Pair<Socket, JSONObject>>(100)

    init {
        scope.launch {
            for ((connection, event) in events) {
                try {
                    synchronized(writerLock) {
                        if (authenticated && socket === connection) sendMessage(event)
                    }
                } catch (_: IOException) {
                    // Wake the receive loop and let its normal reconnect path recover.
                    try { connection.close() } catch (_: IOException) { }
                }
            }
        }
    }

    fun sendEvent(event: JSONObject): Boolean {
        val connection = socket ?: return false
        return authenticated && events.trySend(connection to event).isSuccess
    }

    fun start() {
        if (running || stopped) return

        running = true
        scope.launch {
            connectionLoop()
        }
    }

    fun reconnect() {
        if (stopped) return
        notifyLog("Reconnect requested")
        closeConnection()
        wakeUp.trySend(Unit)
        start()
    }

    fun stop() {
        stopped = true
        running = false
        closeConnection()
        wakeUp.cancel()
        events.cancel()
        scope.cancel()
    }

    private suspend fun connectionLoop() {
        val retry = ReconnectPolicy()

        while (running && scope.isActive) {
            var rejected = false

            notifyStatus(ConnectionStatus.CONNECTING)
            notifyLog("Connecting to $host:$port...")

            try {
                val serverAddress = InetSocketAddress(host, port)
                val connectedSocket = (BridgeTls.context(pinnedKey).socketFactory.createSocket() as SSLSocket).apply {
                    socket = this
                    if (!running) {
                        close()
                        throw IOException("Connection stopped")
                    }
                    keepAlive = true
                    enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                    soTimeout = 10_000
                    connect(serverAddress, CONNECT_TIMEOUT_MS)
                    startHandshake()
                    soTimeout = 65_000
                }

                if (!running) {
                    connectedSocket.close()
                    break
                }
                writer = BufferedWriter(
                    OutputStreamWriter(
                        connectedSocket.getOutputStream(), Charsets.UTF_8
                    )
                )
                notifyStatus(ConnectionStatus.AWAITING_APPROVAL)
                notifyLog("Waiting for computer approval")
                val reader = BufferedReader(InputStreamReader(connectedSocket.getInputStream(), Charsets.UTF_8))
                val fingerprint = BridgeTls.fingerprint(connectedSocket.session.peerCertificates[0] as X509Certificate)
                performPairing(reader, fingerprint)
                connectedSocket.soTimeout = 0
                authenticated = true
                retry.reset()

                notifyStatus(ConnectionStatus.CONNECTED)
                notifyLog("Connected to Linux daemon")

                listenForMessages(reader)
            } catch (exception: Exception) {
                rejected = exception is PairingRejectedException || exception is SSLHandshakeException
                if (running) {
                    notifyLog(
                        "Connection error: " +
                                "${exception.javaClass.simpleName}: " +
                                "${exception.message}"
                    )
                }
            } finally {
                closeConnection()
                notifyStatus(ConnectionStatus.DISCONNECTED)
            }

            if (!running) break

            if (rejected) {
                notifyStatus(ConnectionStatus.RECONNECT_REQUIRED)
                notifyLog("Pairing rejected; manual reconnect required")
                wakeUp.receive()
                retry.reset()
            } else {
                val delayMs = retry.nextDelayMs()
                notifyLog("Reconnecting in ${delayMs / 1000} seconds")
                withTimeoutOrNull(delayMs) { wakeUp.receive() }
            }
        }
    }

    private fun listenForMessages(reader: BufferedReader) {
        while (running) {
            val line = reader.readLine() ?: break
            notifyLog("Request received")

            try {
                val response = messageRouter.handle(JSONObject(line))
                if (response != null) {
                    sendMessage(response)
                    notifyLog("Response sent")
                }
            } catch (_: JSONException) {
                notifyLog("Invalid JSON received")
            }
        }

        if (running) {
            notifyLog("Connection closed by daemon")
        }
    }

    private suspend fun performPairing(reader: BufferedReader, fingerprint: String) {
        val pairingRequest = JSONObject()
            .put("kind", "request")
            .put("id", "pairing")
            .put("method", "pairing.request")
            .put(
                "params",
                JSONObject()
                    .put("device_id", deviceId)
                    .put("model", deviceModel)
                    .apply {
                        if (pinnedKey != null && !pairingToken.isNullOrBlank()) {
                            put("pairing_token", pairingToken)
                        }
                    }
            )
        sendMessage(pairingRequest)

        val response = reader.readLine() ?: throw IOException("Computer closed pairing request")
        var message = JSONObject(response)
        var expectedId = "pairing"
        if (message.optString("kind") == "response" && message.optString("id") == "pairing" &&
            message.optJSONObject("result")?.optBoolean("confirmation_required") == true) {
            val decision = CompletableDeferred<Boolean>()
            confirmation = decision
            mainHandler.post { if (!stopped && !decision.isCompleted) onFingerprint(fingerprint) { decision.complete(it) } }
            val accepted = withTimeoutOrNull(60_000) { decision.await() } == true
            mainHandler.post { onFingerprint(null, null) }
            confirmation = null
            if (!accepted) throw PairingRejectedException("Fingerprint confirmation cancelled")
            sendMessage(JSONObject().put("kind", "request").put("id", "confirm")
                .put("method", "pairing.confirm").put("params", JSONObject().put("accepted", true)))
            message = JSONObject(reader.readLine() ?: throw IOException("Computer closed pairing"))
            expectedId = "confirm"
        } else if (pinnedKey == null) {
            throw PairingRejectedException("Computer did not request fingerprint verification")
        }
        val error = message.optJSONObject("error")
        if (error != null) {
            throw PairingRejectedException(error.optString("message", "Pairing rejected"))
        }
        if (
            message.optString("kind") != "response" ||
            message.optString("id") != expectedId ||
            message.optJSONObject("result")?.optBoolean("accepted") != true
        ) {
            throw IOException("Invalid pairing response")
        }
        if (expectedId == "confirm" && message.optJSONObject("result")?.optString("pairing_token").isNullOrBlank()) {
            throw IOException("Pairing response has no token")
        }
        if (stopped || !running) throw IOException("Connection stopped")
        message.optJSONObject("result")
            ?.optString("pairing_token")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                synchronized(writerLock) {
                    if (stopped || !running) throw IOException("Connection stopped")
                    onTrustReceived(fingerprint, it)
                    pinnedKey = fingerprint
                    pairingToken = it
                }
                if (!stopped) onPairingTokenReceived(it)
                notifyLog("Computer pairing saved")
            }
    }

    private fun sendMessage(message: JSONObject) {
        synchronized(writerLock) {
            val currentWriter = writer
                ?: throw IOException("Connection is not available")

            currentWriter.write(message.toString())
            currentWriter.newLine()
            currentWriter.flush()
        }
    }

    private fun closeConnection() {
        confirmation?.complete(false)
        mainHandler.post { onFingerprint(null, null) }
        authenticated = false
        // Closing the socket first interrupts any blocked writer before taking its lock.
        try { socket?.close() } catch (_: IOException) { }
        synchronized(writerLock) {
            try {
                writer?.close()
            } catch (_: Exception) {
            }
            writer = null

            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
        }
    }

    private fun notifyStatus(status: ConnectionStatus) {
        mainHandler.post {
            if (!stopped) onStatusChanged(status)
        }
    }

    private fun notifyLog(message: String) {
        mainHandler.post {
            if (!stopped) onLog(message)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
    }
}

private class PairingRejectedException(message: String) : IOException(message)
