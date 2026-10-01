package com.yuldash.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Name, disabled state and progress must survive replacement of text by a spinner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoadingButtonAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    private fun verify(style: AppButtonStyle, language: AppLanguage) {
        compose.mainClock.autoAdvance = false
        val loading = mutableStateOf(false)
        var clicks = 0
        val expected = if (language == AppLanguage.Ru) "Отправить" else "Ебәрергә"
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                AppButton(appText("Отправить", "Ебәрергә"), { clicks++; loading.value = true },
                    modifier = Modifier.testTag("action"), style = style, loading = loading.value)
            }
        }
        val action = compose.onNodeWithTag("action")
        compose.onNodeWithText(expected).assertIsDisplayed()
        action.assertIsEnabled().performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1, clicks)
        val semantics = action.fetchSemanticsNode().config
        val names = semantics.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            semantics.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        assertTrue("loading $style/$language lost its accessible action name: $names", expected in names)
        action.assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true)
            .assertCountEquals(1)
        action.performTouchInput { click() }
        assertEquals(1, clicks)
        compose.runOnIdle { loading.value = false }
        compose.mainClock.advanceTimeByFrame()
        action.assertIsEnabled()
        assertNull(action.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription))
        compose.onNodeWithText(expected).assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true)
            .assertCountEquals(0)
        action.performClick()
        assertEquals(2, clicks)
    }

    @Test fun primaryRu() = verify(AppButtonStyle.Primary, AppLanguage.Ru)
    @Test fun primaryBa() = verify(AppButtonStyle.Primary, AppLanguage.Ba)
    @Test fun accentRu() = verify(AppButtonStyle.Accent, AppLanguage.Ru)
    @Test fun accentBa() = verify(AppButtonStyle.Accent, AppLanguage.Ba)
    @Test fun secondaryRu() = verify(AppButtonStyle.Secondary, AppLanguage.Ru)
    @Test fun secondaryBa() = verify(AppButtonStyle.Secondary, AppLanguage.Ba)
    @Test fun dangerRu() = verify(AppButtonStyle.Danger, AppLanguage.Ru)
    @Test fun dangerBa() = verify(AppButtonStyle.Danger, AppLanguage.Ba)
}
