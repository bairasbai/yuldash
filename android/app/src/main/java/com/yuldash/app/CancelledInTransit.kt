package com.yuldash.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.BookingDetailsDto
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripStateDto
import kotlinx.coroutines.CancellationException

// Поездку отменили, когда пассажир уже был в машине (решение Александра 23.10.2026, сервер —
// `visibility.cancelled_in_transit`). Отменить «в пути» может только водитель или администратор:
// «сломалась машина», «высадил за поведение». Человек, возможно, стоит на трассе — поэтому экран
// поездки не закрывается сам (как при обычной отмене), а ведёт к помощи: SOS, «Застрял на трассе»,
// «Сообщить о проблеме» (окно сервера `problem_report_sec`, 6 ч от отмены), машина и госномер —
// по ним родные и полиция ищут машину. Телефона водителя и чата нет: сервер их после отмены не даёт,
// связь — через SOS и разбор. Экран общий: экран поездки и «Детали поездки», куда ведёт пуш.

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
            AnimatedContent(filed, label = "in-transit-report") { done ->
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
