package com.yuldash.app

// «Мои предзаказы» — такси «на время» (scheduled). Список моих будущих заказов:
//   • route + время подачи + обратный отсчёт («через 2 ч 10 мин» / «пора ехать»);
//   • «Начать поиск сейчас» (activate → обычный экран поиска) и «Отменить».
// Автодиспетчинг ленивый: на входе дёргаем список — бэкенд сам активирует наступившие ко времени
// и возвращает их в блоке «activated» (показываем как «Пора ехать»). Фонового шедулера нет —
// мягко подсказываем открыть приложение ко времени. Все состояния (загрузка/пусто/ошибка+повтор).

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.InstantOrderDto
import com.yuldash.app.data.ScheduledOrdersDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun ScheduledOrdersScreen(onBack: () -> Unit, onActivated: () -> Unit) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(ScheduledOrdersDto(emptyList(), emptyList())) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var busyId by remember { mutableStateOf(0) }   // id, по которому идёт activate/cancel (гард двойного тапа)

    LaunchedEffect(reload) {
        loading = true
        ApiClient.getScheduledOrders()
            .onSuccess { data = it; error = false }
            .onFailure { e -> error = (e as? ApiException)?.status != 401 }
        loading = false
    }

    // Тикаем раз в 30с — обратный отсчёт живой, а наступившие ко времени подтягиваются с сервера.
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(30_000)
            ApiClient.getScheduledOrders().onSuccess { data = it }
        }
    }

    // «Часы» для обратного отсчёта: обновляются раз в 30с (перерисовка меток «через …»).
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) { nowMs = System.currentTimeMillis(); delay(30_000) }
    }

    fun activate(id: Int) {
        if (busyId != 0) return
        busyId = id
        scope.launch {
            ApiClient.activateScheduledOrder(id)
                .onSuccess { onActivated() }
                .onFailure { reload++ }   // гонка (уже активирован/отменён) → просто обновим список
            busyId = 0
        }
    }
    fun cancel(id: Int) {
        if (busyId != 0) return
        busyId = id
        scope.launch {
            ApiClient.cancelScheduledOrder(id).onSuccess {
                data = data.copy(scheduled = data.scheduled.filterNot { it.id == id })
            }
            busyId = 0
        }
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Мои предзаказы", "Минең алдан заказдар"), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            item {
                Text(appText("Такси к нужному времени", "Кәрәкле ваҡытҡа такси"),
                    color = CanonGreen, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Black)
                Text(appText("Мы напомним и начнём искать машину ко времени подачи.",
                    "Беҙ иҫкә төшөрөрбөҙ һәм килеү ваҡытына машина эҙләй башларбыҙ."),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
            }

            when {
                loading -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(2) { SkeletonCard(lines = 3) } }
                }
                error -> item {
                    EmptyStateCard(
                        title = appText("Не удалось загрузить предзаказы", "Алдан заказдарҙы йөкләп булманы"),
                        text = appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла"),
                        icon = Icons.Default.Refresh,
                        action = appText("Повторить", "Ҡабатлау"),
                        onAction = { reload++ },
                    )
                }
                data.scheduled.isEmpty() && data.activated.isEmpty() -> item {
                    EmptyStateCard(
                        title = appText("Пока предзаказов нет", "Әлегә алдан заказ юҡ"),
                        text = appText("Закажи такси «на время» — в экране заказа выбери «На время».",
                            "Такси «ваҡытҡа» заказ ит — заказ экранында «Ваҡытҡа» һайла."),
                        icon = Icons.Default.Schedule,
                    )
                }
                else -> {
                    // «Пора ехать» — активированные ко времени (сервер уже перевёл в поиск).
                    if (data.activated.isNotEmpty()) {
                        item {
                            Text(appText("Пора ехать", "Барыр ваҡыт"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp)
                        }
                        items(data.activated, key = { "act-${it.id}" }) { order ->
                            ActivatedOrderCard(order = order, onOpen = onActivated)
                        }
                    }
                    if (data.scheduled.isNotEmpty()) {
                        item {
                            Text(appText("Ждут своего времени", "Ваҡытын көтә"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp,
                                modifier = Modifier.padding(top = if (data.activated.isNotEmpty()) 6.dp else 0.dp))
                        }
                        items(data.scheduled, key = { it.id }) { order ->
                            ScheduledOrderCard(
                                order = order,
                                nowMs = nowMs,
                                busy = busyId == order.id,
                                anyBusy = busyId != 0,
                                onActivate = { activate(order.id) },
                                onCancel = { cancel(order.id) },
                            )
                        }
                    }
                    // Честная подсказка: фонового шедулера нет.
                    item {
                        InfoCard(
                            appText("Открой приложение ко времени", "Ваҡытына ҡушымтаны ас"),
                            appText("Чтобы начать поиск, открой Юлдаш к времени подачи или нажми «Начать поиск сейчас».",
                                "Эҙләүҙе башлар өсөн Юлдашты килеү ваҡытына ас йәки «Хәҙер эҙләй башларға» баҫ."),
                            Icons.Default.Info,
                        )
                    }
                }
            }
        }
    }
}

