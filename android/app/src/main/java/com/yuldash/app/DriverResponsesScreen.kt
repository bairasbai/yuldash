package com.yuldash.app

// ═══════════════════ «Мои отклики» (водитель): торг о цене ═══════════════════
// Водитель откликался на заявку и дальше не видел ничего: пассажир мог ответить встречной ценой,
// а узнать об этом было можно только из пуша — пропустил уведомление, потерял поездку.
// Здесь он видит все свои отклики, чью сейчас очередь ходить, как шёл торг, и может принять
// встречную цену или предложить свою.
//
// ── Как читается экран (правки 2026-07-30) ─────────────────────────────────
// Водитель открывает его между заказами, на ходу, одной рукой — значит ответ «где мой ход»
// должен приходить раньше, чем он успеет вчитаться:
//  • в шапке — счётчик «Твой ход: N», он же единственный повод сюда зайти;
//  • в карточке порядок жёсткий: пилюля состояния → цена на столе → твой комментарий →
//    плашка с ходом торга → действия (золото — согласие, контур — своя цена, тихо — отказ);
//  • типографика и сетка — общие для обеих сторон торга (Bargain*, см. BargainUi.kt),
//    чтобы пассажирский экран и водительский не разъехались по виду.

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ResponseDto
import kotlinx.coroutines.launch

@Composable
internal fun DriverResponsesScreen(onBack: () -> Unit, onOpenTrip: (Int) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf<List<ResponseDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var counterFor by remember { mutableStateOf<ResponseDto?>(null) }
    val failMsg = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")

    LaunchedEffect(tick) {
        loading = true; error = false
        ApiClient.getMyResponses().onSuccess { rows = it }.onFailure { error = true }
        loading = false
    }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Мои отклики", "Минең яуаптарым"), onBack) }) { padding ->
        // Торг ведут двое: пассажир отвечает на твою цену, пока ты смотришь этот экран.
        // Без жеста единственным способом увидеть ответ было выйти и зайти заново.
        AppPullRefresh(
            refreshing = loading && rows.isNotEmpty(),
            onRefresh = { tick++ },
            modifier = Modifier.padding(padding),
        ) {
        DriverResponsesContent(
            loading = loading,
            error = error,
            responses = rows,
            busy = busy,
            onRetry = { tick++ },
            onAccept = { r ->
                if (busy) return@DriverResponsesContent
                busy = true; val id = r.id
                scope.launch {
                    ApiClient.acceptResponse(id)
                        .onSuccess { bid -> busy = false; tick++; onOpenTrip(bid) }
                        .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: failMsg, Toast.LENGTH_LONG).show(); busy = false }
                }
            },
            onCounter = { r -> counterFor = r },
            onDecline = { r ->
                if (busy) return@DriverResponsesContent
                busy = true; val id = r.id
                scope.launch {
                    ApiClient.declineResponse(id)
                        .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: failMsg, Toast.LENGTH_SHORT).show() }
                    busy = false; tick++
                }
            },
        )
        }
    }

    counterFor?.let { target ->
        CounterPriceDialog(
            current = target.onTable,
            roundsLeft = (BARGAIN_MAX_TOTAL_UI - target.bargainRounds).coerceAtLeast(1),
            busy = busy,
            onDismiss = { counterFor = null },
            onSend = { price ->
                busy = true; val id = target.id
                scope.launch {
                    ApiClient.counterOffer(id, price)
                        .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: failMsg, Toast.LENGTH_LONG).show() }
                    busy = false; counterFor = null; tick++
                }
            },
        )
    }
}

