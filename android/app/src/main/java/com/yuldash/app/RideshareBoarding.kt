package com.yuldash.app

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.BoardingApi
import com.yuldash.app.data.BoardingResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Посадку отмечает ВОДИТЕЛЬ (решение Александра 17.10.2026). Раньше пассажир сам жал «Я сел» —
// за 30 минут до выезда и без всякой проверки: нажать можно было из дома, чтобы водитель уже не
// отметил «Не пришёл», а нажатое по ошибке было не вернуть. Теперь:
//  • водитель — «Сел» рядом с «Не пришёл» на карточке подтверждённого пассажира (кабинет) и
//    «Пассажир сел» на экране поездки; промахнулся карточкой — «Отменить» в первые 5 минут;
//  • пассажир — «Водитель отметит посадку» и «Сообщить о проблеме»; код посадки виден ему,
//    пока водитель не отметил посадку (BoardingCodeRules.kt).
// Сервер — bookings.py (`_mark_boarded`, `undo_boarded`), сайт — DriverBoarding.tsx / BoardingWait.tsx.

/** Секунды → «4:59»: сколько ещё можно отменить отметку «Сел». */
internal fun boardingUndoClock(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * Карточка пассажира в «Едут с тобой»: подтверждённому — «Сел» рядом с «Не пришёл»; «в пути» —
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
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var shown by remember(bookingId, bookingStatus) { mutableStateOf(bookingStatus) }
    var undoSec by remember(bookingId, bookingStatus, boardingUndoSec) { mutableIntStateOf(boardingUndoSec) }
    var marking by remember(bookingId) { mutableStateOf(false) }
    val failMsg = appText("Не получилось отметить. Проверь сеть.", "Билдәләп булманы. Селтәрҙе тикшер.")
    AnimatedContent(targetState = shown, modifier = modifier.fillMaxWidth(), label = "driverBoarding") { state ->
        when (state) {
            "confirmed" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                AppButton(
                    text = appText("Сел", "Ултырҙы"),
                    onClick = {
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
                    modifier = Modifier.weight(1f),
                    style = AppButtonStyle.Secondary,
                    icon = Icons.Default.Check,
                    loading = marking,
                    fillWidth = false,
                    height = 48.dp,
                )
                NoShowButton(
                    bookingId = bookingId,
                    onSent = { shown = "no_show" },
                    onBoarded = { undoSec = 0; shown = "onboard" },
                    modifier = Modifier.weight(1f),
                )
            }
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
 * Пассажир до посадки: «Водитель отметит посадку» и «Сообщить о проблеме» — разбор по брони с
 * водителем (водитель не приехал, отметил не того). [driverId] — вторая сторона брони с сервера;
 * 0 — старый сервер, разбора нет (не с кем).
 */
@Composable
internal fun PassengerBoardingWait(bookingId: Int, driverId: Int, driverName: String, modifier: Modifier = Modifier) {
    var open by remember(bookingId) { mutableStateOf(false) }
    var filed by remember(bookingId) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(CanonSpace.sm))
            Text(appText("Водитель отметит посадку", "Йөрөтөүсе ултырыуҙы билдәләр"), color = CanonText, style = CanonBodyStrong)
        }
        Text(
            appText(
                "Сверь машину и назови код. Когда сядешь, водитель отметит посадку в приложении.",
                "Машинаны тикшер һәм кодты әйт. Ултырғас, йөрөтөүсе ултырыуҙы ҡушымтала билдәләр.",
            ),
            color = CanonMuted, style = CanonCaption,
        )
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
            respondentName = driverName,
            bookingId = bookingId,
            onDismiss = { open = false },
            onFiled = { open = false; filed = true },
        )
    }
}

/**
 * Что показать над кнопками экрана поездки про посадку:
 *  • пассажиру до посадки — «Водитель отметит посадку» и «Сообщить о проблеме»;
 *  • водителю «в пути» — «Пассажир в машине» и «Отменить · 4:59», пока можно.
 * null — ничего: водитель до посадки отмечает её главной кнопкой «Пассажир сел».
 */
internal fun activeTripBoardingSlot(
    role: String,
    bookingStatus: String,
    bookingId: Int?,
    peerId: Int,
    driverName: String,
    undoSec: Int,
    onUndone: (BoardingResult) -> Unit,
): (@Composable () -> Unit)? {
    if (bookingId == null) return null
    if (role == "passenger" && bookingStatus == "confirmed") {
        val wait: @Composable () -> Unit = { PassengerBoardingWait(bookingId, peerId, driverName) }
        return wait
    }
    if (role == "driver" && bookingStatus == "onboard") {
        val inCar: @Composable () -> Unit = { BoardingInCarRow(bookingId, undoSec, onUndone) }
        return inCar
    }
    return null
}
