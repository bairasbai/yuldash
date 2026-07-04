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
 * ДОБОР покрытия сетевого слоя `ApiClient` — методы/ветки, которых НЕ было в остальных
 * ApiClient*Test (Network/Rides/Bookings/Auth/Account/Actions/Ads/Cached/CriticalBadPath).
 * Покрываем: createRequest, addContact, getDriverBookings, getDriverRides, getNotifications,
 * getAds, getAdStats, getAdminAds, editMessage, deleteMessage, sendVoiceMessage, sendPhotoMessage,
 * redeemReferral, getPendingPayments, confirmPayment, rejectPayment, getPaymentsSummary,
 * uploadChatPhoto, uploadPhoto (multipart).
 *
 * Проверяем и happy-path (парсинг полей результата, пустой массив), и «плохие» ветки
 * (500 / битый JSON на 200 / 400 с detail). Все «плохие» ответы отдаём С ТЕЛОМ — пустое тело
 * при ошибке = чтение до readTimeout = зависание теста (ловили раньше).
 *
 * Базовый URL подменяется тест-хуком `ApiClient.testBaseUrl` (в проде null → реальный бэкенд).
 * Токен НЕ выставляем: методы с auth=true просто не шлют Authorization (MockWebServer это не важно),
 * а разбор тела/ответа идентичен. `logout()` в setup/teardown синхронно чистит локальную сессию и
 * общий кеш GET-ответов (`respCache`), чтобы состояние не «протекало» между тестами. Analytics.log
 * в onSuccess некоторых методов (createRequest, redeemReferral) — no-op без Firebase (fa == null).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiClientMoreTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
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
    }

    private fun json(body: String) = MockResponse().setResponseCode(200).setBody(body)
    private fun error500() = MockResponse().setResponseCode(500).setBody("""{"detail":"Сбой сервера"}""")
    private fun detail(code: Int, msg: String) =
        MockResponse().setResponseCode(code).setBody("""{"detail":"$msg"}""")
    private fun brokenJson() = MockResponse().setResponseCode(200).setBody("{не json")

    // ======================================================================
    //  createRequest (POST /requests → id)
    // ======================================================================

    @Test
    fun createRequest_postsSnakeCaseBody_andParsesId() = runBlocking {
        server.enqueue(json("""{"id":501}"""))
        val res = ApiClient.createRequest(
            fromCity = "Уфа", toCity = "Сибай", seats = 2, category = "regular",
            withKids = true, comment = "с багажом", maxPrice = 800,
            womenOnly = true, baggage = true, desiredAt = "2026-07-05T08:00:00",
            relativeName = "Бабушка",
        )
        assertTrue(res.isSuccess)
        assertEquals(501, res.getOrThrow())   // id → оптимистичный serverId заявки
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/requests", rec.path)
        val body = rec.body.readUtf8()
        assertTrue(body.contains("\"from_city\":\"Уфа\""))
        assertTrue(body.contains("\"to_city\":\"Сибай\""))
        assertTrue(body.contains("\"seats\":2"))
        assertTrue(body.contains("\"with_kids\":true"))
        assertTrue(body.contains("\"women_only\":true"))
        assertTrue(body.contains("\"baggage\":true"))
        assertTrue(body.contains("\"max_price\":800"))
        // Опциональные поля добавляются только если не пустые.
        assertTrue(body.contains("\"desired_at\":\"2026-07-05T08:00:00\""))
        assertTrue(body.contains("\"for_relative_name\":\"Бабушка\""))
    }

    @Test
    fun createRequest_omitsBlankOptionalFields() = runBlocking {
        // voice_url/transcript/for_relative_name/desired_at пустые → в теле их быть не должно.
        server.enqueue(json("""{"id":1}"""))
        ApiClient.createRequest(
            fromCity = "Уфа", toCity = "Бирск", seats = 1, category = "regular",
            withKids = false, comment = "", maxPrice = 0,
        ).getOrThrow()
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("voice_url"))
        assertFalse(body.contains("transcript"))
        assertFalse(body.contains("for_relative_name"))
        assertFalse(body.contains("desired_at"))
    }

    @Test
    fun createRequest_error400_returnsFailureWithDetail() = runBlocking {
        server.enqueue(detail(400, "Заполни маршрут"))
        val res = ApiClient.createRequest(
            fromCity = "", toCity = "", seats = 1, category = "regular",
            withKids = false, comment = "", maxPrice = 0,
        )
        assertTrue(res.isFailure)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(400, (e as ApiException).status)
        assertEquals("Заполни маршрут", e.message)
    }

    // ======================================================================
    //  addContact (POST /trusted-contacts)
    // ======================================================================

    @Test
    fun addContact_postsContactFields() = runBlocking {
        server.enqueue(json("{}"))
        val res = ApiClient.addContact(
            name = "Айгуль", relation = "сестра", phone = "+79991112233", notifyByDefault = true,
        )
        assertTrue(res.isSuccess)
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/trusted-contacts", rec.path)
        val body = rec.body.readUtf8()
        assertTrue(body.contains("\"name\":\"Айгуль\""))
        assertTrue(body.contains("\"relation\":\"сестра\""))
        assertTrue(body.contains("\"phone\":\"+79991112233\""))
        assertTrue(body.contains("\"notify_by_default\":true"))
    }

    @Test
    fun addContact_serverError500_returnsFailureWithApiException() = runBlocking {
        server.enqueue(error500())
        val res = ApiClient.addContact("Х", "друг", "+70000000000", false)
        assertTrue(res.isFailure)
        assertEquals(500, (res.exceptionOrNull() as ApiException).status)
    }

    // ======================================================================
    //  getDriverBookings (GET /driver/bookings → List<DriverBookingDto>)
    // ======================================================================

    @Test
    fun getDriverBookings_parsesFields_andNullRating() = runBlocking {
        server.enqueue(
            json(
                """{"items":[
                   {"booking_id":10,"passenger_name":"Гуль","passenger_rating":4.5,"route":"Уфа → Сибай","status":"active"},
                   {"booking_id":11,"passenger_name":"Азат","passenger_rating":null,"route":"Уфа → Бирск","status":"done"}
                ]}""",
            ),
        )
        val list = ApiClient.getDriverBookings().getOrThrow()
        assertEquals(2, list.size)
        assertEquals(10, list[0].bookingId)
        assertEquals("Гуль", list[0].passengerName)
        assertEquals(4.5, list[0].passengerRating!!, 0.001)
        assertEquals("Уфа → Сибай", list[0].route)
        assertEquals("active", list[0].status)
        assertNull(list[1].passengerRating)   // isNull → поле DTO null
        assertEquals("/driver/bookings", server.takeRequest().path)
    }

    @Test
    fun getDriverBookings_emptyItems_returnsEmptyList() = runBlocking {
        server.enqueue(json("""{"items":[]}"""))
        assertTrue(ApiClient.getDriverBookings().getOrThrow().isEmpty())
    }

    @Test
    fun getDriverBookings_serverError500_returnsFailure() = runBlocking {
        server.enqueue(error500())
        val res = ApiClient.getDriverBookings()
        assertTrue(res.isFailure)
        assertEquals(500, (res.exceptionOrNull() as ApiException).status)
    }

    // ======================================================================
    //  getDriverRides (GET /driver/rides → List<RideDto>)
    // ======================================================================

    @Test
    fun getDriverRides_parsesRideDto() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"id":90,"from_city":"Уфа","to_city":"Казань","depart_at":"2026-07-05T09:00:00",
                   "seats_total":4,"seats_left":2,"price":600,"driver_name":"Марат","boosted":true}]}""",
            ),
        )
        val list = ApiClient.getDriverRides().getOrThrow()
        assertEquals(1, list.size)
        assertEquals(90, list[0].id)
        assertEquals("Уфа", list[0].fromCity)
        assertEquals(2, list[0].seatsLeft)
        assertEquals(600, list[0].price)
        assertTrue(list[0].boosted)
        assertEquals("/driver/rides", server.takeRequest().path)
    }

    @Test
    fun getDriverRides_emptyItems_returnsEmptyList() = runBlocking {
        server.enqueue(json("""{"items":[]}"""))
        assertTrue(ApiClient.getDriverRides().getOrThrow().isEmpty())
    }

    // ======================================================================
    //  getNotifications (GET /notifications → List<NotifDto>)
    // ======================================================================

    @Test
    fun getNotifications_parsesTitleAndText() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"title":"Новое сообщение","text":"Марат: еду"},
                   {"title":"Бронь","text":"Место подтверждено"}]}""",
            ),
        )
        val list = ApiClient.getNotifications().getOrThrow()
        assertEquals(2, list.size)
        assertEquals("Новое сообщение", list[0].title)
        assertEquals("Марат: еду", list[0].text)
        assertEquals("Бронь", list[1].title)
        assertEquals("/notifications", server.takeRequest().path)
    }

    @Test
    fun getNotifications_emptyItems_returnsEmptyList() = runBlocking {
        server.enqueue(json("""{"items":[]}"""))
        assertTrue(ApiClient.getNotifications().getOrThrow().isEmpty())
    }

    // ======================================================================
    //  getAds (GET /ads → List<AdDto>)
    // ======================================================================

    @Test
    fun getAds_parsesAdFields() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"id":"ad1","title":"Кафе Урал","text":"Скидка 10%","button":"Открыть",
                   "erid":"XYZ","placement":"feed","partner":"Урал","contact":"@ural","target":"https://ural.ru",
                   "image":"https://img/1.jpg","city":"Уфа"}]}""",
            ),
        )
        val list = ApiClient.getAds().getOrThrow()
        assertEquals(1, list.size)
        val a = list[0]
        assertEquals("ad1", a.id)
        assertEquals("Кафе Урал", a.title)
        assertEquals("Скидка 10%", a.text)
        assertEquals("Открыть", a.button)
        assertEquals("XYZ", a.erid)
        assertEquals("feed", a.placement)
        assertEquals("Урал", a.partner)
        assertEquals("Уфа", a.city)
        assertEquals("/ads", server.takeRequest().path)
    }

    @Test
    fun getAds_emptyItems_returnsEmptyList() = runBlocking {
        server.enqueue(json("""{"items":[]}"""))
        assertTrue(ApiClient.getAds().getOrThrow().isEmpty())
    }

    @Test
    fun getAds_brokenJsonOn200_isCaughtIntoFailure() = runBlocking {
        // 200, но тело не JSON → JSONObject(text) в call() кидает, ловится в try/catch → failure, не краш.
        server.enqueue(brokenJson())
        assertTrue(ApiClient.getAds().isFailure)
    }

    // ======================================================================
    //  getAdStats (GET /ads/stats → Map<String, AdStatsDto>)
    // ======================================================================

    @Test
    fun getAdStats_parsesMapOfStats() = runBlocking {
        server.enqueue(
            json(
                """{"ad1":{"impressions":100,"clicks":7},"ad2":{"impressions":50,"clicks":3}}""",
            ),
        )
        val map = ApiClient.getAdStats().getOrThrow()
        assertEquals(2, map.size)
        assertEquals(100, map["ad1"]!!.impressions)
        assertEquals(7, map["ad1"]!!.clicks)
        assertEquals(50, map["ad2"]!!.impressions)
        assertEquals(3, map["ad2"]!!.clicks)
        assertEquals("/ads/stats", server.takeRequest().path)
    }

    @Test
    fun getAdStats_emptyObject_returnsEmptyMap() = runBlocking {
        server.enqueue(json("{}"))
        assertTrue(ApiClient.getAdStats().getOrThrow().isEmpty())
    }

    @Test
    fun getAdStats_serverError500_returnsFailure() = runBlocking {
        server.enqueue(error500())
        val res = ApiClient.getAdStats()
        assertTrue(res.isFailure)
        assertEquals(500, (res.exceptionOrNull() as ApiException).status)
    }

    // ======================================================================
    //  getAdminAds (GET /admin/ads → AdminAdsDto)
    // ======================================================================

    @Test
    fun getAdminAds_parsesFounderCountersAndItems() = runBlocking {
        server.enqueue(
            json(
                """{"founder_used":3,"founder_limit":10,"items":[
                   {"id":"a1","partner":"Кафе","title":"Скидка","text":"10%","plan":"founder","status":"active",
                    "placements":["map","feed"],"erid":"E1","ends_at":"2026-08-01T00:00:00","live":true,"expired":false,
                    "button":"Открыть","target":"https://x.ru","cities":["Уфа","Сибай"],"reject_reason":"","owner_id":42},
                   {"id":"a2","partner":"Мойка","title":"Мойка","text":"t","plan":"standard","status":"draft",
                    "placements":[],"erid":"","live":false,"expired":false,"cities":[],"owner_id":null}
                ]}""",
            ),
        )
        val dto = ApiClient.getAdminAds().getOrThrow()
        assertEquals(3, dto.founderUsed)
        assertEquals(10, dto.founderLimit)
        assertEquals(2, dto.items.size)
        val a1 = dto.items[0]
        assertEquals("a1", a1.id)
        assertEquals("Кафе", a1.partner)
        assertEquals("founder", a1.plan)
        assertEquals("map,feed", a1.placements)   // массив склеен через запятую
        assertEquals("Уфа,Сибай", a1.cities)
        assertTrue(a1.live)
        assertFalse(a1.expired)
        assertEquals(42, a1.ownerId)
        // owner_id: null → ownerId == null; пустые placements/cities → пустые строки.
        assertNull(dto.items[1].ownerId)
        assertEquals("", dto.items[1].placements)
        assertEquals("", dto.items[1].cities)
        assertEquals("/admin/ads", server.takeRequest().path)
    }

    @Test
    fun getAdminAds_missingCounters_useDefaults() = runBlocking {
        // Нет founder_used/limit/items → founderUsed=0, founderLimit=10 (дефолт), items пустой.
        server.enqueue(json("{}"))
        val dto = ApiClient.getAdminAds().getOrThrow()
        assertEquals(0, dto.founderUsed)
        assertEquals(10, dto.founderLimit)
        assertTrue(dto.items.isEmpty())
    }

    // ======================================================================
    //  Чат: editMessage / deleteMessage / sendVoiceMessage / sendPhotoMessage
    // ======================================================================

    @Test
    fun editMessage_postsTextToEditPath() = runBlocking {
        server.enqueue(json("{}"))
        val res = ApiClient.editMessage(bookingId = 5, messageId = 77, text = "исправил")
        assertTrue(res.isSuccess)
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/bookings/5/messages/77/edit", rec.path)
        assertTrue(rec.body.readUtf8().contains("\"text\":\"исправил\""))
    }

    @Test
    fun deleteMessage_sendsDeleteWithScopeQuery() = runBlocking {
        server.enqueue(json("{}"))
        val res = ApiClient.deleteMessage(bookingId = 5, messageId = 77, scope = "all")
        assertTrue(res.isSuccess)
        val rec = server.takeRequest()
        assertEquals("DELETE", rec.method)
        assertEquals("/bookings/5/messages/77?scope=all", rec.path)
    }

    @Test
    fun sendVoiceMessage_postsVoiceUrlToMessages() = runBlocking {
        server.enqueue(json("{}"))
        val res = ApiClient.sendVoiceMessage(bookingId = 8, voiceUrl = "https://cdn/v.m4a")
        assertTrue(res.isSuccess)
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/bookings/8/messages", rec.path)
        val body = rec.body.readUtf8().replace("\\", "")   // org.json может экранировать '/'
        assertTrue(body.contains("\"voice_url\":\"https://cdn/v.m4a\""))
    }

    @Test
    fun sendPhotoMessage_prefixesImgTagInText() = runBlocking {
        // sendPhotoMessage → sendMessage(text = "[img]<url>"): в теле text с префиксом IMG_PREFIX.
        server.enqueue(json("{}"))
        val res = ApiClient.sendPhotoMessage(bookingId = 8, photoUrl = "https://cdn/p.jpg")
        assertTrue(res.isSuccess)
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/bookings/8/messages", rec.path)
        val body = rec.body.readUtf8().replace("\\", "")
        assertTrue(body.contains("\"text\":\"${ApiClient.IMG_PREFIX}https://cdn/p.jpg\""))
    }

    @Test
    fun editMessage_error400_returnsFailure() = runBlocking {
        server.enqueue(detail(400, "Нельзя редактировать чужое"))
        val res = ApiClient.editMessage(5, 77, "x")
        assertTrue(res.isFailure)
        assertEquals(400, (res.exceptionOrNull() as ApiException).status)
        assertEquals("Нельзя редактировать чужое", res.exceptionOrNull()?.message)
    }

    // ======================================================================
    //  redeemReferral (POST /referral/redeem → credits)
    // ======================================================================

    @Test
    fun redeemReferral_postsCode_andParsesCredits() = runBlocking {
        server.enqueue(json("""{"credits":250}"""))
        val res = ApiClient.redeemReferral("AB12")
        assertTrue(res.isSuccess)
        assertEquals(250, res.getOrThrow())   // новый баланс бонусов
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/referral/redeem", rec.path)
        assertTrue(rec.body.readUtf8().contains("\"code\":\"AB12\""))
    }

    @Test
    fun redeemReferral_error400_returnsFailureWithDetail() = runBlocking {
        server.enqueue(detail(400, "Код уже использован"))
        val res = ApiClient.redeemReferral("USED")
        assertTrue(res.isFailure)
        assertEquals(400, (res.exceptionOrNull() as ApiException).status)
        assertEquals("Код уже использован", res.exceptionOrNull()?.message)
    }

    // ======================================================================
    //  Админ: очередь оплат — getPendingPayments / confirm / reject / summary
    // ======================================================================

    @Test
    fun getPendingPayments_parsesFields_andNullRideId() = runBlocking {
        server.enqueue(
            json(
                """{"items":[
                   {"payment_id":100,"purpose":"boost","tier":"gold","amount":499,"ride_id":7,
                    "payer_name":"Марат","payer_phone":"+79990001122","created_at":"2026-07-01T10:00:00","note":"СБП"},
                   {"payment_id":101,"purpose":"donate","tier":"","amount":500,"ride_id":null,
                    "payer_name":"Гуль","payer_phone":"+79995553311","created_at":"2026-07-01T11:00:00"}
                ]}""",
            ),
        )
        val list = ApiClient.getPendingPayments().getOrThrow()
        assertEquals(2, list.size)
        assertEquals(100, list[0].paymentId)
        assertEquals("boost", list[0].purpose)
        assertEquals("gold", list[0].tier)
        assertEquals(499, list[0].amount)
        assertEquals(7, list[0].rideId)
        assertEquals("Марат", list[0].payerName)
        assertEquals("СБП", list[0].note)
        assertNull(list[1].rideId)   // ride_id: null → поле DTO null (донат без поездки)
        assertEquals("donate", list[1].purpose)
        assertEquals("/admin/payments/pending", server.takeRequest().path)
    }

    @Test
    fun getPendingPayments_emptyItems_returnsEmptyList() = runBlocking {
        server.enqueue(json("""{"items":[]}"""))
        assertTrue(ApiClient.getPendingPayments().getOrThrow().isEmpty())
    }

    @Test
    fun confirmPayment_postsToConfirmPath() = runBlocking {
        server.enqueue(json("{}"))
        ApiClient.confirmPayment(paymentId = 100).getOrThrow()
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/admin/payments/100/confirm", rec.path)
    }

    @Test
    fun rejectPayment_postsToRejectPath() = runBlocking {
        server.enqueue(json("{}"))
        ApiClient.rejectPayment(paymentId = 101).getOrThrow()
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/admin/payments/101/reject", rec.path)
    }

    @Test
    fun getPaymentsSummary_parsesNestedDonateAndBoost() = runBlocking {
        server.enqueue(
            json(
                """{"donate":{"count":4,"sum_rub":2000},"boost":{"count":6,"sum_rub":3000}}""",
            ),
        )
        val s = ApiClient.getPaymentsSummary().getOrThrow()
        assertEquals(4, s.donateCount)
        assertEquals(2000, s.donateSum)
        assertEquals(6, s.boostCount)
        assertEquals(3000, s.boostSum)
        assertEquals("/admin/payments/summary", server.takeRequest().path)
    }

    @Test
    fun getPaymentsSummary_missingObjects_defaultsToZero() = runBlocking {
        // Нет donate/boost → optJSONObject == null → JSONObject() → все счётчики 0 (не краш).
        server.enqueue(json("{}"))
        val s = ApiClient.getPaymentsSummary().getOrThrow()
        assertEquals(0, s.donateCount)
        assertEquals(0, s.donateSum)
        assertEquals(0, s.boostCount)
        assertEquals(0, s.boostSum)
    }

    @Test
    fun getPaymentsSummary_serverError500_returnsFailure() = runBlocking {
        server.enqueue(error500())
        val res = ApiClient.getPaymentsSummary()
        assertTrue(res.isFailure)
        assertEquals(500, (res.exceptionOrNull() as ApiException).status)
    }

    // ======================================================================
    //  Multipart: uploadChatPhoto / uploadPhoto (callMultipart → url)
    // ======================================================================

    @Test
    fun uploadChatPhoto_returnsUrl_andSendsMultipartPost() = runBlocking {
        server.enqueue(json("""{"url":"https://cdn.yulbash.ru/chat/p.jpg"}"""))
        val res = ApiClient.uploadChatPhoto(byteArrayOf(1, 2, 3, 4), ext = "jpg")
        assertTrue(res.isSuccess)
        assertEquals("https://cdn.yulbash.ru/chat/p.jpg", res.getOrThrow())
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/upload/chat-photo", rec.path)
        assertTrue(rec.getHeader("Content-Type").orEmpty().contains("multipart/form-data"))
    }

    @Test
    fun uploadPhoto_returnsUrl_andHitsUploadPhotoPath() = runBlocking {
        server.enqueue(json("""{"url":"https://cdn.yulbash.ru/photo/doc.jpg"}"""))
        val res = ApiClient.uploadPhoto(byteArrayOf(9, 8, 7), ext = "jpg")
        assertTrue(res.isSuccess)
        assertEquals("https://cdn.yulbash.ru/photo/doc.jpg", res.getOrThrow())
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/upload/photo", rec.path)
    }

    @Test
    fun uploadChatPhoto_serverError500_returnsFailure() = runBlocking {
        server.enqueue(error500())
        val res = ApiClient.uploadChatPhoto(byteArrayOf(1), ext = "jpg")
        assertTrue(res.isFailure)
        assertEquals(500, (res.exceptionOrNull() as ApiException).status)
    }
}
