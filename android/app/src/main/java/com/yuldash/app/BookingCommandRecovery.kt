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
import org.json.JSONObject

internal class PreparedBookingRequest(val rideId: Int, val key: String, val wireBody: String)

internal class BookingRecoveryState(val generation: Long) {
    var slot by mutableStateOf<CommandIntent.Slot?>(null)
    var loading by mutableStateOf(false)
    var outcome by mutableStateOf<BookingCommandOutcome?>(null)
    var problem by mutableStateOf<CommandIntent.Problem?>(null)
    var rejectionCode by mutableStateOf<String?>(null)
    var needsFreshTerms by mutableStateOf(false)
        private set
    var refreshingTerms by mutableStateOf(false)
    private var existingBooking by mutableStateOf<BookingCreatedDto?>(null)
    private var preparedRequest by mutableStateOf<PreparedBookingRequest?>(null)
    val knownBooking: BookingCreatedDto? get() = outcome?.booking ?: outcome?.existingBooking ?: existingBooking
    val retry: Boolean get() = slot?.state == CommandIntent.State.Unknown
    val preparedRetry: PreparedBookingRequest? get() =
        preparedRequest?.takeIf { retry && slot?.key == it.key && slot?.rideId == it.rideId }
    val canSubmit: Boolean get() = !loading && !needsFreshTerms &&
        (!retry || slot?.bookingTerms?.forAnother != true || preparedRetry != null) &&
        knownBooking?.status.let { it == null || it == "cancelled" }
    val guardianPhoneRejected: Boolean get() = !loading && when (problem) {
        CommandIntent.Problem.Rejected -> rejectionCode == "guardian_phone_invalid"
        null -> outcome?.let { it.state == CommandIntent.State.Rejected && it.rejectionCode == "guardian_phone_invalid" } == true
        else -> false
    }

    fun apply(result: BookingCommandOutcome) {
        if (result.state == CommandIntent.State.Rejected) requireFreshTerms(result.rejectionCode)
        outcome = result
        existingBooking = null
        problem = null
        slot = slot?.copy(state = result.state)
        if (!retry) preparedRequest = null
    }

    fun failed(error: Throwable, prepared: PreparedBookingRequest? = null) {
        if (error !is CommandIntent.Failure) {
            problem = CommandIntent.Problem.Unknown
            return
        }
        val failedSlot = error.slot
        if (failedSlot != null || error.problem == CommandIntent.Problem.Superseded) slot = failedSlot
        if (error.problem == CommandIntent.Problem.Superseded) {
            existingBooking = null
            outcome = null
        }
        problem = error.problem
        rejectionCode = error.rejectionCode
        if (error.problem == CommandIntent.Problem.Rejected) requireFreshTerms(error.rejectionCode)
        // Only the form that prepared this key can retain its body, and only in memory.
        if (error.problem == CommandIntent.Problem.Unknown && prepared != null && failedSlot != null &&
            failedSlot.key == prepared.key && failedSlot.rideId == prepared.rideId && retry) {
            preparedRequest = prepared
        } else if (error.problem != CommandIntent.Problem.Unknown || preparedRetry == null) {
            preparedRequest = null
        }
        if (error.existingBooking != null) existingBooking = error.existingBooking
    }

    private fun requireFreshTerms(code: String?) {
        if (code in setOf("price_changed", "ride_terms_changed", "seats_not_enough", "booking_traveler_women_only",
            "booking_traveler_no_minors")) requireFreshTerms()
    }

    fun requireFreshTerms() {
        needsFreshTerms = true
    }

    fun freshTermsShown() {
        needsFreshTerms = false
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
                val command = checkNotNull(saved)
                val result = BookingCommandApi.reconcileBookingCommand(generation, command, close = false)
                if (currentScreen()) result.onSuccess(state::apply).onFailure {
                    state.failed(it)
                    // An unverified refusal can still be about stale price or route terms.
                    if (command.state == CommandIntent.State.Rejected) state.requireFreshTerms()
                }
            }
        } finally { state.loading = false }
    }
    return state
}

