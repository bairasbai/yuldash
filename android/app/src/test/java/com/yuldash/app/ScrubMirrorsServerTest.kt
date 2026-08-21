package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Приложение чистит личные данные из отчётов о сбоях так же, как сервер.
 *
 * Зачем. Когда приложение падает, отчёт уезжает во внешний сервис мониторинга. В тексте
 * сбоя запросто оказывается то, что человек только что вводил: свой телефон, чужую почту,
 * координаты, ссылку слежения. Поэтому и на сервере, и в приложении текст перед отправкой
 * чистится по списку правил, и рядом с этим списком написано «правила держим одинаковыми
 * с обеих сторон».
 *
 * Одинаковыми они не были (аудит 2026-08-08, волна 87). Правило про почту завели на сервере
 * в волне 45 и не перенесли сюда: письмо, которое человек написал в поддержку, уезжало
 * в мониторинг как есть. Разошлись не по злому умыслу — просто фразу в комментарии никто
 * не проверял, а проверить её можно ровно одним тестом.
 *
 * Тест сверяет не сами регулярные выражения (они пишутся на разных языках), а то, ЧЕМ
 * заменяется найденное: `<телефон>`, `<почта>`, `<токен>` и так далее. Появилось правило
 * на сервере — оно обязано появиться и здесь.
 */
class ScrubMirrorsServerTest {

    private fun serverSource(): String? =
        listOf("../backend/app/observability.py", "../../backend/app/observability.py")
            .map(::File).firstOrNull { it.isFile }?.readText()

    private fun appSource(): String {
        val f = File("src/main/java/com/yuldash/app/YuldashApplication.kt")
        assertTrue("не нашёл исходник приложения: ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /** Метки замены — то, что видно в очищенном тексте. */
    private fun marks(src: String): Set<String> =
        Regex("""<[а-яё]+>|/t/\*\*\*""").findAll(src).map { it.value }.toSet()

    @Test
    fun `набор правил чистки совпадает с серверным`() {
        val server = serverSource()
            ?: return   // сервера рядом нет (отдельная сборка модуля) — сверять не с чем
        val missing = marks(server) - marks(appSource())
        assertTrue(
            "сервер прячет это, а приложение отправляет как есть: $missing. " +
                "Правило надо перенести в SCRUB (YuldashApplication.kt).",
            missing.isEmpty(),
        )
    }

    @Test
    fun `почта в тексте сбоя не уезжает наружу`() {
        val cleaned = scrubPersonal("человек написал на марат.юлдаш@example.com, разберитесь")
        assertEquals("человек написал на <почта>, разберитесь", cleaned)
    }

    @Test
    fun `телефон координаты и ссылка слежения тоже спрятаны`() {
        val cleaned = scrubPersonal(
            "звонил +7 917 123-45-67, был на lat=54.7261&lng=55.9475, ссылка /t/AbCdEfGh12345678xyz",
        )
        assertTrue("телефон остался в тексте: $cleaned", "<телефон>" in cleaned)
        assertTrue("координаты остались в тексте: $cleaned", "<коорд>" in cleaned)
        assertTrue("ссылка слежения осталась в тексте: $cleaned", "/t/***" in cleaned)
    }

    @Test
    fun `обычный текст ошибки не портим`() {
        val text = "NullPointerException in MapScreen at line 42"
        assertEquals("чистка съела обычный текст — по такому отчёту нечего чинить", text, scrubPersonal(text))
    }

    @Test
    fun `адрес ник госномер и голые координаты тоже спрятаны`() {
        // Дословный перенос правил с сервера не работает: в Python `\w` и `\b` знают кириллицу,
        // а в Java по умолчанию нет. Тест выше сверяет только МЕТКИ — правило с мёртвой
        // регуляркой он бы пропустил, а личное продолжало бы уезжать наружу.
        val cleaned = scrubPersonal(
            "забрать ул. Ленина 12, кв. 5, точка 54.7261, 55.9475, машина а123вс102, пиши @marat_ufa",
        )
        assertTrue("адрес остался в тексте: $cleaned", "<адрес>" in cleaned)
        assertTrue("номер дома остался в тексте: $cleaned", "Ленина 12" !in cleaned)
        assertTrue("голые координаты остались в тексте: $cleaned", "<коорд>" in cleaned)
        assertTrue("госномер остался в тексте: $cleaned", "<номер авто>" in cleaned)
        assertTrue("ник остался в тексте: $cleaned", "<ник>" in cleaned)
    }

    @Test
    fun `числа из обычной жизни за координаты не принимаем`() {
        // «мест 3, 5» и «цена 300, 400» — не координаты. Если чистка съест их, по отчёту
        // о сбое нельзя будет понять, что случилось.
        val text = "мест 3, 5 и цена 300, 400"
        assertEquals("чистка приняла обычные числа за координаты", text, scrubPersonal(text))
    }
}
