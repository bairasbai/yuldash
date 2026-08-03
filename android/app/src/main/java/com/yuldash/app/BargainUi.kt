package com.yuldash.app

// ═══════════════════ Торг о цене: общий UI для обеих сторон ═══════════════════
// Раньше отклик водителя был «бери или уходи»: он назвал 500, пассажир хотел 400 — и поездка
// просто не случалась, хотя оба согласились бы на 450. В селе торговаться — привычка, а не
// неудобство, и половина сделок гибла на разнице в полсотни рублей.
//
// Здесь только то, что одинаково у пассажира и водителя: строка «как шёл торг», подпись «чей
// ход» и диалог ввода встречной цены. Карточки у сторон разные (экран откликов ↔ «Мои отклики»),
// а правила торга одни — держим их в одном месте, чтобы не разъехались.
//
// ── Визуальный канон торга (правки 2026-07-30) ─────────────────────────────
// Разговор о деньгах должен читаться спокойно и мгновенно, поэтому:
//  • ЧЕТЫРЕ размера текста и ни одного лишнего (Price/Title/Body/Meta ниже);
//  • всё кратно 4dp: карточка дышит 20dp, вложенная плашка 16dp, блоки 12dp, пары 4dp;
//  • цвет работает как смысл: зелёный — твой ход и согласие, красный — разошлись,
//    жёлтый — последний ход, серый — ждём вторую сторону;
//  • сумма НИКОГДА не подставляется рывком: цена на столе и цепочка сменяются анимацией,
//    иначе человек не замечает, что торг сдвинулся, — а это и есть главное событие экрана.

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ResponseDto

/** Потолок цены — тот же, что на сервере: торг не обходит общий лимит. */
private const val BARGAIN_PRICE_CAP = 100_000

// ─────────────────────────── Типографика торга ───────────────────────────
// Торг видят обе стороны — пассажир в «Откликах водителей», водитель в «Моих откликах».
// Значит и читаться он обязан одинаково. Четыре размера, у каждого ОДНА роль:
//   Price — цена, которая сейчас на столе. Единственное крупное число карточки.
//   Title — заголовок карточки и диалога.
//   Body  — то, что человек читает целиком: комментарий, пояснение, надпись кнопки.
//   Meta  — история торга, подпись «чей ход», сноски.
// Вес закреплён за ролью: Black — цены и заголовки, Bold — то, что требует твоего хода,
// Normal — спокойный текст. Пятого размера в торге не бывает.
internal val BargainPrice = 22.sp
internal val BargainPriceLine = 26.sp
internal val BargainTitle = 17.sp
internal val BargainTitleLine = 22.sp
internal val BargainBody = 14.sp
internal val BargainBodyLine = 20.sp
internal val BargainMeta = 12.sp
internal val BargainMetaLine = 16.sp

// ─────────────────────────── Ритм торга (сетка 4dp) ───────────────────────────
internal val BargainCardPad = 20.dp     // воздух внутри карточки отклика
internal val BargainRowPad = 16.dp      // воздух вложенной плашки и поля экрана
internal val BargainGap = 12.dp         // между смысловыми блоками
internal val BargainGapTight = 8.dp     // между близкими элементами
internal val BargainGapHair = 4.dp      // пара «подпись + значение»
internal val BargainTouch = 48.dp       // минимальная тач-цель
internal val BargainIcon = 20.dp        // иконка заголовка/диалога
internal val BargainIconSmall = 16.dp   // иконка внутри строки и пилюли
private val BargainFieldShape = RoundedCornerShape(14.dp)

/**
 * «Как шёл торг» одной строкой: `d:500,p:400,d:450` → «500 ₽ → 400 ₽ → 450 ₽».
 * Нужна для озвучки TalkBack: цепочку из плиток он прочитал бы как россыпь чисел без связи.
 */
internal fun bargainChain(history: String): String =
    bargainSteps(history).joinToString("  →  ") { "$it ₽" }

