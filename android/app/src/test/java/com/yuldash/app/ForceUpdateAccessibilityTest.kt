package com.yuldash.app

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual mandatory-update content on a narrow, short window; no store is opened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h568dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForceUpdateAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    private fun verify(language: AppLanguage, scale: Float, store: Boolean = true) {
        val ru = language == AppLanguage.Ru
        val title = if (ru) "Обнови Юлдаш" else "Юлдашты яңырт"
        val body = if (ru) "Вышла новая версия — эта уже не поддерживается. Обнови приложение, и поехали дальше 🚗"
            else "Яңы версия сыҡты — быныһы инде эшләмәй. Ҡушымтаны яңырт та, артабан юлға сығабыҙ 🚗"
        val last = if (store) {
            if (ru) "Обновить приложение" else "Ҡушымтаны яңыртыу"
        } else {
            if (ru) "Скачай свежую версию там же, где ставил эту" else "Яңы версияны быныһын ҡуйған урындан уҡ алып ҡуй"
        }
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalAppLanguage provides language, LocalDensity provides Density(density, scale)) {
                Box(Modifier.fillMaxSize().testTag("viewport")) {
                    ForceUpdateScreen(if (store) "https://example.invalid/update" else "")
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        val scrollable = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()
        for (text in listOf(title, body, last)) {
            val node = compose.onNodeWithText(text, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val right = (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
            val end = layout.getLineEnd(layout.lineCount - 1)
            println("FORCE_UPDATE language=$language scale=$scale store=$store textLength=${text.length} size=${layout.size} overflow=${layout.hasVisualOverflow} paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} right=$right end=$end lineCount=${layout.lineCount} exceeded=${layout.multiParagraph.didExceedMaxLines} constraints=${layout.layoutInput.constraints}")
            // Center-aligned paragraph uses the available width; its natural Text width can
            // be smaller without clipping (confirmed by the normal-font device screenshot).
            assertTrue("$language scale=$scale clipped text: $text", !layout.multiParagraph.didExceedMaxLines &&
                layout.multiParagraph.height <= layout.size.height + 1f && right <= layout.layoutInput.constraints.maxWidth + 1f && end == text.length)
            if (scrollable) node.performScrollTo()
            node.assertIsDisplayed()
        }
        val action = compose.onNodeWithText(last)
        if (scrollable) action.performScrollTo()
        val bounds = action.getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        assertTrue("last action escaped viewport: $bounds / $viewport", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        if (store) action.assertHeightIsAtLeast(48.dp)
    }

    @Test fun narrowRuNormal() = verify(AppLanguage.Ru, 1f)
    @Test fun narrowBaNormal() = verify(AppLanguage.Ba, 1f)
    @Test fun narrowRuLarge() = verify(AppLanguage.Ru, 2f)
    @Test fun narrowBaLarge() = verify(AppLanguage.Ba, 2f)
    @Test fun narrowRuLargeWithoutStore() = verify(AppLanguage.Ru, 2f, false)
    @Test fun narrowBaLargeWithoutStore() = verify(AppLanguage.Ba, 2f, false)
}
