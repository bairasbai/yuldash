package com.yuldash.app

import com.yuldash.app.data.InstantOrderDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Деньги и статусы заказа — чистая логика, без Android и Compose.
 *
 * Зачем: `kopToRub` раньше делил копейки нацело (`kop / 100`), и долг 150,50 ₽ показывался как
 * «150 ₽» — человек считал, что рассчитался полностью (аудит 2026-07-26). Округлять деньги вниз
 * нельзя, поэтому формат зафиксирован тестами.
 */
class MoneyFormatTest {

    @Test
    fun kopToRub_wholeRubles_hasNoKopeckTail() {
        assertEquals("150 ₽", kopToRub(15_000))
        assertEquals("0 ₽", kopToRub(0))
    }

    @Test
    fun kopToRub_keepsKopecks() {
        // Главный баг: 150,50 ₽ показывалось как «150 ₽».
        assertEquals("150,50 ₽", kopToRub(15_050))
        assertEquals("0,01 ₽", kopToRub(1))
        assertEquals("0,99 ₽", kopToRub(99))
    }

    @Test
    fun kopToRub_padsSingleDigitKopecks() {
        assertEquals("1,05 ₽", kopToRub(105))
    }

    @Test
    fun kopToRub_groupsThousandsWithSpace() {
        assertEquals("1 250 ₽", kopToRub(125_000))
        assertEquals("12 345,67 ₽", kopToRub(1_234_567))
    }

    @Test
    fun kopToRub_negativeKeepsSignAndKopecks() {
        // Долг/списание: знак стоит ПЕРЕД суммой, копейки не теряются.
        assertTrue(kopToRub(-15_050).startsWith("−"))
        assertTrue(kopToRub(-15_050).endsWith("150,50 ₽"))
    }

    // ---- Очередь «подожду машину»: заказ формально expired, но поиск продолжается ----

    private fun order(status: String, waitUntil: String? = null) = InstantOrderDto(
        id = 1, status = status, role = "passenger",
        fromLat = 0.0, fromLng = 0.0, toLat = 0.0, toLng = 0.0,
        fromText = "", toText = "", category = "standard",
        priceEstimate = 100, priceFinal = null, distanceKm = 1.0, etaMin = 5.0,
        driverId = null, offerExpiresAt = null, cancelBy = "", cancelReason = "",
        surgeK = 1.0, waitingStartedAt = null, waitingFeeKop = 0, cancelFeeKop = 0,
        noShow = false, waitFreeMin = 5, waitFeeRubPerMin = 5, noShowAt = null, cancelFeeNowKop = 0,
        passengerRating = null, passengerTrips = 0,
        driverName = "", driverCar = "", driverVerified = false, driverRating = 0.0,
        driverPhone = "", passengerPhone = "", passengerName = "",
        waitUntil = waitUntil,
    )

    @Test
    fun expiredOrder_withoutWait_isTerminalAndNotQueued() {
        val o = order("expired")
        assertTrue(o.isTerminal)
        assertFalse(o.isWaitingQueue)
    }

    /** Срок ожидания в наивном UTC — ровно в том виде, в каком его шлёт сервер. */
    private fun waitIso(minutesFromNow: Long): String =
        java.time.LocalDateTime.ofInstant(
            java.time.Instant.now().plusSeconds(minutesFromNow * 60),
            java.time.ZoneOffset.UTC,
        ).withNano(0).toString()

    @Test
    fun expiredOrder_withWait_staysAliveForPolling() {
        // Именно это держит поллинг живым: иначе нажал «Подожду» — и приложение перестало следить.
        //
        // Срок берём ОТ ТЕКУЩЕГО ВРЕМЕНИ, а не зашитой датой: раньше здесь стояло
        // «2026-07-26», и тест проверял правило ровно до тех пор, пока эта дата была
        // в будущем. Прошёл месяц — и он стал проверять календарь, а не смысл.
        val o = order("expired", waitUntil = waitIso(10))
        assertTrue(o.isTerminal)
        assertTrue(o.isWaitingQueue)
    }

    @Test
    fun expiredOrder_withPastWait_isNoLongerQueued() {
        // Срок ожидания вышел — очередь кончилась. Раньше проверялось только НАЛИЧИЕ срока,
        // и экран писал «ищем машину дальше» до конца времён, хотя никто уже не искал
        // (аудит сценариев 2026-08-30).
        val o = order("expired", waitUntil = waitIso(-10))
        assertTrue(o.isTerminal)
        assertFalse(o.isWaitingQueue)
    }

    @Test
    fun unreadableWaitKeepsTheQueueAlive() {
        // Чужой формат даты — не повод обрывать поиск, который, может быть, идёт.
        // Сомнение толкуем в пользу человека, который ждёт машину.
        assertTrue(order("expired", waitUntil = "не дата").isWaitingQueue)
    }

    @Test
    fun finishedOrder_isNeverTreatedAsQueued() {
        // Завершённая/отменённая поездка не должна «оживать» из-за живого wait_until.
        assertFalse(order("done", waitUntil = waitIso(10)).isWaitingQueue)
        assertFalse(order("cancelled", waitUntil = waitIso(10)).isWaitingQueue)
    }
}
