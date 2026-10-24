package com.yuldash.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.BookingDetailsDto
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.TripStateDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Поездку отменили, когда пассажир уже был в машине (решение Александра 23.10.2026, сервер —
// `visibility.cancelled_in_transit`). Отменить «в пути» может только водитель или администратор:
// «сломалась машина», «высадил за поведение». Человек, возможно, стоит на трассе — поэтому экран
// поездки не закрывается сам (как при обычной отмене), а ведёт к помощи: SOS, «Застрял на трассе»,
// «Сообщить о проблеме» (окно сервера `problem_report_sec`, 6 ч от отмены), машина и госномер —
// по ним родные и полиция ищут машину. Телефона водителя, чата и карты нет: сервер телефон после
// отмены не даёт, связь — через SOS и разбор. Экран общий: экран поездки и «Детали поездки».
// Здесь же — конец поездки на экране поездки (удаление паспорта, закрыть или остаться) и память
// об отмене в пути на этот вход ([InTransitCancels]): человек на трассе часто без сети.

/** Машина, в которой человек ехал: «серая Лада Веста» и госномер. Телефона водителя здесь нет. */
internal data class InTransitCar(val car: String, val plate: String) {
    val known: Boolean get() = car.isNotBlank() || plate.isNotBlank()
}

internal fun inTransitCarOf(d: BookingDetailsDto): InTransitCar =
    InTransitCar(listOf(d.driverCarColor, d.driverCar).filter { it.isNotBlank() }.joinToString(" "), d.driverPlate)

/** «Детали поездки» показывают экран отмены в пути: только пассажиру и только по слову сервера. */
internal fun showsCancelledInTransit(d: BookingDetailsDto): Boolean =
    d.role == "passenger" && d.status == "cancelled" && d.cancelledInTransit

/**
 * Что экран поездки знает об отмене в пути. Признак только с сервера (`cancelled_in_transit` в /role
 * и /details); неизвестно или старый сервер — `false`, и обычная отмена закрывает экран, как раньше.
 */
@Stable
internal class CancelledInTransitState(initial: Boolean) {
    var cancelled by mutableStateOf(initial)
        private set
    /** Водитель брони — для «Сообщить о проблеме»; 0 — ещё не знаем или старый сервер. */
    var driverId by mutableIntStateOf(0)
        private set
    /** Сколько секунд ещё открыт «Сообщить о проблеме» (6 ч от отмены, считает сервер). */
    var reportSec by mutableIntStateOf(0)
        private set
    var driverName by mutableStateOf("")
        private set
    /** null — машину ещё не знаем: экран сам дочитает детали брони. */
    var car by mutableStateOf<InTransitCar?>(null)
        private set

    fun accept(st: TripStateDto) {
        if (st.status == "cancelled" && st.cancelledInTransit) cancelled = true
        if (st.role == "passenger") { driverId = st.peerId; reportSec = st.problemReportSec }
    }

    fun accept(d: BookingDetailsDto) {
        if (d.status == "cancelled" && d.cancelledInTransit) cancelled = true
        if (d.driverName.isNotBlank()) driverName = d.driverName
        inTransitCarOf(d).takeIf { it.known }?.let { car = it }
    }

    /** Перед удалением офлайн-паспорта: машина и номер из него остаются на экране, телефон — нет. */
    fun keep(pass: TripPass?) {
        if (pass == null) return
        if (car == null) InTransitCar(pass.driverCar, pass.driverPlate).takeIf { it.known }?.let { car = it }
        if (driverName.isBlank()) driverName = pass.driverName
    }

    /** Экран поездки остаётся открытым: только у пассажира — водитель отменил сам и знает почему. */
    fun keepsScreen(role: String): Boolean = cancelled && role == "passenger"

    /** Эта отмена уже была видна этому входу ([InTransitCancels]): экран помощи — сразу, без сети. */
    fun restore(knownCar: InTransitCar?, knownDriver: String) {
        cancelled = true
        if (car == null) car = knownCar
        if (driverName.isBlank()) driverName = knownDriver
    }

    /** Окно разбора вышло по часам («Детали поездки» читают /role один раз — без этого кнопка висела бы). */
    fun expireReport() {
        reportSec = 0
    }
}

/**
 * Отмены в пути, которые этот вход уже видел: номер брони, машина, госномер и имя водителя —
 * без телефона. Память процесса: переживает уход на SOS и обратно и поворот экрана — а офлайн-паспорт
 * к этому моменту уже удалён, и сети на трассе может не быть. Пуш без сети сюда не смотрит: переход по
 * нему сначала спрашивает сервер (не покрыто, docs/tasks.md). Выход из аккаунта
 * стирает всё; запись чужого входа (другое поколение сессии) не видна и стирается при первом же обращении.
 */
internal object InTransitCancels {
    private data class Entry(val owner: Long, val car: InTransitCar?, val driverName: String)

    private val entries = HashMap<Int, Entry>()

    init {
        // Выход или другой аккаунт: машина и госномер уходят из памяти вместе с кешем ответов.
        ApiClient.onSessionReset { synchronized(this) { entries.clear() } }
    }

    @Synchronized
    fun record(bookingId: Int, owner: Long, car: InTransitCar?, driverName: String) {
        val old = entries[bookingId]?.takeIf { it.owner == owner }
        entries[bookingId] = Entry(owner, car?.takeIf { it.known } ?: old?.car, driverName.ifBlank { old?.driverName.orEmpty() })
    }

    @Synchronized
    private fun entry(bookingId: Int?, owner: Long): Entry? {
        entries.values.removeAll { it.owner != owner }
        return if (bookingId == null) null else entries[bookingId]
    }

    /** Подсказка экрану поездки: отменённая в пути бронь пассажира — экран помощи сразу, без опроса. */
    fun hint(bookingId: Int?, owner: Long): ActiveTripTerminalHint? =
        entry(bookingId, owner)?.let { knownTerminalTripHint(bookingId, "cancelled", "passenger", inTransit = true) }

    fun restoreInto(bookingId: Int?, owner: Long, state: CancelledInTransitState) {
        entry(bookingId, owner)?.let { state.restore(it.car, it.driverName) }
    }

    @Synchronized
    internal fun clearForTest() {
        entries.clear()
    }

    /** Для сообщений тестов: что помнит процесс (номер брони, вход, есть ли госномер) — без самих данных. */
    @Synchronized
    internal fun describeForTest(): String =
        entries.entries.joinToString { (id, e) -> "$id@${e.owner}:plate=${e.car?.plate?.isNotBlank() == true}" }
}

/** Состояние экрана поездки: признак из подсказки или из [InTransitCancels] — отмена уже была видна. */
@Composable
internal fun rememberCancelledInTransitState(bookingId: Int?, hint: ActiveTripTerminalHint?, owner: Long): CancelledInTransitState =
    remember(bookingId) {
        CancelledInTransitState(hint?.inTransit == true).also { InTransitCancels.restoreInto(bookingId, owner, it) }
    }

/**
 * Конец поездки на экране поездки (`done` / `cancelled`). Офлайн-паспорт с телефоном водителя
 * удаляется всегда; при отмене экран закрывается сам — кроме отмены в пути у пассажира (признак — из
 * /role или /details, что пришло первым; лишних запросов после конца поездки нет — их сторожат тесты
 * ActiveTripChatTerminalHttpTest). Удалить не вышло — окно с повтором; его «Закрыть» уводит
 * с экрана, но экран помощи после отмены в пути остаётся (человеку на трассе он нужнее копии).
 * [onPassDropped] — экран убирает паспорт из памяти, машина и номер из него остаются в [inTransit].
 */
@Composable
internal fun ActiveTripTerminalCleanup(
    bookingId: Int?,
    terminal: String?,
    tripSession: Long,
    role: String,
    inTransit: CancelledInTransitState,
    onPassDropped: () -> Unit,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    var removalFailed by remember(bookingId) { mutableStateOf(false) }
    var removalBusy by remember(bookingId) { mutableStateOf(false) }
    var removalRetry by remember(bookingId) { mutableIntStateOf(0) }
    val finish by rememberUpdatedState(onFinish)
    val dropPass by rememberUpdatedState(onPassDropped)
    val currentRole by rememberUpdatedState(role)
    // Observe server completion too, not only a local finish button. Keep this above the
    // completed-screen early return so failed durable writes can still be retried there.
    LaunchedEffect(bookingId, terminal, removalRetry) {
        val id = bookingId ?: return@LaunchedEffect
        if (terminal == null || tripSession != ApiClient.queueSessionGeneration()) return@LaunchedEffect
        dropPass()
        removalBusy = true
        try {
            val result = withContext(Dispatchers.IO) {
                TripPassStore.requestRemoval(context, id, expectedGeneration = tripSession)
            }
            if (tripSession != ApiClient.queueSessionGeneration()) return@LaunchedEffect
            removalFailed = result == TripPassStore.RemovalResult.NOT_SAVED
            if (removalFailed || terminal != "cancelled") return@LaunchedEffect
            // DEFERRED already has a durable barrier; inaccessible storage is cleaned on init.
            if (!inTransit.keepsScreen(currentRole)) finish()
        } finally {
            removalBusy = false
        }
    }
    if (removalFailed) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = CanonSurface,
            shape = CanonCardShape,
            title = { Text(appText("Не удалось удалить сохранённую поездку", "Һаҡланған сәфәрҙе юйып булманы")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
                    Text(appText(
                        "Поездка уже закрыта. Её копия может остаться на телефоне. Попробуй удалить ещё раз.",
                        "Сәфәр ябылған инде. Уның күсермәһе телефонда ҡалырға мөмкин. Тағы юйып ҡара.",
                    ))
                    if (removalBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = { removalRetry++ }, enabled = !removalBusy,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(appText("Повторить удаление", "Тағы юйырға"), color = CanonGreen2)
                }
            },
            dismissButton = {
                TextButton(onClick = { if (inTransit.keepsScreen(currentRole)) removalFailed = false else finish() },
                    enabled = !removalBusy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(appText("Закрыть", "Ябырға"))
                }
            },
        )
    }
}

