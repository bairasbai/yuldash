package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
 * Сетевой слой `ApiClient`: группа ТАКСИ (instant) и КУРЬЕР/ПОСЫЛКИ — на локальном MockWebServer.
 *
 * Зачем именно эти методы. Такси и курьер — самый нагруженный контракт клиента (деньги, статусы,
 * приватность), и до этого он не был покрыт НИ ОДНИМ тестом: соседние файлы `ApiClient*Test`
 * трогают auth/bookings/rides/ads/account и ни разу не заходят в instant/taxi/courier/parcel.
 * Ошибка в разборе одного поля здесь — это неверная сумма на экране или потерянный статус заказа.
 *
 * Что проверяем: путь и метод запроса, разбор snake_case-полей в DTO, обёртку голого массива
 * в `{items:[...]}`, значения по умолчанию (клиент новее сервера не должен падать) и ветки ошибок.
 * Базовый URL подменяется тест-хуком `ApiClient.testBaseUrl` (в проде он null).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiClientTaxiCourierTest {

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

    // ---------------------------- Такси: оценка цены ----------------------------

    @Test
    fun instantEstimate_parsesPriceDistanceAndSurge() = runBlocking {
        server.enqueue(
            json(
                """{"price":320,"distance_km":12.4,"eta_min":18.0,"pickup_eta_min":4,
                   "zone":"Баймак","category":"standard","tariff_id":2,"surge_k":1.25,
                   "surge_note":{"ru":"Много заказов","ba":"Заказ күп"},"base_price":260}"""
            )
        )
        val est = ApiClient.instantEstimate(52.5, 58.3, 52.6, 58.4).getOrThrow()
        assertEquals(320, est.price)
        assertEquals(12.4, est.distanceKm, 0.001)
        assertEquals(4, est.pickupEtaMin)
        assertEquals(1.25, est.surgeK, 0.001)
        assertEquals("Много заказов", est.surgeNoteRu)
        assertEquals("Заказ күп", est.surgeNoteBa)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/instant/estimate", recorded.path)
    }

    /**
     * «Не знаю время подачи» сервер отдаёт как null — и это должно доехать до экрана именно как
     * «неизвестно», а не как бодрое «0 минут». Ноль здесь читался бы как «машина уже у подъезда».
     */
    @Test
    fun instantEstimate_nullPickupEta_staysNull() = runBlocking {
        server.enqueue(json("""{"price":200,"distance_km":5.0,"eta_min":9.0,"pickup_eta_min":null}"""))
        val est = ApiClient.instantEstimate(52.5, 58.3, 52.6, 58.4).getOrThrow()
        assertNull(est.pickupEtaMin)
    }

    // ---------------------------- Такси: заказ ----------------------------

    @Test
    fun createInstantOrder_sendsCommentAndEntrance() = runBlocking {
        server.enqueue(json("""{"id":7,"status":"searching","role":"passenger"}"""))
        ApiClient.createInstantOrder(
            52.5, 58.3, 52.6, 58.4,
            fromText = "Ленина 12", toText = "Вокзал",
            comment = "за магазином, синие ворота", entrance = "2 подъезд",
        ).getOrThrow()
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/instant/orders", recorded.path)
        val body = recorded.body.readUtf8()
        // В селе адрес «Ленина 12» без ориентира бесполезен — комментарий обязан доехать до водителя.
        assertTrue(body.contains("за магазином, синие ворота"))
        assertTrue(body.contains("2 подъезд"))
    }

    /** Пустые необязательные поля в тело не кладём — лишний шум в запросе ни к чему. */
    @Test
    fun createInstantOrder_skipsEmptyOptionalFields() = runBlocking {
        server.enqueue(json("""{"id":8,"status":"searching","role":"passenger"}"""))
        ApiClient.createInstantOrder(52.5, 58.3, 52.6, 58.4).getOrThrow()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(!body.contains("comment"))
        assertTrue(!body.contains("entrance"))
        assertTrue(!body.contains("for_phone"))
    }

    /**
     * Госномер, деньги и предупреждения ожидания — то, из-за чего пассажир садится не в ту машину
     * или спорит о сумме. Проверяем, что они доезжают из ответа сервера без потерь.
     */
    @Test
    fun getMyInstantOrders_parsesPlateMoneyAndWaiting() = runBlocking {
        server.enqueue(
            json(
                """[{"id":15,"status":"onboard","role":"passenger","price_estimate":300,"price_final":340,
                   "driver_plate":"Х123УХ102","driver_name":"Ильдар","driver_car":"Лада Гранта",
                   "waiting_fee_kop":4500,"cancel_fee_kop":0,"no_show":false,
                   "wait_free_min":3,"wait_fee_rub_per_min":5,"surge_k":1.0,
                   "created_at":"2026-08-01T09:15:00"}]"""
            )
        )
        val list = ApiClient.getMyInstantOrders(limit = 5).getOrThrow()
        assertEquals(1, list.size)
        val o = list[0]
        assertEquals(15, o.id)
        assertEquals("onboard", o.status)
        assertEquals("Х123УХ102", o.driverPlate)
        assertEquals(340, o.priceFinal)
        assertEquals(4500, o.waitingFeeKop)
        assertEquals(3, o.waitFreeMin)
        assertEquals("2026-08-01T09:15:00", o.createdAt)
        assertEquals("/instant/orders/mine?limit=5", server.takeRequest().path)
    }

    /**
     * Старый сервер новых полей не присылает. Клиент обязан это пережить и показать пустоту,
     * а не упасть: иначе обновлённое приложение ломается о неподнятый бэкенд.
     */
    @Test
    fun getMyInstantOrders_oldServerWithoutNewFields_usesDefaults() = runBlocking {
        server.enqueue(json("""[{"id":3,"status":"done","role":"passenger","price_estimate":100}]"""))
        val o = ApiClient.getMyInstantOrders().getOrThrow().first()
        assertEquals("", o.driverPlate)
        assertEquals("", o.comment)
        assertEquals(false, o.forOther)
        assertNull(o.createdAt)
    }

    @Test
    fun instantCancel_sendsReason() = runBlocking {
        server.enqueue(json("""{"id":21,"status":"cancelled","role":"passenger"}"""))
        val o = ApiClient.instantCancel(21, reason = "долго ждать").getOrThrow()
        assertEquals("cancelled", o.status)
        val recorded = server.takeRequest()
        assertEquals("/instant/orders/21/cancel", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("долго ждать"))
    }

    /** Сервер ответил ошибкой — она обязана доехать статусом, а не превратиться в «пустой список». */
    @Test
    fun getMyInstantOrders_serverError_returnsApiException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}"""))
        val res = ApiClient.getMyInstantOrders()
        assertTrue(res.isFailure)
        assertEquals(500, (res.exceptionOrNull() as ApiException).status)
    }

    // ---------------------------- Такси: доступность ----------------------------

    @Test
    fun getTaxiAvailability_parsesBilingualMessage() = runBlocking {
        server.enqueue(
            json(
                """{"enabled":false,"reason":"city_off","city":"Сибай",
                   "message":{"ru":"Такси здесь скоро","ba":"Бында такси тиҙҙән"}}"""
            )
        )
        val av = ApiClient.getTaxiAvailability(52.5, 58.3).getOrThrow()
        assertEquals(false, av.enabled)
        assertEquals("city_off", av.reason)
        assertEquals("Сибай", av.city)
        assertEquals("Такси здесь скоро", av.messageRu)
        assertEquals("Бында такси тиҙҙән", av.messageBa)
    }

    // ---------------------------- Админ: журнал предрейсовых ----------------------------

    /**
     * Журнал предрейсовых подтверждений (580-ФЗ) — юридический след: при разборе ДТП видно,
     * кто и когда заявил о готовности. Сервер вёл его давно, клиент не запрашивал вообще.
     */
    @Test
    fun adminPretripJournal_parsesDayAndEntries() = runBlocking {
        server.enqueue(
            json(
                """{"day":"2026-08-01","items":[
                   {"driver_id":4,"name":"Ильдар","phone":"+79270000000",
                    "confirmed_at":"2026-08-01T06:12:00","note":"выспался"}]}"""
            )
        )
        val j = ApiClient.adminPretripJournal("2026-08-01").getOrThrow()
        assertEquals("2026-08-01", j.day)
        assertEquals(1, j.items.size)
        assertEquals(4, j.items[0].driverId)
        assertEquals("выспался", j.items[0].note)
        assertEquals("/admin/taxi/pretrip?day=2026-08-01", server.takeRequest().path)
    }

    /** Без даты день выбирает сервер — параметр не шлём вовсе. */
    @Test
    fun adminPretripJournal_withoutDay_omitsQuery() = runBlocking {
        server.enqueue(json("""{"day":"2026-08-03","items":[]}"""))
        ApiClient.adminPretripJournal().getOrThrow()
        assertEquals("/admin/taxi/pretrip", server.takeRequest().path)
    }

    // ---------------------------- Курьер и посылки ----------------------------

    /**
     * Два фида курьера намеренно РАЗНЫЕ: «по пути» (`/parcels/available`) и профессиональные
     * заказы (`/courier/available`). Их однажды приняли за дубль и чуть не удалили один —
     * тест фиксирует, что это разные адреса и оба живые.
     */
    @Test
    fun availableParcels_andCourierAvailable_useDifferentEndpoints() = runBlocking {
        server.enqueue(json("""{"items":[{"id":1,"from_city":"Баймак","to_city":"Сибай","status":"new"}]}"""))
        ApiClient.getAvailableParcels().getOrThrow()
        assertTrue(server.takeRequest().path!!.startsWith("/parcels/available"))

        server.enqueue(json("""{"items":[{"id":2,"from_city":"Сибай","to_city":"Уфа","status":"new"}]}"""))
        ApiClient.getCourierAvailable().getOrThrow()
        assertTrue(server.takeRequest().path!!.startsWith("/courier/available"))
    }

    @Test
    fun getAvailableParcels_passesCityFilters() = runBlocking {
        server.enqueue(json("""{"items":[]}"""))
        ApiClient.getAvailableParcels(fromCity = "Баймак", toCity = "Сибай").getOrThrow()
        val path = server.takeRequest().path.orEmpty()
        assertTrue(path.contains("from_city="))
        assertTrue(path.contains("to_city="))
    }

    /** Деньги и тип доставки: по ним курьер решает, брать заказ или нет. */
    @Test
    fun getCourierAvailable_parsesMoneyAndDeliveryType() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"id":9,"sender_id":3,"from_city":"Баймак","to_city":"Сибай",
                   "size":"m","description":"коробка","receiver_name":"Айгуль","receiver_phone":"",
                   "fee_kop":0,"status":"new","confirm_code":"","created_at":"2026-08-02T10:00:00",
                   "delivery_type":"buy_bring","urgency":"now","price_kop":45000,
                   "commission_kop":3600,"cod_amount_kop":120000,"declared_value_kop":50000}]}"""
            )
        )
        val p = ApiClient.getCourierAvailable().getOrThrow().first()
        assertEquals("buy_bring", p.deliveryType)
        assertEquals("now", p.urgency)
        assertEquals(45000, p.priceKop)
        assertEquals(3600, p.commissionKop)
        assertEquals(120000, p.codAmountKop)
        assertEquals(50000, p.declaredValueKop)
    }

    /**
     * Телефон получателя сервер отдаёт ТОЛЬКО тому, кому положено. Пустая строка — это «скрыт»,
     * и она обязана оставаться пустой: подставлять что-то своё нельзя (приватность, §8).
     */
    @Test
    fun parcel_hiddenPhoneStaysEmpty() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"id":10,"sender_id":3,"from_city":"Баймак","to_city":"Сибай","size":"s",
                   "description":"документы","receiver_name":"Айгуль","receiver_phone":"",
                   "fee_kop":0,"status":"new","confirm_code":"","created_at":"2026-08-02T10:00:00"}]}"""
            )
        )
        val p = ApiClient.getAvailableParcels().getOrThrow().first()
        assertEquals("", p.receiverPhone)
        assertEquals("", p.confirmCode)
        assertEquals("poputka", p.deliveryType)   // старый сервер без поля → попутка, а не пусто
    }

    /** Возврат посылки: причина и время должны доехать, иначе статус «возвращена» нечем объяснить. */
    @Test
    fun getCarryingParcels_parsesReturnFields() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"id":12,"sender_id":3,"from_city":"Баймак","to_city":"Сибай","size":"m",
                   "description":"мёд","receiver_name":"Айгуль","receiver_phone":"+79270000001",
                   "fee_kop":0,"status":"returned","confirm_code":"4455",
                   "created_at":"2026-08-01T10:00:00","return_reason":"получателя нет дома",
                   "returned_at":"2026-08-02T18:00:00"}]}"""
            )
        )
        val p = ApiClient.getCarryingParcels().getOrThrow().first()
        assertEquals("returned", p.status)
        assertEquals("получателя нет дома", p.returnReason)
        assertEquals("2026-08-02T18:00:00", p.returnedAt)
    }

    @Test
    fun getCourierAvailable_serverError_returnsApiException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"not approved"}"""))
        val res = ApiClient.getCourierAvailable()
        assertTrue(res.isFailure)
        assertEquals(403, (res.exceptionOrNull() as ApiException).status)
    }

    // ------------------ Посылка: где забрать и куда привезти ------------------
    // До этого в посылке был только город: курьер брал заказ и ехал «в Баймак» — ни дома,
    // ни калитки. Ориентир («у мечети, синие ворота») обязан доехать до сервера и обратно.

    @Test
    fun createParcel_sendsPickupAndDropoffAddresses() = runBlocking {
        server.enqueue(json("""{"id":31,"from_city":"Баймак","to_city":"Сибай","status":"new"}"""))
        ApiClient.createParcel(
            fromCity = "Баймак", toCity = "Сибай", size = "s", description = "мёд",
            receiverName = "Айгуль", receiverPhone = "+79270000001", rulesAccepted = true,
            fromAddress = "у мечети, синие ворота", toAddress = "за школой, белый дом",
        ).getOrThrow()
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/parcels", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("from_address"))
        assertTrue(body.contains("у мечети, синие ворота"))
        assertTrue(body.contains("to_address"))
        assertTrue(body.contains("за школой, белый дом"))
    }

    /** Ориентир необязателен: не указали — ключей в теле нет вовсе (как comment/entrance у такси). */
    @Test
    fun createParcel_skipsEmptyAddresses() = runBlocking {
        server.enqueue(json("""{"id":32,"from_city":"Баймак","to_city":"Сибай","status":"new"}"""))
        ApiClient.createParcel(
            fromCity = "Баймак", toCity = "Сибай", size = "s", description = "",
            receiverName = "Айгуль", receiverPhone = "+79270000001", rulesAccepted = true,
        ).getOrThrow()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(!body.contains("from_address"))
        assertTrue(!body.contains("to_address"))
    }

    /** Обратно адреса приходит те же ключи — иначе курьеру нечего показать на карточке. */
    @Test
    fun getMyParcels_parsesAddresses() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"id":33,"sender_id":3,"from_city":"Баймак","to_city":"Сибай","size":"m",
                   "description":"мёд","receiver_name":"Айгуль","receiver_phone":"+79270000001",
                   "fee_kop":0,"status":"accepted","confirm_code":"1234",
                   "created_at":"2026-08-02T10:00:00",
                   "from_address":"у мечети, синие ворота","to_address":"за школой, белый дом"}]}"""
            )
        )
        val p = ApiClient.getMyParcels().getOrThrow().first()
        assertEquals("у мечети, синие ворота", p.fromAddress)
        assertEquals("за школой, белый дом", p.toAddress)
    }

    /**
     * Старый сервер этих ключей не пришлёт — клиент обязан отдать пустые строки и не упасть.
     * Пустая строка потом означает «блок не рисуем», а не «прочерк вместо адреса».
     */
    @Test
    fun getMyParcels_oldServerWithoutAddresses_returnsEmptyStrings() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"id":34,"sender_id":3,"from_city":"Баймак","to_city":"Сибай","size":"m",
                   "description":"мёд","receiver_name":"Айгуль","receiver_phone":"+79270000001",
                   "fee_kop":0,"status":"new","confirm_code":"1234",
                   "created_at":"2026-08-02T10:00:00"}]}"""
            )
        )
        val p = ApiClient.getMyParcels().getOrThrow().first()
        assertEquals("", p.fromAddress)
        assertEquals("", p.toAddress)
    }

    // ---------------------------- Фото на границах ответственности ----------------------------

    /**
     * «Взял целой» уходит именно на переходе в путь: заказ берут заранее, а рядом с посылкой
     * курьер оказывается позже — снимок в момент принятия заявки физически невозможен.
     */
    @Test
    fun setParcelStatus_sendsPickupPhotoOnTransit() = runBlocking {
        server.enqueue(json("""{"id":31,"status":"in_transit","from_city":"Баймак","to_city":"Сибай",
            "size":"m","description":"мёд","receiver_name":"Айгуль","receiver_phone":"",
            "fee_kop":0,"confirm_code":"","created_at":"2026-08-03T10:00:00","sender_id":3,
            "pickup_photo_url":"/media/p31.jpg"}""")) 
        val p = ApiClient.setParcelStatus(31, "in_transit", pickupPhotoUrl = "/media/p31.jpg").getOrThrow()
        assertEquals("/media/p31.jpg", p.pickupPhotoUrl)
        val recorded = server.takeRequest()
        assertEquals("/parcels/31/status", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("pickup_photo_url"))
    }

    /** Снимок необязателен: без него тело запроса не должно тащить пустой ключ. */
    @Test
    fun setParcelStatus_withoutPhoto_omitsKey() = runBlocking {
        server.enqueue(json("""{"id":32,"status":"in_transit","from_city":"Баймак","to_city":"Сибай",
            "size":"s","description":"","receiver_name":"Айгуль","receiver_phone":"",
            "fee_kop":0,"confirm_code":"","created_at":"2026-08-03T10:00:00","sender_id":3}""")) 
        ApiClient.setParcelStatus(32, "in_transit").getOrThrow()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(!body.contains("pickup_photo_url"))
        assertTrue(!body.contains("delivery_photo_url"))
    }

    /** Оба снимка разбираются из ответа — без них показывать в карточке было бы нечего. */
    @Test
    fun parcel_parsesBothPhotos() = runBlocking {
        server.enqueue(json("""{"items":[{"id":33,"sender_id":3,"from_city":"Баймак","to_city":"Сибай",
            "size":"m","description":"банка мёда","receiver_name":"Айгуль","receiver_phone":"+79270000001",
            "fee_kop":0,"status":"delivered","confirm_code":"1234","created_at":"2026-08-01T10:00:00",
            "pickup_photo_url":"/media/take.jpg","delivery_photo_url":"/media/give.jpg"}]}"""))
        val p = ApiClient.getCarryingParcels().getOrThrow().first()
        assertEquals("/media/take.jpg", p.pickupPhotoUrl)
        assertEquals("/media/give.jpg", p.deliveryPhotoUrl)
    }

    /** Старый сервер снимков не шлёт — клиент получает пустые строки, а не падает. */
    @Test
    fun parcel_oldServerWithoutPhotos_usesEmpty() = runBlocking {
        server.enqueue(json("""{"items":[{"id":34,"sender_id":3,"from_city":"Баймак","to_city":"Сибай",
            "size":"s","description":"","receiver_name":"Айгуль","receiver_phone":"",
            "fee_kop":0,"status":"new","confirm_code":"","created_at":"2026-08-01T10:00:00"}]}"""))
        val p = ApiClient.getAvailableParcels().getOrThrow().first()
        assertEquals("", p.pickupPhotoUrl)
        assertEquals("", p.deliveryPhotoUrl)
    }
}
