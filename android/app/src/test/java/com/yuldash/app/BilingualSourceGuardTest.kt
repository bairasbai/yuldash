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
 *  • **то же для `text = "…"`, `contentDescription = "…"` и `Toast.makeText(…, "…", …)`** —
 *    подпись для TalkBack и всплывающая подсказка тоже надписи (с 13.10.2026, планка нулевая);
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

    /** Русский текст прямо в `Text(...)` — осознанные исключения, все четыре. */
    private val hardcodedExceptions = setOf(
        "Юлдаш",          // название приложения — имя собственное, не переводится
        "Юлдаш ✓",        // бейдж официального сообщения
        "Юлдаш © 2026",   // копирайт в настройках
        "Х123УХ102",      // пример автономера в подсказке поля — образец, а не текст
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

    // ---------- надписи вне Text(...): text =, contentDescription =, Toast (13.10.2026) ----------
    //
    // Сторож выше видел только `Text("…")` в одной строке. Мимо шли `Text(text = "…")`, подпись
    // для TalkBack (её слышит незрячий человек — это тоже надпись) и всплывающие Toast. Сейчас
    // одноязычных среди них ноль — планка нулевая. Разбор — через SourceScan: комментарии
    // и `?text=` внутри адреса в кавычках не считаются.

    /** Выбор языка в выражении: `appText(…)` или ветка по `AppLanguage`. */
    private val languageSwitch = Regex("""\bappText(?:For)?\(|\bAppLanguage\b""")

    /** Строка-литерал сразу после `<name> =`: номер строки и текст в кавычках. */
    private fun literalAssignments(src: String, name: String): List<Pair<Int, String>> {
        val code = SourceScan.scrub(src, blankStrings = true)
        val plain = SourceScan.scrub(src, blankStrings = false)
        return Regex("""\b$name\s*=\s*"""").findAll(code).map { m ->
            val open = m.range.last
            SourceScan.lineOf(code, open) to plain.substring(open + 1, SourceScan.stringEnd(plain, open))
        }.toList()
    }

    /** Все `Toast.makeText`: номер строки, текст сообщения и есть ли в нём русский без выбора языка. */
    private fun toastMessages(src: String): List<Triple<Int, String, Boolean>> {
        val code = SourceScan.scrub(src, blankStrings = true)
        val plain = SourceScan.scrub(src, blankStrings = false)
        return Regex("""\bToast\.makeText\(""").findAll(code).mapNotNull { m ->
            val msg = SourceScan.callArgs(code, m.range.last).getOrNull(1) ?: return@mapNotNull null
            val text = plain.substring(msg.first, msg.last + 1)
            // Русский — только внутри кавычек (там, где в `code` текст стёрт): имя переменной
            // `текст` в `serverSaid(it, текст)` — не надпись, сама надпись собрана выше по языку.
            val russianText = msg.any { i -> code[i] == ' ' && cyrillic.matches(plain[i].toString()) }
            val oneLanguage = russianText && !languageSwitch.containsMatchIn(code.substring(msg.first, msg.last + 1))
            Triple(SourceScan.lineOf(code, m.range.first), text.trim().replace(Regex("""\s+"""), " "), oneLanguage)
        }.toList()
    }

    private fun oneLanguageAssignments(src: String, name: String, allowed: Set<String>) =
        literalAssignments(src, name).filter { (_, s) -> cyrillic.containsMatchIn(s) && s !in allowed }

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
    fun `text = «…» с русским — мимо appText`() = assertNone("Надпись text = «…» на одном языке") { src ->
        oneLanguageAssignments(src, "text", hardcodedExceptions).map { (line, s) -> "$line  «$s»" }
    }

    @Test
    fun `подпись для TalkBack — на двух языках`() = assertNone("contentDescription на одном языке") { src ->
        oneLanguageAssignments(src, "contentDescription", emptySet()).map { (line, s) -> "$line  «$s»" }
    }

    @Test
    fun `всплывающая подсказка Toast — на двух языках`() = assertNone("Toast на одном языке") { src ->
        toastMessages(src).filter { it.third }.map { (line, s, _) -> "$line  $s" }
    }

    @Test
    fun `разбор text, contentDescription и Toast вообще работает`() {
        val ru = "Привет"
        assertEquals(1, oneLanguageAssignments("Text(text = \"$ru\")", "text", hardcodedExceptions).size)
        assertEquals(1, oneLanguageAssignments("Icon(i, contentDescription = \"Назад\")", "contentDescription", emptySet()).size)
        assertEquals(1, toastMessages("Toast.makeText(ctx, \"Скопировано\", Toast.LENGTH_SHORT)").count { it.third })
        assertEquals(1, toastMessages("Toast.makeText(\n    context,\n    \"Ошибка: \${e.message}\",\n    Toast.LENGTH_LONG,\n)").count { it.third })
        // Двуязычное, служебное и не-надписи — не тревога.
        assertEquals(0, oneLanguageAssignments("Text(text = appText(\"$ru\", \"Сәләм\"))", "text", hardcodedExceptions).size)
        assertEquals(0, oneLanguageAssignments("Text(text = \"Юлдаш\")", "text", hardcodedExceptions).size)
        assertEquals(0, oneLanguageAssignments("Uri.parse(\"https://yandex.ru/maps/?text=\" + q)", "text", hardcodedExceptions).size)
        assertEquals(0, oneLanguageAssignments("// Text(text = \"$ru\")", "text", hardcodedExceptions).size)
        assertEquals(0, oneLanguageAssignments("Modifier.semantics { contentDescription = \"\$title. \$subtitle\" }", "contentDescription", emptySet()).size)
        assertEquals(0, toastMessages("Toast.makeText(ctx, if (language == AppLanguage.Ba) \"Сәләм\" else \"$ru\", Toast.LENGTH_SHORT)").count { it.third })
        assertEquals(0, toastMessages("Toast.makeText(ctx, appText(\"$ru\", \"Сәләм\"), Toast.LENGTH_SHORT)").count { it.third })
        assertEquals(0, toastMessages("Toast.makeText(context, serverSaid(it, текст), Toast.LENGTH_LONG)").count { it.third })
        // На живом коде разбор обязан видеть сотни Toast и хоть какие-то contentDescription = "…".
        var toasts = 0
        var descriptions = 0
        SourceScan.appSources().forEach { f ->
            val src = f.readText()
            toasts += toastMessages(src).size
            descriptions += literalAssignments(src, "contentDescription").size
        }
        assertTrue("нашёл всего $toasts Toast — разбор ослеп", toasts > 200)
        assertTrue("не нашёл ни одного contentDescription = \"…\" — разбор ослеп", descriptions > 0)
    }
}
