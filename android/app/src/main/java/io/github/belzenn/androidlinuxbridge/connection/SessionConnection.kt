package io.github.belzenn.androidlinuxbridge.connection

import android.bluetooth.BluetoothDevice
import android.net.Network
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLSocket

internal interface SessionConnection : Closeable {
    fun connect(): String
    fun readLine(): String?
    fun writeLine(line: String)
    fun authenticated(heartbeatTimeout: Int) = Unit
}

internal class LanSessionConnection(
    private val host: String,
    private val port: Int,
    private val pin: String?,
    private val network: Network?,
) : SessionConnection {
    private val socket = Socket()
    @Volatile private var tls: SSLSocket? = null
    private lateinit var reader: BufferedReader
    private lateinit var writer: BufferedWriter

    override fun connect(): String {
        socket.keepAlive = true
        network?.bindSocket(socket)
        socket.soTimeout = 10_000
        socket.connect(InetSocketAddress(host, port), 3_000)
        val secured = BridgeTls.context(pin).socketFactory.createSocket(socket, host, port, true) as SSLSocket
        tls = secured
        secured.enabledProtocols = secured.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        secured.soTimeout = 10_000
        secured.startHandshake()
        secured.soTimeout = 65_000
        reader = secured.inputStream.bufferedReader(Charsets.UTF_8)
        writer = secured.outputStream.bufferedWriter(Charsets.UTF_8)
        return BridgeTls.fingerprint(secured.session.peerCertificates[0] as X509Certificate)
    }

    override fun readLine(): String? = reader.readLine()

    override fun writeLine(line: String) {
        writer.write(line)
        writer.newLine()
        writer.flush()
    }

    override fun authenticated(heartbeatTimeout: Int) {
        tls?.soTimeout = heartbeatTimeout
    }

    override fun close() {
        try {
            socket.close()
        } finally {
            tls?.close()
        }
    }
}

internal class BluetoothSessionConnection(device: BluetoothDevice, pin: String) : SessionConnection {
    private val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
    private val engine = BridgeTls.context(pin).createSSLEngine().apply {
        useClientMode = true
        enabledProtocols = arrayOf("TLSv1.2")
    }
    private lateinit var tls: RfcommTls

    override fun connect(): String {
        socket.connect()
        tls = RfcommTls(engine, socket.inputStream, socket.outputStream)
        tls.handshake()
        return BridgeTls.fingerprint(engine.session.peerCertificates[0] as X509Certificate)
    }

    override fun readLine(): String? = tls.readLine()

    override fun writeLine(line: String) = tls.writeLine(line)

    override fun close() = socket.close()

    private companion object {
        val SERVICE_UUID: UUID = UUID.fromString("b0159377-444a-4988-a430-4aec727f715c")
    }
}
