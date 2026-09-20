package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * «ПЛОХИЕ СЦЕНАРИИ» сети на КРИТИЧНЫХ методах `ApiClient` (регистрация / поездка / оплата / чат)
 * на локальном MockWebServer. Цель — устойчивость к сбоям, а НЕ happy-path (он покрыт в
 * `ApiClientAuthTest` / `ApiClientRidesTest` / `ApiClientActionsTest` / `ApiClientBookingsTest`
 * / `ApiClientNetworkTest` — здесь их не дублируем).
 *
 * Матрица на метод: обрыв соединения (нет сети) → 500 сервер → 500/400 с detail →
 * пустой список (для list-методов) → битый JSON на 200 (парсер ловит в try/catch call()).
 *
 * Токен НЕ выставляем: методы с auth=true просто не шлют заголовок Authorization — MockWebServer
 * это не важно, а тела/ответы разбираются так же. `logout()` в `@Before`/`@After` чистит локальную
 * сессию и кеш GET-ответов (иначе токен, случайно сохранённый другим тестом при 2xx-разборе, или
 * закешированный ответ «протекли» бы сюда). Без токена logout() — безопасный no-op на сервер.
 *
 * Analytics.log в `onSuccess` некоторых методов здесь не срабатывает (все сценарии — ошибка),
 * а сам он no-op без Firebase, так что краша нет в любом случае.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiClientCriticalBadPathTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        // Test failure classification, not production-length waits or retry scheduling.
        ApiClient.testTimeoutMs = 1000
        server = MockWebServer()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.logout()   // чистим токен/кеш от прошлых тестов (без токена — no-op на сервер)
    }

    @After
    fun teardown() {
        ApiClient.logout()
        ApiClient.testBaseUrl = null
        server.shutdown()
        ApiClient.testTimeoutMs = null
    }

    private fun disconnect() = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
    private fun error500() = MockResponse().setResponseCode(500).setBody("")
    private fun detail(code: Int, msg: String) =
        MockResponse().setResponseCode(code).setBody("""{"detail":"$msg"}""")
    private fun brokenJson() = MockResponse().setResponseCode(200).setBody("{не json")
    private fun emptyItems() = MockResponse().setResponseCode(200).setBody("""{"items":[]}""")

    /** Общая проверка «упало с ApiException нужного статуса» (не краш, а Result.failure). */
    private fun assertApiStatus(res: Result<*>, status: Int) {
        assertTrue("ожидали Result.failure", res.isFailure)
        val e = res.exceptionOrNull()
        assertTrue("ожидали ApiException, а был $e", e is ApiException)
        assertEquals(status, (e as ApiException).status)
    }

    // ======================================================================
    //  РЕГИСТРАЦИЯ: requestCode / verifyCode / tgVerify
    // ======================================================================

    @Test
    fun requestCode_disconnect_returnsFailure() = runBlocking {
        server.enqueue(disconnect())
        val res = ApiClient.requestCode("+79990001122")
        assertTrue(res.isFailure)
        assertFalse(res.isSuccess)
    }

    @Test
    fun requestCode_serverError500_failureWithStatus() = runBlocking {
        server.enqueue(error500())
        assertApiStatus(ApiClient.requestCode("+79990001122"), 500)
    }

    @Test
    fun verifyCode_disconnect_returnsFailure() = runBlocking {
        server.enqueue(disconnect())
        val res = ApiClient.verifyCode("+79991234567", "1234", "Айгуль")
        assertTrue(res.isFailure)
        assertFalse(res.isSuccess)
    }

    @Test
    fun verifyCode_error400WithDetail_usesServerMessage() = runBlocking {
        server.enqueue(detail(400, "Неверный код"))
        val res = ApiClient.verifyCode("+79991234567", "0000", "Гость")
        assertApiStatus(res, 400)
        assertEquals("Неверный код", res.exceptionOrNull()?.message)
    }

    @Test
    fun verifyCode_brokenJsonOn200_isCaughtIntoFailure() = runBlocking {
        // 200, но тело не JSON: JSONObject(text) в call() кидает, ловится в try/catch → failure, не краш.
        server.enqueue(brokenJson())
        val res = ApiClient.verifyCode("+79991234567", "1234", "Айгуль")
        assertTrue(res.isFailure)
    }

    @Test
    fun tgVerify_serverError500_failureWithStatus() = runBlocking {
        server.enqueue(error500())
        assertApiStatus(ApiClient.tgVerify("req_1", "654321"), 500)
    }

    @Test
    fun tgVerify_error400WithDetail_usesServerMessage() = runBlocking {
        server.enqueue(detail(400, "Код истёк"))
        val res = ApiClient.tgVerify("req_1", "000000")
        assertApiStatus(res, 400)
        assertEquals("Код истёк", res.exceptionOrNull()?.message)
    }

    // ======================================================================
    //  ПОЕЗДКА: publishRide / book / getRides / getNearbyRides
    // ======================================================================

    @Test
    fun publishRide_disconnect_returnsFailure() = runBlocking {
        server.enqueue(disconnect())
        val res = ApiClient.publishRide(
            fromCity = "Уфа", toCity = "Казань",
            departAt = "2026-07-01T10:00:00", seats = 3, price = 500, comment = "",
        )
        assertTrue(res.isFailure)
        assertFalse(res.isSuccess)
    }

    @Test
    fun publishRide_serverError500_failureWithStatus() = runBlocking {
        server.enqueue(error500())
        val res = ApiClient.publishRide(
            fromCity = "Уфа", toCity = "Казань",
            departAt = "2026-07-01T10:00:00", seats = 3, price = 500, comment = "",
        )
        assertApiStatus(res, 500)
    }

    @Test
    fun book_disconnect_returnsFailure() = runBlocking {
        server.enqueue(disconnect())
        val res = ApiClient.book(rideId = 42, seats = 2)
        assertTrue(res.isFailure)
        assertFalse(res.isSuccess)
    }

    @Test
    fun book_error400WithDetail_usesServerMessage() = runBlocking {
        // типовой отказ бэка: мест не осталось.
        server.enqueue(detail(400, "Мест не осталось"))
        val res = ApiClient.book(rideId = 42, seats = 2)
        assertApiStatus(res, 400)
        assertEquals("Мест не осталось", res.exceptionOrNull()?.message)
    }

    @Test
    fun getRides_disconnect_returnsFailure() = runBlocking {
        server.enqueue(disconnect())
        val res = ApiClient.getRides()
        assertTrue(res.isFailure)
        assertFalse(res.isSuccess)
    }

    @Test
    fun getRides_serverError500_failureWithStatus() = runBlocking {
        server.enqueue(error500())
        assertApiStatus(ApiClient.getRides(), 500)
    }

    @Test
    fun getRides_emptyItems_returnsEmptyListNotCrash() = runBlocking {
        server.enqueue(emptyItems())
        val res = ApiClient.getRides()
        assertTrue(res.isSuccess)
        assertTrue(res.getOrThrow().isEmpty())
    }

    @Test
    fun getNearbyRides_disconnect_returnsFailure() = runBlocking {
        server.enqueue(disconnect())
        val res = ApiClient.getNearbyRides("Уфа", "Казань")
        assertTrue(res.isFailure)
        assertFalse(res.isSuccess)
    }

    @Test
    fun getNearbyRides_emptyItems_returnsEmptyListNotCrash() = runBlocking {
        server.enqueue(emptyItems())
        val res = ApiClient.getNearbyRides("Уфа", "Казань", lat = 54.7, lng = 55.9, radiusKm = 30.0)
        assertTrue(res.isSuccess)
        assertTrue(res.getOrThrow().isEmpty())
    }

    @Test
    fun getNearbyRides_brokenJsonOn200_isCaughtIntoFailure() = runBlocking {
        server.enqueue(brokenJson())
        val res = ApiClient.getNearbyRides("Уфа", "Казань")
        assertTrue(res.isFailure)
    }

    // ======================================================================
    //  ОПЛАТА / ДОНАТ / БУСТ: createDonation / createBoost / payAd / boostFree
    // ======================================================================

    @Test
    fun createDonation_disconnect_returnsFailure() = runBlocking {
        server.enqueue(disconnect())
        val res = ApiClient.createDonation(amount = 500)
        assertTrue(res.isFailure)
        assertFalse(res.isSuccess)
    }

    @Test
    fun createDonation_serverError500_failureWithStatus() = runBlocking {
        server.enqueue(error500())
        assertApiStatus(ApiClient.createDonation(amount = 500), 500)
    }

    @Test
    fun createBoost_disconnect_returnsFailure() = runBlocking {
        server.enqueue(disconnect())
        val res = ApiClient.createBoost(rideId = 7, tier = "gold")
        assertTrue(res.isFailure)
        assertFalse(res.isSuccess)
    }

    @Test
    fun createBoost_error400WithDetail_usesServerMessage() = runBlocking {
        server.enqueue(detail(400, "Поездка уже поднята"))
        val res = ApiClient.createBoost(rideId = 7, tier = "gold")
        assertApiStatus(res, 400)
        assertEquals("Поездка уже поднята", res.exceptionOrNull()?.message)
    }

    @Test
    fun payAd_disconnect_returnsFailure() = runBlocking {
        server.enqueue(disconnect())
        val res = ApiClient.payAd(id = "m4")
        assertTrue(res.isFailure)
        assertFalse(res.isSuccess)
    }

    @Test
    fun payAd_serverError500_failureWithStatus() = runBlocking {
        server.enqueue(error500())
        assertApiStatus(ApiClient.payAd(id = "m4"), 500)
    }

    @Test
    fun boostFree_error400WithDetail_usesServerMessage() = runBlocking {
        server.enqueue(detail(400, "Недостаточно бонусов"))
        val res = ApiClient.boostFree(rideId = 7)
        assertApiStatus(res, 400)
        assertEquals("Недостаточно бонусов", res.exceptionOrNull()?.message)
    }

    // ======================================================================
    //  ЧАТ: getMessages / sendMessage
    // ======================================================================

    @Test
    fun getMessages_disconnect_returnsFailure() = runBlocking {
        server.enqueue(disconnect())
        val res = ApiClient.getMessages(bookingId = 5)
        assertTrue(res.isFailure)
        assertFalse(res.isSuccess)
    }

    @Test
    fun getMessages_serverError500_failureWithStatus() = runBlocking {
        server.enqueue(error500())
        assertApiStatus(ApiClient.getMessages(bookingId = 5), 500)
    }

    @Test
    fun getMessages_emptyItems_returnsEmptyListNotCrash() = runBlocking {
        server.enqueue(emptyItems())
        val res = ApiClient.getMessages(bookingId = 5)
        assertTrue(res.isSuccess)
        assertTrue(res.getOrThrow().isEmpty())
    }

    @Test
    fun getMessages_brokenJsonOn200_isCaughtIntoFailure() = runBlocking {
        server.enqueue(brokenJson())
        val res = ApiClient.getMessages(bookingId = 5)
        assertTrue(res.isFailure)
    }

    @Test
    fun sendMessage_disconnect_returnsFailure() = runBlocking {
        server.enqueue(disconnect())
        val res = ApiClient.sendMessage(bookingId = 5, text = "привет")
        assertTrue(res.isFailure)
        assertFalse(res.isSuccess)
    }

    @Test
    fun sendMessage_error400WithDetail_usesServerMessage() = runBlocking {
        server.enqueue(detail(400, "Чат недоступен"))
        val res = ApiClient.sendMessage(bookingId = 5, text = "привет")
        assertApiStatus(res, 400)
        assertEquals("Чат недоступен", res.exceptionOrNull()?.message)
    }
}
