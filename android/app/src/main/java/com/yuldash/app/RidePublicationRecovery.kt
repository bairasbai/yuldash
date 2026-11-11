package com.yuldash.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.yuldash.app.data.CommandFingerprint
import com.yuldash.app.data.CommandIntent
import com.yuldash.app.data.RidePublicationOutcome
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.RidePublicationApi
import com.yuldash.app.data.RidePublicationIntent
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal data class RidePublicationUi(
    val ready: Boolean, val busy: Boolean, val error: String?, val label: String?,
    val notice: (@Composable () -> Unit)?, val publish: () -> Unit,
)

@Composable
internal fun rememberRidePublication(
    bodyForDraft: () -> JSONObject,
    stopPriceError: String,
    stopLanguage: String,
    onPublicationResolved: () -> Unit,
    onPublish: (Ride) -> Unit,
): RidePublicationUi {
    val publishScope = rememberCoroutineScope()
    val errPublish = appText("Не удалось опубликовать. Проверь сеть и повтори.", "Баҫтырып булманы. Селтәрҙе тикшереп ҡабатла.")
    val stopErrors = rideStopErrorMessages()
    val session = ApiClient.queueSessionGeneration()
    var publishing by remember(session) { mutableStateOf(false) }
    var publishError by remember(session) { mutableStateOf<String?>(null) }
    var publicationSlot by remember(session) { mutableStateOf<RidePublicationIntent.Slot?>(null) }
    var publicationStatus by remember(session) { mutableStateOf<RidePublicationOutcome?>(null) }
    var publicationReady by remember(session) { mutableStateOf(false) }
    var publicationLoading by remember(session) { mutableStateOf(true) }
    var publicationRead by remember(session) { mutableIntStateOf(0) }
    val failedCheck = appText("Не удалось проверить запрос. Попробуй ещё раз.", "Һорауҙы тикшереп булманы. Тағы ҡабатла.")
    val previousApplied = appText("Этот запрос уже выполнен. Для новой поездки измени данные.", "Был һорау үтәлгән. Яңы сәфәр өсөн мәғлүмәтте үҙгәрт.")
    val problems = ridePublicationProblems()

    fun applyPublication(slot: RidePublicationIntent.Slot, outcome: RidePublicationOutcome) {
        publicationSlot = slot.copy(state = outcome.state)
        publicationStatus = outcome
        publishError = null
        if (outcome.state == CommandIntent.State.Applied) {
            onPublicationResolved()
            val ride = outcome.ride
            if (ride?.status == "active" && publicationDraftHash(bodyForDraft()) == slot.draft.hash) {
                onPublish(ride.toUiRide())
            }
        }
    }

    LaunchedEffect(session, publicationRead) {
        publicationLoading = true
        publicationReady = false
        try {
            val saved = RidePublicationApi.saved(session).getOrElse {
                if (ApiClient.isCurrentSession(session)) publishError = failedCheck
                return@LaunchedEffect
            }
            if (!ApiClient.isCurrentSession(session)) return@LaunchedEffect
            publicationSlot = saved
            if (saved != null && (saved.state == CommandIntent.State.Unknown ||
                    (saved.state == CommandIntent.State.Applied && publicationDraftHash(bodyForDraft()) == saved.draft.hash))) {
                RidePublicationApi.check(session, saved, close = false).onSuccess { outcome ->
                    if (ApiClient.isCurrentSession(session)) applyPublication(saved, outcome)
                }
            }
            if (ApiClient.isCurrentSession(session)) publicationReady = true
        } finally {
            publicationLoading = false
        }
    }

    fun checkPublication(close: Boolean) {
        val slot = publicationSlot ?: return
        if (publishing || publicationLoading) return
        publishing = true
        publishScope.launch {
            try {
                val checked = RidePublicationApi.check(session, slot, close)
                if (!ApiClient.isCurrentSession(session)) return@launch
                checked.onSuccess { applyPublication(slot, it) }.onFailure { publishError = failedCheck }
            } finally {
                publishing = false
            }
        }
    }

    fun publishPublication() {
        if (publishing || !publicationReady) return
        val body = bodyForDraft()
        val hash = publicationDraftHash(body)
        val previous = publicationSlot
        if (previous?.state == CommandIntent.State.Applied && hash == previous.draft.hash) {
            publishError = previousApplied
            return
        }
        val retry = previous?.state == CommandIntent.State.Unknown
        if (retry && hash != previous.draft.hash) {
            publishError = problems.getValue(CommandIntent.Problem.Changed)
            return
        }
        val draft = if (retry) checkNotNull(previous).draft else RidePublicationIntent.Draft(
            hash, publicationDeparture(body.getString("depart_at")), stopLanguage,
        )
        val categorySent = body.getString("category")
        if (rideCategoryClosed(categorySent)) return
        val stopInputs = body.getJSONArray("waypoints").strings()
        val pricesEnabled = body.getBoolean("stop_prices_enabled")
        val priceInputs = body.getJSONArray("stop_prices").strings()
        publishError = null
        publicationStatus = null
        publishing = true
        publishScope.launch {
            try {
                val stops = try {
                    resolveRideStops(stopInputs, body.getString("from_city"), body.getString("to_city"),
                        if (draft.stopLanguage == "ba") AppLanguage.Ba else AppLanguage.Ru)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    publishError = if (error is RideStopException) stopErrors.getValue(error.problem) else serverSaid(error, errPublish)
                    return@launch
                }
                if (!ApiClient.isCurrentSession(session) || rideCategoryClosed(categorySent)) return@launch
                val prices = if (pricesEnabled && stops.isNotEmpty()) {
                    stopPricesInputValid(body.getInt("price"), stops.size, priceInputs.filter { it.isNotBlank() })
                        ?: run { publishError = stopPriceError; return@launch }
                } else emptyList()
                body.remove("stop_prices_enabled")
                body.put("stop_prices", JSONArray(prices)).put("waypoints", stops.joinToString(" | "))
                    .put("depart_at", draft.departAt)
                val submitted = RidePublicationApi.publish(session, body, draft, previous?.key, retry)
                if (!ApiClient.isCurrentSession(session)) return@launch
                submitted.onSuccess { (slot, outcome) ->
                    applyPublication(slot, outcome)
                }.onFailure { error ->
                    val failure = error as? RidePublicationIntent.Failure
                    if (failure != null) {
                        failure.slot?.let { publicationSlot = it }
                        publishError = failure.refusal?.let { serverSaid(it, problems.getValue(failure.problem)) }
                            ?: problems.getValue(failure.problem)
                    } else publishError = serverSaid(error, errPublish)
                }
            } finally {
                publishing = false
            }
        }
    }

    val pending = publicationSlot?.state == CommandIntent.State.Unknown
    val notice: (@Composable () -> Unit)? = if (publicationLoading || !publicationReady || pending || publicationStatus != null) {
        {
            RidePublicationNotice(
                ready = publicationReady, loading = publicationLoading, busy = publishing,
                pending = pending, outcome = publicationStatus, onReload = { publicationRead++ },
                onCheck = { checkPublication(false) }, onClose = { checkPublication(true) },
            )
        }
    } else null
    val label = if (pending) appText("Повторить тот же запрос", "Шул уҡ һорауҙы ҡабатлау") else null
    return RidePublicationUi(publicationReady, publishing, publishError, label, notice, ::publishPublication)
}

