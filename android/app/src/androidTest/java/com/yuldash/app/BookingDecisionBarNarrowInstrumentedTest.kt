package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Нижняя панель брони шириной 320 dp на настоящем Android (шрифты устройства, а не Robolectric):
 * ни одна подпись не рвётся посреди слова — «Написать» раньше переносилась как «Написа/ть».
 */
@RunWith(AndroidJUnit4::class)
class BookingDecisionBarNarrowInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val labels = listOf(
        "Забронировать место" to "Урынды бронләү",
        "Отменить бронь" to "Бронде кире алыу",
        "Найти другую поездку" to "Башҡа сәфәр табыу",
        "Открыть поездку" to "Сәфәрҙе асыу",
        "Ждём водителя" to "Йөрөтөүсене көтәбеҙ",
    )

    private fun narrowBar(language: AppLanguage) {
        val primary = mutableStateOf(labels.first())
        composeRule.setContent {
            YuldashTheme {
                // Обычный размер шрифта: при ≥1.2 панель сама складывается в столбик.
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalAppLanguage provides language, LocalDensity provides Density(density, 1f)) {
                    Box(Modifier.width(320.dp)) {
                        val (ru, ba) = primary.value
                        BookingDecisionBar(
                            primaryText = if (language == AppLanguage.Ba) ba else ru,
                            primaryEnabled = true,
                            primaryStyle = AppButtonStyle.Primary,
                            primaryIcon = Icons.Default.EventSeat,
                            onMessage = {},
                            onPrimary = {},
                        )
                    }
                }
            }
        }
        for (label in labels) {
            composeRule.runOnIdle { primary.value = label }
            composeRule.waitForIdle()
            assertWholeWords(if (language == AppLanguage.Ba) "Яҙырға" else "Написать")
            assertWholeWords(if (language == AppLanguage.Ba) label.second else label.first)
        }
    }

    private fun assertWholeWords(text: String) {
        val node = composeRule.onNodeWithText(text, useUnmergedTree = true)
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(text, layout.layoutInput.text.text)
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            assertTrue("«$text» breaks inside a word after «${text.substring(0, end)}»",
                text[end - 1].isWhitespace() || text.getOrNull(end)?.isWhitespace() == true)
        }
    }

    @Test fun narrowBarKeepsWholeWordsRu() = narrowBar(AppLanguage.Ru)
    @Test fun narrowBarKeepsWholeWordsBa() = narrowBar(AppLanguage.Ba)
}
