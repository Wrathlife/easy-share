package com.easyshare.app.webrtc

import java.security.SecureRandom

/**
 * Human-facing pairing codes.
 *
 * Format: letters then digits, no separators — e.g. ABCDFGJK23456789
 * Alphabet excludes I/O/0/1 for read-aloud clarity.
 *
 * Default length is 8+8 (~61 bits) so the code is a stronger sole WAN secret
 * than the earlier 5+5 (~38 bits) demo format.
 */
object PairingCode {
    private val letters = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray()
    private val digits = "23456789".toCharArray()
    private val random = SecureRandom()

    const val LETTER_COUNT = 8
    const val DIGIT_COUNT = 8
    const val TOTAL_LENGTH = LETTER_COUNT + DIGIT_COUNT

    fun generateShort(
        letterCount: Int = LETTER_COUNT,
        digitCount: Int = DIGIT_COUNT
    ): String {
        val letterPart = CharArray(letterCount) { letters[random.nextInt(letters.size)] }.concatToString()
        val digitPart = CharArray(digitCount) { digits[random.nextInt(digits.size)] }.concatToString()
        return letterPart + digitPart
    }

    fun normalize(raw: String): String =
        raw.trim().uppercase().replace("-", "").replace(" ", "").filter { it.isLetterOrDigit() }

    /** Strict host/guest format check for WAN pairing. */
    fun isValidShort(raw: String): Boolean {
        val code = normalize(raw)
        if (code.length != TOTAL_LENGTH) return false
        val letterPart = code.take(LETTER_COUNT)
        val digitPart = code.takeLast(DIGIT_COUNT)
        if (letterPart.any { it !in letters }) return false
        if (digitPart.any { it !in digits }) return false
        return true
    }
}
