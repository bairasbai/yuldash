package com.yuldash.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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
    val inf = rememberInfiniteTransition(label = "motes")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "t")
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
private val RoadYellow = Color(0xFFE8A21A)
private val ArrowLight = Color(0xFFFFD23D)
private val ArrowDeep = Color(0xFFE8A21A)

/**
 * Курай-соцветие (герб Башкортостана: 7 цветков на стеблях = 7 родов) распускается за лого.
 * Веер из 7 шаров на стеблях в верхней полусфере; центр (втулка) скрыт за пином. Дорога снизу
 * заменяет нижний стебель. scale(bloom) → веер «вырастает» из-за пина, после bloom=1 статичен.
 */
private fun DrawScope.drawKurai(cx: Float, cy: Float, bloom: Float) {
    if (bloom <= 0f) return
    scale(bloom, bloom, pivot = Offset(cx, cy)) {
        val r = 86.dp.toPx()
        val ballR = 8.dp.toPx()
        val sw = 6.dp.toPx()
        for (k in 0..6) {
            val rad = Math.toRadians(180.0 - k * 30.0)   // 180°(слева) → 0°(справа) по верхней дуге
            val bx = cx + (cos(rad) * r).toFloat()
            val by = cy - (sin(rad) * r).toFloat()
            drawLine(KuraiGold.copy(alpha = 0.70f * bloom), Offset(cx, cy), Offset(bx, by), strokeWidth = sw, cap = StrokeCap.Round)
            drawCircle(KuraiGold.copy(alpha = 0.82f * bloom), radius = ballR, center = Offset(bx, by))
        }
    }
}

/** Дорога-маршрут рисуется снизу к пину; по голове едет НАВ-СТРЕЛКА (как курсор навигатора = машина). */
private fun DrawScope.drawRoad(cx: Float, yBottom: Float, yTop: Float, progress: Float, fade: Float) {
    if (progress <= 0f || fade <= 0f) return
    val h = yBottom - yTop
    val road = Path().apply {
        moveTo(cx, yBottom)
        cubicTo(cx - 13.dp.toPx(), yBottom - h * 0.36f, cx + 11.dp.toPx(), yBottom - h * 0.68f, cx, yTop)
    }
    val pm = PathMeasure().apply { setPath(road, false) }
    val stop = pm.length * progress
    val seg = Path()
    pm.getSegment(0f, stop, seg, true)
    // полотно + белая пунктирная осевая → читается как ДОРОГА
    drawPath(seg, color = RoadYellow.copy(alpha = 0.9f * fade), style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round))
    drawPath(
        seg, color = Color.White.copy(alpha = 0.85f * fade),
        style = Stroke(
            width = 2.dp.toPx(), cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 8.dp.toPx())),
        ),
    )
    // нав-стрелка на голове, ориентирована по направлению движения (касательная маршрута)
    val head = pm.getPosition(stop)
    val back = pm.getPosition((stop - 6.dp.toPx()).coerceAtLeast(0f))
    val deg = Math.toDegrees(atan2(head.y - back.y, head.x - back.x).toDouble()).toFloat()
    rotate(deg + 90f, pivot = head) {   // стрелка в локали смотрит ВВЕРХ (−y) → +90 совмещает с курсом
        val len = 19.dp.toPx(); val half = 12.dp.toPx(); val bk = 7.dp.toPx()
        val tip = Offset(head.x, head.y - len)
        val notch = Offset(head.x, head.y + bk * 0.4f)
        val left = Path().apply { moveTo(tip.x, tip.y); lineTo(head.x - half, head.y + bk); lineTo(notch.x, notch.y); close() }
        val right = Path().apply { moveTo(tip.x, tip.y); lineTo(head.x + half, head.y + bk); lineTo(notch.x, notch.y); close() }
        drawPath(left, ArrowDeep.copy(alpha = fade))
        drawPath(right, ArrowLight.copy(alpha = fade))
    }
}

/**
 * Герой интро: дешёвый Canvas-вектор (курай-соцветие + дорога с нав-стрелкой) ПОЗАДИ + лого
 * эффективным Image-композаблом с graphicsLayer (без per-frame ресэмпла битмапа → плавно).
 * Центр лого: (width/2, 88dp). Значения 0..1 гонит таймлайн IntroScreen.
 */
@Composable
internal fun BrandHero(
    logoAlpha: Float,
    logoScale: Float,
    glow: Float,
    ring: Float,
    kurai: Float,
    road: Float,
    roadFade: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(224.dp, 158.dp)) {
        // Чистый белый значок (как референс) — без курая и кольца. Свечение рисуем Canvas-кругом
        // (drawCircle = математически круг, без квадратного слоя graphicsLayer → НИКАКОГО квадрата).
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 22.dp).size(132.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Без отдельного свечения: полупрозрачный слой-glow давал квадратный артефакт
            // (GPU эмулятора подсвечивал прямоугольные границы слоя). Ambient-свет даёт сам
            // пейзаж (центр-глоу неба за значком) — чистый белый круг, как референс.
            // shadowElevation = 0: тень значка на эмуляторе рисовалась прямоугольником под кругом
            // (квадрат). Без тени — чистый белый круг, отделяется контрастом от тёмного пейзажа.
            Surface(
                modifier = Modifier.size(132.dp)
                    .graphicsLayer { scaleX = logoScale; scaleY = logoScale; alpha = logoAlpha },
                shape = CircleShape, color = Color.White, shadowElevation = 0.dp,
            ) {
                Image(painterResource(R.drawable.yuldash_logo), "Юлдаш", Modifier.padding(22.dp))
            }
        }
    }
}
