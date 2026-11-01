package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Only the documented health values authorize or deny payment; malformed success remains unknown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PaymentsAvailabilityApiTest {
    private lateinit var server: MockWebServer

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        server = MockWebServer().apply { start() }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1_000
    }

    @After fun cleanup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun response(body: String, status: Int = 200): Result<Boolean> = runBlocking {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
        val result = ApiClient.paymentsOnlineEnabled(expectedGeneration = ApiClient.queueSessionGeneration())
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/health", request.path)
        assertNull("availability is public configuration", request.getHeader("Authorization"))
        result
    }

    @Test fun onlyDocumentedEnabledProvidersAllowPayment() {
        for (provider in listOf("yookassa", "sbp_manual")) {
            assertTrue(response("""{"payments":"$provider"}""").getOrThrow())
        }
    }

    @Test fun explicitOffIsAConfirmedDenial() {
        assertFalse(response("""{"payments":"off"}""").getOrThrow())
    }

    @Test fun missingPaymentsIsUnknownInsteadOfPermanentDenial() {
        assertTrue(response("{}").isFailure)
    }

    @Test fun nullPaymentsIsUnknown() {
        assertTrue(response("""{"payments":null}""").isFailure)
    }

    @Test fun unknownProviderIsNotPermissionToPay() {
        for (value in listOf("future_provider", "on", "mock", "", " yookassa ")) {
            assertTrue("unrecognized payment flag: $value", response("""{"payments":"$value"}""").isFailure)
        }
    }

    @Test fun nonStringPaymentsIsUnknown() {
        for (value in listOf("1", "true", "[]", "{}")) {
            assertTrue("wrong payment flag type: $value", response("""{"payments":$value}""").isFailure)
        }
    }

    @Test fun malformedJsonDoesNotEnablePayment() {
        assertTrue(response("not json").isFailure)
    }

    @Test fun unavailableHealthEndpointIsUnknown() {
        assertTrue(response("{}", 503).isFailure)
    }
}
