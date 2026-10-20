package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

internal const val RIDE_DEPARTURE_DIALOG = "ride-departure-dialog"
internal const val RIDE_DEPARTURE_MONTH = "ride-departure-month"
internal const val RIDE_DEPARTURE_DAY = "ride-departure-day"
internal const val RIDE_DEPARTURE_YEAR = "ride-departure-year"
internal const val RIDE_DEPARTURE_HOUR = "ride-departure-hour"
internal const val RIDE_DEPARTURE_MINUTE = "ride-departure-minute"
internal const val RIDE_DEPARTURE_CONFIRM = "ride-departure-confirm"
internal const val RIDE_DEPARTURE_CANCEL = "ride-departure-cancel"

private val rideDepartureFormat = DateTimeFormatter.ofPattern("dd.MM.uuuu, HH:mm", Locale.US)
    .withResolverStyle(ResolverStyle.STRICT)

internal fun parseRideDeparture(value: String): LocalDateTime? =
    runCatching { LocalDateTime.parse(value, rideDepartureFormat) }.getOrNull()

internal fun formatRideDeparture(date: LocalDate, time: LocalTime): String =
    date.atTime(time).format(rideDepartureFormat)

internal fun initialRideDeparture(value: String, now: LocalDateTime): LocalDateTime {
    val initial = parseRideDeparture(value) ?: now.plusHours(3)
    val date = initial.toLocalDate().coerceAtLeast(now.toLocalDate())
    return date.atTime(initial.toLocalTime().withSecond(0).withNano(0))
}

internal fun clampRideDepartureDate(year: Int, month: Int, day: Int, today: LocalDate): LocalDate {
    val validYear = year.coerceIn(today.year, 9999)
    val validMonth = month.coerceIn(if (validYear == today.year) today.monthValue else 1, 12)
    val minDay = if (validYear == today.year && validMonth == today.monthValue) today.dayOfMonth else 1
    val validDay = day.coerceIn(minDay, YearMonth.of(validYear, validMonth).lengthOfMonth())
    return LocalDate.of(validYear, validMonth, validDay)
}

internal fun rideDepartureTime(hour: String, minute: String): LocalTime? {
    fun asciiNumber(value: String) = value.isNotEmpty() && value.length <= 2 && value.all { it in '0'..'9' }
    if (!asciiNumber(hour) || !asciiNumber(minute)) return null
    val h = hour.toInt()
    val m = minute.toInt()
    return if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null
}

@Composable
private fun rideDepartureMonths(): List<String> = listOf(
    "Январь" to "Ғинуар", "Февраль" to "Февраль", "Март" to "Март",
    "Апрель" to "Апрель", "Май" to "Май", "Июнь" to "Июнь",
    "Июль" to "Июль", "Август" to "Август", "Сентябрь" to "Сентябрь",
    "Октябрь" to "Октябрь", "Ноябрь" to "Ноябрь", "Декабрь" to "Декабрь",
).map { (ru, ba) -> appText(ru, ba) }

/** Черновик живёт только в диалоге; форма получает дату исключительно после подтверждения. */
@Composable
internal fun RideDeparturePicker(initialValue: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val today = LocalDate.now()
    val initial = remember(initialValue) { initialRideDeparture(initialValue, LocalDateTime.now()) }
    var year by rememberSaveable(initialValue) { mutableIntStateOf(initial.year) }
    var month by rememberSaveable(initialValue) { mutableIntStateOf(initial.monthValue) }
    var day by rememberSaveable(initialValue) { mutableIntStateOf(initial.dayOfMonth) }
    var hour by rememberSaveable(initialValue) { mutableStateOf(String.format(Locale.US, "%02d", initial.hour)) }
    var minute by rememberSaveable(initialValue) { mutableStateOf(String.format(Locale.US, "%02d", initial.minute)) }
    var confirmed by remember { mutableStateOf(false) }
    val months = rideDepartureMonths()
    val date = LocalDate.of(year, month, day)
    val time = rideDepartureTime(hour, minute)
    val error = when {
        date < today -> appText("Выбери дату не раньше сегодняшней.", "Бөгөнгөнән иртәрәк булмаған датаны һайла.")
        time == null -> appText("Укажи время от 00:00 до 23:59.", "Ваҡытты 00:00–23:59 араһында күрһәт.")
        else -> null
    }
    fun selectDate(newYear: Int = year, newMonth: Int = month, newDay: Int = day) {
        val next = clampRideDepartureDate(newYear, newMonth, newDay, today)
        year = next.year
        month = next.monthValue
        day = next.dayOfMonth
    }
    AlertDialog(
        modifier = Modifier.testTag(RIDE_DEPARTURE_DIALOG).imePadding(),
        onDismissRequest = onDismiss,
        shape = CanonCardShape,
        containerColor = CanonSurface,
        tonalElevation = CanonDepth.flat,
        titleContentColor = CanonText,
        textContentColor = CanonText,
        title = { Text(appText("Дата и время", "Дата һәм ваҡыт"), style = CanonHeading) },
        text = {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.5f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
            ) {
                DepartureChoice(
                    label = appText("Месяц", "Ай"), value = month,
                    range = (if (year == today.year) today.monthValue else 1)..12,
                    valueLabel = { months[it - 1] }, tag = RIDE_DEPARTURE_MONTH,
                    onSelect = { selectDate(newMonth = it) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
                    DepartureChoice(
                        label = appText("День", "Көн"), value = day,
                        range = (if (year == today.year && month == today.monthValue) today.dayOfMonth else 1)..date.lengthOfMonth(),
                        valueLabel = Int::toString, tag = RIDE_DEPARTURE_DAY,
                        onSelect = { selectDate(newDay = it) }, modifier = Modifier.weight(1f),
                    )
                    DepartureChoice(
                        label = appText("Год", "Йыл"), value = year, range = today.year..9999,
                        valueLabel = Int::toString, tag = RIDE_DEPARTURE_YEAR,
                        onSelect = { selectDate(newYear = it) }, modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
                    DepartureTimeField(
                        label = appText("Часы", "Сәғәт"), value = hour, tag = RIDE_DEPARTURE_HOUR,
                        invalid = rideDepartureTime(hour, "00") == null,
                        onChange = { hour = it }, modifier = Modifier.weight(1f),
                    )
                    DepartureTimeField(
                        label = appText("Минуты", "Минут"), value = minute, tag = RIDE_DEPARTURE_MINUTE,
                        invalid = rideDepartureTime("00", minute) == null,
                        onChange = { minute = it }, modifier = Modifier.weight(1f),
                    )
                }
                AnimatedVisibility(
                    visible = error != null,
                    enter = fadeIn(tween(CanonMotion.QUICK)) + expandVertically(tween(CanonMotion.QUICK)),
                    exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.QUICK)),
                ) { Text(error.orEmpty(), color = CanonRed, style = CanonCaption) }
            }
        },
        confirmButton = {
            Button(
                modifier = Modifier.heightIn(min = CanonSpace.huge).testTag(RIDE_DEPARTURE_CONFIRM),
                enabled = error == null && !confirmed,
                shape = CanonFieldShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CanonGreen2, contentColor = CanonOnFilled,
                    disabledContainerColor = CanonBg, disabledContentColor = CanonMutedStrong,
                ),
                onClick = {
                    if (error == null && time != null && !confirmed) {
                        confirmed = true
                        onConfirm(formatRideDeparture(date, time))
                    }
                },
            ) { Text(appText("Сохранить", "Һаҡларға"), style = CanonButton) }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.heightIn(min = CanonSpace.huge).testTag(RIDE_DEPARTURE_CANCEL),
                onClick = onDismiss,
            ) { Text(appText("Отмена", "Баш тартыу"), color = CanonGreen2, style = CanonButton) }
        },
    )
}

