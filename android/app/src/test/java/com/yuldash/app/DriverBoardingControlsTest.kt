package com.yuldash.app

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Посадку отмечает ВОДИТЕЛЬ (решение Александра 17.10.2026). Настоящие компоненты карточки и
 * экрана поездки, настоящий сетевой слой (BoardingApi → ApiClient), сервер — подставной.
 *
 *  • водитель: «Сел» рядом с «Не пришёл» → «Пассажир в машине» и «Отменить · 5:00»;
 *    «Отменить» возвращает «Сел»; отказ сервера — его словами;
 *  • пассажир: «Водитель отметит посадку» и «Сообщить о проблеме» (разбор с водителем брони).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriverBoardingControlsTest {
    @get:Rule val compose = createComposeRule()
    private val calls = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer
    @Volatile private var markReply: MockResponse = json("""{"ok":true,"status":"onboard","boarding_undo_sec":300}""")
    @Volatile private var undoReply: MockResponse = json("""{"ok":true,"status":"confirmed","boarding_undo_sec":0}""")

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        ShadowToast.reset()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val line = "${request.method} ${request.path} ${request.body.readUtf8()}"
                    calls += line
                    return when (request.path) {
                        "/bookings/42/driver-status" -> markReply
                        "/bookings/42/boarded/undo" -> undoReply
                        "/bookings/42/no-show" -> json("""{"id":42,"status":"cancelled"}""")
                        else -> json("{}", 404)
                    }
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 2000
    }

    @After fun cleanup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun waitFor(text: String, substring: Boolean = false) = compose.waitUntil(15_000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun card(status: String = "confirmed", undoSec: Int = 0, language: AppLanguage = AppLanguage.Ru) =
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                DriverBoardingControls(bookingId = 42, bookingStatus = status, boardingUndoSec = undoSec,
                    passengerId = 7, passengerName = "Айгуль")
            }
        }

    @Test fun driverMarksBoardingAndSeesTheUndoWindow() {
        card()
        compose.onNodeWithText("Сел").assertIsDisplayed()
        compose.onNodeWithText("Не пришёл").assertIsDisplayed()
        compose.onNodeWithText("Сел").performClick()
        waitFor("Пассажир в машине")
        assertEquals(1, calls.count { it.startsWith("POST /bookings/42/driver-status") })
        assertTrue(calls.single().endsWith("""{"status":"boarded"}"""))
        compose.onAllNodesWithText("Отменить ·", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Не пришёл").assertCountEquals(0)
        // Пока можно отменить, разбор не предлагаем.
        compose.onAllNodesWithText("Сообщить о проблеме").assertCountEquals(0)
    }

    @Test fun undoWithinTheWindowBringsTheBoardingButtonBack() {
        card(status = "onboard", undoSec = 120)
        // Точные «м:сс» проверяет undoClockCountsServerSeconds: часы теста могут уйти на секунду.
        compose.onNodeWithText("Отменить · ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Отменить · ", substring = true).performClick()
        waitFor("Сел")
        assertEquals(listOf("POST /bookings/42/boarded/undo {}"), calls.toList())
        compose.onNodeWithText("Не пришёл").assertIsDisplayed()
        compose.onAllNodesWithText("Пассажир в машине").assertCountEquals(0)
    }

    @Test fun lateUndoShowsTheServerWordsAndOpensTheDispute() {
        undoReply = json("""{"detail":{"ru":"Отменить посадку можно в первые 5 минут.","ba":"Тәүге 5 минутта ғына."}}""", 409)
        card(status = "onboard", undoSec = 300)
        compose.onNodeWithText("Отменить · ", substring = true).performClick()
        waitFor("Сообщить о проблеме")
        assertEquals("Отменить посадку можно в первые 5 минут.", ShadowToast.getTextOfLatestToast())
        compose.onAllNodesWithText("Отменить ·", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Пассажир в машине").assertIsDisplayed()
    }

    @Test fun refusedMarkKeepsTheCardAndExplainsWhy() {
        markReply = json("""{"detail":{"ru":"Посадку можно отметить за 30 минут до выезда — не раньше","ba":"Ултырыуҙы сығыуға 30 минут ҡалғас ҡына билдәләп була"}}""", 409)
        card()
        compose.onNodeWithText("Сел").performClick()
        compose.waitUntil(15_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() != null
        }
        assertEquals("Посадку можно отметить за 30 минут до выезда — не раньше", ShadowToast.getTextOfLatestToast())
        compose.onNodeWithText("Сел").assertIsDisplayed()
        compose.onNodeWithText("Не пришёл").assertIsDisplayed()
    }

    @Test fun noShowReplacesBothButtonsWithTheNote() {
        card()
        compose.onNodeWithText("Не пришёл").performClick()
        waitFor("Отмечена неявка", substring = true)
        compose.onAllNodesWithText("Сел").assertCountEquals(0)
        assertEquals(1, calls.count { it.startsWith("POST /bookings/42/no-show") })
    }

    @Test fun bashkirLabelsForTheDriver() {
        card(language = AppLanguage.Ba)
        compose.onNodeWithText("Ултырҙы").assertIsDisplayed()
        compose.onNodeWithText("Килмәне").assertIsDisplayed()
    }

    @Test fun undoClockCountsServerSeconds() {
        assertEquals("5:00", boardingUndoClock(300))
        assertEquals("4:59", boardingUndoClock(299))
        assertEquals("0:07", boardingUndoClock(7))
        assertEquals("0:00", boardingUndoClock(-3))
    }

    @Test fun passengerWaitsForTheDriverAndCanReportAProblem() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PassengerBoardingWait(bookingId = 42, driverId = 9, driverName = "Ринат")
            }
        }
        compose.onNodeWithText("Водитель отметит посадку").assertIsDisplayed()
        compose.onAllNodesWithText("Я сел").assertCountEquals(0)
        compose.onNodeWithText("Сообщить о проблеме").performClick()
        waitFor("Открыть разбор")
    }

    @Test fun passengerOnOldServerHasNoDisputeButton() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                PassengerBoardingWait(bookingId = 42, driverId = 0, driverName = "")
            }
        }
        compose.onNodeWithText("Йөрөтөүсе ултырыуҙы билдәләр").assertIsDisplayed()
        compose.onAllNodesWithText("Проблема тураһында хәбәр итергә").assertCountEquals(0)
    }

    @Test fun tripScreenSlotFollowsRoleAndStatus() {
        val noop: (com.yuldash.app.data.BoardingResult) -> Unit = {}
        assertTrue(activeTripBoardingSlot("passenger", "confirmed", 42, 9, "Ринат", 0, noop) != null)
        assertTrue(activeTripBoardingSlot("driver", "onboard", 42, 7, "", 120, noop) != null)
        assertTrue(activeTripBoardingSlot("driver", "confirmed", 42, 7, "", 0, noop) == null)
        assertTrue(activeTripBoardingSlot("passenger", "onboard", 42, 9, "Ринат", 0, noop) == null)
        assertTrue("без брони — демо, посадки нет", activeTripBoardingSlot("passenger", "confirmed", null, 0, "", 0, noop) == null)
    }
}
