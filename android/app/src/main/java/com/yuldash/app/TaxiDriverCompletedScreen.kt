package com.yuldash.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.InstantOrderDto
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Финал поездки глазами таксиста — утверждённый вариант A «доход прежде всего».
 *
 * Деньги и оценка живут на одном коротком экране, но финансовый документ не дублируется:
 * подтверждение наличных, маршрут, спор и остальные восстановительные действия остаются
 * в [TaxiReceiptScreen]. Так экран быстро отвечает «сколько заработал», а чек — «почему».
 */
@Composable
internal fun TaxiDriverCompletedScreen(
    order: InstantOrderDto,
    onReturnToLine: () -> Unit,
    onShiftFinished: () -> Unit,
    onOpenReceipt: () -> Unit,
    rateOrder: suspend (Int, Int, String) -> Result<Unit> = { id, stars, tags ->
        ApiClient.rateInstantOrder(id, stars, tags)
    },
    finishShift: suspend () -> Result<Unit> = { ApiClient.setOnline(false) },
) {
    val scope = rememberCoroutineScope()
    var stars by rememberSaveable(order.id) { mutableIntStateOf(0) }
    var selectedTags by rememberSaveable(order.id) { mutableStateOf(emptyList<String>()) }
    var ratingSending by rememberSaveable(order.id) { mutableStateOf(false) }
    var ratingSent by rememberSaveable(order.id) { mutableStateOf(false) }
    var shiftEnding by rememberSaveable(order.id) { mutableStateOf(false) }
    var errorText by remember(order.id) { mutableStateOf<String?>(null) }

    val rateFail = appText(
        "Не получилось отправить оценку. Проверь сеть и повтори.",
        "Баһаны ебәреп булманы. Селтәрҙе тикшереп ҡабатла.",
    )
    val shiftFail = appText(
        "Не получилось завершить смену. Проверь сеть и повтори.",
        "Сменаны тамамлап булманы. Селтәрҙе тикшереп ҡабатла.",
    )

    Scaffold(
        containerColor = CanonBg,
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(CanonBg)
                    .navigationBarsPadding()
                    .padding(start = CanonSpace.lg, top = CanonSpace.sm, end = CanonSpace.lg, bottom = CanonSpace.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(CanonSpace.xs),
            ) {
                AppButton(
                    text = appText("Вернуться на линию", "Линияға ҡайтыу"),
                    onClick = onReturnToLine,
                    enabled = !ratingSending && !shiftEnding,
                    height = 56.dp,
                )
                TextButton(
                    onClick = {
                        if (ratingSending || shiftEnding) return@TextButton
                        shiftEnding = true
                        errorText = null
                        scope.launch {
                            finishShift()
                                .onSuccess { onShiftFinished() }
                                .onFailure { errorText = serverSaid(it, shiftFail) }
                            shiftEnding = false
                        }
                    },
                    enabled = !ratingSending && !shiftEnding,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    if (shiftEnding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = CanonGreen2,
                        )
                    } else {
                        Text(
                            appText("Завершить смену", "Сменаны тамамлау"),
                            style = CanonBodyStrong,
                            color = CanonGreen2,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CanonSpace.lg)
                .padding(bottom = CanonSpace.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
        ) {
            Text(
                appText("Поездка завершена", "Сәфәр тамамланды"),
                style = CanonHeading,
                color = CanonText,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = CanonSpace.lg, bottom = CanonSpace.sm)
                    .appearIn(0),
            )

            TaxiDriverIncomeHero(order, Modifier.appearIn(1))
            TaxiDriverMoneySummary(order, Modifier.appearIn(2))

            if (order.hasPromoDiscount) {
                Surface(
                    color = CanonMint,
                    shape = CanonItemShape,
                    border = BorderStroke(1.dp, CanonGreen2.copy(alpha = 0.18f)),
                    modifier = Modifier.fillMaxWidth().appearIn(3),
                ) {
                    Text(
                        appText(
                            "Скидку пассажира оплатил Юлдаш — твой доход не уменьшился.",
                            "Пассажирҙың ташламаһын Юлдаш түләне — һинең килемең кәмемәне.",
                        ),
                        style = CanonCaption,
                        color = CanonGreen2,
                        modifier = Modifier.padding(CanonSpace.md),
                    )
                }
            }

            TaxiDriverPassengerRating(
                order = order,
                stars = stars,
                selectedTags = selectedTags,
                sending = ratingSending,
                sent = ratingSent,
                onStars = { value ->
                    stars = value
                    selectedTags = emptyList()
                    errorText = null
                },
                onTag = { code ->
                    selectedTags = if (code in selectedTags) selectedTags - code else selectedTags + code
                },
                onSubmit = {
                    if (stars <= 0 || ratingSending || ratingSent) return@TaxiDriverPassengerRating
                    ratingSending = true
                    errorText = null
                    scope.launch {
                        rateOrder(order.id, stars, selectedTags.joinToString(","))
                            .onSuccess { ratingSent = true }
                            .onFailure { errorText = serverSaid(it, rateFail) }
                        ratingSending = false
                    }
                },
                modifier = Modifier.appearIn(4),
            )

            AnimatedVisibility(visible = errorText != null, enter = fadeIn(), exit = fadeOut()) {
                Surface(color = CanonDangerBg, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        errorText.orEmpty(),
                        style = CanonCaption,
                        color = CanonRed,
                        modifier = Modifier.fillMaxWidth().padding(CanonSpace.md),
                    )
                }
            }

            Column(
                Modifier.fillMaxWidth().appearIn(5),
                verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
            ) {
                TaxiDriverCompletedActionRow(
                    icon = Icons.Default.ReceiptLong,
                    label = appText("Чек и детали", "Чек һәм ентеклектәр"),
                    description = if (order.paymentMethod == "cash") {
                        appText(
                            "Подтвердить наличные, маршрут и помощь",
                            "Ҡулаҡсаны раҫлау, юл һәм ярҙам",
                        )
                    } else {
                        appText("Сумма, маршрут и помощь", "Сумма, юл һәм ярҙам")
                    },
                    onClick = onOpenReceipt,
                )
                UnpaidReportButton(orderId = order.id)
            }
        }
    }
}

