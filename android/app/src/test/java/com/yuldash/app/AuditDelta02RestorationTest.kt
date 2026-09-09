package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.StateRestorationTester
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.v2.runComposeUiTest
import com.yuldash.app.data.InstantOrderDto
import kotlinx.coroutines.awaitCancellation
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class AuditDelta02RestorationTest {
    private fun order(role: String, status: String = "done") = InstantOrderDto(
        id = 7202,
        status = status,
        role = role,
        fromLat = 54.0,
        fromLng = 55.0,
        toLat = 54.1,
        toLng = 55.1,
        fromText = "Тест А",
        toText = "Тест Б",
        category = "standard",
        priceEstimate = 300,
        priceFinal = 300,
        distanceKm = 8.0,
        etaMin = 20.0,
        driverId = 2,
        offerExpiresAt = null,
        cancelBy = "",
        cancelReason = "",
        surgeK = 1.0,
        waitingStartedAt = null,
        waitingFeeKop = 0,
        cancelFeeKop = 0,
        noShow = false,
        waitFreeMin = 5,
        waitFeeRubPerMin = 5,
        noShowAt = null,
        cancelFeeNowKop = 0,
        passengerRating = null,
        passengerTrips = 0,
        driverName = "Водитель",
        driverCar = "Машина",
        driverVerified = false,
        driverRating = 5.0,
        driverPhone = "",
        passengerName = "Пассажир",
        passengerPhone = "",
        driverGrossKop = 30_000,
        driverNetKop = 27_000,
        passengerPriceKop = 30_000,
    )

    private fun ComposeUiTest.waitForStart(starts: AtomicInteger) {
        waitUntil(timeoutMillis = 5_000) { starts.get() == 1 }
    }

    @Test
    fun `passenger rating becomes available after state restoration cancels request`() = runComposeUiTest {
        val starts = AtomicInteger()
        val restoration = StateRestorationTester(this)
        restoration.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TaxiPassengerCompletedScreen(
                    order = order("passenger"),
                    onClose = {},
                    onNewOrder = {},
                    onOpenReceipt = {},
                    onOpenChat = {},
                    rateOrder = { _, _, _ -> starts.incrementAndGet(); awaitCancellation() },
                )
            }
        }

        onNodeWithContentDescription("5 звёзд", useUnmergedTree = true)
            .onParent()
            .assertHasClickAction()
            .performClick()
        mainClock.advanceTimeBy(500)
        awaitIdle()
        onNodeWithText("Отправить оценку").performScrollTo().assertIsEnabled().performClick()
        waitForStart(starts)

        restoration.emulateSaveAndRestore()

        onNodeWithText("Отправить оценку").assertIsEnabled()
    }

    @Test
    fun `passenger lost item becomes available after state restoration cancels request`() = runComposeUiTest {
        val starts = AtomicInteger()
        val restoration = StateRestorationTester(this)
        restoration.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TaxiPassengerCompletedScreen(
                    order = order("passenger"),
                    onClose = {},
                    onNewOrder = {},
                    onOpenReceipt = {},
                    onOpenChat = {},
                    openLostItem = { starts.incrementAndGet(); awaitCancellation() },
                )
            }
        }

        onNodeWithText("Забыл вещь?").performScrollTo().performClick()
        waitForStart(starts)

        restoration.emulateSaveAndRestore()

        onNodeWithText("Забыл вещь?").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `driver rating becomes available after state restoration cancels request`() = runComposeUiTest {
        val starts = AtomicInteger()
        val restoration = StateRestorationTester(this)
        restoration.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TaxiDriverCompletedScreen(
                    order = order("driver"),
                    onReturnToLine = {},
                    onShiftFinished = {},
                    onOpenReceipt = {},
                    rateOrder = { _, _, _ -> starts.incrementAndGet(); awaitCancellation() },
                )
            }
        }

        onNodeWithContentDescription("5 звёзд", useUnmergedTree = true)
            .onParent()
            .performScrollTo()
            .assertHasClickAction()
            .performClick()
        mainClock.advanceTimeBy(500)
        awaitIdle()
        onNodeWithText("Отправить оценку").performScrollTo().assertIsEnabled().performClick()
        waitForStart(starts)

        restoration.emulateSaveAndRestore()

        onNodeWithText("Отправить оценку").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `completed driver shift exit becomes available after state restoration cancels request`() = runComposeUiTest {
        val starts = AtomicInteger()
        val restoration = StateRestorationTester(this)
        restoration.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TaxiDriverCompletedScreen(
                    order = order("driver"),
                    onReturnToLine = {},
                    onShiftFinished = {},
                    onOpenReceipt = {},
                    finishShift = { starts.incrementAndGet(); awaitCancellation() },
                )
            }
        }

        onNodeWithText("Завершить смену").performClick()
        waitForStart(starts)

        restoration.emulateSaveAndRestore()

        onNodeWithText("Завершить смену").assertExists().assertIsEnabled()
    }

    @Test
    fun `cancelled driver shift exit becomes available after state restoration cancels request`() = runComposeUiTest {
        val starts = AtomicInteger()
        val restoration = StateRestorationTester(this)
        restoration.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TaxiDriverCancelledScreen(
                    order = order("driver", status = "cancelled"),
                    onReturnToLine = {},
                    onShiftFinished = {},
                    finishShift = { starts.incrementAndGet(); awaitCancellation() },
                )
            }
        }

        onNodeWithText("Завершить смену").performClick()
        waitForStart(starts)

        restoration.emulateSaveAndRestore()

        onNodeWithText("Завершить смену").assertExists().assertIsEnabled()
    }
}
