package com.yuldash.app

import androidx.compose.material3.Text
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
 * КОМПОНЕНТНЫЕ UI-тесты для AppStateContainer + AppLoading (UiKit.kt).
 * Идут на JVM через Robolectric (без эмулятора). Проверяют логику выбора состояния:
 *   loading && пусто → loadingContent · error && пусто → AppErrorState ·
 *   пусто → AppEmptyState · есть данные → content (даже во время фонового loading).
 *
 * ⚠️ AppLoading по умолчанию = CircularProgressIndicator (бесконечная анимация).
 * Любой тест, где он реально рендерится, ОБЯЗАН отключить часы: composeRule.mainClock.autoAdvance = false,
 * иначе composeRule.awaitIdle() зависнет навсегда. Где спиннера нет (кастомный loadingContent = Text) —
 * часы не трогаем.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiKitStateContainerTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ── ЕСТЬ данные ──────────────────────────────────────────────────────────

    @Test
    fun showsContent_whenItemsPresent() {
        composeRule.setContent {
            AppStateContainer(
                loading = false,
                error = false,
                items = listOf("Поездка №1"),
                onRetry = {},
                content = { list -> Text(list.first()) },
            )
        }
        composeRule.onNodeWithText("Поездка №1").assertIsDisplayed()
    }

    @Test
    fun showsContent_whenLoadingButItemsPresent_noSpinnerOverData() {
        // Важный кейс «не мигаем спиннером поверх данных»: loading=true, но список НЕпустой →
        // показываем content, спиннер (AppLoading) НЕ появляется. Спиннера в дереве нет,
        // поэтому autoAdvance трогать не нужно.
        composeRule.setContent {
            AppStateContainer(
                loading = true,
                error = false,
                items = listOf("Поездка №1"),
                onRetry = {},
                content = { list -> Text(list.first()) },
            )
        }
        composeRule.onNodeWithText("Поездка №1").assertIsDisplayed()
    }

    // ── ПУСТО + loading ──────────────────────────────────────────────────────

    @Test
    fun showsLoading_whenEmptyAndLoading_customContent() {
        // Кастомный loadingContent = Text (без анимации) → часы можно не трогать.
        // Проверяем, что показан именно loadingContent, а content НЕ вызван.
        composeRule.setContent {
            AppStateContainer(
                loading = true,
                error = false,
                items = emptyList<String>(),
                onRetry = {},
                loadingContent = { Text("ГРУЖУ") },
                content = { list -> Text(list.first()) },
            )
        }
        composeRule.onNodeWithText("ГРУЖУ").assertIsDisplayed()
        composeRule.onNodeWithText("Поездка №1").assertDoesNotExist()
    }

    @Test
    fun showsDefaultLoading_whenEmptyAndLoading_spinnerClockStopped() {
        // loadingContent по умолчанию = AppLoading = бесконечный спиннер →
        // ПЕРВОЙ строкой останавливаем часы, иначе awaitIdle зависнет.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AppStateContainer(
                loading = true,
                error = false,
                items = emptyList<String>(),
                onRetry = {},
                content = { list -> Text(list.first()) },
            )
        }
        // content не должен появиться, пока грузим и пусто.
        composeRule.onNodeWithText("Поездка №1").assertDoesNotExist()
    }

    // ── ПУСТО + error ────────────────────────────────────────────────────────

    @Test
    fun showsError_whenEmptyAndError_defaultTitleRu() {
        // Язык по умолчанию Ru (LocalAppLanguage = Ru), дефолтный title AppErrorState = "Что-то пошло не так".
        composeRule.setContent {
            AppStateContainer(
                loading = false,
                error = true,
                items = emptyList<String>(),
                onRetry = {},
                content = { list -> Text(list.first()) },
            )
        }
        composeRule.onNodeWithText("Что-то пошло не так").assertIsDisplayed()
        composeRule.onNodeWithText("Поездка №1").assertDoesNotExist()
    }

    @Test
    fun errorRetryButton_invokesOnRetry() {
        // Кнопка "Повторить" (Ru по умолчанию) должна дёрнуть onRetry.
        var retried = false
        composeRule.setContent {
            AppStateContainer(
                loading = false,
                error = true,
                items = emptyList<String>(),
                onRetry = { retried = true },
                content = { list -> Text(list.first()) },
            )
        }
        composeRule.onNodeWithText("Повторить").performClick()
        assertTrue(retried)
    }

    // ── ПУСТО (без loading/error) ────────────────────────────────────────────

    @Test
    fun showsEmpty_whenNoLoadingNoError_customTitle() {
        composeRule.setContent {
            AppStateContainer(
                loading = false,
                error = false,
                items = emptyList<String>(),
                onRetry = {},
                emptyTitle = "Пусто тут",
                content = { list -> Text(list.first()) },
            )
        }
        composeRule.onNodeWithText("Пусто тут").assertIsDisplayed()
        composeRule.onNodeWithText("Поездка №1").assertDoesNotExist()
    }

    @Test
    fun errorTakesPriorityOverEmpty_whenEmptyAndErrorAndNotLoading() {
        // error && пусто идёт раньше ветки «просто пусто» → показываем ошибку, а не emptyTitle.
        composeRule.setContent {
            AppStateContainer(
                loading = false,
                error = true,
                items = emptyList<String>(),
                onRetry = {},
                emptyTitle = "Пусто тут",
                content = { list -> Text(list.first()) },
            )
        }
        composeRule.onNodeWithText("Что-то пошло не так").assertIsDisplayed()
        composeRule.onNodeWithText("Пусто тут").assertDoesNotExist()
    }

    // ── AppLoading напрямую ──────────────────────────────────────────────────

    @Test
    fun appLoading_showsLabel_spinnerClockStopped() {
        // AppLoading рендерит бесконечный спиннер → останавливаем часы ПЕРВОЙ строкой.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AppLoading(label = "Секунду")
        }
        composeRule.onNodeWithText("Секунду").assertIsDisplayed()
    }
}
