package com.yuldash.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yuldash.app.data.*
import kotlinx.coroutines.delay

internal data class OfflineTripSnapshotMode(val snapshot: OfflineTripSnapshot, val revision: Long)
internal data class BookingNavigationRead(val live: Result<TripStateDto>, val saved: OfflineTripSnapshot?, val fallbackAllowed: Boolean)

/** Cache failure is separate from a successful server action and never retries its POST. */
@Composable internal fun OfflineSnapshotSaveNotice() {
    val result by OfflineTripSnapshots.persistence.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val message = appText("Не удалось обновить сохранённую копию поездки.", "Һаҡланған сәфәр күсермәһен яңыртып булманы.")
    LaunchedEffect(result) {
        if (result == SnapshotPersistence.NOT_DURABLE) android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
    }
}

internal suspend fun readBookingNavigation(bookingId: Int): BookingNavigationRead {
    val read = ApiClient.getTripStateForNavigation(bookingId)
    val saved = if (read.result.isFailure && read.fallbackAllowed) read.ticket?.let { OfflineTripSnapshots.load(it.owner, bookingId) } else null
    return BookingNavigationRead(read.result, saved, read.fallbackAllowed)
}

/** Read-only warm fallback: never mounts ActiveTripScreen or its chat/action/location effects. */
@Composable internal fun OfflineActiveTripSnapshot(mode: OfflineTripSnapshotMode, isCurrent: () -> Boolean, onRetry: () -> Unit, onBack: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current by rememberUpdatedState(isCurrent)
    val version by OfflineTripSnapshots.version.collectAsState()
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var displayed by remember(mode) { mutableStateOf<OfflineTripSnapshot?>(null) }
    var loading by remember(mode) { mutableStateOf(true) }
    DisposableEffect(lifecycle, mode) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) displayed = null
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); displayed = null }
    }
    LaunchedEffect(mode, version, resumed) {
        displayed = null; loading = true
        if (resumed && current()) {
            val value = OfflineTripSnapshots.load(mode.snapshot.owner, mode.snapshot.bookingId)
            if (resumed && current()) displayed = value
        }
        loading = false
    }
    LaunchedEffect(displayed, resumed) {
        val value = displayed ?: return@LaunchedEffect
        while (resumed && current()) {
            val remaining = value.remaining(OfflineTripSnapshots.clockInBoot(value.stateStamp.boot)) ?: break
            delay(remaining)
        }
        if (current()) OfflineTripSnapshots.load(value.owner, value.bookingId) // latch expiration before clock rollback
        displayed = null
    }
    fun visible() = resumed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && current() &&
        displayed?.let { value -> OfflineTripSnapshots.current(value.owner) && OfflineTripSnapshots.canDrawSnapshot(value) &&
            value.remaining(OfflineTripSnapshots.clockInBoot(value.stateStamp.boot)) != null } == true
    BackHandler { if (current()) onBack() }
    Column(Modifier.fillMaxSize().background(CanonBg).safeDrawingPadding()) {
        ScreenTopBar(appText("Сохранённая поездка", "Һаҡланған сәфәр"), onBack)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(CanonSpace.lg),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
            Text(appText("Показываем последнее сохранённое состояние. Оно могло измениться.",
                "Һуңғы һаҡланған хәлде күрһәтәбеҙ. Ул үҙгәргән булыуы мөмкин."), style = CanonBody, color = CanonMutedStrong)
            if (visible()) TripAuthorityContent(::visible) {
                val value = requireNotNull(displayed)
                val route = requireNotNull(value.route)
                AppCard {
                    Column(Modifier.padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                        Text("${route.from} → ${route.to}", style = CanonTitle, color = CanonText)
                        Text(formatDepart(route.departAt), style = CanonBody, color = CanonText)
                        BookingSegmentNote(route.pickup, route.dropoff, route.hasStops, route.from, route.to, showSegmentLabel = false)
                        Text(when {
                            value.state.status == "onboard" -> appText("Пассажир сел", "Пассажир ултырҙы")
                            value.state.phase == "arriving" -> appText("Водитель подъезжает", "Йөрөтөүсе яҡынлаша")
                            value.state.phase == "departed" -> appText("Водитель выехал", "Йөрөтөүсе юлға сыҡты")
                            else -> appText("Поездка подтверждена", "Сәфәр раҫланды")
                        }, style = CanonBodyStrong, color = CanonText)
                    }
                }
            } else if (loading) CircularProgressIndicator(color = CanonGreen2)
            else Text(appText("Сохранённые данные устарели или недоступны", "Һаҡланған мәғлүмәт иҫкергән йәки уны асып булмай"), style = CanonBodyStrong, color = CanonText)
            AppButton(appText("Повторить", "Ҡабатлау"), onClick = { if (current()) onRetry() })
        }
    }
}

/** Last-known history, not a restored active trip. No chat, location, phone, code or money is mounted. */
@Composable internal fun HistoricalTripSummaryCard(value: HistoricalTripSummary, onRetry: () -> Unit) {
    val version by OfflineTripSnapshots.version.collectAsState()
    var tick by remember(value) { mutableIntStateOf(0) }
    LaunchedEffect(value) {
        val now = OfflineTripSnapshots.clock()
        val remaining = now?.let(value::remaining)
        if (remaining != null) { delay(remaining); tick++ }
    }
    if (!version.let { tick; OfflineTripSnapshots.canDrawHistorical(value) }) return
    AppCard {
        Column(Modifier.padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Text(appText("Последняя сохранённая копия", "Һуңғы һаҡланған күсермә"), style = CanonBodyStrong, color = CanonText)
            Text(value.route.from + " → " + value.route.to, style = CanonTitle, color = CanonText)
            Text(formatDepart(value.route.departAt), style = CanonBody, color = CanonText)
            BookingSegmentNote(value.route.pickup, value.route.dropoff, value.route.hasStops,
                value.route.from, value.route.to, showSegmentLabel = false)
            Text(appText("Текущий статус неизвестен. Поездка могла измениться или завершиться.",
                "Хәҙерге хәл билдәһеҙ. Сәфәр үҙгәргән йәки тамамланған булыуы мөмкин."),
                style = CanonCaption, color = CanonMutedStrong)
            Text(when {
                value.state.status == "onboard" -> appText("В сохранённой копии: пассажир сел", "Һаҡланған күсермәлә: пассажир ултырған")
                value.state.phase == "arriving" -> appText("В сохранённой копии: водитель подъезжал", "Һаҡланған күсермәлә: йөрөтөүсе яҡынлашҡан")
                value.state.phase == "departed" -> appText("В сохранённой копии: водитель выехал", "Һаҡланған күсермәлә: йөрөтөүсе юлға сыҡҡан")
                else -> appText("В сохранённой копии: поездка была подтверждена", "Һаҡланған күсермәлә: сәфәр раҫланған булған")
            }, style = CanonBody, color = CanonText)
            AppButton(appText("Проверить статус", "Хәлде тикшереү"), onClick = {
                if (OfflineTripSnapshots.canDrawHistorical(value)) onRetry()
            }, style = AppButtonStyle.Secondary)
        }
    }
}