package io.github.belzenn.androidlinuxbridge.connection

import android.os.Handler
import android.os.Looper
import io.github.belzenn.androidlinuxbridge.protocol.MessageRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
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
    private val pairingToken: String?,
    private val onPairingTokenReceived: (String) -> Unit,
    private val messageRouter: MessageRouter,
    private val onStatusChanged: (ConnectionStatus) -> Unit,
    private val onLog: (String) -> Unit
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

    fun start() {
        if (running) return

        running = true
        scope.launch {
            connectionLoop()
        }
    }

    fun reconnect() {
        if (running) {
            notifyLog("Connection attempt is already running")
            return
        }

        notifyLog("Manual reconnect requested")
        start()
    }

    fun stop() {
        running = false
        closeConnection()
        scope.cancel()
    }

    private suspend fun connectionLoop() {
        var failedAttempts = 0

        while (running && scope.isActive) {
            var connectionEstablished = false

            notifyStatus(ConnectionStatus.CONNECTING)
            notifyLog(
                "Connecting to $host:$port " +
                        "(attempt ${failedAttempts + 1} of $MAX_ATTEMPTS)..."
            )

            try {
                val serverAddress = InetSocketAddress(host, port)
                val connectedSocket = Socket().apply {
                    keepAlive = true
                    connect(serverAddress, CONNECT_TIMEOUT_MS)
                }

                connectionEstablished = true
                socket = connectedSocket
                writer = BufferedWriter(
                    OutputStreamWriter(
                        connectedSocket.getOutputStream()
                    )
                )
                notifyStatus(ConnectionStatus.AWAITING_APPROVAL)
                notifyLog("Waiting for computer approval")
                performPairing(connectedSocket)
                failedAttempts = 0

                notifyStatus(ConnectionStatus.CONNECTED)
                notifyLog("Connected to Linux daemon")

                listenForMessages(connectedSocket)
            } catch (exception: Exception) {
                if (running) {
                    notifyLog(
                        "Connection error: " +
                                "${exception.javaClass.simpleName}: " +
                                "${exception.message}"
                    )
                }
            } finally {
                if (!connectionEstablished) {
                    failedAttempts++
                }

                closeConnection()
                notifyStatus(ConnectionStatus.DISCONNECTED)
            }

            if (!running) break

            if (failedAttempts >= MAX_ATTEMPTS) {
                running = false
                notifyStatus(ConnectionStatus.RECONNECT_REQUIRED)
                notifyLog(
                    "Automatic reconnect stopped after " +
                            "$MAX_ATTEMPTS failed attempts"
                )
                break
            }

            notifyLog("Reconnecting in 5 seconds")
            delay(RECONNECT_DELAY_MS)
        }
    }

    private fun listenForMessages(connectedSocket: Socket) {
        val reader = BufferedReader(
            InputStreamReader(
                connectedSocket.getInputStream()
            )
        )

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

    private fun performPairing(connectedSocket: Socket) {
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
                        if (!pairingToken.isNullOrBlank()) {
                            put("pairing_token", pairingToken)
                        }
                    }
            )
        sendMessage(pairingRequest)

        val response = BufferedReader(
            InputStreamReader(connectedSocket.getInputStream())
        ).readLine() ?: throw IOException("Computer closed pairing request")
        val message = JSONObject(response)
        val error = message.optJSONObject("error")
        if (error != null) {
            throw IOException(error.optString("message", "Pairing rejected"))
        }
        if (
            message.optString("kind") != "response" ||
            message.optString("id") != "pairing" ||
            message.optJSONObject("result")?.optBoolean("accepted") != true
        ) {
            throw IOException("Invalid pairing response")
        }
        message.optJSONObject("result")
            ?.optString("pairing_token")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                onPairingTokenReceived(it)
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
            onStatusChanged(status)
        }
    }

    private fun notifyLog(message: String) {
        mainHandler.post {
            onLog(message)
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val CONNECT_TIMEOUT_MS = 3_000
        const val RECONNECT_DELAY_MS = 5_000L
    }
}
