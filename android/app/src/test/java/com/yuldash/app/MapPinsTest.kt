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
