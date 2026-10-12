package com.yuldash.app

// ═══════════════════ M1: Купонный маркетплейс «Скидки по пути» (пользователь) ═══════════════════
// Витрина скидок от местных заведений вдоль маршрута. Две вкладки: «Скидки рядом» (публичная витрина
// с фильтром по городу) и «Мои купоны» (активированные). Тап по купону → детально → «Активировать» →
// крупный КОД для показа в заведении. Скидку даёт заведение (честный дисклеймер). Всё двуязычно,
// все состояния (загрузка/пусто/ошибка), цвета только Canon*, анимации плавные.

import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
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
import kotlinx.coroutines.Job
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

/**
 * Копейки → «X ₽» (или «X,YZ ₽», если копейки есть).
 *
 * Было `kop / 100` — целочисленное деление молча съедало копейки: долг 150,50 ₽ показывался
 * как «150 ₽», и человек считал, что рассчитался полностью. Деньги округлять вниз нельзя.
 * Тысячи разделяем пробелом, как в [fmtRub]: «1 250 ₽» читается быстрее, чем «1250 ₽».
 */
internal fun kopToRub(kop: Int): String {
    val abs = kotlin.math.abs(kop.toLong())          // toLong: abs(Int.MIN_VALUE) не переполняется
    val rub = String.format(java.util.Locale.US, "%,d", abs / 100).replace(',', ' ')
    val cents = (abs % 100).toInt()
    val tail = if (cents == 0) "" else String.format(java.util.Locale.US, ",%02d", cents)
    return (if (kop < 0) "−" else "") + rub + tail + " ₽"
}

/**
 * ISO-момент с сервера → «ДД.ММ.ГГГГ» по часам ТЕЛЕФОНА. Непонятный формат → null (подпись не
 * показываем). Для ОБЫЧНЫХ моментов-событий (что-то произошло: `createdAt`, `deliveredAt`,
 * `lastPassedAt` в чужих экранах) — человек смотрит на свои часы, как и в [formatDepart] (тот же
 * приём, тот же прецедент проекта).
 *
 * Для СРОКА ДЕЙСТВИЯ купона/промокода (когда сервер перестанет его принимать) эта функция НЕ
 * годится: там день решает не пояс телефона, а УФА (часовой пояс самого правила сервера) и
 * ИСКЛЮЧАЮЩАЯ граница `valid_until`. Смотри [couponLastAcceptedDay] — независимое ревью листа
 * поймало именно эту подмену (R2 здесь раньше ошибочно называл её «переводом в Уфу»).
 *
 * Было: первые 10 символов строки резались как готовая дата, БЕЗ перевода часового пояса вообще
 * — отсюда чистая дата из 10 символов (её мог обрезать вызывающий код, напр.
 * `CarPhotoScreen.dueAt?.take(10)`) идёт как есть: часа в ней нет, переводить некуда.
 *
 * Одна на всё приложение — переиспользуется партнёром/админом/документами (`shortDate` вызывают
 * `AdminParcelsScreen`, `AdminPromoScreen`, `CarPhotoScreen`, `DriverProfileScreen`,
 * `PartnerCabinetScreen`, `TaxiDocsScreens`). Копий было две с ОДНИМ именем: здесь и в профиле
 * водителя — и они расходились на битой дате. Третья функция с тем же именем (в баннере событий)
 * давала другой формат — «дд.мм», без года; она переименована в `shortDayMonth`.
 */
internal fun shortDate(iso: String?): String? {
    val raw = (iso ?: "").trim()
    if (raw.length == 10) {
        if (raw[4] != '-' || raw[7] != '-') return null
        return "${raw.substring(8, 10)}.${raw.substring(5, 7)}.${raw.substring(0, 4)}"
    }
    if (raw.length < 10) return null
    val ms = parseIsoUtcMillis(raw) ?: return null
    val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return String.format(
        java.util.Locale.US, "%02d.%02d.%04d",
        c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.YEAR),
    )
}

/** Часовой пояс самого бизнес-правила сервера (когда касса перестанет принимать купон/код) —
 *  Уфа всегда, независимо от того, где стоит телефон смотрящего на экран. */
private val UFA_TZ: java.util.TimeZone = java.util.TimeZone.getTimeZone("Asia/Yekaterinburg")

