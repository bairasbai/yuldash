package com.yuldash.app

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.TipInfoDto
import com.yuldash.app.data.TripReceiptDto

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ActiveTripScreen(
    ride: Ride?,
    contacts: List<TrustedContact>,
    bookingId: Int?,
    onBack: () -> Unit,
    onTripEnd: () -> Unit,
    onSos: () -> Unit,
    onSupport: () -> Unit = {},
    onOpenReceipt: (Int) -> Unit = {},
    // Чат — второй вид этого же экрана (Screen.TripChat): общие сокет, опрос и черновик.
    // null — экран уже не наш (идёт анимация ухода): держим тот вид, что был на экране.
    chatOpen: Boolean? = null,
    // null — без навигации (тесты, превью): вид переключается внутри экрана.
    onChatOpenChange: ((Boolean) -> Unit)? = null,
    terminalHint: ActiveTripTerminalHint? = null,
    onTrustedContacts: () -> Unit = {},   // «Поделиться с близким» без контактов → добавить близкого (приёмка 18.10, п.8)
    // Поездка кончилась, а экран остаётся (отмена в пути): живое гео и маршрут на карте — гасить сразу.
    onTripStopped: () -> Unit = {},
    onDriverFinished: (suspend (Int) -> Boolean)? = null,
    isCurrentParent: (() -> Boolean)? = null,
    isCurrentReceiptParent: (() -> Boolean)? = null,
) {
    val currentSession by ApiClient.sessionChanges.collectAsState()
    val projectionOwner by OfflineTripSnapshots.owner.collectAsState()
    val tripSession = remember(bookingId) { currentSession }
    val leave by rememberUpdatedState(onBack)
    if (currentSession != tripSession) {
        // Remove all remembered state, sheets, coroutine scopes and subscriptions before navigation.
        // chatOpen в ключе: «Назад» из чата приводит на этот же экран поездки — уходим и с него.
        // null — экран уже уезжает: второй уход снял бы со стека «Назад» чужой экран.
        LaunchedEffect(tripSession, chatOpen) { if (onChatOpenChange == null || chatOpen != null) leave() }
        return
    }
    key(bookingId, tripSession, projectionOwner) {
        // Отмену в пути этот вход уже видел (CancelledInTransit.kt): экран помощи сразу, даже без сети.
        val hint = terminalHint?.takeIf { it.bookingId == bookingId && it.status in setOf("done", "cancelled") }
            ?: InTransitCancels.hint(bookingId, tripSession)
        ActiveTripContent(ride, contacts, bookingId, onBack, onTripEnd, onSos, onSupport, onOpenReceipt, tripSession, chatOpen, onChatOpenChange,
            hint, onTripStopped, onTrustedContacts, onDriverFinished, isCurrentParent, isCurrentReceiptParent)
    }
}

/**
 * Отдельный экран после завершения попутки — утверждённый вариант B «Рәхмәт прежде всего».
 *
 * В отличие от активной поездки здесь нет карты, SOS и чата: поездка уже закончилась.
 * Остаются только действия после дороги — оценка, благодарность, чек, потерянная вещь и помощь.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RideshareCompletedScreen(
    bookingId: Int,
    ride: Ride?,
    fallbackRole: String,
    fallbackPayMethod: String,
    fallbackPayAmount: Int?,
    onClose: () -> Unit,
    onOpenReceipt: () -> Unit,
    onSupport: () -> Unit,
    loadReceipt: suspend (Int) -> Result<TripReceiptDto> = { ApiClient.getTripReceipt(it) },
    rateBooking: suspend (Int, Int, String, List<String>) -> Result<Unit> = { id, stars, text, tags ->
        ApiClient.rateBooking(id, stars, text, tags)
    },
    loadThanks: suspend (Int) -> Result<TipInfoDto> = { ApiClient.getBookingTipInfo(it) },
    sendThanks: suspend (Int) -> Result<Unit> = { ApiClient.sayBookingThanks(it) },
    openLostItem: suspend (Int) -> Result<Unit> = { ApiClient.bookingLostItem(it).map { Unit } },
    isCurrentParent: (() -> Boolean)? = null,
    isCurrentReceiptParent: (() -> Boolean)? = null,
) {
    val currentSession by ApiClient.sessionChanges.collectAsState()
    val completedSession = remember(bookingId) { currentSession }
    val leave by rememberUpdatedState(onClose)
    if (currentSession != completedSession) {
        LaunchedEffect(completedSession) { leave() }
        return
    }
    key(bookingId, completedSession, ride) {
        RideshareCompletedSession(bookingId, ride, fallbackRole, fallbackPayMethod, fallbackPayAmount,
            onClose, onOpenReceipt, onSupport, loadReceipt, rateBooking, loadThanks, sendThanks,
            openLostItem, completedSession, isCurrentParent, isCurrentReceiptParent)
    }
}

