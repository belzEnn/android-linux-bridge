package io.github.belzenn.androidlinuxbridge

import io.github.belzenn.androidlinuxbridge.connection.BridgeTls
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BridgeTlsTest {
    private val store = KeyStore.getInstance("PKCS12").apply {
        File("src/androidTest/assets/test-identity.p12").inputStream().use { load(it, "test-only".toCharArray()) }
    }

    private fun exchange(pin: String?): Boolean {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "test-only".toCharArray())
        }
        val serverContext = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val server = serverContext.serverSocketFactory.createServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()) as SSLServerSocket
        val executor = Executors.newSingleThreadExecutor()
        val received = executor.submit<Int> {
            try {
                server.accept().use { it.soTimeout = 3000; it.getInputStream().read() }
            } catch (_: java.io.IOException) { -1 }
        }
        try {
            var accepted = false
            try {
                (BridgeTls.context(pin).socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket).use {
                    it.soTimeout = 3000
                    it.startHandshake()
                    it.getOutputStream().write(42)
                    accepted = true
                }
            } catch (_: javax.net.ssl.SSLException) { }
            assertEquals(if (accepted) 42 else -1, received.get(5, TimeUnit.SECONDS))
            return accepted
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test fun pinnedKeyAcceptsMatchingServer() {
        assertTrue(exchange(BridgeTls.fingerprint(store.getCertificate("test") as X509Certificate)))
    }

    @Test fun changedKeyRejectsBeforeApplicationData() {
        assertFalse(exchange("00".repeat(32)))
    }
}
