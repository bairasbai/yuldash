package com.yuldash.app

import org.junit.Assert.assertFalse
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
        val routeCard = screen.substringAfter("private fun InstantOfferRouteCard(")
            .substringBefore("private fun InstantOfferMoneyAndClass(")
        assertTrue(routeCard.contains("Время подачи уточняется"))
        assertTrue(routeCard.contains("Килеп етеү ваҡыты асыҡлана"))
        assertFalse(routeCard.contains("order.offerPickupKm"))
        assertFalse(routeCard.contains("order.offerPickupEtaMin"))
        assertTrue(screen.contains("InstantOfferPassengerTrust(order)"))
        assertTrue(screen.contains("InstantOfferTimer(secondsLeft, timerProgress, canAccept)"))
        assertTrue(screen.contains("text = appText(\"Пропустить\""))
        assertTrue(screen.contains("text = appText(\"Принять\""))
        assertTrue(screen.contains("Modifier.weight(1f).verticalScroll"))
        assertTrue(screen.contains("navigationBarsPadding()"))
    }

    @Test
    fun `offer accept checks the real clock even between timer ticks`() {
        val screen = source("app/src/main/java/com/yuldash/app/InstantOrderScreen.kt")
        assertTrue(screen.contains("instantOfferRemainingMillis(order.offerExpiresAt, System.currentTimeMillis()) > 0L"))
    }

    @Test
    fun `driver offer preview supplies the money snapshot retained by copy`() {
        val preview = source("app/src/debug/java/com/yuldash/app/MobilityUiPreview.kt")
            .substringAfter("private fun previewDriverOfferOrder(")
            .substringBefore("private fun previewDriverTripOrder(")
        assertTrue(preview.contains("TaxiOfferMoneyDto(32_900, 31_913, 987, 3.0)"))
        assertTrue(preview.contains("offerMoney = money"))
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
