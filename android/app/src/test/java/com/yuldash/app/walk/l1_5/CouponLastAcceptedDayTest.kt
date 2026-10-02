package com.yuldash.app.walk.l1_5

import com.yuldash.app.couponLastAcceptedDay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * leaf-1.5 R2 (CouponsScreen.kt::couponLastAcceptedDay): «Действует до» купона/промокода — это
 * последний КАЛЕНДАРНЫЙ ДЕНЬ ПО УФЕ, когда код ещё примут, а не резаная/переведённая как попало
 * UTC-строка.
 *
 * История ошибки (нашло независимое ревью после первого прохода листа):
 *  1) исходный код резал первые 10 символов UTC-строки без всякого перевода пояса;
 *  2) мой первый фикс переводил момент в часы ТЕЛЕФОНА через `Calendar.getInstance()` — на
 *     реальных записях (форма партнёра шлёт только дату, сервер кладёт её как начало дня по Уфе)
 *     это давало дату на день ПОЗЖЕ настоящей: купон «до 31.10» показывался «31.10», хотя касса
 *     по исключающей границе `valid_until <= now` отказывает уже В НАЧАЛЕ 31-го — реальный
 *     последний день приёма кода — 30.10.
 * Верно — минус секунда от `valid_until` (последний момент, когда код ещё действует), и ЖЁСТКО
 * часовой пояс Уфы (Asia/Yekaterinburg), а не часы телефона: это бизнес-правило сервера, а не
 * сообщение человеку о том, когда что-то произошло.
 *
 * Пояс JVM фиксируем на ЗАВЕДОМО ДРУГОЙ (не Уфа) — тест должен падать из-за кода, а не из-за
 * того, что тест случайно гоняли на машине с уфимским часовым поясом по умолчанию.
 */
class CouponLastAcceptedDayTest {
    private var savedTz: TimeZone? = null

    @Before
    fun fixTimeZone() {
        savedTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }

    @After
    fun restoreTimeZone() {
        savedTz?.let { TimeZone.setDefault(it) }
    }

    @Test
    fun oldStyleRecord_formAskedForThirtyFirst_lastAcceptedDayIsThirtieth() {
        // Реальная прод-запись (`coupons.py`+`client_dt_to_utc`, форма партнёра шлёт голую дату):
        // "2026-10-31" → хранится как "2026-10-30T19:00:00" UTC (00:00 31.10 по Уфе). Исключающая
        // граница делает последним принятым днём 30.10, а не 31.10.
        assertEquals("30.10.2026", couponLastAcceptedDay("2026-10-30T19:00:00"))
    }

    @Test
    fun wellPastUfaMidnight_naiveUtcTruncationWouldUnderstateByOneDay() {
        // Отдельная проверка от двух соседних: здесь UTC-дата строки («30») СЛУЧАЙНО не совпадает
        // ни с «минус секунда», ни с «без неё» в соседних тестах — обе Уфа-версии дают 31, а
        // наивная резка первых 10 символов («2026-10-30…») дала бы 30. Без этого теста мутация
        // «вернуть резку строки вместо перевода в Уфу» молча проходит мимо двух других кейсов,
        // где наивный и верный ответ случайно совпадают.
        assertEquals("31.10.2026", couponLastAcceptedDay("2026-10-30T20:30:00"))
    }

    @Test
    fun newStyleRecord_endOfDayUfa_lastAcceptedDayIsTheSameDay() {
        // После правки сервера (лист 1.3): хранится конец дня по Уфе. Минус секунда остаётся в
        // том же календарном дне.
        assertEquals("31.10.2026", couponLastAcceptedDay("2026-10-31T18:59:59"))
    }

    @Test
    fun staysOnSameUfaDayWellBeforeMidnight() {
        assertEquals("31.10.2026", couponLastAcceptedDay("2026-10-31T05:00:00"))
    }

    @Test
    fun plainTenCharDateIsKeptAsIsNoTimeToSubtractFrom() {
        // Уже обрезанная чистая дата (без времени) — секунду вычитать не из чего.
        assertEquals("31.10.2026", couponLastAcceptedDay("2026-10-31"))
    }

    @Test
    fun blankNullAndMalformedReturnNull() {
        assertNull(couponLastAcceptedDay(null))
        assertNull(couponLastAcceptedDay(""))
        assertNull(couponLastAcceptedDay("bad-date"))
        assertNull(couponLastAcceptedDay("2026/10/31"))
    }
}
