package com.yuldash.app

// «Мои предзаказы» — такси «на время» (scheduled). Список моих будущих заказов:
//   • route + время подачи + обратный отсчёт («через 2 ч 10 мин» / «пора ехать»);
//   • «Начать поиск сейчас» (activate → обычный экран поиска) и «Отменить».
// Автодиспетчинг работает на сервере: taxi_worker активирует заказ ко времени, даже если приложение
// закрыто. Экран дополнительно подхватывает уже активированный заказ из истории и ведёт в живой поиск.
// Все состояния: загрузка, пусто, ошибка+повтор, busy-guard и подтверждение отмены.

import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.InstantOrderDto
import com.yuldash.app.data.ScheduledOrdersDto
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun ScheduledOrdersScreen(onBack: () -> Unit, onActivated: (Int) -> Unit, isCurrentParent: () -> Boolean = { true }) {
    val generation by ApiClient.sessionChanges.collectAsState()
    key(generation) { ScheduledOrdersContent(generation, onBack, onActivated, isCurrentParent) }
}

@Composable
private fun ScheduledOrdersContent(generation: Long, onBack: () -> Unit, onActivated: (Int) -> Unit,
    isCurrentParent: () -> Boolean) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var data by remember { mutableStateOf(ScheduledOrdersDto(emptyList(), emptyList())) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var deferredId by remember { mutableStateOf(0) }
    var activationEnded by remember { mutableStateOf(false) }
    var listRevision by remember { mutableStateOf(0) }
    fun current() = scope.isActive && isCurrentParent() && ApiClient.isCurrentSession(generation)
    // Данные на экране есть, но последнее обновление не дошло: показываем честную плашку
    // вместо молчаливого вранья тикающим отсчётом.
    var stale by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var busyId by remember { mutableStateOf(0) }   // id, по которому идёт activate/cancel (гард двойного тапа)
    var cancelTarget by remember { mutableStateOf<InstantOrderDto?>(null) }
    val actionError = appText(
        "Не получилось. Проверь интернет и повтори.",
        "Булманы. Интернетты тикшереп ҡабатла.",
    )

    LaunchedEffect(reload) {
        if (!current()) return@LaunchedEffect
        loading = true
        val revision = listRevision
        val scheduledResult = ApiClient.getScheduledOrders(generation)
        if (!current() || revision != listRevision) return@LaunchedEffect
        val loaded = scheduledResult.getOrNull()
        if (loaded != null) {
            stale = false
            // Воркер мог активировать заказ до открытия этого экрана. GET /scheduled уже не вернёт
            // его как scheduled, поэтому дочитываем последние заказы и не оставляем человека без входа
            // в живой статус поездки.
            val alreadyActive = ApiClient.getMyInstantOrders(limit = 5, expectedGeneration = generation).getOrNull().orEmpty()
                .filter { it.role == "passenger" && shouldShowActivatedScheduled(it.status, it.scheduledAt, it.waitUntil) }
            if (!current() || revision != listRevision) return@LaunchedEffect
            data = loaded.copy(activated = (loaded.activated + alreadyActive).distinctBy { it.id })
            error = false
        } else {
            val e = scheduledResult.exceptionOrNull()
            if (data.scheduled.isEmpty() && data.activated.isEmpty()) error = (e as? ApiException)?.status != 401
            else if ((e as? ApiException)?.status != 401) stale = true
        }
        loading = false
    }

    // Тикаем раз в 30с — обратный отсчёт живой, а наступившие ко времени подтягиваются с сервера.
    // Сбой сети тут ОБЯЗАН быть виден: раньше поллинг обрабатывал только удачу, и при пропаже
    // связи отсчёт «через 10 мин» продолжал тикать по замороженным данным, а отменённый на
    // сервере предзаказ так и висел в списке. Человек шёл к дороге к несуществующей машине.
    // В фоне цикл стоит (repeatOnLifecycle RESUMED) — как остальные опросы приложения.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                delay(30_000)
                if (current() && busyId == 0) reload++
            }
        }
    }

    // «Часы» для обратного отсчёта: обновляются раз в 30с (перерисовка меток «через …»).
    // В фоне стоят: считать метки для невидимого экрана незачем, а при возврате время
    // берётся заново из системных часов — отсчёт сразу правильный.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) { nowMs = System.currentTimeMillis(); delay(30_000) }
        }
    }

    fun activate(id: Int) {
        if (busyId != 0 || !current() || data.scheduled.none { it.id == id }) return
        busyId = id
        deferredId = 0
        activationEnded = false
        scope.launch {
            val result = ApiClient.activateScheduledOrder(id, generation)
            if (!current()) return@launch
            listRevision++
            loading = false
            busyId = 0
            val order = result.getOrNull()
            if (order == null) {
                Toast.makeText(ctx, serverSaid(requireNotNull(result.exceptionOrNull()), actionError), Toast.LENGTH_LONG).show()
                reload++
                return@launch
            }
            when (scheduledActivation(order, id, ApiClient.myUserId())) {
                ScheduledActivation.WAITING -> {
                    data = data.copy(scheduled = data.scheduled.map { if (it.id == id) order else it })
                    deferredId = id
                }
                ScheduledActivation.OPEN -> onActivated(id)
                ScheduledActivation.ENDED -> { activationEnded = true; reload++ }
                ScheduledActivation.INVALID -> {
                    Toast.makeText(ctx, actionError, Toast.LENGTH_LONG).show()
                    reload++
                }
            }
        }
    }
    fun cancel(id: Int) {
        if (busyId != 0 || !current() || data.scheduled.none { it.id == id }) return
        busyId = id
        scope.launch {
            val result = ApiClient.cancelScheduledOrder(id, generation)
            if (!current()) return@launch
            listRevision++
            loading = false
            result.onSuccess {
                    data = data.copy(scheduled = data.scheduled.filterNot { it.id == id })
                }
                .onFailure {
                    Toast.makeText(ctx, serverSaid(it, actionError), Toast.LENGTH_LONG).show()
                    reload++
                }
            busyId = 0
        }
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Мои предзаказы", "Минең алдан заказдар"), onBack) },
    ) { padding ->
        AppPullRefresh(
            refreshing = loading && (data.scheduled.isNotEmpty() || data.activated.isNotEmpty()),
            onRefresh = { reload++ },
            modifier = Modifier.padding(padding),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            if (deferredId != 0 && data.scheduled.any { it.id == deferredId && it.status == "scheduled" }) {
                item { AppNoticeCard(text = appText("Предзаказ пока ждёт. Поиск ещё не начался. Попробуй позже.",
                    "Алдан заказ әлегә көтә. Эҙләү әле башланманы. Һуңыраҡ ҡабатла.")) }
            }
            if (activationEnded) item { AppNoticeCard(text = appText("Предзаказ уже завершён или отменён. Проверь список.",
                "Алдан заказ тамамланған йәки кире алынған. Исемлекте тикшер.")) }
            // Связь пропала, а на экране тикает обратный отсчёт — предупреждаем прямо, а не молчим.
            if (stale) {
                item {
                    AppNoticeCard(
                        text = appText(
                            "Не удалось обновить — время могло измениться. Потяни вниз.",
                            "Яңырта алманыҡ — ваҡыт үҙгәргән булыуы мөмкин. Аҫҡа тарт.",
                        ),
                    )
                }
            }
            item {
                MobilityScreenIntro(
                    mode = MobilityMode.Taxi,
                    title = appText("Такси к нужному времени", "Кәрәкле ваҡытҡа такси"),
                    subtitle = appText(
                        "Поиск запустится автоматически ко времени подачи.",
                        "Эҙләү килеү ваҡытына автоматик башланыр.",
                    ),
                    badge = appText("Предзаказ", "Алдан заказ"),
                )
            }

            when {
                loading -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(2) { SkeletonCard(lines = 3) } }
                }
                // Ошибка — это ошибка, а не «пусто»: у неё свой вид (AppErrorState), иначе человек
                // читает сбой сети как «предзаказов нет» и заказывает такси второй раз.
                error -> item {
                    AppErrorState(
                        onRetry = { reload++ },
                        title = appText("Не удалось загрузить предзаказы", "Алдан заказдарҙы йөкләп булманы"),
                        text = appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла"),
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
                            Text(appText("Пора ехать", "Барыр ваҡыт"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        items(data.activated, key = { "act-${it.id}" }) { order ->
                            ActivatedOrderCard(order = order, onOpen = {
                                if (current() && busyId == 0 && data.activated.any { it.id == order.id } &&
                                    scheduledActivation(order, order.id, ApiClient.myUserId()) == ScheduledActivation.OPEN) onActivated(order.id)
                            })
                        }
                    }
                    if (data.scheduled.isNotEmpty()) {
                        item {
                            Text(appText("Ждут своего времени", "Ваҡытын көтә"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                modifier = Modifier.padding(top = if (data.activated.isNotEmpty()) 4.dp else 0.dp))
                        }
                        items(data.scheduled, key = { it.id }) { order ->
                            ScheduledOrderCard(
                                order = order,
                                nowMs = nowMs,
                                busy = busyId == order.id,
                                anyBusy = busyId != 0,
                                onActivate = { activate(order.id) },
                                onCancel = { cancelTarget = order },
                            )
                        }
                    }
                    item {
                        InfoCard(
                            appText("Можно закрыть приложение", "Ҡушымтаны ябырға мөмкин"),
                            appText(
                                "Сервер сам начнёт поиск ко времени подачи. Открой Юлдаш ближе к поездке, чтобы следить за статусом.",
                                "Сервер килеү ваҡытына эҙләүҙе үҙе башлар. Статусты ҡарау өсөн Юлдашты сәфәргә яҡыныраҡ ас.",
                            ),
                            Icons.Default.Info,
                        )
                    }
                }
            }
        }
        }
    }

    cancelTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (busyId == 0) cancelTarget = null },
            containerColor = CanonSurface,
            title = {
                Text(
                    appText("Отменить предзаказ?", "Алдан заказды кире алырғамы?"),
                    color = CanonText,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    appText(
                        "Поиск машины в назначенное время не начнётся.",
                        "Билдәләнгән ваҡытта машина эҙләү башланмаясаҡ.",
                    ),
                    color = CanonMuted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = busyId == 0,
                    onClick = {
                        cancelTarget = null
                        cancel(target.id)
                    },
                ) {
                    Text(appText("Отменить заказ", "Заказды кире алыу"), color = CanonRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(enabled = busyId == 0, onClick = { cancelTarget = null }) {
                    Text(appText("Оставить", "Ҡалдырыу"), color = CanonMuted)
                }
            },
        )
    }
}

