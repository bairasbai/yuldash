package com.yuldash.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.PayTripResultDto
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * «Оплатить онлайн» — оплата ЗАВЕРШЁННОЙ поездки картой/СБП через ЮKassa (за флагом провайдера).
 * Карточка быстрого заказа → pay = { m, owner -> ApiClient.payInstantOrder(id, m, owner) }.
 * В попутке онлайн-оплата не предлагается (RS-01, RS-08).
 *
 * ЧЕСТНОСТЬ: сервер — источник правды. Провайдер выключен и попытки нет → сервер отвечает taxi_online_pay_off,
 * мы запоминаем это на сессию ([OnlinePayGate]) и карточка исчезает везде — никаких кнопок-обманок.
 * При неизвестном результате оставляем только проверку ранее начатого платежа.
 * Онлайн-оплата ДОПОЛНЯЕТ «договорённость об оплате» (PayAgreementBlock), не заменяет её.
 */
internal object OnlinePayGate {
    private var generation by mutableStateOf<Long?>(null)
    fun ensureOwner(owner: Long) {
        if (generation != owner) { generation = owner; asked = false; unavailable = false; enabled = false }
    }
    fun unavailableFor(owner: Long): Boolean = generation == owner && unavailable
    fun enabledFor(owner: Long): Boolean = generation == owner && enabled && !unavailable
    /** Только успешный ответ сервера разрешает показать карточку. */
    var enabled by mutableStateOf(false)
    /** true, если сервер сообщил, что онлайн-оплата отключена. */
    var unavailable by mutableStateOf(false)
    /** Спрашивали ли уже сервер, включена ли онлайн-оплата (один раз на сессию). */
    var asked by mutableStateOf(false)
}

private enum class PayOnlineStage { Idle, Waiting, Paid }

/**
 * @param amountKop сумма в КОПЕЙКАХ. Раньше сюда передавали рубли, посчитанные как `kop / 100`:
 * кнопка обещала «Оплатить 188 ₽», а списывалось 188,50 ₽ — сервер-то знает точную сумму.
 * Расхождение кнопки с чеком на любые деньги подрывает доверие ко всему платежу.
 */
@Composable
internal fun PayOnlineCard(
    amountKop: Int?,
    pay: suspend (String, Long) -> Result<PayTripResultDto>,
    modifier: Modifier = Modifier,
    targetKey: String = "",
    ownerGeneration: Long? = null,
    onlinePaymentPending: Boolean = false,
    isCurrentParent: () -> Boolean = { true },
) {
    // Захватываем владельца до любого conditional return. Смена входа скрывает старую
    // карточку, а не перепривязывает прежний заказ к новому аккаунту.
    val owner = remember { ownerGeneration ?: ApiClient.queueSessionGeneration() }
    val session by ApiClient.sessionChanges.collectAsState()
    if (session != owner || !ApiClient.isCurrentSession(owner) ||
        (ownerGeneration != null && ownerGeneration != owner)) return
    key(targetKey, owner) { PayOnlineOwnedCard(amountKop, pay, modifier, owner, onlinePaymentPending, isCurrentParent) }
}

