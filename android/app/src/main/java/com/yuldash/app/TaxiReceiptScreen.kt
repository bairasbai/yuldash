package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.InstantReceiptDto
import kotlinx.coroutines.launch

/*
 * ════════════════════════════════════════════════════════════════════════════
 *  Чек за поездку на такси — GET /instant/orders/{id}/receipt
 * ════════════════════════════════════════════════════════════════════════════
 *  Раньше чека за такси не существовало вообще: «дай справку о поездке на работу» —
 *  дать нечего, а в споре «я заплатил / он не заплатил» не было ни одной записи
 *  (аудит 2026-07-26). Телефонов в чеке нет — только факт, маршрут, сумма, способ.
 *
 *  Экран же закрывает ещё два пробела «после поездки»:
 *   • «Я забыл вещь в машине» → чат заказа снова открыт на запись 48 часов (обе стороны);
 *   • «Сказать рәхмәт» водителю (пассажир, без денег);
 *   • «Наличные получил» (водитель) — если пассажир вышел и не отметил оплату сам.
 *
 *  Вид: документ. Шапка — факт и номер заказа, таблица — детали, карточка суммы — одна
 *  крупная цифра. Типографика — четыре ступени [MoneyType], как на всех денежных экранах.
 */

@Composable
internal fun TaxiReceiptScreen(orderId: Int, onBack: () -> Unit, onOpenChat: (Int) -> Unit = {}) {
    var receipt by remember(orderId) { mutableStateOf<InstantReceiptDto?>(null) }
    var loading by remember(orderId) { mutableStateOf(true) }
    var errorStatus by remember(orderId) { mutableStateOf<Int?>(null) }   // null = нет ошибки; 409 = ещё не завершена
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(orderId, reload) {
        if (orderId <= 0) { loading = false; errorStatus = -1; return@LaunchedEffect }
        loading = true; errorStatus = null
        ApiClient.getInstantReceipt(orderId)
            .onSuccess { receipt = it; errorStatus = null }
            .onFailure { errorStatus = (it as? ApiException)?.status ?: -1 }
        loading = false
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Чек за поездку", "Сәфәр чегы"), onBack) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val r = receipt
            when {
                loading -> TaxiReceiptSkeleton()
                errorStatus == 409 -> TaxiReceiptPendingCard()
                r == null -> AppErrorState(onRetry = { reload++ })
                else -> {
                    TaxiReceiptCard(r)
                    TaxiAfterRideActions(r, onOpenChat = onOpenChat, onPaidLocally = { reload++ })
                }
            }
        }
    }
}

// ─────────────────── Карточка чека ───────────────────

