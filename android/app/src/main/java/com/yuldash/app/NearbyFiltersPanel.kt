package com.yuldash.app

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Woman
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate

// Ряд фильтров «Ближайших поездок»: Откуда · Куда · Когда · Фильтры (Александр, 16.10.2026:
// «сделаем иконку и там поставим фильтры»). Было двенадцать чипов в одном ряду: условия листались
// вбок, «Тихая поездка» пряталась за краем экрана. Теперь каждый чип — один вопрос (где, куда,
// когда, какие условия), а условия — в шторке «Фильтры» с числом выбранных на чипе.
// Значок — ползунки, как у «Фильтров по умолчанию» в настройках: лупа на этом экране уже значит
// «Найти попутку», вторая лупа с другим смыслом путала бы.

/** Условия пассажира в порядке шторки; ключи — те же, что уходят на сервер (`conditions=`). */
internal val NEARBY_CONDITION_KEYS = listOf("women", "child", "pets", "baggage", "ac", "nosmoke", "quiet")

@Composable
private fun conditionLabel(key: String): String = when (key) {
    "women" -> appText("Только женщины", "Тик ҡатын-ҡыҙ")
    "child" -> appText("Детское кресло", "Балалар ултырғысы")
    "pets" -> appText("С животным", "Хайуан менән")
    "baggage" -> appText("Багаж", "Йөк")
    "ac" -> appText("Кондиционер", "Кондиционер")
    "nosmoke" -> appText("Некурящий", "Тартмаусы")
    else -> appText("Тихая поездка", "Тыныс сәфәр")
}

private fun conditionIcon(key: String): ImageVector = when (key) {
    "women" -> Icons.Default.Woman
    "child" -> Icons.Default.ChildCare
    "pets" -> Icons.Default.Pets
    "baggage" -> Icons.Default.Luggage
    "ac" -> Icons.Default.AcUnit
    "nosmoke" -> Icons.Default.Block
    else -> Icons.Default.VolumeOff
}

/**
 * Шторка фильтров ленты: заголовок (+ действие справа, например «Сбросить») и содержимое.
 * Кнопки внутри («Готово», «Показать N поездок», выбор дня) закрывают её через `close` — тем же уходом
 * вниз, что тап по затемнению и свайп, и только потом [onDismiss] убирает её. Раньше кнопка выдёргивала
 * шторку из экрана без движения (решение лида, 17.10.2026; пара к сайту — NearbySheet в NearbyFilters.tsx).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NearbySheet(
    title: String,
    onDismiss: () -> Unit,
    action: @Composable () -> Unit = {},
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() } }
    AppModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = CanonBg) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = CanonSpace.lg).navigationBarsPadding().imePadding()
                .padding(bottom = CanonSpace.lg).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.xs),
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = CanonSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = CanonText, style = CanonTitle, modifier = Modifier.weight(1f))
                action()
            }
            content(close)
        }
    }
}

/** Подпись чипа «Когда»: «Когда», «Когда · Сегодня», «Когда · Завтра»; экран открыт за полночь — дата. */
@Composable
private fun whenLabel(dateFilter: String?, today: LocalDate): String {
    val base = appText("Когда", "Ҡасан")
    return when (dateFilter) {
        null -> base
        today.toString() -> "$base · ${appText("Сегодня", "Бөгөн")}"
        today.plusDays(1).toString() -> "$base · ${appText("Завтра", "Иртәгә")}"
        else -> "$base · ${dayMonth(dateFilter.orEmpty())}"
    }
}

/** Чип «Когда» + шторка дня. Выбор одного из трёх — шторка закрывается сразу. */
@Composable
internal fun NearbyWhenChip(dateFilter: String?, onChange: (String?) -> Unit, chipModifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    NearbyFilterChip(Icons.Default.CalendarMonth, whenLabel(dateFilter, today), dateFilter != null, modifier = chipModifier) { open = true }
    if (open) {
        NearbySheet(appText("Когда едем", "Ҡасан барабыҙ"), onDismiss = { open = false }) { close ->
            NearbyDayOptions(dateFilter, today) { onChange(it); close() }
        }
    }
}

/**
 * Варианты дня (YYYY-MM-DD или null — все дни) + «Другая дата…»: поездки в РБ планируют на
 * выходные и на неделю вперёд, а не только на «завтра». Отдельно от шторки — чтобы проверять тестом.
 */
@Composable
internal fun NearbyDayOptions(dateFilter: String?, today: LocalDate, onPick: (String?) -> Unit) {
    val days = listOf(
        null to appText("Все дни", "Бөтә көндәр"),
        today.toString() to appText("Сегодня", "Бөгөн"),
        today.plusDays(1).toString() to appText("Завтра", "Иртәгә"),
    )
    val other = dateFilter != null && days.none { it.first == dateFilter }
    val context = LocalContext.current
    // Одна группа переключателей: читалка экрана скажет «1 из 4», а не четыре отдельных кнопки.
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
        days.forEach { (value, label) -> NearbyCheckRow(label, dateFilter == value, role = Role.RadioButton) { onPick(value) } }
        NearbyCheckRow(
            if (other) appText("Дата · ${dayMonth(dateFilter.orEmpty())}", "Көн · ${dayMonth(dateFilter.orEmpty())}")
            else appText("Другая дата…", "Башҡа көн…"),
            other,
            role = Role.RadioButton,
        ) { openDayPicker(context, today, dateFilter) { onPick(it.toString()) } }
    }
}

