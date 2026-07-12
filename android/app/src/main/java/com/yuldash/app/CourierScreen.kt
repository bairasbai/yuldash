package com.yuldash.app

// ============================ «Режим курьера» (работа) — C1 ============================
// Если не одобрен → CTA «Стать курьером». Если одобрен → тумблер «На линии» + выбор зоны
// (🏙 город / 🛣 межгород / 🌍 регион) → доступные заказы (без телефона, «Взять»→accept) +
// «Везу» (телефон виден, «В пути»/«Доставлено» по коду). Кабинет: доставлено N · наш сбор.
// Бэкенд: GET /courier/me, POST /courier/online|offline, GET /courier/available,
//         приём/доставка — существующие /parcels/{id}/accept, /parcels/{id}/status, /parcels/carrying.

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.CourierMeDto
import com.yuldash.app.data.ParcelDto
import kotlinx.coroutines.launch

@Composable
internal fun CourierScreen(onBack: () -> Unit, onBecomeCourier: () -> Unit) {
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<CourierMeDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true; error = false
        ApiClient.getCourierMe()
            .onSuccess { me = it }
            .onFailure { error = true }
        loading = false
    }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Режим курьера", "Курьер режимы"), onBack) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val m = me
            when {
                loading && m == null -> Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SkeletonCard(lines = 2)
                    SkeletonCard(lines = 3)
                    SkeletonCard(lines = 3)
                }
                error && m == null -> Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) {
                    AppErrorState(onRetry = { reloadKey++ })
                }
                m == null || m.application?.status != "approved" -> CourierNotApprovedView(m, onBecomeCourier)
                else -> CourierWorkContent(m, onReloadMe = { reloadKey++ })
            }
        }
    }
}

// ─────────────────────────── Не одобрен → CTA ───────────────────────────
@Composable
private fun CourierNotApprovedView(me: CourierMeDto?, onBecomeCourier: () -> Unit) {
    val status = me?.application?.status
    val (emoji, title, body) = when (status) {
        "pending" -> Triple(
            "⏳",
            appText("Заявка на проверке", "Заявка тикшереүҙә"),
            appText("Как только мы одобрим твою заявку — здесь появятся заказы. Обычно это меньше дня.", "Заявкаңды раҫлау менән — бында заказдар күренер. Ғәҙәттә был бер көндән әҙерәк."),
        )
        "rejected" -> Triple(
            "✋",
            appText("Заявка отклонена", "Заявка кире ҡағылды"),
            appText("Загляни в заявку, исправь замечания и подай снова.", "Заявкаға кер, иҫкәрмәләрҙе төҙәт тә ҡабат бир."),
        )
        else -> Triple(
            "🛵",
            appText("Стань курьером Юлдаша", "Юлдаш курьеры бул"),
            appText("Развози посылки своим и зарабатывай. Комиссия всего 8% — прозрачно.", "Үҙебеҙҙекеләргә бандеролдәр илт тә аҡса эшлә. Комиссия бары 8% — асыҡ."),
        )
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        contentPadding = PaddingValues(vertical = 24.dp),
    ) {
        item {
            Surface(shape = CircleShape, color = CanonMint, border = BorderStroke(1.dp, CanonGreen2.copy(alpha = 0.3f))) {
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 44.sp) }
            }
            Spacer(Modifier.height(20.dp))
        }
        item {
            Text(title, color = CanonText, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Black, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(body, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(20.dp))
        }
        item {
            AppButton(
                text = if (status == "rejected") appText("Открыть заявку", "Заявканы асыу") else if (status == "pending") appText("Открыть заявку", "Заявканы асыу") else appText("Стать курьером", "Курьер булыу"),
                onClick = onBecomeCourier,
                style = AppButtonStyle.Accent,
                icon = Icons.Default.DeliveryDining,
            )
        }
    }
}

