package io.github.belzenn.androidlinuxbridge

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.belzenn.androidlinuxbridge.connection.ConnectionManager
import io.github.belzenn.androidlinuxbridge.connection.ConnectionStatus
import io.github.belzenn.androidlinuxbridge.protocol.MessageRouter
import io.github.belzenn.androidlinuxbridge.service.BootReceiver
import io.github.belzenn.androidlinuxbridge.settings.ConnectionSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AutomaticConnectionTest {
    @Test fun bootRequiresSavedComputerAndPermission() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val preferences = base.getSharedPreferences("boot_receiver_test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        var starts = 0
        var granted = true
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
            override fun checkSelfPermission(permission: String): Int =
                if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            override fun startForegroundService(service: Intent): ComponentName? {
                starts++
                return service.component
            }
        }
        try {
            val receiver = BootReceiver()
            receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
            assertEquals(0, starts)
            ConnectionSettings.saveServer(context, "192.168.1.2", 1234, "pc", "PC", "Linux")
            receiver.onReceive(context, Intent("unrelated"))
            assertEquals(0, starts)
            receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
            receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
            assertEquals(2, starts)
            if (Build.VERSION.SDK_INT >= 37) {
                granted = false
                receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
                assertEquals(2, starts)
            }
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test fun reconnectUsesTokenReceivedOnFirstConnection() {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 10000
        val executor = Executors.newSingleThreadExecutor()
        val connected = CountDownLatch(1)
        val manager = ConnectionManager("127.0.0.1", server.localPort, "test", "Test", null, {},
            MessageRouter(emptyMap()),
            { if (it == ConnectionStatus.CONNECTED) connected.countDown() }, {})
        val result = executor.submit<String> {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                assertFalse(JSONObject(reader.readLine()).getJSONObject("params").has("pairing_token"))
                socket.getOutputStream().write(("{\"kind\":\"response\",\"id\":\"pairing\",\"result\":" +
                    "{\"accepted\":true,\"pairing_token\":\"saved-token\"}}\n").toByteArray())
                // Keep the first connection alive until manual reconnect closes it.
                reader.readLine()
            }
            server.accept().use { socket ->
                JSONObject(socket.getInputStream().bufferedReader().readLine())
                    .getJSONObject("params").getString("pairing_token")
            }
        }
        try {
            manager.start()
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            manager.reconnect()
            assertEquals("saved-token", result.get(10, TimeUnit.SECONDS))
        } finally {
            manager.stop()
            server.close()
            executor.shutdownNow()
        }
    }

    @Test fun connectionRecoversAfterFourRefusedAttempts() {
        val address = InetAddress.getByName("127.0.0.1")
        val port = ServerSocket(0, 1, address).use { it.localPort }
        val failures = CountDownLatch(4)
        val connected = CountDownLatch(1)
        val manager = ConnectionManager("127.0.0.1", port, "test", "Test", "token", {},
            MessageRouter(emptyMap()),
            { if (it == ConnectionStatus.CONNECTED) connected.countDown() },
            { if (it.startsWith("Connection error:")) failures.countDown() })
        try {
            manager.start()
            manager.start() // Starting twice must still leave only one connection loop.
            assertTrue("Retries stopped before four failures", failures.await(50, TimeUnit.SECONDS))
            ServerSocket(port, 1, address).use { server ->
                server.soTimeout = 50000
                server.accept().use { socket ->
                    socket.getInputStream().bufferedReader().readLine()
                    socket.getOutputStream().write(
                        "{\"kind\":\"response\",\"id\":\"pairing\",\"result\":{\"accepted\":true}}\n".toByteArray())
                    assertTrue(connected.await(5, TimeUnit.SECONDS))
                }
            }
        } finally {
            manager.stop()
        }
    }

    @Test fun pairingRejectionWaitsForManualRetry() {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val rejected = CountDownLatch(1)
        val manager = ConnectionManager("127.0.0.1", server.localPort, "test", "Test", null, {},
            MessageRouter(emptyMap()),
            { if (it == ConnectionStatus.RECONNECT_REQUIRED) rejected.countDown() }, {})
        try {
            server.soTimeout = 5000
            manager.start()
            server.accept().use { socket ->
                socket.getInputStream().bufferedReader().readLine()
                socket.getOutputStream().write("{\"error\":{\"message\":\"Denied\"}}\n".toByteArray())
            }
            assertTrue(rejected.await(5, TimeUnit.SECONDS))
            server.soTimeout = 6000
            try {
                server.accept().use { fail("Pairing rejection retried automatically") }
            } catch (_: SocketTimeoutException) { }
            manager.reconnect()
            server.soTimeout = 3000
            server.accept().close()
        } finally {
            manager.stop()
            server.close()
        }
    }
}
