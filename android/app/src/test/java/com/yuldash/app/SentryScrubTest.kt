package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sentry не должен унести наружу чужой телефон, координаты и токены.
 *
 * `isSendDefaultPii = false` закрывает только автоматическое приложение тел и заголовков.
 * Если номер попал ВНУТРЬ текста ошибки или в адрес запроса (а адреса приезжают сами,
 * хлебными крошками сетевого слоя) — он уедет в облако. Здесь проверяем последний рубеж.
 *
 * Правила зеркалят серверные (`backend/app/observability.py`) — держим одинаковыми.
 */
class SentryScrubTest {

    @Test
    fun `телефон вычищается в любом написании`() {
        listOf("+79171234567", "8 917 123 45 67", "8-917-123-45-67", "89171234567").forEach { raw ->
            val out = scrubPersonal("Номер $raw занят")
            assertFalse("не вычищен: $raw", out.contains(raw))
            assertTrue("нет метки: $raw", out.contains("<телефон>"))
        }
    }

    @Test
    fun `координаты вычищаются, безобидное остаётся`() {
        val out = scrubPersonal("GET /rides?lat=54.0512&lng=58.3187&seats=2")
        assertFalse(out.contains("54.0512"))
        assertFalse(out.contains("58.3187"))
        assertTrue(out.contains("seats=2"))
    }

    @Test
    fun `токен вычищается`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N"
        val out = scrubPersonal("401 Unauthorized: $jwt")
        assertFalse(out.contains(jwt))
        assertTrue(out.contains("<токен>"))
    }

    @Test
    fun `секреты в адресе вычищаются`() {
        val out = scrubPersonal("POST /auth/verify?code=482913&token=abc123def")
        assertFalse(out.contains("482913"))
        assertFalse(out.contains("abc123def"))
    }

    @Test
    fun `безобидный текст не трогаем`() {
        listOf(
            "Ride 42 not found",
            "цена 800 руб, 3 места",
            "буду через 10 минут, подъезд 89",
            "seats=2",
        ).forEach { assertEquals(it, scrubPersonal(it)) }
    }

    @Test
    fun `пустая строка не роняет`() {
        assertEquals("", scrubPersonal(""))
    }
}
