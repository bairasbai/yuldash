package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.BookingCommandApi
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.TripPassAuthorityFixture
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.Outbox
import com.yuldash.app.data.TripPassStore
import androidx.test.core.app.ApplicationProvider
import android.Manifest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections

/**
 * Д2 (решение Александра 24.10.2026): водитель везёт нескольких и нажал «Завершить» по одному.
 * Окно «Завершить поездку?» держится с «Завершаем…», пока сервер не принял и не пришёл список рейса;
 * остались пассажиры — экран итога не показываем (увели в «Едут с тобой»); последний — итог, как раньше.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriverFinishRideTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer
    private val sent = Collections.synchronizedList(mutableListOf<String>())
    private var mounted: MutableState<Boolean>? = null
    @Volatile private var bookingStatus = "confirmed"

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = 1_000
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        Outbox.initStores(MemoryDiskPreferences(), null)
        DriverGpsActions.revoke()
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path == "/me" -> json("""{"id":7,"created_at":"2026-01-01T00:00:00Z"}""")
                    request.method == "GET" && path == "/bookings/7707/role" -> json(
                        """{"role":"driver","status":"$bookingStatus","driver_phase":"departed","arrival_verified":false,"alone_with_driver":false}"""
                    )
                    request.method == "GET" && path == "/bookings/7707/messages" -> json("""{"items":[]}""")
                    request.method == "GET" && path == "/bookings/7707/boarding-code" -> json("""{"code":"1234"}""")
                    request.method == "GET" && path == "/bookings/7707/details" -> json("""{"booking_id":7707,"ride_id":7,"role":"driver","status":"$bookingStatus","contact_unlocked":true,"pay_method":"negotiate","from_city":"А","to_city":"Б","depart_at":"2026-11-20T09:00:00Z","seats":1,"price":500}""")
                    request.method == "POST" && path in setOf("/bookings/7707/driver-status", "/bookings/7707/commands/v1/driver-status") -> {
                        sent += request.body.readUtf8()
                        bookingStatus = "done"
                        json("""{"ok":true,"id":7707,"status":"done"}""")
                    }
                    path == "/trips/7707/receipt" -> json("""{"booking_id":7707,"ride_id":7,"role":"driver","amount":700,"paid":false}""")
                    path == "/bookings/7707/tip" -> json("""{"already_thanked":false}""")
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
        server.start()
        TripPassAuthorityFixture.installSession(server.url("/").toString().trimEnd('/'), 7)
        BookingCommandApi.init(MemoryDiskPreferences())
        runBlocking {
            ApiClient.getTripState(7707).getOrThrow()
            val details = ApiClient.getBookingDetails(7707).getOrThrow()
            assertTrue("Fixture must obtain actual live authority", OfflineTripSnapshots.allowsLive(7707))
            assertTrue(OfflineTripSnapshots.privateDetails(details))
            assertEquals("1234", ApiClient.getBoardingCode(7707).getOrThrow())
            assertNull(OfflineTripSnapshots.privatePass(7707))
        }
    }

    @After fun teardown() {
        mounted?.let { state ->
            composeRule.runOnIdle { state.value = false }
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun mount(onDriverFinished: suspend (Int) -> Boolean) {
        val screenMounted = mutableStateOf(true)
        mounted = screenMounted
        composeRule.setContent {
            if (screenMounted.value) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 7707, onBack = {}, onTripEnd = {}, onSos = {},
                        onDriverFinished = onDriverFinished)
                }
            }
        }
        // Completion is disabled until the saved command check has finished.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            composeRule.onAllNodes(hasText("Завершить поездку") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun confirmFinish() {
        composeRule.onNodeWithText("Завершить поездку").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Да, завершить").assertIsDisplayed().performClick()
    }

    @Test fun otherPassengersStillRidingSkipTheSummaryScreen() {
        val asked = Collections.synchronizedList(mutableListOf<Int>())
        val answer = CompletableDeferred<Boolean>()
        mount { id -> asked += id; answer.await() }
        confirmFinish()

        composeRule.waitUntil(10_000) {   // сервер принял «done», экран спросил, остались ли пассажиры
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            synchronized(asked) { asked.isNotEmpty() }
        }
        // Ждём список рейса — окно не закрылось, кнопка «Завершаем…», второй тап не уйдёт.
        composeRule.onNodeWithText("Завершаем…").assertIsDisplayed()
        assertEquals(listOf("{\"status\":\"done\"}"), synchronized(sent) { sent.toList() })
        assertEquals(listOf(7707), synchronized(asked) { asked.toList() })

        answer.complete(true)   // у рейса есть ещё живые брони — экран уже ушёл в «Едут с тобой»
        composeRule.waitUntil(10_000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithText("Завершаем…").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onAllNodesWithText("Поездка завершена").fetchSemanticsNodes().let {
            assertEquals("экран итога одного пассажира задержал бы водителя с остальными в машине", 0, it.size)
        }
    }

    @Test fun lastPassengerStillGetsTheSummaryScreen() {
        mount { false }
        confirmFinish()
        waitForText("Поездка завершена")
        assertEquals(1, synchronized(sent) { sent.size })
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
}
