package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Функции безопасности должны быть во ВСЕХ трёх сценариях, а не в двух из трёх.
 *
 * Это самая живучая ошибка проекта: правило заводят там, где о нём подумали, а соседний
 * сценарий остаётся без него — и никто этого не видит, потому что «в приложении же есть».
 * История в самом коде: кнопку «Застрял на трассе» курьеру добавили отдельным заходом
 * (2026-08-06), красную SOS — тем же заходом и с той же формулировкой: «курьер едет по той
 * же зимней трассе и ОДИН, рядом нет пассажира, который заметит беду».
 *
 * Третью функцию того же ряда — предупреждение о гололёде и метели на маршруте — пропустили.
 * Её видел таксист, видел водитель попутки, видел даже тот, кто только ПУБЛИКУЕТ рейс. А
 * человек, который прямо сейчас везёт посылку по той же трассе Баймак–Сибай, не видел ничего
 * (найдено 2026-08-14). Ровно тот же класс, третий раз подряд.
 *
 * Сторож проверяет по исходникам: у каждого из трёх «дорожных» экранов есть все три вещи.
 * Он не про красоту — про то, что человек в дороге получает одинаковую помощь независимо
 * от того, везёт он пассажира, едет пассажиром или везёт посылку.
 */
class SafetyEverywhereGuardTest {

    /** Экран → как он называется человеку (для понятного текста ошибки). */
    private val roadScreens = mapOf(
        "BookingActiveTripScreen.kt" to "поездка-попутка",
        "InstantOrderScreen.kt" to "заказ такси",
        "CourierScreen.kt" to "доставка курьером",
    )

    /** Что обязано быть в каждом. Ключ — как искать в коде, значение — как объяснить. */
    private val mustHave = mapOf(
        "RoadsideHelpAction" to "кнопка «Застрял на трассе» (мягкий сигнал близким и админу)",
        "WeatherWarningCard" to "предупреждение о гололёде и метели на маршруте",
    )

    private fun sourcesDir(): File? {
        var dir = File("").absoluteFile
        repeat(4) {
            for (rel in listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app")) {
                val d = File(dir, rel)
                if (d.isDirectory) return d
            }
            dir = dir.parentFile ?: return null
        }
        return null
    }

    @Test
    fun `сторож видит исходники`() {
        val dir = sourcesDir()
        assertTrue("не нашёл каталог экранов — сторож ничего не проверяет", dir != null)
        for (name in roadScreens.keys) {
            assertTrue("не нашёл экран $name — сторож проверяет не то", File(dir, name).isFile)
        }
    }

    @Test
    fun `помощь в дороге одинакова во всех трёх сценариях`() {
        val dir = sourcesDir() ?: return
        val missing = mutableListOf<String>()
        for ((file, human) in roadScreens) {
            val text = File(dir, file).readText()
            for ((marker, what) in mustHave) {
                if (!text.contains(marker)) missing += "«$human»: нет — $what"
            }
        }
        assertTrue(
            "в дороге помощь разная в зависимости от сценария — ${missing.size} шт.:\n" +
                missing.joinToString("\n") +
                "\n\nЧеловек на трассе один и тот же, чем бы он ни был занят.",
            missing.isEmpty(),
        )
    }

    @Test
    fun `красная кнопка SOS есть в каждом сценарии`() {
        // Ищем отдельно: в каждом экране она называется по-своему (CourierSosButton,
        // SosButton, вызов SOS-экрана), поэтому сверяем по смыслу — упоминанию SOS.
        val dir = sourcesDir() ?: return
        val missing = roadScreens.filter { (file, _) ->
            !File(dir, file).readText().contains("Sos", ignoreCase = true)
        }.values
        assertTrue(
            "красной кнопки нет в сценариях: ${missing.joinToString(", ")}. " +
                "В беде человек не ходит по вкладкам искать, где она спрятана.",
            missing.isEmpty(),
        )
    }
}
