package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Содержимое `AnimatedVisibility` живёт в композиции ВСЮ анимацию ухода — уже после того,
 * как условие `visible` стало ложным. Значит, внутри нельзя опираться на то, что это условие
 * проверяет: к моменту прорисовки список уже пуст, а поле уже null.
 *
 * Как это выглядело в жизни. Карточка «Погода на маршруте» показывалась, пока сервер отдаёт
 * предупреждения: `visible = warnings.isNotEmpty()`, а внутри — `warnings.first()`. Гололёд
 * заканчивается, сервер убирает предупреждение, список пустеет, карточка начинает уезжать —
 * и в этот момент `first()` падает на пустом списке. Приложение закрывалось ровно тогда,
 * когда погода улучшилась. Заметить чтением почти невозможно: строка выглядит защищённой
 * условием, которое стоит в двадцати строках выше и работает не так, как кажется.
 *
 * Правильно: либо держать последний непустой набор (тогда карточка уезжает с тем, что человек
 * читал), либо работать через `firstOrNull`/`?.`. В «Погоде» сделано и то и другое.
 *
 * Сторож грубый и с узким окном (первые ~1200 символов тела). Если он поймает честный код —
 * не расширяй исключения молча, а посмотри: скорее всего, там правда опасное обращение.
 */
class ExitAnimationGuardTest {

    /** Обращения, которые падают на пустом/нулевом значении. */
    private val unsafeCalls = listOf(".first()", ".last()", ".maxOf", ".minOf", ".reduce", "[0]")

    /**
     * Разрешённые места — каждое с причиной. Список обязан оставаться коротким:
     * длинный список исключений означает, что правило не работает.
     */
    private val allowed = mapOf<String, String>()

    private fun sources(): List<File> {
        var dir = File("").absoluteFile
        repeat(4) {
            for (rel in listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app")) {
                val src = File(dir, rel)
                if (src.isDirectory) return src.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
            dir = dir.parentFile ?: return emptyList()
        }
        return emptyList()
    }

    @Test
    fun `сторож видит исходники`() {
        assertTrue("не нашёл исходники экранов, сторож ничего не проверяет", sources().size > 30)
    }

    @Test
    fun `внутри уезжающей анимации нет обращений, которые падают на пустом`() {
        // Условие вида `visible = список.isNotEmpty()` / `поле != null` / `флаг == true`.
        val guardCond = Regex("""isNotEmpty\(\)|!= null|== true""")
        val visibleAssign = Regex("""visible\s*=\s*([^,\n]+)""")
        val hits = mutableListOf<String>()
        for (file in sources()) {
            val text = file.readText()
            for (m in Regex("""AnimatedVisibility\(|AnimatedContent\(""").findAll(text)) {
                val chunk = text.substring(m.range.first, minOf(text.length, m.range.first + 2500))
                val cond = visibleAssign.find(chunk) ?: continue
                if (!guardCond.containsMatchIn(cond.groupValues[1])) continue
                val body = chunk.substring(cond.range.last).take(1200)
                val found = unsafeCalls.filter { body.contains(it) }
                // `!!` ищем отдельно: подстрока «!!» встречается и в текстах, поэтому только
                // как обращение к значению — «что-то!!», а не внутри кавычек.
                val bang = Regex("""\w!!""").containsMatchIn(body.replace(Regex("\"[^\"]*\""), "\"\""))
                if (found.isEmpty() && !bang) continue
                val line = text.take(m.range.first).count { it == '\n' } + 1
                val key = "${file.name}:$line"
                if (allowed.containsKey(key)) continue
                hits += "$key  visible=${cond.groupValues[1].trim().take(60)}  →  " +
                    (found + if (bang) listOf("!!") else emptyList()).joinToString(", ")
            }
        }
        assertTrue(
            "внутри анимации ухода есть обращение, которое упадёт на пустом значении — ${hits.size} шт.\n" +
                "Содержимое AnimatedVisibility живёт всю анимацию ухода, когда условие visible уже ложно.\n" +
                "Держи последний непустой набор или используй firstOrNull/?.:\n" +
                hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }
}
