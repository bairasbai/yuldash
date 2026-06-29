package com.yuldash.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

private val RoadYellow = Color(0xFFE8A21A)

/** Курай (7 лепестков = 7 родов) распускается за лого. Лёгкий золотой, медленно вращается. */
private fun DrawScope.drawKurai(cx: Float, cy: Float, bloom: Float, rot: Float) {
    if (bloom <= 0f) return
    val len = 96.dp.toPx() * bloom
    val w = 30.dp.toPx()
    rotate(rot, Offset(cx, cy)) {
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
        cubicTo(cx - 42.dp.toPx(), yBottom - h * 0.34f, cx + 42.dp.toPx(), yBottom - h * 0.66f, cx, yTop)
    }
    val pm = PathMeasure().apply { setPath(road, false) }
    val stop = pm.length * progress
    val seg = Path()
    pm.getSegment(0f, stop, seg, true)
    drawPath(seg, color = RoadYellow.copy(alpha = 0.9f * fade), style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
    val head = pm.getPosition(stop)
    drawCircle(Color.White.copy(alpha = 0.9f * fade), radius = 7.dp.toPx(), center = head)
    drawCircle(RoadYellow.copy(alpha = fade), radius = 4.dp.toPx(), center = head)
}

/**
 * Герой интро: курай за лого + дорога с едущей точкой + сам логотип (растровый пин в белом круге).
 * Всё в одном Canvas → точное позиционирование. Значения 0..1 гонит таймлайн IntroScreen.
 */
@Composable
internal fun BrandHero(
    logoAlpha: Float,
    logoScale: Float,
    glow: Float,
    ring: Float,
    kurai: Float,
    kuraiRot: Float,
    road: Float,
    roadFade: Float,
    modifier: Modifier = Modifier,
) {
    val logo = ImageBitmap.imageResource(R.drawable.yuldash_logo)
    Box(modifier.size(220.dp, 196.dp)) {
        Canvas(Modifier.size(220.dp, 196.dp)) {
            val cx = size.width / 2f
            val cy = 78.dp.toPx()            // центр лого — в верхней части (дороге место снизу)
            val logoR = 66.dp.toPx()
            drawKurai(cx, cy, kurai, kuraiRot)
            drawRoad(cx, size.height - 6.dp.toPx(), cy + logoR - 4.dp.toPx(), road, roadFade)
            // сияние
            if (glow > 0f) drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.30f * glow), Color.Transparent),
                    center = Offset(cx, cy), radius = logoR * 1.8f,
                ),
                radius = logoR * 1.8f, center = Offset(cx, cy),
            )
            // золотое кольцо
            if (ring > 0f) drawCircle(
                Color(0xFFD89B12).copy(alpha = 0.45f * ring),
                radius = logoR + 9.dp.toPx(), center = Offset(cx, cy),
                style = Stroke(width = 1.5.dp.toPx()),
            )
            // белый круг
            drawCircle(Color.White.copy(alpha = logoAlpha), radius = logoR * logoScale, center = Offset(cx, cy))
            // пин (логотип) внутри круга
            val iw = logoR * 1.0f * logoScale
            val ih = iw * (logo.height.toFloat() / logo.width.toFloat())
            drawImage(
                image = logo,
                dstOffset = IntOffset((cx - iw / 2f).toInt(), (cy - ih / 2f).toInt()),
                dstSize = IntSize(iw.toInt(), ih.toInt()),
                alpha = logoAlpha,
            )
        }
    }
}
