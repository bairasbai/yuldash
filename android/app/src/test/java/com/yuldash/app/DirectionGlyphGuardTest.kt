package com.yuldash.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Не даёт экранам снова разойтись на системные стрелки после унификации 2026-08-28. */
class DirectionGlyphGuardTest {

    private fun source(name: String): String {
        var dir = File("").absoluteFile
        repeat(5) {
            val file = File(dir, "app/src/main/java/com/yuldash/app/$name")
            if (file.isFile) return file.readText()
            val nested = File(dir, "android/app/src/main/java/com/yuldash/app/$name")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile ?: return ""
        }
        return ""
    }

    @Test
    fun `direction component is the only geometry source for compose and mapkit`() {
        val geometry = source("DirectionGlyph.kt")
        assertTrue(geometry.contains("traceDirectionBody"))
        assertTrue(geometry.contains("traceDirectionTop"))
        assertTrue(geometry.contains("traceDirectionRight"))
        assertTrue(geometry.contains("traceDirectionLower"))
        assertTrue(geometry.contains("traceDirectionLeft"))
        assertTrue(geometry.contains("YuldashDirectionGlyph"))
        assertTrue(geometry.contains("yuldashDirectionBitmap"))
        assertTrue(source("MapPins.kt").contains("yuldashDirectionBitmap("))
        assertTrue(source("TaxiLocationMark.kt").contains("yuldashDirectionBitmap("))
    }

    @Test
    fun `navigation actions no longer use material direction arrows`() {
        val instant = source("InstantOrderScreen.kt")
        val map = source("MapScreen.kt")
        assertFalse(instant.contains("Icons.Default.Navigation"))
        assertFalse(map.contains("Icons.Default.NearMe"))
        assertFalse(map.contains("Icons.Default.NearMeDisabled"))
        // Два места: заголовок статуса поездки и кнопка открытия внешнего навигатора.
        assertTrue(Regex("YuldashDirectionGlyph\\(").findAll(instant).count() >= 2)
        assertTrue(map.contains("YuldashDirectionGlyph("))
        assertTrue(source("RidesRequestsChatScreens.kt").contains("YuldashDirectionGlyph("))
        assertTrue(source("SosVerifyScreens.kt").contains("YuldashDirectionGlyph("))
    }

    @Test
    fun `unrelated back destination and route framing icons remain distinct`() {
        assertTrue(source("CreateRideScreen.kt").contains("Icons.Default.NearMe"))
        // Эта кнопка показывает весь маршрут, а не направление человека — отдельный смысл.
        assertTrue(source("TaxiTripScreen.kt").contains("Icons.Default.MyLocation"))
        assertTrue(source("MapScreen.kt").contains("Icons.Default.ArrowBackIosNew"))
    }
}
