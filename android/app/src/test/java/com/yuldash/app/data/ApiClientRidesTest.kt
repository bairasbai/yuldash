package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
 * Сетевой слой `ApiClient` — методы ПОЕЗДОК и ЗАЯВОК на локальном MockWebServer (без реального бэкенда).
 * Покрывает: getNearbyRides, getNearbyRidesPaged, getNearbyRequests, getPriceHint, getMyRequests,
 * getRequestsFeed, publishRide, getBlocks. Проверяем парсинг DTO (snake_case-ключи из кода),
 * отправляемые query/body (server.takeRequest) и ветки ошибок call().
 *
 * Базовый URL подменяется тест-хуком `ApiClient.testBaseUrl` (в проде null → идём на BuildConfig).
 * Примечание: publishRide зовёт Analytics.log в onSuccess, но в тестах Firebase не инициализирован
 * (fa == null) → это безопасный no-op, ничего не падает.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiClientRidesTest {

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

    // ---------- getNearbyRides ----------

    @Test
    fun getNearbyRides_parsesRideDto() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"id":42,"from_city":"Уфа","to_city":"Казань","depart_at":"2026-07-01T10:00:00",
                "seats_total":4,"seats_left":3,"price":500,"category":"regular","driver_name":"Марат",
                "driver_rating":4.7,"driver_verified":true,"driver_car":"Kia Rio","distance_km":12.5,
                "women_only":true,"baggage":true}]}""".trimIndent(),
            ),
        )
        val list = ApiClient.getNearbyRides("Уфа", "Казань", lat = 54.7, lng = 55.9, radiusKm = 30.0).getOrThrow()
        assertEquals(1, list.size)
        val r = list[0]
        assertEquals(42, r.id)
        assertEquals("Уфа", r.fromCity)
        assertEquals("Казань", r.toCity)
        assertEquals(4, r.seatsTotal)
        assertEquals(3, r.seatsLeft)
        assertEquals(500, r.price)
        assertEquals("Марат", r.driverName)
        assertEquals(4.7, r.driverRating, 0.001)
        assertTrue(r.driverVerified)
        assertEquals("Kia Rio", r.driverCar)
        assertEquals(12.5, r.distanceKm!!, 0.001)
        assertTrue(r.womenOnly)
        assertTrue(r.baggage)
    }

    @Test
    fun getNearbyRides_sendsQueryParams() = runBlocking {
        server.enqueue(json("""{"items":[]}"""))
        ApiClient.getNearbyRides("Уфа", "Казань", lat = 54.7, lng = 55.9, radiusKm = 30.0).getOrThrow()
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        val path = recorded.path.orEmpty()
        assertTrue(path.startsWith("/rides/near?"))
        // Города URL-энкодятся (кириллица), проверяем ключи + числовые значения.
        assertTrue(path.contains("from_city="))
        assertTrue(path.contains("to_city="))
        assertTrue(path.contains("lat=54.7"))
        assertTrue(path.contains("lng=55.9"))
        assertTrue(path.contains("radius_km=30.0"))
    }

    @Test
    fun getNearbyRides_noParams_hitsPlainPath() = runBlocking {
        server.enqueue(json("""{"items":[]}"""))
        val list = ApiClient.getNearbyRides(null, null).getOrThrow()
        assertTrue(list.isEmpty())
        val recorded = server.takeRequest()
        assertEquals("/rides/near", recorded.path)
    }

    // ---------- getNearbyRidesPaged (NearbyPage) ----------

    @Test
    fun getNearbyRidesPaged_parsesItemsAndTotalFromCount() = runBlocking {
        // total берётся из ключа "count" (не "total") — см. NearbyPage(..., obj.optInt("count")).
        server.enqueue(
            json(
                """{"count":17,"items":[
                {"id":1,"from_city":"Уфа","to_city":"Сибай","price":700,"seats_left":2,"driver_name":"Айдар"},
                {"id":2,"from_city":"Уфа","to_city":"Сибай","price":650,"seats_left":1,"driver_name":"Рустам"}]}""".trimIndent(),
            ),
        )
        val page = ApiClient.getNearbyRidesPaged("Уфа", "Сибай", limit = 2).getOrThrow()
        assertEquals(2, page.items.size)
        assertEquals(17, page.total)
        assertEquals(1, page.items[0].id)
        assertEquals("Айдар", page.items[0].driverName)
        assertEquals(650, page.items[1].price)
    }

    @Test
    fun getNearbyRidesPaged_sendsLimitInQuery() = runBlocking {
        server.enqueue(json("""{"count":0,"items":[]}"""))
        ApiClient.getNearbyRidesPaged("Уфа", "Сибай", limit = 5).getOrThrow()
        val recorded = server.takeRequest()
        val path = recorded.path.orEmpty()
        assertTrue(path.startsWith("/rides/near?"))
        assertTrue(path.contains("limit=5"))
    }

    // ---------- getNearbyRequests ----------

    @Test
    fun getNearbyRequests_parsesRequestNearDto() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"id":9,"passenger_name":"Гульназ","from_city":"Уфа","to_city":"Бирск",
                "from_lat":54.9,"from_lng":56.0,"seats":2,"comment":"с детьми","distance_km":3.2}]}""".trimIndent(),
            ),
        )
        val list = ApiClient.getNearbyRequests(lat = 54.7, lng = 55.9, radiusKm = 20.0).getOrThrow()
        assertEquals(1, list.size)
        val q = list[0]
        assertEquals(9, q.id)
        assertEquals("Гульназ", q.passengerName)
        assertEquals("Уфа", q.fromCity)
        assertEquals("Бирск", q.toCity)
        assertEquals(54.9, q.fromLat!!, 0.001)
        assertEquals(56.0, q.fromLng!!, 0.001)
        assertEquals(2, q.seats)
        assertEquals("с детьми", q.comment)
        assertEquals(3.2, q.distanceKm!!, 0.001)
    }

    @Test
    fun getNearbyRequests_blankName_fallsBackToPassenger() = runBlocking {
        // passenger_name пустой → toRequestNearDto подставляет "Пассажир".
        server.enqueue(json("""{"items":[{"id":1,"passenger_name":"","from_city":"Уфа","to_city":"Бирск","seats":1}]}"""))
        val list = ApiClient.getNearbyRequests().getOrThrow()
        assertEquals(1, list.size)
        assertEquals("Пассажир", list[0].passengerName)
        assertNull(list[0].fromLat)
        assertNull(list[0].distanceKm)
    }

    // ---------- getPriceHint ----------

    @Test
    fun getPriceHint_parsesAvgAndCount() = runBlocking {
        server.enqueue(json("""{"avg":540,"count":8}"""))
        val hint = ApiClient.getPriceHint("Уфа", "Казань").getOrThrow()
        assertEquals(540, hint.avg)
        assertEquals(8, hint.count)
        val recorded = server.takeRequest()
        val path = recorded.path.orEmpty()
        assertTrue(path.startsWith("/rides/price_hint?"))
        assertTrue(path.contains("from_city="))
        assertTrue(path.contains("to_city="))
    }

    @Test
    fun getPriceHint_noData_countZero() = runBlocking {
        server.enqueue(json("""{"avg":0,"count":0}"""))
        val hint = ApiClient.getPriceHint("Уфа", "Мелеуз").getOrThrow()
        assertEquals(0, hint.avg)
        assertEquals(0, hint.count)
    }

    // ---------- getMyRequests ----------

    @Test
    fun getMyRequests_mapsSnakeCaseFields() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"id":3,"from_city":"Уфа","to_city":"Октябрьский","seats":2,"category":"regular",
                "with_kids":true,"max_price":800,"comment":"утром","for_relative_name":"Бабушка",
                "status":"active","desired_at":"2026-07-02T08:00:00"}]}""".trimIndent(),
            ),
        )
        val list = ApiClient.getMyRequests().getOrThrow()
        assertEquals(1, list.size)
        val q = list[0]
        assertEquals(3, q.id)
        assertEquals("Уфа", q.fromCity)
        assertEquals("Октябрьский", q.toCity)
        assertEquals(2, q.seats)
        assertTrue(q.withKids)
        assertEquals(800, q.maxPrice)
        assertEquals("Бабушка", q.forRelativeName)
        assertEquals("active", q.status)
        assertEquals("2026-07-02T08:00:00", q.desiredAt)
    }

    @Test
    fun getMyRequests_blankRelativeAndDesired_becomeNull() = runBlocking {
        // for_relative_name / desired_at пустые → .ifBlank { null }.
        server.enqueue(json("""{"items":[{"id":5,"from_city":"Уфа","to_city":"Салават","seats":1,"status":"matched"}]}"""))
        val q = ApiClient.getMyRequests().getOrThrow()[0]
        assertNull(q.forRelativeName)
        assertNull(q.desiredAt)
        assertEquals("matched", q.status)
    }

    // ---------- getRequestsFeed ----------

    @Test
    fun getRequestsFeed_parsesPrefsArrayAndResponded() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"id":11,"passenger_name":"Ильдар","from_city":"Уфа","to_city":"Нефтекамск",
                "seats":3,"comment":"еду в гости","responded":true,"passenger_avatar":"http://a/1.jpg",
                "prefs":["baggage","women_only"]}]}""".trimIndent(),
            ),
        )
        val list = ApiClient.getRequestsFeed().getOrThrow()
        assertEquals(1, list.size)
        val f = list[0]
        assertEquals(11, f.id)
        assertEquals("Ильдар", f.passengerName)
        assertEquals("Уфа", f.from)
        assertEquals("Нефтекамск", f.to)
        assertEquals(3, f.seats)
        assertTrue(f.responded)
        assertEquals("http://a/1.jpg", f.passengerAvatar)
        assertEquals(listOf("baggage", "women_only"), f.prefs)
    }

    @Test
    fun getRequestsFeed_missingPrefs_emptyList() = runBlocking {
        // prefs отсутствует → пустой список (не падаем).
        server.enqueue(json("""{"items":[{"id":1,"passenger_name":"Азат","from_city":"Уфа","to_city":"Дюртюли","seats":1,"responded":false}]}"""))
        val f = ApiClient.getRequestsFeed().getOrThrow()[0]
        assertFalse(f.responded)
        assertTrue(f.prefs.isEmpty())
    }

    // ---------- publishRide (POST + проверка тела) ----------

    @Test
    fun publishRide_sendsPostWithSnakeCaseBody() = runBlocking {
        server.enqueue(json("""{"id":42}"""))
        val res = ApiClient.publishRide(
            fromCity = "Уфа",
            toCity = "Стерлитамак",
            departAt = "2026-07-05T09:30:00",
            seats = 3,
            price = 450,
            comment = "поеду утром",
            womenOnly = true,
            baggage = true,
            category = "regular",
        )
        assertEquals(42, res.getOrThrow())
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/rides", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"from_city\":\"Уфа\""))
        assertTrue(body.contains("\"to_city\":\"Стерлитамак\""))
        assertTrue(body.contains("\"depart_at\":\"2026-07-05T09:30:00\""))
        assertTrue(body.contains("\"seats_total\":3"))
        assertTrue(body.contains("\"price\":450"))
        assertTrue(body.contains("\"women_only\":true"))
        assertTrue(body.contains("\"baggage\":true"))
        assertTrue(body.contains("\"category\":\"regular\""))
    }

    // ---------- getBlocks ----------

    @Test
    fun getBlocks_parsesBlockDto() = runBlocking {
        server.enqueue(json("""{"items":[{"blocked_user_id":77,"name":"Спамер"},{"blocked_user_id":88,"name":"Грубиян"}]}"""))
        val list = ApiClient.getBlocks().getOrThrow()
        assertEquals(2, list.size)
        assertEquals(77, list[0].blockedUserId)
        assertEquals("Спамер", list[0].name)
        assertEquals(88, list[1].blockedUserId)
    }

    @Test
    fun getBlocks_topLevelArray_isWrappedIntoItems() = runBlocking {
        // Сервер отдал голый массив [ ... ] — call() оборачивает в {items:[...]}.
        server.enqueue(json("""[{"blocked_user_id":5,"name":"Тест"}]"""))
        val list = ApiClient.getBlocks().getOrThrow()
        assertEquals(1, list.size)
        assertEquals(5, list[0].blockedUserId)
        assertEquals("Тест", list[0].name)
    }

    // ---------- Ветки ошибок call() ----------

    @Test
    fun getMyRequests_serverError500_returnsFailureWithApiException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        val res = ApiClient.getMyRequests()
        assertTrue(res.isFailure)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(500, (e as ApiException).status)
    }

    @Test
    fun getPriceHint_error400_usesServerDetail() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Маршрут не найден"}"""))
        val res = ApiClient.getPriceHint("", "")
        assertTrue(res.isFailure)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(400, (e as ApiException).status)
        assertEquals("Маршрут не найден", e.message)
    }
}
