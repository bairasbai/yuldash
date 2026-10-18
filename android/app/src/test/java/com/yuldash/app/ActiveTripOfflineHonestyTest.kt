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
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.TripPass
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** Real screen and loopback transport: an unknown booking cannot become "confirmed" on failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripOfflineHonestyTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(true)
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var detailsAvailable = false
    @Volatile private var receiptAvailable = false
    private var openedReceipt = 0
    private var backCalls = 0
    private lateinit var server: MockWebServer

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.logout() // No authenticated WebSocket or external map in this fixture.
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    requests += "${request.method} $path"
                    if (request.method != "GET") return MockResponse().setResponseCode(405)
                    val body = when (path) {
                        // The independent role poll remains unavailable while details are retried.
                        "/bookings/42/role" -> return MockResponse().setResponseCode(503).setBody("{}")
                        "/bookings/42/details" -> {
                            if (!detailsAvailable) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                            """{"booking_id":42,"role":"driver","status":"done","from_city":"Город А","to_city":"Город Б"}"""
                        }
                        "/bookings/42/boarding-code" -> """{"code":"5678"}"""
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/trips/42/receipt" -> {
                            if (!receiptAvailable) return MockResponse().setResponseCode(503).setBody("{}")
                            """{"booking_id":42,"role":"driver","from_city":"Город А","to_city":"Город Б","counterparty_name":"Тестовый пассажир","amount":400,"paid":true,"my_stars":3}"""
                        }
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 2000
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
    }

    private fun mount(language: AppLanguage, hint: ActiveTripTerminalHint? = null) {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides language) {
                ActiveTripScreen(null, emptyList(), 42, onBack = { backCalls++ }, onTripEnd = {}, onSos = {},
                    onOpenReceipt = { openedReceipt = it }, terminalHint = hint)
            }
        }
    }

    private fun awaitText(text: String) = compose.waitUntil(15000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun assertNoInventedActiveTrip() {
        listOf("Поездка подтверждена", "Сәфәр раҫланды", "Подтверждено", "Раҫланды",
            "Код посадки", "Ултырыу коды", "5678", "Позвонить", "Шылтыратырға",
            "Водитель отметит посадку", "Я выехал", "Подъезжаю").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(0)
        }
        assertTrue("Reading a failed booking must not change it", requests.all { it.startsWith("GET ") })
    }

    private fun unknownFailure(language: AppLanguage) {
        mount(language)
        val failed = if (language == AppLanguage.Ru) "Не удалось загрузить данные поездки" else "Сәфәр мәғлүмәттәрен йөкләп булманы"
        awaitText(failed)
        compose.onNodeWithText(failed).assertIsDisplayed()
        compose.onNodeWithText(if (language == AppLanguage.Ru) "Повторить загрузку данных" else "Мәғлүмәттәрҙе яңынан йөкләргә").assertIsEnabled()
        assertNoInventedActiveTrip()
        assertNull(TripPassStore.load(context, 42))
    }

    @Test fun unknownNetworkFailureIsHonestRussian() = unknownFailure(AppLanguage.Ru)
    @Test fun unknownNetworkFailureIsHonestBashkir() = unknownFailure(AppLanguage.Ba)

    private fun recoverUnknown(language: AppLanguage) {
        unknownFailure(language)
        detailsAvailable = true
        receiptAvailable = true
        compose.onNodeWithText(if (language == AppLanguage.Ru) "Повторить загрузку данных" else "Мәғлүмәттәрҙе яңынан йөкләргә").performClick()
        awaitText(if (language == AppLanguage.Ru) "Поездка завершена" else "Сәфәр тамамланды")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requests.any { it == "GET /trips/42/receipt" }
        }
        assertNoInventedActiveTrip()
        assertTrue(requests.any { it == "GET /trips/42/receipt" })
        assertNull(TripPassStore.load(context, 42))
    }

    @Test fun detailsRetryRecoversCompletionWithoutSuccessfulRolePollRussian() = recoverUnknown(AppLanguage.Ru)
    @Test fun detailsRetryRecoversCompletionWithoutSuccessfulRolePollBashkir() = recoverUnknown(AppLanguage.Ba)

    private fun knownCompletion(language: AppLanguage) {
        mount(language, knownTerminalTripHint(42, "done", "driver"))
        val completed = if (language == AppLanguage.Ru) "Поездка завершена" else "Сәфәр тамамланды"
        val retry = if (language == AppLanguage.Ru) "Повторить" else "Ҡабатлау"
        awaitText(completed)
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runCatching {
                compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasTestTag("rideshareReceiptRetry"))
                compose.onNodeWithTag("rideshareReceiptRetry").assertIsDisplayed()
            }.isSuccess
        }
        assertNoInventedActiveTrip()
        assertFalse("Known completion must not load active trip details", requests.any { it == "GET /bookings/42/details" })
        receiptAvailable = true
        compose.onNodeWithTag("rideshareReceiptRetry").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requests.count { it == "GET /trips/42/receipt" } >= 2 &&
                compose.onAllNodesWithText(retry).fetchSemanticsNodes().isEmpty()
        }
        val receipt = if (language == AppLanguage.Ru) "Квитанция" else "Сәфәр квитанцияһы"
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(receipt))
        compose.onNodeWithText(receipt).performClick()
        assertEquals(42, openedReceipt)
        assertNoInventedActiveTrip()
    }

    @Test fun knownCompletionStaysClosedAndReceiptRetryWorksRussian() = knownCompletion(AppLanguage.Ru)
    @Test fun knownCompletionStaysClosedAndReceiptRetryWorksBashkir() = knownCompletion(AppLanguage.Ba)

    @Test fun terminalHintCannotApplyToAnotherBooking() {
        mount(AppLanguage.Ru, knownTerminalTripHint(99, "done", "driver"))
        awaitText("Не удалось загрузить данные поездки")
        compose.onAllNodesWithText("Поездка завершена").assertCountEquals(0)
        assertNoInventedActiveTrip()
    }

    @Test fun logoutRemovesKnownCompletedScreenAndDoesNotContinueIt() {
        mount(AppLanguage.Ru, knownTerminalTripHint(42, "done", "driver"))
        awaitText("Поездка завершена")
        compose.runOnIdle { ApiClient.logout() }
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            backCalls > 0 && compose.onAllNodesWithText("Поездка завершена").fetchSemanticsNodes().isEmpty()
        }
        assertNoInventedActiveTrip()
    }

    @Test fun knownCompletionRemovesOnlyItsOwnOfflinePassport() {
        val pass = TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "1234"))
        assertTrue(TripPassStore.save(context, pass))
        assertTrue(TripPassStore.save(context, pass.copy(bookingId = 99, boardingCode = "unrelated")))
        mount(AppLanguage.Ru, knownTerminalTripHint(42, "done", "driver"))
        awaitText("Поездка завершена")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            TripPassStore.load(context, 42) == null
        }
        assertEquals("unrelated", TripPassStore.load(context, 99)?.boardingCode)
        assertNoInventedActiveTrip()
    }

    @Test fun knownCancellationDoesNotShowBoardingOrConfirmedTripRussian() = knownCancellation(AppLanguage.Ru)
    @Test fun knownCancellationDoesNotShowBoardingOrConfirmedTripBashkir() = knownCancellation(AppLanguage.Ba)

    private fun knownCancellation(language: AppLanguage) {
        mount(language, knownTerminalTripHint(42, "cancelled", "passenger"))
        awaitText(if (language == AppLanguage.Ru) "Поездка отменена" else "Сәфәр кире алынды")
        assertNoInventedActiveTrip()
        assertNull(TripPassStore.load(context, 42))
    }

    @Test fun hintRejectsMissingBookingUnknownRoleAndNonTerminalStatus() {
        assertNull(knownTerminalTripHint(null, "done", "driver"))
        assertNull(knownTerminalTripHint(0, "done", "driver"))
        assertNull(knownTerminalTripHint(42, "confirmed", "driver"))
        assertNull(knownTerminalTripHint(42, "", "driver"))
        assertNull(knownTerminalTripHint(42, "done", ""))
        assertEquals(42, knownTerminalTripHint(42, "done", "passenger")?.bookingId)
    }
}
