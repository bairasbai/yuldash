package com.yuldash.app

// «Пульс такси» (B7b-3) — живая панель админа: кто на линии, активные заказы, счётчики дня,
// разбивка по городам. Автообновление ~30с, честные состояния: скелетон / ошибка / пусто.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.yuldash.app.data.TaxiPulseDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun AdminTaxiPulseScreen(onBack: () -> Unit) {
    var pulse by remember { mutableStateOf<TaxiPulseDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }

    // Первая загрузка + автообновление ~30с (панель «живая», админ не жмёт руками).
    LaunchedEffect(reloadTick) {
        while (isActive) {
            ApiClient.getTaxiPulse()
                .onSuccess { pulse = it; error = false }
                .onFailure { if (pulse == null) error = true }   // при живых данных сбой сети не пугает
            loading = false
            delay(30_000)
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
                    item {
                        Text(appText("По городам", "Ҡалалар буйынса"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.appearIn(3))
                    }
                    if (p.byCity.isEmpty()) {
                        item {
                            InfoCard(
                                title = appText("Пока тихо", "Әлегә тыныс"),
                                text = appText("Нет водителей на линии и активных заказов", "Линияла водителдәр һәм актив заказдар юҡ"),
                                icon = Icons.Default.DirectionsCar,
                            )
                        }
                    } else {
                        p.byCity.forEachIndexed { i, c ->
                            item {
                                Surface(
                                    color = CanonSurface, shape = CanonItemShape,
                                    border = BorderStroke(1.dp, CanonBorder),
                                    modifier = Modifier.fillMaxWidth().appearIn(3 + i),
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
            item { Spacer(Modifier.height(12.dp)) }
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
