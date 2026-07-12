package com.yuldash.app

// ═══════════════════ M1: Купонный маркетплейс «Скидки по пути» (пользователь) ═══════════════════
// Витрина скидок от местных заведений вдоль маршрута. Две вкладки: «Скидки рядом» (публичная витрина
// с фильтром по городу) и «Мои купоны» (активированные). Тап по купону → детально → «Активировать» →
// крупный КОД для показа в заведении. Скидку даёт заведение (честный дисклеймер). Всё двуязычно,
// все состояния (загрузка/пусто/ошибка), цвета только Canon*, анимации плавные.

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ActivatedCouponDto
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.CouponDto
import com.yuldash.app.data.MyCouponDto
import kotlinx.coroutines.launch

// ─────────────────────────── Категории заведений (иконка + подпись) ───────────────────────────

/** Иконка категории заведения. Неизвестная категория → универсальная витрина. */
internal fun couponCategoryIcon(category: String): androidx.compose.ui.graphics.vector.ImageVector =
    when (category.lowercase()) {
        "cafe", "restaurant", "food", "кафе", "ресторан", "еда" -> Icons.Default.Storefront
        "beauty", "салон", "красота" -> Icons.Default.WorkspacePremium
        else -> Icons.Default.LocalOffer
    }

/** Двуязычная подпись категории. Незнакомую отдаём как есть (сервер уже мог прислать читаемо). */
@Composable
internal fun couponCategoryLabel(category: String): String = when (category.lowercase()) {
    "cafe" -> appText("Кафе", "Кафе")
    "restaurant" -> appText("Ресторан", "Ресторан")
    "food", "grocery" -> appText("Продукты", "Аҙыҡ-түлек")
    "beauty" -> appText("Красота", "Матурлыҡ")
    "auto", "car" -> appText("Авто", "Авто")
    "pharmacy" -> appText("Аптека", "Дарыухана")
    "fuel", "gas" -> appText("Заправка", "Заправка")
    "shop", "store" -> appText("Магазин", "Кибет")
    "" -> appText("Заведение", "Урын")
    else -> category
}

/** Копейки → «X ₽». */
internal fun kopToRub(kop: Int): String = "${kop / 100} ₽"

/** Обрезать ISO-дату до "дд.мм.гггг" (или как есть, если формат другой). */
internal fun shortDate(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val date = iso.take(10)
    val parts = date.split("-")
    return if (parts.size == 3) "${parts[2]}.${parts[1]}.${parts[0]}" else date
}

// ─────────────────────────── Экран ───────────────────────────

@Composable
internal fun CouponsScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(0) }                    // 0 = рядом, 1 = мои
    var detail by remember { mutableStateOf<CouponDto?>(null) }  // открыт купон
    var activated by remember { mutableStateOf<ActivatedCouponDto?>(null) } // показываем код

    when {
        activated != null -> ActivatedCodeView(activated!!, onDone = { activated = null; tab = 1; detail = null })
        detail != null -> CouponDetailView(
            couponId = detail!!.id,
            preview = detail!!,
            onBack = { detail = null },
            onActivated = { activated = it; detail = null },
        )
        else -> CouponsListScreen(
            tab = tab,
            onTab = { tab = it },
            onOpen = { detail = it },
            onBack = onBack,
        )
    }
}

@Composable
private fun CouponsListScreen(tab: Int, onTab: (Int) -> Unit, onOpen: (CouponDto) -> Unit, onBack: () -> Unit) {
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Скидки по пути", "Юл буйынса ташламалар"), onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxWidth()) {
            // Переключатель вкладок
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CouponTab(appText("Скидки рядом", "Яҡындағы ташламалар"), tab == 0, Modifier.weight(1f)) { onTab(0) }
                CouponTab(appText("Мои купоны", "Минең купондар"), tab == 1, Modifier.weight(1f)) { onTab(1) }
            }
            AnimatedContent(
                targetState = tab,
                transitionSpec = { (fadeIn(tween(220)) togetherWith fadeOut(tween(160))) },
                label = "coupon-tab",
            ) { t ->
                if (t == 0) NearbyCouponsTab(onOpen) else MyCouponsTab()
            }
        }
    }
}

@Composable
private fun CouponTab(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) CanonMint else CanonSurface, tween(220), label = "tabBg")
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

// ─────────────────────────── Вкладка «Скидки рядом» ───────────────────────────

