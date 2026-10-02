package com.yuldash.app.walk.l1_5

import com.yuldash.app.shortDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * leaf-1.5 R2 (CouponsScreen.kt::shortDate): срок купона — по часам Уфы, а не резаная UTC-строка.
 *
 * Было: `shortDate` брал первые 10 символов ISO-строки с сервера как готовую дату. Сервер шлёт
 * `valid_until` наивным UTC (`backend/app/routers/coupons.py` + `client_dt_to_utc` в `timeutil.py`):
 * «2026-07-05T21:00:00» — это уже «06.07, 02:00» по Уфе (UTC+5). Старая резка показывала
 * «05.07.2026» — на день раньше настоящего срока действия купона. Тот же класс ошибки уже чинили
 * в `formatDepart` (разбор №2, 2026-08-03) и в серверной `local_date` (волна 79/203) — здесь он
 * просто не был замечен: из вида пропадает только ДЕНЬ, а не час, и расхождение не бросается в глаза.
 *
 * Пояс фиксируем на Уфу, как в `CoreLogicTest`/`HelpersEdgeTest` — тест должен падать из-за кода,
 * а не из-за часового пояса машины, где его запустили.
 */
class ShortDateUfaTimezoneTest {
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
    fun crossesIntoNextUfaDay() {
        // 21:00 UTC = 02:00 СЛЕДУЮЩЕГО дня в Уфе — купон реально действует ДО 6 июля, не до 5-го.
        assertEquals("06.07.2026", shortDate("2026-07-05T21:00:00"))
    }

    @Test
    fun crossesIntoNextUfaDayWithFractionalSeconds() {
        assertEquals("06.07.2026", shortDate("2026-07-05T21:00:00.123456"))
    }

    @Test
    fun staysOnSameUfaDayWellBeforeMidnight() {
        // 05:00 UTC = 10:00 в Уфе — тот же календарный день, переводить нечего.
        assertEquals("05.07.2026", shortDate("2026-07-05T05:00:00"))
    }

    @Test
    fun respectsExplicitOffsetWhenServerSendsOne() {
        assertEquals("05.08.2026", shortDate("2026-08-05T23:30:00+05:00"))
        assertEquals("06.08.2026", shortDate("2026-08-05T23:30:00+00:00"))
    }

    @Test
    fun plainTenCharDateIsKeptAsIsNoTimeToConvert() {
        // Вызывающий код мог уже обрезать строку до чистой даты сам (CarPhotoScreen.kt:427,
        // `data.dueAt?.take(10)`) — часа в ней нет, переводить нечего, показываем как есть.
        // Важно не регрессировать этот путь фиксом часового пояса.
        assertEquals("05.07.2026", shortDate("2026-07-05"))
    }

    @Test
    fun blankAndNullReturnNull() {
        assertNull(shortDate(null))
        assertNull(shortDate(""))
        assertNull(shortDate("   "))
    }

    @Test
    fun malformedReturnsNull() {
        assertNull(shortDate("bad-date"))
        assertNull(shortDate("2026/07/05"))
    }
}
