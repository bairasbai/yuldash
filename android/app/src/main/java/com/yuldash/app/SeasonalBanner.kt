package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddRoad
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.SeasonalEventDto

/**
 * F15: баннер «на праздник» на вкладке Карта. Появляется, когда близко сезонное событие
 * (Сабантуй, Ураза/Курбан, День города, 1 сентября…). Даты считает сервер и обновляет сам
 * каждый год — клиент только рисует. Тап «Опубликовать поездку» → форма создания поездки.
 *
 * Данные тянет [MapScreen] (один запрос), сюда приходит уже выбранное событие — экран решает,
 * показывать ли пункт списка (нет события → пункта нет, без пустой дырки).
 */
@Composable
internal fun SeasonalBanner(
    event: SeasonalEventDto,
    onPublish: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Мягкое появление (уровень iPhone): плавно всплывает при первом показе.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(420)) + slideInVertically(tween(420)) { it / 4 },
    ) {
        val name = appText(event.nameRu, event.nameBa)
        val title =
            if (event.active) appText("$name уже идёт", "$name бара")
            else appText("Скоро $name", "Тиҙҙән $name")
        // Подзаголовок: дата (нейтральна к языку) + пометка события, если есть.
        val whenStr =
            if (event.active) appText("идёт сейчас", "хәҙер бара")
            else shortDate(event.startsAt)
        val note = appText(event.noteRu, event.noteBa)
        val subtitle = listOf(whenStr, note).filter { it.isNotBlank() }.joinToString(" · ")

        AppCard {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = CanonMint, shape = CircleShape) {
                        Text(
                            event.emoji.ifBlank { "🎉" },
                            fontSize = 22.sp,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            title,
                            color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (subtitle.isNotBlank()) {
                            Text(subtitle, color = CanonMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(onClick = onDismiss, shape = CircleShape, color = CanonSurface) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = appText("Скрыть", "Йәшереү"),
                            tint = CanonMuted,
                            modifier = Modifier.minimumInteractiveComponentSize().size(18.dp),
                        )
                    }
                }
                Text(
                    appText(
                        "Едешь на праздник? Опубликуй поездку — попутчики уже ищут.",
                        "Байрамға бараһыңмы? Сәфәреңде ҡуй — юлдаштар эҙләй.",
                    ),
                    color = CanonMutedStrong, fontSize = 13.sp, lineHeight = 18.sp,
                )
                AppButton(
                    text = appText("Опубликовать поездку", "Сәфәр булдырыу"),
                    onClick = onPublish,
                    icon = Icons.Default.AddRoad,
                    height = 50.dp,
                )
            }
        }
    }
}

/** ISO «2026-03-20» → «20.03» (нейтрально к языку, без названий месяцев). */
private fun shortDate(iso: String): String {
    val p = iso.split("-")
    return if (p.size == 3) "${p[2]}.${p[1]}" else ""
}

/**
 * Шаблон для формы создания поездки под праздник: дата события → «dd.MM.yyyy, 09:00»
 * (тот же формат, что даёт пикер даты). Пусто, если дату не разобрали — форма откроется как обычно.
 */
internal fun seasonalRidePrefillDate(event: SeasonalEventDto): String {
    val p = event.anchor.split("-")   // «2026-06-13»
    return if (p.size == 3) "${p[2]}.${p[1]}.${p[0]}, 09:00" else ""
}
