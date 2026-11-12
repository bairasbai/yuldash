package com.yuldash.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.CommandIntent
import com.yuldash.app.data.DriverBookingDecisionApi
import com.yuldash.app.data.DriverDecisionAction
import com.yuldash.app.data.DriverDecisionFailure
import com.yuldash.app.data.DriverDecisionOutcome
import com.yuldash.app.data.DriverDecision
import com.yuldash.app.data.OfflineTripSnapshots
import kotlinx.coroutines.launch

internal data class DriverDecisionUi(
    val blocked: (Int) -> Boolean,
    val confirm: (Int) -> Unit,
    val decline: (Int) -> Unit,
    val recovery: (@Composable () -> Unit)?,
)

@Composable
internal fun rememberDriverDecisionRecovery(generation: Long, onResult: () -> Unit): DriverDecisionUi {
    val scope = rememberCoroutineScope()
    val language = LocalAppLanguage.current
    var pending by remember(generation) { mutableStateOf<List<DriverDecision>>(emptyList()) }
    var busy by remember(generation) { mutableStateOf(false) }
    var loadFailed by remember(generation) { mutableStateOf(false) }
    var reload by remember(generation) { mutableIntStateOf(0) }
    var outcome by remember(generation) { mutableStateOf<DriverDecisionOutcome?>(null) }
    var failure by remember(generation) { mutableStateOf<Throwable?>(null) }
    var replyLanguage by remember(generation) { mutableStateOf(language) }

    suspend fun refresh() {
        val saved = DriverBookingDecisionApi.pending(generation)
        if (!ApiClient.isCurrentSession(generation)) return
        saved.onSuccess { pending = it; loadFailed = false }.onFailure { loadFailed = true }
    }

    LaunchedEffect(generation, reload) { refresh() }

    fun run(id: Int, request: suspend () -> Result<DriverDecisionOutcome>) {
        if (busy || !ApiClient.isCurrentSession(generation)) return
        busy = true
        outcome = null
        failure = null
        replyLanguage = language
        val ticket = OfflineTripSnapshots.ticket()?.takeIf { it.owner.generation == generation }
        scope.launch {
            try {
                val result = request()
                if (!ApiClient.isCurrentSession(generation)) return@launch
                result.onSuccess {
                    val status = it.bookingStatus
                    if (status == "done" || status == "cancelled") OfflineTripSnapshots.terminal(ticket, id, status)
                    if (!ApiClient.isCurrentSession(generation)) return@launch
                    outcome = it
                    if (it.state != CommandIntent.State.Unknown) onResult()
                }.onFailure {
                    failure = it
                    if ((it as? DriverDecisionFailure)?.problem == CommandIntent.Problem.Changed) onResult()
                }
                refresh()
            } finally {
                busy = false
            }
        }
    }

    val recovery: (@Composable () -> Unit)? = if (pending.isNotEmpty() || outcome != null || failure != null || loadFailed) {
        {
            Column {
                if (loadFailed) {
                    Text(appText("Не удалось открыть сохранённые решения.", "Һаҡланған ҡарарҙарҙы асып булманы."), style = CanonCaption)
                    TextButton(onClick = { reload++ }, enabled = !busy) {
                        Text(appText("Повторить проверку", "Тикшереүҙе ҡабатлау"))
                    }
                }
                pending.forEach { decision ->
                    val label = if (decision.action == DriverDecisionAction.Confirm) {
                        appText("Подтверждение брони", "Бронды раҫлау")
                    } else appText("Отклонение брони", "Бронды кире ҡағыу")
                    Text("$label №${decision.bookingId}", style = CanonBodyStrong)
                    TextButton(enabled = !busy, onClick = { run(decision.bookingId) {
                        DriverBookingDecisionApi.check(generation, decision, close = false)
                    } }) { Text(appText("Проверить запрос", "Һорауҙы тикшереү")) }
                    TextButton(enabled = !busy, onClick = { run(decision.bookingId) {
                        DriverBookingDecisionApi.repeat(generation, decision)
                    } }) { Text(appText("Повторить тот же запрос", "Шул уҡ һорауҙы ҡабатлау")) }
                    TextButton(enabled = !busy, onClick = { run(decision.bookingId) {
                        DriverBookingDecisionApi.check(generation, decision, close = true)
                    } }) { Text(appText("Закрыть запрос", "Һорауҙы ябыу")) }
                }
                val useServerText = LocalAppLanguage.current == replyLanguage
                outcome?.let { Text(driverDecisionMessage(it, useServerText), style = CanonCaption) }
                failure?.let { Text(driverDecisionError(it, useServerText), style = CanonCaption) }
            }
        }
    } else null
    return DriverDecisionUi(
        blocked = { id -> busy || pending.any { it.bookingId == id } },
        confirm = { id -> run(id) { DriverBookingDecisionApi.decide(generation, id, DriverDecisionAction.Confirm) } },
        decline = { id -> run(id) { DriverBookingDecisionApi.decide(generation, id, DriverDecisionAction.Decline) } },
        recovery = recovery,
    )
}

@Composable
private fun driverDecisionMessage(result: DriverDecisionOutcome, useServerText: Boolean): String = when (result.state) {
    CommandIntent.State.Unknown -> appText("Решение пока неизвестно. Его можно повторить или закрыть.", "Ҡарар әлегә билдәһеҙ. Уны ҡабатларға йәки ябырға мөмкин.")
    CommandIntent.State.Closed -> appText("Запрос закрыт. Можно ответить заново.", "Һорау ябылды. Яңынан яуап бирергә мөмкин.")
    CommandIntent.State.Rejected -> result.rejectionMessage?.takeIf { useServerText }
        ?: appText("Сервер отклонил действие. Проверь статус брони.", "Сервер ғәмәлде кире ҡаҡты. Бронь торошон тикшер.")
    CommandIntent.State.Applied -> when (result.bookingStatus) {
        "confirmed" -> appText("Бронь подтверждена.", "Бронь раҫланды.")
        "cancelled" -> if (result.declined) appText("Бронь отклонена.", "Бронь кире ҡағылды.")
            else appText("Бронь отменена.", "Бронь кире алынды.")
        "onboard" -> appText("Пассажир уже в пути.", "Юлаусы инде юлда.")
        "done" -> appText("Поездка уже завершена.", "Сәфәр инде тамамланған.")
        "pending" -> appText("Бронь ждёт ответа.", "Бронь яуап көтә.")
        else -> appText("Бронь больше недоступна.", "Бронь бүтән асыҡ түгел.")
    }
}

@Composable
private fun driverDecisionError(error: Throwable, useServerText: Boolean): String {
    val failure = error as? DriverDecisionFailure
    return when (failure?.problem) {
        CommandIntent.Problem.Changed -> appText("Статус брони изменился. Проверь список.", "Бронь торошо үҙгәрҙе. Исемлекте тикшер.")
        CommandIntent.Problem.Expired -> appText("Срок повтора истёк. Проверь или закрой запрос.", "Ҡабатлау ваҡыты үтте. Һорауҙы тикшер йәки яп.")
        CommandIntent.Problem.Unknown -> appText("Ответ потерялся. Проверь решение.", "Яуап юғалды. Ҡарарҙы тикшер.")
        else -> {
            val fallback = appText("Не удалось проверить решение. Попробуй ещё раз.", "Ҡарарҙы тикшереп булманы. Тағы ҡабатла.")
            if (useServerText) serverSaid(error, fallback) else fallback
        }
    }
}
