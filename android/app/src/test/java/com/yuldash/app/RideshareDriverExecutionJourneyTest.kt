package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.Outbox
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassStore
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

/** PATH-03: actual driver CTA, authoritative HTTP phases, completion and retry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RideshareDriverExecutionJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(false)
    private val posted = CopyOnWriteArrayList<String>()
    private val server = MockWebServer()
    @Volatile private var phase = ""
    @Volatile private var bookingStatus = "confirmed"
    @Volatile private var rejectedStatus = ""
    @Volatile private var refusal = 409

    @Before fun prepare() {
        ApiClient.resetForTest()
        ApiClient.logout() // Controlled REST without authenticated live socket/map.
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        Outbox.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        val pass = TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "old"))
        assertTrue(TripPassStore.save(context, pass))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/bookings/42/driver-status") {
                    val status = JSONObject(request.body.readUtf8()).getString("status")
                    posted += status
                    if (status == rejectedStatus) return MockResponse().setResponseCode(refusal)
                        .setBody("""{"detail":{"ru":"Переход отклонён","ba":"Күсеү кире ҡағылды"}}""")
                    if (status == "done") bookingStatus = "done" else phase = status
                    return MockResponse().setBody("{}")
                }
                val body = when (request.path) {
                    "/bookings/42/role" -> """{"role":"driver","status":"$bookingStatus","driver_phase":"$phase"}"""
                    "/bookings/42/messages" -> """{"items":[]}"""
                    "/bookings/42/boarding-code" -> """{"code":"1234"}"""
                    "/bookings/42/details" -> """{"booking_id":42,"role":"driver","status":"$bookingStatus"}"""
                    "/trips/42/receipt" -> """{"booking_id":42,"role":"driver","amount":700,"paid":false}"""
                    "/bookings/42/tip" -> """{"already_thanked":false}"""
                    else -> return MockResponse().setResponseCode(404).setBody("{}")
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
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
    private fun mount(text: String) {
        mounted.value = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                    onBack = {}, onTripEnd = {}, onSos = {})
            }
        }
        waitFor(text)
    }
    private fun waitFor(text: String) = compose.waitUntil(10000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun pump() {
        repeat(15) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }
    private fun openFinishConfirmation() {
        compose.onNodeWithText("Завершить").performClick()
        compose.onNodeWithText("Завершить поездку?").assertIsDisplayed()
    }

    @Test fun driverExecutesEveryPhaseAndConfirmsCompletionBeforeTheOnlyDonePost() {
        mount("Я выехал")
        compose.onNodeWithText("Я выехал").performClick()
        waitFor("Подъезжаю")
        assertEquals(listOf("departed"), posted.toList())
        compose.onNodeWithText("Подъезжаю").performClick()
        waitFor("Завершить")
        openFinishConfirmation()
        assertEquals(listOf("departed", "arriving"), posted.toList())
        compose.onNodeWithText("Не завершать").performClick()
        assertNotNull(TripPassStore.load(context, 42))
        assertEquals(2, posted.size)
        openFinishConfirmation()
        compose.onNodeWithText("Да, завершить").performClick()
        waitFor("Поездка завершена")
        compose.waitUntil(10000) { TripPassStore.load(context, 42) == null }
        assertEquals(listOf("departed", "arriving", "done"), posted.toList())
    }

    @Test fun rejectedArrivalKeepsItsPreviousPhaseAndRetriesTheSameTransition() {
        phase = "departed"
        rejectedStatus = "arriving"
        mount("Подъезжаю")
        compose.onNodeWithText("Подъезжаю").performClick()
        pump()
        compose.onNodeWithText("Подъезжаю").assertIsDisplayed()
        assertEquals("departed", phase)
        assertEquals(0, Outbox.count(context, 42))
        rejectedStatus = ""
        compose.onNodeWithText("Подъезжаю").performClick()
        waitFor("Завершить")
        assertEquals(listOf("arriving", "arriving"), posted.toList())
        assertEquals("arriving", phase)
    }

    @Test fun refusedCompletionKeepsThePassportAndRetriesOnlyAfterConfirmation() {
        phase = "arriving"
        rejectedStatus = "done"
        refusal = 503
        mount("Завершить")
        openFinishConfirmation()
        assertEquals(0, posted.size)
        compose.onNodeWithText("Да, завершить").performClick()
        pump()
        compose.onNodeWithText("Завершить").assertIsDisplayed()
        compose.onAllNodesWithText("Поездка завершена").assertCountEquals(0)
        assertNotNull(TripPassStore.load(context, 42))
        assertEquals(0, Outbox.count(context, 42))
        rejectedStatus = ""
        openFinishConfirmation()
        assertEquals(listOf("done"), posted.toList())
        compose.onNodeWithText("Да, завершить").performClick()
        waitFor("Поездка завершена")
        compose.waitUntil(10000) { TripPassStore.load(context, 42) == null }
        assertEquals(listOf("done", "done"), posted.toList())
    }
}
