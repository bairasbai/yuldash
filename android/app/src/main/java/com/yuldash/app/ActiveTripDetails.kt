package com.yuldash.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.BookingDetailsDto
import kotlinx.coroutines.CancellationException

/**
 * Только статус из уже прочитанной серверной карточки, с номером её брони. [inTransit] — сервер сказал
 * `cancelled_in_transit` (отменили, когда пассажир был в машине): экран поездки не закрывается сам.
 */
internal data class ActiveTripTerminalHint(val bookingId: Int, val status: String, val role: String, val inTransit: Boolean = false)

internal fun knownTerminalTripHint(bookingId: Int?, status: String, role: String, inTransit: Boolean = false): ActiveTripTerminalHint? =
    bookingId?.takeIf { it > 0 && status in setOf("done", "cancelled") && role in setOf("driver", "passenger") }
        ?.let { ActiveTripTerminalHint(it, status, role, inTransit = inTransit && status == "cancelled") }

/** Без ответа сервера и сохранённого паспорта активную поездку ещё нельзя утверждать. */
@Composable
internal fun ActiveTripUnverifiedScreen(loading: Boolean, failure: Throwable?, onRetry: () -> Unit, onBack: () -> Unit,
    cancelled: Boolean = false) {
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Моя поездка", "Минең сәфәр"), onBack) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(CanonSpace.lg)) {
            if (cancelled) AppCard {
                Column(Modifier.fillMaxWidth().padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
                    Text(appText("Поездка отменена", "Сәфәр кире алынды"), style = CanonBodyStrong, color = CanonText)
                    AppButton(appText("Закрыть", "Ябырға"), onClick = onBack, style = AppButtonStyle.Secondary)
                }
            }
            else ActiveTripDetailsNotice(loading = loading, failure = failure, onRetry = onRetry)
        }
    }
}

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
    // A completed screen load, not a cached/private-ready Boolean, serializes the first CODE read.
    var completedDetails by mutableStateOf<BookingDetailsDto?>(null)
        private set
    var completedLoad by mutableLongStateOf(0L)
        private set
    private var initialConsumed = false
    val needsReload: Boolean get() = reloadTick != attemptedTick

    // UI recovery intent only. Neither flag grants permission or replaces current private authority.
    private var privateWasReady by mutableStateOf(false)
    var privateRecoveryPending by mutableStateOf(false)
        private set

    fun privateCodeReadStarted(): Boolean {
        val id = bookingId ?: return false
        if (privateRecoveryPending || !ApiClient.isCurrentSession(owner) || currentTripPrivateDetails(id) == null) return false
        privateWasReady = true
        return true
    }
    fun privateDataMissing(ready: Boolean, role: String, status: String): Boolean =
        bookingId != null && role in setOf("driver", "passenger") && status in CONTACT_STATUSES &&
            ((privateWasReady && !ready) || privateRecoveryPending)

    fun retryData(isCurrent: () -> Boolean, role: String, status: String) {
        if (!isCurrent() || !ApiClient.isCurrentSession(owner)) return
        if (privateDataMissing(currentTripPrivateDetails(bookingId) != null, role, status)) retryPrivateData(isCurrent, role, status)
        else retry()
    }
    fun retryCode(isCurrent: () -> Boolean, role: String, status: String, codeOnlyRetry: () -> Unit) {
        if (!isCurrent() || !ApiClient.isCurrentSession(owner)) return
        if (privateDataMissing(currentTripPrivateDetails(bookingId) != null, role, status)) retryPrivateData(isCurrent, role, status)
        else codeOnlyRetry()
    }
    private fun retryPrivateData(isCurrent: () -> Boolean, role: String, status: String) {
        if (!isCurrent() || !ApiClient.isCurrentSession(owner) || loading || needsReload ||
            role !in setOf("driver", "passenger") || status !in CONTACT_STATUSES) return
        privateRecoveryPending = true
        loading = true
        reloadTick++
    }
    fun privateRecoveryCommitted(details: BookingDetailsDto, isCurrent: () -> Boolean, role: String, status: String) {
        if (!privateRecoveryPending || !isCurrent() || !ApiClient.isCurrentSession(owner) || loading || needsReload || failure != null) return
        if (details.bookingId != bookingId || details.role != role || details.status != status ||
            status !in CONTACT_STATUSES || currentTripPrivateDetails(bookingId) !== details) return
        privateRecoveryPending = false
    }

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
    fun retryUnverified() {
        val id = bookingId ?: return
        if (loading || needsReload || !ApiClient.isCurrentSession(owner) ||
            com.yuldash.app.data.OfflineTripSnapshots.allowsLive(id)) return
        // Only this explicit action turns lost authority into a fresh details attempt.
        if (privateWasReady) privateRecoveryPending = true
        if (failure == null) failure = IllegalStateException()
        retry()
    }
    private var sawSuccessfulPoll = false
    fun serverReached(failureAtRequestStart: Throwable?, role: String = "", status: String = "") {
        val first = !sawSuccessfulPoll
        sawSuccessfulPoll = true
        if (privateRecoveryPending && failure != null) return
        // A role request begun before a details failure cannot recover that later failure.
        if (failureAtRequestStart != null && failure === failureAtRequestStart) { retry(); return }
        if (failure != null || loading || needsReload || loadedStatus == null ||
            !ApiClient.isCurrentSession(owner) || role !in setOf("driver", "passenger") || status !in CONTACT_STATUSES) return
        // A phase change can withdraw private access while retaining accepted DETAILS.
        // A semantic blank CODE removes that projection; ROLE alone must not recreate it.
        val projection = bookingId?.let(com.yuldash.app.data.OfflineTripSnapshots::projection) ?: return
        if (projection.owner.generation != owner || projection.details.bookingId != bookingId) return
        val missing = !com.yuldash.app.data.OfflineTripSnapshots.privateDetails(projection.details)
        if (first && !missing) return
        // A successful poll refreshes mutable details (including the payment agreement).
        // The GET still needs its own authority; a ROLE response never grants private access.
        privateRecoveryPending = missing
        loading = true
        reloadTick++
    }

    suspend fun load(
        isCurrent: () -> Boolean,
        currentStatus: () -> String,
        currentRole: () -> String,
        fetch: suspend (Int) -> Result<BookingDetailsDto> = { ApiClient.getBookingDetails(it) },
        confirmsBoardingCorrection: (String, BookingDetailsDto) -> Boolean = { _, _ -> false },
        initial: BookingDetailsDto? = null,
    ): BookingDetailsDto? {
        val id = bookingId ?: return null
        if (!isCurrent() || owner != ApiClient.queueSessionGeneration()) return null
        val ticket = ++latestRequest
        attemptedTick = reloadTick
        loading = true
        completedDetails = null
        failure = null
        try {
            // Reusing a current permit is not a new network observation and never renews its age.
            val seeded = initial?.takeIf { !initialConsumed && reloadTick == 0 && com.yuldash.app.data.OfflineTripSnapshots.freshDetails(it) }
            initialConsumed = true
            val result = seeded?.let { Result.success(it) } ?: fetch(id)
            if (ticket != latestRequest || !isCurrent() || owner != ApiClient.queueSessionGeneration()) return null
            return result.fold(onSuccess = { details ->
                val status = currentStatus()
                val role = currentRole()
                val terminal = com.yuldash.app.data.OfflineTripSnapshots.terminal(id)
                val acceptedTerminal = details.status in setOf("done", "cancelled") && terminal?.let {
                    it.owner.generation == owner && it.status == details.status && it.role == details.role
                } == true
                if (details.bookingId != id ||
                    (role.isNotBlank() && details.role.isNotBlank() && role != details.role) ||
                    (status.isNotBlank() && details.status.isNotBlank() && status != details.status &&
                        !acceptedTerminal && !(status == "onboard" && details.status == "confirmed" && confirmsBoardingCorrection(status, details)))) {
                    failure = IllegalStateException()
                    null
                } else details.also { loadedStatus = it.status; completedDetails = it; completedLoad = ticket }
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

/** Existing missing-ride UI, extracted to keep the guarded booking screen within its cap. */
@Composable
internal fun BookingRideUnavailable(bookingId: Int?, onBack: () -> Unit) {
    if (bookingId == null) {
        LaunchedEffect(Unit) { onBack() }
    } else {
        // The parent is restoring this known booking. Do not race its server lookup with back navigation.
        Scaffold(
            containerColor = CanonBg,
            topBar = { ScreenTopBar(appText("Детали поездки", "Сәфәр тураһында"), onBack) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CanonGreen2)
            }
        }
    }
    return
}
