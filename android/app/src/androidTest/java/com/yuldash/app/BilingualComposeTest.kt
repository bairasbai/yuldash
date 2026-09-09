package com.yuldash.app

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.filters.SdkSuppress
import org.junit.Rule
import org.junit.Test

/**
 * Инструментальный Compose-тест (на устройстве/эмуляторе) — каркас «с нуля».
 * Проверяет ядро продукта: двуязычие. `appText` рендерит нужный язык по `LocalAppLanguage`.
 * Запуск: gradlew :app:connectedDebugAndroidTest
 *
 * На Android 17 / API 37 Espresso 3.6.1 (под капотом у createComposeRule для idle-sync)
 * падает: NoSuchMethodException
 * InputManager.getInstance (метод удалён в новом API, AndroidX ещё не догнал). Логика/каркас верны —
 * На поддерживаемых API ≤36 тест выполняется; ограничение применяется только к новым API.
 * Тем временем ViewModel покрыт YuldashViewModelInstrumentedTest (без Espresso → зелёный на API 37).
 */
@SdkSuppress(maxSdkVersion = 36)
class BilingualComposeTest {

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
