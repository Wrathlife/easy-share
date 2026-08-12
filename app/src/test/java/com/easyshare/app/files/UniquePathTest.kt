package com.easyshare.app.files

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirrors SafShareCollector.uniquePath disambiguation without Android APIs.
 */
class UniquePathTest {
    @Test
    fun disambiguatesDuplicateNames() {
        val used = linkedSetOf<String>()
        fun unique(displayName: String): String {
            if (used.add(displayName)) return displayName
            val dot = displayName.lastIndexOf('.')
            val stem = if (dot > 0) displayName.substring(0, dot) else displayName
            val ext = if (dot > 0) displayName.substring(dot) else ""
            var i = 2
            while (true) {
                val candidate = "$stem ($i)$ext"
                if (used.add(candidate)) return candidate
                i++
            }
        }
        assertEquals("a.txt", unique("a.txt"))
        assertEquals("a (2).txt", unique("a.txt"))
        assertEquals("a (3).txt", unique("a.txt"))
        assertEquals("photo.jpg", unique("photo.jpg"))
        assertEquals("photo (2).jpg", unique("photo.jpg"))
    }
}
