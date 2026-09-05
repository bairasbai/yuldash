package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainContractTest {

    @Test
    fun rideDefaults_keepPrivateAndPreferenceFieldsOffUnlessProvided() {
        val ride = Ride(
            id = "id",
            from = "A",
            to = "B",
            time = "today",
            driver = "Driver",
            car = "Car",
            price = 100,
            seats = 1,
            rating = 5.0,
            verified = false,
            boosted = false,
        )

        assertFalse(ride.driverOnline)
        assertFalse(ride.petsAllowed)
        assertFalse(ride.childSeat)
        assertFalse(ride.womenOnly)
        assertFalse(ride.smoking)
        assertFalse(ride.baggage)
        assertFalse(ride.airConditioner)
        assertEquals("", ride.pickup)
        assertNull(ride.pickupLat)
        assertNull(ride.pickupLng)
        assertEquals("", ride.receiverName)
        assertEquals("", ride.parcelSize)
    }

    @Test
    fun rideCopy_preservesDataClassValueSemantics() {
        val original = demoRides.first()
        val boosted = original.copy(boosted = !original.boosted, price = original.price + 100)

        assertNotEquals(original, boosted)
        assertEquals(original.id, boosted.id)
        assertEquals(original.price + 100, boosted.price)
    }

    @Test
    fun popularRouteDefaults_useRussianFallbacksWhenBashkirMissing() {
        val route = PopularRoute("A", "B", "10 min", distance = "5 km", nearbyCount = 2, label = "Popular")

        assertNull(route.minutesBa)
        assertNull(route.labelBa)
        assertEquals(2, route.nearbyCount)
    }

    @Test
    fun trustedContactDefaultId_isLocalUntilServerAssignsOne() {
        val contact = TrustedContact("Name", "Friend", "+7", notifyByDefault = true)

        assertEquals(0, contact.id)
        assertTrue(contact.notifyByDefault)
        assertNull(contact.relationBa)
    }

    @Test
    fun frequentTrip_hasBilingualTitleAndCategoryKey() {
        val trip = demoFrequentTrips.first()

        assertTrue(trip.title.isNotBlank())
        assertTrue(trip.titleBa.isNotBlank())
        assertTrue(trip.categoryKey.isNotBlank())
    }

    @Test
    fun localRequestServerId_zeroMeansLocalOnly() {
        val local = LocalRequest("Title", "A -> B", "today", "Passenger", "active")
        val server = local.copy(serverId = 77, voiceUrl = "voice.m4a", trustedContact = "Mom")

        assertEquals(0, local.serverId)
        assertEquals(77, server.serverId)
        assertEquals("voice.m4a", server.voiceUrl)
        assertEquals("Mom", server.trustedContact)
    }

    @Test
    fun localVoiceMessageDefaults_toTranscriptOnly() {
        val voice = LocalVoiceMessage(author = "User", transcript = "Need ride", time = "10:00")

        assertNull(voice.audioPath)
        assertEquals(0, voice.durationSec)
    }

    @Test
    fun demoRides_areStableEnoughForOfflineFallback() {
        assertTrue(demoRides.size >= 3)
        assertTrue(demoRides.all { it.from.isNotBlank() && it.to.isNotBlank() })
        assertTrue(demoRides.all { it.price > 0 })
        assertTrue(demoRides.any { it.verified })
    }

    @Test
    fun demoPopularRoutes_haveRouteAndDistanceForMapFeedFallback() {
        assertTrue(demoPopularRoutes.size >= 3)
        assertTrue(demoPopularRoutes.all { it.from.isNotBlank() && it.to.isNotBlank() })
        assertTrue(demoPopularRoutes.all { it.distance.endsWith("км") || it.distance.endsWith("РєРј") })
    }

    @Test
    fun partnerAdRouteMatching_rejectsWrongCompleteRouteButAllowsPartialTargeting() {
        val active = demoPartnerAds.first { it.routeFrom != null && it.routeTo != null }

        assertTrue(active.matchesRoute(active.routeFrom!!, active.routeTo!!))
        assertFalse(active.matchesRoute(active.routeFrom!!, "Wrong"))
        assertTrue(active.copy(routeFrom = null).matchesRoute("Any", "Wrong"))
        assertTrue(active.copy(routeTo = null).matchesRoute("Wrong", "Any"))
    }

    @Test
    fun partnerAdFilters_doNotLeakNonActiveAds() {
        assertTrue(demoPartnerAds.any { it.status != AdStatus.Active })
        assertTrue(demoPartnerAds.forPlacement(AdPlacement.Route).all { it.status == AdStatus.Active })
        assertTrue(demoPartnerAds.forRoute("Баймаҡ", "Сибай").all { it.status == AdStatus.Active })
        assertTrue(demoPartnerAds.forCity("Сибай").all { it.status == AdStatus.Active })
    }

    @Test
    fun adStatsIntegerCtr_doesNotRoundUpOrCrash() {
        assertEquals(0, AdStats(impressions = 0, clicks = 10).ctrPercent)
        assertEquals(33, AdStats(impressions = 3, clicks = 1).ctrPercent)
        assertEquals(150, AdStats(impressions = 2, clicks = 3).ctrPercent)
    }
}
