package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingHistoryCleanupTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plain = MemoryDiskPreferences()
    private val secure = MemoryDiskPreferences()
    private val mounted = mutableStateOf(true)
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var preparingPass: TripPass? = null
    private val preparationRequests = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer
    @Volatile private var status = "done"
    @Volatile private var detailsHttpStatus = 200
    private val warning = "Не удалось удалить сохранённую поездку"

    @Before fun setup() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    preparationResponse(request)?.let { return it }
                    requests.add("${request.method} ${request.path}")
                    if (request.getHeader("Authorization") != "Bearer ${TripPassAuthorityFixture.jwt(7)}") {
                        return MockResponse().setResponseCode(403)
                    }
                    if (request.method == "GET" && request.path == "/me") return MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"id":7,"created_at":"2026-01-01T00:00:00Z"}""")
                    if (request.path != "/bookings/42/details") return MockResponse().setResponseCode(404)
                    if (detailsHttpStatus != 200) return MockResponse().setResponseCode(detailsHttpStatus).setBody("{}")
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(canonicalTripBody(request.path,
                        """{"booking_id":42,"ride_id":17,"role":"passenger","status":"$status","contact_unlocked":true,"from_city":"Город А","to_city":"Город Б","driver_name":"История поездки","price":700,"seats":1}"""))
                }
            }
            start()
        }
        TripPassAuthorityFixture.installSession(server.url("/").toString().trimEnd('/'))
        com.yuldash.app.data.BookingCommandApi.init(context.getSharedPreferences("history_cleanup_commands", Context.MODE_PRIVATE).apply {
            edit().clear().commit()
        })
        ApiClient.testTimeoutMs = 1000
        TripPassStore.initStores(plain, secure)
        val pass = TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "1234").put("pay_method", "negotiate"))
        preparePositive(pass)
        assertTrue(TripPassStore.save(context, pass))
        preparePositive(pass.copy(bookingId = 99, boardingCode = "other"))
        assertTrue(TripPassStore.save(context, pass.copy(bookingId = 99, boardingCode = "other")))
    }
    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        if (::server.isInitialized) server.shutdown()
    }
    private fun open() {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BookingScreen(ride = Ride(id="17", from="Город А", to="Город Б", time="вчера", driver="Водитель", car="Lada", price=700, seats=1, rating=4.9, verified=true, boosted=false),
                    bookingId=42, ads=emptyList(), adStats=emptyMap(), onBack={}, onMessage={}, onAdImpression={}, onAdClick={}, bookingStatus=status,
                    onConfirmRide={ _,_,_,_,_,_ -> fail("History must not create a booking") })
            }
        }
    }
    private fun waitUntil(predicate: () -> Boolean) {
        compose.waitUntil(15000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); predicate() }
    }
    private fun verifyRemoved() {
        waitUntil { !secure.restarted().contains("pass_42") }
        compose.runOnIdle { mounted.value = false }
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertNull(TripPassStore.load(context, 42))
        assertEquals("other", TripPassStore.load(context, 99)?.boardingCode)
        assertEquals(listOf("GET /bookings/42/details"), requests.filterNot { it == "GET /me" })
    }
    @Test fun completedHistoryRemovesPreviousOfflinePassport() { open(); verifyRemoved() }
    @Test fun cancelledHistoryRemovesPreviousOfflinePassport() { status="cancelled"; open(); verifyRemoved() }
    @Test fun failedCleanupOffersLocalRetryAndPreservesOtherBooking() {
        plain.failWriteOf = TripPassDeletion.KEY
        open()
        waitUntil { compose.onAllNodesWithText(warning).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(secure.restarted().contains("pass_42"))
        plain.failWriteOf = null
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Повторить удаление"))
        compose.onNodeWithText("Повторить удаление").performClick()
        verifyRemoved()
    }
    @Test fun unavailableDetailsCannotDeleteBasedOnlyOnHistoryListStatus() {
        detailsHttpStatus = 503
        open()
        waitUntil { compose.onAllNodesWithText("Не удалось обновить детали").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(secure.restarted().contains("pass_42"))
        assertEquals("1234", TripPassStore.load(context, 42)?.boardingCode)
        compose.onAllNodesWithText(warning).assertCountEquals(0)
    }
    @Test fun deferredSecureDeletionBlocksReadAndFinishesAfterStorageRecovery() {
        secure.failRemovalOf = "pass_42"
        open()
        waitUntil { TripPassStore.load(context, 42) == null }
        compose.waitForIdle()
        assertTrue(secure.restarted().contains("pass_42"))
        assertEquals("other", TripPassStore.load(context, 99)?.boardingCode)
        compose.onAllNodesWithText(warning).assertCountEquals(0)
        compose.runOnIdle { mounted.value = false }
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertNull(TripPassStore.load(context, 42))
        assertFalse(secure.restarted().contains("pass_42"))
    }

    private fun preparationResponse(request: RecordedRequest): MockResponse? {
        val pass = preparingPass ?: return null
        val prefix = "/bookings/${pass.bookingId}"
        val body = when (request.path) {
            "$prefix/role" -> JSONObject().put("role", "passenger").put("status", "confirmed").put("driver_phase", "departed")
            "$prefix/details" -> pass.toJson().put("ride_id", pass.bookingId + 1000)
                .put("role", "passenger").put("status", "confirmed").put("contact_unlocked", true)
                .put("pay_method", pass.payMethod ?: "negotiate")
            "$prefix/boarding-code" -> JSONObject().put("code", pass.boardingCode)
            else -> return null
        }
        preparationRequests += "${request.method} ${request.path}"
        return MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
    }
    private fun preparePositive(pass: TripPass) = kotlinx.coroutines.runBlocking {
        val start = preparationRequests.size
        check(preparingPass == null)
        preparingPass = pass
        try {
            ApiClient.getTripState(pass.bookingId).getOrThrow()
            val actual = ApiClient.getBookingDetails(pass.bookingId).getOrThrow()
            assertTrue("Setup must obtain its own actual current DETAILS", OfflineTripSnapshots.privateDetails(actual))
            assertEquals(pass.boardingCode, ApiClient.getBoardingCode(pass.bookingId).getOrThrow())
        } finally { preparingPass = null }
        val prefix = "GET /bookings/${pass.bookingId}"
        assertEquals(listOf("$prefix/role", "$prefix/details", "$prefix/boarding-code"),
            preparationRequests.drop(start))
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

}
