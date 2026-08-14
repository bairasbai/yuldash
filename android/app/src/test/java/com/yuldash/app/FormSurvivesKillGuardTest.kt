package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Заполненная форма должна пережить выгрузку приложения из памяти.
 *
 * Что происходит на телефоне. Водитель заполняет «Создать поездку» — откуда, куда, время,
 * места, цена, комментарий, галочки про животных и багаж. На середине звонок, или он уходит
 * в карты посмотреть адрес. На бюджетном Android система в это время спокойно выгружает
 * приложение из памяти. Он возвращается — экран тот же (номер экрана живёт в
 * `SavedStateHandle`), а поля пустые. Всё заново.
 *
 * Разница между `remember` и `rememberSaveable` ровно в этом: первый живёт, пока жив процесс,
 * второй складывает значение в системный «карман» и достаёт обратно после воскрешения.
 *
 * Правило в проекте уже было, просто не везде: «Посылки» (32 места), «Такси» (31), онбординг
 * таксиста (20), вход (10), курьер (15) — всё на `rememberSaveable`. «Создать поездку» была
 * исключением: 10 полей ввода и ни одного сохраняемого.
 *
 * Сторож читает исходники и требует, чтобы поля ввода на длинных формах оставались
 * сохраняемыми. Проверяем именно введённое человеком: загруженное с сервера и временное
 * (открыт ли пикер, идёт ли отправка) переживать выгрузку не должно.
 */
class FormSurvivesKillGuardTest {

    /** Экран → поля, которые человек вводит руками и терять которые нельзя. */
    private val guarded = mapOf(
        "CreateRideScreen.kt" to listOf(
            "from", "to", "dateTime", "seats", "price", "comment",
            "petsAllowed", "childSeat", "womenOnly", "smoking", "baggage",
            "airConditioner", "onlyTrusted", "quiet", "noMinors",
            "recurrence", "category", "receiverName", "parcelSize", "pickup",
        ),
        // Анкета бизнеса и редактор купона: заполняются один раз и вручную, потерять их
        // особенно обидно — человек уходит искать свой адрес или ИНН в другом приложении.
        "PartnerCabinetScreen.kt" to listOf(
            "name", "category", "city", "address", "phone", "description",
            "title", "discountText", "routeHint", "limitTotal", "limitPerUser",
            "validUntil", "premium",
        ),
    )

    /** Экраны, где приём уже применён. Сторож следит, чтобы его не растеряли при правках. */
    private val expectedUsers = mapOf(
        "ParcelsScreen.kt" to 20,
        "InstantOrderScreen.kt" to 20,
        "TaxiOnboardingScreen.kt" to 10,
        "LoginScreen.kt" to 5,
        "CourierScreen.kt" to 10,
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
        val d = sourcesDir()
        assertTrue("не нашёл каталог экранов — сторож ничего не проверяет", d != null && d.isDirectory)
    }

    @Test
    fun `поля длинной формы переживают выгрузку приложения`() {
        val dir = sourcesDir() ?: return
        val bad = mutableListOf<String>()
        for ((file, fields) in guarded) {
            val text = File(dir, file).readText()
            for (name in fields) {
                val saveable = Regex("""\bvar $name by rememberSaveable\s*\{""").containsMatchIn(text)
                val plain = Regex("""\bvar $name by remember\s*\{""").containsMatchIn(text)
                if (plain && !saveable) bad += "$file: поле «$name» на remember — потеряется при выгрузке"
            }
        }
        assertTrue(
            "поля формы не переживут выгрузку приложения — ${bad.size} шт. " +
                "Возьми rememberSaveable (как в «Посылках» и «Такси»):\n" + bad.joinToString("\n"),
            bad.isEmpty(),
        )
    }

    @Test
    fun `экраны, где приём уже был, его не растеряли`() {
        val dir = sourcesDir() ?: return
        val bad = mutableListOf<String>()
        for ((file, atLeast) in expectedUsers) {
            val n = Regex("rememberSaveable").findAll(File(dir, file).readText()).count()
            if (n < atLeast) bad += "$file: было ≥$atLeast сохраняемых состояний, стало $n"
        }
        assertTrue(
            "на длинных формах поубавилось сохраняемого состояния — значит что-то снова будет " +
                "теряться при выгрузке:\n" + bad.joinToString("\n"),
            bad.isEmpty(),
        )
    }
}