@Composable
private fun DepartureChoice(
    label: String, value: Int, range: IntRange, valueLabel: (Int) -> String,
    tag: String, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(0) }
    val menuWidth = with(LocalDensity.current) { widthPx.toDp() }
    val description = "$label: ${valueLabel(value)}"
    Column(modifier, verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
        Text(label, color = CanonMutedStrong, style = CanonCaption)
        Box {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = CanonSpace.huge).testTag(tag)
                    .onSizeChanged { widthPx = it.width }
                    .semantics { contentDescription = description },
                shape = CanonFieldShape,
                border = BorderStroke(CanonDepth.card, CanonBorder),
                contentPadding = PaddingValues(horizontal = CanonSpace.md, vertical = CanonSpace.sm),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CanonText),
                onClick = { expanded = true },
            ) {
                Text(valueLabel(value), style = CanonBody, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = expanded, onDismissRequest = { expanded = false },
                modifier = Modifier.width(menuWidth), shape = CanonFieldShape,
                containerColor = CanonSurface, tonalElevation = CanonDepth.flat, shadowElevation = CanonDepth.sheet,
            ) {
                LazyColumn(
                    modifier = Modifier.width(menuWidth).height(CanonSpace.huge * 4).testTag("$tag-options"),
                    state = rememberLazyListState(initialFirstVisibleItemIndex = (value - range.first).coerceIn(0, range.count() - 1)),
                ) {
                    items(count = range.count(), key = { range.first + it }) { index ->
                        val option = range.first + index
                        val optionDescription = "$label: ${valueLabel(option)}"
                        DropdownMenuItem(
                            modifier = Modifier.fillMaxWidth().heightIn(min = CanonSpace.huge)
                                .testTag("$tag-option-$option")
                                .semantics { contentDescription = optionDescription; selected = value == option },
                            text = { Text(valueLabel(option), color = CanonText, style = CanonBody) },
                            onClick = { expanded = false; onSelect(option) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DepartureTimeField(
    label: String, value: String, tag: String, invalid: Boolean,
    onChange: (String) -> Unit, modifier: Modifier = Modifier,
) {
    val bringIntoView = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboardBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    // Фокус приходит раньше клавиатуры: повторяем прокрутку после изменения её размера.
    LaunchedEffect(focused, keyboardBottom) {
        if (focused) {
            withFrameNanos { }
            bringIntoView.bringIntoView()
        }
    }
    OutlinedTextField(
        modifier = modifier.heightIn(min = CanonSpace.huge).testTag(tag)
            .bringIntoViewRequester(bringIntoView)
            .onFocusChanged { focused = it.isFocused },
        value = value,
        onValueChange = { onChange(it.filter { ch -> ch in '0'..'9' }.take(2)) },
        label = { Text(label, style = CanonCaption) },
        textStyle = CanonBody,
        singleLine = true,
        isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = CanonFieldShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = CanonText, unfocusedTextColor = CanonText,
            focusedBorderColor = CanonGreen2, unfocusedBorderColor = CanonBorder,
            focusedLabelColor = CanonGreen2, unfocusedLabelColor = CanonMutedStrong,
            cursorColor = CanonGreen2, errorBorderColor = CanonRed, errorLabelColor = CanonRed,
            errorTextColor = CanonText, errorCursorColor = CanonRed,
        ),
    )
}
