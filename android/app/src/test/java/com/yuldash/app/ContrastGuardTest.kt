package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контраст надписей — правило доступности из `CLAUDE.md` §4.5: обычный текст ≥ 4.5:1,
 * иконки и рамки ≥ 3:1. До этого сторожа его никто не проверял, и вот что нашлось:
 *
 * | Что | Светлая | Тёмная |
 * |---|---|---|
 * | Белая надпись на главной кнопке (`CanonGreen2`) | 6.61 | **3.19** |
 * | Белая надпись на опасной кнопке (`CanonRed`) | 5.36 | **3.31** |
 * | Подпись «Такси» на своей жёлтой подложке | **1.97** | 6.97 |
 * | Золотой ярлык рекламы на золотом чипе | **1.63** | 6.73 |
 *
 * Что это значит для человека. Ночью, в тёмной теме, надписи «Заказать» и «Отменить поездку»
 * читались хуже нормы на КАЖДОМ экране: подложка кнопки в тёмной теме светлеет (иначе кнопка
 * сливается с фоном), а надпись оставалась белой. А слово «Такси» в шапке было жёлтым по
 * жёлтому — то есть почти невидимым.
 *
 * Почему тест, а не глаз. Такое не ловится взглядом: «бледновато, но читается» — обычная
 * реакция зрячего человека при хорошем свете. На солнце, с уставшими глазами или при слабом
 * зрении «бледновато» превращается в «не вижу». Цифра либо ≥ 4.5, либо нет.
 *
 * Тест читает `CanonTokens.kt` и считает контраст по формуле WCAG 2.1 прямо из hex-значений —
 * никакого рендера и эмулятора не нужно. Поэтому он быстрый и работает в облаке.
 */
class ContrastGuardTest {

    // ---------- разбор токенов ----------

    private fun tokensFile(): File {
        var dir = File("").absoluteFile
        repeat(4) {
            for (rel in listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app")) {
                val f = File(File(dir, rel), "CanonTokens.kt")
                if (f.isFile) return f
            }
            dir = dir.parentFile ?: return File("нет")
        }
        return File("нет")
    }

    /** Имя токена → (тёмная, светлая) в ARGB. Плоские токены дают одинаковую пару. */
    private fun tokens(): Map<String, Pair<Long, Long>> {
        val src = tokensFile().readText()
        val out = mutableMapOf<String, Pair<Long, Long>>()
        val adaptive = Regex(
            """internal val (Canon\w+): Color @Composable get\(\) = if \(appIsDark\(\)\) Color\(0x([0-9A-Fa-f]{8})\) else Color\(0x([0-9A-Fa-f]{8})\)"""
        )
        for (m in adaptive.findAll(src)) {
            out[m.groupValues[1]] = m.groupValues[2].toLong(16) to m.groupValues[3].toLong(16)
        }
        val flat = Regex("""internal val (Canon\w+): Color = Color\(0x([0-9A-Fa-f]{8})\)""")
        for (m in flat.findAll(src)) {
            val v = m.groupValues[2].toLong(16)
            out[m.groupValues[1]] = v to v
        }
        return out
    }

    // ---------- формула WCAG 2.1 ----------

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    private fun luminance(argb: Long): Double =
        0.2126 * channel(((argb shr 16) and 0xFF).toInt()) +
            0.7152 * channel(((argb shr 8) and 0xFF).toInt()) +
            0.0722 * channel((argb and 0xFF).toInt())