/** «2026-10-18» → «18.10». */
private fun dayMonth(isoDate: String): String = isoDate.takeLast(5).split('-').reversed().joinToString(".")

/**
 * Нативный календарь — только дата и только с сегодняшнего дня; тема и русские месяцы — как в
 * публикации поездки (`openDateTimePicker`, MainActivity.kt), чтобы два календаря не отличались.
 */
private fun openDayPicker(context: Context, today: LocalDate, current: String?, onPicked: (LocalDate) -> Unit) {
    val start = current?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
    val cfg = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale("ru")) }
    val ctx = android.view.ContextThemeWrapper(context, 0).apply { applyOverrideConfiguration(cfg) }
    android.app.DatePickerDialog(
        ctx, com.yuldash.app.R.style.Theme_Yuldash_DatePicker,
        { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) },
        start.year, start.monthValue - 1, start.dayOfMonth,
    ).apply { datePicker.minDate = System.currentTimeMillis() - 1000 }.show()
}

/** Кнопка шторки: сколько поездок покажет лента с этими условиями (сервер уже посчитал — `count`). */
@Composable
private fun showRidesLabel(total: Int): String =
    if (total > 0) appText("Показать $total ${pluralRu(total, "поездку", "поездки", "поездок")}", "$total сәфәрҙе күрһәтергә")
    else appText("Нет поездок с такими условиями", "Был шарттар менән сәфәр юҡ")   // 0 — честно, а не «Готово»

/**
 * Чип «Фильтры · N» + шторка условий. Условия применяются сразу (лента за шторкой обновляется),
 * кнопка внизу показывает, сколько поездок осталось, — человек видит цену каждой галочки.
 */
@Composable
internal fun NearbyConditionsChip(
    chosen: Set<String>,
    onChange: (Set<String>) -> Unit,
    verifiedOnly: Boolean,
    total: Int,
    chipModifier: Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val base = appText("Фильтры", "Фильтрҙар")
    NearbyFilterChip(Icons.Default.Tune, if (chosen.isEmpty()) base else "$base · ${chosen.size}", chosen.isNotEmpty(), modifier = chipModifier) { open = true }
    if (open) {
        NearbySheet(
            base,
            onDismiss = { open = false },
            action = {
                AnimatedVisibility(chosen.isNotEmpty(), enter = fadeIn(tween(CanonMotion.QUICK)), exit = fadeOut(tween(CanonMotion.QUICK))) {
                    TextButton(onClick = { onChange(emptySet()) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(appText("Сбросить", "Бушатырға"), color = CanonGreen2, style = CanonBodyStrong)
                    }
                }
            },
        ) { close ->
            NearbyConditionsList(chosen, onChange, verifiedOnly)
            AppButton(showRidesLabel(total), close, modifier = Modifier.padding(top = CanonSpace.md))
        }
    }
}

/** Пояснение «только проверенные» + галочки условий. Отдельно от шторки — чтобы проверять тестом. */
@Composable
internal fun NearbyConditionsList(chosen: Set<String>, onChange: (Set<String>) -> Unit, verifiedOnly: Boolean) {
    // «Только проверенные» включают в «Безопасности», а режет он эту же ленту. Без строки здесь
    // человек видел меньше поездок и не понимал почему.
    if (verifiedOnly) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = CanonSpace.xs).background(CanonMint, CanonItemShape).padding(CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(end = CanonSpace.md).size(20.dp))
            Text(
                appText("Показываем только проверенных водителей — так включено в «Безопасности».",
                    "Тик тикшерелгән йөрөтөүселәрҙе күрһәтәбеҙ — «Хәүефһеҙлек» бүлегендә шулай ҡабыҙылған."),
                color = CanonText, style = CanonCaption,
            )
        }
    }
    NEARBY_CONDITION_KEYS.forEach { key ->
        val on = key in chosen
        NearbyCheckRow(conditionLabel(key), on, icon = conditionIcon(key)) { onChange(if (on) chosen - key else chosen + key) }
        if (key == "women") {
            // F9: «Только женщины» — это и женщины за рулём, а не одни поездки с такой отметкой.
            AnimatedVisibility(
                visible = on,
                enter = fadeIn(tween(CanonMotion.QUICK)) + expandVertically(tween(CanonMotion.NORMAL)),
                exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.QUICK)),
            ) {
                Text(
                    appText("Женщины за рулём и поездки «только для женщин».", "Рулдә ҡатын-ҡыҙҙар һәм «тик ҡатын-ҡыҙ өсөн» сәфәрҙәр."),
                    color = CanonMuted, style = CanonMicro, modifier = Modifier.padding(horizontal = CanonSpace.md),
                )
            }
        }
    }
}
