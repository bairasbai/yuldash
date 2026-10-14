package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Сторож двуязычия. Читает исходники экранов и не даёт приложению снова заговорить
 * по-русски там, где обещан башкирский.
 *
 * Зачем именно так. Правило «любая надпись — сразу на двух языках» записано в правилах
 * проекта первым пунктом, но держалось только на внимательности. Проверка исходников
 * 2026-08-04 показала, чем это кончилось: **143 надписи, где в башкирское поле скопирован
 * русский текст**. То есть человек переключил язык на башкирский, а видит «Водитель»,
 * «Комментарий», «Заявка», «Пауза». Приложение обещает родной язык и не даёт его.
 *
 * Что делает этот тест:
 *  • **не пускает пустой башкирский** — таких сейчас ноль, пусть так и остаётся;
 *  • **не пускает русский текст мимо `appText`** — надпись прямо в `Text("Привет")`
 *    непереводима в принципе, её не увидит даже переводчик;
 *  • **то же для `text = …`, многострочного `Text(`, `contentDescription = …`, `Icon(i, "…")`,
 *    `Image(p, "…")` и `Toast.makeText(…, "…", …)`** — подпись для TalkBack и всплывающая подсказка
 *    тоже надписи (с 13.10.2026, планка нулевая);
 *  • **не даёт расти долгу** по скопированным словам: сегодняшние 143 зафиксированы,
 *    144-я уронит сборку.
 *
 * Почему долг не закрыт прямо здесь. Башкирский, который придумала модель, — это черновик.
 * Подтвердить его может только носитель, и это Александр. Список из 87 слов лежит
 * в `docs/tasks.md`, раздел «Переводы на проверку». По мере подтверждения слов число
 * ниже уменьшается — планка опускается и обратно не поднимается.
 */
class BilingualSourceGuardTest {

    /**
     * Сколько надписей сейчас дублируют русский в башкирском поле.
     * Планка-храповик: расти нельзя, уменьшать — нужно. Замер 2026-08-06 (было 143 на 2026-08-04).
     *
     * Минус 21 — перенос переводов из ветки `audit-refactor-branches` при сборке релизной ветки:
     * Водитель → «Йөрөтөүсе», Заявка → «Ғариза», Комментарий → «Аңлатма», Маршрут → «Юл»,
     * Пауза → «Туҡтатылған». Черновик носителем ещё не подтверждён (docs/tasks.md).
     *
     * Ещё минус 1 при слиянии веток монетизации и паритета (2026-08-31). Обе держали ровно
     * 122, но РАЗНЫЕ: у одной в долгу висел «ОСГОП», у другой — образец телефона
     * «+7 999 000-00-00». Вместе вышло 123, и сторож упал. Переводить там нечего: первое —
     * аббревиатура закона, второе — цифры. Оба ушли в `noTranslationNeeded`, планка — на 121.
     *
     * Урок на будущее: при слиянии веток храповик надо пересчитывать, а не верить, что
     * одинаковое число с обеих сторон означает одинаковый долг.
     *
     * Минус 17 при вливании ветки премиального такси (2026-09-05). Двумя разными способами.
     * Пять надписей перевели по живому образцу проекта: «Маршрут» → «Юл» (так уже в пяти
     * местах), «Багаж» → «Йөк» (ср. «Йөк транспорты»), «бюджет $it ₽» → «бюджет $it һум»
     * (ср. «$price һум»). Остальные ушли в `sameInBothLanguages` — это заимствования,
     * которые в башкирском пишутся так же, и у каждого рядом стоит подтверждённая
     * башкирская фраза с этим словом. Черновики — в `docs/tasks.md`.
     */
    private val knownRussianInBashkir = 104

    /**
     * Надписи, которым перевод не нужен: латиница, аббревиатуры, знаки.
     * Каждое — с причиной, иначе список превратится в свалку.
     */
    private val noTranslationNeeded = setOf(
        "SOS",          // международный сигнал, одинаков везде
        "CTR",          // рекламный термин в кабинете, латиница
        "СБП",          // название системы платежей, имя собственное
        "—",            // тире-заглушка «нет данных»
        "Эмодзи",       // подпись к ряду смайликов; черновик перевода — в tasks.md
        "ОСГОП",        // страховка перевозчика по закону, аббревиатура — как СБП
        "+7 999 000-00-00", // образец телефона в подсказке поля, одни цифры
    )

