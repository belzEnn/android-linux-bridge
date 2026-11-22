package io.github.belzenn.androidlinuxbridge.connection

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLException

/** After handshake, one reader and one serialized writer can use the engine concurrently. */
internal class RfcommTls(
    private val engine: SSLEngine,
    private val input: InputStream,
    private val output: OutputStream,
) {
    private val incoming = ByteBuffer.allocate(engine.session.packetBufferSize).apply { flip() }
    private val outgoing = ByteBuffer.allocate(engine.session.packetBufferSize)
    private val plaintext = ByteBuffer.allocate(engine.session.applicationBufferSize)
    private val empty = ByteBuffer.allocate(0)

    fun handshake() {
        engine.beginHandshake()
        while (true) {
            when (engine.handshakeStatus) {
                HandshakeStatus.NEED_WRAP -> sendRecord(empty)
                HandshakeStatus.NEED_UNWRAP -> {
                    if (receiveRecord().status == Status.CLOSED) {
                        throw SSLException("Peer closed during TLS handshake")
                    }
                }
                HandshakeStatus.NEED_TASK -> {
                    while (true) (engine.delegatedTask ?: break).run()
                }
                HandshakeStatus.FINISHED, HandshakeStatus.NOT_HANDSHAKING -> {
                    plaintext.flip()
                    return
                }
                else -> throw SSLException("Unexpected TLS handshake state")
            }
        }
    }

    fun writeLine(message: String) {
        val source = ByteBuffer.wrap((message + "\n").toByteArray(Charsets.UTF_8))
        while (source.hasRemaining()) sendRecord(source)
    }

    fun readLine(): String? {
        val line = ByteArrayOutputStream()
        while (line.size() < MAX_MESSAGE_BYTES) {
            if (!plaintext.hasRemaining()) {
                plaintext.clear()
                val result = receiveRecord()
                plaintext.flip()
                if (result.status == Status.CLOSED) {
                    if (line.size() != 0) throw EOFException("TLS closed in the middle of a message")
                    return null
                }
                if (result.handshakeStatus != HandshakeStatus.NOT_HANDSHAKING) {
                    throw SSLException("Unexpected TLS renegotiation")
                }
                continue
            }
            val byte = plaintext.get().toInt() and 0xff
            if (byte == '\n'.code) return line.toString("UTF-8")
            line.write(byte)
        }
        throw SSLException("Bluetooth message exceeds size limit")
    }

    fun closeOutbound() {
        engine.closeOutbound()
        if (!engine.isOutboundDone) sendRecord(empty)
    }

    private companion object {
        // JSON escapes can expand clipboard text to six bytes per input byte.
        const val MAX_MESSAGE_BYTES = 6 * 1024 * 1024
    }

    private fun sendRecord(source: ByteBuffer) {
        outgoing.clear()
        val result = engine.wrap(source, outgoing)
        if (result.status != Status.OK && result.status != Status.CLOSED) {
            throw SSLException("TLS wrap failed: ${result.status}")
        }
        if (source.hasRemaining() && result.bytesConsumed() == 0) {
            throw SSLException("TLS write made no progress")
        }
        output.write(outgoing.array(), 0, outgoing.position())
        output.flush()
    }

    private fun receiveRecord(): SSLEngineResult {
        while (true) {
            val result = engine.unwrap(incoming, plaintext)
            when (result.status) {
                Status.OK, Status.CLOSED -> return result
                Status.BUFFER_UNDERFLOW -> {
                    incoming.compact()
                    if (!incoming.hasRemaining()) throw SSLException("TLS record exceeds buffer size")
                    val size = input.read(incoming.array(), incoming.position(), incoming.remaining())
                    if (size < 0) throw EOFException("RFCOMM closed before TLS shutdown")
                    incoming.position(incoming.position() + size)
                    incoming.flip()
                }
                else -> throw SSLException("TLS unwrap failed: ${result.status}")
            }
        }
    }
}
