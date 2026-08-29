package com.yuldash.app

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Битмап-иконки маркеров (MapPins.kt) — чистая графика (Bitmap/Canvas/Paint), без Compose.
 * Реальный рендер требует нативной графики Robolectric → @GraphicsMode(NATIVE). Проверяем:
 * каждый билдер отдаёт непустой Bitmap (width>0/height>0, ARGB_8888); постоянные иконки кешируются
 * (тот же инстанс на повторный вызов); ridePin кешируется по ключу (цена|boosted) и разные ключи
 * дают разные битмапы; разные аргументы не роняют.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapPinsTest {

    private fun assertNonEmpty(b: Bitmap) {
        assertTrue("width должен быть > 0", b.width > 0)
        assertTrue("height должен быть > 0", b.height > 0)
        assertEquals(Bitmap.Config.ARGB_8888, b.config)
    }

    // --- userPuckBitmap: точка «моя геопозиция», постоянная → кеш ---

    @Test
    fun userPuck_returnsNonEmptyBitmap() {
        assertNonEmpty(userPuckBitmap())
    }

    @Test
    fun userPuck_isCached_sameInstance() {
        assertSame(userPuckBitmap(), userPuckBitmap())
    }

    // --- destFlagBitmap: флажок пункта Б, постоянный → кеш ---

    @Test
    fun destFlag_returnsNonEmptyBitmap() {
        assertNonEmpty(destFlagBitmap())
    }

    @Test
    fun destFlag_isCached_sameInstance() {
        assertSame(destFlagBitmap(), destFlagBitmap())
    }

    // --- requestPinBitmap: оранжевый человечек «ищет попутку», постоянный → кеш ---

    @Test
    fun requestPin_returnsNonEmptyBitmap() {
        assertNonEmpty(requestPinBitmap())
    }

    @Test
    fun requestPin_isCached_sameInstance() {
        assertSame(requestPinBitmap(), requestPinBitmap())
    }

    // --- peerArrowBitmap: нав-стрелка другого участника, постоянная → кеш ---

    @Test
    fun peerArrow_returnsNonEmptyBitmap() {
        assertNonEmpty(peerArrowBitmap())
    }

    @Test
    fun peerArrow_isCached_sameInstance() {
        assertSame(peerArrowBitmap(), peerArrowBitmap())
    }

    @Test
    fun peerArrow_usesBrandBodyAndRoadFaces() {
        val body = android.graphics.Color.rgb(11, 107, 58)
        val road = android.graphics.Color.rgb(245, 179, 1)
        val bitmap = peerArrowBitmap(body = body, road = road)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("на курсоре должна быть фирменная зелёная грань", pixels.any { it == body })
        assertTrue("на курсоре должна быть глубокая зелёная грань", pixels.any { it == 0xFF073F25.toInt() })
        assertTrue("на курсоре должна быть мягкая золотая грань", pixels.any { it == 0xFFFFE3A1.toInt() })
        assertTrue(
            "на курсоре должна быть основная золотая грань",
            pixels.any {
                android.graphics.Color.alpha(it) > 200 &&
                    kotlin.math.abs(android.graphics.Color.red(it) - android.graphics.Color.red(road)) < 40 &&
                    kotlin.math.abs(android.graphics.Color.green(it) - android.graphics.Color.green(road)) < 24 &&
                    kotlin.math.abs(android.graphics.Color.blue(it) - android.graphics.Color.blue(road)) < 24
            },
        )
    }

    @Test
    fun peerArrow_isLargeEnoughButKeepsSafeEdges() {
        val bitmap = peerArrowBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val solidInk = pixels.count { android.graphics.Color.alpha(it) >= 180 }
        assertTrue(
            "курсор не должен снова сжаться в маленький лист",
            solidInk > pixels.size * 0.10f,
        )
        assertTrue("курсор не должен касаться верхнего или нижнего края", (0 until bitmap.width).all { x ->
            android.graphics.Color.alpha(bitmap.getPixel(x, 0)) == 0 &&
                android.graphics.Color.alpha(bitmap.getPixel(x, bitmap.height - 1)) == 0
        })
        assertTrue("курсор не должен касаться левого или правого края", (0 until bitmap.height).all { y ->
            android.graphics.Color.alpha(bitmap.getPixel(0, y)) == 0 &&
                android.graphics.Color.alpha(bitmap.getPixel(bitmap.width - 1, y)) == 0
        })
    }

    @Test
    fun ownLocationArrow_hasNoRoundPlatformAndIsNotClipped() {
        val surface = 0xFFFFFFFF.toInt()
        val bitmap = yuldashDirectionBitmap(
            sizePx = 100,
            surface = surface,
            body = 0xFF0B6B3A.toInt(),
            road = 0xFFF5B301.toInt(),
            platform = false,
        )
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val surfacePixels = pixels.count { it == surface }
        assertTrue(
            "у стрелки на карте не должно оставаться круглой светлой платформы: $surfacePixels/${pixels.size}",
            surfacePixels < pixels.size * 0.15f,
        )
        assertTrue("стрелка не должна обрезаться сверху или снизу", (0 until bitmap.width).all { x ->
            android.graphics.Color.alpha(bitmap.getPixel(x, 0)) == 0 &&
                android.graphics.Color.alpha(bitmap.getPixel(x, bitmap.height - 1)) == 0
        })
        assertTrue("стрелка не должна обрезаться слева или справа", (0 until bitmap.height).all { y ->
            android.graphics.Color.alpha(bitmap.getPixel(0, y)) == 0 &&
                android.graphics.Color.alpha(bitmap.getPixel(bitmap.width - 1, y)) == 0
        })
    }

    @Test
    fun peerArrow_themeColors_areSeparateCacheKeys() {
        val light = peerArrowBitmap(surface = 0xFFFFFFFF.toInt(), body = 0xFF0B6B3A.toInt(), road = 0xFFE8A200.toInt())
        val dark = peerArrowBitmap(surface = 0xFF192420.toInt(), body = 0xFF27A463.toInt(), road = 0xFFF2C14E.toInt())
        assertNotSame(light, dark)
        assertSame(dark, peerArrowBitmap(surface = 0xFF192420.toInt(), body = 0xFF27A463.toInt(), road = 0xFFF2C14E.toInt()))
    }

    // --- ridePinBitmap: ценник, кеш по ключу (цена|boosted) ---

    @Test
    fun ridePin_returnsNonEmptyBitmap() {
        assertNonEmpty(ridePinBitmap("250 ₽", boosted = false))
    }

    @Test
    fun ridePin_boostedVariant_returnsNonEmptyBitmap() {
        assertNonEmpty(ridePinBitmap("250 ₽", boosted = true))
    }

    @Test
    fun ridePin_sameKey_isCached_sameInstance() {
        assertSame(
            ridePinBitmap("300 ₽", boosted = false),
            ridePinBitmap("300 ₽", boosted = false),
        )
    }

    @Test
    fun ridePin_differentBoosted_producesDifferentBitmaps() {
        // ключ = "$price|$boosted" → boosted меняет кеш-ключ.
        assertNotSame(
            ridePinBitmap("300 ₽", boosted = false),
            ridePinBitmap("300 ₽", boosted = true),
        )
    }

    @Test
    fun ridePin_differentPrice_producesDifferentBitmaps() {
        assertNotSame(
            ridePinBitmap("100 ₽", boosted = false),
            ridePinBitmap("9999 ₽", boosted = false),
        )
    }

    @Test
    fun ridePin_longerPrice_isWiderBitmap() {
        // ширина пилюли = measureText(price) + паддинги → длиннее текст = шире битмап.
        val short = ridePinBitmap("1 ₽", boosted = false)
        val long = ridePinBitmap("123456789 ₽", boosted = false)
        assertTrue(
            "длинный ценник (${long.width}) должен быть шире короткого (${short.width})",
            long.width > short.width,
        )
    }
}
