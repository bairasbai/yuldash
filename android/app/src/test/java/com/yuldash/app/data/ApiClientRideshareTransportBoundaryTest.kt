package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Actual HTTP requests, including a disconnect after the server has received the whole intent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientRideshareTransportBoundaryTest {
    private lateinit var server: MockWebServer
    private val received = CopyOnWriteArrayList<RecordedRequest>()
    private var replyCode = 0

    @Before fun prepare() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    received += request
                    return when (replyCode) {
                        0 -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        307 -> MockResponse().setResponseCode(307).setHeader("Location", url("/redirect-target"))
                        else -> MockResponse().setResponseCode(replyCode).setHeader("Retry-After", "0")
                            .setBody("""{"detail":"Synthetic refusal"}""")
                    }
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        ApiClient.testBaseUrl = "http://127.0.0.1:${server.port}"
        ApiClient.testTimeoutMs = 1500
    }

    @After fun cleanup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun sentOnce(action: suspend () -> Result<*>) = runBlocking {
        val result = action()
        assertEquals("A received mutation was silently replayed after its reply was lost/refused", 1, received.size)
        assertTrue("An unknown/refused result must remain a failure", result.isFailure)
        assertTrue(received.single().bodySize > 0 || received.single().method in setOf("POST", "DELETE"))
        if (replyCode != 0) assertEquals(replyCode, (result.exceptionOrNull() as? ApiException)?.status)
    }

    @Test fun requestEofDoesNotReplay() = sentOnce {
        ApiClient.createRequest("Баймак", "Сибай", 1, "regular", false, "", 300)
    }
    @Test fun publishEofDoesNotReplay() = sentOnce {
        ApiClient.publishRide("Баймак", "Сибай", "2026-10-22T12:00:00+05:00", 2, 300, "")
    }
    @Test fun bargainEofDoesNotReplay() = sentOnce { ApiClient.counterOffer(42, 350, "Синтетический тест") }
    @Test fun sosEofDoesNotReplay() = sentOnce { ApiClient.sos("other", "Synthetic test", bookingId = 42) }
    @Test fun boardingEofDoesNotReplay() = sentOnce { BoardingApi.markBoarded(42) }
    @Test fun undoBoardingEofDoesNotReplay() = sentOnce { BoardingApi.undoBoarded(42) }
    @Test fun shareEofDoesNotReplay() = sentOnce { ApiClient.shareTrip(42, 7) }
    @Test fun revokeShareEofDoesNotReplay() = sentOnce { ApiClient.revokeBookingShare(42, 7) }
    @Test fun completeRideEofDoesNotReplay() = sentOnce { ApiClient.completeRide(42) }
    @Test fun driverDoneEofDoesNotReplay() = sentOnce { ApiClient.driverStatus(42, "done") }
    @Test fun request503DoesNotReplay() {
        replyCode = 503
        sentOnce { ApiClient.createRequest("Баймак", "Сибай", 1, "regular", false, "", 300) }
    }
    @Test fun request408DoesNotReplay() {
        replyCode = 408
        sentOnce { ApiClient.createRequest("Баймак", "Сибай", 1, "regular", false, "", 300) }
    }
    @Test fun requestRedirectDoesNotForwardMutation() {
        replyCode = 307
        sentOnce { ApiClient.createRequest("Баймак", "Сибай", 1, "regular", false, "", 300) }
    }
}
