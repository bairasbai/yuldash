package com.yuldash.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Блок D (парсинг ответов бэкенда). Требует реального `org.json` в unit-classpath
 * (добавлен в build.gradle.kts). Проверяет, что JSON чата с сервера корректно превращается
 * в `MessageDto` — включая пограничные случаи voice_url (json null / строка "null" / нет ключа).
 */
class ApiParsingTest {

    @Test
    fun parseMessageDto_readsAllFields() {
        val dto = parseMessageDto(
            JSONObject(
                """{"id":42,"text":"Привет","sender_id":7,"voice_url":"https://yulbash.ru/v.m4a","deleted":false,"edited":true}"""
            )
        )
        assertEquals(42, dto.id)
        assertEquals("Привет", dto.text)
        assertEquals(7, dto.senderId)
        assertEquals("https://yulbash.ru/v.m4a", dto.voiceUrl)
        assertFalse(dto.deleted)
        assertTrue(dto.edited)
    }

    @Test
    fun parseMessageDto_voiceUrlJsonNull_becomesNull() {
        val dto = parseMessageDto(JSONObject("""{"id":1,"text":"t","sender_id":2,"voice_url":null}"""))
        assertNull(dto.voiceUrl)
    }

    @Test
    fun parseMessageDto_voiceUrlLiteralNullString_becomesNull() {
        // Сервер иногда шлёт строку "null" вместо JSON null — тоже не должно стать «голосовым».
        val dto = parseMessageDto(JSONObject("""{"id":1,"text":"t","sender_id":2,"voice_url":"null"}"""))
        assertNull(dto.voiceUrl)
    }

    @Test
    fun parseMessageDto_voiceUrlMissingKey_becomesNull() {
        val dto = parseMessageDto(JSONObject("""{"id":1,"text":"t","sender_id":2}"""))
        assertNull(dto.voiceUrl)
    }

    @Test
    fun parseMessageDto_missingScalarsFallBackToDefaults() {
        // optInt→0, optString→"" — парсер не падает на неполном объекте.
        val dto = parseMessageDto(JSONObject("""{}"""))
        assertEquals(0, dto.id)
        assertEquals("", dto.text)
        assertEquals(0, dto.senderId)
        assertNull(dto.voiceUrl)
        assertFalse(dto.deleted)
        assertFalse(dto.edited)
    }

    @Test
    fun parseMessageDto_deletedAndEditedFlags() {
        val dto = parseMessageDto(JSONObject("""{"id":5,"text":"x","sender_id":9,"deleted":true,"edited":true}"""))
        assertTrue(dto.deleted)
        assertTrue(dto.edited)
    }

    @Test
    fun normalizeOptionalJsonString_literalNullStringIsDropped() {
        // Ветка `it != "null"` — не покрыта в CoreLogicTest.
        assertNull(normalizeOptionalJsonString(hasValue = true, isJsonNull = false, raw = "null"))
    }

    @Test
    fun normalizeOptionalJsonString_realValueSurvives() {
        assertEquals(
            "abc",
            normalizeOptionalJsonString(hasValue = true, isJsonNull = false, raw = "abc"),
        )
    }
}
