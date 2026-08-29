package com.yuldash.app

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Единый фирменный курсор направления Юлдаш.
 *
 * Референс — понятная гранёная навигационная стрелка, но геометрия и раскладка цветов свои:
 * четыре плоскости точно сходятся в одном центре, золото ведёт к носу, два зелёных тона
 * формируют устойчивое основание. Одна трассировка используется и Compose, и Bitmap-маркерами
 * MapKit — силуэт не разъедется между экранами.
 */
private interface DirectionPathSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float)
    fun close()
}

private fun traceDirectionBody(path: DirectionPathSink, left: Float, top: Float, width: Float, height: Float) = with(path) {
    moveTo(left + width * 0.11f, top + height * 0.51f)
    lineTo(left + width * 0.89f, top + height * 0.07f)
    lineTo(left + width * 0.59f, top + height * 0.93f)
    lineTo(left + width * 0.46f, top + height * 0.69f)
    close()
}

private fun traceDirectionTop(path: DirectionPathSink, left: Float, top: Float, width: Float, height: Float) = with(path) {
    moveTo(left + width * 0.11f, top + height * 0.51f)
    lineTo(left + width * 0.89f, top + height * 0.07f)
    lineTo(left + width * 0.53f, top + height * 0.54f)
    close()
}

private fun traceDirectionRight(path: DirectionPathSink, left: Float, top: Float, width: Float, height: Float) = with(path) {
    moveTo(left + width * 0.89f, top + height * 0.07f)
    lineTo(left + width * 0.59f, top + height * 0.93f)
    lineTo(left + width * 0.53f, top + height * 0.54f)
    close()
}

private fun traceDirectionLower(path: DirectionPathSink, left: Float, top: Float, width: Float, height: Float) = with(path) {
    moveTo(left + width * 0.59f, top + height * 0.93f)
    lineTo(left + width * 0.46f, top + height * 0.69f)
    lineTo(left + width * 0.53f, top + height * 0.54f)
    close()
}

private fun traceDirectionLeft(path: DirectionPathSink, left: Float, top: Float, width: Float, height: Float) = with(path) {
    moveTo(left + width * 0.46f, top + height * 0.69f)
    lineTo(left + width * 0.11f, top + height * 0.51f)
    lineTo(left + width * 0.53f, top + height * 0.54f)
    close()
}

private fun composePath(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    trace: (DirectionPathSink, Float, Float, Float, Float) -> Unit,
): Path = Path().also { result ->
    trace(
        object : DirectionPathSink {
            override fun moveTo(x: Float, y: Float) = result.moveTo(x, y)
            override fun lineTo(x: Float, y: Float) = result.lineTo(x, y)
            override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
                result.cubicTo(x1, y1, x2, y2, x3, y3)
            override fun close() = result.close()
        },
        left,
        top,
        width,
        height,
    )
}

private fun androidPath(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    trace: (DirectionPathSink, Float, Float, Float, Float) -> Unit,
): AndroidPath = AndroidPath().also { result ->
    trace(
        object : DirectionPathSink {
            override fun moveTo(x: Float, y: Float) = result.moveTo(x, y)
            override fun lineTo(x: Float, y: Float) = result.lineTo(x, y)
            override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
                result.cubicTo(x1, y1, x2, y2, x3, y3)
            override fun close() = result.close()
        },
        left,
        top,
        width,
        height,
    )
}

/**
 * Курсор для кнопок и статусов. Геометрия изначально смотрит вправо-вверх, как привычное
 * действие навигации. Для реального курса MapKit использует тот же знак, развёрнутый носом
 * на север, и затем сам поворачивает его по bearing.
 */
