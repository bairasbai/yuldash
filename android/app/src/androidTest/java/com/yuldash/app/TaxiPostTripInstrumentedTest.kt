package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.InstantOrderDto
import com.yuldash.app.data.InstantReceiptDto
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Реальный Compose-рендер утверждённых экранов B + C для QA и снимков с эмулятора. */
@RunWith(AndroidJUnit4::class)
class TaxiPostTripInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun passengerCompleted_showsCalmRatingFirst() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TaxiPassengerCompletedScreen(
                    order = previewOrder(),
                    onClose = {},
                    onNewOrder = {},
                    onOpenReceipt = {},
                    onOpenChat = {},
                    rateOrder = { _, _, _ -> Result.success(Unit) },
                    openLostItem = { Result.success(Unit) },
                )
            }
        }

        composeRule.onNodeWithText("Как прошла поездка?").assertIsDisplayed()
        composeRule.onAllNodesWithText("350 ₽").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("Чек и детали").assertIsDisplayed()
        composeRule.waitForIdle()
        holdIfRequested()
    }

    @Test
    fun receipt_showsPremiumDocumentAndRecoveryActions() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TaxiReceiptScreen(
                    orderId = 90_001,
                    onBack = {},
                    initialReceipt = previewReceipt(),
                    loadRemote = false,
                )
            }
        }

        composeRule.onNodeWithText("Детали поездки").assertIsDisplayed()
        composeRule.onAllNodesWithText("350 ₽").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("ул. Ленина, 12").assertIsDisplayed()
        composeRule.onNodeWithText("Сказать «рәхмәт»").assertIsDisplayed()
        composeRule.waitForIdle()
        holdIfRequested()
    }

    private fun holdIfRequested() {
        val holdMs = InstrumentationRegistry.getArguments()
            .getString("holdMs")
            ?.toLongOrNull()
            ?.coerceIn(0L, 60_000L)
            ?: 0L
        Thread.sleep(holdMs)
    }

    private fun previewOrder() = InstantOrderDto(
        id = 90_001,
        status = "done",
        role = "passenger",
        fromLat = 52.5931,
        fromLng = 58.3186,
        toLat = 52.6076,
        toLng = 58.3338,
        fromText = "ул. Ленина, 12",
        toText = "ул. Гагарина, 8",
        category = "standard",
        paymentMethod = "cash",
        priceEstimate = 350,
        priceFinal = 350,
        distanceKm = 3.8,
        etaMin = 0.0,
        driverId = 77,
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
        driverName = "Ильдар",
        driverCar = "Kia Rio",
        driverCarColor = "серебристый",
        driverVerified = true,
        driverRating = 4.9,
        driverPhone = "+7 927 000-00-00",
        passengerName = "Александр",
        passengerPhone = "+7 927 111-11-11",
        driverTrips = 312,
        driverFrom = "Баймак",
        driverPlate = "А123ВС 02",
    )

    private fun previewReceipt() = InstantReceiptDto(
        orderId = 90_001,
        role = "passenger",
        fromText = "ул. Ленина, 12",
        toText = "ул. Гагарина, 8",
        doneAt = "2026-08-30T18:42:00+03:00",
        distanceKm = 3.8,
        amount = 350,
        amountKop = 35_000,
        waitingFeeKop = 0,
        priceKop = 35_000,
        paymentMethod = "cash",
        paid = true,
        driverName = "Ильдар",
        driverVerified = true,
        counterpartyId = 77,
        counterpartyName = "Ильдар",
        ridePrice = 350,
        rideBasePrice = 350,
    )
}
