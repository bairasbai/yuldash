package com.yuldash.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Покрываем `ui/theme/Theme.kt` (`YuldashTheme` + светлая/тёмная палитры) на JVM через Robolectric.
 * `YuldashTheme` красит системные бары в `SideEffect` (тянет window через `findActivity`) и выбирает
 * `ColorScheme` по `darkTheme` — рендерим его в обоих режимах: тема не падает, дочерний контент рисуется,
 * и в `MaterialTheme.colorScheme` лежит ИМЕННО та палитра (сверяем primary дословно с исходными hex).
 *
 * Значения primary — из Theme.kt: светлая `0xFF0B6B3A`, тёмная `0xFF2FB36E`.
 * Заголовок класса — как в RobolectricSmokeTest / IntroScreenContentTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theme_light_rendersContentAndAppliesLightPalette() {
        var primary: Color? = null
        composeRule.setContent {
            YuldashTheme(darkTheme = false) {
                primary = MaterialTheme.colorScheme.primary
                Text("Юлдаш")
            }
        }
        composeRule.onNodeWithText("Юлдаш").assertIsDisplayed()
        assertEquals(Color(0xFF0B6B3A).toArgb(), primary!!.toArgb())
    }

    @Test
    fun theme_dark_rendersContentAndAppliesDarkPalette() {
        var primary: Color? = null
        composeRule.setContent {
            YuldashTheme(darkTheme = true) {
                primary = MaterialTheme.colorScheme.primary
                Text("Юлдаш")
            }
        }
        composeRule.onNodeWithText("Юлдаш").assertIsDisplayed()
        assertEquals(Color(0xFF2FB36E).toArgb(), primary!!.toArgb())
    }

    @Test
    fun theme_dark_usesDarkBackground() {
        // тёмная палитра: фон — глубокий зелёно-чёрный 0xFF0F1613 (дословно из Theme.kt).
        var background: Color? = null
        composeRule.setContent {
            YuldashTheme(darkTheme = true) {
                background = MaterialTheme.colorScheme.background
            }
        }
        assertEquals(Color(0xFF0F1613).toArgb(), background!!.toArgb())
    }

    @Test
    fun theme_light_usesLightBackground() {
        // светлая палитра: фон 0xFFFAFAF6 (дословно из Theme.kt).
        var background: Color? = null
        composeRule.setContent {
            YuldashTheme(darkTheme = false) {
                background = MaterialTheme.colorScheme.background
            }
        }
        assertEquals(Color(0xFFFAFAF6).toArgb(), background!!.toArgb())
    }

    @Test
    fun theme_togglingDarkMode_switchesPaletteAndKeepsContent() {
        // смена режима на лету: палитра перещёлкивается (light↔dark primary), контент остаётся в кадре.
        var dark by mutableStateOf(false)
        var primary: Color? = null
        composeRule.setContent {
            YuldashTheme(darkTheme = dark) {
                primary = MaterialTheme.colorScheme.primary
                Text("Юлдаш")
            }
        }
        composeRule.runOnIdle { assertEquals(Color(0xFF0B6B3A).toArgb(), primary!!.toArgb()) }

        dark = true
        composeRule.runOnIdle { assertEquals(Color(0xFF2FB36E).toArgb(), primary!!.toArgb()) }
        composeRule.onNodeWithText("Юлдаш").assertIsDisplayed()
    }
}
