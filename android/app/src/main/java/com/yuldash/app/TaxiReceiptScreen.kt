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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
internal fun TaxiReceiptScreen(
    orderId: Int,
    onBack: () -> Unit,
    onOpenChat: (Int) -> Unit = {},
    initialReceipt: InstantReceiptDto? = null,
    loadRemote: Boolean = true,
) {
    var receipt by remember(orderId, initialReceipt) { mutableStateOf(initialReceipt) }
    var loading by remember(orderId, initialReceipt) { mutableStateOf(initialReceipt == null && loadRemote) }
    var errorStatus by remember(orderId) { mutableStateOf<Int?>(null) }   // null = нет ошибки; 409 = ещё не завершена
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(orderId, reload, loadRemote) {
        if (!loadRemote) return@LaunchedEffect
        if (orderId <= 0) { loading = false; errorStatus = -1; return@LaunchedEffect }
        loading = true; errorStatus = null
        ApiClient.getInstantReceipt(orderId)
            .onSuccess { receipt = it; errorStatus = null }
            .onFailure { errorStatus = (it as? ApiException)?.status ?: -1 }
        loading = false
    }

    val context = LocalContext.current
    val shareChooser = appText("Поделиться чеком", "Чек менән бүлешеү")
    val shareTitle = appText("Юлдаш · Чек за поездку", "Юлдаш · Сәфәр чегы")
    val shareAmount = appText("Сумма", "Сумма")
    val shareDriver = appText("Водитель", "Йөрөтөүсе")
    val sharePayment = receipt?.let { payMethodLabel(it.paymentMethod) }.orEmpty()
    val shareOrder = receipt?.let { appText("Заказ № ${it.orderId}", "Заказ № ${it.orderId}") }.orEmpty()
    val shareText = receipt?.let { r ->
        remember(r, shareTitle, shareAmount, shareDriver, shareOrder) {
            buildString {
                appendLine(shareTitle)
                appendLine(shareOrder)
                appendLine("${r.fromText.ifBlank { "—" }} → ${r.toText.ifBlank { "—" }}")
                appendLine(formatDepart(r.doneAt))
                appendLine("$shareAmount: ${kopToRub(r.amountKop)} · $sharePayment")
                if (r.driverName.isNotBlank()) appendLine("$shareDriver: ${r.driverName}")
            }
        }
    }
    val onShare = {
        shareText?.let { shareRide(context, it, shareChooser) }
        Unit
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { TaxiReceiptTopBar(onBack = onBack, onShare = onShare.takeIf { shareText != null }) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(CanonSpace.md),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val r = receipt
            when {
                loading -> TaxiReceiptSkeleton()
                errorStatus == 409 -> TaxiReceiptPendingCard()
                r == null -> AppErrorState(onRetry = { reload++ })
                else -> {
                    TaxiReceiptCard(r)
                    TaxiAfterRideActions(
                        r,
                        onOpenChat = onOpenChat,
                        onPaidLocally = { reload++ },
                        onShare = onShare,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaxiReceiptTopBar(onBack: () -> Unit, onShare: (() -> Unit)?) {
    TopAppBar(
        title = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(appText("Детали поездки", "Сәфәр ентеклектәре"), style = CanonHeading, color = CanonText)
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = appText("Назад", "Артҡа"), tint = CanonText)
            }
        },
        actions = {
            IconButton(onClick = { onShare?.invoke() }, enabled = onShare != null) {
                Icon(
                    Icons.Default.IosShare,
                    contentDescription = appText("Поделиться чеком", "Чек менән бүлешеү"),
                    tint = if (onShare != null) CanonText else CanonMuted,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = CanonBg),
    )
}


// ─────────────────── Карточка чека ───────────────────

@Composable
private fun TaxiReceiptCard(r: InstantReceiptDto) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TaxiReceiptDocument(r, modifier = Modifier.appearIn(0))
        TaxiReceiptCompactRating(r, modifier = Modifier.appearIn(1))
    }
}

@Composable
private fun TaxiReceiptDocument(r: InstantReceiptDto, modifier: Modifier = Modifier) {
    val paidSuffix = if (r.paid) appText(" · Оплачено", " · Түләнгән") else ""
    val payment = payMethodLabel(r.paymentMethod) + paidSuffix
    val distanceText = if (r.distanceKm > 0) {
        String.format(java.util.Locale.US, "%.1f", r.distanceKm).replace('.', ',') +
            " " + appText("км", "км")
    } else ""
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = CanonSurface,
        shape = CanonCardShape,
        border = BorderStroke(1.dp, CanonBorder),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(CanonSpace.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
        ) {
            Surface(shape = CircleShape, color = CanonMint) {
                Row(
                    Modifier.padding(horizontal = CanonSpace.md, vertical = CanonSpace.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                    Text(appText("Поездка завершена", "Сәфәр тамамланды"), style = CanonBodyStrong, color = CanonGreen2)
                }
            }
            Text(kopToRub(r.amountKop), style = CanonDisplay, color = CanonText)
            Text(payment, style = CanonBody, color = CanonMuted, textAlign = TextAlign.Center)

            TaxiReceiptHairline()
            TaxiReceiptBreakdown(r)
            if (r.waitingFeeKop > 0) {
                TaxiReceiptLine(
                    appText("Ожидание", "Көтөү"),
                    kopToRub(r.waitingFeeKop),
                    CanonWarn,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(appText("Итого", "Бөтәһе"), style = CanonBodyStrong, color = CanonText, modifier = Modifier.weight(1f))
                Text(kopToRub(r.amountKop), style = CanonHeading, color = CanonTaxiText)
            }

            TaxiReceiptHairline()
            MobilityRouteTimeline(
                from = r.fromText.ifBlank { appText("Точка отправления", "Китеү нөктәһе") },
                to = r.toText.ifBlank { appText("Точка назначения", "Барыу нөктәһе") },
                compact = true,
            )
            TaxiReceiptHairline()

            val meta = listOf(formatDepart(r.doneAt).takeIf { r.doneAt.isNotBlank() }, distanceText.takeIf { it.isNotBlank() })
                .filterNotNull().joinToString("  ·  ")
            if (meta.isNotBlank()) {
                Text(meta, style = CanonCaption, color = CanonMuted, modifier = Modifier.fillMaxWidth())
            }
            if (r.driverName.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.driverName, style = CanonBodyStrong, color = CanonText)
                    if (r.driverVerified) {
                        Spacer(Modifier.width(CanonSpace.xs))
                        Icon(
                            Icons.Default.Verified,
                            contentDescription = appText("Водитель проверен", "Йөрөтөүсе тикшерелгән"),
                            tint = CanonGreen2,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(appText("Заказ № ${r.orderId}", "Заказ № ${r.orderId}"), style = CanonMicro, color = CanonMuted)
                }
            }
            Text(
                appText(
                    "Деньги идут напрямую водителю — Юлдаш их не держит.",
                    "Аҡса туранан-тура йөрөтөүсегә бара — Юлдаш уны тотмай.",
                ),
                style = CanonMicro,
                color = CanonMuted,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TaxiReceiptCompactRating(r: InstantReceiptDto, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var stars by remember(r.orderId, r.myStars) { mutableIntStateOf(r.myStars) }
    var pending by remember(r.orderId) { mutableIntStateOf(0) }
    var busy by remember(r.orderId) { mutableStateOf(false) }
    var error by remember(r.orderId) { mutableStateOf<String?>(null) }
    val fail = appText("Не получилось сохранить оценку.", "Баһаны һаҡлап булманы.")

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
    ) {
        Text(
            if (r.role == "driver") appText("Оценить пассажира", "Пассажирҙы баһалау")
            else appText("Оценить водителя", "Йөрөтөүсене баһалау"),
            style = CanonBody,
            color = CanonMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            (1..5).forEach { value ->
                val shown = pending.takeIf { it > 0 } ?: stars
                Box(
                    Modifier.size(48.dp).clickable(enabled = !busy) {
                        pending = value
                        busy = true
                        scope.launch {
                            ApiClient.rateInstantOrder(r.orderId, value, r.myRatingTags)
                                .onSuccess { stars = value; pending = 0; error = null }
                                .onFailure { pending = 0; error = serverSaid(it, fail) }
                            busy = false
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (value <= shown) Icons.Default.Star else Icons.Outlined.StarOutline,
                        contentDescription = starsText(value),
                        tint = if (value <= shown) CanonStar else CanonMuted,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }
        if (stars > 0 && error == null) {
            Text(appText("Твоя оценка сохранена", "Һинең баһаң һаҡланды"), style = CanonMicro, color = CanonGreen2)
        }
        if (error != null) Text(error.orEmpty(), style = CanonMicro, color = CanonRed)
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
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
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
/** Строка счёта: слева за что, справа сколько. Одна строка — одна причина, по которой
 *  сумма стала такой. */
@Composable
private fun TaxiReceiptLine(label: String, value: String, accent: Color = CanonText,
                            hint: String = "") {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label, color = CanonMuted,
                fontSize = MoneyType.Caption, lineHeight = MoneyType.CaptionLine,
                modifier = Modifier.weight(1f),
            )
            Text(
                value, color = accent,
                fontSize = MoneyType.Caption, lineHeight = MoneyType.CaptionLine,
                fontWeight = FontWeight.Bold,
            )
        }
        if (hint.isNotBlank()) {
            Text(hint, color = CanonMuted, fontSize = MoneyType.Caption, lineHeight = MoneyType.CaptionLine)
        }
    }
}

/** Разбивка суммы: поездка, наценка, дорога водителя, опции — и, если чек смотрит водитель,
 *  зеркальный расчёт с комиссией.
 *
 *  Пассажиру комиссию НЕ показываем: в нашей модели он платит водителю напрямую, наши 15%
 *  через него не проходят, и строка «комиссия платформы» была бы неправдой о его деньгах. */
@Composable
private fun TaxiReceiptBreakdown(r: InstantReceiptDto) {
    // Старый сервер разбивки не шлёт — тогда показывать нечего, и блока просто нет.
    if (r.ridePrice <= 0) return

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
        TaxiReceiptLine(
            appText("Поездка", "Сәфәр"),
            "${r.rideBasePrice.takeIf { it > 0 } ?: r.ridePrice} ₽",
        )
        if (r.surgeRub > 0) {
            TaxiReceiptLine(
                appText("Наценка за спрос", "Ихтыяж өҫтәмәһе"),
                "+${r.surgeRub} ₽", CanonWarn,
            )
        }
        if (r.pickupFeeKop > 0) {
            val km = kotlin.math.round(r.pickupKm).toInt()
            TaxiReceiptLine(
                appText("Дорога водителя к тебе, ~$km км", "Водителдең һиңә тиклем юлы, ~$km км"),
                "+" + kopToRub(r.pickupFeeKop), CanonGreen2,
                hint = if (r.pickupEnroute)
                    appText("Ему было по пути — вдвое дешевле", "Уға юл ыңғайы ине — ике тапҡыр арзаныраҡ")
                else appText("Уходит водителю целиком", "Тулыһынса водителгә бара"),
            )
        }
        if (r.weatherFeeKop > 0) {
            val weather = when (r.weatherKind) {
                "ice" -> appText("Гололёд на дороге", "Юлда быҙлауыҡ")
                "blizzard" -> appText("Метель по пути", "Юлда буран")
                "snow" -> appText("Сильный снегопад", "Көслө ҡар яуа")
                "frost" -> appText("Сильный мороз", "Ҡаты һыуыҡ")
                else -> appText("Тяжёлая дорога", "Ауыр юл")
            }
            TaxiReceiptLine(
                weather, "+" + kopToRub(r.weatherFeeKop), CanonGreen2,
                hint = appText("Уходит водителю целиком", "Тулыһынса водителгә бара"),
            )
        }
        if (r.optionsFeeKop > 0) {
            TaxiReceiptLine(
                appText("Кресло и опции", "Ултырғыс һәм өҫтәмәләр"),
                "+" + kopToRub(r.optionsFeeKop), CanonGreen2,
                hint = appText("Уходит водителю целиком", "Тулыһынса водителгә бара"),
            )
        }
        if (r.promoDiscountKop > 0) {
            TaxiReceiptLine(
                appText("Скидка Юлдаша", "Юлдаш ташламаһы"),
                "−" + kopToRub(r.promoDiscountKop),
                CanonGreen2,
                hint = appText(
                    "Водитель получил полную сумму — скидку оплатил Юлдаш",
                    "Йөрөтөүсе тулы сумманы алды — ташламаны Юлдаш түләне",
                ),
            )
        }
        if (r.role == "driver" && r.driverGrossKop > 0) {
            val feeLabel = if (r.driverFeePercent % 1.0 == 0.0) r.driverFeePercent.toInt().toString()
            else String.format(java.util.Locale.US, "%.1f", r.driverFeePercent)
            Box(Modifier.fillMaxWidth().height(1.dp).background(CanonBorder))
            TaxiReceiptLine(
                appText("Всего от пассажира", "Пассажирҙан барлығы"),
                kopToRub(r.driverGrossKop),
            )
            TaxiReceiptLine(
                // Процент печатаем тут же: «8» вместо «8.0», а дробный — с одним знаком.
                // Тянуть ради этого приватный хелпер из соседнего экрана незачем.
                appText("Комиссия Юлдаша $feeLabel%", "Юлдаш комиссияһы $feeLabel%"),
                "−" + kopToRub(r.driverFeeKop), CanonRed,
                hint = if (r.commissionFreeKop > 0)
                    appText(
                        "С ${kopToRub(r.commissionFreeKop)} комиссию не берём — это твой бензин и кресло",
                        "${kopToRub(r.commissionFreeKop)} суммаһынан комиссия алмайбыҙ — был һинең бензин һәм ултырғыс",
                    )
                else "",
            )
            TaxiReceiptLine(
                appText("Чистыми тебе", "Һиңә таҙа килем"),
                kopToRub(r.driverNetKop), CanonGreen2,
            )
        }
    }
}

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
    onShare: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isDriver = r.role == "driver"
    var thanked by remember(r.orderId) { mutableStateOf(false) }
    var thanksBusy by remember(r.orderId) { mutableStateOf(false) }
    var cashBusy by remember(r.orderId) { mutableStateOf(false) }
    var lostBusy by remember(r.orderId) { mutableStateOf(false) }
    var lostOpened by remember(r.orderId) { mutableStateOf(false) }
    var showProblemChoice by remember(r.orderId) { mutableStateOf(false) }
    var showReport by remember(r.orderId) { mutableStateOf(false) }
    var showDispute by remember(r.orderId) { mutableStateOf(false) }
    var disputeFiled by remember(r.orderId) { mutableStateOf(false) }
    var errText by remember(r.orderId) { mutableStateOf<String?>(null) }
    var successText by remember(r.orderId) { mutableStateOf<String?>(null) }

    val errFallback = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    val thanksSentMessage = appText("Спасибо передано водителю", "Рәхмәт йөрөтөүсегә тапшырылды")
    val reportSentMessage = appText("Спасибо. Мы проверим обращение.", "Рәхмәт. Мөрәжәғәтте тикшерәсәкбеҙ.")
    val disputeSentMessage = appText("Разбор открыт. Мы сообщим о решении.", "Ҡарау асылды. Ҡарар тураһында хәбәр итербеҙ.")
    val counterpartyFallback = appText("Участник поездки", "Сәфәрҙә ҡатнашыусы")

    // Уже сказал «рәхмәт» раньше — узнаём у сервера, чтобы не предлагать второй раз.
    LaunchedEffect(r.orderId, isDriver) {
        if (!isDriver) ApiClient.getInstantTipInfo(r.orderId).onSuccess { thanked = it.alreadyThanked }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            appText("Ещё можно", "Тағы мөмкин"),
            color = CanonText,
            fontSize = MoneyType.Value,
            lineHeight = MoneyType.ValueLine,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp),
        )

        // Пассажир: тёплое спасибо. Денег не двигаем — это жест, а не чаевые.
        if (!isDriver) {
            TaxiReceiptActionRow(
                icon = if (thanked) Icons.Default.CheckCircle else Icons.Default.Favorite,
                title = if (thanked) appText("«Рәхмәт» сказан", "Рәхмәт әйтелде")
                else appText("Сказать «рәхмәт»", "Рәхмәт әйтеү"),
                text = if (thanked) appText("Водитель получил твоё спасибо", "Йөрөтөүсе һинең рәхмәтеңде алды")
                else appText("Тёплое спасибо водителю — без денег", "Йөрөтөүсегә йылы рәхмәт — аҡсаһыҙ"),
                busy = thanksBusy,
                enabled = !thanked,
                onClick = {
                    if (thanksBusy || thanked) return@TaxiReceiptActionRow
                    thanksBusy = true
                    scope.launch {
                        ApiClient.sayInstantThanks(r.orderId)
                            .onSuccess {
                                thanked = true
                                errText = null
                                successText = thanksSentMessage
                            }
                            .onFailure { errText = (it as? ApiException)?.message ?: errFallback }
                        thanksBusy = false
                    }
                },
            )
        }

        // Водитель: отметить наличные. Без этой кнопки заказ навсегда «не оплачен», если
        // пассажир вышел и закрыл приложение (аудит 2026-07-26).
        if (isDriver && !r.paid) {
            AppCard(modifier = Modifier.appearIn(4)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

        TaxiReceiptActionRow(
            icon = Icons.Default.IosShare,
            title = appText("Поделиться чеком", "Чек менән бүлешеү"),
            text = appText("Отправить маршрут и сумму поездки", "Сәфәр юлын һәм суммаһын ебәреү"),
            onClick = onShare,
        )

        // Забытая вещь — обеим сторонам. Телефон второй стороны после поездки скрыт,
        // а чат был только на чтение: телефон с заднего сиденья терялся навсегда.
        TaxiReceiptActionRow(
            icon = if (lostOpened) Icons.Default.ChatBubble else Icons.Default.Search,
            title = if (lostOpened) appText("Открыть чат поездки", "Сәфәр чатын асыу")
            else appText("Забыли вещь?", "Әйбер онотолдомо?"),
            text = if (lostOpened)
                appText("Чат открыт на 48 часов", "Чат 48 сәғәткә асылды")
            else appText("Связаться по этой поездке", "Был сәфәр буйынса бәйләнешеү"),
            busy = lostBusy,
            onClick = {
                if (lostOpened) {
                    onOpenChat(r.orderId)
                } else if (!lostBusy) {
                    lostBusy = true
                    scope.launch {
                        ApiClient.instantLostItem(r.orderId)
                            .onSuccess {
                                lostOpened = true
                                errText = null
                                onOpenChat(r.orderId)
                            }
                            .onFailure { errText = (it as? ApiException)?.message ?: errFallback }
                        lostBusy = false
                    }
                }
            },
        )

        TaxiReceiptActionRow(
            icon = Icons.Outlined.ReportProblem,
            title = appText("Проблема с поездкой", "Сәфәр менән проблема"),
            text = if (disputeFiled) appText("Разбор уже открыт", "Ҡарау асылған")
            else appText("Сообщить или открыть разбор", "Хәбәр итеү йәки ҡарау асыу"),
            onClick = { showProblemChoice = true },
        )

        // Пассажир может завершить оплату прямо из чека, если закрыл финальный экран.
        if (!isDriver && !r.paid) {
            PayOnlineCard(
                amountKop = r.amountKop,
                pay = { method -> ApiClient.payInstantOrder(r.orderId, method) },
            )
        }

        AnimatedVisibility(
            visible = successText != null,
            enter = fadeIn(tween(CanonMotion.QUICK)) + expandVertically(tween(CanonMotion.QUICK)),
            exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.QUICK)),
        ) {
            Surface(color = CanonMint, shape = CanonItemShape) {
                Text(
                    successText.orEmpty(), color = CanonGreen2, fontSize = MoneyType.Body,
                    lineHeight = MoneyType.BodyLine, fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = errText != null,
            enter = fadeIn(tween(CanonMotion.QUICK)) + expandVertically(tween(CanonMotion.QUICK)),
            exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.QUICK)),
        ) {
            Surface(color = CanonDangerBg, shape = CanonItemShape) {
                Text(
                    errText.orEmpty(), color = CanonRed, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }

    if (showProblemChoice) {
        AlertDialog(
            onDismissRequest = { showProblemChoice = false },
            containerColor = CanonSurface,
            shape = CanonCardShape,
            title = {
                Text(
                    appText("Что случилось?", "Нимә булды?"),
                    color = CanonText,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TaxiReceiptActionRow(
                        icon = Icons.Outlined.ReportProblem,
                        title = appText("Сообщить о нарушении", "Боҙоу тураһында хәбәр итеү"),
                        text = appText("Анонимно, проверит человек", "Аноним, кеше тикшерәсәк"),
                        onClick = { showProblemChoice = false; showReport = true },
                    )
                    if (r.counterpartyId > 0) {
                        TaxiReceiptActionRow(
                            icon = Icons.Default.Verified,
                            title = appText("Открыть разбор", "Ҡарауҙы асыу"),
                            text = appText("Выслушаем обе стороны", "Ике яҡты ла тыңлаясаҡбыҙ"),
                            onClick = { showProblemChoice = false; showDispute = true },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showProblemChoice = false }) {
                    Text(appText("Закрыть", "Ябыу"), color = CanonGreen2)
                }
            },
        )
    }

    if (showReport) {
        ReportCategoryDialog(
            title = appText("Сообщить о нарушении", "Боҙоу тураһында хәбәр итеү"),
            categories = if (isDriver) reportCategoriesPassenger() else reportCategoriesDriver(),
            onDismiss = { showReport = false },
            onSend = { category, details ->
                showReport = false
                scope.launch {
                    ApiClient.reportUser(reason = details, category = category, orderId = r.orderId)
                        .onSuccess {
                            errText = null
                            successText = reportSentMessage
                        }
                        .onFailure { errText = (it as? ApiException)?.message ?: errFallback }
                }
            },
        )
    }

    if (showDispute && r.counterpartyId > 0) {
        FileIncidentDialog(
            respondentId = r.counterpartyId,
            respondentName = r.counterpartyName.ifBlank { counterpartyFallback },
            orderId = r.orderId,
            onDismiss = { showDispute = false },
            onFiled = {
                showDispute = false
                disputeFiled = true
                successText = disputeSentMessage
            },
        )
    }
}

@Composable
private fun TaxiReceiptActionRow(
    icon: ImageVector,
    title: String,
    text: String,
    onClick: () -> Unit,
    busy: Boolean = false,
    enabled: Boolean = true,
) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.64f, label = "receipt-action-alpha")
    Surface(
        modifier = Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha },
        shape = CanonItemShape,
        color = CanonSurface,
        border = BorderStroke(1.dp, CanonBorder),
        onClick = onClick,
        enabled = enabled && !busy,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(
                    icon,
                    contentDescription = title,
                    tint = CanonGreen2,
                    modifier = Modifier.padding(10.dp).size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    color = CanonText,
                    fontSize = MoneyType.Body,
                    lineHeight = MoneyType.BodyLine,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (busy) appText("Подожди…", "Көт…") else text,
                    color = CanonMuted,
                    fontSize = MoneyType.Caption,
                    lineHeight = MoneyType.CaptionLine,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = CanonMuted,
                modifier = Modifier.size(20.dp),
            )
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
                fontWeight = FontWeight.Bold,
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
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Text(
                appText("Он появится после завершения поездки. Хорошей дороги!", "Ул сәфәр тамамланғас барлыҡҡа килер. Юлың уң булһын!"),
                color = CanonMuted, fontSize = MoneyType.Body, lineHeight = MoneyType.BodyLine, textAlign = TextAlign.Center,
            )
        }
    }
}
