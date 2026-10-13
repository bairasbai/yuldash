package com.yuldash.app

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Нижняя панель брони на самом узком телефоне (320 dp, обычный шрифт): «Написать» переносилась
 * посреди слова («Написа/ть»), потому что кнопка получала фиксированную долю ширины (weight 0.9),
 * меньше своей подписи. Проверяем настоящую раскладку текста: перенос допустим только между
 * словами — для «Написать» и для каждой подписи главной кнопки, на обоих языках. Шрифт 1.15
 * («Крупный»): в строке рвалось «Забронировать» — теперь панель складывается в столбик.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingDecisionBarNarrowTest {
    @get:Rule val compose = createComposeRule()

    private class Primary(val ru: String, val ba: String, val style: AppButtonStyle, val icon: ImageVector)

    // Все подписи главной кнопки — те же, что выбирает BookingScreen (primaryLabel).
    private val primaries = listOf(
        Primary("Забронировать место", "Урынды бронләү", AppButtonStyle.Primary, Icons.Default.EventSeat),
        Primary("Отменить бронь", "Бронде кире алыу", AppButtonStyle.Danger, Icons.Default.Close),
        Primary("Найти другую поездку", "Башҡа сәфәр табыу", AppButtonStyle.Primary, Icons.Default.Search),
        Primary("Открыть поездку", "Сәфәрҙе асыу", AppButtonStyle.Primary, Icons.Default.EventSeat),
        Primary("Ждём водителя", "Йөрөтөүсене көтәбеҙ", AppButtonStyle.Primary, Icons.Default.EventSeat),
    )

    private fun noWordIsBroken(language: AppLanguage, fontScale: Float = 1f) {
        val shown = mutableStateOf(primaries.first())
        compose.setContent {
            YuldashTheme {
                CompositionLocalProvider(
                    LocalAppLanguage provides language,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                ) {
                    val p = shown.value
                    BookingDecisionBar(
                        primaryText = if (language == AppLanguage.Ba) p.ba else p.ru,
                        primaryEnabled = true,
                        primaryStyle = p.style,
                        primaryIcon = p.icon,
                        onMessage = {},
                        onPrimary = {},
                    )
                }
            }
        }
        val message = if (language == AppLanguage.Ba) "Яҙырға" else "Написать"
        for (p in primaries) {
            compose.runOnIdle { shown.value = p }
            compose.waitForIdle()
            assertWholeWords(message)
            assertWholeWords(if (language == AppLanguage.Ba) p.ba else p.ru)
        }
    }

    private fun assertWholeWords(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(text, layout.layoutInput.text.text)
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            assertTrue(
                "«$text» breaks inside a word after «${text.substring(0, end)}» (lines=${layout.lineCount})",
                text[end - 1].isWhitespace() || text.getOrNull(end)?.isWhitespace() == true,
            )
        }
    }

    @Test fun narrowBarKeepsWholeWordsRu() = noWordIsBroken(AppLanguage.Ru)
    @Test fun narrowBarKeepsWholeWordsBa() = noWordIsBroken(AppLanguage.Ba)
    @Test fun narrowBarKeepsWholeWordsAtLargeFontRu() = noWordIsBroken(AppLanguage.Ru, 1.15f)
    @Test fun narrowBarKeepsWholeWordsAtLargeFontBa() = noWordIsBroken(AppLanguage.Ba, 1.15f)
}