@Composable
private fun TaxiDriverIncomeHero(order: InstantOrderDto, modifier: Modifier = Modifier) {
    val hasBreakdown = order.driverGrossKop > 0
    val amount = if (hasBreakdown) order.driverNetKop else order.passengerPayKop
    Surface(
        color = CanonMint,
        shape = CanonCardShape,
        border = BorderStroke(1.dp, CanonGreen2.copy(alpha = 0.22f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = CanonSpace.xl, vertical = CanonSpace.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
        ) {
            Surface(shape = CircleShape, color = CanonGreen2, modifier = Modifier.size(48.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = CanonOnFilled,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Text(
                if (hasBreakdown) appText("Чистыми за поездку", "Сәфәрҙән таҙа килем")
                else appText("Получено за поездку", "Сәфәр өсөн алынды"),
                style = CanonBodyStrong,
                color = CanonGreen2,
                textAlign = TextAlign.Center,
            )
            Text(
                formatTaxiKop(amount),
                style = CanonDisplay,
                color = CanonText,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TaxiDriverMoneySummary(order: InstantOrderDto, modifier: Modifier = Modifier) {
    val hasBreakdown = order.driverGrossKop > 0
    Surface(
        color = CanonSurface,
        shape = CanonCardShape,
        border = BorderStroke(1.dp, CanonBorder),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaxiDriverMoneyCell(
                label = appText("Заплатил", "Түләне"),
                value = formatTaxiKop(order.passengerPayKop),
                modifier = Modifier.weight(1f),
            )
            TaxiDriverMoneyDivider()
            TaxiDriverMoneyCell(
                label = appText("Комиссия", "Комиссия"),
                value = if (hasBreakdown) formatTaxiKop(order.driverFeeKop) else "—",
                modifier = Modifier.weight(1f),
            )
            TaxiDriverMoneyDivider()
            TaxiDriverMoneyCell(
                label = appText("Оплата", "Түләү"),
                value = taxiDriverPayMethodShortLabel(order.paymentMethod),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun taxiDriverPayMethodShortLabel(method: String): String = when (method) {
    "cash" -> appText("Наличные", "Ҡулаҡса")
    "sbp" -> appText("СБП", "СБП")
    else -> appText("По договору", "Килешеү буйынса")
}

@Composable
private fun TaxiDriverMoneyCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = CanonSpace.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CanonSpace.xs),
    ) {
        Text(label, style = CanonMicro, color = CanonMuted, textAlign = TextAlign.Center, maxLines = 2)
        Text(
            value,
            style = CanonBodyStrong,
            color = CanonText,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TaxiDriverMoneyDivider() {
    Box(Modifier.width(1.dp).height(64.dp).background(CanonBorder))
}

@Composable
private fun TaxiDriverPassengerRating(
    order: InstantOrderDto,
    stars: Int,
    selectedTags: List<String>,
    sending: Boolean,
    sent: Boolean,
    onStars: (Int) -> Unit,
    onTag: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = CanonSurface,
        shape = CanonCardShape,
        border = BorderStroke(1.dp, CanonBorder),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(CanonSpace.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
        ) {
            TaxiDriverPassengerIdentity(order)
            AnimatedContent(
                targetState = sent,
                transitionSpec = {
                    (fadeIn(tween(CanonMotion.NORMAL)) + scaleIn(initialScale = 0.97f)) togetherWith
                        (fadeOut(tween(CanonMotion.QUICK)) + scaleOut(targetScale = 0.97f))
                },
                label = "driverPassengerRatingResult",
            ) { completed ->
                if (completed) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 148.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = CanonGreen2,
                            modifier = Modifier.size(44.dp),
                        )
                        Spacer(Modifier.size(CanonSpace.sm))
                        Text(appText("Спасибо за оценку", "Баһа өсөн рәхмәт"), style = CanonTitle, color = CanonText)
                        Text(starsText(stars), style = CanonCaption, color = CanonMuted)
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
                    ) {
                        Text(
                            appText(
                                "Как прошла поездка с пассажиром?",
                                "Пассажир менән сәфәр нисек үтте?",
                            ),
                            style = CanonTitle,
                            color = CanonText,
                            textAlign = TextAlign.Center,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                            (1..5).forEach { value ->
                                val selected = value <= stars
                                val scale by animateFloatAsState(
                                    if (selected) 1f else 0.88f,
                                    tween(CanonMotion.QUICK),
                                    label = "driverCompletedStar$value",
                                )
                                Box(
                                    Modifier.size(48.dp).clickable(enabled = !sending) { onStars(value) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        if (selected) Icons.Default.Star else Icons.Outlined.StarOutline,
                                        contentDescription = starsText(value),
                                        tint = if (selected) CanonStar else CanonMuted,
                                        modifier = Modifier.size(38.dp).graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                        },
                                    )
                                }
                            }
                        }
                        if (stars > 0) {
                            val tags = taxiDriverRatingTags(stars)
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
                            ) {
                                tags.forEach { tag ->
                                    val selected = tag.code in selectedTags
                                    Surface(
                                        onClick = { onTag(tag.code) },
                                        enabled = !sending,
                                        color = if (selected) CanonMint else CanonBg,
                                        shape = CanonItemShape,
                                        border = BorderStroke(1.dp, if (selected) CanonGreen2 else CanonBorder),
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    ) {
                                        Box(
                                            Modifier.fillMaxSize().padding(horizontal = CanonSpace.xs, vertical = CanonSpace.sm),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                tag.label,
                                                style = CanonMicro,
                                                color = if (selected) CanonGreen2 else CanonText,
                                                textAlign = TextAlign.Center,
                                                maxLines = 2,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            appText(
                                "Оценка анонимна — пассажир увидит только средний рейтинг.",
                                "Баһа аноним — пассажир тик уртаса рейтингты күрер.",
                            ),
                            style = CanonMicro,
                            color = CanonMuted,
                            textAlign = TextAlign.Center,
                        )
                        AppButton(
                            text = appText("Отправить оценку", "Баһаны ебәреү"),
                            onClick = onSubmit,
                            style = AppButtonStyle.Secondary,
                            loading = sending,
                            enabled = stars > 0 && !sending,
                            height = 50.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaxiDriverPassengerIdentity(order: InstantOrderDto) {
    val name = order.passengerName.ifBlank { appText("Пассажир", "Пассажир") }
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "•"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CanonSpace.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = CanonMint, modifier = Modifier.size(64.dp)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(initial, style = CanonDisplay, color = CanonGreen2)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
            Text(name, style = CanonTitle, color = CanonText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                appText(
                    taxiDriverPassengerTrustRu(order),
                    taxiDriverPassengerTrustBa(order),
                ),
                style = CanonCaption,
                color = CanonMuted,
            )
        }
    }
}

private fun taxiDriverPassengerTrustRu(order: InstantOrderDto): String {
    val rating = order.passengerRating?.let { String.format(Locale.US, "%.1f", it).replace('.', ',') }
        ?: "Новичок"
    val trips = order.passengerTrips.coerceAtLeast(0)
    val word = when {
        trips % 100 in 11..14 -> "поездок"
        trips % 10 == 1 -> "поездка"
        trips % 10 in 2..4 -> "поездки"
        else -> "поездок"
    }
    return "$rating · $trips $word"
}

private fun taxiDriverPassengerTrustBa(order: InstantOrderDto): String {
    val rating = order.passengerRating?.let { String.format(Locale.US, "%.1f", it).replace('.', ',') }
        ?: "Яңы кеше"
    return "$rating · ${order.passengerTrips.coerceAtLeast(0)} сәфәр"
}

private data class TaxiDriverRatingTag(val code: String, val label: String)

@Composable
private fun taxiDriverRatingTags(stars: Int): List<TaxiDriverRatingTag> = if (stars >= 4) {
    listOf(
        TaxiDriverRatingTag("polite", appText("Вежливо", "Әҙәпле")),
        TaxiDriverRatingTag("ontime", appText("Вовремя", "Ваҡытында")),
        TaxiDriverRatingTag("safe", appText("Бережно", "Һаҡсыл")),
    )
} else {
    listOf(
        TaxiDriverRatingTag("late", appText("Опоздание", "Һуңланы")),
        TaxiDriverRatingTag("rude", appText("Грубость", "Ҡаты мөғәмәлә")),
        TaxiDriverRatingTag("unsafe", appText("Неаккуратно", "Һаҡһыҙ")),
    )
}

@Composable
private fun TaxiDriverCompletedActionRow(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, CanonBorder),
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = CanonSpace.md, vertical = CanonSpace.sm),
            horizontalArrangement = Arrangement.spacedBy(CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = CanonMint, modifier = Modifier.size(44.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(22.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                Text(label, style = CanonBodyStrong, color = CanonText)
                Text(
                    description,
                    style = CanonMicro,
                    color = CanonMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(22.dp))
        }
    }
}
