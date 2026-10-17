package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Пульс стоянки: стоящая машина всё равно отзывается (проверка безопасности PR170).
 *
 * Было: GPS отдавал точку, только если машина сдвинулась на 10 м. Встала — ни одной точки, а сервер
 * держит место машины для ссылки близким и для SOS 120 с. Через две минуты стоянки машина пропадала
 * у мамы с карты и из сигнала SOS — ровно в случае «машина остановилась в поле».
 *
 * Здесь — правила отправки без эмулятора: точка GPS приходит каждые 3 с, часы — наши.
 * Сквозной путь через настоящий сервис и сокет — `TripLocationHeartbeatTest`.
 */
class TripLocationPacerTest {

    private val fixMs = TripLocationService.FIX_INTERVAL_MS
    private val minMs = TripLocationService.MIN_INTERVAL_MS
    private val beatMs = TripLocationService.HEARTBEAT_MS

    // Уфа. Метр к северу ≈ 1/111 195 градуса широты.
    private val lat0 = 54.7388
    private val lng0 = 55.9721
    private fun north(m: Double) = lat0 + m / 111_195.0

    /**
     * Поездка на [durationMs]: точка GPS каждые 3 с в месте [latAt]; [accepted] — принял ли её сокет.
     * Возвращает моменты, когда точка правда ушла.
     */
    private fun ride(
        durationMs: Long,
        latAt: (Long) -> Double,
        accepted: (Long) -> Boolean = { true },
        pacer: TripLocationPacer = TripLocationPacer(),
    ): List<Long> {
        val sent = mutableListOf<Long>()
        var t = 0L
        while (t <= durationMs) {
            val lat = latAt(t)
            if (pacer.due(t, lat, lng0) && accepted(t)) {
                pacer.sent(t, lat, lng0)
                sent += t
            }
            t += fixMs
        }
        return sent
    }

    private fun gaps(sent: List<Long>) = sent.zipWithNext { a, b -> b - a }

    @Test
    fun `стоящая машина отзывается раз в 45 секунд`() {
        val sent = ride(10 * 60_000L, latAt = { lat0 })

        assertEquals("первая точка поездки уходит сразу", 0L, sent.first())
        assertTrue(
            "на стоянке точки не было дольше пульса — сервер забудет машину: ${gaps(sent)}",
            gaps(sent).all { it <= beatMs + fixMs },
        )
        assertTrue(
            "на стоянке точка уходит чаще пульса — батарея и трафик впустую: ${gaps(sent)}",
            gaps(sent).all { it >= beatMs },
        )
        assertEquals("10 минут стоянки — 14 точек (0, 45, 90 … 585 с)", 14, sent.size)
    }

    @Test
    fun `дрожание GPS на месте не превращается в отправку каждые 7 секунд`() {
        // Стоим, а GPS «гуляет» на ±4 м — это не езда.
        val sent = ride(10 * 60_000L, latAt = { t -> north(if ((t / fixMs) % 2 == 0L) 4.0 else -4.0) })

        assertTrue("дрожание на месте считалось ездой: ${gaps(sent)}", gaps(sent).all { it >= beatMs })
        assertTrue(gaps(sent).all { it <= beatMs + fixMs })
    }

    @Test
    fun `в движении темп 7 секунд, пульс сверху ничего не добавляет`() {
        // 30 м за точку ≈ 36 км/ч.
        val sent = ride(10 * 60_000L, latAt = { t -> north(30.0 * (t / fixMs)) })

        assertTrue("в движении точки пошли чаще раза в 7 с: ${gaps(sent)}", gaps(sent).all { it >= minMs })
        assertTrue("в движении точки пошли реже — машина на карте прыгает: ${gaps(sent)}", gaps(sent).all { it < minMs + fixMs })
        assertTrue("лишние отправки сверх темпа 7 с: ${sent.size}", sent.size <= 10 * 60_000L / minMs + 1)
    }

