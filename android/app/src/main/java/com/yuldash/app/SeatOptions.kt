package com.yuldash.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.BookingDetailsDto

// Опции мест в машине (2026-10-14), как у BlaBlaCar, без схемы кресел:
// водитель — «сзади максимум двое» и «переднее место свободно», пассажир — «хочу переднее».
// Правду держит сервер (потолок мест, одно переднее на поездку); здесь — показ и подсказки.

/** Больше мест в попутку не берут (минивэн). Зеркало SEATS_MAX в backend/app/routers/rides.py. */
internal const val SEATS_MAX = 8

/** Сколько пассажирских мест можно предложить при опциях. Зеркало `seat_cap` на сервере:
 *  «сзади двое» → 2 сзади + 1 спереди = 3; спереди свой человек → только 2. */
internal fun seatCap(backMaxTwo: Boolean, frontSeatAvailable: Boolean): Int =
    if (!backMaxTwo) SEATS_MAX else 2 + if (frontSeatAvailable) 1 else 0

/** Число мест из поля формы, урезанное под опции. Пустое поле не трогаем — его проверит форма. */
internal fun clampSeatsText(seats: String, backMaxTwo: Boolean, frontSeatAvailable: Boolean): String {
    val n = seats.toIntOrNull() ?: return seats
    return n.coerceAtMost(seatCap(backMaxTwo, frontSeatAvailable)).toString()
}

/** Подсказка под переключателями: что будет с числом мест при этих опциях. */
@Composable
internal fun seatOptionsHint(backMaxTwo: Boolean, frontSeatAvailable: Boolean): String = when {
    !backMaxTwo && frontSeatAvailable -> appText(
        "Обычно 4 места: 1 спереди и 3 сзади. Включи, если сзади поедут только двое — будет 3 места",
        "Ғәҙәттә 4 урын: 1 алда һәм 3 артта. Артта икәү генә барһа, ҡабыҙ — 3 урын булыр",
    )
    !backMaxTwo -> appText(
        "Спереди едет свой человек — пассажиры сядут сзади",
        "Алда үҙ кешең бара — юлсылар артҡа ултырыр",
    )
    frontSeatAvailable -> appText(
        "Сзади поедут двое и один спереди — мест не больше 3",
        "Артта икәү, алда бер кеше барыр — 3 урындан артыҡ түгел",
    )
    else -> appText(
        "Сзади поедут двое, спереди — свой человек: мест не больше 2",
        "Артта икәү барыр, алда — үҙ кешең: 2 урындан артыҡ түгел",
    )
}

/** Карточка «Места в машине» в форме поездки: два переключателя в стиле соседних условий. */
@Composable
internal fun SeatOptionsCard(
    backMaxTwo: Boolean,
    frontSeatAvailable: Boolean,
    onBackMaxTwo: (Boolean) -> Unit,
    onFrontSeatAvailable: (Boolean) -> Unit,
) {
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.padding(vertical = CanonSpace.xs)) {
            Text(
                appText("Места в машине", "Машинала урындар"),
                modifier = Modifier.padding(horizontal = CanonSpace.md, vertical = CanonSpace.sm),
                style = CanonBodyStrong, color = CanonText,
            )
            PrefToggleRow(Icons.Default.People, appText("Сзади максимум двое", "Артта күп тигәндә икәү"), backMaxTwo, onBackMaxTwo)
            PrefToggleRow(
                Icons.Default.AirlineSeatReclineNormal,
                appText("Переднее место свободно", "Алғы урын буш"),
                frontSeatAvailable, onFrontSeatAvailable,
            )
            // Подсказка меняется вместе с переключателями — мягкой сменой, а не скачком.
            AnimatedContent(targetState = seatOptionsHint(backMaxTwo, frontSeatAvailable), label = "seatHint") { hint ->
                Text(
                    hint,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = CanonSpace.md).padding(bottom = CanonSpace.sm),
                    style = CanonCaption, color = CanonMuted,
                )
            }
        }
    }
}

/**
 * «Хочу переднее место» в брони пассажира. Три состояния, все честные заранее:
 * переднее свободно — переключатель; уже обещано другому — так и говорим; водитель
 * его не предлагает (спереди свой человек) — тоже говорим, чтобы не нажимать впустую.
 */
