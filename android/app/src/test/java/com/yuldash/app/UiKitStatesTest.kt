package com.yuldash.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
 * КОМПОНЕНТНЫЕ UI-тесты состояний UiKit (JVM через Robolectric, без эмулятора).
 * Проверяет `AppErrorState` и `AppEmptyState` — их видимость, дефолтные тексты
 * и обработку кликов (retry / action). Язык по умолчанию — Ru (нет override
 * `LocalAppLanguage`, `staticCompositionLocalOf { AppLanguage.Ru }`), поэтому
 * `appText` отдаёт русскую строку.
 *
 * Компоненты статичны (у `AppErrorState` кнопка «Повторить» без анимации) —
 * бесконечных анимаций тут нет, `autoAdvance` не трогаем.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiKitStatesTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ─────────────────────────── AppErrorState ───────────────────────────

    @Test
    fun errorState_showsCustomTitleTextAndRetryLabel() {
        composeRule.setContent {
            AppErrorState(
                onRetry = {},
                title = "Сбой сети",
                text = "Нет связи",
                retryLabel = "Ещё раз",
            )
        }
        composeRule.onNodeWithText("Сбой сети").assertIsDisplayed()
        composeRule.onNodeWithText("Нет связи").assertIsDisplayed()
        composeRule.onNodeWithText("Ещё раз").assertIsDisplayed()
    }

    @Test
    fun errorState_retryButtonClick_invokesOnRetry() {
        var retried = false
        composeRule.setContent {
            AppErrorState(
                onRetry = { retried = true },
                title = "Сбой сети",
                text = "Нет связи",
                retryLabel = "Ещё раз",
            )
        }
        composeRule.onNodeWithText("Ещё раз").performClick()
        assertTrue(retried)
    }

    @Test
    fun errorState_defaultTexts_areRussian() {
        // Без кастома → дефолты из UiKit.kt на языке по умолчанию (Ru).
        composeRule.setContent {
            AppErrorState(onRetry = {})
        }
        composeRule.onNodeWithText("Что-то пошло не так").assertIsDisplayed()
        composeRule.onNodeWithText("Проверь интернет и повтори").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить").assertIsDisplayed()
    }

    @Test
    fun errorState_defaultRetryButton_invokesOnRetry() {
        var retried = false
        composeRule.setContent {
            AppErrorState(onRetry = { retried = true })
        }
        composeRule.onNodeWithText("Повторить").performClick()
        assertTrue(retried)
    }

    // ─────────────────────────── AppEmptyState ───────────────────────────

    @Test
    fun emptyState_showsTitleAndText() {
        composeRule.setContent {
            AppEmptyState(title = "Ничего нет", text = "Пусто-пусто")
        }
        composeRule.onNodeWithText("Ничего нет").assertIsDisplayed()
        composeRule.onNodeWithText("Пусто-пусто").assertIsDisplayed()
    }

    @Test
    fun emptyState_withAction_showsActionLabel() {
        composeRule.setContent {
            AppEmptyState(
                title = "Т",
                text = "Т2",
                actionLabel = "Создать",
                onAction = {},
            )
        }
        composeRule.onNodeWithText("Создать").assertIsDisplayed()
    }

    @Test
    fun emptyState_actionClick_invokesOnAction() {
        var created = false
        composeRule.setContent {
            AppEmptyState(
                title = "Т",
                text = "Т2",
                actionLabel = "Создать",
                onAction = { created = true },
            )
        }
        composeRule.onNodeWithText("Создать").performClick()
        assertTrue(created)
    }

    @Test
    fun emptyState_withoutAction_rendersTitleWithoutCrash() {
        // actionLabel = null → кнопки нет, но компонент рисуется и заголовок виден.
        composeRule.setContent {
            AppEmptyState(title = "Только заголовок", text = "Описание")
        }
        composeRule.onNodeWithText("Только заголовок").assertIsDisplayed()
    }
}
