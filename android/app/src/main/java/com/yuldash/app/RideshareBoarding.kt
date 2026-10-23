package com.yuldash.app

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Pin
import androidx.compose.animation.animateContentSize
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.BoardingApi
import com.yuldash.app.data.BoardingResult
import com.yuldash.app.data.NoShowTerms
import com.yuldash.app.data.afterRefusal
import com.yuldash.app.data.TripReceiptDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Посадку отмечает ВОДИТЕЛЬ (решение Александра 17.10.2026). Раньше пассажир сам жал «Я сел» —
// за 30 минут до выезда и без всякой проверки: нажать можно было из дома, чтобы водитель уже не
// отметил «Не пришёл», а нажатое по ошибке было не вернуть. Теперь:
//  • водитель — «Сел» рядом с «Не пришёл» на карточке подтверждённого пассажира (кабинет) и
//    «Пассажир сел» на экране поездки; промахнулся карточкой — «Отменить» в первые 5 минут;
//    «Не пришёл» оживает через 15 минут после выезда или «Подъезжаю» — что позже (срок от сервера)
//    и спрашивает «Точно?»; на поездке с остановками до «Подъезжаю» на её месте — «Подъезжаю»;
//  • пассажир — «Водитель отметит посадку» и «Сообщить о проблеме»; код посадки виден ему,
//    пока водитель не отметил посадку (BoardingCodeRules.kt).
// Сервер — bookings.py (`_mark_boarded`, `undo_boarded`), сайт — DriverBoarding.tsx / BoardingWait.tsx.

