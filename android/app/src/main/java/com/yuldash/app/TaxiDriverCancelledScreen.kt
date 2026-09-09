package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.InstantOrderDto
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/**
 * Закрытый без поездки заказ глазами таксиста — утверждённая связка C + A.
 *
 * Маршрут и факты помогают восстановить контекст для разбора, крупный финансовый блок
 * честно показывает только зафиксированную сервером плату: деньги напрямую не списываются.
 */
@Composable
internal fun TaxiDriverCancelledScreen(
    order: InstantOrderDto,
    onReturnToLine: () -> Unit,
    onShiftFinished: () -> Unit,
    finishShift: suspend () -> Result<Unit> = { ApiClient.setOnline(false) },
) {
    val scope = rememberCoroutineScope()
    var shiftEnding by remember(order.id) { mutableStateOf(false) }
    var showProblemChoice by rememberSaveable(order.id) { mutableStateOf(false) }
    var showReport by rememberSaveable(order.id) { mutableStateOf(false) }
    var showDispute by rememberSaveable(order.id) { mutableStateOf(false) }
    var disputeFiled by rememberSaveable(order.id) { mutableStateOf(false) }
    var successText by remember(order.id) { mutableStateOf<String?>(null) }
    var errorText by remember(order.id) { mutableStateOf<String?>(null) }

    val shiftFail = appText(
        "Не получилось завершить смену. Проверь сеть и повтори.",
        "Сменаны тамамлап булманы. Селтәрҙе тикшереп ҡабатла.",
    )
    val reportSent = appText(
        "Спасибо. Мы проверим обращение.",
        "Рәхмәт. Мөрәжәғәтте тикшерәсәкбеҙ.",
    )
    val reportFail = appText(
        "Не получилось отправить обращение. Проверь сеть и повтори.",
        "Мөрәжәғәтте ебәреп булманы. Селтәрҙе тикшереп ҡабатла.",
    )
    val disputeSent = appText(
        "Разбор открыт. Мы сообщим о решении.",
        "Ҡарау асылды. Ҡарар тураһында хәбәр итербеҙ.",
    )

    Scaffold(
        containerColor = CanonBg,
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(CanonBg)
                    .navigationBarsPadding()
                    .padding(start = CanonSpace.lg, top = CanonSpace.sm, end = CanonSpace.lg, bottom = CanonSpace.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(CanonSpace.xs),
            ) {
                AppButton(
                    text = appText("Вернуться на линию", "Линияға ҡайтыу"),
                    onClick = onReturnToLine,
                    enabled = !shiftEnding,
                    height = 56.dp,
                )
                TextButton(
                    onClick = {
                        if (shiftEnding) return@TextButton
                        shiftEnding = true
                        errorText = null
                        scope.launch {
                            finishShift()
                                .onSuccess { onShiftFinished() }
                                .onFailure { errorText = serverSaid(it, shiftFail) }
                            shiftEnding = false
                        }
                    },
                    enabled = !shiftEnding,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    if (shiftEnding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = CanonGreen2,
                        )
                    } else {
                        Text(
                            appText("Завершить смену", "Сменаны тамамлау"),
                            style = CanonBodyStrong,
                            color = CanonGreen2,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CanonSpace.lg)
                .padding(bottom = CanonSpace.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
        ) {
            TaxiDriverCancelledHeader(order, Modifier.appearIn(0))
            TaxiDriverCancelledRoute(order, Modifier.appearIn(1))
            TaxiDriverCancelledFacts(order, Modifier.appearIn(2))
            TaxiDriverCancelledMoney(order, Modifier.appearIn(3))

            AnimatedVisibility(
                visible = successText != null,
                enter = fadeIn(tween(CanonMotion.QUICK)) + expandVertically(tween(CanonMotion.QUICK)),
                exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.QUICK)),
            ) {
                Surface(color = CanonMint, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        successText.orEmpty(),
                        style = CanonCaption,
                        color = CanonGreen2,
                        modifier = Modifier.fillMaxWidth().padding(CanonSpace.md),
                    )
                }
            }

            AnimatedVisibility(
                visible = errorText != null,
                enter = fadeIn(tween(CanonMotion.QUICK)) + expandVertically(tween(CanonMotion.QUICK)),
                exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.QUICK)),
            ) {
                Surface(color = CanonDangerBg, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        errorText.orEmpty(),
                        style = CanonCaption,
                        color = CanonRed,
                        modifier = Modifier.fillMaxWidth().padding(CanonSpace.md),
                    )
                }
            }

            TaxiDriverCancelledProblemRow(
                filed = disputeFiled,
                onClick = { showProblemChoice = true },
                modifier = Modifier.appearIn(4),
            )
        }
    }

    if (showProblemChoice) {
        AlertDialog(
            onDismissRequest = { showProblemChoice = false },
            containerColor = CanonSurface,
            shape = CanonCardShape,
            title = {
                Text(appText("Что случилось?", "Нимә булды?"), style = CanonTitle, color = CanonText)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                    TaxiDriverCancelledDialogChoice(
                        icon = Icons.Outlined.ReportProblem,
                        title = appText("Сообщить о нарушении", "Боҙоу тураһында хәбәр итеү"),
                        description = appText("Анонимно, проверит человек", "Аноним, кеше тикшерәсәк"),
                        onClick = { showProblemChoice = false; showReport = true },
                    )
                    if ((order.passengerId ?: 0) > 0) {
                        TaxiDriverCancelledDialogChoice(
                            icon = Icons.Default.Verified,
                            title = appText("Открыть разбор", "Ҡарауҙы асыу"),
                            description = appText("Выслушаем обе стороны", "Ике яҡты ла тыңлаясаҡбыҙ"),
                            onClick = { showProblemChoice = false; showDispute = true },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showProblemChoice = false }) {
                    Text(appText("Закрыть", "Ябыу"), color = CanonGreen2)
                }
            },
        )
    }

    if (showReport) {
        ReportCategoryDialog(
            title = appText("Сообщить о нарушении", "Боҙоу тураһында хәбәр итеү"),
            categories = reportCategoriesPassenger(),
            onDismiss = { showReport = false },
            onSend = { category, details ->
                showReport = false
                scope.launch {
                    ApiClient.reportUser(reason = details, category = category, orderId = order.id)
                        .onSuccess { errorText = null; successText = reportSent }
                        .onFailure { errorText = serverSaid(it, reportFail) }
                }
            },
        )
    }

    val passengerId = order.passengerId ?: 0
    if (showDispute && passengerId > 0) {
        FileIncidentDialog(
            respondentId = passengerId,
            respondentName = order.passengerName.ifBlank { appText("Пассажир", "Пассажир") },
            orderId = order.id,
            onDismiss = { showDispute = false },
            onFiled = {
                showDispute = false
                disputeFiled = true
                errorText = null
                successText = disputeSent
            },
        )
    }
}

