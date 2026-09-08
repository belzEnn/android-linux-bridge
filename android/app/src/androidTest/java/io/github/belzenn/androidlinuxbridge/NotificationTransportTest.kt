package io.github.belzenn.androidlinuxbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.belzenn.androidlinuxbridge.connection.ConnectionManager
import io.github.belzenn.androidlinuxbridge.connection.ConnectionStatus
import io.github.belzenn.androidlinuxbridge.protocol.MessageRouter
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
class NotificationTransportTest {
    @Test fun eventsWaitForPairingAndDoNotCorruptResponses() {
        val server = TlsTestServer.open()
        val executor = Executors.newSingleThreadExecutor()
        val pairingReceived = CountDownLatch(1)
        val prePairingAttempted = CountDownLatch(1)
        val connected = CountDownLatch(1)
        val manager = ConnectionManager("127.0.0.1", server.localPort, "test", "Test phone", null, {},
            MessageRouter(mapOf("system.ping" to { JSONObject().put("pong", true) })),
            { if (it == ConnectionStatus.CONNECTED) connected.countDown() }, {}, pinnedKey = TlsTestServer.pin)
        val future = executor.submit<List<JSONObject>> {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                assertEquals("pairing.request", JSONObject(reader.readLine()).getString("method"))
                pairingReceived.countDown()
                assertTrue(prePairingAttempted.await(5, TimeUnit.SECONDS))
                socket.soTimeout = 250
                try {
                    reader.readLine()
                    fail("Event was sent before pairing")
                } catch (_: SocketTimeoutException) { }
                socket.soTimeout = 5000
                // Coalesce approval and a request to exercise the shared receive buffer.
                writer.write("{\"kind\":\"response\",\"id\":\"pairing\",\"result\":{\"accepted\":true}}\n")
                writer.write("{\"kind\":\"request\",\"id\":\"ping\",\"method\":\"system.ping\"}\n")
                writer.flush()
                listOf(JSONObject(reader.readLine()), JSONObject(reader.readLine()))
            }
        }
        try {
            manager.start()
            assertTrue(pairingReceived.await(5, TimeUnit.SECONDS))
            manager.sendEvent(JSONObject().put("kind", "event").put("event", "before-pairing"))
            prePairingAttempted.countDown()
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            manager.sendEvent(JSONObject().put("kind", "event").put("event", "notification.posted")
                .put("data", JSONObject().put("text", "Привет\n😀")))
            val messages = future.get(10, TimeUnit.SECONDS)
            val event = messages.single { it.getString("kind") == "event" }
            assertEquals("notification.posted", event.getString("event"))
            assertEquals("Привет\n😀", event.getJSONObject("data").getString("text"))
            assertTrue(messages.single { it.getString("kind") == "response" }.getJSONObject("result").getBoolean("pong"))
            manager.stop()
            manager.sendEvent(JSONObject().put("kind", "event").put("event", "after-stop"))
        } finally {
            manager.stop()
            server.close()
            executor.shutdownNow()
        }
    }
}
