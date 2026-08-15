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

    private val root = File("src/main/java/com/yuldash/app")

    private fun sources(): List<String> {
        assertTrue("не нашёл исходники приложения: ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.toList()
    }

    /**
     * Все ключи, которые приложение пишет на диск.
     *
     * Ищем несколько написаний, потому что до волны 109 сторож знал только первое и восемь
     * ключей прошли мимо него незамеченными:
     *  • `putString("mode_last", …)` — имя ключа прямо в строке;
     *  • `putString(KEY_PARCELS, …)` — имя спрятано в константе, её значение ищем отдельно.
     * И `putStringSet` тоже: им сохраняются фильтры поиска, среди которых «только женщины».
     */
    private fun keysInSources(): Set<String> {
        val literal = Regex("""put(?:String|Boolean|Int|Long|Float|StringSet)\("([a-z_0-9.]+)"""")
        val viaConst = Regex("""put(?:String|Boolean|Int|Long|Float|StringSet)\(\s*([A-Za-z_][A-Za-z_0-9]*)\s*,""")
        val constDef = Regex("""val\s+([A-Z_][A-Z_0-9]*)\s*(?::\s*String\s*)?=\s*"([^"]+)"""")

        val consts = mutableMapOf<String, String>()
        val names = mutableSetOf<String>()
        val found = mutableSetOf<String>()
        for (text in sources()) {
            constDef.findAll(text).forEach { consts[it.groupValues[1]] = it.groupValues[2] }
            found += literal.findAll(text).map { it.groupValues[1] }
            names += viaConst.findAll(text).map { it.groupValues[1] }
        }
        found += names.mapNotNull { consts[it] }
        return found - notOurs
    }

    /**
     * Проверка зрения самого сторожа.
     *
     * Тест ниже устроен так, что ослепить его — значит сделать зелёным: чем меньше ключей он
     * находит, тем меньше «забытых». Ровно так он и жил до волны 109 — не видел ключи, спрятанные
     * в константах, и восемь штук молча проходили мимо. Поэтому здесь названы три ключа,
     * заведомо записанные разными способами: пропал хоть один — сторож снова слепнет.
     */
    @Test
    fun `сторож видит ключи всех написаний, а не только строкой`() {
        val seen = keysInSources()
        val mustSee = mapOf(
            "user_name" to "прямо строкой: putString(\"user_name\", …)",
            "last_parcels" to "через константу: putString(KEY_PARCELS, …)",
            "default_filters" to "через putStringSet — им сохраняются фильтры поиска",
        )
        val blind = mustSee.filterKeys { it !in seen }
        assertTrue(
            "сторож перестал видеть такие ключи: ${blind.values}. Это делает его зелёным " +
                "и бесполезным: ненайденный ключ выглядит как «забытых нет».",
            blind.isEmpty(),
        )
    }

    @Test
    fun `у каждого ключа на диске есть решение про выход из аккаунта`() {
        val decided = SessionKeys.CLEARED_BY_FILE.values.flatten().toSet() + SessionKeys.SURVIVES_LOGOUT.keys
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
        val both = SessionKeys.CLEARED_BY_FILE.values.flatten().filter { it in SessionKeys.SURVIVES_LOGOUT.keys }
        assertTrue("ключи в обоих списках сразу: $both", both.isEmpty())
    }

    @Test
    fun `выход заходит в каждый ящик настроек, а не только в тот, где токен`() {
        val src = File("src/main/java/com/yuldash/app/data/ApiClient.kt").readText()
        val logout = src.substringAfter("private fun clearLocalSession()").substringBefore("\n    }")
        assertTrue(
            "очистка перестала ходить по адресам из SessionKeys.CLEARED_BY_FILE — значит снова " +
                "чистит один ящик, хотя ключи лежат в нескольких",
            logout.contains("CLEARED_BY_FILE"),
        )
    }

    @Test
    fun `у каждого ящика настроек есть решение про выход`() {
        val boxes = Regex("""getSharedPreferences\(\s*"([a-z_0-9]+)"""")
        val found = sources().flatMap { text -> boxes.findAll(text).map { it.groupValues[1] }.toList() }.toSet()
        val known = SessionKeys.CLEARED_BY_FILE.keys + setOf(
            "yuldash_theme",     // тёмная тема — внешний вид устройства
            "yuldash_settings",  // звуки, уведомления, язык фоновых сервисов — настройки телефона
            "yuldash_secure",    // шифрованный ящик сессии: ключи те же, что у основного
        )
        val unknown = (found - known).sorted()
        assertTrue(
            "появился новый ящик настроек, а про выход из аккаунта в нём никто не подумал: $unknown. " +
                "Либо внеси его в SessionKeys.CLEARED_BY_FILE, либо в список известных здесь — с причиной.",
            unknown.isEmpty(),
        )
    }

    @Test
    fun `живой GPS гасится весь, а не только у поездки`() {
        val all = sources().joinToString("\n")
        val services = Regex("""(\w*LocationService)\.stop\(""")
            .findAll(all).map { it.groupValues[1] }.toSet()
        val stopper = File("src/main/java/com/yuldash/app/YuldashApp.kt").readText()
            .substringAfter("internal fun stopLiveTracking").substringBefore("\n}")
        val missed = services.filterNot { stopper.contains(it + ".stop(") }
        assertTrue(
            "эти сервисы льют GPS, но выход из аккаунта их не глушит: $missed. Телефон нового " +
                "владельца продолжит светить дорогу за прошлого — сервис умеет воскресать сам.",
            missed.isEmpty(),
        )
    }
}
