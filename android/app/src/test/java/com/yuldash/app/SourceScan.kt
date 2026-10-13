package com.yuldash.app

import java.io.File

/**
 * Общий разбор исходников для сторожей, которые читают код экранов (`ColorSourceGuardTest`,
 * `SpacingRatchetGuardTest`, новые проверки `BilingualSourceGuardTest`).
 *
 * Зачем разбор, а не регулярка по строке. Регулярка не отличает код от комментария и от текста
 * в кавычках: `// было Color(0xFF…)` или `"https://…?text=" + q` она приняла бы за нарушение —
 * и сторож либо врёт, либо обрастает исключениями. Здесь Kotlin-текст размечается честно:
 * комментарии (`//`, `/* */`, в том числе вложенные), строки `"…"` и `"""…"""`, подстановки
 * `${…}` внутри строк (это снова код) и символы вроде `'"'`.
 */
internal object SourceScan {

    // Не KDoc: путь со звёздочками внутри `/** */` открыл бы вложенный комментарий и «съел» файл.
    // Все `.kt` приложения — папка app/src/main/java/com/yuldash/app и вглубь, откуда бы ни запустили тест.
    fun appSources(): List<File> {
        var dir = File("").absoluteFile
        repeat(4) {
            for (rel in listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app")) {
                val src = File(dir, rel)
                if (src.isDirectory) return src.walkTopDown().filter { it.extension == "kt" }.sortedBy { it.path }.toList()
            }
            dir = dir.parentFile ?: return emptyList()
        }
        return emptyList()
    }

    /** Путь от `com/yuldash/app/` — `ui/theme/Theme.kt`, а не только имя файла. */
    fun relPath(f: File): String = f.invariantSeparatorsPath.substringAfter("com/yuldash/app/")

    /** Номер строки (с 1) для позиции в тексте. */
    fun lineOf(text: String, index: Int): Int {
        var line = 1
        for (i in 0 until minOf(index, text.length)) if (text[i] == '\n') line++
        return line
    }

    private class Frame(val kind: Char) { var braces = 0 }   // 'c' — код в ${…}, 's' — "…", 'r' — """…"""

    /**
     * Текст той же длины, где комментарии заменены пробелами (переводы строк на месте — номера
     * строк и позиции не едут). При [blankStrings] пробелами становится и содержимое строк
     * в кавычках (сами кавычки и код подстановок `${…}` остаются): поиск идёт только по коду.
     */
    fun scrub(src: String, blankStrings: Boolean): String {
        val out = StringBuilder(src)
        fun blank(i: Int) {
            if (i < out.length && out[i] != '\n' && out[i] != '\r') out.setCharAt(i, ' ')
        }
        val stack = ArrayDeque<Frame>()
        val n = src.length
        var i = 0
        while (i < n) {
            val top = stack.lastOrNull()
            val c = src[i]
            if (top == null || top.kind == 'c') {
                when {
                    src.startsWith("//", i) -> while (i < n && src[i] != '\n') blank(i++)
                    src.startsWith("/*", i) -> {
                        var level = 0
                        while (i < n) {
                            if (src.startsWith("/*", i)) {
                                level++; blank(i); blank(i + 1); i += 2
                            } else if (src.startsWith("*/", i)) {
                                level--; blank(i); blank(i + 1); i += 2
                                if (level == 0) break
                            } else {
                                blank(i++)
                            }
                        }
                    }
                    src.startsWith("\"\"\"", i) -> { stack.addLast(Frame('r')); i += 3 }
                    c == '"' -> { stack.addLast(Frame('s')); i++ }
                    c == '\'' -> i = charLiteralEnd(src, i)
                    c == '{' && top != null -> { top.braces++; i++ }
                    c == '}' && top != null -> { if (top.braces == 0) stack.removeLast() else top.braces--; i++ }
                    else -> i++
                }
            } else {
                when {
                    top.kind == 's' && c == '\\' -> { if (blankStrings) { blank(i); blank(i + 1) }; i += 2 }
                    top.kind == 's' && c == '"' -> { stack.removeLast(); i++ }
                    top.kind == 'r' && src.startsWith("\"\"\"", i) -> {
                        var end = i
                        while (end < n && src[end] == '"') end++
                        if (blankStrings) for (k in i until end - 3) blank(k)   // лишние кавычки — часть текста
                        stack.removeLast(); i = end
                    }
                    src.startsWith("\${", i) -> { stack.addLast(Frame('c')); i += 2 }
                    else -> { if (blankStrings) blank(i); i++ }
                }
            }
        }
        return out.toString()
    }

    /** Позиция сразу за символьным литералом: `'a'`, `'"'`, `'\''`, `'A'`. */
    private fun charLiteralEnd(src: String, open: Int): Int {
        var i = open + 1
        if (i < src.length && src[i] == '\\') i += 2 else i++
        while (i < src.length && src[i] != '\'' && src[i] != '\n') i++
        return minOf(i + 1, src.length)
    }

    /** Позиция закрывающей кавычки строки `"…"`, открытой в [open]; подстановки `${…}` со своими строками — внутри. */
    fun stringEnd(src: String, open: Int): Int {
        var i = open + 1
        while (i < src.length) {
            when {
                src[i] == '\\' -> i += 2
                src[i] == '"' -> return i
                src.startsWith("\${", i) -> {
                    var depth = 0
                    i++
                    while (i < src.length) {
                        when (src[i]) {
                            '{' -> depth++
                            '}' -> { depth--; if (depth == 0) break }
                            '"' -> i = stringEnd(src, i)
                        }
                        i++
                    }
                    i++
                }
                else -> i++
            }
        }
        return src.length
    }

    /**
     * Аргументы вызова, чья открывающая скобка стоит в [open], — диапазоны позиций.
     * [code] — текст после [scrub] с `blankStrings = true`: запятые и скобки в строках уже стёрты.
     */
    fun callArgs(code: String, open: Int): List<IntRange> {
        val args = mutableListOf<IntRange>()
        var depth = 0
        var start = open + 1
        for (i in open until code.length) {
            when (code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) {
                        if (code.substring(start, i).isNotBlank()) args += start until i
                        return args
                    }
                }
                ',' -> if (depth == 1) { args += start until i; start = i + 1 }
            }
        }
        return args
    }
}
