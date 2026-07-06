package com.yuldash.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.LedgerEntryDto
import com.yuldash.app.data.PayResultDto
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Деньги v1 (Фаза 3, UI). Два блока:
 *  1) [PayTripCard] — «Оплатить поездку» ПОСЛЕ завершения (done). Наличные / Карта / СБП.
 *     Переиспользуемый: подставляешь `pay = { m -> ApiClient.payBooking(id, m) }` (бронь) или
 *     `ApiClient.payInstantOrder(id, m)` (быстрый заказ — когда его экран появится).
 *  2) [WalletScreen] — кошелёк водителя: баланс + история начислений/комиссий понятным языком.
 *
 * Суммы храним и считаем в копейках (Int), показываем в рублях аккуратно ([rubFromKop]). Без float.
 */

/** Копейки → «1 234 ₽» или «22,50 ₽» (копейки показываем только если они есть). Знак сохраняем. */
internal fun rubFromKop(kop: Int): String {
    val neg = kop < 0
    val a = abs(kop)
    val rub = a / 100
    val kops = a % 100
    val grouped = rub.toString().reversed().chunked(3).joinToString(" ").reversed()
    val body = if (kops == 0) "$grouped ₽" else "$grouped,%02d ₽".format(kops)
    return if (neg) "−$body" else body
}

// ─────────────────────────── 1. Оплата поездки ───────────────────────────

private enum class PayStage { Idle, PaidCash, PaidOnline, Waiting }

private data class PayMethod(val code: String, val ru: String, val ba: String, val hintRu: String, val hintBa: String, val icon: ImageVector)

