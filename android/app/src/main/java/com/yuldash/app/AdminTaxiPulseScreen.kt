package com.yuldash.app

// «Пульс такси» (B7b-3) — живая панель админа: кто на линии, активные заказы, счётчики дня,
// воронка «смотрят цену → заказывают», разбивка по городам. Автообновление ~30с,
// честные состояния: скелетон / ошибка / пусто.

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import androidx.compose.foundation.lazy.items
import com.yuldash.app.data.PriceComplaintDto
import com.yuldash.app.data.TaxiFunnelDto
import com.yuldash.app.data.TaxiPulseDto
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun AdminTaxiPulseScreen(onBack: () -> Unit) {
    var pulse by remember { mutableStateOf<TaxiPulseDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    var complaints by remember { mutableStateOf<List<PriceComplaintDto>>(emptyList()) }

    // Первая загрузка + автообновление ~30с (панель «живая», админ не жмёт руками).
    // В фоне цикл стоит (repeatOnLifecycle RESUMED), как остальные опросы приложения:
    // свёрнутая панель обновляла сводку, которую никто не смотрит.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(reloadTick, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                ApiClient.getTaxiPulse()
                    .onSuccess { pulse = it; error = false }
                    .onFailure { if (pulse == null) error = true }   // при живых данных сбой сети не пугает
                // Жалобы на цену тянем тем же циклом: отдельный опрос ради списка — лишний
                // трафик, а сбой здесь не должен гасить сам пульс.
                ApiClient.getPriceComplaints().onSuccess { complaints = it }
                loading = false
                delay(30_000)
            }
        }
    }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Пульс такси", "Такси пульсы"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                Text(
                    appText("Живая сводка: обновляется каждые 30 секунд.", "Йәнле күҙәтеү: һәр 30 секунд һайын яңыра."),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }
            val p = pulse
            when {
                loading && p == null -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { SkeletonCard(lines = 2) } }
                }
                error && p == null -> item {
                    EmptyStateCard(
                        title = appText("Не удалось загрузить пульс", "Пульсты йөкләп булманы"),
                        text = appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла"),
                        icon = Icons.Default.Refresh,
                        action = appText("Повторить", "Ҡабатлау"),
                        onAction = { loading = true; error = false; reloadTick++ },
                    )
                }
                p != null -> {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PulseTile(
                                value = "${p.driversOnline}",
                                label = appText("на линии", "линияла"),
                                accent = true,
                                modifier = Modifier.weight(1f).appearIn(0),
                            )
                            PulseTile(
                                value = "${p.ordersActive}",
                                label = appText("активных заказов", "актив заказ"),
                                accent = true,
                                modifier = Modifier.weight(1f).appearIn(0),
                            )
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PulseTile("${p.ordersToday}", appText("заказов сегодня", "бөгөн заказ"), modifier = Modifier.weight(1f).appearIn(1))
                            PulseTile("${p.doneToday}", appText("завершено", "тамамланды"), modifier = Modifier.weight(1f).appearIn(1))
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PulseTile("${p.cancelledToday}", appText("отмен", "кире алыу"), modifier = Modifier.weight(1f).appearIn(2))
                            PulseTile("${p.noShowToday}", appText("не вышли", "сыҡманы"), modifier = Modifier.weight(1f).appearIn(2))
                            PulseTile(
                                value = p.avgSearchSec?.let { formatSearchSec(it) } ?: "—",
                                label = appText("средний подбор", "уртаса эҙләү"),
                                modifier = Modifier.weight(1f).appearIn(2),
                            )
                        }
                    }
                    p.funnel?.let { f ->
                        item { PulseFunnelCard(f, Modifier.appearIn(3)) }
                    }
                    item {
                        Text(appText("По городам", "Ҡалалар буйынса"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.appearIn(4))
                    }
                    if (p.byCity.isEmpty()) {
                        item {
                            InfoCard(
                                title = appText("Пока тихо", "Әлегә тыныс"),
                                text = appText("Нет водителей на линии и активных заказов", "Линияла йөрөтөүселәр һәм актив заказдар юҡ"),
                                icon = Icons.Default.DirectionsCar,
                            )
                        }
                    } else {
                        p.byCity.forEachIndexed { i, c ->
                            item {
                                Surface(
                                    color = CanonSurface, shape = CanonItemShape,
                                    border = BorderStroke(1.dp, CanonBorder),
                                    modifier = Modifier.fillMaxWidth().appearIn(4 + i),
                                ) {
                                    Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(c.city, color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                        PulseDotStat(c.online, appText("на линии", "линияла"), CanonGreen2)
                                        Spacer(Modifier.width(12.dp))
                                        PulseDotStat(c.active, appText("заказы", "заказдар"), CanonTaxi)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Жалобы на цену. Место выбрано не случайно: пульс — это «как себя чувствует
            // такси», и несогласие людей с ценой относится сюда же, рядом с подбором и
            // машинами на линии. Отдельный экран ради списка из десяти строк — лишний.
            if (complaints.isNotEmpty()) {
                item {
                    Text(
                        appText("Не согласны с ценой", "Хаҡ менән килешмәйҙәр"),
                        color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                }
                items(complaints, key = { it.id }) { c -> PriceComplaintCard(c) }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

/**
 * Воронка «смотрят цену → заказывают». Главная цифра для правки тарифа: без неё падение
 * заказов после надбавки выглядит как «людей мало», а не как «дорого».
 *
 * Проценты приходят как Double? — null значит «никто не смотрел». Показывать в этом случае
 * 0% нельзя: «не приходили» и «пришли и ушли из-за цены» — противоположные новости.
 */
@Composable
private fun PulseFunnelCard(f: TaxiFunnelDto, modifier: Modifier = Modifier) {
    Surface(
        color = CanonSurface, shape = CanonItemShape,
        border = BorderStroke(1.dp, CanonBorder), modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    appText("Смотрят цену → заказывают", "Хаҡты ҡарайҙар → заказ бирәләр"),
                    color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    f.percentToday?.let { formatPercent(it) } ?: "—",
                    color = CanonGreen2, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                )
            }
            Text(
                if (f.viewsToday == 0)
                    appText("Сегодня цену ещё никто не смотрел", "Бөгөн хаҡты әле бер кем ҡараманы")
                else
                    appText(
                        "Сегодня: ${f.ordersToday} из ${f.viewsToday} заказали",
                        "Бөгөн: ${f.viewsToday} кешенән ${f.ordersToday} заказ бирҙе",
                    ),
                color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
            )
            if (f.viewsPeriod > 0) {
                Text(
                    appText(
                        "За ${f.windowDays} дней: ${f.percentPeriod?.let { formatPercent(it) } ?: "—"} · ${f.ordersPeriod} из ${f.viewsPeriod}",
                        "${f.windowDays} көнгә: ${f.percentPeriod?.let { formatPercent(it) } ?: "—"} · ${f.viewsPeriod} кешенән ${f.ordersPeriod}",
                    ),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                )
                FunnelBars(f.byDay.reversed())
            }
        }
    }
}

/**
 * Столбики по дням, старые слева. Высота — просмотры, залитая часть снизу — заказы:
 * видно и «сколько людей приходило», и «сколько из них доехало до кнопки», одним взглядом.
 */
@Composable
private fun FunnelBars(days: List<com.yuldash.app.data.TaxiFunnelDayDto>) {
    if (days.isEmpty()) return
    val maxViews = days.maxOf { it.views }.coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { d ->
            // Столбик тянется из нуля: панель «оживает», а не подставляет готовую картинку.
            val viewsH by animateFloatAsState(
                BAR_MAX_DP * d.views / maxViews, tween(CanonMotion.SLOW), label = "funnelViews",
            )
            val ordersH by animateFloatAsState(
                BAR_MAX_DP * d.orders / maxViews, tween(CanonMotion.SLOW), label = "funnelOrders",
            )
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.height(BAR_MAX_DP.dp), contentAlignment = Alignment.BottomCenter) {
                    Surface(
                        color = CanonMint, shape = CanonTinyShape,
                        modifier = Modifier.fillMaxWidth().height(viewsH.dp.coerceAtLeast(3.dp)),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            Surface(
                                color = CanonGreen2, shape = CanonTinyShape,
                                modifier = Modifier.fillMaxWidth().height(ordersH.dp),
                            ) {}
                        }
                    }
                }
                Text(dayLabel(d.day), color = CanonMuted, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/** Высота самого высокого столбика воронки, dp. */
private const val BAR_MAX_DP = 44f

/** «2026-08-28» → «28»: в неделе число дня однозначно, а места в столбике мало. */
private fun dayLabel(iso: String): String = iso.takeLast(2)

/** «26.5» → «27%»: доля процента в такой метрике — шум, а не точность. */
private fun formatPercent(p: Double): String = "${Math.round(p)}%"

/** Одна жалоба: сумма, причина словами и то, что человек дописал сам. */
@Composable
private fun PriceComplaintCard(c: PriceComplaintDto) {
    val reason = when (c.reason) {
        "expensive_for_distance" -> appText("дорого для такого расстояния", "был ара өсөн ҡиммәт")
        "was_cheaper" -> appText("минуту назад было дешевле", "бер минут элек арзаныраҡ ине")
        "line_unclear" -> appText("не понял строку в счёте", "иҫәптәге юлды аңламаған")
        else -> appText("другое", "башҡа")
    }
    Surface(color = CanonSurface, shape = CanonItemShape,
        border = BorderStroke(1.dp, CanonBorder), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${c.price} ₽", color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(reason, color = CanonMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
            if (c.comment.isNotBlank()) {
                Text(c.comment, color = CanonText, fontSize = 12.sp, lineHeight = 17.sp)
            }
            if (c.breakdown.isNotBlank()) {
                Text(c.breakdown, color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

/** Средний подбор: до 100с — секундами, дальше — минутами (панель читается с одного взгляда). */
private fun formatSearchSec(sec: Double): String =
    if (sec < 100) "${sec.toInt()} с" else "${(sec / 60).toInt()} мин"

/** Плитка цифры: крупное значение + спокойная подпись; акцентные — мятная подложка. */
@Composable
private fun PulseTile(value: String, label: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Surface(
        color = if (accent) CanonMint else CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, if (accent) CanonGreen2.copy(alpha = 0.35f) else CanonBorder),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, color = if (accent) CanonGreen2 else CanonText, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, color = CanonMuted, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** «● 3 на линии» — компактная пара точка-цифра-подпись в строке города. */
@Composable
private fun PulseDotStat(count: Int, label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
        Spacer(Modifier.width(4.dp))
        Text("$count", color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(4.dp))
        Text(label, color = CanonMuted, fontSize = 12.sp)
    }
}
