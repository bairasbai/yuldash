package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Сетевой слой `ApiClient` на локальном MockWebServer (без реального бэкенда).
 * Покрывает `call()` (парсинг 2xx, обёртка массива в items, ветки ошибок) и парсеры DTO
 * ключевых методов. Базовый URL подменяется тест-хуком `ApiClient.testBaseUrl` (в проде null).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiClientNetworkTest {

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

    private fun json(body: String) = MockResponse().setResponseCode(200).setBody(body)

    // ---------- Парсинг списков ----------

    @Test
    fun getPendingReviews_parsesItems() = runBlocking {
        server.enqueue(json("""{"items":[{"id":1,"name":"Айгуль","city":"Уфа","stars":5,"text":"Класс"}]}"""))
        val res = ApiClient.getPendingReviews()
        assertTrue(res.isSuccess)
        val list = res.getOrThrow()
        assertEquals(1, list.size)
        assertEquals("Айгуль", list[0].name)
        assertEquals("Уфа", list[0].city)
        assertEquals(5, list[0].stars)
        assertEquals("Класс", list[0].text)
    }

    @Test
    fun getPendingReviews_emptyItems_returnsEmptyList() = runBlocking {
        server.enqueue(json("""{"items":[]}"""))
        val res = ApiClient.getPendingReviews()
        assertTrue(res.isSuccess)
        assertTrue(res.getOrThrow().isEmpty())
    }

    @Test
    fun getAdminReports_mapsSnakeCaseFields() = runBlocking {
        server.enqueue(json("""{"items":[{"id":7,"reporter_name":"Пётр","target_name":"Иван","target_phone":"+79990001122","reason":"спам","created_at":"2026-07-01T09:00:00"}]}"""))
        val list = ApiClient.getAdminReports().getOrThrow()
        assertEquals(1, list.size)
        assertEquals(7, list[0].id)
        assertEquals("Пётр", list[0].reporterName)
        assertEquals("Иван", list[0].targetName)
        assertEquals("+79990001122", list[0].targetPhone)
        assertEquals("спам", list[0].reason)
    }

    @Test
    fun getPendingDrivers_parsesDriverFields() = runBlocking {
        server.enqueue(json("""{"items":[{"user_id":42,"name":"Марат","phone":"+79995553311","car":"Kia Rio","autocheck_result":"pass"}]}"""))
        val list = ApiClient.getPendingDrivers().getOrThrow()
        assertEquals(1, list.size)
        assertEquals(42, list[0].userId)
        assertEquals("Марат", list[0].name)
        assertEquals("Kia Rio", list[0].car)
        assertEquals("pass", list[0].autocheckResult)
    }

    @Test
    fun getRequestResponses_handlesNullDriverRating() = runBlocking {
        server.enqueue(json("""{"items":[{"id":3,"driver_id":9,"driver_name":"Айдар","driver_rating":null,"price":600,"comment":"еду","status":"pending"}]}"""))
        val list = ApiClient.getRequestResponses(55).getOrThrow()
        assertEquals(1, list.size)
        assertEquals(9, list[0].driverId)
        assertEquals("Айдар", list[0].driverName)
        assertNull(list[0].driverRating)
        assertEquals(600, list[0].price)
    }

    @Test
    fun getRides_parsesRideDtoViaToRideDto() = runBlocking {
        server.enqueue(json("""{"items":[{"id":42,"from_city":"Уфа","to_city":"Казань","depart_at":"2026-07-01T10:00:00","seats_left":3,"price":500,"driver_name":"Марат"}]}"""))
        val list = ApiClient.getRides().getOrThrow()
        assertEquals(1, list.size)
        assertEquals(42, list[0].id)
        assertEquals("Уфа", list[0].fromCity)
        assertEquals("Казань", list[0].toCity)
        assertEquals(3, list[0].seatsLeft)
        assertEquals(500, list[0].price)
        assertEquals("Марат", list[0].driverName)
    }

    @Test
    fun getReportableUsers_topLevelArray_isWrappedIntoItems() = runBlocking {
        // Сервер отдал голый массив [ ... ] — call() должен обернуть в {items:[...]}.
        server.enqueue(json("""[{"id":1,"name":"Гость"}]"""))
        val list = ApiClient.getReportableUsers().getOrThrow()
        assertEquals(1, list.size)
        assertEquals("Гость", list[0].name)
    }

    // ---------- POST-действия + проверка запроса ----------

    @Test
    fun publishReview_sendsPostToCorrectPath() = runBlocking {
        server.enqueue(json("{}"))
        val res = ApiClient.publishReview(id = 12, published = true)
        assertTrue(res.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/admin/reviews/12/publish", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"published\":true"))
    }

    @Test
    fun moderateDriver_sendsApproveFlag() = runBlocking {
        server.enqueue(json("{}"))
        ApiClient.moderateDriver(userId = 7, approve = false).getOrThrow()
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/admin/drivers/7/moderate", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"approve\":false"))
    }

    // ---------- Ветки ошибок call() ----------

    @Test
    fun serverError500_returnsFailureWithApiException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        val res = ApiClient.getPendingReviews()
        assertTrue(res.isFailure)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(500, (e as ApiException).status)
    }

    @Test
    fun errorWithDetail_usesServerMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Плохой запрос"}"""))
        val res = ApiClient.getAdminReports()
        assertTrue(res.isFailure)
        assertEquals("Плохой запрос", res.exceptionOrNull()?.message)
    }

    @Test
    fun networkFailure_isCaughtIntoFailure() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val res = ApiClient.getPendingReviews()
        assertTrue(res.isFailure)
        assertFalse(res.isSuccess)
    }
}
