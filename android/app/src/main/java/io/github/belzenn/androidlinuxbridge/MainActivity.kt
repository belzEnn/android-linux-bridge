package io.github.belzenn.androidlinuxbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.belzenn.androidlinuxbridge.connection.ConnectionManager
import io.github.belzenn.androidlinuxbridge.connection.ConnectionStatus
import io.github.belzenn.androidlinuxbridge.features.battery.BatteryHandler
import io.github.belzenn.androidlinuxbridge.protocol.MessageRouter
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val batteryLevel = mutableIntStateOf(-1)
    private val connectionStatus =
        mutableStateOf(ConnectionStatus.DISCONNECTED)
    private val logs = mutableStateListOf<String>()

    private val computerIp = "192.168.1.102"
    private val computerPort = 4242

    private lateinit var connectionManager: ConnectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val batteryHandler = BatteryHandler(
            applicationContext
        ) { level ->
            batteryLevel.intValue = level
        }

        val messageRouter = MessageRouter(
            handlers = mapOf(
                "battery.get" to batteryHandler::handle
            )
        )

        connectionManager = ConnectionManager(
            host = computerIp,
            port = computerPort,
            messageRouter = messageRouter,
            onStatusChanged = { status ->
                connectionStatus.value = status
            },
            onLog = ::addLog
        )

        addLog("Application started")
        addLog("Server address: $computerIp:$computerPort")

        setContent {
            MaterialTheme {
                BridgeScreen(
                    status = connectionStatus.value,
                    serverAddress = "$computerIp:$computerPort",
                    batteryLevel = batteryLevel.intValue,
                    logs = logs,
                    onClearLogs = logs::clear,
                    onReconnect = connectionManager::reconnect
                )
            }
        }

        connectionManager.start()
    }

    private fun addLog(message: String) {
        val time = LocalTime.now().format(
            DateTimeFormatter.ofPattern("HH:mm:ss")
        )
        logs.add("[$time] $message")

        if (logs.size > 200) {
            logs.removeAt(0)
        }
    }

    override fun onDestroy() {
        connectionManager.stop()
        super.onDestroy()
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
