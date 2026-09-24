package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.TripPassStore
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripSessionChainTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(false)
    private val held = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val returned = CountDownLatch(1)
    private val requests = CopyOnWriteArrayList<Pair<String?, String?>>()
    private var holdPath = ""
    private lateinit var server: MockWebServer

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.saveToken("local-account-A")
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request.path to request.getHeader("Authorization"))
                    if (request.path == holdPath) {
                        held.countDown()
                        if (!release.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                        returned.countDown()
                    }
                    val body = when (request.path) {
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/bookings/42/role" -> """{"role":"passenger","status":"confirmed","driver_phase":"waiting"}"""
                        "/bookings/42/boarding-code" -> """{"code":"5678"}"""
                        "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"confirmed"}"""
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 45000
    }

    @After fun cleanup() {
        release.countDown()
        compose.runOnIdle { mounted.value = false }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
    }

    @Test fun accountSwitchDuringMessagesMustNotRequestCodeOrDetailsWithNewToken() =
        checkChain("/bookings/42/messages")

    @Test fun accountSwitchDuringBoardingCodeMustNotRequestDetailsWithNewToken() =
        checkChain("/bookings/42/boarding-code")

    private fun checkChain(path: String) {
        holdPath = path
        mounted.value = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                    onBack = {}, onTripEnd = {}, onSos = {})
            }
        }
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            held.count == 0L
        }
        assertTrue(requests.contains(path to "Bearer local-account-A"))
        compose.runOnIdle {
            ApiClient.logout()
            ApiClient.saveToken("local-account-B")
        }
        release.countDown()
        assertTrue(returned.await(5, TimeUnit.SECONDS))
        // Bounded observation of the actual UI/IO chain; not a claim about all future callbacks.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        compose.waitForIdle()
        val successorRequests = requests.filter {
            it.second == "Bearer local-account-B" &&
                it.first in setOf("/bookings/42/boarding-code", "/bookings/42/details")
        }
        assertTrue("Old screen continued with B token: $successorRequests", successorRequests.isEmpty())
    }
}
