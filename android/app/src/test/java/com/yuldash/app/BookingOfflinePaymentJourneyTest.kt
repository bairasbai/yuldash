package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassStore
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

/** Actual confirmed BookingScreen -> durable snapshot -> offline ActiveTripScreen; no live backend. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingOfflinePaymentJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var plain = MemoryDiskPreferences()
    private var secure = MemoryDiskPreferences()
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<String>()
    private val logoutTokens = CopyOnWriteArrayList<String?>()
    private val mounted = mutableStateOf(false)
    private val active = mutableStateOf(false)
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    @Volatile private var offline = false
    private var method = "cash"
    private var amount: Int? = 400
    @Volatile private var holdCode = false
    private val held = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val returned = CountDownLatch(1)

    @Before fun prepare() {
        ApiClient.resetForTest()
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.serverUnreachable.value = false
        TripPassStore.initStores(plain, secure)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += "${request.method} ${request.path}"
                    if (request.path == "/auth/logout") logoutTokens += request.getHeader("Authorization")
                    if (offline) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    if (holdCode && request.path == "/bookings/42/boarding-code") {
                        held.countDown()
                        if (!release.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                        returned.countDown()
                    }
                    val data = when (request.path) {
                        "/bookings/42/details" -> JSONObject()
                            .put("booking_id", 42).put("ride_id", 17).put("role", "passenger")
                            .put("status", "confirmed").put("contact_unlocked", true)
                            .put("from_city", "Город А").put("to_city", "Город Б")
                            .put("driver_name", "Тестовый водитель").put("price", 700).put("seats", 1)
                            .put("pay_method", method).put("pay_amount", amount ?: JSONObject.NULL)
                        "/bookings/42/boarding-code" -> JSONObject().put("code", "4821")
                        "/bookings/42/messages" -> JSONObject().put("items", org.json.JSONArray())
                        else -> JSONObject()
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(data.toString())
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 700
    }

    @After fun cleanup() {
        release.countDown()
        compose.runOnIdle {
            mounted.value = false
            if (owner.registry.currentState.isAtLeast(Lifecycle.State.CREATED))
                owner.registry.currentState = Lifecycle.State.DESTROYED
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        offline = false
        val logoutBearer = ApiClient.currentToken()?.let { "Bearer $it" }
        ApiClient.logout()
        // Keep the loopback URL until the asynchronous synthetic-token logout has reached it.
        if (logoutBearer != null) waitFor { logoutTokens.contains(logoutBearer) }
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
    }

    private fun mount(language: AppLanguage) {
        mounted.value = true
        compose.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language, LocalLifecycleOwner provides owner) {
                    if (mounted.value) {
                        if (active.value) ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                            onBack = {}, onTripEnd = {}, onSos = {})
                        else BookingScreen(
                            ride = Ride(id = "17", from = "Город А", to = "Город Б", time = "09:00",
                                driver = "Тестовый водитель", car = "", price = 700, seats = 1,
                                rating = 4.9, verified = false, boosted = false),
                            bookingId = 42, bookingStatus = "confirmed", ads = emptyList(), adStats = emptyMap(),
                            onBack = {}, onMessage = {}, onAdImpression = {}, onAdClick = {},
                            onConfirmRide = { _, _, _, _, _ -> active.value = true })
                    }
                }
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        condition()
    }
    private fun show(text: String) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }
    private fun diskJson() = JSONObject(requireNotNull(secure.restarted().getString("pass_42", null)))

    private fun awaitOfflineBanner(language: AppLanguage) {
        val banner = if (language == AppLanguage.Ba) "Офлайн — мәғлүмәт һаҡланған" else "Офлайн — данные сохранены"
        waitFor { ApiClient.serverUnreachable.value && requests.contains("GET /bookings/42/role") }
        // The banner lies after the tall hero and is not composed until the LazyColumn scrolls.
        waitFor { runCatching { show(banner); true }.getOrDefault(false) }
    }

    private fun journey(language: AppLanguage, payMethod: String, payAmount: Int?, expected: String) {
        method = payMethod
        amount = payAmount
        mount(language)
        waitFor { TripPassStore.load(context, 42)?.boardingCode == "4821" &&
            compose.onAllNodesWithText(if (language == AppLanguage.Ba) "Раҫлауҙы яңыртабыҙ" else "Обновляем подтверждение поездки").fetchSemanticsNodes().isEmpty() }
        val openLabel = if (language == AppLanguage.Ba) "Сәфәрҙе асыу" else "Открыть поездку"
        compose.onNodeWithText(openLabel).assertIsDisplayed()
        // Reopen independent durable state before navigating into the offline trip.
        plain = plain.restarted(); secure = secure.restarted()
        TripPassStore.initStores(plain, secure)
        assertEquals(1, requests.count { it == "GET /bookings/42/details" })
        offline = true
        compose.onNodeWithText(openLabel).performClick()
        awaitOfflineBanner(language)
        show(expected) // First assertion proves the actual saved passport UI, not a model field.
        // The separate read-only agreement must not contradict the saved passport.
        show(if (language == AppLanguage.Ba) "Түләү тураһында нисек килешкәнбеҙ" else "Как договорились платить")
        show("${payAmount ?: 700} ₽")
        val methodLabel = expected.substringAfter(" · ")
        compose.onNodeWithText(methodLabel).assertIsDisplayed()
        val disk = diskJson()
        assertEquals("cash/sbp must survive confirmed-save and reopening", payMethod, disk.optString("pay_method"))
        if (payAmount == null) assertTrue(disk.isNull("pay_amount")) else assertEquals(payAmount, disk.optInt("pay_amount", -1))
        assertEquals("Original booking price is separately retained", 700, disk.optInt("price"))
        assertFalse(requests.any { it.startsWith("POST ") })
    }

    @Test fun cashAgreementSurvivesConfirmedSaveAndOfflineRu() = journey(AppLanguage.Ru, "cash", 400, "400 ₽ · Наличными")
    @Test fun cashAgreementSurvivesConfirmedSaveAndOfflineBa() = journey(AppLanguage.Ba, "cash", 400, "400 ₽ · Наличный менән")
    @Test fun explicitZeroSurvivesOfflineRu() = journey(AppLanguage.Ru, "cash", 0, "0 ₽ · Наличными")
    @Test fun explicitZeroSurvivesOfflineBa() = journey(AppLanguage.Ba, "cash", 0, "0 ₽ · Наличный менән")
    @Test fun nullAgreementUsesOriginalAmountWithoutInventingSbp() = journey(AppLanguage.Ru, "negotiate", null, "700 ₽ · Договоримся")
    @Test fun nullAgreementUsesOriginalAmountWithoutInventingSbpBa() = journey(AppLanguage.Ba, "negotiate", null, "700 ₽ · Килешербеҙ")
    @Test fun explicitSbpRemainsSbp() = journey(AppLanguage.Ru, "sbp", 400, "400 ₽ · Перевод по СБП")

    @Test fun refusedConfirmedSaveWarnsThenRetryPersistsAgreementWithoutPost() {
        assertTrue(TripPassStore.save(context, TripPass.fromJson(JSONObject().put("booking_id", 42)
            .put("price", 700).put("pay_method", "sbp").put("pay_amount", 700).put("boarding_code", "old"))))
        secure.failWriteOf = "pass_42"
        mount(AppLanguage.Ru)
        val warning = "Не удалось сохранить поездку без интернета"
        waitFor { runCatching { show(warning); true }.getOrDefault(false) }
        assertEquals("sbp", diskJson().getString("pay_method"))
        assertEquals(700, diskJson().getInt("pay_amount"))
        secure.failWriteOf = null
        show("Сохранить ещё раз")
        compose.onNodeWithText("Сохранить ещё раз").performClick()
        waitFor { diskJson().optInt("pay_amount") == 400 &&
            compose.onAllNodesWithText(warning).fetchSemanticsNodes().isEmpty() }
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertEquals("cash", requireNotNull(TripPassStore.load(context, 42)).toJson().getString("pay_method"))
        assertEquals(400, diskJson().getInt("pay_amount"))
        assertEquals(2, requests.count { it == "GET /bookings/42/boarding-code" })
        assertEquals(1, requests.count { it == "GET /bookings/42/details" })
        assertFalse(requests.any { it.startsWith("POST ") })
    }

    @Test fun heldCodeFromAccountACannotReplaceNewAccountBAgreement() {
        ApiClient.saveToken("local-payment-account-A")
        holdCode = true
        ApiClient.testTimeoutMs = 45000
        mount(AppLanguage.Ru)
        waitFor { held.count == 0L }
        val generationA = ApiClient.queueSessionGeneration()
        compose.runOnIdle {
            ApiClient.logout()
            ApiClient.saveToken("local-payment-account-B")
        }
        assertNotEquals(generationA, ApiClient.queueSessionGeneration())
        plain = MemoryDiskPreferences(); secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, secure)
        assertTrue(TripPassStore.save(context, TripPass.fromJson(JSONObject().put("booking_id", 42)
            .put("price", 900).put("pay_method", "sbp").put("pay_amount", 900).put("boarding_code", "B-only"))))
        release.countDown()
        assertTrue(returned.await(5, TimeUnit.SECONDS))
        // The warning is set only after saveTripPass returns false: observe completion, not a fixed delay.
        waitFor { runCatching { show("Не удалось сохранить поездку без интернета"); true }.getOrDefault(false) }
        compose.waitForIdle()
        assertEquals("sbp", diskJson().getString("pay_method"))
        assertEquals(900, diskJson().getInt("pay_amount"))
        assertEquals("B-only", diskJson().getString("boarding_code"))
        assertFalse(requests.any { it.startsWith("POST /bookings/") })
    }

    @Test fun readonlyAgreementShowsExplicitZero() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PayAgreementBlock(editable = false, method = "cash", amountText = "", summaryAmount = 0,
                    onMethod = {}, onAmount = {})
            }
        }
        compose.onNodeWithText("Наличными").assertIsDisplayed()
        compose.onNodeWithText("0 ₽").assertIsDisplayed()
    }

    @Test fun legacySnapshotDoesNotInventAPaymentMethodRu() = legacy(AppLanguage.Ru, "700 ₽ · Способ оплаты не сохранён")
    @Test fun legacySnapshotDoesNotInventAPaymentMethodBa() = legacy(AppLanguage.Ba, "700 ₽ · Түләү ысулы һаҡланмаған")
    private fun legacy(language: AppLanguage, expected: String) {
        assertTrue(secure.edit().putString("pass_42", JSONObject()
            .put("booking_id", 42).put("price", 700).put("boarding_code", "4821")
            .put("payment_note", "наличными").toString()).commit())
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        offline = true
        active.value = true
        mount(language)
        awaitOfflineBanner(language)
        show(expected)
        val heading = if (language == AppLanguage.Ba) "Түләү тураһында нисек килешкәнбеҙ" else "Как договорились платить"
        val scroll = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        scroll.assertExists()
        // Search the entire lazy list: a non-visible node alone would not prove its absence.
        val missing = runCatching { scroll.performScrollToNode(hasText(heading)) }.exceptionOrNull()
        assertTrue("Legacy snapshot must not invent a second payment agreement: $missing",
            missing is AssertionError && missing.message.orEmpty().contains(heading))
        assertTrue(diskJson().isNull("pay_method"))
        assertTrue(diskJson().isNull("pay_amount"))
        assertFalse(requests.any { it.startsWith("POST ") })
    }
}
