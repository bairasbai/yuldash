package com.yuldash.app.data

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
