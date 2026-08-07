package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Повтор запроса при обрыве связи не должен создавать дубли (аудит 2026-08-07).
 *
 * Что было не так. Сетевой слой повторял ЛЮБОЙ запрос при `IOException` — в том числе POST.
 * В коде рядом стояло объяснение: «даже POST безопасен, повтор идёт лишь когда ответ не
 * получен вовсе, значит сервер запрос не обработал». Рассуждение неверное: обрыв бывает
 * двух совершенно разных видов, и оба прилетают одним `IOException`:
 *
 *  • не смогли дозвониться (нет сети, хост не резолвится) — запрос не ушёл, повтор безопасен;
 *  • ответ не пришёл вовремя (`SocketTimeoutException` на чтении) — запрос УШЁЛ, сервер мог
 *    выполнить его целиком, просто не успел ответить за 15 секунд.
 *
 * Во втором случае повтор создаёт вторую бронь, вторую посылку, второй платёж. Дедуп на
 * сервере есть только у двух ручек из всех — бронь и заказ такси; остальные ловили дубль молча.
 *
 * Что проверяем: сервер, который «завис» и не отвечает, получает от нас РОВНО ОДИН POST —
 * и по-прежнему получает несколько GET (чтение повторять безопасно и нужно: транзитный
 * обрыв в дороге не должен показывать человеку ошибку).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiClientRetrySafetyTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun teardown() {
        ApiClient.testBaseUrl = null
        server.shutdown()
    }

    /** Сервер принимает соединение и молчит → у клиента таймаут чтения. */
    private fun enqueueSilence(times: Int) {
        repeat(times) {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        }
    }

    @Test
    fun postIsNotRepeatedAfterReadTimeout() = runBlocking {
        enqueueSilence(3)

        // Любая меняющая операция; берём создание заявки — у неё на сервере дедупа нет.
        val res = ApiClient.createRequest(
            fromCity = "Баймак", toCity = "Сибай", seats = 1, category = "regular",
            withKids = false, comment = "", maxPrice = 300,
        )

        assertTrue("обрыв обязан вернуться ошибкой, а не тихим успехом", res.isFailure)
        assertEquals(
            "POST повторён после таймаута чтения — это вторая заявка у человека",
            1, server.requestCount,
        )
    }

    @Test
    fun getIsStillRepeatedAfterReadTimeout() = runBlocking {
        enqueueSilence(3)

        // Берём чтение БЕЗ кеша: у кешируемых ручек (`/feed`, `/rides`) повторный вызов
        // мог бы вернуть сохранённое значение и до сети не дойти — тест бы ничего не проверил.
        val res = ApiClient.getPendingReviews()

        assertTrue(res.isFailure)
        assertTrue(
            "чтение повторять безопасно и нужно — транзитный обрыв не должен сразу " +
                "показывать ошибку (было ${server.requestCount} попыток)",
            server.requestCount > 1,
        )
    }
}