private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

internal fun publicationDraftHash(body: JSONObject): String =
    CommandFingerprint.create("ride.create.draft", null, body).payloadHash

internal fun publicationDeparture(dateText: String, now: Long = System.currentTimeMillis()): String {
    val picked = runCatching {
        java.text.SimpleDateFormat("dd.MM.yyyy, HH:mm", java.util.Locale.US).parse(dateText)?.time
    }.getOrNull()
    return isoWithOffset(picked ?: (now + 3 * 3600_000L))
}

@Composable
private fun ridePublicationProblems(): Map<CommandIntent.Problem, String> = mapOf(
    CommandIntent.Problem.Storage to appText("Не удалось сохранить запрос на телефоне.", "Һорауҙы телефонда һаҡлап булманы."),
    CommandIntent.Problem.Full to appText("Сначала проверь предыдущую публикацию.", "Тәүҙә алдағы баҫтырыуҙы тикшер."),
    CommandIntent.Problem.Unknown to appText("Ответ потерялся. Проверь результат или повтори тот же запрос.", "Яуап юғалды. Һөҙөмтәне тикшер йәки шул уҡ һорауҙы ҡабатла."),
    CommandIntent.Problem.Changed to appText("Данные изменились. Проверь или закрой прежний запрос.", "Мәғлүмәт үҙгәрҙе. Элекке һорауҙы тикшер йәки яп."),
    CommandIntent.Problem.Expired to appText("Срок повтора истёк. Проверь или закрой запрос.", "Ҡабатлау ваҡыты үтте. Һорауҙы тикшер йәки яп."),
    CommandIntent.Problem.Superseded to appText("Запрос уже изменился. Проверь результат.", "Һорау үҙгәрҙе. Һөҙөмтәне тикшер."),
    CommandIntent.Problem.Gone to appText("Поездка больше недоступна.", "Сәфәр бүтән асыҡ түгел."),
    CommandIntent.Problem.Closed to appText("Запрос закрыт. Можно опубликовать поездку.", "Һорау ябылды. Сәфәрҙе баҫтырырға мөмкин."),
    CommandIntent.Problem.Rejected to appText("Сервер отклонил запрос. Проверь данные поездки.", "Сервер һорауҙы кире ҡаҡты. Сәфәр мәғлүмәтен тикшер."),
)