/**
 * «Детали поездки» отменённой брони — до основного экрана: отмена в пути — экран помощи; статус
 * «отменена» пришёл из списка, а детали ещё грузятся — загрузка, а не мелькнувшая «Найти другую
 * поездку» (это может оказаться отмена в пути). true — экран уже нарисован здесь.
 */
@Composable
internal fun bookingCancelledOverride(
    details: BookingDetailsDto?,
    listStatus: String,
    loading: Boolean,
    onSos: () -> Unit,
    onBack: () -> Unit,
): Boolean {
    if (details != null && showsCancelledInTransit(details)) {
        CancelledInTransitScreen(details, onSos, onBack)
        return true
    }
    if (details == null && loading && listStatus == "cancelled") {
        Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Детали поездки", "Сәфәр тураһында"), onBack) }) { insets ->
            Box(Modifier.fillMaxSize().padding(insets).testTag("cancelledBookingLoading"), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CanonGreen2)
            }
        }
        return true
    }
    return false
}

/** «Детали поездки» (сюда ведёт пуш): детали уже прочитаны, водителя и окно разбора дочитаем из /role. */
@Composable
internal fun CancelledInTransitScreen(details: BookingDetailsDto, onSos: () -> Unit, onClose: () -> Unit) {
    val owner = remember(details.bookingId) { ApiClient.queueSessionGeneration() }
    val state = remember(details.bookingId) { CancelledInTransitState(true).apply { accept(details) } }
    CancelledInTransitScreen(details.bookingId, state, owner, onSos, onClose, loadRole = true)
}

