package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.RideshareBankStatus
import com.yuldash.app.data.RideshareCardPayment
import com.yuldash.app.data.TripReceiptDto
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

internal data class CardPaymentRead(
    val payment: RideshareCardPayment?,
    val loading: Boolean,
    val error: Boolean,
    val refresh: () -> Unit,
) {
    fun replacesDirectPayment(receiptHasObligation: Boolean) =
        payment?.replacesDirectPayment(receiptHasObligation) ?: receiptHasObligation
}

@Composable
internal fun rememberRideshareCardPayment(
    receipt: TripReceiptDto?,
    generation: Long,
    canRead: () -> Boolean,
): CardPaymentRead {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val allowed by rememberUpdatedState(canRead)
    var payment by remember(receipt, generation) { mutableStateOf<RideshareCardPayment?>(null) }
    var loading by remember(receipt, generation) { mutableStateOf(false) }
    var error by remember(receipt, generation) { mutableStateOf(false) }
    var revision by remember(receipt, generation) { mutableIntStateOf(0) }

    val foreground = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(receipt, generation, foreground, revision) {
        if (receipt == null || !foreground || !allowed()) return@LaunchedEffect
        val requestRevision = revision
        loading = true
        error = false
        val read = ApiClient.rideshareCardPayment(receipt.bookingId, generation)
        if (!currentCoroutineContext().isActive || !allowed() || requestRevision != revision) return@LaunchedEffect
        read.onSuccess { payment = it }.onFailure {
            if ((it as? ApiException)?.status in setOf(401, 403, 404)) payment = null
            error = true
        }
        loading = false
    }
    return CardPaymentRead(payment, loading, error) {
        if (receipt != null && allowed() && !loading) revision++
    }
}

@Composable
internal fun RideshareCardPaymentSummary(read: CardPaymentRead, isDriver: Boolean, actions: @Composable () -> Unit = {}) {
    val payment = read.payment?.takeIf { it.hasOperation }
    AppCard {
        Column(Modifier.padding(CanonSpace.md), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Text(appText("Оплата картой", "Карта менән түләү"), style = CanonBodyStrong, color = CanonText)
            if (payment != null) {
                Text(bankPaymentStatus(payment.payment), style = CanonBody, color = CanonText)
                (payment.paidKop.takeIf { it > 0 } ?: payment.amountKop)?.let { kop ->
                    Text(formatTaxiKop(kop), style = CanonHeading, color = CanonText)
                }
                if (payment.refundCoverage != "none") {
                    val label = if (payment.refundCoverage == "full") appText("Полный возврат", "Тулыһынса ҡайтарылды")
                    else appText("Частичный возврат", "Өлөшләтә ҡайтарылды")
                    Text("$label · ${formatTaxiKop(payment.refundedKop)}", style = CanonBody, color = CanonText)
                }
                settlementStatus(payment.refund, refund = true)?.let { Text(it, style = CanonBody, color = CanonText) }
                if (isDriver) {
                    settlementStatus(payment.payout, refund = false)?.let { Text(it, style = CanonBody, color = CanonText) }
                    payment.paidOutKop?.takeIf { it > 0 }?.let { kop ->
                        val label = appText("Выплачено", "Түләнде")
                        Text("$label · ${formatTaxiKop(kop)}", style = CanonBody, color = CanonText)
                    }
                    payment.payoutRemainingKop?.takeIf { it > 0 }?.let { kop ->
                        val label = appText("Ожидает выплаты", "Түләү көтә")
                        Text("$label · ${formatTaxiKop(kop)}", style = CanonBody, color = CanonText)
                    }
                }
            } else if (!read.error && !read.loading) {
                Text(appText("Статус оплаты уточняется", "Түләү торошо асыҡлана"), style = CanonBody, color = CanonText)
            }
            if (read.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (read.error) Text(
                appText("Статус не обновился. Проверь связь и повтори.", "Торош яңырманы. Бәйләнеште тикшереп ҡабатла."),
                style = CanonBody, color = CanonText,
            )
            TextButton(onClick = read.refresh, enabled = !read.loading) {
                Text(appText("Обновить статус", "Торошто яңыртырға"))
            }
            actions()
        }
    }
}

@Composable
private fun bankPaymentStatus(status: RideshareBankStatus): String = when (status) {
    RideshareBankStatus.Queued -> appText("Оплата ожидает обработки", "Түләү эшкәртеүҙе көтә")
    RideshareBankStatus.WaitingBank -> appText("Ждём подтверждения банка", "Банктың раҫлауын көтәбеҙ")
    RideshareBankStatus.Reconciling -> appText("Проверяем результат оплаты", "Түләү һөҙөмтәһен тикшерәбеҙ")
    RideshareBankStatus.Succeeded -> appText("Банк подтвердил оплату", "Банк түләүҙе раҫланы")
    RideshareBankStatus.Canceled -> appText("Оплата картой не состоялась", "Карта менән түләү үтмәне")
    else -> appText("Статус оплаты уточняется", "Түләү торошо асыҡлана")
}

@Composable
private fun settlementStatus(status: RideshareBankStatus?, refund: Boolean): String? = when (status) {
    RideshareBankStatus.Queued, RideshareBankStatus.WaitingBank, RideshareBankStatus.Reconciling -> if (refund) {
        appText("Банк обрабатывает возврат", "Банк аҡсаны кире ҡайтара")
    } else appText("Банк обрабатывает выплату", "Банк түләүҙе башҡара")
    RideshareBankStatus.Canceled -> if (refund) {
        appText("Возврат не состоялся", "Аҡса кире ҡайтарылманы")
    } else appText("Выплата не состоялась", "Түләү башҡарылманы")
    else -> null
}
