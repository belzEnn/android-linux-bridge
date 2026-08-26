package io.github.belzenn.androidlinuxbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    private val batteryLevel = mutableIntStateOf(-1)
    private val connectionStatus =
        mutableStateOf(ConnectionStatus.CONNECTING)

    private val logs = mutableStateListOf<String>()

    /*
     * Android Emulator:
     * private val computerIp = "10.0.2.2"
     *
     * Physical phone:
     * use the computer's local Wi-Fi address,
     * for example "192.168.1.100".
     */
    private val computerIp = "192.168.1.102"
    private val computerPort = 4242

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) {
                return
            }

            val level = intent.getIntExtra(
                BatteryManager.EXTRA_LEVEL,
                -1
            )

            val scale = intent.getIntExtra(
                BatteryManager.EXTRA_SCALE,
                100
            )

            if (level < 0 || scale <= 0) {
                addLog("Failed to read battery level")
                return
            }

            val percentage = level * 100 / scale

            if (percentage != batteryLevel.intValue) {
                batteryLevel.intValue = percentage
                addLog("Battery level: $percentage%")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        addLog("Application started")
        addLog("Server address: $computerIp:$computerPort")

        registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        startConnectionLoop()

        setContent {
            MaterialTheme {
                BridgeScreen(
                    status = connectionStatus.value,
                    serverAddress = "$computerIp:$computerPort",
                    batteryLevel = batteryLevel.intValue,
                    logs = logs,
                    onClearLogs = {
                        logs.clear()
                    }
                )
            }
        }
    }

    private fun startConnectionLoop() {
        lifecycleScope.launch {
            while (isActive) {
                if (connectionStatus.value != ConnectionStatus.CONNECTED) {
                    connectionStatus.value =
                        ConnectionStatus.CONNECTING
                }

                addLog("Connecting to $computerIp:$computerPort...")

                val result = withContext(Dispatchers.IO) {
                    sendBatteryLevel(batteryLevel.intValue)
                }

                if (result.isSuccess) {
                    connectionStatus.value =
                        ConnectionStatus.CONNECTED

                    addLog(
                        "Connected, battery sent: " +
                                "${batteryLevel.intValue}%"
                    )
                } else {
                    connectionStatus.value =
                        ConnectionStatus.DISCONNECTED

                    val exception = result.exceptionOrNull()
                    val errorName =
                        exception?.javaClass?.simpleName
                            ?: "UnknownError"
                    val errorMessage =
                        exception?.message
                            ?: "No error message"

                    addLog(
                        "Connection failed: " +
                                "$errorName: $errorMessage"
                    )
                }

                delay(5_000)
            }
        }
    }

    private fun sendBatteryLevel(
        level: Int
    ): Result<Unit> {
        if (level < 0) {
            return Result.failure(
                IllegalStateException(
                    "Battery level is not available"
                )
            )
        }

        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(
                        computerIp,
                        computerPort
                    ),
                    3_000
                )

                val message = JSONObject()
                    .put("version", 1)
                    .put("type", "battery")
                    .put("level", level)
                    .toString()

                BufferedWriter(
                    OutputStreamWriter(
                        socket.getOutputStream()
                    )
                ).use { writer ->
                    writer.write(message)
                    writer.newLine()
                    writer.flush()
                }
            }
        }
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
        unregisterReceiver(batteryReceiver)
        super.onDestroy()
    }
}

private enum class ConnectionStatus {
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

@Composable
private fun BridgeScreen(
    status: ConnectionStatus,
    serverAddress: String,
    batteryLevel: Int,
    logs: List<String>,
    onClearLogs: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            ConnectionHeader(
                status = status,
                serverAddress = serverAddress,
                batteryLevel = batteryLevel
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 20.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement =
                    Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Logs",
                    fontSize = 22.sp
                )

                Button(
                    onClick = onClearLogs
                ) {
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
private fun ConnectionHeader(
    status: ConnectionStatus,
    serverAddress: String,
    batteryLevel: Int
) {
    val statusColor = when (status) {
        ConnectionStatus.CONNECTING ->
            Color(0xFFFFA000)

        ConnectionStatus.CONNECTED ->
            Color(0xFF4CAF50)

        ConnectionStatus.DISCONNECTED ->
            Color(0xFFF44336)
    }

    val statusText = when (status) {
        ConnectionStatus.CONNECTING ->
            "Connecting..."

        ConnectionStatus.CONNECTED ->
            "Connected"

        ConnectionStatus.DISCONNECTED ->
            "Disconnected"
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

            Text(
                text = statusText,
                fontSize = 26.sp
            )
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