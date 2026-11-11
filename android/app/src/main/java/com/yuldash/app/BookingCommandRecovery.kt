package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.BookingCommandApi
import com.yuldash.app.data.BookingCommandOutcome
import com.yuldash.app.data.BookingCreatedDto
import com.yuldash.app.data.CommandIntent
import kotlinx.coroutines.launch

internal class BookingRecoveryState(val generation: Long) {
    var slot by mutableStateOf<CommandIntent.Slot?>(null)
    var loading by mutableStateOf(false)
    var outcome by mutableStateOf<BookingCommandOutcome?>(null)
    var problem by mutableStateOf<CommandIntent.Problem?>(null)
    var rejectionCode by mutableStateOf<String?>(null)
    private var existingBooking by mutableStateOf<BookingCreatedDto?>(null)
    val knownBooking: BookingCreatedDto? get() = outcome?.booking ?: existingBooking
    val retry: Boolean get() = slot?.state == CommandIntent.State.Unknown
    val canSubmit: Boolean get() = !loading && knownBooking?.status.let { it == null || it == "cancelled" }

    fun apply(result: BookingCommandOutcome) {
        outcome = result
        problem = null
        slot = slot?.copy(state = result.state)
    }

    fun failed(error: Throwable) {
        if (error !is CommandIntent.Failure) {
            problem = CommandIntent.Problem.Unknown
            return
        }
        if (error.slot != null) slot = error.slot
        problem = error.problem
        rejectionCode = error.rejectionCode
        if (error.existingBooking != null) existingBooking = error.existingBooking
    }
}

@Composable
internal fun rememberBookingRecovery(rideId: Int?, generation: Long, current: () -> Boolean): BookingRecoveryState {
    val state = remember(rideId, generation) { BookingRecoveryState(generation) }
    val currentScreen by rememberUpdatedState(current)
    LaunchedEffect(rideId, generation, current()) {
        if (rideId == null || !currentScreen()) return@LaunchedEffect
        state.loading = true
        try {
            val saved = BookingCommandApi.savedBookingCommands(generation).getOrElse { error ->
                if (currentScreen()) {
                    state.problem = CommandIntent.Problem.Unknown
                    state.failed(error)
                }
                return@LaunchedEffect
            }.firstOrNull { it.rideId == rideId }
            if (!currentScreen()) return@LaunchedEffect
            state.slot = saved
            if (saved?.state in setOf(CommandIntent.State.Unknown, CommandIntent.State.Applied, CommandIntent.State.Rejected)) {
                val result = BookingCommandApi.reconcileBookingCommand(generation, checkNotNull(saved), close = false)
                if (currentScreen()) result.onSuccess(state::apply).onFailure(state::failed)
            }
        } finally { state.loading = false }
    }
    return state
}

@Composable
internal fun BookingRecoveryActions(
    state: BookingRecoveryState, busy: Boolean, onOpen: (BookingCreatedDto) -> Unit,
    afterCheck: suspend (BookingCommandOutcome?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val slot = state.slot
    fun check(close: Boolean) {
        if (slot == null || state.loading || busy || !ApiClient.isCurrentSession(state.generation)) return
        state.loading = true
        scope.launch {
            try {
                val result = BookingCommandApi.reconcileBookingCommand(state.generation, slot, close)
                    .onSuccess { if (ApiClient.isCurrentSession(state.generation)) state.apply(it) }
                    .onFailure { if (ApiClient.isCurrentSession(state.generation)) state.problem = CommandIntent.Problem.Unknown }
                if (ApiClient.isCurrentSession(state.generation)) afterCheck(result.getOrNull())
            } finally { state.loading = false }
        }
    }
    AnimatedVisibility(state.retry || state.outcome != null || state.problem != null || state.loading) {
        Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.xs), modifier = Modifier.fillMaxWidth()) {
            val result = state.outcome
            val message = when {
                state.loading -> appText("Проверяем запрос…", "Һорауҙы тикшерәбеҙ…")
                state.problem != null -> bookingCommandMessage(checkNotNull(state.problem), state.rejectionCode)
                result?.state == CommandIntent.State.Rejected -> bookingCommandMessage(CommandIntent.Problem.Rejected, result.rejectionCode)
                result?.state == CommandIntent.State.Closed -> bookingCommandMessage(CommandIntent.Problem.Closed)
                result?.state == CommandIntent.State.Applied && result.booking == null -> bookingCommandMessage(CommandIntent.Problem.Gone)
                state.retry -> bookingCommandMessage(CommandIntent.Problem.Unknown)
                else -> null
            }
            if (message != null) Text(message, color = CanonText, style = CanonBody)
            state.knownBooking?.let { booking ->
                TextButton(onClick = { ApiClient.runIfCurrentSession(state.generation) { onOpen(booking) } },
                    modifier = Modifier.heightIn(min = 48.dp), enabled = !busy && !state.loading) {
                    Text(appText("Открыть бронь", "Бронде асыу"), color = CanonGreen, style = CanonBody)
                }
            }
            if (state.retry) {
                TextButton(onClick = { check(false) }, modifier = Modifier.heightIn(min = 48.dp), enabled = !busy && !state.loading) {
                    Text(appText("Проверить запрос", "Һорауҙы тикшереү"), color = CanonGreen, style = CanonBody)
                }
                TextButton(onClick = { check(true) }, modifier = Modifier.heightIn(min = 48.dp), enabled = !busy && !state.loading) {
                    Text(appText("Закрыть запрос", "Һорауҙы ябыу"), color = CanonGreen, style = CanonBody)
                }
                Text(appText("Если бронь уже создана, покажем её.", "Бронь яһалған булһа, уны күрһәтербеҙ."),
                    color = CanonMuted, style = CanonBody)
            }
        }
    }
}

