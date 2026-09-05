package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.Analytics
import kotlinx.coroutines.launch
import com.yuldash.app.data.ApiClient
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Способы расчёта с водителем.
 *
 * Это НЕ кошелёк и не платёжный экран: деньги через приложение не идут. Пассажир платит
 * водителю сам — наличными или переводом по СБП, — а здесь записывается договорённость,
 * о которой узнают обе стороны. Спор «я думал, ты переводом» случается ровно потому, что
 * до высадки об этом никто не говорил.
 *
 * Карты и корпоративный счёт стоят строкой «скоро» намеренно. Принимать деньги без договора
 * с банком, кассы и чеков нельзя, а немой вопрос «а картой можно?» человек задаёт себе
 * каждый заказ. Нажатие считается заявкой — по ней и решим, когда включать эквайринг.
 */
/** Ключ памяти выбора: где лежит последний способ расчёта. */
internal const val PAY_METHOD_PREF = "pay_method"

internal object PayMethods {
    const val CASH = "cash"
    const val SBP = "sbp"
    const val NEGOTIATE = "negotiate"
    const val CARD = "card"
    const val CORPORATE = "corporate"

    /** Название способа на языке человека. Неизвестное → «договоримся»: это умолчание. */
    @Composable
    fun title(method: String): String = when (method) {
        CASH -> appText("Наличными", "Наличный менән")
        SBP -> appText("Переводом по СБП", "СБП аша күсереү")
        CARD -> appText("Картой в приложении", "Ҡушымтала карта менән")
        CORPORATE -> appText("Корпоративный счёт", "Корпоратив иҫәп")
        else -> appText("Договоримся на месте", "Урында килешәбеҙ")
    }

    /** Короткая подпись для кнопки на экране заказа: там места на одно слово. */
    @Composable
    fun short(method: String): String = when (method) {
        CASH -> appText("Наличные", "Наличный")
        SBP -> appText("СБП", "СБП")
        CARD -> appText("Карта", "Карта")
        CORPORATE -> appText("Счёт", "Иҫәп")
        else -> appText("Договоримся", "Килешәбеҙ")
    }

    fun icon(method: String): ImageVector = when (method) {
        CASH -> Icons.Default.Payments
        SBP -> Icons.Default.SwapHoriz
        CARD -> Icons.Default.CreditCard
        CORPORATE -> Icons.Default.AccountBalance
        else -> Icons.Default.Handshake
    }
}

