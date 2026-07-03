package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit-тесты доменных data-классов (Domain.kt) и статистики рекламы (AdStats из Mocks.kt).
 * Чистая логика Kotlin без Android-фреймворка/Compose.
 * Проверяем: значения по умолчанию опциональных полей, поведение copy(), equals/hashCode (data class),
 * и формулу CTR (целочисленное деление, усечение — НЕ округление).
 * Запуск: gradlew :app:testDebugUnitTest
 *
 * Числа для AdStats намеренно отличаются от CoreLogicTest (там 0/5, 200/50, 10/10),
 * добавлены дробные случаи с усечением (1/3→33, 7/8→87, 3/7→42).
 */
class DomainModelsTest {

    // --- Ride: значения по умолчанию опциональных полей ---
    @Test
    fun ride_optionalFields_haveExpectedDefaults() {
        val ride = Ride(
            id = "1",
            from = "Сибай",
            to = "Уфа",
            time = "10:00",
            driver = "Азат",
            car = "Kia Rio",
            price = 500,
            seats = 3,
            rating = 4.8,
            verified = true,
            boosted = false,
        )
        // Строковые/nullable дефолты
        assertNull(ride.timeBa)
        assertEquals("", ride.driverAvatar)
        assertNull(ride.carBa)
        assertEquals("", ride.pickup)
        assertNull(ride.pickupLat)
        assertNull(ride.pickupLng)
        assertEquals("", ride.receiverName)
        assertEquals("", ride.parcelSize)
        // Булевы флаги-удобства по умолчанию false
        assertFalse(ride.driverOnline)
        assertFalse(ride.petsAllowed)
        assertFalse(ride.childSeat)
        assertFalse(ride.womenOnly)
        assertFalse(ride.smoking)
        assertFalse(ride.baggage)
        assertFalse(ride.airConditioner)
    }

    // --- Ride: copy() меняет одно поле, остальные сохраняются ---
    @Test
    fun ride_copy_changesOnlyTargetField() {
        val base = Ride(
            id = "1",
            from = "Сибай",
            to = "Уфа",
            time = "10:00",
            driver = "Азат",
            car = "Kia Rio",
            price = 500,
            seats = 3,
            rating = 4.8,
            verified = true,
            boosted = false,
        )
        val boosted = base.copy(boosted = true)

        assertTrue(boosted.boosted)
        // Всё остальное без изменений
        assertEquals(base.id, boosted.id)
        assertEquals(base.from, boosted.from)
        assertEquals(base.to, boosted.to)
        assertEquals(base.price, boosted.price)
        assertEquals(base.seats, boosted.seats)
        assertEquals(base.rating, boosted.rating, 0.0)
        assertEquals(base.verified, boosted.verified)
    }

    // --- Ride: equals/hashCode data class ---
    @Test
    fun ride_equality_matchesWhenAllFieldsEqualAndDiffersOnOneField() {
        val a = Ride(
            id = "1",
            from = "Сибай",
            to = "Уфа",
            time = "10:00",
            driver = "Азат",
            car = "Kia Rio",
            price = 500,
            seats = 3,
            rating = 4.8,
            verified = true,
            boosted = false,
        )
        val sameAsA = a.copy()
        val differsByPrice = a.copy(price = 600)

        assertEquals(a, sameAsA)
        assertEquals(a.hashCode(), sameAsA.hashCode())
        assertNotEquals(a, differsByPrice)
    }

    // --- PopularRoute: nullable дефолты + copy ---
    @Test
    fun popularRoute_defaultsAndCopyPreserveOtherFields() {
        val route = PopularRoute(
            from = "Темясово",
            to = "Уфа",
            minutes = "4 ч",
            distance = "250 км",
            nearbyCount = 3,
            label = "Хит",
        )
        // Двуязычные подписи по умолчанию не заданы
        assertNull(route.minutesBa)
        assertNull(route.labelBa)

        val renamed = route.copy(label = "Топ")
        assertEquals("Топ", renamed.label)
        assertEquals(route.from, renamed.from)
        assertEquals(route.to, renamed.to)
        assertEquals(route.nearbyCount, renamed.nearbyCount)
        assertNotEquals(route, renamed)
    }

    // --- TrustedContact: id и relationBa дефолты ---
    @Test
    fun trustedContact_defaultsForIdAndRelationBa() {
        val contact = TrustedContact(
            name = "Марат",
            relation = "Брат",
            phone = "+79990000000",
            notifyByDefault = true,
        )
        assertEquals(0, contact.id)
        assertNull(contact.relationBa)
        assertTrue(contact.notifyByDefault)

        // copy проставляет серверный id, не трогая остальное
        val withId = contact.copy(id = 42)
        assertEquals(42, withId.id)
        assertEquals(contact.name, withId.name)
        assertEquals(contact.phone, withId.phone)
        assertNotEquals(contact, withId)
    }

