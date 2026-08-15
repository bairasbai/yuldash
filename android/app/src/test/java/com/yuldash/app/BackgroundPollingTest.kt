package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Свернул приложение — экраны перестают ходить в сеть по кругу.
 *
 * Экраны Юлдаша переспрашивают сервер по кругу: «где машины рядом», «что с заказом», «нет ли
 * нового предложения». Обычный `LaunchedEffect` этот круг не останавливает: человек свернул
 * приложение или положил телефон в карман — а тот продолжает каждые пять секунд ходить в сеть.
 * Батарея садится молча, трафик оплачивает пользователь, а сервер получает нагрузку от людей,
 * которые в этот момент вообще не смотрят в телефон.
 *
 * Знакомая история: правило было, но не на всех дверях. Двенадцать циклов обвязку имели —
 * каждый свою копию, — а на четырёх её забыли. Теперь правило живёт в одном месте
 * (`RepeatWhileVisible` в UiKit), и этот сторож следит, чтобы новый экран его не обошёл.
 *
 * Что проверяем: любой цикл вида `while (isActive/true) { … ApiClient… delay(…) }` внутри
 * экрана должен стоять либо под `RepeatWhileVisible`, либо под ручным `repeatOnLifecycle`
 * (так сделаны старые экраны — переписывать рабочее незачем).
 *
 * Исключения перечислены поимённо и с причиной. Это не «замести под ковёр», а часть правила:
 * бывает работа, которая ОБЯЗАНА идти со свёрнутым приложением, и молчаливый список без причин
 * ровно этим и опасен — через полгода никто не вспомнит, почему строка тут.
 */
class BackgroundPollingTest {

    /** Файлы, где опрос со свёрнутым приложением — намеренный, с причиной. */
    private val намеренно = mapOf(
        "TaxiLineService.kt" to
            "это и есть фоновый сервис линии: он существует ровно для того, чтобы держать " +
            "водителя видимым серверу, когда приложение свёрнуто",
    )

    /** Циклы, оставленные под обычным LaunchedEffect осознанно: файл → кусок строки рядом. */
    private val намеренныеЦиклы = listOf(
        // Сигнал «я на линии, шлите заказы». Пропадёт — сервер через ~45с забудет водителя,
        // и тот простоит смену без заказов. Дублирует сервис линии специально, как страховка.
        "ApiClient.instantPresence",
    )

    private fun экраны(): List<File> {
        var dir = File("").absoluteFile
        repeat(4) {
            for (rel in listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app")) {
                val d = File(dir, rel)
                if (d.isDirectory) return d.walkTopDown().filter { it.extension == "kt" }.toList()
            }
            dir = dir.parentFile ?: return emptyList()
        }
        return emptyList()
    }

    @Test
    fun `опрос сервера по кругу засыпает вместе с приложением`() {
        val файлы = экраны()
        assertTrue("не нашёл исходники — сторож ничего не проверяет", файлы.size > 20)

        val нарушения = mutableListOf<String>()
        for (f in файлы) {
            if (f.name in намеренно) continue
            val строки = f.readText().split("\n")
            строки.forEachIndexed { i, строка ->
                if (!Regex("""\bwhile \((isActive|true)\)""").containsMatchIn(строка)) return@forEachIndexed
                val тело = строки.subList(i, minOf(i + 40, строки.size)).joinToString("\n")
                // Нас интересуют только циклы, которые ходят в сеть по таймеру.
                if (!тело.contains("ApiClient.") || !тело.contains("delay(")) return@forEachIndexed
                if (намеренныеЦиклы.any { тело.contains(it) }) return@forEachIndexed
                val выше = строки.subList(maxOf(0, i - 30), i).joinToString("\n")
                val подПравилом = выше.contains("RepeatWhileVisible") || выше.contains("repeatOnLifecycle")
                if (!подПравилом) нарушения += "${f.name}:${i + 1}"
            }
        }

        assertTrue(
            "эти опросы продолжают ходить в сеть со свёрнутым приложением — жгут батарею и " +
                "трафик человека, который на экран не смотрит. Оберни цикл в RepeatWhileVisible " +
                "(UiKit.kt), а если фоновая работа тут нужна намеренно — добавь причину в список " +
                "исключений этого теста:\n  " + нарушения.joinToString("\n  "),
            нарушения.isEmpty(),
        )
    }

    @Test
    fun `общая обвязка на месте и действительно про видимость экрана`() {
        // Сторож на проводку: если RepeatWhileVisible превратится в обычный LaunchedEffect,
        // верхний тест останется зелёным, а опросы снова начнут жить в фоне — молча.
        val ui = экраны().firstOrNull { it.name == "UiKit.kt" }?.readText().orEmpty()
        assertTrue("не нашёл UiKit.kt", ui.isNotEmpty())
        val тело = ui.substringAfter("internal fun RepeatWhileVisible", "")
        assertTrue("RepeatWhileVisible пропал из UiKit.kt", тело.isNotEmpty())
        assertTrue(
            "RepeatWhileVisible больше не привязан к видимости экрана — обвязка стала пустышкой",
            тело.take(600).contains("repeatOnLifecycle") && тело.take(600).contains("RESUMED"),
        )
    }
}
