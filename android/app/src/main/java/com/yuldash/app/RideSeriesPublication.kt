package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.CommandIntent
import com.yuldash.app.data.RidePublicationApi
import com.yuldash.app.data.RideSeriesIntent
import com.yuldash.app.data.RideSeriesStore
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class RideSeriesUi(
    val ready: Boolean, val busy: Boolean, val slot: RideSeriesStore.Slot?, val error: String?,
    val publish: () -> Unit, val reload: () -> Unit, val check: () -> Unit, val repeat: () -> Unit,
    val close: (String) -> Unit, val replace: (Set<String>, List<String>) -> Unit, val finish: () -> Unit,
)

@Composable
internal fun rememberRideSeries(
    bodyForDraft: () -> JSONObject,
    departures: List<String>,
    stopPriceError: String,
    onPublicationResolved: () -> Unit,
    onPublished: () -> Unit,
): RideSeriesUi {
    val generation = ApiClient.queueSessionGeneration()
    val scope = rememberCoroutineScope()
    val language = LocalAppLanguage.current
    val failureText = appText("Не удалось завершить запрос. Проверь результат.",
        "Һорауҙы тамамлап булманы. Һөҙөмтәне тикшер.")
    val stopErrors = rideStopErrorMessages()
    var slot by remember(generation) { mutableStateOf<RideSeriesStore.Slot?>(null) }
    var busy by remember(generation) { mutableStateOf(false) }
    var ready by remember(generation) { mutableStateOf(false) }
    var read by remember(generation) { mutableIntStateOf(0) }
    var error by remember(generation) { mutableStateOf<String?>(null) }

    fun display(saved: RideSeriesStore.Slot?) {
        val oldIds = slot?.rows.orEmpty().mapNotNull { it.result.rideId }.toSet()
        slot = saved
        if (saved?.rows.orEmpty().any { it.result.rideId != null && it.result.rideId !in oldIds }) onPublicationResolved()
    }

    suspend fun recoverState() {
        val saved = RidePublicationApi.savedSeries(generation).getOrThrow()
        if (ApiClient.isCurrentSession(generation)) display(saved)
    }

    LaunchedEffect(generation, read) {
        busy = true
        ready = false
        error = null
        try {
            recoverState()
            if (!ApiClient.isCurrentSession(generation)) return@LaunchedEffect
            ready = true
            val prior = slot
            if (prior != null && prior.pending.isNotEmpty()) {
                val checked = RidePublicationApi.checkSeries(generation, prior.key)
                recoverState()
                checked.getOrThrow()
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (ApiClient.isCurrentSession(generation)) error = failureText
        } finally {
            busy = false
        }
    }

    fun run(action: suspend () -> Unit) {
        if (busy || !ready) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
                recoverState()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (ApiClient.isCurrentSession(generation)) {
                    error = if (failure is RideStopException) stopErrors.getValue(failure.problem) else failureText
                    runCatching { recoverState() }
                }
            } finally {
                busy = false
            }
        }
    }

    return RideSeriesUi(ready, busy, slot, error,
        publish = {
            if (slot == null && departures.size >= 2) run publish@{
                val body = bodyForDraft()
                if (rideCategoryClosed(body.getString("category"))) return@publish
                if (!prepareRidePublicationBody(body, language)) {
                    error = stopPriceError
                    return@publish
                }
                val intent = RideSeriesIntent.create(body, departures.map { publicationDeparture(it) })
                val saved = RidePublicationApi.reserveSeries(generation, intent).getOrThrow()
                RidePublicationApi.sendSeries(generation, saved.key).getOrThrow()
            }
        },
        reload = { if (!busy) read++ },
        check = { slot?.let { run { RidePublicationApi.checkSeries(generation, it.key).getOrThrow() } } },
        repeat = { slot?.let { run { RidePublicationApi.sendSeries(generation, it.key).getOrThrow() } } },
        close = { date -> slot?.let { run { RidePublicationApi.checkSeries(generation, it.key, date).getOrThrow() } } },
        replace = { rejected, dates ->
            slot?.let {
                run {
                    val replacement = RidePublicationApi.replaceSeriesDates(generation, it.key, rejected,
                        dates.map { date -> publicationDeparture(date) }).getOrThrow()
                    RidePublicationApi.sendSeries(generation, replacement.key).getOrThrow()
                }
            }
        },
        finish = {
            slot?.let { finished ->
                run {
                    RidePublicationApi.releaseSeries(generation, finished.key).getOrThrow()
                    if (ApiClient.isCurrentSession(generation) && finished.rows.any { it.result.rideId != null }) onPublished()
                }
            }
        },
    )
}

