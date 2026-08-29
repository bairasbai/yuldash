package com.yuldash.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class TaxiUserLocationVisual {
    Dot,
    DotWithDirection,
    Arrow,
    Selected,
}

internal data class TaxiUserLocationBitmap(
    val bitmap: Bitmap,
    val anchorY: Float = 0.5f,
)

private data class TaxiUserLocationBitmapKey(
    val visual: TaxiUserLocationVisual,
    val densityBucket: Int,
    val surface: Int,
    val green: Int,
    val gold: Int,
    val text: Int,
    val label: String,
)

private val taxiUserLocationBitmapCache = HashMap<TaxiUserLocationBitmapKey, TaxiUserLocationBitmap>()

/**
 * Растровая оболочка нужна только MapKit: сама графика строится кодом и остаётся резкой на
 * любой плотности. Точка с жёлтым лучом показывает направление телефона, стрелка —
 * подтверждённый GPS-курс при движении, полный знак появляется только после нажатия.
 * Букв в геолокации нет намеренно.
 */
internal fun taxiUserLocationBitmap(
    context: Context,
    visual: TaxiUserLocationVisual,
    surface: Int,
    green: Int,
    gold: Int,
    text: Int,
    selectedLabel: String,
): TaxiUserLocationBitmap {
    val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
    val key = TaxiUserLocationBitmapKey(
        visual = visual,
        densityBucket = (density * 100).toInt(),
        surface = surface,
        green = green,
        gold = gold,
        text = text,
        label = if (visual == TaxiUserLocationVisual.Selected) selectedLabel else "",
    )
    taxiUserLocationBitmapCache[key]?.let { return it }

    fun dp(value: Float): Float = value * density
    fun liftedPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        setShadowLayer(dp(3.5f), 0f, dp(1.5f), 0x3D000000)
    }
    fun drawPlatform(canvas: AndroidCanvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, liftedPaint(surface))
        canvas.drawCircle(
            cx,
            cy,
            radius - dp(0.75f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = green
                alpha = 46
                style = Paint.Style.STROKE
                strokeWidth = dp(1f)
            },
        )
    }

    val rendered = when (visual) {
        TaxiUserLocationVisual.Dot,
        TaxiUserLocationVisual.DotWithDirection -> {
            val size = dp(58f).toInt().coerceAtLeast(58)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = AndroidCanvas(bitmap)
            val center = size / 2f
            if (visual == TaxiUserLocationVisual.DotWithDirection) {
                // Утверждённый B: не стрелка и не «ракета», а луч фонаря от самой точки.
                // Вся картинка поворачивается MapKit по реальному компасу телефона.
                val beam = AndroidPath().apply {
                    moveTo(center - dp(3.2f), center - dp(3.5f))
                    cubicTo(
                        center - dp(4.8f), center - dp(9f),
                        center - dp(10.5f), center - dp(20f),
                        center - dp(12.5f), center - dp(26f),
                    )
                    lineTo(center + dp(12.5f), center - dp(26f))
                    cubicTo(
                        center + dp(10.5f), center - dp(20f),
                        center + dp(4.8f), center - dp(9f),
                        center + dp(3.2f), center - dp(3.5f),
                    )
                    close()
                }
                canvas.drawPath(
                    beam,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(
                            center,
                            center - dp(3f),
                            center,
                            center - dp(26f),
                            (gold and 0x00FFFFFF) or (218 shl 24),
                            (gold and 0x00FFFFFF) or (58 shl 24),
                            Shader.TileMode.CLAMP,
                        )
                    },
                )
            }
            // Белое/тёмное кольцо отделяет координату от карты; зелёный центр — сама точка.
            canvas.drawCircle(center, center, dp(10.5f), liftedPaint(surface))
            canvas.drawCircle(center, center, dp(6.4f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green })
            TaxiUserLocationBitmap(bitmap)
        }

        TaxiUserLocationVisual.Arrow -> {
            // Утверждённый финал: крупный четырёхгранный курсор Юлдаш внутри светлого круга.
            // Геометрия общая с кнопками и live-маркерами; MapKit получает нос строго на север.
            // В 50 dp Bitmap: круг 42 dp, знак 32 dp; по краям остаётся прозрачный запас,
            // поэтому тень и контур не обрезаются.
            val size = dp(50f).toInt().coerceAtLeast(50)
            TaxiUserLocationBitmap(
                yuldashDirectionBitmap(
                    sizePx = size,
                    surface = surface,
                    body = green,
                    road = gold,
                    platform = true,
                ),
            )
        }

        TaxiUserLocationVisual.Selected -> {
            val width = dp(124f).toInt().coerceAtLeast(124)
            val height = dp(98f).toInt().coerceAtLeast(98)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = AndroidCanvas(bitmap)
            val cx = width / 2f
            val cy = dp(34f)
            val radius = dp(29f)
            drawPlatform(canvas, cx, cy, radius)

            val logo = BitmapFactory.decodeResource(context.resources, R.drawable.yuldash_logo)
            val logoHeight = dp(45f)
            val logoWidth = logoHeight * logo.width / logo.height
            canvas.drawBitmap(
                logo,
                Rect(0, 0, logo.width, logo.height),
                RectF(cx - logoWidth / 2f, cy - logoHeight / 2f, cx + logoWidth / 2f, cy + logoHeight / 2f),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )

            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = text
                textSize = dp(13f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val horizontalPadding = dp(13f)
            val pillWidth = (labelPaint.measureText(selectedLabel) + horizontalPadding * 2f)
                .coerceAtMost(width - dp(8f))
            val pill = RectF(
                cx - pillWidth / 2f,
                dp(69f),
                cx + pillWidth / 2f,
                dp(93f),
            )
            canvas.drawRoundRect(pill, dp(12f), dp(12f), liftedPaint(surface))
            canvas.drawText(selectedLabel, cx, pill.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2f, labelPaint)
            TaxiUserLocationBitmap(bitmap, anchorY = cy / height)
        }
    }
    return rendered.also { taxiUserLocationBitmapCache[key] = it }
}

