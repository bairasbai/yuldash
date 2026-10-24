package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.yuldash.app.data.DriverBookingDto
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «Отклонить» бронь у водителя (приёмка 18.10, п.6). Раньше одно касание сразу снимало бронь:
 * промахнулся пальцем мимо «Подтвердить» — и человек уже получил отказ. Теперь сначала вопрос
 * «Отклонить бронь?», а снимается бронь только после «Да, отклонить».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriverDeclineBookingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun pendingCabinet(onReject: (Int) -> Unit, language: AppLanguage = AppLanguage.Ru) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                DriverCabinetContent(
                    online = false,
                    driverRides = emptyList(),
                    driverBookings = listOf(
                        DriverBookingDto(
                            bookingId = 55, passengerName = "Гульнара", passengerRating = null,
                            route = "Сибай → Уфа", status = "pending",
                        )
                    ),
                    ratingText = "—",
                    onToggleOnline = {},
                    onRate = { _, _, done -> done(true) },
                    onCreateRide = {},
                    onVerifyDriver = {},
                    onBoost = {},
                    onRequestsFeed = {},
                    onRejectBooking = onReject,
                )
            }
        }
        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Гульнара"))
    }

    @Test
    fun `one tap on decline only asks and never drops the booking`() {
        val rejected = mutableListOf<Int>()
        pendingCabinet(onReject = { rejected += it })

        composeRule.onNodeWithText("Отклонить").performClick()

        assertEquals(emptyList<Int>(), rejected)
        composeRule.onNodeWithText("Отклонить бронь?").assertIsDisplayed()
        composeRule.onNodeWithText("Гульнара получит уведомление", substring = true).assertIsDisplayed()
    }

    @Test
    fun `yes declines exactly this booking once`() {
        val rejected = mutableListOf<Int>()
        pendingCabinet(onReject = { rejected += it })

        composeRule.onNodeWithText("Отклонить").performClick()
        composeRule.onNodeWithText("Да, отклонить").performClick()

        assertEquals(listOf(55), rejected)
        composeRule.onNodeWithText("Отклонить бронь?").assertDoesNotExist()
    }

    @Test
    fun `keep leaves the booking alone`() {
        val rejected = mutableListOf<Int>()
        pendingCabinet(onReject = { rejected += it })

        composeRule.onNodeWithText("Отклонить").performClick()
        composeRule.onNodeWithText("Не отклонять").performClick()

        assertEquals(emptyList<Int>(), rejected)
        composeRule.onNodeWithText("Отклонить бронь?").assertDoesNotExist()
        composeRule.onNodeWithText("Подтвердить").assertIsDisplayed()
    }

    @Test
    fun `question is in bashkir too`() {
        pendingCabinet(onReject = {}, language = AppLanguage.Ba)

        composeRule.onNodeWithText("Кире ҡағыу").performClick()

        composeRule.onNodeWithText("Бронде кире ҡағырғамы?").assertIsDisplayed()
    }
}