    /** Полупрозрачный цвет поверх непрозрачного — иначе контраст считается по несуществующему цвету. */
    private fun flatten(fg: Long, bg: Long): Long {
        val a = ((fg shr 24) and 0xFF) / 255.0
        if (a >= 1.0) return fg
        fun ch(shift: Int): Long {
            val f = (fg shr shift) and 0xFF
            val b = (bg shr shift) and 0xFF
            return Math.round(f * a + b * (1 - a))
        }
        return (0xFFL shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun ratio(fg: Long, bg: Long): Double {
        val f = luminance(flatten(fg, bg))
        val b = luminance(bg)
        val hi = maxOf(f, b)
        val lo = minOf(f, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** Цвет поверх подложки с прозрачностью (тонированный чип `accent.copy(alpha = a)`). */
    private fun tint(color: Long, alpha: Double, under: Long): Long =
        flatten((Math.round(alpha * 255) shl 24) or (color and 0xFFFFFF), under)

    // ---------- проверки ----------

    private data class Pairing(val what: String, val fg: String, val bg: String, val min: Double)

    /** Надписи: 4.5:1. Каждая пара реально встречается в экранах. */
    private val textPairs = listOf(
        Pairing("основной текст на фоне", "CanonText", "CanonBg", 4.5),
        Pairing("основной текст на карточке", "CanonText", "CanonSurface", 4.5),
        Pairing("приглушённый текст на фоне", "CanonMuted", "CanonBg", 4.5),
        Pairing("приглушённый текст на карточке", "CanonMuted", "CanonSurface", 4.5),
        Pairing("надпись на главной кнопке", "CanonOnFilledProbe", "CanonGreen2", 4.5),
        Pairing("надпись на опасной кнопке", "CanonOnFilledProbe", "CanonRed", 4.5),
        Pairing("надпись кнопки «Поддержать»", "CanonGoldInk", "CanonGold", 4.5),
        Pairing("предупреждение на своей подложке", "CanonWarn", "CanonWarnBg", 4.5),
        Pairing("ошибка на своей подложке", "CanonRed", "CanonDangerBg", 4.5),
        Pairing("«женский» текст на своей подложке", "CanonWoman", "CanonWomanBg", 4.5),
        Pairing("курьерский текст на своей подложке", "CanonCourier", "CanonCourierBg", 4.5),
        Pairing("госномер в бейдже такси", "CanonTaxiText", "CanonTaxiBg", 4.5),
        Pairing("зелёный текст на мяте", "CanonGreen", "CanonMint", 4.5),
        Pairing("текст на зелёной карточке", "CanonBg", "CanonGreen2", 4.5),
    )

    @Test
    fun `сторож видит токены`() {
        val t = tokens()
        assertTrue("не разобрал CanonTokens.kt — сторож ничего не проверяет", t.size > 20)
        assertTrue("нет CanonGreen2 — разбор сломался", t.containsKey("CanonGreen2"))
    }

    @Test
    fun `надписи читаются в обеих темах`() {
        val t = tokens()
        // CanonOnFilled — вычисляемый токен (равен CanonBg), в hex его нет: подставляем сам CanonBg.
        val probe = t.getValue("CanonBg")
        val bad = mutableListOf<String>()
        for (p in textPairs) {
            val fg = if (p.fg == "CanonOnFilledProbe") probe else t[p.fg] ?: continue
            val bg = t[p.bg] ?: continue
            for ((i, mode) in listOf(0 to "тёмная", 1 to "светлая")) {
                val f = if (i == 0) fg.first else fg.second
                val b = if (i == 0) bg.first else bg.second
                val r = ratio(f, b)
                if (r < p.min) bad += "%-34s %-8s %.2f:1  (нужно %.1f)".format(p.what, mode, r, p.min)
            }
        }
        assertTrue(
            "надписи ниже нормы контраста (CLAUDE.md §4.5) — ${bad.size} шт.:\n" + bad.joinToString("\n"),
            bad.isEmpty(),
        )
    }

    @Test
    fun `подпись на тонированном чипе своего же цвета читается`() {
        // Приём «чип цвета акцента + подпись тем же акцентом». Для жёлтых он даёт 1.6–2.0:1,
        // поэтому в коде подпись берётся через canonChipInk() — этот тест держит правило.
        val t = tokens()
        val ink = t.getValue("CanonTaxiText")
        val bad = mutableListOf<String>()
        for (name in listOf("CanonTaxi", "CanonGold", "CanonStar")) {
            val accent = t[name] ?: continue
            for ((i, mode) in listOf(0 to "тёмная", 1 to "светлая")) {
                val a = if (i == 0) accent.first else accent.second
                val page = if (i == 0) t.getValue("CanonBg").first else t.getValue("CanonBg").second
                val chip = tint(a, 0.15, page)
                val own = ratio(a, chip)
                val fixed = ratio(if (i == 0) ink.first else ink.second, chip)
                // Смысл проверки: если своим цветом нечитаемо, то канонические чернила обязаны читаться.
                if (own < 4.5 && fixed < 4.5) {
                    bad += "%-12s %-8s своим цветом %.2f, чернилами %.2f — оба ниже 4.5".format(name, mode, own, fixed)
                }
            }
        }
        assertTrue("нет читаемого варианта подписи на тонированном чипе:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `иконки и рамки различимы (3 к 1)`() {
        val t = tokens()
        val bad = mutableListOf<String>()
        val iconPairs = listOf(
            Pairing("звезда рейтинга на фоне", "CanonStar", "CanonBg", 3.0),
            Pairing("звезда рейтинга на карточке", "CanonStar", "CanonSurface", 3.0),
            // `CanonTaxi` на светлом фоне даёт 2.09 — и это НЕ нарушение: такси-жёлтый стоит
            // только как декоративная иконка рядом со словом «Такси» (contentDescription = null),
            // а правило 3:1 распространяется на графику, без которой смысл теряется. Требовать
            // его здесь — значит перекрасить фирменный цвет ради проверки, которая не про людей.
            // Опасен другой случай — жёлтый как ЦВЕТ ТЕКСТА; его ловит отдельный тест ниже.
            Pairing("акцент курьера на фоне", "CanonCourier", "CanonBg", 3.0),
            Pairing("зелёный акцент на фоне", "CanonGreen2", "CanonBg", 3.0),
            Pairing("красный акцент на фоне", "CanonRed", "CanonBg", 3.0),
        )
        for (p in iconPairs) {
            val fg = t[p.fg] ?: continue
            val bg = t[p.bg] ?: continue
            for ((i, mode) in listOf(0 to "тёмная", 1 to "светлая")) {
                val r = ratio(if (i == 0) fg.first else fg.second, if (i == 0) bg.first else bg.second)
                if (r < p.min) bad += "%-32s %-8s %.2f:1  (нужно %.1f)".format(p.what, mode, r, p.min)
            }
        }
        assertTrue("иконки/рамки ниже 3:1 — ${bad.size} шт.:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /** Каталог исходников экранов — для тестов, которые читают код, а не считают цвета. */
    private fun sources(): List<File> {
        var dir = File("").absoluteFile
        repeat(4) {
            for (rel in listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app")) {
                val d = File(dir, rel)
                if (d.isDirectory) return d.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
            dir = dir.parentFile ?: return emptyList()
        }
        return emptyList()
    }

    @Test
    fun `жёлтый акцент не бывает цветом надписи`() {
        // Так и нашлось: водителю писали «Ты будешь первым здесь» цветом CanonTaxi по белой
        // карточке — 2.19:1. Фраза мотивирующая, её и надо прочитать. Как заливка и как иконка
        // жёлтый уместен; как текст — нет. Тёплый читаемый вариант уже есть: CanonWarn.
        val re = Regex("""color\s*=\s*(CanonTaxi|CanonGold|CanonStar)\s*,\s*fontSize""")
        val hits = sources().flatMap { f ->
            f.readText().lineSequence().withIndex()
                .filter { (_, line) -> re.containsMatchIn(line) }
                .map { (i, line) -> "${f.name}:${i + 1}  ${line.trim().take(80)}" }
                .toList()
        }
        assertTrue(
            "жёлтый акцент как цвет текста — ${hits.size} шт. (2.1–2.2:1 на светлом фоне). " +
                "Возьми CanonWarn, а на тонированном чипе — canonChipInk():\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    @Test
    fun `главная кнопка не красится белым в обход токена`() {
        // Именно так дефект и появился: белый выглядит «правильным» цветом для кнопки,
        // пока не посмотришь на тёмную тему. Ловим возврат к нему.
        var dir = File("").absoluteFile
        var srcDir: File? = null
        repeat(4) {
            for (rel in listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app")) {
                val d = File(dir, rel)
                if (d.isDirectory && srcDir == null) srcDir = d
            }
            dir = dir.parentFile ?: return@repeat
        }
        val files = srcDir?.walkTopDown()?.filter { it.isFile && it.extension == "kt" }?.toList().orEmpty()
        val re = Regex("""containerColor\s*=\s*(CanonGreen2|CanonRed)\s*,\s*contentColor\s*=\s*(Color\.White|CanonOnAccent)""")
        val hits = files.flatMap { f ->
            re.findAll(f.readText()).map { m ->
                "${f.name}: ${m.groupValues[2]} на ${m.groupValues[1]} — возьми CanonOnFilled"
            }
        }
        assertTrue("белая надпись на адаптивной заливке — ${hits.size} шт.:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
