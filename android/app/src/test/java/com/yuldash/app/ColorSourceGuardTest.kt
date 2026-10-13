package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сторож цвета. Читает исходники приложения и падает на любом цвете, заданном числом
 * (`Color(0xFF0B6B3A)`, `Color(255, 0, 0)`, `Color(red = 0.5f, …)`) где-либо, кроме
 * `CanonTokens.kt` и `ui/theme/`.
 *
 * Зачем. Правило «цвет — только `Canon*`» стояло в правилах проекта давно, но держалось на
 * подсказке хука после правки и на ревью — тест его не проверял. Хук видит только новые строки
 * и только у Claude; Codex, ручная правка и слияние веток проходили мимо. Ревизия 13.10.2026
 * нашла в экранах 8 своих цветов: 7 в карточке «Мой Юлдаш» и 1 на заставке. Все перенесены
 * в `CanonTokens.kt` (плоскими токенами — от темы они не зависят), список исключений пуст.
 *
 * Тест упал — заведи цвет в `CanonTokens.kt`: адаптивный (`if (appIsDark()) … else …`), если он
 * красит интерфейс, или плоский, если это часть рисунка или картинки, которая от темы не зависит.
 *
 * Чего сторож НЕ видит (честно): `Color.White`/`Color.Black` и прочие именованные цвета Compose;
 * `android.graphics.Color.parseColor("#…")` и числа `0xFF….toInt()` в рисовании на `Canvas`
 * (значки карты в `MapPins.kt`/`MapCluster.kt`, картинка-открытка в `MyStatsScreen.kt`).
 */
class ColorSourceGuardTest {

    /**
     * Осознанные исключения: путь от `com/yuldash/app/` → причина. Пусто — так и держать.
     * Цвету, которому правда нужно своё значение, место в `CanonTokens.kt`, а не здесь.
     */
    private val exceptions: Map<String, String> = emptyMap()

    private val numericColor = Regex("""\bColor\(\s*(?:\d|(?:red|green|blue|alpha)\s*=\s*\d)""")

    private fun allowedPlace(rel: String) = rel == "CanonTokens.kt" || rel.startsWith("ui/theme/")

    /** Нарушения в тексте файла: «строка: код». Комментарии и текст в кавычках не считаются. */
    private fun ownColors(src: String): List<String> {
        val code = SourceScan.scrub(src, blankStrings = true)
        val lines = src.lines()
        return numericColor.findAll(code).map { m ->
            val line = SourceScan.lineOf(code, m.range.first)
            "$line: ${lines[line - 1].trim()}"
        }.toList()
    }

    @Test
    fun `исходники на месте — иначе сторож ничего не проверяет`() {
        assertTrue("не нашёл исходники приложения", SourceScan.appSources().size > 30)
    }

    @Test
    fun `цвет числом — только в CanonTokens и теме`() {
        val bad = mutableListOf<String>()
        SourceScan.appSources().forEach { f ->
            val rel = SourceScan.relPath(f)
            if (allowedPlace(rel) || rel in exceptions) return@forEach
            ownColors(f.readText()).forEach { bad += "$rel:$it" }
        }
        assertTrue(
            "Свой цвет мимо Canon* — ${bad.size} шт. Заведи токен в CanonTokens.kt " +
                "(адаптивный для интерфейса, плоский для рисунка):\n" +
                bad.take(25).joinToString("\n") { "    $it" },
            bad.isEmpty(),
        )
    }

    @Test
    fun `исключения не залёживаются`() {
        // Исключение без нарушения — дыра: под ним можно завести новый цвет, и никто не заметит.
        val files = SourceScan.appSources().associateBy { SourceScan.relPath(it) }
        val stale = exceptions.keys.filter { rel -> files[rel]?.let { ownColors(it.readText()).isEmpty() } ?: true }
        assertTrue("исключения без нарушений — убери их: $stale", stale.isEmpty())
    }

    /**
     * Страховка от «зелено, потому что разбор ничего не нашёл»: так уже обжигались на сторожe
     * двуязычия. Разбор обязан ловить цвет в коде и пропускать его в комментарии и в тексте.
     */
    @Test
    fun `разбор цвета вообще работает`() {
        val caught = listOf(
            "val a = Color(0xFF0B6B3A)",
            "Box(Modifier.background(Color( 0x33FFFFFF )))",
            "val b = Color(255, 0, 0)",
            "val c = Color(red = 0.5f, green = 0f, blue = 0f)",
            "Text(\"https://yulbash.ru\", color = Color(0xFF000000))",
        )
        caught.forEach { assertEquals("не поймал: $it", 1, ownColors(it).size) }
        val passed = listOf(
            "// было Color(0xFF0B6B3A)",
            "/* Color(0xFF000000) */ val a = CanonGreen",
            "val s = \"Color(0xFF000000)\"",
            "val t = \"цвет: \${name}\" // Color(0x1)",
            "val c = Color(argb)",
            "val d = CanonText.copy(alpha = 0.5f)",
        )
        passed.forEach { assertEquals("ложная тревога: $it", 0, ownColors(it).size) }
        // На самом CanonTokens.kt разбор обязан увидеть десятки цветов — иначе он ослеп.
        val tokens = SourceScan.appSources().first { SourceScan.relPath(it) == "CanonTokens.kt" }
        assertTrue("в CanonTokens.kt не нашёл цветов — разбор сломан", ownColors(tokens.readText()).size > 40)
    }
}
