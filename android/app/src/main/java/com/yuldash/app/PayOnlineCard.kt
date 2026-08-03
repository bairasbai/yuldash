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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.PayTripResultDto
import kotlinx.coroutines.launch

/**
 * «Оплатить онлайн» — оплата ЗАВЕРШЁННОЙ поездки картой/СБП через ЮKassa (за флагом провайдера).
 * Переиспользуемая карточка: бронь → pay = { m -> ApiClient.payBooking(id, m) },
 * быстрый заказ → pay = { m -> ApiClient.payInstantOrder(id, m) }.
 *
 * ЧЕСТНОСТЬ: сервер — источник правды. Провайдер выключен (mock в проде) → сервер отвечает 503,
 * мы запоминаем это на сессию ([OnlinePayGate]) и карточка исчезает везде — никаких кнопок-обманок.
 * Когда Александр включит ЮKassa, карточка оживёт сама, без правок клиента.
 * Онлайн-оплата ДОПОЛНЯЕТ «договорённость об оплате» (PayAgreementBlock), не заменяет её.
 */
internal object OnlinePayGate {
    /** true после первого 503 от /pay — онлайн-оплата ещё не включена (до перезапуска приложения). */
    var unavailable by mutableStateOf(false)
}

private enum class PayOnlineStage { Idle, Waiting, Paid }

@Composable
internal fun PayOnlineCard(
    amountRub: Int?,
    pay: suspend (String) -> Result<PayTripResultDto>,
    modifier: Modifier = Modifier,
) {
    if (OnlinePayGate.unavailable) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf(PayOnlineStage.Idle) }
    var method by remember { mutableStateOf("card") }   // card | sbp
    var busy by remember { mutableStateOf(false) }
    var paymentId by remember { mutableStateOf<Int?>(null) }

    val soonMsg = appText(
        "Онлайн-оплата скоро появится. Пока рассчитайтесь, как договорились.",
        "Онлайн түләү оҙаҡламай буласаҡ. Әлегә һөйләшкәнсә иҫәпләшегеҙ.",
    )
    val errMsg = appText("Не получилось оплатить. Проверь сеть и повтори.", "Түләп булманы. Селтәрҙе тикшереп ҡабатла.")
    val notYetMsg = appText(
        "Оплата ещё не подтверждена. Заверши её в браузере и проверь снова.",
        "Түләү әле раҫланманы. Уны браузерҙа тамамла ла яңынан тикшер.",
    )

    fun startPay() {
        if (busy) return
        busy = true
        scope.launch {
            pay(method)
                .onSuccess { res ->
                    busy = false
                    res.paymentId?.let { paymentId = it }
                    when {
                        res.isPaid -> stage = PayOnlineStage.Paid
                        !res.confirmationUrl.isNullOrBlank() -> {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(res.confirmationUrl))) }
                            stage = PayOnlineStage.Waiting
                        }
                        else -> stage = PayOnlineStage.Waiting
                    }
                }
                .onFailure { e ->
                    busy = false
                    if (e is ApiException && e.status == 503) {
                        // Сервер честно сказал «ещё нельзя» → прячем карточку на всю сессию.
                        OnlinePayGate.unavailable = true
                        Toast.makeText(context, soonMsg, Toast.LENGTH_LONG).show()
                    } else {
                        val msg = if (e is ApiException) (e.message ?: errMsg) else errMsg
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    fun checkPayment() {
        if (busy) return
        val pid = paymentId ?: run { stage = PayOnlineStage.Idle; return }
        busy = true
        scope.launch {
            // Тот же механизм, что у Boost: сервер при pending сам перепроверяет оплату у ЮKassa.
            ApiClient.getPaymentStatus(pid)
                .onSuccess { st ->
                    busy = false
                    if (st.status == "succeeded") stage = PayOnlineStage.Paid
                    else Toast.makeText(context, notYetMsg, Toast.LENGTH_SHORT).show()
                }
                .onFailure { busy = false; Toast.makeText(context, errMsg, Toast.LENGTH_SHORT).show() }
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
                        if (amountRub != null && amountRub > 0) {
                            Text("${fmtRub(amountRub)} ₽", color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        }
                        // Выбор способа оплаты — не мелкий фильтр, а решение про деньги:
                        // тач-цель ≥ 48dp (§4.5), иначе палец промахивается и платит «не тем».
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NearbyFilterChip(
                                Icons.Default.CreditCard, appText("Карта", "Карта"), method == "card",
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { if (!busy) method = "card" }
                            NearbyFilterChip(
                                Icons.Default.Bolt, appText("СБП", "СБП"), method == "sbp",
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { if (!busy) method = "sbp" }
                        }
                        AppButton(
                            text = if (amountRub != null && amountRub > 0)
                                appText("Оплатить ${fmtRub(amountRub)} ₽", "${fmtRub(amountRub)} ₽ түләү")
                            else appText("Оплатить", "Түләү"),
                            onClick = { startPay() },
                            icon = Icons.Default.CreditCard,
                            loading = busy,
                        )
                        Text(
                            appText(
                                "Наличными или переводом напрямую — тоже можно, как договорились.",
                                "Нәҡзләй йәки туранан-тура күсереп тә була — нисек һөйләшкәнһегеҙ.",
                            ),
                            color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                        )
                    }
                    PayOnlineStage.Waiting -> {
                        PayOnlineHeader(
                            icon = Icons.Default.HourglassBottom, tint = CanonWarn, bg = CanonWarnBg,
                            title = appText("Ждём подтверждения оплаты", "Түләүҙең раҫланыуын көтәбеҙ"),
                            subtitle = appText(
                                "Заверши оплату в открывшемся окне и вернись сюда.",
                                "Асылған тәҙрәлә түләүҙе тамамла һәм бында кире ҡайт.",
                            ),
                        )
                        AppButton(
                            text = appText("Проверить оплату", "Түләүҙе тикшереү"),
                            onClick = { checkPayment() },
                            loading = busy,
                        )
                        TextButton(onClick = { if (!busy) stage = PayOnlineStage.Idle }) {
                            Text(appText("Выбрать другой способ", "Икенсе ысул һайлау"), color = CanonMuted, fontSize = 14.sp)
                        }
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
                                    appText("Деньги уйдут водителю.", "Аҡса водителгә китә."),
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
