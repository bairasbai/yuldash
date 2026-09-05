package com.yuldash.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Сторож утверждённого ожидания C + подробной смены A. */
class TaxiDriverWaitingGuardTest {

    private fun source(relative: String): String {
        var dir = File("").absoluteFile
        repeat(6) {
            val direct = File(dir, relative)
            if (direct.isFile) return direct.readText()
            val nested = File(dir, "android/$relative")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile ?: return ""
        }
        return ""
    }

    @Test
    fun `waiting screen keeps map compact and detailed states with real metrics`() {
        val screen = source("app/src/main/java/com/yuldash/app/InstantOrderScreen.kt")
        assertTrue(screen.contains("internal fun InstantDriverWaitingScreen("))
        assertTrue(screen.contains("TaxiSheetScaffold("))
        assertTrue(screen.contains("initialStop: TaxiSheetStop = TaxiSheetStop.Peek"))
        assertTrue(screen.contains("TaxiSheetStop.Full) TaxiSheetStop.Half"))
        assertTrue(screen.contains("demandZones = demandZones"))
        assertTrue(screen.contains("workday.netTodayKop"))
        assertTrue(screen.contains("workday.ordersToday"))
        assertTrue(screen.contains("text = appText(\"Уйти с линии\""))
        assertTrue(screen.contains("start = CanonSpace.lg, end = CanonSpace.lg"))
        assertFalse(screen.contains("start = 72.dp, end = 72.dp"))
    }

    @Test
    fun `online controller preserves offer priority and server truth`() {
        val screen = source("app/src/main/java/com/yuldash/app/InstantOrderScreen.kt")
        val profile = source("app/src/main/java/com/yuldash/app/ProfileScreen.kt")
        assertTrue(screen.contains("ApiClient.instantPresence"))
        // Не `getDriverOffer`, а `getDriverOfferState`: в том же ответе приходит причина,
        // почему заказов не будет (документы, долг, смена, зона, проверка перед выездом).
        // Со старым вызовом экран молчал и обещал заказ, которого не дождаться.
        assertTrue(screen.contains("ApiClient.getDriverOfferState()"))
        assertTrue(screen.contains("offerBlocked = "))
        assertTrue(screen.contains("ApiClient.getInstantDemand()"))
        assertTrue(screen.contains("if (online && current == null)"))
        assertTrue(screen.contains("if (online && current != null)"))
        assertTrue(profile.contains("workday = workday"))
        assertTrue(profile.contains("zone = zone"))
        assertTrue(profile.contains("onGoOffline = { goOffline() }"))
    }

    @Test
    fun `demand map stays aggregated and private`() {
        val screen = source("app/src/main/java/com/yuldash/app/InstantOrderScreen.kt")
        assertTrue(screen.contains("demandZones: List<com.yuldash.app.data.DemandZoneDto>"))
        assertTrue(screen.contains("map.mapObjects.addCircle("))
        assertTrue(screen.contains("val radius = 420f + 880f * weight"))
        assertTrue(screen.contains("Активных зон:"))
    }
}
