package io.github.belzenn.androidlinuxbridge.service

import io.github.belzenn.androidlinuxbridge.features.notifications.NotificationForwarder
import io.github.belzenn.androidlinuxbridge.features.notifications.NotificationSettings
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.net.ConnectivityManager
import android.net.Network
import io.github.belzenn.androidlinuxbridge.discovery.ComputerDiscoveryManager
import io.github.belzenn.androidlinuxbridge.connection.ConnectionStatus
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.belzenn.androidlinuxbridge.BridgeState
import io.github.belzenn.androidlinuxbridge.MainActivity
import io.github.belzenn.androidlinuxbridge.R
import io.github.belzenn.androidlinuxbridge.connection.ConnectionManager
import io.github.belzenn.androidlinuxbridge.features.battery.BatteryHandler
import io.github.belzenn.androidlinuxbridge.features.system.PingHandler
import io.github.belzenn.androidlinuxbridge.protocol.MessageRouter
import io.github.belzenn.androidlinuxbridge.settings.ConnectionSettings

class BridgeService : Service() {
    private var connectionManager: ConnectionManager? = null

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var discovery: ComputerDiscoveryManager
    private lateinit var connectivity: ConnectivityManager
    private var destroyed = false
    private var network: Network? = null
    private var pairingRejected = false
    private var connectionGeneration = 0
    private val refreshDiscovery = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (network != null && ConnectionSettings.hasLocalNetworkPermission(this@BridgeService) &&
                BridgeState.connectionStatus.value != ConnectionStatus.CONNECTED) {
                discovery.stop()
                discovery.start()
            }
            handler.postDelayed(this, 60_000L)
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(available: Network) { handler.post {
            if (!destroyed && network != available) {
                network = available
                discovery.stop()
                if (ConnectionSettings.hasLocalNetworkPermission(this@BridgeService)) discovery.start()
                if (!pairingRejected) applyConnectionSettings()
            }
        } }
        override fun onLost(lost: Network) { handler.post {
            if (!destroyed && network == lost) {
                network = null
                discovery.stop()
                connectionManager?.stop()
                connectionManager = null
                BridgeState.connectionStatus.value = ConnectionStatus.DISCONNECTED
            }
        } }
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startAsForegroundService()

