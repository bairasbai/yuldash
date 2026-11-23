package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
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
                val order = previewOrder()
                TaxiPassengerCompletedScreen(
                    order = order,
                    onClose = {},
                    onNewOrder = {},
                    onOpenReceipt = {},
                    onOpenChat = {},
                    readRating = { id, _ -> check(id == order.id); Result.success(order.copy(canRate = true)) },
                    rateOrder = { _, _, _, _ -> Result.success(Unit) },
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
        check(BuildConfig.DEBUG && BuildConfig.YULDASH_NATIVE_QA_PINNED_API &&
            ApiClient.apiBase() == "http://127.0.0.1:8000") {
            "Receipt session fixture requires the pinned isolated debug build"
        }
        check(!ApiClient.isLoggedIn()) { "Receipt session fixture must start as guest" }
        // An intentionally unsigned, synthetic bearer for the local session guard only.
        val fixtureBearer = "fixture.eyJzdWIiOiI5MDAwMSJ9.unsigned"
        val mounted = mutableStateOf(true)
        val fixtureRoute = mutableStateOf(false)
        var originalFailure: Throwable? = null
        try {
            composeRule.setContent {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    if (mounted.value) key(fixtureRoute.value) {
                        TaxiReceiptScreen(
                            orderId = 90_001,
                            onBack = {},
                            initialReceipt = previewReceipt(),
                            loadRemote = false,
                        )
                    }
                }
            }

            // A supplied private receipt is still hidden without an active session.
            composeRule.onNodeWithText("Детали поездки").assertDoesNotExist()
            composeRule.onNodeWithText("ул. Ленина, 12").assertDoesNotExist()

            ApiClient.saveToken(fixtureBearer)
            check(ApiClient.currentToken() == fixtureBearer &&
                ApiClient.isCurrentSession(ApiClient.queueSessionGeneration()))
            // Model opening a fresh receipt route, not making the guest composition
            // adopt the new generation across an account change.
            composeRule.runOnIdle { fixtureRoute.value = true }
            receiptAssertion(ReceiptStage.HEADER) {
                composeRule.onNodeWithText("Детали поездки").assertIsDisplayed()
            }
            receiptAssertion(ReceiptStage.AMOUNT) {
                composeRule.onAllNodesWithText("350 ₽").onFirst().assertIsDisplayed()
            }
            receiptAssertion(ReceiptStage.ADDRESS) {
                composeRule.onNodeWithText("ул. Ленина, 12").performScrollTo().assertIsDisplayed()
            }
            receiptAssertion(ReceiptStage.THANKS) {
                composeRule.onNodeWithText("Сказать «рәхмәт»").performScrollTo().assertIsDisplayed()
            }
            composeRule.waitForIdle()
            holdIfRequested()
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            try {
                try {
                    composeRule.runOnIdle { mounted.value = false }
                    composeRule.waitForIdle()
                } finally {
                    clearReceiptFixtureSession(fixtureBearer)
                }
            } catch (cleanup: Throwable) {
                val primary = originalFailure
                if (primary == null) throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
    }

    private fun clearReceiptFixtureSession(fixtureBearer: String) {
        try {
            // Normal logout enqueues HTTP only for a nonblank bearer. First remove
            // our synthetic value locally; refuse to touch an unexpected session.
            if (ApiClient.currentToken() == fixtureBearer) ApiClient.saveToken("")
            check(ApiClient.currentToken().isNullOrBlank())
            ApiClient.logout()
            check(!ApiClient.isLoggedIn()) { "Fixture must leave no current session" }
        } finally {
            ApiClient.resetForTest()
        }
    }

    private enum class ReceiptStage { HEADER, AMOUNT, ADDRESS, THANKS }

    private fun receiptAssertion(stage: ReceiptStage, assertion: () -> Unit) {
        try {
            assertion()
        } catch (failure: AssertionError) {
            // Emit one fixed marker only when the assertion group fails. The isolated
            // harness may report the marker, never its private cause or semantics tree.
            throw AssertionError("TF11_RECEIPT_ASSERTION_${stage.name}", failure)
        }
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
