package io.github.belzenn.androidlinuxbridge

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationManagerCompat
import io.github.belzenn.androidlinuxbridge.connection.ConnectionStatus
import io.github.belzenn.androidlinuxbridge.discovery.ComputerDiscoveryManager
import io.github.belzenn.androidlinuxbridge.discovery.DiscoveredComputer
import io.github.belzenn.androidlinuxbridge.features.notifications.NotificationSettings
import io.github.belzenn.androidlinuxbridge.features.clipboard.ClipboardTileService
import io.github.belzenn.androidlinuxbridge.service.BridgeService
import io.github.belzenn.androidlinuxbridge.settings.ConnectionSettings
import io.github.belzenn.androidlinuxbridge.ui.BridgeApp
import io.github.belzenn.androidlinuxbridge.ui.SetupStatus

class MainActivity : ComponentActivity() {
    private val setupStatus = mutableStateOf(SetupStatus())

    override fun onResume() {
        super.onResume()
        refreshSetupStatus()
        BridgeService.start(this)
        if (hasLocalNetworkPermission()) discovery.start()
    }

    private lateinit var discovery: ComputerDiscoveryManager
    private val bluetoothPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) BridgeService.setupBluetooth(this)
        else BridgeState.bluetoothSetupMessage.value = "Nearby devices access is required for Bluetooth backup."
    }
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshSetupStatus()
        BridgeService.start(this)
        if (hasLocalNetworkPermission()) {
            discovery.start()
        } else if (!ConnectionSettings.canUseBluetoothBackup(this)) {
            BridgeState.connectionStatus.value = ConnectionStatus.RECONNECT_REQUIRED
            BridgeState.addLog("Local network permission is required")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        discovery = ComputerDiscoveryManager(this, { computers ->
            BridgeState.updateComputers(computers)
        }, BridgeState::addLog)
        ConnectionSettings.loadServer(this)?.let { BridgeState.updateServer(it.host, it.port, it.computerName, it.distribution) }

        refreshSetupStatus()
        setContent {
            BridgeApp(
                setup = setupStatus.value,
                onNotificationAccess = { openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                onPermissions = ::requestRequiredPermissions,
                onAppSettings = { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                onNotificationSettings = {
                    openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                },
                onBatterySettings = { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                onComputerSelected = ::selectComputer,
                onReconnect = {
                    if (BridgeState.bluetoothBackupReady.value || hasLocalNetworkPermission()) BridgeService.reconnect(this)
                    else requestRequiredPermissions()
                },
                onAddClipboardTile = ::addClipboardTile,
                onSetupBluetooth = ::setupBluetoothBackup
            )
        }
        BridgeState.addLog("Application opened")
    }

    private fun setupBluetoothBackup() {
        if (ConnectionSettings.hasBluetoothPermission(this)) {
            BridgeService.setupBluetooth(this)
        } else {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun refreshSetupStatus() {
        setupStatus.value = SetupStatus(
            notificationAccess = NotificationSettings.accessGranted(this),
            notifications = NotificationManagerCompat.from(this).areNotificationsEnabled(),
            localNetwork = hasLocalNetworkPermission(),
            background = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        )
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    override fun onDestroy() {
        discovery.stop()
        super.onDestroy()
    }

    private fun selectComputer(computer: DiscoveredComputer) {
        if (!hasLocalNetworkPermission()) {
            requestRequiredPermissions()
            return
        }
        discovery.resolve(computer) { host, port ->
            ConnectionSettings.saveServer(this, host, port, computer.serviceName, computer.computerName, computer.distribution)
            BridgeState.updateServer(host, port, computer.computerName, computer.distribution)
            BridgeState.addLog("Selected ${computer.computerName}")
            BridgeService.applySettings(this)
        }
    }

    private fun requestRequiredPermissions() {
        val missingPermissions = buildList {
            if (!hasLocalNetworkPermission()) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missingPermissions.isEmpty()) {
            BridgeService.start(this)
            discovery.start()
        } else permissionLauncher.launch(missingPermissions.toTypedArray())
    }

    private fun hasLocalNetworkPermission(): Boolean =
        ConnectionSettings.hasLocalNetworkPermission(this)

    private fun addClipboardTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, ClipboardTileService::class.java),
                getString(R.string.clipboard_tile_label),
                android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher),
                mainExecutor,
            ) { }
        } else {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
