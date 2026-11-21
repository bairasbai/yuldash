package com.yuldash.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.yuldash.app.data.RideSeriesIntent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RideDatesPicker(
    initial: List<String>,
    minimum: Int,
    maximum: Int = RideSeriesIntent.MAX_DATES,
    unavailable: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val now = remember { LocalDateTime.now() }
    val initialValues = remember(initial) { initial.mapNotNull(::parseRideDeparture) }
    val time = remember(initialValues) { initialValues.firstOrNull()?.toLocalTime() ?: now.plusHours(3).toLocalTime() }
    var month by remember(initialValues) { mutableStateOf(YearMonth.from(initialValues.firstOrNull() ?: now)) }
    var dates by remember(initialValues) { mutableStateOf(initialValues.map { it.toLocalDate() }.toSet()) }
    var hour by remember(time) { mutableStateOf("%02d".format(Locale.US, time.hour)) }
    var minute by remember(time) { mutableStateOf("%02d".format(Locale.US, time.minute)) }
    val selectedTime = rideDepartureTime(hour, minute)
    val selected = if (selectedTime == null) emptyList() else dates.sorted().map { formatRideDeparture(it, selectedTime) }
    val conflicts = selected.any { RideSeriesIntent.minute(publicationDeparture(it)) in unavailable }
    val months = rideDepartureMonths()
    val title = appText("Даты отправления", "Юлға сығыу көндәре")
    val today = now.toLocalDate()
    val lastMonth = YearMonth.of(today.year + RIDE_DEPARTURE_YEARS_AHEAD, 12)
    val weekdays = listOf(
        appText("Пн", "Дш"), appText("Вт", "Шш"), appText("Ср", "Шр"), appText("Чт", "Кс"),
        appText("Пт", "Йм"), appText("Сб", "Шб"), appText("Вс", "Йш"),
    )
    AppModalBottomSheet(onDismissRequest = onDismiss, containerColor = CanonSurface) {
        ResizeWindowForKeyboard()
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(CanonSpace.md),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Text(title, style = CanonTitle, color = CanonText)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { month = month.minusMonths(1) }, enabled = month > YearMonth.from(today)) {
                    Text(appText("Назад", "Артҡа"), style = CanonCaption)
                }
                Text("${months[month.monthValue - 1]} ${month.year}", style = CanonButton,
                    color = CanonText, modifier = Modifier.weight(1f))
                TextButton(onClick = { month = month.plusMonths(1) }, enabled = month < lastMonth) {
                    Text(appText("Дальше", "Артабан"), style = CanonCaption)
                }
            }
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Row {
                    weekdays.forEach { name ->
                        Box(Modifier.width(CanonSpace.huge), contentAlignment = Alignment.Center) {
                            Text(name, style = CanonCaption, color = CanonMutedStrong)
                        }
                    }
                }
                val first = month.atDay(1)
                val shift = first.dayOfWeek.value - 1
                val weeks = (shift + month.lengthOfMonth() + 6) / 7
                repeat(weeks) { row ->
                    Row {
                        repeat(7) { column ->
                            val day = row * 7 + column - shift + 1
                            val cell = Modifier.size(CanonSpace.huge)
                            if (day !in 1..month.lengthOfMonth()) Spacer(cell)
                            else {
                                val date = month.atDay(day)
                                val checked = date in dates
                                val dayLabel = "${date.dayOfMonth} ${months[date.monthValue - 1]} ${date.year}"
                                val enabled = date >= today && (checked || dates.size < maximum)
                                Box(cell.toggleable(value = checked, enabled = enabled, role = Role.Checkbox,
                                    onValueChange = { pick -> dates = if (pick) dates + date else dates - date })
                                    .background(if (checked) CanonMint else CanonSurface, CanonFieldShape)
                                    .semantics { contentDescription = dayLabel }, contentAlignment = Alignment.Center) {
                                    Text(day.toString(), style = CanonButton,
                                        color = if (checked) CanonGreen2 else if (enabled) CanonText else CanonMutedStrong)
                                }
                            }
                        }
                    }
                }
            }
            Text(appText("Одно время для выбранных дат", "Һайланған көндәр өсөн бер ваҡыт"),
                style = CanonCaption, color = CanonMutedStrong)
            Row(horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                DepartureTimeField(appText("Часы", "Сәғәт"), hour, "series_hour", selectedTime == null,
                    { hour = it }, Modifier.weight(1f))
                DepartureTimeField(appText("Минуты", "Минут"), minute, "series_minute", selectedTime == null,
                    { minute = it }, Modifier.weight(1f))
            }
            if (conflicts) Text(appText("На это время уже есть дата серии.", "Был ваҡытҡа серия көнө бар."),
                color = CanonRed, style = CanonCaption)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(appText("Отмена", "Баш тартыу"), style = CanonCaption) }
                TextButton(onClick = { onConfirm(selected) }, enabled = selected.size in minimum..maximum && !conflicts) {
                    Text(appText("Сохранить", "Һаҡларға") + " ${selected.size}/$maximum", style = CanonButton)
                }
            }
        }
    }
}
