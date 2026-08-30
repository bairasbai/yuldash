package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.InstantOrderDto
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Живой полноэкранный рендер водительского оффера A + доверие C на настоящей MapKit-карте. */
@RunWith(AndroidJUnit4::class)
class TaxiIncomingOfferInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun premiumOffer_showsEverythingNeededBeforeAccept() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                InstantOfferOverlay(
                    order = previewOffer(),
                    onAccept = {},
                    onDecline = {},
                    onSkipTap = {},
                )
            }
        }

        composeRule.onNodeWithText("Входящий заказ").assertIsDisplayed()
        composeRule.onNodeWithText("2,4 км · 6 мин до пассажира").assertIsDisplayed()
        composeRule.onNodeWithText("620 ₽").assertIsDisplayed()
        composeRule.onNodeWithText("Пассажир ★ 4,9").assertIsDisplayed()
        composeRule.onNodeWithText("Пропустить").assertIsDisplayed()
        composeRule.onNodeWithText("Принять").assertIsDisplayed()
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

    private fun previewOffer() = InstantOrderDto(
        id = 91_001,
        status = "offered",
        role = "driver",
        fromLat = 52.5931,
        fromLng = 58.3186,
        toLat = 52.6470,
        toLng = 58.4210,
        fromText = "ул. Ленина, 12",
        toText = "просп. Октября, 48",
        category = "comfort",
        paymentMethod = "cash",
        priceEstimate = 700,
        priceFinal = null,
        ridePrice = 700,
        pickupFeeKop = 0,
        pickupKm = 0.0,
        offerPickupKm = 2.4,
        offerPickupEtaMin = 6,
        distanceKm = 9.8,
        etaMin = 18.0,
        driverId = null,
        offerExpiresAt = Instant.now().plusSeconds(30).toString(),
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
        passengerRating = 4.9,
        passengerTrips = 48,
        driverName = "",
        driverCar = "",
        driverVerified = false,
        driverRating = 0.0,
        driverPhone = "",
        passengerName = "",
        passengerPhone = "",
        driverGrossKop = 70_000,
        driverFeeKop = 8_000,
        driverNetKop = 62_000,
        driverFeePercent = 11.4,
    )
}
