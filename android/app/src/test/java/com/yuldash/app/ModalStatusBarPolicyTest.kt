package com.yuldash.app

import android.app.Application
import android.view.Window
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModalStatusBarPolicyTest {
    @get:Rule val compose = createComposeRule()

    private fun verify(dark: Boolean) {
        val dialog = AtomicReference<Window>()
        var visible by mutableStateOf(true)
        compose.setContent {
            YuldashTheme(darkTheme = dark) {
                if (visible) AppModalBottomSheet(
                    onDismissRequest = { visible = false },
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    val provider = LocalView.current.parent as DialogWindowProvider
                    SideEffect { dialog.set(provider.window) }
                    Text("QA sheet", Modifier.testTag("sheet-content"))
                }
            }
        }
        compose.waitUntil(10_000) { dialog.get() != null }
        compose.runOnIdle {
            val window = dialog.get()
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            assertFalse("Собственное окно шторки сразу использует белые часы", controller.isAppearanceLightStatusBars)
            assertEquals("Навигация сохраняет прежнюю политику Material3", !dark, controller.isAppearanceLightNavigationBars)
            visible = false
        }
        compose.waitForIdle()
        compose.onNodeWithTag("sheet-content").assertDoesNotExist()
    }

    @Test fun lightSheetKeepsWhiteClockAndDarkNavigationThenDisposes() = verify(false)
    @Test fun darkSheetKeepsWhiteClockAndLightNavigationThenDisposes() = verify(true)
}