/**
 * Карточка оплаты завершённой поездки. Показывай только пассажиру и только при статусе done.
 *
 * @param amountRub сумма к оплате в рублях (для показа; итог всё равно считает сервер). null → скрыть сумму.
 * @param pay функция оплаты выбранным способом (cash|card|sbp) — обычно замыкание на ApiClient.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PayTripCard(
    amountRub: Int?,
    pay: suspend (String) -> Result<PayResultDto>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()

    var stage by remember { mutableStateOf(PayStage.Idle) }
    var showSheet by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf("card") }
    var busy by remember { mutableStateOf(false) }
    var lastMethod by remember { mutableStateOf("") }

    val errMsg = appText("Не получилось оплатить. Проверь сеть и попробуй ещё.", "Түләп булманы. Селтәрҙе тикшереп ҡабатла.")
    val notYetMsg = appText("Оплата ещё не подтверждена. Как оплатишь — вернись сюда.", "Түләү әле раҫланманы. Түләгәс — бында кире ҡайт.")

    // Один общий обработчик результата оплаты (первичная оплата и «Проверить»).
    fun handle(method: String, res: PayResultDto) {
        lastMethod = method
        when {
            method == "cash" -> stage = PayStage.PaidCash
            res.isPaid -> stage = PayStage.PaidOnline
            !res.confirmationUrl.isNullOrBlank() -> {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(res.confirmationUrl))) }
                stage = PayStage.Waiting
            }
            else -> stage = PayStage.Waiting
        }
    }

    AppCard(modifier = modifier) {
        AnimatedContent(
            targetState = stage,
            transitionSpec = { (fadeIn(tween(220)) togetherWith fadeOut(tween(160))) },
            label = "payStage",
        ) { st ->
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (st) {
                    PayStage.Idle -> {
                        PayHeader(
                            icon = Icons.Default.AccountBalanceWallet,
                            tint = CanonGreen2,
                            bg = CanonMint,
                            title = appText("Оплата поездки", "Сәфәр түләүе"),
                            subtitle = appText("Поездка завершена — можно рассчитаться.", "Сәфәр тамам — иҫәпләшергә мөмкин."),
                        )
                        if (amountRub != null && amountRub > 0) {
                            Text(rubFromKop(amountRub * 100), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 26.sp)
                        }
                        AppButton(
                            text = appText("Оплатить поездку", "Сәфәрҙе түләү"),
                            onClick = { showSheet = true },
                            icon = Icons.Default.CreditCard,
                        )
                    }
                    PayStage.PaidCash -> PaySuccess(
                        title = appText("Оплатишь наличными", "Нәҡзләй түләйһең"),
                        text = appText("Передай сумму водителю в поездке. Спасибо!", "Аҡсаны сәфәрҙә водителгә тапшыр. Рәхмәт!"),
                    )
                    PayStage.PaidOnline -> PaySuccess(
                        title = appText("Оплачено", "Түләнде"),
                        text = appText("Спасибо! Деньги ушли водителю.", "Рәхмәт! Аҡса водителгә китте."),
                    )
                    PayStage.Waiting -> {
                        PayHeader(
                            icon = Icons.Default.HourglassBottom,
                            tint = CanonGold,
                            bg = CanonWarnBg,
                            title = appText("Ждём подтверждения оплаты", "Түләүҙе раҫлауын көтәбеҙ"),
                            subtitle = appText("Заверши оплату в открывшемся окне и вернись сюда.", "Асылған тәҙрәлә түләүҙе тамамла һәм бында кире ҡайт."),
                        )
                        AppButton(
                            text = appText("Проверить оплату", "Түләүҙе тикшереү"),
                            onClick = {
                                if (busy) return@AppButton
                                busy = true
                                scope.launch {
                                    pay(lastMethod)
                                        .onSuccess { res ->
                                            busy = false
                                            if (res.isPaid) stage = PayStage.PaidOnline
                                            else Toast.makeText(context, notYetMsg, Toast.LENGTH_SHORT).show()
                                        }
                                        .onFailure { busy = false; Toast.makeText(context, errMsg, Toast.LENGTH_SHORT).show() }
                                }
                            },
                            loading = busy,
                        )
                    }
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { if (!busy) showSheet = false }, sheetState = sheetState, containerColor = CanonSurface) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(appText("Как оплатишь?", "Нисек түләйһең?"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 22.sp)
                if (amountRub != null && amountRub > 0) {
                    Text(
                        appText("К оплате ", "Түләргә ") + rubFromKop(amountRub * 100),
                        color = CanonMuted, fontSize = 14.sp,
                    )
                }
                PAY_METHODS.forEach { m ->
                    PayMethodRow(method = m, selected = selected == m.code, onSelect = { if (!busy) selected = m.code })
                }
                Spacer(Modifier.height(2.dp))
                AppButton(
                    text = appText("Продолжить", "Дауам итеү"),
                    onClick = {
                        if (busy) return@AppButton
                        busy = true
                        val method = selected
                        scope.launch {
                            pay(method)
                                .onSuccess { res ->
                                    busy = false
                                    showSheet = false
                                    handle(method, res)
                                }
                                .onFailure { busy = false; Toast.makeText(context, errMsg, Toast.LENGTH_SHORT).show() }
                        }
                    },
                    loading = busy,
                )
                Text(
                    appText("Наличные — рассчитаешься с водителем сам. Карта и СБП — безопасно через ЮKassa.",
                        "Нәҡзләй — водитель менән үҙең иҫәпләшәһең. Карта һәм СБП — ЮKassa аша хәүефһеҙ."),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
        }
    }
}

private val PAY_METHODS = listOf(
    PayMethod("cash", "Наличные", "Нәҡзләй", "Отдашь водителю в поездке", "Сәфәрҙә водителгә бирәһең", Icons.Default.Payments),
    PayMethod("card", "Банковская карта", "Банк картаһы", "Оплата онлайн через ЮKassa", "ЮKassa аша онлайн түләү", Icons.Default.CreditCard),
    PayMethod("sbp", "СБП", "СБП", "Быстрый платёж по QR", "QR буйынса тиҙ түләү", Icons.Default.Bolt),
)

@Composable
private fun PayHeader(icon: ImageVector, tint: Color, bg: Color, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = bg, shape = RoundedCornerShape(16.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(10.dp).size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp)
            Text(subtitle, color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun PaySuccess(title: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = CanonMint, shape = CircleShape) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(9.dp).size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp)
            Text(text, color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun PayMethodRow(method: PayMethod, selected: Boolean, onSelect: () -> Unit) {
    val labelCd = appText(method.ru, method.ba)
    Card(
        modifier = Modifier.fillMaxWidth().bounceClick(onSelect).semantics { contentDescription = labelCd },
        colors = CardDefaults.cardColors(containerColor = if (selected) CanonMint else CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) CanonGreen2 else CanonBorder),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp).height(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(method.icon, contentDescription = null, tint = if (selected) CanonGreen2 else CanonMuted, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(appText(method.ru, method.ba), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(appText(method.hintRu, method.hintBa), color = CanonMuted, fontSize = 12.sp, lineHeight = 15.sp)
            }
            if (selected) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(22.dp))
        }
    }
}

// ─────────────────────────── 2. Кошелёк водителя ───────────────────────────

/** Экран кошелька: баланс + история начислений/комиссий. Вход из кабинета водителя. */
@Composable
internal fun WalletScreen(onBack: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var balanceKop by remember { mutableStateOf(0) }
    var ledger by remember { mutableStateOf<List<LedgerEntryDto>>(emptyList()) }

    androidx.compose.runtime.LaunchedEffect(tick) {
        loading = true; error = false
        val bal = ApiClient.getWalletBalance()
        val led = ApiClient.getWalletLedger()
        if (bal.isSuccess && led.isSuccess) {
            balanceKop = bal.getOrNull()!!.balanceKop
            ledger = led.getOrNull().orEmpty()
        } else {
            error = true
        }
        loading = false
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Кошелёк", "Хамъян"), onBack) },
    ) { padding ->
        when {
            loading -> Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SkeletonCard(lines = 2)
                SkeletonCard(lines = 4)
            }
            error -> Column(Modifier.padding(padding).padding(16.dp)) {
                AppErrorState(onRetry = { tick++ })
            }
            else -> WalletContent(balanceKop = balanceKop, ledger = ledger, modifier = Modifier.padding(padding))
        }
    }
}