        connectivity = getSystemService(ConnectivityManager::class.java)
        discovery = ComputerDiscoveryManager(this, { computers ->
            if (!destroyed && network != null) {
                val preferred = ConnectionSettings.preferredServiceName(this)
                val computer = computers.firstOrNull { it.serviceName == preferred }
                val saved = ConnectionSettings.loadServer(this)
                if (computer != null && saved != null &&
                    (computer.host != saved.host || computer.port != saved.port)) {
                    ConnectionSettings.saveServer(this, computer.host, computer.port,
                        computer.serviceName, computer.computerName, computer.distribution)
                    if (!pairingRejected) applyConnectionSettings()
                }
            }
        }, BridgeState::addLog)
        if (ConnectionSettings.hasLocalNetworkPermission(this)) {
            connectivity.registerDefaultNetworkCallback(networkCallback)
        }
        handler.postDelayed(refreshDiscovery, 60_000L)
        BridgeState.addLog("Bridge service started")
    }

    private fun createConnectionManager() {
        val serverAddress = ConnectionSettings.loadServer(this) ?: return
        BridgeState.updateServer(serverAddress.host, serverAddress.port, serverAddress.computerName, serverAddress.distribution)

        val batteryHandler = BatteryHandler(applicationContext) { level ->
            BridgeState.batteryLevel.intValue = level
        }
        val pingHandler = PingHandler()

        val messageRouter = MessageRouter(
            handlers = mapOf(
                "battery.get" to batteryHandler::handle,
                "system.ping" to pingHandler::handle,
                "notifications.settings.get" to { NotificationSettings.get(applicationContext) },
                "notifications.settings.set" to { NotificationSettings.set(applicationContext, it) }
            )
        )

        val computer = ConnectionSettings.preferredServiceName(this) ?: return
        val generation = connectionGeneration
        connectionManager = ConnectionManager(
            host = serverAddress.host,
            port = serverAddress.port,
            deviceId = ConnectionSettings.deviceId(this),
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            pairingToken = ConnectionSettings.pairingToken(this),
            onPairingTokenReceived = {},
            pinnedKey = ConnectionSettings.pinnedKey(this),
            onTrustReceived = { fingerprint, token ->
                ConnectionSettings.saveTrust(this, computer, fingerprint, token)
            },
            onFingerprint = { fingerprint, respond ->
                if (generation == connectionGeneration && !destroyed) {
                    BridgeState.pairingFingerprint.value = fingerprint
                    BridgeState.confirmFingerprint = respond
                }
            },
            messageRouter = messageRouter,
            onStatusChanged = { status ->
                if (status == ConnectionStatus.RECONNECT_REQUIRED) pairingRejected = true
                BridgeState.connectionStatus.value = status
            },
            onLog = BridgeState::addLog
        )

        NotificationForwarder.send = { event -> connectionManager?.sendEvent(event) }

        BridgeState.addLog(
            "Server address: ${serverAddress.host}:${serverAddress.port}"
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (!ConnectionSettings.hasLocalNetworkPermission(this) || ConnectionSettings.loadServer(this) == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_FORGET_PAIRING -> {
                connectionManager?.stop()
                connectionManager = null
                ConnectionSettings.clearPairingToken(this)
                pairingRejected = false
                applyConnectionSettings()
            }
            ACTION_RECONNECT -> {
                pairingRejected = false
                if (connectionManager == null) applyConnectionSettings()
                else connectionManager?.reconnect()
            }
            ACTION_APPLY_SETTINGS -> {
                pairingRejected = false
                applyConnectionSettings()
            }
            else -> if (connectionManager == null) applyConnectionSettings()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        BridgeState.pairingFingerprint.value = null
        BridgeState.confirmFingerprint = null
        discovery.stop()
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        handler.removeCallbacksAndMessages(null)
        NotificationForwarder.send = null
        connectionManager?.stop()
        BridgeState.connectionStatus.value =
            io.github.belzenn.androidlinuxbridge.connection.ConnectionStatus.DISCONNECTED
        BridgeState.addLog("Bridge service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun applyConnectionSettings() {
        connectionGeneration++
        BridgeState.pairingFingerprint.value = null
        BridgeState.confirmFingerprint = null
        BridgeState.addLog("Applying connection settings")
        connectionManager?.stop()
        connectionManager = null
        if (network == null || pairingRejected || !ConnectionSettings.hasLocalNetworkPermission(this)) return
        createConnectionManager()
        connectionManager?.start()
    }

    private fun startAsForegroundService() {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Android Linux Bridge")
            .setContentText("Bridge is running")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bridge connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the connection to the Linux daemon active"
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "bridge_connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_FORGET_PAIRING =
            "io.github.belzenn.androidlinuxbridge.action.FORGET_PAIRING"
        private const val ACTION_RECONNECT =
            "io.github.belzenn.androidlinuxbridge.action.RECONNECT"
        private const val ACTION_APPLY_SETTINGS =
            "io.github.belzenn.androidlinuxbridge.action.APPLY_SETTINGS"

        fun start(context: Context) {
            if (ConnectionSettings.loadServer(context) == null || !ConnectionSettings.hasLocalNetworkPermission(context)) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, BridgeService::class.java)
            )
        }

        fun reconnect(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BridgeService::class.java).apply {
                    action = ACTION_RECONNECT
                }
            )
        }

        fun forgetPairing(context: Context) {
            ContextCompat.startForegroundService(context,
                Intent(context, BridgeService::class.java).apply { action = ACTION_FORGET_PAIRING })
        }

        fun applySettings(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BridgeService::class.java).apply {
                    action = ACTION_APPLY_SETTINGS
                }
            )
        }
    }
}
