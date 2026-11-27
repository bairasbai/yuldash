package com.yuldash.app

/** HTTP success is not an acknowledgement that the server actually stored the driver's presence. */
internal class TaxiPresenceHealth {
    private var misses = 0
    val lost: Boolean get() = misses >= MISSES_TO_WARN

    fun record(result: Result<Boolean>): Boolean {
        val wasLost = lost
        misses = if (result.getOrNull() == true) 0 else (misses + 1).coerceAtMost(MISSES_TO_WARN)
        return wasLost != lost
    }

    private companion object {
        const val MISSES_TO_WARN = 3 // Three 15-second misses consume the server's presence TTL.
    }
}
