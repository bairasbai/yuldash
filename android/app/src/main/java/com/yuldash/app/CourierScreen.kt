package com.yuldash.app

// ============================ «Режим курьера» (работа) — C1 ============================
// Если не одобрен → CTA «Стать курьером». Если одобрен → тумблер «На линии» + выбор зоны
// (🏙 город / 🛣 межгород / 🌍 регион) → доступные заказы (без телефона, «Взять»→accept) +
// «Везу» (телефон виден, «В пути»/«Доставлено» по коду). Кабинет: доставлено N · наш сбор.
// Бэкенд: GET /courier/me, POST /courier/online|offline, GET /courier/available,
//         приём/доставка — существующие /parcels/{id}/accept, /parcels/{id}/status, /parcels/carrying.

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.CourierMeDto
import com.yuldash.app.data.ParcelDto
import com.yuldash.app.data.PayCommissionDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CourierScreen(
    onBack: () -> Unit,
    onBecomeCourier: () -> Unit,
    // «Мой заработок» курьера: раньше он видел только «должен Юлдашу столько-то».
    onEarnings: () -> Unit = {},
) {
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
                else -> CourierWorkContent(m, onReloadMe = { reloadKey++ }, onEarnings = onEarnings)
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
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                    // Приглашение стать курьером — брендовая иконка (в тон онлайн-герою); статусы (⏳/✋) остаются эмодзи.
                    if (emoji == "🛵") Icon(painterResource(R.drawable.yu_mode_courier), contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(46.dp))
                    else Text(emoji, fontSize = 44.sp)
                }
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
private fun CourierWorkContent(me: CourierMeDto, onReloadMe: () -> Unit, onEarnings: () -> Unit = {}) {
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

    // C2: смена зоны. Если уже на линии — сразу переустанавливаем зону на сервере, иначе заказы
    // продолжали бы приходить по старой зоне (список тоже перезапросится: он завязан на zone).
    fun changeZone(newZone: String) {
        if (newZone == zone) return
        zone = newZone
        if (online && !(newZone == "city" && workCity.isBlank())) {
            scope.launch {
                ApiClient.courierOnline(newZone, if (newZone == "city") workCity.trim() else null, null)
                    .onFailure { Toast.makeText(ctx, toggleErr, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Тумблер «На линии» + зона.
        Surface(color = if (online) CanonMint else CanonSurface, shape = CanonCardShape, border = BorderStroke(1.dp, if (online) CanonGreen2 else CanonBorder), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = if (online) CanonGreen2 else CanonBg, shape = CircleShape) {
                        Icon(painterResource(R.drawable.yu_mode_courier), contentDescription = null, tint = if (online) Color.White else CanonMuted, modifier = Modifier.padding(9.dp).size(22.dp))
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
                    CourierZoneChip(Icons.Default.LocationCity, appText("Город", "Ҡала"), zone == "city") { changeZone("city") }
                    CourierZoneChip(Icons.Default.AltRoute, appText("Межгород", "Ҡалалар араһы"), zone == "intercity") { changeZone("intercity") }
                    CourierZoneChip(Icons.Default.Public, appText("Регион", "Төбәк"), zone == "region") { changeZone("region") }
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
                0 -> CourierAvailableTab(zone = zone, workCity = if (zone == "city") workCity else "")
                1 -> CourierCarryingTab()
                else -> CourierCabinetTab(me, onReloadMe, onEarnings)
            }
        }
    }
}

@Composable
private fun CourierZoneChip(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) CanonGreen2 else CanonSurface, tween(200), label = "zone")
    Surface(
        onClick = onClick, color = bg, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (active) CanonGreen2 else CanonBorder),
        modifier = Modifier.height(48.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = if (active) Color.White else CanonMuted, modifier = Modifier.size(18.dp))
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
        modifier = modifier.height(48.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(label, color = if (active) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Black, fontSize = 14.sp)
        }
    }
}

