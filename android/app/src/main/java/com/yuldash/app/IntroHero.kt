package com.yuldash.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlin.math.sin

// Золото «пыльцы» на заставке. Единственный цвет мимо шкалы `Canon*` во всём приложении, и это
// сознательно: он не оформляет интерфейс, а участвует в РИСУНКЕ — искорки на ночном небе поверх
// иллюстрации курая. Брать сюда `CanonGold` нельзя: тот меняется по светлой/тёмной теме, а небо
// на заставке тёмное всегда, и в светлой теме искорки стали бы блеклыми. Тот же принцип, что у
// палитры открытки «Мой Юлдаш» (MyStatsScreen) — арт живёт по своим правилам, интерфейс по шкале.
private val KuraiGold = Color(0xFFF0C840)

/** Золотая «пыльца» неба: едва заметные тёплые искорки, медленный дрейф + мерцание. Премиум-жизнь без шума. */
private class Mote(val x: Float, val y: Float, val r: Float, val spd: Float, val ph: Float)
private val MOTES = listOf(
    Mote(0.14f, 0.10f, 2.0f, 0.60f, 0.0f),
    Mote(0.27f, 0.30f, 1.5f, 0.85f, 1.2f),
    Mote(0.78f, 0.16f, 2.2f, 0.50f, 2.1f),
    Mote(0.86f, 0.36f, 1.6f, 0.70f, 0.6f),
    Mote(0.66f, 0.24f, 1.7f, 0.90f, 2.7f),
    Mote(0.10f, 0.40f, 1.4f, 0.55f, 1.7f),
    Mote(0.90f, 0.50f, 1.5f, 0.65f, 3.0f),
)

/** Канвас золотой пыльцы в верхней полусфере (небо). `sceneAlpha` гасит её вместе с проявлением пейзажа. */
@Composable
internal fun SkyMotes(modifier: Modifier = Modifier, sceneAlpha: () -> Float) {
    // Темп фонового дрейфа — со шкалы (CanonMotion.AMBIENT). При выключенных анимациях
    // пыльца остаётся на своих местах: небо живое на вид, движения нет.
    val t by canonDrift(label = "motes")
    Canvas(modifier) {
        val a = sceneAlpha()
        if (a <= 0f) return@Canvas
        for (m in MOTES) {
            val raw = (m.y - t * m.spd) % 1f
            val ny = if (raw < 0f) raw + 1f else raw
            val tw = 0.5f + 0.5f * sin((t * 6.2832f * m.spd + m.ph).toDouble()).toFloat()
            drawCircle(
                color = KuraiGold,
                radius = m.r.dp.toPx(),
                center = Offset(m.x * size.width, (0.06f + ny * 0.46f) * size.height),
                alpha = ((0.12f + 0.40f * tw) * a).coerceIn(0f, 0.6f),
            )
        }
    }
}

/**
 * Герой интро: чистый белый значок с лого (Image + graphicsLayer для плавного scale/alpha без ресэмпла битмапа).
 * Без курая/кольца/свечения/тени — они давали квадратные артефакты на эмуляторе; ambient-свет даёт сам пейзаж.
 * Значения logoAlpha/logoScale (0..1) гонит таймлайн IntroScreen.
 */
@Composable
internal fun BrandHero(
    logoAlpha: Float,
    logoScale: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(224.dp, 158.dp)) {
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp).size(132.dp)
                .graphicsLayer { scaleX = logoScale; scaleY = logoScale; alpha = logoAlpha },
            shape = CircleShape, color = Color.White, shadowElevation = CanonDepth.flat,
        ) {
            Image(painterResource(R.drawable.yuldash_logo), "Юлдаш", Modifier.padding(24.dp))
        }
    }
}