    // --- FrequentTrip: все поля обязательны (нет дефолтов) — проверяем equals ---
    @Test
    fun frequentTrip_equality_dependsOnEveryField() {
        val trip = FrequentTrip(
            title = "На работу",
            titleBa = "Эшкә",
            from = "Сибай",
            to = "Баймак",
            timeHint = "Утро",
            timeHintBa = "Иртә",
            categoryKey = "regular",
        )
        val same = trip.copy()
        val differsByCategory = trip.copy(categoryKey = "urgent")

        assertEquals(trip, same)
        assertNotEquals(trip, differsByCategory)
        assertEquals("urgent", differsByCategory.categoryKey)
        // остальные поля сохранились при copy
        assertEquals(trip.title, differsByCategory.title)
        assertEquals(trip.titleBa, differsByCategory.titleBa)
    }

    // --- LocalRequest: дефолты price/trustedContact/voiceUrl/serverId ---
    @Test
    fun localRequest_optionalFields_haveExpectedDefaults() {
        val request = LocalRequest(
            title = "Нужна поездка",
            route = "Сибай → Уфа",
            time = "Сегодня",
            passenger = "Гульнара",
            status = "open",
        )
        assertEquals(0, request.price)
        assertNull(request.trustedContact)
        assertNull(request.voiceUrl)
        assertEquals(0, request.serverId)
    }

    // --- LocalRequest: copy проставляет serverId, не меняя прочее ---
    @Test
    fun localRequest_copy_setsServerIdWithoutTouchingRest() {
        val request = LocalRequest(
            title = "Нужна поездка",
            route = "Сибай → Уфа",
            time = "Сегодня",
            passenger = "Гульнара",
            status = "open",
            price = 300,
        )
        val withServerId = request.copy(serverId = 77)

        assertEquals(77, withServerId.serverId)
        assertEquals(300, withServerId.price)
        assertEquals(request.title, withServerId.title)
        assertEquals(request.status, withServerId.status)
        assertNotEquals(request, withServerId)
    }

    // --- LocalVoiceMessage: audioPath/durationSec дефолты ---
    @Test
    fun localVoiceMessage_defaultsForAudioPathAndDuration() {
        val voice = LocalVoiceMessage(
            author = "Азат",
            transcript = "Еду в Уфу в 8 утра",
            time = "9:30",
        )
        assertNull(voice.audioPath)
        assertEquals(0, voice.durationSec)

        val withAudio = voice.copy(audioPath = "/media/v.m4a", durationSec = 12)
        assertEquals("/media/v.m4a", withAudio.audioPath)
        assertEquals(12, withAudio.durationSec)
        assertEquals(voice.author, withAudio.author)
        assertNotEquals(voice, withAudio)
    }

    // --- AdStats: дефолты 0/0 → CTR 0 ---
    @Test
    fun adStats_defaults_areZeroImpressionsClicksAndCtr() {
        val stats = AdStats()
        assertEquals(0, stats.impressions)
        assertEquals(0, stats.clicks)
        assertEquals(0, stats.ctrPercent)
    }

    // --- AdStats: CTR — целочисленное деление с усечением (не округление) ---
    @Test
    fun adStats_ctrPercent_usesIntegerTruncationNotRounding() {
        // 1/3 = 33.33% → усекается до 33 (округление дало бы 33 тоже, но проверяем усечение явно ниже)
        assertEquals(33, AdStats(impressions = 3, clicks = 1).ctrPercent)
        // 7/8 = 87.5% → усечение даёт 87 (округление дало бы 88 — важно, что НЕ округляем)
        assertEquals(87, AdStats(impressions = 8, clicks = 7).ctrPercent)
        // 3/7 = 42.85% → усечение 42 (округление дало бы 43)
        assertEquals(42, AdStats(impressions = 7, clicks = 3).ctrPercent)
    }

    // --- AdStats: без показов CTR = 0 даже при кликах (защита от деления на ноль) ---
    @Test
    fun adStats_ctrPercent_isZeroWhenNoImpressionsEvenWithClicks() {
        assertEquals(0, AdStats(impressions = 0, clicks = 3).ctrPercent)
    }

    // --- AdStats: equals data class ---
    @Test
    fun adStats_equality_matchesSameCountersAndDiffersOnClicks() {
        val a = AdStats(impressions = 100, clicks = 12)
        val same = AdStats(impressions = 100, clicks = 12)
        val differs = AdStats(impressions = 100, clicks = 13)

        assertEquals(a, same)
        assertEquals(a.hashCode(), same.hashCode())
        assertNotEquals(a, differs)
    }
}
