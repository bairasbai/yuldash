package com.yuldash.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.drawToBitmap
import java.util.concurrent.atomic.AtomicReference
import android.view.View
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusBarProtectionTest {
    @get:Rule val compose = createComposeRule()

    private fun verify(insetPx: Int, protected: Boolean) {
        val insets = WindowInsets(top = insetPx)
        val renderedView = AtomicReference<View>()
        compose.setContent {
            val view = LocalView.current
            SideEffect { renderedView.set(view) }
            Box(Modifier.size(200.dp).testTag("host")) {
                AppStatusBarProtection(statusBarInsets = insets) {
                    Box(Modifier.fillMaxSize().background(Color.Blue).testTag("body")) {
                        Box(Modifier.windowInsetsPadding(insets)) {
                            Box(Modifier.size(1.dp).testTag("inset-marker"))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val host = compose.onNodeWithTag("host").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot
        val marker = compose.onNodeWithTag("inset-marker").fetchSemanticsNode().boundsInRoot
        assertEquals("Защита не меняет положение и размер контента", host, body)
        assertEquals("Вложенный контент получает весь исходный inset", host.top + insetPx, marker.top, 0.5f)
        val pixels = compose.runOnIdle { renderedView.get().drawToBitmap() }
        val originX = host.left.toInt()
        val originY = host.top.toInt()
        fun assertPixel(y: Int, expected: Color) {
            val actual = Color(pixels.getPixel(originX + host.width.toInt() / 2, originY + y))
            assertEquals(expected.red, actual.red, 0.005f)
            assertEquals(expected.green, actual.green, 0.005f)
            assertEquals(expected.blue, actual.blue, 0.005f)
        }
        assertPixel(0, if (protected) CanonGreenInk else Color.Blue)
        assertPixel(insetPx - 1, if (protected) CanonGreenInk else Color.Blue)
        assertPixel(insetPx, Color.Blue)
        assertPixel(host.height.toInt() - 1, Color.Blue)
    }

    @Test fun legacyWindowDoesNotGetASecondProtectionInsideContent() = verify(80, false)

    @Test @Config(sdk = [35])
    fun edgeToEdgeProtectsOnlyTheExactStatusInset() = verify(40, true)

    @Test @Config(sdk = [35])
    fun tallerCutoutInsetIsNotReplacedWithAFixedHeightOrConsumed() = verify(100, true)
}
