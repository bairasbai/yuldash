package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
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
    val shareChooser = appText("Поделиться чеком", "Чек менән бүлешеү")
    // Строки для шеринга считаем ЗАРАНЕЕ: appText — @Composable, внутри buildString его не позвать.
    val shTitle = appText("Юлдаш · Чек за поездку", "Юлдаш · Сәфәр чегы")
    val shAmount = appText("Сумма", "Сумма")
    val shDriver = appText("Водитель", "Йөрөтөүсе")
    val shareText = buildString {
        appendLine(shTitle)
        appendLine("${r.fromText.ifBlank { "—" }} → ${r.toText.ifBlank { "—" }}")
        appendLine(formatDepart(r.doneAt))
        appendLine("$shAmount: ${r.amount} ₽ · $payLabel")
        if (r.driverName.isNotBlank()) appendLine("$shDriver: ${r.driverName}")
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Шапка — фиксированный ink-зелёный (белый текст читаем в обеих темах).
        Surface(shape = CanonCardShape, color = Color.Transparent) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(CanonGreenInk, CanonGreenInkDark)), CanonCardShape)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedVisibility(visible = true, enter = scaleIn(tween(300)) + fadeIn()) {
                    Box(Modifier.size(56.dp).background(Color.White.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                }
                Text(appText("Поездка завершена", "Сәфәр тамамланды"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text(
                    "${r.fromText.ifBlank { "—" }} → ${r.toText.ifBlank { "—" }}",
                    color = Color.White.copy(alpha = 0.92f), fontSize = 15.sp, textAlign = TextAlign.Center,
                )
            }
        }

        AppCard {
            Column(Modifier.padding(4.dp)) {
                ReceiptRow(Icons.Default.Place, appText("Маршрут", "Маршрут"),
                    "${r.fromText.ifBlank { "—" }} → ${r.toText.ifBlank { "—" }}")
                ReceiptDivider()
                ReceiptRow(Icons.Default.Schedule, appText("Дата и время", "Көн һәм ваҡыт"), formatDepart(r.doneAt))
                if (r.distanceKm > 0) {
                    ReceiptDivider()
                    ReceiptRow(Icons.Default.Route, appText("Расстояние", "Ара"),
                        String.format(java.util.Locale.US, "%.1f км", r.distanceKm))
                }
                if (r.driverName.isNotBlank()) {
                    ReceiptDivider()
                    ReceiptDriverRow(r.driverName, r.driverVerified)
                }
            }
        }

        // Сумма + способ оплаты
        AppCard {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(appText("Сумма поездки", "Сәфәр суммаһы"), color = CanonMuted, fontSize = 13.sp)
                        Text("${r.amount} ₽", color = CanonText, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    }
                    if (r.paid) {
                        Surface(shape = RoundedCornerShape(999.dp), color = CanonMint) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(appText("Оплачено", "Түләнгән"), color = CanonGreen2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                // Платное ожидание показываем отдельной строкой — иначе «почему больше, чем в оценке?».
                if (r.waitingFeeKop > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            appText("В том числе ожидание: ", "Шул иҫәптән көтөү: ") + kopToRub(r.waitingFeeKop),
                            color = CanonMutedStrong, fontSize = 14.sp,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Payments, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(payLabel, color = CanonMutedStrong, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Text(
                    appText(
                        "Это запись о поездке. Деньги идут напрямую водителю — Юлдаш их не держит.",
                        "Был — сәфәр яҙмаһы. Аҡса туранан-тура водителгә бара — Юлдаш уны тотмай.",
                    ),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
        }

        AppButton(
            text = appText("Поделиться", "Бүлешеү"),
            onClick = { shareRide(ctx, shareText, shareChooser) },
            style = AppButtonStyle.Secondary,
            icon = Icons.Default.IosShare,
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
            AppCard {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = CanonMint, shape = CircleShape) {
                            Icon(Icons.Default.Favorite, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(10.dp).size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (thanked) appText("Рәхмәт сказан 💚", "Рәхмәт әйтелде 💚")
                                else appText("Сказать рәхмәт", "Рәхмәт әйтеү"),
                                color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp,
                            )
                            Text(
                                appText("Тёплое спасибо водителю — без денег.", "Водителгә йылы рәхмәт — аҡсаһыҙ."),
                                color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp,
                            )
                        }
                    }
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
            AppCard {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(appText("Оплата не отмечена", "Түләү билдәләнмәгән"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp)
                    Text(
                        appText(
                            "Если деньги на руках — отметь. Так поездка закроется честно, а в отчёте не будет дыры.",
                            "Аҡса ҡулда булһа — билдәлә. Шунда сәфәр намыҫлы ябыла, отчётта тишек ҡалмай.",
                        ),
                        color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
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
        AppCard {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = CanonWarnBg, shape = CircleShape) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = CanonWarn, modifier = Modifier.padding(10.dp).size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(appText("Забыли вещь?", "Әйбер онотолдомо?"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp)
                        Text(
                            if (lostOpened)
                                appText("Чат снова открыт на 48 часов — напиши, что искать.", "Чат 48 сәғәткә кире асыҡ — нимә эҙләргә, яҙ.")
                            else
                                appText("Откроем чат этой поездки на 48 часов, чтобы вы связались.", "Бәйләнешер өсөн был сәфәр чатын 48 сәғәткә асабыҙ."),
                            color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp,
                        )
                    }
                }
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

        errText?.let { msg ->
            Surface(color = CanonDangerBg, shape = CanonItemShape) {
                Text(
                    msg, color = CanonRed, fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                )
            }
        }
    }
}

// ─────────────────── Состояния ───────────────────

@Composable
private fun TaxiReceiptSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SkeletonBox(widthFraction = 1f, height = 120.dp, shape = CanonCardShape)
        SkeletonCard(lines = 4)
        SkeletonCard(lines = 3)
    }
}

/** 409: поездка ещё не завершена — спокойный текст без тревоги. */
@Composable
private fun TaxiReceiptPendingCard() {
    AppCard {
        Column(
            Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(16.dp).size(30.dp))
            }
            Text(appText("Чек ещё не готов", "Чек әҙер түгел"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp, textAlign = TextAlign.Center)
            Text(
                appText("Он появится после завершения поездки. Хорошей дороги!", "Ул сәфәр тамамланғас барлыҡҡа килер. Юлың уң булһын!"),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
            )
        }
    }
}
