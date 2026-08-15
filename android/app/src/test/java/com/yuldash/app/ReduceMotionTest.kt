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
 * как системный размер шрифта, — а размер шрифта Юлдаш уважает с самого начала.
 *
 * Что было. Выключатель читал ровно ОДИН экран — заставка. Остальные 500 с лишним мест с
 * анимацией не знали о нём вовсе. Человека, которого укачивает, встречало спокойное
 * приветствие, а дальше всё приложение в движении.
 *
 * Почему чинится одним местом. Каждая анимация берёт длительность из `CanonMotion` — своё
 * число писать нельзя, это держит `CanonSourceGuardTest`. Значит достаточно обнулить
 * длительности: ноль означает «сразу конечное состояние», карточки и экраны не перестают
 * появляться — появляются мгновенно.
 */
class ReduceMotionTest {

    @After
    fun tearDown() {
        CanonMotion.enabled = true   // не оставляем выключенным для соседних тестов
    }

    @Test
    fun `при выключенных анимациях все длительности нулевые`() {
        CanonMotion.enabled = false
        assertEquals("быстрая анимация осталась", 0, CanonMotion.QUICK)
        assertEquals("обычная анимация осталась", 0, CanonMotion.NORMAL)
        assertEquals("медленная анимация осталась", 0, CanonMotion.SLOW)
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
        var dir = File("").absoluteFile
        var main: File? = null
        repeat(4) {
            for (rel in listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app")) {
                val f = File(File(dir, rel), "MainActivity.kt")
                if (f.isFile && main == null) main = f
            }
            dir = dir.parentFile ?: return@repeat
        }
        val text = main?.readText().orEmpty()
        assertTrue("не нашёл MainActivity.kt — сторож ничего не проверяет", text.isNotEmpty())
        assertTrue(
            "приложение не читает системную настройку анимаций — выключатель не действует",
            text.contains("ANIMATOR_DURATION_SCALE") && text.contains("CanonMotion.enabled"),
        )
    }
}
