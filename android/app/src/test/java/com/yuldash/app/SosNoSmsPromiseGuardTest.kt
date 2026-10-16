package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Экран SOS и «Позвать помощь?» не обещают SMS близким (16.10.2026).
 *
 * Было: «SMS твоим доверенным контактам…», «Твоим доверенным контактам уйдёт SMS…». На проде SMS
 * выключены (SMS_PROVIDER=mock), а человек в опасности рассчитывает, что родные уже знают, — и
 * не звонит им сам. Теперь экран обещает только то, что точно случится: сигнал поддержке Юлдаш.
 * Кому из близких SMS правда ушло, «Позвать помощь» показывает уже ПОСЛЕ отправки, по ответу сервера.
 *
 * Читает только строки кода, не комментарии: в комментарии причину написать можно и нужно.
 */
class SosNoSmsPromiseGuardTest {

    private val files = listOf("SosVerifyScreens.kt", "RoadsideHelp.kt", "SecondaryScreens.kt")

    private val forbidden = listOf(
        "уйдёт SMS",
        "SMS твоим доверенным контактам",
        "Для SMS близким",
        "Сообщить близким и поддержке",
    )

    private fun sourceDir(): File? {
        var dir = File("").absoluteFile
        repeat(4) {
            listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app").forEach { path ->
                val src = File(dir, path)
                if (src.isDirectory) return src
            }
            dir = dir.parentFile ?: return null
        }
        return null
    }

    /** Строки файла без целых строк-комментариев (`//`, `/*`, `*`). */
    private fun codeLines(name: String): List<String> {
        val file = File(sourceDir() ?: return emptyList(), name)
        if (!file.isFile) return emptyList()
        return file.readLines().filterNot { line ->
            val t = line.trim()
            t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")
        }
    }

    @Test
    fun `сторож видит все три файла`() {
        for (name in files) assertTrue("не нашёл $name — сторож ничего не проверяет", codeLines(name).isNotEmpty())
    }

    @Test
    fun `в SOS и помощи на трассе нет обещаний SMS близким`() {
        val hits = files.flatMap { name ->
            codeLines(name).mapNotNull { line ->
                forbidden.firstOrNull { line.contains(it) }?.let { "$name  «$it»  ${line.trim().take(90)}" }
            }
        }
        assertTrue(
            "SMS на проде выключены — не обещай их близким (${hits.size} шт.):\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    @Test
    fun `кнопка SOS обещает сигнал поддержке, а не близким`() {
        val sos = codeLines("SosVerifyScreens.kt").joinToString("\n")
        assertTrue(
            "подпись кнопки SOS должна быть про поддержку",
            sos.contains("""appText("Сообщить поддержке", "Ярҙамға хәбәр итеү")"""),
        )
    }
}
