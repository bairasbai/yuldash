package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Полноэкранный живой рендер варианта B «доверие прежде всего» на настоящей MapKit-карте. */
@RunWith(AndroidJUnit4::class)
class RideshareBookingInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bookingOptionB_showsDriverTrustRouteAndFixedActions() {
        render()

        composeRule.onNodeWithText("Детали поездки").assertIsDisplayed()
        composeRule.onNodeWithText("Ринат Хабиров").assertIsDisplayed()
        composeRule.onNodeWithText("4,9").assertIsDisplayed()
        composeRule.onNodeWithText("Проверен").assertIsDisplayed()
        composeRule.onNodeWithText("128 поездок").assertIsDisplayed()
        composeRule.onNodeWithText("Написать").assertIsDisplayed()
        composeRule.onNodeWithText("Забронировать место").assertIsDisplayed()
        composeRule.waitForIdle()
        holdIfRequested()
    }

    @Test
    fun bookingOptionB_submitsRealSelectionFromFixedAction() {
        var called = false
        var method = ""
        var amount: Int? = null
        render(onConfirm = { pickedMethod, pickedAmount, _, _, _ ->
            called = true
            method = pickedMethod
            amount = pickedAmount
        })

        composeRule.onNodeWithText("Забронировать место").performClick()
        assertTrue(called)
        assertEquals("negotiate", method)
        assertEquals(450, amount)
    }

    @Test
    fun bookingOptionB_keepsBashkirCoreCopyVisible() {
        render(language = AppLanguage.Ba)

        composeRule.onNodeWithText("Сәфәр тураһында").assertIsDisplayed()
        composeRule.onNodeWithText("Тикшерелгән").assertIsDisplayed()
        composeRule.onNodeWithText("Яҙырға").assertIsDisplayed()
        composeRule.onNodeWithText("Урынды бронләү").assertIsDisplayed()
    }

    private fun render(
        language: AppLanguage = AppLanguage.Ru,
        onConfirm: (String, Int?, Boolean, String, String) -> Unit = { _, _, _, _, _ -> },
    ) {
        composeRule.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    BookingScreen(
                        ride = previewRide(),
                        bookingId = null,
                        ads = emptyList(),
                        adStats = emptyMap(),
                        onBack = {},
                        onMessage = {},
                        onAdImpression = {},
                        onAdClick = {},
                        onConfirmRide = onConfirm,
                    )
                }
            }
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

    private fun previewRide() = Ride(
        id = "booking-b-preview",
        from = "Баймак",
        to = "Сибай",
        time = "Сегодня, 18:30",
        timeBa = "Бөгөн, 18:30",
        driver = "Ринат Хабиров",
        driverTrips = 128,
        driverSince = "2023-04",
        car = "Lada Vesta · A123AA 102",
        price = 450,
        seats = 2,
        rating = 4.9,
        verified = true,
        boosted = false,
    )
}
