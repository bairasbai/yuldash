package com.yuldash.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.CourierEarningsDayDto
import com.yuldash.app.data.CourierEarningsDto

/*
 * ════════════════════════════════════════════════════════════════════════════
 *  Типографика и ритм «денежных» экранов Юлдаша
 * ════════════════════════════════════════════════════════════════════════════
 *  Заработок курьера, расшифровка денег таксиста и чек за такси — один документ
 *  в трёх видах, и читаться они должны одинаково. Поэтому размеров ровно ЧЕТЫРЕ,
 *  пятого на экране не бывает:
 *
 *    Hero    34sp Black  — ОДНА главная сумма на экране, она и доминирует
 *    Value   17sp Black  — суммы в строках, заголовки блоков
 *    Body    14sp        — подписи строк, обычный текст
 *    Caption 12sp        — сноски, метки, вторичное
 *
 *  Отступы — только кратные 4dp: 4 / 8 / 12 / 16 / 20 / 24 / 32.
 *  Человек смотрит сюда, когда сомневается, не обманули ли его: главная цифра
 *  должна читаться раньше, чем он успеет усомниться.
 */
internal object MoneyType {
    val Hero = 34.sp
    val HeroLine = 40.sp
    val HeroTracking = (-0.5f).sp   // крупные цифры без разрядки выглядят рыхло
    val Value = 17.sp
    val ValueLine = 22.sp
    val Body = 14.sp
    val BodyLine = 19.sp
    val Caption = 12.sp
    val CaptionLine = 16.sp
}

/**
 * «Мой заработок» курьера — по образцу экрана водителя.
 *
 * Зачем: курьер видел только «должен Юлдашу столько-то», и работа выглядела сплошным долгом,
 * хотя у водителя разбивка заработка есть с самого начала (аудит 2026-07-26). Здесь честно:
 * сколько получено чистыми, сколько ушло комиссией и сколько доставок — по дням.
 *
 * ВАЖНО: все суммы в КОПЕЙКАХ (в отличие от экрана водителя, где сервер отдаёт рубли) —
 * форматируем через [kopToRub], иначе ошибка в 100 раз.
 * Состояния: загрузка / ошибка + «Повторить» / пусто (новичок без доставок) /
 * «показываем старое, обновить не вышло» — деньги молча устаревать не должны.
 */
