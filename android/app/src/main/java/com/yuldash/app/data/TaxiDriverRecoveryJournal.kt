package com.yuldash.app.data

/** Inactive JVM core. A future adapter must use a private, non-backed-up, single-writer slot.
 * A successful write is a synchronous durable replacement/removal, never a memory-only update.
 */
internal interface TaxiDriverRecoveryStorage {
    fun read(): String?
    fun commit(value: String?): Boolean
}

/** Invoke action synchronously at most once, under the lock that guards session/account/API changes. */
internal fun interface TaxiDriverRecoverySessionFence {
    fun ifCurrent(owner: SnapshotOwner, action: () -> Unit)
}

/** Status lookup only. Never put the value in a URL, analytics, logs, or an acceptance retry. */
internal class TaxiDriverRecoveryHandle private constructor(val value: String) {
    override fun toString() = "TaxiDriverRecoveryHandle(redacted)"
    companion object {
        private val wire = Regex("[A-Za-z0-9_-]{1,1000}={0,2}")
        fun parse(value: String): TaxiDriverRecoveryHandle? =
            value.takeIf(wire::matches)?.let(::TaxiDriverRecoveryHandle)
    }
}

internal class TaxiDriverRecoveryJournal(
    private val storage: TaxiDriverRecoveryStorage,
    private val sessions: TaxiDriverRecoverySessionFence,
) {
    // Generation and permit never go to disk. Cold restore must only make status available.
    internal class SendPermit internal constructor(
        internal val owner: SnapshotOwner,
        internal val handle: TaxiDriverRecoveryHandle,
    )
    private var armed: SendPermit? = null
    private var storageUncertain = false

    private fun <T> guarded(owner: SnapshotOwner, action: () -> T): T? {
        if (!owner.valid || owner.origin.length > 512) return null
        var result: T? = null
        sessions.ifCurrent(owner) {
            synchronized(this) { result = runCatching(action).getOrNull() }
        }
        return result
    }

    private fun prefix(owner: SnapshotOwner) = "1\n${owner.origin}\n${owner.namespace}\n"

    private fun save(value: String?): Boolean {
        val ok = runCatching { storage.commit(value) && storage.read() == value }.getOrDefault(false)
        storageUncertain = !ok
        return ok
    }

    /** Only on receipt of a fresh handle for the explicit click, before any outbound accept. */
    fun prepare(owner: SnapshotOwner, newHandle: TaxiDriverRecoveryHandle): SendPermit? = guarded(owner) {
        if (storageUncertain || storage.read() != null || !save(prefix(owner) + newHandle.value)) null
        else SendPermit(owner, newHandle).also { armed = it }
    }

    internal enum class Admission { NOT_INVOKED, INVOKED_OUTCOME_UNKNOWN }

    /** Claim once under the session fence. Invoking the callback never proves an HTTP outcome. */
    fun consumeSendPermit(permit: SendPermit, admit: () -> Unit): Admission {
        var invoked = false
        runCatching {
            guarded(permit.owner) {
                if (armed !== permit) return@guarded
                armed = null
                if (storageUncertain || readOwned(permit.owner)?.value != permit.handle.value) return@guarded
                // A caller or session fence may throw after control passed to the
                // caller. Neither failure proves the command was never dispatched.
                invoked = true
                admit()
            }
        }
        return if (invoked) Admission.INVOKED_OUTCOME_UNKNOWN else Admission.NOT_INVOKED
    }

    private fun readOwned(owner: SnapshotOwner): TaxiDriverRecoveryHandle? {
        val row = storage.read() ?: return null
        val expected = prefix(owner)
        return if (row.startsWith(expected)) TaxiDriverRecoveryHandle.parse(row.substring(expected.length)) else null
    }

    /** A caller with a new process generation may recover only the same durable scope. */
    fun restoreForStatus(owner: SnapshotOwner): TaxiDriverRecoveryHandle? = guarded(owner) {
        if (storageUncertain) null else readOwned(owner)
    }

    /** Unknown results must not clear the interlock. Only exact positive evidence may clear it. */
    fun clearCommitted(owner: SnapshotOwner, expected: TaxiDriverRecoveryHandle): Boolean = guarded(owner) {
        if (storageUncertain || readOwned(owner)?.value != expected.value) return@guarded false
        armed = null
        save(null)
    } ?: false

    /** Trusted lifecycle action only: before logout, or explicitly after verified session rotation.
     * Not for refresh, cold restore, an unknown result, or a late network callback.
     */
    fun clearAtSessionBoundary(current: SnapshotOwner): Boolean = guarded(current) {
        armed = null
        save(null)
    } ?: false
}
