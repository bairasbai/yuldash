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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.testTag
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
    /** true, когда health-check сервера явно ответил «онлайн-оплата выключена». */
    var unavailable by mutableStateOf(false)
    /**
     * Пришёл ли уже ответ health-check (успешный или нет). Пока false — мы ЕЩЁ НЕ СПРОСИЛИ
     * сервер, и карточка не должна ничего показывать: раньше она рисовалась с первого кадра,
     * потому что `unavailable` стартовал как false — «выключено» и «ещё не узнали» выглядели
     * одинаково, и на медленной сети человек видел на миг рабочую кнопку оплаты до того, как
     * сервер вообще ответил (ревью Opus).
     */
    var checked by mutableStateOf(false)
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
    pay: suspend (String) -> Result<PayTripResultDto>,
    modifier: Modifier = Modifier,
) {
    // Спрашиваем сервер ДО показа кнопки: раньше карточка появлялась всегда и пряталась
    // только после первого нажатия, ответившего «нельзя». Один впустую нажатый платёж за
    // сессию — мелочь, но именно на ней человек решает, можно ли верить кнопкам вообще.
    LaunchedEffect(Unit) {
        if (!OnlinePayGate.asked) {
            OnlinePayGate.asked = true
            ApiClient.paymentsOnlineEnabled()
                .onSuccess { OnlinePayGate.unavailable = !it }
                // Сеть/сервер промолчали — это НЕ «выключено навсегда», это «не узнали в этот
                // раз». Карточку всё равно рисуем (как раньше, до этой правки): настоящий 503
                // при ПОПЫТКЕ оплаты уже ловит обработчик ниже, через человеческое сообщение,
                // а не через вечное исчезновение.
                .onFailure { OnlinePayGate.unavailable = false }
            OnlinePayGate.checked = true
        }
    }
    // checked=false — ответа ещё нет, рисовать нечего (и нечего прятать «на всякий случай»).
    if (!OnlinePayGate.checked || OnlinePayGate.unavailable) return
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
                        // НЕ прячем карточку навсегда: 503 именно на ПОПЫТКЕ оплаты может быть
                        // одноразовым сбоем провайдера, а не выключенным флагом (та проверка —
                        // отдельный health-check выше, он решает про показ карточки). Раньше
                        // единственный неудачный платёж навсегда прятал кнопку — временный сбой
                        // выглядел как «фичи больше нет» (ревью Opus). Человек может просто
                        // нажать «Оплатить» ещё раз — карточка остаётся на месте.
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
                .onFailure { busy = false; Toast.makeText(context, serverSaid(it, errMsg), Toast.LENGTH_LONG).show() }
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
                            ) { if (!busy) method = "card" }
                            NearbyFilterChip(
                                Icons.Default.Bolt, appText("СБП", "СБП"), method == "sbp",
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { if (!busy) method = "sbp" }
                        }
                        AppButton(
                            text = if (amountKop != null && amountKop > 0)
                                appText("Оплатить ${kopToRub(amountKop)}", "${kopToRub(amountKop)} түләү")
                            else appText("Оплатить", "Түләү"),
                            onClick = { startPay() },
                            icon = Icons.Default.CreditCard,
                            loading = busy,
                            // Текст кнопки при loading прячется под спиннер (см. AppButtonContent) —
                            // тест двойного нажатия находит кнопку по тегу, не по тексту.
                            modifier = Modifier.testTag("pay_online_submit"),
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
