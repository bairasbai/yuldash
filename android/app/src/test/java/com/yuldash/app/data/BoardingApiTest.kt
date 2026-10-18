package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Посадку отмечает водитель (решение 17.10.2026): «Сел» — `driver-status` «boarded», «Отменить» —
 * `/boarded/undo`. Проверяем адрес, тело и разбор ответа; отказ сервера доходит до экрана как
 * [ApiException] с его словами.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BoardingApiTest {

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

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    @Test
    fun markBoarded_postsBoardedToDriverStatusAndReadsTheUndoWindow() = runBlocking {
        server.enqueue(json("""{"ok":true,"status":"onboard","boarding_undo_sec":300}"""))
        val result = BoardingApi.markBoarded(42).getOrThrow()
        assertEquals(BoardingResult(status = "onboard", undoSec = 300), result)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/bookings/42/driver-status", recorded.path)
        assertEquals("boarded", JSONObject(recorded.body.readUtf8()).getString("status"))
    }

    @Test
    fun undoBoarded_postsToUndoAndReturnsConfirmed() = runBlocking {
        server.enqueue(json("""{"ok":true,"status":"confirmed","boarding_undo_sec":0}"""))
        val result = BoardingApi.undoBoarded(42).getOrThrow()
        assertEquals(BoardingResult(status = "confirmed", undoSec = 0), result)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/bookings/42/boarded/undo", recorded.path)
    }

    @Test
    fun refusal_isAnApiExceptionWithTheServerWords() = runBlocking {
        server.enqueue(json("""{"detail":{"ru":"Отменить посадку можно в первые 5 минут.","ba":"Тәүге 5 минутта ғына."}}""", 409))
        val error = BoardingApi.undoBoarded(42).exceptionOrNull()
        assertTrue(error is ApiException)
        assertEquals(409, (error as ApiException).status)
        assertTrue(error.message.orEmpty().isNotBlank())
    }

    @Test
    fun parse_neverReturnsNegativeSecondsAndToleratesOldServers() {
        assertEquals(0, parseBoardingResult(JSONObject("""{"status":"onboard","boarding_undo_sec":-5}""")).undoSec)
        assertEquals(BoardingResult(status = "", undoSec = 0), parseBoardingResult(JSONObject("{}")))
    }

    @Test
    fun tripStateAndDriverBookings_carryTheBoardingFields() = runBlocking {
        server.enqueue(json("""{"role":"driver","status":"onboard","driver_phase":"arriving","peer_id":7,"boarding_undo_sec":120}"""))
        val state = ApiClient.getTripState(42).getOrThrow()
        assertEquals(7, state.peerId)
        assertEquals(120, state.boardingUndoSec)
        val row = parseDriverBookingDto(JSONObject("""{"booking_id":5,"status":"onboard","boarding_undo_sec":90}"""))
        assertEquals(90, row.boardingUndoSec)
        assertEquals(0, parseDriverBookingDto(JSONObject("""{"booking_id":5}""")).boardingUndoSec)
    }

    /** «Не пришёл» доступна с момента `no_show_at` (выезд + 15 минут, считает сервер — 18.10.2026). */
    @Test
    fun driverBookings_carryTheMomentNoShowOpens() {
        val row = parseDriverBookingDto(JSONObject("""{"booking_id":5,"status":"confirmed","no_show_at":"2030-01-01T05:15:00"}"""))
        assertEquals("2030-01-01T05:15:00", row.noShowAt)
        // Старый сервер без поля, null и пустая строка — срока нет: кнопку не прячем, решает сервер.
        assertNull(parseDriverBookingDto(JSONObject("""{"booking_id":5}""")).noShowAt)
        assertNull(parseDriverBookingDto(JSONObject("""{"booking_id":5,"no_show_at":null}""")).noShowAt)
        assertNull(parseDriverBookingDto(JSONObject("""{"booking_id":5,"no_show_at":""}""")).noShowAt)
    }
}
