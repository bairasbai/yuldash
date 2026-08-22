package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Сторож фоновых опросов. Падает, если экран крутит цикл с паузой (`while … delay(…)`),
 * но не останавливает его, когда приложение свёрнуто (`repeatOnLifecycle`).
 *
 * Зачем именно так. Аудит 2026-08-11 нашёл шесть таких циклов сразу. Дороже всех — ожидание
 * такси: статус заказа опрашивался раз в 3 секунды и продолжал опрашиваться в свёрнутом
 * приложении, то есть двести запросов за десять минут в никуда. Рядом были чат такси (15 с),
 * чат поддержки (8 с), предзаказы, карта спроса водителя и админ-панель.
 *
 * Экран, которого не видно, не должен ни ходить в сеть, ни будить процессор: о событиях на
 * погашенном экране сообщает пуш. Компилятор и тесты текстов такого не ловят — код собирается,
 * экран работает, а батарея садится молча.
 *
 * Проверка файловая (грубая, но дешёвая): если в файле экрана есть цикл с задержкой,
 * то в нём должен встречаться и `repeatOnLifecycle`. Точное сопоставление «этот цикл внутри
 * этой обёртки» потребовало бы разбора синтаксиса — а этот сторож ловит именно новый экран,
 * который завёл опрос и не подумал про фон.
 */
class PollingSourceGuardTest {

    /** Осознанные исключения: файл — причина. */
    private val allowed = mapOf(
        // Повтор сетевого запроса с задержкой (backoff) внутри самого клиента — не опрос экрана.
        "ApiClient.kt" to "backoff повторов запроса, к жизненному циклу экрана не относится",
        // Фоновые сервисы — их работа в фоне и есть смысл: держат presence и ловят офферы,
        // пока приложение свёрнуто. Останавливает их тумблер «Я на линии», а не Lifecycle.
        "TaxiLineService.kt" to "foreground-сервис линии водителя: обязан работать свёрнутым",
        "TripLocationService.kt" to "foreground-сервис живой геопозиции поездки",
        "CourierLocationService.kt" to "foreground-сервис живой геопозиции курьера",
    )

    private fun sources(): List<File> {
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

    @Test
    fun `сторож видит исходники`() {
        assertTrue("не нашёл исходники экранов, сторож ничего не проверяет", sources().size > 30)
    }

    @Test
    fun `циклы опроса стоят на паузе в фоне`() {
        val loop = Regex("""while\s*\((isActive|true)\)""")
        val bad = mutableListOf<String>()
        sources().forEach { f ->
            if (f.name in allowed) return@forEach
            val text = f.readText()
            if (!loop.containsMatchIn(text)) return@forEach
            if (!text.contains("delay(")) return@forEach
            if (text.contains("repeatOnLifecycle")) return@forEach
            // RepeatWhileVisible — штатная обвязка проекта, внутри она и есть
            // repeatOnLifecycle(RESUMED) (UiKit.kt). Файл, где опрос идёт ТОЛЬКО через неё,
            // сторож раньше считал нарушителем: слова repeatOnLifecycle в тексте нет,
            // а правило соблюдено. Ложная тревога заставляет обходить сторожа вручную —
            // а обойдённый сторож не ловит уже ничего.
            if (text.contains("RepeatWhileVisible")) return@forEach
            bad += f.name
        }
        assertTrue(
            "цикл с задержкой не останавливается в фоне — оберни его в " +
                "repeatOnLifecycle(Lifecycle.State.RESUMED). Файлы (${bad.size}):\n" +
                bad.joinToString("\n") { "    $it" },
            bad.isEmpty(),
        )
    }
}
