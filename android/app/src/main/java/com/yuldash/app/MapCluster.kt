package com.yuldash.app

// Метка группы машин на карте: кружок с числом вместо десятка отдельных машинок.
//
// Отдельный файл, а не MapPins.kt, намеренно: там сейчас идёт параллельная работа над
// иконками, и новый файл ничем с ней не пересекается.
//
// Зачем группировка. Восемь машин в одном квартале на карте размером в треть экрана —
// сплошное пятно, за которым не видно ни улиц, ни маршрута (замечание Александра:
// «не люблю, когда на маленькой карте много машин и не видно ничего»). Отдалил камеру —
// соседние машины собираются в один кружок с числом, приблизил — распадаются обратно.

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

// Кеш по числу: кружков всего десяток-другой, а перерисовка идёт на каждом движении камеры.
private val clusterCache = HashMap<Int, Bitmap>()

/**
 * Кружок с числом машин.
 *
 * Цвета фирменные и заданы числами, а не токенами `Canon*`: это обычный Canvas, а не Compose,
 * и @Composable-значения тут не читаются. Те же коды, что у метки машины в MapPins.
 *
 * Больше 99 не пишем: три цифры не помещаются в кружок, который должен оставаться меткой,
 * а не плашкой. «99+» честнее мелкого нечитаемого числа.
 */
internal fun carClusterBitmap(count: Int): Bitmap {
    clusterCache[count]?.let { return it }
    val taxi = android.graphics.Color.parseColor("#F5B301")   // CanonGold (такси-жёлтый)
    val ink = android.graphics.Color.parseColor("#3A2A00")    // тёмный текст на жёлтом
    val ring = android.graphics.Color.parseColor("#FFFFFF")   // белое кольцо — отрыв от карты

    val label = if (count > 99) "99+" else count.toString()
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        textSize = 30f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    // Диаметр от длины числа: «3» и «12» в кружке одного размера смотрятся по-разному,
    // а одинаковый кружок с разным полем вокруг цифры выглядит небрежно.
    val diameter = maxOf(64f, textPaint.measureText(label) + 40f)
    val pad = 6f
    val size = (diameter + pad * 2).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f

    // Тень рисуем на слое кольца: у Canvas тень идёт от фигуры, и если повесить её на
    // жёлтый круг, белое кольцо ляжет поверх и тень пропадёт.
    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ring
        setShadowLayer(4f, 0f, 2f, 0x40000000)
    }
    c.drawCircle(cx, cy, diameter / 2, ringPaint)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = taxi }
    c.drawCircle(cx, cy, diameter / 2 - 4f, fill)
    // Базовая линия текста: центр минус середина высоты шрифта — иначе цифра «висит»
    // выше середины кружка, и это видно даже без линейки.
    val fm = textPaint.fontMetrics
    c.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2, textPaint)

    return bmp.also { clusterCache[count] = it }
}
