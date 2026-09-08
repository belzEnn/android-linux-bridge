package io.github.belzenn.androidlinuxbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.belzenn.androidlinuxbridge.connection.ConnectionManager
import io.github.belzenn.androidlinuxbridge.connection.ConnectionStatus
import io.github.belzenn.androidlinuxbridge.protocol.MessageRouter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TlsSecurityTest {
    @Test fun changedKeyBlocksBeforeTokenAndManualRetryKeepsPin() {
        val server = TlsTestServer.open()
        server.soTimeout = 5000
        val blocked = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val manager = ConnectionManager("127.0.0.1", server.localPort, "phone", "Phone", "secret-token", {},
            MessageRouter(emptyMap()), { if (it == ConnectionStatus.RECONNECT_REQUIRED) blocked.countDown() }, {},
            pinnedKey = "00".repeat(32), onFingerprint = { _, _ -> fail("Changed key must not prompt for trust") })
        val result = executor.submit<Boolean> {
            repeat(2) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    try {
                        assertEquals(-1, socket.getInputStream().read())
                    } catch (_: IOException) { /* TLS alert, no application data */ }
                }
            }
            true
        }
        try {
            manager.start()
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            manager.reconnect()
            assertTrue(result.get(10, TimeUnit.SECONDS))
        } finally {
            manager.stop()
            server.close()
            executor.shutdownNow()
        }
    }
}