// ─────────────────────────── Одобрен → работа ───────────────────────────
@Composable
private fun CourierWorkContent(me: CourierMeDto, onReloadMe: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var online by remember { mutableStateOf(me.profile?.online ?: false) }
    var zone by remember { mutableStateOf(me.profile?.zone?.takeIf { it.isNotBlank() } ?: "city") }
    var workCity by remember { mutableStateOf(me.profile?.workCity ?: "") }
    var toggling by remember { mutableStateOf(false) }
    var sub by remember { mutableIntStateOf(0) }   // 0 = доступные, 1 = везу, 2 = кабинет

    val toggleErr = appText("Не получилось изменить статус. Проверь сеть.", "Статусты үҙгәртеп булманы. Селтәрҙе тикшер.")
    val needCityMsg = appText("Укажи город работы", "Эш ҡалаһын күрһәт")

    fun setOnline(target: Boolean) {
        if (toggling) return
        if (target && zone == "city" && workCity.isBlank()) {
            Toast.makeText(ctx, needCityMsg, Toast.LENGTH_SHORT).show(); return
        }
        toggling = true
        scope.launch {
            val res = if (target) ApiClient.courierOnline(zone, if (zone == "city") workCity.trim() else null, null)
            else ApiClient.courierOffline()
            res.onSuccess { online = target }
                .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: toggleErr, Toast.LENGTH_SHORT).show() }
            toggling = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Тумблер «На линии» + зона.
        Surface(color = if (online) CanonMint else CanonSurface, shape = CanonCardShape, border = BorderStroke(1.dp, if (online) CanonGreen2 else CanonBorder), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = if (online) CanonGreen2 else CanonBg, shape = CircleShape) {
                        Icon(Icons.Default.DeliveryDining, contentDescription = null, tint = if (online) Color.White else CanonMuted, modifier = Modifier.padding(9.dp).size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (online) appText("Ты на линии", "Һин линияла") else appText("Не на линии", "Линияла түгел"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                        Text(if (online) appText("Заказы приходят тебе", "Заказдар һиңә килә") else appText("Включи, чтобы брать заказы", "Заказ алыр өсөн ҡабыҙ"), color = CanonMuted, fontSize = 13.sp)
                    }
                    Switch(
                        checked = online,
                        onCheckedChange = { setOnline(it) },
                        enabled = !toggling,
                        colors = SwitchDefaults.colors(checkedTrackColor = CanonGreen2),
                    )
                }
                // Выбор зоны.
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CourierZoneChip("🏙", appText("Город", "Ҡала"), zone == "city") { zone = "city" }
                    CourierZoneChip("🛣", appText("Межгород", "Ҡалалар араһы"), zone == "intercity") { zone = "intercity" }
                    CourierZoneChip("🌍", appText("Регион", "Төбәк"), zone == "region") { zone = "region" }
                }
                if (zone == "city") {
                    OutlinedTextField(
                        value = workCity,
                        onValueChange = { workCity = it.take(40) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(appText("Город работы", "Эш ҡалаһы")) },
                        placeholder = { Text(appText("Например: Баймак", "Мәҫәлән: Баймаҡ"), color = CanonMuted) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                    )
                }
            }
        }
        // Под-вкладки.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CourierSubTab(appText("Заказы", "Заказдар"), sub == 0, Modifier.weight(1f)) { sub = 0 }
            CourierSubTab(appText("Везу", "Илтәм"), sub == 1, Modifier.weight(1f)) { sub = 1 }
            CourierSubTab(appText("Кабинет", "Кабинет"), sub == 2, Modifier.weight(1f)) { sub = 2 }
        }
        Spacer(Modifier.height(12.dp))
        AnimatedContent(
            targetState = sub,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(140)) },
            label = "courier-sub",
        ) { s ->
            when (s) {
                0 -> CourierAvailableTab(workCity = if (zone == "city") workCity else "")
                1 -> CourierCarryingTab()
                else -> CourierCabinetTab(me, onReloadMe)
            }
        }
    }
}

@Composable
private fun CourierZoneChip(emoji: String, label: String, active: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) CanonGreen2 else CanonSurface, tween(200), label = "zone")
    Surface(
        onClick = onClick, color = bg, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (active) CanonGreen2 else CanonBorder),
        modifier = Modifier.height(44.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(emoji, fontSize = 15.sp)
            Text(label, color = if (active) Color.White else CanonMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun CourierSubTab(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) CanonMint else CanonSurface, tween(220), label = "csub")
    Surface(
        onClick = onClick, color = bg, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (active) CanonGreen2 else CanonBorder),
        modifier = modifier.height(46.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(label, color = if (active) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Black, fontSize = 14.sp)
        }
    }
}

