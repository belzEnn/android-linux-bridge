package io.github.belzenn.androidlinuxbridge.features.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import io.github.belzenn.androidlinuxbridge.connection.BluetoothSessionConnection
import io.github.belzenn.androidlinuxbridge.settings.ConnectionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

internal object AudioRoute {
    private val SERVICE_UUID = UUID.fromString("25b49f7e-268a-4fe0-bd73-7a942681f443")

    @SuppressLint("MissingPermission")
    suspend fun toggle(context: Context, computer: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        val pin = ConnectionSettings.pinnedKey(context)?.takeIf { it.isNotBlank() }
        val token = ConnectionSettings.pairingToken(context)?.takeIf { it.isNotBlank() }
        if (pin == null || token == null) throw IOException("Pair this computer in Android Linux Bridge")

        val channel = BluetoothSessionConnection(computer, pin, SERVICE_UUID)
        val exchange = async {
            channel.use {
                channel.connect()
                val identity = JSONObject()
                    .put("device_id", ConnectionSettings.deviceId(context))
                    .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("pairing_token", token)
                val pairing = request(channel, "pairing", "pairing.request", identity)
                if (pairing.opt("accepted") != true) throw IOException("Computer rejected audio control")

                val audio = request(channel, "audio", "audio.route.toggle", JSONObject())
                audio.opt("on_pc") as? Boolean ?: throw IOException("Computer returned an invalid audio route")
            }
        }
        try {
            withTimeout(20_000) { exchange.await() }
        } finally {
            // Closing the RFCOMM socket interrupts blocking I/O on timeout or cancellation.
            if (!exchange.isCompleted) runCatching { channel.close() }
        }
    }

    private fun request(
        channel: BluetoothSessionConnection,
        id: String,
        method: String,
        params: JSONObject,
    ): JSONObject {
        val message = JSONObject().put("kind", "request").put("id", id)
            .put("method", method).put("params", params)
        channel.writeLine(message.toString())
        val response = JSONObject(channel.readLine() ?: throw IOException("Computer closed audio control"))
        if (response.optString("kind") != "response" || response.optString("id") != id) {
            throw IOException("Computer returned an invalid audio response")
        }
        response.optJSONObject("error")?.let {
            throw IOException(it.optString("message", "Audio request failed"))
        }
        return response.optJSONObject("result") ?: throw IOException("Missing audio response")
    }
}