/** Ходы торга числами: `d:500,p:400` → [500, 400]. Порядок — как шёл разговор. */
private fun bargainSteps(history: String): List<Int> =
    history.split(',').mapNotNull { it.substringAfter(':', "").trim().toIntOrNull() }

/**
 * Подпись «чей сейчас ход» — глазами смотрящего. Строка есть всегда: состояние торга
 * человек должен видеть в любой момент, иначе «я же называл другую цену» вспомнить нечем.
 */
@Composable
internal fun bargainTurnHint(r: ResponseDto): String = when {
    r.status == "accepted" -> appText("Цена согласована — поездка в разделе «Поездки»", "Хаҡ килешелде — сәфәр «Сәфәрҙәр» бүлегендә")
    r.status == "declined" -> appText("Торг закрыт: по цене не сошлись", "Һатыулашыу ябыҡ: хаҡ буйынса килешмәнек")
    r.canAccept -> appText("Твой ход: прими цену или предложи свою", "Һинең сират: хаҡты ҡабул ит йәки үҙеңдекен тәҡдим ит")
    else -> appText("Ждём ответа второй стороны", "Икенсе яҡтың яуабын көтәбеҙ")
}

/** Значок состояния торга. Только из иконок, уже живущих в проекте — новых сущностей не плодим. */
private fun bargainTurnIcon(r: ResponseDto): ImageVector = when {
    r.status == "accepted" -> Icons.Default.CheckCircle
    r.status == "declined" -> Icons.Default.Cancel
    r.canAccept -> Icons.Default.Bolt
    else -> Icons.Default.Schedule
}

/**
 * Блок торга внутри карточки отклика: как двигались цены и чей сейчас ход.
 * Один и тот же у пассажира и у водителя — торг обязан выглядеть одинаково с обеих сторон.
 */
@Composable
internal fun BargainSummary(r: ResponseDto) {
    Column(verticalArrangement = Arrangement.spacedBy(BargainGapTight)) {
        // Цепочка появляется, когда торг реально пошёл: карточка раздвигается, а не «прыгает»
        // на высоту новой строки.
        AnimatedVisibility(
            visible = r.haggled,
            enter = fadeIn(tween(CanonMotion.NORMAL)) + expandVertically(tween(CanonMotion.NORMAL)),
            exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.QUICK)),
        ) {
            BargainChainRow(r.bargainHistory)
        }
        BargainTurnLine(r)
    }
}

/**
 * Цепочка цен «500 → 400 → 450». Последняя — та, что сейчас на столе: она и подсвечена,
 * прошлые остаются тихим следом разговора.
 *
 * Ряд едет вбок, а не переносится: шесть встречных ходов в две строки превращаются в кашу,
 * а с крупным системным шрифтом рвали бы карточку.
 */
@Composable
private fun BargainChainRow(history: String) {
    val steps = remember(history) { bargainSteps(history) }
    if (steps.isEmpty()) return
    // TalkBack читает цепочку одной фразой — иначе это просто «500, 400, 450» без связи.
    val spoken = appText("Ход торга: ", "Һатыулашыу барышы: ") + bargainChain(history)
    // Последний ход виден всегда: после шести встречных цепочка длиннее экрана, и подсвеченная
    // цена уезжала бы вправо за край. Лента сама доезжает до конца, историю листаем рукой.
    val scroll = rememberScrollState()
    LaunchedEffect(steps, scroll.maxValue) { scroll.animateScrollTo(scroll.maxValue) }
    AnimatedContent(
        targetState = steps,
        transitionSpec = { fadeIn(tween(CanonMotion.NORMAL)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
        label = "bargainChain",
    ) { row ->
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll)
                // mergeDescendants: TalkBack читает ОДНУ фразу целиком, а не прыгает по числам.
                .semantics(mergeDescendants = true) { contentDescription = spoken },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BargainGapHair),
        ) {
            Icon(
                Icons.Default.Handshake,
                contentDescription = null,   // цепочка озвучена целиком выше — дважды читать нечего
                tint = CanonMuted,
                modifier = Modifier.size(BargainIconSmall),
            )
            row.forEachIndexed { i, value ->
                if (i > 0) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = CanonMuted,
                        modifier = Modifier.size(BargainIconSmall),
                    )
                }
                BargainStep(value = value, current = i == row.lastIndex)
            }
        }
    }
}