@Composable
private fun TaxiReceiptCard(r: InstantReceiptDto) {
    val ctx = LocalContext.current
    val payLabel = payMethodLabel(r.paymentMethod)
    val route = "${r.fromText.ifBlank { "—" }} → ${r.toText.ifBlank { "—" }}"
    val orderLabel = appText("Заказ № ${r.orderId}", "Заказ № ${r.orderId}")
    val shareChooser = appText("Поделиться чеком", "Чек менән бүлешеү")
    // Строки для шеринга считаем ЗАРАНЕЕ: appText — @Composable, внутри buildString его не позвать.
    val shTitle = appText("Юлдаш · Чек за поездку", "Юлдаш · Сәфәр чегы")
    val shAmount = appText("Сумма", "Сумма")
    val shDriver = appText("Водитель", "Йөрөтөүсе")
    val shareText = buildString {
        appendLine(shTitle)
        appendLine(orderLabel)
        appendLine(route)
        appendLine(formatDepart(r.doneAt))
        appendLine("$shAmount: ${fmtRub(r.amount)} ₽ · $payLabel")
        if (r.driverName.isNotBlank()) appendLine("$shDriver: ${r.driverName}")
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TaxiReceiptHeader(route = route, orderLabel = orderLabel)

        // Таблица деталей. Маршрут не повторяем — он уже крупно стоит в шапке.
        AppCard(modifier = Modifier.appearIn(1)) {
            Column(Modifier.padding(vertical = 4.dp)) {
                TaxiReceiptLine(Icons.Default.Schedule, appText("Дата и время", "Көн һәм ваҡыт"), formatDepart(r.doneAt))
                if (r.distanceKm > 0) {
                    TaxiReceiptHairline()
                    TaxiReceiptLine(
                        Icons.Default.Route,
                        appText("Расстояние", "Ара"),
                        // Дробь пишем через запятую: и по-русски, и по-башкирски «12,5 км».
                        // Locale.US тут нужен только чтобы число не поехало на чужом телефоне —
                        // вид разделителя задаём сами, а не отдаём на волю системной локали.
                        String.format(java.util.Locale.US, "%.1f", r.distanceKm).replace('.', ',') +
                            " " + appText("км", "км"),
                    )
                }
                if (r.driverName.isNotBlank()) {
                    TaxiReceiptHairline()
                    TaxiReceiptDriverLine(r.driverName, r.driverVerified)
                }
            }
        }

        // Сумма + способ оплаты. Одна крупная цифра на экран — она и есть ответ на вопрос
        // «сколько с меня взяли».
        AppCard(modifier = Modifier.appearIn(2)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        appText("Сумма поездки", "Сәфәр суммаһы"),
                        color = CanonMuted, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine,
                        modifier = Modifier.weight(1f),
                    )
                    if (r.paid) {
                        Spacer(Modifier.width(12.dp))
                        Surface(shape = RoundedCornerShape(999.dp), color = CanonMint) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    appText("Оплачено", "Түләнгән"),
                                    color = CanonGreen2, fontSize = MoneyType.Caption, fontWeight = FontWeight.Black, maxLines = 1,
                                )
                            }
                        }
                    }
                }
                Text(
                    "${fmtRub(r.amount)} ₽",
                    color = CanonText,
                    fontSize = MoneyType.Hero,
                    lineHeight = MoneyType.HeroLine,
                    letterSpacing = MoneyType.HeroTracking,
                    fontWeight = FontWeight.Black,
                )
                // Платное ожидание показываем отдельной строкой — иначе «почему больше, чем в оценке?».
                if (r.waitingFeeKop > 0) {
                    TaxiReceiptNote(
                        Icons.Default.Schedule, CanonWarn,
                        appText("В том числе ожидание: ", "Шул иҫәптән көтөү: ") + kopToRub(r.waitingFeeKop),
                    )
                }
                TaxiReceiptNote(Icons.Default.Payments, CanonMuted, payLabel)
                Text(
                    appText(
                        "Это запись о поездке. Деньги идут напрямую водителю — Юлдаш их не держит.",
                        "Был — сәфәр яҙмаһы. Аҡса туранан-тура водителгә бара — Юлдаш уны тотмай.",
                    ),
                    color = CanonMuted, fontSize = MoneyType.Caption, lineHeight = MoneyType.CaptionLine,
                )
            }
        }

        AppButton(
            text = appText("Поделиться", "Бүлешеү"),
            onClick = { shareRide(ctx, shareText, shareChooser) },
            modifier = Modifier.appearIn(3),
            style = AppButtonStyle.Secondary,
            icon = Icons.Default.IosShare,
        )
    }
}

