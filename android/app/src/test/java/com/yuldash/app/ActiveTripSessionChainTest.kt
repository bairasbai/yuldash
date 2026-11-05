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
import com.yuldash.app.data.TripPassAuthorityFixture

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
    @Volatile private var preparingRole = false
    private val preparationRequests = CopyOnWriteArrayList<String>()
    private val tokenA = TripPassAuthorityFixture.jwt(7)
    private val tokenB = TripPassAuthorityFixture.jwt(8)
    private var holdPath = ""
    private lateinit var server: MockWebServer

    @Before fun setup() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    preparationRoleResponse(request)?.let { return it }
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
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(canonicalTripBody(request.path, body))
                }
            }
            start()
        }
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 45000
        ApiClient.init(context)
        ApiClient.logout()
        awaitSnapshotCleanup()
        ApiClient.saveToken(tokenA)
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
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
        prepareRole()
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
        assertTrue(requests.contains(path to "Bearer $tokenA"))
        compose.runOnIdle { ApiClient.logout() }
        awaitSnapshotCleanup()
        compose.runOnIdle { ApiClient.saveToken(tokenB) }
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
            it.second == "Bearer $tokenB" &&
                it.first in setOf("/bookings/42/boarding-code", "/bookings/42/details")
        }
        assertTrue("Old screen continued with B token: $successorRequests", successorRequests.isEmpty())
    }
    private fun awaitSnapshotCleanup() = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeout(5000) {
            while (!com.yuldash.app.data.OfflineTripSnapshots.ensureResetCommitted()) kotlinx.coroutines.delay(10)
        }
    }

    private fun canonicalTripBody(path: String?, body: String): String {
        val endpoint = path.orEmpty()
        val details = endpoint.endsWith("/details")
        val role = endpoint.endsWith("/role")
        val receipt = endpoint.endsWith("/receipt")
        if (!details && !role && !receipt) return body
        val value = org.json.JSONObject(body)
        if ((details || receipt) && !value.has("ride_id")) value.put("ride_id", 1042)
        if (details) {
            if (!value.has("contact_unlocked")) value.put("contact_unlocked", value.optString("status") in setOf("confirmed", "onboard"))
            if (!value.has("pay_method")) value.put("pay_method", "negotiate")
        }
        if (role && value.optString("driver_phase") == "waiting") value.put("driver_phase", "")
        if ((role || details) && value.optString("status") == "onboard" && !value.has("onboard_at"))
            value.put("onboard_at", "2031-02-03T09:00:00.123456Z")
        return value.toString()
    }

    private fun preparationRoleResponse(request: RecordedRequest): MockResponse? {
        if (!preparingRole || request.path != "/bookings/42/role") return null
        preparationRequests += "${request.method} ${request.path}"
        return MockResponse().setBody(canonicalTripBody(request.path, """{"role":"passenger","status":"confirmed","driver_phase":"waiting"}"""))
    }
    private fun prepareRole() = kotlinx.coroutines.runBlocking {
        val start = preparationRequests.size
        preparingRole = true
        try { ApiClient.getTripState(42).getOrThrow() } finally { preparingRole = false }
        assertEquals(listOf("GET /bookings/42/role"), preparationRequests.drop(start))
        assertTrue(com.yuldash.app.data.OfflineTripSnapshots.allowsLive(42))
        assertNull(com.yuldash.app.data.OfflineTripSnapshots.privatePass(42))
    }

}
