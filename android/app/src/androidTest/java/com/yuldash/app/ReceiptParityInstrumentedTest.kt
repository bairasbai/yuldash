package com.yuldash.app

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.TripReceiptDto
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Independent read-only host of the production receipt summary: native typography, both raw points and own rating.
 * No Application navigation, HTTP, payment or full user journey is supplied/proven by this component matrix. */
@RunWith(AndroidJUnit4::class)
class ReceiptParityInstrumentedTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun verify(language:AppLanguage,dark:Boolean) {
        val narrow=mutableStateOf(false)
        val receipt=mutableStateOf(TripReceiptDto(
            bookingId=42,rideId=7,role="passenger",fromCity="Сибай",toCity="Акъяр",departAt="2030-01-02T10:00:00Z",
            seats=1,amount=300,payMethod="cash",paid=false,driverName="Тестовый водитель",driverVerified=true,myStars=5,
            pickupStop="Ургаза",dropoffStop="Бурибай",
        ))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            WindowCompat.getInsetsController(compose.activity.window,compose.activity.window.decorView).apply {
                isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
            }
        }
        compose.setContent{
            val inheritedDensity=LocalDensity.current
            CompositionLocalProvider(
                LocalAppLanguage provides language,LocalNightRide provides dark,
                LocalDensity provides Density(inheritedDensity.density,if(narrow.value)1.3f else inheritedDensity.fontScale),
            ){YuldashTheme(darkTheme=dark){
                Box(Modifier.fillMaxSize().background(CanonBg).systemBarsPadding(),contentAlignment=Alignment.TopCenter) {
                    Column(
                        (if(narrow.value)Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize())
                            .testTag("receipt-summary-host").verticalScroll(rememberScrollState()).padding(CanonSpace.lg),
                    ) { ReceiptIdentityCard(receipt.value) }
                }
            }}
        }
        val own=if(language==AppLanguage.Ba)"Һинең юл өлөшөң" else "Твой участок"
        val parent=if(language==AppLanguage.Ba)"Сәфәр" else "Рейс"
        val rating=if(language==AppLanguage.Ba)"Һинең баһаң" else "Твоя оценка"
        val stars=if(language==AppLanguage.Ba)"5 йондоҙ" else "5 звёзд"
        compose.onNodeWithText(own).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ургаза → Бурибай").assertIsDisplayed()
        compose.onNodeWithText(parent).assertIsDisplayed()
        compose.onNodeWithText(rating).performScrollTo().assertIsDisplayed();compose.onNodeWithText(stars).assertIsDisplayed()
        capture(language,dark,"known-segment-and-rating")
        compose.runOnIdle{receipt.value=receipt.value.copy(dropoffStop=null)}
        compose.onNodeWithText(own).assertDoesNotExist();compose.onNodeWithText("Ургаза → Акъяр").assertDoesNotExist()
        compose.onNodeWithText(parent).performScrollTo().assertIsDisplayed();capture(language,dark,"legacy-null")
        compose.runOnIdle{receipt.value=receipt.value.copy(pickupStop="",dropoffStop="",role="driver")}
        compose.onNodeWithText(own).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Сибай → Акъяр").onLast().performScrollTo().assertIsDisplayed();capture(language,dark,"explicit-endpoints")
        compose.runOnIdle{receipt.value=receipt.value.copy(myStars=0)}
        compose.onNodeWithText(rating).assertDoesNotExist();compose.onNodeWithText(stars).assertDoesNotExist()
        capture(language,dark,"unrated")
        compose.runOnIdle{
            receipt.value=receipt.value.copy(role="passenger",pickupStop="Ургаза",dropoffStop="Бурибай",myStars=5)
            narrow.value=true
        }
        compose.onNodeWithText(own).performScrollTo().assertIsDisplayed()
        val segment=compose.onNodeWithText("Ургаза → Бурибай").performScrollTo().assertIsDisplayed()
        val layouts=mutableListOf<TextLayoutResult>()
        segment.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
        val layout=layouts.single()
        val hostWidth=compose.onNodeWithTag("receipt-summary-host").fetchSemanticsNode().boundsInRoot.width
        val hostWidthDp=hostWidth/layout.layoutInput.density.density
        assertEquals("Narrow host width",320f,hostWidthDp,0.5f)
        assertEquals("Narrow font scale",1.3f,layout.layoutInput.density.fontScale,0.001f)
        assertTrue("Personal segment unreadable: lines=${layout.lineCount}, size=${layout.size}, overflow=${layout.hasVisualOverflow}",
            layout.lineCount in 1..2 && !layout.hasVisualOverflow && layout.getLineEnd(layout.lineCount-1)=="Ургаза → Бурибай".length)
        val metrics=JSONObject().put("host_width_dp",hostWidthDp.toDouble()).put("font_scale",layout.layoutInput.density.fontScale.toDouble())
            .put("segment_line_count",layout.lineCount).put("segment_width_px",layout.size.width).put("segment_height_px",layout.size.height)
            .put("overflow",layout.hasVisualOverflow).put("independent_component_host",true)
        val directory=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"receipt-parity-20261018")
        directory.mkdirs();File(directory,"${language.name}-${if(dark)"dark" else "light"}-narrow-320-font1_3.json").writeText(metrics.toString())
        capture(language,dark,"narrow-320-font1_3")
    }
    private fun capture(language: AppLanguage, dark: Boolean, state: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "receipt-parity-20261018")
        directory.mkdirs()
        val bitmap = captureRenderedReceipt(instrumentation)
        try {
            File(directory, "${language.name}-${if (dark) "dark" else "light"}-$state.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun captureRenderedReceipt(instrumentation: Instrumentation): Bitmap {
        val window = compose.activity.window.decorView
        val deadline = SystemClock.uptimeMillis() + 3_000
        do {
            val presented = CountDownLatch(1)
            instrumentation.runOnMainSync {
                window.postInvalidateOnAnimation()
                val choreographer = Choreographer.getInstance()
                choreographer.postFrameCallback {
                    choreographer.postFrameCallback { presented.countDown() }
                }
            }
            check(presented.await(1, TimeUnit.SECONDS)) { "The receipt window did not present a frame" }
            instrumentation.waitForIdleSync()
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val bitmap = if (screenshot.config == Bitmap.Config.HARDWARE) {
                requireNotNull(screenshot.copy(Bitmap.Config.ARGB_8888, false)).also { screenshot.recycle() }
            } else screenshot
            val background = bitmap.getPixel(bitmap.width / 2, bitmap.height * 3 / 4)
            val hasContent = (1..6).any { row -> (1..6).any { column ->
                bitmap.getPixel(bitmap.width * column / 7, bitmap.height * row / 8) != background
            } }
            if (hasContent) return bitmap
            bitmap.recycle()
        } while (SystemClock.uptimeMillis() < deadline)
        error("The receipt screenshot contains no rendered content")
    }

    @Test fun russianLight()=verify(AppLanguage.Ru,false)
    @Test fun russianDark()=verify(AppLanguage.Ru,true)
    @Test fun bashkirLight()=verify(AppLanguage.Ba,false)
    @Test fun bashkirDark()=verify(AppLanguage.Ba,true)
}
