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
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.TripPassStore
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

/**
 * Приёмка 18.10.2026, находка 2: пассажир ждёт на экране «Детали поездки», водитель подтверждает —
 * а экран так и пишет «Ждём ответа водителя», пока не придёт пуш (пуши на телефоне ещё не проверены).
 * Пока бронь ждёт, экран сам тихо перечитывает детали.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingPendingRefreshTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(false)
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var serverStatus = "pending"
    private val phone = "+7 900 111-22-33"
    private val statuses = CopyOnWriteArrayList<String>()   // что экран сообщил наверх (YuldashApp.selectedBookingStatus)

    @Before fun setup() {
        ApiClient.resetForTest()
        owner.registry.currentState = Lifecycle.State.RESUMED
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("${request.method} ${request.path}")
                    val body = when (request.path) {
                        "/bookings/42/details" -> details()
                        "/bookings/42/boarding-code" -> """{"code":"4821"}"""
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        owner.registry.currentState = Lifecycle.State.DESTROYED
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
    }

    private fun details(): String {
        val open = serverStatus == "confirmed"
        return JSONObject().put("booking_id", 42).put("ride_id", 17).put("role", "passenger")
            .put("status", serverStatus).put("contact_unlocked", open)
            .put("from_city", "Город А").put("to_city", "Город Б").put("depart_at", "2031-02-03T09:00:00")
            .put("driver_name", "Айдар").put("driver_car", "Lada Vesta").put("price", 700).put("seats", 1)
            .put("driver_phone", if (open) phone else "").put("pickup", if (open) "Остановка «Школа»" else "")
            .toString()
    }

    private fun render(status: String) {
        mounted.value = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru, LocalLifecycleOwner provides owner) {
                BookingScreen(
                    ride = Ride(id = "17", from = "Город А", to = "Город Б", time = "завтра, 09:00",
                        driver = "Айдар", car = "Lada Vesta", price = 700, seats = 1,
                        rating = 4.9, verified = true, boosted = false),
                    bookingId = 42, ads = emptyList(), adStats = emptyMap(), onBack = {},
                    onMessage = {}, onAdImpression = {}, onAdClick = {}, bookingStatus = status,
                    onBookingStatusChange = { statuses += it },
                    onConfirmRide = { _, _, _, _, _ -> },
                )
            }
        }
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requests.contains("GET /bookings/42/details") &&
                compose.onAllNodesWithText("Обновляем подтверждение поездки").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun detailsCalls() = requests.count { it == "GET /bookings/42/details" }

    @Test fun waitingPassengerSeesConfirmationWithoutPush() {
        render("pending")
        compose.onNodeWithText("Ждём ответа водителя").assertIsDisplayed()
        compose.onAllNodesWithText(phone).assertCountEquals(0)

        serverStatus = "confirmed"   // водитель нажал «Подтвердить», пуш до телефона не дошёл
        pump(15_000)
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            statuses.lastOrNull() == "confirmed" && TripPassStore.load(context, 42)?.driverPhone == phone
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(phone))
        compose.onNodeWithText(phone).assertIsDisplayed()
        compose.onAllNodesWithText("Ждём ответа водителя").assertCountEquals(0)
        compose.onNodeWithText("Открыть поездку").assertIsDisplayed()
        assertEquals("Наверх уходит свежий статус — по нему открывается «Моя поездка»", "confirmed", statuses.last())
        // Подтвердили — паспорт для трассы без связи пишется, как при обычном открытии.
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            TripPassStore.load(context, 42)?.driverPhone == phone
        }
        // Подтвердили — больше не опрашиваем.
        val after = detailsCalls()
        pump(40_000)
        assertEquals(after, detailsCalls())
    }

    @Test fun confirmedBookingIsNotPolled() {
        serverStatus = "confirmed"
        render("confirmed")
        pump(40_000)
        assertEquals(1, detailsCalls())
    }

    @Test fun waitingBookingIsNotPolledInBackgroundAndResumesWhenVisible() {
        render("pending")
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        val before = detailsCalls()
        serverStatus = "confirmed"
        pump(40_000)
        assertEquals("Свёрнутый экран не опрашивает сервер", before, detailsCalls())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        pump(15_000)
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            statuses.lastOrNull() == "confirmed"
        }
    }

    @Test fun waitingBookingDoesNotPollAfterSessionChanges() {
        render("pending")
        compose.runOnIdle { ApiClient.saveToken("local-account-B") }
        val before = detailsCalls()
        serverStatus = "confirmed"
        pump(40_000)
        assertEquals("Старая бронь не запрашивается под новым входом", before, detailsCalls())
        assertFalse(statuses.contains("confirmed"))
    }

    // У Compose и Android разные часы: delay в repeatOnLifecycle требует продвижения обоих.
    private fun pump(millis: Long) {
        repeat((millis / 100).toInt()) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(5)
        }
        compose.waitForIdle()
    }
}
