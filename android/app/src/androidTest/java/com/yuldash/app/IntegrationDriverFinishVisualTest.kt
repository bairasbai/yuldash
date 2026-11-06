package com.yuldash.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.geometry.Offset
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import com.yuldash.app.data.MeetingPointInput
import kotlin.math.roundToInt

/** Только публичный занятый диалог; не заменяет проверку входа и всей поездки. */
@RunWith(AndroidJUnit4::class)
class IntegrationDriverFinishVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val previousTheme = ThemePrefs.darkOverride

    @After fun cleanup() { ThemePrefs.darkOverride = previousTheme }

    @Test fun russianLight() = show(AppLanguage.Ru, false, "ru-light")
    @Test fun russianDark() = show(AppLanguage.Ru, true, "ru-dark")
    @Test fun bashkirLight() = show(AppLanguage.Ba, false, "ba-light")
    @Test fun bashkirDark() = show(AppLanguage.Ba, true, "ba-dark")

    @Test fun meetingPointKeepsDoneAboveKeyboard() {
        val saved = AtomicReference<MeetingPointInput?>()
        ThemePrefs.darkOverride = false
        compose.setContent {
            YuldashTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    // Пустой город: подсказки не обращаются к серверу; карту не открываем.
                    MeetingPointSheet(city = "", title = "Место встречи", initial = null,
                        onDone = { saved.set(it) }, onDismiss = {})
                }
            }
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            compose.onNodeWithText("Пояснение: ворота, подъезд, что рядом").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("QA point")
            var keyboard = Rect()
            compose.waitUntil(10_000) {
                automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    ?.also { it.getBoundsInScreen(keyboard) } != null && !keyboard.isEmpty
            }
            compose.onNodeWithText("Готово").performScrollTo().assertIsDisplayed()
            compose.waitForIdle()
            // Берём настоящий экранный прямоугольник Compose-узла шторки.
            // Accessibility-текст Compose не обязан дублироваться в отдельном UIAutomation-дереве.
            val done = compose.onNodeWithText("Готово").fetchSemanticsNode()
            val bounds = compose.runOnIdle {
                val coordinates = done.layoutInfo.coordinates
                val top = coordinates.localToScreen(Offset.Zero)
                val bottom = coordinates.localToScreen(Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                Rect(top.x.roundToInt(), top.y.roundToInt(), bottom.x.roundToInt(), bottom.y.roundToInt())
            }
            android.os.SystemClock.sleep(250) // закончить платформенное появление IME перед кадром
            automation.windows.first { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }.getBoundsInScreen(keyboard)
            assertTrue("Кнопка должна оставаться выше настоящей клавиатуры: $bounds / $keyboard",
                !bounds.isEmpty && bounds.top >= 0 && bounds.bottom <= keyboard.top)
            println("INTEGRATION_KEYBOARD button=$bounds ime=$keyboard")
            screenshot("meeting-keyboard")
            compose.onNodeWithText("Готово").performTouchInput { click() }
            compose.waitUntil(3_000) { saved.get() != null }
            assertEquals("QA point", saved.get()?.note)
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    private fun show(language: AppLanguage, dark: Boolean, suffix: String) {
        var submitted = 0
        ThemePrefs.darkOverride = dark
        compose.setContent {
            YuldashTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppLanguage provides language, LocalNightRide provides dark) {
                    DriverFinishDialog(busy = true, onConfirm = { submitted++ }, onDismiss = {})
                }
            }
        }
        val busy = if (language == AppLanguage.Ba) "Тамамлайбыҙ…" else "Завершаем…"
        compose.onNodeWithText(busy).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(busy).performTouchInput { click() }
        compose.waitForIdle()
        assertEquals("Повторное нажатие не отправляет завершение", 0, submitted)
        screenshot("finish-$suffix")
    }

    private fun screenshot(suffix: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(instrumentation.targetContext.cacheDir, "integration-20261106-$suffix.png")
        check(!file.exists()) { "Снимок текущей задачи уже существует" }
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }
}
