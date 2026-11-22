package io.github.belzenn.androidlinuxbridge.service

import io.github.belzenn.androidlinuxbridge.features.notifications.NotificationForwarder
import io.github.belzenn.androidlinuxbridge.features.notifications.NotificationSettings
import io.github.belzenn.androidlinuxbridge.features.notifications.NotificationDismissHandler
import io.github.belzenn.androidlinuxbridge.features.clipboard.ClipboardForwarder
import io.github.belzenn.androidlinuxbridge.features.clipboard.ClipboardHistory
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import org.json.JSONObject

class BridgeService : Service() {
    private lateinit var findPhone: io.github.belzenn.androidlinuxbridge.features.system.FindPhoneHandler
    private var lastBattery: String? = null
    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            publishBattery()
        }
    }

    private fun publishBattery(force: Boolean = false) {
        if (BridgeState.connectionStatus.value != ConnectionStatus.CONNECTED) return
        val data = runCatching {
            BatteryHandler(applicationContext) { BridgeState.batteryLevel.intValue = it }
                .handle(org.json.JSONObject())
        }.getOrNull() ?: return
        if (force || lastBattery != data.toString()) {
            val queued = connectionManager?.sendEvent(org.json.JSONObject()
                .put("kind", "event").put("event", "battery.changed").put("data", data)) == true
            if (queued) lastBattery = data.toString()
        }
    }
    @Volatile private var connectionManager: ConnectionManager? = null
    private var wifiCandidate: ConnectionManager? = null
    private var activeBluetooth = false
    private var wifiRejected = false
    private var bluetoothRejected = false

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var discovery: ComputerDiscoveryManager
    private lateinit var connectivity: ConnectivityManager
    private var destroyed = false
    private var network: Network? = null
    private val tryWifi = Runnable {
        if (!destroyed && network != null && activeBluetooth && wifiCandidate == null && !wifiRejected) {
            when (BridgeState.connectionStatus.value) {
                ConnectionStatus.CONNECTED -> {
                    wifiCandidate = newConnection(null, candidate = true)?.also { it.start() }
                }
                ConnectionStatus.CONNECTING, ConnectionStatus.AWAITING_APPROVAL -> scheduleWifiReturn(WIFI_SETTLE_MS)
                else -> activate(null)
            }
        }
    }
    private val refreshDiscovery = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (network != null && (activeBluetooth || BridgeState.connectionStatus.value != ConnectionStatus.CONNECTED)) {
                discovery.stop()
                discovery.start(network)
            }
            handler.postDelayed(this, 60_000)
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(available: Network) { handler.post { updateNetwork() } }
        override fun onCapabilitiesChanged(available: Network, capabilities: NetworkCapabilities) {
            handler.post { updateNetwork() }
        }
        override fun onLost(lost: Network) { handler.post { updateNetwork(lost) } }
    }
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshBluetooth()
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED &&
                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.address ==
                    ConnectionSettings.bluetoothAddress(this@BridgeService) &&
                intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR) == BluetoothDevice.BOND_NONE &&
                BridgeState.bluetoothSetupInProgress.value) {
                finishBluetoothSetup("Bluetooth pairing was declined or failed. Check the Linux Bluetooth pairing prompt and try again.")
            }
            if (activeBluetooth && ConnectionSettings.bondedComputer(this@BridgeService) == null) {
                stopConnections()
                connectPreferred()
            } else if (connectionManager == null || (!activeBluetooth &&
                    BridgeState.connectionStatus.value == ConnectionStatus.DISCONNECTED && !wifiRejected)) {
                val fallback = ConnectionSettings.bondedComputer(this@BridgeService)
                if (fallback != null && connectionManager != null) activate(fallback)
                else connectPreferred()
            }
        }
    }
    private val setupTimeout = Runnable {
        finishBluetoothSetup("Bluetooth pairing timed out. Check the Linux Bluetooth pairing prompt and try again.")
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ContextCompat.registerReceiver(this, bluetoothReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)

        findPhone = io.github.belzenn.androidlinuxbridge.features.system.FindPhoneHandler(this) { ringing ->
            connectionManager?.sendEvent(JSONObject()
                .put("kind", "event").put("event", "find_phone.changed")
                .put("data", JSONObject().put("ringing", ringing)))
        }
        BridgeState.stopFindingPhone = { findPhone.stop() }
        NotificationForwarder.send = { event -> connectionManager?.sendEvent(event) }
        ClipboardForwarder.send = { event -> connectionManager?.sendEvent(event) == true }
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
                    if (activeBluetooth) {
                        stopWifiCandidate()
                        scheduleWifiReturn(WIFI_SETTLE_MS)
                    } else if (!wifiRejected) {
                        activate(null)
                    }
                }
            }
        }, BridgeState::addLog)
        connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build(), networkCallback)
        handler.postDelayed(refreshDiscovery, 60_000)
        BridgeState.addLog("Bridge service started")
    }

    private fun updateNetwork(lost: Network? = null) {
        if (destroyed) return
        val available = if (ConnectionSettings.hasLocalNetworkPermission(this)) {
            connectivity.allNetworks.filter { candidate ->
                candidate != lost && connectivity.getNetworkCapabilities(candidate)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                } == true
            }
        } else emptyList()
        val selected = network?.takeIf { it in available } ?: available.firstOrNull()
        if (selected == network) return
        network = selected
        discovery.stop()
        if (selected != null) discovery.start(selected)

        stopWifiCandidate()
        if (activeBluetooth) {
            scheduleWifiReturn(WIFI_SETTLE_MS)
        } else {
            stopConnections()
            connectPreferred()
        }
    }

    private fun connectPreferred() {
        if (destroyed || connectionManager != null || wifiRejected) return
        if (network != null) {
            activate(null)
            return
        }
        if (!bluetoothRejected) ConnectionSettings.bondedComputer(this)?.let(::activate)
    }

    private fun activate(device: BluetoothDevice?) {
        val next = newConnection(device) ?: return
        stopConnections()
        connectionManager = next
        activeBluetooth = device != null
        BridgeState.usingBluetooth.value = activeBluetooth
        next.start()
        if (activeBluetooth) scheduleWifiReturn(WIFI_RETRY_MS)
    }

    private fun stopWifiCandidate() {
        handler.removeCallbacks(tryWifi)
        wifiCandidate?.stop()
        wifiCandidate = null
    }

    private fun stopConnections() {
        stopWifiCandidate()
        connectionManager?.stop()
        connectionManager = null
        activeBluetooth = false
        BridgeState.usingBluetooth.value = false
        BridgeState.connectionStatus.value = ConnectionStatus.DISCONNECTED
        BridgeState.pairingFingerprint.value = null
        BridgeState.confirmFingerprint = null
    }

    private fun scheduleWifiReturn(delay: Long) {
        handler.removeCallbacks(tryWifi)
        if (network != null && activeBluetooth && !wifiRejected && wifiCandidate == null) {
            handler.postDelayed(tryWifi, delay)
        }
    }

    private fun newConnection(device: BluetoothDevice?, candidate: Boolean = false): ConnectionManager? {
        val serverAddress = ConnectionSettings.loadServer(this) ?: return null
        val computer = ConnectionSettings.preferredServiceName(this) ?: return null
        val bluetooth = device != null
        val selectedNetwork = if (bluetooth) null else network ?: return null
        val pin = ConnectionSettings.pinnedKey(this)
        val token = ConnectionSettings.pairingToken(this)
        if ((bluetooth || candidate) && (pin.isNullOrBlank() || token.isNullOrBlank())) return null
        BridgeState.updateServer(serverAddress.host, serverAddress.port, serverAddress.computerName, serverAddress.distribution)
        val battery = BatteryHandler(applicationContext) { level -> BridgeState.batteryLevel.intValue = level }
        val ping = PingHandler()
        val dismiss = NotificationDismissHandler()
        lateinit var manager: ConnectionManager
        val router = MessageRouter(
            handlers = mapOf(
                "battery.get" to battery::handle,
                "system.ping" to ping::handle,
                "notification.dismiss" to dismiss::handle,
                "system.find_phone" to findPhone::handle,
                "system.find_phone.stop" to {
                    findPhone.stop()
                    JSONObject().put("ringing", false)
                },
                "notifications.settings.get" to { NotificationSettings.get(applicationContext) },
                "notifications.settings.set" to { NotificationSettings.set(applicationContext, it) },
                "clipboard.history.replace" to { ClipboardHistory.replace(applicationContext, it) },
                "bluetooth.offer" to { params ->
                    handler.post {
                        if (!bluetooth && manager === connectionManager) recognizeBluetooth(params.optString("address"))
                    }
                    JSONObject().put("ok", true)
                },
                "bluetooth.pairing.ready" to { params ->
                    handler.post {
                        if (!bluetooth && manager === connectionManager && BridgeState.bluetoothSetupInProgress.value) {
                            val address = params.optString("address")
                            if (address == ConnectionSettings.bluetoothAddress(this)) {
                                if (params.optBoolean("ready")) bondBluetoothComputer(address)
                                else finishBluetoothSetup("Linux could not open Bluetooth pairing. Check the Bluetooth settings on the computer.")
                            }
                        }
                    }
                    JSONObject().put("ok", true)
                },
            )
        )
        manager = ConnectionManager(
            host = serverAddress.host,
            port = serverAddress.port,
            deviceId = ConnectionSettings.deviceId(this),
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            pairingToken = token,
            onPairingTokenReceived = {},
            pinnedKey = pin,
            onTrustReceived = { fingerprint, newToken ->
                ConnectionSettings.saveTrust(this, computer, fingerprint, newToken)
            },
            onFingerprint = { fingerprint, respond ->
                if (manager === connectionManager && !destroyed) {
                    BridgeState.pairingFingerprint.value = fingerprint
                    BridgeState.confirmFingerprint = respond
                }
            },
            messageRouter = router,
            onStatusChanged = { status -> connectionStatus(manager, bluetooth, status) },
            onLog = BridgeState::addLog,
            bluetooth = device,
            network = selectedNetwork,
            onBluetoothAddress = { address ->
                if (manager === connectionManager || manager === wifiCandidate) recognizeBluetooth(address)
            },
        )
        return manager
    }

    private fun connectionStatus(manager: ConnectionManager, bluetooth: Boolean, status: ConnectionStatus) {
        if (destroyed) return
        if (manager === wifiCandidate) {
            when (status) {
                ConnectionStatus.CONNECTED -> {
                    wifiCandidate = null
                    connectionManager?.stop()
                    connectionManager = manager
                    activeBluetooth = false
                    BridgeState.usingBluetooth.value = false
                    BridgeState.addLog("Returned to Wi-Fi")
                }
                ConnectionStatus.DISCONNECTED, ConnectionStatus.RECONNECT_REQUIRED -> {
                    if (status == ConnectionStatus.RECONNECT_REQUIRED) wifiRejected = true
                    stopWifiCandidate()
                    scheduleWifiReturn(WIFI_RETRY_MS)
                    return
                }
                else -> return
            }
        } else if (manager !== connectionManager) return

        BridgeState.connectionStatus.value = status
        when (status) {
            ConnectionStatus.CONNECTED -> {
                publishBattery(force = true)
                findPhone.publishState()
                refreshBluetooth()
            }
            ConnectionStatus.DISCONNECTED -> {
                if (!bluetooth && !bluetoothRejected) {
                    ConnectionSettings.bondedComputer(this)?.let {
                        BridgeState.addLog("Wi-Fi unavailable, trying Bluetooth")
                        activate(it)
                    }
                }
            }
            ConnectionStatus.RECONNECT_REQUIRED -> {
                if (bluetooth) bluetoothRejected = true else wifiRejected = true
            }
            else -> Unit
        }
    }

    private fun recognizeBluetooth(address: String) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return
        ConnectionSettings.saveBluetoothAddress(this, address)
        refreshBluetooth()
    }

    private fun refreshBluetooth() {
        BridgeState.bluetoothAvailable.value = ConnectionSettings.bluetoothAddress(this) != null
        BridgeState.bluetoothBackupReady.value = ConnectionSettings.bondedComputer(this) != null
        if (BridgeState.bluetoothBackupReady.value && BridgeState.bluetoothSetupInProgress.value) {
            finishBluetoothSetup(null)
        }
    }

    private fun requestBluetoothSetup() {
        refreshBluetooth()
        if (BridgeState.bluetoothBackupReady.value || BridgeState.bluetoothSetupInProgress.value) return
        val address = ConnectionSettings.bluetoothAddress(this) ?: return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (!ConnectionSettings.hasBluetoothPermission(this) || adapter?.isEnabled != true) {
            finishBluetoothSetup("Turn on Bluetooth to set up the backup connection.")
            return
        }
        val event = JSONObject().put("kind", "event").put("event", "bluetooth.pairing.open")
            .put("data", JSONObject().put("address", address))
        if (activeBluetooth || connectionManager?.sendEvent(event) != true) {
            finishBluetoothSetup("Connect to the computer over Wi-Fi to set up Bluetooth.")
            return
        }
        BridgeState.bluetoothSetupMessage.value = null
        BridgeState.bluetoothSetupInProgress.value = true
        handler.postDelayed(setupTimeout, 50_000)
    }

    private fun bondBluetoothComputer(address: String) {
        try {
            val device = getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)
            when (device?.bondState) {
                BluetoothDevice.BOND_BONDED -> refreshBluetooth()
                BluetoothDevice.BOND_BONDING -> Unit
                else -> if (device?.createBond() != true) {
                    finishBluetoothSetup("Could not start Bluetooth pairing. Check Bluetooth on both devices.")
                }
            }
        } catch (_: SecurityException) {
            finishBluetoothSetup("Allow Nearby devices access and try again.")
        }
    }

    private fun finishBluetoothSetup(message: String?) {
        handler.removeCallbacks(setupTimeout)
        if (BridgeState.bluetoothSetupInProgress.value && !activeBluetooth) {
            connectionManager?.sendEvent(JSONObject()
                .put("kind", "event").put("event", "bluetooth.pairing.done").put("data", JSONObject()))
        }
        BridgeState.bluetoothSetupInProgress.value = false
        BridgeState.bluetoothSetupMessage.value = message
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_FIND_PHONE) {
            findPhone.stop()
            return START_STICKY
        }
        if (ConnectionSettings.loadServer(this) == null ||
            (!ConnectionSettings.hasLocalNetworkPermission(this) && !ConnectionSettings.canUseBluetoothBackup(this))) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_FORGET_PAIRING, ACTION_APPLY_SETTINGS, ACTION_RECONNECT -> {
                wifiRejected = false
                bluetoothRejected = false
                finishBluetoothSetup(null)
                stopConnections()
                if (intent.action == ACTION_FORGET_PAIRING) ConnectionSettings.clearPairingToken(this)
            }
        }
        refreshBluetooth()
        updateNetwork()
        connectPreferred()
        if (intent?.action == ACTION_SETUP_BLUETOOTH) requestBluetoothSetup()
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        findPhone.close()
        BridgeState.stopFindingPhone = null
        unregisterReceiver(batteryReceiver)
        unregisterReceiver(bluetoothReceiver)
        discovery.stop()
        connectivity.unregisterNetworkCallback(networkCallback)
        handler.removeCallbacksAndMessages(null)
        NotificationForwarder.send = null
        ClipboardForwarder.send = null
        finishBluetoothSetup(null)
        stopConnections()
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
        const val ACTION_STOP_FIND_PHONE = "io.github.belzenn.androidlinuxbridge.action.STOP_FIND_PHONE"
        private const val CHANNEL_ID = "bridge_connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_FORGET_PAIRING =
            "io.github.belzenn.androidlinuxbridge.action.FORGET_PAIRING"
        private const val ACTION_RECONNECT =
            "io.github.belzenn.androidlinuxbridge.action.RECONNECT"
        private const val ACTION_APPLY_SETTINGS =
            "io.github.belzenn.androidlinuxbridge.action.APPLY_SETTINGS"
        private const val ACTION_SETUP_BLUETOOTH =
            "io.github.belzenn.androidlinuxbridge.action.SETUP_BLUETOOTH"
        private const val WIFI_SETTLE_MS = 5_000L
        private const val WIFI_RETRY_MS = 15_000L

        fun setupBluetooth(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BridgeService::class.java).setAction(ACTION_SETUP_BLUETOOTH)
            )
        }

        fun start(context: Context) {
            if (ConnectionSettings.loadServer(context) == null ||
                (!ConnectionSettings.hasLocalNetworkPermission(context) && !ConnectionSettings.canUseBluetoothBackup(context))) return
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