@Composable
private fun NearbyCouponsTab(onOpen: (CouponDto) -> Unit) {
    val scope = rememberCoroutineScope()
    var coupons by remember { mutableStateOf<List<CouponDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var cityFilter by remember { mutableStateOf("") }
    val loadErr = appText("Не удалось загрузить скидки. Проверь интернет.", "Ташламаларҙы йөкләп булманы. Интернетты тикшер.")

    // По умолчанию показываем скидки в моём городе (если задан в профиле). Один раз при входе;
    // легко сбросить чипом «Все города». me() кешируется — лишней сети нет.
    LaunchedEffect(Unit) {
        ApiClient.me().onSuccess { o -> o.optString("city").takeIf { it.isNotBlank() }?.let { cityFilter = it } }
    }

    fun reload() {
        loading = true; error = null
        scope.launch {
            ApiClient.getCoupons(city = cityFilter.takeIf { it.isNotBlank() })
                .onSuccess { coupons = it }
                .onFailure { error = (it as? com.yuldash.app.data.ApiException)?.message ?: loadErr }
            loading = false
        }
    }
    LaunchedEffect(cityFilter) { reload() }

    // Города для чипов-фильтра — из пришедших купонов (+ уже выбранный).
    val cities = remember(coupons, cityFilter) {
        (coupons.map { it.city }.filter { it.isNotBlank() } + listOfNotNull(cityFilter.takeIf { it.isNotBlank() }))
            .distinct()
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item {
            Text(
                appText(
                    "Скидки от местных заведений по твоему маршруту. Активируй — покажешь код на месте.",
                    "Маршрутың буйынса ерле урындарҙан ташламалар. Активлаштыр — кодты урында күрһәтерһең.",
                ),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp,
            )
        }
        if (cities.isNotEmpty()) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CouponFilterChip(appText("Все города", "Бөтә ҡалалар"), cityFilter == "") { cityFilter = "" }
                    cities.forEach { c -> CouponFilterChip(c, cityFilter == c) { cityFilter = if (cityFilter == c) "" else c } }
                }
            }
        }
        when {
            loading && coupons.isEmpty() -> {
                item { SkeletonCard(lines = 3) }
                item { SkeletonCard(lines = 3) }
            }
            error != null && coupons.isEmpty() -> item { ListedError(error ?: "") { reload() } }
            coupons.isEmpty() -> item {
                AppEmptyState(
                    title = appText("Скидок пока нет", "Ташламалар әлегә юҡ"),
                    text = appText("Загляни позже — заведения-партнёры скоро появятся здесь.", "Һуңыраҡ кер — партнёр урындар тиҙҙән бында күренер."),
                    icon = Icons.Default.LocalOffer,
                )
            }
            else -> items(coupons.size, key = { "coup-" + coupons[it].id }) { i ->
                Box(Modifier.appearIn(i.coerceAtMost(6))) { CouponCard(coupons[i]) { onOpen(coupons[i]) } }
            }
        }
    }
}

@Composable
private fun CouponFilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (active) CanonMint else CanonSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (active) CanonGreen2 else CanonBorder),
        modifier = Modifier.height(48.dp),
    ) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (active) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

// ─────────────────────────── Карточка купона (витрина) ───────────────────────────

