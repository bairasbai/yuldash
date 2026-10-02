package com.yuldash.app.walk.l1_5

import com.yuldash.app.ufaIsoDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * leaf-1.5 R2b (CouponsScreen.kt::ufaIsoDate): круговой путь «сервер → форма правки → сервер»
 * не должен терять сутки.
 *
 * Найдено по дополнению ведущего (независимый обзор серверной части купонов): кабинет партнёра
 * показывает и пересылает срок купона как ГГГГ-ММ-ДД без перевода из UTC, поэтому «до 31.10»
 * после открытия и сохранения формы без изменений превращается в «до 30.10», а при следующем
 * открытии — в «до 29.10» и так далее. Форма живёт в `PartnerCabinetScreen.kt` (вне зоны этого
 * листа — не правлю), но хелпер для правильного чтения даты в поле добавлен сюда же, рядом с
 * [com.yuldash.app.shortDate]: `initial.validUntil?.let(::ufaIsoDate) ?: ""` вместо
 * `initial?.validUntil?.take(10) ?: ""` останавливает сдвиг на чтении.
 */
class UfaIsoDateTest {
    private var savedTz: TimeZone? = null

    @Before
    fun fixTimeZone() {
        savedTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Yekaterinburg"))   // Уфа, UTC+5
    }

    @After
    fun restoreTimeZone() {
        savedTz?.let { TimeZone.setDefault(it) }
    }

    @Test
    fun convertsUtcMomentThatIsAlreadyNextDayInUfa() {
        // 19:00 UTC = 00:00 СЛЕДУЮЩЕГО дня в Уфе — ровно граница, где голый take(10) теряет сутки.
        assertEquals("2026-10-31", ufaIsoDate("2026-10-30T19:00:00"))
    }

    @Test
    fun roundTripReadThenSaveUnchangedDoesNotDriftBackwards() {
        // Сервер вернул купон, созданный «до конца 31.10 по Уфе»: это 2026-10-31T18:59:59 UTC.
        val fromServer = "2026-10-31T18:59:59"
        val shownInField = ufaIsoDate(fromServer)
        assertEquals("2026-10-31", shownInField)
        // Партнёр жмёт «Сохранить» ничего не поменяв — поле снова чистая дата, читается как есть.
        assertEquals(shownInField, ufaIsoDate(shownInField))
    }

    @Test
    fun staysOnSameUfaDayWellBeforeMidnight() {
        assertEquals("2026-10-31", ufaIsoDate("2026-10-31T05:00:00"))
    }

    @Test
    fun plainIsoDateIsKeptAsIs() {
        assertEquals("2026-10-31", ufaIsoDate("2026-10-31"))
    }

    @Test
    fun blankNullAndMalformedReturnNull() {
        assertNull(ufaIsoDate(null))
        assertNull(ufaIsoDate(""))
        assertNull(ufaIsoDate("bad-date"))
        assertNull(ufaIsoDate("2026/10/31"))
    }
}