/**
 * Экран отмены в пути. [onClose] — «Закрыть», стрелка и системное «Назад»: с экрана поездки это
 * тот же выход, что у обычной отмены (живое гео гаснет, человек — на карте).
 */
@Composable
internal fun CancelledInTransitScreen(
    bookingId: Int,
    state: CancelledInTransitState,
    ownerGeneration: Long,
    onSos: () -> Unit,
    onClose: () -> Unit,
    // Экран поездки: экран помощи на месте, а поездка кончилась — живое гео и маршрут на карте гаснут.
    onShown: () -> Unit = {},
    loadRole: Boolean = false,
    fetchDetails: suspend (Int) -> Result<BookingDetailsDto> = { ApiClient.getBookingDetails(it) },
    fetchRole: suspend (Int) -> Result<TripStateDto> = { ApiClient.getTripState(it) },
) {
    fun isCurrent() = ownerGeneration == ApiClient.queueSessionGeneration()
    var carLoading by remember(bookingId) { mutableStateOf(state.car == null) }
    var carFailed by remember(bookingId) { mutableStateOf(false) }
    var retry by remember(bookingId) { mutableIntStateOf(0) }
    LaunchedEffect(bookingId, retry) {
        if (state.car != null || !isCurrent()) { carLoading = false; return@LaunchedEffect }
        carLoading = true; carFailed = false
        val result = fetchDetails(bookingId)
        if (!isCurrent()) return@LaunchedEffect
        result.onSuccess { d -> if (d.bookingId == bookingId) state.accept(d) }
            .onFailure { e -> if (e is CancellationException) throw e; carFailed = true }
        carLoading = false
    }
    if (loadRole) LaunchedEffect(bookingId, retry) {
        val st = fetchRole(bookingId).getOrNull() ?: return@LaunchedEffect
        if (isCurrent()) state.accept(st)
    }
    val shown by rememberUpdatedState(onShown)
    LaunchedEffect(bookingId) { if (isCurrent()) shown() }
    // Запомнить на этот вход: вернулся с SOS или повернул экран без сети — экран помощи на месте.
    // SideEffect, а не LaunchedEffect: запись — в том же кадре, что экран помощи. Корутина эффекта стартует
    // кадром позже, и SOS, нажатый сразу, уводил с экрана раньше записи (CI PR207).
    SideEffect {
        if (isCurrent()) InTransitCancels.record(bookingId, ownerGeneration, state.car, state.driverName)
    }
    LaunchedEffect(state.reportSec) {
        val left = state.reportSec
        if (left > 0) {
            delay(left * 1000L)
            state.expireReport()
        }
    }
    BackHandler(onBack = onClose)
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Моя поездка", "Минең сәфәр"), onClose) }) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                .padding(CanonSpace.lg).testTag("cancelledInTransit"),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
        ) {
            CancelledInTransitHeader(Modifier.appearIn(0))
            InTransitCarCard(state.car, carLoading, carFailed, onRetry = { if (!carLoading) retry++ }, modifier = Modifier.appearIn(1))
            AppButton(
                appText("SOS — нужна помощь", "SOS — ярҙам кәрәк"), onClick = onSos,
                style = AppButtonStyle.Danger, icon = Icons.Default.Shield, modifier = Modifier.appearIn(2),
            )
            // Блок общий с поездкой, такси и доставкой (RoadsideHelp.kt); сервер принимает его и по отменённой брони.
            RoadsideHelpAction(key = bookingId, modifier = Modifier.appearIn(2), ownerGeneration = ownerGeneration,
                isCurrentParent = ::isCurrent) { lat, lng, generation ->
                ApiClient.roadsideHelp(bookingId, lat, lng, "", expectedGeneration = generation)
            }
            if (state.reportSec > 0 && state.driverId > 0) {
                InTransitProblemReport(bookingId, state.driverId, state.driverName, ownerGeneration, Modifier.appearIn(3))
            }
            AppButton(appText("Закрыть", "Ябырға"), onClick = onClose, style = AppButtonStyle.Secondary, modifier = Modifier.appearIn(3))
        }
    }
}

