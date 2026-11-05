package com.yuldash.app.data

import android.app.Application
import com.yuldash.app.currentTripCode
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Driver online display remains participant-authorized; every positive code comes from HTTP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class DriverBoardingCodeWiringTest : SnapshotWiringFixture() {
    private fun driverResponses() {
        handler = { request -> when {
            request.path?.endsWith("/details") == true -> MockResponse().setBody(detailsJson(role = "driver", status = "confirmed").apply { remove("onboard_at") }.toString())
            request.path?.endsWith("/role") == true -> MockResponse().setBody(roleJson(role = "driver", status = "confirmed").apply { remove("onboard_at") }.toString())
            else -> defaultResponse(request)
        } }
    }
    private suspend fun online(): BookingDetailsDto {
        driverResponses()
        val d = ApiClient.getBookingDetails(11).getOrThrow()
        assertTrue(OfflineTripSnapshots.privateDetails(d))
        assertEquals("4821", ApiClient.getBoardingCode(11).getOrThrow())
        assertEquals("4821", currentTripCode(11, "4821"))
        return d
    }
    @Test fun freshDriverDetailsAndCodeDisplayOnlineWithoutAnyPassengerPassOrDurableCode(): Unit = runBlocking {
        val plain = MemoryDiskPreferences(); val encrypted = MemoryDiskPreferences()
        TripPassStore.initStores(plain, encrypted)
        val d = online()
        assertNull(OfflineTripSnapshots.privatePass(11)); assertNull(TripPassStore.load(context, 11))
        assertFalse("Even a code-free current driver projection cannot be persisted as a passenger pass", TripPassStore.save(context, tripPassFromDetails(d)))
        assertFalse(TripPassStore.save(context, tripPassFromDetails(d, "4821")))
        assertFalse(TripPassStore.updateBoardingCode(context, 11, "4821"))
        assertFalse(plain.restarted().contains("pass_11")); assertFalse(encrypted.restarted().contains("pass_11"))
        assertFalse(meta.restarted().all.values.any { it.toString() == "4821" || it.toString().contains("\"4821\"") || it.toString().contains("\"boarding_code\"") })
        assertFalse(secure.restarted().all.values.any { it.toString() == "4821" || it.toString().contains("\"4821\"") || it.toString().contains("\"boarding_code\"") })
        assertEquals(1, requests.count { it.path == "/bookings/11/boarding-code" })
        assertEquals("", currentTripCode(12, "4821"))
    }
    @Test fun roleOnlyAndColdCiphertextCannotSupplyADriverCode(): Unit = runBlocking {
        driverResponses()
        val oldPlain = MemoryDiskPreferences(); val oldEncrypted = MemoryDiskPreferences()
        TripPassStore.initStores(oldPlain, oldEncrypted)
        assertTrue(oldEncrypted.edit().putString("pass_11", tripPassFromDetails(
            parseBookingDetailsDto(detailsJson(role = "driver", status = "confirmed")), "4821").toJson().toString()).commit())
        assertTrue(ApiClient.getTripState(11).isSuccess)
        assertEquals("", currentTripCode(11, "4821")); assertNull(TripPassStore.load(context, 11))
        online(); reopenAuthority()
        assertEquals("", currentTripCode(11, "4821")); assertNull(OfflineTripSnapshots.privatePass(11))
        assertEquals("4821", TripPassAuthorityFixture.durable(oldEncrypted, 11)?.boardingCode)
    }
    @Test fun everyFailedFreshDriverDetailsHidesTheOldCodeEvenWhenFallbackIsAllowed(): Unit = runBlocking {
        for (status in listOf("400", "403", "404", "410", "503", "io", "bad200")) {
            if (status != "400") ApiClient.saveToken(jwt(7, "driver-$status"))
            online()
            handler = { request -> if (request.path?.endsWith("/details") == true) when (status) {
                "io" -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                "bad200" -> MockResponse().setBody("{\"booking_id\":11,\"ride_id\":33,\"role\":\"unknown\"}")
                else -> MockResponse().setResponseCode(status.toInt()).setBody("{}")
            } else defaultResponse(request) }
            assertTrue(ApiClient.getBookingDetails(11).isFailure)
            assertEquals("DETAILS $status cannot leave an earlier driver code visible", "", currentTripCode(11, "4821"))
            assertNull(TripPassStore.load(context, 11))
        }
    }
    @Test fun aNewDetailsRevisionRequiresItsOwnCodeAndRejectsTheEarlierLocalText(): Unit = runBlocking {
        online()
        handler = { request -> if (request.path?.endsWith("/details") == true)
            MockResponse().setBody(detailsJson(role = "driver", status = "confirmed", marker = "revision-B").apply { remove("onboard_at") }.toString())
            else MockResponse().setBody("{\"code\":\"5932\"}") }
        ApiClient.getBookingDetails(11).getOrThrow()
        assertEquals("", currentTripCode(11, "4821"))
        assertEquals("5932", ApiClient.getBoardingCode(11).getOrThrow())
        assertEquals("", currentTripCode(11, "4821")); assertEquals("5932", currentTripCode(11, "5932"))
    }
    private suspend fun heldCode(change: suspend () -> Unit) = coroutineScope {
        driverResponses(); ApiClient.getBookingDetails(11).getOrThrow()
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        handler = { request -> if (request.path?.endsWith("/boarding-code") == true) {
            held.countDown(); check(release.await(10, TimeUnit.SECONDS)); MockResponse().setBody("{\"code\":\"4821\"}")
        } else MockResponse().setResponseCode(503).setBody("{}") }
        ApiClient.testTimeoutMs = 8000
        val old = async(Dispatchers.IO) { ApiClient.getBoardingCode(11) }
        try {
            assertTrue("Positive driver code GET must reach actual HTTP before the competing event", held.await(10, TimeUnit.SECONDS))
            change(); release.countDown(); assertTrue("The superseded code must not be delivered as current success", old.await().isFailure)
            assertEquals("", currentTripCode(11, "4821")); assertNull(TripPassStore.load(context, 11))
        } finally { release.countDown() }
    }
    @Test fun lateDriverCodeCannotCrossOwnerChange(): Unit = runBlocking { heldCode { ApiClient.saveToken(jwt(8)) } }
    @Test fun lateDriverCodeCannotCrossOriginChange(): Unit = runBlocking {
        val other = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503).setBody("{}")
            }
            start()
        }
        try {
            assertNotEquals("Both controlled servers must have distinct live ports", server.port, other.port)
            heldCode {
                val original = snapshotOrigin(ApiClient.apiBase())
                ApiClient.testBaseUrl = other.url("/").toString().trimEnd('/')
                assertNotEquals(original, snapshotOrigin(ApiClient.apiBase()))
            }
            assertEquals("An old code operation must not refresh DETAILS against the replacement origin", 0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }
    @Test fun lateDriverCodeCannotCrossDetailsRevision(): Unit = runBlocking {
        heldCode {
            handler = { request -> if (request.path?.endsWith("/details") == true)
                MockResponse().setBody(detailsJson(role = "driver", status = "confirmed", marker = "new-revision").apply { remove("onboard_at") }.toString())
                else MockResponse().setResponseCode(503).setBody("{}") }
            ApiClient.getBookingDetails(11).getOrThrow()
        }
    }
    @Test fun lateDriverCodeCannotCrossAcceptedTerminal(): Unit = runBlocking {
        heldCode {
            handler = { request -> if (request.path?.endsWith("/role") == true)
                MockResponse().setBody(roleJson(role = "driver", status = "done").toString()) else defaultResponse(request) }
            ApiClient.getTripState(11).getOrThrow()
        }
    }
    @Test fun codeGetCannotExtendTheOriginalDriverDetailsLifetimeOrReviveItAfterRollback(): Unit = runBlocking {
        driverResponses(); ApiClient.getBookingDetails(11).getOrThrow()
        clock = SnapshotClock(3, OfflineTripSnapshotPolicy.MAX_AGE_MS, OfflineTripSnapshotPolicy.MAX_AGE_MS, OfflineTripSnapshotPolicy.MAX_AGE_MS)
        assertEquals("4821", ApiClient.getBoardingCode(11).getOrThrow())
        assertEquals("4821", currentTripCode(11, "4821"))
        clock = SnapshotClock(3, OfflineTripSnapshotPolicy.MAX_AGE_MS + 1001, OfflineTripSnapshotPolicy.MAX_AGE_MS + 1001, OfflineTripSnapshotPolicy.MAX_AGE_MS + 1001)
        assertEquals("", currentTripCode(11, "4821"))
        clock = SnapshotClock(3, 2000, 3000, 3000)
        assertEquals("", currentTripCode(11, "4821"))
        assertTrue(ApiClient.getBookingDetails(11).isSuccess)
        assertEquals("", currentTripCode(11, "4821"))
        assertEquals("4821", ApiClient.getBoardingCode(11).getOrThrow())
        assertEquals("4821", currentTripCode(11, "4821"))
    }
}