// ─────────────────────────── Доступные заказы ───────────────────────────
@Composable
private fun CourierAvailableTab(zone: String, workCity: String) {
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
    LaunchedEffect(zone, workCity) { reload() }

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
                // Только цена доставки — то, что достанется курьеру. Наш сервисный сбор
                // (feeKop) сюда не подставляем: это чужие деньги (аудит 2026-07-26).
                if (p.priceKop > 0) {
                    Text(kopToRub(p.priceKop), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 18.sp)
                } else {
                    Text(appText("По-соседски", "Күрше хаҡы"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
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
    var goodsTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var disputeTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var troubleTarget by remember { mutableStateOf<ParcelDto?>(null) }   // отказ / возврат (аудит 2026-07-26)
    var rateTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var ratedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    val actionErr = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    val transitMsg = appText("Статус обновлён: в пути", "Статус яңырҙы: юлда")
    val deliveredMsg = appText("Заказ вручён. Спасибо!", "Заказ тапшырылды. Рәхмәт!")
    val goodsSavedMsg = appText("Стоимость покупки сохранена", "Һатып алыу хаҡы һаҡланды")

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
            else -> {
                // Онлайн-трекинг: карта активной доставки (курьер шлёт свой GPS отправителю). Одна на вкладку.
                list.firstOrNull { it.status == "accepted" || it.status == "in_transit" }?.let { activeParcel ->
                    item(key = "ccar-track") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(appText("Ты в пути — отправитель видит тебя на карте", "Һин юлда — ебәреүсе һине картала күрә"),
                                color = CanonMuted, fontSize = 12.sp)
                            ParcelTrackMap(activeParcel, asCourier = true)
                        }
                    }
                }
                items(list.size, key = { "ccar-" + list[it].id }) { i ->
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
                        onSetGoods = { goodsTarget = list[i] },
                        onDispute = { disputeTarget = list[i] },
                        onTrouble = { troubleTarget = list[i] },
                        rated = ratedIds.contains(list[i].id),
                        onRate = { rateTarget = list[i] },
                    )
                }
                }
            }
        }
    }

    // C3: курьер оценивает отправителя после вручения.
    rateTarget?.let { target ->
        ParcelRateDialog(
            parcel = target,
            raterIsCourier = true,
            onDismiss = { rateTarget = null },
            onRated = { ratedIds = ratedIds + target.id; rateTarget = null },
        )
    }

    // C2: курьер вводит фактическую стоимость купленного товара (buy_bring).
    goodsTarget?.let { target ->
        var rub by remember(target.id) { mutableStateOf(((target.settlement?.goodsActualKop ?: 0) / 100).takeIf { it > 0 }?.toString() ?: "") }
        var goodsError by remember(target.id) { mutableStateOf<String?>(null) }
        var saving by remember(target.id) { mutableStateOf(false) }
        val rubInt = rub.filter(Char::isDigit).toIntOrNull()
        val goodsOk = rubInt != null && rubInt in 1..5000
        AlertDialog(
            onDismissRequest = { if (!saving) goodsTarget = null },
            containerColor = CanonSurface,
            title = { Text(appText("Стоимость покупки", "Һатып алыу хаҡы"), color = CanonText, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(appText("Сколько ты потратил на товар? Получатель вернёт эту сумму плюс доставку.", "Тауарға күпме тотондоң? Алыусы был сумманы һәм илтеүҙе кире ҡайтарыр."), color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
                    OutlinedTextField(
                        value = rub,
                        onValueChange = { rub = it.filter(Char::isDigit).take(5); goodsError = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(appText("Сумма покупки, ₽", "Һатып алыу суммаһы, ₽")) },
                        placeholder = { Text("0", color = CanonMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        isError = goodsError != null,
                    )
                    Text(appText("Лимит покупки — 5000 ₽.", "Һатып алыу лимиты — 5000 ₽."), color = CanonMuted, fontSize = 12.sp)
                    if (goodsError != null) Text(goodsError ?: "", color = CanonRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !saving && goodsOk,
                    onClick = {
                        val kop = (rubInt ?: 0) * 100
                        saving = true; goodsError = null
                        scope.launch {
                            ApiClient.setGoodsCost(target.id, kop)
                                .onSuccess {
                                    Toast.makeText(ctx, goodsSavedMsg, Toast.LENGTH_SHORT).show()
                                    goodsTarget = null; reload()
                                }
                                .onFailure { goodsError = (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr }
                            saving = false
                        }
                    },
                ) { Text(appText("Сохранить", "Һаҡлау"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(enabled = !saving, onClick = { goodsTarget = null }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) } },
        )
    }

    // C2: спор по заказу (общий диалог из ParcelsScreen).
    disputeTarget?.let { target ->
        ParcelDisputeDialog(
            parcel = target,
            onDismiss = { disputeTarget = null },
            onOpened = { disputeTarget = null; reload() },
        )
    }

    // «Что-то пошло не так»: отказ от заказа и возврат посылки отправителю.
    troubleTarget?.let { target ->
        CourierTroubleDialog(
            parcel = target,
            onDismiss = { troubleTarget = null },
            onDone = { troubleTarget = null; reload() },
        )
    }

    deliverTarget?.let { target ->
        var code by remember(target.id) { mutableStateOf("") }
        var codeError by remember(target.id) { mutableStateOf<String?>(null) }
        var submitting by remember(target.id) { mutableStateOf(false) }
        // Фото «отдал целой» — граница ответственности. Поле сервер принимал давно, клиент его
        // не слал, и в споре о повреждении у курьера не было НИЧЕГО, кроме своего слова.
        var deliveryPhoto by remember(target.id) { mutableStateOf<String?>(null) }
        var photoBusy by remember(target.id) { mutableStateOf(false) }
        val photoFail = appText("Фото не загрузилось, попробуй ещё раз", "Фото йөкләнмәне, тағы ҡабатла")
        val pickDeliveryPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            photoBusy = true
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { decodeToJpeg(ctx, uri) }
                if (bytes == null) { photoBusy = false; codeError = photoFail; return@launch }
                ApiClient.uploadEvidence(bytes)
                    .onSuccess { url -> if (url.isNotBlank()) deliveryPhoto = url }
                    .onFailure { codeError = photoFail }
                photoBusy = false
            }
        }
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
                    // Фото «отдал целой» — необязательно, но это единственное, что отличает
                    // слово от доказательства, если получатель потом скажет «пришло битое».
                    AppButton(
                        text = when {
                            deliveryPhoto != null -> appText("Фото приложено ✓", "Фото тағылды ✓")
                            photoBusy -> appText("Загружаем фото…", "Фото йөкләнә…")
                            else -> appText("Сфотографировать при вручении", "Тапшырғанда фотоға төшөрөү")
                        },
                        onClick = { if (!photoBusy && deliveryPhoto == null) pickDeliveryPhoto.launch("image/*") },
                        style = AppButtonStyle.Secondary,
                        loading = photoBusy,
                        enabled = !photoBusy && deliveryPhoto == null,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !submitting && code.isNotBlank(),
                    onClick = {
                        submitting = true; codeError = null
                        scope.launch {
                            ApiClient.setParcelStatus(target.id, "delivered", code.trim(), deliveryPhoto)
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

/**
 * Онлайн-трекинг доставки на карте (курьер ↔ отправитель) — тот же движок, что у такси.
 * asCourier=true: этот телефон ШЛЁТ свой GPS (курьер) и видит себя на маршруте.
 * asCourier=false: отправитель ВИДИТ движущегося курьера (принимает позицию по WS).
 * Приватность как у такси-трека: поток живёт лишь пока посылка в работе (accepted/in_transit),
 * координаты сервер не хранит, канал закрыт для чужих.
 */
@Composable
internal fun ParcelTrackMap(parcel: ParcelDto, asCourier: Boolean, modifier: Modifier = Modifier) {
    val from = parcel.fromLat?.let { la -> parcel.fromLng?.let { lo -> com.yandex.mapkit.geometry.Point(la, lo) } }
    val to = parcel.toLat?.let { la -> parcel.toLng?.let { lo -> com.yandex.mapkit.geometry.Point(la, lo) } }
    if (from == null && to == null) return   // без координат карту не рисуем
    val active = parcel.status == "accepted" || parcel.status == "in_transit"

    var courierPoint by remember(parcel.id) { mutableStateOf<com.yandex.mapkit.geometry.Point?>(null) }
    var courierBearing by remember(parcel.id) { mutableStateOf<Double?>(null) }

    val sock = remember(parcel.id) {
        com.yuldash.app.data.InstantLocationSocket.forParcel(parcel.id, onPeer = { peer ->
            if (peer.role == "courier") {
                courierPoint = com.yandex.mapkit.geometry.Point(peer.lat, peer.lng)
                courierBearing = peer.bearing
            }
        })
    }
    DisposableEffect(parcel.id, active) {
        if (!active) return@DisposableEffect onDispose { }
        sock.connect()
        onDispose { sock.close() }
    }

    // Курьер: раз в ~5с шлём свою позицию отправителю (координаты не логируем) + показываем себя.
    val myPoint by rememberMyPoint(active = asCourier && active)
    if (asCourier) {
        var lastSent by remember { mutableStateOf(0L) }
        var prev by remember { mutableStateOf<com.yandex.mapkit.geometry.Point?>(null) }
        LaunchedEffect(myPoint) {
            val p = myPoint ?: return@LaunchedEffect
            val now = System.currentTimeMillis()
            if (now - lastSent < 5_000) return@LaunchedEffect
            lastSent = now
            val bearing = prev?.let { q ->
                val dLat = p.latitude - q.latitude; val dLng = p.longitude - q.longitude
                if (kotlin.math.abs(dLat) + kotlin.math.abs(dLng) < 0.00005) null
                else (Math.toDegrees(kotlin.math.atan2(dLng * kotlin.math.cos(Math.toRadians(p.latitude)), dLat)) + 360) % 360
            }
            prev = p
            sock.sendLoc(p.latitude, p.longitude, bearing)
            courierPoint = p; courierBearing = bearing
        }
    }

    InstantRouteMap(
        from = from, to = to,
        car = courierPoint, carBearing = courierBearing,
        modifier = modifier.fillMaxWidth().height(190.dp).clip(CanonItemShape),
    )
}

@Composable
private fun CourierCarryingCard(
    p: ParcelDto,
    busy: Boolean,
    onTransit: () -> Unit,
    onDeliver: () -> Unit,
    onSetGoods: () -> Unit,
    onDispute: () -> Unit,
    onTrouble: () -> Unit,
    rated: Boolean,
    onRate: () -> Unit,
) {
    val delivered = p.status == "delivered"
    val buyBring = p.deliveryType == "buy_bring"
    val needGoods = buyBring && (p.settlement?.goodsActualKop ?: 0) == 0
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
            // C2: «купи и привези» — блок расчёта (за товар · доставка · получатель платит)
            if (buyBring) {
                val st = p.settlement
                if (st != null) ParcelSettlementBlock(st, forCourier = true)
                else if (p.codAmountKop > 0) Text(appText("Выкуп товара: ", "Тауар выкупы: ") + kopToRub(p.codAmountKop), color = CanonWarn, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            // Комиссия до вручения — оценка (финал считается после вручения).
            val feeEst = if (!delivered) appText(" ≈ ориентировочно", " ≈ самаға") else ""
            val myIncome = p.priceKop - p.commissionKop
            if (myIncome > 0) {
                Text(appText("Твой доход: ", "Һинең килем: ") + kopToRub(myIncome) + appText(" (наш сбор ${kopToRub(p.commissionKop)}$feeEst)", " (беҙҙең сбор ${kopToRub(p.commissionKop)}$feeEst)"), color = CanonMuted, fontSize = 13.sp)
            } else {
                // «По пути»: комиссии платформы нет, вся оплата — напрямую от отправителя.
                Text(
                    if (p.priceKop > 0) appText("Тебе заплатят: ", "Һиңә түләйәсәктәр: ") + kopToRub(p.priceKop)
                    else appText("По-соседски, без оплаты", "Күрше хаҡы, түләүһеҙ"),
                    color = CanonMuted, fontSize = 13.sp,
                )
            }
            if (!delivered) {
                if (needGoods) {
                    AppButton(
                        text = appText("Указать стоимость покупки", "Һатып алыу хаҡын күрһәтеү"),
                        onClick = onSetGoods,
                        style = AppButtonStyle.Accent,
                        icon = Icons.Default.Payments,
                        enabled = !busy,
                    )
                    Text(
                        appText("Сначала укажи стоимость покупки — потом сможешь вручить заказ.", "Тәүҙә һатып алыу хаҡын күрһәт — шунан заказды тапшыра алырһың."),
                        color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                }
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
                        enabled = !busy && !needGoods,
                    )
                }
            }
            // «Что-то пошло не так»: пока посылка у курьера — отказаться или везти обратно.
            // Без этого выхода заказ навсегда зависал «в пути», а коробка оставалась дома у курьера.
            if (!delivered && p.status != "canceled" && p.status != "returned") {
                CourierTroubleButton(returning = p.status == "returning", onClick = onTrouble)
            }
            // C3: оценить отправителя — после вручения
            if (delivered) {
                if (rated) ParcelRatedRow() else ParcelRateButton(onClick = onRate)
            }
            // C2: спор доступен, когда заказ в пути или доставлен
            if (p.status == "in_transit" || delivered) {
                ParcelDisputeButton(onClick = onDispute)
            }
        }
    }
}

// ─────────────────────────── Кабинет курьера ───────────────────────────
@Composable
private fun CourierCabinetTab(me: CourierMeDto, onReloadMe: () -> Unit, onEarnings: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var paying by remember { mutableStateOf(false) }
    var payResult by remember { mutableStateOf<PayCommissionDto?>(null) }
    val payErr = appText("Не получилось оформить оплату. Проверь сеть.", "Түләүҙе рәсмиләштереп булманы. Селтәрҙе тикшер.")
    val commissionPaidMsg = appText("Комиссия оплачена. Спасибо!", "Комиссия түләнде. Рәхмәт!")

    val st = me.statement
    val owed = st.commissionOwedKop

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        // «Мой заработок» — первым делом: курьер должен видеть, СКОЛЬКО он получил, а не только
        // сколько должен. До этого раунда экрана не было вовсе (аудит 2026-07-26).
        item {
            AppButton(
                text = appText("Мой заработок", "Минең табыш"),
                onClick = onEarnings,
                style = AppButtonStyle.Secondary,
                icon = Icons.Default.Payments,
            )
        }
        // Пауза по качеству (если задана) — тёплая плашка, не ругательно.
        me.pausedUntil?.takeIf { it.isNotBlank() }?.let { until ->
            item {
                Surface(color = CanonWarnBg, shape = CanonCardShape, border = BorderStroke(1.dp, CanonWarn)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PauseCircle, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(14.dp))
                        val until10 = until.take(10)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(appText("Пауза по качеству", "Сифат буйынса пауза"), color = CanonWarn, fontWeight = FontWeight.Black, fontSize = 15.sp)
                            Text(
                                appText("Пауза до $until10. Подтяни рейтинг — и снова в строю. Мы рядом, поможем.", "$until10 тиклем пауза. Рейтингты күтәр — һәм ҡабат сафта. Беҙ янда, ярҙам итербеҙ."),
                                color = CanonWarn, fontSize = 13.sp, lineHeight = 18.sp,
                            )
                        }
                    }
                }
            }
        }
        // Рейтинг курьера.
        item {
            val avg = me.rating.avg
            Surface(color = CanonSurface, shape = CanonCardShape, border = BorderStroke(1.dp, CanonBorder)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.padding(12.dp).size(26.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(appText("Твой рейтинг", "Һинең рейтинг"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        if (avg != null && me.rating.count > 0) {
                            Text(String.format("%.1f", avg) + " ★", color = CanonText, fontWeight = FontWeight.Black, fontSize = 28.sp)
                            Text(appText("оценок: ${me.rating.count}", "баһа: ${me.rating.count}"), color = CanonMuted, fontSize = 12.sp)
                        } else {
                            Text(appText("Пока нет оценок", "Әлегә оценка юҡ"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(appText("Первые доставки — и рейтинг появится.", "Тәүге илтеүҙәр — һәм рейтинг күренер."), color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                }
            }
        }
        // Доставлено заказов.
        item {
            Surface(color = CanonMint, shape = CanonCardShape, border = BorderStroke(1.dp, CanonGreen2)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = CanonSurface, shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(26.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(appText("Доставлено заказов", "Тапшырылған заказдар"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("${st.deliveredCount}", color = CanonText, fontWeight = FontWeight.Black, fontSize = 30.sp)
                    }
                }
            }
        }
        // Текущая ступень комиссии — курьер видит, сколько платит и почему.
        if (st.feeTier.isNotBlank()) {
            item {
                val pct = courierFeePercentText(st.currentFeePercent)
                val tierLine = when (st.feeTier) {
                    "promo" -> appText("🎁 Промо для первых: 0% — пользуйся!", "🎁 Тәүгеләр өсөн промо: 0% — файҙалан!")
                    "tier1" -> appText("Новичок: 3% — самая низкая ставка", "Яңы башлаусы: 3% — иң түбән ставка")
                    "tier2" -> appText("Опытный курьер: 5%", "Тәжрибәле курьер: 5%")
                    "tier3" -> appText("8% — обычная ставка", "8% — ғәҙәти ставка")
                    else -> appText("Комиссия по твоей ступени", "Баҫҡысың буйынса комиссия")
                }
                val promo = st.feeTier == "promo"
                Surface(
                    color = if (promo) CanonMint else CanonSurface,
                    shape = CanonCardShape,
                    border = BorderStroke(1.dp, if (promo) CanonGreen2 else CanonBorder),
                ) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(color = if (promo) CanonSurface else CanonMint, shape = RoundedCornerShape(14.dp)) {
                                Icon(Icons.Default.Percent, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(10.dp).size(22.dp))
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(appText("Сейчас ты платишь $pct% комиссии", "Хәҙер һин $pct% комиссия түләйһең"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                                Text(tierLine, color = if (promo) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                        if (st.commissionMinKop > 0) {
                            Text(
                                appText(
                                    "Комиссия минимум ${kopToRub(st.commissionMinKop)} за доставку. Всё прозрачно — видно, сколько и за что.",
                                    "Комиссия иң кәме ${kopToRub(st.commissionMinKop)} бер илтеү өсөн. Барыһы ла асыҡ — күпме һәм ни өсөн икәне күренә.",
                                ),
                                color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                            )
                        }
                    }
                }
            }
        }
        // Комиссия: заработали · к оплате (крупно) · оплачено.
        item {
            Surface(color = CanonSurface, shape = CanonCardShape, border = BorderStroke(1.dp, if (owed > 0) CanonGreen2 else CanonBorder)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Payments, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(22.dp))
                        Text(appText("Наша комиссия за доставки", "Илтеүҙәр өсөн беҙҙең комиссия"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
                    }
                    Text(
                        appText("Это сбор Юлдаша (8%) за то, что мы свели тебя с заказами. Твой доход остаётся у тебя — сюда попадает только наша часть.", "Был — заказдар менән таныштырғаныбыҙ өсөн Юлдаш сборы (8%). Килемең үҙеңдә ҡала — бында тик беҙҙең өлөш."),
                        color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                    StatementRow(appText("Всего заработали мы", "Барлығы беҙ эшләнек"), kopToRub(st.commissionEarnedKop), CanonMuted)
                    StatementRow(appText("Уже оплачено", "Түләнгән"), kopToRub(st.commissionPaidKop), CanonGreen2)
                    // К оплате сейчас — крупно.
                    Surface(color = if (owed > 0) CanonMint else CanonBg, shape = CanonItemShape) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(appText("К оплате сейчас", "Хәҙер түләргә"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text(kopToRub(owed), color = if (owed > 0) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Black, fontSize = 24.sp)
                        }
                    }
                    if (owed > 0) {
                        AppButton(
                            text = appText("Оплатить комиссию", "Комиссияны түләү"),
                            onClick = {
                                if (paying) return@AppButton
                                paying = true
                                scope.launch {
                                    ApiClient.payCommission()
                                        .onSuccess onPaid@{ res ->
                                            when {
                                                // ЮKassa (по флажку): в браузер оплаты, статус — поллингом.
                                                res.method == "yookassa" && res.status == "pending" && !res.confirmationUrl.isNullOrBlank() -> {
                                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(res.confirmationUrl))) }
                                                    paying = false
                                                    repeat(6) {
                                                        kotlinx.coroutines.delay(4000)
                                                        if (ApiClient.getPaymentStatus(res.paymentId).getOrNull()?.status == "succeeded") {
                                                            Toast.makeText(ctx, commissionPaidMsg, Toast.LENGTH_LONG).show(); onReloadMe(); return@onPaid
                                                        }
                                                    }
                                                    onReloadMe()
                                                    return@onPaid
                                                }
                                                res.status == "succeeded" -> { Toast.makeText(ctx, commissionPaidMsg, Toast.LENGTH_LONG).show(); onReloadMe() }
                                                else -> payResult = res   // СБП «на доверии» → показать реквизиты
                                            }
                                        }
                                        .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: payErr, Toast.LENGTH_LONG).show() }
                                    paying = false
                                }
                            },
                            style = AppButtonStyle.Accent,
                            icon = Icons.Default.Payments,
                            loading = paying,
                        )
                        Text(
                            appText("Переведи сумму по СБП на реквизиты Юлдаша — админ подтвердит оплату вручную.", "Сумманы СБП аша Юлдаш реквизиттарына күсер — админ түләүҙе ҡулдан раҫлар."),
                            color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                        )
                    } else {
                        Text(appText("Долгов нет — спасибо, что возишь по-честному.", "Бурыс юҡ — намыҫлы илткәнең өсөн рәхмәт."), color = CanonGreen2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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

    // Реквизиты СБП после оформления оплаты (переиспользуем общий лист донат/буста).
    payResult?.let { pr ->
        SbpTransferSheet(
            amountRub = pr.amountKop / 100,
            onPaid = { payResult = null; onReloadMe() },
            onDismiss = { payResult = null },
            payeePhone = pr.payeePhone,
            payeeBank = pr.payeeBank,
            payeeName = pr.payeeName,
        )
    }
}

@Composable
private fun StatementRow(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CanonMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
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

/** Ставка комиссии в проценты без лишних нулей: 0.0→«0», 3.0→«3», 7.5→«7,5». */
private fun courierFeePercentText(pct: Double): String =
    if (pct % 1.0 == 0.0) pct.toInt().toString() else String.format("%.1f", pct).replace('.', ',')

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
