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

internal fun LazyListScope.pickupStopItem(
    ride: Ride, bookingId: Int?, details: BookingDetailsDto?, picked: String?, onPick: (String) -> Unit,
    dropoff: String? = null, onDropoff: (String) -> Unit = {},
) {
    item(key = "pickup-stop") {
        if (bookingId == null) BookingSegmentPicker(ride, picked, dropoff, onPick, onDropoff)
        else details?.let { BookingSegmentNote(it.pickupStop, it.dropoffStop, it.rideHasStops, it.fromCity, it.toCity) }
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
    if (ride.matchedStop == null && !ride.matchedFromStart && ride.matchedDropoffStop == null && !ride.matchedToEnd) return
    val stop = initialPickupStop(ride) ?: return
    val dropoff = initialDropoffStop(ride)
    Text(
        if (ride.matchedFromStart && stop == ride.from) appText("Посадка: начало маршрута", "Ултырыу: юлдың башы")
        else appText("Посадка по пути: $stop", "Юл ыңғайында ултырыу: $stop"),
        style = CanonCaption, color = CanonMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    dropoff?.let { DropoffStopNote(it, ride.to) }
}

@Composable
internal fun NearbyRideTrustOrPickup(ride: com.yuldash.app.data.RideDto) {
    val uiRide = ride.toUiRide()
    if (uiRide.matchedStop != null || uiRide.matchedFromStart || uiRide.matchedDropoffStop != null || uiRide.matchedToEnd) MatchedPickupStopNote(uiRide)
    else CompactTrustLine(trips = ride.driverTrips, since = ride.driverSince)
}

/** Both choices share one parent state; changing boarding can invalidate the alighting point. */
@Composable
internal fun BookingSegmentPicker(
    ride: Ride, pickup: String?, dropoff: String?, onPickup: (String) -> Unit, onDropoff: (String) -> Unit,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
        Text(appText("Водитель увидит, где тебя забрать и высадить.", "Йөрөтөүсе һине ҡайҙан алырға һәм ҡайҙа төшөрөргә икәнен күрер."), style = CanonCaption, color = CanonMuted)
        if (pickupStops(ride).isEmpty()) {
            Text(appText("Посадка: ${ride.from}", "Ултырыу: ${ride.from}"), style = CanonBody, color = CanonText)
            Text(appText("Высадка: ${ride.to}", "Төшөү: ${ride.to}"), style = CanonBody, color = CanonText)
        } else {
            PickupStopPicker(ride, pickup, onPickup, enabled)
            Surface(color = CanonSurface, shape = CanonItemShape) {
                Column(Modifier.fillMaxWidth().padding(CanonSpace.md).selectableGroup(), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                    Text(appText("Где выходишь?", "Ҡайҙа төшәһең?"), style = CanonBodyStrong, color = CanonText)
                    Text(appText("Выбери остановку после посадки или конец маршрута.", "Ултырғандан һуңғы туҡталышты йәки юлдың аҙағын һайла."), style = CanonCaption, color = CanonMuted)
                    val choices = dropoffStops(ride, pickup)
                    if (choices.isEmpty()) Text(appText("Сначала выбери место посадки.", "Башта ултырыу урынын һайла."), style = CanonCaption, color = CanonMuted)
                    choices.forEach { place ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                            selected = dropoff == place, enabled = enabled, role = Role.RadioButton, onClick = { onDropoff(place) },
                        ), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = dropoff == place, onClick = null, enabled = enabled,
                                colors = RadioButtonDefaults.colors(selectedColor = CanonGreen2, unselectedColor = CanonMuted))
                            Text(if (place == ride.to) appText("Конец маршрута · $place", "Юлдың аҙағы · $place") else place,
                                modifier = Modifier.weight(1f).padding(start = CanonSpace.sm), style = CanonBody, color = CanonText)
                        }
                    }
                    AnimatedContent(targetState = dropoff == null && choices.isNotEmpty(), label = "dropoffStopChoice") { missing ->
                        if (missing) Text(appText("Выбери место высадки, чтобы забронировать.", "Бронлау өсөн төшөү урынын һайла."), style = CanonCaption, color = CanonMuted)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DropoffStopNote(dropoffStop: String?, to: String = "") {
    val text = when {
        dropoffStop == null -> appText("Место высадки не указано — уточни в чате", "Төшөү урыны күрһәтелмәгән — чатта асыҡла")
        dropoffStop.isEmpty() -> appText("Высадка: конец маршрута", "Төшөү: юлдың аҙағы") + if (to.isNotBlank()) " · $to" else ""
        else -> appText("Высадка: $dropoffStop", "Төшөү: $dropoffStop")
    }
    Text(text, style = CanonCaption, color = CanonMuted)
}

@Composable
internal fun BookingSegmentNote(pickup: String?, dropoff: String?, hasStops: Boolean, from: String = "", to: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
        val boarding = pickup?.ifEmpty { from }?.takeIf(String::isNotBlank)
        val alighting = dropoff?.ifEmpty { to }?.takeIf(String::isNotBlank)
        if (boarding != null && alighting != null) Text(appText("Участок: $boarding → $alighting", "Юл өлөшө: $boarding → $alighting"), style = CanonBodyStrong, color = CanonText)
        PickupStopNote(pickup, hasStops, from)
        DropoffStopNote(dropoff, to)
    }
}
