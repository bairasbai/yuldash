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
 * не должен ни терять, ни ДОБАВЛЯТЬ сутки.
 *
 * Было (моя первая версия, поймана независимым ревью): функция просто переводила UTC-момент в
 * календарь Уфы БЕЗ учёта того, что `valid_until` — ИСКЛЮЧАЮЩАЯ граница (сервер отказывает, если
 * `valid_until <= now`). Для реальной записи вида `"2026-10-30T19:00:00"` (форма партнёра
 * попросила «до 31.10», `client_dt_to_utc` положил это как 00:00 31.10 по Уфе) старая версия
 * возвращала `"2026-10-31"` — и если партнёр открывал купон и жал «Сохранить» НИЧЕГО не меняя,
 * купон молча продлевался на лишние сутки (бизнес платит комиссию за каждое погашение).
 *
 * Верно — минус секунда ДО перевода в Уфу (тот же момент, что касса реально ещё примет):
 * `"2026-10-30T19:00:00"` → `"2026-10-30"` (форма и просила именно это число).
 *
 * Форма купона живёт в `PartnerCabinetScreen.kt` (вне зоны этого листа — не правлю), хелпер
 * подготовлен здесь же, рядом с [com.yuldash.app.couponLastAcceptedDay], которым пользуется
 * показ срока на 4 экранах этого листа.
 */
class UfaIsoDateTest {
    private var savedTz: TimeZone? = null

    @Before
    fun fixTimeZone() {
        savedTz = TimeZone.getDefault()
        // Намеренно НЕ Уфа — функция обязана сама считать по Уфе, а не по умолчанию JVM/телефона.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }

    @After
    fun restoreTimeZone() {
        savedTz?.let { TimeZone.setDefault(it) }
    }

    @Test
    fun oldStyleStartOfDayRecordKeepsTheDayTheFormAskedFor() {
        // Старая запись: форма просила «до 31.10», client_dt_to_utc положил 00:00 31.10 по Уфе.
        // Исключающая граница делает реальным последним днём 30.10, а не 31.10.
        assertEquals("2026-10-30", ufaIsoDate("2026-10-30T19:00:00"))
    }

    @Test
    fun wellPastUfaMidnight_naiveUtcTruncationWouldUnderstateByOneDay() {
        // Та же проверка, что в CouponLastAcceptedDayTest: наивная резка первых 10 символов дала
        // бы «2026-10-30», а верный ответ (перевод в Уфу, с учётом исключающей границы) — 31-е.
        // Без этого примера мутация «вернуть резку строки» могла бы случайно пройти мимо соседних
        // тестов, где наивный и верный ответ совпадают.
        assertEquals("2026-10-31", ufaIsoDate("2026-10-30T20:30:00"))
    }

    @Test
    fun newStyleEndOfDayRecordStaysOnTheSameDay() {
        // Новая запись (после правки сервера, лист 1.3): конец дня по Уфе — минус секунда
        // остаётся в том же календарном дне.
        assertEquals("2026-10-31", ufaIsoDate("2026-10-31T18:59:59"))
    }

    @Test
    fun roundTripReadThenSaveUnchangedDoesNotDriftEitherWay() {
        val fromServer = "2026-10-30T19:00:00"
        val shownInField = ufaIsoDate(fromServer)
        assertEquals("2026-10-30", shownInField)
        // Партнёр жмёт «Сохранить» ничего не поменяв — поле снова чистая дата, читается как есть,
        // повторное применение функции не сдвигает её ещё раз.
        assertEquals(shownInField, ufaIsoDate(shownInField))
    }

    @Test
    fun plainIsoDateIsKeptAsIs() {
        // Уже обрезанная чистая дата — часа в ней нет, минус секунда не из чего брать.
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
