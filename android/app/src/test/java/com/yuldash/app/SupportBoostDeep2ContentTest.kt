package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину»: вынос стейта экрана `SupportScreen` (донат). Умная обёртка (сеть/стейт/лист СБП)
 * отделена от чистого `SupportContent(state, callbacks)`. Здесь Content покрыт в состояниях
 * (пресеты / «своя сумма» + ввод / отправка-спиннер / ошибка / «ждём перевод») на двух языках —
 * то, что раньше было 0%. Импур-часть (`SbpTransferSheet`: QR/буфер) живёт в обёртке, не тестируется.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SupportBoostDeep2ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun russian_showsHeaderAndPresetsAndDonateButton() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SupportContent(
                    selectedAmount = 30, customMode = false, customInput = "", completed = false,
                    sending = false, sendError = false, minAmount = 10, effectiveAmount = 30, amountValid = true,
                    onSelectAmount = {}, onToggleCustom = {}, onCustomInputChange = {}, onDonate = {}, onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Добровольная поддержка").assertIsDisplayed()
        composeRule.onNodeWithText("30 ₽").assertIsDisplayed()
        composeRule.onNodeWithText("Своя сумма").assertIsDisplayed()
        composeRule.onNodeWithText("Поддержать на 30 ₽").assertIsDisplayed()
    }

    @Test
    fun bashkir_showsBashkirHeader() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                SupportContent(
                    selectedAmount = 30, customMode = false, customInput = "", completed = false,
                    sending = false, sendError = false, minAmount = 10, effectiveAmount = 30, amountValid = true,
                    onSelectAmount = {}, onToggleCustom = {}, onCustomInputChange = {}, onDonate = {}, onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Ирекле ярҙам").assertIsDisplayed()
        composeRule.onNodeWithText("Үҙеңдең сумма").assertIsDisplayed()
    }

    @Test
    fun presetClick_firesOnSelectAmountWithValue() {
        var picked: Int? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SupportContent(
                    selectedAmount = 30, customMode = false, customInput = "", completed = false,
                    sending = false, sendError = false, minAmount = 10, effectiveAmount = 30, amountValid = true,
                    onSelectAmount = { picked = it }, onToggleCustom = {}, onCustomInputChange = {}, onDonate = {}, onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("100 ₽").performClick()
        assertEquals(100, picked)
    }

    @Test
    fun customToggleClick_firesOnToggleCustom() {
        var toggled = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SupportContent(
                    selectedAmount = 30, customMode = false, customInput = "", completed = false,
                    sending = false, sendError = false, minAmount = 10, effectiveAmount = 30, amountValid = true,
                    onSelectAmount = {}, onToggleCustom = { toggled = true }, onCustomInputChange = {}, onDonate = {}, onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Своя сумма").performClick()
        assertTrue(toggled)
    }

    @Test
    fun customMode_showsInputFieldWithLabel() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SupportContent(
                    selectedAmount = 30, customMode = true, customInput = "", completed = false,
                    sending = false, sendError = false, minAmount = 10, effectiveAmount = null, amountValid = false,
                    onSelectAmount = {}, onToggleCustom = {}, onCustomInputChange = {}, onDonate = {}, onBack = {},
                )
            }
        }
        // Поле «своя сумма» ниже фолда LazyColumn → скроллим к его МЕТКЕ (плейсхолдер семантикой не матчится).
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Сумма, ₽"))
        composeRule.onNodeWithText("Сумма, ₽").assertIsDisplayed()
    }

    @Test
    fun customMode_invalidInput_showsRangeHint() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SupportContent(
                    selectedAmount = 30, customMode = true, customInput = "5", completed = false,
                    sending = false, sendError = false, minAmount = 10, effectiveAmount = 5, amountValid = false,
                    onSelectAmount = {}, onToggleCustom = {}, onCustomInputChange = {}, onDonate = {}, onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("От 10 до 100 000 ₽").assertIsDisplayed()
    }

    @Test
    fun invalidAmount_donateButtonShowsGenericLabel() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SupportContent(
                    selectedAmount = 30, customMode = true, customInput = "", completed = false,
                    sending = false, sendError = false, minAmount = 10, effectiveAmount = null, amountValid = false,
                    onSelectAmount = {}, onToggleCustom = {}, onCustomInputChange = {}, onDonate = {}, onBack = {},
                )
            }
        }
        // Кнопка доната внизу LazyColumn → проскроллить к ней перед проверкой.
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Поддержать"))
        composeRule.onNodeWithText("Поддержать").assertIsDisplayed()
    }

    @Test
    fun donateClick_firesOnDonate() {
        var donated = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SupportContent(
                    selectedAmount = 30, customMode = false, customInput = "", completed = false,
                    sending = false, sendError = false, minAmount = 10, effectiveAmount = 30, amountValid = true,
                    onSelectAmount = {}, onToggleCustom = {}, onCustomInputChange = {}, onDonate = { donated = true }, onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Поддержать на 30 ₽").performClick()
        assertTrue(donated)
    }

    @Test
    fun sending_showsSpinnerNotDonateLabel() {
        composeRule.mainClock.autoAdvance = false   // бесконечный спиннер в кнопке
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SupportContent(
                    selectedAmount = 30, customMode = false, customInput = "", completed = false,
                    sending = true, sendError = false, minAmount = 10, effectiveAmount = 30, amountValid = true,
                    onSelectAmount = {}, onToggleCustom = {}, onCustomInputChange = {}, onDonate = {}, onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Поддержать на 30 ₽").assertDoesNotExist()
    }

    @Test
    fun sendError_showsErrorMessage() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SupportContent(
                    selectedAmount = 30, customMode = false, customInput = "", completed = false,
                    sending = false, sendError = true, minAmount = 10, effectiveAmount = 30, amountValid = true,
                    onSelectAmount = {}, onToggleCustom = {}, onCustomInputChange = {}, onDonate = {}, onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Не получилось. Проверь сеть и повтори.").assertIsDisplayed()
    }

    @Test
    fun completed_showsWaitingForTransferCard() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SupportContent(
                    selectedAmount = 30, customMode = false, customInput = "", completed = true,
                    sending = false, sendError = false, minAmount = 10, effectiveAmount = 30, amountValid = true,
                    onSelectAmount = {}, onToggleCustom = {}, onCustomInputChange = {}, onDonate = {}, onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Ждём перевод").assertIsDisplayed()
    }

    @Test
    fun notNowClick_firesOnBack() {
        var backed = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SupportContent(
                    selectedAmount = 30, customMode = false, customInput = "", completed = false,
                    sending = false, sendError = false, minAmount = 10, effectiveAmount = 30, amountValid = true,
                    onSelectAmount = {}, onToggleCustom = {}, onCustomInputChange = {}, onDonate = {}, onBack = { backed = true },
                )
            }
        }
        composeRule.onNodeWithText("Не сейчас").performClick()
        assertTrue(backed)
    }
}
