package com.yuldash.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.WalletBalanceDto
import com.yuldash.app.data.WalletLedgerEntryDto
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * «Кошелёк» — баланс водителя + история операций (ledger).
 * Крупная карточка баланса (₽) сверху; ниже — список операций: дата, назначение (note),
 * сумма со знаком и цветом (приход зелёным, списание — приглушённым).
 * Приватность: сервер всегда отдаёт только свои записи (по токену).
 * Состояния: загрузка (скелетоны) / ошибка (повтор) / пусто (дружелюбная заглушка).
 */
@Composable
internal fun WalletScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var balance by remember { mutableStateOf<WalletBalanceDto?>(null) }
    var ledger by remember { mutableStateOf<List<WalletLedgerEntryDto>>(emptyList()) }

    suspend fun load() {
        loading = true; error = false
        val balRes = ApiClient.getWalletBalance()
        val ledRes = ApiClient.getWalletLedger(50)
        // Баланс — обязателен для «шапки»; если и он, и история упали → это ошибка. Иначе показываем что есть.
        balRes.onSuccess { balance = it }
        ledRes.onSuccess { ledger = it }
        error = balRes.isFailure && ledRes.isFailure
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Кошелёк", "Янсыҡ"), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
        ) {
            item { WalletBalanceCard(balance, loading) }

            item {
                SectionHeader(
                    appText("История операций", "Операциялар тарихы"),
                    appText("Начисления за поездки и комиссии сервиса.", "Сәфәрҙәр өсөн килем һәм сервис комиссияһы."),
                )
            }

            when {
                loading && ledger.isEmpty() -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(4) { SkeletonCard(lines = 2) } }
                }
                error && ledger.isEmpty() -> item { AppErrorState(onRetry = { scope.launch { load() } }) }
                ledger.isEmpty() -> item {
                    AppEmptyState(
                        title = appText("Пока операций нет", "Операциялар әлегә юҡ"),
                        text = appText(
                            "Как появятся начисления за поездки — покажем их здесь.",
                            "Сәфәрҙәр өсөн килем булһа — бында күрһәтәбеҙ.",
                        ),
                        icon = Icons.Default.ReceiptLong,
                    )
                }
                else -> items(ledger, key = { it.id }) { entry -> WalletLedgerRow(entry) }
            }
        }
    }
}

/** Крупная карточка баланса. Фиксированный ink-зелёный градиент (белый текст читаем в обеих темах). */
@Composable
private fun WalletBalanceCard(balance: WalletBalanceDto?, loading: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().appearIn(0),
        shape = CanonCardShape,
        colors = CardDefaults.cardColors(containerColor = CanonGreenInk),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(CanonGreenInk, CanonGreenInkDark)))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color.White.copy(alpha = 0.18f), shape = CircleShape) {
                    Icon(
                        Icons.Default.AccountBalanceWallet, contentDescription = null,
                        tint = Color.White, modifier = Modifier.padding(11.dp).size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    appText("Баланс кошелька", "Янсыҡ балансы"),
                    color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
            }
            Text(
                if (loading && balance == null) "…" else "${fmtRub(balance?.balanceRub ?: 0)} ₽",
                color = Color.White, fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Black,
            )
            Text(
                appText("Доступно к выводу через СБП", "СБП аша сығарырға мөмкин"),
                color = Color.White.copy(alpha = 0.82f), fontSize = 13.sp, lineHeight = 17.sp,
            )
        }
    }
}

/** Строка истории: иконка направления, назначение + дата, сумма со знаком и цветом. */
@Composable
private fun WalletLedgerRow(e: WalletLedgerEntryDto) {
    // Направление: приход (amount ≥ 0) — зелёный «получено»; списание/комиссия (< 0) — приглушённый.
    val income = e.amountKop >= 0
    val amountColor = if (income) CanonGreen2 else CanonMutedStrong
    val sign = if (income) "+" else "−"
    val fallbackNote = ledgerKindLabel(e.kind)   // @Composable — считаем до ifBlank (в лямбду звать нельзя)
    val noteText = e.note.ifBlank { fallbackNote }
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(color = if (income) CanonMint else CanonBg, shape = CircleShape) {
                Icon(
                    if (income) Icons.Default.CallReceived else Icons.Default.CallMade,
                    contentDescription = if (income) appText("Приход", "Килем") else appText("Списание", "Сығым"),
                    tint = if (income) CanonGreen2 else CanonMutedStrong,
                    modifier = Modifier.padding(10.dp).size(20.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(noteText, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 19.sp)
                Text(formatDepart(e.createdAt), color = CanonMuted, fontSize = 12.sp)
            }
            Text(
                "$sign${fmtRub(abs(e.amountKop) / 100)} ₽",
                color = amountColor, fontWeight = FontWeight.Black, fontSize = 16.sp,
            )
        }
    }
}

/** Запасная подпись по kind, если сервер не прислал человекочитаемый note. */
@Composable
private fun ledgerKindLabel(kind: String): String = when (kind) {
    "earn" -> appText("Начисление за поездку", "Сәфәр өсөн килем")
    "fee" -> appText("Комиссия сервиса", "Сервис комиссияһы")
    "payout" -> appText("Выплата", "Түләү")
    else -> appText("Операция", "Операция")
}

/** Разряды пробелом: 12 500 ₽. */
internal fun fmtRub(n: Int): String = "%,d".format(n).replace(',', ' ')
