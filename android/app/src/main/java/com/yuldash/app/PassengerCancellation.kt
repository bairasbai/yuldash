package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.yuldash.app.data.Analytics
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.BookingDecision
import com.yuldash.app.data.BookingDecisionApi
import com.yuldash.app.data.BookingDecisionFailure
import com.yuldash.app.data.BookingDecisionOutcome
import com.yuldash.app.data.CommandIntent
import com.yuldash.app.data.PassengerCancellationTerms
import kotlinx.coroutines.launch

internal data class PassengerCancellationUi(
    val open: () -> Unit,
    val blocked: Boolean,
    val content: (@Composable () -> Unit)?,
)

@Composable
internal fun rememberPassengerCancellation(
    generation: Long,
    bookingId: Int? = null,
    status: String? = null,
    isCurrent: () -> Boolean = { true },
    onChanged: (BookingDecisionOutcome?) -> Unit,
): PassengerCancellationUi {
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(isCurrent)
    val changed by rememberUpdatedState(onChanged)
    val liveStatus by rememberUpdatedState(status)
    var pending by remember(generation, bookingId) { mutableStateOf<List<BookingDecision>>(emptyList()) }
    var busy by remember(generation, bookingId) { mutableStateOf(false) }
    var loadError by remember(generation, bookingId) { mutableStateOf(false) }
    var reload by remember(generation, bookingId) { mutableIntStateOf(0) }
    var open by remember(generation, bookingId) { mutableStateOf(false) }
    var terms by remember(generation, bookingId) { mutableStateOf<PassengerCancellationTerms?>(null) }
    var reason by remember(generation, bookingId) { mutableStateOf("") }
    var outcome by remember(generation, bookingId) { mutableStateOf<BookingDecisionOutcome?>(null) }
    var error by remember(generation, bookingId) { mutableStateOf<Throwable?>(null) }

    fun active() = ApiClient.isCurrentSession(generation) && current()

    suspend fun refresh() {
        if (!active()) return
        val saved = BookingDecisionApi.pending(generation, passenger = true)
        if (!active()) return
        saved.onSuccess { rows ->
            pending = rows.filter { bookingId == null || it.bookingId == bookingId }
            loadError = false
        }.onFailure { loadError = true }
    }
    LaunchedEffect(generation, bookingId, reload) { refresh() }
    LaunchedEffect(status) {
        if (status == null || terms?.status?.let { it != status } == true) open = false
    }

    fun <T> run(request: suspend () -> Result<T>, accept: (T) -> Unit) {
        if (busy || !active()) return
        busy = true
        outcome = null
        error = null
        scope.launch {
            try {
                if (!active()) return@launch
                val result = request()
                if (!active()) return@launch
                result.onSuccess(accept).onFailure {
                    error = it
                    when ((it as? BookingDecisionFailure)?.problem) {
                        CommandIntent.Problem.Changed -> { open = false; changed(null) }
                        CommandIntent.Problem.Unknown -> open = false
                        else -> Unit
                    }
                }
                refresh()
            } finally { busy = false }
        }
    }

    fun prepare() {
        if (busy || pending.isNotEmpty() || !active()) return
        val id = bookingId ?: return
        val stage = liveStatus ?: return
        open = true
        terms = null
        reason = ""
        run({ BookingDecisionApi.preparePassengerCancellation(generation, id, stage) }) {
            if (open && liveStatus == it.status) terms = it
            else if (liveStatus != it.status) { open = false; changed(null) }
        }
    }

    fun apply(value: BookingDecisionOutcome, submitted: Boolean = false) {
        outcome = value
        if (value.state == CommandIntent.State.Unknown) return
        if (submitted && value.bookingStatus == "cancelled") Analytics.log("booking_cancel")
        changed(value)
    }

    if (open && status != null) {
        val preview = terms?.takeIf { it.status == status }
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = CanonSurface,
            shape = CanonCardShape,
            title = {
                Text(if (status == "pending") appText("Отменить бронь?", "Бронде кире алырғамы?")
                    else appText("Отменить поездку?", "Сәфәрҙе кире аларғамы?"))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                    Text(when (preview?.consequence) {
                        "driver_departed" -> appText("Водитель уже в пути. При отмене он получит уведомление, а место освободится.", "Йөрөтөүсе инде юлда. Кире алһаң, уға хәбәр барасаҡ һәм урын бушаясаҡ.")
                        "late" -> appText("До выезда осталось мало времени. При отмене водитель получит уведомление, а место освободится.", "Юлға сығырға аҙ ваҡыт ҡалды. Кире алһаң, йөрөтөүсе хәбәр аласаҡ һәм урын бушаясаҡ.")
                        "early" -> appText("Место освободится, а водитель получит уведомление.", "Урын бушаясаҡ, ә йөрөтөүсе хәбәр аласаҡ.")
                        else -> error?.let { passengerCancellationError(it) }
                            ?: appText("Проверяем условия отмены…", "Кире алыу шарттарын тикшерәбеҙ…")
                    })
                    if (preview?.status == "confirmed") {
                        Text(appText("Причина (по желанию)", "Сәбәбе (теләгәнсә)"), color = CanonMuted, style = CanonCaption)
                        listOf(
                            "plans_changed" to appText("Планы поменялись", "Пландар үҙгәрҙе"),
                            "found_other" to appText("Нашёл другой вариант", "Башҡа юл таптым"),
                            "driver_no_response" to appText("Водитель не отвечает", "Йөрөтөүсе яуап бирмәй"),
                        ).forEach { (code, label) ->
                            FilterChip(selected = reason == code, onClick = { reason = if (reason == code) "" else code },
                                label = { Text(label) }, enabled = !busy)
                        }
                    }
                }
            },
            confirmButton = {
                if (preview != null) TextButton(enabled = !busy, onClick = {
                    if (liveStatus != preview.status || !active() || busy) return@TextButton
                    val chosen = reason
                    open = false
                    run({ BookingDecisionApi.cancelPassenger(generation, preview, chosen) }) { apply(it, submitted = true) }
                }) { Text(appText("Да, отменить", "Эйе, кире алырға"), color = CanonRed) }
                else if (error != null) TextButton(enabled = !busy, onClick = ::prepare) {
                    Text(appText("Повторить проверку", "Тикшереүҙе ҡабатлау"))
                }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(appText("Оставить бронь", "Бронде ҡалдырырға")) } },
        )
    }

    val content: (@Composable () -> Unit)? = if (pending.isNotEmpty() || loadError || outcome != null || (error != null && !open)) {
        {
            Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                if (loadError) {
                    Text(appText("Не удалось открыть сохранённые запросы отмены.", "Кире алыуҙың һаҡланған һорауҙарын асып булманы."), style = CanonCaption)
                    TextButton(onClick = { reload++ }, enabled = !busy) {
                        Text(appText("Повторить проверку", "Тикшереүҙе ҡабатлау"))
                    }
                }
                pending.forEach { row ->
                    val label = appText("Запрос отмены брони", "Бронды кире алыу һорауы")
                    val id = row.bookingId
                    Text("$label №$id", style = CanonBodyStrong)
                    TextButton(enabled = !busy, onClick = { run({ BookingDecisionApi.check(generation, row, false) }) { apply(it) } }) {
                        Text(appText("Проверить запрос", "Һорауҙы тикшереү"))
                    }
                    TextButton(enabled = !busy, onClick = { run({ BookingDecisionApi.repeat(generation, row) }) { apply(it) } }) {
                        Text(appText("Повторить тот же запрос", "Шул уҡ һорауҙы ҡабатлау"))
                    }
                    TextButton(enabled = !busy, onClick = { run({ BookingDecisionApi.check(generation, row, true) }) { apply(it) } }) {
                        Text(appText("Закрыть запрос", "Һорауҙы ябыу"))
                    }
                }
                if (!open) error?.let { Text(passengerCancellationError(it), style = CanonCaption) }
                outcome?.let { Text(passengerCancellationMessage(it), style = CanonCaption) }
            }
        }
    } else null
    return PassengerCancellationUi(::prepare, busy || pending.isNotEmpty(), content)
}

