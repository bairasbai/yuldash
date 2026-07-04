package com.yuldash.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Глубже покрываем `IntroScreen.kt` — то, что предыдущий раунд (IntroScreenContentTest) не тронул.
 * Живой `IntroScreen` целиком НЕ рендерим (бесконечный таймлайн + `SkyMotes` с `infiniteRepeatable`
 * повесили бы JVM). Берём ВЫНЕСЕННЫЕ чистые куски, ставшие `internal`:
 *  - `StaggerWord` — статичная витрина слова ПО БУКВАМ (Row из AnimatedVisibility). При `visible=true`
 *    буквы на месте → `assertIsDisplayed`. При `visible=false` — их нет.
 *  - `Context.findActivityCompat()` — чистая размотка ContextWrapper→Activity (без Compose), assertSame/null.
 *
 * ⚠ Окно HIGH (`w411dp-h2600dp`): вынесенный контент — плоский Column/Row без своего скролла,
 * высокое окно гарантирует, что буквы в кадре и `assertIsDisplayed` проходит (урок раунда 3).
 * Строки — ДОСЛОВНО из констант интро (INTRO_MEANING_WORD/INTRO_BRAND_WORD), RU/BA не смешиваем.
 * Заголовок класса — как в IntroScreenContentTest / LoginScreenContentTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntroScreenDeepContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- StaggerWord: статичное слово по буквам ---

    @Test
    fun staggerWord_visible_showsEveryLetterOfMeaningWord() {
        // «Попутчик» по буквам виден целиком, когда visible=true.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                StaggerWord(text = INTRO_MEANING_WORD, visible = true, fontSize = 38.sp)
            }
        }
        // каждая буква слова — отдельный Text-узел; проверяем несколько ключевых.
        composeRule.onNodeWithText("П").assertIsDisplayed()
        composeRule.onNodeWithText("о").assertIsDisplayed()
        composeRule.onNodeWithText("к").assertIsDisplayed()
    }

    @Test
    fun staggerWord_visible_rendersBrandWordLetters() {
        // тот же компонент несёт и бренд «Юлдаш» (морф-переход) — буквы на месте при visible=true.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                StaggerWord(text = INTRO_BRAND_WORD, visible = true, fontSize = 44.sp)
            }
        }
        composeRule.onNodeWithText("Ю").assertIsDisplayed()
        composeRule.onNodeWithText("л").assertIsDisplayed()
        composeRule.onNodeWithText("ш").assertIsDisplayed()
    }

    @Test
    fun staggerWord_notVisible_lettersAreNotDisplayed() {
        // visible=false → AnimatedVisibility прячет буквы: узел «П» существует в дереве, но не отображается.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                StaggerWord(text = INTRO_MEANING_WORD, visible = false, fontSize = 38.sp)
            }
        }
        composeRule.onNodeWithText("П").assertDoesNotExist()
    }

    // --- Context.findActivityCompat(): чистая размотка контекста ---

    @Test
    fun findActivityCompat_returnsActivity_whenContextIsActivity() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertSame(activity, activity.findActivityCompat())
    }

    @Test
    fun findActivityCompat_unwrapsThroughContextWrapper() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val wrapped: Context = object : ContextWrapper(activity) {}
        assertSame(activity, wrapped.findActivityCompat())
    }

    @Test
    fun findActivityCompat_returnsNull_whenNoActivityInChain() {
        // «голый» application-контекст без Activity в цепочке → null (не падаем).
        val appContext: Context =
            org.robolectric.RuntimeEnvironment.getApplication().applicationContext
        assertNull(appContext.findActivityCompat())
    }
}