@Composable
internal fun FrontSeatRequestBlock(
    frontSeatAvailable: Boolean,
    frontSeatTaken: Boolean,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(vertical = CanonSpace.xs)) {
            when {
                !frontSeatAvailable -> FrontSeatNote(
                    appText("Переднее место занято своим", "Алғы урында йөрөтөүсенең үҙ кешеһе"),
                    appText("Спереди едет человек водителя — для пассажиров места сзади.",
                        "Алда йөрөтөүсенең үҙ кешеһе бара — юлсыларға урын артта."),
                )
                frontSeatTaken -> FrontSeatNote(
                    appText("Переднее место уже занято", "Алғы урын инде алынған"),
                    appText("Его попросил другой пассажир. Сзади места есть.",
                        "Уны башҡа юлсы һораны. Артта урын бар."),
                )
                else -> {
                    PrefToggleRow(
                        Icons.Default.AirlineSeatReclineNormal,
                        appText("Хочу переднее место", "Алғы урынды теләйем"),
                        checked, onChecked,
                    )
                    Text(
                        appText("Переднее одно на машину — достанется тому, кто попросил первым.",
                            "Алғы урын машинала берәү — уны тәүге һораған кеше ала."),
                        modifier = Modifier.padding(horizontal = CanonSpace.md).padding(bottom = CanonSpace.sm),
                        style = CanonCaption, color = CanonMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun FrontSeatNote(title: String, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = CanonSpace.md, vertical = CanonSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = CanonMint, shape = CanonFieldShape) {
            Icon(
                Icons.Default.AirlineSeatReclineNormal, contentDescription = null, tint = CanonGreen2,
                modifier = Modifier.padding(CanonSpace.sm).size(20.dp),
            )
        }
        Spacer(Modifier.width(CanonSpace.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
            Text(title, style = CanonBodyStrong, color = CanonText)
            Text(text, style = CanonCaption, color = CanonMuted)
        }
    }
}

/**
 * Пункт экрана брони про переднее место: до брони — просьба (или честное «занято»),
 * после — пометка в самой брони (пассажиру — что просил, водителю — что просят).
 * Живёт здесь, а не в BookingActiveTripScreen.kt: большой файл не растёт (FileSizeRatchetTest).
 */
internal fun LazyListScope.frontSeatItem(
    ride: Ride,
    bookingId: Int?,
    details: BookingDetailsDto?,
    frontSeat: Boolean,
    onFrontSeatChange: (Boolean) -> Unit,
) {
    if (bookingId == null) {
        item(key = "front-seat") {
            FrontSeatRequestBlock(ride.frontSeatAvailable, ride.frontSeatTaken, frontSeat, onFrontSeatChange)
        }
    } else if (details?.frontSeat == true) {
        item(key = "front-seat") {
            InfoCard(
                title = appText("Переднее место", "Алғы урын"),
                text = if (details?.role == "driver")
                    appText("Пассажир просит переднее место.", "Юлсы алғы урынды һорай.")
                else
                    appText("Ты просишь переднее место — водитель это видит.", "Һин алғы урынды һорайһың — йөрөтөүсе быны күрә."),
                icon = Icons.Default.AirlineSeatReclineNormal,
            )
        }
    }
}

/** Два тумблера опций мест без рамки — для диалога правки поездки у водителя. */
@Composable
internal fun SeatOptionsToggles(
    backMaxTwo: Boolean,
    frontSeatAvailable: Boolean,
    onBackMaxTwo: (Boolean) -> Unit,
    onFrontSeatAvailable: (Boolean) -> Unit,
) {
    Column {
        PrefToggleRow(Icons.Default.People, appText("Сзади максимум двое", "Артта күп тигәндә икәү"), backMaxTwo, onBackMaxTwo)
        PrefToggleRow(Icons.Default.AirlineSeatReclineNormal, appText("Переднее место свободно", "Алғы урын буш"), frontSeatAvailable, onFrontSeatAvailable)
        Text(seatOptionsHint(backMaxTwo, frontSeatAvailable), style = CanonMicro, color = CanonMuted)
    }
}

/** Метка «просит переднее место» в карточке заявки у водителя — видна до решения. */
@Composable
internal fun FrontSeatRequestBadge(modifier: Modifier = Modifier) {
    Surface(color = CanonMint, shape = CanonTinyShape, modifier = modifier) {
        Row(
            Modifier.padding(horizontal = CanonSpace.sm, vertical = CanonSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.AirlineSeatReclineNormal, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(CanonSpace.xs))
            Text(appText("Просит переднее место", "Алғы урынды һорай"), style = CanonMicro, color = CanonGreen2)
        }
    }
}
