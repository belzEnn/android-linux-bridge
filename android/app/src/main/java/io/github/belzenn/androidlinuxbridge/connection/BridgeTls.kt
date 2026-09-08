package io.github.belzenn.androidlinuxbridge.connection

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object BridgeTls {
    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
            .joinToString("") { "%02X".format(it) }

    fun context(pin: String?): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("Client certificates are not supported")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("Missing server certificate")
                chain[0].checkValidity()
                if (pin != null && fingerprint(chain[0]) != pin) {
                    throw CertificateException("Computer key changed. Forget the computer only after verifying the change.")
                }
                // With no pin this channel permits pairing only. Human verification is
                // required before persisting trust, sending tokens, or exposing features.
            }
        }), null)
    }
}
