package com.yuldash.app

// ═══════════════════ M1: Кабинет бизнеса-партнёра «Мой бизнес» ═══════════════════
// GET /partner/me определяет ветку:
//   partner == null   → форма регистрации бизнеса
//   status = pending  → «Бизнес на проверке»
//   status = rejected → причина отказа + правка и повторная отправка
//   status = active    → подписка · выписка · мои купоны (создать/править/статус) · погасить код клиента
// Всё двуязычно, все состояния, цвета Canon*, оплата подписки «на доверии» (СБП).

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.PartnerCouponDto
import com.yuldash.app.data.PartnerDto
import com.yuldash.app.data.PartnerMeDto
import com.yuldash.app.data.PartnerPlanDto
import com.yuldash.app.data.PartnerSubscribeDto
import kotlinx.coroutines.launch

// Категории заведения для выбора при регистрации.
private val partnerCategories = listOf("cafe", "restaurant", "food", "beauty", "auto", "pharmacy", "fuel", "shop")

@Composable
internal fun PartnerCabinetScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<PartnerMeDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")

    fun reload() {
        loading = true; error = null
        scope.launch {
            ApiClient.getPartnerMe()
                .onSuccess { me = it }
                .onFailure { error = (it as? com.yuldash.app.data.ApiException)?.message ?: loadErr }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Мой бизнес", "Минең бизнес"), onBack) }) { padding ->
        Box(Modifier.padding(padding).fillMaxWidth()) {
            when {
                loading && me == null -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SkeletonCard(lines = 3); SkeletonCard(lines = 2)
                }
                error != null && me == null -> Column(Modifier.fillMaxWidth().padding(16.dp)) { ListedError(error ?: "") { reload() } }
                else -> {
                    val partner = me?.partner
                    when {
                        partner == null -> PartnerForm(
                            initial = null,
                            onDone = { reload() },
                        )
                        partner.status == "pending" -> PartnerPendingView(partner)
                        partner.status == "rejected" -> PartnerRejectedView(partner, onResubmitted = { reload() })
                        else -> ActivePartnerCabinet(partner, me?.statement, onChanged = { reload() })
                    }
                }
            }
        }
    }
}

// ─────────────────────────── Статус: на проверке ───────────────────────────

@Composable
private fun PartnerPendingView(p: PartnerDto) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(color = CanonWarnBg, shape = CircleShape) {
            Icon(Icons.Default.HourglassTop, contentDescription = null, tint = CanonWarn, modifier = Modifier.padding(16.dp).size(36.dp))
        }
        Text(appText("Бизнес на проверке", "Бизнес тикшереүҙә"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 24.sp, textAlign = TextAlign.Center)
        Text(p.name, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center)
        Text(
            appText(
                "Мы смотрим твою заявку — обычно это недолго. Как одобрим, ты сможешь публиковать купоны и привлекать клиентов по маршрутам.",
                "Заявкаңды ҡарайбыҙ — ғәҙәттә оҙаҡ түгел. Раҫлаһаҡ, купон баҫтырып, маршруттар буйынса клиент йыя алырһың.",
            ),
            color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
        )
    }
}

// ─────────────────────────── Статус: отклонён ───────────────────────────

@Composable
private fun PartnerRejectedView(p: PartnerDto, onResubmitted: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Surface(color = CanonDangerBg, shape = CanonItemShape) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(appText("Заявка отклонена", "Заявка кире ҡағылды"), color = CanonRed, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (p.rejectReason.isNotBlank()) Text(p.rejectReason, color = CanonRed, fontSize = 14.sp, lineHeight = 20.sp)
                    Text(appText("Поправь данные и отправь снова.", "Мәғлүмәтте төҙәтеп ҡабат ебәр."), color = CanonMuted, fontSize = 14.sp)
                }
            }
        }
        item { PartnerForm(initial = p, onDone = onResubmitted, embedded = true) }
    }
}

// ─────────────────────────── Форма регистрации / правки ───────────────────────────

