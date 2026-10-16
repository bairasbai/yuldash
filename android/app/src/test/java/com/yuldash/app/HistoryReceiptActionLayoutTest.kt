package com.yuldash.app

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class, qualifiers="w360dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryReceiptActionLayoutTest {
    @get:Rule val compose=createComposeRule()
    @Test fun russianActionsRemainReadableOnNarrowLargeText()=verify(AppLanguage.Ru)
    @Test fun bashkirActionsRemainReadableOnNarrowLargeText()=verify(AppLanguage.Ba)
    private fun verify(language:AppLanguage) {
        val primary=if(language==AppLanguage.Ru) "Повторить маршрут" else "Маршрутты ҡабатлау"
        var repeats=0;var receipts=0
        compose.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language,
                    LocalDensity provides Density(LocalDensity.current.density,1.3f)) {
                    Box(Modifier.width(320.dp)) {
                        MyTripCard(Ride(id="1",from="Сибай",to="Магнитогорск",time="Вчера",driver="Driver",car="",price=300,seats=1,rating=5.0,verified=true,boosted=false),
                            status=appText("Завершена","Тамамланған"),statusColor=CanonMint,icon=Icons.Default.Verified,
                            primaryAction=primary,secondaryAction="Квитанция",stackActions=true,onPrimary={repeats++},onSecondary={receipts++})
                    }
                }
            }
        }
        for(text in listOf(primary,"Квитанция")) {
            val node=compose.onNodeWithText(text,useUnmergedTree=true);node.assertIsDisplayed()
            val layouts=mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
            val layout=layouts.single()
            assertFalse("Action clipped: $text; size=${layout.size}; paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}; lines=${layout.lineCount}; exceeded=${layout.multiParagraph.didExceedMaxLines}; style=${layout.layoutInput.style}; constraints=${layout.layoutInput.constraints}",layout.hasVisualOverflow)
            for(line in 0 until layout.lineCount) assertFalse("Action ellipsized: $text",layout.isLineEllipsized(line))
        }
        compose.onNodeWithText(primary).performClick();compose.onNodeWithText("Квитанция").performClick()
        assertEquals(1,repeats);assertEquals(1,receipts)
    }
}
