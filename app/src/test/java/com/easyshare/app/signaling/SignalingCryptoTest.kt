package com.easyshare.app.signaling

import com.easyshare.app.webrtc.PairingCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalingCryptoTest {
    @Test
    fun macRoundTrip() {
        val code = PairingCode.generateShort()
        val key = SignalingCrypto.authKey(code)
        val canonical = SignalingCrypto.canonical("h", "ready", 1_700_000_000L, 1_700_000_600L, "nonce1")
        val mac = SignalingCrypto.macHex(key, canonical)
        assertTrue(SignalingCrypto.verifyMac(key, canonical, mac))
        assertFalse(SignalingCrypto.verifyMac(key, canonical + "x", mac))
    }

    @Test
    fun authAndEncKeysDiffer() {
        val code = PairingCode.generateShort()
        val auth = SignalingCrypto.authKey(code)
        val enc = SignalingCrypto.encKey(code)
        assertEquals(32, auth.size)
        assertEquals(32, enc.size)
        assertFalse(auth.contentEquals(enc))
    }

    @Test
    fun keysAreDeterministicPerCode() {
        val code = PairingCode.generateShort()
        assertTrue(SignalingCrypto.authKey(code).contentEquals(SignalingCrypto.authKey(code)))
        assertTrue(SignalingCrypto.encKey(code).contentEquals(SignalingCrypto.encKey(code)))
        assertFalse(
            SignalingCrypto.authKey(code).contentEquals(
                SignalingCrypto.authKey(PairingCode.generateShort())
            )
        )
    }

    @Test
    fun envelopeRoundTripHidesPlaintext() {
        val code = PairingCode.generateShort()
        val key = SignalingCrypto.encKey(code)
        val inner = """{"r":"h","e":"manifest","files":[{"n":"secret/path.txt","s":12}]}"""
        val outer = SignalingCrypto.sealEnvelope(key, inner)
        assertFalse(outer.contains("secret/path.txt"))
        val opened = SignalingCrypto.openEnvelope(key, outer)
        assertEquals(inner, opened)
        assertNull(SignalingCrypto.openEnvelope(SignalingCrypto.encKey("OTHERCODE12XXXXXX"), outer))
    }

    @Test
    fun envelopeJsonAllowsWhitespaceAndKeyOrder() {
        val packed = EnvelopeJson.encode(1, "YWJj")
        assertEquals("""{"v":1,"blob":"YWJj"}""", packed)

        val spaced = """{ "blob" : "YWJj" , "v" : 1 }"""
        val decoded = EnvelopeJson.decode(spaced)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.version)
        assertEquals("YWJj", decoded.blob)

        assertEquals(2, EnvelopeJson.decode("""{"v":2,"blob":"x"}""")!!.version)
        assertNull(EnvelopeJson.decode("not-json"))
        assertNull(EnvelopeJson.decode("""{"v":1}"""))
    }

    @Test
    fun openEnvelopeRejectsWrongVersion() {
        val key = SignalingCrypto.encKey(PairingCode.generateShort())
        val sealed = SignalingCrypto.sealEnvelope(key, """{"e":"ready"}""")
        val v2 = sealed.replace("\"v\":1", "\"v\":2")
        assertNull(SignalingCrypto.openEnvelope(key, v2))
    }

    @Test
    fun topicHidesRawCode() {
        val code = PairingCode.generateShort()
        val topic = SignalingCrypto.topicId(code)
        assertEquals(32, topic.length)
        assertFalse(topic.contains(code, ignoreCase = true))
    }

    @Test
    fun pairingCodeValidation() {
        val good = PairingCode.generateShort()
        assertEquals(PairingCode.TOTAL_LENGTH, good.length)
        assertTrue(PairingCode.isValidShort(good))
        assertFalse(PairingCode.isValidShort("ABCDF23457"))
    }

    @Test
    fun sanitizeWirePath() {
        assertEquals("a/b.txt", InternetCodeSignaling.sanitizeWirePath("a/b.txt"))
        assertNull(InternetCodeSignaling.sanitizeWirePath("../etc/passwd"))
        assertNull(InternetCodeSignaling.sanitizeWirePath("/abs"))
        assertNull(InternetCodeSignaling.sanitizeWirePath("a/./b"))
    }

    @Test
    fun manifestCapConstant() {
        assertTrue(InternetCodeSignaling.MAX_MANIFEST_FILES >= 200)
        assertNotNull(InternetCodeSignaling.MAX_MANIFEST_FILES)
        assertNotEquals(0, InternetCodeSignaling.MAX_MANIFEST_FILES)
    }
}
