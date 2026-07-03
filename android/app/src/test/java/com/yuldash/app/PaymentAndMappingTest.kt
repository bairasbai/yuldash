package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Доп. JVM unit-тесты на чистую логику, не покрытую CoreLogicTest:
 * - `sberPayLink` — денежный путь (СБП-перевод по номеру): нормализация телефона в цифры.
 * - `rideTypeMeta` — маппинг типа поездки в двуязычные подписи (RU/BA).
 */
class PaymentAndMappingTest {

    @Test
    fun sberPayLink_stripsEverythingButDigits() {
        assertEquals(
            "https://www.sberbank.com/sms/pbpn?requisiteNumber=79991234567",
            sberPayLink("+7 (999) 123-45-67"),
        )
    }

    @Test
    fun sberPayLink_keepsPlainDigitsUntouched() {
        assertEquals(
            "https://www.sberbank.com/sms/pbpn?requisiteNumber=79991234567",
            sberPayLink("79991234567"),
        )
    }

    @Test
    fun sberPayLink_emptyWhenNoDigits() {
        assertEquals(
            "https://www.sberbank.com/sms/pbpn?requisiteNumber=",
            sberPayLink("телефон недоступен"),
        )
    }

    @Test
    fun rideTypeMeta_mapsKnownKeysBilingually() {
        val parcel = rideTypeMeta("parcel")
        assertEquals("Посылка", parcel.second)
        assertEquals("Посылка", parcel.third)

        val cargo = rideTypeMeta("cargo")
        assertEquals("Груз", cargo.second)
        assertEquals("Йөк", cargo.third)

        val urgent = rideTypeMeta("urgent")
        assertEquals("Срочно", urgent.second)
        assertEquals("Ашығыс", urgent.third)
    }

    @Test
    fun rideTypeMeta_unknownKeyFallsBackToPassengers() {
        val fallback = rideTypeMeta("что-то-неизвестное")
        assertEquals("Пассажиры", fallback.second)
        assertEquals("Пассажирҙар", fallback.third)
    }
}