@Composable
private fun CouponCard(c: CouponDto, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                    Icon(couponCategoryIcon(c.partner.category), contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(10.dp).size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(c.partner.name.ifBlank { c.title }, color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                    Text(couponCategoryLabel(c.partner.category) + (if (c.city.isNotBlank()) "  ·  ${c.city}" else ""), color = CanonMuted, fontSize = 13.sp)
                }
                if (c.premium) PremiumBadge()
            }
            Text(c.title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp)
            DiscountBadge(c.discountText)
            // Метка партнёра + срок + остаток
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = CanonMint, shape = RoundedCornerShape(10.dp)) {
                    Text(appText("Партнёр Юлдаша", "Юлдаш партнёры"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
                Spacer(Modifier.weight(1f))
                if (c.remaining != null) {
                    Text(appText("осталось ${c.remaining}", "${c.remaining} ҡалды"), color = CanonMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            shortDate(c.validUntil)?.let { until ->
                Text(appText("Действует до $until", "$until тиклем ғәмәлдә"), color = CanonMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DiscountBadge(text: String) {
    if (text.isBlank()) return
    Surface(color = CanonGold, shape = RoundedCornerShape(14.dp)) {
        Text(
            text, color = CanonGoldInk, fontWeight = FontWeight.Black, fontSize = 22.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun PremiumBadge() {
    Surface(color = CanonGold, shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WorkspacePremium, contentDescription = appText("Премиум", "Премиум"), tint = CanonGoldInk, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(appText("Премиум", "Премиум"), color = CanonGoldInk, fontWeight = FontWeight.Black, fontSize = 11.sp)
        }
    }
}

// ─────────────────────────── Вкладка «Мои купоны» ───────────────────────────

@Composable
private fun MyCouponsTab() {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<MyCouponDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val loadErr = appText("Не удалось загрузить купоны. Проверь интернет.", "Купондарҙы йөкләп булманы. Интернетты тикшер.")

    fun reload() {
        loading = true; error = null
        scope.launch {
            ApiClient.getMyCoupons()
                .onSuccess { list = it }
                .onFailure { error = (it as? com.yuldash.app.data.ApiException)?.message ?: loadErr }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
    ) {
        when {
            loading && list.isEmpty() -> {
                item { SkeletonCard(lines = 2) }
                item { SkeletonCard(lines = 2) }
            }
            error != null && list.isEmpty() -> item { ListedError(error ?: "") { reload() } }
            list.isEmpty() -> item {
                AppEmptyState(
                    title = appText("Пока нет купонов", "Әлегә купондар юҡ"),
                    text = appText("Активируй скидку на вкладке «Скидки рядом» — код появится здесь.", "«Яҡындағы ташламалар» бүлегендә ташламаны активлаштыр — код бында күренер."),
                    icon = Icons.Default.LocalOffer,
                )
            }
            else -> items(list.size, key = { "myc-" + list[it].code }) { i ->
                Box(Modifier.appearIn(i.coerceAtMost(6))) { MyCouponCard(list[i]) }
            }
        }
    }
}

@Composable
private fun MyCouponCard(m: MyCouponDto) {
    val clipboard = LocalClipboardManager.current
    val c = m.coupon
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(c.partner.name.ifBlank { c.title }, color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                    Text(c.title, color = CanonMuted, fontSize = 13.sp)
                }
                CouponStatusChip(m.status)
            }
            DiscountBadge(c.discountText)
            // Код — крупно, моноширинно, легко продиктовать
            if (m.status == "reserved") {
                Surface(color = CanonMint, shape = CanonItemShape, border = BorderStroke(1.dp, CanonGreen2)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.code, color = CanonGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 24.sp, modifier = Modifier.weight(1f))
                        Surface(onClick = { clipboard.setText(AnnotatedString(m.code)) }, modifier = Modifier.minimumInteractiveComponentSize(), color = CanonSurface, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = appText("Скопировать код", "Кодты күсереп алыу"), tint = CanonGreen2, modifier = Modifier.padding(9.dp).size(20.dp))
                        }
                    }
                }
                Text(appText("Покажи код в заведении. Скидку даёт заведение.", "Кодты урында күрһәт. Ташламаны урын бирә."), color = CanonMuted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            shortDate(c.validUntil)?.let { until ->
                Text(appText("Действует до $until", "$until тиклем ғәмәлдә"), color = CanonMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CouponStatusChip(status: String) {
    val (bg, fg, ru, ba) = when (status) {
        "redeemed" -> Quad(CanonMint, CanonGreen2, "Использован", "Ҡулланылған")
        "canceled" -> Quad(CanonDangerBg, CanonRed, "Отменён", "Кире алынған")
        "expired" -> Quad(CanonWarnBg, CanonWarn, "Истёк", "Ваҡыты сыҡҡан")
        else -> Quad(CanonWarnBg, CanonWarn, "Ждёт показа", "Күрһәтеүҙе көтә")
    }
    Surface(color = bg, shape = RoundedCornerShape(10.dp)) {
        Text(appText(ru, ba), color = fg, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

private data class Quad(val bg: androidx.compose.ui.graphics.Color, val fg: androidx.compose.ui.graphics.Color, val ru: String, val ba: String)

// ─────────────────────────── Детально + активация ───────────────────────────

@Composable
private fun CouponDetailView(couponId: Int, preview: CouponDto, onBack: () -> Unit, onActivated: (ActivatedCouponDto) -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var coupon by remember { mutableStateOf(preview) }
    var activating by remember { mutableStateOf(false) }
    var actError by remember { mutableStateOf<String?>(null) }
    val actErrDefault = appText("Не получилось активировать. Повтори.", "Активлаштырып булманы. Ҡабатла.")

    // Догружаем свежий купон (актуальный remaining/срок), но UI сразу показывает preview.
    LaunchedEffect(couponId) {
        ApiClient.getCoupon(couponId).onSuccess { coupon = it }
    }

    val couponTitleFallback = appText("Купон", "Купон")
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(coupon.partner.name.ifBlank { couponTitleFallback }, onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                        Icon(couponCategoryIcon(coupon.partner.category), contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(26.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(coupon.partner.name.ifBlank { coupon.title }, color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp)
                        Text(couponCategoryLabel(coupon.partner.category), color = CanonMuted, fontSize = 13.sp)
                    }
                    if (coupon.premium) PremiumBadge()
                }
            }
            item { Text(coupon.title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 20.sp, lineHeight = 26.sp) }
            item { DiscountBadge(coupon.discountText) }
            if (coupon.description.isNotBlank()) {
                item { Text(coupon.description, color = CanonText, fontSize = 15.sp, lineHeight = 21.sp) }
            }
            // Заведение: адрес + телефон
            if (coupon.partner.address.isNotBlank() || coupon.partner.phone.isNotBlank()) {
                item {
                    AppCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (coupon.partner.address.isNotBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Place, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(coupon.partner.address, color = CanonText, fontSize = 14.sp)
                                }
                            }
                            shortDate(coupon.validUntil)?.let { until ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(appText("Действует до $until", "$until тиклем ғәмәлдә"), color = CanonText, fontSize = 14.sp)
                                }
                            }
                            if (coupon.remaining != null) {
                                Text(appText("Осталось купонов: ${coupon.remaining}", "Ҡалған купондар: ${coupon.remaining}"), color = CanonMuted, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            // Честный дисклеймер
            item {
                Surface(color = CanonMint, shape = CanonItemShape) {
                    Text(
                        appText(
                            "Скидку даёт заведение, а не Юлдаш. Мы только сводим тебя и местных партнёров.",
                            "Ташламаны Юлдаш түгел, урын бирә. Беҙ һине ерле партнёрҙар менән тик таныштырабыҙ.",
                        ),
                        color = CanonGreen2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(14.dp),
                    )
                }
            }
            if (actError != null) {
                item { Text(actError ?: "", color = CanonRed, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
            item {
                AppButton(
                    text = appText("Активировать скидку", "Ташламаны активлаштырыу"),
                    onClick = {
                        if (activating) return@AppButton
                        activating = true; actError = null
                        scope.launch {
                            ApiClient.activateCoupon(coupon.id)
                                .onSuccess { onActivated(it) }
                                .onFailure { actError = (it as? com.yuldash.app.data.ApiException)?.message ?: actErrDefault }
                            activating = false
                        }
                    },
                    style = AppButtonStyle.Accent,
                    icon = Icons.Default.LocalOffer,
                    loading = activating,
                )
            }
        }
    }
}

// ─────────────────────────── Активированный код (успех) ───────────────────────────

@Composable
private fun ActivatedCodeView(a: ActivatedCouponDto, onDone: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val c = a.coupon
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Твой код", "Кодың"), onDone) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Surface(color = CanonMint, shape = androidx.compose.foundation.shape.CircleShape) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(16.dp).size(36.dp))
                }
            }
            item {
                Text(appText("Скидка активирована!", "Ташлама активлаштырылды!"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 22.sp, textAlign = TextAlign.Center)
            }
            item {
                Text(appText("Покажи этот код в заведении", "Был кодты урында күрһәт"), color = CanonMuted, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
            item { DiscountBadge(c.discountText) }
            // Крупный код
            item {
                Surface(color = CanonSurface, shape = CanonCardShape, border = BorderStroke(2.dp, CanonGreen2)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(a.code, color = CanonGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 40.sp, textAlign = TextAlign.Center)
                        Surface(onClick = { clipboard.setText(AnnotatedString(a.code)) }, modifier = Modifier.minimumInteractiveComponentSize(), color = CanonMint, shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(appText("Скопировать", "Күсереп алыу"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
            item {
                Text(c.partner.name.ifBlank { c.title }, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center)
            }
            shortDate(c.validUntil)?.let { until ->
                item { Text(appText("Действует до $until", "$until тиклем ғәмәлдә"), color = CanonMuted, fontSize = 13.sp, textAlign = TextAlign.Center) }
            }
            item {
                Text(
                    appText("Скидку даёт заведение. Код одноразовый — сотрудник погасит его при тебе.", "Ташламаны урын бирә. Код бер тапҡырлыҡ — хеҙмәткәр уны һинең алдыңда һүндерә."),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            item { AppButton(appText("Готово", "Әҙер"), onDone, style = AppButtonStyle.Primary) }
        }
    }
}
