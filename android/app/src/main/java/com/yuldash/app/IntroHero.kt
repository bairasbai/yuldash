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
import androidx.compose.runtime.Composable
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
    Box(modifier.size(224.dp, 230.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = 88.dp.toPx()
            val logoR = 66.dp.toPx()
            drawKurai(cx, cy, kurai)
            // дорога короткая (стрелка-навигатор главная, дорога = её след к пину)
            drawRoad(cx, size.height - 40.dp.toPx(), cy + logoR - 2.dp.toPx(), road, roadFade)
        }
        // Лого-стек (сияние + кольцо + белый круг + пин), центр на (112, 88dp).
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 22.dp).size(132.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(188.dp).graphicsLayer { alpha = glow * 0.40f }
                    .background(Brush.radialGradient(listOf(Color.White.copy(0.30f), Color.Transparent))),
            )
            Box(Modifier.size(150.dp).graphicsLayer { alpha = ring * 0.42f }.border(1.5.dp, RoadYellow, CircleShape))
            Surface(
                modifier = Modifier.size(132.dp)
                    .graphicsLayer { scaleX = logoScale; scaleY = logoScale; alpha = logoAlpha },
                shape = CircleShape, color = Color.White, shadowElevation = 20.dp,
            ) {
                Image(painterResource(R.drawable.yuldash_logo), "Юлдаш", Modifier.padding(22.dp))
            }
        }
    }
}