@Composable
internal fun YuldashDirectionGlyph(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    disabled: Boolean = false,
    rotationDegrees: Float = 0f,
    body: Color? = null,
    road: Color? = null,
) {
    val leftColor = if (disabled) CanonMutedStrong.copy(alpha = 0.62f) else body ?: CanonGreenInk
    val lowerColor = if (disabled) CanonMuted.copy(alpha = 0.48f) else CanonGreenInkDark
    val topColor = if (disabled) CanonMuted.copy(alpha = 0.42f) else CanonCompassGoldSoft
    val rightColor = if (disabled) CanonMutedStrong.copy(alpha = 0.58f) else road ?: CanonCompassGold
    val disabledCutout = CanonSurface
    val disabledStroke = CanonMutedStrong
    val semanticsModifier = if (contentDescription == null) modifier else {
        modifier.semantics { this.contentDescription = contentDescription }
    }
    Canvas(semanticsModifier) {
        val side = size.minDimension
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val bodyPath = composePath(left, top, side, side, ::traceDirectionBody)
        val topPath = composePath(left, top, side, side, ::traceDirectionTop)
        val rightPath = composePath(left, top, side, side, ::traceDirectionRight)
        val lowerPath = composePath(left, top, side, side, ::traceDirectionLower)
        val leftPath = composePath(left, top, side, side, ::traceDirectionLeft)
        rotate(rotationDegrees, pivot = center) {
            drawPath(topPath, topColor)
            drawPath(rightPath, rightColor)
            drawPath(lowerPath, lowerColor)
            drawPath(leftPath, leftColor)
            drawPath(
                path = bodyPath,
                color = if (disabled) disabledStroke.copy(alpha = 0.28f) else CanonGreenInkDark.copy(alpha = 0.34f),
                style = Stroke(
                    width = (side * 0.0075f).coerceAtLeast(0.75f),
                    join = StrokeJoin.Round,
                ),
            )
        }
        if (disabled) {
            drawLine(
                color = disabledCutout,
                start = Offset(size.width * 0.22f, size.height * 0.20f),
                end = Offset(size.width * 0.80f, size.height * 0.82f),
                strokeWidth = (side * 0.09f).coerceAtLeast(2f),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = disabledStroke,
                start = Offset(size.width * 0.24f, size.height * 0.18f),
                end = Offset(size.width * 0.82f, size.height * 0.80f),
                strokeWidth = (side * 0.035f).coerceAtLeast(1f),
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Один и тот же курсор для MapKit; `platform=true` добавляет круглую подложку для кнопок. */
internal fun yuldashDirectionBitmap(
    sizePx: Int,
    surface: Int,
    body: Int,
    road: Int,
    platform: Boolean,
): Bitmap {
    val size = sizePx.coerceAtLeast(32)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val center = size / 2f
    // Утверждённый размер: 32 dp знака в 42 dp платформе. В 50 px Bitmap это 64% поля.
    val glyphSide = if (platform) size * 0.64f else size * 0.70f
    val left = center - glyphSide / 2f
    val top = center - glyphSide / 2f
    if (platform) {
        val radius = size * 0.42f
        canvas.drawCircle(
            center,
            center,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = surface
                setShadowLayer(size * 0.04f, 0f, size * 0.018f, 0x26000000)
            },
        )
        canvas.drawCircle(
            center,
            center,
            radius - size * 0.01f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = body
                alpha = 28
                style = Paint.Style.STROKE
                strokeWidth = size * 0.012f
            },
        )
    }
    canvas.save()
    // Вектор от центра (0.53;0.54) к носу (0.89;0.07) имеет угол −52,5°.
    // MapKit ожидает исходный нос строго на север (−90°), поэтому поворачиваем ещё на −37,5°.
    canvas.rotate(-37.5f, center, center)
    val bodyPath = androidPath(left, top, glyphSide, glyphSide, ::traceDirectionBody)
    if (!platform) {
        canvas.drawPath(
            bodyPath,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = surface
                style = Paint.Style.STROKE
                strokeWidth = size * 0.055f
                strokeJoin = Paint.Join.ROUND
                setShadowLayer(size * 0.035f, 0f, size * 0.018f, 0x30000000)
            },
        )
    }
    canvas.drawPath(
        androidPath(left, top, glyphSide, glyphSide, ::traceDirectionTop),
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CanonCompassGoldSoft.toArgb() },
    )
    canvas.drawPath(
        androidPath(left, top, glyphSide, glyphSide, ::traceDirectionRight),
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = road },
    )
    canvas.drawPath(
        androidPath(left, top, glyphSide, glyphSide, ::traceDirectionLower),
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CanonGreenInkDark.toArgb() },
    )
    canvas.drawPath(
        androidPath(left, top, glyphSide, glyphSide, ::traceDirectionLeft),
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = body },
    )
    canvas.drawPath(
        bodyPath,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CanonGreenInkDark.toArgb()
            alpha = 87
            style = Paint.Style.STROKE
            strokeWidth = (size * 0.0075f).coerceAtLeast(1f)
            strokeJoin = Paint.Join.ROUND
        },
    )
    canvas.restore()
    return bitmap
}
