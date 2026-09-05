package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Сторож утверждённого гибрида B+A для откликов на заявку. */
class RequestResponsesGuardTest {

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
    fun `option B plus A keeps price first and trust next`() {
        val screen = source("RidesRequestsChatScreens.kt")
        assertTrue(screen.contains("ResponseMoneyHero(response)"))
        assertTrue(screen.contains("ResponseDriverTrust(response)"))
        assertTrue(screen.indexOf("ResponseMoneyHero(response)") < screen.indexOf("ResponseDriverTrust(response)"))
        // «Твой», а не «Ваш»: в Юлдаше обращение на «ты», это держит ToneSourceGuardTest.
        assertTrue(screen.contains("Твой бюджет"))
        assertTrue(screen.contains("Предложение"))
        assertTrue(screen.contains("Цена фиксируется после принятия"))
    }

    @Test
    fun `screen preserves every bargain decision and adaptive layout`() {
        val screen = source("RidesRequestsChatScreens.kt")
        assertTrue(screen.contains("canAccept = canTake"))
        assertTrue(screen.contains("canCounter = response.canCounter"))
        assertTrue(screen.contains("canDecline = canDecline"))
        assertTrue(screen.contains("LocalDensity.current.fontScale <= 1.15f"))
        assertTrue(screen.contains("appText(\"Отказать\", \"Баш тартыу\")"))
        assertTrue(screen.contains("appText(\"Своя цена\", \"Үҙ хаҡың\")"))
        assertTrue(screen.contains("appText(\"Принять\", \"Ҡабул итеү\")"))
    }

    @Test
    fun `api carries only real request and public driver facts`() {
        val api = source("data/ApiClient.kt")
        assertTrue(api.contains("driverVerified = optBoolean(\"driver_verified\")"))
        assertTrue(api.contains("driverTripsCount = optInt(\"driver_trips_count\")"))
        assertTrue(api.contains("driverCar = optString(\"driver_car\")"))
        assertTrue(api.contains("requestMaxPrice = optInt(\"request_max_price\")"))
        assertTrue(api.contains("requestDesiredAt = optString(\"request_desired_at\")"))
    }
}
