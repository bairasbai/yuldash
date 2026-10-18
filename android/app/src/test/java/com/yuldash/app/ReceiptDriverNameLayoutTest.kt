package com.yuldash.app

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.yuldash.app.data.TripReceiptDto
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Production receipt, including its actual screen/card insets; no network or actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiptDriverNameLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowLargeRussianVerifiedName() = verify(AppLanguage.Ru, true, 320, 1.3f)
    @Test fun narrowLargeRussianUnverifiedName() = verify(AppLanguage.Ru, false, 320, 1.3f)
    @Test fun narrowLargeBashkirVerifiedName() = verify(AppLanguage.Ba, true, 320, 1.3f)
    @Test fun narrowLargeBashkirUnverifiedName() = verify(AppLanguage.Ba, false, 320, 1.3f)
    @Test fun normalRussianVerifiedName() = verify(AppLanguage.Ru, true, 411, 1f)
    @Test fun normalRussianUnverifiedName() = verify(AppLanguage.Ru, false, 411, 1f)
    @Test fun normalBashkirVerifiedName() = verify(AppLanguage.Ba, true, 411, 1f)
    @Test fun normalBashkirUnverifiedName() = verify(AppLanguage.Ba, false, 411, 1f)
    @Test fun reportedNameFitsNarrowRussianReceipt() = verify(AppLanguage.Ru, true, 320, 1.3f, "Тестовый водитель")
    @Test fun reportedNameFitsNarrowBashkirReceipt() = verify(AppLanguage.Ba, true, 320, 1.3f, "Тестовый водитель")

    private fun verify(language: AppLanguage, verified: Boolean, width: Int, fontScale: Float,
        name: String = if (language == AppLanguage.Ru) "Мухаметдинов Александр Рамилевич" else "Мөхәмәтйәров Ғабдулла Йосоп улы") {
        compose.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Column(Modifier.width(width.dp).padding(CanonSpace.lg).testTag("receipt")) {
                        ReceiptIdentityCard(TripReceiptDto(bookingId = 77, rideId = 7, role = "passenger",
                            fromCity = "Сибай", toCity = "Акъяр", departAt = "2026-10-18T10:00:00Z", seats = 1,
                            amount = 700, payMethod = "negotiate", paid = false, driverName = name, driverVerified = verified))
                    }
                }
            }
        }
        val label = if (language == AppLanguage.Ru) "Водитель" else "Йөрөтөүсе"
        compose.onNodeWithText(label).assertIsDisplayed()
        val node = compose.onNodeWithText(name, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val diagnostics = "language=$language verified=$verified width=$width scale=$fontScale; name=$name; " +
            "size=${layout.size}; paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}; " +
            "lines=${layout.lineCount}; constraints=${layout.layoutInput.constraints}"
        // hasVisualOverflow compares the paragraph width with the smaller text node under
        // Robolectric. Check the actual glyph bounds, complete text, and available height.
        assertFalse("Name must keep every line: $diagnostics", layout.multiParagraph.didExceedMaxLines)
        assertEquals("The complete name must remain visible: $diagnostics", name.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue("Name must have enough height: $diagnostics", layout.multiParagraph.height <= layout.size.height + 1f)
        for (line in 0 until layout.lineCount) {
            assertFalse("Name must not be shortened: $diagnostics line=$line", layout.isLineEllipsized(line))
            assertTrue("Glyphs must fit the available width: $diagnostics line=$line",
                layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.layoutInput.constraints.maxWidth + 1f)
            assertTrue("Glyphs must fit vertically: $diagnostics line=$line",
                layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= layout.size.height + 1f)
            val end = layout.getLineEnd(line)
            assertTrue("Normal words must not break into separate letters: $diagnostics line=$line end=$end",
                end == name.length || end > 0 && (name[end - 1].isWhitespace() || name[end].isWhitespace()))
        }
        val bounds = node.getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("receipt").getUnclippedBoundsInRoot()
        assertTrue("Name must remain inside its receipt: $diagnostics bounds=$bounds viewport=$viewport",
            bounds.left >= viewport.left && bounds.right <= viewport.right && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        val labelBounds = compose.onNodeWithText(label).getUnclippedBoundsInRoot()
        assertTrue("The label must leave full lines for the name: $diagnostics", labelBounds.bottom <= bounds.top)
        val badge = compose.onNodeWithContentDescription(if (language == AppLanguage.Ru) "Проверен" else "Тикшерелгән")
        if (verified) {
            badge.assertIsDisplayed()
            val badgeBounds = badge.getUnclippedBoundsInRoot()
            assertTrue("Verification must stay beside the readable name: $diagnostics",
                badgeBounds.right <= bounds.left && badgeBounds.top >= bounds.top && badgeBounds.top < bounds.bottom)
        } else badge.assertDoesNotExist()
    }
}