/** Календарь Уфы на момент (valid_until − 1 секунда) — общая часть [couponLastAcceptedDay] и
 *  [ufaIsoDate]. `null`, если `iso` короче полного момента или не разбирается. Зачем минус
 *  секунда — см. docs у [couponLastAcceptedDay]. */
private fun ufaMomentJustBeforeExpiry(iso: String): java.util.Calendar? {
    val ms = parseIsoUtcMillis(iso) ?: return null
    return java.util.Calendar.getInstance(UFA_TZ).apply { timeInMillis = ms - 1000L }
}

/**
 * «Действует до» для СРОКА купона/промокода — последний КАЛЕНДАРНЫЙ ДЕНЬ по Уфе
 * (Asia/Yekaterinburg, UTC+5 — ИМЕННО Уфа, а не часы телефона смотрящего), когда код ещё примут.
 *
 * `valid_until` — ИСКЛЮЧАЮЩАЯ граница: купон/промокод истёк, если `valid_until <= now`
 * (`backend/app/routers/coupons.py::_in_window`, `backend/app/promo_ride.py::in_window`). Значит
 * последний момент, когда код ещё действует, — `valid_until − 1 секунда`; показывать нужно
 * календарный день ИМЕННО этого момента (по Уфе), а не момента `valid_until` как есть.
 *
 * Почему это не то же самое, что [shortDate]. В проде форма партнёра и админ шлют ОБЫЧНУЮ дату
 * без времени («2026-10-31» — «действует по 31 октября»), и `client_dt_to_utc` кладёт её как
 * 00:00 ЭТОГО дня по Уфе — в базе лежит момент НАЧАЛА дня, а не его конец:
 * `"2026-10-31"` (форма) → `valid_until = "2026-10-30T19:00:00"` (UTC, это и есть 00:00 31.10 Уфа).
 * Показать этот момент как есть (`shortDate`-стиль) даёт «31.10» — но из-за исключающей границы
 * касса откажет УЖЕ в начале 31 октября: реальный последний принятый день — 30.10. Отнимаем
 * секунду ДО перевода в Уфу — получаем «30.10, 23:59:59», то есть верный ответ.
 *
 * После правки сервера (лист 1.3, `_coupon_valid_until`) НОВЫЕ записи хранят КОНЕЦ дня по Уфе
 * (23:59:59) — тогда минус секунда остаётся в том же календарном дне и ничего не меняет. Одно
 * правило верно для ОБОИХ видов строк, старых и новых:
 *
 * ```
 * "2026-10-30T19:00:00" (старая запись, форма просила «до 31.10») → 30.10.2026
 * "2026-10-31T18:59:59" (новая запись, после правки сервера)      → 31.10.2026
 * ```
 *
 * Старые записи (созданные до правки сервера) миграцией на этой ветке не затронуты — функция
 * лечит ПОКАЗ, не саму запись в базе; так и задумано (см. карточку, «Остаток»).
 */
internal fun couponLastAcceptedDay(iso: String?): String? {
    val raw = (iso ?: "").trim()
    if (raw.length == 10) {
        // Чистая дата без времени — отнимать нечего, часа тут нет (как и в shortDate).
        if (raw[4] != '-' || raw[7] != '-') return null
        return "${raw.substring(8, 10)}.${raw.substring(5, 7)}.${raw.substring(0, 4)}"
    }
    if (raw.length < 10) return null
    val c = ufaMomentJustBeforeExpiry(raw) ?: return null
    return String.format(
        java.util.Locale.US, "%02d.%02d.%04d",
        c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.YEAR),
    )
}

