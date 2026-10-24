package com.yuldash.app

import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.BoardingResult
import com.yuldash.app.data.BoardingUndoTerms
import com.yuldash.app.data.TripStateDto
import com.yuldash.app.data.boardingUndoMatches
import com.yuldash.app.data.sameBoardingTimestamp

/** Порядок ответов одной открытой брони: старая проверка не отменяет свежий ответ пассажира. */
internal class BoardingUndoState {
    private var revision = 0
    private var onboardAt: String? = null
    private var correctedAt: String? = null

    fun accept(current: String, incoming: String, terms: BoardingUndoTerms): Boolean {
        if (current == "done" || current == "cancelled") return false
        if (current == "onboard" && incoming == "confirmed") {
            if (!boardingUndoMatches(onboardAt, terms)) return false
            correctedAt = onboardAt
        }
        if (incoming == "onboard") {
            if (current == "confirmed" && sameBoardingTimestamp(correctedAt, terms.onboardAt)) return false
            onboardAt = terms.onboardAt ?: onboardAt
        }
        if (current != incoming) revision += 1
        return true
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
        val result = ApiClient.getTripState(bookingId)
        return if (requested == revision) result else Result.failure(ApiException(409, ""))
    }
}
