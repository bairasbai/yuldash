package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Храповик сырых отступов: `padding(16.dp)` вместо `padding(CanonSpace.lg)`.
 *
 * Зачем храповик, а не запрет. Лестница отступов `CanonSpace` (4·8·12·16·24·32·48) появилась,
 * когда экраны уже были написаны: замер 13.10.2026 — **1004** вызова `padding(…)` с числом `N.dp`
 * против **495** ссылок на `CanonSpace`. Переписать тысячу мест разом — риск сломать вёрстку
 * десятков экранов ради ноля пользы. Поэтому долг заморожен: число не растёт, только падает.
 * После слияния с `main` в тот же день (правки экранов из ревью 12.10) — **996**.
 *
 * Что считается. Вызов `padding(…)`, в аргументах которого есть число `N.dp`, кроме `0.dp`
 * («без отступа» — не дизайнерское решение). Один вызов — одна штука, сколько бы чисел в нём
 * ни было; запись числа любая — `16.dp`, `16f.dp`, `(16).dp`, `Dp(16f)`. Комментарии и текст в кавычках
 * не считаются. НЕ считаются (честно): `PaddingValues(…)`, `Arrangement.spacedBy(…)`,
 * `Spacer(Modifier.height(…))` и отступ через переменную (`val p = 16.dp; padding(p)`) — они на ревью.
 *
 * Как снижать. Трогаешь экран — переведи его отступы на лестницу: 4→`xs`, 8→`sm`, 12→`md`,
 * 16→`lg`, 24→`xl`, 32→`xxl`, 48→`huge`; число мимо лестницы (10, 14, 18…) — ближайшая ступень.
 * Стало меньше — опусти [ceiling] до нового числа в том же коммите (тест подскажет, когда пора).
 * Тест упал на росте — в новой строке возьми `CanonSpace`, а не поднимай потолок.
 */
class SpacingRatchetGuardTest {

    /** Потолок: сколько вызовов `padding(N.dp)` сейчас в коде. Расти нельзя, снижать — нужно. */
    private val ceiling = 983   // 16.10.2026: −2 вместе с рядом из 12 чипов (NearbyFiltersPanel.kt)

    /**
     * На сколько число может опуститься ниже потолка, прежде чем тест попросит опустить и потолок.
     * Запас — чтобы две ветки, каждая убравшая по паре отступов, не валили `main` после слияния;
     * больше 10 не делать — иначе после уборки можно незаметно вернуть столько же сырых отступов.
     */
    private val slack = 10

    // `16.dp`, `16f.dp`, `(16).dp`, `Dp(16f)` — одно и то же число в разных записях.
    private val rawDp = Regex(
        """(?<![\w.])(\d+(?:\.\d+)?)[fF]?\.dp\b|\(\s*(\d+(?:\.\d+)?)[fF]?\s*\)\.dp\b|\bDp\(\s*(\d+(?:\.\d+)?)[fF]?\s*\)"""
    )

    /** Вызовы `padding(…)` с сырым `N.dp` в тексте файла: номера строк. */
    private fun rawPaddings(src: String): List<Int> {
        val code = SourceScan.scrub(src, blankStrings = true)
        return Regex("""\bpadding\(""").findAll(code).mapNotNull { m ->
            val open = m.range.last
            val args = SourceScan.callArgs(code, open).joinToString(",") { code.substring(it.first, it.last + 1) }
            val own = rawDp.findAll(args).any { m -> m.groupValues.drop(1).first { it.isNotEmpty() }.toDouble() != 0.0 }
            if (own) SourceScan.lineOf(code, open) else null
        }.toList()
    }

    private fun count(): Int = SourceScan.appSources()
        .filter { SourceScan.relPath(it) != "CanonTokens.kt" }
        .sumOf { rawPaddings(it.readText()).size }

    @Test
    fun `сырых отступов не становится больше`() {
        val n = count()
        assertTrue(
            "Сырых padding(N.dp) стало $n, потолок $ceiling. В новой строке возьми CanonSpace " +
                "(4 xs · 8 sm · 12 md · 16 lg · 24 xl · 32 xxl · 48 huge), потолок не поднимай.\n" +
                "Свои новые строки: git diff origin/main -- '*.kt' | grep -E '^\\+.*padding\\(.*[0-9]\\.dp'",
            n <= ceiling,
        )
    }

    @Test
    fun `потолок опускают вслед за долгом`() {
        val n = count()
        assertTrue(
            "Сырых padding(N.dp) уже $n — опусти ceiling с $ceiling до $n, иначе потолок снова разрешит рост.",
            n >= ceiling - slack,
        )
    }

    /** Страховка от «зелено, потому что разбор ничего не нашёл». */
    @Test
    fun `разбор отступов вообще работает`() {
        val counted = listOf(
            "Modifier.padding(16.dp)",
            "Modifier.padding(horizontal = 16.dp, vertical = 8.dp)",
            "Modifier.padding(\n    start = 12.dp,\n    end = CanonSpace.lg,\n)",
            "Modifier.padding(if (wide) 10.dp else CanonSpace.md)",
            "Modifier.padding(CanonSpace.lg + 2.dp)",
            "Modifier.padding(0.5.dp)",
            "Modifier.padding(16f.dp)",
            "Modifier.padding((16).dp)",
            "Modifier.padding(Dp(16f))",
        )
        counted.forEach { assertEquals("не посчитал: $it", 1, rawPaddings(it).size) }
        val skipped = listOf(
            "Modifier.padding(CanonSpace.lg)",
            "Modifier.padding(padding)",
            "Modifier.padding(0.dp)",
            "Modifier.imePadding()",
            "LazyColumn(contentPadding = PaddingValues(16.dp))",
            "// Modifier.padding(16.dp)",
            "Text(\"padding(16.dp)\")",
        )
        skipped.forEach { assertEquals("лишнее посчитал: $it", 0, rawPaddings(it).size) }
        val n = count()
        assertTrue("в экранах не нашлось ни одного padding(N.dp) — разбор ослеп", n > 500)
    }
}
