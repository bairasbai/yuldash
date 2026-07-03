package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * POST/action-методы `ApiClient` для БРОНЕЙ / ЗАЯВОК / ПОЕЗДКИ на локальном MockWebServer.
 * Проверяем: тело запроса (method + path + JSON), распарсенное значение из ответа
 * (`book`→id, `acceptResponse`→booking_id) и ветки ошибок (400/500 → ApiException.status).
 *
 * Токен не выставляем (не логинимся) — как в `ApiClientNetworkTest`: методы с `auth=true`
 * просто не шлют заголовок, MockWebServer это не важно. В `@After` на всякий случай зовём
 * `logout()` (чистит локальную сессию; без токена — безопасный no-op).
 *
 * НЕ покрыто здесь (другая зона / не подходит под шаблон):
 *  - `publishRide` / `moderateDriver` / `publishReview` — уже в `ApiClientNetworkTest`.
 *  - multipart/upload (`uploadVoice`, `uploadPhoto`, `uploadChatPhoto`) — другой транспорт.
 *  - fire-and-forget без `Result` (`fireShareTrip`, `fireSetTripStatus`, `fireAdEvent` и т.п.).
 *  - кешируемые GET (`cachedGet`) — не из этой группы.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiClientActionsTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun teardown() {
        ApiClient.logout()          // чистим возможные токен-сеттеры (без токена — no-op)
        ApiClient.testBaseUrl = null
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setResponseCode(200).setBody(body)

    // ---------- Брони: создание / отмена / оценка ----------

    @Test
    fun book_postsRideIdAndSeats_andParsesBookingId() = runBlocking {
        server.enqueue(json("""{"id":777}"""))
        val res = ApiClient.book(rideId = 42, seats = 2)
        assertTrue(res.isSuccess)
        assertEquals(777, res.getOrThrow())   // распарсенный id брони
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/bookings", rec.path)
        val body = rec.body.readUtf8()
        assertTrue(body.contains("\"ride_id\":42"))
        assertTrue(body.contains("\"seats\":2"))
    }

    @Test
    fun cancelBooking_postsToCancelPath_withoutBody() = runBlocking {
        server.enqueue(json("{}"))
        val res = ApiClient.cancelBooking(bookingId = 15)
        assertTrue(res.isSuccess)
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/bookings/15/cancel", rec.path)
    }

    @Test
    fun rateBooking_postsStars() = runBlocking {
        server.enqueue(json("{}"))
        ApiClient.rateBooking(bookingId = 9, stars = 5).getOrThrow()
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/bookings/9/rate", rec.path)
        assertTrue(rec.body.readUtf8().contains("\"stars\":5"))
    }

    // ---------- Активная поездка: поделиться / статус / сигнал водителя ----------

    @Test
    fun shareTrip_postsContactId() = runBlocking {
        server.enqueue(json("{}"))
        ApiClient.shareTrip(bookingId = 8, contactId = 3).getOrThrow()
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/bookings/8/share", rec.path)
        assertTrue(rec.body.readUtf8().contains("\"contact_id\":3"))
    }

    @Test
    fun setTripStatus_postsStatus() = runBlocking {
        server.enqueue(json("{}"))
        ApiClient.setTripStatus(bookingId = 8, status = "started").getOrThrow()
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/bookings/8/trip-status", rec.path)
        assertTrue(rec.body.readUtf8().contains("\"status\":\"started\""))
    }

    @Test
    fun driverStatus_postsDepartedSignal() = runBlocking {
        server.enqueue(json("{}"))
        ApiClient.driverStatus(bookingId = 8, status = "departed").getOrThrow()
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/bookings/8/driver-status", rec.path)
        assertTrue(rec.body.readUtf8().contains("\"status\":\"departed\""))
    }

    // ---------- Заявки ↔ водители: отклик / принятие / отмена ----------

    @Test
    fun respondToRequest_postsPriceAndComment() = runBlocking {
        server.enqueue(json("{}"))
        val res = ApiClient.respondToRequest(requestId = 55, price = 600, comment = "еду мимо")
        assertTrue(res.isSuccess)
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/requests/55/respond", rec.path)
        val body = rec.body.readUtf8()
        assertTrue(body.contains("\"price\":600"))
        assertTrue(body.contains("\"comment\":\"еду мимо\""))
    }

    @Test
    fun acceptResponse_parsesBookingId() = runBlocking {
        server.enqueue(json("""{"booking_id":321}"""))
        val res = ApiClient.acceptResponse(responseId = 12)
        assertTrue(res.isSuccess)
        assertEquals(321, res.getOrThrow())   // переход в активную поездку
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/responses/12/accept", rec.path)
    }

    @Test
    fun cancelRequest_postsToCancelPath() = runBlocking {
        server.enqueue(json("{}"))
        ApiClient.cancelRequest(requestId = 55).getOrThrow()
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/requests/55/cancel", rec.path)
    }

    // ---------- SOS ----------

    @Test
    fun sos_postsCategoryAndNote() = runBlocking {
        server.enqueue(json("{}"))
        ApiClient.sos(category = "danger", note = "нужна помощь").getOrThrow()
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/sos", rec.path)
        val body = rec.body.readUtf8()
        assertTrue(body.contains("\"category\":\"danger\""))
        assertTrue(body.contains("\"note\":\"нужна помощь\""))
    }

    // ---------- Ветки ошибок (общие для всех POST через call()) ----------

    @Test
    fun book_serverError500_returnsFailureWithApiException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        val res = ApiClient.book(rideId = 1, seats = 1)
        assertTrue(res.isFailure)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(500, (e as ApiException).status)
    }

    @Test
    fun acceptResponse_conflict400_returnsFailureWithApiException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Отклик уже принят"}"""))
        val res = ApiClient.acceptResponse(responseId = 12)
        assertTrue(res.isFailure)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(400, (e as ApiException).status)
        assertEquals("Отклик уже принят", e.message)   // detail с сервера прокидывается в текст
    }

    @Test
    fun cancelBooking_error400_returnsFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Поездка уже началась"}"""))
        val res = ApiClient.cancelBooking(bookingId = 15)
        assertTrue(res.isFailure)
        assertEquals(400, (res.exceptionOrNull() as ApiException).status)
    }
}
