package io.github.belzenn.androidlinuxbridge.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.belzenn.androidlinuxbridge.BridgeState
import io.github.belzenn.androidlinuxbridge.MainActivity
import io.github.belzenn.androidlinuxbridge.R
import io.github.belzenn.androidlinuxbridge.connection.ConnectionManager
import io.github.belzenn.androidlinuxbridge.features.battery.BatteryHandler
import io.github.belzenn.androidlinuxbridge.protocol.MessageRouter

class BridgeService : Service() {
    private lateinit var connectionManager: ConnectionManager

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startAsForegroundService()

        val batteryHandler = BatteryHandler(applicationContext) { level ->
            BridgeState.batteryLevel.intValue = level
        }

        val messageRouter = MessageRouter(
            handlers = mapOf(
                "battery.get" to batteryHandler::handle
            )
        )

        connectionManager = ConnectionManager(
            host = BridgeState.COMPUTER_IP,
            port = BridgeState.COMPUTER_PORT,
            messageRouter = messageRouter,
            onStatusChanged = { status ->
                BridgeState.connectionStatus.value = status
            },
            onLog = BridgeState::addLog
        )

        BridgeState.addLog("Bridge service started")
        BridgeState.addLog(
            "Server address: " +
                    "${BridgeState.COMPUTER_IP}:${BridgeState.COMPUTER_PORT}"
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_RECONNECT -> connectionManager.reconnect()
            else -> connectionManager.start()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        connectionManager.stop()
        BridgeState.connectionStatus.value =
            io.github.belzenn.androidlinuxbridge.connection.ConnectionStatus.DISCONNECTED
        BridgeState.addLog("Bridge service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForegroundService() {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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
        private const val ACTION_RECONNECT =
            "io.github.belzenn.androidlinuxbridge.action.RECONNECT"

        fun start(context: Context) {
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
    }
}
