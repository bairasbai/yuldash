package com.yuldash.app

// ═══════════════════ Торг о цене: общий UI для обеих сторон ═══════════════════
// Раньше отклик водителя был «бери или уходи»: он назвал 500, пассажир хотел 400 — и поездка
// просто не случалась, хотя оба согласились бы на 450. В селе торговаться — привычка, а не
// неудобство, и половина сделок гибла на разнице в полсотни рублей.
//
// Здесь только то, что одинаково у пассажира и водителя: строка «как шёл торг», подпись «чей
// ход» и диалог ввода встречной цены. Карточки у сторон разные (экран откликов ↔ «Мои отклики»),
// а правила торга одни — держим их в одном месте, чтобы не разъехались.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ResponseDto

/** Потолок цены — тот же, что на сервере: торг не обходит общий лимит. */
private const val BARGAIN_PRICE_CAP = 100_000

/**
 * «Как шёл торг»: `d:500,p:400,d:450` → «500 ₽ → 400 ₽ → 450 ₽».
 * Без этой строки торг превращается в «я же называл другую цену» — вспомнить нечем.
 */
internal fun bargainChain(history: String): String =
    history.split(',')
        .mapNotNull { it.substringAfter(':', "").trim().toIntOrNull() }
        .joinToString("  →  ") { "$it ₽" }

/** Подпись «чей сейчас ход» — глазами смотрящего. Пусто, когда торг закрыт. */
@Composable
internal fun bargainTurnHint(r: ResponseDto): String = when {
    r.status == "accepted" -> appText("Договорились", "Килештек")
    r.status == "declined" -> appText("Не договорились по цене", "Хаҡ буйынса килешмәнек")
    r.canAccept -> appText("Твой ход: прими или предложи свою цену", "Һинең сират: ҡабул ит йәки үҙ хаҡыңды тәҡдим ит")
    else -> appText("Ждём ответа второй стороны", "Икенсе яҡтың яуабын көтәбеҙ")
}

/** Блок торга внутри карточки отклика: цена на столе, история, подпись «чей ход». */
@Composable
internal fun BargainSummary(r: ResponseDto) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (r.haggled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Handshake, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(bargainChain(r.bargainHistory), color = CanonMuted, fontSize = 12.sp)
            }
        }
        val hint = bargainTurnHint(r)
        if (hint.isNotBlank()) {
            Text(
                hint,
                color = if (r.canAccept) CanonGreen2 else CanonMuted,
                fontSize = 12.sp, fontWeight = if (r.canAccept) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

/**
 * Диалог встречной цены. Подсказка честно говорит, сколько ходов осталось: торг не бесконечен
 * (по три встречных на сторону), и человек должен это знать ДО того, как потратит ход.
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
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = CanonSurface,
        title = { Text(appText("Твоя цена", "Һинең хаҡың"), color = CanonText, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    appText("Сейчас на столе $current ₽. Назови свою — вторая сторона ответит.",
                            "Хәҙер өҫтәлдә $current һ. Үҙеңдекен әйт — икенсе яҡ яуап бирер."),
                    color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit).take(6) },
                    label = { Text(appText("Цена, ₽", "Хаҡ, ₽")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (roundsLeft <= 1)
                        appText("Это последний ход в торге. Дальше — принять или разойтись.",
                                "Был һатыулашыуҙағы һуңғы сират. Артабан — ҡабул итеү йәки таралышыу.")
                    else appText("Осталось ходов: $roundsLeft. Торгуемся по очереди.",
                                 "Ҡалған сират: $roundsLeft. Сиратлап һатыулашабыҙ."),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (ok && !busy) onSend(price!!) }, enabled = ok && !busy) {
                Text(appText("Предложить", "Тәҡдим итеү"), color = if (ok && !busy) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(appText("Отмена", "Кире алыу"), color = CanonMuted)
            }
        },
    )
}