@Composable
internal fun rememberPendingBookingCommands(generation: Long): PendingBookingCommands {
    val pending = remember(generation) { PendingBookingCommands(generation) }
    LaunchedEffect(generation) { pending.refresh() }
    return pending
}

internal class PendingBookingCommands(val generation: Long) {
    var rows by mutableStateOf(emptyList<CommandIntent.Slot>())
        private set
    var lastOutcome by mutableStateOf<BookingCommandOutcome?>(null)

    suspend fun refresh() {
        if (!ApiClient.isCurrentSession(generation)) return
        BookingCommandApi.savedBookingCommands(generation).onSuccess { rows ->
            if (ApiClient.isCurrentSession(generation)) this.rows = rows.filter { it.state == CommandIntent.State.Unknown }
        }
    }
}

internal fun LazyListScope.pendingBookingItems(
    pending: PendingBookingCommands, onOpen: (BookingCreatedDto) -> Unit,
) {
    pending.lastOutcome?.let { result -> item("pending-booking-result") {
        val problem = when (result.state) {
            CommandIntent.State.Closed -> CommandIntent.Problem.Closed
            CommandIntent.State.Rejected -> CommandIntent.Problem.Rejected
            else -> CommandIntent.Problem.Gone
        }
        Text(bookingCommandMessage(problem, result.rejectionCode), color = CanonText, style = CanonBody)
    } }
    if (pending.rows.isNotEmpty()) item("pending-booking-title") {
        Text(appText("Незавершённые запросы на бронь", "Тамамланмаған бронлау һорауҙары"),
            color = CanonText, style = CanonTitle)
    }
    items(pending.rows, key = { "pending-booking-${it.key}" }) { slot ->
        val state = remember(slot.key, pending.generation) { BookingRecoveryState(pending.generation).also { it.slot = slot } }
        Column {
            Text(appText("Поездка №${slot.rideId}", "Сәфәр №${slot.rideId}"), color = CanonText, style = CanonBody)
            BookingRecoveryActions(state, busy = false, onOpen = onOpen, afterCheck = { result ->
                if (result?.booking != null) ApiClient.runIfCurrentSession(pending.generation) { onOpen(result.booking) }
                else if (result != null && result.state != CommandIntent.State.Unknown) pending.lastOutcome = result
                pending.refresh()
            })
        }
    }
}

@Composable
private fun bookingCommandMessage(problem: CommandIntent.Problem, code: String? = null): String = when (problem) {
    CommandIntent.Problem.Storage -> appText("Не удалось сохранить запрос на этом устройстве.", "Был ҡоролмала һорауҙы һаҡлап булманы.")
    CommandIntent.Problem.Full -> appText("Сначала проверь незавершённые запросы в «Моих поездках».",
        "Тәүҙә «Минең сәфәрҙәр»ҙә тамамланмаған һорауҙарҙы тикшер.")
    CommandIntent.Problem.Changed -> appText("Данные изменились. Проверь или закрой предыдущий запрос.",
        "Мәғлүмәт үҙгәрҙе. Алдағы һорауҙы тикшер йәки яп.")
    CommandIntent.Problem.Expired -> appText("Срок повтора истёк или время изменилось. Проверь запрос.",
        "Ҡабатлау ваҡыты үтте йәки ваҡыт үҙгәрҙе. Һорауҙы тикшер.")
    CommandIntent.Problem.Gone -> appText("Результат бронирования больше недоступен.", "Бронлау һөҙөмтәһе бүтән асыҡ түгел.")
    CommandIntent.Problem.Closed -> appText("Запрос закрыт. Бронь по нему не создана.", "Һорау ябылды. Уның буйынса бронь яһалманы.")
    CommandIntent.Problem.Rejected -> when (code) {
        "price_changed" -> appText("Цена поездки изменилась.", "Сәфәр хаҡы үҙгәрҙе.")
        "ride_terms_changed" -> appText("Условия поездки изменились.", "Сәфәр шарттары үҙгәрҙе.")
        "seats_not_enough" -> appText("На этом участке не хватило мест.", "Был участкала урындар етмәне.")
        "front_seat_taken" -> appText("Переднее место уже занято.", "Алғы урын инде алынған.")
        else -> appText("Сервер отклонил запрос на бронь.", "Сервер бронлау һорауын кире ҡаҡты.")
    }
    else -> appText("Не удалось подтвердить результат. Проверим, что сохранилось.",
        "Һөҙөмтәне раҫлап булманы. Нимә һаҡланғанын тикшерәйек.")
}
