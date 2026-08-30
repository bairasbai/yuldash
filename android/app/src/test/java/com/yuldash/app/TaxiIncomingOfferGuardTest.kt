package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Сторож утверждённого оффера A + блок доверия C. */
class TaxiIncomingOfferGuardTest {

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
    fun `offer keeps route live pickup trust timer and fixed decisions`() {
        val screen = source("app/src/main/java/com/yuldash/app/InstantOrderScreen.kt")
        assertTrue(screen.contains("InstantRouteMap("))
        assertTrue(screen.contains("interactive = false"))
        assertTrue(screen.contains("Входящий заказ"))
        assertTrue(screen.contains("order.offerPickupKm"))
        assertTrue(screen.contains("order.offerPickupEtaMin"))
        assertTrue(screen.contains("InstantOfferPassengerTrust(order)"))
        assertTrue(screen.contains("InstantOfferTimer(secondsLeft, timerProgress, canAccept)"))
        assertTrue(screen.contains("text = appText(\"Пропустить\""))
        assertTrue(screen.contains("text = appText(\"Принять\""))
        assertTrue(screen.contains("Modifier.weight(1f).verticalScroll"))
        assertTrue(screen.contains("navigationBarsPadding()"))
    }

    @Test
    fun `offer pickup distance comes from server payload`() {
        val api = source("app/src/main/java/com/yuldash/app/data/ApiClient.kt")
        assertTrue(api.contains("val offerPickupKm: Double? = null"))
        assertTrue(api.contains("val offerPickupEtaMin: Int? = null"))
        assertTrue(api.contains("optDouble(\"offer_pickup_km\")"))
        assertTrue(api.contains("optInt(\"offer_pickup_eta_min\")"))
    }
}
