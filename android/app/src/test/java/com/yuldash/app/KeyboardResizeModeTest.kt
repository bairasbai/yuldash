package com.yuldash.app

import android.view.Window
import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
import android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Режим клавиатуры окна (KeyboardResizesWindow.kt) — вход и чат поездки включают «поджимать»,
 * уход возвращает прежний.
 *
 * Ревью Opus 23.10, P1-1: у приложения режим в манифесте не задан, и «вернуть прежний» значило
 * вернуть «не задан» — такой возврат Android игнорирует (ViewRootImpl держит последний режим), окно
 * оставалось «поджимать», и на Android 15+ поля без `imePadding` (форма поездки, профиль) уходили под
 * клавиатуру после первого же входа. Теперь вместо «не задан» отдаём то, что система сама выбирает
 * для такого окна, — «сдвигать».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardResizeModeTest {
    @get:Rule val compose = createComposeRule()

    private fun Window.adjustMode() = attributes.softInputMode and SOFT_INPUT_MASK_ADJUST

    @Test
    fun leavingTheScreenGivesTheWindowPanNotTheIgnoredUnspecified() {
        val shown = mutableStateOf(false)
        var window: Window? = null
        compose.setContent {
            window = LocalView.current.context.findActivity()?.window
            if (shown.value) ResizeWindowForKeyboard()
        }
        compose.runOnIdle { window!!.setSoftInputMode(0) }   // как в приложении: в манифесте режима нет

        compose.runOnIdle { shown.value = true }
        compose.waitForIdle()
        assertEquals(SOFT_INPUT_ADJUST_RESIZE, window!!.adjustMode())

        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        assertEquals(SOFT_INPUT_ADJUST_PAN, window!!.adjustMode())
    }

    @Test
    fun anExplicitPreviousModeComesBackAsItWas() {
        val shown = mutableStateOf(false)
        var window: Window? = null
        compose.setContent {
            window = LocalView.current.context.findActivity()?.window
            if (shown.value) ResizeWindowForKeyboard()
        }
        compose.runOnIdle { window!!.setSoftInputMode(SOFT_INPUT_ADJUST_NOTHING) }

        compose.runOnIdle { shown.value = true }
        compose.waitForIdle()
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()

        assertEquals(SOFT_INPUT_ADJUST_NOTHING, window!!.adjustMode())
    }

    @Test
    fun twoScreensAtOnceKeepResizeUntilBothAreGone() {
        val first = mutableStateOf(false)
        val second = mutableStateOf(false)
        var window: Window? = null
        compose.setContent {
            window = LocalView.current.context.findActivity()?.window
            if (first.value) ResizeWindowForKeyboard()
            if (second.value) ResizeWindowForKeyboard()
        }
        compose.runOnIdle { window!!.setSoftInputMode(0) }

        compose.runOnIdle { first.value = true; second.value = true }
        compose.waitForIdle()
        compose.runOnIdle { first.value = false }
        compose.waitForIdle()
        assertEquals("второй экран ещё на месте", SOFT_INPUT_ADJUST_RESIZE, window!!.adjustMode())

        compose.runOnIdle { second.value = false }
        compose.waitForIdle()
        assertEquals(SOFT_INPUT_ADJUST_PAN, window!!.adjustMode())
    }
}