@Composable
internal fun BookingRecoveryActions(
    state: BookingRecoveryState, busy: Boolean, onOpen: (BookingCreatedDto) -> Unit,
    isCurrent: () -> Boolean = { true },
    onRefreshTerms: (() -> Unit)? = null,
    showGuardianPhoneError: Boolean = true,
    afterCheck: suspend (BookingCommandOutcome?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(isCurrent)
    val slot = state.slot
    val prepared = state.preparedRetry
    val originalSelection = remember(prepared) {
        prepared?.let {
            val payload = JSONObject(it.wireBody)
            val stops = listOf(payload.optString("pickup_stop"), payload.optString("dropoff_stop"))
            payload.optInt("seats") to stops.filter(String::isNotBlank).joinToString(" → ")
        }
    }
    fun active() = current() && ApiClient.isCurrentSession(state.generation)
    fun check(close: Boolean) {
        if (slot == null || state.loading || busy || !active()) return
        state.loading = true
        scope.launch {
            try {
                val result = BookingCommandApi.reconcileBookingCommand(state.generation, slot, close)
                    .onSuccess { if (active()) state.apply(it) }
                    .onFailure { if (active()) state.failed(it) }
                if (active()) afterCheck(result.getOrNull())
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
            if (message != null && (showGuardianPhoneError || !state.guardianPhoneRejected)) {
                Text(message, color = CanonText, style = CanonBody)
            }
            if (state.needsFreshTerms && onRefreshTerms != null) {
                TextButton(onClick = { if (active()) onRefreshTerms() },
                    modifier = Modifier.heightIn(min = 48.dp), enabled = !busy && !state.refreshingTerms) {
                    Text(appText("Повторить", "Ҡабатлау"), color = CanonGreen, style = CanonBody)
                }
            }
            originalSelection?.let { (seats, route) ->
                if (route.isNotBlank()) Text(route, color = CanonText, style = CanonBody)
                Text(appText("Количество мест", "Урындар һаны") + " · " + seats, color = CanonText, style = CanonBody)
            }
            state.knownBooking?.let { booking ->
                TextButton(onClick = { ApiClient.runIfCurrentSession(state.generation) { if (active()) onOpen(booking) } },
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
        result.existingBooking?.let { booking ->
            TextButton(onClick = { ApiClient.runIfCurrentSession(pending.generation) { onOpen(booking) } }) {
                Text(appText("Открыть свою бронь", "Үҙ бронеңде асыу"), color = CanonGreen, style = CanonBody)
            }
        }
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
internal fun guardianPhoneBookingError(): String =
    appText("Проверь номер телефона взрослого.", "Оло кешенең телефон номерын тикшер.")

@Composable
private fun bookingCommandMessage(problem: CommandIntent.Problem, code: String? = null): String = if (
    code == "booking_traveler_unsupported"
) appText("Сервер пока не поддерживает бронь за другого. Попробуй позже.",
    "Сервер әлегә башҡа кеше өсөн бронлауҙы ҡабул итмәй. Һуңыраҡ ҡабатлап ҡара.") else when (problem) {
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
        "guardian_phone_invalid" -> guardianPhoneBookingError()
        "booking_traveler_conflict" -> appText("У тебя уже есть бронь с другими данными пассажира.",
            "Һинең юлсы мәғлүмәттәре икенсе булған бронең бар.")
        "booking_traveler_women_only" -> appText("В поездку «Только женщины» можно бронировать место только для себя.",
            "«Тик ҡатын-ҡыҙ» сәфәренә урынды үҙең өсөн генә бронларға була.")
        "booking_traveler_no_minors" -> appText("Водитель не берёт пассажиров младше 18 без взрослого рядом.",
            "Йөрөтөүсе оло кешеһеҙ 18-ҙән кесе юлсыларҙы алмай.")
        else -> appText("Сервер отклонил запрос на бронь.", "Сервер бронлау һорауын кире ҡаҡты.")
    }
    else -> appText("Не удалось подтвердить результат. Проверим, что сохранилось.",
        "Һөҙөмтәне раҫлап булманы. Нимә һаҡланғанын тикшерәйек.")
}
