package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Онбординг-интро. Живой `IntroScreen` — кинематографичный таймлайн (бесконечные корутины `delay`,
 * sheen-sweep, Ken-Burns push-in, пыльца `SkyMotes` с `infiniteRepeatable`) → на JVM он бы ВИСНУЛ на
 * авто-анимации, поэтому его целиком НЕ рендерим. Вместо этого покрываем то, что вынесено чистым:
 *  - `IntroBrandContent` — СТАТИЧНАЯ витрина бренда (значок + слово + черта + слоган), управляемая
 *    примитивами (язык + видимость смысла/слогана), без единой бесконечной анимации → рендерится ровно.
 *  - `introSlogan` — чистый (не @Composable) хелпер строки слогана по языку.
 *
 * Тексты — ДОСЛОВНО из констант интро. Заголовок класса — как в LoginFormContentTest / AdminAdsContentTest.
 * Скролла тут нет (контент компактный), autoAdvance не трогаем — статике он не мешает.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntroScreenContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- IntroBrandContent: статичная витрина бренда ---

    @Test
    fun brandContent_default_showsBrandWordAndRussianSlogan() {
        // по умолчанию (showMeaning=false) виден сам бренд «Юлдаш» и русский слоган.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                IntroBrandContent(language = AppLanguage.Ru)
            }
        }
        composeRule.onNodeWithText("Юлдаш").assertIsDisplayed()
        composeRule.onNodeWithText("Поездки между своими").assertIsDisplayed()
    }

    @Test
    fun brandContent_bashkir_showsBashkirSlogan() {
        // язык BA → башкирский слоган (черновой BA → на проверку носителю).
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                IntroBrandContent(language = AppLanguage.Ba)
            }
        }
        composeRule.onNodeWithText("Үҙебеҙҙекеләр араһында юллашыу").assertIsDisplayed()
    }

    @Test
    fun brandContent_showMeaning_showsMeaningWordNotBrand() {
        // фаза «смысла»: показано слово «Попутчик», бренд «Юлдаш» в этот момент скрыт.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                IntroBrandContent(language = AppLanguage.Ru, showMeaning = true)
            }
        }
        composeRule.onNodeWithText("Попутчик").assertIsDisplayed()
        composeRule.onNodeWithText("Юлдаш").assertDoesNotExist()
    }

    @Test
    fun brandContent_withoutSlogan_hidesSloganButKeepsBrand() {
        // showSlogan=false → строки слогана нет, но бренд-слово по-прежнему на месте.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                IntroBrandContent(language = AppLanguage.Ru, showSlogan = false)
            }
        }
        composeRule.onNodeWithText("Юлдаш").assertIsDisplayed()
        composeRule.onNodeWithText("Поездки между своими").assertDoesNotExist()
    }

    // --- introSlogan: чистый хелпер строки слогана (без Compose) ---

    @Test
    fun introSlogan_returnsPerLanguageString() {
        assertEquals("Поездки между своими", introSlogan(AppLanguage.Ru))
        assertEquals("Үҙебеҙҙекеләр араһында юллашыу", introSlogan(AppLanguage.Ba))
    }
}
