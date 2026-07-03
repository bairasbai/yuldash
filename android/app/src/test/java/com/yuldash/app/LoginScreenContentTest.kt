package com.yuldash.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Пилот «content-композаблы»: чистые (без сети/state/анимаций) под-компоненты экрана входа
 * стали `internal` → покрываем их на JVM через Robolectric. Проверяем ядро продукта на экране
 * входа: двуязычие (RU/BA) и поведение переключателя языка.
 *
 * Заголовок класса — как в RobolectricSmokeTest. Анимаций тут нет → autoAdvance не нужен.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoginScreenContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun heroFeatures_russian_showsAllThreeTitles() {
        composeRule.setContent { LoginHeroFeatures(AppLanguage.Ru) }
        composeRule.onNodeWithText("Вход без пароля").assertIsDisplayed()
        composeRule.onNodeWithText("Никакого спама").assertIsDisplayed()
        composeRule.onNodeWithText("Данные под защитой").assertIsDisplayed()
    }

    @Test
    fun heroFeatures_bashkir_showsAllThreeTitles() {
        composeRule.setContent { LoginHeroFeatures(AppLanguage.Ba) }
        composeRule.onNodeWithText("Парольһеҙ инеү").assertIsDisplayed()
        composeRule.onNodeWithText("Спам юҡ").assertIsDisplayed()
        composeRule.onNodeWithText("Мәғлүмәт һаҡлауҙа").assertIsDisplayed()
    }

    @Test
    fun langToggle_showsBothChips() {
        composeRule.setContent { LoginLangToggle(AppLanguage.Ru, onToggleLanguage = {}) }
        composeRule.onNodeWithText("РУС").assertIsDisplayed()
        composeRule.onNodeWithText("БАШ").assertIsDisplayed()
    }

    @Test
    fun langToggle_clickingInactiveChip_firesToggle() {
        var toggled = false
        composeRule.setContent { LoginLangToggle(AppLanguage.Ru, onToggleLanguage = { toggled = true }) }
        composeRule.onNodeWithText("БАШ").performClick()   // язык Ru → тап по БАШ переключает
        assertTrue(toggled)
    }

    @Test
    fun langToggle_clickingActiveChip_doesNotFireToggle() {
        var toggled = false
        composeRule.setContent { LoginLangToggle(AppLanguage.Ru, onToggleLanguage = { toggled = true }) }
        composeRule.onNodeWithText("РУС").performClick()   // РУС уже активен → колбэк не зовём
        assertFalse(toggled)
    }

    @Test
    fun divider_isBilingual() {
        composeRule.setContent { LoginDivider(AppLanguage.Ru) }
        composeRule.onNodeWithText("или").assertIsDisplayed()
    }

    @Test
    fun divider_bashkir() {
        composeRule.setContent { LoginDivider(AppLanguage.Ba) }
        composeRule.onNodeWithText("йәки").assertIsDisplayed()
    }

    @Test
    fun safetyFooter_isBilingual() {
        composeRule.setContent { SafetyFooter(AppLanguage.Ru) }
        composeRule.onNodeWithText("Безопасность поездок — наш приоритет").assertIsDisplayed()
    }

    @Test
    fun safetyFooter_bashkir() {
        composeRule.setContent { SafetyFooter(AppLanguage.Ba) }
        composeRule.onNodeWithText("Сәфәр хәүефһеҙлеге — беҙҙең өҫтөнлөк").assertIsDisplayed()
    }
}
