package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.AdStatsDto
import com.yuldash.app.data.AdminAdDto
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.launch

private val PLAN_OPTIONS = listOf("founder", "standard", "premium")
// key to (ru, ba) — appText вызывается на месте отрисовки (он @Composable)
private val PLACEMENT_OPTIONS = listOf(
    "route" to ("Маршрут" to "Маршрут"),
    "ridesList" to ("Лента" to "Тасма"),
    "profile" to ("Профиль" to "Профиль"),
    "nearby" to ("Рядом" to "Янда"),
    "tripDetails" to ("Поездка" to "Сәфәр"),
    "help" to ("Помощь" to "Ярҙам"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdminAdsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var founderUsed by remember { mutableStateOf(0) }
    var founderLimit by remember { mutableStateOf(10) }
    val items: SnapshotStateList<AdminAdDto> = remember { mutableStateListOf() }
    var stats by remember { mutableStateOf<Map<String, AdStatsDto>>(emptyMap()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AdminAdDto?>(null) }

    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")

    suspend fun reload() {
        loading = true; error = null
        ApiClient.getAdminAds()
            .onSuccess { d -> founderUsed = d.founderUsed; founderLimit = d.founderLimit; items.clear(); items.addAll(d.items) }
            .onFailure { error = loadErr }
        ApiClient.getAdStats().onSuccess { stats = it }
        loading = false
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Управление рекламой", "Реклама идаралау"), onBack) },
    ) { padding ->
        when {
            loading -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = CanonGreen)
            }
            error != null -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(error ?: "", color = CanonRed, fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { scope.launch { reload() } }, colors = ButtonDefaults.buttonColors(containerColor = CanonGreen, contentColor = CanonBg)) {
                    Text(appText("Повторить", "Ҡабатлау"), fontWeight = FontWeight.Bold)
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                // счётчик founder + кнопка создать
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = CanonGold.copy(alpha = 0.15f), shape = RoundedCornerShape(999.dp)) {
                            // Золотом по золоту было 1.63:1 — счётчик читался только на просвет.
                            Text("Founder $founderUsed/$founderLimit", color = canonChipInk(CanonGold), fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { editing = null; showForm = !showForm }, colors = ButtonDefaults.buttonColors(containerColor = CanonGreen, contentColor = CanonBg), shape = CanonCardShape) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(appText("Создать", "Булдырыу"), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (showForm || editing != null) {
                    item { CreateAdForm(founderFull = founderUsed >= founderLimit, edit = editing, onCreated = { showForm = false; editing = null; scope.launch { reload() } }) }
                }
                if (items.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(appText("Объявлений пока нет", "Иғландар юҡ әле"), color = CanonText, fontWeight = FontWeight.Bold)
                            Text(appText("Создай первое — оно появится в приложении после публикации", "Беренсене булдыр — баҫтырғас ҡушымтала күренер"), color = CanonMuted, fontSize = 14.sp)
                        }
                    }
                }
                items(items, key = { it.id }) { ad ->
                    AdAdminCard(
                        ad = ad,
                        stat = stats[ad.id],
                        busy = busyId == ad.id,
                        onPublish = {
                            busyId = ad.id
                            scope.launch { ApiClient.setAdStatus(ad.id, "active").onSuccess { reload() }.onFailure { error = loadErr }; busyId = null }
                        },
                        onPause = {
                            busyId = ad.id
                            scope.launch { ApiClient.setAdStatus(ad.id, "paused").onSuccess { reload() }.onFailure { error = loadErr }; busyId = null }
                        },
                        onDelete = {
                            busyId = ad.id
                            scope.launch { ApiClient.deleteAd(ad.id).onSuccess { reload() }.onFailure { error = loadErr }; busyId = null }
                        },
                        onEdit = { editing = ad; showForm = false },
                        onApprove = {
                            busyId = ad.id
                            scope.launch { ApiClient.approveAd(ad.id, ad.erid).onSuccess { reload() }.onFailure { error = loadErr }; busyId = null }
                        },
                        onReject = { reason ->
                            busyId = ad.id
                            scope.launch { ApiClient.rejectAd(ad.id, reason).onSuccess { reload() }.onFailure { error = loadErr }; busyId = null }
                        },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
internal fun AdAdminCard(
    ad: AdminAdDto, stat: AdStatsDto?, busy: Boolean,
    onPublish: () -> Unit, onPause: () -> Unit, onDelete: () -> Unit, onEdit: () -> Unit,
    onApprove: () -> Unit, onReject: (String) -> Unit,
) {
    val statusColor = when {
        ad.status == "pending_review" -> CanonGold
        ad.status == "rejected" -> CanonRed
        ad.status == "active" && ad.live -> CanonGreen
        ad.status == "paused" -> CanonGold
        ad.expired -> CanonRed
        else -> CanonMuted
    }
    val statusLabel = when (ad.status) {
        "active" -> if (ad.live) appText("Активно", "Актив") else if (ad.expired) appText("Истекло", "Бөттө") else appText("Запланировано", "Планлы")
        "pending_review" -> appText("На модерации", "Тикшереүҙә")
        "rejected" -> appText("Отклонено", "Кире ҡағылды")
        "paused" -> appText("Пауза", "Туҡтатылған")
        "draft" -> appText("Черновик", "Ҡаралама")
        else -> ad.status
    }
    var showReject by remember(ad.id) { mutableStateOf(false) }
    var reason by remember(ad.id) { mutableStateOf(ad.rejectReason) }
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape, elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ad.title.ifBlank { appText("Без названия", "Исемһеҙ") }, modifier = Modifier.weight(1f), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Surface(color = statusColor.copy(alpha = 0.14f), shape = RoundedCornerShape(999.dp)) {
                    // «На модерации» и «Пауза» окрашены золотом: золотом по золоту — 1.69:1.
                    Text(statusLabel, color = canonChipInk(statusColor), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            Text("${ad.partner.ifBlank { if (ad.ownerId != null) appText("Партнёр", "Партнёр") else "—" }} · ${planLabel(ad.plan)}", color = CanonMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (ad.text.isNotBlank()) Text(ad.text, color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp)
            Text(
                appText("Места: ${ad.placements.ifBlank { "—" }}", "Урын: ${ad.placements.ifBlank { "—" }}") +
                    (ad.endsAt?.let { appText(" · до ${it.take(10)}", " · ${it.take(10)} тиклем") } ?: "") +
                    appText(
                        "  ·  показы ${stat?.impressions ?: 0} · клики ${stat?.clicks ?: 0}",
                        "  ·  күрһәтеү ${stat?.impressions ?: 0} · баҫыу ${stat?.clicks ?: 0}",
                    ),
                color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
            )
            if (ad.status == "rejected" && ad.rejectReason.isNotBlank()) {
                Text(appText("Причина отказа: ${ad.rejectReason}", "Кире ҡағыу сәбәбе: ${ad.rejectReason}"), color = CanonRed, fontSize = 12.sp, lineHeight = 17.sp)
            }
            // Модерация: pending_review → одобрить / отклонить (с причиной)
            if (ad.status == "pending_review") {
                if (showReject) {
                    OutlinedTextField(
                        value = reason, onValueChange = { reason = it },
                        label = { Text(appText("Причина отказа (партнёр увидит)", "Кире ҡағыу сәбәбе (партнёр күрә)")) },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, shape = CanonCardShape,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showReject = false }, enabled = !busy, modifier = Modifier.weight(1f), shape = CanonCardShape) {
                            Text(appText("Отмена", "Кире"), fontSize = 14.sp)
                        }
                        Button(onClick = { onReject(reason) }, enabled = !busy && reason.isNotBlank(), modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = CanonRed, contentColor = CanonBg), shape = CanonCardShape) {
                            Text(appText("Отклонить", "Кире ҡағырға"), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onApprove, enabled = !busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen, contentColor = CanonBg), shape = CanonCardShape) {
                            if (busy) CircularProgressIndicator(color = CanonBg, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text(appText("Одобрить", "Раҫларға"), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = { showReject = true }, enabled = !busy, modifier = Modifier.weight(1f), shape = CanonCardShape) {
                            Text(appText("Отклонить", "Кире ҡағырға"), color = CanonRed, fontSize = 14.sp)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ad.status == "active") {
                    OutlinedButton(onClick = onPause, enabled = !busy, modifier = Modifier.weight(1f), shape = CanonCardShape) {
                        Text(appText("Пауза", "Туҡтатылған"), fontSize = 14.sp)
                    }
                } else if (ad.status != "pending_review") {
                    Button(onClick = onPublish, enabled = !busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen, contentColor = CanonBg), shape = CanonCardShape) {
                        if (busy) CircularProgressIndicator(color = CanonBg, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text(appText("Опубликовать", "Баҫтырырға"), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedButton(onClick = onEdit, enabled = !busy, shape = CanonCardShape) {
                    Text(appText("Изменить", "Үҙгәртергә"), fontSize = 14.sp)
                }
                OutlinedButton(onClick = onDelete, enabled = !busy, shape = CanonCardShape) {
                    Text(appText("Удалить", "Бөтөрөргә"), color = CanonRed, fontSize = 14.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CreateAdForm(founderFull: Boolean, edit: AdminAdDto? = null, onCreated: () -> Unit) {
    val scope = rememberCoroutineScope()
    var partner by remember(edit?.id) { mutableStateOf(edit?.partner ?: "") }
    var title by remember(edit?.id) { mutableStateOf(edit?.title ?: "") }
    var text by remember(edit?.id) { mutableStateOf(edit?.text ?: "") }
    var button by remember(edit?.id) { mutableStateOf(edit?.button ?: "") }
    var erid by remember(edit?.id) { mutableStateOf(edit?.erid ?: "") }
    var target by remember(edit?.id) { mutableStateOf(edit?.target ?: "") }
    var city by remember(edit?.id) { mutableStateOf(edit?.cities ?: "") }
    var price by remember(edit?.id) { mutableStateOf("") }
    var plan by remember(edit?.id) { mutableStateOf(edit?.plan ?: "standard") }
    val places = remember(edit?.id) { mutableStateListOf<String>().apply { edit?.placements?.split(",")?.forEach { if (it.isNotBlank()) add(it) } } }
    var sending by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val genericErr = appText("Не удалось сохранить", "Һаҡлап булманы")
    val canSave = partner.isNotBlank() && title.isNotBlank() && text.trim().length >= 3 && erid.isNotBlank() && !sending

    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape, elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(appText(if (edit != null) "Изменить объявление" else "Новое объявление", if (edit != null) "Иғланды үҙгәртеү" else "Яңы иғлан"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            OutlinedTextField(partner, { partner = it }, label = { Text(appText("Рекламодатель", "Рекламала ҡатнашыусы")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(title, { title = it }, label = { Text(appText("Заголовок", "Башлыҡ")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(text, { text = it }, label = { Text(appText("Текст", "Текст")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(button, { button = it }, label = { Text(appText("Кнопка", "Төймә")) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(city, { city = it }, label = { Text(appText("Город", "Ҡала")) }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(target, { target = it }, label = { Text(appText("Ссылка при клике", "Баҫҡанда һылтанма")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(erid, { erid = it }, label = { Text(appText("erid (маркировка)", "erid (билдәләмә)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (edit == null) OutlinedTextField(price, { price = it.filter { c -> c.isDigit() }.take(7) }, label = { Text(appText("Цена партнёру, ₽ (0 — без оплаты)", "Партнёр хаҡы, ₽ (0 — түләүһеҙ)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text(appText("Тариф", "Тариф"), color = CanonMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PLAN_OPTIONS.forEach { p ->
                    val sel = plan == p
                    val disabled = p == "founder" && founderFull && !sel
                    Surface(
                        color = if (sel) CanonGreen else CanonBg,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        Text(
                            planLabel(p) + if (disabled) appText(" (нет мест)", " (урын юҡ)") else "",
                            color = if (sel) CanonBg else if (disabled) CanonMuted else CanonText,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .then(if (disabled) Modifier else Modifier.clickable { plan = p }),
                        )
                    }
                }
            }

            Text(appText("Места показа", "Күрһәтеү урындары"), color = CanonMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PLACEMENT_OPTIONS.forEach { (key, label) ->
                    val (labelRu, labelBa) = label
                    val sel = places.contains(key)
                    Surface(color = if (sel) CanonGreen2 else CanonBg, shape = RoundedCornerShape(999.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            appText(labelRu, labelBa), color = if (sel) Color.White else CanonText, fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).clickable {
                                if (sel) places.remove(key) else places.add(key)
                            },
                        )
                    }
                }
            }

            err?.let { Text(it, color = CanonRed, fontSize = 14.sp) }
            Button(
                onClick = {
                    sending = true; err = null
                    scope.launch {
                        val res = if (edit != null)
                            ApiClient.updateAd(edit.id, partner.trim(), title.trim(), text.trim(), button.trim(), plan, places.joinToString(","), erid.trim(), target.trim(), city.trim())
                        else
                            ApiClient.createAd(partner.trim(), title.trim(), text.trim(), button.trim(), plan, places.joinToString(","), erid.trim(), target.trim(), city.trim(), price.toIntOrNull() ?: 0)
                        res.onSuccess { onCreated() }.onFailure { err = (it as? com.yuldash.app.data.ApiException)?.message ?: genericErr }
                        sending = false
                    }
                },
                enabled = canSave,
                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen, contentColor = CanonBg),
                shape = CanonCardShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            ) {
                if (sending) CircularProgressIndicator(color = CanonBg, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(appText(if (edit != null) "Сохранить" else "Создать (черновик)", if (edit != null) "Һаҡлау" else "Булдырыу (ҡаралама)"), fontWeight = FontWeight.Bold)
            }
            if (edit == null) Text(appText("Указал цену → объявление попадёт в «Заявки на оплату». Партнёр заплатил → подтвердишь → реклама опубликуется. Цена 0 → публикуешь вручную.", "Хаҡ ҡуйһаң → иғлан «Түләү заявкалары»на эләгә. Партнёр түләне → раҫлайһың → реклама баҫтырыла. Хаҡ 0 → үҙең баҫтыр."), color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
internal fun planLabel(p: String): String = when (p) {
    "founder" -> appText("Основатель", "Нигеҙләүсе")
    "premium" -> appText("Премиум", "Премиум")
    else -> appText("Стандарт", "Стандарт")
}