@Composable
private fun CancelledInTransitHeader(modifier: Modifier = Modifier) {
    // Тёплая плашка, а не красная: человеку нужна ясность, что делать, а не тревога.
    Surface(modifier = modifier.fillMaxWidth(), color = CanonWarnBg, shape = CanonCardShape) {
        Column(Modifier.padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(CanonSpace.sm))
                Text(
                    appText("Поездку отменили в пути", "Сәфәр юлда кире алынды"),
                    style = CanonHeading, color = CanonText, modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                appText(
                    "Если нужна помощь — нажми SOS или «Застрял на трассе». Машина и госномер — ниже.",
                    "Ярҙам кәрәк булһа — SOS йәки «Юлда ҡалдым» төймәһенә баҫ. Машина һәм дәүләт номеры — түбәндә.",
                ),
                style = CanonBody, color = CanonText,
            )
        }
    }
}

private enum class CarView { Loading, Failed, Empty, Known }

@Composable
private fun InTransitCarCard(car: InTransitCar?, loading: Boolean, failed: Boolean, onRetry: () -> Unit, modifier: Modifier) {
    val view = when {
        car?.known == true -> CarView.Known
        loading -> CarView.Loading
        failed -> CarView.Failed
        else -> CarView.Empty
    }
    AppCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(CanonSpace.sm))
                Text(appText("Машина и госномер", "Машина һәм дәүләт номеры"), style = CanonCaption, color = CanonMuted)
            }
            AnimatedContent(view, transitionSpec = {
                fadeIn(tween(CanonMotion.QUICK)) togetherWith fadeOut(tween(CanonMotion.QUICK))
            }, label = "in-transit-car") { shown ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                    when (shown) {
                        CarView.Known -> {
                            car?.car?.takeIf { it.isNotBlank() }?.let { Text(it, style = CanonBodyStrong, color = CanonText) }
                            car?.plate?.takeIf { it.isNotBlank() }?.let { Text(it, style = CanonTitle, color = CanonText) }
                        }
                        CarView.Loading -> {
                            Text(appText("Загружаем машину и госномер", "Машина һәм дәүләт номерын йөкләйбеҙ"), style = CanonCaption, color = CanonMuted)
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = CanonGreen2)
                        }
                        CarView.Failed -> {
                            Text(appText("Не удалось загрузить машину и госномер", "Машина һәм дәүләт номерын йөкләп булманы"), style = CanonCaption, color = CanonMuted)
                            AppButton(appText("Повторить", "Ҡабатлау"), onClick = onRetry, style = AppButtonStyle.Secondary, icon = Icons.Default.Refresh)
                        }
                        CarView.Empty -> Text(
                            appText("Водитель не указал машину и госномер", "Йөрөтөүсе машинаһын һәм дәүләт номерын күрһәтмәгән"),
                            style = CanonCaption, color = CanonMuted,
                        )
                    }
                }
            }
        }
    }
}