@Composable
private fun TaxiDriverCancelledHeader(order: InstantOrderDto, modifier: Modifier = Modifier) {
    val title = when {
        order.noShow -> appText("Пассажир не вышел", "Пассажир сыҡманы")
        order.cancelBy == "passenger" -> appText("Пассажир отменил заказ", "Пассажир заказды кире алды")
        else -> appText("Заказ отменён", "Заказ кире алынды")
    }
    val status = when {
        order.noShow -> appText("Заказ закрыт", "Заказ ябылды")
        order.cancelBy == "passenger" && order.cancelFeeKop > 0 -> appText("Поздняя отмена", "Һуң кире алыу")
        order.cancelBy == "passenger" -> appText("Отмена без платы", "Түләүһеҙ кире алыу")
        order.cancelBy == "driver" -> appText("Отменено тобой", "Һин кире алдың")
        else -> appText("Заказ закрыт", "Заказ ябылды")
    }
    Column(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = CanonSpace.lg, bottom = CanonSpace.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
    ) {
        // Отмена — новость нейтральная. Зелёная галочка здесь читалась как «всё отлично»
        // ровно в тот момент, когда водитель зря съездил на подачу.
        Surface(shape = CircleShape, color = CanonSurface, modifier = Modifier.size(64.dp)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    if (order.noShow) Icons.Default.EventBusy else Icons.Outlined.Info,
                    contentDescription = null,
                    tint = CanonMutedStrong,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        Text(title, style = CanonHeading, color = CanonText, textAlign = TextAlign.Center)
        Text(
            "$status · ${appText("Заказ № ${order.id}", "Заказ № ${order.id}")}",
            style = CanonCaption,
            color = CanonMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TaxiDriverCancelledRoute(order: InstantOrderDto, modifier: Modifier = Modifier) {
    Surface(
        color = CanonMint,
        shape = CanonCardShape,
        border = BorderStroke(1.dp, CanonGreen2.copy(alpha = 0.20f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        MobilityRouteTimeline(
            from = order.fromText,
            to = order.toText,
            fromLabel = appText("Точка подачи", "Килеү нөктәһе"),
            toLabel = appText("Куда ехали", "Ҡайҙа бара инегеҙ"),
            modifier = Modifier.padding(CanonSpace.lg),
        )
    }
}

@Composable
private fun TaxiDriverCancelledFacts(order: InstantOrderDto, modifier: Modifier = Modifier) {
    val arrival = taxiDriverCancelledTime(order.waitingStartedAt)
    val waitMinutes = taxiDriverCancelledRequiredWaitMinutes(order)
    val classInfo = InstantClasses.firstOrNull { it.category == order.category }
    val className = classInfo?.let { appText(it.titleRu, it.titleBa) } ?: appText("Эконом", "Эконом")

    Surface(
        color = CanonSurface,
        shape = CanonCardShape,
        border = BorderStroke(1.dp, CanonBorder),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(CanonSpace.md),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
        ) {
            TaxiDriverCancelledFactRow(
                firstIcon = if (arrival != null) Icons.Default.Schedule else Icons.Default.CheckCircle,
                firstLabel = if (arrival != null) appText("Прибыл", "Килде") else appText("Заказ", "Заказ"),
                firstValue = arrival ?: "№ ${order.id}",
                secondIcon = if (order.noShow) Icons.Default.EventBusy else Icons.Outlined.ReportProblem,
                secondLabel = if (order.noShow) appText("Ожидание", "Көтөү") else appText("Отменил", "Кире алды"),
                secondValue = if (order.noShow) {
                    waitMinutes?.let { appText("$it+ мин", "$it+ мин") }
                        ?: appText("Завершено", "Тамамланған")
                } else {
                    when (order.cancelBy) {
                        "passenger" -> appText("Пассажир", "Пассажир")
                        "driver" -> appText("Ты", "Һин")
                        else -> appText("Система", "Система")
                    }
                },
            )
            TaxiDriverCancelledFactRow(
                firstIcon = Icons.Default.LocalTaxi,
                firstLabel = appText("Тариф", "Тариф"),
                firstValue = className,
                secondIcon = PayMethods.icon(order.paymentMethod),
                secondLabel = appText("Оплата", "Түләү"),
                secondValue = taxiDriverPayMethodShortLabel(order.paymentMethod),
            )
        }
    }
}

@Composable
private fun TaxiDriverCancelledFactRow(
    firstIcon: ImageVector,
    firstLabel: String,
    firstValue: String,
    secondIcon: ImageVector,
    secondLabel: String,
    secondValue: String,
) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
    ) {
        TaxiDriverCancelledFact(firstIcon, firstLabel, firstValue, Modifier.weight(1f).fillMaxHeight())
        TaxiDriverCancelledFact(secondIcon, secondLabel, secondValue, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun TaxiDriverCancelledFact(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(color = CanonBg, shape = CanonItemShape, modifier = modifier.heightIn(min = 88.dp)) {
        Row(
            Modifier.fillMaxSize().padding(CanonSpace.sm),
            horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = CanonMint, modifier = Modifier.size(40.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(21.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                Text(label, style = CanonMicro, color = CanonMuted, maxLines = 2)
                Text(
                    value,
                    style = CanonBodyStrong,
                    color = CanonText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TaxiDriverCancelledMoney(order: InstantOrderDto, modifier: Modifier = Modifier) {
    val hasFee = order.cancelFeeKop > 0
    val label = if (hasFee) {
        appText("Плата за подачу зафиксирована", "Килеү хаҡы теркәлде")
    } else {
        appText("Без компенсации", "Компенсацияһыҙ")
    }
    val explanation = when {
        hasFee -> appText(
            "Пассажиру выставлена эта сумма. Деньги не списываются автоматически.",
            "Пассажирға был сумма ҡуйылды. Аҡса автоматик рәүештә алынмай.",
        )
        order.noShow -> appText(
            "Сервер не указал плату за подачу. Если это ошибка — открой проблему с заказом.",
            "Сервер килеү хаҡын күрһәтмәне. Был хата булһа — заказ буйынса проблема ас.",
        )
        order.cancelBy == "passenger" -> appText(
            "Отмена произошла до платного окна.",
            "Заказ түләүле ваҡыт башланғансы кире алынды.",
        )
        order.cancelBy == "driver" -> appText(
            "Заказ отменён тобой — плата пассажиру не выставляется.",
            "Заказды һин кире алдың — пассажирға түләү ҡуйылмай.",
        )
        else -> appText(
            "Плата за отмену не зафиксирована.",
            "Кире алыу өсөн түләү теркәлмәгән.",
        )
    }
    // Зелёным празднуем только реальные деньги. «Без компенсации» — нейтральная карточка:
    // цвет успеха на нулевой сумме читается как издёвка.
    Surface(
        color = if (hasFee) CanonMint else CanonSurface,
        shape = CanonCardShape,
        border = BorderStroke(
            1.dp,
            if (hasFee) CanonGreen2.copy(alpha = 0.20f) else CanonBorder,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(CanonSpace.lg),
            horizontalArrangement = Arrangement.spacedBy(CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = if (hasFee) CanonGreen2 else CanonBg,
                modifier = Modifier.size(54.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        if (hasFee) Icons.Default.Payments else Icons.Outlined.Info,
                        contentDescription = null,
                        tint = if (hasFee) CanonOnFilled else CanonMutedStrong,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                Text(
                    label,
                    style = CanonCaption,
                    color = if (hasFee) CanonGreen2 else CanonMutedStrong,
                    fontWeight = FontWeight.Bold,
                )
                Text(formatTaxiKop(order.cancelFeeKop), style = CanonDisplay, color = CanonText)
                Text(explanation, style = CanonMicro, color = CanonMutedStrong)
            }
        }
    }
}

@Composable
private fun TaxiDriverCancelledProblemRow(
    filed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, CanonBorder),
        modifier = modifier.fillMaxWidth().heightIn(min = 76.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(CanonSpace.md),
            horizontalArrangement = Arrangement.spacedBy(CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = CanonMint, modifier = Modifier.size(44.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.ReportProblem, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(22.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                Text(appText("Проблема с заказом", "Заказ менән проблема"), style = CanonBodyStrong, color = CanonText)
                Text(
                    if (filed) appText("Разбор уже открыт", "Ҡарау асылған")
                    else appText("Сообщить или открыть разбор", "Хәбәр итеү йәки ҡарау асыу"),
                    style = CanonMicro,
                    color = CanonMuted,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun TaxiDriverCancelledDialogChoice(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = CanonBg,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, CanonBorder),
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(CanonSpace.md),
            horizontalArrangement = Arrangement.spacedBy(CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                Text(title, style = CanonBodyStrong, color = CanonText)
                Text(description, style = CanonMicro, color = CanonMuted)
            }
        }
    }
}

private fun taxiDriverCancelledInstant(iso: String?): Instant? {
    val value = iso?.trim().orEmpty()
    if (value.isEmpty()) return null
    return runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value.removeSuffix("Z")).toInstant(ZoneOffset.UTC) }.getOrNull()
}

private fun taxiDriverCancelledTime(iso: String?): String? = taxiDriverCancelledInstant(iso)
    ?.atZone(ZoneId.systemDefault())
    ?.format(DateTimeFormatter.ofPattern("HH:mm"))

private fun taxiDriverCancelledRequiredWaitMinutes(order: InstantOrderDto): Int? {
    val start = taxiDriverCancelledInstant(order.waitingStartedAt) ?: return null
    val end = taxiDriverCancelledInstant(order.noShowAt) ?: return null
    val seconds = Duration.between(start, end).seconds
    if (seconds <= 0L) return null
    return ceil(seconds / 60.0).toInt().coerceAtLeast(1)
}
