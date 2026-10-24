package com.yuldash.app

import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.BoardingResult
import com.yuldash.app.data.BoardingUndoTerms
import com.yuldash.app.data.TripStateDto
import com.yuldash.app.data.boardingUndoMatches
import com.yuldash.app.data.sameBoardingTimestamp

/** Порядок ответов одной открытой брони: старая проверка не отменяет свежий ответ пассажира. */
internal class BoardingUndoState(private val readState: suspend (Int) -> Result<TripStateDto> = { ApiClient.getTripState(it) }) {
    private var revision = 0
    private var onboardAt: String? = null
    private var correctedAt: String? = null

    fun accept(current: String, incoming: String, terms: BoardingUndoTerms): Boolean {
        if (current == "done" || current == "cancelled") return false
        if (current == "onboard" && incoming == "confirmed") {
            if (!boardingUndoMatches(onboardAt, terms)) return false
            correctedAt = onboardAt
        }
        var epochChanged = false
        if (incoming == "onboard") {
            val previous = epoch(onboardAt)
            val received = epoch(terms.onboardAt)
            if (previous != null && (received == null || received < previous)) return false
            if (!terms.onboardAt.isNullOrBlank() && received == null) return false
            if (current == "confirmed" && sameBoardingTimestamp(correctedAt, terms.onboardAt)) return false
            if (received != null && (previous == null || received > previous)) {
                onboardAt = terms.onboardAt
                epochChanged = true
            }
        }
        if (current != incoming || epochChanged) revision += 1
        return true
    }

    private fun epoch(value: String?): java.time.Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { java.time.Instant.parse(if (value.endsWith("Z") || value.endsWith("+00:00")) value else value + "Z") }.getOrNull()
    }

    fun resultIsCurrent(current: String, result: BoardingResult): Boolean = when {
        current == "done" || current == "cancelled" -> false
        current == "onboard" && result.status == "confirmed" -> boardingUndoMatches(onboardAt, result.undo)
        current == "onboard" && result.status == "onboard" && onboardAt != null -> sameBoardingTimestamp(onboardAt, result.undo.onboardAt)
        else -> true
    }

    fun actionCommitted() { revision += 1 }

    suspend fun read(bookingId: Int): Result<TripStateDto> {
        val requested = revision
        val result = readState(bookingId)
        return if (requested == revision) result else Result.failure(ApiException(409, ""))
    }
}
