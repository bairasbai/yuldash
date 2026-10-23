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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
internal fun rememberBookingPayAmount(ride: Ride, bookingId: Int?, seats: Int): MutableState<String> =
    remember(ride.id, bookingId, seats) { mutableStateOf(if (ride.price > 0) (ride.price * seats).toString() else "") }

internal fun canBookSeats(available: Int, selected: Int, busy: Boolean): Boolean = !busy && selected in 1..available

/** Кнопка брони называет, сколько мест берёт человек: «Забронировать 3 места» (приёмка #189). */
@Composable
internal fun bookSeatsLabel(seats: Int): String =
    if (seats > 1) appText("Забронировать $seats ${pluralRu(seats, "место", "места", "мест")}", "$seats урынды бронләү")
    else appText("Забронировать место", "Урынды бронләү")

internal fun LazyListScope.bookingSeatCountItem(ride: Ride, bookingId: Int?, selected: Int, busy: Boolean, onChange: (Int) -> Unit) {
    if (bookingId == null) {
        item(key = "booking-seat-count") {
            BookingSeatCount(available = ride.seats, selected = selected, price = ride.price, enabled = !busy, onChange = onChange)
        }
    }
}

@Composable
internal fun BookingSeatCount(available: Int, selected: Int, price: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    // Поездку обновили, и свободных мест стало меньше выбранного — уменьшаем число, а не гасим кнопку молча.
    LaunchedEffect(available, selected, enabled) { if (enabled && available in 1 until selected) onChange(available) }
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
                    IconButton(enabled = enabled && selected > 1, onClick = { onChange((selected - 1).coerceIn(1, available)) }) {
                        Icon(Icons.Default.Remove, appText("Меньше мест", "Урындарҙы кәметеү"), tint = if (enabled && selected > 1) CanonGreen2 else CanonMuted)
                    }
                    AnimatedContent(targetState = selected, label = "bookingSeats") { count ->
                        Text(count.toString(), style = CanonHeading, color = CanonText)
                    }
                    IconButton(enabled = enabled && selected < available, onClick = { onChange((selected + 1).coerceIn(1, available)) }) {
                        Icon(Icons.Default.Add, appText("Больше мест", "Урындарҙы арттырыу"), tint = if (enabled && selected < available) CanonGreen2 else CanonMuted)
                    }
                }
                Text(
                    appText("Итого: ", "Бөтәһе: ") + kopToRub(price * selected * 100),
                    style = CanonBodyStrong, color = CanonText,
                )
            }
        }
    }
}
