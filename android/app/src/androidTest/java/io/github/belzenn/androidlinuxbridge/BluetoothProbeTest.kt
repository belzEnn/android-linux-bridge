package io.github.belzenn.androidlinuxbridge

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.belzenn.androidlinuxbridge.connection.BridgeTls
import io.github.belzenn.androidlinuxbridge.connection.RfcommTls
import io.github.belzenn.androidlinuxbridge.features.system.PingHandler
import io.github.belzenn.androidlinuxbridge.settings.ConnectionSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BluetoothProbeTest {
    @Test
    fun answersPingOverRfcommWithoutWifi() {
        val address = InstrumentationRegistry.getArguments().getString("bridgeBluetoothAddress")
        assumeTrue("Pass the Linux Bluetooth address to enable the hardware test", address != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertEquals(
                "Grant Nearby devices permission to the debug app before running the probe",
                PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            )
        }
        val pin = checkNotNull(ConnectionSettings.pinnedKey(context)) { "Pair the debug app over Wi-Fi first" }
        val token = checkNotNull(ConnectionSettings.pairingToken(context)) { "Missing Bridge pairing token" }
        require(pin.isNotBlank() && token.isNotBlank()) { "Incomplete Bridge pairing" }
        val adapter = checkNotNull(context.getSystemService(BluetoothManager::class.java)?.adapter) {
            "This device has no Bluetooth adapter"
        }
        check(adapter.isEnabled) { "Enable Bluetooth on the phone" }
        val computer = checkNotNull(adapter.bondedDevices.firstOrNull { it.address.equals(address, ignoreCase = true) }) {
            "Pair the Linux computer in the system Bluetooth settings first"
        }

        val engine = BridgeTls.context(pin).createSSLEngine().apply {
            useClientMode = true
            // One TLS version is sufficient for the single ping and has no post-handshake tickets.
            enabledProtocols = arrayOf("TLSv1.2")
        }
        val timer = Executors.newSingleThreadScheduledExecutor()
        try {
            computer.createRfcommSocketToServiceRecord(SERVICE_UUID).use { socket ->
                val deadline = timer.schedule({ runCatching { socket.close() } }, 30, TimeUnit.SECONDS)
                try {
                    socket.connect()
                    val tls = RfcommTls(engine, socket.inputStream, socket.outputStream)
                    tls.handshake()
                    val credentials = JSONObject()
                        .put("device_id", ConnectionSettings.deviceId(context))
                        .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                        .put("pairing_token", token)
                    tls.writeLine(JSONObject().put("kind", "request").put("id", "pairing")
                        .put("method", "pairing.request").put("params", credentials).toString())
                    val pairing = JSONObject(checkNotNull(tls.readLine()) { "Daemon closed during authentication" })
                    assertEquals("response", pairing.optString("kind"))
                    assertEquals("pairing", pairing.optString("id"))
                    assertTrue("Daemon rejected the saved pairing", pairing.optJSONObject("result")?.optBoolean("accepted") == true)

                    val ping = JSONObject(checkNotNull(tls.readLine()) { "Daemon closed before ping" })
                    assertEquals("request", ping.optString("kind"))
                    assertEquals("system.ping", ping.optString("method"))
                    val result = PingHandler().handle(ping.getJSONObject("params"))
                    tls.writeLine(JSONObject().put("kind", "response").put("id", ping.getString("id"))
                        .put("result", result).toString())
                    assertEquals("Daemon should close the probe after ping", null, tls.readLine())
                    tls.closeOutbound()
                } finally {
                    deadline.cancel(false)
                }
            }
        } finally {
            timer.shutdownNow()
        }
    }

    private companion object {
        val SERVICE_UUID: UUID = UUID.fromString("193d39e6-c2a6-4dcb-b93c-2671860ee44d")
    }
}
