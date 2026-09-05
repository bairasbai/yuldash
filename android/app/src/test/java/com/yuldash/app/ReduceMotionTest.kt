package com.yuldash.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Человек отключил анимации в системе — приложение обязано это уважать.
 *
 * Это не про вкус. Вестибулярные нарушения, мигрень, укачивание: для таких людей движущийся
 * интерфейс вызывает настоящее физическое недомогание, и Android даёт им общий выключатель
 * (Настройки → Специальные возможности → Удалить анимации). Уважать его так же обязательно,
 * как системный размер шрифта.
 *
 * ЧЕСТНО О ТОМ, ЧТО ЗДЕСЬ ПРОВЕРЯЕТСЯ. Основную работу делает сам Compose: рекомпозер читает
 * системную настройку вживую и при нуле досрочно завершает конечные анимации, а бесконечные
 * усыпляет (подробности со ссылками на исходники — в KDoc `CanonMotion.enabled`). Наш флаг
 * поверх этого нужен для движения, которое Compose анимацией не считает — своих таймлайнов
 * на `delay()`, как ролик заставки.
 *
 * Поэтому тест держит НАШ механизм: что каждая ступень шкалы обнуляется, что каскады
 * схлопываются, что приложение вообще читает системную настройку при старте. Внутренности
 * чужой библиотеки он не проверяет и проверять не должен — они меняются от версии к версии.
 */
class ReduceMotionTest {

    @After
    fun tearDown() {
        CanonMotion.enabled = true   // не оставляем выключенным для соседних тестов
    }

    @Test
    fun `при выключенных анимациях все длительности нулевые`() {
        CanonMotion.enabled = false
        // ВСЕ ступени, а не три первые: новая ступень без обнуления — это ровно та же
        // дыра, что была у своих чисел в экранах, только спрятанная внутрь шкалы.
        val steps = mapOf(
            "QUICK" to CanonMotion.QUICK, "NORMAL" to CanonMotion.NORMAL, "SLOW" to CanonMotion.SLOW,
            "ENTRY" to CanonMotion.ENTRY, "COUNT" to CanonMotion.COUNT, "SCENE" to CanonMotion.SCENE,
            "TICK" to CanonMotion.TICK, "PULSE" to CanonMotion.PULSE, "CINEMA" to CanonMotion.CINEMA,
            "DRIFT" to CanonMotion.DRIFT, "AMBIENT" to CanonMotion.AMBIENT,
        )
        steps.forEach { (name, value) -> assertEquals("ступень $name не обнулилась", 0, value) }
        assertEquals("каскад прихода остался", 0, CanonMotion.cascadeIn(5))
        assertEquals("каскад ухода остался", 0, CanonMotion.cascadeOut(5))
    }

    /**
     * Проводка бесконечных анимаций: пульс поиска, тревога SOS, мерцание скелетона, пыльца.
     *
     * Им обнуления не хватает: `tween(0)` в бесконечном цикле — это не покой, а мигание
     * каждый кадр. Поэтому такие места идут через `canonBreath`/`canonDrift`, а сторож
     * `CanonSourceGuardTest` не даёт завести бесконечную анимацию в экране руками.
     *
     * Здесь проверяем саму проводку по исходникам: хелперы существуют, читают выключатель
     * и возвращают спокойное значение вместо анимации. Сами они @Composable — без эмулятора
     * их не вызвать, поэтому смотрим в текст файла, как это делают остальные сторожа проекта.
     */
    @Test
    fun `бесконечные анимации не запускаются вовсе`() {
        val tokens = sourceFile("CanonTokens.kt").readText()
        assertTrue("нет хелпера дыхания — пульсам нечем замениться", tokens.contains("fun canonBreath("))
        assertTrue("нет хелпера дрейфа", tokens.contains("fun canonDrift("))
        // Главное: оба выходят РАНЬШЕ, чем заведут анимацию.
        assertEquals(
            "хелпер не замыкается на выключателе — бесконечная анимация всё равно запустится",
            2,
            tokens.split("if (!CanonMotion.enabled) return remember").size - 1,
        )
    }

    @Test
    fun `при включённых анимациях длительности прежние`() {
        CanonMotion.enabled = true
        assertTrue("быстрая должна остаться быстрой, но заметной", CanonMotion.QUICK in 120..220)
        assertTrue("обычная", CanonMotion.NORMAL in 200..320)
        assertTrue("медленная", CanonMotion.SLOW in 260..400)
        assertTrue("порядок шкалы нарушен", CanonMotion.QUICK < CanonMotion.NORMAL)
        assertTrue("порядок шкалы нарушен", CanonMotion.NORMAL < CanonMotion.SLOW)
    }

    @Test
    fun `приложение читает системный выключатель при старте`() {
        // Сторож на проводку: без чтения настройки флаг навсегда останется true, и весь
        // механизм окажется мёртвым кодом — как было до этого с полем createdAt в очереди.
        val text = sourceFile("MainActivity.kt").readText()
        assertTrue("не нашёл MainActivity.kt — сторож ничего не проверяет", text.isNotEmpty())
        assertTrue(
            "приложение не читает системную настройку анимаций — выключатель не действует",
            text.contains("ANIMATOR_DURATION_SCALE") && text.contains("CanonMotion.enabled"),
        )
    }

    /** Исходник экрана по имени: тест запускают и из корня репозитория, и из модуля `app`. */
    private fun sourceFile(name: String): File {
        var dir = File("").absoluteFile
        repeat(4) {
            for (rel in listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app")) {
                val f = File(File(dir, rel), name)
                if (f.isFile) return f
            }
            dir = dir.parentFile ?: return@repeat
        }
        throw AssertionError("не нашёл $name — сторож ничего не проверяет")
    }
}