internal fun shouldShowActivatedScheduled(status: String, scheduledAt: String?, waitUntil: String?): Boolean {
    if (scheduledAt.isNullOrBlank() || status == "scheduled") return false
    val terminal = status == "done" || status == "cancelled" || status == "expired"
    val waitingQueue = !waitUntil.isNullOrBlank() && status != "done" && status != "cancelled"
    return !terminal || waitingQueue
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
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
        border = if (ready) BorderStroke(1.5.dp, CanonGreen2) else null,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RouteLine(order)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                Text(order.scheduledAt?.let { formatDepart(it) } ?: "—", color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                CountdownChip(order.scheduledAt, nowMs)
            }
            if (order.priceEstimate > 0) {
                Text(appText("≈ ${order.priceEstimate} ₽ · цену уточним при подаче", "≈ ${order.priceEstimate} ₽ · хаҡты килгәндә асыҡлайбыҙ"),
                    color = CanonMuted, fontSize = 14.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    // Общий темп дыхания (CanonMotion.PULSE). При выключенных анимациях точка просто горит —
    // карточка «пора ехать» остаётся заметной.
    val a by canonBreath(label = "ready-alpha", from = 0.5f, to = 1f)
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonGreen2),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.raised),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).alpha(a).background(CanonBg, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(activatedScheduledTitle(order.status),
                    color = CanonBg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("${order.fromText.ifBlank { appText("Точка А", "А нөктәһе") }} → ${order.toText.ifBlank { appText("Точка Б", "Б нөктәһе") }}",
                    color = CanonBg.copy(alpha = 0.9f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Surface(color = CanonBg.copy(alpha = 0.22f), shape = RoundedCornerShape(999.dp)) {
                Text(appText("Открыть", "Асыу"), color = CanonBg, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun RouteLine(order: InstantOrderDto) {
    MobilityRouteTimeline(
        from = order.fromText.ifBlank { appText("Точка А", "А нөктәһе") },
        to = order.toText.ifBlank { appText("Точка Б", "Б нөктәһе") },
        compact = true,
    )
}

@Composable
private fun activatedScheduledTitle(status: String): String = when (status) {
    "accepted", "arriving" -> appText("Водитель едет к тебе", "Йөрөтөүсе һиңә килә")
    "onboard" -> appText("Поездка началась", "Сәфәр башланды")
    else -> appText("Пора ехать — ищем машину", "Барыр ваҡыт — машина эҙләйбеҙ")
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
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
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