@Composable
internal fun CourierEarningsScreen(onBack: () -> Unit) {
    var period by remember { mutableStateOf("week") }
    var data by remember { mutableStateOf<CourierEarningsDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(period, reload) {
        loading = true; error = false
        ApiClient.getCourierEarnings(period)
            .onSuccess { data = it }
            .onFailure { error = true }
        loading = false
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Мой заработок", "Минең табыш"), onBack) },
    ) { padding ->
        val d = data
        // Пока тянем новый период, старые цифры приглушаем: сумма за неделю не должна
        // молча выдавать себя за сумму за месяц.
        val freshness by animateFloatAsState(
            targetValue = if (loading && d != null) 0.4f else 1f,
            animationSpec = tween(220),
            label = "courierFreshness",
        )
        // Появление всего блока разом, а не карточка за карточкой: состояние живёт на уровне
        // экрана, поэтому при прокрутке назад ничего заново не мигает (в LazyColumn строки
        // пересоздаются, и покадровая анимация «на элемент» дёргалась бы каждый раз).
        val reveal by animateFloatAsState(
            targetValue = if (d != null) 1f else 0f,
            animationSpec = tween(360),
            label = "courierReveal",
        )
        val enter = Modifier.graphicsLayer {
            alpha = reveal * freshness
            translationY = (1f - reveal) * 16.dp.toPx()
        }

        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        ) {
            item(key = "period") {
                MoneyPeriodSwitch(period = period, onSelect = { period = it })
            }
            when {
                loading && d == null -> {
                    item(key = "skeleton-total") { SkeletonCard(lines = 3) }
                    item(key = "skeleton-days") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            repeat(4) { SkeletonCard(lines = 1) }
                        }
                    }
                }
                error && d == null -> item(key = "error") { AppErrorState(onRetry = { reload++ }) }
                d == null || d.deliveries == 0 -> item(key = "empty") {
                    // Пусто бывает двух видов: новичок вообще без доставок и опытный курьер
                    // с тихой неделей. Говорить второму «пока нет доставок» — врать ему в глаза.
                    AppEmptyState(
                        title = when (period) {
                            "week" -> appText("За эту неделю доставок нет", "Был аҙнала илтеү юҡ")
                            "month" -> appText("За этот месяц доставок нет", "Был айҙа илтеү юҡ")
                            else -> appText("Пока нет доставок", "Әлегә илтеү юҡ")
                        },
                        text = appText(
                            "Возьми заказ во вкладке «Заказы» — здесь появится, сколько ты заработал.",
                            "«Заказдар» бүлегендә заказ ал — бында күпме эшләгәнең күренәсәк.",
                        ),
                        icon = Icons.Default.DeliveryDining,
                    )
                }
                else -> {
                    // Данные есть, но последнее обновление не прошло — говорим прямо.
                    if (error) item(key = "stale") { MoneyStaleStrip(onRetry = { reload++ }) }
                    item(key = "totals") { CourierTotalsCard(d, enter) }
                    if (d.byDay.isNotEmpty()) {
                        item(key = "days-header") {
                            MoneySectionHeader(
                                title = appText("По дням", "Көндәр буйынса"),
                                caption = appText(
                                    "Сколько осталось на руках за каждый день.",
                                    "Һәр көн өсөн ҡулда күпме ҡалғаны.",
                                ),
                                modifier = enter,
                            )
                        }
                        val maxNet = d.byDay.maxOfOrNull { it.netKop }?.coerceAtLeast(1) ?: 1
                        itemsIndexed(d.byDay, key = { _, day: CourierEarningsDayDto -> day.date }) { i, day ->
                            CourierDayRow(day, maxNet, i, enter)
                        }
                    }
                    item(key = "note") {
                        Text(
                            appText(
                                "Деньги за доставку получаешь напрямую — Юлдаш их не держит. Комиссия копится отдельно и платится в кабинете.",
                                "Илтеү аҡсаһын туранан-тура алаһың — Юлдаш уны тотмай. Комиссия айырым йыйыла һәм кабинетта түләнә.",
                            ),
                            color = CanonMuted,
                            fontSize = MoneyType.Caption,
                            lineHeight = MoneyType.CaptionLine,
                            modifier = enter.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────── Переключатель периода ───────────────────

/**
 * Сегменты равной ширины в одной пилюле — период читается как один выбор, а не как
 * три случайных чипа разной длины. Активный сегмент перекрашивается плавно.
 * Тач-цель 48dp; текст без иконок, чтобы длинное башкирское слово не выдавливало вёрстку.
 */
@Composable
private fun MoneyPeriodSwitch(period: String, onSelect: (String) -> Unit) {
    Surface(
        color = CanonSurface,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, CanonBorder),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MoneyPeriodSegment(appText("Неделя", "Аҙна"), period == "week", Modifier.weight(1f)) { onSelect("week") }
            MoneyPeriodSegment(appText("Месяц", "Ай"), period == "month", Modifier.weight(1f)) { onSelect("month") }
            MoneyPeriodSegment(appText("Всё время", "Бөтә ваҡыт"), period == "all", Modifier.weight(1f)) { onSelect("all") }
        }
    }
}

@Composable
private fun MoneyPeriodSegment(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    // Color.White на CanonGreen2 — тот же «чернильный» цвет, что у основной кнопки
    // (AppButton.Primary) и активного чипа: отдельного токена под «текст на зелёном» нет.
    val bg by animateColorAsState(if (active) CanonGreen2 else Color.Transparent, tween(220), label = "segBg")
    val fg by animateColorAsState(if (active) Color.White else CanonMutedStrong, tween(220), label = "segFg")
    Surface(
        color = bg,
        shape = RoundedCornerShape(999.dp),
        modifier = modifier.bounceClick(onSelect),
    ) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = fg,
                fontSize = MoneyType.Body,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ─────────────────── Итоги ───────────────────

@Composable
private fun CourierTotalsCard(d: CourierEarningsDto, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    appText("Заработано чистыми", "Таҙа эшләнде"),
                    color = CanonMuted, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine,
                )
                // Сумма меняется по периоду — не подменяем цифру рывком, а проявляем новую.
                AnimatedContent(
                    targetState = d.netKop,
                    transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
                    label = "courierNet",
                ) { net ->
                    Text(
                        kopToRub(net),
                        color = CanonText,
                        fontSize = MoneyType.Hero,
                        lineHeight = MoneyType.HeroLine,
                        letterSpacing = MoneyType.HeroTracking,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
            // Строки во всю ширину, а не половинные плитки: сумму никогда не обрежет,
            // сколько бы ни было разрядов. Тот же вид, что у расшифровки таксиста.
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MoneyLine(
                    Icons.Default.DeliveryDining, CanonMint, CanonGreen2,
                    appText("Доставок", "Илтеү"),
                    d.deliveries.toString(),
                    CanonText,
                )
                // Комиссия — деньги, которые ушли: цвет предупреждения, а не бренда.
                MoneyLine(
                    Icons.Default.Percent, CanonWarnBg, CanonWarn,
                    appText("Комиссия", "Комиссия"),
                    kopToRub(d.commissionKop),
                    CanonWarn,
                )
            }
        }
    }
}

// ─────────────────── День ───────────────────

/** Строка дня с полоской, пропорциональной сумме — видно, какой день был удачным. */
@Composable
private fun CourierDayRow(
    day: CourierEarningsDayDto,
    maxNet: Int,
    index: Int,
    modifier: Modifier = Modifier,
) {
    // Полоска должна ВЫРАСТАТЬ. animateFloatAsState на первом кадре берёт цель как есть
    // (то есть появлялась рывком на полную длину) — поэтому стартуем с нуля и включаем
    // настоящую цель после первой композиции, с каскадом по индексу.
    var grown by remember(day.date) { mutableStateOf(false) }
    LaunchedEffect(day.date) { grown = true }
    val target = if (grown) (day.netKop.toFloat() / maxNet).coerceIn(0f, 1f) else 0f
    val fraction by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 560, delayMillis = 40 * index.coerceAtMost(6)),
        label = "courierDayBar",
    )

    Surface(
        color = CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, CanonBorder),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        courierDayLabel(day.date),
                        color = CanonText, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        appText("Доставок: ${day.deliveries}", "Илтеү: ${day.deliveries}"),
                        color = CanonMuted, fontSize = MoneyType.Caption, lineHeight = MoneyType.CaptionLine,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    kopToRub(day.netKop),
                    color = CanonGreen2, fontSize = MoneyType.Value, lineHeight = MoneyType.ValueLine,
                    fontWeight = FontWeight.Black, textAlign = TextAlign.End,
                )
            }
            Box(
                Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(999.dp)).background(CanonMint),
            ) {
                Box(
                    Modifier.fillMaxWidth(fraction).height(8.dp)
                        .clip(RoundedCornerShape(999.dp)).background(CanonGreen2),
                )
            }
        }
    }
}

