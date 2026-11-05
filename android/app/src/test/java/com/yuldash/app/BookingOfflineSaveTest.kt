package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.TripPassAuthorityFixture
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

/** Real BookingScreen + HTTP, with a failed local migration cleanup rather than a fake save result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingOfflineSaveTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plain = MemoryDiskPreferences()
    private val secure = MemoryDiskPreferences()
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<String>()
    private val authenticationRequests = CopyOnWriteArrayList<String>()
    private val tokenA = TripPassAuthorityFixture.jwt(7)
    private val preparationRequests = CopyOnWriteArrayList<String>()
    @Volatile private var preparing = false
    @Volatile private var preparedPass: TripPass? = null
    private var confirmCalls = 0
    @Volatile private var boardingCodeStatus = 200
    @Volatile private var boardingCodeValue = "4821"
    private val warning = "Не удалось сохранить поездку без интернета"

    private fun awaitApiBackgroundCompletion()=runBlocking {
        // Observe only the test-owned API coroutine job, never auth/key fields.
        val scope=ApiClient::class.java.getDeclaredField("bg").apply {isAccessible=true}.get(ApiClient) as CoroutineScope
        val parent=requireNotNull(scope.coroutineContext[Job])
        try {
            withTimeout(10_000) {
                while(true) {
                    val children=parent.children.toList()
                    if(children.isEmpty()) break
                    children.forEach {it.join()}
                }
            }
        } catch(failure:TimeoutCancellationException) {
            throw AssertionError("Fixture API jobs still running; local origin/server retained. Abort this isolated run.",failure)
        }
    }
    private fun awaitSnapshotCleanup() = runBlocking {
        withTimeout(5_000) {
            while (!OfflineTripSnapshots.ensureResetCommitted()) delay(10)
        }
    }

    private fun startAuthenticatedFixture() {
        ApiClient.init(context)
        ApiClient.logout()
        awaitSnapshotCleanup()
        ApiClient.saveToken(tokenA)
        val actual = requireNotNull(OfflineTripSnapshots.currentOwner())
        assertEquals(7, actual.userId)
        assertEquals(ApiClient.queueSessionGeneration(), actual.generation)
        assertTrue(OfflineTripSnapshots.cacheEnabled(actual))
    }

    private fun preparationResponse(request: RecordedRequest): MockResponse {
        preparationRequests.add(request.method + " " + request.path)
        val pass = requireNotNull(preparedPass)
        if (request.method != "GET" || request.getHeader("Authorization") != "Bearer " + tokenA)
            return MockResponse().setResponseCode(403).setBody("{}")
        val base = "/bookings/" + pass.bookingId
        val body = when (request.path) {
            base + "/role" -> JSONObject().put("role", "passenger").put("status", "confirmed").put("driver_phase", "")
            base + "/details" -> pass.toJson()
                .put("ride_id", if (pass.bookingId == 42) 17 else pass.bookingId + 1000)
                .put("role", "passenger").put("status", "confirmed").put("contact_unlocked", true)
            base + "/boarding-code" -> JSONObject().put("code", pass.boardingCode)
            else -> return MockResponse().setResponseCode(404).setBody("{}")
        }
        return MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
    }

    private fun authorizePositive(pass: TripPass) = runBlocking {
        check(!preparing)
        val before = preparationRequests.size
        preparedPass = pass
        preparing = true
        try {
            assertEquals("passenger", ApiClient.getBookingRole(pass.bookingId).getOrThrow())
            val actual = ApiClient.getBookingDetails(pass.bookingId).getOrThrow()
            assertTrue("Positive save requires actual current private DETAILS", OfflineTripSnapshots.privateDetails(actual))
            assertEquals(pass.boardingCode, ApiClient.getBoardingCode(pass.bookingId).getOrThrow())
            assertTrue("The original pass must match the actual HTTP projection", OfflineTripSnapshots.matchesPass(pass))
            val base = "GET /bookings/" + pass.bookingId
            assertEquals(listOf(base + "/role", base + "/details", base + "/boarding-code"),
                preparationRequests.drop(before))
        } finally {
            preparing = false
            preparedPass = null
        }
    }

    @Before fun setup() {
        awaitApiBackgroundCompletion();awaitSnapshotCleanup()
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path in setOf("/auth/logout", "/push/unregister")) {
                        authenticationRequests.add(request.method + " " + request.path)
                        return MockResponse().setHeader("Content-Type", "application/json").setBody("{}")
                    }
                    if (preparing) return preparationResponse(request)
                    requests.add("${request.method} ${request.path}")
                    if (request.getHeader("Authorization") != "Bearer " + tokenA)
                        return MockResponse().setResponseCode(403).setBody("{}")
                    val body = when (request.path) {
                        "/bookings/42/details" -> """{"booking_id":42,"ride_id":17,"role":"passenger","status":"confirmed","contact_unlocked":true,"from_city":"Город А","to_city":"Город Б","depart_at":"2031-02-03T09:00:00","driver_name":"Серверный водитель","driver_car":"Lada Vesta","driver_phone":"+70000000000","price":700,"seats":1,"pickup":"Точка встречи"}"""
                        "/bookings/42/boarding-code" -> return MockResponse()
                            .setResponseCode(boardingCodeStatus)
                            .setHeader("Content-Type", "application/json")
                            .setBody(JSONObject().put("code", boardingCodeValue).toString())
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
        startAuthenticatedFixture()
        TripPassStore.initStores(plain, null)
        val old = TripPass.fromJson(JSONObject().put("booking_id", 7).put("boarding_code", "old")
            .put("pay_method", "negotiate"))
        authorizePositive(old)
        assertTrue(TripPassStore.save(context, old))
        assertEquals("old", TripPassAuthorityFixture.durable(plain, 7)?.boardingCode)
        // Prepare the exact candidate before the real legacy migration cleanup fault.
        authorizePositive(old.copy(bookingId = 42))
        plain.failRemovalOf = "pass_7"
        TripPassStore.initStores(plain, secure)
        assertTrue(OfflineTripSnapshots.matchesPass(old.copy(bookingId = 42)))
        assertFalse(TripPassStore.save(context, old.copy(bookingId = 42)))
        assertNull(TripPassAuthorityFixture.durable(secure, 42))
        assertNull(TripPassAuthorityFixture.durable(plain, 42))
    }

    @After fun cleanup() {
        if (!::server.isInitialized) { awaitApiBackgroundCompletion(); awaitSnapshotCleanup(); return }
        plain.failRemovalOf = null
        ApiClient.logout()
        awaitApiBackgroundCompletion()
        awaitSnapshotCleanup()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
    }

    private fun render(allowWithdrawnPrivate: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BookingScreen(
                    ride = Ride(id = "17", from = "Город А", to = "Город Б", time = "завтра, 09:00",
                        driver = "Исходный водитель", car = "Lada Vesta", price = 700, seats = 1,
                        rating = 4.9, verified = true, boosted = false),
                    bookingId = 42, ads = emptyList(), adStats = emptyMap(), onBack = {},
                    onMessage = {}, onAdImpression = {}, onAdClick = {}, bookingStatus = "confirmed",
                    onConfirmRide = { _, _, _, _, _ -> confirmCalls++ },
                )
            }
        }
        compose.waitUntil(20000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requests.contains("GET /bookings/42/boarding-code") &&
                compose.onAllNodesWithText("Обновляем подтверждение поездки").fetchSemanticsNodes().isEmpty()
        }
        if (allowWithdrawnPrivate) {
            assertFalse(OfflineTripSnapshots.allowsLive(42))
            assertNull(OfflineTripSnapshots.projection(42))
            assertNull(OfflineTripSnapshots.privatePass(42))
            showWarning()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Сохранить ещё раз"))
            compose.onNodeWithText("Сохранить ещё раз").assertIsDisplayed().assertIsEnabled()
            return
        }
        try {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Серверный водитель"))
            compose.onNodeWithText("Серверный водитель").assertIsDisplayed()
        } catch (failure: AssertionError) {
            if (boardingCodeStatus == 200 && boardingCodeValue.isEmpty()) {
                runCatching {
                    fun exists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
                    val projection = OfflineTripSnapshots.projection(42)
                    val ownGets = listOf("GET /bookings/42/details", "GET /bookings/42/boarding-code")
                    val labels = listOf(
                        "serverLabel" to exists("Серверный водитель"),
                        "publicFallback" to exists("Исходный водитель"),
                        "warning" to exists(warning),
                        "confirmationLoading" to exists("Обновляем подтверждение поездки"))
                    println("UI_PRIVACY_PROBE booking phase=old-private-server-locator-failure case=blank labels=" + labels +
                        " live=" + OfflineTripSnapshots.allowsLive(42) +
                        " details=" + (projection != null) +
                        " detailsFresh=" + (projection?.details?.let(OfflineTripSnapshots::freshDetails) == true) +
                        " detailsPrivate=" + (projection?.details?.let(OfflineTripSnapshots::privateDetails) == true) +
                        " detailsSeq=" + (projection?.proof?.request?.ticket?.sequence ?: -1L) +
                        " privatePass=" + (OfflineTripSnapshots.privatePass(42) != null) +
                        " durableOldCode=" + (TripPassAuthorityFixture.durable(secure, 42)?.boardingCode == "4821") +
                        " ownGetCounts=" + ownGets.associateWith { path -> requests.count { it == path } } +
                        " ownGetOrder=" + requests.filter { it in ownGets })
                }.onFailure { probeFailure ->
                    println("UI_PRIVACY_PROBE booking diagnosticUnavailable=" + probeFailure.javaClass.simpleName)
                }
            }
            throw failure
        }
    }

    private fun show() {
        render()
        assertTrue("Fresh successful HTTP remains private even when the disk save fails",
            OfflineTripSnapshots.privatePass(42)?.boardingCode == "4821")
        assertNull(TripPassAuthorityFixture.durable(secure, 42))
        assertNull(TripPassAuthorityFixture.durable(plain, 42))
        assertEquals(listOf("GET /bookings/42/details", "GET /bookings/42/boarding-code"), requests.toList())
    }

    private fun showWarning() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(warning))
        compose.onNodeWithText(warning).assertIsDisplayed()
    }

    @Test fun failedOfflineSaveIsVisibleWithoutLosingConfirmedBooking() {
        show()
        showWarning()
        compose.onNodeWithText("Открыть поездку").assertIsDisplayed()
        assertNull(TripPassAuthorityFixture.durable(secure, 42))
        assertNull(TripPassAuthorityFixture.durable(plain, 42))
        assertEquals(0, confirmCalls)
    }

    @Test fun retryAfterStorageRecoverySavesPassportWithoutCreatingAnotherBooking() {
        show()
        showWarning()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Сохранить ещё раз"))
        compose.onNodeWithText("Сохранить ещё раз").performClick()
        compose.waitUntil(20000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requests.count { it == "GET /bookings/42/boarding-code" } == 2 &&
                compose.onAllNodes(hasText("Сохранить ещё раз") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        showWarning()
        assertNull(TripPassAuthorityFixture.durable(secure, 42))
        assertNull(TripPassAuthorityFixture.durable(plain, 42))
        plain.failRemovalOf = null
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Сохранить ещё раз"))
        compose.onNodeWithText("Сохранить ещё раз").performClick()
        compose.waitUntil(20000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            TripPassAuthorityFixture.durable(secure, 42)?.boardingCode == "4821" &&
                compose.onAllNodesWithText(warning).fetchSemanticsNodes().isEmpty()
        }
        val saved = requireNotNull(TripPassAuthorityFixture.durable(secure, 42))
        assertEquals("Серверный водитель", saved.driverName)
        assertEquals("Город А", saved.fromCity)
        compose.onNodeWithText(warning).assertDoesNotExist()
        assertEquals(0, confirmCalls)
        assertTrue(requests.all { it.startsWith("GET ") })
        assertEquals(1, requests.count { it == "GET /bookings/42/details" })
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertEquals("4821", TripPassAuthorityFixture.durable(secure, 42)?.boardingCode)
        assertEquals("old", TripPassAuthorityFixture.durable(secure, 7)?.boardingCode)
        assertNull(TripPassAuthorityFixture.durable(plain, 42))
        assertNull(TripPassAuthorityFixture.durable(plain, 7))
        assertEquals("4821", OfflineTripSnapshots.privatePass(42)?.boardingCode)
    }

    @Test fun failedBoardingCodeRefreshPreservesSavedCodeAndCanRetry() {
        verifyBoardingCodeFailure(status = 503, code = "4821")
    }

    @Test fun emptyBoardingCodeRefreshPreservesSavedCodeAndCanRetry() {
        verifyBoardingCodeFailure(status = 200, code = "")
    }

    private fun verifyBoardingCodeFailure(status: Int, code: String) {
        plain.failRemovalOf = null
        TripPassStore.initStores(plain, secure)
        val previous = TripPass.fromJson(JSONObject()
            .put("booking_id", 42)
            .put("boarding_code", "4821")
            .put("driver_name", "Предыдущий водитель").put("pay_method", "negotiate"))
        authorizePositive(previous)
        assertTrue(TripPassStore.save(context, previous))
        boardingCodeStatus = status
        boardingCodeValue = code

        val withdrawnPrivate = status == 200 && code.isEmpty()
        val previousDurableBytes = if (withdrawnPrivate) secure.restarted().getString("pass_42", null) else null
        // render waits until detailsLoading ends, after the initial save attempt completes.
        render(allowWithdrawnPrivate = withdrawnPrivate)
        assertEquals(listOf("GET /bookings/42/details", "GET /bookings/42/boarding-code"), requests.toList())
        // This is intentionally the first failure assertion: HTTP failure must not erase a known code.
        assertEquals("4821", TripPassAuthorityFixture.durable(secure, 42)?.boardingCode)
        if (withdrawnPrivate) {
            assertNotNull(previousDurableBytes)
            assertEquals(previousDurableBytes, secure.restarted().getString("pass_42", null))
        }
        showWarning()
        compose.onNodeWithText("Открыть поездку").assertIsDisplayed()

        val beforeRetry = if (withdrawnPrivate) requests.size else 0
        boardingCodeValue = "5932"
        boardingCodeStatus = 200
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Сохранить ещё раз"))
        compose.onNodeWithText("Сохранить ещё раз").performClick()
        compose.waitUntil(20000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            TripPassAuthorityFixture.durable(secure, 42)?.boardingCode == "5932" &&
                compose.onAllNodesWithText(warning).fetchSemanticsNodes().isEmpty()
        }
        assertEquals("Серверный водитель", TripPassAuthorityFixture.durable(secure, 42)?.driverName)
        compose.onNodeWithText(warning).assertDoesNotExist()
        assertEquals(0, confirmCalls)
        if (withdrawnPrivate) {
            assertEquals(listOf("GET /bookings/42/details", "GET /bookings/42/boarding-code"), requests.drop(beforeRetry))
            assertEquals(listOf("GET /bookings/42/details", "GET /bookings/42/boarding-code",
                "GET /bookings/42/details", "GET /bookings/42/boarding-code"), requests.toList())
            assertFalse(requests.any { it.startsWith("POST ") })
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Серверный водитель"))
            compose.onNodeWithText("Серверный водитель").assertIsDisplayed()
        } else {
            assertEquals(listOf("GET /bookings/42/details", "GET /bookings/42/boarding-code",
                "GET /bookings/42/boarding-code"), requests.toList())
        }
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertEquals("5932", TripPassAuthorityFixture.durable(secure, 42)?.boardingCode)
        assertNull(TripPassAuthorityFixture.durable(plain, 42))
        assertEquals("5932", OfflineTripSnapshots.privatePass(42)?.boardingCode)
    }
}
