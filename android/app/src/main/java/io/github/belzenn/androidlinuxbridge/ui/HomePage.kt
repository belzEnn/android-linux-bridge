package io.github.belzenn.androidlinuxbridge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.belzenn.androidlinuxbridge.BridgeState
import io.github.belzenn.androidlinuxbridge.connection.ConnectionStatus
import io.github.belzenn.androidlinuxbridge.discovery.DiscoveredComputer
import io.github.belzenn.androidlinuxbridge.service.BridgeService

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomePage(
    onReconnect: () -> Unit,
    onComputerSelected: (DiscoveredComputer) -> Unit,
    permissionsGranted: Boolean,
    onSetup: () -> Unit,
    onSetupBluetooth: (() -> Unit)?,
) {
    val context = LocalContext.current
    var forget by rememberSaveable { mutableStateOf(false) }
    var showComputers by rememberSaveable { mutableStateOf(false) }
    val status = BridgeState.connectionStatus.value
    val canReconnect = BridgeState.serverHost.value.isNotBlank() &&
        status != ConnectionStatus.CONNECTED && status != ConnectionStatus.CONNECTING

    if (forget) AlertDialog(
        onDismissRequest = { forget = false },
        title = { Text("Forget computer key?") },
        text = { Text("A new pairing will require comparing fingerprints on both devices. Continue only if you intended to reset trust.") },
        confirmButton = {
            TextButton(onClick = {
                BridgeService.forgetPairing(context)
                forget = false
            }) { Text("Forget and pair again", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { forget = false }) { Text("Cancel") } },
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ComputerIcon()
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            BridgeState.computerName.value.ifBlank { "Select a computer" },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        val distribution = BridgeState.distribution.value
                        if (distribution.isNotBlank()) {
                            Text(
                                distribution,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                ConnectionIndicator(status, BridgeState.usingBluetooth.value)
                HorizontalDivider()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val computersAction = if (showComputers) "Hide computers" else "Find computers"
                    if (canReconnect) {
                        Button(onClick = onReconnect) { Text("Reconnect") }
                        OutlinedButton(onClick = { showComputers = !showComputers }) { Text(computersAction) }
                        TextButton(onClick = { forget = true }) { Text("Forget pairing") }
                    } else {
                        Button(onClick = { showComputers = !showComputers }) { Text(computersAction) }
                    }
                }
            }
        }
        if (showComputers) NearbyComputers(onComputerSelected)
        if (status == ConnectionStatus.CONNECTED && !BridgeState.usingBluetooth.value) {
            when {
                BridgeState.bluetoothBackupReady.value -> {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text("Bluetooth backup ready") },
                        )
                    }
                }
                onSetupBluetooth != null && BridgeState.bluetoothAvailable.value -> {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            TextButton(
                                onClick = onSetupBluetooth,
                                enabled = !BridgeState.bluetoothSetupInProgress.value,
                            ) {
                                Text(if (BridgeState.bluetoothSetupInProgress.value) {
                                    "Waiting for Bluetooth pairing…"
                                } else {
                                    "Enable Bluetooth backup"
                                })
                            }
                            BridgeState.bluetoothSetupMessage.value?.let { message ->
                                Text(
                                    message,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (!permissionsGranted) TextButton(onClick = onSetup) { Text("Finish setup") }
    }
}

@Composable
private fun ComputerIcon() {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Canvas(Modifier.padding(12.dp).size(24.dp)) {
            val stroke = Stroke(1.6.dp.toPx())
            drawRoundRect(
                color,
                topLeft = Offset(stroke.width, stroke.width),
                size = Size(size.width - 2 * stroke.width, size.height * .65f),
                cornerRadius = CornerRadius(2.dp.toPx()),
                style = stroke,
            )
            drawLine(color, Offset(center.x, size.height * .72f), Offset(center.x, size.height * .92f), stroke.width)
            drawLine(color, Offset(size.width * .28f, size.height * .92f), Offset(size.width * .72f, size.height * .92f), stroke.width)
        }
    }
}

@Composable
private fun ConnectionIndicator(status: ConnectionStatus, usingBluetooth: Boolean) {
    val text = when (status) {
        ConnectionStatus.CONNECTED -> if (usingBluetooth) "Connected via Bluetooth" else "Connected"
        ConnectionStatus.CONNECTING -> if (usingBluetooth) "Connecting via Bluetooth…" else "Connecting…"
        ConnectionStatus.AWAITING_APPROVAL -> "Waiting for approval…"
        else -> "Disconnected"
    }
    val colors = MaterialTheme.colorScheme
    val color = when (status) {
        ConnectionStatus.CONNECTED -> colors.tertiary
        ConnectionStatus.CONNECTING, ConnectionStatus.AWAITING_APPROVAL -> colors.primary
        else -> colors.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(Modifier.size(8.dp), shape = CircleShape, color = color) {}
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun NearbyComputers(onSelected: (DiscoveredComputer) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Nearby computers", style = MaterialTheme.typography.titleMedium)
        Text(
            "Connect both devices to the same local network.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedCard(Modifier.fillMaxWidth()) {
            if (BridgeState.computers.isEmpty()) {
                ListItem(
                    headlineContent = { Text("Looking for computers…") },
                    supportingContent = { Text("Open Android Linux Bridge on your Linux computer.") },
                )
            }
            BridgeState.computers.forEachIndexed { index, computer ->
                if (index > 0) HorizontalDivider()
                ListItem(
                    headlineContent = { Text(computer.computerName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (computer.distribution.isBlank()) null else {
                        { Text(computer.distribution) }
                    },
                    modifier = Modifier.clickable(onClickLabel = "Connect") { onSelected(computer) },
                    trailingContent = {
                        Text("Connect", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    },
                )
            }
        }
    }
}