/** Секунды → «4:59»: сколько ещё можно отменить отметку «Сел». */
internal fun boardingUndoClock(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * Карточка пассажира в «Едут с тобой»: подтверждённому — «Сел» рядом с «Не пришёл» (или «Подъезжаю»
 * на поездке с остановками, [ConfirmedPassengerControls]); «в пути» —
 * «Пассажир в машине» и «Отменить · 4:59», пока можно, потом — разбор. Итог действия виден сразу,
 * не дожидаясь, пока кабинет перечитает список: статус с сервера сменится — карточка начнёт с него.
 */
@Composable
internal fun DriverBoardingControls(
    bookingId: Int,
    // Без значения по умолчанию: новое место вызова обязано передать статус брони с сервера,
    // иначе «Не пришёл» снова появится у брони «в пути» (ревью Opus волны 1).
    bookingStatus: String,
    boardingUndoSec: Int,
    modifier: Modifier = Modifier,
    passengerId: Int = 0,
    passengerName: String = "",
    // С какого момента доступна «Не пришёл» (ISO UTC, `no_show_at` из /driver/bookings); null — срока нет / старый сервер.
    noShowAt: String? = null,
    // Поездка с остановками ждёт «Подъезжаю» по этой брони (`no_show_needs_arriving`, 18.10.2026).
    noShowNeedsArriving: Boolean = false,
    // С какого момента можно нажать «Подъезжаю» (`arriving_opens_at`: за 30 минут до выезда); null — старый сервер.
    arrivingOpensAt: String? = null,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var shown by remember(bookingId, bookingStatus) { mutableStateOf(bookingStatus) }
    var undoSec by remember(bookingId, bookingStatus, boardingUndoSec) { mutableIntStateOf(boardingUndoSec) }
    var marking by remember(bookingId) { mutableStateOf(false) }
    val failMsg = appText("Не получилось отметить. Проверь сеть.", "Билдәләп булманы. Селтәрҙе тикшер.")
    AnimatedContent(targetState = shown, modifier = modifier.fillMaxWidth(), label = "driverBoarding") { state ->
        when (state) {
            "confirmed" -> ConfirmedPassengerControls(
                bookingId = bookingId,
                noShowAt = noShowAt,
                noShowNeedsArriving = noShowNeedsArriving,
                arrivingOpensAt = arrivingOpensAt,
                marking = marking,
                onMark = {
                    if (!marking) {
                        marking = true
                        scope.launch {
                            BoardingApi.markBoarded(bookingId)
                                .onSuccess { r -> undoSec = r.undoSec; shown = r.status.ifBlank { "onboard" } }
                                .onFailure { e -> Toast.makeText(ctx, serverSaid(e, failMsg), Toast.LENGTH_LONG).show() }
                            marking = false
                        }
                    }
                },
                onNoShowSent = { shown = "no_show" },
                onBoarded = { sec -> undoSec = sec; shown = "onboard" },
            )
            "onboard" -> BoardingInCarRow(
                bookingId = bookingId,
                undoSec = undoSec,
                onUndone = { r -> undoSec = r.undoSec; shown = r.status.ifBlank { "confirmed" } },
            ) { BoardedDisputeBlock(bookingId, passengerId, passengerName) }
            "no_show" -> NoShowSentNote()
            else -> Box(Modifier)   // ждёт ответа / закрыта — ни посадке, ни неявке не место
        }
    }
}

/** Что стоит справа от «Сел» у подтверждённого пассажира. */
internal enum class NoShowSlot { Arriving, Waiting, Ready }

/**
 * Решение без часов и сети: поездка с остановками ждёт «Подъезжаю» — «Подъезжаю»; срок не наступил —
 * неактивная «Не пришёл» и подпись «можно отметить с …»; наступил или его нет — «Не пришёл».
 */
internal fun noShowSlot(needsArriving: Boolean, open: Boolean): NoShowSlot = when {
    needsArriving -> NoShowSlot.Arriving
    open -> NoShowSlot.Ready
    else -> NoShowSlot.Waiting
}

/**
 * Подтверждённый пассажир: «Сел» и справа — место «Не пришёл» (решения 17.10 и 18.10.2026).
 *
 * Место справа занято с первого кадра, «Сел» всегда в левой половине и не участвует ни в одной
 * анимации (приёмка PR182: раньше переход «подпись → кнопка» анимировал весь ряд, и «Сел» в момент
 * срока перемигивал и сжимался с полной ширины до половины). Меняется только содержимое справа:
 * «Подъезжаю» → неактивная «Не пришёл» → «Не пришёл» (плавная смена, цвет кнопки оживает), а
 * подпись под рядом выезжает и уезжает. Срок — от сервера: из строки списка, из ответа «Подъезжаю»
 * и из отказа «рано» ([NoShowTerms]); своих 15 минут карточка не держит.
 */
@Composable
private fun ConfirmedPassengerControls(
    bookingId: Int,
    noShowAt: String?,
    noShowNeedsArriving: Boolean,
    arrivingOpensAt: String?,
    marking: Boolean,
    onMark: () -> Unit,
    onNoShowSent: () -> Unit,
    onBoarded: (undoSec: Int) -> Unit,
) {
    var terms by remember(bookingId, noShowAt, noShowNeedsArriving, arrivingOpensAt) {
        mutableStateOf(NoShowTerms(noShowAt = noShowAt, needsArriving = noShowNeedsArriving, arrivingOpensAt = arrivingOpensAt))
    }
    val opensAt = remember(terms) { terms.opensAtMs() }
    val open by rememberNoShowOpen(opensAt)
    // «Подъезжаю» — не раньше чем за 30 минут до выезда (приёмка PR184); часы тикают, только пока ждём её.
    val arrivingAt = remember(terms) { if (terms.needsArriving) terms.arrivingOpensAtMs() else null }
    val arrivingOpen by rememberNoShowOpen(arrivingAt)
    val onRefused: (ApiException) -> Unit = { e -> terms.afterRefusal(e.detailCode, e.detail)?.let { terms = it } }
    val slot = noShowSlot(terms.needsArriving, open)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            AppButton(
                text = appText("Сел", "Ултырҙы"),
                onClick = onMark,
                modifier = Modifier.weight(1f).testTag("driverBoardedButton"),
                style = AppButtonStyle.Secondary,
                icon = Icons.Default.Check,
                loading = marking,
                fillWidth = false,
                height = 48.dp,
            )
            AnimatedContent(
                targetState = slot == NoShowSlot.Arriving,
                modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn(tween(CanonMotion.NORMAL)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
                label = "noShowSlot",
            ) { arriving ->
                if (arriving) {
                    ArrivingButton(
                        bookingId,
                        onArrived = { terms = it },
                        modifier = Modifier.fillMaxWidth().testTag("driverArrivingButton"),
                        locked = !arrivingOpen,
                        onRefused = onRefused,
                    )
                } else {
                    NoShowButton(
                        bookingId = bookingId,
                        onSent = onNoShowSent,
                        onBoarded = onBoarded,
                        modifier = Modifier.fillMaxWidth(),
                        locked = slot == NoShowSlot.Waiting,
                        onRefused = onRefused,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = slot != NoShowSlot.Ready,
            enter = fadeIn(tween(CanonMotion.NORMAL)) + expandVertically(tween(CanonMotion.NORMAL)),
            exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.NORMAL)),
        ) {
            AnimatedContent(targetState = terms.needsArriving, label = "noShowNote") { needs ->
                if (needs) {
                    NoShowNeedsArrivingNote(arrivingFrom = terms.arrivingOpensAt.takeIf { !arrivingOpen })
                } else {
                    NoShowWaitNote(terms.noShowAt)
                }
            }
        }
    }
}

/**
 * «Пассажир в машине» и «Отменить · 4:59», пока водитель может вернуть свою отметку. Отсчёт идёт
 * от секунд, что прислал сервер, и сверяется с ним при каждом новом ответе. Окно вышло —
 * под строкой то, что передали в [afterWindow] (в кабинете — разбор).
 */
@Composable
internal fun BoardingInCarRow(
    bookingId: Int,
    undoSec: Int,
    onUndone: (BoardingResult) -> Unit,
    modifier: Modifier = Modifier,
    afterWindow: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var left by remember(bookingId, undoSec) { mutableIntStateOf(undoSec) }
    var undoing by remember(bookingId) { mutableStateOf(false) }
    val failMsg = appText("Не получилось отменить. Проверь сеть.", "Кире алып булманы. Селтәрҙе тикшер.")
    LaunchedEffect(bookingId, left) {
        if (left > 0) {
            delay(1_000)
            left -= 1
        }
    }
    val clock = boardingUndoClock(left)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).background(CanonMint, CanonItemShape)
                .padding(start = CanonSpace.md, end = CanonSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(CanonSpace.sm))
            Text(
                appText("Пассажир в машине", "Пассажир машинала"),
                color = CanonGreen2, style = CanonBodyStrong, modifier = Modifier.weight(1f),
            )
            AnimatedVisibility(visible = left > 0) {
                TextButton(
                    onClick = {
                        if (!undoing) {
                            undoing = true
                            scope.launch {
                                BoardingApi.undoBoarded(bookingId)
                                    .onSuccess(onUndone)
                                    .onFailure { e ->
                                        // 409 — окно вышло или поездка ушла вперёд: дальше только разбор.
                                        if (e is ApiException && e.status == 409) left = 0
                                        Toast.makeText(ctx, serverSaid(e, failMsg), Toast.LENGTH_LONG).show()
                                    }
                                undoing = false
                            }
                        }
                    },
                    enabled = !undoing,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        appText("Отменить · $clock", "Кире алырға · $clock"),
                        color = CanonGreen2, style = CanonCaption, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        AnimatedVisibility(visible = left <= 0) { afterWindow() }
    }
}

/**
 * Где пассажиру показываем «Сообщить о проблеме» — разбор по брони с водителем (тип «Водитель
 * не приехал» уже выбран): до посадки, после отметки водителя и после поездки (приёмка PR174).
 */
internal enum class PassengerReportStage { Waiting, Marked, AfterTrip }

/**
 * «Сообщить о проблеме» пассажира — разбор по брони с водителем (водитель не приехал, отметил
 * не того, уехал без него). [driverId] — вторая сторона брони с сервера; 0 — старый сервер,
 * разбора нет (не с кем).
 *  • [PassengerReportStage.Waiting] — до посадки: «Водитель отметит посадку»;
 *  • [PassengerReportStage.Marked] — водитель отметил «Сел»: отмены у пассажира «в пути» нет
 *    (сервер отвечает `onboard_no_cancel`), и если он не садился — путь только этот;
 *  • [PassengerReportStage.AfterTrip] — экран завершённой поездки, пока сервер держит окно
 *    (`problem_report_sec`, 6 часов): водитель мог отметить не того и сразу закрыть поездку.
 */
@Composable
internal fun PassengerBoardingWait(
    bookingId: Int,
    driverId: Int,
    driverName: String,
    modifier: Modifier = Modifier,
    stage: PassengerReportStage = PassengerReportStage.Waiting,
) {
    var open by remember(bookingId) { mutableStateOf(false) }
    var filed by remember(bookingId) { mutableStateOf(false) }
    val title = when (stage) {
        PassengerReportStage.Waiting -> appText("Водитель отметит посадку", "Йөрөтөүсе ултырыуҙы билдәләр")
        PassengerReportStage.Marked -> appText("Водитель отметил посадку", "Йөрөтөүсе ултырыуҙы билдәләне")
        PassengerReportStage.AfterTrip -> appText("Не садился в машину?", "Машинаға ултырманыңмы?")
    }
    val caption = when (stage) {
        PassengerReportStage.Waiting -> appText(
            "Сверь машину и назови код. Когда сядешь, водитель отметит посадку в приложении.",
            "Машинаны тикшер һәм кодты әйт. Ултырғас, йөрөтөүсе ултырыуҙы ҡушымтала билдәләр.",
        )
        PassengerReportStage.Marked -> appText("Не садился — сообщи о проблеме.", "Ултырмаған булһаң — проблема тураһында хәбәр ит.")
        PassengerReportStage.AfterTrip -> appText(
            "Водитель отметил посадку без тебя или не приехал — сообщи о проблеме, разберёмся.",
            "Йөрөтөүсе һинһеҙ ултырыуҙы билдәләгән йәки килмәгән булһа — проблема тураһында хәбәр ит, тикшерербеҙ.",
        )
    }
    val respondent = driverName.ifBlank { appText("водителя", "йөрөтөүсене") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (stage) {
                    PassengerReportStage.Waiting -> Icons.Default.DirectionsCar
                    PassengerReportStage.Marked -> Icons.Default.CheckCircle
                    PassengerReportStage.AfterTrip -> Icons.Default.Info
                },
                contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(CanonSpace.sm))
            Text(title, color = CanonText, style = CanonBodyStrong)
        }
        Text(caption, color = CanonMuted, style = CanonCaption)
        AnimatedContent(targetState = filed, label = "boardingWaitReport") { done ->
            if (done) {
                Text(
                    appText(
                        "Разбор открыт. Позовём водителя объясниться, а решение объясним обеим сторонам.",
                        "Ҡарау асылды. Йөрөтөүсене аңлатырға саҡырабыҙ, ә ҡарарҙы ике яҡҡа ла аңлатабыҙ.",
                    ),
                    color = CanonGreen2, style = CanonCaption,
                )
            } else if (driverId > 0) {
                TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(appText("Сообщить о проблеме", "Проблема тураһында хәбәр итергә"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    if (open) {
        FileIncidentDialog(
            respondentId = driverId,
            respondentName = respondent,
            bookingId = bookingId,
            onDismiss = { open = false },
            onFiled = { open = false; filed = true },
            initialType = "driver_no_show",
        )
    }
}

/**
 * Кому пассажир сообщит о проблеме с экрана завершённой поездки; 0 — кнопки нет: водитель, окно
 * сервера (`problem_report_sec`, 6 часов) вышло или сервер старый и второй стороны не прислал.
 */
internal fun afterTripReportTo(receipt: TripReceiptDto?, isDriver: Boolean): Int =
    receipt?.takeIf { !isDriver && it.problemReportSec > 0 }?.counterpartyId ?: 0

/** Экран завершённой поездки: тот же «Сообщить о проблеме» в карточке, как соседние действия. */
@Composable
internal fun PassengerAfterTripReport(bookingId: Int, driverId: Int, driverName: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("rideshareProblemReport"),
        color = CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, CanonBorder),
    ) {
        PassengerBoardingWait(
            bookingId, driverId, driverName,
            modifier = Modifier.padding(CanonSpace.md),
            stage = PassengerReportStage.AfterTrip,
        )
    }
}

/**
 * Что показать над кнопками экрана поездки про посадку:
 *  • пассажиру до посадки — «Водитель отметит посадку» и «Сообщить о проблеме»;
 *  • пассажиру «в пути» — «Водитель отметил посадку. Не садился — сообщи о проблеме» (приёмка PR174);
 *  • водителю «в пути» — «Пассажир в машине» и «Отменить · 4:59», пока можно, потом — разбор
 *    «Если пассажир на самом деле не садился» (как в кабинете, Q4).
 * null — ничего: водитель до посадки отмечает её главной кнопкой «Пассажир сел».
 * [peerName] — имя второй стороны брони; пусто — в разборе «пассажира» / «водителя».
 */
internal fun activeTripBoardingSlot(
    role: String,
    bookingStatus: String,
    bookingId: Int?,
    peerId: Int,
    peerName: String,
    undoSec: Int,
    onUndone: (BoardingResult) -> Unit,
): (@Composable () -> Unit)? {
    if (bookingId == null) return null
    if (role == "passenger" && (bookingStatus == "confirmed" || bookingStatus == "onboard")) {
        val stage = if (bookingStatus == "onboard") PassengerReportStage.Marked else PassengerReportStage.Waiting
        val wait: @Composable () -> Unit = { PassengerBoardingWait(bookingId, peerId, peerName, stage = stage) }
        return wait
    }
    if (role == "driver" && bookingStatus == "onboard") {
        val inCar: @Composable () -> Unit = {
            BoardingInCarRow(bookingId, undoSec, onUndone) {
                BoardedDisputeBlock(bookingId, peerId, peerName.ifBlank { appText("пассажира", "юлсыны") })
            }
        }
        return inCar
    }
    return null
}

/** Код посадки на экране поездки: код, ошибка с повтором загрузки или «загружается» — без вечного спиннера. */
@Composable
internal fun ActiveTripBoardingCodeCard(code: String, role: String, loadFailed: Boolean, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        color = CanonGreen2,
        contentColor = CanonOnFilled,
        shape = CanonCardShape,
        shadowElevation = CanonDepth.raised,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 17.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(Icons.Default.Pin, contentDescription = null, modifier = Modifier.size(19.dp))
                Text(appText("Код посадки", "Ултырыу коды"), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            if (code.isNotBlank()) {
                Text(
                    code,
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                    letterSpacing = 8.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    if (role == "driver") {
                        appText("Попроси пассажира назвать код", "Пассажирҙан кодты әйтеүен һора")
                    } else {
                        appText("Назови код водителю перед посадкой", "Ултырыр алдынан кодты йөрөтөүсегә әйт")
                    },
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    textAlign = TextAlign.Center,
                )
            } else if (loadFailed) {
                Text(appText("Не удалось загрузить код", "Кодты йөкләп булманы"),
                    fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(appText("Повторить загрузку кода", "Кодты яңынан йөкләргә"), color = CanonOnFilled)
                }
            } else {
                Row(
                    Modifier.heightIn(min = 52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(19.dp), color = CanonOnFilled, strokeWidth = 2.dp)
                    Text(appText("Код загружается", "Код йөкләнә"), fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
