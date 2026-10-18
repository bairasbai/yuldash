package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SegmentRequestApiTest {
    private lateinit var server: MockWebServer
    @Before fun setup() { server = MockWebServer().apply { start() }; ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/') }
    @After fun cleanup() { ApiClient.testBaseUrl = null; server.shutdown() }

    @Test fun bothPublishedNamesAreSentForDirectAndIntermediateSegments() = runBlocking<Unit> {
        for ((pickup, dropoff) in listOf("Сибай" to "Акъяр", "Ургаза" to "Бурибай")) {
            server.enqueue(MockResponse().setBody("""{"id":42,"status":"pending"}"""))
            ApiClient.bookWithStatus(7, 1, pickupStop = pickup, dropoffStop = dropoff).getOrThrow()
            val request = server.takeRequest(); assertEquals("/bookings", request.path)
            val body = JSONObject(request.body.readUtf8())
            assertEquals(pickup, body.getString("pickup_stop")); assertEquals(dropoff, body.getString("dropoff_stop"))
        }
    }

    @Test fun routeFeedUsesServerFilterAndKeepsRideAndSegment() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":5,"matched_ride_id":7,"matched_ride_from_city":"Сибай","matched_ride_to_city":"Акъяр","matched_ride_depart_at":"2030-01-02T10:00:00Z","matched_pickup_stop":"Ургаза","matched_dropoff_stop":"Бурибай","detour_km":42}]}"""))
        val match = ApiClient.getRequestsFeed(onRoute = true).getOrThrow().single()
        assertEquals("/requests/feed?on_route=true", server.takeRequest().path)
        assertEquals(7, match.matchedRideId); assertEquals("Ургаза", match.matchedPickupStop); assertEquals("Бурибай", match.matchedDropoffStop)
        assertEquals(42, match.detourKm)
    }

    @Test fun explicitlyBoundOfferSendsRideIdButIndependentOfferDoesNot() = runBlocking<Unit> {
        for (id in listOf(7, null)) {
            server.enqueue(MockResponse().setBody("{}"))
            ApiClient.respondToRequest(5, 500, "", rideId = id).getOrThrow()
            val request = server.takeRequest(); assertEquals("/requests/5/respond", request.path)
            val body = JSONObject(request.body.readUtf8())
            if (id == null) assertFalse(body.has("ride_id")) else assertEquals(id, body.getInt("ride_id"))
        }
    }

    @Test fun boundResponseParsesCanonicalEndpointsAndConflictCannotBecomeSuccess() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":9,"ride_id":7,"ride_from_city":"Сибай","ride_to_city":"Акъяр","ride_depart_at":"2030-01-02T10:00:00Z","pickup_stop":"","dropoff_stop":"","status":"offered"}]}"""))
        val r = ApiClient.getRequestResponses(5).getOrThrow().single(); server.takeRequest()
        assertEquals(7, r.rideId); assertEquals("", r.pickupStop); assertEquals("", r.dropoffStop)
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"Изменился рейс"}"""))
        val result = ApiClient.acceptResponse(9); assertTrue(result.isFailure)
        assertEquals(409, (result.exceptionOrNull() as ApiException).status)
        assertEquals("/responses/9/accept", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(ApiClient.acceptResponse(9).isFailure); server.takeRequest()
    }
}
