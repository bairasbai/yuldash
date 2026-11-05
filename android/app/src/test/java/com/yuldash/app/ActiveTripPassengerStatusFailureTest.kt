package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.Outbox
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.TripPassAuthorityFixture
import com.yuldash.app.data.OfflineTripSnapshots
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CopyOnWriteArrayList

/**
 * DESIGN062: actual passenger CTA and controlled HTTP, not the legacy status component.
 * С 17.10.2026 посадку отмечает водитель: до неё у пассажира главной кнопки нет («Водитель отметит
 * посадку»), а первая его собственная отметка — «Доехал» после «в пути».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripPassengerStatusFailureTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(false)
    private val posted = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer
    @Volatile private var responseCode = 200
    @Volatile private var bookingStatus = "onboard"

    @Before fun setup() {
        ApiClient.resetForTest()
        ShadowToast.reset()
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        Outbox.initStores(MemoryDiskPreferences(), null)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path
                    if (path == "/bookings/42/trip-status") {
                        posted += request.body.readUtf8()
                        return MockResponse().setResponseCode(responseCode).setBody(
                            if (responseCode == 200) "{}" else """{"detail":{"ru":"Переход отклонён","ba":"Күсеү кире ҡағылды"}}"""
                        )
                    }
                    val body = when (path) {
                        "/bookings/42/role" -> JSONObject().put("role", "passenger").put("status", bookingStatus).put("driver_phase", "departed")
                            .put("onboard_at", if (bookingStatus == "onboard") "2031-02-03T09:00:00.123456Z" else JSONObject.NULL).toString()
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/bookings/42/boarding-code" -> """{"code":"1234"}"""
                        "/bookings/42/details" -> JSONObject().put("booking_id", 42).put("ride_id", 17).put("role", "passenger").put("status", bookingStatus)
                            .put("onboard_at", if (bookingStatus == "onboard") "2031-02-03T09:00:00.123456Z" else JSONObject.NULL).toString()
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setBody(body).setHeader("Content-Type", "application/json")
                }
            }
            start()
        }
        TripPassAuthorityFixture.installSession(server.url("/").toString().trimEnd('/'))
        ApiClient.testTimeoutMs = 1000
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        Outbox.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
    }

    private fun open(ready: String = "Доехал") {
        mounted.value = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                    onBack = {}, onTripEnd = {}, onSos = {})
            }
        }
        waitFor(ready)
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitFor(text: String, expectedPosts: Int? = null) {
        compose.waitUntil(15000) {
            settle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() && (expectedPosts == null || posted.size == expectedPosts)
        }
    }

    private fun rejectedStatus(code: Int) {
        responseCode = code
        open()
        compose.onNodeWithText("Доехал").performClick()
        compose.waitUntil(15000) {
            settle()
            posted.size == 1 && ShadowToast.getTextOfLatestToast() == "Переход отклонён"
        }
        // The retry action must still be arrival, rather than the next false finish step.
        waitFor("Доехал")
        compose.onNodeWithText("Доехал").assertIsDisplayed()
        assertEquals(0, Outbox.count(context, 42))
        responseCode = 200
        compose.onNodeWithText("Доехал").performClick()
        waitFor("Завершить", expectedPosts = 2)
        assertEquals(2, posted.size)
        posted.forEach { assertEquals("arrived", org.json.JSONObject(it).getString("status")) }
    }

    @Test fun forbiddenArrivalRevokesLiveAndRejectsRetainedRetry() {
        responseCode = 403
        open()
        val retainedArrival = requireNotNull(compose.onNodeWithText("Доехал").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action)
        compose.onNodeWithText("Доехал").performClick()
        compose.waitUntil(15000) {
            settle()
            posted.size == 1 && ShadowToast.getTextOfLatestToast() == "Переход отклонён" &&
                !OfflineTripSnapshots.allowsLive(42) &&
                compose.onAllNodesWithText("Доехал").fetchSemanticsNodes().isEmpty()
        }
        compose.onAllNodesWithText("Доехал").assertCountEquals(0)
        compose.onAllNodesWithText("Завершить").assertCountEquals(0)
        assertNull(currentTripPrivateDetails(42))
        assertNull(OfflineTripSnapshots.privatePass(42))
        assertNull(TripPassStore.load(context, 42))
        assertEquals(0, Outbox.count(context, 42))
        responseCode = 200
        compose.runOnIdle { retainedArrival() }
        repeat(20) { settle(); Thread.sleep(50) }
        assertFalse(OfflineTripSnapshots.allowsLive(42))
        assertNull(OfflineTripSnapshots.privatePass(42))
        assertNull(TripPassStore.load(context, 42))
        assertEquals("Отозванная кнопка не отправляет второй POST", 1, posted.size)
        assertEquals("arrived", JSONObject(posted.single()).getString("status"))
        assertEquals(0, Outbox.count(context, 42))
    }
    @Test fun conflictingArrivalKeepsTheSameRetryAction() = rejectedStatus(409)
    @Test fun serverOutageKeepsTheSameRetryAction() = rejectedStatus(503)

    @Test fun acceptedArrivalAdvancesThePassengerAction() {
        open()
        compose.onNodeWithText("Доехал").performClick()
        waitFor("Завершить", expectedPosts = 1)
        assertEquals(1, posted.size)
        assertEquals("arrived", org.json.JSONObject(posted.single()).getString("status"))
    }

    /** Посадку отмечает водитель: подтверждённому пассажиру нажимать нечего — и на сервер ничего не уходит. */
    @Test fun confirmedPassengerWaitsForTheDriverAndPostsNothing() {
        bookingStatus = "confirmed"
        open(ready = "Водитель отметит посадку")
        compose.onAllNodesWithText("Я сел").assertCountEquals(0)
        compose.onAllNodesWithText("Доехал").assertCountEquals(0)
        repeat(20) { settle(); Thread.sleep(50) }
        assertEquals(emptyList<String>(), posted.toList())
    }
}
