package com.yuldash.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * Раньше «в пути» ставил пассажир своим «Я сел», а у водителя в «Едут с тобой» висела та же
 * красная «Пассажир не явился»: тап отменял бронь человека, который уже в машине. С 17.10.2026
 * посадку отмечает сам водитель («Сел» рядом с «Не пришёл»), но правило то же: «в пути» неявкой
 * не снять. Пока можно — «Отменить» свою отметку; окно вышло — разбор («Сообщить о проблеме»).
 * Сетевые сценарии «Сел» / «Отменить» и «Точно не пришёл?» — DriverBoardingControlsTest.
 *
 * С 18.10.2026 «Не пришёл» доступна не раньше срока от сервера (`no_show_at`: выезд + 15 минут):
 * здесь — чистое правило «открыто ли» с точностью до миллисекунды и подпись вместо кнопки.
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
    fun `onboard booking after the undo window offers a dispute instead of no-show`() {
        composeRule.setContent {
            DriverBoardingControls(bookingId = 41, bookingStatus = "onboard", boardingUndoSec = 0, passengerId = 7, passengerName = "Айгуль")
        }
        composeRule.onNodeWithText("Не пришёл").assertDoesNotExist()
        composeRule.onNodeWithText("Сел").assertDoesNotExist()
        composeRule.onNodeWithText("Пассажир в машине").assertIsDisplayed()
        composeRule.onNodeWithText("Сообщить о проблеме").assertIsDisplayed()
        composeRule.onNodeWithText("Если пассажир на самом деле не садился", substring = true).assertIsDisplayed()
    }

    @Test
    fun `confirmed booking keeps no-show next to the boarding mark`() {
        composeRule.setContent {
            DriverBoardingControls(bookingId = 42, bookingStatus = "confirmed", boardingUndoSec = 0, passengerId = 7, passengerName = "Айгуль")
        }
        composeRule.onNodeWithText("Не пришёл").assertIsDisplayed()
        composeRule.onNodeWithText("Сел").assertIsDisplayed()
        composeRule.onNodeWithText("Сообщить о проблеме").assertDoesNotExist()
    }

    @Test
    fun `old server without passenger id still explains and never shows no-show`() {
        composeRule.setContent {
            DriverBoardingControls(bookingId = 43, bookingStatus = "onboard", boardingUndoSec = 0, passengerId = 0, passengerName = "")
        }
        composeRule.onNodeWithText("Не пришёл").assertDoesNotExist()
        composeRule.onNodeWithText("Сообщить о проблеме").assertDoesNotExist()
        composeRule.onNodeWithText("Если пассажир на самом деле не садился", substring = true).assertIsDisplayed()
    }

    @Test
    fun `no-show opens at the exact moment the server sent`() {
        val opens = "2030-01-01T05:15:00"   // как отдаёт сервер: UTC без «Z»
        val ms = java.time.LocalDateTime.parse(opens).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        assertFalse("за миллисекунду до срока — ещё рано", noShowOpen(opens, ms - 1))
        assertTrue("ровно в срок — можно", noShowOpen(opens, ms))
        assertTrue(noShowOpen(opens, ms + 1))
        assertEquals(ms, noShowOpensAtMs(opens))
        // Те же секунды в других записях сервера и клиента: «Z», доли секунды, явный пояс.
        assertEquals(ms, noShowOpensAtMs("2030-01-01T05:15:00Z"))
        assertEquals(ms, noShowOpensAtMs("2030-01-01T05:15:00.123456"))
        assertEquals(ms, noShowOpensAtMs("2030-01-01T10:15:00+05:00"))
    }

    @Test
    fun `no deadline from the server means the button stays and the server decides`() {
        assertTrue(noShowOpen(null, 0L))
        assertTrue(noShowOpen("", 0L))
        assertTrue(noShowOpen("не дата", 0L))
        assertNull(noShowOpensAtMs(null))
        assertNull(noShowOpensAtMs("  "))
        assertNull(noShowOpensAtMs("не дата"))
    }

    @Test
    fun `confirmed booking before the wait shows when no-show opens instead of the button`() {
        val opens = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
            java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(System.currentTimeMillis() + 3 * 60 * 60_000L), java.time.ZoneOffset.UTC,
            ).withNano(0),
        )
        composeRule.setContent {
            DriverBoardingControls(
                bookingId = 44, bookingStatus = "confirmed", boardingUndoSec = 0,
                passengerId = 7, passengerName = "Айгуль", noShowAt = opens,
            )
        }
        composeRule.onNodeWithText("Не пришёл").assertDoesNotExist()
        composeRule.onNodeWithText("Не пришёл — можно отметить с", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Сел").assertIsDisplayed()
    }
}
