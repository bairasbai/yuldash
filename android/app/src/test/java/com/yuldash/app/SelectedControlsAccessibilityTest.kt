package com.yuldash.app

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Actual rendered text styles and semantic tree, rather than palette-only assertions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectedControlsAccessibilityTest {
    @get:Rule val compose = createComposeRule()
    private var previousTheme: Boolean? = null

    @Before fun setup() { previousTheme = ThemePrefs.darkOverride; TelegramLoginBot.testName = "yuldash_test_bot" }
    @After fun cleanup() { ThemePrefs.darkOverride = previousTheme; TelegramLoginBot.testName = null }

    @Test fun selectedLanguageTextHasReadableDarkContrast() {
        ThemePrefs.darkOverride = true
        var background = Color.Unspecified
        compose.setContent {
            YuldashTheme(darkTheme = true) {
                background = CanonGreen2
                OnboardingLangChip("РУС", active = true, onClick = {})
            }
        }
        assertReadable("РУС", background)
    }

    @Test fun languageSelectionIsExposedAndHasMinimumTouchHeight() {
        compose.setContent { OnboardingLangChip("РУС", active = true, onClick = {}) }
        val control = compose.onNodeWithText("РУС")
        control.assertIsSelected()
        control.assertHeightIsAtLeast(48.dp)
    }

    @Test fun telegramLoginTextHasReadableDarkContrast() {
        ThemePrefs.darkOverride = true
        var background = Color.Unspecified
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme(darkTheme = true) {
                    background = CanonGreen2
                    LoginScreen(AppLanguage.Ru, {}, {})
                }
            }
        }
        compose.onNodeWithTag(TAG_LOGIN_TELEGRAM_BTN).performScrollTo()
        assertReadable("Войти через Telegram", background)
    }

    @Test fun selectedNavigationLabelHasReadableLightContrast() {
        ThemePrefs.darkOverride = false
        var background = Color.Unspecified
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme(darkTheme = false) {
                    background = CanonSurface
                    YuldashBottomBar(HomeTab.Map, {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        assertReadable("Карта", background)
    }

    @Test fun selectedNavigationTabIsExposedToAccessibility() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                YuldashBottomBar(HomeTab.Rides, {})
            }
        }
        compose.onNodeWithText("Сәфәрҙәр").assertIsSelected()
        compose.onNodeWithText("Карта").assertIsNotSelected()
    }

    @Test fun roleChooserExposesSelectionAndSwitchesBothWays() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                var selectedRole by remember { mutableStateOf(RideRole.Passenger) }
                OnboardingRoleChooser(selectedRole) { selectedRole = it }
            }
        }
        val passenger = compose.onNodeWithText("Я пассажир")
        val driver = compose.onNodeWithText("Я водитель")
        passenger.assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        driver.assertIsNotSelected().performClick().assertIsSelected()
        passenger.assertIsNotSelected().performClick().assertIsSelected()
        driver.assertIsNotSelected()
    }

    @Test fun russianNavigationLabelsFitAtLargeFontOnNarrowScreen() =
        assertNavigationLabelsFit(AppLanguage.Ru, listOf("Карта", "Поездки", "Заявка", "Чат", "Профиль"))

    @Test fun bashkirNavigationLabelsFitAtLargeFontOnNarrowScreen() =
        assertNavigationLabelsFit(AppLanguage.Ba, listOf("Карта", "Сәфәрҙәр", "Ғариза", "Чат", "Профиль"))

    private fun assertNavigationLabelsFit(language: AppLanguage, labels: List<String>) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalAppLanguage provides language,
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                YuldashTheme(darkTheme = false) {
                    androidx.compose.foundation.layout.Box(Modifier.width(320.dp)) {
                        YuldashBottomBar(HomeTab.Rides, {})
                    }
                }
            }
        }
        val tabTops = mutableListOf<Float>()
        for (label in labels) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertTrue("$label is truncated at fontScale=2 on 320dp; size=${layout.size}, " +
                "lineHeight=${layout.layoutInput.style.lineHeight}, " +
                "overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}",
                !layout.hasVisualOverflow)
            compose.onNodeWithText(label).assertHeightIsAtLeast(48.dp)
            tabTops += compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue("Navigation tabs must align when labels wrap: $tabTops",
            tabTops.max() - tabTops.min() <= 1f)
    }

    private fun assertReadable(text: String, background: Color) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val foreground = layouts.single().layoutInput.style.color
        assertTrue("$text has unspecified foreground", foreground != Color.Unspecified)
        val opaque = Color(
            foreground.red * foreground.alpha + background.red * (1f - foreground.alpha),
            foreground.green * foreground.alpha + background.green * (1f - foreground.alpha),
            foreground.blue * foreground.alpha + background.blue * (1f - foreground.alpha),
        )
        fun linear(value: Float): Double = value.toDouble().let {
            if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4)
        }
        fun luminance(color: Color) = 0.2126 * linear(color.red) +
            0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
        val fg = luminance(opaque)
        val bg = luminance(background)
        val ratio = (max(fg, bg) + 0.05) / (min(fg, bg) + 0.05)
        assertTrue("$text contrast=$ratio, requires >=4.5", ratio >= 4.5)
    }
}
