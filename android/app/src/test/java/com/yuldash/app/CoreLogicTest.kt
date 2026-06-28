package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Каркас JVM unit-тестов «с нуля» (раньше у приложения было 0 тестов).
 * Покрывает чистую логику без Android-фреймворка: двуязычие (ядро продукта) и расчёт CTR рекламы.
 * Запуск: gradlew :app:testDebugUnitTest
 *
 * Тесты экранов (Compose UI) и инструментальные — отдельная задача (нужен androidTest + устройство/эмулятор).
 */
class CoreLogicTest {

    @Test
    fun appTextFor_picksRussianForRu() {
        assertEquals("Поехать", appTextFor(AppLanguage.Ru, "Поехать", "Китергә"))
    }

    @Test
    fun appTextFor_picksBashkirForBa() {
        assertEquals("Китергә", appTextFor(AppLanguage.Ba, "Поехать", "Китергә"))
    }

    @Test
    fun appTextFor_isTrulyBilingual_notOneSided() {
        // Если RU и BA однажды начнут совпадать на разных языках — это регресс двуязычия.
        assertNotEquals(
            appTextFor(AppLanguage.Ru, "Найти поездку", "Сәфәр табырға"),
            appTextFor(AppLanguage.Ba, "Найти поездку", "Сәфәр табырға"),
        )
    }

    @Test
    fun adStats_ctrPercent_isZeroWithoutImpressions() {
        assertEquals(0, AdStats(impressions = 0, clicks = 5).ctrPercent)
    }

    @Test
    fun adStats_ctrPercent_computesPercent() {
        assertEquals(25, AdStats(impressions = 200, clicks = 50).ctrPercent)
        assertEquals(100, AdStats(impressions = 10, clicks = 10).ctrPercent)
    }
}
