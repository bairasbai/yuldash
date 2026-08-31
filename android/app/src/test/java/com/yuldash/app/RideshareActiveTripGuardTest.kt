package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Сторож утверждённого варианта B «безопасная посадка» экрана активной попутки. */
class RideshareActiveTripGuardTest {

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
    fun `option B keeps safety hierarchy and fixed action`() {
        val source = source("BookingActiveTripScreen.kt")
        assertTrue(source.contains("ActiveTripOptionBHero("))
        assertTrue(source.contains("ActiveTripBoardingCodeCard("))
        assertTrue(source.contains("ActiveTripProgressCard("))
        assertTrue(source.contains("ActiveTripRouteCard("))
        assertTrue(source.contains("ActiveTripDecisionBar("))
        assertTrue(source.contains("liveBookingId = liveBookingId"))
        assertTrue(source.contains("TripLocationBus.bookingId == liveBookingId"))
    }

    @Test
    fun `next action follows existing server state machine`() {
        assertEquals("departed", activeTripNextStatus("driver", "", null))
        assertEquals("arriving", activeTripNextStatus("driver", "departed", null))
        assertEquals("done", activeTripNextStatus("driver", "arriving", null))
        assertEquals("sat", activeTripNextStatus("passenger", "", null))
        assertEquals("arrived", activeTripNextStatus("passenger", "", "sat"))
        assertEquals("done", activeTripNextStatus("passenger", "", "arrived"))
    }

    @Test
    fun `screen does not invent unsupported eta or status`() {
        val source = source("BookingActiveTripScreen.kt")
        assertFalse(source.contains("Я уже выхожу"))
        assertFalse(source.contains("Мин сығам инде"))
    }
}
