package com.netshare.app.signaling

import android.net.http.X509TrustManagerExtensions
import androidx.annotation.Keep
import com.netshare.app.debug.AgentDebugLog
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * MQTTS socket factory with system PKIX validation plus SPKI pinning for the public broker.
 * Pins cover leaf + intermediate so leaf rotation does not brick pairing until an app update.
 *
 * Implements hostname-aware checkServerTrusted(chain, authType, host) so Android Network
 * Security Config domain pins (broker.emqx.io) accept this TrustManager.
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
        val ctx = runCatching { SSLContext.getInstance("TLSv1.2") }
            .getOrElse { SSLContext.getInstance("TLS") }
        ctx.init(null, arrayOf(HostnameAwarePinningTrustManager(system)), null)
        // Paho calls createSocket() with no args then connect() — must delegate that overload.
        return PahoFriendlyFactory(ctx.socketFactory)
    }

    /**
     * Android NSC domain configs require hostname-aware checkServerTrusted.
     * Conscrypt finds the 3-arg method by reflection on the TrustManager instance.
     */
    @Keep
    private class HostnameAwarePinningTrustManager(
        private val system: X509TrustManager
    ) : X509TrustManager {
        private val extensions = X509TrustManagerExtensions(system)

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            system.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            // Fallback for callers without a hostname — still pin after system PKIX.
            system.checkServerTrusted(chain, authType)
            verifyPin(chain, host = null)
        }

        /**
         * Used by Android / Conscrypt when Network Security Config has domain-specific rules.
         * Must remain a public method named checkServerTrusted with this signature
         * (looked up by reflection — keep via @Keep / ProGuard).
         */
        @Keep
        @Suppress("unused")
        fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            host: String
        ): List<X509Certificate> {
            val trusted = extensions.checkServerTrusted(chain, authType, host)
            verifyPin(trusted.toTypedArray(), host)
            return trusted
        }

        private fun verifyPin(chain: Array<X509Certificate>, host: String?) {
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
                data = mapOf(
                    "pinOk" to ok,
                    "chainLen" to chain.size,
                    "host" to (host ?: "none")
                ),
                runId = "fix-residuals"
            )
            // #endregion
            if (!ok) {
                throw CertificateException("MQTT broker SPKI pin mismatch")
            }
        }
    }

    /**
     * Delegates every createSocket overload (including the no-arg one Paho uses).
     * Default SSLSocketFactory.createSocket() throws "Unconnected sockets not implemented".
     */
    private class PahoFriendlyFactory(
        private val delegate: SSLSocketFactory
    ) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(): Socket =
            prepare(delegate.createSocket() as SSLSocket, host = null)

        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            prepare(delegate.createSocket(s, host, port, autoClose) as SSLSocket, host)

        override fun createSocket(host: String, port: Int): Socket =
            prepare(delegate.createSocket(host, port) as SSLSocket, host)

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            prepare(delegate.createSocket(host, port, localHost, localPort) as SSLSocket, host)

        override fun createSocket(address: InetAddress, port: Int): Socket =
            prepare(
                delegate.createSocket(address, port) as SSLSocket,
                address.hostName?.takeIf { it.isNotBlank() } ?: address.hostAddress
            )

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int
        ): Socket = prepare(
            delegate.createSocket(address, port, localAddress, localPort) as SSLSocket,
            address.hostName?.takeIf { it.isNotBlank() } ?: address.hostAddress
        )

        private fun prepare(socket: SSLSocket, host: String?): SSLSocket {
            runCatching {
                val params = socket.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                if (!host.isNullOrBlank() && !looksLikeIp(host)) {
                    params.serverNames = listOf(SNIHostName(host))
                }
                socket.sslParameters = params
            }
            if (!host.isNullOrBlank()) {
                runCatching {
                    val m = socket.javaClass.methods.firstOrNull {
                        it.name == "setHostname" && it.parameterTypes.size == 1
                    }
                    m?.invoke(socket, host)
                }
            }
            return socket
        }

        private fun looksLikeIp(host: String): Boolean =
            host.all { it.isDigit() || it == '.' || it == ':' }
    }
}