// ─────────────────── Общие блоки денежных экранов ───────────────────

/**
 * Строка расшифровки: кружок с иконкой, подпись и сумма справа.
 * Одна и та же на заработке курьера и на расшифровке таксиста — деньги должны выглядеть
 * одинаково, где бы человек их ни считал. Сумма не обрезается: ширину уступает подпись.
 */
@Composable
internal fun MoneyLine(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    label: String,
    value: String,
    valueColor: Color,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = iconBg, shape = CircleShape) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.padding(8.dp).size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label, color = CanonMuted, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value, color = valueColor, fontSize = MoneyType.Value, lineHeight = MoneyType.ValueLine,
            fontWeight = FontWeight.Black, textAlign = TextAlign.End,
        )
    }
}

/** Заголовок раздела: Value + тихая подпись. Свой, чтобы не тащить пятый размер шрифта. */
@Composable
internal fun MoneySectionHeader(title: String, caption: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title, color = CanonText, fontSize = MoneyType.Value, lineHeight = MoneyType.ValueLine,
            fontWeight = FontWeight.Black,
        )
        Text(caption, color = CanonMuted, fontSize = MoneyType.Caption, lineHeight = MoneyType.CaptionLine)
    }
}

/**
 * Цифры на экране есть, но обновить их не вышло. Молчать нельзя: человек решит, что видит
 * свежую сумму. Поэтому спокойная плашка + честный повтор (тач-цель 48dp).
 */
@Composable
internal fun MoneyStaleStrip(onRetry: () -> Unit) {
    Surface(color = CanonWarnBg, shape = CanonItemShape) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                appText("Не удалось обновить — цифры могут быть старыми", "Яңырта алманыҡ — һандар иҫке булыуы мөмкин"),
                color = CanonWarn, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                color = CanonSurface,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.bounceClick(onRetry),
            ) {
                Row(
                    Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        appText("Повторить", "Ҡабатлау"),
                        color = CanonWarn, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine,
                        fontWeight = FontWeight.Black, maxLines = 1,
                    )
                }
            }
        }
    }
}

/** "2026-07-14" → "14.07". Формат неожиданный — показываем как есть, выдумывать дату нельзя. */
private fun courierDayLabel(date: String): String =
    if (date.length >= 10 && date[4] == '-' && date[7] == '-') {
        "${date.substring(8, 10)}.${date.substring(5, 7)}"
    } else {
        date
    }
