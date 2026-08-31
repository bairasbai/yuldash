package com.yuldash.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Сторож утверждённого варианта B экрана деталей попутки: доверие к водителю прежде всего. */
class RideshareBookingGuardTest {

    private fun source(name: String): String {
        var dir = File("").absoluteFile
        repeat(6) {
            val direct = File(dir, "app/src/main/java/com/yuldash/app/$name")
            if (direct.isFile) return direct.readText()
            val nested = File(dir, "android/app/src/main/java/com/yuldash/app/$name")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile ?: return ""
        }
        return ""
    }

    @Test
    fun `option B keeps driver trust route and fixed decisions`() {
        val booking = source("BookingActiveTripScreen.kt")
        assertTrue(booking.contains("BookingDriverHeroCard("))
        assertTrue(booking.contains("DriverTrustBadges(verified = verified, trips = trips, since = since)"))
        assertTrue(booking.contains("BookingRouteDecisionCard("))
        assertTrue(booking.contains("BookingDecisionBar("))
        assertTrue(booking.contains("bottomBar = {"))
        assertTrue(booking.contains("Забронировать место"))
        assertTrue(booking.contains("Урынды бронләү"))
    }

    @Test
    fun `screen does not invent driver experience`() {
        val booking = source("BookingActiveTripScreen.kt")
        assertFalse(booking.contains("Опытный водитель"))
        assertFalse(booking.contains("Тәжрибәле йөрөтөүсе"))
    }
}
