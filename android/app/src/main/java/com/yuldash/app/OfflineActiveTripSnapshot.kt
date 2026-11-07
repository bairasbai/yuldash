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

internal data class OfflineTripSnapshotMode(
    val snapshot: OfflineTripSnapshot,
    val revision: Long,
    val cold: ColdTripPassport? = null,
)
internal data class BookingNavigationRead(
    val live: Result<TripStateDto>,
    val saved: OfflineTripSnapshot?,
    val fallbackAllowed: Boolean,
    val cold: ColdTripPassport? = null,
)

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
    val owner = read.ticket?.owner
    val saved = if (read.result.isFailure && read.fallbackAllowed && owner != null) OfflineTripSnapshots.load(owner, bookingId) else null
    val cold = if (read.result.isFailure && read.fallbackAllowed && saved == null && owner != null) {
        OfflineTripSnapshots.loadColdPassport(owner, bookingId)
    } else null
    return BookingNavigationRead(read.result, saved ?: cold?.snapshot, read.fallbackAllowed, cold)
}

/** Read-only offline fallback: never mounts ActiveTripScreen or its chat/action/location effects. */
@Composable internal fun OfflineActiveTripSnapshot(mode: OfflineTripSnapshotMode, isCurrent: () -> Boolean, onRetry: () -> Unit, onBack: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current by rememberUpdatedState(isCurrent)
    val version by OfflineTripSnapshots.version.collectAsState()
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var displayed by remember(mode) { mutableStateOf<OfflineTripSnapshot?>(null) }
    var displayedCold by remember(mode) { mutableStateOf<ColdTripPassport?>(null) }
    var loading by remember(mode) { mutableStateOf(true) }
    DisposableEffect(lifecycle, mode) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) { displayed = null; displayedCold = null }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); displayed = null; displayedCold = null }
    }
    LaunchedEffect(mode, version, resumed) {
        displayed = null; displayedCold = null; loading = true
        if (resumed && current()) {
            val value = OfflineTripSnapshots.load(mode.snapshot.owner, mode.snapshot.bookingId)
            if (value != null) {
                if (resumed && current()) displayed = value
            } else {
                val cold = OfflineTripSnapshots.loadColdPassport(mode.snapshot.owner, mode.snapshot.bookingId)
                if (resumed && current() && cold != null) { displayed = cold.snapshot; displayedCold = cold }
            }
        }
        loading = false
    }
    LaunchedEffect(displayed, displayedCold, resumed) {
        val value = displayed ?: return@LaunchedEffect
        while (resumed && current()) {
            val remaining = value.remaining(OfflineTripSnapshots.clockInBoot(value.stateStamp.boot)) ?: break
            delay(remaining)
        }
        if (current()) {
            if (displayedCold == null) OfflineTripSnapshots.load(value.owner, value.bookingId)
            else OfflineTripSnapshots.loadColdPassport(value.owner, value.bookingId)
        }
        displayed = null; displayedCold = null
    }
    fun visible(): Boolean {
        val value = displayed ?: return false
        if (!resumed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !current() ||
            !OfflineTripSnapshots.current(value.owner) || value.owner != mode.snapshot.owner ||
            value.bookingId != mode.snapshot.bookingId || value.rideId != mode.snapshot.rideId) return false
        val cold = displayedCold
        return if (cold != null) {
            cold.snapshot === value && OfflineTripSnapshots.canDrawCold(cold) &&
                cold.remaining(OfflineTripSnapshots.clockInBoot(value.stateStamp.boot)) != null
        } else {
            OfflineTripSnapshots.canDrawSnapshot(value) &&
                value.remaining(OfflineTripSnapshots.clockInBoot(value.stateStamp.boot)) != null
        }
    }
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
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
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
                    val cold = displayedCold?.takeIf { it.snapshot === value }
                    if (cold != null) {
                        ColdTripPassportCard(cold, isCurrent = { visible() && displayedCold === cold })
                    } else {
                        val pass = if (visible()) OfflineTripSnapshots.privatePass(value.bookingId) else null
                        val method = pass?.payMethod?.takeIf { it in payMethodKeys }
                        if (pass != null && method != null) {
                            fun privateVisible() = visible() && isCurrent() && displayed === value &&
                                OfflineTripSnapshots.currentOwner() == value.owner && OfflineTripSnapshots.matchesPass(pass)
                            TripAuthorityContent(::privateVisible) {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
                                    Text("${kopToRub(rideshareRubToKop(pass.effectivePayAmount))} · ${payMethodLabel(method)}",
                                        style = CanonBodyStrong, color = CanonText)
                                    PayAgreementBlock(editable = false, method = method, amountText = "",
                                        summaryAmount = pass.effectivePayAmount, onMethod = {}, onAmount = {})
                                }
                            }
                        }
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