package com.yuldash.app

// Битмап-иконки для маркеров Яндекс-карты. Вынесено из MapScreen.kt (Спринт 3 — разрезка гиганта).
// Чистая графика (Bitmap/Canvas/Paint), без Compose и состояния экрана. Тот же пакет com.yuldash.app
// → вызовы из MapScreen (userPuckBitmap() и т.д.) резолвятся без импортов. Кеши постоянных иконок —
// рисуем один раз на процесс (GPS шлёт апдейты часто, без кеша = лишние аллокации Bitmap+Paint и GC).

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

// Маркер-«ценник» (стиль Яндекс/Airbnb): белая пилюля с ценой, цветная рамка, остриё вниз.
// Boosted-поездка — золотой акцент, обычная — фирменный зелёный.
// Метка «моя геопозиция»: круглая точка (тень + белое кольцо + зелёный центр).
// Метка геопозиции постоянна → рисуем один раз и переиспользуем (GPS шлёт апдейты ~раз в 2с,
// без кеша это была новая Bitmap+Canvas+3 Paint на каждый апдейт → лишний GC и аллокации).
private var userPuckCache: Bitmap? = null
internal fun userPuckBitmap(): Bitmap {
    userPuckCache?.let { return it }
    val size = 64
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f
    c.drawCircle(cx, cy, 19f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#22000000") })
    c.drawCircle(cx, cy, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    c.drawCircle(cx, cy, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#0B6B3A") })
    return bmp.also { userPuckCache = it }
}

// Флажок пункта назначения (точка Б) — зелёный вымпел на флагштоке. Якорь у основания.
// Тоже постоянный → кешируем.
private var destFlagCache: Bitmap? = null
internal fun destFlagBitmap(): Bitmap {
    destFlagCache?.let { return it }
    val w = 50
    val h = 62
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#0B6B3A") }
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    c.drawOval(android.graphics.RectF(4f, h - 12f, 22f, h - 2f), shadow)   // тень у земли
    c.drawRect(11f, 8f, 14.5f, h - 6f, green)                              // флагшток
    // полотнище: белая кайма + зелёный вымпел
    c.drawPath(android.graphics.Path().apply { moveTo(14.5f, 6f); lineTo(46f, 16f); lineTo(14.5f, 28f); close() }, white)
    c.drawPath(android.graphics.Path().apply { moveTo(16f, 9.5f); lineTo(41f, 16f); lineTo(16f, 24.5f); close() }, green)
    c.drawCircle(12.7f, h - 6f, 5f, white)                                 // точка у основания
    c.drawCircle(12.7f, h - 6f, 3f, green)
    return bmp.also { destFlagCache = it }
}

// Маркер заявки пассажира («ищет попутку») — оранжевый человечек, визуально отличается от ценников поездок.
private var requestPinCache: Bitmap? = null
internal fun requestPinBitmap(): Bitmap {
    requestPinCache?.let { return it }
    val s = 46
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val accent = android.graphics.Color.parseColor("#E07B00")   // оранжевый = заявка (не поездка)
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    val cx = s / 2f; val cy = s / 2f
    c.drawCircle(cx, cy + 2f, 18f, shadow)
    c.drawCircle(cx, cy, 18f, white)        // белая кайма
    c.drawCircle(cx, cy, 14f, fill)         // оранжевый круг
    c.drawCircle(cx, cy - 3f, 4.5f, white)  // голова человечка
    c.drawRoundRect(android.graphics.RectF(cx - 6f, cy + 1f, cx + 6f, cy + 10f), 4f, 4f, white)  // тело
    return bmp.also { requestPinCache = it }
}

// Маркер другого участника (live-позиция) — нав-стрелка курса (как в навигаторах). Остриё = направление
// движения; MapKit поворачивает её по bearing. Синяя с белой обводкой (контраст на карте).
private var peerArrowCache: Bitmap? = null
internal fun peerArrowBitmap(): Bitmap {
    peerArrowCache?.let { return it }
    val s = 54
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = s / 2f
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#1565C0") }
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 5f; strokeJoin = Paint.Join.ROUND
    }
    // Стрелка вверх: остриё сверху, крылья вниз, вырез снизу (классический «курс»).
    val p = android.graphics.Path().apply {
        moveTo(cx, 7f)              // остриё (направление)
        lineTo(s - 11f, s - 9f)     // правое крыло
        lineTo(cx, s - 19f)         // вырез (вогнутый низ)
        lineTo(11f, s - 9f)         // левое крыло
        close()
    }
    c.drawPath(p, outline)   // белая обводка под заливкой
    c.drawPath(p, fill)
    return bmp.also { peerArrowCache = it }
}

// Ценник-маркер зависит только от (цена, boosted) → кешируем по ключу,
// чтобы при перерисовке/смене поездки не лепить заново Bitmap+Paint каждый раз.
private val ridePinCache = HashMap<String, Bitmap>()
internal fun ridePinBitmap(price: String, boosted: Boolean): Bitmap {
    val cacheKey = "$price|$boosted"
    ridePinCache[cacheKey]?.let { return it }
    val accent = android.graphics.Color.parseColor(if (boosted) "#C98A00" else "#0B6B3A")
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val padX = 22f
    val padY = 13f
    val pointer = 16f
    val pad = 6f // запас под тень
    val pillH = textPaint.textSize + padY * 2
    val pillW = textPaint.measureText(price) + padX * 2
    val bmp = Bitmap.createBitmap(
        (pillW + pad * 2).toInt(),
        (pillH + pointer + pad * 2).toInt(),
        Bitmap.Config.ARGB_8888
    )
    val c = Canvas(bmp)
    val left = pad; val right = pad + pillW
    val pillTop = pad + pointer; val pillBottom = pillTop + pillH
    val radius = pillH / 2
    val cx = (left + right) / 2
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = accent
    }
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    // остриё СВЕРХУ (смотрит на город), пилюля под ним → цена ниже названия города
    val tip = android.graphics.Path().apply {
        moveTo(cx - pointer / 2, pillTop + 2f)
        lineTo(cx + pointer / 2, pillTop + 2f)
        lineTo(cx, pad)
        close()
    }
    c.drawRoundRect(left, pillTop + 3f, right, pillBottom + 3f, radius, radius, shadow)
    c.drawPath(tip, white)
    c.drawPath(tip, border)
    c.drawRoundRect(left, pillTop, right, pillBottom, radius, radius, white)
    c.drawRoundRect(left, pillTop, right, pillBottom, radius, radius, border)
    val fm = textPaint.fontMetrics
    val ty = pillTop + pillH / 2 - (fm.ascent + fm.descent) / 2
    c.drawText(price, left + padX, ty, textPaint)
    return bmp.also { ridePinCache[cacheKey] = it }
}

private val carEtaCache = HashMap<String, Bitmap>()

/** Маркер «свободная машина рядом» для карты такси: жёлтая (такси) пилюля «🚕 ≈N мин»,
 *  остриё СНИЗУ — пилюля висит НАД реальной точкой машины (anchor 0.5, 1.0).
 *  Показывает только правду: рядом есть машина на линии и примерное время подачи. */
internal fun carEtaBitmap(eta: String): Bitmap {
    carEtaCache[eta]?.let { return it }
    val taxi = android.graphics.Color.parseColor("#F5B301")   // CanonGold (такси-жёлтый)
    val ink = android.graphics.Color.parseColor("#3A2A00")    // тёмный текст на жёлтом (читаем)
    val label = "🚕 $eta"
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink; textSize = 30f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val padX = 18f; val padY = 11f; val pointer = 14f; val pad = 6f
    val pillH = textPaint.textSize + padY * 2
    val pillW = textPaint.measureText(label) + padX * 2
    val bmp = Bitmap.createBitmap(
        (pillW + pad * 2).toInt(),
        (pillH + pointer + pad * 2).toInt(),
        Bitmap.Config.ARGB_8888,
    )
    val c = Canvas(bmp)
    val left = pad; val right = pad + pillW
    val pillTop = pad; val pillBottom = pillTop + pillH
    val radius = pillH / 2
    val cx = (left + right) / 2
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = taxi }
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    // остриё СНИЗУ (смотрит на точку машины на карте)
    val tip = android.graphics.Path().apply {
        moveTo(cx - pointer / 2, pillBottom - 2f)
        lineTo(cx + pointer / 2, pillBottom - 2f)
        lineTo(cx, pillBottom + pointer)
        close()
    }
    c.drawRoundRect(left, pillTop + 3f, right, pillBottom + 3f, radius, radius, shadow)
    c.drawPath(tip, fill)
    c.drawRoundRect(left, pillTop, right, pillBottom, radius, radius, fill)
    val fm = textPaint.fontMetrics
    val ty = pillTop + pillH / 2 - (fm.ascent + fm.descent) / 2
    c.drawText(label, left + padX, ty, textPaint)
    return bmp.also { carEtaCache[eta] = it }
}
