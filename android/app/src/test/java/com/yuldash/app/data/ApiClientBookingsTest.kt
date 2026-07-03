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
 * Сетевой слой `ApiClient`: read-методы группы БРОНИ / ЧАТА / ПРОФИЛЯ ВОДИТЕЛЯ / РЕКЛАМЫ
 * на локальном MockWebServer (без реального бэкенда). Проверяем парсинг DTO (snake_case-ключи
 * из кода), обёртку голого массива в {items:[...]} и ветки ошибок (500/400 → ApiException.status).
 * Базовый URL подменяется тест-хуком `ApiClient.testBaseUrl` (в проде null).
 *
 * Пропущены (см. отчёт агента): методы с Analytics/Firebase, multipart/upload (uploadVoice,
 * uploadChatPhoto, uploadPhoto), WebSocket. Кеширующие (cachedGet) методы вызываются РОВНО ОДИН
 * раз за тест — тестируем первый (сетевой) вызов; их общий процессный кеш между тестами не чистится,
 * поэтому каждый кеш-метод покрыт единожды.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiClientBookingsTest {

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

    // ---------- Брони ----------

    @Test
    fun getMyBookings_parsesItemIds() = runBlocking {
        server.enqueue(json("""{"items":[{"id":11},{"id":22},{"id":33}]}"""))
        val res = ApiClient.getMyBookings()
        assertTrue(res.isSuccess)
        val ids = res.getOrThrow()
        assertEquals(listOf(11, 22, 33), ids)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/bookings/mine", recorded.path)
    }

    @Test
    fun getMyBookingsDetailed_mapsSummaryFields() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"id":5,"ride_id":90,"seats":2,"price":700,"status":"active",
                   "boarding_code":"7788","from_city":"Уфа","to_city":"Сибай",
                   "depart_at":"2026-07-02T08:00:00","driver_name":"Ильдар","driver_verified":true}]}"""
            )
        )
        val list = ApiClient.getMyBookingsDetailed().getOrThrow()
        assertEquals(1, list.size)
        val b = list[0]
        assertEquals(5, b.id)
        assertEquals(90, b.rideId)
        assertEquals(2, b.seats)
        assertEquals(700, b.price)
        assertEquals("active", b.status)
        assertEquals("7788", b.boardingCode)
        assertEquals("Уфа", b.fromCity)
        assertEquals("Сибай", b.toCity)
        assertEquals("Ильдар", b.driverName)
        assertTrue(b.driverVerified)
    }

    @Test
    fun getBookingDetails_parsesPrivateFieldsAndNullCoords() = runBlocking {
        // pickup_lat/lng заданы, from/to/toLat координаты — null (isNull → поле DTO null).
        server.enqueue(
            json(
                """{"booking_id":42,"ride_id":90,"role":"passenger","status":"confirmed",
                   "contact_unlocked":true,"from_city":"Уфа","to_city":"Казань",
                   "depart_at":"2026-07-02T09:00:00","seats":3,"price":500,
                   "driver_name":"Марат","driver_verified":true,"driver_phone":"+79990001122",
                   "driver_car":"Kia Rio","pickup":"у вокзала",
                   "pickup_lat":54.7,"pickup_lng":55.9,
                   "from_lat":null,"from_lng":null,"to_lat":null,"to_lng":null}"""
            )
        )
        val d = ApiClient.getBookingDetails(42).getOrThrow()
        assertEquals(42, d.bookingId)
        assertEquals(90, d.rideId)
        assertEquals("passenger", d.role)
        assertEquals("confirmed", d.status)
        assertTrue(d.contactUnlocked)
        assertEquals("Уфа", d.fromCity)
        assertEquals(3, d.seats)
        assertEquals(500, d.price)
        assertEquals("Марат", d.driverName)
        assertTrue(d.driverVerified)
        assertEquals("+79990001122", d.driverPhone)
        assertEquals("Kia Rio", d.driverCar)
        assertEquals(54.7, d.pickupLat!!, 0.0001)
        assertEquals(55.9, d.pickupLng!!, 0.0001)
        assertNull(d.fromLat)
        assertNull(d.toLat)
        // путь берёт id из аргумента
        assertEquals("/bookings/42/details", server.takeRequest().path)
    }

    @Test
    fun getBoardingCode_extractsCodeString() = runBlocking {
        server.enqueue(json("""{"code":"4271"}"""))
        val res = ApiClient.getBoardingCode(7)
        assertTrue(res.isSuccess)
        assertEquals("4271", res.getOrThrow())
        assertEquals("/bookings/7/boarding-code", server.takeRequest().path)
    }

    @Test
    fun getBookingRole_extractsRole() = runBlocking {
        server.enqueue(json("""{"role":"driver","status":"active","driver_phase":"departed"}"""))
        val res = ApiClient.getBookingRole(3)
        assertTrue(res.isSuccess)
        assertEquals("driver", res.getOrThrow())
    }

    @Test
    fun getTripState_parsesRoleStatusPhase() = runBlocking {
        server.enqueue(json("""{"role":"driver","status":"active","driver_phase":"arriving"}"""))
        val s = ApiClient.getTripState(3).getOrThrow()
        assertEquals("driver", s.role)
        assertEquals("active", s.status)
        assertEquals("arriving", s.driverPhase)
    }

    // ---------- Чат ----------

    @Test
    fun getMessages_parsesSenderIdSnakeCaseAndFlags() = runBlocking {
        server.enqueue(
            json(
                """{"items":[
                   {"id":1,"text":"Привет","sender_id":42,"deleted":false,"edited":false},
                   {"id":2,"text":"","sender_id":7,"voice_url":"https://x/v.m4a","deleted":false,"edited":true}
                ]}"""
            )
        )
        val list = ApiClient.getMessages(5).getOrThrow()
        assertEquals(2, list.size)
        assertEquals(1, list[0].id)
        assertEquals("Привет", list[0].text)
        assertEquals(42, list[0].senderId)
        assertNull(list[0].voiceUrl)
        assertEquals("https://x/v.m4a", list[1].voiceUrl)
        assertTrue(list[1].edited)
        assertEquals("/bookings/5/messages", server.takeRequest().path)
    }

    @Test
    fun getConversations_parsesInboxFields() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"booking_id":8,"peer_name":"Айгуль","route":"Уфа → Казань",
                   "last_message":"Еду","peer_avatar":"https://a/1.jpg","depart_at":"2026-07-02T10:00:00"}]}"""
            )
        )
        val list = ApiClient.getConversations().getOrThrow()
        assertEquals(1, list.size)
        assertEquals(8, list[0].bookingId)
        assertEquals("Айгуль", list[0].peerName)
        assertEquals("Уфа → Казань", list[0].route)
        assertEquals("Еду", list[0].lastMessage)
        assertEquals("2026-07-02T10:00:00", list[0].departAt)
    }

    // ---------- Профиль водителя ----------

    @Test
    fun getDriverStatus_mapsCarAndVerification() = runBlocking {
        server.enqueue(
            json(
                """{"docs_status":"approved","verified":true,"car_make":"Kia","car_model":"Rio",
                   "car_color":"белый","car_plate":"А123ВС102","seats":4,
                   "license_url":"https://x/l.jpg","car_photo_url":"https://x/c.jpg",
                   "online":true,"autocheck_result":"pass","autocheck_data":"{}"}"""
            )
        )
        val s = ApiClient.getDriverStatus().getOrThrow()
        assertEquals("approved", s.docsStatus)
        assertTrue(s.verified)
        assertEquals("Kia", s.carMake)
        assertEquals("Rio", s.carModel)
        assertEquals("белый", s.carColor)
        assertEquals("А123ВС102", s.carPlate)
        assertEquals(4, s.seats)
        assertTrue(s.online)
        assertEquals("pass", s.autocheckResult)
        assertEquals("/driver/status", server.takeRequest().path)
    }

    @Test
    fun getDriverStatus_defaultsWhenFieldsMissing() = runBlocking {
        // Пустой объект → docs_status="none" (дефолт), seats=4 (дефолт), verified=false.
        server.enqueue(json("{}"))
        val s = ApiClient.getDriverStatus().getOrThrow()
        assertEquals("none", s.docsStatus)
        assertEquals(4, s.seats)
        assertFalse(s.verified)
        assertFalse(s.online)
    }

    // ---------- Реклама / тарифы (cachedGet — тест первого вызова) ----------

    @Test
    fun getMyAds_parsesArrayCsvFields() = runBlocking {
        // placements/cities приходят массивами → в DTO склеиваются через запятую.
        server.enqueue(
            json(
                """{"items":[{"id":"a1","title":"Кафе","text":"Заходи","button":"Открыть",
                   "target":"https://cafe","erid":"XYZ","status":"active","reject_reason":"",
                   "package":"standard","package_title":"Стандарт","budget_kop":500000,"period_days":30,
                   "placements":["feed","map"],"cities":["Уфа","Сибай"],"paid":true,
                   "submitted_at":"2026-07-01T00:00:00"}]}"""
            )
        )
        val list = ApiClient.getMyAds().getOrThrow()
        assertEquals(1, list.size)
        val a = list[0]
        assertEquals("a1", a.id)
        assertEquals("Кафе", a.title)
        assertEquals("standard", a.pkg)
        assertEquals("Стандарт", a.pkgTitle)
        assertEquals(500000, a.budgetKop)
        assertEquals(30, a.periodDays)
        assertEquals("feed,map", a.placements)
        assertEquals("Уфа,Сибай", a.cities)
        assertTrue(a.paid)
        assertEquals("/ads/mine", server.takeRequest().path)
    }

    @Test
    fun getBoostPlans_parsesTierPriceHours() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"tier":"day","title":"На сутки","price":99,"hours":24},
                   {"tier":"week","title":"На неделю","price":499,"hours":168}]}"""
            )
        )
        val list = ApiClient.getBoostPlans().getOrThrow()
        assertEquals(2, list.size)
        assertEquals("day", list[0].tier)
        assertEquals("На сутки", list[0].title)
        assertEquals(99, list[0].price)
        assertEquals(24, list[0].hours)
        assertEquals(168, list[1].hours)
    }

    // ---------- Ветки ошибок call() ----------

    @Test
    fun getMyBookings_serverError500_returnsFailureWithApiException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        val res = ApiClient.getMyBookings()
        assertTrue(res.isFailure)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(500, (e as ApiException).status)
    }

    @Test
    fun getBookingDetails_error400_usesServerDetailMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Бронь не найдена"}"""))
        val res = ApiClient.getBookingDetails(999)
        assertTrue(res.isFailure)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(400, (e as ApiException).status)
        assertEquals("Бронь не найдена", e.message)
    }
}
