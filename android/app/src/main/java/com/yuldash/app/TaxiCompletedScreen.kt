package com.yuldash.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.InstantOrderDto
import kotlinx.coroutines.launch

/**
 * Первый экран после завершения поездки — утверждённый вариант B.
 *
 * Здесь одна главная задача: спокойно оценить поездку. Финансовая расшифровка, спор и
 * служебные действия не удалены — они живут в отдельном чеке C. Так человек не получает
 * длинную «простыню» в ту секунду, когда только вышел из машины.
 */
@Composable
internal fun TaxiPassengerCompletedScreen(
    order: InstantOrderDto,
    onClose: () -> Unit,
    onNewOrder: () -> Unit,
    onOpenReceipt: () -> Unit,
    onOpenChat: () -> Unit,
    rateOrder: suspend (Int, Int, String) -> Result<Unit> = { id, stars, tags ->
        ApiClient.rateInstantOrder(id, stars, tags)
    },
    openLostItem: suspend (Int) -> Result<Unit> = { id ->
        ApiClient.instantLostItem(id).map { Unit }
    },
) {
    val scope = rememberCoroutineScope()
    var stars by rememberSaveable(order.id) { mutableIntStateOf(0) }
    var selectedTags by rememberSaveable(order.id) { mutableStateOf(emptyList<String>()) }
    var sending by rememberSaveable(order.id) { mutableStateOf(false) }
    var sent by rememberSaveable(order.id) { mutableStateOf(false) }
    var lostBusy by rememberSaveable(order.id) { mutableStateOf(false) }
    var errorText by remember(order.id) { mutableStateOf<String?>(null) }
    val rateFail = appText(
        "Не получилось отправить оценку. Проверь сеть и повтори.",
        "Баһаны ебәреп булманы. Селтәрҙе тикшереп ҡабатла.",
    )
    val lostFail = appText(
        "Не получилось открыть чат. Проверь сеть и повтори.",
        "Чатты асып булманы. Селтәрҙе тикшереп ҡабатла.",
    )

    Column(Modifier.fillMaxSize()) {
        TaxiCompletedTopBar(onClose)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = CanonSpace.lg, vertical = CanonSpace.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CanonSpace.lg),
        ) {
            TaxiCompletedPerson(order)

            AnimatedContent(
                targetState = sent,
                transitionSpec = {
                    (fadeIn(tween(CanonMotion.NORMAL)) + scaleIn(initialScale = 0.96f)) togetherWith
                        (fadeOut(tween(CanonMotion.QUICK)) + scaleOut(targetScale = 0.96f))
                },
                label = "taxiRatingResult",
            ) { completed ->
                if (completed) {
                    TaxiRatingThanks(stars)
                } else {
                    TaxiRatingPicker(
                        stars = stars,
                        selectedTags = selectedTags,
                        onStars = { value ->
                            stars = value
                            selectedTags = emptyList()
                            errorText = null
                        },
                        onTag = { code ->
                            selectedTags = if (code in selectedTags) selectedTags - code else selectedTags + code
                        },
                    )
                }
            }

            TaxiCompletedMoney(order)
            TaxiPromoPayRow(order = order, forDriver = false)

            AppButton(
                text = if (sent) appText("Готово", "Әҙер")
                else appText("Отправить оценку", "Баһаны ебәреү"),
                onClick = {
                    if (sent) {
                        onNewOrder()
                    } else if (stars > 0 && !sending) {
                        sending = true
                        scope.launch {
                            rateOrder(order.id, stars, selectedTags.joinToString(","))
                                .onSuccess { sent = true; errorText = null }
                                .onFailure { errorText = serverSaid(it, rateFail) }
                            sending = false
                        }
                    }
                },
                loading = sending,
                enabled = sent || (stars > 0 && !sending),
                style = AppButtonStyle.Primary,
                height = 54.dp,
            )

            if (!sent) {
                TextButton(onClick = onNewOrder, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(appText("Пропустить", "Үткәреп ебәреү"), style = CanonBodyStrong, color = CanonGreen2)
                }
            }

            AnimatedVisibility(visible = errorText != null, enter = fadeIn(), exit = fadeOut()) {
                Surface(color = CanonDangerBg, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        errorText.orEmpty(),
                        style = CanonCaption,
                        color = CanonRed,
                        modifier = Modifier.fillMaxWidth().padding(CanonSpace.md),
                    )
                }
            }

            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
            ) {
                TaxiCompletedActionRow(
                    icon = Icons.Default.ReceiptLong,
                    label = appText("Чек и детали", "Чек һәм ентеклектәр"),
                    description = appText("Сумма, маршрут и помощь", "Сумма, юл һәм ярҙам"),
                    onClick = onOpenReceipt,
                )
                TaxiCompletedActionRow(
                    icon = Icons.Default.Search,
                    label = appText("Забыли вещь?", "Әйбер оноттоңмо?"),
                    description = appText("Откроем чат поездки на 48 часов", "Сәфәр чатын 48 сәғәткә асабыҙ"),
                    busy = lostBusy,
                    onClick = {
                        if (!lostBusy) {
                            lostBusy = true
                            scope.launch {
                                openLostItem(order.id)
                                    .onSuccess { errorText = null; onOpenChat() }
                                    .onFailure { errorText = serverSaid(it, lostFail) }
                                lostBusy = false
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TaxiCompletedTopBar(onClose: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = CanonSpace.md),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onClose,
            shape = CircleShape,
            color = CanonSurface,
            border = BorderStroke(1.dp, CanonBorder),
            shadowElevation = CanonDepth.raised,
            modifier = Modifier.align(Alignment.CenterStart).size(48.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = appText("Закрыть", "Ябыу"),
                    tint = CanonText,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(appText("Поездка завершена", "Сәфәр тамамланды"), style = CanonCaption, color = CanonMuted)
    }
}

@Composable
private fun TaxiCompletedPerson(order: InstantOrderDto) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box {
            Surface(shape = CircleShape, color = CanonMint, modifier = Modifier.size(76.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        order.driverName.trim().take(1).uppercase().ifBlank { "?" },
                        style = CanonDisplay,
                        color = CanonGreen2,
                    )
                }
            }
            if (order.driverVerified) {
                Surface(
                    shape = CircleShape,
                    color = CanonGreen2,
                    modifier = Modifier.align(Alignment.BottomEnd).size(26.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Verified,
                            contentDescription = appText("Водитель проверен", "Йөрөтөүсе тикшерелгән"),
                            tint = CanonOnFilled,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(CanonSpace.lg))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
            Text(
                order.driverName.ifBlank { appText("Водитель", "Йөрөтөүсе") },
                style = CanonTitle,
                color = CanonText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val car = buildList {
                if (order.driverCar.isNotBlank()) add(order.driverCar)
                if (order.driverPlate.isNotBlank()) add(order.driverPlate)
            }.joinToString("  ·  ")
            if (car.isNotBlank()) {
                Text(car, style = CanonBody, color = CanonMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TaxiRatingPicker(
    stars: Int,
    selectedTags: List<String>,
    onStars: (Int) -> Unit,
    onTag: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
    ) {
        Text(
            appText("Как прошла поездка?", "Сәфәр нисек үтте?"),
            style = CanonTitle,
            color = CanonText,
            textAlign = TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            (1..5).forEach { value ->
                val selected = value <= stars
                val scale by animateFloatAsState(
                    targetValue = if (selected) 1f else 0.9f,
                    animationSpec = tween(CanonMotion.QUICK),
                    label = "postTripStar$value",
                )
                Box(
                    Modifier
                        .size(52.dp)
                        .semantics { role = Role.Button }
                        .clickable { onStars(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (selected) Icons.Default.Star else Icons.Outlined.StarOutline,
                        contentDescription = starsText(value),
                        tint = if (selected) CanonStar else CanonMuted,
                        modifier = Modifier.size(40.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                    )
                }
            }
        }
        AnimatedVisibility(visible = stars > 0) {
            val tags = if (stars >= 4) positiveTaxiRatingTags() else negativeTaxiRatingTags()
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
                maxItemsInEachRow = 3,
            ) {
                tags.forEach { tag ->
                    val selected = tag.code in selectedTags
                    Surface(
                        onClick = { onTag(tag.code) },
                        shape = CircleShape,
                        color = if (selected) CanonMint else CanonBg,
                        border = BorderStroke(1.dp, if (selected) CanonGreen2 else CanonBorder),
                        modifier = Modifier.padding(horizontal = CanonSpace.xs).heightIn(min = 48.dp),
                    ) {
                        Box(Modifier.padding(horizontal = CanonSpace.md), contentAlignment = Alignment.Center) {
                            Text(tag.label, style = CanonCaption, color = if (selected) CanonGreen2 else CanonText)
                        }
                    }
                }
            }
        }
        Text(
            appText(
                "Оценка анонимна — водитель увидит только средний рейтинг.",
                "Баһа аноним — йөрөтөүсе тик уртаса рейтингты күрер.",
            ),
            style = CanonMicro,
            color = CanonMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TaxiRatingThanks(stars: Int) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 184.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = CanonMint, modifier = Modifier.size(64.dp)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.size(CanonSpace.md))
        Text(appText("Спасибо за оценку", "Баһа өсөн рәхмәт"), style = CanonTitle, color = CanonText)
        Text(starsText(stars), style = CanonCaption, color = CanonMuted)
    }
}

@Composable
private fun TaxiCompletedMoney(order: InstantOrderDto) {
    Surface(
        shape = CanonItemShape,
        color = CanonSurface,
        border = BorderStroke(1.dp, CanonBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(CanonSpace.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                Text(appText("Итого", "Бөтәһе"), style = CanonHeading, color = CanonText)
                Text(payMethodLabel(order.paymentMethod), style = CanonCaption, color = CanonMuted)
            }
            Text(formatTaxiKop(order.passengerPayKop), style = CanonDisplay, color = CanonText)
        }
    }
}

@Composable
private fun TaxiCompletedActionRow(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    busy: Boolean = false,
) {
    Surface(
        onClick = onClick,
        enabled = !busy,
        shape = CanonItemShape,
        color = CanonSurface,
        border = BorderStroke(1.dp, CanonBorder),
        modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = CanonSpace.md, vertical = CanonSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CanonSpace.md),
        ) {
            Surface(shape = CircleShape, color = CanonMint, modifier = Modifier.size(40.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CanonGreen2)
                    } else {
                        Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                Text(label, style = CanonBodyStrong, color = CanonText)
                Text(description, style = CanonMicro, color = CanonMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(22.dp))
        }
    }
}

private data class TaxiRatingTag(val code: String, val label: String)

@Composable
private fun positiveTaxiRatingTags() = listOf(
    TaxiRatingTag("polite", appText("Вежливо", "Әҙәпле")),
    TaxiRatingTag("safe", appText("Аккуратно", "Иғтибарлы")),
    TaxiRatingTag("clean", appText("Чистая машина", "Таҙа машина")),
)

@Composable
private fun negativeTaxiRatingTags() = listOf(
    TaxiRatingTag("late", appText("Опоздание", "Һуңланы")),
    TaxiRatingTag("rude", appText("Грубость", "Ҡаты мөғәмәлә")),
    TaxiRatingTag("unsafe", appText("Опасное вождение", "Хәүефле йөрөтөү")),
)
