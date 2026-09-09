package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Регрессия DELTA06: действия внизу должны соответствовать настоящему статусу брони. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditDelta06BookingStatusTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val ride = Ride(
        id = "17",
        from = "Город А",
        to = "Город Б",
        time = "завтра, 09:00",
        driver = "Айрат",
        car = "Lada Vesta",
        price = 700,
        seats = 2,
        rating = 4.9,
        verified = true,
        boosted = false,
    )

    private fun show(
        status: String,
        canOpen: Boolean,
        onCancel: () -> Unit = {},
        onFindAnother: () -> Unit = {},
        onOpen: () -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BookingScreen(
                    ride = ride,
                    bookingId = 91,
                    ads = emptyList(),
                    adStats = emptyMap(),
                    onBack = {},
                    onMessage = {},
                    onAdImpression = {},
                    onAdClick = {},
                    canOpenActiveTrip = canOpen,
                    bookingStatus = status,
                    onCancelBooking = onCancel,
                    onFindAnotherRide = onFindAnother,
                    onConfirmRide = { _, _, _, _, _ -> onOpen() },
                )
            }
        }
    }

    @Test
    fun pendingOffersCancellationOnlyAfterConfirmation() {
        var cancelCalls = 0
        show(status = "pending", canOpen = false, onCancel = { cancelCalls++ })

        composeRule.onNodeWithText("Ждём ответа водителя").assertIsDisplayed()
        composeRule.onNodeWithText("Отменить бронь").assertIsDisplayed().performClick()
        assertEquals(0, cancelCalls)

        composeRule.onNodeWithText("Да, отменить").assertIsDisplayed().performClick()
        assertEquals(1, cancelCalls)
    }

    @Test
    fun cancelledIsHonestAndStartsAnotherSearch() {
        var findCalls = 0
        show(status = "cancelled", canOpen = false, onFindAnother = { findCalls++ })

        composeRule.onNodeWithText("Бронь отменена").assertIsDisplayed()
        composeRule.onNodeWithText("Ждём водителя").assertDoesNotExist()
        composeRule.onNodeWithText("Найти другую поездку").assertIsDisplayed().performClick()
        assertEquals(1, findCalls)
    }

    @Test
    fun confirmedStillOpensTrip() {
        var openCalls = 0
        show(status = "confirmed", canOpen = true, onOpen = { openCalls++ })

        composeRule.onNodeWithText("Открыть поездку").assertIsDisplayed().performClick()
        assertEquals(1, openCalls)
    }
}
