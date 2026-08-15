package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Каждый вид уведомления, который шлёт сервер, должен быть узнаваем в ленте.
 *
 * Как это разъехалось. В коде ленты стояла пометка: «Таба „Система“ убрана: сервер таких
 * уведомлений не шлёт (все события — booking/ride/message)». Когда-то это была правда.
 * К 2026-08-14 сервер шлёт ОДИННАДЦАТЬ видов, и самый частый из них — посылки (15 мест
 * против 11 у поездок).
 *
 * Что из этого вышло. Фильтр «Поездки» собирал только попутку и не показывал такси: человек
 * фильтровал, не находил своё уведомление и решал, что оно не пришло. Доставку не собирал
 * никто. Своя иконка была у трёх видов из одиннадцати — остальные восемь выглядели одинаковым
 * колокольчиком, включая самый частый (посылки) и самый важный (безопасность). В списке из
 * двадцати строк иконка — это то, чем человек ищет глазами.
 *
 * Устаревший комментарий опаснее отсутствующего: он выглядит как проверенный факт, и по нему
 * принимают решения. Поэтому сторож сверяет ленту с СЕРВЕРОМ, а не с чьей-то памятью:
 * читает виды из `backend/app`, читает разбор в `SecondaryScreens.kt` и требует, чтобы у
 * каждого вида была своя иконка.
 */
class NotifTypesGuardTest {

    /**
     * Виды, которым отдельная иконка не нужна: они и есть «прочее».
     * Список должен оставаться коротким — иначе правило превратится в пожелание.
     */
    private val genericOk = setOf("system")

    private fun repoRoot(): File? {
        var dir = File("").absoluteFile
        repeat(5) {
            if (File(dir, "backend/app").isDirectory && File(dir, "android/app").isDirectory) return dir
            dir = dir.parentFile ?: return null
        }
        return null
    }

    /** Виды уведомлений, которые реально шлёт сервер (третий аргумент `push_notification`). */
    private fun serverTypes(root: File): Set<String> {
        val re = Regex("""push_notification\(\s*[^,]+,\s*[^,]+,\s*"([a-z_]+)"""")
        return File(root, "backend/app").walkTopDown()
            .filter { it.isFile && it.extension == "py" }
            .flatMap { re.findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet()
    }

    @Test
    fun `сторож видит обе стороны`() {
        val root = repoRoot()
        assertTrue("не нашёл корень репозитория — сторож ничего не проверяет", root != null)
        assertTrue("не нашёл ни одного вида уведомлений на сервере", serverTypes(root!!).size > 3)
    }

    @Test
    fun `у каждого вида уведомления своя иконка`() {
        val root = repoRoot() ?: return
        val feed = File(root, "android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt").readText()
        val block = feed.substringAfter("private fun notifIcon(").substringBefore("\n}")
        val known = Regex(""""([a-z_]+)"""").findAll(block).map { it.groupValues[1] }.toSet()
        val missing = (serverTypes(root) - known - genericOk).sorted()
        assertTrue(
            "виды уведомлений без своей иконки — ${missing.size} шт.: ${missing.joinToString(", ")}. " +
                "В ленте они все выглядят одинаковым колокольчиком, а искать человек будет глазами.",
            missing.isEmpty(),
        )
    }

    @Test
    fun `фильтр «Поездки» собирает все виды поездок`() {
        // Попутка и такси — для человека одно и то же: он куда-то ехал. Разделение на
        // booking/ride/taxi это наша внутренняя кухня, и она не должна протекать в фильтр.
        val root = repoRoot() ?: return
        val feed = File(root, "android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt").readText()
        // Строк с «"trips" ->» две: подпись выбранной вкладки и сам фильтр. Нужна вторая —
        // та, что смотрит на тип уведомления (первая версия сторожа взяла первую и обвинила
        // честный код).
        val line = feed.lineSequence().first { it.contains("\"trips\" ->") && it.contains("n.type") }
        for (kind in listOf("booking", "ride", "taxi")) {
            assertTrue(
                "фильтр «Поездки» не показывает вид «$kind» — человек не найдёт своё уведомление " +
                    "и решит, что оно не пришло:\n$line",
                line.contains("\"$kind\""),
            )
        }
    }
}
