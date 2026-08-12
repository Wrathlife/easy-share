package com.easyshare.app.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure merge logic mirror of ReceivedHistoryStore.upsertSession.
 * Android EncryptedSharedPreferences needs instrumentation; unit-test the merge rules.
 */
class ReceivedHistoryMergeTest {
    @Test
    fun preservesDownloadedFlagsOnReupsert() {
        val existing = listOf(
            ReceivedFileRecord("a/b.txt", 10, downloaded = true),
            ReceivedFileRecord("c.txt", 20, downloaded = false)
        )
        val incoming = listOf(
            ReceivedFileRecord("a/b.txt", 10, downloaded = false),
            ReceivedFileRecord("c.txt", 20, downloaded = false),
            ReceivedFileRecord("d.txt", 30, downloaded = false)
        )
        val merged = incoming.map { file ->
            val prior = existing.find { it.name == file.name }
            if (prior?.downloaded == true) file.copy(downloaded = true) else file
        }
        assertTrue(merged.first { it.name == "a/b.txt" }.downloaded)
        assertEquals(false, merged.first { it.name == "c.txt" }.downloaded)
        assertEquals(3, merged.size)
    }

    @Test
    fun sessionIndexMatchesIdOnlyNotRedactedCode() {
        val sessions = listOf(
            ReceivedSessionRecord(
                id = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                shareCode = "ABCD••••WXYZ",
                receivedAtEpochMs = 1L,
                files = listOf(ReceivedFileRecord("a.txt", 1, downloaded = true))
            ),
            ReceivedSessionRecord(
                id = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                shareCode = "ABCD••••WXYZ", // same redaction, different real code
                receivedAtEpochMs = 2L,
                files = listOf(ReceivedFileRecord("b.txt", 2, downloaded = false))
            )
        )
        val incomingId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val idxById = sessions.indexOfFirst { it.id == incomingId }
        val idxByCodeOrId = sessions.indexOfFirst {
            it.id == incomingId || it.shareCode == "ABCD••••WXYZ"
        }
        assertEquals(1, idxById)
        // Matching on redacted code would wrongly hit the first session.
        assertEquals(0, idxByCodeOrId)
        assertTrue(idxById != idxByCodeOrId)
    }
}