// ─────────────────────────── Доступные заказы ───────────────────────────
@Composable
private fun CourierAvailableTab(workCity: String) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<ParcelDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableIntStateOf(0) }
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    val actionErr = appText("Не получилось взять. Проверь сеть.", "Алып булманы. Селтәрҙе тикшер.")
    val tookMsg = appText("Заказ у тебя. Он во вкладке «Везу».", "Заказ һиндә. Ул «Илтәм» бүлегендә.")

    fun reload() {
        loading = true; error = null
        scope.launch {
            ApiClient.getCourierAvailable(fromCity = workCity.takeIf { it.isNotBlank() })
                .onSuccess { list = it }
                .onFailure { error = (it as? com.yuldash.app.data.ApiException)?.message ?: loadErr }
            loading = false
        }
    }
    LaunchedEffect(workCity) { reload() }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item {
            Text(
                appText("Возьми заказ по своей зоне. Телефон получателя откроется, когда возьмёшь.", "Үҙ зонаң буйынса заказ ал. Алыусы телефоны заказ алғас асыла."),
                color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
            )
        }
        when {
            loading && list.isEmpty() -> {
                item { SkeletonCard(lines = 3) }
                item { SkeletonCard(lines = 3) }
            }
            error != null && list.isEmpty() -> item { ListedError(error ?: "") { reload() } }
            list.isEmpty() -> item {
                AppEmptyState(
                    title = appText("Свободных заказов нет", "Буш заказдар юҡ"),
                    text = appText("Загляни позже — соседи скоро что-нибудь отправят.", "Һуңыраҡ кер — күршеләр тиҙҙән берәй нәмә ебәрер."),
                    icon = Icons.Default.LocalShipping,
                )
            }
            else -> items(list.size, key = { "cav-" + list[it].id }) { i ->
                Box(Modifier.appearIn(i.coerceAtMost(6))) {
                    CourierAvailableCard(
                        p = list[i],
                        busy = busyId == list[i].id,
                        onTake = {
                            if (busyId != 0) return@CourierAvailableCard
                            busyId = list[i].id
                            scope.launch {
                                ApiClient.acceptParcel(list[i].id)
                                    .onSuccess { Toast.makeText(ctx, tookMsg, Toast.LENGTH_SHORT).show(); reload() }
                                    .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr, Toast.LENGTH_SHORT).show() }
                                busyId = 0
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CourierAvailableCard(p: ParcelDto, busy: Boolean, onTake: () -> Unit) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Inventory2, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(10.dp).size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CourierRouteRow(p.fromCity, p.toCity)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(parcelSizeLabel(p.size), color = CanonMuted, fontSize = 13.sp)
                        CourierDeliveryTag(p.deliveryType, p.urgency)
                    }
                }
                val amount = if (p.priceKop > 0) p.priceKop else p.feeKop
                if (amount > 0) Text(kopToRub(amount), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
            if (p.description.isNotBlank()) {
                Text(p.description, color = CanonText, fontSize = 14.sp, lineHeight = 19.sp)
            }
            if (p.deliveryType == "buy_bring" && p.codAmountKop > 0) {
                Text(appText("Выкуп товара: ", "Тауар выкупы: ") + kopToRub(p.codAmountKop), color = CanonWarn, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            AppButton(
                text = appText("Взять заказ", "Заказ алыу"),
                onClick = onTake,
                style = AppButtonStyle.Primary,
                icon = Icons.Default.LocalShipping,
                enabled = !busy,
                loading = busy,
            )
        }
    }
}

// ─────────────────────────── Везу ───────────────────────────
@Composable
private fun CourierCarryingTab() {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<ParcelDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableIntStateOf(0) }
    var deliverTarget by remember { mutableStateOf<ParcelDto?>(null) }
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    val actionErr = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    val transitMsg = appText("Статус обновлён: в пути", "Статус яңырҙы: юлда")
    val deliveredMsg = appText("Заказ вручён. Спасибо!", "Заказ тапшырылды. Рәхмәт!")

    fun reload() {
        loading = true; error = null
        scope.launch {
            ApiClient.getCarryingParcels()
                .onSuccess { list = it.sortedByDescending { p -> p.acceptedAt ?: p.createdAt } }
                .onFailure { error = (it as? com.yuldash.app.data.ApiException)?.message ?: loadErr }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        when {
            loading && list.isEmpty() -> {
                item { SkeletonCard(lines = 3) }
                item { SkeletonCard(lines = 3) }
            }
            error != null && list.isEmpty() -> item { ListedError(error ?: "") { reload() } }
            list.isEmpty() -> item {
                AppEmptyState(
                    title = appText("Ты пока ничего не везёшь", "Әлегә бер нәмә лә илтмәйһең"),
                    text = appText("Возьми заказ во вкладке «Заказы» — он появится здесь.", "«Заказдар» бүлегендә заказ ал — ул бында күренер."),
                    icon = Icons.Default.LocalShipping,
                )
            }
            else -> items(list.size, key = { "ccar-" + list[it].id }) { i ->
                Box(Modifier.appearIn(i.coerceAtMost(6))) {
                    CourierCarryingCard(
                        p = list[i],
                        busy = busyId == list[i].id,
                        onTransit = {
                            if (busyId != 0) return@CourierCarryingCard
                            busyId = list[i].id
                            scope.launch {
                                ApiClient.setParcelStatus(list[i].id, "in_transit")
                                    .onSuccess { Toast.makeText(ctx, transitMsg, Toast.LENGTH_SHORT).show(); reload() }
                                    .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr, Toast.LENGTH_SHORT).show() }
                                busyId = 0
                            }
                        },
                        onDeliver = { deliverTarget = list[i] },
                    )
                }
            }
        }
    }

    deliverTarget?.let { target ->
        var code by remember(target.id) { mutableStateOf("") }
        var codeError by remember(target.id) { mutableStateOf<String?>(null) }
        var submitting by remember(target.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!submitting) deliverTarget = null },
            containerColor = CanonSurface,
            title = { Text(appText("Код вручения", "Тапшырыу коды"), color = CanonText, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(appText("Спроси код у получателя и введи его. Так подтвердим, что заказ попал по адресу.", "Кодты алыусынан һора һәм индер. Шулай заказ дөрөҫ ергә барғанын раҫлайбыҙ."), color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it; codeError = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(appText("Код от получателя", "Алыусы коды")) },
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        isError = codeError != null,
                    )
                    if (codeError != null) Text(codeError ?: "", color = CanonRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !submitting && code.isNotBlank(),
                    onClick = {
                        submitting = true; codeError = null
                        scope.launch {
                            ApiClient.setParcelStatus(target.id, "delivered", code.trim())
                                .onSuccess {
                                    Toast.makeText(ctx, deliveredMsg, Toast.LENGTH_SHORT).show()
                                    deliverTarget = null; reload()
                                }
                                .onFailure { codeError = (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr }
                            submitting = false
                        }
                    },
                ) { Text(appText("Подтвердить вручение", "Тапшырыуҙы раҫлау"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(enabled = !submitting, onClick = { deliverTarget = null }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) } },
        )
    }
}

@Composable
private fun CourierCarryingCard(p: ParcelDto, busy: Boolean, onTransit: () -> Unit, onDeliver: () -> Unit) {
    val delivered = p.status == "delivered"
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CourierRouteRow(p.fromCity, p.toCity)
                    Text(parcelSizeLabel(p.size) + (if (p.description.isNotBlank()) "  ·  ${p.description}" else ""), color = CanonMuted, fontSize = 13.sp)
                }
                ParcelStatusChip(p.status)
            }
            Surface(color = CanonMint, shape = CanonItemShape) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(p.receiverName, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    if (p.receiverPhone.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Phone, contentDescription = appText("Телефон получателя", "Алыусы телефоны"), tint = CanonGreen2, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(p.receiverPhone, color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 15.sp)
                        }
                    }
                }
            }
            if (p.deliveryType == "buy_bring" && p.codAmountKop > 0) {
                Text(appText("Выкуп товара: ", "Тауар выкупы: ") + kopToRub(p.codAmountKop), color = CanonWarn, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            val myIncome = p.priceKop - p.commissionKop
            if (myIncome > 0) {
                Text(appText("Твой доход: ", "Һинең килем: ") + kopToRub(myIncome) + appText(" (наш сбор ${kopToRub(p.commissionKop)})", " (беҙҙең сбор ${kopToRub(p.commissionKop)})"), color = CanonMuted, fontSize = 13.sp)
            } else if (p.feeKop > 0) {
                Text(appText("Твой сбор: ", "Һинең сбор: ") + kopToRub(p.feeKop), color = CanonMuted, fontSize = 13.sp)
            }
            if (!delivered) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (p.status == "accepted") {
                        AppButton(
                            text = appText("В пути", "Юлда"),
                            onClick = onTransit,
                            style = AppButtonStyle.Secondary,
                            fillWidth = false,
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            loading = busy,
                        )
                    }
                    AppButton(
                        text = appText("Доставлено", "Тапшырылды"),
                        onClick = onDeliver,
                        style = AppButtonStyle.Primary,
                        icon = Icons.Default.CheckCircle,
                        fillWidth = false,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

// ─────────────────────────── Кабинет курьера ───────────────────────────
@Composable
private fun CourierCabinetTab(me: CourierMeDto, onReloadMe: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item {
            Surface(color = CanonMint, shape = CanonCardShape, border = BorderStroke(1.dp, CanonGreen2)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = CanonSurface, shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(26.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(appText("Доставлено заказов", "Тапшырылған заказдар"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("${me.statement.deliveredCount}", color = CanonText, fontWeight = FontWeight.Black, fontSize = 30.sp)
                    }
                }
            }
        }
        item {
            Surface(color = CanonSurface, shape = CanonCardShape, border = BorderStroke(1.dp, CanonBorder)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Default.Payments, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(26.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(appText("Наш сбор с твоих доставок", "Илтеүҙәреңдән беҙҙең сбор"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(kopToRub(me.statement.commissionKop), color = CanonText, fontWeight = FontWeight.Black, fontSize = 26.sp)
                        Text(appText("Это сбор Юлдаша (8%), а не твой заработок — твой доход остаётся у тебя.", "Был — Юлдаш сборы (8%), һинең килемең түгел — килемең үҙеңдә ҡала."), color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
        }
        me.profile?.let { pr ->
            item {
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(appText("Транспорт: ", "Транспорт: ") + courierTransportLabel(me.application?.transport ?: ""), color = CanonText, fontSize = 14.sp)
                        Text(appText("Статус: ", "Статус: ") + if (pr.online) appText("на линии", "линияла") else appText("не на линии", "линияла түгел"), color = if (pr.online) CanonGreen2 else CanonMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            AppButton(appText("Обновить", "Яңыртыу"), onReloadMe, style = AppButtonStyle.Secondary)
        }
    }
}

// ─────────────────────────── Общие мелочи ───────────────────────────
/** Строка «маршрут»: Откуда → Куда. */
@Composable
private fun CourierRouteRow(from: String, to: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Default.Place, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
        Text(from.ifBlank { "—" }, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("→", color = CanonMuted, fontSize = 14.sp)
        Text(to.ifBlank { "—" }, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

/** Ярлык типа доставки + срочности (для карточек курьера). */
@Composable
internal fun CourierDeliveryTag(deliveryType: String, urgency: String) {
    val (label, bg, fg) = when (deliveryType) {
        "buy_bring" -> Triple(appText("Купи и привези", "Ал да килтер"), CanonWarnBg, CanonWarn)
        "courier" -> Triple(appText("Курьер", "Курьер"), CanonMint, CanonGreen2)
        else -> Triple(appText("По пути", "Юл ыңғайы"), CanonMint, CanonGreen2)
    }
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(
            label + if (urgency == "now") appText(" · срочно", " · ашығыс") else "",
            color = fg, fontWeight = FontWeight.Bold, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
