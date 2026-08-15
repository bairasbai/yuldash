package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Тач-цель ≥ 48dp — правило доступности из `CLAUDE.md` §4.5.
 *
 * Круглая кнопка-иконка обычно собрана так: кликабельная обёртка, внутри иконка с отступом.
 * Настоящий размер нажатия = размер иконки + отступ с двух сторон. `20dp + 12dp·2 = 44dp`
 * выглядит нормально на макете, но это на четверть меньше нормы по площади.
 *
 * Где это било. «Позвонить» и «Написать» в активном заказе такси: пассажир жмёт их стоя на
 * улице, водитель — за рулём, зимой в перчатках. Промах по такой кнопке — это не «неудобно»,
 * это несостоявшийся звонок в момент, когда люди друг друга ищут.
 *
 * Лечится `minimumInteractiveComponentSize()`: он расширяет ОБЛАСТЬ НАЖАТИЯ, не трогая
 * внешний вид — кружок остаётся прежним. В проекте приём уже применялся (кнопки «скопировать
 * код» в купонах и посылках), просто не везде.
 *
 * Сторож считает размер по исходникам и падает на кликабельной иконке меньше 48dp.
 * Он НЕ трогает случаи, где нажимается вся строка или карточка (`fillMaxWidth`,
 * `heightIn(min = …)`) — там иконка просто лежит внутри большой цели.
 */
class TouchTargetGuardTest {

    private fun sourcesDir(): File? {
        var dir = File("").absoluteFile
        repeat(4) {
            for (rel in listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app")) {
                val d = File(dir, rel)
                if (d.isDirectory) return d
            }
            dir = dir.parentFile ?: return null
        }
        return null
    }

    @Test
    fun `сторож видит исходники`() {
        val d = sourcesDir()
        assertTrue("не нашёл каталог экранов — сторож ничего не проверяет", d != null)
    }

    @Test
    fun `кнопка-иконка не меньше 48dp`() {
        val dir = sourcesDir() ?: return
        // Кликабельная обёртка: `Surface(` с `onClick` где-то в аргументах, или свой bounceClick.
        //
        // Первая версия требовала `onClick` СРАЗУ после `Surface(` (максимум через строку
        // `modifier = …`). Между ними спокойно живут комментарии — и сторож переставал видеть
        // кнопку вовсе. Поймал на нарочно сломанной кнопке: код испорчен, а тест зелёный.
        // Поэтому теперь `onClick` ищем в начале блока аргументов, а не впритык.
        val clickable = Regex("""Surface\(|\.bounceClick\(""")
        // Иконка внутри: отступ + размер. Настоящая цель = size + padding·2.
        val iconSize = Regex("""Modifier\.padding\((\d+)\.dp\)\.size\((\d+)\.dp\)""")
        // Если цель — вся строка/карточка, размер иконки ни при чём.
        val wholeRow = listOf(
            "minimumInteractiveComponentSize", "fillMaxWidth", "fillMaxSize",
            "heightIn(min", "sizeIn(min",
        )
        val small = mutableListOf<String>()
        for (file in dir.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val text = file.readText()
            for (m in clickable.findAll(text)) {
                val chunk = text.substring(m.range.first, minOf(text.length, m.range.first + 1000))
                // `Surface(` без onClick — просто подложка, нажимать её никто не будет.
                if (m.value.startsWith("Surface") && !chunk.take(400).contains("onClick")) continue
                // Смотрим и НАЗАД, но строго до начала СТРОКИ: `Modifier.fillMaxWidth().bounceClick(…)`
                // — цель тут вся строка, а `fillMaxWidth` стоит левее совпадения, в той же цепочке.
                //
                // Первая версия смотрела назад «на 160 символов» и стала ловить `fillMaxWidth`
                // от ВНЕШНЕГО контейнера — сторож замолчал на нарочно сломанной кнопке. То есть
                // лечение ложных срабатываний породило ложное спокойствие, что хуже. Граница
                // строки — единственный честный предел: цепочка модификаторов не переносится.
                val lineStart = text.lastIndexOf('\n', m.range.first).let { if (it < 0) 0 else it + 1 }
                val back = text.substring(lineStart, m.range.first)
                val head = back + chunk.take(320)
                if (wholeRow.any { head.contains(it) }) continue
                val ic = iconSize.find(chunk) ?: continue
                val total = ic.groupValues[2].toInt() + ic.groupValues[1].toInt() * 2
                if (total >= 48) continue
                val line = text.take(m.range.first).count { it == '\n' } + 1
                small += "${file.name}:$line  ${ic.groupValues[2]}dp иконка + ${ic.groupValues[1]}dp отступ = ${total}dp"
            }
        }
        assertTrue(
            "кнопка-иконка меньше 48dp — ${small.size} шт. (CLAUDE.md §4.5). " +
                "Добавь Modifier.minimumInteractiveComponentSize(): область нажатия вырастет, " +
                "вид не изменится:\n" + small.joinToString("\n"),
            small.isEmpty(),
        )
    }
}
