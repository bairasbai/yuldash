package com.yuldash.app

import com.yuldash.app.data.SessionKeys
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Сторож: у каждого ключа на диске записано, переживает ли он выход из аккаунта.
 *
 * Зачем (аудит 2026-08-12, волна 31). Телефон переходит из рук в руки — мужу, сыну, покупателю.
 * Выход из аккаунта должен уносить чужое и оставлять настройки самого устройства. Очистка была
 * написана списком «что вспомнили»: работала верно, но новый ключ мог тихо в неё не попасть,
 * и следующий владелец телефона получил бы кусок чужой жизни.
 *
 * Тест читает исходники и требует решения для КАЖДОГО ключа: либо он в `CLEARED_ON_LOGOUT`,
 * либо в `SURVIVES_LOGOUT` с человеческой причиной. Третьего (молчания) быть не должно.
 *
 * Это четвёртый прибор такого рода в проекте; три предыдущих (удаление аккаунта, уведомления,
 * сроки хранения) уже ловили забытое раньше человека.
 */
class SessionKeysGuardTest {

    /** Ключи-исключения: их пишет не приложение, а библиотека/системный слой. */
    private val notOurs = setOf<String>()

    private fun keysInSources(): Set<String> {
        val root = File("src/main/java/com/yuldash/app")
        assertTrue("не нашёл исходники приложения: ${root.absolutePath}", root.isDirectory)
        val re = Regex("""put(?:String|Boolean|Int|Long|Float)\("([a-z_0-9.]+)"""")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> re.findAll(file.readText()).map { it.groupValues[1] } }
            .toSet() - notOurs
    }

    @Test
    fun `у каждого ключа на диске есть решение про выход из аккаунта`() {
        val decided = SessionKeys.CLEARED_ON_LOGOUT.toSet() + SessionKeys.SURVIVES_LOGOUT.keys
        val forgotten = (keysInSources() - decided).sorted()
        assertTrue(
            "эти ключи пишутся на диск, но никто не решил, переживают ли они выход из аккаунта: " +
                "$forgotten. Добавь их в SessionKeys.CLEARED_ON_LOGOUT (это данные аккаунта) " +
                "или в SURVIVES_LOGOUT с причиной (это настройка самого телефона).",
            forgotten.isEmpty(),
        )
    }

    @Test
    fun `выход из аккаунта действительно стирает то, что обещано`() {
        val src = File("src/main/java/com/yuldash/app/data/ApiClient.kt").readText()
        val logout = src.substringAfter("private fun clearLocalSession()").substringBefore("\n    }")
        assertTrue(
            "clearLocalSession перестал ходить по списку SessionKeys — список и код разъехались",
            logout.contains("SessionKeys.CLEARED_ON_LOGOUT"),
        )
        // Паспорта поездок (чужие имена и телефоны) и очередь исходящих — отдельные хранилища,
        // список ключей их не покрывает, поэтому проверяем отдельно.
        assertTrue("паспорта поездок должны стираться при выходе", logout.contains("TripPassStore.clearAll"))
        assertTrue("очередь исходящих должна стираться при выходе", logout.contains("Outbox.clearAll"))
    }

    @Test
    fun `причины «остаётся» написаны для человека, а не для галочки`() {
        for ((key, reason) in SessionKeys.SURVIVES_LOGOUT) {
            assertTrue("причина для $key слишком короткая: $reason", reason.length >= 20)
        }
    }

    @Test
    fun `ключ не может одновременно умирать и оставаться`() {
        val both = SessionKeys.CLEARED_ON_LOGOUT.filter { it in SessionKeys.SURVIVES_LOGOUT.keys }
        assertTrue("ключи в обоих списках сразу: $both", both.isEmpty())
    }
}
