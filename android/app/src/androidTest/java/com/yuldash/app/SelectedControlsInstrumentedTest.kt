package com.yuldash.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.ui.theme.YuldashTheme
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device semantics/layout proof for corrected controls; no API, MapKit or provider. */
@RunWith(AndroidJUnit4::class)
class SelectedControlsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var previousTheme: Boolean? = null
    @Before fun rememberTheme() { previousTheme = ThemePrefs.darkOverride }
    @After fun restoreTheme() { ThemePrefs.darkOverride = previousTheme }

    @Test fun lightRussianControlsExposeSelectionAndSwitchLanguage() =
        checkControls(dark = false, initialLanguage = AppLanguage.Ru, imageName = "audit-controls-light-ru.png")

    @Test fun darkBashkirControlsExposeSelectionAndSwitchLanguage() =
        checkControls(dark = true, initialLanguage = AppLanguage.Ba, imageName = "audit-controls-dark-ba.png")

    private fun checkControls(dark: Boolean, initialLanguage: AppLanguage, imageName: String) {
        ThemePrefs.darkOverride = dark
        compose.setContent {
            var language by remember { mutableStateOf(initialLanguage) }
            var tab by remember { mutableStateOf(HomeTab.Rides) }
            CompositionLocalProvider(LocalAppLanguage provides language) {
                YuldashTheme(darkTheme = dark) {
                    Surface(color = CanonBg) {
                        Column(Modifier.fillMaxSize()) {
                            OnboardingLangChip("РУС", language == AppLanguage.Ru) { language = AppLanguage.Ru }
                            OnboardingLangChip("БАШ", language == AppLanguage.Ba) { language = AppLanguage.Ba }
                            Spacer(Modifier.weight(1f))
                            YuldashBottomBar(tab) { tab = it }
                        }
                    }
                }
            }
        }
        val selectedLanguage = if (initialLanguage == AppLanguage.Ru) "РУС" else "БАШ"
        compose.onNodeWithText(selectedLanguage).assertIsSelected().assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        val rides = if (initialLanguage == AppLanguage.Ru) "Поездки" else "Сәфәрҙәр"
        compose.onNodeWithText(rides).assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        val labels = if (initialLanguage == AppLanguage.Ru)
            listOf("Карта", "Поездки", "Заявка", "Чат", "Профиль")
        else listOf("Карта", "Сәфәрҙәр", "Ғариза", "Чат", "Профиль")
        val metrics = JSONArray()
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val tabTops = mutableListOf<Float>()
        for (label in labels) {
            val layouts = mutableListOf<TextLayoutResult>()
            val text = compose.onNodeWithText(label, useUnmergedTree = true)
            text.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
                it(layouts)
            }
            val layout = layouts.single()
            val bounds = text.fetchSemanticsNode().boundsInRoot
            metrics.put(JSONObject().put("label", label).put("fontSize", layout.layoutInput.style.fontSize.toString())
                .put("lineHeight", layout.layoutInput.style.lineHeight.toString()).put("lineCount", layout.lineCount)
                .put("overflowWidth", layout.didOverflowWidth).put("overflowHeight", layout.didOverflowHeight)
                .put("widthPx", layout.size.width).put("heightPx", layout.size.height)
                .put("topPx", bounds.top).put("bottomPx", bounds.bottom))
            assertFalse("$label must be fully readable: $layout", layout.hasVisualOverflow)
            assertTrue("$label extends beyond screen", bounds.top >= rootBounds.top && bounds.bottom <= rootBounds.bottom)
            compose.onNodeWithText(label).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            tabTops += compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue("Tab rows must stay aligned: $tabTops", tabTops.max() - tabTops.min() <= 1f)
        compose.onNodeWithText("Карта").performClick().assertIsSelected()
        compose.onNodeWithText(rides).assertIsNotSelected()
        compose.mainClock.advanceTimeBy(500)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "$imageName.json").writeText(metrics.toString(2))
        File(context.getExternalFilesDir(null), imageName).outputStream().use { output ->
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        val otherLanguage = if (initialLanguage == AppLanguage.Ru) "БАШ" else "РУС"
        compose.onNodeWithText(otherLanguage).performClick().assertIsSelected()
        compose.onNodeWithText(selectedLanguage).assertIsNotSelected()
    }
}
