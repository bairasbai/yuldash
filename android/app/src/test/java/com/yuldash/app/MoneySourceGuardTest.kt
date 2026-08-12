package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Сторож денег. Читает исходники экранов и падает, если сумма снова печатается
 * через целочисленное деление копеек (`somethingKop / 100`).
 *
 * Зачем именно так. Аудит 2026-08-11 нашёл эту ошибку в ОДИННАДЦАТИ местах сразу:
 * кошелёк показывал комиссию 10,96 ₽ как «10 ₽», баннер долга — 150,50 ₽ как «150 ₽»
 * (водитель переводил ровно столько и оставался должен полтинник), чек посылки не
 * сходился столбиком, а кнопка онлайн-оплаты обещала меньше, чем списывалось.
 *
 * Правильные форматтеры в проекте были всё это время — [kopToRub] и [formatTaxiKop].
 * Ни компилятор, ни тесты текстов такого не ловят: код собирается, экран выглядит
 * рабочим, и ошибка живёт до первой жалобы. Этот тест — договор в коде.
 *
 * Если тест упал — не добавляй исключение не подумав. Спроси: эта сумма правда не
 * может иметь копеек? Комиссия, скидка, плата за подачу, ожидание, цена поездки —
 * могут. Тариф из конфига и границы вывода — нет.
 */
class MoneySourceGuardTest {

    /**
     * Осознанные исключения: "файл:фрагмент строки". Каждое — с причиной.
     * Это НЕ печать суммы человеку, а арифметика над заведомо круглыми значениями.
     */
    private val allowed = setOf(
        // Границы вывода из конфига сервера (мин 100 ₽, макс 15 000 ₽) — круглые по определению,
        // и делятся тут для сравнения с введённой суммой, а не для показа копеек.
        "WalletScreen.kt:val minRub = status.minKop / 100",
        "WalletScreen.kt:val maxRub = status.maxKop / 100",
    )

    private fun screens(): List<File> {
        var dir = File("").absoluteFile
        repeat(4) {
            val src = File(dir, "app/src/main/java/com/yuldash/app")
            if (src.isDirectory) return src.walkTopDown().filter { it.extension == "kt" }.toList()
            val src2 = File(dir, "src/main/java/com/yuldash/app")
            if (src2.isDirectory) return src2.walkTopDown().filter { it.extension == "kt" }.toList()
            dir = dir.parentFile ?: return emptyList()
        }
        return emptyList()
    }

    @Test
    fun `сторож видит исходники`() {
        assertTrue("не нашёл исходники экранов, сторож ничего не проверяет", screens().size > 30)
    }

    @Test
    fun `копейки не режутся целочисленным делением`() {
        // Ищем `<что-то>Kop / 100` и `Kop/100`, но НЕ `/ 100.0` — деление на Double
        // с осознанным округлением (например, прикидка расхода топлива) законно.
        val re = Regex("""\w*[Kk]op\s*/\s*100(?!\.)""")
        val bad = mutableListOf<String>()
        screens().forEach { f ->
            f.readLines().forEachIndexed { i, raw ->
                val line = raw.trim()
                // Комментарии пропускаем: в них эта запись объясняет саму ошибку.
                if (line.startsWith("//") || line.startsWith("*")) return@forEachIndexed
                if (!re.containsMatchIn(line)) return@forEachIndexed
                if (allowed.any { it.substringBefore(':') == f.name && line.contains(it.substringAfter(':')) }) return@forEachIndexed
                bad += "${f.name}:${i + 1}  $line"
            }
        }
        assertTrue(
            "деньги печатаются через `Kop / 100` — копейки теряются. " +
                "Используй kopToRub() или formatTaxiKop(). Найдено ${bad.size}:\n" +
                bad.take(25).joinToString("\n") { "    $it" },
            bad.isEmpty(),
        )
    }
}
