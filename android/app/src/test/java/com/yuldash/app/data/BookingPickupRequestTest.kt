package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
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
class BookingPickupRequestTest {
    private lateinit var server: MockWebServer
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }
    @After fun cleanup() { ApiClient.testBaseUrl = null; server.shutdown() }

    @Test fun explicitStartAndWaypointAreSentAsNames() = runBlocking {
        startBookingCommandSession(server)
        for ((index, stop) in listOf("Баймак", "Акъяр").withIndex()) {
            val rideId = 7 + index
            server.enqueue(bookingCommandResponse(rideId))
            ApiClient.bookWithStatus(rideId, 1, pickupStop = stop).getOrThrow()
            if (index == 0) assertEquals("/me", server.takeRequest().path)
            val request = server.takeRequest()
            assertEquals("/bookings/commands/v1", request.path)
            assertEquals(stop, JSONObject(request.body.readUtf8()).getString("pickup_stop"))
        }
    }

    @Test fun absentChoiceIsNotSilentlyConvertedToStart() = runBlocking {
        startBookingCommandSession(server)
        server.enqueue(bookingCommandResponse(7))
        ApiClient.bookWithStatus(7, 1).getOrThrow()
        assertEquals("/me", server.takeRequest().path)
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("pickup_stop"))
    }
}
