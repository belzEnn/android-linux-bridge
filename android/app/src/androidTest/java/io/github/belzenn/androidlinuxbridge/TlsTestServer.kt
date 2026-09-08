package io.github.belzenn.androidlinuxbridge

import androidx.test.platform.app.InstrumentationRegistry
import io.github.belzenn.androidlinuxbridge.connection.BridgeTls
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/** Public test-only identity, never packaged into the application APK. */
object TlsTestServer {
    private val store = KeyStore.getInstance("PKCS12").apply {
        InstrumentationRegistry.getInstrumentation().context.assets.open("test-identity.p12").use {
            load(it, "test-only".toCharArray())
        }
    }
    val pin = BridgeTls.fingerprint(store.getCertificate("test") as X509Certificate)
    fun open(port: Int = 0): SSLServerSocket {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "test-only".toCharArray())
        }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        return (context.serverSocketFactory.createServerSocket(port, 2, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).apply {
            enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        }
    }
}