@Composable
internal fun RideSeriesResults(series: RideSeriesUi) {
    val slot = series.slot
    if (slot == null) {
        if (!series.ready) {
            if (series.busy) CircularProgressIndicator(modifier = Modifier.size(CanonSpace.xl), color = CanonGreen2)
            else TextButton(onClick = series.reload) { Text(appText("Проверить публикацию", "Баҫтырыуҙы тикшерергә")) }
        }
        return
    }
    val ba = LocalAppLanguage.current == AppLanguage.Ba
    val pending = slot.pending.isNotEmpty()
    var replaced by remember(slot.key) { mutableStateOf(setOf<String>()) }
    var closeDate by remember(slot.key) { mutableStateOf<String?>(null) }
    var picking by remember(slot.key) { mutableStateOf(false) }
    val applied = slot.rows.count { it.result.state == CommandIntent.State.Applied }
    Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Text(appText("Опубликовано $applied из ${slot.rows.size}", "${slot.rows.size} көндөң $applied баҫтырылды"),
                style = CanonTitle, color = CanonText, modifier = Modifier.weight(1f))
            if (series.busy) CircularProgressIndicator(modifier = Modifier.size(CanonSpace.xl), color = CanonGreen2)
        }
        slot.rows.forEach { row ->
            val result = row.result
            val departs = OffsetDateTime.parse(row.date.departureUtc).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
            val stamp = formatRideDeparture(departs.toLocalDate(), departs.toLocalTime())
            val description = when (result.state) {
                CommandIntent.State.Applied -> when {
                    result.code == "idempotency_result_gone" -> appText("Поездка была опубликована. Карточка недоступна.",
                        "Сәфәр баҫтырылған ине. Карточка асыҡ түгел.")
                    result.already -> appText("Уже опубликовано", "Элек баҫтырылған")
                    else -> appText("В ленте", "Таҫмала")
                }
                CommandIntent.State.Closed -> appText("Запрос закрыт", "Һорау ябылды")
                CommandIntent.State.Rejected -> (if (ba) result.ba else result.ru).takeUnless { it.isNullOrBlank() }
                    ?: appText("Поездку не опубликовали", "Сәфәр баҫтырылманы")
                CommandIntent.State.Unknown -> appText("Результат пока неизвестен", "Һөҙөмтә әле билдәһеҙ")
            }
            val utc = row.date.departureUtc
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stamp, style = CanonButton, color = CanonText)
                    Text(description, style = CanonCaption, color = CanonMutedStrong)
                }
                if (result.state == CommandIntent.State.Unknown) {
                    TextButton(onClick = { closeDate = utc }, enabled = !series.busy) {
                        Text(appText("Закрыть", "Ябырға"))
                    }
                } else if (!pending && result.state != CommandIntent.State.Applied) {
                    Checkbox(checked = utc in replaced, onCheckedChange = {
                        replaced = if (it) replaced + utc else replaced - utc
                    }, enabled = !series.busy,
                        modifier = Modifier.semantics { contentDescription = stamp })
                }
            }
        }
        AnimatedVisibility(pending) {
            Column {
                TextButton(onClick = series.check, enabled = !series.busy) {
                    Text(appText("Сверить результаты", "Һөҙөмтәләрҙе тикшерергә"))
                }
                if (slot.mayRepeat()) TextButton(onClick = series.repeat, enabled = !series.busy) {
                    Text(appText("Повторить неизвестные даты", "Билдәһеҙ көндәрҙе ҡабатларға"))
                }
            }
        }
        if (!pending) {
            if (slot.rows.any { it.result.state != CommandIntent.State.Applied }) {
                TextButton(onClick = { picking = true }, enabled = !series.busy && replaced.isNotEmpty()) {
                    Text(appText("Выбрать другие даты", "Башҡа көндәр һайларға"))
                }
            }
            TextButton(onClick = series.finish, enabled = !series.busy) { Text(appText("Готово", "Әҙер")) }
        }
    }
    if (picking) {
        RideDatesPicker(emptyList(), replaced.size, replaced.size,
            unavailable = slot.rows.map { it.date.departureUtc }.toSet() - replaced,
            onDismiss = { picking = false }, onConfirm = {
                picking = false
                series.replace(replaced, it)
            })
    }
    closeDate?.let { utc ->
        AlertDialog(onDismissRequest = { closeDate = null },
            title = { Text(appText("Закрыть запрос на эту дату?", "Был көнгә һорауҙы ябырғамы?")) },
            text = { Text(appText("Если поездка уже опубликована, покажем её результат.",
                "Сәфәр баҫтырылған булһа, һөҙөмтәһен күрһәтербеҙ.")) },
            confirmButton = { TextButton(onClick = { closeDate = null; series.close(utc) }) { Text(appText("Закрыть", "Ябырға")) } },
            dismissButton = { TextButton(onClick = { closeDate = null }) { Text(appText("Отмена", "Баш тартыу")) } })
    }
}
