package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Не даёт активной поездке снова потерять утверждённую связку C (карта) + A (детали). */
class TaxiTripAdaptiveGuardTest {

    private fun source(name: String): String {
        var dir = File("").absoluteFile
        repeat(5) {
            val direct = File(dir, "app/src/main/java/com/yuldash/app/$name")
            if (direct.isFile) return direct.readText()
            val nested = File(dir, "android/app/src/main/java/com/yuldash/app/$name")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile ?: return ""
        }
        return ""
    }

    @Test
    fun `accepted opens compact map first and arriving reveals details`() {
        val trip = source("TaxiTripScreen.kt")
        assertTrue(trip.contains("\"accepted\" -> TaxiSheetStop.Peek"))
        assertTrue(trip.contains("if (order.status == \"arriving\")"))
        assertTrue(trip.contains("stop = TaxiSheetStop.Half"))
        assertTrue(trip.contains("TripCompactHeader("))
        assertTrue(trip.contains("stop == TaxiSheetStop.Peek"))
    }

    @Test
    fun `compact state keeps trust contact payment safety and primary action`() {
        val trip = source("TaxiTripScreen.kt")
        assertTrue(trip.contains("TripDriverAvatar(url = order.driverAvatar"))
        assertTrue(trip.contains("TripCompactAction("))
        assertTrue(trip.contains("TripCompactPaymentSafety("))
        assertTrue(trip.contains("TripImComingButton(order.id)"))
        assertTrue(trip.contains("AppButtonStyle.Primary"))
        assertTrue(trip.contains("TripMinimizeButton("))
        assertTrue(source("InstantOrderScreen.kt").contains("onMinimize = onBack"))
    }

    @Test
    fun `map follows a live point until a real map gesture interrupts it`() {
        val map = source("InstantOrderScreen.kt")
        assertTrue(map.contains("followPoint: Point? = null"))
        assertTrue(map.contains("followBearing: Double? = null"))
        assertTrue(map.contains("reason == CameraUpdateReason.GESTURES"))
        assertTrue(map.contains("onFollowInterrupted()"))
        assertTrue(map.contains("CameraPosition(point, 16.2f"))
        assertTrue(map.contains("window.focusPoint = com.yandex.mapkit.ScreenPoint("))
        assertTrue(map.contains("window.height() * followFocusY.coerceIn(0.2f, 0.5f)"))
        assertTrue(map.contains(".setScale(if (followEnabled) 1.28f else 1f)"))
    }

    @Test
    fun `passenger and driver onboard screens share honest navigator language`() {
        val passenger = source("TaxiTripScreen.kt")
        val driver = source("TaxiDriverNavigationScreen.kt")
        val host = source("InstantOrderScreen.kt")
        assertTrue(passenger.contains("TripOnboardRouteSummary(order)"))
        assertTrue(passenger.contains("navigatorFollow = true"))
        assertTrue(driver.contains("internal fun TaxiDriverOnboardNavigator"))
        assertTrue(driver.contains("Открыть голосовой навигатор"))
        assertTrue(driver.contains("followEnabled = following && locationFix != null"))
        assertTrue(host.contains("current.status == \"onboard\" -> TaxiDriverOnboardNavigator("))
    }
}
