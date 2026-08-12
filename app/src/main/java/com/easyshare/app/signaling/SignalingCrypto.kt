package com.easyshare.app.signaling

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Session crypto for MQTT signaling:
 * - Topic hides the raw share code
 * - PBKDF2 stretches the code before deriving auth/enc keys
 * - AES-GCM encrypts payloads (broker cannot read filenames)
 * - HMAC remains as an inner authenticity check
 */
object SignalingCrypto {
    private const val MAC_ALG = "HmacSHA256"
    private const val AES = "AES"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12
    private const val PBKDF2 = "PBKDF2WithHmacSHA256"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val MASTER_KEY_BITS = 256
    private val utf8 = StandardCharsets.UTF_8
    private val random = SecureRandom()

    fun topicId(normalizedCode: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedCode.toByteArray(utf8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun authKey(normalizedCode: String): ByteArray =
        expandKey(masterKey(normalizedCode), "easyshare-v1-auth")

    fun encKey(normalizedCode: String): ByteArray =
        expandKey(masterKey(normalizedCode), "easyshare-v1-enc")

    /** PBKDF2 master from share code; salt is deterministic so both peers agree. */
    private fun masterKey(normalizedCode: String): ByteArray {
        val salt = MessageDigest.getInstance("SHA-256")
            .digest("easyshare-v1-salt|$normalizedCode".toByteArray(utf8))
        val spec = PBEKeySpec(
            normalizedCode.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            MASTER_KEY_BITS
        )
        return SecretKeyFactory.getInstance(PBKDF2).generateSecret(spec).encoded
    }

    /** HKDF-Extract-style expand: HMAC(master, label) → 32-byte domain key. */
    private fun expandKey(master: ByteArray, label: String): ByteArray {
        val mac = Mac.getInstance(MAC_ALG)
        mac.init(SecretKeySpec(master, MAC_ALG))
        return mac.doFinal(label.toByteArray(utf8))
    }

    fun macHex(key: ByteArray, canonical: String): String {
        val mac = Mac.getInstance(MAC_ALG)
        mac.init(SecretKeySpec(key, MAC_ALG))
        return mac.doFinal(canonical.toByteArray(utf8)).joinToString("") { "%02x".format(it) }
    }

    fun verifyMac(key: ByteArray, canonical: String, macHex: String): Boolean {
        if (macHex.isBlank() || macHex.length != 64) return false
        val expected = macHex(key, canonical)
        return MessageDigest.isEqual(
            expected.toByteArray(utf8),
            macHex.lowercase().toByteArray(utf8)
        )
    }

    fun randomNonce(): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun canonical(
        role: String,
        event: String,
        ts: Long,
        exp: Long,
        nonce: String,
        extra: String = ""
    ): String = listOf(role, event, ts.toString(), exp.toString(), nonce, extra).joinToString("|")

    /** Encrypt inner JSON → outer envelope `{"v":1,"blob":"..."}`. */
    fun sealEnvelope(encKey: ByteArray, innerJson: String): String {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, AES), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(innerJson.toByteArray(utf8))
        val packed = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, packed, 0, iv.size)
        System.arraycopy(ct, 0, packed, iv.size, ct.size)
        val blob = Base64.getEncoder().encodeToString(packed)
        return EnvelopeJson.encode(version = 1, blob = blob)
    }

    /** Decrypt outer envelope to inner JSON string, or null if invalid. */
    fun openEnvelope(encKey: ByteArray, outerPayload: String): String? {
        val parsed = EnvelopeJson.decode(outerPayload) ?: return null
        if (parsed.version != 1 || parsed.blob.isBlank()) return null
        val packed = runCatching { Base64.getDecoder().decode(parsed.blob) }.getOrNull() ?: return null
        if (packed.size <= IV_LEN + 16) return null
        val iv = packed.copyOfRange(0, IV_LEN)
        val ct = packed.copyOfRange(IV_LEN, packed.size)
        return runCatching {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, AES), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), utf8)
        }.getOrNull()
    }
}

/**
 * Minimal JSON envelope codec for `{"v":N,"blob":"..."}` — no Android org.json,
 * so JVM unit tests work without Robolectric.
 */
internal object EnvelopeJson {
    data class Envelope(val version: Int, val blob: String)

    fun encode(version: Int, blob: String): String {
        require(blob.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) {
            "blob must be standard Base64"
        }
        return """{"v":$version,"blob":"$blob"}"""
    }

    fun decode(raw: String): Envelope? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val body = trimmed.substring(1, trimmed.length - 1)
        var version: Int? = null
        var blob: String? = null
        var i = 0
        while (i < body.length) {
            skipWs(body, i).also { i = it }
            if (i >= body.length) break
            if (body[i] != '"') return null
            val keyEnd = body.indexOf('"', i + 1)
            if (keyEnd < 0) return null
            val key = body.substring(i + 1, keyEnd)
            i = keyEnd + 1
            i = skipWs(body, i)
            if (i >= body.length || body[i] != ':') return null
            i = skipWs(body, i + 1)
            when (key) {
                "v" -> {
                    val start = i
                    while (i < body.length && (body[i].isDigit() || body[i] == '-')) i++
                    version = body.substring(start, i).toIntOrNull() ?: return null
                }
                "blob" -> {
                    if (i >= body.length || body[i] != '"') return null
                    val start = i + 1
                    val end = body.indexOf('"', start)
                    if (end < 0) return null
                    blob = body.substring(start, end)
                    i = end + 1
                }
                else -> {
                    // Skip unknown value (string or number) for forward compatibility.
                    i = when {
                        i < body.length && body[i] == '"' -> {
                            val end = body.indexOf('"', i + 1)
                            if (end < 0) return null
                            end + 1
                        }
                        else -> {
                            while (i < body.length && body[i] != ',' && body[i] != '}') i++
                            i
                        }
                    }
                }
            }
            i = skipWs(body, i)
            if (i < body.length && body[i] == ',') i++
        }
        val v = version ?: return null
        val b = blob ?: return null
        return Envelope(version = v, blob = b)
    }

    private fun skipWs(s: String, start: Int): Int {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }
}
