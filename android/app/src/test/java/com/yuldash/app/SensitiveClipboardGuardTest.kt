package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Сторож: чувствительное копируется через `copySensitive`, а не обычным способом.
 *
 * Зачем (аудит 2026-08-12, волна 32). Android 13+ на каждое копирование показывает всплывашку
 * с ПРЕДПРОСМОТРОМ содержимого — её видит любой, кто смотрит на экран через плечо; буфер обмена
 * читают клавиатуры и менеджеры паролей. У нас копируют чужой телефон, код вручения посылки
 * (кто знает код — тот и получит посылку), ссылку слежения за поездкой и текст для диктовки
 * при SOS.
 *
 * Обычное копирование разрешено там, где оно и задумано публичным — код скидки показывают,
 * чтобы им делились. Такие места перечислены ниже ПОИМЕННО и с причиной: список короткий,
 * и каждый новый пункт в нём — осознанное решение, а не забывчивость.
 */
class SensitiveClipboardGuardTest {

    /** Где обычное копирование — это нормально, и почему. */
    private val plainCopyAllowed = mapOf(
        "CouponsScreen.kt" to "код скидки показывают, чтобы человек его видел и передавал",
    )

    private fun sourceFiles(): List<File> {
        val root = File("src/main/java/com/yuldash/app")
        assertTrue("не нашёл исходники приложения: ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `чувствительное не копируется обычным способом`() {
        val offenders = sourceFiles().filter { file ->
            file.name !in plainCopyAllowed &&
                Regex("""clipboard\.setText\(""").containsMatchIn(file.readText())
        }
        assertTrue(
            "здесь копируют через обычный буфер обмена: ${offenders.map { it.name }}. " +
                "Если это личное (телефон, код, ссылка на поездку) — зови copySensitive(context, text). " +
                "Если публичное — впиши файл в plainCopyAllowed с причиной.",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `причины для обычного копирования написаны для человека`() {
        for ((file, reason) in plainCopyAllowed) {
            assertTrue("причина для $file слишком короткая: $reason", reason.length >= 20)
        }
    }

    @Test
    fun `помощник действительно ставит системный флаг`() {
        val src = File("src/main/java/com/yuldash/app/SensitiveClipboard.kt").readText()
        assertTrue("нет системного флага EXTRA_IS_SENSITIVE — предпросмотр останется виден",
            src.contains("EXTRA_IS_SENSITIVE"))
        assertTrue("метка (label) должна быть пустой: она тоже видна в системном окне",
            src.contains("newPlainText(\"\""))
    }
}