// ---------- Карточка предзаказа (ждёт своего времени) ----------
@Composable
private fun ScheduledOrderCard(
    order: InstantOrderDto,
    nowMs: Long,
    busy: Boolean,
    anyBusy: Boolean,
    onActivate: () -> Unit,
    onCancel: () -> Unit,
) {
    val ready = isScheduleReady(order.scheduledAt, nowMs)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = if (ready) BorderStroke(1.5.dp, CanonGreen2) else null,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RouteLine(order)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                Text(order.scheduledAt?.let { formatDepart(it) } ?: "—", color = CanonText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                CountdownChip(order.scheduledAt, nowMs)
            }
            if (order.priceEstimate > 0) {
                Text(appText("≈ ${order.priceEstimate} ₽ · цену уточним при подаче", "≈ ${order.priceEstimate} ₽ · хаҡты килгәндә асыҡлайбыҙ"),
                    color = CanonMuted, fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton(
                    text = appText("Начать поиск сейчас", "Хәҙер эҙләргә"),
                    onClick = onActivate,
                    enabled = !anyBusy,
                    loading = busy,
                    style = AppButtonStyle.Primary,
                    fillWidth = false,
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    text = appText("Отменить", "Кире алыу"),
                    onClick = onCancel,
                    enabled = !anyBusy,
                    style = AppButtonStyle.Secondary,
                    fillWidth = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ---------- Карточка «Пора ехать» (уже активирован ко времени) ----------
@Composable
private fun ActivatedOrderCard(order: InstantOrderDto, onOpen: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "ready-pulse")
    val a by pulse.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "ready-alpha",
    )
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonGreen2),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).alpha(a).background(CanonBg, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(appText("Пора ехать — ищем машину", "Барыр ваҡыт — машина эҙләйбеҙ"),
                    color = CanonBg, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Text("${order.fromText.ifBlank { appText("Точка А", "А нөктәһе") }} → ${order.toText.ifBlank { appText("Точка Б", "Б нөктәһе") }}",
                    color = CanonBg.copy(alpha = 0.9f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Surface(color = CanonBg.copy(alpha = 0.22f), shape = RoundedCornerShape(999.dp)) {
                Text(appText("Открыть", "Асыу"), color = CanonBg, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun RouteLine(order: InstantOrderDto) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(8.dp).background(CanonGreen2, CircleShape))
            Box(Modifier.size(width = 2.dp, height = 16.dp).background(CanonBorder))
            Box(Modifier.size(8.dp).background(CanonTaxi, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(order.fromText.ifBlank { appText("Точка А", "А нөктәһе") }, color = CanonText, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(order.toText.ifBlank { appText("Точка Б", "Б нөктәһе") }, color = CanonText, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CountdownChip(iso: String?, nowMs: Long) {
    val minutes = minutesUntil(iso, nowMs)
    val ready = minutes != null && minutes <= 0
    val label = when {
        minutes == null -> appText("на время", "ваҡытҡа")
        ready -> appText("пора", "ваҡыт")
        minutes < 60 -> appText("через $minutes мин", "$minutes мин эсендә")
        else -> {
            val h = minutes / 60; val m = minutes % 60
            if (m == 0L) appText("через $h ч", "$h сәғәт эсендә")
            else appText("через $h ч $m мин", "$h сәғәт $m мин эсендә")
        }
    }
    Surface(
        color = if (ready) CanonMint else CanonBg,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, if (ready) CanonGreen2 else CanonBorder),
    ) {
        Text(label, color = if (ready) CanonGreen2 else CanonMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

// ---------- Разбор времени подачи (ISO) ----------
/** Минуты до времени подачи (может быть отрицательным = уже пора). null = не удалось разобрать. */
private fun minutesUntil(iso: String?, nowMs: Long): Long? {
    val ms = parseIsoMs(iso) ?: return null
    return (ms - nowMs) / 60_000L
}
private fun isScheduleReady(iso: String?, nowMs: Long): Boolean {
    val m = minutesUntil(iso, nowMs) ?: return false
    return m <= 5   // за 5 минут до подачи подсвечиваем карточку «пора»
}
/** ISO → epoch ms. Терпимо к 'Z', смещению и «наивному» локальному времени (без зоны). */
private fun parseIsoMs(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        try {
            java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.LocalDateTime.parse(iso)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }
}
