package com.yuldash.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Savings
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.DriverTaxiRideDto
import com.yuldash.app.data.DriverTaxiRidesDto

/**
 * «Мои поездки такси» — расшифровка денег по каждой поездке (GET /driver/taxi-rides).
 *
 * Зачем: «Мой заработок» отдаёт одну сумму за период, и спор «Юлдаш говорит 4200, я насчитал
 * 4600 — где мои 400?» закрыть было нечем. В таксопарках это причина №1 ухода водителя.
 * Здесь по каждой поездке видно: цена пассажиру → комиссия платформы → чистыми водителю.
 *
 * Деньги: price — в ₽ (как показываем пассажиру), fee/net — в копейках (kopToRub).
 * Типографика — четыре ступени [MoneyType], как на всех денежных экранах.
 * Состояния: загрузка (скелетоны) / ошибка + «Повторить» / пусто (новичок без поездок) /
 * данные есть, но обновить не вышло → плашка [MoneyStaleStrip].
 */
@Composable
internal fun DriverTaxiRidesScreen(onBack: () -> Unit, onOpenReceipt: (Int) -> Unit = {}) {
    var data by remember { mutableStateOf<DriverTaxiRidesDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true; error = false
        ApiClient.getDriverTaxiRides()
            .onSuccess { data = it }
            .onFailure { error = true }
        loading = false
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Мои поездки такси", "Такси сәфәрҙәрем"), onBack) },
    ) { padding ->
        val d = data
        // Список появляется разом и один раз: состояние живёт на уровне экрана, поэтому
        // прокрутка назад ничего не переигрывает (строки в LazyColumn пересоздаются).
        val reveal by animateFloatAsState(
            targetValue = if (d != null) 1f else 0f,
            animationSpec = tween(CanonMotion.SLOW),
            label = "taxiRidesReveal",
        )
        val enter = Modifier.graphicsLayer {
            alpha = reveal
            translationY = (1f - reveal) * 16.dp.toPx()
        }

        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        ) {
            when {
                loading && d == null -> {
                    item(key = "skeleton-total") { SkeletonCard(lines = 3) }
                    item(key = "skeleton-rides") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            repeat(5) { SkeletonCard(lines = 2) }
                        }
                    }
                }
                error && d == null -> item(key = "error") { AppErrorState(onRetry = { reload++ }) }
                d == null || d.rides.isEmpty() -> item(key = "empty") {
                    AppEmptyState(
                        title = appText("Поездок пока нет", "Әлегә сәфәр юҡ"),
                        text = appText(
                            "Здесь появится расшифровка по каждой поездке: цена, наша комиссия и сколько остаётся тебе.",
                            "Бында һәр сәфәр буйынса яҙма күренәсәк: хаҡ, беҙҙең комиссия һәм һиңә күпме ҡала.",
                        ),
                        icon = Icons.Default.DirectionsCar,
                    )
                }
                else -> {
                    if (error) item(key = "stale") { MoneyStaleStrip(onRetry = { reload++ }) }
                    item(key = "totals") { TaxiRidesTotalsCard(d, enter) }
                    item(key = "rides-header") {
                        MoneySectionHeader(
                            title = appText("Каждая поездка", "Һәр сәфәр"),
                            caption = appText(
                                "Цена пассажиру, наша комиссия и сколько осталось тебе.",
                                "Юлаусыға хаҡ, беҙҙең комиссия һәм һиңә күпме ҡалғаны.",
                            ),
                            modifier = enter,
                        )
                    }
                    items(d.rides, key = { it.orderId }) { ride ->
                        TaxiRideRow(ride, enter, onClick = { onOpenReceipt(ride.orderId) })
                    }
                    item(key = "note") {
                        Text(
                            appText(
                                "Комиссию мы не удерживаем из твоих денег: пассажир платит тебе целиком, а комиссия копится долгом и платится отдельно.",
                                "Комиссияны һинең аҡсанан тотоп ҡалмайбыҙ: юлаусы һиңә тулыһынса түләй, комиссия айырым бурыс булып йыйыла.",
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

// ─────────────────── Итоги ───────────────────

/**
 * Итоги как арифметика, а не как три отдельных числа: заплатили − комиссия = тебе.
 * Именно эту цепочку водитель и хочет проверить, когда сомневается в сумме.
 */
@Composable
private fun TaxiRidesTotalsCard(d: DriverTaxiRidesDto, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    appText("Чистыми за последние поездки", "Һуңғы сәфәрҙәр өсөн таҙа"),
                    color = CanonMuted, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine,
                )
                // Та же сумма, что в строке «Осталось тебе» ниже — считаем её одним хелпером,
                // иначе итог и расшифровка расходятся на копейки и доверия экрану нет.
                Text(
                    kopToRub(d.totalNetKop),
                    color = CanonText,
                    fontSize = MoneyType.Hero,
                    lineHeight = MoneyType.HeroLine,
                    letterSpacing = MoneyType.HeroTracking,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MoneyLine(
                    Icons.Default.Payments, CanonMint, CanonGreen2,
                    appText("Пассажиры заплатили", "Юлаусылар түләне"),
                    "${fmtRub(d.totalPriceRub)} ₽",
                    CanonText,
                )
                MoneyLine(
                    Icons.Default.Percent, CanonWarnBg, CanonWarn,
                    appText("Комиссия Юлдаша", "Юлдаш комиссияһы"),
                    "− " + kopToRub(d.totalFeeKop),
                    CanonWarn,
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(CanonHairlineGreen))
                MoneyLine(
                    Icons.Default.Savings, CanonMint, CanonGreen2,
                    appText("Осталось тебе", "Һиңә ҡалды"),
                    kopToRub(d.totalNetKop),
                    CanonGreen2,
                )
            }
        }
    }
}

// ─────────────────── Одна поездка ───────────────────

/**
 * Одна поездка. Главная цифра строки — «тебе», потому что именно её водитель ищет глазами;
 * под ней лежит проверяемая арифметика (цена и комиссия), а справа — стрелка: карточка
 * открывает чек, и раньше об этом ничто не говорило.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaxiRideRow(r: DriverTaxiRideDto, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AppCard(modifier = modifier, onClick = onClick, shape = CanonItemShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${r.from.ifBlank { "—" }} → ${r.to.ifBlank { "—" }}",
                        color = CanonText, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatDepart(r.doneAt),
                        color = CanonMuted, fontSize = MoneyType.Caption, lineHeight = MoneyType.CaptionLine,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        kopToRub(r.netKop),
                        color = CanonGreen2, fontSize = MoneyType.Value, lineHeight = MoneyType.ValueLine,
                        fontWeight = FontWeight.Bold, maxLines = 1,
                    )
                    Text(
                        appText("тебе", "һиңә"),
                        color = CanonMuted, fontSize = MoneyType.Caption, lineHeight = MoneyType.CaptionLine,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = appText("Открыть чек поездки", "Сәфәр чеген асыу"),
                    tint = CanonMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            // Проверяемая арифметика: две колонки, чтобы длинный башкирский переносился,
            // а не выдавливал сумму за край.
            Surface(color = CanonBg, shape = CanonItemShape) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    TaxiLedgerCell(
                        label = appText("Цена", "Хаҡ"),
                        value = "${fmtRub(r.priceRub)} ₽",
                        valueColor = CanonMutedStrong,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    TaxiLedgerCell(
                        label = appText("Комиссия", "Комиссия"),
                        value = "− " + kopToRub(r.feeKop),
                        valueColor = CanonWarn,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TaxiRideTag(
                    if (r.paid) appText("Оплачено", "Түләнгән") else appText("Не отмечено", "Билдәләнмәгән"),
                    if (r.paid) CanonMint else CanonWarnBg,
                    if (r.paid) CanonGreen2 else CanonWarn,
                )
                if (r.paymentMethod.isNotBlank()) {
                    TaxiRideTag(payMethodLabel(r.paymentMethod), CanonMint, CanonGreen2)
                }
                feeStatusTag(r.feeStatus)?.let { (label, bg, tint) ->
                    TaxiRideTag(label, bg, tint)
                }
            }
        }
    }
}

@Composable
private fun TaxiLedgerCell(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = CanonMuted, fontSize = MoneyType.Caption, lineHeight = MoneyType.CaptionLine)
        // Без maxLines: сумму переносим, но не обрезаем. Обрезанные деньги — худшее, что
        // может показать экран, который человек открыл, чтобы себя перепроверить.
        Text(
            value, color = valueColor, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun TaxiRideTag(label: String, bg: Color, tint: Color) {
    Surface(color = bg, shape = RoundedCornerShape(999.dp)) {
        Text(
            label, color = tint, fontSize = MoneyType.Caption, lineHeight = MoneyType.CaptionLine,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** Статус комиссии по поездке. none = долга нет (промо 0% / грошовый заказ) → метку не рисуем. */
@Composable
private fun feeStatusTag(status: String): Triple<String, Color, Color>? = when (status) {
    "pending" -> Triple(appText("Комиссия не оплачена", "Комиссия түләнмәгән"), CanonWarnBg, CanonWarn)
    "declared" -> Triple(appText("Оплату проверяем", "Түләүҙе тикшерәбеҙ"), CanonWarnBg, CanonWarn)
    "paid" -> Triple(appText("Комиссия оплачена", "Комиссия түләнгән"), CanonMint, CanonGreen2)
    "void" -> Triple(appText("Комиссия списана", "Комиссия һүндерелгән"), CanonMint, CanonGreen2)
    else -> null
}
