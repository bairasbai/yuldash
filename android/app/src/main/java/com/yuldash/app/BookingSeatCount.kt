package com.yuldash.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
internal fun rememberBookingSeats(revision: Long, initial: Int) = key(revision) {
    rememberSaveable { mutableIntStateOf(initial) }
}

@Composable
internal fun rememberBookingPayAmount(ride: Ride, bookingId: Int?, seats: Int, perSeat: Int = ride.price): MutableState<String> =
    remember(ride.id, bookingId, seats, perSeat) { mutableStateOf(if (perSeat > 0) (perSeat * seats).toString() else "") }

internal fun canBookSeats(available: Int, selected: Int, busy: Boolean): Boolean = !busy && selected in 1..available

/** Кнопка брони называет, сколько мест берёт человек: «Забронировать 3 места» (приёмка #189). */
@Composable
internal fun bookSeatsLabel(seats: Int): String =
    if (seats > 1) appText("Забронировать $seats ${pluralRu(seats, "место", "места", "мест")}", "$seats урынды бронләү")
    else appText("Забронировать место", "Урынды бронләү")

internal fun LazyListScope.bookingSeatCountItem(ride: Ride, bookingId: Int?, selected: Int, busy: Boolean,
                                                pickupStop: String? = null, dropoffStop: String? = null,
                                                onChange: (Int) -> Unit, onFindAnother: () -> Unit = {}) {
    if (bookingId == null) {
        item(key = "booking-seat-count") {
            // RS-02 А/Б: доступно и цена — на ВЫБРАННОМ участке; нет данных — как раньше, весь рейс.
            BookingSeatCount(
                available = seatsOnSpan(ride, pickupStop, dropoffStop) ?: ride.seats,
                selected = selected,
                price = segmentPriceRub(ride, pickupStop, dropoffStop) ?: ride.price,
                enabled = !busy, onChange = onChange, onFindAnother = onFindAnother,
            )
        }
    }
}

@Composable
internal fun BookingSeatCount(available: Int, selected: Int, price: Int, enabled: Boolean,
                              onFindAnother: (() -> Unit)? = null, onChange: (Int) -> Unit) {
    AppCard {
        Column(Modifier.fillMaxWidth().padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Text(appText("Количество мест", "Урындар һаны"), style = CanonBodyStrong, color = CanonText)
            if (available < 1) {
                Text(
                    appText("Мест больше нет. Посмотри другие поездки.", "Буш урындар ҡалманы. Башҡа сәфәрҙәрҙе ҡара."),
                    style = CanonCaption, color = CanonMuted,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
                    IconButton(enabled = enabled && selected in 2..available, onClick = { onChange(selected - 1) }) {
                        Icon(Icons.Default.Remove, appText("Меньше мест", "Урындарҙы кәметеү"), tint = if (enabled && selected > 1) CanonGreen2 else CanonMuted)
                    }
                    AnimatedContent(targetState = selected, label = "bookingSeats") { count ->
                        Text(count.toString(), style = CanonHeading, color = CanonText)
                    }
                    IconButton(enabled = enabled && selected < available, onClick = { onChange(selected + 1) }) {
                        Icon(Icons.Default.Add, appText("Больше мест", "Урындарҙы арттырыу"), tint = if (enabled && selected < available) CanonGreen2 else CanonMuted)
                    }
                }
                if (selected > available) {
                    // Намерение группы не трём молча (RS-02 В): не clamp автоматически — отправка
                    // заблокирована (canBookSeats), а человек сам решает: уменьшить или сменить поездку.
                    Text(
                        appText("Свободных мест осталось $available — уменьшите число или посмотрите другие поездки.",
                                "Буш урындар $available ҡалды — һанын кәмет йәки башҡа сәфәрҙәрҙе ҡара."),
                        style = CanonCaption, color = CanonText,
                    )
                    androidx.compose.material3.TextButton(enabled = enabled, onClick = { onChange(available) }) {
                        Text(
                            appText("Уменьшить до $available", "$available тиклем кәметеү"),
                            style = CanonBodyStrong, color = CanonGreen2,
                        )
                    }
                }
                AnimatedContent(targetState = price * selected, label = "bookingTotal") { total ->
                    Text(
                        appText("Итого: ", "Бөтәһе: ") + kopToRub(rideshareRubToKop(total)),
                        style = CanonBodyStrong, color = CanonText,
                    )
                }
            }
            if (selected > available && onFindAnother != null) {
                androidx.compose.material3.TextButton(enabled = enabled, onClick = onFindAnother) {
                    Text(appText("Найти другую поездку", "Башҡа сәфәр табыу"), style = CanonBodyStrong, color = CanonGreen2)
                }
            }
        }
    }
}
