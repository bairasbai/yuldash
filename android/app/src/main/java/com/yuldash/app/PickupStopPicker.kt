package com.yuldash.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.BookingDetailsDto

internal fun LazyListScope.pickupStopItem(ride: Ride, bookingId: Int?, details: BookingDetailsDto?, picked: String?, onPick: (String) -> Unit) {
    item(key = "pickup-stop") {
        if (bookingId == null) PickupStopPicker(ride, picked, onPick)
        else details?.let { PickupStopNote(it.pickupStop, it.rideHasStops, it.fromCity) }
    }
}

/** The parent owns the choice so that submission and the visible selection cannot diverge. */
@Composable
internal fun PickupStopPicker(ride: Ride, picked: String?, onPick: (String) -> Unit, enabled: Boolean = true) {
    val stops = pickupStops(ride)
    if (stops.isEmpty()) return
    Surface(color = CanonSurface, shape = CanonItemShape) {
        Column(Modifier.fillMaxWidth().padding(CanonSpace.md).selectableGroup(), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
            Text(appText("Где садишься?", "Ҡайҙа ултыраһың?"), style = CanonBodyStrong, color = CanonText)
            Text(
                appText("Выбери начало маршрута или остановку по пути.", "Юлдың башын йәки юл ыңғайындағы туҡталышты һайла."),
                style = CanonCaption, color = CanonMuted,
            )
            (listOf(ride.from) + stops).forEach { place ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = picked == place, enabled = enabled, role = Role.RadioButton, onClick = { onPick(place) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = picked == place, onClick = null, enabled = enabled,
                        colors = RadioButtonDefaults.colors(selectedColor = CanonGreen2, unselectedColor = CanonMuted),
                    )
                    Text(
                        if (place == ride.from) appText("Начало маршрута · $place", "Юлдың башы · $place") else place,
                        modifier = Modifier.weight(1f).padding(start = CanonSpace.sm), style = CanonBody, color = CanonText,
                    )
                }
            }
            AnimatedContent(targetState = picked == null, label = "pickupStopChoice") { missing ->
                if (missing) Text(
                    appText("Выбери место посадки, чтобы забронировать.", "Бронлау өсөн ултырыу урынын һайла."),
                    style = CanonCaption, color = CanonMuted,
                )
            }
        }
    }
}

/** Private values come only from booking APIs; public ride data cannot establish a passenger's choice. */
@Composable
internal fun PickupStopNote(pickupStop: String?, rideHasStops: Boolean, from: String = "") {
    if (pickupStop == null && !rideHasStops) return
    val text = when {
        pickupStop == null -> appText("Место посадки не указано — уточни в чате", "Ултырыу урыны күрһәтелмәгән — чатта асыҡла")
        pickupStop.isEmpty() -> appText("Посадка: начало маршрута", "Ултырыу: юлдың башы") + if (from.isNotBlank()) " · $from" else ""
        else -> appText("Посадка: $pickupStop", "Ултырыу: $pickupStop")
    }
    Text(text, style = CanonCaption, color = CanonMuted)
}

@Composable
internal fun MatchedPickupStopNote(ride: Ride) {
    val stop = initialPickupStop(ride) ?: return
    Text(
        if (ride.matchedFromStart && stop == ride.from) appText("Посадка: начало маршрута", "Ултырыу: юлдың башы")
        else appText("Посадка по пути: $stop", "Юл ыңғайында ултырыу: $stop"),
        style = CanonCaption, color = CanonMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun NearbyRideTrustOrPickup(ride: com.yuldash.app.data.RideDto) {
    val uiRide = ride.toUiRide()
    if (initialPickupStop(uiRide) != null) MatchedPickupStopNote(uiRide)
    else CompactTrustLine(trips = ride.driverTrips, since = ride.driverSince)
}
