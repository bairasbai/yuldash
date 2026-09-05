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

    /** Текст внутри скобок; [open] — индекс открывающей. */
    private fun balanced(text: String, open: Int): String {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        return text.substring(open + 1)
    }

    /**
     * Убрать ссылки на шкалу вместе с их аргументами:
     * `CanonMotion.cascadeIn(index, max = 6)` → пусто. Что осталось — то экран придумал сам.
     */
    private fun stripCanon(expr: String): String {
        val ref = Regex("""CanonMotion\.\w+""")
        val out = StringBuilder()
        var i = 0
        while (i < expr.length) {
            val m = ref.matchAt(expr, i)
            if (m != null) {
                i = m.range.last + 1
                if (i < expr.length && expr[i] == '(') i += balanced(expr, i).length + 2
                continue
            }
            out.append(expr[i])
            i++
        }
        return out.toString()
    }

    /** Выражения, которые задают время: аргументы `tween(...)` и значения `duration`/`delayMillis`. */
    private fun timeExpressions(code: String): List<String> {
        val out = mutableListOf<String>()
        Regex("""\btween\(""").findAll(code).forEach { out += balanced(code, it.range.last) }
        Regex("""\b(?:duration|delay)Millis\s*=""").findAll(code).forEach { m ->
            val buf = StringBuilder()
            var depth = 0
            for (ch in code.substring(m.range.last + 1)) {
                if (depth == 0 && (ch == ')' || ch == ']' || ch == ',')) break
                if (ch == '(' || ch == '[') depth++
                if (ch == ')' || ch == ']') depth--
                buf.append(ch)
            }
            out += buf.toString()
        }
        return out
    }

    /**
     * Никаких сырых длительностей в экранах — ни одной, ни в каком виде.
     *
     * Сторож переписывался дважды, и оба раза по одной причине: он проверял ФОРМУ записи,
     * а не смысл.
     *
     * Версия 1 ловила только окно 100..400мс, а всё вне окна объявляла «таймером» и
     * пропускала. Мимо прошли 24 своих числа (420, 560, 700, 820, 900, 1000, 1100, 1150,
     * 1600, 4700, 9000 и каскадные сдвиги) в двадцати с лишним местах.
     *
     * Версия 2 запретила любое число, но искала его регуляркой сразу после `tween(` или
     * `delayMillis =`. Мимо прошло `delayMillis = index * 55` в `appearIn` — а это самый
     * частый помощник появления во всём приложении: число просто спряталось за именем
     * переменной.
     *
     * Поэтому теперь проверяется не форма, а ВЫРАЖЕНИЕ целиком: берём аргументы `tween(...)`
     * и значения `duration`/`delayMillis`, вырезаем из них ссылки на шкалу вместе с их
     * аргументами — и если осталась хоть одна цифра, экран придумал своё число.
     *
     * Единственное исключение — голый `0`: «без задержки» это не дизайнерское решение,
     * а его отсутствие (`delayMillis = if (play) CanonMotion.cascadeIn(i) else 0`).
     */
    @Test
    fun `длительность анимации только через CanonMotion`() {
        val numbers = Regex("""\d+""")
        val bad = mutableListOf<String>()
        screens().forEach { f ->
            if (f.name == "CanonTokens.kt") return@forEach
            f.readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//")
                if (code.trimStart().startsWith("import ")) return@forEachIndexed
                timeExpressions(code).forEach { expr ->
                    val own = numbers.findAll(stripCanon(expr)).map { it.value }.filter { it != "0" }.toList()
                    if (own.isNotEmpty()) {
                        bad += "${f.name}:${i + 1}  своё число ${own.joinToString()} — возьми ступень CanonMotion"
                    }
                }
            }
        }
        check("длительность мимо CanonMotion", bad)
    }

    /**
     * Страховка от «зелено, потому что разбор ничего не нашёл». Если `timeExpressions`
     * сломается, проверка выше позеленеет на пустых списках, и мы этого не заметим —
     * ровно так уже обожглись на сторожe двуязычия.
     */
    @Test
    fun `разбор длительностей вообще работает`() {
        assertTrue(
            "разбор tween(...) сломан",
            timeExpressions("val a by animateFloatAsState(x, tween(CanonMotion.SLOW, delayMillis = index * 55))")
                .any { stripCanon(it).contains("55") },
        )
        assertTrue(
            "`else 0` не должен считаться своим числом",
            timeExpressions("tween(CanonMotion.ENTRY, delayMillis = if (p) CanonMotion.cascadeIn(i) else 0)")
                .none { Regex("""\d+""").findAll(stripCanon(it)).any { m -> m.value != "0" } },
        )
        var found = 0
        screens().forEach { f -> f.readLines().forEach { found += timeExpressions(it.substringBefore("//")).size } }
        assertTrue("в экранах не нашлось ни одной длительности — сторож ничего не проверяет", found > 50)
    }

    /**
     * Сырые константы шкалы (`CanonMotion.NORMAL_MS`) — только внутри `CanonTokens.kt`.
     *
     * Они существуют ради хелперов `canonBreath`/`canonDrift`, которым нужно настоящее число.
     * В экране `_MS` — это обход выключателя: значение не обнулится, и анимация переживёт
     * «Удалить анимации» ровно так же, как переживала своё число.
     */
    @Test
    fun `сырые константы CanonMotion не утекают в экраны`() {
        val re = Regex("""CanonMotion\.\w+_MS""")
        val bad = mutableListOf<String>()
        screens().forEach { f ->
            if (f.name == "CanonTokens.kt") return@forEach
            f.readLines().forEachIndexed { i, line ->
                if (re.containsMatchIn(line)) bad += "${f.name}:${i + 1}  _MS минует выключатель анимаций"
            }
        }
        check("сырая константа шкалы в экране", bad)
    }

    /**
     * Бесконечную анимацию в экране не заводят руками — только через `canonBreath`/`canonDrift`.
     *
     * Обнулить её длительность недостаточно и даже вредно: `tween(0)` не останавливает
     * бесконечный цикл, а разгоняет — значение прыгает между краями каждый кадр, и человек,
     * выключивший анимации, получает не покой, а мигание. Хелперы при выключенных анимациях
     * не запускаются вовсе и отдают спокойное конечное значение.
     */
    @Test
    fun `бесконечные анимации идут через canonBreath или canonDrift`() {
        val bad = mutableListOf<String>()
        screens().forEach { f ->
            if (f.name == "CanonTokens.kt") return@forEach
            f.readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//")
                // Строка import ничего не запускает — она только называет имя. Первая версия этой
                // проверки ругалась на два мёртвых импорта в MainActivity.kt, где никакой анимации нет.
                if (code.trimStart().startsWith("import ")) return@forEachIndexed
                if (code.contains("infiniteRepeatable") || code.contains("rememberInfiniteTransition")) {
                    bad += "${f.name}:${i + 1}  бесконечная анимация мимо canonBreath/canonDrift"
                }
            }
        }
        check("бесконечная анимация заведена руками", bad)
    }

    @Test
    fun `тени только через CanonDepth`() {
        // (?::\s*Dp\s*)? — объявление параметра со ЗНАЧЕНИЕМ по умолчанию тоже задаёт тень:
        // `elevation: Dp = 1.dp` проходило мимо, потому что regex ждал сразу знак равенства.
        val re = Regex("""\w*[Ee]levation\s*(?::\s*Dp\s*)?=\s*(\d+)\.dp""")
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
