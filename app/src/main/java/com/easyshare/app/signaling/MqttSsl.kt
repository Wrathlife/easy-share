package com.easyshare.app.signaling

import com.easyshare.app.debug.AgentDebugLog
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * MQTTS socket factory with system PKIX validation plus SPKI pinning for the public broker.
 * Pins cover leaf + intermediate so leaf rotation does not brick pairing until an app update.
 * Rotate pins when broker.emqx.io changes its intermediate/CA.
 */
object MqttSsl {
    /**
     * SHA-256 of SubjectPublicKeyInfo, Base64 — chain observed 2026-08-12.
     * Index 0 = leaf, 1 = intermediate, 2 = root (backup).
     */
    private val SPKI_PINS_BASE64 = setOf(
        "KTYj5LiWqYowwWQsMEgva7C/CJQj8tDQ0Dk9I6is1ZE=", // leaf
        "E3tYcwo9CiqATmKtpMLW5V+pzIq+ZoDmpXSiJlXGmTo=", // intermediate
        "i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY="  // root backup
    )

    fun pinnedSocketFactory(): SSLSocketFactory {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as java.security.KeyStore?)
        val system = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        val pinned = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                system.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                system.checkServerTrusted(chain, authType)
                if (chain.isEmpty()) throw CertificateException("Empty server chain")
                val pinBytes = SPKI_PINS_BASE64.mapNotNull { pin ->
                    runCatching { Base64.getDecoder().decode(pin) }.getOrNull()
                }
                val ok = chain.any { cert ->
                    val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
                    pinBytes.any { pin -> MessageDigest.isEqual(pin, digest) }
                }
                // #region agent log
                AgentDebugLog.log(
                    hypothesisId = "H4",
                    location = "MqttSsl.checkServerTrusted",
                    message = "TLS pin check",
                    data = mapOf("pinOk" to ok, "chainLen" to chain.size),
                    runId = "fix-residuals"
                )
                // #endregion
                if (!ok) {
                    throw CertificateException("MQTT broker SPKI pin mismatch")
                }
            }
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(pinned), null)
        return ctx.socketFactory
    }
}
