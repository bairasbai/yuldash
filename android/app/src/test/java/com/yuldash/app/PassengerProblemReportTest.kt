package com.yuldash.app

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.BoardingResult
import com.yuldash.app.data.TipInfoDto
import com.yuldash.app.data.TripReceiptDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «Сообщить о проблеме» не пропадает тогда, когда оно нужнее всего (приёмка PR174).
 *
 * Водитель отметил «Сел» — у пассажира «в пути» отмены нет (сервер: `onboard_no_cancel`), и если он
 * не садился, разбор — единственный путь. Водитель мог и сразу закрыть поездку — тогда путь
 * остаётся на экране завершённой поездки, пока сервер держит окно (`problem_report_sec`, 6 часов).
 * Тип спора у пассажира сразу «Водитель не приехал». Водителю на экране поездки после окна
 * «Отменить» — разбор «Если пассажир на самом деле не садился» (Q4), как в кабинете.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PassengerProblemReportTest {
    @get:Rule val compose = createComposeRule()
    private val noop: (BoardingResult) -> Unit = {}

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")   // диалог разбора живёт только во входе
    }

    @After fun cleanup() {
        ApiClient.resetForTest()
    }

    private fun waitFor(text: String, substring: Boolean = false) = compose.waitUntil(15_000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) { content() }
    }

    private fun receipt(role: String = "passenger", reportSec: Int = 3600, counterparty: Int = 9) = TripReceiptDto(
        bookingId = 42, rideId = 7, role = role, fromCity = "Уфа", toCity = "Бирск", departAt = "",
        seats = 1, amount = 400, payMethod = "cash", paid = true, driverName = "Ринат", driverVerified = true,
        counterpartyName = "Ринат", counterpartyId = counterparty, problemReportSec = reportSec,
    )

    @Test fun boardedPassengerCanStillReportAProblem() {
        val slot = activeTripBoardingSlot("passenger", "onboard", 42, 9, "Ринат", 0, noop)!!
        show { slot() }
        compose.onNodeWithText("Водитель отметил посадку").assertIsDisplayed()
        compose.onNodeWithText("Не садился — сообщи о проблеме.").assertIsDisplayed()
        compose.onNodeWithText("Сообщить о проблеме").performClick()
        waitFor("Водитель не приехал")
        // Тип уже выбран — список свёрнут в одну строку.
        compose.onAllNodesWithText("Нагрубили").assertCountEquals(0)
    }

    @Test fun waitingPassengerOpensTheDisputeWithDriverNoShow() {
        val slot = activeTripBoardingSlot("passenger", "confirmed", 42, 9, "Ринат", 0, noop)!!
        show { slot() }
        compose.onNodeWithText("Водитель отметит посадку").assertIsDisplayed()
        compose.onNodeWithText("Сообщить о проблеме").performClick()
        waitFor("Водитель не приехал")
    }

    @Test fun driverTripScreenOffersTheDisputeAfterTheUndoWindow() {
        val slot = activeTripBoardingSlot("driver", "onboard", 42, 7, "", 0, noop)!!
        show { slot() }
        compose.onNodeWithText("Пассажир в машине").assertIsDisplayed()
        compose.onAllNodesWithText("Отменить ·", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Если пассажир на самом деле не садился", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Сообщить о проблеме").performClick()
        waitFor("Пассажир не вышел")
        // Имени пассажира на экране поездки нет — в тексте разбора «пассажира», а не пустое место.
        waitFor("Мы позовём пассажира объясниться", substring = true)
    }

    @Test fun completedTripKeepsTheReportForThePassenger() {
        show {
            RideshareCompletedScreen(42, null, "passenger", "cash", 400, onClose = {}, onOpenReceipt = {}, onSupport = {},
                loadReceipt = { Result.success(receipt()) },
                loadThanks = { Result.success(TipInfoDto("Ринат", alreadyThanked = false, sbpPhone = null)) })
        }
        compose.waitUntil(15_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithTag("rideshareReceiptLoading").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasTestTag("rideshareProblemReport"))
        compose.onNodeWithText("Не садился в машину?").assertIsDisplayed()
        compose.onNodeWithText("Сообщить о проблеме").performClick()
        waitFor("Водитель не приехал")
    }

    @Test fun afterTripReportFollowsTheServerWindowAndTheRole() {
        assertEquals(9, afterTripReportTo(receipt(), isDriver = false))
        assertEquals("окно сервера вышло", 0, afterTripReportTo(receipt(reportSec = 0), isDriver = false))
        assertEquals("водителю — нет", 0, afterTripReportTo(receipt(role = "driver"), isDriver = true))
        assertEquals("старый сервер без второй стороны", 0, afterTripReportTo(receipt(counterparty = 0), isDriver = false))
        assertEquals("квитанция ещё не пришла", 0, afterTripReportTo(null, isDriver = false))
    }

    @Test fun bashkirCopyForTheBoardedPassenger() {
        val slot = activeTripBoardingSlot("passenger", "onboard", 42, 9, "Ринат", 0, noop)!!
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) { slot() } }
        compose.onNodeWithText("Йөрөтөүсе ултырыуҙы билдәләне").assertIsDisplayed()
        compose.onNodeWithText("Проблема тураһында хәбәр итергә").assertIsDisplayed()
    }
}
