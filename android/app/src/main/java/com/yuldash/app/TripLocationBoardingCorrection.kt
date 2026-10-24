package com.yuldash.app

import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** После исправления посадки прежний пассажирский канал проходит свежую серверную проверку. */
internal fun recheckPassengerCorrection(
    scope: CoroutineScope, bookingId: Int, owner: Long,
    isCurrent: () -> Boolean, onAllowed: () -> Unit, onRefused: () -> Unit,
) {
    scope.launch {
        val result = ApiClient.authorized("GET", "/bookings/$bookingId/role", null, owner)
        if (!isCurrent()) return@launch
        val state = result.getOrNull()
        if (state?.optString("role") == "passenger" && state.optString("status") in setOf("confirmed", "onboard")) onAllowed()
        else onRefused()
    }
}