/** Чистый рендер: все состояния (загрузка / ошибка+повтор / пусто / список) — данные параметрами. */
@Composable
internal fun DriverResponsesContent(
    loading: Boolean,
    error: Boolean,
    responses: List<ResponseDto>,
    busy: Boolean,
    onRetry: () -> Unit,
    onAccept: (ResponseDto) -> Unit,
    onCounter: (ResponseDto) -> Unit = {},
    onDecline: (ResponseDto) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Сколько откликов ждут именно тебя — ради этого числа сюда и заходят. Считаем один раз
    // на список, а не на каждую перерисовку.
    val myTurn = remember(responses) { responses.count { it.canAccept } }
    // Пока уходит запрос, список приглушаем: видно, что ответ ещё в пути, и второй раз не жмут.
    val liveness by animateFloatAsState(if (busy) 0.6f else 1f, tween(CanonMotion.QUICK), label = "responsesBusy")

    LazyColumn(
        modifier
            .graphicsLayer { alpha = liveness }
            .padding(horizontal = BargainRowPad),
        verticalArrangement = Arrangement.spacedBy(BargainGap),
        contentPadding = PaddingValues(top = BargainGapTight, bottom = 24.dp),
    ) {
        item(key = "lede") { ResponsesLede(myTurn) }
        when {
            // Скелетон той же формы, что карточка: список не «прыгает», когда данные придут.
            // Показываем его ТОЛЬКО когда показывать больше нечего — уже загруженные отклики
            // не прячем за скелетоном при каждом обновлении (правило UiKit).
            loading && responses.isEmpty() -> items(3) { i -> Box(Modifier.appearIn(i)) { ResponseSkeletonCard() } }
            error && responses.isEmpty() -> item(key = "error") {
                Box(Modifier.appearIn(0)) {
                    AppErrorState(
                        onRetry = onRetry,
                        title = appText("Отклики не загрузились", "Яуаптар йөкләнмәне"),
                        text = appText(
                            "Проверь интернет и повтори — торг никуда не денется.",
                            "Интернетты тикшереп ҡабатла — һатыулашыу юғалмай.",
                        ),
                    )
                }
            }
            responses.isEmpty() -> item(key = "empty") {
                Box(Modifier.appearIn(0)) {
                    AppEmptyState(
                        title = appText("Ты пока никому не откликнулся", "Һин әле бер кемгә лә яуап бирмәнең"),
                        text = appText(
                            "Открой «Заявки пассажиров» и предложи свою цену — торг начинается с первого хода.",
                            "«Пассажир заявкалары»н асып, үҙ хаҡыңды тәҡдим ит — һатыулашыу беренсе сираттан башлана.",
                        ),
                        icon = Icons.Default.Handshake,
                        actionLabel = appText("Обновить", "Яңыртыу"),
                        onAction = onRetry,
                    )
                }
            }
            else -> {
                // Обновление не прошло, но отклики на экране есть — говорим об этом строкой,
                // а не подменой всего списка карточкой ошибки.
                if (error) item(key = "stale") { ResponsesStaleNotice(onRetry) }
                // Каскад появления ограничен: на длинном списке ждать полсекунды нижнюю карточку незачем.
                itemsIndexed(responses, key = { _, r -> r.id }) { i, r ->
                    DriverResponseCard(r, i.coerceAtMost(6), busy, onAccept, onCounter, onDecline)
                }
            }
        }
    }
}