@Composable
internal fun PaymentMethodsScreen(
    current: String,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
    /** Номер идущей поездки (0 = поездки нет). Если она идёт, смена способа уходит на сервер:
     *  про наличные человек вспоминает уже сидя в машине, и водителю об этом сообщат. */
    activeOrderId: Int = 0,
) {
    // Какой из будущих способов человек попросил. Держим до ухода с экрана: это ответ
    // на его нажатие, а не системное сообщение — исчезать через секунду он не должен.
    var wanted by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // Что летит на сервер прямо сейчас и что до него не долетело.
    //
    // Галочка встаёт ТОЛЬКО после ответа сервера. Раньше она вставала сразу, а отправка
    // уходила молча: пропал интернет на секунду — пассажир видит «Наличные», водитель
    // по-прежнему ждёт перевод, и на высадке они спорят. Ровно тот спор, ради которого
    // экран и делался.
    var летит by remember { mutableStateOf<String?>(null) }
    var неДошло by remember { mutableStateOf<String?>(null) }

    fun выбрать(method: String) {
        // Поездки нет — сервер про этот выбор ничего не знает, это память на будущий заказ.
        if (activeOrderId <= 0) {
            onPick(method)
            return
        }
        if (летит != null) return
        неДошло = null
        летит = method
        scope.launch {
            val дошло = ApiClient.setInstantPaymentMethod(activeOrderId, method).isSuccess
            летит = null
            if (дошло) onPick(method) else неДошло = method
        }
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Способы оплаты", "Түләү ысулдары"), onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(CanonSpace.lg),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.lg),
        ) {
            Image(
                painter = painterResource(R.drawable.yuldash_payment_wallet),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(140.dp),
            )

            PaySectionTitle(appText("Как рассчитаетесь", "Нисек иҫәпләшәһегеҙ"))
            Surface(color = CanonSurface, shape = CanonCardShape, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    PayMethodRow(
                        method = PayMethods.CASH,
                        subtitle = appText("Отдашь деньги водителю в конце поездки",
                                           "Сәфәр аҙағында аҡсаны йөрөтөүсегә бирәһең"),
                        selected = current == PayMethods.CASH,
                        ждёт = летит == PayMethods.CASH,
                        занято = летит != null,
                        onClick = { выбрать(PayMethods.CASH) },
                    )
                    PayDivider()
                    PayMethodRow(
                        method = PayMethods.SBP,
                        subtitle = appText("Переведёшь на телефон водителя",
                                           "Йөрөтөүсенең телефонына күсерәһең"),
                        selected = current == PayMethods.SBP,
                        ждёт = летит == PayMethods.SBP,
                        занято = летит != null,
                        onClick = { выбрать(PayMethods.SBP) },
                    )
                    PayDivider()
                    PayMethodRow(
                        method = PayMethods.NEGOTIATE,
                        subtitle = appText("Обсудишь с водителем", "Йөрөтөүсе менән һөйләшерһең"),
                        selected = current == PayMethods.NEGOTIATE,
                        ждёт = летит == PayMethods.NEGOTIATE,
                        занято = летит != null,
                        onClick = { выбрать(PayMethods.NEGOTIATE) },
                    )
                }
            }

            // Не дошло до сервера — значит и до водителя. Молчать тут нельзя: человек
            // уверен, что предупредил, и на высадке достанет не то. Выбор при этом
            // остаётся прежним — врать галочкой хуже, чем признать неудачу.
            AnimatedVisibility(
                visible = неДошло != null,
                enter = fadeIn(tween(CanonMotion.NORMAL)) + expandVertically(),
                exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(),
            ) {
                PayFailedRow(
                    method = неДошло ?: PayMethods.CASH,
                    onRetry = { неДошло?.let { выбрать(it) } },
                )
            }

            // Реквизитов до принятия заказа ещё нет: телефон водителя открывается только
            // после того, как он взял заказ. Промолчать здесь — значит выглядеть забывчивым.
            AnimatedVisibility(
                visible = current == PayMethods.SBP,
                enter = fadeIn(tween(CanonMotion.NORMAL)) + expandVertically(),
                exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(),
            ) {
                PayNote(
                    if (activeOrderId > 0) appText(
                        "Номер водителя — в карточке поездки, кнопка «Позвонить».",
                        "Йөрөтөүсенең номеры — сәфәр карточкаһында, «Шылтыратырға» төймәһе.",
                    ) else appText(
                        "Номер для перевода появится, когда водитель примет заказ.",
                        "Күсереү өсөн номер йөрөтөүсе заказды алғас күренәсәк.",
                    )
                )
            }

            PaySectionTitle(appText("Скоро", "Тиҙҙән"))
            Surface(color = CanonSurface, shape = CanonCardShape, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    PaySoonRow(
                        method = PayMethods.CARD,
                        subtitle = appText("Спишем с карты автоматически",
                                           "Картанан автоматик рәүештә алабыҙ"),
                        onClick = {
                            wanted = PayMethods.CARD
                            Analytics.log("pay_wanted_card")
                        },
                    )
                    PayDivider()
                    PaySoonRow(
                        method = PayMethods.CORPORATE,
                        subtitle = appText("Поездки за счёт компании",
                                           "Компания иҫәбенә сәфәрҙәр"),
                        onClick = {
                            wanted = PayMethods.CORPORATE
                            Analytics.log("pay_wanted_corporate")
                        },
                    )
                }
            }

            AnimatedVisibility(
                visible = wanted != null,
                enter = fadeIn(tween(CanonMotion.NORMAL)) + expandVertically(),
                exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(),
            ) {
                PayNote(appText(
                    "Записали. Сообщим, как только заработает — таких просьб мы считаем.",
                    "Яҙып ҡуйҙыҡ. Эшләй башлаһа, хәбәр итәбеҙ — бындай һорауҙарҙы иҫәпләйбеҙ.",
                ))
            }

            // Справка переехала сюда из профиля: «почему напрямую» — это не мелкий текст,
            // а суть договорённости, и место ей рядом с самим выбором.
            Surface(color = CanonSurface, shape = CanonCardShape, modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(CanonSpace.md),
                    verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
                ) {
                    Text(
                        appText("Почему платим напрямую", "Ниңә тура түләйбеҙ"),
                        style = CanonBodyStrong, color = CanonText,
                    )
                    Text(
                        appText(
                            "Юлдаш не берёт деньги за поездку и не удерживает комиссию с этой " +
                                "суммы: пассажир рассчитывается с водителем сам. Приложение лишь " +
                                "записывает, о чём договорились, — чтобы на высадке не спорить.",
                            "Юлдаш сәфәр өсөн аҡса алмай һәм был сумманан комиссия тотмай: пассажир " +
                                "йөрөтөүсе менән үҙе иҫәпләшә. Ҡушымта тик нимә тураһында килешеүҙе " +
                                "яҙып ҡуя — төшкәндә бәхәсләшмәҫ өсөн.",
                        ),
                        style = CanonCaption, color = CanonMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun PaySectionTitle(text: String) {
    Text(text, style = CanonBodyStrong, color = CanonMuted,
         modifier = Modifier.padding(start = CanonSpace.xs))
}

@Composable
private fun PayDivider() {
    Box(
        Modifier
            .padding(start = 64.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(CanonBorder),
    )
}

/** Пояснение под списком: серым, без рамки — это не ошибка и не предупреждение. */
@Composable
private fun PayNote(text: String) {
    Text(text, style = CanonCaption, color = CanonMuted,
         modifier = Modifier.padding(horizontal = CanonSpace.xs))
}

@Composable
private fun PayMethodRow(
    method: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    /** Этот способ прямо сейчас летит на сервер — вместо галочки крутилка. */
    ждёт: Boolean = false,
    /** Что-то уже летит: второе нажатие подряд только запутает обоих. */
    занято: Boolean = false,
) {
    val title = PayMethods.title(method)
    Surface(
        onClick = onClick,
        enabled = !занято,
        color = if (selected) CanonTaxiBg else CanonSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(PayMethods.icon(method), contentDescription = null, tint = CanonGreen2,
                 modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(CanonSpace.md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = CanonBodyStrong, color = CanonText,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = CanonCaption, color = CanonMuted,
                     maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(CanonSpace.sm))
            // Галочка вместо кружка-радио: выбранное должно читаться как «сделано»,
            // а не как «одна из настроек».
            Box(
                Modifier.size(28.dp)
                    .background(
                        if (selected && !ждёт) CanonTaxi else CanonBg,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    // Пока ответа нет — крутилка: выбор ещё не состоялся, и показывать
                    // галочку значило бы соврать.
                    ждёт -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = CanonGreen2,
                        strokeWidth = 2.dp,
                    )
                    selected -> Icon(
                        Icons.Default.Check,
                        contentDescription = appText("Выбрано", "Һайланған"),
                        // Не CanonText: он в тёмной теме светлый, и белая галочка на жёлтом
                        // круге пропадала (≈1.4:1 при норме 3:1). Ink — тёмный «на жёлтом».
                        tint = CanonTaxiInk, modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * «Водителю не сказали» — с кнопкой «Повторить».
 *
 * Красная, но не пугающая: ничего не сломалось и деньги никуда не делись. Просто
 * договорённость не долетела, и пока не долетит — водитель считает по-старому.
 */
@Composable
private fun PayFailedRow(method: String, onRetry: () -> Unit) {
    Surface(color = CanonDangerBg, shape = CanonCardShape, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    appText("Водитель об этом не знает", "Йөрөтөүсе быны белмәй"),
                    style = CanonBodyStrong, color = CanonRed,
                )
                Text(
                    appText(
                        "«${PayMethods.title(method)}» не дошло до сервера. Он ждёт прежнего расчёта.",
                        "«${PayMethods.title(method)}» серверға барып етмәне. Ул элекке иҫәпләшеүҙе көтә.",
                    ),
                    style = CanonCaption, color = CanonMutedStrong,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(CanonSpace.sm))
            Surface(
                onClick = onRetry,
                color = CanonSurface,
                shape = CanonItemShape,
            ) {
                Row(
                    Modifier.heightIn(min = 48.dp)
                        .padding(horizontal = CanonSpace.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CanonSpace.xs),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null,
                         tint = CanonText, modifier = Modifier.size(18.dp))
                    Text(appText("Повторить", "Ҡабатларға"),
                         style = CanonBodyStrong, color = CanonText)
                }
            }
        }
    }
}

/** Способ, которого пока нет. Приглушён, но нажимается: нажатие — это заявка. */
@Composable
private fun PaySoonRow(method: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = CanonSurface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(PayMethods.icon(method), contentDescription = null, tint = CanonMuted,
                 modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(CanonSpace.md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(PayMethods.title(method), style = CanonBodyStrong, color = CanonMutedStrong,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = CanonCaption, color = CanonMuted,
                     maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(CanonSpace.sm))
            Surface(color = CanonBg, shape = CanonTinyShape) {
                Text(
                    appText("скоро", "тиҙҙән"),
                    style = CanonCaption, color = CanonMutedStrong,
                    modifier = Modifier.padding(horizontal = CanonSpace.sm, vertical = 2.dp),
                )
            }
        }
    }
}
