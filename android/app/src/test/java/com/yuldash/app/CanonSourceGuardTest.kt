package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Сторож дизайн-системы. Читает сами исходники экранов и падает, если кто-то снова
 * завёл свой кегль, свой радиус, свою длительность или вес Black.
 *
 * Зачем именно так. Полировка 2026-08-03/04 свела 27 кеглей к 6, 18 радиусов к 4,
 * 38 длительностей к 3 и убрала 474 надписи веса Black. Всё это — разовые правки:
 * ни компилятор, ни обычные тесты их не удержат, а следующий экран снова принесёт
 * «ну тут надо 15sp». Через полгода разнобой вернётся, и приложение опять будет
 * выглядеть самоделкой. Этот тест — договор в коде, а не в голове.
 *
 * Если тест упал — не добавляй исключение не подумав. Сперва спроси: моему тексту
 * правда нужен свой размер, или подойдёт ближайшая ступень? В 99 случаях из 100
 * подходит ступень.
 */
class CanonSourceGuardTest {

    private val allowedSizes = setOf(12, 14, 16, 19, 24, 34)
    private val allowedLeading = setOf(17, 20, 23, 25, 30, 40)
    private val allowedRadii = setOf(8, 14, 22, 28)

    /** Осознанные исключения: "файл:значение". Каждое — с причиной. */
    private val sizeExceptions = setOf(
        "IntroScreen.kt:44", "IntroScreen.kt:36",   // логотип-слово на заставке, не текст интерфейса
        "InstantOrderScreen.kt:44",          // эмодзи 🚕 в кружке
        "ForceUpdateScreen.kt:46",           // эмодзи 🙌 в кружке
        "RidesRequestsChatScreens.kt:23",    // эмодзи-реакция в чате
        "LoginScreen.kt:52", "LoginScreen.kt:56",   // логотип-слово на входе и его межстрочный
        "TaxiOnboardingScreen.kt:20",        // EmojiRow — эмодзи в строке правила, это картинка
        "TaxiOnboardingScreen.kt:44",        // EmojiHero — эмодзи в круге на экране статуса
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

    private fun check(what: String, bad: List<String>) {
        assertTrue(
            "$what — ${bad.size} шт.:\n" + bad.take(25).joinToString("\n") { "    $it" },
            bad.isEmpty(),
        )
    }

    @Test
    fun `в экранах нет исходников — тест бесполезен`() {
        assertTrue("не нашёл исходники экранов, сторож ничего не проверяет", screens().size > 30)
    }

    /**
     * Проверяем ВСЕ три способа задать кегль, а не только прямой. Первая версия сторожа знала
     * лишь `fontSize = 14.sp` — и пропустила девять локальных шкал (`private val TxBody = 16.sp`,
     * `object MoneyType`) и восемь условных размеров (`if (compact) 20.sp else 24.sp`).
     * Экраны жили по своим правилам, а тест показывал зелёный: худший вид проверки.
     */
    @Test
    fun `кегль только со шкалы`() {
        val bad = mutableListOf<String>()
        val spLiteral = Regex("""(\d+)\.sp""")
        val decl = Regex("""\bval\s+\w+\s*(?::\s*TextUnit\s*)?=\s*(\d+)\.sp""")
        screens().forEach { f ->
            if (f.name == "CanonTokens.kt") return@forEach
            f.readLines().forEachIndexed { i, line ->
                // запятая разделяет аргументы: так fontSize и lineHeight в одной строке не путаются
                line.split(",").forEach { part ->
                    val allowed = when {
                        part.contains("fontSize") -> allowedSizes
                        part.contains("lineHeight") -> allowedLeading
                        else -> return@forEach
                    }
                    spLiteral.findAll(part).forEach { m ->
                        val v = m.groupValues[1].toInt()
                        if (v !in allowed && "${f.name}:$v" !in sizeExceptions) {
                            bad += "${f.name}:${i + 1}  ${v}sp — шкала: ${allowed.sorted()}"
                        }
                    }
                }
                // Своя шкала в файле — тоже шкала: её ступени обязаны совпасть с общими.
                decl.find(line)?.let { m ->
                    val v = m.groupValues[1].toInt()
                    if (v !in allowedSizes && v !in allowedLeading && "${f.name}:$v" !in sizeExceptions) {
                        bad += "${f.name}:${i + 1}  своя ступень ${v}sp мимо общей шкалы"
                    }
                }
            }
        }
        check("кегль вне шкалы", bad)
    }

    @Test
    fun `радиус скругления только со шкалы`() {
        val re = Regex("""RoundedCornerShape\(\s*(\d+)\.dp""")
        val bad = mutableListOf<String>()
        screens().forEach { f ->
            if (f.name == "CanonTokens.kt") return@forEach
            f.readLines().forEachIndexed { i, line ->
                re.findAll(line).forEach { m ->
                    val v = m.groupValues[1].toInt()
                    // > 28 — приём «таблетка» (RoundedCornerShape(999.dp)), это не радиус
                    if (v <= 28 && v !in allowedRadii) {
                        bad += "${f.name}:${i + 1}  ${v}dp — шкала: ${allowedRadii.sorted()}"
                    }
                }
            }
        }
        check("радиус вне шкалы", bad)
    }

    @Test
    fun `длительность анимации только через CanonMotion`() {
        val re = Regex("""(?:\btween\(\s*|\bdurationMillis\s*=\s*)(\d+)\b""")
        val bad = mutableListOf<String>()
        screens().forEach { f ->
            if (f.name == "CanonTokens.kt") return@forEach
            f.readLines().forEachIndexed { i, line ->
                re.findAll(line).forEach { m ->
                    val v = m.groupValues[1].toInt()
                    // вне 100..400 — это не переход интерфейса, а таймер, пульс или долгий цикл
                    if (v in 100..400) bad += "${f.name}:${i + 1}  ${v}мс — возьми CanonMotion"
                }
            }
        }
        check("длительность мимо CanonMotion", bad)
    }

    @Test
    fun `тени только через CanonDepth`() {
        val re = Regex("""\w*[Ee]levation\s*=\s*(\d+)\.dp""")
        val bad = mutableListOf<String>()
        screens().forEach { f ->
            if (f.name == "CanonTokens.kt") return@forEach
            f.readLines().forEachIndexed { i, line ->
                if (re.containsMatchIn(line)) bad += "${f.name}:${i + 1}  возьми CanonDepth"
            }
        }
        check("тень мимо CanonDepth", bad)
    }

    @Test
    fun `вес Black не вернулся`() {
        val bad = mutableListOf<String>()
        screens().forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                if (line.contains("FontWeight.Black")) bad += "${f.name}:${i + 1}"
            }
        }
        check("вес Black (было 474, убрали — он читается как крик)", bad)
    }
}
