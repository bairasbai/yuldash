package com.yuldash.app

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatCardNarrowLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun russianNarrowLargeText() = verify(AppLanguage.Ru, 1.3f, false)
    @Test fun bashkirNarrowLargeText() = verify(AppLanguage.Ba, 1.3f, false)
    @Test fun russianNarrowExtraLargeText() = verify(AppLanguage.Ru, 2f, true)
    @Test fun bashkirNarrowExtraLargeText() = verify(AppLanguage.Ba, 2f, true)

    @Test fun requestWithoutDateAddsNoEmptyTextOrBlankLine() {
        val route = "Уфа → Мелеуз"
        val subtitle = "Заявка · 1 место"
        val preview = "Смотреть отклики водителей"
        var clicks = 0
        compose.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru,
                    LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    Column(Modifier.width(320.dp).padding(CanonSpace.lg).testTag("chat-card-parent")) {
                        // Requests use this real ChatCard contract with no time and no unread badge.
                        ChatCard(initial = "У", name = route, subtitle = subtitle, message = preview,
                            time = "", unread = 0, verified = false, onClick = { clicks++ }, avatarUrl = "")
                    }
                }
            }
        }
        listOf(route, subtitle, preview).forEach { wholeText(it, 1.3f) }
        compose.onAllNodesWithText("", useUnmergedTree = true).assertCountEquals(0)
        val card = compose.onNode(hasClickAction()).assertIsDisplayed()
        val cardBounds = card.getUnclippedBoundsInRoot()
        val previewBounds = compose.onNodeWithText(preview, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals("An absent date must leave only the existing card inset below the preview",
            CanonSpace.lg.value, (cardBounds.bottom - previewBounds.bottom).value, 1f)
        card.performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    private fun verify(language: AppLanguage, scale: Float, dark: Boolean) {
        val unread = mutableIntStateOf(0)
        val name = if (language == AppLanguage.Ru) "Гульназ" else "Гөлназ"
        val route = if (language == AppLanguage.Ru) "Уфа → Мелеуз" else "Өфө → Мәләүез"
        val preview = if (language == AppLanguage.Ru) "Буду у входа" else "Ишек янында"
        val date = formatDepart("2026-10-29T12:30:00Z")
        var clicks = 0
        compose.setContent {
            YuldashTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppLanguage provides language,
                    LocalDensity provides Density(LocalDensity.current.density, scale)) {
                    // The real chat list has the same 16dp horizontal screen inset.
                    Column(Modifier.width(320.dp).padding(CanonSpace.lg).testTag("chat-card-parent")) {
                        ChatCard(initial = "Г", name = name, subtitle = route, message = preview,
                            time = date, unread = unread.intValue, verified = true,
                            onClick = { clicks++ }, avatarUrl = "")
                    }
                }
            }
        }
        for ((index, count) in listOf(0, 71, 0).withIndex()) {
            compose.runOnIdle { unread.intValue = count }
            val card = compose.onNode(hasClickAction()).assertIsDisplayed()
            val cardBounds = card.getUnclippedBoundsInRoot()
            val parent = compose.onNodeWithTag("chat-card-parent").getUnclippedBoundsInRoot()
            assertTrue("The actual card must remain a 48dp target: $cardBounds",
                cardBounds.right - cardBounds.left >= 48.dp && cardBounds.bottom - cardBounds.top >= 48.dp)
            assertTrue("The card must fit its real screen inset: $cardBounds parent=$parent",
                cardBounds.left >= parent.left && cardBounds.right <= parent.right)
            val texts = listOf(name, route, preview, date)
            texts.forEach { wholeText(it, scale) }
            val bounds = texts.map { compose.onNodeWithText(it, useUnmergedTree = true).getUnclippedBoundsInRoot() }
            for ((previous, next) in bounds.zipWithNext()) {
                assertTrue("Name, route, preview and date must not cover one another: $bounds", previous.bottom <= next.top)
                assertEquals("The date must share the text column, not squeeze it from the side",
                    previous.left.value, next.left.value, 0.5f)
            }
            compose.onNodeWithText("Г", useUnmergedTree = true).assertIsDisplayed()
            val badge = compose.onNodeWithText("71", useUnmergedTree = true)
            if (count == 0) badge.assertDoesNotExist() else {
                wholeText("71", scale)
                val badgeBounds = badge.getUnclippedBoundsInRoot()
                assertTrue("Unread count must not overlap any text: badge=$badgeBounds text=$bounds",
                    bounds.all { it.right <= badgeBounds.left })
            }
            card.performClick()
            compose.waitForIdle()
            assertEquals("The same card callback must fire once per click", index + 1, clicks)
        }
    }

    private fun wholeText(text: String, scale: Float) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        val nodeBounds = node.getUnclippedBoundsInRoot()
        val cardBounds = compose.onNode(hasClickAction()).getUnclippedBoundsInRoot()
        val diagnostics = "text=$text scale=$scale size=${layout.size} paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
            "constraints=${layout.layoutInput.constraints} density=${layout.layoutInput.density} node=$nodeBounds card=$cardBounds"
        assertEquals(diagnostics, text, layout.layoutInput.text.text)
        assertEquals(diagnostics, scale, layout.layoutInput.density.fontScale, 0f)
        assertFalse("Every line must survive: $diagnostics", layout.multiParagraph.didExceedMaxLines)
        assertEquals("The complete text must survive: $diagnostics", text.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) {
            val left = layout.getLineLeft(line)
            val right = layout.getLineRight(line)
            val details = "$diagnostics line=$line left=$left right=$right top=${layout.getLineTop(line)} bottom=${layout.getLineBottom(line)}"
            assertFalse("No ellipsis: $details", layout.isLineEllipsized(line))
            // Compose line coordinates belong to the paragraph; also check the glyph span in the node.
            assertTrue("Horizontal text must fit: $details", left >= -1f &&
                right <= layout.layoutInput.constraints.maxWidth + 1f && right - left <= layout.size.width + 1f)
            assertTrue("Vertical text must fit: $details", layout.getLineTop(line) >= -1f &&
                layout.getLineBottom(line) <= layout.size.height + 1f)
            val end = layout.getLineEnd(line)
            assertTrue("Never split a word: $details end=$end", end == text.length || end <= 0 ||
                !text[end - 1].isLetterOrDigit() || !text[end].isLetterOrDigit())
        }
        assertTrue("The full text must stay inside its actual card: $diagnostics",
            nodeBounds.left >= cardBounds.left && nodeBounds.right <= cardBounds.right &&
                nodeBounds.top >= cardBounds.top && nodeBounds.bottom <= cardBounds.bottom)
    }
}
