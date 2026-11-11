package com.yuldash.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RidePublicationIntentTest {
    private val disk = mutableMapOf<String, String>()
    private var now = 1_000_000L
    private var writable = true
    private val draft = RidePublicationIntent.Draft("b".repeat(64), "2030-01-02T10:00:00+05:00", "ba")
    private val hash = "a".repeat(64)

    private fun open() = RidePublicationIntent(disk::get, { account, value ->
        if (writable) {
            if (value == null) disk.remove(account) else disk[account] = value
        }
        writable
    }, { now })

    @Test fun storedPublicationHasOnlyRecoveryMetadataAndBelongsToOneAccount() {
        val slot = open().prepare("driver-a", hash, draft, null, retry = false)
        assertEquals(slot, open().current("driver-a"))
        assertNull(open().current("driver-b"))
        assertEquals(setOf("key", "hash", "created", "state", "draft_hash", "depart_at", "stop_language"),
            JSONObject(disk.getValue("driver-a")).keys().asSequence().toSet())
        assertNotEquals(CommandIntent.prefKey("driver-a"), RidePublicationIntent.prefKey("driver-a"))
    }

    @Test fun writeFailureDoesNotAdmitRequestAndBlocksAmbiguousInMemoryState() {
        val store = open()
        writable = false
        assertEquals(CommandIntent.Problem.Storage, failure { store.prepare("a", hash, draft, null, false) })
        assertTrue(disk.isEmpty())
        writable = true
        assertEquals(CommandIntent.Problem.Storage, failure { store.current("a") })
        assertNull(open().current("a"))
    }

    @Test fun duplicateAndLateResultsCannotReplaceCurrentSlot() {
        val store = open()
        val initial = store.prepare("a", hash, draft, null, false)
        assertEquals(CommandIntent.Problem.Unknown, failure { store.prepare("a", hash, draft, null, false) })
        assertTrue(store.settle("a", initial, CommandIntent.State.Closed))
        assertFalse(store.settle("a", initial, CommandIntent.State.Applied))
        val newer = store.prepare("a", hash, draft, initial.key, false)
        assertNotEquals(initial.key, newer.key)
        assertFalse(store.settle("a", initial, CommandIntent.State.Applied))
        assertEquals(newer, store.current("a"))
    }

    @Test fun retryPreservesOriginalDateAndLanguageAndExpiresAtOneDay() {
        val store = open()
        val slot = store.prepare("a", hash, draft, null, false)
        now += 24 * 60 * 60 * 1000L - 1
        assertEquals(slot, store.prepare("a", hash, draft, slot.key, true))
        assertEquals(CommandIntent.Problem.Changed,
            failure { store.prepare("a", hash, draft.copy(stopLanguage = "ru"), slot.key, true) })
        now++
        assertEquals(CommandIntent.Problem.Expired, failure { store.prepare("a", hash, draft, slot.key, true) })
        assertTrue(store.settle("a", slot, CommandIntent.State.Closed))
    }

    private fun failure(action: () -> Any?): CommandIntent.Problem =
        (runCatching(action).exceptionOrNull() as RidePublicationIntent.Failure).problem
}
