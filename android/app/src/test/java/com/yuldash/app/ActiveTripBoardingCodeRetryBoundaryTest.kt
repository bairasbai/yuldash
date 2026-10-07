package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.*
import com.yuldash.app.ui.theme.YuldashTheme
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual error -> held code retry, with owner/terminal/disposal boundaries. No live backend. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripBoardingCodeRetryBoundaryTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(false)
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private var plain = MemoryDiskPreferences()
    private var secure = MemoryDiskPreferences()
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    private val trace = CopyOnWriteArrayList<String>()
    private val codeCalls = AtomicInteger()
    private val held = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val returned = CountDownLatch(1)
    private lateinit var server: MockWebServer
    @Volatile private var tripStatus = "confirmed"
    @Volatile private var lateStatus = 200
    private var ended = 0
    private var backed = 0

    @Before fun prepare() {
        ApiClient.resetForTest()
        ApiClient.serverUnreachable.value = false
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += "${request.method} ${request.path}" to request.getHeader("Authorization")
                    if (request.path == "/bookings/42/boarding-code") {
                        val index = codeCalls.incrementAndGet()
                        if (index == 1) return MockResponse().setResponseCode(503).setBody("{}")
                        held.countDown()
                        if (!release.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                        returned.countDown()
                        return MockResponse().setResponseCode(lateStatus).setBody("""{"code":"5678"}""")
                    }
                    val body = when (request.path) {
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/bookings/42/role" -> """{"role":"passenger","status":"$tripStatus","driver_phase":"departed"}"""
                        "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"confirmed","price":700,"pay_method":"cash","pay_amount":400}"""
                        "/trips/42/receipt" -> """{"booking_id":42,"role":"passenger","amount":400}"""
                        else -> "{}"
                    }
                    return MockResponse().setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 45000
        ApiClient.testTrace = { trace += it }
        ApiClient.init(context)
        ApiClient.logout()
        TripPassStore.initStores(plain, secure)
        Outbox.initStores(MemoryDiskPreferences(), null)
        assertTrue(TripPassStore.save(context, pass(42, "1234", "cash", 400)))
        assertTrue(TripPassStore.save(context, pass(99, "other", "sbp", 900)))
    }

    @After fun cleanup() {
        release.countDown()
        compose.runOnIdle {
            mounted.value = false
            if (owner.registry.currentState.isAtLeast(Lifecycle.State.CREATED))
                owner.registry.currentState = Lifecycle.State.DESTROYED
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val logoutBearer = ApiClient.currentToken()?.let { "Bearer $it" }
        ApiClient.logout()
        if (logoutBearer != null) waitFor { requests.contains("POST /auth/logout" to logoutBearer) }
        ApiClient.resetForTest()
        ApiClient.testTrace = null
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        Outbox.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
    }

    private fun pass(id: Int, code: String, method: String, amount: Int) = TripPass.fromJson(JSONObject()
        .put("booking_id", id).put("boarding_code", code).put("price", 700)
        .put("pay_method", method).put("pay_amount", amount))
    private fun disk(id: Int = 42) = secure.restarted().getString("pass_$id", null)
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        condition()
    }
    private fun observeAfterReleasedResponse() {
        release.countDown()
        assertTrue(returned.await(5, TimeUnit.SECONDS))
        // This is a bounded negative observation after the server response, not a coroutine-join claim.
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < until) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }

    private fun startHeldRetry(language: AppLanguage = AppLanguage.Ru, rapid: Boolean = false) {
        mounted.value = true
        compose.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language, LocalLifecycleOwner provides owner) {
                    if (mounted.value) ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                        onBack = { backed++; mounted.value = false }, onTripEnd = { ended++ }, onSos = {})
                }
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        waitFor { trace.any { it.contains("ВЫШЛИ из вызова GET /bookings/42/details успешно") } }
        val ba = language == AppLanguage.Ba
        val error = if (ba) "Кодты йөкләп булманы" else "Не удалось загрузить код"
        compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
        val retry = if (ba) "Кодты яңынан йөкләргә" else "Повторить загрузку кода"
        if (rapid) compose.onNodeWithText(retry).performTouchInput { click(); advanceEventTime(50); click() }
        else compose.onNodeWithText(retry).performClick()
        waitFor { held.count == 0L }
        compose.onNodeWithText(if (ba) "Код йөкләнә" else "Код загружается")
            .performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(retry).assertCountEquals(0)
        assertEquals("Only the initial refused GET and one held retry", 2, codeCalls.get())
        assertEquals("1234", JSONObject(requireNotNull(disk())).getString("boarding_code"))
    }

    private fun assertNoRepeatedHistoryOrTripPost() {
        assertEquals(2, codeCalls.get())
        assertEquals(1, requests.count { it.first == "GET /bookings/42/messages" })
        assertFalse(requests.any { it.first.startsWith("POST /bookings/") })
    }
    private fun successfulRetry(language: AppLanguage, rapid: Boolean = false) {
        startHeldRetry(language, rapid)
        val sibling = disk(99)
        release.countDown()
        waitFor { JSONObject(requireNotNull(disk())).optString("boarding_code") == "5678" }
        compose.onNodeWithText("5678").performScrollTo().assertIsDisplayed()
        val saved = JSONObject(requireNotNull(disk()))
        assertEquals("cash", saved.getString("pay_method"))
        assertEquals(400, saved.getInt("pay_amount"))
        assertEquals(sibling, disk(99))
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertEquals("5678", TripPassStore.load(context, 42)?.boardingCode)
        assertNoRepeatedHistoryOrTripPost()
    }

    @Test fun heldRetryKeepsOldDiskCodeUntilSuccessRu() = successfulRetry(AppLanguage.Ru)
    @Test fun heldRetryKeepsOldDiskCodeUntilSuccessBa() = successfulRetry(AppLanguage.Ba)
    @Test fun twoRapidPointerTapsProduceOneCodeRetry() = successfulRetry(AppLanguage.Ru, rapid = true)

    @Test fun lateSuccessfulRetryCannotReplaceNewAccountSnapshot() = switchAccount(200)
    @Test fun lateRejectedRetryCannotOfferOldScreenAnotherRequestAsNewAccount() = switchAccount(503)
    private fun switchAccount(response: Int) {
        ApiClient.saveToken("local-code-account-A")
        startHeldRetry()
        assertTrue(requests.contains("GET /bookings/42/boarding-code" to "Bearer local-code-account-A"))
        compose.runOnIdle { ApiClient.logout(); ApiClient.saveToken("local-code-account-B") }
        plain = MemoryDiskPreferences(); secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, secure)
        assertTrue(TripPassStore.save(context, pass(42, "B-only", "sbp", 900)))
        assertTrue(TripPassStore.save(context, pass(99, "B-other", "cash", 0)))
        val own = disk(); val sibling = disk(99)
        lateStatus = response
        observeAfterReleasedResponse()
        assertEquals(own, disk())
        assertEquals(sibling, disk(99))
        compose.onAllNodesWithText("5678").assertCountEquals(0)
        compose.onAllNodesWithText("Повторить загрузку кода").assertCountEquals(0)
        assertFalse(requests.any { it.first == "GET /bookings/42/boarding-code" && it.second == "Bearer local-code-account-B" })
        assertNoRepeatedHistoryOrTripPost()
    }

    @Test fun lateRetryAfterRemoteDoneDoesNotRestoreDeletedPassport() = terminal("done")
    @Test fun lateRetryAfterRemoteCancellationDoesNotRestoreDeletedPassport() = terminal("cancelled")
    private fun terminal(value: String) {
        startHeldRetry()
        val sibling = disk(99)
        // Pause/resume the actual lifecycle poll while keeping the independent retry coroutine mounted.
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        tripStatus = value
        val before = requests.count { it.first == "GET /bookings/42/role" }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        waitFor { requests.count { it.first == "GET /bookings/42/role" } > before && disk() == null &&
            (value != "cancelled" || ended == 1) }
        if (value == "done") compose.onNodeWithText("Поездка завершена").assertIsDisplayed()
        else assertEquals(1, ended)
        observeAfterReleasedResponse()
        assertNull(disk())
        if (value == "cancelled") assertEquals("Late code must not repeat the terminal callback", 1, ended)
        assertEquals(sibling, disk(99))
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertNull(TripPassStore.load(context, 42))
        assertEquals("other", TripPassStore.load(context, 99)?.boardingCode)
        compose.onAllNodesWithText("5678").assertCountEquals(0)
        assertNoRepeatedHistoryOrTripPost()
    }

    @Test fun backDisposesHeldRetryWithoutChangingTheDiskSnapshot() {
        startHeldRetry()
        val own = disk(); val sibling = disk(99)
        compose.onNodeWithContentDescription("Назад").performClick()
        assertEquals(1, backed)
        observeAfterReleasedResponse()
        assertEquals(own, disk())
        assertEquals(sibling, disk(99))
        compose.onAllNodesWithText("Моя поездка").assertCountEquals(0)
        assertNoRepeatedHistoryOrTripPost()
    }
}
