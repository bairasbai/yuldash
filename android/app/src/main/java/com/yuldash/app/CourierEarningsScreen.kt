package com.yuldash.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.CourierEarningsDayDto
import com.yuldash.app.data.CourierEarningsDto

/**
 * «Мой заработок» курьера — по образцу экрана водителя.
 *
 * Зачем: курьер видел только «должен Юлдашу столько-то», и работа выглядела сплошным долгом,
 * хотя у водителя разбивка заработка есть с самого начала (аудит 2026-07-26). Здесь честно:
 * сколько получено чистыми, сколько ушло комиссией и сколько доставок — по дням.
 *
 * ВАЖНО: все суммы в КОПЕЙКАХ (в отличие от экрана водителя, где сервер отдаёт рубли) —
 * форматируем через [kopToRub], иначе ошибка в 100 раз.
 * Состояния: загрузка / ошибка + «Повторить» / пусто (новичок без доставок).
 */
@Composable
internal fun CourierEarningsScreen(onBack: () -> Unit) {
    var period by remember { mutableStateOf("week") }
    var data by remember { mutableStateOf<CourierEarningsDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(period, reload) {
        loading = true; error = false
        ApiClient.getCourierEarnings(period)
            .onSuccess { data = it }
            .onFailure { error = true }
        loading = false
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Мой заработок", "Минең табыш"), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NearbyFilterChip(Icons.Default.CalendarViewWeek, appText("Неделя", "Аҙна"), period == "week",
                        modifier = Modifier.heightIn(min = 48.dp)) { period = "week" }
                    NearbyFilterChip(Icons.Default.CalendarMonth, appText("Месяц", "Ай"), period == "month",
                        modifier = Modifier.heightIn(min = 48.dp)) { period = "month" }
                    NearbyFilterChip(Icons.Default.AllInclusive, appText("Всё время", "Бөтә ваҡыт"), period == "all",
                        modifier = Modifier.heightIn(min = 48.dp)) { period = "all" }
                }
            }
            val d = data
            when {
                loading && d == null -> {
                    item { SkeletonCard(lines = 3) }
                    item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(4) { SkeletonCard(lines = 1) } } }
                }
                error && d == null -> item { AppErrorState(onRetry = { reload++ }) }
                d == null || d.deliveries == 0 -> item {
                    AppEmptyState(
                        title = appText("Пока нет доставок", "Әлегә илтеү юҡ"),
                        text = appText(
                            "Возьми заказ во вкладке «Заказы» — здесь появится, сколько ты заработал.",
                            "«Заказдар» бүлегендә заказ ал — бында күпме эшләгәнең күренәсәк.",
                        ),
                        icon = Icons.Default.DeliveryDining,
                    )
                }
                else -> {
                    item { CourierTotalsCard(d) }
                    item {
                        Text(appText("По дням", "Көндәр буйынса"), color = CanonMuted, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    }
                    val maxNet = d.byDay.maxOfOrNull { it.netKop }?.coerceAtLeast(1) ?: 1
                    items(d.byDay, key = { it.date }) { day -> CourierDayRow(day, maxNet) }
                    item {
                        Text(
                            appText(
                                "Деньги за доставку получаешь напрямую — Юлдаш их не держит. Комиссия копится отдельно и платится в кабинете.",
                                "Илтеү аҡсаһын туранан-тура алаһың — Юлдаш уны тотмай. Комиссия айырым йыйыла һәм кабинетта түләнә.",
                            ),
                            color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CourierTotalsCard(d: CourierEarningsDto) {
    AppCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(appText("Заработано чистыми", "Таҙа эшләнде"), color = CanonMuted, fontSize = 13.sp)
            Text(kopToRub(d.netKop), color = CanonText, fontSize = 36.sp, fontWeight = FontWeight.Black)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CourierTotalTile(Icons.Default.DeliveryDining, appText("Доставок", "Илтеү"),
                    d.deliveries.toString(), Modifier.weight(1f))
                CourierTotalTile(Icons.Default.Percent, appText("Комиссия", "Комиссия"),
                    kopToRub(d.commissionKop), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CourierTotalTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(color = CanonBg, shape = CanonItemShape, modifier = modifier) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(value, color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text(label, color = CanonMuted, fontSize = 11.sp)
            }
        }
    }
}

/** Строка дня с полоской, пропорциональной сумме — видно, какой день был удачным. */
@Composable
private fun CourierDayRow(day: CourierEarningsDayDto, maxNet: Int) {
    val fraction by animateFloatAsState(
        targetValue = (day.netKop.toFloat() / maxNet).coerceIn(0.02f, 1f),
        animationSpec = tween(420),
        label = "courierDayBar",
    )
    Surface(color = CanonSurface, shape = CanonItemShape) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(shortDate(day.date) ?: day.date, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text(kopToRub(day.netKop), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
            Box(
                Modifier.fillMaxWidth().height(8.dp)
                    .background(CanonBg, RoundedCornerShape(999.dp)),
            ) {
                Box(
                    Modifier.fillMaxWidth(fraction).height(8.dp)
                        .background(CanonGreen2, RoundedCornerShape(999.dp)),
                )
            }
            Text(
                appText("Доставок: ${day.deliveries}", "Илтеү: ${day.deliveries}"),
                color = CanonMuted, fontSize = 12.sp,
            )
        }
    }
}
