package io.github.belzenn.androidlinuxbridge

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import io.github.belzenn.androidlinuxbridge.connection.ConnectionStatus
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object BridgeState {
    const val COMPUTER_IP = "192.168.1.102"
    const val COMPUTER_PORT = 4242

    val batteryLevel = mutableIntStateOf(-1)
    val connectionStatus = mutableStateOf(ConnectionStatus.DISCONNECTED)
    val logs = mutableStateListOf<String>()

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

    private const val MAX_LOGS = 200
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
}
