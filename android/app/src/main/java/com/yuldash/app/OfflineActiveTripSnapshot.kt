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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class OfflineTripSnapshotMode(
    val snapshot: OfflineTripSnapshot,
    val revision: Long,
)
internal data class BookingNavigationRead(
    val live: Result<TripStateDto>,
    val saved: OfflineTripSnapshot?,
    val fallbackAllowed: Boolean,
)
private enum class ColdHideStage { IDLE, RUNNING, CLEARED, DEFERRED, NOT_SAVED }
private data class ColdHideRequest(
    val operation: Int,
    val owner: SnapshotOwner,
    val bookingId: Int,
    val rideId: Int,
    val grantHash: String,
    val session: Long,
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

internal suspend fun readBookingNavigation(bookingId: Int, singleAttemptTransport: Boolean = false,
    transportSlot: java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>? = null): BookingNavigationRead {
    val read = ApiClient.getTripStateForNavigation(bookingId, singleAttemptTransport, transportSlot)
    val owner = read.ticket?.owner
    val saved = if (read.result.isFailure && read.fallbackAllowed && owner != null) OfflineTripSnapshots.load(owner, bookingId) else null
    val cold = if (read.result.isFailure && read.fallbackAllowed && saved == null && owner != null) {
        OfflineTripSnapshots.loadColdPassport(owner, bookingId,
            (transportSlot as? ParentTransportGate)?.let { gate -> { gate.beginColdRestore(owner, bookingId) } })
    } else null
    return BookingNavigationRead(read.result, saved ?: cold?.snapshot, read.fallbackAllowed)
}

/** Read-only offline fallback: never mounts ActiveTripScreen or its chat/action/location effects. */
@Composable internal fun OfflineActiveTripSnapshot(mode: OfflineTripSnapshotMode, isCurrent: () -> Boolean, onRetry: () -> Unit,
    onBack: () -> Unit, onHideStart: () -> Unit = {},
    coldRestoreAdmission: (() -> SnapshotColdRestoreTicket?)? = null) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = androidx.compose.ui.platform.LocalContext.current
    val current by rememberUpdatedState(isCurrent)
    val version by OfflineTripSnapshots.version.collectAsState()
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var displayed by remember(mode) { mutableStateOf<OfflineTripSnapshot?>(null) }
    var displayedCold by remember(mode) { mutableStateOf<ColdTripPassport?>(null) }
    var grantHash by remember(mode) { mutableStateOf<String?>(null) }
    var loading by remember(mode) { mutableStateOf(true) }
    var stage by remember(mode) { mutableStateOf(ColdHideStage.IDLE) }
    var operation by remember(mode) { mutableIntStateOf(0) }
    var removal by remember(mode) { mutableStateOf<ColdHideRequest?>(null) }
    DisposableEffect(lifecycle, mode) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) { displayed = null; displayedCold = null }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); displayed = null; displayedCold = null }
    }
    fun visible(): Boolean {
        if (stage != ColdHideStage.IDLE) return false
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
    fun requestCurrent(request: ColdHideRequest): Boolean = current() && request.operation == operation &&
        request.owner == mode.snapshot.owner && request.bookingId == mode.snapshot.bookingId &&
        request.rideId == mode.snapshot.rideId && request.grantHash == grantHash &&
        request.session == ApiClient.queueSessionGeneration() && OfflineTripSnapshots.currentOwner() == request.owner
    fun beginRemoval(value: ColdTripPassport) {
        if (stage != ColdHideStage.IDLE || !resumed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            displayedCold !== value || displayed !== value.snapshot) return
        val next = operation + 1
        operation = next
        val request = ColdHideRequest(next, value.snapshot.owner, value.snapshot.bookingId, value.grant.rideId,
            value.grant.tripPassSha256, ApiClient.queueSessionGeneration())
        if (!requestCurrent(request)) return
        onHideStart()
        removal = request
        stage = ColdHideStage.RUNNING
        displayed = null; displayedCold = null
    }
    fun retryRemoval() {
        if (stage == ColdHideStage.IDLE || stage == ColdHideStage.RUNNING || !resumed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val previous = removal ?: return
        val next = operation + 1
        operation = next
        val request = previous.copy(operation = next)
        if (!requestCurrent(request)) return
        onHideStart()
        removal = request
        stage = ColdHideStage.RUNNING
        displayed = null; displayedCold = null
    }
    LaunchedEffect(removal) {
        val request = removal ?: return@LaunchedEffect
        if (stage != ColdHideStage.RUNNING || request.operation != operation) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            TripPassStore.requestRemoval(context, request.bookingId, expectedGeneration = request.session)
        }
        if (stage != ColdHideStage.RUNNING || !requestCurrent(request)) return@LaunchedEffect
        displayed = null; displayedCold = null
        when (result) {
            TripPassStore.RemovalResult.CLEARED -> {
                removal = null; grantHash = null; stage = ColdHideStage.CLEARED
            }
            TripPassStore.RemovalResult.DEFERRED -> stage = ColdHideStage.DEFERRED
            TripPassStore.RemovalResult.NOT_SAVED -> stage = ColdHideStage.NOT_SAVED
        }
    }
    LaunchedEffect(mode, version, resumed, operation) {
        displayed = null; displayedCold = null; loading = true
        if (stage == ColdHideStage.IDLE && resumed && current()) {
            val value = OfflineTripSnapshots.load(mode.snapshot.owner, mode.snapshot.bookingId)
            if (value != null) {
                if (stage == ColdHideStage.IDLE && resumed && current()) displayed = value
            } else {
                val cold = OfflineTripSnapshots.loadColdPassport(mode.snapshot.owner, mode.snapshot.bookingId, coldRestoreAdmission)
                if (stage == ColdHideStage.IDLE && resumed && current() && cold != null &&
                    cold.snapshot.owner == mode.snapshot.owner && cold.snapshot.bookingId == mode.snapshot.bookingId &&
                    cold.grant.rideId == mode.snapshot.rideId && OfflineTripSnapshots.canDrawCold(cold)) {
                    displayed = cold.snapshot; displayedCold = cold; grantHash = cold.grant.tripPassSha256
                }
            }
        }
        loading = false
    }
    LaunchedEffect(displayed, displayedCold, resumed, operation) {
        val value = displayed ?: return@LaunchedEffect
        while (resumed && current() && stage == ColdHideStage.IDLE) {
            val remaining = value.remaining(OfflineTripSnapshots.clockInBoot(value.stateStamp.boot)) ?: break
            delay(remaining)
        }
        if (stage == ColdHideStage.IDLE && current()) {
            if (displayedCold == null) OfflineTripSnapshots.load(value.owner, value.bookingId)
            else OfflineTripSnapshots.loadColdPassport(value.owner, value.bookingId, coldRestoreAdmission)
        }
        if (stage == ColdHideStage.IDLE) { displayed = null; displayedCold = null; grantHash = null }
    }
    fun leave() { if (current() && stage != ColdHideStage.RUNNING) onBack() }
    BackHandler { leave() }
    Column(Modifier.fillMaxSize().background(CanonBg).safeDrawingPadding()) {
        ScreenTopBar(appText("Сохранённая поездка", "Һаҡланған сәфәр"), onBack = { leave() })
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(CanonSpace.lg),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
            Text(appText("Показываем последнее сохранённое состояние. Оно могло измениться.",
                "Һуңғы һаҡланған хәлде күрһәтәбеҙ. Ул үҙгәргән булыуы мөмкин."), style = CanonBody, color = CanonMutedStrong)
            when (stage) {
                ColdHideStage.RUNNING -> {
                    Text(appText("Скрываем личные данные…", "Шәхси мәғлүмәтте йәшерәбеҙ…"),
                        style = CanonBodyStrong, color = CanonText)
                    CircularProgressIndicator(color = CanonGreen2)
                }
                ColdHideStage.CLEARED -> Text(appText("Личные данные скрыты на этом устройстве",
                    "Шәхси мәғлүмәт был ҡоротмала йәшерелде"), style = CanonBodyStrong, color = CanonText)
                ColdHideStage.DEFERRED -> {
                    Text(appText("Доступ к личным данным закрыт. Очистка не завершена.",
                        "Шәхси мәғлүмәткә инеү ябыҡ. Таҙартыу тамамланмаған."), style = CanonBodyStrong, color = CanonText)
                    Text(appText("Приложение повторит очистку при следующем запуске.",
                        "Ҡулланма таҙартыуҙы ҡабат асыуҙан һуң ҡабатлаясыҡ."), style = CanonBody, color = CanonMutedStrong)
                    AppButton(appText("Завершить очистку", "Таҙартыуҙы тамамлау"), onClick = { retryRemoval() },
                        style = AppButtonStyle.Secondary)
                }
                ColdHideStage.NOT_SAVED -> {
                    Text(appText("Не удалось подтвердить скрытие. После перезапуска старая копия может появиться снова.",
                        "Йәшереүҙе раҫлап булманы. Ҡабат асыуҙан һуң иҫке күсермә ҡабат күренеүе мөмкин."),
                        style = CanonBodyStrong, color = CanonText)
                    AppButton(appText("Повторить скрытие", "Йәшереүҙе ҡабатлау"), onClick = { retryRemoval() },
                        style = AppButtonStyle.Secondary)
                }
                ColdHideStage.IDLE -> {
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
                                ColdTripPassportCard(cold, isCurrent = { visible() && displayedCold === cold },
                                    onHidePrivate = { beginRemoval(cold) })
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
