package io.github.belzenn.androidlinuxbridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.belzenn.androidlinuxbridge.connection.ConnectionStatus
import io.github.belzenn.androidlinuxbridge.discovery.ComputerDiscoveryManager
import io.github.belzenn.androidlinuxbridge.discovery.DiscoveredComputer
import io.github.belzenn.androidlinuxbridge.service.BridgeService
import io.github.belzenn.androidlinuxbridge.settings.ConnectionSettings

class MainActivity : ComponentActivity() {
    private lateinit var discovery: ComputerDiscoveryManager
    private var preferredServiceAttempted = false
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasLocalNetworkPermission()) {
            BridgeService.start(this)
            discovery.start()
        } else {
            BridgeState.connectionStatus.value = ConnectionStatus.RECONNECT_REQUIRED
            BridgeState.addLog("Local network permission is required")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        discovery = ComputerDiscoveryManager(this, { computers ->
            BridgeState.updateComputers(computers)
            val preferred = ConnectionSettings.preferredServiceName(this)
            val computer = computers.firstOrNull { it.serviceName == preferred }
            if (!preferredServiceAttempted && computer != null) {
                preferredServiceAttempted = true
                selectComputer(computer)
            }
        }, BridgeState::addLog)
        ConnectionSettings.loadServer(this)?.let { BridgeState.updateServer(it.host, it.port) }

        setContent {
            MaterialTheme {
                BridgeScreen(
                    status = BridgeState.connectionStatus.value,
                    serverAddress = "${BridgeState.serverHost.value}:${BridgeState.serverPort.intValue}",
                    batteryLevel = BridgeState.batteryLevel.intValue,
                    computers = BridgeState.computers,
                    logs = BridgeState.logs,
                    onComputerSelected = ::selectComputer,
                    onClearLogs = BridgeState::clearLogs,
                    onReconnect = {
                        if (hasLocalNetworkPermission()) BridgeService.reconnect(this)
                        else requestRequiredPermissions()
                    }
                )
            }
        }
        BridgeState.addLog("Application opened")
        requestRequiredPermissions()
    }

    override fun onDestroy() {
        discovery.stop()
        super.onDestroy()
    }

    private fun selectComputer(computer: DiscoveredComputer) {
        discovery.resolve(computer) { host, port ->
            ConnectionSettings.saveServer(this, host, port, computer.serviceName)
            BridgeState.updateServer(host, port)
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
        Build.VERSION.SDK_INT < 37 ||
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun BridgeScreen(
    status: ConnectionStatus,
    serverAddress: String,
    batteryLevel: Int,
    computers: List<DiscoveredComputer>,
    logs: List<String>,
    onComputerSelected: (DiscoveredComputer) -> Unit,
    onClearLogs: () -> Unit,
    onReconnect: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            ConnectionHeader(status, serverAddress, batteryLevel, onReconnect)
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            ComputersList(computers, onComputerSelected)
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Logs", fontSize = 22.sp)
                Button(onClick = onClearLogs) { Text("Clear") }
            }
            Spacer(Modifier.size(12.dp))
            LogsView(logs, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ComputersList(computers: List<DiscoveredComputer>, onSelected: (DiscoveredComputer) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Computers", fontSize = 22.sp)
        if (computers.isEmpty()) Text("No computers found. Check that both devices are on the same local network.")
        computers.forEach { computer ->
            Button(onClick = { onSelected(computer) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                    Text(computer.computerName)
                    Text(computer.distribution)
                }
            }
        }
    }
}

@Composable
private fun ConnectionHeader(status: ConnectionStatus, serverAddress: String, batteryLevel: Int, onReconnect: () -> Unit) {
    val connected = status == ConnectionStatus.CONNECTED || status == ConnectionStatus.AWAITING_APPROVAL
    val color = when { connected -> Color(0xFF4CAF50); status == ConnectionStatus.CONNECTING -> Color(0xFFFFA000); else -> Color(0xFFF44336) }
    val label = when (status) {
        ConnectionStatus.CONNECTING -> "Connecting..."
        ConnectionStatus.AWAITING_APPROVAL -> "Waiting for approval..."
        ConnectionStatus.CONNECTED -> "Connected"
        ConnectionStatus.DISCONNECTED, ConnectionStatus.RECONNECT_REQUIRED -> "Disconnected"
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(color, CircleShape))
            Spacer(Modifier.size(10.dp)); Text(label, fontSize = 26.sp)
        }
        Spacer(Modifier.size(8.dp))
        Text("Server: $serverAddress", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (batteryLevel >= 0) "Battery: $batteryLevel%" else "Battery: Unknown", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (status == ConnectionStatus.RECONNECT_REQUIRED) Button(onClick = onReconnect) { Text("Reconnect") }
    }
}

@Composable
private fun LogsView(logs: List<String>, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) state.animateScrollToItem(logs.lastIndex) }
    LazyColumn(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp), state = state,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (logs.isEmpty()) item { Text("No logs") }
        items(logs) { Text(it, fontSize = 13.sp) }
    }
}
