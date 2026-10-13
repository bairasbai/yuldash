package com.yuldash.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «Не пришёл» нельзя отметить пассажиру «в пути» (решение 14.10.2026, docs/decisions.md).
 *
 * Пассажир сам нажал «Я сел» — бронь onboard. Раньше у водителя в «Едут с тобой» висела та же
 * красная «Пассажир не явился»: тап отменял бронь человека, который уже в машине. Теперь
 * сервер отвечает 409, а экран вместо кнопки объясняет и ведёт в разбор («Сообщить о проблеме»).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoShowOnboardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `no-show only for a confirmed booking`() {
        assertTrue(noShowAllowed("confirmed"))
        assertFalse(noShowAllowed("onboard"))
        assertFalse(noShowAllowed("done"))
        assertFalse(noShowAllowed("cancelled"))
    }

    @Test
    fun `onboard booking offers a dispute instead of no-show`() {
        composeRule.setContent {
            NoShowButton(bookingId = 41, bookingStatus = "onboard", passengerId = 7, passengerName = "Айгуль")
        }
        composeRule.onNodeWithText("Пассажир не явился").assertDoesNotExist()
        composeRule.onNodeWithText("Сообщить о проблеме").assertIsDisplayed()
        composeRule.onNodeWithText("Пассажир отметил «Я сел»", substring = true).assertIsDisplayed()
    }

    @Test
    fun `confirmed booking keeps the no-show button`() {
        composeRule.setContent {
            NoShowButton(bookingId = 42, bookingStatus = "confirmed", passengerId = 7, passengerName = "Айгуль")
        }
        composeRule.onNodeWithText("Пассажир не явился").assertIsDisplayed()
        composeRule.onNodeWithText("Сообщить о проблеме").assertDoesNotExist()
    }

    @Test
    fun `old server without passenger id still explains and never shows no-show`() {
        composeRule.setContent {
            NoShowButton(bookingId = 43, bookingStatus = "onboard", passengerId = 0, passengerName = "")
        }
        composeRule.onNodeWithText("Пассажир не явился").assertDoesNotExist()
        composeRule.onNodeWithText("Пассажир отметил «Я сел»", substring = true).assertIsDisplayed()
    }
}
