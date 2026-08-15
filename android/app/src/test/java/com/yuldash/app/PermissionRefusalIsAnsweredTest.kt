package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Отказ в разрешении должен получать ответ, а не тишину.
 *
 * История. Гульнара жмёт «записать голосовое» — Android спрашивает микрофон, она отказывает.
 * Дальше не происходило ничего: кнопка молчит, экран не меняется. Она жмёт снова, снова
 * отказывает (диалог-то она уже видела) и решает, что приложение сломано (аудит 2026-08-08,
 * волна 103).
 *
 * В проекте для этого есть общий гейт: он сначала объясняет, ЗАЧЕМ нужно разрешение, а после
 * отказа даёт экрану сказать своё — и отдельно ловит случай «система больше не спросит»,
 * подсказывая про настройки. Геолокация в такси и в профиле водителя через него уже шла,
 * а микрофон в чате и на экране голосовой заявки — нет.
 *
 * Экран голосовой заявки тут особенно важен: он сделан для тех, кому набирать текст трудно.
 *
 * Тест читает исходники: запрос разрешения напрямую, без общего гейта, — это будущая
 * молчащая кнопка.
 */
class PermissionRefusalIsAnsweredTest {

    private val screens = File("src/main/java/com/yuldash/app")

    private fun sources(): List<File> =
        screens.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Файл, где живёт сам гейт: в нём прямой вызов законен. */
    private val GATE = "Permissions.kt"

    @Test
    fun `разрешения запрашиваются через общий гейт`() {
        val direct = sources()
            .filter { it.name != GATE }
            .filter { "ActivityResultContracts.RequestPermission()" in it.readText() }
            .map { it.name }
            .sorted()

        // Разобрано поимённо, а не спрятано оптом:
        //  • YuldashApp.kt — разрешение на УВЕДОМЛЕНИЯ. Отказ здесь — нормальный выбор
        //    человека, и объяснять его не надо: он просто не получает пуши, кнопка ничего
        //    не обещала. Навязываться с диалогом было бы хуже молчания.
        //  • остальные — геолокация: у них своё объяснение прямо на экране (карта, подача
        //    машины, «я на линии»), человек видит результат отказа сразу.
        val allowed = setOf("YuldashApp.kt", "MainActivity.kt", "CourierScreen.kt", "MapScreen.kt",
                            "CreateRideScreen.kt", "SosVerifyScreens.kt", "InstantOrderScreen.kt")
        val guilty = direct.filterNot { it in allowed }

        assertTrue(
            "эти экраны просят разрешение напрямую, минуя общий гейт: $guilty. " +
                "При отказе человек не увидит ничего — кнопка будет молчать. " +
                "Используй rememberPermissionGate(...) с onDenied.",
            guilty.isEmpty(),
        )
    }

    @Test
    fun `отказ в микрофоне объясняют словами`() {
        for (name in listOf("RidesRequestsChatScreens.kt", "AccessibilityScreens.kt")) {
            val src = File(screens, name).readText()
            assertTrue("$name больше не просит микрофон через общий гейт",
                "permission = Manifest.permission.RECORD_AUDIO" in src)
            // Проверяем не «есть слово onDenied», а что в нём что-то ПРОИСХОДИТ: пустой
            // обработчик выглядит как обработка, а ведёт себя как молчание.
            val denied = Regex("""onDenied\s*=\s*\{([^}]*)\}""").find(src)?.groupValues?.get(1)
            assertTrue("$name: обработчик отказа не найден", denied != null)
            assertTrue(
                "$name: на отказ ничего не происходит — кнопка снова будет молчать",
                denied!!.trim().isNotEmpty() && ("Toast" in denied || "show" in denied),
            )
            assertTrue(
                "$name: человеку не сказали, что делать без микрофона",
                "Без микрофона" in src,
            )
        }
    }

    @Test
    fun `ответ на отказ есть на обоих языках`() {
        val chat = File(screens, "RidesRequestsChatScreens.kt").readText()
        val idx = chat.indexOf("Без микрофона")
        assertTrue("не нашёл текст про отказ", idx > 0)

        val around = chat.substring(idx, minOf(chat.length, idx + 400))
        assertTrue(
            "к тексту про отказ нет башкирского перевода: любая надпись живёт на двух языках",
            "Микрофонһыҙ" in around,
        )
    }

    @Test
    fun `гейт по-прежнему подсказывает про настройки`() {
        val gate = File(screens, GATE).readText()
        // Ищем само ВЫЧИСЛЕНИЕ, а не упоминание: слово встречается ещё и в комментарии рядом,
        // и проверка «есть подстрока» проходила даже с выброшенной проверкой.
        val computed = Regex("""canAskAgain\s*=\s*(?:activity[^
]*
\s*)?[^
]*shouldShowRequestPermissionRationale""")
        assertTrue(
            "гейт больше не спрашивает систему, будет ли ещё диалог: человек, которому Android " +
                "перестал показывать запрос, останется без пути дальше",
            computed.containsMatchIn(gate),
        )
        assertTrue(
            "пропала подсказка про настройки приложения (или её башкирская половина)",
            "PermissionStep.Blocked" in gate && "көйләүҙәрендә" in gate,
        )
    }
}