/** Шапка экрана: зачем он нужен + сколько откликов ждут твоего хода прямо сейчас. */
@Composable
private fun ResponsesLede(myTurn: Int) {
    Column(
        Modifier.appearIn(0),
        verticalArrangement = Arrangement.spacedBy(BargainGapTight),
    ) {
        Text(
            appText(
                "Здесь видно, где пассажир ответил на твою цену. Торгуемся по очереди — можно принять или предложить своё.",
                "Бында пассажир хаҡыңа яуап биргән урындар күренә. Сиратлап һатыулашабыҙ — ҡабул итергә йәки үҙеңдекен тәҡдим итергә була.",
            ),
            color = CanonMutedStrong,
            fontSize = BargainBody,
            lineHeight = BargainBodyLine,
        )
        // Пилюля появляется и исчезает плавно, вместе с высотой шапки — иначе после каждого
        // обновления список дёргался бы вниз-вверх ровно на её высоту.
        AnimatedVisibility(
            visible = myTurn > 0,
            enter = fadeIn(tween(CanonMotion.NORMAL)) + expandVertically(tween(CanonMotion.NORMAL)),
            exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.QUICK)),
        ) {
            Surface(color = CanonMint, shape = CircleShape) {
                Row(
                    Modifier.padding(horizontal = BargainGap, vertical = BargainGapTight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Bolt,
                        contentDescription = null,   // текст рядом называет то же самое
                        tint = CanonGreen2,
                        modifier = Modifier.size(BargainIconSmall),
                    )
                    Spacer(Modifier.width(BargainGapHair))
                    AnimatedContent(
                        targetState = myTurn,
                        transitionSpec = { fadeIn(tween(CanonMotion.NORMAL)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
                        label = "myTurnCount",
                    ) { count ->
                        Text(
                            appText("Твой ход: $count", "Һинең сират: $count"),
                            color = CanonGreen2,
                            fontSize = BargainMeta,
                            lineHeight = BargainMetaLine,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Обновление не прошло, но старые отклики на экране. Прятать их за карточкой ошибки нельзя:
 * человек потеряет и то, что уже видел. Список остаётся, сверху — честная строка с повтором.
 * Вся строка и есть кнопка: тач-цель 48dp, промахнуться нечем.
 */
@Composable
private fun ResponsesStaleNotice(onRetry: () -> Unit) {
    Surface(
        onClick = onRetry,
        color = CanonWarnBg,
        shape = CanonItemShape,
        modifier = Modifier.fillMaxWidth().heightIn(min = BargainTouch).appearIn(0),
    ) {
        Row(
            Modifier.padding(horizontal = BargainRowPad, vertical = BargainGapTight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Refresh,
                // Значок сам несёт действие, подписи рядом у него нет — озвучиваем на двух языках.
                contentDescription = appText("Повторить", "Ҡабатлау"),
                tint = CanonWarn,
                modifier = Modifier.size(BargainIconSmall),
            )
            Spacer(Modifier.width(BargainGapTight))
            Text(
                appText(
                    "Не удалось обновить. Показываем последнее, что пришло. Нажми, чтобы повторить.",
                    "Яңыртып булманы. Һуңғы килгәнде күрһәтәбеҙ. Ҡабатлау өсөн баҫ.",
                ),
                color = CanonWarn,
                fontSize = BargainMeta,
                lineHeight = BargainMetaLine,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Скелетон повторяет карточку отклика блок в блок: пилюля статуса, цена справа, плашка торга,
 * кнопка. Тогда в момент прихода данных ничего не перескакивает — просто проявляется содержимое.
 */
@Composable
private fun ResponseSkeletonCard() {
    AppCard {
        Column(
            Modifier.padding(BargainCardPad),
            verticalArrangement = Arrangement.spacedBy(BargainGap),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(BargainGapTight),
                ) {
                    SkeletonBox(widthFraction = 0.55f, height = 20.dp, shape = CircleShape)
                    SkeletonBox(widthFraction = 0.35f, height = 12.dp)
                }
                Spacer(Modifier.width(BargainGap))
                SkeletonBox(modifier = Modifier.width(72.dp), height = 24.dp)
            }
            SkeletonBox(widthFraction = 0.8f, height = 12.dp)
            SkeletonBox(height = 72.dp, shape = CanonItemShape)          // плашка торга
            SkeletonBox(height = 56.dp, shape = RoundedCornerShape(14.dp))   // кнопка действия
        }
    }
}

/**
 * Карточка отклика глазами водителя. Порядок чтения задан жёстко, сверху вниз:
 * что за отклик → по какой цене поедешь → что просил пассажир → как шёл торг → что можно сделать.
 *
 * @param index номер в списке, только для каскада появления
 */
@Composable
private fun DriverResponseCard(
    r: ResponseDto,
    index: Int,
    busy: Boolean,
    onAccept: (ResponseDto) -> Unit,
    onCounter: (ResponseDto) -> Unit,
    onDecline: (ResponseDto) -> Unit,
) {
    AppCard(modifier = Modifier.appearIn(index)) {
        Column(
            Modifier.padding(BargainCardPad),
            verticalArrangement = Arrangement.spacedBy(BargainGap),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(BargainGapTight),
                ) {
                    DealPill(r)
                    // Своё первое предложение водитель забывает первым — напоминаем тихой строкой.
                    if (r.haggled && r.price > 0) {
                        Text(
                            appText("Ты предлагал ${r.price} ₽", "Һин ${r.price} һ тәҡдим иткәйнең"),
                            color = CanonMuted,
                            fontSize = BargainMeta,
                            lineHeight = BargainMetaLine,
                        )
                    }
                }
                if (r.onTable > 0) {
                    Spacer(Modifier.width(BargainGap))
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(BargainGapHair),
                    ) {
                        // Подпись честная в каждом состоянии: «на столе» только пока торг живой,
                        // иначе это цена поездки или последняя названная — врать над цифрой нельзя.
                        Text(
                            when (r.status) {
                                "accepted" -> appText("Цена поездки", "Сәфәр хаҡы")
                                "declined" -> appText("Последняя цена", "Һуңғы хаҡ")
                                // Заявка закрылась по времени — цена уже ни к чему не ведёт.
                                "expired" -> appText("Последняя цена", "Һуңғы хаҡ")
                                else -> appText("Сейчас на столе", "Хәҙер өҫтәлдә")
                            },
                            color = CanonMuted,
                            fontSize = BargainMeta,
                            lineHeight = BargainMetaLine,
                            textAlign = TextAlign.End,
                        )
                        // Цена — главное число карточки, и меняется она НЕ подстановкой: старая
                        // уходит вверх, новая приходит снизу. Иначе человек не замечает, что
                        // пассажир сходил, и жмёт «согласиться» на цену, которой уже нет.
                        AnimatedContent(
                            targetState = r.onTable,
                            transitionSpec = {
                                (fadeIn(tween(CanonMotion.NORMAL)) + slideInVertically(tween(CanonMotion.NORMAL)) { it / 2 }) togetherWith
                                    (fadeOut(tween(CanonMotion.QUICK)) + slideOutVertically(tween(CanonMotion.QUICK)) { -it / 2 })
                            },
                            label = "onTablePrice",
                        ) { price ->
                            Text(
                                "$price ₽",
                                color = CanonGreen2,
                                fontWeight = FontWeight.Bold,
                                fontSize = BargainPrice,
                                lineHeight = BargainPriceLine,
                                letterSpacing = (-0.2f).sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            if (r.comment.isNotBlank()) {
                Text(r.comment, color = CanonText, fontSize = BargainBody, lineHeight = BargainBodyLine)
            }
            // Ход торга — на утопленной подложке: это «квитанция» разговора, а не сам разговор.
            Surface(color = CanonBg, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.padding(BargainRowPad)) { BargainSummary(r) }
            }
            BargainActions(r, busy, onAccept, onCounter, onDecline)
        }
    }
}

/**
 * Состояние сделки одной пилюлей — чтобы карточка читалась за полсекунды.
 * Цвета меняются плавно: «идёт торг» → «договорились» не должно быть перескоком.
 */
@Composable
private fun DealPill(r: ResponseDto) {
    val agreed = r.status == "accepted"
    val failed = r.status == "declined" || r.status == "expired"
    val bg by animateColorAsState(
        when {
            agreed -> CanonMint
            failed -> CanonDangerBg
            r.haggled -> CanonWarnBg
            else -> CanonBg
        },
        tween(CanonMotion.SLOW),
        label = "dealPillBg",
    )
    val fg by animateColorAsState(
        when {
            agreed -> CanonGreen2
            failed -> CanonRed
            r.haggled -> CanonWarn
            else -> CanonMutedStrong
        },
        tween(CanonMotion.SLOW),
        label = "dealPillFg",
    )
    val icon: ImageVector = when {
        agreed -> Icons.Default.CheckCircle
        failed -> Icons.Default.Cancel
        r.haggled -> Icons.Default.SwapHoriz
        else -> Icons.Default.Handshake
    }
    val label = when {
        agreed -> appText("Договорились", "Килештек")
        failed -> appText("Не договорились", "Килешмәнек")
        r.haggled -> appText("Идёт торг", "Һатыулашыу бара")
        else -> appText("Твой отклик", "Һинең яуабың")
    }
    Surface(
        color = bg,
        shape = CircleShape,
        // Спокойное состояние почти сливается с карточкой — держим его волоском рамки.
        border = if (agreed || failed || r.haggled) null else BorderStroke(1.dp, CanonBorder),
    ) {
        Row(
            Modifier.padding(horizontal = BargainGap, vertical = BargainGapTight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,   // подпись пилюли говорит ровно то же
                tint = fg,
                modifier = Modifier.size(BargainIconSmall),
            )
            Spacer(Modifier.width(BargainGapHair))
            AnimatedContent(
                targetState = label,
                transitionSpec = { fadeIn(tween(CanonMotion.NORMAL)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
                label = "dealPillLabel",
            ) { text ->
                Text(
                    text,
                    color = fg,
                    fontSize = BargainMeta,
                    lineHeight = BargainMetaLine,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Что можно сделать с откликом. Иерархия видна с одного взгляда: согласие — золотая кнопка,
 * встречная цена — контурная, «не договорились» — тихая строка, но с честной тач-целью 48dp.
 */
@Composable
private fun BargainActions(
    r: ResponseDto,
    busy: Boolean,
    onAccept: (ResponseDto) -> Unit,
    onCounter: (ResponseDto) -> Unit,
    onDecline: (ResponseDto) -> Unit,
) {
    val canDecline = r.status == "offered" && (r.canAccept || r.canCounter)
    if (!r.canAccept && !r.canCounter && !canDecline) return
    Column(verticalArrangement = Arrangement.spacedBy(BargainGapTight)) {
        if (r.canAccept) {
            AppButton(
                text = appText("Согласиться на ${r.onTable} ₽", "${r.onTable} һ менән килешеү"),
                onClick = { onAccept(r) },
                style = AppButtonStyle.Accent,
                icon = Icons.Default.Handshake,
                enabled = !busy,
            )
        }
        if (r.canCounter) {
            AppButton(
                text = appText("Предложить свою цену", "Үҙ хаҡыңды тәҡдим итеү"),
                onClick = { onCounter(r) },
                style = AppButtonStyle.Secondary,
                enabled = !busy,
            )
        }
        if (canDecline) {
            TextButton(
                onClick = { onDecline(r) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = BargainTouch),
            ) {
                Text(
                    appText("Не договорились", "Килешмәнек"),
                    color = CanonMutedStrong,
                    fontSize = BargainBody,
                    lineHeight = BargainBodyLine,
                )
            }
        }
    }
}
