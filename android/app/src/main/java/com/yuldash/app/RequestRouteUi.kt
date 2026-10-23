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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.RequestFeedDto
import com.yuldash.app.data.ResponseDto

internal fun requestOfferReady(request: RequestFeedDto, bind: Boolean?): Boolean = request.matchedRideId == null || bind != null

@Composable
internal fun RequestRouteOfferChoice(request: RequestFeedDto, bind: Boolean?, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    if (request.matchedRideId == null) return
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
        Text(appText("Как предложить поездку?", "Сәфәрҙе нисек тәҡдим итергә?"), style = CanonBodyStrong, color = CanonText)
        listOf(true, false).forEach { inRide ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                selected = bind == inRide, enabled = enabled, role = Role.RadioButton, onClick = { onChange(inRide) },
            ), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = bind == inRide, onClick = null, enabled = enabled,
                    colors = RadioButtonDefaults.colors(selectedColor = CanonGreen2, unselectedColor = CanonMuted))
                Text(if (inRide) appText("Место в моём рейсе", "Үҙ сәфәремдә урын") else appText("Отдельная поездка", "Айырым сәфәр"),
                    style = CanonBody, color = CanonText, modifier = Modifier.weight(1f).padding(start = CanonSpace.sm))
            }
        }
        MatchedRequestRouteNote(request)
        AnimatedContent(targetState = bind, label = "requestRideBinding") { selected ->
            Text(when (selected) {
                true -> appText("Пассажир займёт место в этом рейсе после принятия.", "Пассажир ҡабул иткәндән һуң был сәфәрҙә урын аласаҡ.")
                false -> appText("Предложишь отдельную поездку вне этого рейса.", "Был сәфәрҙән тыш айырым сәфәр тәҡдим итәһең.")
                null -> appText("Выбери рейс или отдельную поездку перед отправкой.", "Ебәрер алдынан сәфәрҙе йәки айырым сәфәрҙе һайла.")
            }, style = CanonCaption, color = CanonMuted)
        }
    }
}

@Composable
internal fun MatchedRequestRouteNote(request: RequestFeedDto) {
    if (request.matchedRideId == null) return
    RouteOfferSummary(request.matchedRideFromCity, request.matchedRideToCity, request.matchedRideDepartAt,
        request.matchedPickupStop, request.matchedDropoffStop)
}

@Composable
internal fun BoundResponseRouteNote(response: ResponseDto) {
    if (response.rideId == null) return
    RouteOfferSummary(response.rideFromCity, response.rideToCity, response.rideDepartAt, response.pickupStop, response.dropoffStop)
}

/** Departure belongs to the whole ride; an arrival time at an intermediate stop is not supplied. */
@Composable
private fun RouteOfferSummary(from: String, to: String, depart: String, pickup: String?, dropoff: String?) {
    Surface(color = CanonMint, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(CanonSpace.md), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
            Text(appText("Рейс: $from → $to", "Сәфәр: $from → $to"), style = CanonBodyStrong, color = CanonText)
            if (depart.isNotBlank()) Text(appText("Выезд рейса: ${formatDepart(depart)}", "Сәфәрҙең юлға сығыуы: ${formatDepart(depart)}"), style = CanonCaption, color = CanonMutedStrong)
            BookingSegmentNote(pickup, dropoff, true, from, to)
            Text(appText("Время посадки уточни в чате.", "Ултырыу ваҡытын чатта асыҡла."), style = CanonCaption, color = CanonMutedStrong)
        }
    }
}

@Composable
internal fun OwnRouteRequestsEmpty(hasOwnRides: Boolean?) {
    if (hasOwnRides == false) ListedEmpty(
        appText("Нет опубликованных рейсов", "Баҫтырылған сәфәрҙәр юҡ"),
        appText("Опубликуй рейс или выбери все заявки для отдельной поездки.", "Сәфәр баҫтыр йәки айырым сәфәр өсөн бөтә заявкаларҙы һайла."),
    ) else ListedEmpty(
        appText("По твоим маршрутам заявок пока нет", "Һинең юлдарың буйынса әлегә заявкалар юҡ"),
        appText("Проверяем опубликованные остановки, день и свободные места. Можно выбрать все заявки.", "Баҫтырылған туҡталыштарҙы, көндө һәм буш урындарҙы тикшерәбеҙ. Бөтә заявкаларҙы һайларға була."),
    )
}
