package com.yuldash.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Сторож утверждённого варианта B «Рәхмәт прежде всего». */
class RideshareCompletedGuardTest {

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
    fun `done trip opens dedicated option B before returning home`() {
        val active = source("BookingActiveTripScreen.kt")
        assertTrue(active.contains("if (bookingStatus == \"done\" && bookingId != null)"))
        assertTrue(active.contains("RideshareCompletedScreen("))
        assertTrue(active.contains("onClose = onTripEnd"))
        assertTrue(active.contains("bookingStatus = \"done\""))
    }

    @Test
    fun `option B keeps all post trip actions and honest payment wording`() {
        val screen = source("RideshareCompletedScreen.kt")
        assertTrue(screen.contains("ApiClient.rateBooking"))
        assertTrue(screen.contains("ApiClient.sayBookingThanks"))
        assertTrue(screen.contains("ApiClient.bookingLostItem"))
        assertTrue(screen.contains("PayOnlineCard("))
        assertTrue(screen.contains("onOpenReceipt"))
        assertTrue(screen.contains("Как договорились"))
        assertTrue(screen.contains("if (paid) appText(\"Оплата подтверждена\""))
        assertFalse(screen.contains("Оплачено ·"))
    }

    @Test
    fun `receipt restores only own rating and counterparty`() {
        val api = source("data/ApiClient.kt")
        assertTrue(api.contains("counterpartyName = o.optString(\"counterparty_name\")"))
        assertTrue(api.contains("myStars = o.optInt(\"my_stars\")"))
        assertTrue(api.contains("myRatingTags = o.optString(\"my_rating_tags\")"))
    }
}