/** Один ход торга: цена на столе — мятная пилюля, прошлые предложения — спокойный текст. */
@Composable
private fun BargainStep(value: Int, current: Boolean) {
    if (current) {
        Surface(color = CanonMint, shape = CircleShape) {
            Text(
                "$value ₽",
                color = CanonGreen2,
                fontSize = BargainMeta,
                lineHeight = BargainMetaLine,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = BargainGapTight, vertical = BargainGapHair),
            )
        }
    } else {
        Text(
            "$value ₽",
            color = CanonMuted,
            fontSize = BargainMeta,
            lineHeight = BargainMetaLine,
            maxLines = 1,
            modifier = Modifier.padding(vertical = BargainGapHair),
        )
    }
}

/**
 * Чей сейчас ход. Ради этой строки экран и существует: пропустил свой ход — потерял поездку.
 * Цвет и текст меняются плавно, чтобы переход «ждём → твой ход» читался как событие.
 */
@Composable
private fun BargainTurnLine(r: ResponseDto) {
    val mine = r.canAccept && r.status != "accepted" && r.status != "declined"
    val accent by animateColorAsState(
        when {
            r.status == "accepted" -> CanonGreen2
            r.status == "declined" -> CanonRed
            mine -> CanonGreen2
            else -> CanonMutedStrong
        },
        tween(CanonMotion.SLOW),
        label = "bargainTurnAccent",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            bargainTurnIcon(r),
            contentDescription = null,   // подпись рядом говорит то же самое
            tint = accent,
            modifier = Modifier.size(BargainIconSmall),
        )
        Spacer(Modifier.width(BargainGapTight))
        AnimatedContent(
            targetState = bargainTurnHint(r),
            transitionSpec = { fadeIn(tween(CanonMotion.NORMAL)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
            label = "bargainTurn",
        ) { hint ->
            Text(
                hint,
                color = accent,
                fontSize = BargainMeta,
                lineHeight = BargainMetaLine,
                fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

/**
 * Диалог встречной цены. Показывает три вещи разом: что сейчас на столе, насколько ты от неё
 * уходишь и сколько ходов осталось. Торг не бесконечен (по три встречных на сторону), и человек
 * должен знать это ДО того, как потратит ход.
 *
 * @param current цена, которая сейчас на столе — от неё человек и отталкивается
 * @param roundsLeft сколько встречных ещё можно сделать всего
 */
@Composable
internal fun CounterPriceDialog(
    current: Int,
    roundsLeft: Int,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSend: (Int) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    val price = value.filter(Char::isDigit).toIntOrNull()
    val ok = price != null && price in 0..BARGAIN_PRICE_CAP
    val lastMove = roundsLeft <= 1
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = CanonSurface,
        shape = CanonCardShape,
        icon = {
            Icon(
                Icons.Default.Handshake,
                contentDescription = null,   // заголовок диалога рядом и говорит то же
                tint = CanonGreen2,
                modifier = Modifier.size(BargainIcon),
            )
        },
        title = {
            Text(
                appText("Твоя цена", "Һинең хаҡың"),
                color = CanonText,
                fontWeight = FontWeight.Bold,
                fontSize = BargainTitle,
                lineHeight = BargainTitleLine,
                textAlign = TextAlign.Center,
            )
        },
        text = {
            // Крупный системный шрифт + длинный башкирский: диалог прокручивается, а не обрезает текст.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(BargainGap),
            ) {
                // Цена на столе — цифрой, а не в середине предложения: от неё человек и считает.
                Surface(color = CanonMint, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(BargainRowPad),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(BargainGapHair),
                        ) {
                            Text(
                                appText("Сейчас на столе", "Хәҙер өҫтәлдә"),
                                color = CanonGreen2,
                                fontSize = BargainMeta,
                                lineHeight = BargainMetaLine,
                            )
                            Text(
                                "$current ₽",
                                color = CanonGreen2,
                                fontSize = BargainPrice,
                                lineHeight = BargainPriceLine,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.2f).sp,
                                maxLines = 1,
                            )
                        }
                        // Насколько ты отходишь от цены на столе — считается на лету и меняется плавно,
                        // чтобы «минус полтинник» было видно до нажатия, а не после ответа сервера.
                        AnimatedVisibility(
                            visible = ok && price != current,
                            enter = fadeIn(tween(CanonMotion.QUICK)),
                            exit = fadeOut(tween(CanonMotion.QUICK)),
                        ) {
                            AnimatedContent(
                                targetState = (price ?: current) - current,
                                transitionSpec = { fadeIn(tween(CanonMotion.NORMAL)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
                                label = "bargainDelta",
                            ) { delta ->
                                Text(
                                    if (delta > 0) "+$delta ₽" else "$delta ₽",
                                    color = CanonGreen2,
                                    fontSize = BargainMeta,
                                    lineHeight = BargainMetaLine,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit).take(6) },
                    label = { Text(appText("Цена, ₽", "Хаҡ, ₽"), fontSize = BargainBody) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = TextStyle(
                        fontSize = BargainPrice,
                        lineHeight = BargainPriceLine,
                        fontWeight = FontWeight.Bold,
                        color = CanonText,
                    ),
                    shape = BargainFieldShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CanonGreen2,
                        cursorColor = CanonGreen2,
                        focusedLabelColor = CanonGreen2,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Сколько ходов осталось — честно и заранее, а не «торг окончен» в ответ на кнопку.
                Surface(
                    color = if (lastMove) CanonWarnBg else CanonBg,
                    shape = CanonItemShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (lastMove)
                            appText("Это последний ход в торге. Дальше — принять или разойтись.",
                                    "Был һатыулашыуҙағы һуңғы сират. Артабан — ҡабул итеү йәки таралышыу.")
                        else appText("Осталось ходов: $roundsLeft. Торгуемся по очереди.",
                                     "Ҡалған сират: $roundsLeft. Сиратлап һатыулашабыҙ."),
                        color = if (lastMove) CanonWarn else CanonMutedStrong,
                        fontSize = BargainMeta,
                        lineHeight = BargainMetaLine,
                        fontWeight = if (lastMove) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(BargainRowPad),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (ok && !busy) onSend(price!!) },
                enabled = ok && !busy,
                modifier = Modifier.heightIn(min = BargainTouch),
            ) {
                // Отправка не должна выглядеть как «ничего не произошло»: подпись сменяется спиннером.
                AnimatedContent(
                    targetState = busy,
                    transitionSpec = { fadeIn(tween(CanonMotion.QUICK)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
                    label = "bargainSending",
                ) { sending ->
                    if (sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(BargainIcon),
                            color = CanonGreen2,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            appText("Предложить", "Тәҡдим итеү"),
                            color = if (ok) CanonGreen2 else CanonMuted,
                            fontWeight = FontWeight.Bold,
                            fontSize = BargainBody,
                            lineHeight = BargainBodyLine,
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy,
                modifier = Modifier.heightIn(min = BargainTouch),
            ) {
                Text(
                    appText("Отмена", "Кире алыу"),
                    color = CanonMutedStrong,
                    fontSize = BargainBody,
                    lineHeight = BargainBodyLine,
                )
            }
        },
    )
}
