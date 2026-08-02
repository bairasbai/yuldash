package com.yuldash.app

import com.yuldash.app.data.FeedDto
import com.yuldash.app.data.RideDto
import com.yuldash.app.data.normalizeOptionalJsonString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun formatDepart_formatsBackendIsoForRideCards() {
        assertEquals("23.06, 05:57", formatDepart("2026-06-23T05:57:00"))
        assertEquals("03.07, 12:44", formatDepart("2026-07-03T12:44:19.838345"))
        assertEquals("bad-date", formatDepart("bad-date"))
    }

    @Test
    fun bookingStatusAllowsActiveTrip_blocksPendingUntilDriverConfirms() {
        assertFalse(bookingStatusAllowsActiveTrip("pending"))
        assertFalse(bookingStatusAllowsActiveTrip("cancelled"))
        assertFalse(bookingStatusAllowsActiveTrip(""))
        assertTrue(bookingStatusAllowsActiveTrip("confirmed"))
        assertTrue(bookingStatusAllowsActiveTrip("onboard"))
        assertTrue(bookingStatusAllowsActiveTrip("done"))
    }

    @Test
    fun bookingStatusAllowsBoarding_onlyForLiveTrip() {
        assertFalse(bookingStatusAllowsBoarding("pending"))
        assertFalse(bookingStatusAllowsBoarding("done"))
        assertTrue(bookingStatusAllowsBoarding("confirmed"))
        assertTrue(bookingStatusAllowsBoarding("onboard"))
    }

    @Test
    fun fmtKm_usesOneDecimalBelowTenAndRoundsAboveTen() {
        assertEquals("8.4", fmtKm(8.44))
        assertEquals("10", fmtKm(9.95))
        assertEquals("17", fmtKm(16.6))
    }

    @Test
    fun apiCategoryToUiFor_mapsBackendCategoriesBilingually() {
        assertEquals("Срочно", apiCategoryToUiFor(AppLanguage.Ru, "urgent", withKids = false))
        assertEquals("Ашығыс", apiCategoryToUiFor(AppLanguage.Ba, "urgent", withKids = false))
        assertEquals("С детьми", apiCategoryToUiFor(AppLanguage.Ru, "regular", withKids = true))
        assertEquals("Балалар менән", apiCategoryToUiFor(AppLanguage.Ba, "regular", withKids = true))
        assertEquals("Обычная", apiCategoryToUiFor(AppLanguage.Ru, "regular", withKids = false))
    }

    @Test
    fun mapFeedFrom_usesServerCountersAndFormatsDonations() {
        val route = PopularRoute(
            from = "Темясово",
            to = "Уфа",
            minutes = "4 ч",
            distance = "250 км",
            nearbyCount = 3,
            label = "Хит",
        )
        val cards = mapFeedFrom(
            listOf(route),
            FeedDto(
                today = 21,
                week = 140,
                month = 1234,
                year = 15678,
                drivers = 42,
                topFrom = "Темясово",
                topTo = "Уфа",
                topCount = 9,
                donationsTotal = 12500,
            )
        )

        assertEquals(7, cards.size)
        assertEquals(FeedKind.Route, cards[0].kind)
        assertEquals(route, cards[0].route)
        assertTrue(cards.any { it.kind == FeedKind.Live && it.title.startsWith("21 ") })
        assertTrue(cards.any { it.kind == FeedKind.Top && it.title == "Темясово → Уфа" && it.pill == "9 раз" })
        assertTrue(cards.any { it.kind == FeedKind.Donate && it.title.startsWith("12 500 ₽") })
    }

    @Test
    fun partnerAds_filtersOnlyActiveMatchingAds() {
        val routeAds = demoPartnerAds.forRoute("Баймаҡ", "Сибай")
        assertTrue(routeAds.isNotEmpty())
        assertTrue(routeAds.all { it.status == AdStatus.Active })
        assertTrue(routeAds.all { it.matchesRoute("Баймаҡ", "Сибай") })

        assertTrue(demoPartnerAds.forPlacement(AdPlacement.Profile).all { it.status == AdStatus.Active })
        assertTrue(demoPartnerAds.forCity("Баймаҡ").all { it.status == AdStatus.Active && it.city == "Баймаҡ" })
        assertFalse(demoPartnerAds.forCategory("категория-которой-нет").any())
    }

    @Test
    fun partnerAd_matchesRoute_allowsUntargetedAdsButRejectsWrongRoute() {
        val active = demoPartnerAds.first { it.status == AdStatus.Active }
        val untargeted = active.copy(routeFrom = null, routeTo = null)
        val targeted = active.copy(routeFrom = "A", routeTo = "B")

        assertTrue(untargeted.matchesRoute("Any", "Route"))
        assertTrue(targeted.matchesRoute("A", "B"))
        assertFalse(targeted.matchesRoute("A", "C"))
    }

    @Test
    fun mapFeedFrom_usesDemoRoutesAndServerCountersWhenPopularIsEmpty() {
        val cards = mapFeedFrom(
            emptyList(),
            FeedDto(
                today = 1,
                week = 0,
                month = 2,
                year = 5,
                drivers = 21,
                topFrom = "",
                topTo = "",
                topCount = 0,
                donationsTotal = 999,
            )
        )

        assertEquals(7, cards.size)
        assertEquals(demoPopularRoutes[0], cards[0].route)
        assertTrue(cards[1].title.startsWith("1 "))
        assertTrue(cards[4].title.startsWith("2 "))
        assertTrue(cards[5].title.startsWith("5 "))
        assertTrue(cards.last().title.startsWith("999 "))
    }

    @Test
    fun rideDtoToUiRide_mapsBackendContractAndKeepsPrivateDetailsOnlyWhenProvided() {
        val ride = RideDto(
            id = 42,
            fromCity = "From",
            toCity = "To",
            departAt = "2030-01-02T03:04:05",
            seatsTotal = 4,
            seatsLeft = 2,
            price = 1200,
            category = "parcel",
            driverName = "",
            driverRating = 4.75,
            driverVerified = true,
            driverCar = "Kia Rio",
            driverAvatar = "avatar.png",
            driverOnline = true,
            petsAllowed = true,
            childSeat = true,
            womenOnly = true,
            smoking = true,
            baggage = true,
            airConditioner = true,
            pickup = "",
            pickupLat = null,
            pickupLng = null,
            receiverName = "Receiver",
            parcelSize = "small",
        ).toUiRide()

        assertEquals("42", ride.id)
        assertEquals("From", ride.from)
        assertEquals("To", ride.to)
        assertEquals("02.01, 03:04", ride.time)
        // Пустое имя водителя ЗДЕСЬ и остаётся пустым — это осознанное решение, а не потеря.
        // Раньше слой данных подставлял русское «Водитель», и башкирский интерфейс показывал
        // русское слово. Теперь подпись «Водитель»/«Йөрөтөүсе» ставит слой отрисовки через
        // appText — на нужном языке. Тест держал старое поведение и падал.
        assertEquals("", ride.driver)
        assertEquals("Kia Rio", ride.car)
        assertEquals(1200, ride.price)
        assertEquals(2, ride.seats)
        assertEquals(4.75, ride.rating, 0.0)
        assertTrue(ride.verified)
        assertTrue(ride.driverOnline)
        assertTrue(ride.petsAllowed)
        assertTrue(ride.childSeat)
        assertTrue(ride.womenOnly)
        assertTrue(ride.smoking)
        assertTrue(ride.baggage)
        assertTrue(ride.airConditioner)
        assertEquals("", ride.pickup)
        assertNull(ride.pickupLat)
        assertNull(ride.pickupLng)
        assertEquals("Receiver", ride.receiverName)
        assertEquals("small", ride.parcelSize)
    }

    @Test
    fun normalizeOptionalJsonString_treatsJsonNullVoiceUrlAsNoVoiceMessage() {
        assertNull(normalizeOptionalJsonString(hasValue = true, isJsonNull = true, raw = "null"))
        assertNull(normalizeOptionalJsonString(hasValue = false, isJsonNull = false, raw = ""))
        assertNull(normalizeOptionalJsonString(hasValue = true, isJsonNull = false, raw = ""))
        assertEquals(
            "https://yulbash.ru/media/voice.m4a",
            normalizeOptionalJsonString(
                hasValue = true,
                isJsonNull = false,
                raw = "https://yulbash.ru/media/voice.m4a",
            )
        )
    }
}
