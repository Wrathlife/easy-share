package com.netshare.app.webrtc

import java.security.SecureRandom

/**
 * Human-facing pairing codes.
 *
 * Format: letters then digits — e.g. ABCDF-23457 (display) / ABCDF23457 (wire).
 * Alphabet excludes I/O/0/1 for read-aloud clarity.
 *
 * Length is 5+5 for easy readout; PBKDF2 stretches the secret before use.
 */
object PairingCode {
    private val letters = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray()
    private val digits = "23456789".toCharArray()
    private val random = SecureRandom()

    const val LETTER_COUNT = 5
    const val DIGIT_COUNT = 5
    const val TOTAL_LENGTH = LETTER_COUNT + DIGIT_COUNT

    fun generateShort(
        letterCount: Int = LETTER_COUNT,
        digitCount: Int = DIGIT_COUNT
    ): String {
        val letterPart = CharArray(letterCount) { letters[random.nextInt(letters.size)] }.concatToString()
        val digitPart = CharArray(digitCount) { digits[random.nextInt(digits.size)] }.concatToString()
        return letterPart + digitPart
    }

    /** Display form with a hyphen between letters and digits. */
    fun formatForDisplay(raw: String): String {
        val code = normalize(raw)
        if (code.length != TOTAL_LENGTH) return code
        return code.take(LETTER_COUNT) + "-" + code.takeLast(DIGIT_COUNT)
    }

    fun normalize(raw: String): String =
        raw.trim().uppercase().replace("-", "").replace(" ", "").filter { it.isLetterOrDigit() }

    /** Typing/paste sanitizer: alphanumerics only, capped length (hyphen is display-only). */
    fun sanitizeTyping(raw: String): String = normalize(raw).take(TOTAL_LENGTH)

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