@Composable
private fun PayOnlineOwnedCard(
    amountKop: Int?,
    pay: suspend (String, Long) -> Result<PayTripResultDto>,
    modifier: Modifier,
    generation: Long,
    onlinePaymentPending: Boolean,
    isCurrentParent: () -> Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentParent by rememberUpdatedState(isCurrentParent)
    fun isCurrent(): Boolean = scope.isActive && ApiClient.isCurrentSession(generation) && currentParent()
    fun commit(action: () -> Unit) {
        ApiClient.runIfCurrentSession(generation) { if (isCurrent()) action() }
    }
    LaunchedEffect(generation) {
        var ask = false
        commit {
            OnlinePayGate.ensureOwner(generation)
            // Ушли с экрана до ответа — это ещё не результат проверки; на новом открытии спрашиваем снова.
            if (!OnlinePayGate.asked || !OnlinePayGate.enabledFor(generation) && !OnlinePayGate.unavailableFor(generation)) {
                OnlinePayGate.asked = true; ask = true
            }
        }
        if (ask) ApiClient.paymentsOnlineEnabled(expectedGeneration = generation)
            .onSuccess { enabled -> commit {
                OnlinePayGate.enabled = enabled
                OnlinePayGate.unavailable = OnlinePayGate.unavailable || !enabled
            } }
            .onFailure { commit { OnlinePayGate.unavailable = true } }
    }
    var stageOverride by remember { mutableStateOf<PayOnlineStage?>(null) }
    val stage = stageOverride ?: if (onlinePaymentPending) PayOnlineStage.Waiting else PayOnlineStage.Idle
    if (stage == PayOnlineStage.Idle && !OnlinePayGate.enabledFor(generation)) return

    var method by remember { mutableStateOf("card") }
    var busy by remember { mutableStateOf(false) }
    val errMsg = appText("Не получилось оплатить. Проверь сеть и повтори.", "Түләп булманы. Селтәрҙе тикшереп ҡабатла.")

    fun requestPayment(check: Boolean) {
        val canRequest = if (check) stage == PayOnlineStage.Waiting
            else stage == PayOnlineStage.Idle && OnlinePayGate.enabledFor(generation)
        if (!isCurrent() || busy || !canRequest) return
        busy = true
        scope.launch {
            if (!isCurrent()) return@launch
            pay(if (check) "check" else method, generation)
                .onSuccess { res -> commit {
                    busy = false
                    when (taxiCheckoutOutcome(res)) {
                        TaxiCheckoutOutcome.Paid -> stageOverride = PayOnlineStage.Paid
                        TaxiCheckoutOutcome.Canceled -> {
                            stageOverride = PayOnlineStage.Idle
                            Toast.makeText(context, appText(
                                "Банк отменил оплату. Можно выбрать способ оплаты заново.",
                                "Банк түләүҙе ғәмәлдән сығарҙы. Түләү ысулын яңынан һайлап була.",
                            ), Toast.LENGTH_LONG).show()
                        }
                        TaxiCheckoutOutcome.OpenBank -> {
                            stageOverride = PayOnlineStage.Waiting
                            res.confirmationUrl?.let { url ->
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            }
                        }
                        else -> stageOverride = PayOnlineStage.Waiting
                    }
                } }
                .onFailure { e -> commit {
                    busy = false
                    val apiError = e as? ApiException
                    when (taxiCheckoutFailure(check, apiError?.status, apiError?.detailCode)) {
                        TaxiCheckoutOutcome.Disabled -> {
                            OnlinePayGate.unavailable = true
                            Toast.makeText(context, appText(
                                "Оплата картой и СБП сейчас недоступна.",
                                "Карта һәм СБП аша түләү әле мөмкин түгел.",
                            ), Toast.LENGTH_LONG).show()
                        }
                        TaxiCheckoutOutcome.Pending -> stageOverride = PayOnlineStage.Waiting
                        else -> Toast.makeText(context, apiError?.message ?: errMsg, Toast.LENGTH_LONG).show()
                    }
                } }
        }
    }

    AppCard(modifier = modifier) {
        AnimatedContent(
            targetState = stage,
            transitionSpec = { fadeIn(tween(CanonMotion.QUICK)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
            label = "payOnlineStage",
        ) { st ->
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (st) {
                    PayOnlineStage.Idle -> {
                        PayOnlineHeader(
                            icon = Icons.Default.CreditCard, tint = CanonGreen2, bg = CanonMint,
                            title = appText("Оплатить онлайн", "Онлайн түләү"),
                            subtitle = appText(
                                "Карта или СБП — безопасно через ЮKassa.",
                                "Карта йәки СБП — ЮKassa аша хәүефһеҙ.",
                            ),
                        )
                        if (amountKop != null && amountKop > 0) {
                            Text(kopToRub(amountKop), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        }
                        // Выбор способа оплаты — не мелкий фильтр, а решение про деньги:
                        // тач-цель ≥ 48dp (§4.5), иначе палец промахивается и платит «не тем».
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NearbyFilterChip(
                                Icons.Default.CreditCard, appText("Карта", "Карта"), method == "card",
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { if (isCurrent() && stage == PayOnlineStage.Idle && !busy) method = "card" }
                            NearbyFilterChip(
                                Icons.Default.Bolt, appText("СБП", "СБП"), method == "sbp",
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { if (isCurrent() && stage == PayOnlineStage.Idle && !busy) method = "sbp" }
                        }
                        AppButton(
                            text = if (amountKop != null && amountKop > 0)
                                appText("Оплатить ${kopToRub(amountKop)}", "${kopToRub(amountKop)} түләү")
                            else appText("Оплатить", "Түләү"),
                            onClick = { requestPayment(check = false) },
                            icon = Icons.Default.CreditCard,
                            loading = busy,
                            // Текст кнопки при loading прячется под спиннер (см. AppButtonContent) —
                            // тест двойного нажатия находит кнопку по тегу, не по тексту.
                            modifier = Modifier.testTag("pay_online_submit"),
                        )
                    }
                    PayOnlineStage.Waiting -> {
                        PayOnlineHeader(
                            icon = Icons.Default.HourglassBottom, tint = CanonWarn, bg = CanonWarnBg,
                            title = appText("Ждём подтверждения оплаты", "Түләүҙең раҫланыуын көтәбеҙ"),
                            subtitle = appText(
                                "Онлайн-оплата ещё проверяется. Не плати повторно.",
                                "Онлайн түләү әле тикшерелә. Ҡабат түләмә.",
                            ),
                        )
                        AppButton(
                            text = appText("Проверить оплату", "Түләүҙе тикшереү"),
                            onClick = { requestPayment(check = true) },
                            loading = busy,
                        )
                    }
                    PayOnlineStage.Paid -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = CanonMint, shape = CircleShape) {
                                Icon(
                                    Icons.Default.CheckCircle, contentDescription = null,
                                    tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(26.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(appText("Оплачено — спасибо!", "Түләнде — рәхмәт!"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(
                                    appText("Деньги уйдут водителю.", "Аҡса йөрөтөүсегә китә."),
                                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PayOnlineHeader(icon: ImageVector, tint: Color, bg: Color, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = bg, shape = CircleShape) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(8.dp).size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(subtitle, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}
