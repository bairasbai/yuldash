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
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.BookingDetailsDto
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.TripPassAuthorityFixture
import com.yuldash.app.data.OfflineTripSnapshots
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

/** The actual active-trip screen must distinguish a visible code from a durable offline copy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripBoardingCodeSaveTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plain = MemoryDiskPreferences()
    private val secure = MemoryDiskPreferences()
    private val mounted = mutableStateOf(false)
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var preparingPass: TripPass? = null
    private val preparationRequests = CopyOnWriteArrayList<String>()
    @Volatile private var offlineRole = false
    @Volatile private var preparedDetails: BookingDetailsDto? = null
    private val transportObservations = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer

    @Before fun setup() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    preparationResponse(request)?.let { return it }
                    requests.add("${request.method} ${request.path}")
                    if (request.method != "GET") return MockResponse().setResponseCode(405)
                    if (request.path == "/bookings/42/boarding-code") observeNormalCodeRequest()
                    if (offlineRole && request.path == "/bookings/42/role") return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    val body = when (request.path) {
                        "/bookings/42/role" -> """{"role":"passenger","status":"confirmed","driver_phase":"departed"}"""
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/bookings/42/boarding-code" -> """{"code":"5678"}"""
                        "/bookings/42/details" -> """{"booking_id":42,"ride_id":1042,"role":"passenger","status":"confirmed","contact_unlocked":true,"pay_method":"negotiate","from_city":"Город А","to_city":"Город Б"}"""
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        TripPassAuthorityFixture.installSession(server.url("/").toString().trimEnd('/'))
        ApiClient.testTimeoutMs = 1000
        ApiClient.testTrace = ::traceOwnedTransport
        TripPassStore.initStores(plain, secure)
        val pass = TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "1234").put("pay_method", "negotiate"))
        preparePositive(pass)
        assertTrue(TripPassStore.save(context, pass))
        assertEquals("1234", diskCode())
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        ApiClient.testTrace = null
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        if (::server.isInitialized) server.shutdown()
    }

    @Test fun failedCodeSaveShowsWarningAndRetryPersistsAfterRestart() {
        secure.failWriteOf = "pass_42"
        openScreen()
        waitForText("Код не сохранён на телефоне")
        compose.onNodeWithText("Код не сохранён на телефоне").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText("Сохранить код ещё раз"))
        compose.onNodeWithText("Сохранить код ещё раз").assertIsDisplayed()
        // commit(false) can mutate process memory; only the independent disk model proves loss.
        assertEquals("1234", diskCode())
        dumpCodeRequestDiagnostics("before-original-initial-count")
        val initialCodeReads = assertInitialCodeReadProtocol()

        secure.failWriteOf = null
        compose.onNodeWithText("Сохранить код ещё раз").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            diskCode() == "5678" &&
                compose.onAllNodesWithText("Код не сохранён на телефоне").fetchSemanticsNodes().isEmpty()
        }
        compose.onAllNodesWithText("Сохранить код ещё раз").assertCountEquals(0)
        dumpCodeRequestDiagnostics("after-original-local-save-retry")
        assertEquals("A local save retry must not fetch or generate another code", initialCodeReads,
            requests.count { it == "GET /bookings/42/boarding-code" })
        assertFalse(requests.any { it.startsWith("POST ") })
        verifyAfterRestart()
    }

    @Test fun successfullySavedCodeSurvivesRestartWithoutWarning() {
        openScreen()
        waitForText("5678")
        try {
            compose.waitUntil(15000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                diskCode() == "5678" && OfflineTripSnapshots.projection(42)?.details?.let(OfflineTripSnapshots::freshDetails) == true
            }
        } catch (failure: ComposeTimeoutException) {
            val diskNew = runCatching { diskCode() == "5678" }
            // Keep the independent durable read; report only its predicate, never the stored code.
            throw AssertionError("CODE_SAVE diskNew=${diskNew.getOrNull()} diskReadFailed=${diskNew.isFailure} " +
                "normalDetails=${requests.count { it == "GET /bookings/42/details" }} " +
                "prepDetails=${preparationRequests.count { it == "GET /bookings/42/details" }} " +
                "normalCode=${requests.count { it == "GET /bookings/42/boarding-code" }} " +
                "prepCode=${preparationRequests.count { it == "GET /bookings/42/boarding-code" }}", failure)
        }
        compose.waitForIdle()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText("5678"))
        compose.onNodeWithText("5678").assertIsDisplayed()
        compose.onAllNodesWithText("Код не сохранён на телефоне").assertCountEquals(0)
        compose.onAllNodesWithText("Сохранить код ещё раз").assertCountEquals(0)
        dumpCodeRequestDiagnostics("before-original-initial-count")
        assertInitialCodeReadProtocol()
        verifyAfterRestart()
    }

    @Test fun offlinePassportDoesNotShowKnownStaleCodeAfterWriteFailure() {
        offlineRole = true
        secure.failWriteOf = "pass_42"
        openScreen()
        waitForText("Код не сохранён на телефоне")
        var lastFailure: Throwable? = null
        try {
            compose.waitUntil(15000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                runCatching {
                    compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText("Паспорт поездки"))
                    compose.onNodeWithText("Паспорт поездки").assertIsDisplayed()
                }.onFailure { lastFailure = it }.isSuccess
            }
        } catch (e: Throwable) {
            println("OFFLINE_SCROLL_FAILURE=" + lastFailure)
            println("OFFLINE_PASS=" + TripPassStore.load(context, 42)?.boardingCode)
            println("OFFLINE_REQUESTS=" + requests.toList())
            println(compose.onRoot().printToString())
            throw e
        }
        assertEquals("1234", diskCode())
        compose.onAllNodesWithText("1234").assertCountEquals(0)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText("5678"))
        compose.onNodeWithText("5678").assertIsDisplayed()
        assertTrue(requests.contains("GET /bookings/42/role"))
        dumpCodeRequestDiagnostics("after-original-offline-role-control")
        assertEquals("A withheld role response cannot cause a fresh projection retry", 1,
            requests.count { it == "GET /bookings/42/boarding-code" })
        assertEquals("The prepared projection remains sufficient while role is withheld", 0,
            requests.count { it == "GET /bookings/42/details" })
    }

    /** Startup may renew one superseded projection; a local disk-save retry may never fetch code. */
    private fun assertInitialCodeReadProtocol(): Int {
        val codePath = "GET /bookings/42/boarding-code"
        val detailsPath = "GET /bookings/42/details"
        val owned = setOf("GET /bookings/42/role", detailsPath, codePath)
        val reads = requests.filter { it in owned }
        val codes = reads.indices.filter { reads[it] == codePath }
        val details = reads.indices.filter { reads[it] == detailsPath }
        assertTrue("Startup permits one code read and at most one projection retry", codes.size in 1..2)
        assertTrue("Startup must not repeatedly reload participant details", details.size <= 1)
        val events = transportObservations.filter { it.endsWith(" preparation=false") }
        assertEquals("Every initial code GET must have one completed transport call", codes.size,
            events.count { it == "success /bookings/42/boarding-code preparation=false" })
        assertEquals("Unexpected extra initial code call", codes.size,
            events.count { it == "start /bookings/42/boarding-code preparation=false" })
        assertFalse("A cancelled UI loader is not the accepted projection-retry phase",
            events.any { it == "cancel /bookings/42/boarding-code preparation=false" })
        if (details.isNotEmpty()) {
            assertTrue("A refreshed startup projection requires an actual successful role read",
                events.any { it == "success /bookings/42/role preparation=false" })
        }
        if (codes.size == 1) {
            assertTrue("Optional initial details must precede the only code request",
                details.isEmpty() || details.single() < codes.single())
        } else {
            assertEquals("A second code requires exactly one new participant-details read", 1, details.size)
            assertTrue("The fresh details must separate the initial and retried code reads",
                codes.first() < details.single() && details.single() < codes.last())
        }
        return codes.size
    }

    private fun traceOwnedTransport(line: String) {
        val path = listOf("/bookings/42/role", "/bookings/42/details", "/bookings/42/boarding-code")
            .firstOrNull { line.contains("GET " + it) } ?: return
        val event = when {
            line.startsWith("вошли в вызов ") -> "start"
            line.startsWith("ВЫШЛИ из вызова ") -> "success"
            line.contains("ОТМЕНЁН") -> "cancel"
            else -> return
        }
        transportObservations += event + " " + path + " preparation=" + (preparingPass != null)
    }

    private fun observeNormalCodeRequest() {
        val projection = OfflineTripSnapshots.projection(42)
        val details = projection?.details
        val permit = projection?.permit
        val observes = permit?.input?.let { state ->
            OfflineTripSnapshots.gate.canObserve(permit, permit.ticket, 42, state, permit.inputRide, permit.inputRoute)
        } == true
        transportObservations += "wire /bookings/42/boarding-code" +
            " ordinal=" + requests.count { it == "GET /bookings/42/boarding-code" } +
            " roleReads=" + requests.count { it == "GET /bookings/42/role" } +
            " detailsReads=" + requests.count { it == "GET /bookings/42/details" } +
            " preparedSameRef=" + (details != null && details === preparedDetails) +
            " preparedEqual=" + (details != null && details == preparedDetails) +
            " detailsFresh=" + (details?.let(OfflineTripSnapshots::freshDetails) == true) +
            " private=" + OfflineTripSnapshots.privateDetails(details) +
            " live=" + OfflineTripSnapshots.allowsLive(42) +
            " permitCurrentWrite=" + observes +
            " detailsSequence=" + projection?.proof?.request?.ticket?.sequence +
            " permitSequence=" + permit?.ticket?.sequence
    }

    private fun dumpCodeRequestDiagnostics(stage: String) {
        val ownedReads = setOf("GET /bookings/42/role", "GET /bookings/42/details", "GET /bookings/42/boarding-code")
        println("CODE_SAVE_DIAG stage=" + stage + " normalOrder=" + requests.filter { it in ownedReads } +
            " preparationOrder=" + preparationRequests.filter { it in ownedReads })
        transportObservations.toList().forEach { println("CODE_SAVE_DIAG " + it) }
    }

    private fun openScreen() {
        mounted.value = true
        compose.setContent {
            if (mounted.value) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    // Empty coordinates and no Ride avoid mounting a real MapKit during this test.
                    ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                        onBack = {}, onTripEnd = {}, onSos = {})
                }
            }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun diskCode(): String =
        JSONObject(requireNotNull(secure.restarted().getString("pass_42", null))).getString("boarding_code")

    private fun verifyAfterRestart() {
        compose.runOnIdle { mounted.value = false }
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertEquals("5678", TripPassStore.load(context, 42)?.boardingCode)
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

    private fun preparePositive(pass: TripPass) = runBlocking {
        val start = preparationRequests.size
        check(preparingPass == null)
        preparingPass = pass
        try {
            ApiClient.getTripState(pass.bookingId).getOrThrow()
            val actual = ApiClient.getBookingDetails(pass.bookingId).getOrThrow()
            preparedDetails = actual
            assertTrue("Setup must obtain its own actual current DETAILS", OfflineTripSnapshots.privateDetails(actual))
            assertEquals(pass.boardingCode, ApiClient.getBoardingCode(pass.bookingId).getOrThrow())
        } finally { preparingPass = null }
        val prefix = "GET /bookings/${pass.bookingId}"
        assertEquals(listOf("$prefix/role", "$prefix/details", "$prefix/boarding-code"),
            preparationRequests.drop(start))
    }

}
