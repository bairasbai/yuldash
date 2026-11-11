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
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.TripPassAuthorityFixture
import kotlinx.coroutines.runBlocking
import com.yuldash.app.data.TripPassStore
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** DESIGN066: actual code card must leave loading on error, and retry only its GET. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripBoardingCodeLoadTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(false)
    private val requested = CopyOnWriteArrayList<String>()
    private val preparationRequests = CopyOnWriteArrayList<String>()
    @Volatile private var preparingRole = false
    private val server = MockWebServer()
    @Volatile private var codeStatus = 200
    @Volatile private var privateRecoveryDetails: String? = null
    @Volatile private var codeBody = """{"code":"1234"}"""

    @Before fun prepare() {
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        Outbox.initStores(MemoryDiskPreferences(), null)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (preparingRole && request.path == "/bookings/42/role") {
                    preparationRequests += "${request.method} ${request.path}"
                    return MockResponse().setBody("""{"role":"passenger","status":"confirmed","driver_phase":"departed"}""")
                }
                requested += "${request.method} ${request.path}"
                if (request.path == "/bookings/42/boarding-code") return MockResponse()
                    .setResponseCode(codeStatus).setBody(codeBody)
                val body = when (request.path) {
                    "/bookings/42/messages" -> """{"items":[]}"""
                    "/bookings/42/role" -> """{"role":"passenger","status":"confirmed","driver_phase":"departed"}"""
                    "/bookings/42/details" -> privateRecoveryDetails ?: """{"booking_id":42,"ride_id":1042,"role":"passenger","status":"confirmed","contact_unlocked":true,"pay_method":"negotiate"}"""
                    else -> return MockResponse().setResponseCode(404).setBody("{}")
                }
                return MockResponse().setBody(body)
            }
        }
        server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        TripPassAuthorityFixture.installSession(server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/'))
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

    private fun mount() {
        preparingRole = true
        try { runBlocking { ApiClient.getTripState(42).getOrThrow() } }
        finally { preparingRole = false }
        assertEquals(listOf("GET /bookings/42/role"), preparationRequests.toList())
        assertTrue(OfflineTripSnapshots.allowsLive(42))
        assertNull(OfflineTripSnapshots.projection(42))
        assertNull(OfflineTripSnapshots.privatePass(42))
        mounted.value = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                    onBack = {}, onTripEnd = {}, onSos = {})
            }
        }
    }
    private fun waitFor(text: String) = compose.waitUntil(10000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun rejectsThenRetries(status: Int, body: String) {
        codeStatus = status
        codeBody = body
        mount()
        if (status == 403) {
            verifyHardDenialAfterExplicitRetry()
            return
        }
        if (status == 200 && body == """{"code":""}""") {
            retryAfterPrivateWithdrawal()
            return
        }
        // Код и детали грузятся независимо: ждём итог самого кода, а не чужой запрос деталей.
        try {
            waitFor("Не удалось загрузить код")
        } catch (failure: ComposeTimeoutException) {
            if (status == 403 || (status == 200 && body == """{"code":""}""")) {
                runCatching {
                    fun exists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
                    val projection = OfflineTripSnapshots.projection(42)
                    val ownGets = listOf("GET /bookings/42/role", "GET /bookings/42/details", "GET /bookings/42/boarding-code", "GET /bookings/42/messages")
                    val labels = listOf(
                        "codeError" to exists("Не удалось загрузить код"),
                        "dataError" to exists("Не удалось загрузить данные поездки"),
                        "codeLoading" to exists("Код загружается"),
                        "dataLoading" to exists("Обновляем данные поездки"),
                        "codeRetry" to exists("Повторить загрузку кода"),
                        "dataRetry" to exists("Повторить загрузку данных"),
                        "codeRow" to exists("Код посадки"))
                    println("UI_PRIVACY_PROBE active phase=old-code-error-timeout case=" +
                        (if (status == 403) "forbidden" else "blank") + " labels=" + labels +
                        " live=" + OfflineTripSnapshots.allowsLive(42) +
                        " details=" + (projection != null) +
                        " detailsFresh=" + (projection?.details?.let(OfflineTripSnapshots::freshDetails) == true) +
                        " detailsPrivate=" + (projection?.details?.let(OfflineTripSnapshots::privateDetails) == true) +
                        " detailsSeq=" + (projection?.proof?.request?.ticket?.sequence ?: -1L) +
                        " privatePass=" + (OfflineTripSnapshots.privatePass(42) != null) +
                        " ownGetCounts=" + ownGets.associateWith { path -> requested.count { it == path } } +
                        " ownGetOrder=" + requested.filter { it in ownGets })
                }.onFailure { probeFailure ->
                    println("UI_PRIVACY_PROBE active diagnosticUnavailable=" + probeFailure.javaClass.simpleName)
                }
            }
            throw failure
        }
        compose.onNodeWithText("Не удалось загрузить код").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Код загружается").assertCountEquals(0)
        codeStatus = 200
        codeBody = """{"code":"5678"}"""
        compose.onNodeWithText("Повторить загрузку кода").performClick()
        waitFor("5678")
        compose.onNodeWithText("5678").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Не удалось загрузить код").assertCountEquals(0)
        assertEquals(2, requested.count { it == "GET /bookings/42/boarding-code" })
        assertEquals(1, requested.count { it == "GET /bookings/42/messages" })
        assertFalse(requested.any { it.startsWith("POST ") })
    }

    private fun retryAfterPrivateWithdrawal() {
        waitFor("Не удалось загрузить данные поездки")
        compose.onNodeWithText("Не удалось загрузить данные поездки").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Код загружается").assertCountEquals(0)
        compose.onAllNodesWithText("Обновляем данные поездки").assertCountEquals(0)
        compose.onAllNodesWithText("Код посадки").assertCountEquals(0)
        assertFalse(OfflineTripSnapshots.allowsLive(42))
        assertNull(OfflineTripSnapshots.projection(42))
        assertNull(OfflineTripSnapshots.privatePass(42))
        assertEquals(1, requested.count { it == "GET /bookings/42/boarding-code" })
        val beforeRetry = requested.size
        codeStatus = 200
        codeBody = """{"code":"5678"}"""
        compose.onNodeWithText("Повторить загрузку данных").performScrollTo().assertIsEnabled().performClick()
        waitFor("5678")
        compose.onNodeWithText("5678").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Не удалось загрузить код").assertCountEquals(0)
        compose.onAllNodesWithText("Не удалось загрузить данные поездки").assertCountEquals(0)
        val recoveryReads = requested.drop(beforeRetry).filter {
            it == "GET /bookings/42/details" || it == "GET /bookings/42/boarding-code"
        }
        assertEquals(listOf("GET /bookings/42/details", "GET /bookings/42/boarding-code"), recoveryReads)
        assertEquals("5678", OfflineTripSnapshots.privatePass(42)?.boardingCode)
        assertEquals(2, requested.count { it == "GET /bookings/42/boarding-code" })
        assertEquals(1, requested.count { it == "GET /bookings/42/messages" })
        assertFalse(requested.any { it.startsWith("POST ") })
    }

    private fun verifyHardDenialAfterExplicitRetry() {
        waitFor("Не удалось загрузить данные поездки")
        compose.onNodeWithText("Не удалось загрузить данные поездки").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Код загружается").assertCountEquals(0)
        compose.onAllNodesWithText("Обновляем данные поездки").assertCountEquals(0)
        compose.onAllNodesWithText("Код посадки").assertCountEquals(0)
        assertFalse(OfflineTripSnapshots.allowsLive(42))
        assertNull(OfflineTripSnapshots.projection(42))
        assertNull(OfflineTripSnapshots.privatePass(42))
        assertEquals(1, requested.count { it == "GET /bookings/42/boarding-code" })
        val retry = compose.onNodeWithText("Повторить загрузку данных").performScrollTo().assertIsEnabled()
        val action = requireNotNull(retry.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        val detailsState = CompletedBoundaryProbe.captures(action, ActiveTripDetailsState::class.java).single()
        assertFalse(detailsState.loading)
        assertFalse(detailsState.needsReload)
        val previousTick = detailsState.reloadTick
        val owner = requireNotNull(OfflineTripSnapshots.currentOwner())
        val generation = ApiClient.queueSessionGeneration()
        val beforeRetry = requested.size
        codeStatus = 200
        codeBody = """{"code":"5678"}"""
        retry.performClick()
        compose.waitUntil(10000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            detailsState.reloadTick == previousTick + 1 && !detailsState.loading &&
                !detailsState.needsReload && detailsState.failure != null &&
                requested.drop(beforeRetry).contains("GET /bookings/42/details")
        }
        assertEquals(previousTick + 1, detailsState.reloadTick)
        assertFalse(detailsState.loading)
        assertFalse(detailsState.needsReload)
        assertNotNull("The fresh DETAILS attempt must finish with denied authority", detailsState.failure)
        assertEquals(listOf("GET /bookings/42/details"), requested.drop(beforeRetry))
        assertTrue("Explicit retry must not replace the owner", OfflineTripSnapshots.currentOwner() == owner)
        assertTrue("Explicit retry must keep the same valid session",
            ApiClient.queueSessionGeneration() == generation && ApiClient.isCurrentSession(generation))
        assertFalse(OfflineTripSnapshots.allowsLive(42))
        assertNull(OfflineTripSnapshots.projection(42))
        assertNull(OfflineTripSnapshots.privatePass(42))
        compose.onNodeWithText("Не удалось загрузить данные поездки").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Повторить загрузку данных").performScrollTo().assertIsEnabled()
        compose.onAllNodesWithText("Код загружается").assertCountEquals(0)
        compose.onAllNodesWithText("Обновляем данные поездки").assertCountEquals(0)
        compose.onAllNodesWithText("Код посадки").assertCountEquals(0)
        compose.onAllNodesWithText("1234").assertCountEquals(0)
        compose.onAllNodesWithText("5678").assertCountEquals(0)
        assertEquals(1, requested.count { it == "GET /bookings/42/boarding-code" })
        assertEquals(1, requested.count { it == "GET /bookings/42/messages" })
        assertFalse(requested.any { it.startsWith("POST ") })
    }

    @Test fun serverOutageLeavesLoadingAndOffersTheSameCodeRetry() = rejectsThenRetries(503, "{}")
    @Test fun forbiddenCodeCannotReopenPrivateDataAfterExplicitRetry() = rejectsThenRetries(403, "{}")
    @Test fun blankSuccessfulPayloadDoesNotBecomeAnEndlessSpinner() = rejectsThenRetries(200, """{"code":""}""")
    @Test fun validCodeAppearsWithoutAnErrorOrExtraRequests() {
        mount()
        waitFor("1234")
        compose.onNodeWithText("1234").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Не удалось загрузить код").assertCountEquals(0)
        assertEquals(1, requested.count { it == "GET /bookings/42/boarding-code" })
    }

    @Test fun freshRoleAfterBlankKeepsChatAndOffersExplicitPrivateDataRecovery() {
        privateRecoveryDetails = """{"booking_id":42,"ride_id":1042,"role":"passenger","status":"confirmed","contact_unlocked":true,"pay_method":"negotiate","driver_phone":"+70000000042","pickup":"PRIVATE_RECOVERY_POINT_42"}"""
        codeBody = """{"code":""}"""
        mount()
        compose.waitUntil(10000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requested.count { it == "GET /bookings/42/boarding-code" } == 1 &&
                requested.count { it == "GET /bookings/42/messages" } == 1 &&
                OfflineTripSnapshots.projection(42) == null
        }
        // CODE renews DETAILS only if a newer ROLE has invalidated the first grant.
        val detailsRead = "GET /bookings/42/details"
        val codeRead = "GET /bookings/42/boarding-code"
        val initialPrivateReads = requested.filter { it == detailsRead || it == codeRead }
        assertTrue(initialPrivateReads.toString(),
            initialPrivateReads == listOf(detailsRead, codeRead) ||
                initialPrivateReads == listOf(detailsRead, detailsRead, codeRead))
        val initialDetailsCount = initialPrivateReads.count { it == detailsRead }
        val owner = requireNotNull(OfflineTripSnapshots.currentOwner())
        val generation = ApiClient.queueSessionGeneration()
        assertNull(OfflineTripSnapshots.privatePass(42))
        // A real ROLE after blank withdrawal grants public live only, never private DETAILS.
        val role = runBlocking { ApiClient.getTripState(42).getOrThrow() }
        assertEquals(initialPrivateReads, requested.filter { it in setOf("GET /bookings/42/details", "GET /bookings/42/boarding-code") })
        assertEquals("passenger", role.role)
        assertEquals("confirmed", role.status)
        assertTrue(OfflineTripSnapshots.allowsLive(42))
        assertNull(OfflineTripSnapshots.projection(42))
        assertNull(OfflineTripSnapshots.privatePass(42))
        waitFor("Написать")
        compose.onNodeWithText("Написать").performClick()
        compose.waitUntil(10000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithTag("tripChatScreen").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("Public chat must remain authorized without private DETAILS", OfflineTripSnapshots.allowsLive(42))
        compose.onAllNodesWithText("+70000000042", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("PRIVATE_RECOVERY_POINT_42", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Открыть поездку").performClick()
        waitFor("Не удалось загрузить данные поездки")
        compose.onNodeWithText("Не удалось загрузить данные поездки").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Код посадки").assertCountEquals(0)
        compose.onAllNodesWithText("Код загружается").assertCountEquals(0)
        compose.onAllNodesWithText("Обновляем данные поездки").assertCountEquals(0)
        compose.onAllNodesWithText("+70000000042", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("PRIVATE_RECOVERY_POINT_42", substring = true).assertCountEquals(0)
        assertNull(OfflineTripSnapshots.privatePass(42))
        assertEquals(initialDetailsCount, requested.count { it == detailsRead })
        assertEquals(1, requested.count { it == codeRead })
        assertEquals(1, requested.count { it == "GET /bookings/42/messages" })
        val beforeRetry = requested.size
        codeBody = """{"code":"5678"}"""
        compose.onNodeWithText("Повторить загрузку данных").performScrollTo().assertIsEnabled().performClick()
        waitFor("5678")
        compose.onNodeWithText("5678").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Не удалось загрузить данные поездки").assertCountEquals(0)
        compose.onAllNodesWithText("Не удалось загрузить код").assertCountEquals(0)
        assertEquals(listOf("GET /bookings/42/details", "GET /bookings/42/boarding-code"),
            requested.drop(beforeRetry).filter { it in setOf("GET /bookings/42/details", "GET /bookings/42/boarding-code") })
        val restored = requireNotNull(OfflineTripSnapshots.privatePass(42))
        assertEquals("5678", restored.boardingCode)
        assertEquals("+70000000042", restored.driverPhone)
        assertEquals("PRIVATE_RECOVERY_POINT_42", restored.pickup)
        assertEquals(owner, OfflineTripSnapshots.currentOwner())
        assertTrue(ApiClient.isCurrentSession(generation))
        assertEquals(initialDetailsCount + 1, requested.count { it == detailsRead })
        assertEquals(2, requested.count { it == codeRead })
        assertEquals(1, requested.count { it == "GET /bookings/42/messages" })
        assertFalse(requested.any { it.startsWith("POST ") })
    }
}
