package com.yuldash.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.ui.theme.YuldashTheme
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real component rendering/semantics at320x568dp; no provider or store is invoked. */
@RunWith(AndroidJUnit4::class)
class UpdateAndLoadingInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var previousTheme: Boolean? = null
    @Before fun saveTheme() { previousTheme = ThemePrefs.darkOverride }
    @After fun restoreTheme() { ThemePrefs.darkOverride = previousTheme }

    private fun save(name: String, metrics: JSONArray = JSONArray()) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "$name.json").writeText(metrics.toString(2))
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            check(compose.onNodeWithTag("viewport").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun update(language: AppLanguage, dark: Boolean, scale: Float, store: Boolean = true) {
        ThemePrefs.darkOverride = dark
        val name = "audit-update-${language.name.lowercase()}-${if(dark) "dark" else "light"}-font$scale${if(store) "" else "-no-store"}"
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalAppLanguage provides language, LocalDensity provides Density(density, scale)) {
                YuldashTheme(darkTheme = dark) {
                    Box(Modifier.size(320.dp, 568.dp).testTag("viewport")) {
                        ForceUpdateScreen(if(store) "https://example.invalid/update" else "")
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        val ru = language == AppLanguage.Ru
        val last = if(store) { if(ru) "Обновить приложение" else "Ҡушымтаны яңыртыу" }
            else { if(ru) "Скачай свежую версию там же, где ставил эту" else "Яңы версияны быныһын ҡуйған урындан уҡ алып ҡуй" }
        val texts = if (ru) listOf("Обнови Юлдаш", "Вышла новая версия — эта уже не поддерживается. Обнови приложение, и поехали дальше 🚗", last)
            else listOf("Юлдашты яңырт", "Яңы версия сыҡты — быныһы инде эшләмәй. Ҡушымтаны яңырт та, артабан юлға сығабыҙ 🚗", last)
        val scrollable = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()
        val metrics = JSONArray()
        save("$name-before", metrics)
        for ((index, text) in texts.withIndex()) {
            val node = compose.onNodeWithText(text, useUnmergedTree = true)
            val results = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val result = results.single()
            val right = (0 until result.lineCount).maxOf { result.getLineRight(it) }
            metrics.put(JSONObject().put("text", text).put("width", result.size.width).put("height", result.size.height)
                .put("paragraphWidth", result.multiParagraph.width).put("paragraphHeight", result.multiParagraph.height)
                .put("availableWidth", result.layoutInput.constraints.maxWidth)
                .put("right", right).put("lineCount", result.lineCount).put("overflow", result.hasVisualOverflow))
            if (scrollable) node.performScrollTo()
            save("$name-step$index", metrics)
            assertTrue("$text clipped: ${result.size}, ${result.multiParagraph.width}x${result.multiParagraph.height}, right=$right",
                !result.multiParagraph.didExceedMaxLines && result.multiParagraph.height <= result.size.height + 1f &&
                    right <= result.layoutInput.constraints.maxWidth + 1f && result.getLineEnd(result.lineCount - 1) == text.length)
            node.assertIsDisplayed()
        }
        val action = compose.onNodeWithText(texts.last())
        if (scrollable) action.performScrollTo()
        if(store) action.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        val bounds = action.getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        assertTrue("update action escaped viewport", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        metrics.put(JSONObject().put("kind", "final-unclipped-dp").put("top", bounds.top.value)
            .put("bottom", bounds.bottom.value).put("viewportTop", viewport.top.value).put("viewportBottom", viewport.bottom.value))
        save("$name-final", metrics)
    }

    private fun loading(language: AppLanguage, dark: Boolean) {
        ThemePrefs.darkOverride = dark
        compose.mainClock.autoAdvance = false
        val active = mutableStateOf(false)
        val expected = if(language == AppLanguage.Ru) "Отправить" else "Ебәрергә"
        var clicks = 0
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                YuldashTheme(darkTheme = dark) {
                    Column(Modifier.size(320.dp, 568.dp).background(CanonBg).testTag("viewport")) {
                        for (style in AppButtonStyle.entries) {
                            AppButton(appText("Отправить", "Ебәрергә"), { clicks++; active.value = true }, style = style,
                                loading = active.value, modifier = Modifier.testTag(style.name))
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag(AppButtonStyle.Primary.name).assertIsEnabled().performClick()
        compose.mainClock.advanceTimeByFrame()
        val metrics = JSONArray()
        for (style in AppButtonStyle.entries) {
            val action = compose.onNodeWithTag(style.name)
            assertTrue(expected in action.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
            action.assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            metrics.put(JSONObject().put("style", style.name).put("name", expected)
                .put("role", action.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role).toString())
                .put("disabled", action.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)))
            action.performTouchInput { click() }
        }
        assertEquals(1, clicks)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true).assertCountEquals(4)
        save("audit-loading-${language.name.lowercase()}-${if(dark) "dark" else "light"}", metrics)
        compose.runOnIdle { active.value = false }
        compose.mainClock.advanceTimeByFrame()
        for(style in AppButtonStyle.entries) compose.onNodeWithTag(style.name).assertIsEnabled()
        compose.onNodeWithTag(AppButtonStyle.Danger.name).performClick()
        assertEquals(2, clicks)
    }

    @Test fun updateRuNormal() = update(AppLanguage.Ru, false, 1f)
    @Test fun updateBaNormal() = update(AppLanguage.Ba, true, 1f)
    @Test fun updateRuLarge() = update(AppLanguage.Ru, false, 2f)
    @Test fun updateBaLarge() = update(AppLanguage.Ba, true, 2f)
    @Test fun updateRuLargeWithoutStore() = update(AppLanguage.Ru, false, 2f, false)
    @Test fun updateBaLargeWithoutStore() = update(AppLanguage.Ba, true, 2f, false)
    @Test fun loadingRuLight() = loading(AppLanguage.Ru, false)
    @Test fun loadingBaDark() = loading(AppLanguage.Ba, true)
}
