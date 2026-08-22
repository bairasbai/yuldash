package com.yuldash.app

import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.Outbox
import com.yuldash.app.data.OutboxAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Отложенный СТАТУС живёт ровно до тех пор, пока он правда. Сообщение — всегда.
 *
 * Что происходит без этого. Водитель жмёт «Я на месте» в селе без связи. Действие ложится в
 * очередь. Он довозит человека, приезжает домой и ловит вайфай вечером — очередь оживает, и
 * пассажиру в одиннадцать вечера прилетает «водитель на месте» о поездке, которая закончилась
 * в шесть. Это хуже, чем не отправить вовсе: человек может выйти к дороге.
 *
 * Отметка времени `createdAt` лежала в очереди с самого начала и не использовалась НИГДЕ —
 * ни в одной проверке, ни в одном условии. Поле было, смысла у него не было.
 *
 * Почему сообщения не выбрасываем. Это слова человека: он их написал и рассчитывает, что они
 * дойдут. Опоздавшее «подъезжаю» неловко, а потерянное «жду у второго подъезда» — хуже.
 *
 * Почему шесть часов, а не двадцать минут. Связь в дороге пропадает надолго; статус,
 * доехавший через час, всё ещё про эту поездку. Порог отсекает «вчерашнее», а не «медленное».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxStaleStatusTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        Outbox.init(ctx)
        Outbox.clearAll()
        // Мир этого теста — «связи нет»: очередь копится, потому что отправить некуда.
        //
        // Раньше адрес брался из сборки (`http://localhost:8000` в local.properties). Если на
        // машине разработчика поднят локальный бэкенд — а он поднимается для ручных проверок, —
        // запрос доходил, сервер отвечал 401, и очередь считала это «сервер отверг, повтор не
        // поможет» и выбрасывала действие. Тест краснел от того, что рядом работает сервер,
        // и зеленел, когда его выключали. Ложно-красный прогон опаснее отсутствующего: на него
        // легко списать настоящую поломку (аудит 2026-08-22, волна 184).
        //
        // Порт 1 закрыт на любой машине — соединение не устанавливается, и это ровно та
        // «нет сети», про которую тест и написан.
        ApiClient.testBaseUrl = "http://127.0.0.1:1"
    }

    @After
    fun tearDown() {
        ApiClient.testBaseUrl = null
    }

    private fun old(kind: String, hoursAgo: Long) = OutboxAction(
        id = System.nanoTime(),
        bookingId = 7,
        kind = kind,
        payload = if (kind == "message") "жду у второго подъезда" else "arrived",
        createdAt = System.currentTimeMillis() - hoursAgo * 3600_000L,
    )

    @Test
    fun `вчерашний статус не уезжает пассажиру`() = runBlocking {
        Outbox.enqueue(ctx, old("trip_status", hoursAgo = 20))
        Outbox.flush(ctx)      // сети нет — отправка не пройдёт, но протухшее должно исчезнуть
        assertEquals(
            "статус двадцатичасовой давности остался в очереди — он уедет и соврёт про «на месте»",
            0, Outbox.count(ctx, 7),
        )
    }

    @Test
    fun `свежий статус остаётся в очереди и ждёт сети`() {
        Outbox.enqueue(ctx, old("trip_status", hoursAgo = 1))
        runBlocking { Outbox.flush(ctx) }
        assertEquals(
            "статус часовой давности выбросили — а связь в дороге пропадает и на дольше",
            1, Outbox.count(ctx, 7),
        )
    }

    @Test
    fun `сообщение не выбрасываем никогда`() {
        Outbox.enqueue(ctx, old("message", hoursAgo = 48))
        runBlocking { Outbox.flush(ctx) }
        assertTrue(
            "выбросили слова человека — потерянное сообщение хуже опоздавшего",
            Outbox.count(ctx, 7) == 1,
        )
    }
}
