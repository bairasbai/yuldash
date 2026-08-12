package com.yuldash.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.DriverEarningsDayDto
import com.yuldash.app.data.DriverEarningsDto
import kotlinx.coroutines.launch

/**
 * «Мой заработок» — история заработка водителя по периодам (неделя / месяц / всё).
 * Итог периода одной карточкой («Заработано ₽» + число поездок) и разбивка по дням с барами (∝ сумме дня).
 * ВАЖНО: total/sum приходят в РУБЛЯХ (₽), не в копейках → форматируем как есть, без /100.
 * Приватность: сервер отдаёт только свои данные (по токену).
 * Состояния: загрузка / ошибка (повтор) / пусто (нули для новичка — «Пока нет завершённых поездок»).
 */
@Composable
internal fun DriverEarningsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    var period by remember { mutableStateOf("week") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf<DriverEarningsDto?>(null) }

    suspend fun load(p: String) {
        loading = true; error = false
        ApiClient.getDriverEarnings(p)
            .onSuccess { data = it; loading = false }
            .onFailure { error = true; loading = false }
    }
    LaunchedEffect(period) { load(period) }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Мой заработок", "Минең табыш"), onBack) },
    ) { padding ->
        // Заработок растёт после каждой поездки — жест сверху вниз даёт свежую сумму,
        // не заставляя выходить с экрана и заходить обратно.
        AppPullRefresh(
            refreshing = loading && data != null,
            onRefresh = { scope.launch { load(period) } },
            modifier = Modifier.padding(padding),
        ) {
        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            // Переключатель периода.
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EarnPeriodChip(Icons.Default.CalendarViewWeek, appText("Неделя", "Аҙна"), period == "week") { period = "week" }
                    EarnPeriodChip(Icons.Default.CalendarMonth, appText("Месяц", "Ай"), period == "month") { period = "month" }
                    EarnPeriodChip(Icons.Default.AllInclusive, appText("Всё время", "Бөтә ваҡыт"), period == "all") { period = "all" }
                }
            }

            when {
                loading && data == null -> {
                    // Скелетон повторяет форму будущего экрана: одна широкая карточка итога,
                    // потом дни. Иначе при загрузке контент прыгает с двух колонок на одну.
                    item { SkeletonCard(lines = 2) }
                    item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(4) { SkeletonCard(lines = 1) } } }
                }
                error && data == null -> item { AppErrorState(onRetry = { scope.launch { load(period) } }) }
                else -> {
                    val d = data ?: return@LazyColumn
                    // Итог: сумма во всю ширину + строка «поездок». Раньше это были две половинные
                    // плитки, и у водителя с хорошим месяцем шестизначная сумма упиралась в край.
                    // Та же карточка, что у курьера и в расшифровке таксиста — деньги в Юлдаше
                    // должны выглядеть одинаково, где бы человек их ни считал.
                    item { EarnTotalsCard(d) }

                    if (d.byDay.isEmpty()) {
                        item {
                            AppEmptyState(
                                title = appText("Пока нет завершённых поездок", "Тамамланған сәфәрҙәр әлегә юҡ"),
                                text = appText(
                                    "Заверши первую поездку — здесь появится твой заработок по дням.",
                                    "Беренсе сәфәрҙе тамамла — бында көнлөк табышың күренер.",
                                ),
                                icon = Icons.Default.Payments,
                            )
                        }
                    } else {
                        item {
                            SectionHeader(
                                appText("По дням", "Көндәр буйынса"),
                                appText("Сумма и число поездок за каждый день.", "Һәр көн өсөн сумма һәм сәфәр һаны."),
                            )
                        }
                        val maxSum = d.byDay.maxOf { it.sum }.coerceAtLeast(1)
                        items(d.byDay, key = { it.date }) { day -> EarnDayRow(day, maxSum) }
                    }
                }
            }
        }
        }
    }
}

/** Чип периода: та же логика, что NearbyFilterChip, но самостоятельный (иконка + подпись). */
@Composable
private fun EarnPeriodChip(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.bounceClick(onClick),
        color = if (active) CanonGreen2 else CanonSurface,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, if (active) Color.Transparent else CanonBorder),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (active) Color.White else CanonGreen2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(
                label, color = if (active) Color.White else CanonText, fontWeight = FontWeight.Bold,
                fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine, maxLines = 1,
            )
        }
    }
}

/** Итог периода: сумма во всю ширину + число поездок строкой. Один в один с заработком курьера. */
@Composable
private fun EarnTotalsCard(d: DriverEarningsDto) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    appText("Заработано", "Табыш"),
                    color = CanonMuted, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine,
                )
                Text(
                    "${fmtRub(d.total)} ₽",
                    color = CanonText,
                    fontSize = MoneyType.Hero,
                    lineHeight = MoneyType.HeroLine,
                    letterSpacing = MoneyType.HeroTracking,
                    fontWeight = FontWeight.Bold,
                )
            }
            MoneyLine(
                Icons.Default.DirectionsCar, CanonMint, CanonGreen2,
                appText("Поездок", "Сәфәр"),
                fmtRub(d.trips),
                CanonText,
            )
        }
    }
}

/** Строка дня: дата, мини-бар шириной ∝ сумме, сумма ₽ и число поездок. */
@Composable
private fun EarnDayRow(day: DriverEarningsDayDto, maxSum: Int) {
    // Полоска должна ВЫРАСТАТЬ. animateFloatAsState на первом кадре берёт цель как есть —
    // столбик появлялся сразу на полную длину и не анимировался никогда. Стартуем с нуля
    // и включаем настоящую цель после первой композиции (тот же приём, что у курьера).
    var grown by remember(day.date) { mutableStateOf(false) }
    LaunchedEffect(day.date) { grown = true }
    val target = if (grown) (day.sum.toFloat() / maxSum.toFloat()).coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(target, animationSpec = tween(600), label = "earnBar")
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shortDay(day.date), color = CanonText, fontWeight = FontWeight.Bold,
                    fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${fmtRub(day.sum)} ₽", color = CanonGreen, fontWeight = FontWeight.Bold,
                    fontSize = MoneyType.Value, lineHeight = MoneyType.ValueLine,
                )
            }
            // Бар: трек (мята) + заполнение (зелёный) шириной ∝ сумме дня.
            Box(
                Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)).background(CanonMint),
            ) {
                Box(
                    Modifier.fillMaxWidth(animated).height(8.dp).clip(RoundedCornerShape(8.dp)).background(CanonGreen2),
                )
            }
            Text(
                appText("${day.trips} ${tripsWordEarn(day.trips)}", "${day.trips} сәфәр"),
                color = CanonMuted, fontSize = MoneyType.Caption, lineHeight = MoneyType.CaptionLine,
            )
        }
    }
}

/** "2026-07-14" → "14.07". При неожиданном формате — как есть. */
private fun shortDay(date: String): String = try {
    "${date.substring(8, 10)}.${date.substring(5, 7)}"
} catch (e: Exception) {
    date
}

/** Русский плюрал «поездок» (локально, чтобы не тянуть private-хелпер из другого файла). */
private fun tripsWordEarn(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "поездка"
        m10 in 2..4 && m100 !in 12..14 -> "поездки"
        else -> "поездок"
    }
}
