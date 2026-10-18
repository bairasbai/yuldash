package com.yuldash.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.BookingDetailsDto
import kotlinx.coroutines.CancellationException

/** Один упорядоченный источник details: повтор не трогает историю, код и изменения поездки. */
@Stable
internal class ActiveTripDetailsState(private val bookingId: Int?, private val owner: Long) {
    var loading by mutableStateOf(false)
        private set
    var failure by mutableStateOf<Throwable?>(null)
        private set
    var reloadTick by mutableIntStateOf(0)
        private set
    private var attemptedTick = -1
    private var latestRequest = 0L
    private var loadedStatus: String? = null
    val needsReload: Boolean get() = reloadTick != attemptedTick

    fun statusChanged(previous: String, next: String) {
        if (next in CONTACT_STATUSES && next != previous &&
            (previous.isNotBlank() || loadedStatus?.let { it != next } == true)) reloadTick++
    }
    fun retry() {
        // Старое нажатие/сигнал после успеха и уже запланированный повтор не создают ещё один GET.
        if (failure == null || loading || needsReload) return
        loading = true
        reloadTick++
    }
    fun serverReached(failureAtRequestStart: Throwable?) {
        // Ответ запроса, начатого до ошибки details, ещё не доказывает восстановление после неё.
        if (failureAtRequestStart != null && failure === failureAtRequestStart) retry()
    }

    suspend fun load(
        isCurrent: () -> Boolean,
        currentStatus: () -> String,
        currentRole: () -> String,
        fetch: suspend (Int) -> Result<BookingDetailsDto> = { ApiClient.getBookingDetails(it) },
    ): BookingDetailsDto? {
        val id = bookingId ?: return null
        if (!isCurrent() || owner != ApiClient.queueSessionGeneration()) return null
        val ticket = ++latestRequest
        attemptedTick = reloadTick
        loading = true
        failure = null
        try {
            val result = fetch(id)
            if (ticket != latestRequest || !isCurrent() || owner != ApiClient.queueSessionGeneration()) return null
            return result.fold(onSuccess = { details ->
                val status = currentStatus()
                val role = currentRole()
                if (details.bookingId != id ||
                    (status.isNotBlank() && details.status.isNotBlank() && status != details.status) ||
                    (role.isNotBlank() && details.role.isNotBlank() && role != details.role)) {
                    failure = IllegalStateException()
                    null
                } else details.also { loadedStatus = it.status }
            }, onFailure = { error ->
                if (error is CancellationException) throw error
                failure = error
                null
            })
        } finally {
            // Поздний ответ не скрывает загрузку и не портит результат более нового запроса.
            if (ticket == latestRequest) loading = false
        }
    }
}

/** Причины повтора меняют только tick: успешный опрос не отменяет уже идущий GET. */
@Composable
internal fun ActiveTripDetailsRefresh(state: ActiveTripDetailsState,
    isCurrent: () -> Boolean, load: suspend () -> Unit) {
    val current by rememberUpdatedState(isCurrent)
    val reload by rememberUpdatedState(load)
    val requested = state.reloadTick
    LaunchedEffect(state, requested) {
        if (requested == state.reloadTick && state.needsReload && current()) reload()
    }
}

@Composable
internal fun ActiveTripDetailsNotice(loading: Boolean, failure: Throwable?, onRetry: () -> Unit) {
    val fallback = appText("Проверь сеть и повтори.", "Селтәрҙе тикшереп ҡабатла.")
    AppCard {
        AnimatedContent(loading, transitionSpec = {
            fadeIn(tween(CanonMotion.QUICK)) togetherWith fadeOut(tween(CanonMotion.QUICK))
        }, label = "trip-details-load") { loading ->
            Column(Modifier.fillMaxWidth().padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
                Text(if (loading) appText("Обновляем данные поездки", "Сәфәр мәғлүмәттәрен яңыртабыҙ")
                    else appText("Не удалось загрузить данные поездки", "Сәфәр мәғлүмәттәрен йөкләп булманы"),
                    style = CanonBodyStrong, color = CanonText)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CanonGreen2)
                else {
                    Text(failure?.let { serverSaid(it, fallback) } ?: fallback, style = CanonCaption, color = CanonMuted)
                    AppButton(appText("Повторить загрузку данных", "Мәғлүмәттәрҙе яңынан йөкләргә"), onClick = onRetry,
                        style = AppButtonStyle.Secondary)
                }
            }
        }
    }
}
