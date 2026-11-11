package com.yuldash.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommandIntentTest {
    private var now = 1L
    private val disk = mutableMapOf<String, String>()
    private val account = CommandIntent.account("https://test.invalid", 7, "created")
    private val hash = "a".repeat(64)
    private fun store() = CommandIntent(disk::get, { key, value ->
        if (value == null) disk.remove(key) else disk[key] = value
        true
    }, { now })

    @Test fun reloadAndClockDoNotReplaceUnknownKey() {
        val first = store().prepare(account, 41, hash, null, retry = false)
        now += 16 * 60 * 1000
        val restarted = store()
        assertEquals(first, restarted.prepare(account, 41, hash, first.key, retry = true))
        assertThrows(CommandIntent.Failure::class.java) { restarted.prepare(account, 41, "b".repeat(64), first.key, true) }
        now = first.createdAt + 24 * 60 * 60 * 1000L
        assertEquals(CommandIntent.Problem.Expired, assertThrows(CommandIntent.Failure::class.java) {
            restarted.prepare(account, 41, hash, first.key, true)
        }.problem)
        now = 0
        assertEquals(CommandIntent.Problem.Expired, assertThrows(CommandIntent.Failure::class.java) {
            restarted.prepare(account, 41, hash, first.key, true)
        }.problem)
        assertEquals(first, restarted.list(account).single())
    }

    @Test fun retryAfterInventoryChangedRestoresOnlyTheOriginalTermsVersion() {
        for ((index, version) in listOf(0L, null).withIndex()) {
            val rideId = index + 1
            val initialBody = JSONObject().put("ride_id", rideId).put("seats", 1)
                .apply { if (version != null) put("expected_terms_version", version) }
            val initial = bookingCommandSnapshot(rideId, initialBody, null)
            val first = store().prepare(account, rideId, initial.payloadHash, null, retry = false,
                bookingTerms = CommandIntent.BookingTerms(version))
            now += 16 * 60 * 1000
            val reopened = store()
            val saved = reopened.list(account).single { it.rideId == rideId }
            assertEquals(CommandIntent.BookingTerms(version), saved.bookingTerms)

            val refreshed = JSONObject().put("ride_id", rideId).put("seats", 1).put("expected_terms_version", 2)
            val retry = bookingCommandSnapshot(rideId, refreshed, saved.bookingTerms)
            assertEquals(initial, retry)
            assertEquals(2, refreshed.getInt("expected_terms_version"))
            assertEquals(first, reopened.prepare(account, rideId, retry.payloadHash, first.key, retry = true))

            refreshed.put("seats", 2)
            val changed = bookingCommandSnapshot(rideId, refreshed, saved.bookingTerms)
            assertEquals(CommandIntent.Problem.Changed, assertThrows(CommandIntent.Failure::class.java) {
                reopened.prepare(account, rideId, changed.payloadHash, first.key, retry = true)
            }.problem)
        }
    }

    @Test fun legacySlotWithoutOriginalTermsCannotGuessAnOlderVersion() {
        val body = JSONObject().put("ride_id", 1).put("expected_terms_version", 0)
        val initial = bookingCommandSnapshot(1, body, null)
        val first = store().prepare(account, 1, initial.payloadHash, null, retry = false)
        val reopened = store()
        assertNull(reopened.list(account).single().bookingTerms)
        val changed = bookingCommandSnapshot(1, body.put("expected_terms_version", 2), null)
        assertEquals(CommandIntent.Problem.Changed, assertThrows(CommandIntent.Failure::class.java) {
            reopened.prepare(account, 1, changed.payloadHash, first.key, retry = true)
        }.problem)
    }

    @Test fun boundedStoreRetainsUnknownAndSettlesOnlyTheCapturedKey() {
        val commands = store()
        val first = (1..64).map { commands.prepare(account, it, hash, null, false) }.first()
        assertEquals(CommandIntent.Problem.Full, assertThrows(CommandIntent.Failure::class.java) {
            commands.prepare(account, 65, hash, null, false)
        }.problem)
        assertTrue(commands.settle(account, first, CommandIntent.State.Closed))
        val next = commands.prepare(account, 1, hash, first.key, false)
        assertNotEquals(first.key, next.key)
        assertFalse(commands.settle(account, first, CommandIntent.State.Applied))
        assertEquals(64, commands.list(account).size)
        val other = CommandIntent.account("https://test.invalid", 9, "created")
        assertTrue(commands.list(other).isEmpty())
        commands.prepare(other, 1, hash, null, false)
        commands.purge(account)
        assertFalse(commands.settle(account, next, CommandIntent.State.Applied))
        assertEquals(1, commands.list(other).size)
    }

    @Test fun rejectedCommitCannotBecomeAnInMemoryRetry() {
        val commands = CommandIntent(disk::get, { key, value -> disk[key] = checkNotNull(value); false })
        assertEquals(CommandIntent.Problem.Storage, assertThrows(CommandIntent.Failure::class.java) {
            commands.prepare(account, 1, hash, null, false)
        }.problem)
        assertThrows(CommandIntent.Failure::class.java) { commands.list(account) }
        disk[account] = "{bad"
        assertThrows(CommandIntent.Failure::class.java) { store().prepare(account, 1, hash, null, false) }
    }
}
