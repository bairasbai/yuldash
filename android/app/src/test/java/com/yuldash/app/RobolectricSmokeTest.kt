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
 * SMOKE: доказательство, что Compose-UI тест идёт на JVM через Robolectric (без эмулятора,
 * без Espresso → без краша InputManager на API 37). Если зелёный — можно писать компонентные
 * UI-тесты этим способом. Проверяет ядро продукта: двуязычие (`appText` по `LocalAppLanguage`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RobolectricSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersBashkir_whenLanguageBa() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                Text(appText("Поехать", "Китергә"))
            }
        }
        composeRule.onNodeWithText("Китергә").assertIsDisplayed()
    }

    @Test
    fun rendersRussian_whenLanguageRu() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Text(appText("Поехать", "Китергә"))
            }
        }
        composeRule.onNodeWithText("Поехать").assertIsDisplayed()
    }
}
