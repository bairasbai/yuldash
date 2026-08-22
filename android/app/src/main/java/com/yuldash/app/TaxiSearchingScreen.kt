package com.yuldash.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yandex.mapkit.geometry.Point
import com.yuldash.app.data.InstantOrderDto

/**
 * Поиск машины — тот же язык, что у заказа и поездки: карта во весь рост, снизу шторка.
 *
 * Было: полноэкранная карточка с пульсирующим кружком по центру и текстом под ним. Карты
 * не было вовсе — человек, который только что выбрал точку на карте, терял её из виду ровно
 * в тот момент, когда ему интереснее всего, откуда поедет машина.
 *
 * Образец — Яндекс: у них на этом шаге «Рядом с вами 3 машины · 00:04» и три кнопки, а карта
 * показывает радар вокруг точки подачи. Мы делаем так же, но честнее: не обещаем «1–3 минуты»
 * после пяти минут ожидания (тексты меняются по мере ожидания) и не подменяем класс машины
 * молча — предлагаем соседний и решает человек.
 */
@Composable
internal fun TaxiSearchingScreen(
    order: InstantOrderDto,
    onCancel: () -> Unit,
    mapContent: (@Composable (Modifier) -> Unit)? = null,
) {
    // Сколько человек ждёт НА САМОМ ДЕЛЕ. Точку отсчёта даёт сервер: свой таймер обнулялся
    // при каждом возврате на экран, и после пяти минут ожидания человек снова читал
    // «обычно машина находится за 1–3 минуты» — это было враньём.
    //
    // Часы телефона могут врать, поэтому отрицательное и неправдоподобно большое (>2 ч)
    // значение считаем негодным и откатываемся на локальный отсчёт БЕЗ цифр.
    val serverStartMs = remember(order.id, order.searchClockFrom) {
        order.searchClockFrom?.let(::parseIsoUtcMillis)
    }
    val screenOpenedMs = remember(order.id) { System.currentTimeMillis() }
    val now by rememberNowMs()
    val serverSec = serverStartMs?.let { (now - it) / 1000 }?.takeIf { it in 0..7_200L }
    val watchedSec = serverSec ?: ((now - screenOpenedMs) / 1000).coerceAtLeast(0)
    val waitStage = when {
        watchedSec < 25L -> 0
        watchedSec < 70L -> 1
        else -> 2
    }
    // Цифру показываем, только когда ожидание уже затянулось: на двадцатой секунде секундомер
    // давит, на второй минуте — наоборот, отвечает на «сколько уже?». И только когда время
    // подтверждено сервером: выдуманных чисел на экране быть не должно.
    val showElapsed = serverSec != null && serverSec >= 70L

    var stop by remember { mutableStateOf(TaxiSheetStop.Half) }

    TaxiSheetScaffold(
        stop = stop,
        onStopChange = { stop = it },
        map = { m ->
            Box(m) {
                if (mapContent != null) {
                    mapContent(Modifier.fillMaxSize())
                } else {
                    InstantRouteMap(
                        from = Point(order.fromLat, order.fromLng),
                        to = Point(order.toLat, order.toLng),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Радар вокруг точки подачи: он же и есть ответ на «а вы вообще ищете?».
                SearchRadar(Modifier.align(Alignment.Center))
            }
        },
        header = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    appText("Ищем машину", "Машина эҙләйбеҙ"),
                    style = CanonTitle,
                    color = CanonText,
                    modifier = Modifier.weight(1f),
                )
                // Секундомер — справа и спокойным цветом: он сообщает, а не подгоняет.
                if (showElapsed) {
                    AnimatedContent(targetState = mmSs(watchedSec), label = "searchElapsed") { t ->
                        Text(t, style = CanonHeading, color = CanonMuted)
                    }
                }
            }
        },
        body = {
            // Ответ на «сколько ещё ждать». Пустого обещания не даём — текст меняется
            // по мере ожидания, и после минуты он честно говорит, что ищем дольше обычного.
            Text(
                when (waitStage) {
                    0 -> appText("Обычно машина находится за 1–3 минуты",
                                 "Ғәҙәттә машина 1–3 минутта табыла")
                    1 -> appText("Ещё ищем — свободных машин рядом сейчас мало",
                                 "Эҙләйбеҙ — яҡында буш машина әҙерәк")
                    else -> appText("Ищем дольше обычного. Можно подождать — как найдём, сразу сообщим",
                                    "Ғәҙәттәгенән оҙағыраҡ эҙләйбеҙ. Көтөп тор — тапҡас, шунда уҡ хәбәр итәбеҙ")
                },
                style = CanonBody,
                color = CanonMuted,
            )
            // Сумма здесь — та, что человек отдаст водителю: скидка по промокоду уже
            // зафиксирована в заказе, и показывать полную цену значило бы обещать одно,
            // а взять другое.
            if (order.hasPromoDiscount) {
                TaxiPromoPayRow(order = order, forDriver = false)
            } else {
                Text(
                    appText("≈ ${formatTaxiKop(order.passengerPayKop)} · подбираем ближайшего водителя",
                            "≈ ${formatTaxiKop(order.passengerPayKop)} · яҡын йөрөтөүсене табабыҙ"),
                    style = CanonCaption,
                    color = CanonMuted,
                )
            }
        },
        extra = {
            // «В Комфорте сейчас никого» — предложение поискать в соседнем классе. Молча класс
            // НЕ подменяем никогда: «заказал Комфорт — приехал Логан» это главная претензия
            // к агрегаторам, и решать тут должен пассажир, а не мы за него.
            InstantAlternativesBlock(order = order, watchedSec = watchedSec)
        },
        footer = {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                shape = CanonFieldShape,
                border = BorderStroke(1.dp, CanonRed.copy(alpha = 0.4f)),
            ) {
                Text(appText("Отменить заказ", "Заказды кире алыу"), style = CanonButton, color = CanonRed)
            }
        },
    )
}

/** Пульсирующий радар поверх карты: видимый признак того, что поиск идёт прямо сейчас. */
@Composable
private fun SearchRadar(modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "search")
    // 900 мс — это цикл дыхания, а не переход между состояниями, поэтому он вне шкалы
    // CanonMotion (там длительности переходов 180/260/320).
    val pulse by infinite.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(modifier.size(140.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size((70 + pulse * 64).dp)
                .background(CanonGreen2.copy(alpha = 0.14f * pulse), CircleShape)
        )
        Surface(shape = CircleShape, color = CanonGreen2, shadowElevation = CanonDepth.raised) {
            // Обычная иконка машины, а не фирменная картинка карты: та рисовалась под метку
            // на карте и в кружке 28dp читалась как две таблетки.
            Icon(
                Icons.Default.DirectionsCar,
                contentDescription = appText("Ищем машину", "Машина эҙләйбеҙ"),
                tint = CanonOnAccent,
                modifier = Modifier.padding(CanonSpace.md).size(30.dp),
            )
        }
    }
}