    /**
     * Заимствования, которые в башкирском пишутся ровно как в русском.
     *
     * Это НЕ долг и не поблажка. У каждого слова рядом стоит уже подтверждённый носителем
     * перевод, где это же слово стоит внутри башкирской фразы — значит, слово башкирское,
     * а не забытое. Придумать ему «другое» башкирское написание — сделать текст хуже:
     * человек увидит слово, которого в языке нет.
     *
     * Правило добавления: пускать сюда можно только со ссылкой на живую строку проекта.
     * Нет такой строки — слово остаётся в долге и ждёт носителя (`docs/tasks.md`).
     */
    private val sameInBothLanguages = setOf(
        "Пассажир",   // ср. «Пассажир ултырҙы», «Пассажир машинала»
        "Заказ",      // ср. «Заказ ябылды», «Заказ ҡабул ителмәне»
        "Тариф",      // ср. «Тарифтар юҡ», «Күтәреү тарифы»
        "Комиссия",   // ср. «Комиссия түләнгән», «Юлдаш комиссияһы»
        "Чат",        // ср. «Чат 48 сәғәткә асылды»
        "Смена",      // ср. «Руль артындағы смена»
        "Бюджет",     // ср. «Һинең бюджетың», «Бюджетты күрһәт»
        "Система",    // ср. «Юлдаш системаһы менән һаҡлана»
        "Эконом",     // название тарифа — имя собственное, как «СБП»
        "Реклама",    // ср. «Реклама кабинеты», «Юлдашта реклама»
        "сек",        // сокращение секунд; ср. «${message.durationSec} сек · ▶ баҫ»
        "₽",          // знак валюты, как тире-заглушка
        // Те же слова плюс число или подстановка — переводить в них нечего.
        "Пассажир ★ \$stars",
        "Заказ № \${order.id}",
        "Заказ № \${it.orderId}",
        "\$it+ мин",
        "\$minutes мин",
        "Зона: \${instantWaitingZoneName(zone)}",  // ср. «Зона: яҡын тирә-яҡ»
    )

