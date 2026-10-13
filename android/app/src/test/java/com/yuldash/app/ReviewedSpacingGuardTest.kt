package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Независимое ревью Android 12.10 (P3): отступы шторки «Поделиться поездкой» (попутка) и экрана
 * «Обнови Юлдаш» были числами (16.dp, 24.dp, 32.dp…), а не шкалой CanonSpace. Размеры элементов
 * (аватар 44.dp, тач-цель 48.dp, круг 104.dp) — не отступы, их сторож не трогает.
 */
class ReviewedSpacingGuardTest {

    private fun source(relative: String): String {
        var dir = File("").absoluteFile
        repeat(6) {
            val direct = File(dir, relative)
            if (direct.isFile) return direct.readText()
            val nested = File(dir, "android/$relative")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile ?: return ""
        }
        return ""
    }

    private val rawSpacing = Regex("""padding\([^)]*\b\d+(\.\d+)?\.dp|Spacer\(Modifier\.(height|width)\(\d+(\.\d+)?\.dp\)""")

    private fun rawIn(block: String) = rawSpacing.findAll(block).map { it.value }.toList()

    @Test
    fun `force update screen spaces with CanonSpace`() {
        val screen = source("app/src/main/java/com/yuldash/app/ForceUpdateScreen.kt")
        assertTrue("ForceUpdateScreen.kt not found", screen.contains("internal fun ForceUpdateScreen") || screen.contains("fun ForceUpdateScreen"))
        assertEquals(emptyList<String>(), rawIn(screen))
    }

    @Test
    fun `trip share sheet spaces with CanonSpace`() {
        val screen = source("app/src/main/java/com/yuldash/app/BookingActiveTripScreen.kt")
        val start = screen.indexOf("if (showShare && isTripChatActive())")
        val end = screen.indexOf("// ── Чистые карточки поездки/статуса", start)
        assertTrue("share sheet block not found: $start..$end", start >= 0 && end > start)
        assertEquals(emptyList<String>(), rawIn(screen.substring(start, end)))
    }
}