/** Шапка-«печать»: тёмно-зелёный ink фиксированный, поэтому белый текст читаем в обеих темах. */
@Composable
private fun TaxiReceiptHeader(route: String, orderLabel: String) {
    // Печать «поездка завершена» проявляется с лёгким отскоком. Два подвоха, оба поймал глазами:
    //  • AnimatedVisibility(visible = true) не анимирует НИЧЕГО — на первом кадре состояние уже
    //    конечное, поэтому включаем анимацию после первой композиции;
    //  • появление через AnimatedVisibility добавляло бы 56dp к высоте шапки на втором кадре —
    //    место под кружок держим всегда, меняется только масштаб и прозрачность.
    var stampShown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { stampShown = true }
    val stamp by animateFloatAsState(
        targetValue = if (stampShown) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "receiptStamp",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .appearIn(0)
            .background(Brush.verticalGradient(listOf(CanonGreenInk, CanonGreenInkDark)), CanonCardShape)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .graphicsLayer {
                    val s = 0.6f + 0.4f * stamp
                    scaleX = s
                    scaleY = s
                    alpha = stamp.coerceIn(0f, 1f)
                }
                .background(CanonOnAccent.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonOnAccent, modifier = Modifier.size(32.dp))
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                appText("Поездка завершена", "Сәфәр тамамланды"),
                color = CanonOnAccent, fontSize = MoneyType.Value, lineHeight = MoneyType.ValueLine,
                fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
            )
            Text(
                route,
                color = CanonOnAccent.copy(alpha = 0.92f), fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine,
                textAlign = TextAlign.Center,
            )
            Text(
                orderLabel,
                color = CanonOnAccent.copy(alpha = 0.7f), fontSize = MoneyType.Caption, lineHeight = MoneyType.CaptionLine,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ─────────────────── Строки документа ───────────────────
// Свои, а не общие с квитанцией попутки: там значение 15sp, а на денежных экранах
// размеров ровно четыре (MoneyType) и пятому взяться неоткуда.

@Composable
private fun TaxiReceiptLine(icon: ImageVector, label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = CanonMint, shape = CircleShape) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        // Ширину уступает подпись, а не значение: длинный башкирский перенесётся,
        // но дата или расстояние не «уедут» за край.
        Text(
            label, color = CanonMuted, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value, color = CanonText, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun TaxiReceiptDriverLine(name: String, verified: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = CanonMint, shape = CircleShape) {
            Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            appText("Водитель", "Йөрөтөүсе"),
            color = CanonMuted, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        if (verified) {
            Icon(
                Icons.Default.Verified,
                contentDescription = appText("Проверен", "Тикшерелгән"),
                tint = CanonGreen2,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            name, color = CanonText, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TaxiReceiptHairline() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(CanonHairlineGreen))
}

/** Тихая строка под суммой: иконка + пояснение (ожидание, способ оплаты). */
@Composable
private fun TaxiReceiptNote(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text, color = CanonMutedStrong, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ─────────────────── Что можно сделать после поездки ───────────────────

/**
 * Блок действий после поездки. Разный для сторон:
 *  • пассажир → «Сказать рәхмәт» (без денег, идемпотентно на сервере);
 *  • водитель → «Наличные получил», если оплата так и не отмечена;
 *  • обе стороны → «Я забыл вещь в машине» (чат снова открыт на 48 часов).
 */
@Composable
private fun TaxiAfterRideActions(
    r: InstantReceiptDto,
    onOpenChat: (Int) -> Unit,
    onPaidLocally: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isDriver = r.role == "driver"
    var thanked by remember(r.orderId) { mutableStateOf(false) }
    var thanksBusy by remember(r.orderId) { mutableStateOf(false) }
    var cashBusy by remember(r.orderId) { mutableStateOf(false) }
    var lostBusy by remember(r.orderId) { mutableStateOf(false) }
    var lostOpened by remember(r.orderId) { mutableStateOf(false) }
    var errText by remember(r.orderId) { mutableStateOf<String?>(null) }

    val errFallback = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")

    // Уже сказал «рәхмәт» раньше — узнаём у сервера, чтобы не предлагать второй раз.
    LaunchedEffect(r.orderId, isDriver) {
        if (!isDriver) ApiClient.getInstantTipInfo(r.orderId).onSuccess { thanked = it.alreadyThanked }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Пассажир: тёплое спасибо. Денег не двигаем — это жест, а не чаевые.
        if (!isDriver) {
            AppCard(modifier = Modifier.appearIn(4)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TaxiActionHead(
                        icon = Icons.Default.Favorite,
                        iconBg = CanonMint,
                        iconTint = CanonGreen2,
                        title = if (thanked) appText("Рәхмәт сказан 💚", "Рәхмәт әйтелде 💚")
                        else appText("Сказать рәхмәт", "Рәхмәт әйтеү"),
                        text = appText("Тёплое спасибо водителю — без денег.", "Водителгә йылы рәхмәт — аҡсаһыҙ."),
                    )
                    if (!thanked) {
                        AppButton(
                            text = appText("Сказать рәхмәт", "Рәхмәт әйтеү"),
                            onClick = {
                                if (thanksBusy) return@AppButton
                                thanksBusy = true
                                scope.launch {
                                    ApiClient.sayInstantThanks(r.orderId)
                                        .onSuccess { thanked = true; errText = null }
                                        .onFailure { errText = (it as? ApiException)?.message ?: errFallback }
                                    thanksBusy = false
                                }
                            },
                            style = AppButtonStyle.Accent,
                            icon = Icons.Default.Favorite,
                            loading = thanksBusy,
                        )
                    }
                }
            }
        }

        // Водитель: отметить наличные. Без этой кнопки заказ навсегда «не оплачен», если
        // пассажир вышел и закрыл приложение (аудит 2026-07-26).
        if (isDriver && !r.paid) {
            AppCard(modifier = Modifier.appearIn(4)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TaxiActionHead(
                        icon = Icons.Default.Payments,
                        iconBg = CanonWarnBg,
                        iconTint = CanonWarn,
                        title = appText("Оплата не отмечена", "Түләү билдәләнмәгән"),
                        text = appText(
                            "Если деньги на руках — отметь. Так поездка закроется честно, а в отчёте не будет дыры.",
                            "Аҡса ҡулда булһа — билдәлә. Шунда сәфәр намыҫлы ябыла, отчётта тишек ҡалмай.",
                        ),
                    )
                    AppButton(
                        text = appText("Наличные получил", "Аҡсаны алдым"),
                        onClick = {
                            if (cashBusy) return@AppButton
                            cashBusy = true
                            scope.launch {
                                ApiClient.instantCashReceived(r.orderId)
                                    .onSuccess { errText = null; onPaidLocally() }
                                    .onFailure { errText = (it as? ApiException)?.message ?: errFallback }
                                cashBusy = false
                            }
                        },
                        icon = Icons.Default.Payments,
                        loading = cashBusy,
                    )
                }
            }
        }

        // Забытая вещь — обеим сторонам. Телефон второй стороны после поездки скрыт,
        // а чат был только на чтение: телефон с заднего сиденья терялся навсегда.
        AppCard(modifier = Modifier.appearIn(5)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TaxiActionHead(
                    icon = Icons.Default.Search,
                    iconBg = CanonWarnBg,
                    iconTint = CanonWarn,
                    title = appText("Забыли вещь?", "Әйбер онотолдомо?"),
                    text = if (lostOpened)
                        appText("Чат снова открыт на 48 часов — напиши, что искать.", "Чат 48 сәғәткә кире асыҡ — нимә эҙләргә, яҙ.")
                    else
                        appText("Откроем чат этой поездки на 48 часов, чтобы вы связались.", "Бәйләнешер өсөн был сәфәр чатын 48 сәғәткә асабыҙ."),
                )
                if (lostOpened) {
                    AppButton(
                        text = appText("Открыть чат поездки", "Сәфәр чатын асыу"),
                        onClick = { onOpenChat(r.orderId) },
                        style = AppButtonStyle.Secondary,
                    )
                } else {
                    AppButton(
                        text = appText("Я забыл вещь в машине", "Машинала әйбер ҡалдырҙым"),
                        onClick = {
                            if (lostBusy) return@AppButton
                            lostBusy = true
                            scope.launch {
                                ApiClient.instantLostItem(r.orderId)
                                    .onSuccess { lostOpened = true; errText = null }
                                    .onFailure { errText = (it as? ApiException)?.message ?: errFallback }
                                lostBusy = false
                            }
                        },
                        style = AppButtonStyle.Secondary,
                        icon = Icons.Default.Search,
                        loading = lostBusy,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = errText != null,
            enter = fadeIn(tween(200)) + expandVertically(tween(200)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
        ) {
            Surface(color = CanonDangerBg, shape = CanonItemShape) {
                Text(
                    errText.orEmpty(), color = CanonRed, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

/** Шапка карточки-действия: кружок с иконкой + заголовок и пояснение. */
@Composable
private fun TaxiActionHead(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    title: String,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = iconBg, shape = CircleShape) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.padding(12.dp).size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title, color = CanonText, fontSize = MoneyType.Value, lineHeight = MoneyType.ValueLine,
                fontWeight = FontWeight.Black,
            )
            Text(text, color = CanonMuted, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine)
        }
    }
}

// ─────────────────── Состояния ───────────────────

@Composable
private fun TaxiReceiptSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SkeletonBox(widthFraction = 1f, height = 148.dp, shape = CanonCardShape)
        SkeletonCard(lines = 3)
        SkeletonCard(lines = 3)
    }
}

/** 409: поездка ещё не завершена — спокойный текст без тревоги. */
@Composable
private fun TaxiReceiptPendingCard() {
    AppCard(modifier = Modifier.appearIn(0)) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(16.dp).size(28.dp))
            }
            Text(
                appText("Чек ещё не готов", "Чек әҙер түгел"),
                color = CanonText, fontSize = MoneyType.Value, lineHeight = MoneyType.ValueLine,
                fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
            )
            Text(
                appText("Он появится после завершения поездки. Хорошей дороги!", "Ул сәфәр тамамланғас барлыҡҡа килер. Юлың уң булһын!"),
                color = CanonMuted, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine, textAlign = TextAlign.Center,
            )
        }
    }
}
