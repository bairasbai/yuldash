package com.yuldash.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.CommandIntent
import com.yuldash.app.data.DriverStageApi
import com.yuldash.app.data.Outbox
import com.yuldash.app.data.TripStateDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class DriverStageRecoveryState {
    var rows by mutableStateOf<List<DriverStageApi.Intent>>(emptyList())
    var oldQueue by mutableStateOf<Set<Int>>(emptySet())
    var readFailed by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    var reload by mutableIntStateOf(0)
    var feedback by mutableStateOf<DriverStageApi.Outcome?>(null)
    var error by mutableStateOf<Throwable?>(null)
    val visible: Boolean get() = readFailed || rows.isNotEmpty() || oldQueue.isNotEmpty() || feedback != null || error != null
    fun blocked(bookingId: Int): Boolean = !loaded || busy || rows.any { it.slot.rideId == bookingId }
}

@Composable
internal fun rememberDriverStageRecovery(generation: Long, enabled: Boolean, bookingId: Int? = null): DriverStageRecoveryState {
    val state = remember(generation, bookingId) { DriverStageRecoveryState() }
    val context = LocalContext.current.applicationContext
    val queueVersion = Outbox.version.value
    val stageVersion by DriverStageApi.revision.collectAsState()
    LaunchedEffect(state, enabled, queueVersion) {
        if (enabled) state.oldQueue = Outbox.staleDriverStages(context, generation).filter { bookingId == null || it == bookingId }.toSet()
    }
    LaunchedEffect(state, enabled, state.reload, stageVersion) {
        if (!enabled) return@LaunchedEffect
        val saved = DriverStageApi.pending(generation, bookingId)
        if (!ApiClient.isCurrentSession(generation)) return@LaunchedEffect
        saved.onSuccess {
            state.rows = it
            state.loaded = true
            state.readFailed = false
        }.onFailure {
            state.loaded = false
            state.readFailed = true
        }
    }
    return state
}

@Composable
internal fun DriverStageRecovery(
    generation: Long,
    state: DriverStageRecoveryState,
    operationScope: CoroutineScope? = null,
    onChanged: suspend (Int, TripStateDto) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val gps = rememberDriverGpsAction(null)
    fun run(intent: DriverStageApi.Intent, repeat: Boolean = false, action: suspend () -> Result<DriverStageApi.Outcome>) {
        if (state.busy || !ApiClient.isCurrentSession(generation)) return
        state.busy = true
        state.feedback = null
        state.error = null
        val ticket = if (repeat) gps.begin(intent.slot.rideId) else null
        (operationScope ?: scope).launch {
            try {
                val outcome = action()
                if (!ApiClient.isCurrentSession(generation)) return@launch
                val trip = outcome.getOrNull()?.trip
                val terminal = trip?.status in setOf("done", "cancelled")
                gps.finish(ticket, trip?.status == "onboard" || terminal, closed = terminal)
                if (!ApiClient.isCurrentSession(generation)) return@launch
                outcome.onSuccess { result ->
                    result.trip?.let {
                        if (it.status == "done" || it.status == "cancelled") DriverGpsActions.closed(intent.slot.rideId)
                        onChanged(intent.slot.rideId, it)
                    }
                    state.feedback = result
                }.onFailure { state.error = it }
                state.reload++
            } finally {
                DriverGpsActions.fail(ticket)
                state.busy = false
            }
        }
    }

    Column {
        state.oldQueue.forEach { id ->
            Text(appText("Старую отметку по брони №$id не удалось подтвердить.", "№$id брондең элекке билдәһе раҫланманы."), style = CanonCaption)
            StageButton(appText("Понятно", "Аңлашылды"), !state.busy) {
                if (!Outbox.dismissStaleDriverStage(context, id, generation)) state.error = IllegalStateException()
            }
        }
        if (state.readFailed) {
            Text(appText("Не удалось прочитать сохранённые отметки.", "Һаҡланған билдәләрҙе уҡып булманы."), style = CanonCaption)
            StageButton(appText("Повторить проверку", "Тикшереүҙе ҡабатлау"), !state.busy) { state.reload++ }
        }
        state.rows.forEach { intent ->
            val label = when (intent.stage) {
                DriverStageApi.Stage.Boarded -> appText("Отметка о посадке", "Ултырыу билдәһе")
                DriverStageApi.Stage.Done -> appText("Отметка о завершении", "Тамамлау билдәһе")
            }
            Text("$label №${intent.slot.rideId}", style = CanonBodyStrong)
            StageButton(appText("Проверить", "Тикшереү"), !state.busy) {
                run(intent) { DriverStageApi.check(generation, intent, close = false) }
            }
            if (System.currentTimeMillis() - intent.slot.createdAt in 0L until 24 * 60 * 60 * 1000L) {
                StageButton(appText("Повторить отправку", "Ебәреүҙе ҡабатлау"), !state.busy) {
                    run(intent, repeat = true) { DriverStageApi.repeat(generation, intent) }
                }
            }
            StageButton(appText("Закрыть запрос", "Һорауҙы ябыу"), !state.busy) {
                run(intent) { DriverStageApi.check(generation, intent, close = true) }
            }
        }
        state.feedback?.let { response ->
            val text = when (response.state) {
                CommandIntent.State.Applied -> if (response.code == "idempotency_result_gone")
                    appText("Отметка принята. Бронь больше недоступна.", "Билдә ҡабул ителде. Бронь бүтән асылмай.")
                else appText("Статус поездки проверен.", "Сәфәр торошо тикшерелде.")
                CommandIntent.State.Unknown -> appText("Результат пока неизвестен.", "Һөҙөмтә әлегә билдәһеҙ.")
                CommandIntent.State.Rejected -> appText("Сервер отклонил отметку. Проверь поездку.", "Сервер билдәне кире ҡаҡты. Сәфәрҙе тикшер.")
                CommandIntent.State.Closed -> appText("Запрос закрыт. Можно отправить отметку заново.", "Һорау ябылды. Билдәне яңынан ебәрергә мөмкин.")
            }
            Text(text, style = CanonCaption)
        }
        state.error?.let { error -> Text(driverStageError(error), style = CanonCaption) }
    }
}

@Composable
private fun StageButton(label: String, enabled: Boolean, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = CanonSpace.huge)) {
        Text(label)
    }
}

@Composable
internal fun driverStageError(error: Throwable): String = when ((error as? CommandIntent.Failure)?.problem) {
    CommandIntent.Problem.Unknown -> appText("Ответ не пришёл. Проверь, сохранилась ли отметка.", "Яуап килмәне. Билдә һаҡланғанмы, тикшер.")
    CommandIntent.Problem.Expired -> appText("Срок повтора истёк. Проверь или закрой запрос.", "Ҡабатлау ваҡыты үтте. Һорауҙы тикшер йәки яп.")
    else -> appText("Не получилось проверить отметку. Попробуй ещё раз.", "Билдәне тикшереп булманы. Тағы ҡабатла.")
}