/** Чистый рендер кошелька (без сети) — тестируется на JVM. */
@Composable
internal fun WalletContent(balanceKop: Int, ledger: List<LedgerEntryDto>, modifier: Modifier = Modifier) {
    val earn = ledger.filter { it.kind == "earn" }.sumOf { it.amountKop }
    val fee = ledger.filter { it.kind == "fee" }.sumOf { it.amountKop }   // отрицательные
    val payout = ledger.filter { it.kind == "payout" }.sumOf { it.amountKop }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
    ) {
        item {
            // Баланс-герой
            Card(
                Modifier.fillMaxWidth().appearIn(0),
                colors = CardDefaults.cardColors(containerColor = CanonGreen2),
                shape = CanonCardShape,
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(appText("Твой баланс", "Балансың"), color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Text(rubFromKop(balanceKop), color = Color.White, fontWeight = FontWeight.Black, fontSize = 34.sp)
                    Text(
                        appText("Деньги за безналичные поездки. Наличные пассажиры отдают тебе напрямую.",
                            "Нәҡзһеҙ сәфәрҙәр өсөн аҡса. Нәҡзләйҙе пассажирҙар туранан-тура һиңә бирә."),
                        color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 16.sp,
                    )
                }
            }
        }
        if (ledger.isNotEmpty()) {
            item {
                // Разбор понятным языком
                AppCard(modifier = Modifier.appearIn(1)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        WalletSummaryRow(appText("Заработано", "Табылған"), rubFromKop(earn), CanonGreen2)
                        WalletSummaryRow(appText("Комиссия сервиса", "Хеҙмәт комиссияһы"), rubFromKop(fee), CanonRed)
                        if (payout != 0) WalletSummaryRow(appText("Выплачено", "Түләнгән"), rubFromKop(payout), CanonMuted)
                        Box(Modifier.fillMaxWidth().height(1.dp).background(CanonBorder))
                        WalletSummaryRow(appText("Итого", "Барлығы"), rubFromKop(balanceKop), CanonText, bold = true)
                    }
                }
            }
            item {
                Text(appText("История", "Тарих"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.appearIn(2))
            }
            items(ledger, key = { it.id }) { e ->
                LedgerRow(e)
            }
        } else {
            item {
                AppEmptyState(
                    title = appText("Пока пусто", "Әлегә буш"),
                    text = appText("Заверши поездки — начисления за безналичную оплату появятся здесь.",
                        "Сәфәрҙәрҙе тамамла — нәҡзһеҙ түләү өсөн килем бында күренә."),
                    icon = Icons.Default.AccountBalanceWallet,
                )
            }
        }
    }
}

@Composable
private fun WalletSummaryRow(label: String, value: String, valueColor: Color, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (bold) CanonText else CanonMuted, fontSize = if (bold) 16.sp else 14.sp, fontWeight = if (bold) FontWeight.Black else FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = if (bold) 17.sp else 15.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun LedgerRow(e: LedgerEntryDto) {
    val (title, ba) = when (e.kind) {
        "earn" -> "Начисление за поездку" to "Сәфәр өсөн килем"
        "fee" -> "Комиссия сервиса" to "Хеҙмәт комиссияһы"
        "payout" -> "Выплата" to "Түләү"
        else -> "Корректировка" to "Төҙәтмә"
    }
    val positive = e.amountKop >= 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, CanonBorder),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(appText(title, ba), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = shortDate(e.createdAt)
                if (sub.isNotBlank()) Text(sub, color = CanonMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                (if (positive) "+" else "") + rubFromKop(e.amountKop),
                color = if (positive) CanonGreen2 else CanonRed,
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
            )
        }
    }
}

/** ISO "2026-07-06T12:34:..." → "06.07.2026". Пустая/битая строка → "". */
private fun shortDate(iso: String): String {
    val date = iso.take(10)
    val p = date.split("-")
    return if (p.size == 3 && p[0].length == 4) "${p[2]}.${p[1]}.${p[0]}" else ""
}
