package com.yuldash.app.walk.l2_2

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.copySensitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * leaf-2.2 (SensitiveClipboard.kt): `copySensitive` обязан реально выставлять системный флаг
 * «не показывать предпросмотр», а не просто копировать текст как обычно.
 *
 * До этого листа `SensitiveClipboardGuardTest` (не в зоне этого листа) проверял только, что
 * строка `EXTRA_IS_SENSITIVE` где-то упомянута в исходнике — структурная проверка не отличила бы
 * `putBoolean(EXTRA_IS_SENSITIVE, true)` от `putBoolean(EXTRA_IS_SENSITIVE, false)`: обе содержат
 * имя константы. Этот тест читает фактический результат копирования.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SensitiveClipboardBehaviorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun clipboard() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Test
    fun copySensitive_setsExtraIsSensitive_true() {
        copySensitive(context, "+79991234567")
        val extras = clipboard().primaryClip?.description?.extras
        assertTrue(
            "EXTRA_IS_SENSITIVE не выставлен — системный предпросмотр покажет чужой номер тому, " +
                "кто смотрит на экран через плечо",
            extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true,
        )
    }

    @Test
    fun copySensitive_actuallyCopiesTheText() {
        // Флаг не должен мешать самому копированию — человек всё ещё вставит то, что хотел.
        copySensitive(context, "YUL-7788")
        assertEquals("YUL-7788", clipboard().primaryClip?.getItemAt(0)?.text.toString())
    }

    @Test
    fun copySensitive_labelIsEmpty_notLeakingContext() {
        // label тоже виден в системном UI — непустая метка вроде "Телефон для СБП" была бы
        // той же утечкой контекста, от которой защищает EXTRA_IS_SENSITIVE.
        copySensitive(context, "+79991234567")
        assertEquals("", clipboard().primaryClip?.description?.label ?: "")
    }
}
