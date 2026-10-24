package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.TripLocationBus
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Водитель ведёт рейс (Д2, решение Александра 24.10.2026): закрыл бронь одного пассажира, а остальные
// ещё едут, — он возвращается к «Едут с тобой», а не на карту. Экран поездки и YuldashApp зовут только
// эти функции; GPS переключает служба точки (TripLocationService), выбор несущей — DriverRideGps.kt.

/** Остались ли у рейса брони [closedBookingId] живые брони: true / false; null — сеть не ответила. */
internal suspend fun driverRideHasLiveBookings(closedBookingId: Int, owner: Long): Boolean? =
    ApiClient.getDriverBookings(expectedGeneration = owner).getOrNull()
        ?.let { pickRideCarrier(it, closedBookingId, setOf(closedBookingId)).liveBookings.isNotEmpty() }

/**
 * Закрытую поездку заменяет кабинет водителя, прокрученный к «Едут с тобой». «Назад» в закрытую
 * поездку не ведёт: она снята с трейла, как и кабинет, из которого её открыли (туда мы и вернулись).
 */
internal fun YuldashViewModel.returnToDriverRide() {
    val closedTrail = setOf(Screen.ActiveTrip, Screen.TripChat, Screen.DriverCabinet)
    while (navHistory.lastOrNull() in closedTrail) navHistory.removeAt(navHistory.lastIndex)
    activeTrip.value = null
    focusRidingSection.value = true
    navPopping.value = true
    screen.value = Screen.DriverCabinet
}

/**
 * «Завершить» у водителя прошло на сервере. Остались живые брони рейса — сразу «Едут с тобой» (true),
 * экран итога не показываем. Последняя бронь или сеть не ответила — false: экран итога и оценки, как раньше.
 */
internal suspend fun YuldashViewModel.driverFinishedBooking(bookingId: Int): Boolean {
    val owner = ApiClient.queueSessionGeneration()
    val remains = driverRideHasLiveBookings(bookingId, owner)
    if (remains == false) driverRideOverBooking.value = bookingId   // «Закрыть» итога потом не ждёт сеть
    if (remains != true) return false
    if (!ApiClient.isCurrentSession(owner) || activeBookingId.value != bookingId) return false
    returnToDriverRide()
    return true
}

/** Сколько «Закрыть» итога ждёт ответа о рейсе, прежде чем увести в кабинет без него. */
internal const val DRIVER_RIDE_LOOKUP_MS = 3_000L

/**
 * Экран поездки закрылся сам или кнопкой (итог «Закрыть», отмена брони). Водитель с живыми бронями
 * рейса — в «Едут с тобой»; не узнали за [DRIVER_RIDE_LOOKUP_MS] (нет сети) — тоже туда: там свои
 * загрузка и «Повторить». Последняя бронь (уже знаем после «Завершить» — сразу) — [fallback], карта, как раньше.
 */
internal fun YuldashViewModel.endTripScreen(closedBookingId: Int?, fallback: () -> Unit) {
    activeTrip.value = null
    if (closedBookingId == null || !isDriverOf(closedBookingId) || driverRideOverBooking.value == closedBookingId) {
        fallback(); return
    }
    val owner = ApiClient.queueSessionGeneration()
    viewModelScope.launch {
        // Запрос — отдельной задачей: чтение сети отмену не слышит, и срок у дочерней задачи ждал бы его до конца.
        val lookup = async(SupervisorJob()) { driverRideHasLiveBookings(closedBookingId, owner) }
        val remains = withTimeoutOrNull(DRIVER_RIDE_LOOKUP_MS) { lookup.await() }
        if (!ApiClient.isCurrentSession(owner) || screen.value.contentScreen() != Screen.ActiveTrip ||
            activeBookingId.value != closedBookingId) return@launch
        if (remains == false) fallback() else returnToDriverRide()
    }
}

/** Ключ заголовка «Едут с тобой» в списке кабинета — по нему список сам доезжает до раздела. */
internal const val RIDING_HEADER_KEY = "riding-header"

/**
 * Список кабинета, который доезжает до «Едут с тобой», когда водитель вернулся из закрытой поездки.
 * Где раздел, заранее не знаем (над ним долг, такси, маршруты), поэтому листаем плавно, пока заголовок
 * не покажется, и подводим его к верху. [ready] — список броней пришёл и раздел есть.
 */
@Composable
internal fun rememberRidingFocusList(focus: Boolean, ready: Boolean, onDone: () -> Unit): LazyListState {
    val state = rememberLazyListState()
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(focus, ready) {
        if (!focus || !ready) return@LaunchedEffect
        repeat(RIDING_FOCUS_MAX_PAGES) {
            withFrameNanos { }   // список успел разложиться после прошлого шага
            val header = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == RIDING_HEADER_KEY }
            if (header != null) {
                state.animateScrollToItem(header.index)
                done()
                return@LaunchedEffect
            }
            if (!state.canScrollForward) { done(); return@LaunchedEffect }
            state.animateScrollBy(state.layoutInfo.viewportSize.height * 0.8f, tween(CanonMotion.QUICK))
        }
        done()
    }
    return state
}

private const val RIDING_FOCUS_MAX_PAGES = 30

/** Под «Едут с тобой»: честно, что машину сейчас видят пассажиры рейса (P2-3 проверки плана). */
@Composable
internal fun DriverRideShareNote() {
    AnimatedVisibility(visible = TripLocationBus.rideShareActive) {
        Row(
            modifier = Modifier.padding(top = CanonSpace.xs),
            horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
            Text(
                appText(
                    "Машину видят пассажиры этого рейса. Перестанем показывать, когда завершишь последнего.",
                    "Машинаңды был рейстың пассажирҙары күрә. Һуңғыһын тамамлағас, күрһәтмәйбеҙ.",
                ),
                color = CanonMuted, style = CanonCaption,
            )
        }
    }
}

/**
 * «Завершить поездку?» у водителя. Пока сервер не ответил и не пришёл список рейса, окно открыто и
 * кнопка показывает «Завершаем…»: второй тап не уходит, а человек видит, что дело идёт (Д2).
 */
@Composable
internal fun DriverFinishDialog(busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(appText("Завершить поездку?", "Сәфәрҙе тамамларғамы?")) },
        text = {
            Text(appText("Подтверди, что поездка действительно закончилась.", "Сәфәр ысынлап та тамамланғанын раҫла."))
        },
        confirmButton = {
            TextButton(onClick = { if (!busy) onConfirm() }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(end = CanonSpace.sm).size(18.dp), color = CanonGreen2, strokeWidth = 2.dp)
                    Text(appText("Завершаем…", "Тамамлайбыҙ…"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                } else Text(appText("Да, завершить", "Эйе, тамамларға"), color = CanonGreen2, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(appText("Не завершать", "Тамамламаҫҡа"))
            }
        },
    )
}
