package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassStore
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * Код посадки на экране «Моя поездка»: когда он нужен и когда тревожная плашка «Код не сохранён
 * на телефоне» вообще уместна. Всё найдено на эмуляторе 14.10.2026:
 *  • у водителя плашка висела всегда — копию кода кладут в офлайн-паспорт пассажира, у водителя
 *    его нет и быть не должно;
 *  • пассажиру плашка тоже мерещилась, если открыть бронь из списка «Поездки», минуя экран брони
 *    (паспорт создаётся там) — класть код было некуда;
 *  • после «Я сел» карточка «Код посадки / Назови код водителю» оставалась над «В пути».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoardingCodeVisibilityTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plain = MemoryDiskPreferences()
    private val secure = MemoryDiskPreferences()
    private val mounted = mutableStateOf(false)
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var role = "passenger"
    @Volatile private var bookingStatus = "confirmed"
    private lateinit var server: MockWebServer

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.logout()
        TripPassStore.initStores(plain, secure)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("${request.method} ${request.path}")
                    if (request.method == "POST" && request.path == "/bookings/42/trip-status") {
                        bookingStatus = "onboard"   // сервер записал «Я сел»
                        return json("""{"ok":true}""")
                    }
                    if (request.method != "GET") return MockResponse().setResponseCode(405)
                    return when (request.path) {
                        "/bookings/42/role" -> json("""{"role":"$role","status":"$bookingStatus","driver_phase":""}""")
                        "/bookings/42/messages" -> json("""{"items":[]}""")
                        "/bookings/42/boarding-code" -> json("""{"code":"5678"}""")
                        "/bookings/42/details" -> json("""{"booking_id":42,"role":"$role","status":"$bookingStatus","from_city":"Город А","to_city":"Город Б"}""")
                        else -> MockResponse().setResponseCode(404).setBody("{}")
                    }
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
    }

    @Test fun driverNeverSeesCodeNotSavedWarning() {
        role = "driver"
        openScreen()
        waitForCodeLoaded()
        settle()
        compose.onAllNodesWithText("Код не сохранён на телефоне").assertCountEquals(0)
        compose.onAllNodesWithText("Сохранить код ещё раз").assertCountEquals(0)
    }

    @Test fun passengerWithoutOfflinePassSeesNoWarning() {
        // Бронь открыта из списка «Поездки»: офлайн-паспорта нет, класть код некуда.
        openScreen()
        waitForCodeLoaded()
        settle()
        assertEquals(null, TripPassStore.load(context, 42))
        compose.onAllNodesWithText("Код не сохранён на телефоне").assertCountEquals(0)
    }

    @Test fun passengerWithOfflinePassStillGetsItsCodeSaved() {
        assertTrue(TripPassStore.save(context, TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "1234"))))
        openScreen()
        waitForCodeLoaded()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            TripPassStore.load(context, 42)?.boardingCode == "5678"
        }
        compose.onAllNodesWithText("Код не сохранён на телефоне").assertCountEquals(0)
    }

    @Test fun codeCardIsShownBeforeBoarding() {
        openScreen()
        waitForCodeLoaded()
        assertFalse(compose.onAllNodesWithText("Назови код водителю перед посадкой").fetchSemanticsNodes().isEmpty())
    }

    @Test fun codeCardIsGoneWhenBookingIsAlreadyOnboard() {
        // Перезапуск приложения посреди поездки: сервер говорит «в пути».
        bookingStatus = "onboard"
        openScreen()
        waitUntilRequested("GET /bookings/42/details")
        settle()
        compose.onAllNodesWithText("Код посадки").assertCountEquals(0)
        compose.onAllNodesWithText("Назови код водителю перед посадкой").assertCountEquals(0)
    }

    @Test fun codeCardLeavesAfterISatIsTapped() {
        openScreen()
        waitForCodeLoaded()
        // «Я сел» появляется, когда известна роль, — её приносит отдельный опрос /role, а не цепочка
        // «история → код → детали». Ответ опроса может прийти позже деталей: ждём саму кнопку.
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Я сел").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Я сел")[0].performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Назови код водителю перед посадкой").fetchSemanticsNodes().isEmpty()
        }
        compose.onAllNodesWithText("Код посадки").assertCountEquals(0)
        assertTrue(requests.contains("POST /bookings/42/trip-status"))
    }

    private fun openScreen() {
        mounted.value = true
        compose.setContent {
            if (mounted.value) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                        onBack = {}, onTripEnd = {}, onSos = {})
                }
            }
        }
    }

    private fun waitUntilRequested(request: String) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requests.contains(request)
        }
    }

    /** Код пришёл с сервера и нарисован на экране (карточка «Код посадки» с цифрами). */
    private fun waitForCodeLoaded() {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("5678").fetchSemanticsNodes().isNotEmpty()
        }
        waitUntilRequested("GET /bookings/42/details")
    }

    /** Даём побочным эффектам (запись копии кода на диск) отработать — плашка могла бы появиться только после них. */
    private fun settle() {
        repeat(15) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(100)
        }
    }
}
