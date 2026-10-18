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
class BookingPickupRequestTest {
    private lateinit var server: MockWebServer
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }
    @After fun cleanup() { ApiClient.testBaseUrl = null; server.shutdown() }

    @Test fun explicitStartAndWaypointAreSentAsNames() = runBlocking {
        for (stop in listOf("Баймак", "Акъяр")) {
            server.enqueue(MockResponse().setBody("""{"id":42,"status":"pending"}"""))
            ApiClient.bookWithStatus(7, 1, pickupStop = stop).getOrThrow()
            val request = server.takeRequest()
            assertEquals("/bookings", request.path)
            assertEquals(stop, JSONObject(request.body.readUtf8()).getString("pickup_stop"))
        }
    }

    @Test fun absentChoiceIsNotSilentlyConvertedToStart() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":42,"status":"pending"}"""))
        ApiClient.bookWithStatus(7, 1).getOrThrow()
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("pickup_stop"))
    }
}