@Composable
private fun RidePublicationNotice(
    ready: Boolean,
    loading: Boolean,
    busy: Boolean,
    pending: Boolean,
    outcome: RidePublicationOutcome?,
    onReload: () -> Unit,
    onCheck: () -> Unit,
    onClose: () -> Unit,
) {
    Column {
        if (loading) {
            Text(appText("Проверяем предыдущую публикацию…", "Алдағы баҫтырыуҙы тикшерәбеҙ…"), style = CanonCaption)
        } else if (!ready) {
            Text(appText("Не удалось проверить предыдущую публикацию.", "Алдағы баҫтырыуҙы тикшереп булманы."), style = CanonCaption)
            TextButton(onClick = onReload, enabled = !busy) { Text(appText("Повторить проверку", "Тикшереүҙе ҡабатлау")) }
        } else if (pending) {
            val message = if (outcome?.state == CommandIntent.State.Unknown)
                appText("Сервер пока не видит результат публикации.", "Сервер баҫтырыу һөҙөмтәһен әлегә күрмәй.")
            else appText("Ответ на публикацию потерялся.", "Баҫтырыу яуабы юғалды.")
            Text(message, style = CanonCaption)
            TextButton(onClick = onCheck, enabled = !busy) { Text(appText("Проверить запрос", "Һорауҙы тикшереү")) }
            TextButton(onClick = onClose, enabled = !busy) { Text(appText("Закрыть запрос", "Һорауҙы ябыу")) }
        } else if (outcome != null) {
            val message = when (outcome.state) {
                CommandIntent.State.Applied -> when (outcome.ride?.status) {
                    "active" -> appText("Поездка опубликована.", "Сәфәр баҫтырылды.")
                    "done" -> appText("Поездка уже завершена.", "Сәфәр тамамланған.")
                    "cancelled" -> appText("Поездка уже отменена.", "Сәфәр кире алынған.")
                    "expired" -> appText("Время этой поездки уже прошло.", "Был сәфәрҙең ваҡыты үткән.")
                    else -> appText("Поездка больше недоступна.", "Сәфәр бүтән асыҡ түгел.")
                }
                CommandIntent.State.Closed -> appText("Запрос закрыт. Можно опубликовать поездку.", "Һорау ябылды. Сәфәрҙе баҫтырырға мөмкин.")
                CommandIntent.State.Rejected -> appText("Сервер отклонил запрос. Проверь данные поездки.", "Сервер һорауҙы кире ҡаҡты. Сәфәр мәғлүмәтен тикшер.")
                CommandIntent.State.Unknown -> return@Column
            }
            Text(message, style = CanonCaption)
        }
    }
}
