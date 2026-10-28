package com.yuldash.app

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeControlsNarrowLayoutTest {
    @get:Rule val compose = createComposeRule()
    private data class Profile(val language: AppLanguage, val dark: Boolean, val width: Int, val scale: Float)
    private val originalDebt = NavSignals.payNowDebtKop.value
    @After fun restoreDebt() { NavSignals.payNowDebtKop.value = originalDebt }

    @Test fun narrowRussianLight() = verify(Profile(AppLanguage.Ru, false, 320, 1.3f))
    @Test fun narrowRussianDark() = verify(Profile(AppLanguage.Ru, true, 320, 1.3f))
    @Test fun narrowBashkirLight() = verify(Profile(AppLanguage.Ba, false, 320, 1.3f))
    @Test fun narrowBashkirDark() = verify(Profile(AppLanguage.Ba, true, 320, 1.3f))
    @Test fun extraLargeRussianKeepsFullModeTitles() = verify(Profile(AppLanguage.Ru, false, 320, 2f), verticalModes = true)
    @Test fun extraLargeBashkirKeepsFullModeTitles() = verify(Profile(AppLanguage.Ba, false, 320, 2f), verticalModes = true)
    @Test fun ordinaryRussianKeepsCaptions() = verify(Profile(AppLanguage.Ru, false, 393, 1f))
    @Test fun ordinaryBashkirKeepsCaptions() = verify(Profile(AppLanguage.Ba, false, 393, 1f))

    private fun verify(p: Profile, verticalModes: Boolean = false) {
        val mode = mutableStateOf(RideMode.Pooling)
        val selectedTab = mutableStateOf(HomeTab.Map)
        val selectedChat = mutableStateOf("active")
        val unread = mutableIntStateOf(71)
        val navClicks = mutableListOf<HomeTab>()
        val chatClicks = mutableListOf<String>()
        var findClicks = 0
        var driverClicks = 0
        val tabs = listOf("active" to LocalizedText("Активные", "Актив"),
            "requests" to LocalizedText("Заявки", "Заявкалар"), "system" to LocalizedText("Система", "Система"))
        compose.setContent {
            YuldashTheme(darkTheme = p.dark) {
                CompositionLocalProvider(LocalAppLanguage provides p.language,
                    LocalDensity provides Density(LocalDensity.current.density, p.scale)) {
                    Column(Modifier.width(p.width.dp).testTag("home-controls-viewport")) {
                        ModeSwitchBar(mode.value, { mode.value = it }, {}, showExplain = true, showCourierWork = false)
                        Box(Modifier.fillMaxWidth().padding(horizontal = CanonSpace.lg)) {
                            MapHeroActions({ findClicks++ }, { driverClicks++ })
                        }
                        Box(Modifier.fillMaxWidth().padding(horizontal = CanonSpace.lg)) {
                            ChatHomeTabs(tabs, selectedChat.value, unread.intValue) { key ->
                                chatClicks += key
                                if (key != "system") selectedChat.value = key
                            }
                        }
                        YuldashBottomBar(selectedTab.value) { navClicks += it; selectedTab.value = it }
                    }
                }
            }
        }
        fun translated(ru: String, ba: String) = if (p.language == AppLanguage.Ru) ru else ba
        val modeLabels = listOf(translated("Попутка", "Юлдаш"), "Такси", "Курьер")
        val find = translated("Найти попутку", "Юлдаш табыу")
        val driver = translated("Я водитель", "Мин йөрөтөүсе")
        val navLabels = listOf("Карта", translated("Поездки", "Сәфәрҙәр"), translated("Заявка", "Ғариза"), "Чат", "Профиль")
        for (label in modeLabels + listOf(find, driver) + tabs.map { translated(it.second.ru, it.second.ba) }) wholeText(label, p)
        for ((index, m) in RideMode.entries.withIndex()) {
            val node = compose.onNodeWithTag("home-mode-${m.name}").assertIsDisplayed()
            touchSize(node)
            node.performClick()
            compose.waitForIdle()
            assertEquals(m, mode.value)
            wholeText(modeLabels[index], p)
            val pill = compose.onNodeWithTag("home-mode-pill").getUnclippedBoundsInRoot()
            val cell = node.getUnclippedBoundsInRoot()
            assertEquals("Selected pill must follow the whole actual segment", (cell.bottom - cell.top).value, (pill.bottom - pill.top).value, 0.5f)
            // Equal-weight cells and animated Dp offsets may round one pixel differently.
            assertEquals(cell.left.value, pill.left.value, 1f)
            assertEquals(cell.right.value, pill.right.value, 1f)
            assertEquals(cell.top.value, pill.top.value, 1f)
            assertEquals(cell.bottom.value, pill.bottom.value, 1f)
        }
        if (verticalModes) {
            val cells = RideMode.entries.map { compose.onNodeWithTag("home-mode-${it.name}").getUnclippedBoundsInRoot() }
            cells.zipWithNext().forEach { (first, second) ->
                assertTrue("Large titles must get separate full-width segments", first.bottom <= second.top)
                assertEquals(first.left.value, second.left.value, 1f)
                assertEquals(first.right.value, second.right.value, 1f)
            }
        }
        for (label in listOf(find, driver)) touchSize(compose.onNodeWithText(label).assertHasClickAction())
        compose.onNodeWithText(find).performClick(); compose.onNodeWithText(driver).performClick()
        assertEquals(1, findClicks); assertEquals(1, driverClicks)
        for (count in listOf(1, 71, 100, 0)) {
            compose.runOnIdle { unread.intValue = count }
            val system = compose.onNodeWithTag("home-chat-system").assertIsDisplayed()
            touchSize(system)
            for ((_, label) in tabs) wholeText(translated(label.ru, label.ba), p)
            val badge = compose.onNodeWithTag("home-chat-unread", useUnmergedTree = true)
            if (count == 0) badge.assertDoesNotExist() else {
                val shown = if (count > 99) "99+" else count.toString()
                wholeText(shown, p)
                badge.assertIsDisplayed()
                val badgeBounds = badge.getUnclippedBoundsInRoot()
                val labelBounds = compose.onNodeWithText(translated("Система", "Система"), useUnmergedTree = true).getUnclippedBoundsInRoot()
                val buttonBounds = system.getUnclippedBoundsInRoot()
                assertTrue("Unread count must not cover the complete label", labelBounds.right <= badgeBounds.left || badgeBounds.right <= labelBounds.left ||
                    labelBounds.bottom <= badgeBounds.top || badgeBounds.bottom <= labelBounds.top)
                assertTrue(badgeBounds.left >= buttonBounds.left && badgeBounds.right <= buttonBounds.right &&
                    badgeBounds.top >= buttonBounds.top && badgeBounds.bottom <= buttonBounds.bottom)
            }
        }
        for (key in listOf("requests", "active", "system")) {
            val node = compose.onNodeWithTag("home-chat-$key").assertIsDisplayed()
            touchSize(node); node.performClick()
        }
        assertEquals(listOf("requests", "active", "system"), chatClicks)
        assertEquals("active", selectedChat.value)
        val captions = navLabels.count { compose.onAllNodesWithText(it, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("Caption visibility is global; never leave a few truncated labels", captions == 0 || captions == navLabels.size)
        if (p.width == 393 && p.scale == 1f) assertEquals("Ordinary layout keeps its complete captions", navLabels.size, captions)
        for ((index, tab) in HomeTab.entries.withIndex()) {
            val label = navLabels[index]
            val node = compose.onNodeWithContentDescription(label).assertIsDisplayed().assertHasClickAction()
            node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            touchSize(node); node.performClick(); compose.waitForIdle()
            node.assertIsSelected(); assertEquals(tab, selectedTab.value)
            navLabels.filter { it != label }.forEach { compose.onNodeWithContentDescription(it).assertIsNotSelected() }
            if (captions > 0) navLabels.forEach { wholeText(it, p) }
        }
        assertEquals(HomeTab.entries.toList(), navClicks)
        compose.runOnIdle { NavSignals.payNowDebtKop.value = 1 }
        compose.waitForIdle()
        compose.onNodeWithTag("home-profile-debt", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { NavSignals.payNowDebtKop.value = 0 }
        compose.waitForIdle()
        compose.onNodeWithTag("home-profile-debt", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun touchSize(node: SemanticsNodeInteraction) {
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("A real clickable target must remain at least 48dp: $bounds", bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
        val viewport = compose.onNodeWithTag("home-controls-viewport").getUnclippedBoundsInRoot()
        assertTrue("Control must stay inside its available width", bounds.left >= viewport.left && bounds.right <= viewport.right)
    }

    private fun wholeText(text: String, p: Profile) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertEquals(text, layout.layoutInput.text.text)
        assertEquals("Use the actual requested system font scale", p.scale, layout.layoutInput.density.fontScale, 0f)
        assertFalse("No maxLines truncation for $text", layout.multiParagraph.didExceedMaxLines)
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) {
            val diagnostics = "profile=$p text=$text line=$line/${layout.lineCount} size=${layout.size} paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
                "constraints=${layout.layoutInput.constraints} density=${layout.layoutInput.density} align=${layout.layoutInput.style.textAlign} " +
                "left=${layout.getLineLeft(line)} right=${layout.getLineRight(line)} top=${layout.getLineTop(line)} bottom=${layout.getLineBottom(line)} " +
                "node=${node.getUnclippedBoundsInRoot()}"
            assertFalse("No ellipsis for $text", layout.isLineEllipsized(line))
            // Line coordinates belong to the paragraph; its width can exceed the text node.
            val left = layout.getLineLeft(line)
            val right = layout.getLineRight(line)
            assertTrue("Horizontal glyph bounds must fit: $diagnostics", left >= -1f &&
                right <= layout.layoutInput.constraints.maxWidth + 1f && right - left <= layout.size.width + 1f)
            assertTrue("Vertical glyph bounds must fit: $diagnostics", layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= layout.size.height + 1f)
            val end = layout.getLineEnd(line)
            assertTrue("Never split a word in $text", end == text.length || end <= 0 ||
                !text[end - 1].isLetterOrDigit() || !text[end].isLetterOrDigit())
        }
        val bounds = node.getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("home-controls-viewport").getUnclippedBoundsInRoot()
        assertTrue("The full text must remain inside the screen", bounds.left >= viewport.left && bounds.right <= viewport.right)
    }
}
