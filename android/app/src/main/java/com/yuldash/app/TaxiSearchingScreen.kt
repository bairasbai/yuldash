package com.yuldash.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yandex.mapkit.geometry.Point
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.InstantOrderDto
import com.yuldash.app.data.NearbyDriverDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Премиальный экран поиска машины: карта отвечает «где ищем и кто рядом», шторка —
 * «что происходит, сколько уже ищем и как посмотреть детали/отменить».
 *
 * Числа не декоративные. Машины и их количество приходят из `/instant/nearby-drivers`,
 * таймер считается от серверного `searchClockFrom`. Если данных нет, экран прямо показывает
 * загрузку/ошибку/пустоту и никогда не рисует выдуманные машины или прогноз.
 */
@Composable
internal fun TaxiSearchingScreen(
    order: InstantOrderDto,
    onCancel: () -> Unit,
    embedded: Boolean = true,
    mapContent: (@Composable (Modifier) -> Unit)? = null,
) {
    val serverStartMs = remember(order.id, order.searchClockFrom) {
        order.searchClockFrom?.let(::parseIsoUtcMillis)
    }
    val screenOpenedMs = remember(order.id) { System.currentTimeMillis() }
    val now by rememberNowMs()
    val serverSec = serverStartMs?.let { (now - it) / 1000 }?.takeIf { it in 0..7_200L }
    val watchedSec = serverSec ?: ((now - screenOpenedMs) / 1000).coerceAtLeast(0)
    val elapsed = serverSec?.let(::mmSs)
    val nearby = rememberSearchNearbyState(order)

    var stop by remember(order.id) { mutableStateOf(TaxiSheetStop.Half) }

    // Во время активного поиска «Назад» не бросает живой заказ в фоне молча: открывается
    // тот же диалог причины, что и по кнопке отмены.
    BackHandler(onBack = onCancel)

    TaxiSheetScaffold(
        stop = stop,
        onStopChange = { stop = it },
        underSystemBar = !embedded,
        halfBodyFraction = 0.32f,
        map = { m ->
            Box(m) {
                if (mapContent != null) {
                    mapContent(Modifier.fillMaxSize())
                } else {
                    // На поиске важна подача, а не весь будущий маршрут: без точки Б карта
                    // показывает реальные машины рядом. Жесты выключены — радар остаётся
                    // привязан к точке подачи, а не «уезжает» после случайного свайпа.
                    InstantRouteMap(
                        from = Point(order.fromLat, order.fromLng),
                        to = null,
                        nearbyDrivers = nearby.drivers,
                        interactive = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                SearchRadar(Modifier.align(Alignment.Center))
            }
        },
        header = {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
            ) {
                Text(
                    appText("Ищем машину", "Машина эҙләйбеҙ"),
                    style = CanonTitle,
                    color = CanonText,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CanonGreen2,
                    shadowElevation = CanonDepth.raised,
                ) {
                    AnimatedContent(
                        targetState = elapsed,
                        label = "searchElapsed",
                    ) { value ->
                        Text(
                            value?.let { appText("Ищем $it", "$it эҙләйбеҙ") }
                                ?: appText("Ищем", "Эҙләйбеҙ"),
                            color = CanonOnFilled,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = CanonSpace.md, vertical = CanonSpace.sm),
                        )
                    }
                }
            }
        },
        body = {
            Text(
                searchStatusText(watchedSec),
                style = CanonBody,
                color = CanonMuted,
            )
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = CanonGreen2,
                trackColor = CanonBorder,
            )
            // Первый водитель отменил — человек видит, что поиск начался заново, и пугается:
            // «а цена? а адрес?». Сказать это надо здесь и сразу, иначе он отменит сам.
            AnimatedVisibility(
                visible = order.reassigns > 0,
                enter = fadeIn(tween(CanonMotion.NORMAL)) + expandVertically(tween(CanonMotion.NORMAL)),
            ) {
                Surface(color = CanonWarnBg, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        appText(
                            "Первый водитель отменил — ищем другую машину. Адрес и цена те же.",
                            "Беренсе йөрөтөүсе баш тартты — башҡа машина эҙләйбеҙ. Адрес та, хаҡ та шул уҡ.",
                        ),
                        color = CanonWarn,
                        style = CanonMicro,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = CanonSpace.md, vertical = CanonSpace.sm),
                    )
                }
            }
            SearchOrderSummary(order)
        },
        extra = {
            SearchDetailsCard(nearby = nearby)
            // Другой класс не навязываем сразу. Сервер сам сообщает порог ожидания и
            // реальные доступные варианты; только после него появляется предложение.
            InstantAlternativesBlock(order = order, watchedSec = watchedSec)
        },
        footer = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
            ) {
                SearchActionButton(
                    title = if (stop == TaxiSheetStop.Full) appText("Свернуть", "Йыйыу")
                    else appText("Детали", "Ентекле"),
                    icon = Icons.Default.Info,
                    onClick = {
                        stop = if (stop == TaxiSheetStop.Full) TaxiSheetStop.Half else TaxiSheetStop.Full
                    },
                    modifier = Modifier.weight(1f),
                )
                SearchActionButton(
                    title = appText("Отменить", "Кире алыу"),
                    icon = Icons.Default.Close,
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                )
            }
        },
        overlay = {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = CanonSpace.md, vertical = CanonSpace.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchNearbyPill(nearby, Modifier.weight(1f, fill = false))
                if (embedded) {
                    Spacer(Modifier.width(CanonSpace.sm))
                    Surface(
                        onClick = onCancel,
                        shape = CircleShape,
                        color = CanonSurface,
                        border = BorderStroke(1.dp, CanonBorder),
                        shadowElevation = CanonDepth.raised,
                        modifier = Modifier.size(52.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = appText("Отменить заказ", "Заказды кире алыу"),
                                tint = CanonText,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        },
    )
}

private data class SearchNearbyState(
    val drivers: List<NearbyDriverDto> = emptyList(),
    val loaded: Boolean = false,
    val failed: Boolean = false,
)

/** Опрос машин засыпает вместе с приложением; при сбое пауза растёт до четырёх минут. */
@Composable
private fun rememberSearchNearbyState(order: InstantOrderDto): SearchNearbyState {
    var state by remember(order.id, order.fromLat, order.fromLng) {
        mutableStateOf(SearchNearbyState())
    }
    RepeatWhileVisible(order.id, order.fromLat, order.fromLng) {
        var fails = 0
        while (isActive) {
            ApiClient.getNearbyDrivers(order.fromLat, order.fromLng)
                .onSuccess {
                    state = SearchNearbyState(drivers = it, loaded = true, failed = false)
                    fails = 0
                }
                .onFailure {
                    fails = (fails + 1).coerceAtMost(4)
                    state = state.copy(failed = true)
                }
            delay(15_000L shl fails)
        }
    }
    return state
}

@Composable
private fun SearchNearbyPill(state: SearchNearbyState, modifier: Modifier = Modifier) {
    val text = when {
        state.failed && !state.loaded -> appText("Не удалось проверить машины", "Машиналарҙы тикшереп булманы")
        !state.loaded -> appText("Проверяем машины рядом", "Яҡындағы машиналарҙы тикшерәбеҙ")
        state.drivers.isEmpty() -> appText("Рядом свободных машин нет", "Яҡында буш машина юҡ")
        else -> appText(
            "${state.drivers.size} ${nearbyCarsWordRu(state.drivers.size)} рядом",
            "${state.drivers.size} машина яҡында",
        )
    }
    Surface(
        shape = CircleShape,
        color = CanonSurface,
        border = BorderStroke(1.dp, CanonBorder),
        shadowElevation = CanonDepth.raised,
        modifier = modifier.heightIn(min = 52.dp),
    ) {
        Row(
            Modifier.padding(horizontal = CanonSpace.md, vertical = CanonSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
        ) {
            if (!state.loaded && !state.failed) {
                CircularProgressIndicator(
                    color = CanonGreen2,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(
                            if (state.drivers.isNotEmpty()) CanonGreen2 else CanonMutedStrong,
                            CircleShape,
                        ),
                )
            }
            Text(
                text,
                color = CanonText,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun nearbyCarsWordRu(count: Int): String {
    val n100 = count % 100
    val n10 = count % 10
    return when {
        n100 in 11..14 -> "машин"
        n10 == 1 -> "машина"
        n10 in 2..4 -> "машины"
        else -> "машин"
    }
}

@Composable
private fun searchStatusText(watchedSec: Long): String = when {
    watchedSec < 25L -> appText(
        "Подбираем ближайшего водителя",
        "Яҡындағы йөрөтөүсене эҙләйбеҙ",
    )
    watchedSec < 70L -> appText(
        "Свободных машин рядом мало — продолжаем поиск",
        "Яҡында буш машина аҙ — эҙләүҙе дауам итәбеҙ",
    )
    else -> appText(
        "Ищем дольше обычного — сразу сообщим, когда водитель примет заказ",
        "Ғәҙәттәгенән оҙағыраҡ эҙләйбеҙ — йөрөтөүсе заказды алғас, шунда уҡ хәбәр итәбеҙ",
    )
}

@Composable
private fun SearchOrderSummary(order: InstantOrderDto) {
    val from = order.fromText.ifBlank { appText("Моя позиция", "Минең урыным") }
    val to = order.toText.ifBlank { appText("Точка на карте", "Карталағы нөктә") }
    val carClass = InstantClasses.firstOrNull { it.category == order.category }
        ?.let { appText(it.titleRu, it.titleBa) }
        ?: appText("Эконом", "Эконом")
    val payment = PayMethods.short(order.paymentMethod)

    Surface(
        shape = CanonItemShape,
        color = CanonBg,
        border = BorderStroke(1.dp, CanonBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = CanonSpace.md)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 54.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
            ) {
                TaxiPickupGlyph(
                    modifier = Modifier.size(width = 19.dp, height = 22.dp),
                )
                Text(
                    from,
                    color = CanonText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = CanonMuted,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    to,
                    color = CanonText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider(color = CanonBorder)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 54.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
            ) {
                TaxiCarGlyph(
                    modifier = Modifier.size(width = 25.dp, height = 18.dp),
                )
                Text(carClass, color = CanonText, maxLines = 1)
                Text("•", color = CanonMuted)
                Text(formatTaxiKop(order.passengerPayKop), color = CanonText, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("•", color = CanonMuted)
                Text(
                    payment,
                    color = CanonMutedStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SearchDetailsCard(nearby: SearchNearbyState) {
    Surface(
        shape = CanonItemShape,
        color = CanonMint,
        border = BorderStroke(1.dp, CanonGreen2.copy(alpha = 0.24f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(CanonSpace.md),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.xs),
        ) {
            Text(
                appText("Как идёт поиск", "Эҙләү нисек бара"),
                color = CanonText,
                fontWeight = FontWeight.Bold,
            )
            Text(
                when {
                    nearby.failed && !nearby.loaded -> appText(
                        "Связь с картой прервалась. Заказ остаётся активным, проверим машины снова автоматически.",
                        "Карта менән бәйләнеш өҙөлдө. Заказ әүҙем ҡала, машиналарҙы автоматик рәүештә ҡабат тикшерәбеҙ.",
                    )
                    nearby.loaded && nearby.drivers.isEmpty() -> appText(
                        "Расширяем поиск дальше от точки подачи. Как только водитель примет заказ, сразу покажем машину.",
                        "Эҙләүҙе килеү нөктәһенән алыҫыраҡ киңәйтәбеҙ. Йөрөтөүсе заказды алғас, машинаны шунда уҡ күрһәтербеҙ.",
                    )
                    else -> appText(
                        "Заказ видят свободные водители рядом. Как только один из них примет его, покажем машину и время подачи.",
                        "Заказды яҡындағы буш йөрөтөүселәр күрә. Береһе заказды алғас, машинаны һәм килеү ваҡытын күрһәтербеҙ.",
                    )
                },
                style = CanonBody,
                color = CanonMutedStrong,
            )
        }
    }
}

@Composable
private fun SearchActionButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shape = CanonItemShape,
        border = BorderStroke(1.dp, CanonBorder),
    ) {
        Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(CanonSpace.sm))
        Text(
            title,
            color = CanonText,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Пульс даёт живой, но спокойный признак работы поиска. */
@Composable
private fun SearchRadar(modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "search")
    val pulse by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_150, easing = LinearEasing), RepeatMode.Reverse),
        label = "searchPulse",
    )
    Box(modifier.size(154.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size((76 + pulse * 74).dp)
                .background(CanonGreen2.copy(alpha = 0.09f + pulse * 0.05f), CircleShape),
        )
        Box(
            Modifier
                .size((52 + pulse * 42).dp)
                .background(CanonGreen2.copy(alpha = 0.10f + pulse * 0.05f), CircleShape),
        )
        TaxiPickupMapMarker(
            contentDescription = appText("Точка поиска машины", "Машина эҙләү нөктәһе"),
        )
    }
}