/**
 * Фирменный автомобиль такси для компактных строк и плашек.
 *
 * Системная пиктограмма показывает машину спереди и на малом размере превращается в тяжёлый
 * квадрат. Здесь силуэт читается сбоку: длинная линия кузова, низкая крыша и отдельные колёса
 * дают спокойный премиальный характер без мелких декоративных деталей.
 */
@Composable
internal fun TaxiCarGlyph(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tone: Color = CanonTaxi,
) {
    val accessibleModifier = if (contentDescription == null) {
        modifier
    } else {
        modifier.semantics { this.contentDescription = contentDescription }
    }

    Canvas(accessibleModifier) {
        val w = size.width
        val h = size.height
        val line = (size.minDimension * 0.085f).coerceAtLeast(1.dp.toPx())
        val body = Path().apply {
            moveTo(w * 0.06f, h * 0.66f)
            lineTo(w * 0.08f, h * 0.57f)
            cubicTo(w * 0.10f, h * 0.51f, w * 0.18f, h * 0.47f, w * 0.28f, h * 0.45f)
            lineTo(w * 0.38f, h * 0.27f)
            cubicTo(w * 0.42f, h * 0.20f, w * 0.49f, h * 0.17f, w * 0.57f, h * 0.17f)
            lineTo(w * 0.66f, h * 0.17f)
            cubicTo(w * 0.72f, h * 0.18f, w * 0.77f, h * 0.23f, w * 0.81f, h * 0.31f)
            lineTo(w * 0.89f, h * 0.47f)
            cubicTo(w * 0.95f, h * 0.49f, w * 0.97f, h * 0.55f, w * 0.97f, h * 0.62f)
            lineTo(w * 0.97f, h * 0.68f)
            lineTo(w * 0.89f, h * 0.68f)
            moveTo(w * 0.62f, h * 0.68f)
            lineTo(w * 0.38f, h * 0.68f)
            moveTo(w * 0.12f, h * 0.68f)
            lineTo(w * 0.06f, h * 0.66f)
        }
        drawPath(
            path = body,
            color = tone,
            style = Stroke(width = line, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Пояс кузова и стойка стекла остаются тоньше контура: силуэт не шумит на 18–24 dp.
        drawLine(
            color = tone.copy(alpha = 0.72f),
            start = Offset(w * 0.29f, h * 0.46f),
            end = Offset(w * 0.88f, h * 0.48f),
            strokeWidth = line * 0.72f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tone.copy(alpha = 0.72f),
            start = Offset(w * 0.58f, h * 0.20f),
            end = Offset(w * 0.52f, h * 0.45f),
            strokeWidth = line * 0.65f,
            cap = StrokeCap.Round,
        )

        val wheelRadius = h * 0.105f
        val wheelStroke = line * 1.04f
        drawCircle(
            color = tone,
            radius = wheelRadius,
            center = Offset(w * 0.25f, h * 0.70f),
            style = Stroke(width = wheelStroke),
        )
        drawCircle(
            color = tone,
            radius = wheelRadius,
            center = Offset(w * 0.75f, h * 0.70f),
            style = Stroke(width = wheelStroke),
        )
    }
}

/**
 * Фирменный знак точки посадки.
 *
 * Это не системный pin и не «прицел»: мягкая капля обозначает конкретное место, белое ядро
 * сохраняет чистоту на карте, а маленькая золотая точка связывает знак именно с режимом такси.
 * Рисуем вектором, поэтому он одинаково резкий на любом размере экрана.
 */
@Composable
internal fun TaxiPickupGlyph(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val accent = CanonGreen2
    val core = CanonOnAccent
    val taxiDot = CanonTaxi
    val accessibleModifier = if (contentDescription == null) {
        modifier
    } else {
        modifier.semantics { this.contentDescription = contentDescription }
    }

    Canvas(accessibleModifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val pin = Path().apply {
            moveTo(cx, h * 0.97f)
            cubicTo(w * 0.43f, h * 0.86f, w * 0.13f, h * 0.64f, w * 0.13f, h * 0.38f)
            cubicTo(w * 0.13f, h * 0.18f, w * 0.29f, h * 0.04f, cx, h * 0.04f)
            cubicTo(w * 0.71f, h * 0.04f, w * 0.87f, h * 0.18f, w * 0.87f, h * 0.38f)
            cubicTo(w * 0.87f, h * 0.64f, w * 0.57f, h * 0.86f, cx, h * 0.97f)
            close()
        }
        drawPath(pin, accent)
        drawCircle(
            color = core,
            radius = size.minDimension * 0.165f,
            center = Offset(cx, h * 0.37f),
        )
        drawCircle(
            color = taxiDot,
            radius = size.minDimension * 0.058f,
            center = Offset(cx, h * 0.37f),
        )
    }
}

/** Белая плавающая метка поверх карты с едва заметной тенью точки привязки. */
@Composable
internal fun TaxiPickupMapMarker(
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val accent = CanonGreen2
    Surface(
        shape = CircleShape,
        color = CanonSurface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
        shadowElevation = CanonDepth.raised,
        modifier = modifier.size(52.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .align(Alignment.Center)
                    .offset(y = 15.dp)
                    .size(width = 20.dp, height = 5.dp),
            ) {
                drawOval(accent.copy(alpha = 0.14f))
            }
            TaxiPickupGlyph(
                contentDescription = contentDescription,
                modifier = Modifier.offset(y = (-1).dp).size(width = 28.dp, height = 33.dp),
            )
        }
    }
}

/**
 * Действие «Где я»: собственная двухтональная стрелка-компас.
 * Светлая грань даёт объём без обводки и не превращает кнопку в ещё одну точку маршрута.
 */
@Composable
internal fun TaxiLocateGlyph(
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    YuldashDirectionGlyph(
        contentDescription = contentDescription,
        modifier = modifier,
    )
}
