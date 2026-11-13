package com.yuldash.app.data

import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingGuardianContactTest {
    private lateinit var server: MockWebServer

    @Before fun setup() {
        ApiClient.resetForTest()
        server = MockWebServer().apply { start() }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 2000
        ApiClient.init(RuntimeEnvironment.getApplication())
        ApiClient.saveToken(COMMAND_TEST_TOKEN)
    }

    @After fun cleanup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun details() = JSONObject()
        .put("booking_id", 73).put("role", "driver").put("status", "pending")
        .put("minor_passenger", true).put("minor_guardian_name", "Тестовый взрослый")
        .put("minor_guardian_phone", "+12025550118").put("contact_unlocked", false)

    @Test fun authorizedContactIsReadWithoutPublishingTripSnapshot() = runBlocking {
        server.enqueue(MockResponse().setBody(details().toString()))
        val phone = ApiClient.getPendingGuardianPhone(73, ApiClient.queueSessionGeneration()).getOrThrow()
        assertEquals("+12025550118", phone)
        val request = request()
        assertEquals("/bookings/73/details", request.path)
        assertEquals("no-cache, no-store", request.getHeader("Cache-Control"))
        assertNull(OfflineTripSnapshots.projection(73))
    }

    @Test fun wrongBookingRoleOrStateCannotOpenContact() = runBlocking {
        val owner = ApiClient.queueSessionGeneration()
        val invalid = listOf(
            details().put("booking_id", 74), details().put("role", "passenger"),
            details().put("status", "cancelled"), details().put("status", "done"),
            details().put("status", "confirmed"), details().put("minor_passenger", false),
            details().put("minor_guardian_phone", JSONObject.NULL),
        )
        for (body in invalid) {
            server.enqueue(MockResponse().setBody(body.toString()))
            assertTrue(ApiClient.getPendingGuardianPhone(73, owner).isFailure)
        }
        assertNull(OfflineTripSnapshots.projection(73))
    }

    @Test fun oldAccountCannotRequestContact() = runBlocking {
        val current = ApiClient.queueSessionGeneration()
        assertTrue(ApiClient.getPendingGuardianPhone(73, current - 1).isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test fun completedContactUsesFreshServerWindowAndNeverPublishesSnapshot() = runBlocking {
        val owner = ApiClient.queueSessionGeneration()
        val recent = details().put("status", "done").put("contact_unlocked", true)
        server.enqueue(MockResponse().setBody(recent.toString()))
        server.enqueue(MockResponse().setBody(recent.put("contact_unlocked", false).toString()))
        assertEquals("+12025550118", ApiClient.getCompletedGuardianPhone(73, owner).getOrThrow())
        assertNull(ApiClient.getCompletedGuardianPhone(73, owner).getOrThrow())
        repeat(2) { assertEquals("no-cache, no-store", request().getHeader("Cache-Control")) }
        assertNull(OfflineTripSnapshots.projection(73))
    }

    @Test fun completedContactRejectsUntrustedDetails() = runBlocking {
        val owner = ApiClient.queueSessionGeneration()
        fun recent() = details().put("status", "done").put("contact_unlocked", true)
        val denied = listOf(
            recent().put("booking_id", "73"), recent().put("booking_id", 74),
            recent().put("role", "passenger"), recent().put("status", "pending"),
            recent().put("minor_passenger", false), recent().put("contact_unlocked", "true"),
            recent().put("minor_guardian_phone", "+12025550118;123"),
        )
        for (body in denied) {
            server.enqueue(MockResponse().setBody(body.toString()))
            assertNull(ApiClient.getCompletedGuardianPhone(73, owner).getOrThrow())
        }
        assertNull(OfflineTripSnapshots.projection(73))
    }

    @Test fun olderBookingsKeepFormattedPhoneNumbersCallable() = runBlocking {
        val owner = ApiClient.queueSessionGeneration()
        val legacy = details().put("minor_guardian_phone", "8 (999) 018-03-36")
        server.enqueue(MockResponse().setBody(legacy.toString()))
        val international = legacy.put("status", "done").put("contact_unlocked", true)
            .put("minor_guardian_phone", "+1 (202) 555-0118")
        server.enqueue(MockResponse().setBody(international.toString()))
        assertEquals("+79990180336", ApiClient.getPendingGuardianPhone(73, owner).getOrThrow())
        assertEquals("+12025550118", ApiClient.getCompletedGuardianPhone(73, owner).getOrThrow())
    }

    @Test fun accountChangedWhileReadingCannotReceiveContact() = runBlocking {
        val owner = ApiClient.queueSessionGeneration()
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (release.await(3, TimeUnit.SECONDS)) MockResponse().setBody(details().toString())
                else MockResponse().setResponseCode(500)
            }
        }
        val result = async { ApiClient.getPendingGuardianPhone(73, owner) }
        try {
            assertEquals("/bookings/73/details", request().path)
            ApiClient.saveToken("test.eyJzdWIiOiI4In0.signature")
        } finally {
            release.countDown()
        }
        assertEquals("Session changed", result.await().exceptionOrNull()?.message)
        assertNull(OfflineTripSnapshots.projection(73))
    }

    private suspend fun request() = withContext(Dispatchers.IO) {
        checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
    }
}
