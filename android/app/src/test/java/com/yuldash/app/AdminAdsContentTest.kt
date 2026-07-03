package com.yuldash.app

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Content-компонент экрана рекламы: `planLabel` — чистая @Composable-функция (String → метка тарифа
 * через `appText`, без сети/state/анимаций) стала `internal` → покрываем на JVM через Robolectric.
 * Метка зависит от языка (`LocalAppLanguage`), поэтому оборачиваем в CompositionLocalProvider.
 *
 * Заголовок класса — как в RobolectricSmokeTest / SecondaryScreensContentTest. Анимаций нет → autoAdvance не нужен.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdminAdsContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- planLabel: метка тарифа по ключу плана, на языке из LocalAppLanguage ---

    @Test
    fun planLabel_founder_russian() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Text(planLabel("founder"))
            }
        }
        composeRule.onNodeWithText("Основатель").assertIsDisplayed()
    }

    @Test
    fun planLabel_founder_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                Text(planLabel("founder"))
            }
        }
        composeRule.onNodeWithText("Нигеҙләүсе").assertIsDisplayed()
    }

    @Test
    fun planLabel_premiumAndUnknown_fallToExpected() {
        // "premium" → Премиум; любой другой ключ (в т.ч. "standard") → Стандарт (ветка else).
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Text(planLabel("premium"))
                Text(planLabel("standard"))
            }
        }
        composeRule.onNodeWithText("Премиум").assertIsDisplayed()
        composeRule.onNodeWithText("Стандарт").assertIsDisplayed()
    }
}
