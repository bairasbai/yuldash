package com.yuldash.app

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Компонентные UI-тесты UiKit (AppButton, SectionHeader) на JVM через Robolectric —
 * без эмулятора. Заголовок класса скопирован 1-в-1 с RobolectricSmokeTest.
 *
 * ⚠️ Спиннер (CircularProgressIndicator) при loading=true — бесконечная анимация.
 * composeRule зависнет на ожидании idle, поэтому в loading-тестах ПЕРВОЙ строкой
 * ставим composeRule.mainClock.autoAdvance = false (до setContent).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiKitButtonTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ─────────────────────────── AppButton ───────────────────────────

    @Test
    fun appButton_showsText() {
        composeRule.setContent {
            AppButton(text = "Поехать", onClick = {})
        }
        composeRule.onNodeWithText("Поехать").assertIsDisplayed()
    }

    @Test
    fun appButton_click_invokesOnClick() {
        var clicked = false
        composeRule.setContent {
            AppButton(text = "Поехать", onClick = { clicked = true })
        }
        composeRule.onNodeWithText("Поехать").performClick()
        assertTrue(clicked)
    }

    @Test
    fun appButton_disabled_isNotEnabled() {
        composeRule.setContent {
            AppButton(text = "Продолжить", onClick = {}, enabled = false)
        }
        composeRule.onNodeWithText("Продолжить").assertIsNotEnabled()
    }

    @Test
    fun appButton_loading_hidesText() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AppButton(text = "Отправить", onClick = {}, loading = true)
        }
        composeRule.onNodeWithText("Отправить").assertDoesNotExist()
    }

    @Test
    fun appButton_loading_isNotEnabled() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            // текст при loading скрыт спиннером, поэтому целимся в кнопку по testTag,
            // а не по тексту — и проверяем, что она реально заблокирована.
            AppButton(
                text = "Отправить",
                onClick = {},
                loading = true,
                modifier = Modifier.testTag("send_btn"),
            )
        }
        composeRule.onNodeWithTag("send_btn").assertIsNotEnabled()
    }

    @Test
    fun appButton_secondary_showsTextAndClicks() {
        var clicked = false
        composeRule.setContent {
            AppButton(
                text = "Назад",
                onClick = { clicked = true },
                style = AppButtonStyle.Secondary,
            )
        }
        composeRule.onNodeWithText("Назад").assertIsDisplayed()
        composeRule.onNodeWithText("Назад").performClick()
        assertTrue(clicked)
    }

    @Test
    fun appButton_danger_showsText() {
        composeRule.setContent {
            AppButton(text = "Отменить", onClick = {}, style = AppButtonStyle.Danger)
        }
        composeRule.onNodeWithText("Отменить").assertIsDisplayed()
    }

    @Test
    fun appButton_accent_showsText() {
        composeRule.setContent {
            AppButton(text = "Буст", onClick = {}, style = AppButtonStyle.Accent)
        }
        composeRule.onNodeWithText("Буст").assertIsDisplayed()
    }

    // ─────────────────────────── SectionHeader ───────────────────────────

    @Test
    fun sectionHeader_showsTitleAndSubtitle() {
        composeRule.setContent {
            SectionHeader(title = "Поездки", subtitle = "Твои активные маршруты")
        }
        composeRule.onNodeWithText("Поездки").assertIsDisplayed()
        composeRule.onNodeWithText("Твои активные маршруты").assertIsDisplayed()
    }

    @Test
    fun sectionHeader_noSubtitle_showsOnlyTitle() {
        composeRule.setContent {
            SectionHeader(title = "Профиль")
        }
        composeRule.onNodeWithText("Профиль").assertIsDisplayed()
        composeRule.onNodeWithText("Твои активные маршруты").assertDoesNotExist()
    }
}