@Composable
private fun PartnerForm(initial: PartnerDto?, onDone: () -> Unit, embedded: Boolean = false) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "cafe") }
    var city by remember { mutableStateOf(initial?.city ?: "") }
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var sending by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val errDefault = appText("Не получилось сохранить. Повтори.", "Һаҡлап булманы. Ҡабатла.")
    val valid = name.isNotBlank() && city.isNotBlank()

    val content: @Composable () -> Unit = {
        if (!embedded) {
            Text(
                appText(
                    "Расскажи о заведении — после проверки сможешь размещать купоны для попутчиков по маршрутам.",
                    "Урын тураһында һөйлә — тикшереүҙән һуң маршруттар буйынса юлдаштар өсөн купон ҡуя алырһың.",
                ),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(appText("Название заведения", "Урын исеме")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        // Категория — чипы
        Text(appText("Категория", "Категория"), color = CanonMuted, fontSize = 14.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            partnerCategories.forEach { code ->
                CategoryChip(code, category == code) { category = code }
            }
        }
        OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text(appText("Город", "Ҡала")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text(appText("Адрес", "Адрес")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text(appText("Телефон", "Телефон")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text(appText("Описание", "Тасуирлау")) }, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        if (err != null) Text(err ?: "", color = CanonRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        AppButton(
            text = if (initial == null) appText("Отправить на проверку", "Тикшереүгә ебәреү") else appText("Сохранить и отправить снова", "Һаҡлап ҡабат ебәреү"),
            onClick = {
                if (sending) return@AppButton
                sending = true; err = null
                scope.launch {
                    val res = if (initial == null)
                        ApiClient.createPartner(name.trim(), category, city.trim(), address.trim(), phone.trim(), description.trim())
                    else
                        ApiClient.updatePartner(initial.id, name.trim(), category, city.trim(), address.trim(), phone.trim(), description.trim())
                    res.onSuccess { onDone() }
                        .onFailure { err = (it as? com.yuldash.app.data.ApiException)?.message ?: errDefault }
                    sending = false
                }
            },
            style = AppButtonStyle.Primary,
            loading = sending,
            enabled = valid,
        )
    }

    if (embedded) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    } else {
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) { item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } } }
    }
}

@Composable
private fun CategoryChip(code: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (active) CanonMint else CanonSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (active) CanonGreen2 else CanonBorder),
        modifier = Modifier.height(48.dp),
    ) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(couponCategoryLabel(code), color = if (active) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

// ─────────────────────────── Активный кабинет ───────────────────────────

@Composable
private fun ActivePartnerCabinet(partner: PartnerDto, statement: com.yuldash.app.data.StatementDto?, onChanged: () -> Unit) {
    // Внутренняя навигация активного кабинета
    var couponEditor by remember { mutableStateOf<CouponEditTarget?>(null) }   // форма купона
    var showSubscribe by remember { mutableStateOf(false) }
    var showRedeem by remember { mutableStateOf(false) }

    // 0 = дашборд, 1 = форма купона, 2 = подписка. Диалог погашения (showRedeem) — оверлеем, не переключает.
    val view = when {
        couponEditor != null -> 1
        showSubscribe -> 2
        else -> 0
    }
    AnimatedContent(
        targetState = view,
        transitionSpec = { (fadeIn(tween(CanonMotion.QUICK)) togetherWith fadeOut(tween(CanonMotion.QUICK))) },
        label = "partner-view",
    ) { v ->
        when (v) {
            1 -> CouponForm(
                partner = partner,
                initial = couponEditor?.coupon,
                onBack = { couponEditor = null },
                onSaved = { couponEditor = null; onChanged() },
            )
            2 -> SubscribeView(partner, onBack = { showSubscribe = false }, onSubscribed = { showSubscribe = false; onChanged() })
            else -> PartnerDashboard(
                partner = partner,
                statement = statement,
                onCreateCoupon = { couponEditor = CouponEditTarget(null) },
                onEditCoupon = { couponEditor = CouponEditTarget(it) },
                onSubscribe = { showSubscribe = true },
                onRedeem = { showRedeem = true },
                onChanged = onChanged,
            )
        }
    }

    if (showRedeem) RedeemDialog(onDismiss = { showRedeem = false })
}

private data class CouponEditTarget(val coupon: PartnerCouponDto?)

@Composable
private fun PartnerDashboard(
    partner: PartnerDto,
    statement: com.yuldash.app.data.StatementDto?,
    onCreateCoupon: () -> Unit,
    onEditCoupon: (PartnerCouponDto) -> Unit,
    onSubscribe: () -> Unit,
    onRedeem: () -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var coupons by remember { mutableStateOf<List<PartnerCouponDto>>(emptyList()) }
    var loadingC by remember { mutableStateOf(true) }
    var errorC by remember { mutableStateOf<String?>(null) }
    var busyCoupon by remember { mutableStateOf(0) }
    val loadErr = appText("Не удалось загрузить купоны.", "Купондарҙы йөкләп булманы.")
    val actionErr = appText("Не получилось. Повтори.", "Булманы. Ҡабатла.")

    fun reloadCoupons() {
        loadingC = true; errorC = null
        scope.launch {
            ApiClient.getPartnerCoupons()
                .onSuccess { coupons = it }
                .onFailure { errorC = (it as? com.yuldash.app.data.ApiException)?.message ?: loadErr }
            loadingC = false
        }
    }
    LaunchedEffect(Unit) { reloadCoupons() }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        // Шапка бизнеса
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                    Icon(couponCategoryIcon(partner.category), contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(partner.name, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text(couponCategoryLabel(partner.category) + (if (partner.city.isNotBlank()) "  ·  ${partner.city}" else ""), color = CanonMuted, fontSize = 14.sp)
                }
                Surface(color = CanonMint, shape = RoundedCornerShape(8.dp)) {
                    Text(appText("Активен", "Актив"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
        // Погасить код клиента — главное действие
        item {
            AppButton(appText("Погасить код клиента", "Клиент кодын һүндереү"), onRedeem, style = AppButtonStyle.Accent, icon = Icons.Default.Redeem)
        }
        // Подписка
        item { SubscriptionCard(partner, onSubscribe) }
        // Выписка
        item { StatementCard(statement) }
        // Заголовок «Мои купоны» + кнопка создать
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(appText("Мои купоны", "Минең купондар"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.weight(1f))
                Surface(onClick = onCreateCoupon, modifier = Modifier.minimumInteractiveComponentSize(), color = CanonGreen2, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(appText("Создать", "Булдырыу"), color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
        when {
            loadingC && coupons.isEmpty() -> item { SkeletonCard(lines = 2) }
            errorC != null && coupons.isEmpty() -> item { ListedError(errorC ?: "") { reloadCoupons() } }
            coupons.isEmpty() -> item {
                ListedEmpty(
                    appText("Купонов пока нет", "Купондар әлегә юҡ"),
                    appText("Создай первый купон — попутчики увидят его на витрине «Скидки по пути».", "Беренсе купонды булдыр — юлдаштар уны «Юл буйынса ташламалар» витринаһында күрер."),
                )
            }
            else -> items(coupons.size, key = { "pc-" + coupons[it].id }) { i ->
                val c = coupons[i]
                PartnerCouponRow(
                    c = c,
                    busy = busyCoupon == c.id,
                    onEdit = { onEditCoupon(c) },
                    onToggleStatus = { newStatus ->
                        if (busyCoupon != 0) return@PartnerCouponRow
                        busyCoupon = c.id
                        scope.launch {
                            ApiClient.setPartnerCouponStatus(c.id, newStatus)
                                .onSuccess { reloadCoupons() }
                                .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr, Toast.LENGTH_SHORT).show() }
                            busyCoupon = 0
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SubscriptionCard(partner: PartnerDto, onSubscribe: () -> Unit) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(appText("Подписка", "Яҙылыу"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (partner.subscriptionActive) {
                val until = shortDate(partner.subscriptionUntil)
                Text(
                    appText("Тариф: ${partner.subscriptionPlan}", "Тариф: ${partner.subscriptionPlan}"),
                    color = CanonText, fontSize = 14.sp,
                )
                if (until != null) Text(appText("Действует до $until", "$until тиклем ғәмәлдә"), color = CanonMuted, fontSize = 14.sp)
                if (partner.hasPremium) {
                    Surface(color = CanonGold, shape = RoundedCornerShape(8.dp)) {
                        Text(appText("Премиум-размещение", "Премиум урынлаштырыу"), color = CanonGoldInk, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
                OutlinedButton(onClick = onSubscribe, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text(appText("Сменить тариф", "Тарифты алмаштырыу"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                }
            } else {
                Text(appText("Подключи тариф, чтобы купоны появились на витрине.", "Купондар витринала күренһен өсөн тариф ҡуш."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
                Button(onClick = onSubscribe, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)) {
                    Text(appText("Выбрать тариф", "Тариф һайлау"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun StatementCard(statement: com.yuldash.app.data.StatementDto?) {
    if (statement == null) return
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(appText("Выписка", "Иҫәп-хисап"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                appText("Погашено купонов: ${statement.redeemedTotal}", "Һүндерелгән купондар: ${statement.redeemedTotal}"),
                color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                appText("К оплате: ${kopToRub(statement.amountKop)}", "Түләргә: ${kopToRub(statement.amountKop)}"),
                color = CanonGreen2, fontSize = 19.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                appText(
                    "Это комиссия Юлдаша за приведённых клиентов (${kopToRub(statement.feePerRedemptionKop)} за погашенный купон).",
                    "Был — килтерелгән клиенттар өсөн Юлдаш комиссияһы (һүндерелгән купон өсөн ${kopToRub(statement.feePerRedemptionKop)}).",
                ),
                color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun PartnerCouponRow(c: PartnerCouponDto, busy: Boolean, onEdit: () -> Unit, onToggleStatus: (String) -> Unit) {
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(c.title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(c.discountText, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                if (c.premium) {
                    Surface(color = CanonGold, shape = RoundedCornerShape(8.dp)) {
                        Text(appText("Премиум", "Премиум"), color = CanonGoldInk, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                CouponAdminStatusChip(c.status)
            }
            Text(
                appText("Активаций: ${c.activations}  ·  погашено: ${c.redeemedCount}", "Активлаштырыу: ${c.activations}  ·  һүндерелгән: ${c.redeemedCount}"),
                color = CanonMuted, fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit, enabled = !busy, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(14.dp)) {
                    Text(appText("Править", "Төҙәтеү"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                // Активировать / поставить на паузу
                if (c.status == "active") {
                    OutlinedButton(onClick = { onToggleStatus("paused") }, enabled = !busy, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(14.dp)) {
                        Text(appText("Пауза", "Пауза"), color = CanonWarn, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                } else if (c.status == "draft" || c.status == "paused") {
                    Button(onClick = { onToggleStatus("active") }, enabled = !busy, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)) {
                        Text(appText("Включить", "Ҡабыҙыу"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CouponAdminStatusChip(status: String) {
    val (bg, fg, ru, ba) = when (status) {
        "active" -> CStatus(CanonMint, CanonGreen2, "Активен", "Актив")
        "paused" -> CStatus(CanonWarnBg, CanonWarn, "Пауза", "Пауза")
        "archived" -> CStatus(CanonWarnBg, CanonMuted, "Архив", "Архив")
        else -> CStatus(CanonWarnBg, CanonWarn, "Черновик", "Ҡаралама")
    }
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(appText(ru, ba), color = fg, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

private data class CStatus(val bg: androidx.compose.ui.graphics.Color, val fg: androidx.compose.ui.graphics.Color, val ru: String, val ba: String)

// ─────────────────────────── Форма купона (создать/править) ───────────────────────────

@Composable
private fun CouponForm(partner: PartnerDto, initial: PartnerCouponDto?, onBack: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var discountText by remember { mutableStateOf(initial?.discountText ?: "") }
    var city by remember { mutableStateOf(initial?.city ?: partner.city) }
    var routeHint by remember { mutableStateOf(initial?.routeHint?.joinToString(", ") ?: "") }
    var limitTotal by remember { mutableStateOf((initial?.limitTotal ?: 100).toString()) }
    var limitPerUser by remember { mutableStateOf((initial?.limitPerUser ?: 1).toString()) }
    var validUntil by remember { mutableStateOf(initial?.validUntil?.take(10) ?: "") }
    var premium by remember { mutableStateOf(initial?.premium ?: false) }
    var sending by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val errDefault = appText("Не получилось сохранить. Повтори.", "Һаҡлап булманы. Ҡабатла.")
    val valid = title.isNotBlank() && discountText.isNotBlank() && city.isNotBlank()

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(if (initial == null) appText("Новый купон", "Яңы купон") else appText("Правка купона", "Купонды төҙәтеү"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item { OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(appText("Заголовок", "Баш")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) }
            item { OutlinedTextField(value = discountText, onValueChange = { discountText = it }, label = { Text(appText("Скидка (например: -20%)", "Ташлама (мәҫәлән: -20%)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) }
            item { OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text(appText("Описание", "Тасуирлау")) }, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) }
            item { OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text(appText("Город", "Ҡала")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) }
            item { OutlinedTextField(value = routeHint, onValueChange = { routeHint = it }, label = { Text(appText("Маршруты через запятую", "Маршруттар өтөр аша")) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = limitTotal, onValueChange = { limitTotal = it.filter { ch -> ch.isDigit() } }, label = { Text(appText("Всего", "Барлығы")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = limitPerUser, onValueChange = { limitPerUser = it.filter { ch -> ch.isDigit() } }, label = { Text(appText("На человека", "Бер кешегә")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp))
                }
            }
            item { OutlinedTextField(value = validUntil, onValueChange = { validUntil = it }, label = { Text(appText("Действует до (ГГГГ-ММ-ДД)", "Тиклем (ГГГГ-ММ-КК)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) }
            // Премиум — только если у бизнеса есть премиум-размещение
            if (partner.hasPremium) {
                item {
                    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(appText("Премиум-показ", "Премиум-күрһәтеү"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(appText("Выше в витрине, заметная метка", "Витринала юғарыраҡ, күренекле билдә"), color = CanonMuted, fontSize = 12.sp)
                            }
                            Switch(checked = premium, onCheckedChange = { premium = it })
                        }
                    }
                }
            }
            if (err != null) item { Text(err ?: "", color = CanonRed, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            item {
                AppButton(
                    text = if (initial == null) appText("Создать купон", "Купон булдырыу") else appText("Сохранить", "Һаҡлау"),
                    onClick = {
                        if (sending) return@AppButton
                        sending = true; err = null
                        val hints = routeHint.split(",").map { it.trim() }.filter { it.isNotBlank() }
                        val total = limitTotal.toIntOrNull() ?: 0
                        val perUser = limitPerUser.toIntOrNull() ?: 1
                        val until = validUntil.trim().takeIf { it.isNotBlank() }
                        scope.launch {
                            val res = if (initial == null)
                                ApiClient.createPartnerCoupon(title.trim(), description.trim(), discountText.trim(), city.trim(), hints, total, perUser, partner.hasPremium && premium, validUntil = until)
                            else
                                ApiClient.updatePartnerCoupon(initial.id, title.trim(), description.trim(), discountText.trim(), city.trim(), hints, total, perUser, partner.hasPremium && premium, validUntil = until)
                            res.onSuccess { onSaved() }
                                .onFailure { err = (it as? com.yuldash.app.data.ApiException)?.message ?: errDefault }
                            sending = false
                        }
                    },
                    style = AppButtonStyle.Primary,
                    loading = sending,
                    enabled = valid,
                )
            }
        }
    }
}

// ─────────────────────────── Подписка (выбор тарифа + оплата «на доверии») ───────────────────────────

@Composable
private fun SubscribeView(partner: PartnerDto, onBack: () -> Unit, onSubscribed: () -> Unit) {
    val scope = rememberCoroutineScope()
    var plans by remember { mutableStateOf<List<PartnerPlanDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var subscribing by remember { mutableStateOf("") }        // code тарифа в процессе
    var payInfo by remember { mutableStateOf<PartnerSubscribeDto?>(null) }
    var actErr by remember { mutableStateOf<String?>(null) }
    val loadErr = appText("Не удалось загрузить тарифы.", "Тарифтарҙы йөкләп булманы.")
    val actErrDefault = appText("Не получилось оформить. Повтори.", "Рәсмиләштереп булманы. Ҡабатла.")

    fun reload() {
        loading = true; error = null
        scope.launch {
            ApiClient.getPartnerPlans().onSuccess { plans = it }.onFailure { error = loadErr }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Тарифы", "Тарифтар"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            // Инструкция оплаты «на доверии» после оформления
            payInfo?.let { info ->
                item {
                    Surface(color = CanonMint, shape = CanonCardShape, border = BorderStroke(1.dp, CanonGreen2)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(appText("Переведи ${kopToRub(info.amountKop)} по СБП", "СБП аша ${kopToRub(info.amountKop)} күсер"), color = CanonGreen, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            Text(
                                appText(
                                    "Оплата «на доверии»: переведи сумму по реквизитам из поддержки. Как подтвердим оплату — подписка включится, и купоны появятся на витрине.",
                                    "«Ышаныс менән» түләү: ярҙам биргән реквизиттар буйынса күсер. Түләүҙе раҫлағас — яҙылыу ҡабыҙыла, купондар витринала күренә.",
                                ),
                                color = CanonText, fontSize = 14.sp, lineHeight = 20.sp,
                            )
                            AppButton(appText("Понятно", "Аңлашыла"), onSubscribed, style = AppButtonStyle.Primary)
                        }
                    }
                }
            }
            if (payInfo == null) {
                item {
                    Text(appText("Выбери тариф — оплата по СБП «на доверии», админ подтвердит.", "Тариф һайла — СБП аша «ышаныс менән» түләү, админ раҫлар."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
                }
                if (actErr != null) item { Text(actErr ?: "", color = CanonRed, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                when {
                    loading && plans.isEmpty() -> item { SkeletonCard(lines = 2) }
                    error != null && plans.isEmpty() -> item { ListedError(error ?: "") { reload() } }
                    plans.isEmpty() -> item { ListedEmpty(appText("Тарифов нет", "Тарифтар юҡ"), appText("Загляни позже.", "Һуңыраҡ кер.")) }
                    else -> items(plans.size, key = { "plan-" + plans[it].code }) { i ->
                        val plan = plans[i]
                        PlanCard(
                            plan = plan,
                            current = partner.subscriptionActive && partner.subscriptionPlan == plan.code,
                            busy = subscribing == plan.code,
                            onSelect = {
                                if (subscribing.isNotBlank()) return@PlanCard
                                subscribing = plan.code; actErr = null
                                scope.launch {
                                    ApiClient.subscribePartner(plan.code)
                                        .onSuccess { payInfo = it }
                                        .onFailure { actErr = (it as? com.yuldash.app.data.ApiException)?.message ?: actErrDefault }
                                    subscribing = ""
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanCard(plan: PartnerPlanDto, current: Boolean, busy: Boolean, onSelect: () -> Unit) {
    Surface(color = CanonSurface, shape = CanonCardShape, border = BorderStroke(if (plan.premium) 2.dp else 1.dp, if (plan.premium) CanonGold else CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(appText(plan.title, plan.titleBa.ifBlank { plan.title }), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.weight(1f))
                if (plan.premium) {
                    Surface(color = CanonGold, shape = RoundedCornerShape(8.dp)) {
                        Text(appText("Премиум", "Премиум"), color = CanonGoldInk, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
            Text("${kopToRub(plan.amountKop)} / ${plan.periodDays} " + appText("дн.", "көн"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            if (current) {
                Surface(color = CanonMint, shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(appText("Текущий тариф", "Хәҙерге тариф"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
            Button(
                onClick = onSelect, enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (plan.premium) CanonGold else CanonGreen2, contentColor = if (plan.premium) CanonGoldInk else androidx.compose.ui.graphics.Color.White),
            ) {
                Text(if (current) appText("Продлить", "Оҙайтыу") else appText("Оформить", "Рәсмиләштереү"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

// ─────────────────────────── Погашение кода клиента ───────────────────────────

@Composable
private fun RedeemDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<com.yuldash.app.data.RedeemResultDto?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val errDefault = appText("Не получилось. Проверь код.", "Булманы. Кодты тикшер.")

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CanonSurface,
        title = { Text(if (result == null) appText("Погасить код клиента", "Клиент кодын һүндереү") else appText("Скидка подтверждена", "Ташлама раҫланды"), color = CanonText, fontWeight = FontWeight.Bold) },
        text = {
            if (result != null) {
                val r = result!!
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(r.discountText, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    }
                    Text(r.couponTitle, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(appText("Клиент: ${r.customerName}", "Клиент: ${r.customerName}"), color = CanonMuted, fontSize = 14.sp)
                    Text(appText("Дай скидку клиенту.", "Клиентҡа ташлама бир."), color = CanonMuted, fontSize = 14.sp)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(appText("Введи код, который показал клиент.", "Клиент күрһәткән кодты индер."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
                    OutlinedTextField(
                        value = code, onValueChange = { code = it.uppercase() },
                        label = { Text(appText("Код купона", "Купон коды")) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    )
                    if (err != null) Text(err ?: "", color = CanonRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            if (result != null) {
                TextButton(onClick = onDismiss) { Text(appText("Готово", "Әҙер"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            } else {
                TextButton(
                    onClick = {
                        if (busy || code.isBlank()) return@TextButton
                        busy = true; err = null
                        scope.launch {
                            ApiClient.redeemCoupon(code.trim())
                                .onSuccess { result = it }
                                .onFailure { err = (it as? com.yuldash.app.data.ApiException)?.message ?: errDefault }
                            busy = false
                        }
                    },
                    enabled = !busy && code.isNotBlank(),
                ) { Text(appText("Погасить", "Һүндереү"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = {
            if (result == null) TextButton(onClick = onDismiss) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) }
        },
    )
}
