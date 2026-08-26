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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import io.github.belzenn.androidlinuxbridge.connection.ConnectionStatus
import io.github.belzenn.androidlinuxbridge.service.BridgeService
import io.github.belzenn.androidlinuxbridge.settings.ConnectionSettings

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

        val savedServerAddress = ConnectionSettings.load(this)
        BridgeState.updateServer(
            savedServerAddress.host,
            savedServerAddress.port
        )

        setContent {
            var serverHost by remember {
                mutableStateOf(savedServerAddress.host)
            }
            var serverPort by remember {
                mutableStateOf(savedServerAddress.port.toString())
            }
            var settingsError by remember { mutableStateOf<String?>(null) }

            MaterialTheme {
                BridgeScreen(
                    status = BridgeState.connectionStatus.value,
                    serverAddress = "${BridgeState.serverHost.value}:" +
                        BridgeState.serverPort.intValue,
                    batteryLevel = BridgeState.batteryLevel.intValue,
                    logs = BridgeState.logs,
                    serverHost = serverHost,
                    serverPort = serverPort,
                    settingsError = settingsError,
                    onServerHostChanged = {
                        serverHost = it
                        settingsError = null
                    },
                    onServerPortChanged = {
                        serverPort = it.filter(Char::isDigit)
                        settingsError = null
                    },
                    onSaveSettings = {
                        val normalizedHost = serverHost.trim()
                        val normalizedPort = serverPort.toIntOrNull()

                        when {
                            normalizedHost.isEmpty() -> {
                                settingsError = "Host cannot be empty"
                            }

                            normalizedPort == null ||
                                normalizedPort !in 1..65535 -> {
                                settingsError = "Port must be between 1 and 65535"
                            }

                            else -> {
                                ConnectionSettings.save(
                                    this,
                                    normalizedHost,
                                    normalizedPort
                                )
                                BridgeState.updateServer(
                                    normalizedHost,
                                    normalizedPort
                                )
                                BridgeState.addLog(
                                    "Connection settings saved"
                                )
                                settingsError = null

                                if (hasLocalNetworkPermission()) {
                                    BridgeService.applySettings(this)
                                } else {
                                    requestRequiredPermissions()
                                }
                            }
                        }
                    },
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
    serverHost: String,
    serverPort: String,
    settingsError: String?,
    onServerHostChanged: (String) -> Unit,
    onServerPortChanged: (String) -> Unit,
    onSaveSettings: () -> Unit,
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

            ConnectionSettingsForm(
                serverHost = serverHost,
                serverPort = serverPort,
                error = settingsError,
                onServerHostChanged = onServerHostChanged,
                onServerPortChanged = onServerPortChanged,
                onSave = onSaveSettings
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
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ConnectionSettingsForm(
    serverHost: String,
    serverPort: String,
    error: String?,
    onServerHostChanged: (String) -> Unit,
    onServerPortChanged: (String) -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text = "Connection settings", fontSize = 22.sp)

        OutlinedTextField(
            value = serverHost,
            onValueChange = onServerHostChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Linux host or IP") },
            singleLine = true
        )

        OutlinedTextField(
            value = serverPort,
            onValueChange = onServerPortChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number
            ),
            isError = error != null,
            supportingText = if (error != null) {
                { Text(error) }
            } else {
                null
            }
        )

        Button(
            onClick = onSave,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Save and reconnect")
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
