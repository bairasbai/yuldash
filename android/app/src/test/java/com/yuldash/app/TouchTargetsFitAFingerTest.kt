package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * По кнопке должно попадать пальцем — в перчатках и в тряске.
 *
 * Правило проекта: тач-цель не меньше 48dp. Это не формальность гайдлайна: Юлдашем
 * пользуются в машине на грунтовке, зимой в варежках и люди, которым за шестьдесят.
 * Промах по маленькой кнопке в чате означает «отправил не то» — а сообщение уже ушло
 * попутчику.
 *
 * Панель эмодзи в чате жила с кнопками по 40dp и восемью штуками в ряд (аудит 2026-08-08,
 * волна 104). Стало шесть по 48dp — помещается даже на узком экране.
 *
 * Тест смотрит на элементы, у которых собственный размер стоит РЯДОМ с обработчиком нажатия:
 * иконка 18dp внутри большой кнопки — это нормально, кликабельный квадрат 40dp — нет.
 */
class TouchTargetsFitAFingerTest {

    private val root = File("src/main/java/com/yuldash/app")

    private val MIN_DP = 48

    /**
     * Ищем связку «своя размерность + клик» внутри одного Modifier-выражения:
     * `Modifier.size(40.dp)…clickable`. Иконки внутри кнопок так не выглядят — у них
     * размер задан отдельным modifier у самой иконки, а клик висит на родителе.
     */
    private val CLICKABLE_BOX = Regex(
        """Modifier\s*\n?\s*\.size\((\d+)\.dp\)(?:\s*\n?\s*\.\w+\([^)]*\))*\s*\n?\s*\.clickable""",
    )

    private fun sources(): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `кликабельные элементы не меньше сорока восьми dp`() {
        val small = mutableListOf<String>()
        for (file in sources()) {
            for (m in CLICKABLE_BOX.findAll(file.readText())) {
                val dp = m.groupValues[1].toInt()
                if (dp < MIN_DP) small += "${file.name} → ${dp}dp"
            }
        }
        assertTrue(
            "по этим элементам трудно попасть пальцем: $small. Правило проекта — не меньше " +
                "${MIN_DP}dp: приложением пользуются в машине, зимой в перчатках и люди в возрасте.",
            small.isEmpty(),
        )
    }

    @Test
    fun `панель эмодзи помещается на узком экране`() {
        val src = File(root, "RidesRequestsChatScreens.kt").readText()
        val perRow = Regex("""CHAT_EMOJIS\.chunked\((\d+)\)""").find(src)?.groupValues?.get(1)?.toInt()

        assertTrue("не нашёл раскладку панели эмодзи", perRow != null)
        assertTrue(
            "в ряду $perRow эмодзи по ${MIN_DP}dp — это ${perRow!! * MIN_DP}dp, узкий экран " +
                "(360dp) такую строку не вместит: кнопки сожмутся обратно",
            perRow * MIN_DP <= 320,
        )
    }
}
