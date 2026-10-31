package com.yuldash.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Утверждения на экране не превращают ручную сверку или будущую оплату в гарантию. */
class HonestPromisesGuardTest {
    private fun sourceDir(): File {
        var dir = File("").absoluteFile
        repeat(6) {
            for (path in listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app", "android/app/src/main/java/com/yuldash/app")) {
                val source = File(dir, path)
                if (source.isDirectory) return source
            }
            dir = dir.parentFile ?: error("Не найдены исходники приложения")
        }
        error("Не найдены исходники приложения")
    }

    private fun source(name: String): String = File(sourceDir(), name).readLines().filterNot {
        val line = it.trim()
        line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")
    }.joinToString("\n")

    @Test fun loginUsesServerListInsteadOfBuildFlagsOrSoonButtons() {
        val login = source("LoginScreen.kt")
        val gate = source("LoginMethodsGate.kt")
        val api = source("data/ApiClient.kt")
        assertTrue(gate.contains("ApiClient.loginMethods()"))
        assertTrue(login.contains("rememberLoginMethodsState("))
        assertTrue(api.contains("\"/config/login-methods\""))
        assertTrue(login.contains("Номер скрыт, пока поездка не подтверждена"))
        for (text in listOf("SMS_LOGIN_ENABLED", "VK_APP_ID", "Telegram скоро", "Не звоним и не шлём SMS", "Шифруем и не передаём третьим")) {
            assertFalse("Обещание/флаг во входе: $text", login.contains(text) || gate.contains(text))
        }
    }

    @Test fun rideshareNeverMountsOnlinePaymentCard() {
        for (file in listOf("BookingActiveTripScreen.kt", "RideshareCompletedScreen.kt")) {
            assertFalse("Онлайн-оплата попутки в $file", source(file).contains("PayOnlineCard("))
        }
        assertFalse(source("PayOnlineCard.kt").contains("Онлайн-оплата скоро"))
        assertFalse(source("SecondaryScreens.kt").contains("Скоро: оплата картой"))
    }

    @Test fun boardingCodeOnlyAsksToCompareWithoutGuarantees() {
        val card = source("BookingActiveTripScreen.kt").substringAfter("internal fun BoardingCodeCard(")
            .substringBefore("@Composable")
        assertTrue(card.contains("Спроси у пассажира код и сверь с этими цифрами."))
        assertTrue(card.contains("Назови код водителю и сверь номер машины."))
        val sources = listOf(card, source("RideshareBoarding.kt"), source("BoardingCodeRules.kt"))
        for (text in listOf("Сошлись — сажай", "Это та самая машина", "защищ", "без кода не", "Тап шул машина", "Тап килде — ултырт")) {
            assertTrue("Гарантия по коду посадки: $text", sources.none { it.contains(text, ignoreCase = true) })
        }
    }
}