/**
 * То же правило, что в [couponLastAcceptedDay] (последний день по Уфе с учётом исключающей
 * границы `valid_until`), но форматом «ГГГГ-ММ-ДД» — для РЕДАКТИРУЕМЫХ полей срока, а не для
 * показа человеку.
 *
 * Зачем нужна именно здесь. Форма купона в кабинете партнёра (`PartnerCabinetScreen.kt::CouponForm`,
 * поле «Действует до (ГГГГ-ММ-ДД)») сейчас читает `initial.validUntil` в это поле через голый
 * `.take(10)` — без учёта исключающей границы и часового пояса. На старой записи
 * («2026-10-30T19:00:00», форма просила «до 31.10») это покажет «2026-10-31», и если партнёр
 * нажмёт «Сохранить» НИЧЕГО не меняя, купон молча продлится на лишние сутки — бизнес платит
 * комиссию за каждое погашение, лишний день не бесплатен. С этой функцией чтение даёт «2026-10-30»
 * — то, что форма и просила изначально, — и сохранение «как есть» ничего не продлевает.
 * Эта функция ломает цепочку на чтении: `initial.validUntil?.let(::ufaIsoDate) ?: ""` вместо
 * `initial?.validUntil?.take(10) ?: ""`. Файл кабинета партнёра вне зоны этого листа — правку не
 * делаю, см. отчёт.
 */
internal fun ufaIsoDate(iso: String?): String? {
    val raw = (iso ?: "").trim()
    if (raw.length == 10) return raw.takeIf { it[4] == '-' && it[7] == '-' }
    if (raw.length < 10) return null
    val c = ufaMomentJustBeforeExpiry(raw) ?: return null
    return String.format(
        java.util.Locale.US, "%04d-%02d-%02d",
        c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
    )
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CouponTab(appText("Скидки рядом", "Яҡындағы ташламалар"), tab == 0, Modifier.weight(1f)) { onTab(0) }
                CouponTab(appText("Мои купоны", "Минең купондар"), tab == 1, Modifier.weight(1f)) { onTab(1) }
            }
            AnimatedContent(
                targetState = tab,
                transitionSpec = { (fadeIn(tween(CanonMotion.QUICK)) togetherWith fadeOut(tween(CanonMotion.QUICK))) },
                label = "coupon-tab",
            ) { t ->
                if (t == 0) NearbyCouponsTab(onOpen) else MyCouponsTab(onGoNearby = { onTab(0) })
            }
        }
    }
}

