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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

private val HeroGold = Color(0xFFD89B12)
private val RoadYellow = Color(0xFFE8A21A)

/**
 * Курай (7 лепестков = 7 родов) распускается за лого. Лёгкое вращение ТОЛЬКО во время роспуска
 * (после bloom=1 значение статично → Compose перестаёт перерисовывать Canvas → главный поток свободен).
 */
private fun DrawScope.drawKurai(cx: Float, cy: Float, bloom: Float) {
    if (bloom <= 0f) return
    val len = 94.dp.toPx() * bloom
    val w = 30.dp.toPx()
    rotate(bloom * 8f, Offset(cx, cy)) {   // дрейф завязан на bloom, не на отдельной долгой анимации
        for (i in 0 until 7) {
            rotate(i * (360f / 7f), Offset(cx, cy)) {
                val petal = Path().apply {
                    moveTo(cx, cy)
                    quadraticBezierTo(cx - w / 2, cy - len * 0.5f, cx, cy - len)
                    quadraticBezierTo(cx + w / 2, cy - len * 0.5f, cx, cy)
                    close()
                }
                drawPath(petal, color = Color(0xFFE9C766).copy(alpha = 0.20f * bloom))
            }
        }
    }
}

/** Дорога рисуется снизу вверх к лого (S-извив), по голове едет светящаяся точка («маршрут»). */
private fun DrawScope.drawRoad(cx: Float, yBottom: Float, yTop: Float, progress: Float, fade: Float) {
    if (progress <= 0f || fade <= 0f) return
    val h = yBottom - yTop
    val road = Path().apply {
        moveTo(cx, yBottom)
        // почти прямая, лёгкий изгиб — дорога уходит к пину (не «червь»)
        cubicTo(cx - 13.dp.toPx(), yBottom - h * 0.36f, cx + 11.dp.toPx(), yBottom - h * 0.68f, cx, yTop)
    }
    val pm = PathMeasure().apply { setPath(road, false) }
    val stop = pm.length * progress
    val seg = Path()
    pm.getSegment(0f, stop, seg, true)
    // полотно дороги + белая пунктирная осевая → читается как ДОРОГА (пин = пункт назначения в конце)
    drawPath(seg, color = RoadYellow.copy(alpha = 0.95f * fade), style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round))
    drawPath(
        seg, color = Color.White.copy(alpha = 0.9f * fade),
        style = Stroke(
            width = 2.dp.toPx(), cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 8.dp.toPx())),
        ),
    )
    // светящаяся точка-маршрут на голове
    val head = pm.getPosition(stop)
    drawCircle(Color.White.copy(alpha = 0.95f * fade), radius = 7.dp.toPx(), center = head)
    drawCircle(RoadYellow.copy(alpha = fade), radius = 4.dp.toPx(), center = head)
}

/**
 * Герой интро: дешёвый Canvas-вектор (курай + дорога) ПОЗАДИ + сам логотип эффективным
 * Image-композаблом с graphicsLayer (без per-frame ресэмпла битмапа в Canvas → плавно).
 * Центр лого: (width/2, 66dp). Дорога чертится снизу до низа белого круга. Значения 0..1 гонит таймлайн.
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
    Box(modifier.size(220.dp, 196.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = 66.dp.toPx()
            val logoR = 66.dp.toPx()
            drawKurai(cx, cy, kurai)
            drawRoad(cx, size.height - 6.dp.toPx(), cy + logoR - 4.dp.toPx(), road, roadFade)
        }
        // Лого-стек (сияние + кольцо + белый круг + пин) — центр на (110, 66dp). Image = эффективный путь.
        Box(Modifier.align(Alignment.TopCenter).size(132.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(190.dp).graphicsLayer { alpha = glow * 0.42f }
                    .background(Brush.radialGradient(listOf(Color.White.copy(0.30f), Color.Transparent))),
            )
            Box(Modifier.size(150.dp).graphicsLayer { alpha = ring * 0.45f }.border(1.5.dp, HeroGold, CircleShape))
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
