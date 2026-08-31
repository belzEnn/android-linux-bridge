package io.github.belzenn.androidlinuxbridge

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import io.github.belzenn.androidlinuxbridge.connection.ConnectionStatus
import io.github.belzenn.androidlinuxbridge.discovery.DiscoveredComputer
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object BridgeState {
    val batteryLevel = mutableIntStateOf(-1)
    val connectionStatus = mutableStateOf(ConnectionStatus.DISCONNECTED)
    val serverHost = mutableStateOf("")
    val serverPort = mutableIntStateOf(0)
    val logs = mutableStateListOf<String>()
    val computers = mutableStateListOf<DiscoveredComputer>()

    fun updateServer(host: String, port: Int) {
        serverHost.value = host
        serverPort.intValue = port
    }

    fun addLog(message: String) {
        val time = LocalTime.now().format(TIME_FORMAT)
        logs.add("[$time] $message")

        if (logs.size > MAX_LOGS) {
            logs.removeAt(0)
        }
    }

    fun clearLogs() {
        logs.clear()
    }

    fun updateComputers(discovered: List<DiscoveredComputer>) {
        computers.clear()
        computers.addAll(discovered)
    }

    private const val MAX_LOGS = 200
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
}
