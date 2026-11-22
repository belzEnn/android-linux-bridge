package io.github.belzenn.androidlinuxbridge.connection

import android.bluetooth.BluetoothDevice
import android.net.Network
import android.os.Handler
import android.os.Looper
import io.github.belzenn.androidlinuxbridge.protocol.MessageRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CompletableDeferred

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
    private val onFingerprint: (String?, ((Boolean) -> Unit)?) -> Unit = { _, _ -> },
    private val bluetooth: BluetoothDevice? = null,
    private val network: Network? = null,
    private val onBluetoothAddress: (String) -> Unit = {},
) {
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val writerLock = Any()

    @Volatile
    private var running = false

    @Volatile
    private var connection: SessionConnection? = null
    @Volatile private var authenticated = false
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var stopped = false
    @Volatile private var confirmation: CompletableDeferred<Boolean>? = null
    private val events = Channel<Pair<SessionConnection, JSONObject>>(100)

    init {
        scope.launch {
            for ((connection, event) in events) {
                try {
                    synchronized(writerLock) {
                        if (authenticated && this@ConnectionManager.connection === connection) {
                            sendMessage(event)
                            val name = event.optString("event", "unknown")
                            val details = if (name == "battery.changed") {
                                event.optJSONObject("data")?.let {
                                    " (level=${it.optInt("level")}%, charging=${it.optBoolean("charging")})"
                                } ?: ""
                            } else ""
                            notifyLog("Event sent: $name$details")
                        }
                    }
                } catch (_: IOException) {
                    // Wake the receive loop and let its normal reconnect path recover.
                    closeSocket(connection)
                }
            }
        }
    }

    fun sendEvent(event: JSONObject): Boolean {
        val connection = connection ?: return false
        return !stopped && authenticated && events.trySend(connection to event).isSuccess
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
        val connection = connection
        confirmation?.complete(false)
        scope.launch {
            closeSocket(connection)
            wakeUp.trySend(Unit)
        }
        start()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        running = false
        authenticated = false
        confirmation?.complete(false)
        wakeUp.cancel()
        events.cancel()
        // A TLS socket can write close_notify, even when triggered by a network callback.
        scope.launch {
            try {
                closeSocket(connection)
            } finally {
                scope.cancel()
            }
        }
    }

    private suspend fun connectionLoop() {
        val retry = ReconnectPolicy()

        while (running && scope.isActive) {
            var rejected = false

            notifyStatus(ConnectionStatus.CONNECTING)
            val endpoint = bluetooth?.let { "Bluetooth" } ?: "$host:$port"
            notifyLog("Connecting to $endpoint...")

            try {
                val channel = if (bluetooth == null) {
                    LanSessionConnection(host, port, pinnedKey, network)
                } else {
                    val pin = pinnedKey?.takeIf { it.isNotBlank() }
                    if (pin == null || pairingToken.isNullOrBlank()) {
                        throw PairingRejectedException("Pair this computer through Wi-Fi first")
                    }
                    BluetoothSessionConnection(bluetooth, pin)
                }
                connection = channel
                if (!running) break
                val deadline = bluetooth?.let {
                    scope.launch {
                        delay(25_000)
                        closeSocket(channel)
                    }
                }
                try {
                    val fingerprint = channel.connect()
                    if (!running) break
                    notifyStatus(ConnectionStatus.AWAITING_APPROVAL)
                    val heartbeatTimeout = performPairing(channel, fingerprint)
                    channel.authenticated(heartbeatTimeout)
                } finally {
                    deadline?.cancel()
                }
                authenticated = true
                retry.reset()

                notifyStatus(ConnectionStatus.CONNECTED)
                notifyLog("Connected to Linux daemon via $endpoint")

                listenForMessages(channel)
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
            }

            if (!running) break

            if (rejected) {
                notifyStatus(ConnectionStatus.RECONNECT_REQUIRED)
                notifyLog("Pairing rejected; manual reconnect required")
                wakeUp.receive()
                retry.reset()
            } else {
                notifyStatus(ConnectionStatus.DISCONNECTED)
                val delayMs = retry.nextDelayMs()
                notifyLog("Reconnecting in ${delayMs / 1000} seconds")
                withTimeoutOrNull(delayMs) { wakeUp.receive() }
            }
        }
    }

    private fun listenForMessages(reader: SessionConnection) {
        while (running) {
            val line = reader.readLine() ?: break
            try {
                val message = JSONObject(line)
                val method = message.optString("method", "unknown")
                if (message.optString("kind") == "request") {
                    notifyLog("Request received: $method")
                }
                val response = messageRouter.handle(message)
                if (response != null) {
                    sendMessage(response)
                    val error = response.optJSONObject("error")
                    notifyLog(if (error == null) "Response sent: $method"
                        else "Error response sent: $method (${error.optString("code")})")
                }
            } catch (_: JSONException) {
                notifyLog("Invalid JSON received")
            }
        }

        if (running) {
            notifyLog("Connection closed by daemon")
        }
    }

    private suspend fun performPairing(reader: SessionConnection, fingerprint: String): Int {
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
            if (bluetooth != null) throw PairingRejectedException("Pair this computer through Wi-Fi first")
            val decision = CompletableDeferred<Boolean>()
            confirmation = decision
            mainHandler.post { if (!stopped && !decision.isCompleted) onFingerprint(fingerprint) { decision.complete(it) } }
            val accepted = try {
                withTimeoutOrNull(60_000) { decision.await() } == true
            } finally {
                confirmation = null
                mainHandler.post { onFingerprint(null, null) }
            }
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
        if (bluetooth != null) return 0
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
        message.optJSONObject("result")?.optString("bluetooth_address")
            ?.takeIf { it.isNotBlank() }
            ?.let { address -> mainHandler.post { if (!stopped) onBluetoothAddress(address) } }
        return if (message.optJSONObject("result")?.optBoolean("periodic_heartbeat") == true) 50_000 else 0
    }

    private fun sendMessage(message: JSONObject) {
        synchronized(writerLock) {
            val channel = connection ?: throw IOException("Connection is not available")
            channel.writeLine(message.toString())
        }
    }

    private fun closeConnection() {
        authenticated = false
        // Closing the socket first interrupts any blocked writer before taking its lock.
        closeSocket(connection)
        synchronized(writerLock) {
            connection = null
        }
    }

    private fun closeSocket(connection: SessionConnection?) {
        try {
            connection?.close()
        } catch (_: IOException) {
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
}

private class PairingRejectedException(message: String) : IOException(message)
