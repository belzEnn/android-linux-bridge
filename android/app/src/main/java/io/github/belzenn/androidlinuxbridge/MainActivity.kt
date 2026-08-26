package io.github.belzenn.androidlinuxbridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
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
import io.github.belzenn.androidlinuxbridge.service.BridgeService

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasLocalNetworkPermission()) {
            BridgeService.start(this)
        } else {
            BridgeState.connectionStatus.value =
                ConnectionStatus.RECONNECT_REQUIRED
            BridgeState.addLog(
                "Local network permission is required"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                BridgeScreen(
                    status = BridgeState.connectionStatus.value,
                    serverAddress =
                        "${BridgeState.COMPUTER_IP}:" +
                                BridgeState.COMPUTER_PORT,
                    batteryLevel = BridgeState.batteryLevel.intValue,
                    logs = BridgeState.logs,
                    onClearLogs = BridgeState::clearLogs,
                    onReconnect = {
                        if (hasLocalNetworkPermission()) {
                            BridgeService.reconnect(this)
                        } else {
                            requestRequiredPermissions()
                        }
                    }
                )
            }
        }

        BridgeState.addLog("Application opened")
        requestRequiredPermissions()
    }

    private fun requestRequiredPermissions() {
        val missingPermissions = buildList {
            if (!hasLocalNetworkPermission()) {
                add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (missingPermissions.isEmpty()) {
            BridgeService.start(this)
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun hasLocalNetworkPermission(): Boolean {
        return Build.VERSION.SDK_INT < 37 ||
                checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
                PackageManager.PERMISSION_GRANTED
    }
}

@Composable
private fun BridgeScreen(
    status: ConnectionStatus,
    serverAddress: String,
    batteryLevel: Int,
    logs: List<String>,
    onClearLogs: () -> Unit,
    onReconnect: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            ConnectionHeader(
                status = status,
                serverAddress = serverAddress,
                batteryLevel = batteryLevel,
                onReconnect = onReconnect
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 20.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Logs", fontSize = 22.sp)

                Button(onClick = onClearLogs) {
                    Text("Clear")
                }
            }

            Spacer(modifier = Modifier.size(12.dp))

            LogsView(
                logs = logs,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ConnectionHeader(
    status: ConnectionStatus,
    serverAddress: String,
    batteryLevel: Int,
    onReconnect: () -> Unit
) {
    val statusColor = when (status) {
        ConnectionStatus.CONNECTING -> Color(0xFFFFA000)
        ConnectionStatus.CONNECTED -> Color(0xFF4CAF50)
        ConnectionStatus.DISCONNECTED -> Color(0xFFF44336)
        ConnectionStatus.RECONNECT_REQUIRED -> Color(0xFFF44336)
    }

    val statusText = when (status) {
        ConnectionStatus.CONNECTING -> "Connecting..."
        ConnectionStatus.CONNECTED -> "Connected"
        ConnectionStatus.DISCONNECTED -> "Disconnected"
        ConnectionStatus.RECONNECT_REQUIRED -> "Disconnected"
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        color = statusColor,
                        shape = CircleShape
                    )
            )

            Spacer(modifier = Modifier.size(10.dp))
            Text(text = statusText, fontSize = 26.sp)
        }

        Spacer(modifier = Modifier.size(8.dp))

        Text(
            text = "Server: $serverAddress",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = if (batteryLevel >= 0) {
                "Battery: $batteryLevel%"
            } else {
                "Battery: Unknown"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (status == ConnectionStatus.RECONNECT_REQUIRED) {
            Spacer(modifier = Modifier.size(12.dp))

            Button(onClick = onReconnect) {
                Text("Reconnect")
            }
        }
    }
}

@Composable
private fun LogsView(
    logs: List<String>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (logs.isEmpty()) {
            item {
                Text(
                    text = "No logs",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(logs) { log ->
            Text(
                text = log,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
