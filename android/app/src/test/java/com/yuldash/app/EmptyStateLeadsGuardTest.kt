package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Пустой экран, который знает дорогу, обязан ВЕСТИ, а не подсказывать.
 *
 * Как это выглядит у человека. Открыл «Мои купоны» — пусто, и написано: «Активируй скидку на
 * вкладке „Скидки рядом“». Куда идти, сказали. Кнопки нет. Он закрывает экран, ищет ту вкладку
 * глазами, иногда не находит и уходит совсем. Ровно то же было в «Везу» у курьера: «Возьми
 * заказ во вкладке „Заказы“» — и никакого способа туда попасть отсюда.
 *
 * Это не придирка к словам. Экран уже ЗНАЕТ следующий шаг: он его написал текстом. Значит
 * знание есть, а действия нет — человек делает лишнюю работу за приложение. Тот же класс,
 * что «Открой вкладку „Карта“ или „Поездки“» на экране поездок (исправлено 2026-08-12).
 *
 * Сторож ищет пустые состояния, в тексте которых есть указание дороги («на вкладке», «открой»,
 * «перейди»), но нет кнопки. Требовать кнопку ВЕЗДЕ нельзя: «Открытых сигналов нет — тишина
 * это хорошая новость» никуда вести не должно, и «Архив пока пуст» тоже.
 */
class EmptyStateLeadsGuardTest {

    /** Слова, которыми текст указывает дорогу. */
    private val pointsSomewhere = listOf("вкладк", "Открой", "открой", "Перейди", "перейди")

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
        assertTrue("не нашёл каталог экранов", sourcesDir() != null)
    }

    @Test
    fun `пустой экран, который знает дорогу, ведёт кнопкой`() {
        val dir = sourcesDir() ?: return
        val empty = Regex("""AppEmptyState\(|EmptyStateCard\(""")
        val hasAction = Regex("""actionLabel\s*=|onAction\s*=|action\s*=""")
        val silent = mutableListOf<String>()
        for (file in dir.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val text = file.readText()
            for (m in empty.findAll(text)) {
                val body = text.substring(m.range.first, minOf(text.length, m.range.first + 700)).take(600)
                if (hasAction.containsMatchIn(body)) continue
                // Смотрим ТОЛЬКО подпись самой заглушки, а не весь кусок кода вокруг.
                // Первая версия брала 600 символов целиком и ловила слово «вкладка» из соседнего
                // блока — обвинила экран, который никуда не зовёт («Споров в этом состоянии нет»).
                val caption = Regex("""text\s*=\s*appText\(\s*"([^"]*)"""").find(body)?.groupValues?.get(1) ?: continue
                if (pointsSomewhere.none { caption.contains(it) }) continue
                val line = text.take(m.range.first).count { it == '\n' } + 1
                silent += "${file.name}:$line"
            }
        }
        assertTrue(
            "пустой экран говорит, куда идти, но не ведёт — ${silent.size} шт. " +
                "Добавь actionLabel/onAction: экран уже знает следующий шаг, раз написал его словами:\n" +
                silent.joinToString("\n"),
            silent.isEmpty(),
        )
    }
}
