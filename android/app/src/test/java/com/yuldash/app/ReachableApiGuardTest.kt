package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Сторож «ручка есть — нажать негде».
 *
 * Откуда взялся. Аудит 2026-08-06 нашёл, что кнопка «застрял на трассе» для доставки
 * существовала только на сервере: эндпоинт принимал сигнал, метод в приложении даже не был
 * написан, а в коммите значилось «кнопка добавлена». Проверка была только серверная —
 * она ходила по маршрутам сервера и физически не могла увидеть, что приложение туда
 * никогда не постучится. Заодно нашлось, что пороги «сколько страйков до паузы» сервер
 * отдаёт, а экран их не спрашивает.
 *
 * Что проверяет. Каждый сетевой метод в `ApiClient` кто-то вызывает из экранов. Если метод
 * никем не вызван — либо фича недоступна человеку, либо это мёртвый код. И то и другое
 * должно быть замечено, а не тихо жить в репозитории.
 *
 * Как жить со списком ниже. Метод, который сознательно пока не подключён, вписывается
 * в `knownUnreachable` С ПРИЧИНОЙ. Список только уменьшается: новая недоступная ручка
 * роняет сборку, и это правильно — значит, кто-то написал половину фичи.
 */
class ReachableApiGuardTest {

    /**
     * Сетевые методы без вызова из экранов — каждый с причиной. Замер 2026-08-06.
     * Тест падает и когда список УСТАРЕЛ (метод подключили, а строку не убрали).
     */
    private val knownUnreachable = mapOf(
        "adminForgiveDebt" to "прощение долга водителю делается вручную через API, экрана нет",
        "createDonation" to "пожертвования не запущены — экран появится вместе с юрлицом",
        "getBookingRole" to "роль в брони приходит вместе с самой бронью, отдельный запрос не нужен",
        "getMyBookings" to "список броней приходит общим пакетом экрана, отдельный запрос не нужен",
        "getNearbyRides" to "поиск рядом идёт через общий поиск поездок",
        "getPartnerCouponStats" to "статистика купонов партнёра — следующий этап кабинета",
        "getPromoStats" to "статистика промокода — админский инструмент, экрана пока нет",
        "setPayAgreement" to "договорённость об оплате записывается при создании брони; менять её после — отдельная задача",
    )

    private fun sourceDir(): File? {
        var dir = File("").absoluteFile
        repeat(4) {
            val src = File(dir, "app/src/main/java/com/yuldash/app")
            if (src.isDirectory) return src
            val src2 = File(dir, "src/main/java/com/yuldash/app")
            if (src2.isDirectory) return src2
            dir = dir.parentFile ?: return null
        }
        return null
    }

    /** Имя метода → его тело (от `suspend fun` до следующего `fun` того же уровня). */
    private fun networkMethods(apiSource: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val header = Regex("""\n {4}(?:internal |private )?suspend fun ([A-Za-z0-9_]+)\s*\(""")
        val hits = header.findAll(apiSource).toList()
        hits.forEachIndexed { i, m ->
            val end = if (i + 1 < hits.size) hits[i + 1].range.first else apiSource.length
            val body = apiSource.substring(m.range.first, end)
            // Нас интересуют только те, что реально ходят в сеть.
            if (body.contains("call(") || body.contains("callRaw(") || body.contains("callMultipart(")) {
                out[m.groupValues[1]] = body
            }
        }
        return out
    }

    @Test
    fun `исходники на месте — иначе сторож ничего не проверяет`() {
        val dir = sourceDir()
        assertTrue("Не нашёл исходники приложения — сторож бесполезен", dir != null)
        assertTrue("Не нашёл ApiClient.kt", File(dir, "data/ApiClient.kt").isFile)
    }

    @Test
    fun `у каждой сетевой ручки есть кнопка — иначе фича недоступна человеку`() {
        val dir = sourceDir() ?: return
        val api = File(dir, "data/ApiClient.kt")
        val apiSource = api.readText()
        val methods = networkMethods(apiSource)
        assertTrue("Сетевых методов подозрительно мало — разбор сломался", methods.size > 100)

        val screens = dir.walkTopDown()
            .filter { it.extension == "kt" && it.absolutePath != api.absolutePath }
            .joinToString("\n") { it.readText() }

        val unreachable = methods.keys.filter { name ->
            val call = Regex("""\b${Regex.escape(name)}\s*\(""")
            // Вызов с экрана, либо метод-помощник, которым пользуется сам ApiClient.
            !call.containsMatchIn(screens) && call.findAll(apiSource).count() <= 1
        }.toSet()

        val newlyUnreachable = unreachable - knownUnreachable.keys
        assertTrue(
            "Эти ручки приложение никому не даёт нажать — фича написана наполовину:\n" +
                newlyUnreachable.joinToString("\n") { "  • $it" } +
                "\nЛибо подключи к экрану, либо впиши в knownUnreachable с причиной.",
            newlyUnreachable.isEmpty(),
        )
    }

    @Test
    fun `список исключений не устарел — подключённое не должно в нём числиться`() {
        val dir = sourceDir() ?: return
        val api = File(dir, "data/ApiClient.kt")
        val apiSource = api.readText()
        val methods = networkMethods(apiSource)
        val screens = dir.walkTopDown()
            .filter { it.extension == "kt" && it.absolutePath != api.absolutePath }
            .joinToString("\n") { it.readText() }

        val stale = knownUnreachable.keys.filter { name ->
            val call = Regex("""\b${Regex.escape(name)}\s*\(""")
            name !in methods.keys || call.containsMatchIn(screens)
        }
        assertTrue(
            "Список исключений протух — эти ручки уже подключены (или удалены), убери их из knownUnreachable:\n" +
                stale.joinToString("\n") { "  • $it" },
            stale.isEmpty(),
        )
    }
}
