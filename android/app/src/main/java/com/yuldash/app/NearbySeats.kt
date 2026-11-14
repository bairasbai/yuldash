package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.yuldash.app.data.NearbyPage

internal val NearbyGroupSizes = 1..8

internal data class SmallerNearbyGroup(val seats: Int, val rides: Int)

internal fun NearbyPage.smallerGroup(requested: Int): SmallerNearbyGroup? =
    if (maxFreeSeats in 1 until requested && countAtMaxFree in 1..hiddenBySeats)
        SmallerNearbyGroup(maxFreeSeats, countAtMaxFree) else null

@Composable
internal fun NearbySeatsChip(seats: Int, onChange: (Int) -> Unit, total: Int?, modifier: Modifier, owner: Long) {
    var open by remember(owner) { mutableStateOf(false) }
    NearbyFilterChip(Icons.Default.People, appText("Мест: $seats", "Урын: $seats"), seats > 1, modifier = modifier) { open = true }
    if (open) {
        NearbySheet(appText("Сколько мест нужно", "Нисә урын кәрәк"), onDismiss = { open = false }) { close ->
            NearbyGroupSizeControl(seats, onChange)
            AppButton(if (total == null) appText("Готово", "Әҙер") else showRidesLabel(total), close)
        }
    }
}

@Composable
internal fun NearbyGroupSizeSetting(seats: Int, onChange: (Int) -> Unit) {
    AppCard {
        Column(Modifier.fillMaxWidth().padding(CanonSpace.md), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Text(appText("Сколько мест обычно нужно", "Ғәҙәттә нисә урын кәрәк"), color = CanonText, style = CanonBodyStrong)
            NearbyGroupSizeControl(seats, onChange)
        }
    }
}

@Composable
private fun NearbyGroupSizeControl(seats: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
        IconButton(enabled = seats > NearbyGroupSizes.first, onClick = { onChange(seats - 1) }) {
            Icon(Icons.Default.Remove, appText("Меньше мест", "Урындарҙы кәметеү"))
        }
        Text(seats.toString(), color = CanonText, style = CanonTitle)
        IconButton(enabled = seats < NearbyGroupSizes.last, onClick = { onChange(seats + 1) }) {
            Icon(Icons.Default.Add, appText("Больше мест", "Урындарҙы арттырыу"))
        }
    }
}

@Composable
internal fun NearbySeatsHint(seats: Int) {
    Text(
        appText("Показаны поездки, где свободно $seats ${pluralRu(seats, "место", "места", "мест")} и больше.",
            "$seats һәм күберәк буш урынлы сәфәрҙәр күрһәтелә."),
        color = CanonMuted, style = CanonCaption,
    )
}

@Composable
internal fun NearbySeatsCard(requested: Int, group: SmallerNearbyGroup, onSearch: (Int) -> Unit, onWatch: () -> Unit) {
    val rides = group.rides
    val seats = group.seats
    Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
        Text(
            appText("Для группы из $requested поездок нет. Есть $rides ${pluralRu(rides, "поездка", "поездки", "поездок")}, где свободно $seats ${pluralRu(seats, "место", "места", "мест")}.",
                "$requested кешелек төркөмгә сәфәр юҡ. $seats буш урынлы $rides сәфәр бар."),
            color = CanonMuted, style = CanonBody,
        )
        AppButton(appText("Искать на $seats", "$seats урынға эҙләү"), { onSearch(seats) })
        NearbySeatsWatch(onWatch)
    }
}

@Composable
internal fun NearbySeatsWatch(onWatch: () -> Unit) {
    TextButton(onClick = onWatch) {
        Text(appText("Следить за маршрутом", "Маршрутты күҙәтеү"), color = CanonGreen2, style = CanonBodyStrong)
    }
}

@Composable
internal fun NearbyResetFilters(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(appText("Сбросить фильтры", "Фильтрҙы бушат"), color = CanonGreen2, style = CanonBodyStrong)
    }
}
