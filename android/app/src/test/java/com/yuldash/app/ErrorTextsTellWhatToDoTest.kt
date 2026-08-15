package com.yuldash.app

import com.yuldash.app.data.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * Ответ сервера человек должен понять — и понять, что делать дальше.
 *
 * Отдельно про «файл слишком большой». Такой отказ приходит не только от нашего кода:
 * веб-сервер режет слишком тяжёлое тело раньше приложения и отдаёт голый 413 без пояснения.
 * Своего текста на этот код не было, и человек видел общее «Не получилось. Повтори.» —
 * повторял, снова упирался и не понимал, что дело в размере снимка (аудит 2026-08-08,
 * волна 101). Для сельской связи это ещё и минуты ожидания на каждую попытку.
 *
 * Проверяем и второе правило проекта: текст существует на обоих языках и они разные,
 * то есть перевод действительно сделан, а не скопирован.
 */
class ErrorTextsTellWhatToDoTest {

    private val method: Method = ApiClient::class.java
        .getDeclaredMethod("genericByStatus", Int::class.java, Boolean::class.java)
        .apply { isAccessible = true }

    private fun text(status: Int, ba: Boolean): String = method.invoke(ApiClient, status, ba) as String

    @Test
    fun `слишком большой файл объясняет причину и выход`() {
        val ru = text(413, false)

        assertTrue("текст про размер файла не про файл: $ru", "файл" in ru.lowercase())
        assertTrue(
            "человеку не сказали, что делать дальше: $ru",
            "выбери" in ru.lowercase() || "сними" in ru.lowercase(),
        )
        assertTrue("это общая заглушка, а не объяснение: $ru", "не получилось" !in ru.lowercase())
    }

    @Test
    fun `тот же ответ есть по-башкирски и он не копия русского`() {
        val ru = text(413, false)
        val ba = text(413, true)

        assertTrue("башкирский текст пустой", ba.isNotBlank())
        assertTrue("русский и башкирский совпали — перевода нет", ru != ba)
    }

    @Test
    fun `частые ответы сервера остались понятными`() {
        for (status in listOf(401, 403, 404, 409, 429, 500)) {
            val ru = text(status, false)
            val ba = text(status, true)
            assertTrue("пустой текст для $status", ru.isNotBlank() && ba.isNotBlank())
            assertTrue("для $status русский и башкирский совпали", ru != ba)
        }
    }

    @Test
    fun `незнакомый код не оставляет человека без ответа`() {
        assertEquals("Не получилось. Повтори.", text(418, false))
    }
}
