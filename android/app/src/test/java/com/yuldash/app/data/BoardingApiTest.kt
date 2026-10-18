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

    /** Поездка с остановками ждёт «Подъезжаю» (18.10.2026): признак в строке; старый сервер — нет. */
    @Test
    fun driverBookings_carryTheArrivingFirstFlag() {
        val row = parseDriverBookingDto(JSONObject("""{"booking_id":5,"status":"confirmed","no_show_at":null,"no_show_needs_arriving":true}"""))
        assertTrue(row.noShowNeedsArriving)
        assertNull(row.noShowAt)
        assertEquals(false, parseDriverBookingDto(JSONObject("""{"booking_id":5}""")).noShowNeedsArriving)
    }

    @Test
    fun markArriving_postsArrivingAndReadsTheNewWait() = runBlocking {
        server.enqueue(json("""{"ok":true,"driver_phase":"arriving","no_show_at":"2030-01-01T05:30:00","no_show_wait_sec":900,"no_show_needs_arriving":false}"""))
        val before = System.currentTimeMillis()
        val terms = BoardingApi.markArriving(42).getOrThrow()
        assertEquals("2030-01-01T05:30:00", terms.noShowAt)
        assertEquals(false, terms.needsArriving)
        val until = terms.waitUntilMs ?: error("секунды сервера потерялись")
        assertTrue("сейчас + 900 с по часам телефона", until in (before + 900_000L)..(System.currentTimeMillis() + 900_000L))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/bookings/42/driver-status", recorded.path)
        assertEquals("arriving", JSONObject(recorded.body.readUtf8()).getString("status"))
    }

    @Test
    fun noShowTerms_toleratesOldServersAndNegativeSeconds() {
        assertEquals(NoShowTerms(null), parseNoShowTerms(JSONObject("""{"ok":true,"driver_phase":"arriving"}"""), nowMs = 1_000L))
        assertEquals(NoShowTerms(null), parseNoShowTerms(null, nowMs = 1_000L))
        assertEquals(NoShowTerms(null), parseNoShowTerms(JSONObject("""{"no_show_at":"","no_show_wait_sec":null}"""), nowMs = 1_000L))
        assertEquals(1_000L, parseNoShowTerms(JSONObject("""{"no_show_wait_sec":-5}"""), nowMs = 1_000L).waitUntilMs)
    }

    /** Отказ несёт новый срок: «рано» — момент и секунды, «сначала Подъезжаю» — признак, «Подъезжаю рано» —
     *  её момент и секунды (приёмка PR184). Остальное в сроках не теряется. */
    @Test
    fun refusal_givesTheCardItsNewTerms() {
        val base = NoShowTerms(null, needsArriving = true, arrivingOpensAt = "2030-01-01T04:30:00")
        val early = NoShowTerms("2030-01-01T05:00:00").afterRefusal(
            "no_show_too_early", JSONObject("""{"code":"no_show_too_early","no_show_wait_sec":600,"no_show_at":"2030-01-01T05:15:00"}"""), nowMs = 1_000L,
        )
        assertEquals(NoShowTerms("2030-01-01T05:15:00", needsArriving = false, waitUntilMs = 601_000L), early)
        assertEquals(
            NoShowTerms(null, needsArriving = true),
            NoShowTerms("2030-01-01T05:15:00", waitUntilMs = 9L).afterRefusal(
                "no_show_needs_arriving", JSONObject("""{"code":"no_show_needs_arriving","no_show_at":null}"""),
            ),
        )
        assertEquals(
            base.copy(arrivingOpensAt = "2030-01-01T04:40:00", arrivingWaitUntilMs = 301_000L),
            base.afterRefusal(
                "arriving_too_early",
                JSONObject("""{"code":"arriving_too_early","arriving_opens_at":"2030-01-01T04:40:00","arriving_wait_sec":300}"""),
                nowMs = 1_000L,
            ),
        )
        assertNull(base.afterRefusal("passenger_onboard", JSONObject("""{"boarding_undo_sec":120}""")))
        assertNull(base.afterRefusal("", null))
    }

    /** Строка и ответ «Подъезжаю» несут и момент «Подъезжаю» (за 30 минут до выезда, приёмка PR184). */
    @Test
    fun arrivingWindow_isReadFromTheRowAndTheTerms() {
        val row = parseDriverBookingDto(JSONObject("""{"booking_id":5,"status":"confirmed","no_show_needs_arriving":true,"arriving_opens_at":"2030-01-01T04:30:00"}"""))
        assertEquals("2030-01-01T04:30:00", row.arrivingOpensAt)
        assertNull(parseDriverBookingDto(JSONObject("""{"booking_id":5}""")).arrivingOpensAt)
        val terms = parseNoShowTerms(JSONObject("""{"arriving_opens_at":"2030-01-01T04:30:00","arriving_wait_sec":120}"""), nowMs = 1_000L)
        assertEquals("2030-01-01T04:30:00", terms.arrivingOpensAt)
        assertEquals(121_000L, terms.arrivingWaitUntilMs)
    }
}
