package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Content-под-компоненты экрана отзыва (`ReviewStarsRow` / `ReviewThanksCard`): чистые (без
 * сети/своего state/корутин) части большого `AppReviewScreen` вынесены в `internal` → покрываем
 * на JVM через Robolectric. Двуязычие проверяем через `LocalAppLanguage` (эти компоненты зовут
 * `appText` внутри), выбранное число звёзд и колбэки — параметрами.
 *
 * Заголовок класса — как в RobolectricSmokeTest / SecondaryScreensContentTest. Анимаций/спиннера
 * нет → autoAdvance не нужен.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppReviewContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- ReviewStarsRow: ряд из 5 звёзд (contentDescription двуязычный, тап через onSelect) ---

    @Test
    fun reviewStarsRow_russian_showsStarDescriptions() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ReviewStarsRow(selected = 3, onSelect = {})
            }
        }
        // 5 звёзд рендерятся, у каждой русский contentDescription "$i звёзд"
        composeRule.onNodeWithContentDescription("1 звезда").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("5 звёзд").assertIsDisplayed()
    }

    @Test
    fun reviewStarsRow_bashkir_showsStarDescriptions() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                ReviewStarsRow(selected = 5, onSelect = {})
            }
        }
        composeRule.onNodeWithContentDescription("1 йондоҙ").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("5 йондоҙ").assertIsDisplayed()
    }

    @Test
    fun reviewStarsRow_tappingStar_firesOnSelectWithIndex() {
        var picked = -1
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ReviewStarsRow(selected = 5, onSelect = { picked = it })
            }
        }
        assertEquals(-1, picked)
        composeRule.onNodeWithContentDescription("2 звезды").performClick()
        assertEquals(2, picked)
    }

    // --- ReviewThanksCard: карточка «спасибо» после отправки (текст + кнопка «Готово») ---

    @Test
    fun reviewThanksCard_russian_showsThanksTextAndButton() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ReviewThanksCard(onDone = {})
            }
        }
        composeRule.onNodeWithText("Спасибо за отзыв!").assertIsDisplayed()
        composeRule.onNodeWithText("Готово").assertIsDisplayed()
    }

    @Test
    fun reviewThanksCard_bashkir_showsThanksText() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                ReviewThanksCard(onDone = {})
            }
        }
        composeRule.onNodeWithText("Фекерең өсөн рәхмәт!").assertIsDisplayed()
        composeRule.onNodeWithText("Әҙер").assertIsDisplayed()
    }

    @Test
    fun reviewThanksCard_clickingDone_firesCallback() {
        var done = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ReviewThanksCard(onDone = { done = true })
            }
        }
        assertFalse(done)
        composeRule.onNodeWithText("Готово").performClick()
        assertTrue(done)
    }
}