/** «Сообщить о проблеме» после отмены в пути: тип спора не подставляем — что случилось, знает человек. */
@Composable
private fun InTransitProblemReport(bookingId: Int, driverId: Int, driverName: String, ownerGeneration: Long, modifier: Modifier) {
    var open by remember(bookingId) { mutableStateOf(false) }
    var filed by remember(bookingId) { mutableStateOf(false) }
    AppCard(modifier.testTag("inTransitProblemReport")) {
        Column(Modifier.fillMaxWidth().padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
            Text(appText("Что-то было не так?", "Нимәлер дөрөҫ булманымы?"), style = CanonBodyStrong, color = CanonText)
            Text(
                appText(
                    "Если водитель высадил тебя без причины или в дороге что-то случилось — расскажи, разберёмся.",
                    "Йөрөтөүсе һине сәбәпһеҙ төшөрөп ҡалдырһа йәки юлда нимәлер булһа — һөйлә, тикшерербеҙ.",
                ),
                style = CanonCaption, color = CanonMuted,
            )
            AnimatedContent(filed, transitionSpec = {
                fadeIn(tween(CanonMotion.QUICK)) togetherWith fadeOut(tween(CanonMotion.QUICK))
            }, label = "in-transit-report") { done ->
                if (done) {
                    Text(
                        appText(
                            "Разбор открыт. Позовём водителя объясниться, а решение объясним обеим сторонам.",
                            "Ҡарау асылды. Йөрөтөүсене аңлатырға саҡырабыҙ, ә ҡарарҙы ике яҡҡа ла аңлатабыҙ.",
                        ),
                        style = CanonCaption, color = CanonGreen2,
                    )
                } else {
                    TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(appText("Сообщить о проблеме", "Проблема тураһында хәбәр итергә"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    if (open) {
        FileIncidentDialog(
            respondentId = driverId,
            respondentName = driverName.ifBlank { appText("водителя", "йөрөтөүсене") },
            bookingId = bookingId,
            onDismiss = { open = false },
            onFiled = { open = false; filed = true },
            ownerGeneration = ownerGeneration,
        )
    }
}