@Composable
private fun passengerCancellationError(error: Throwable): String = when ((error as? BookingDecisionFailure)?.problem) {
    CommandIntent.Problem.Changed -> appText("Статус брони изменился. Проверь поездку.", "Бронь торошо үҙгәрҙе. Сәфәрҙе тикшер.")
    CommandIntent.Problem.Unknown -> appText("Результат отмены пока неизвестен. Проверь запрос.", "Кире алыу һөҙөмтәһе әлегә билдәһеҙ. Һорауҙы тикшер.")
    CommandIntent.Problem.Expired -> appText("Срок повтора истёк. Проверь или закрой запрос.", "Ҡабатлау ваҡыты үтте. Һорауҙы тикшер йәки яп.")
    else -> appText("Не получилось проверить отмену. Попробуй ещё раз.", "Кире алыуҙы тикшереп булманы. Тағы ҡабатла.")
}

@Composable
private fun passengerCancellationMessage(outcome: BookingDecisionOutcome): String = when (outcome.state) {
    CommandIntent.State.Unknown -> appText("Сервер ещё не видит запрос. Его можно повторить или закрыть.", "Сервер һорауҙы әлегә күрмәй. Уны ҡабатларға йәки ябырға мөмкин.")
    CommandIntent.State.Closed -> appText("Запрос закрыт. Можно начать отмену заново.", "Һорау ябылды. Кире алыуҙы яңынан башларға мөмкин.")
    CommandIntent.State.Rejected -> if (outcome.rejectionCode == "cancel_terms_changed")
        appText("Условия отмены изменились. Открой отмену заново.", "Кире алыу шарттары үҙгәрҙе. Кире алыуҙы яңынан ас.")
    else appText("Отмена отклонена. Проверь текущий статус брони.", "Кире алыу кире ҡағылды. Бронь торошон тикшер.")
    CommandIntent.State.Applied -> when (outcome.bookingStatus) {
        "done" -> appText("Поездка уже завершена.", "Сәфәр инде тамамланған.")
        "cancelled" -> if (outcome.contactThenCancel)
            appText("Поездка отменена. Если договорились ехать, заверши поездку в приложении, чтобы работали защита и SOS.", "Сәфәр кире алынды. Барырға һөйләшкән булһаң, яҡлау һәм SOS эшләһен өсөн сәфәрҙе ҡушымтала тамамла.")
        else appText("Бронь отменена.", "Бронь кире алынды.")
        else -> appText("Обнови поездки, чтобы увидеть текущий статус.", "Ағымдағы торошто күрер өсөн сәфәрҙәрҙе яңырт.")
    }
}