@Composable
private fun CouponTab(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) CanonMint else CanonSurface, tween(CanonMotion.QUICK), label = "tabBg")
    Surface(
        onClick = onClick, color = bg, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (active) CanonGreen2 else CanonBorder),
        // Было height(48.dp) — жёсткая высота резала башкирскую подпись («Яҡындағы ташламалар»
        // на половине ширины экрана) на крупном системном шрифте: текст переносился на вторую
        // строку, а вторая строка не помещалась и обрезалась. heightIn(min=…) держит тач-цель
        // ≥48dp, но даёт вкладке вырасти, если подписи нужно две строки.
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (active) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center)
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

    // Было: два запроса гонялись за одним и тем же состоянием. При входе сразу уходил запрос
    // «все города», а следом (когда подтягивался кешированный профиль) — второй, уже по городу.
    // Чей ответ прилетит ПОСЛЕДНИМ, тот и побеждал: иногда это был первый («все города»), и
    // список не совпадал с подсвеченным чипом города. Держим Job последнего запроса и отменяем
    // предыдущий перед стартом нового — устаревший ответ больше не может переписать свежий.
    var loadJob by remember { mutableStateOf<Job?>(null) }
    fun reload() {
        loadJob?.cancel()
        loading = true; error = null
        loadJob = scope.launch {
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

    // Обновление жестом: витрина живая — партнёр может выложить скидку, пока человек смотрит
    // экран. Без жеста единственным способом обновиться был выход с экрана и заход заново.
    AppPullRefresh(refreshing = loading && coupons.isNotEmpty(), onRefresh = { reload() }) {
    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item {
            Text(
                appText(
                    "Скидки от местных заведений по твоему маршруту. Активируй — покажешь код на месте.",
                    "Маршрутың буйынса ерле урындарҙан ташламалар. Активлаштыр — кодты урында күрһәтерһең.",
                ),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
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
        // Витрина не обновилась, а карточки на экране остались: без этой плашки человек шёл
        // в заведение со скидкой, которую партнёр уже снял.
        if (error != null && coupons.isNotEmpty()) {
            item(key = "stale") { AppStaleStrip(onRetry = { reload() }) }
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
                    Icon(couponCategoryIcon(c.partner.category), contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(c.partner.name.ifBlank { c.title }, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(couponCategoryLabel(c.partner.category) + (if (c.city.isNotBlank()) "  ·  ${c.city}" else ""), color = CanonMuted, fontSize = 14.sp)
                }
                if (c.premium) PremiumBadge()
            }
            Text(c.title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 23.sp)
            DiscountBadge(c.discountText)
            // Метка партнёра + срок + остаток
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = CanonMint, shape = RoundedCornerShape(8.dp)) {
                    Text(appText("Партнёр Юлдаша", "Юлдаш партнёры"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                Spacer(Modifier.weight(1f))
                if (c.remaining != null) {
                    Text(appText("осталось ${c.remaining}", "${c.remaining} ҡалды"), color = CanonMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            couponLastAcceptedDay(c.validUntil)?.let { until ->
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
            text, color = CanonGoldInk, fontWeight = FontWeight.Bold, fontSize = 24.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PremiumBadge() {
    Surface(color = CanonGold, shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WorkspacePremium, contentDescription = appText("Премиум", "Премиум"), tint = CanonGoldInk, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(appText("Премиум", "Премиум"), color = CanonGoldInk, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

// ─────────────────────────── Вкладка «Мои купоны» ───────────────────────────

@Composable
private fun MyCouponsTab(onGoNearby: () -> Unit = {}) {
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

    // Тот же жест, что и на витрине. Здесь он важнее: человек показал код в заведении и хочет
    // убедиться, что купон погашен — статус меняет сотрудник, а не приложение.
    AppPullRefresh(refreshing = loading && list.isNotEmpty(), onRefresh = { reload() }) {
    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
    ) {
        // Ровно та ситуация, ради которой тут жест: человек тянет экран, чтобы увидеть «погашен».
        // Если обновление не прошло, а старый статус остался — молчать нельзя.
        if (error != null && list.isNotEmpty()) {
            item(key = "stale") { AppStaleStrip(onRetry = { reload() }) }
        }
        when {
            loading && list.isEmpty() -> {
                item { SkeletonCard(lines = 2) }
                item { SkeletonCard(lines = 2) }
            }
            error != null && list.isEmpty() -> item { ListedError(error ?: "") { reload() } }
            list.isEmpty() -> item {
                // Текст указывал дорогу («активируй на вкладке «Скидки рядом»»), но идти туда
                // человек должен был сам. Экран, который знает, что делать дальше, обязан вести,
                // а не подсказывать: кнопка переключает на ту же вкладку, о которой говорит текст.
                AppEmptyState(
                    title = appText("Пока нет купонов", "Әлегә купондар юҡ"),
                    text = appText("Активируй скидку на вкладке «Скидки рядом» — код появится здесь.", "«Яҡындағы ташламалар» бүлегендә ташламаны активлаштыр — код бында күренер."),
                    icon = Icons.Default.LocalOffer,
                    actionLabel = appText("Посмотреть скидки", "Ташламаларҙы ҡарау"),
                    onAction = onGoNearby,
                )
            }
            else -> items(list.size, key = { "myc-" + list[it].code }) { i ->
                Box(Modifier.appearIn(i.coerceAtMost(6))) { MyCouponCard(list[i]) }
            }
        }
    }
    }
}

@Composable
private fun MyCouponCard(m: MyCouponDto) {
    val clipboard = LocalClipboardManager.current
    val c = m.coupon
    // Сервер никогда не проставляет статус "expired" у брони купона (leaf-1.3, «вечные брони» —
    // известное ограничение) — только "reserved"/"redeemed"/"canceled". Истёкший купон молча
    // оставался «Ждёт показа» вместе с живым кодом, хотя касса его уже не примет. Граница та же,
    // что на сервере (`backend/app/routers/coupons.py::_in_window`): valid_until ИСКЛЮЧАЮЩАЯ —
    // истёк, если valid_until <= сейчас.
    val expiredByDate = m.status == "reserved" && c.validUntil?.let(::parseIsoUtcMillis)
        ?.let { it <= System.currentTimeMillis() } == true
    val effectiveStatus = if (expiredByDate) "expired" else m.status
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(c.partner.name.ifBlank { c.title }, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(c.title, color = CanonMuted, fontSize = 14.sp)
                }
                CouponStatusChip(effectiveStatus)
            }
            DiscountBadge(c.discountText)
            // Код — крупно, моноширинно, легко продиктовать. Истёкший прячем: показывать код
            // рядом с «Истёк» выглядело бы так, будто им ещё можно воспользоваться в заведении.
            if (m.status == "reserved" && !expiredByDate) {
                Surface(color = CanonMint, shape = CanonItemShape, border = BorderStroke(1.dp, CanonGreen2)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.code, color = CanonGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 24.sp, modifier = Modifier.weight(1f))
                        Surface(onClick = { clipboard.setText(AnnotatedString(m.code)) }, modifier = Modifier.minimumInteractiveComponentSize(), color = CanonSurface, shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = appText("Скопировать код", "Кодты күсереп алыу"), tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(20.dp))
                        }
                    }
                }
                Text(appText("Покажи код в заведении. Скидку даёт заведение.", "Кодты урында күрһәт. Ташламаны урын бирә."), color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            couponLastAcceptedDay(c.validUntil)?.let { until ->
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
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(appText(ru, ba), color = fg, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
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
    var reporting by remember { mutableStateOf(false) }
    var reportSending by remember { mutableStateOf(false) }
    var reportError by remember { mutableStateOf<String?>(null) }
    val actErrDefault = appText("Не получилось активировать. Повтори.", "Активлаштырып булманы. Ҡабатла.")
    // Было: при неудачной отправке жалобы человек видел «Не получилось АКТИВИРОВАТЬ» — текст про
    // чужое действие, к жалобе отношения не имеющий. Отдельная строка под своё действие.
    val reportErrDefault = appText("Не получилось отправить. Повтори.", "Ебәреп булманы. Ҡабатла.")
    val reportThanks = appText("Спасибо, посмотрим", "Рәхмәт, ҡарарбыҙ")

    // Догружаем свежий купон (актуальный remaining/срок), но UI сразу показывает preview.
    LaunchedEffect(couponId) {
        ApiClient.getCoupon(couponId).onSuccess { coupon = it }
    }

    val couponTitleFallback = appText("Купон", "Купон")
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(coupon.partner.name.ifBlank { couponTitleFallback }, onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                        Icon(couponCategoryIcon(coupon.partner.category), contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(26.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(coupon.partner.name.ifBlank { coupon.title }, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text(couponCategoryLabel(coupon.partner.category), color = CanonMuted, fontSize = 14.sp)
                    }
                    if (coupon.premium) PremiumBadge()
                }
            }
            item { Text(coupon.title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 25.sp) }
            item { DiscountBadge(coupon.discountText) }
            if (coupon.description.isNotBlank()) {
                item { Text(coupon.description, color = CanonText, fontSize = 16.sp, lineHeight = 23.sp) }
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
                            couponLastAcceptedDay(coupon.validUntil)?.let { until ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(appText("Действует до $until", "$until тиклем ғәмәлдә"), color = CanonText, fontSize = 14.sp)
                                }
                            }
                            // Телефон заведения. Карточка и раньше ОТКРЫВАЛАСЬ по его наличию,
                            // но самого номера в ней не было: у партнёра без адреса человек видел
                            // пустой блок и не мог ни доехать, ни позвонить. Тап — сразу набор
                            // с подставленным номером, вручную переписывать одиннадцать цифр не нужно.
                            if (coupon.partner.phone.isNotBlank()) {
                                Surface(
                                    onClick = {
                                        runCatching {
                                            ctx.startActivity(
                                                Intent(Intent.ACTION_DIAL, Uri.parse("tel:${coupon.partner.phone}"))
                                            )
                                        }
                                    },
                                    color = CanonSurface,
                                    shape = CanonItemShape,
                                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
                                ) {
                                    Row(
                                        Modifier.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Default.Phone,
                                            contentDescription = appText("Позвонить в заведение", "Урынға шылтыратыу"),
                                            tint = CanonGreen2,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(coupon.partner.phone, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                }
                            }
                            if (coupon.remaining != null) {
                                Text(appText("Осталось купонов: ${coupon.remaining}", "Ҡалған купондар: ${coupon.remaining}"), color = CanonMuted, fontSize = 14.sp)
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
                        color = CanonGreen2, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(12.dp),
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
            // «Тут что-то не так» — третий вход в очередь модерации, помимо автопроверки и
            // правок бизнеса. Автопроверка ищет телефоны и ругань по шаблонам; «скидки на деле
            // нет» она не увидит никогда — это может сказать только живой человек, который
            // пришёл в заведение. Жалоба купон НЕ снимает (иначе конкурент гасил бы чужую
            // скидку одной кнопкой) — только ставит его перед глазами админа.
            item {
                TextButton(onClick = { reporting = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        appText("Тут что-то не так — сообщить", "Бында ниҙер дөрөҫ түгел — хәбәр итеү"),
                        color = CanonMuted, fontSize = 14.sp,
                    )
                }
            }
        }
    }

    if (reporting) {
        ReportCouponDialog(
            sending = reportSending,
            error = reportError,
            onDismiss = { if (!reportSending) { reporting = false; reportError = null } },
            onSend = { reason ->
                // Двойной тап по «Отправить» — та же защита, что у «Активировать скидку» выше.
                if (reportSending) return@ReportCouponDialog
                reportSending = true; reportError = null
                scope.launch {
                    ApiClient.reportCoupon(coupon.id, reason)
                        .onSuccess {
                            reporting = false
                            Toast.makeText(ctx, reportThanks, Toast.LENGTH_SHORT).show()
                        }
                        // Было: диалог закрывался ДО ответа сервера, поэтому при неудаче человек
                        // уже не видел ни диалога, ни своего текста — писать жалобу заново от руки.
                        // Теперь диалог остаётся открытым, текст не теряется, ошибка — своя.
                        .onFailure {
                            reportError = (it as? com.yuldash.app.data.ApiException)?.message ?: reportErrDefault
                        }
                    reportSending = false
                }
            },
        )
    }
}

/** Жалоба на купон: коротко и без обвинений — человек просто говорит, что не сошлось. */
@Composable
private fun ReportCouponDialog(sending: Boolean, error: String?, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(appText("Что не так с этой скидкой?", "Был ташлама менән ни дөрөҫ түгел?"),
                 fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    appText(
                        "Напиши в двух словах. Мы посмотрим сами — скидка пока останется на месте.",
                        "Ике һүҙ менән яҙ. Беҙ үҙебеҙ ҡарарбыҙ — ташлама әлегә урынында ҡала.",
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                )
                OutlinedTextField(
                    value = reason, onValueChange = { reason = it.take(500) },
                    placeholder = {
                        Text(appText("Например: скидку не дали", "Мәҫәлән: ташлама бирмәнеләр"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !sending,
                )
                if (error != null) {
                    Text(error, color = CanonRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(reason.trim()) }, enabled = reason.isNotBlank() && !sending) {
                Text(appText("Отправить", "Ебәреү"), color = CanonGreen2, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) {
                Text(appText("Отмена", "Кире ҡағыу"), color = CanonMuted)
            }
        },
    )
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
            contentPadding = PaddingValues(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Surface(color = CanonMint, shape = androidx.compose.foundation.shape.CircleShape) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(16.dp).size(36.dp))
                }
            }
            item {
                Text(appText("Скидка активирована!", "Ташлама активлаштырылды!"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 24.sp, textAlign = TextAlign.Center)
            }
            item {
                Text(appText("Покажи этот код в заведении", "Был кодты урында күрһәт"), color = CanonMuted, fontSize = 16.sp, textAlign = TextAlign.Center)
            }
            item { DiscountBadge(c.discountText) }
            // Крупный код
            item {
                Surface(color = CanonSurface, shape = CanonCardShape, border = BorderStroke(2.dp, CanonGreen2)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(a.code, color = CanonGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 34.sp, textAlign = TextAlign.Center)
                        Surface(onClick = { clipboard.setText(AnnotatedString(a.code)) }, modifier = Modifier.minimumInteractiveComponentSize(), color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(appText("Скопировать", "Күсереп алыу"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
            item {
                Text(c.partner.name.ifBlank { c.title }, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center)
            }
            couponLastAcceptedDay(c.validUntil)?.let { until ->
                item { Text(appText("Действует до $until", "$until тиклем ғәмәлдә"), color = CanonMuted, fontSize = 14.sp, textAlign = TextAlign.Center) }
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