    @Test
    fun `медленная пробка копится от последней отправленной точки`() {
        // 3 м за точку: каждая следующая ближе 10 м к прошлой, но от последней отправленной — уже нет.
        val sent = ride(3 * 60_000L, latAt = { t -> north(3.0 * (t / fixMs)) })

        assertEquals("ползущая машина должна двигаться на карте каждые 12 с (12 м)", listOf(12_000L), gaps(sent).distinct())
    }

    @Test
    fun `не принятая сокетом точка не считается — повтор со следующей точкой GPS`() {
        // Канал переподключается с 40-й по 70-ю секунду: пульс на 45-й не ушёл.
        val sent = ride(3 * 60_000L, latAt = { lat0 }, accepted = { t -> t !in 40_000L..70_000L })

        assertEquals(
            "после переподключения точка уходит с первой же точкой GPS, а дальше — снова раз в 45 с",
            listOf(0L, 72_000L, 117_000L, 162_000L),
            sent,
        )
    }

    @Test
    fun `пассажиру пульс не нужен — стоит молча, едет как водитель`() {
        // Точку пассажира сервер не хранит, только пересылает водителю (приёмка PR179).
        val passenger = { TripLocationPacer(heartbeatMs = TripLocationService.NO_HEARTBEAT_MS) }
        assertEquals("стоящий пассажир шлёт только первую точку", listOf(0L), ride(10 * 60_000L, latAt = { lat0 }, pacer = passenger()))
        val moving: (Long) -> Double = { t -> north(30.0 * (t / fixMs)) }
        assertEquals(
            "в движении пассажир шлёт точно как раньше",
            ride(10 * 60_000L, latAt = moving),
            ride(10 * 60_000L, latAt = moving, pacer = passenger()),
        )
    }

    @Test
    fun `после переподключения канала точка уходит сразу`() {
        val pacer = TripLocationPacer()
        pacer.sent(0L, lat0, lng0)
        assertFalse("стоим: до пульса ещё 42 с", pacer.due(3_000L, lat0, lng0))
        pacer.reset()   // так делает сервис, когда сокет снова вошёл
        assertTrue("после обрыва стоящая машина молчала бы до 45 с", pacer.due(3_000L, lat0, lng0))
    }

    @Test
    fun `новая поездка — первая точка сразу, без паузы от прошлой`() {
        val pacer = TripLocationPacer()
        pacer.sent(0L, lat0, lng0)
        assertFalse(pacer.due(1_000L, north(500.0), lng0))

        pacer.reset()
        assertTrue("после смены брони первая точка ждала бы паузу прошлой поездки", pacer.due(1_000L, lat0, lng0))
    }

    @Test
    fun `пульс укладывается в срок сервера даже с одним потерянным кадром`() {
        // Срок жизни точки на сервере — backend/app/livepos.py. Сократят его — этот тест скажет,
        // что пульс приложения пора поджать, а не машина тихо начнёт мигать у близких.
        val livepos = generateSequence(File("").absoluteFile) { it.parentFile }
            .take(6)
            .map { File(it, "backend/app/livepos.py") }
            .firstOrNull { it.isFile }
        assertNotNull("не нашёл backend/app/livepos.py — сверять пульс не с чем", livepos)
        val ttlSec = Regex("""LIVEPOS_TTL_SEC\s*=\s*(\d+)""").find(livepos!!.readText())?.groupValues?.get(1)?.toLong()
        assertNotNull("в livepos.py нет LIVEPOS_TTL_SEC", ttlSec)

        // Один пульс пропал в сети — следующий через ещё один пульс и одну точку GPS.
        val worstGapMs = 2 * beatMs + fixMs
        assertTrue(
            "пульс $beatMs мс не переживает потерю одного кадра: ${worstGapMs} мс ≥ срока сервера ${ttlSec!! * 1000} мс",
            worstGapMs < ttlSec * 1000,
        )
    }
}