    /** Русский текст прямо в `Text(...)` — осознанные исключения, все пять. */
    private val hardcodedExceptions = setOf(
        "Юлдаш",          // название приложения — имя собственное, не переводится
        "Юлдаш ✓",        // бейдж официального сообщения
        "Юлдаш © 2026",   // копирайт в настройках
        "Х123УХ102",      // пример автономера в подсказке поля — образец, а не текст
        "Аа",             // образец букв в выборе размера шрифта (SecondaryScreens) — одинаков в обоих языках
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

    private val strLiteral = """"((?:[^"\\]|\\.)*)""""
    private val appTextCall = Regex("""appText(?:For)?\(\s*(?:[a-zA-Z]+\s*,\s*)?$strLiteral\s*,\s*$strLiteral""")
    private val plainText = Regex("""\bText\(\s*$strLiteral""")
    private val cyrillic = Regex("""[а-яА-ЯёЁ]""")

    @Test
    fun `исходники экранов на месте — иначе сторож ничего не проверяет`() {
        assertTrue("не нашёл исходники экранов", screens().size > 30)
    }

    @Test
    fun `башкирское поле нигде не пустое`() {
        val bad = mutableListOf<String>()
        screens().forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                appTextCall.findAll(line).forEach { m ->
                    val ru = m.groupValues[1]
                    val ba = m.groupValues[2]
                    if (ba.isBlank() && ru.isNotBlank()) bad += "${f.name}:${i + 1}  «$ru» → башкирского нет"
                }
            }
        }
        assertTrue(
            "Надпись без башкирского перевода — ${bad.size} шт.\n" +
                "Пустое поле хуже скопированного русского: человек увидит пустоту.\n" +
                bad.take(20).joinToString("\n") { "    $it" },
            bad.isEmpty(),
        )
    }

    @Test
    fun `русский текст не пишется прямо в Text — иначе его некому переводить`() {
        val bad = mutableListOf<String>()
        screens().forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                plainText.findAll(line).forEach { m ->
                    val s = m.groupValues[1]
                    if (cyrillic.containsMatchIn(s) && s !in hardcodedExceptions) {
                        bad += "${f.name}:${i + 1}  «$s»"
                    }
                }
            }
        }
        assertTrue(
            "Надпись мимо appText — ${bad.size} шт. Её не увидит ни переводчик, ни переключатель языка:\n" +
                bad.take(20).joinToString("\n") { "    $it" },
            bad.isEmpty(),
        )
    }

    @Test
    fun `долг по непереведённым словам не растёт`() {
        val duplicated = mutableListOf<String>()
        screens().forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                appTextCall.findAll(line).forEach { m ->
                    val ru = m.groupValues[1]
                    val ba = m.groupValues[2]
                    if (ru.isNotBlank() && ru == ba &&
                        ru.trim() !in noTranslationNeeded &&
                        ru.trim() !in sameInBothLanguages
                    ) {
                        duplicated += "${f.name}:${i + 1}  «$ru»"
                    }
                }
            }
        }
        assertTrue(
            "Надписей с русским в башкирском поле стало ${duplicated.size}, а было $knownRussianInBashkir.\n" +
                "Новую надпись нельзя добавлять с копией русского: башкироязычный человек увидит русский.\n" +
                "Не знаешь перевода — положи черновик в docs/tasks.md, раздел «Переводы на проверку».\n" +
                duplicated.takeLast(10).joinToString("\n") { "    $it" },
            duplicated.size <= knownRussianInBashkir,
        )
        // Долг закрывают — планку опускают. Иначе она перестанет что-либо ловить.
        assertTrue(
            "Долг сократился до ${duplicated.size}. Опусти knownRussianInBashkir до этого числа, " +
                "иначе планка снова разрешит рост.",
            duplicated.size >= knownRussianInBashkir - 10,
        )
    }

    @Test
    fun `разбор надписей вообще работает`() {
        // Страховка от «зелено, потому что регулярка ничего не нашла»: если разбор сломается,
        // все проверки выше позеленеют на пустых списках, и мы этого не заметим.
        var found = 0
        screens().forEach { f ->
            f.readLines().forEach { line -> found += appTextCall.findAll(line).count() }
        }
        assertTrue("Из экранов вынули всего $found надписей — разбор сломался", found > 1000)
    }

    // ---------- надписи вне Text("…"): text =, contentDescription, Toast (13.10.2026) ----------
    //
    // Сторож выше видел только `Text("…")` в одной строке. Мимо шли `Text(text = "…")`, многострочный
    // `Text(`, подпись для TalkBack (её слышит незрячий человек — это тоже надпись: `contentDescription =`
    // и второй аргумент `Icon(…)`/`Image(…)`) и всплывающие Toast. Сейчас одноязычных среди них ноль —
    // планка нулевая. Разбор — через SourceScan: комментарии и `?text=` внутри адреса не считаются.
    //
    // Правило одно на все формы: в выражении не должно быть русского в кавычках вне `appText(…)`.
    // Ветка по `AppLanguage` (`if (language == AppLanguage.Ba) "…" else "…"`) — тоже выбор языка.
    // Вложенный блок `{ … }` с выбором языка внутри (Ru/Ba собраны заранее) — не тревога.
    // НЕ видит (честно): надпись, собранную заранее в переменную (`val msg = "…"; Toast(…, msg, …)`),
    // русский в блоке `{ … }`, где рядом уже есть `appText` (сторож не различает, что к чему), и
    // выражение, перенесённое на новую строку сразу после `if (…)`.

    private val languageBranch = Regex("""\bAppLanguage\b""")
    // `appText` и помощники, которые сами зовут его и получают оба языка аргументами
    // (`countText(n, "файл", "файла", "файлов", "файл")` — последний аргумент башкирский).
    // Новый такой помощник — допиши сюда, иначе его русские аргументы сочтутся одноязычными.
    private val appTextOpen = Regex("""\b(?:appText|appTextFor|countText)\(""")
    private val named = Regex("""^\s*\w+\s*=(?!=)""")

    /** Выражение справа от `=` в [code]: до запятой, закрывающей скобки или конца строки на нулевой глубине. */
    private fun exprAfter(code: String, from: Int): IntRange {
        // Значение может стоять и на следующей строке: `contentDescription =\n    "Назад"`.
        var start = from
        while (start < code.length && code[start].isWhitespace()) start++
        var depth = 0
        var i = start
        fun nextWord(): String {
            var j = i + 1
            while (j < code.length && code[j].isWhitespace()) j++
            return code.substring(j, minOf(j + 4, code.length))
        }
        while (i < code.length) {
            val c = code[i]
            if (c == '(' || c == '[' || c == '{') {
                depth++
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) break
                depth--
            } else if (c == ',' && depth == 0) {
                break
            } else if (c == '\n' && depth == 0) {
                val before = code.substring(start, i).trimEnd()
                val after = nextWord()
                val goesOn = listOf("else", "+", "?:").any { before.endsWith(it) } ||
                    listOf("else", "+", "?:", ".").any { after.startsWith(it) }
                if (!goesOn) break
            }
            i++
        }
        return start until i
    }

    /**
     * Текст выражения [range], если в нём есть русский в кавычках, не покрытый выбором языка; иначе null.
     * Русский «в кавычках» — там, где в `code` текст стёрт, а в `plain` стоит кириллица: имя переменной
     * `текст` в `serverSaid(it, текст)` — не надпись. Строка из одного исключения («Юлдаш») — не тревога.
     */
    private fun oneLanguageExpr(code: String, plain: String, range: IntRange, allowed: Set<String>): String? {
        if (range.isEmpty()) return null
        val expr = code.substring(range.first, range.last + 1)
        if (languageBranch.containsMatchIn(expr)) return null
        val covered = BooleanArray(range.last - range.first + 1)
        // Внутри вложенного блока `{ … }` — свой код: там варианты строят по языкам заранее
        // (`val разборRu = …; val разборBa = …; appText(…$разборRu…, …$разборBa…)`), а надписи
        // в лямбдах (`text = { Text("…") }`) проверяет их собственный вызов `Text(`.
        // Блок прощается, только если в нём самом есть выбор языка — иначе `when (s) { A -> "Ждёт" }`,
        // `name.ifBlank { "Гость" }` или `if (a) { "Да" } else { "Нет" }` прошли бы молча.
        var depth = 0
        var open = -1
        expr.forEachIndexed { k, ch ->
            if (ch == '{') { if (depth == 0) open = k; depth++ }
            if (ch == '}' && depth > 0) {
                depth--
                if (depth == 0 && open >= 0) {
                    val block = expr.substring(open, k + 1)
                    if (appTextOpen.containsMatchIn(block) || languageBranch.containsMatchIn(block)) {
                        for (b in open..k) covered[b] = true
                    }
                }
            }
        }
        appTextOpen.findAll(expr).forEach { m ->
            val open = range.first + m.range.last
            val close = (SourceScan.callArgs(code, open).lastOrNull()?.last ?: open) + 1
            for (i in open..minOf(close, range.last)) covered[i - range.first] = true
        }
        val russian = range.any { i -> !covered[i - range.first] && code[i] == ' ' && cyrillic.matches(plain[i].toString()) }
        if (!russian) return null
        val text = plain.substring(range.first, range.last + 1).trim().replace(Regex("""\s+"""), " ")
        val single = Regex("""^"((?:[^"\\]|\\.)*)"$""").find(text)?.groupValues?.get(1)
        return if (single != null && single in allowed) null else text
    }

    /** Одноязычные `<name> = …` в файле: «строка  выражение». */
    private fun oneLanguageAssignments(src: String, name: String, allowed: Set<String> = hardcodedExceptions): List<String> {
        val code = SourceScan.scrub(src, blankStrings = true)
        val plain = SourceScan.scrub(src, blankStrings = false)
        return Regex("""\b$name\s*=(?!=)""").findAll(code).mapNotNull { m ->
            oneLanguageExpr(code, plain, exprAfter(code, m.range.last + 1), allowed)
                ?.let { "${SourceScan.lineOf(code, m.range.first)}  $it" }
        }.toList()
    }

    /** Одноязычный позиционный аргумент номер [index] у вызовов [call] (`Text(…)`, `Icon(i, "…")`). */
    private fun oneLanguagePositional(src: String, call: String, index: Int): List<String> {
        val code = SourceScan.scrub(src, blankStrings = true)
        val plain = SourceScan.scrub(src, blankStrings = false)
        return Regex("""(?<![\w.])$call\(""").findAll(code).mapNotNull { m ->
            val arg = SourceScan.callArgs(code, m.range.last).getOrNull(index) ?: return@mapNotNull null
            if (named.containsMatchIn(code.substring(arg.first, arg.last + 1))) return@mapNotNull null
            oneLanguageExpr(code, plain, arg, hardcodedExceptions)
                ?.let { "${SourceScan.lineOf(code, m.range.first)}  $call: $it" }
        }.toList()
    }

    /** Все `Toast.makeText`: номер строки, текст сообщения и есть ли в нём русский без выбора языка. */
    private fun toastMessages(src: String): List<Triple<Int, String, Boolean>> {
        val code = SourceScan.scrub(src, blankStrings = true)
        val plain = SourceScan.scrub(src, blankStrings = false)
        return Regex("""\bToast\.makeText\(""").findAll(code).mapNotNull { m ->
            val msg = SourceScan.callArgs(code, m.range.last).getOrNull(1) ?: return@mapNotNull null
            val text = plain.substring(msg.first, msg.last + 1).trim().replace(Regex("""\s+"""), " ")
            Triple(SourceScan.lineOf(code, m.range.first), text, oneLanguageExpr(code, plain, msg, emptySet()) != null)
        }.toList()
    }

    private fun assertNone(what: String, find: (String) -> List<String>) {
        val bad = mutableListOf<String>()
        SourceScan.appSources().forEach { f -> find(f.readText()).forEach { bad += "${SourceScan.relPath(f)}:$it" } }
        assertTrue(
            "$what — ${bad.size} шт. Нужен appText(\"рус\", \"баш\"), черновой башкирский — в docs/tasks.md:\n" +
                bad.take(20).joinToString("\n") { "    $it" },
            bad.isEmpty(),
        )
    }

    @Test
    fun `надпись в Text и text = — через appText`() = assertNone("Надпись на одном языке") { src ->
        oneLanguageAssignments(src, "text") + oneLanguagePositional(src, "Text", 0)
    }

    @Test
    fun `подпись для TalkBack — на двух языках`() = assertNone("Подпись для TalkBack на одном языке") { src ->
        oneLanguageAssignments(src, "contentDescription") +
            oneLanguagePositional(src, "Icon", 1) + oneLanguagePositional(src, "Image", 1)
    }

    @Test
    fun `всплывающая подсказка Toast — на двух языках`() = assertNone("Toast на одном языке") { src ->
        toastMessages(src).filter { it.third }.map { (line, s, _) -> "$line  $s" }
    }

    @Test
    fun `разбор text, contentDescription и Toast вообще работает`() {
        val ru = "Привет"
        fun texts(src: String) = oneLanguageAssignments(src, "text") + oneLanguagePositional(src, "Text", 0)
        fun descs(src: String) = oneLanguageAssignments(src, "contentDescription") +
            oneLanguagePositional(src, "Icon", 1) + oneLanguagePositional(src, "Image", 1)
        fun toasts(src: String) = toastMessages(src).count { it.third }
        // Ловит.
        assertEquals(1, texts("Text(text = \"$ru\")").size)
        assertEquals(1, texts("Text(\n    \"$ru\",\n    style = CanonBody,\n)").size)
        assertEquals(1, texts("Text(text = if (x) \"Да\" else \"Нет\")").size)
        assertEquals(1, texts("Text(text = (\"$ru\"))").size)
        assertEquals(1, texts("Text(text = \"\"\"$ru\"\"\")").size)
        assertEquals(1, texts("Text(\"Итого: \" + appText(\"$ru\", \"Сәләм\"))").size)
        assertEquals(1, descs("Icon(i, contentDescription = \"Назад\")").size)
        assertEquals(1, descs("Icon(Icons.Default.Close, \"Закрыть\")").size)
        assertEquals(1, descs("Image(painter, \"Фото машины\", modifier = m)").size)
        assertEquals(1, toasts("Toast.makeText(ctx, \"Скопировано\", Toast.LENGTH_SHORT)"))
        assertEquals(1, toasts("Toast.makeText(\n    context,\n    \"Ошибка: \${e.message}\",\n    Toast.LENGTH_LONG,\n)"))
        assertEquals(1, toasts("Toast.makeText(ctx, \"Ошибка \" + appText(\"$ru\", \"Сәләм\"), Toast.LENGTH_SHORT)"))
        // Двуязычное, служебное и не-надписи — не тревога.
        assertEquals(0, texts("Text(text = appText(\"$ru\", \"Сәләм\"))").size)
        assertEquals(0, texts("Text(appText(\"$ru\", \"Сәләм\"), style = CanonBody)").size)
        assertEquals(0, texts("Text(text = \"Юлдаш\")").size)
        assertEquals(0, texts("Text(\"\${appText(\"Фото\", \"Сүрәт\")} 1\")").size)
        assertEquals(0, texts("Text(if (language == AppLanguage.Ba) \"Сәләм\" else \"$ru\")").size)
        assertEquals(0, texts("Uri.parse(\"https://yandex.ru/maps/?text=\" + q)").size)
        assertEquals(0, texts("// Text(text = \"$ru\")").size)
        assertEquals(0, texts("if (text == \"$ru\") return").size)
        assertEquals(0, texts("Text(if (a) { val r = \"$ru\"; val b = \"Сәләм\"; appText(r, b) } else appText(\"$ru\", \"Сәләм\"))").size)
        assertTrue(texts("AlertDialog(text = { Text(\"$ru\") })").isNotEmpty())
        assertEquals(1, texts("Text(when (s) { A -> \"Ждёт\"\n else -> \"Готово\" })").size)
        assertEquals(1, texts("Text(name.ifBlank { \"Гость\" })").size)
        assertEquals(1, texts("Text(if (a) { \"Да\" } else { \"Нет\" })").size)
        assertEquals(1, texts("Text(buildAnnotatedString { append(\"$ru\") })").size)
        assertEquals(1, descs("Icon(\n    i,\n    contentDescription =\n        \"Назад\",\n)").size)
        assertEquals(1, texts("Text(\n    text =\n        \"$ru\",\n)").size)
        assertEquals(0, descs("Modifier.semantics { contentDescription = \"\$title. \$subtitle\" }").size)
        assertEquals(0, descs("Icon(i, contentDescription = null)").size)
        assertEquals(0, descs("Icon(i, null, tint = CanonGreen)").size)
        assertEquals(0, toasts("Toast.makeText(ctx, if (language == AppLanguage.Ba) \"Сәләм\" else \"$ru\", Toast.LENGTH_SHORT)"))
        assertEquals(0, toasts("Toast.makeText(ctx, appText(\"$ru\", \"Сәләм\"), Toast.LENGTH_SHORT)"))
        assertEquals(0, toasts("Toast.makeText(context, serverSaid(it, текст), Toast.LENGTH_LONG)"))
        // На живом коде разбор обязан видеть сотни Toast, Text и подписей для TalkBack — иначе он ослеп.
        var toastCount = 0
        var textCount = 0
        var descCount = 0
        SourceScan.appSources().forEach { f ->
            val src = f.readText()
            val code = SourceScan.scrub(src, blankStrings = true)
            toastCount += toastMessages(src).size
            textCount += Regex("""(?<![\w.])Text\(""").findAll(code).count()
            descCount += Regex("""\bcontentDescription\s*=(?!=)|(?<![\w.])(?:Icon|Image)\(""").findAll(code).count()
        }
        assertTrue("нашёл всего $descCount contentDescription/Icon/Image — разбор ослеп", descCount > 200)
        assertTrue("нашёл всего $toastCount Toast — разбор ослеп", toastCount > 200)
        assertTrue("нашёл всего $textCount Text( — разбор ослеп", textCount > 1000)
    }
}
