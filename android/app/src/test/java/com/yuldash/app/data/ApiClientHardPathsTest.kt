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
import java.util.concurrent.TimeUnit

/**
 * Тяжёлые пути сетевого слоя, которые не выражаются табличкой ручек.
 *
 * Здесь три темы, и каждая — про то, что человек видит, когда что-то идёт не по плану:
 *
 * 1. **Протухшая сессия.** Токен доступа живёт недолго. Когда он кончается, приложение должно
 *    молча обменять его на новый и повторить запрос — человек не должен ничего заметить.
 *    А если обменять не вышло (второй токен тоже мёртв), приложение обязано честно сказать
 *    «войди снова», а не показывать пустые экраны до перезапуска.
 *
 * 2. **Отправка файлов.** Фото машины, голосовое сообщение, доказательство в споре. Файл идёт
 *    другим способом, чем обычные запросы, и у него свой путь обновления токена.
 *
 * 3. **Ручки со сложной подписью** — те, где часть полей может отсутствовать. Их не покрыла
 *    автоматическая табличка: она умеет подставлять только простые значения.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiClientHardPathsTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.sessionExpired.value = false
    }

    @After
    fun teardown() {
        ApiClient.logout()
        ApiClient.sessionExpired.value = false
        ApiClient.testBaseUrl = null
        runCatching { server.shutdown() }
    }

    private fun ok(body: String = "{}") = MockResponse().setResponseCode(200).setBody(body)

    /** Обычный вход: после него в приложении есть и токен доступа, и токен обновления. */
    private fun login() = runBlocking {
        server.enqueue(ok("""{"access_token":"jwt_старый","refresh_token":"ref_живой","name":"Айгуль"}"""))
        ApiClient.verifyCode(phone = "+79990000001", code = "1234", name = "Айгуль")
        server.takeRequest()
        Unit
    }

    // ---------- Протухшая сессия ----------

    @Test
    fun `протухший токен меняется на новый молча, человек ничего не замечает`() = runBlocking {
        login()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"token expired"}"""))
        server.enqueue(ok("""{"access_token":"jwt_новый","refresh_token":"ref_новый"}"""))
        server.enqueue(ok("""{"id":1,"name":"Айгуль"}"""))

        val res = ApiClient.getConsents()
        assertTrue("запрос обязан выполниться со второй попытки, без участия человека", res.isSuccess)

        val first = server.takeRequest()
        assertEquals("Bearer jwt_старый", first.getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/auth/refresh", refresh.path)
        val retry = server.takeRequest()
        assertEquals("повтор должен идти уже с новым токеном", "Bearer jwt_новый", retry.getHeader("Authorization"))
        assertFalse("сессия жива — пугать «войди снова» нельзя", ApiClient.sessionExpired.value)
    }

    @Test
    fun `мёртвый токен обновления выкидывает на вход, а не в пустые экраны`() = runBlocking {
        login()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"refresh dead"}"""))

        val res = ApiClient.getConsents()
        assertTrue(res.isFailure)
        assertTrue(
            "без этого сигнала человек залипает с пустыми экранами до перезапуска приложения",
            ApiClient.sessionExpired.value,
        )
        assertFalse("локальная сессия обязана очиститься, иначе приложение врёт «ты внутри»", ApiClient.isLoggedIn())
    }

    @Test
    fun `без токена обновления повтор не пробуется вовсе`() = runBlocking {
        // Не вошли: обновлять нечего, лишний запрос на сервер — пустая трата.
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"no auth"}"""))
        val res = ApiClient.getConsents()
        assertTrue(res.isFailure)
        server.takeRequest()
        assertNull("второго запроса быть не должно", server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `после обмена токена следующие запросы идут без повторного обмена`() = runBlocking {
        login()
        // Первый запрос ловит 401 и меняет токен. Дальше приложение обязано просто ходить
        // с новым токеном: лишние обмены сервер считает повторным использованием и рвёт сессию.
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}"""))
        server.enqueue(ok("""{"access_token":"jwt_новый","refresh_token":"ref_новый"}"""))
        repeat(3) { server.enqueue(ok("""{"items":[]}""")) }

        val results: List<Result<*>> = listOf(
            ApiClient.getConsents(), ApiClient.getConsents(), ApiClient.getConsents(),
        )
        assertTrue("все три запроса должны довыполниться", results.all { it.isSuccess })

        val paths = buildList { repeat(6) { server.takeRequest(3, TimeUnit.SECONDS)?.path?.let(::add) } }
        assertEquals(
            "обмен токена ушёл больше одного раза: $paths",
            1, paths.count { it == "/auth/refresh" },
        )
        assertFalse("сессия жива", ApiClient.sessionExpired.value)
    }

    // ---------- Отправка файлов ----------

    @Test
    fun `фото уходит на сервер как файл и возвращает ссылку`() = runBlocking {
        login()
        server.enqueue(ok("""{"url":"https://example.org/фото.jpg"}"""))
        val url = ApiClient.uploadPhoto(ByteArray(64) { 1 }, "jpg").getOrThrow()
        assertEquals("https://example.org/фото.jpg", url)

        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/upload/photo", rec.path)
        assertTrue(
            "файл должен уходить как файл, а не как текст — иначе он занимает вдвое больше памяти",
            rec.getHeader("Content-Type")!!.startsWith("multipart/form-data"),
        )
    }

    @Test
    fun `голосовое и доказательство в споре идут своими адресами`() = runBlocking {
        login()
        server.enqueue(ok("""{"url":"https://example.org/голос.m4a"}"""))
        ApiClient.uploadVoice(ByteArray(16))
        assertEquals("/voice", server.takeRequest().path)

        server.enqueue(ok("""{"url":"https://example.org/док.jpg"}"""))
        ApiClient.uploadEvidence(ByteArray(16), "jpg")
        assertEquals("/upload/evidence", server.takeRequest().path)
    }

    @Test
    fun `протухший токен при отправке файла тоже обновляется`() = runBlocking {
        login()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}"""))
        server.enqueue(ok("""{"access_token":"jwt_новый","refresh_token":"ref_новый"}"""))
        server.enqueue(ok("""{"url":"https://example.org/ф.jpg"}"""))

        val res = ApiClient.uploadPhoto(ByteArray(8), "jpg")
        assertTrue("фото не должно теряться из-за протухшего токена", res.isSuccess)
    }

    @Test
    fun `сервер отверг файл — ошибка честная, а не пустая ссылка`() = runBlocking {
        login()
        server.enqueue(MockResponse().setResponseCode(413).setBody("""{"detail":"слишком большой"}"""))
        assertTrue(ApiClient.uploadPhoto(ByteArray(8), "jpg").isFailure)
    }

    // ---------- Ручки, где часть полей может отсутствовать ----------

    @Test
    fun `договорённость об оплате отправляет и пустые поля тоже`() = runBlocking {
        login()
        server.enqueue(ok())
        ApiClient.setPayAgreement(bookingId = 42)
        val rec = server.takeRequest()
        assertEquals("/bookings/42/pay-agreement", rec.path)
        val body = rec.body.readUtf8()
        // Сервер должен понять «способ не выбран», а не «поле не пришло» — это разные вещи.
        assertTrue("пустой способ оплаты должен уходить явно: $body", body.contains("pay_method"))
    }

    @Test
    fun `правка заявки шлёт только изменённые поля`() = runBlocking {
        login()
        server.enqueue(ok())
        ApiClient.editRequest(requestId = 7, maxPrice = 800)
        val rec = server.takeRequest()
        assertEquals("/requests/7/edit", rec.path)
        assertTrue(rec.body.readUtf8().contains("800"))
    }

    @Test
    fun `подписка на маршрут заводится и снимается`() = runBlocking {
        login()
        server.enqueue(ok("""{"id":3}"""))
        ApiClient.createRouteWatch(fromCity = "Сибай", toCity = "Уфа")
        val rec = server.takeRequest()
        assertEquals("/route-watch", rec.path)
        val body = rec.body.readUtf8()
        assertTrue(body.contains("Сибай") && body.contains("Уфа"))

        server.enqueue(ok())
        ApiClient.deleteRouteWatch(3)
        assertEquals("/route-watch/3", server.takeRequest().path)
    }

    @Test
    fun `заведение создаётся и правится`() = runBlocking {
        login()
        server.enqueue(ok("""{"id":5,"name":"Кафе","city":"Сибай"}"""))
        ApiClient.createPartner(
            name = "Кафе", category = "food", city = "Сибай", address = "Ленина 1",
            phone = "+79990000001", description = "вкусно",
        )
        assertEquals("/partner", server.takeRequest().path)

        server.enqueue(ok("""{"id":5,"name":"Кафе 2","city":"Сибай"}"""))
        ApiClient.updatePartner(
            id = 5, name = "Кафе 2", category = "food", city = "Сибай", address = "Ленина 1",
            phone = "+79990000001", description = "ещё вкуснее",
        )
        assertEquals("/partner/5", server.takeRequest().path)
    }

    @Test
    fun `скидка заводится и правится`() = runBlocking {
        login()
        server.enqueue(ok("""{"id":9,"title":"Кофе -20%"}"""))
        ApiClient.createPartnerCoupon(
            title = "Кофе -20%", description = "по будням", discountText = "-20%", city = "Сибай",
            routeHint = listOf("Сибай", "Уфа"), limitTotal = 100, limitPerUser = 1, premium = false,
        )
        val rec = server.takeRequest()
        assertEquals("/partner/coupons", rec.path)
        assertTrue("маршрут скидки должен уходить списком", rec.body.readUtf8().contains("Сибай"))

        server.enqueue(ok("""{"id":9,"title":"Кофе -30%"}"""))
        ApiClient.updatePartnerCoupon(
            id = 9, title = "Кофе -30%", description = "по будням", discountText = "-30%",
            city = "Сибай", routeHint = emptyList(), limitTotal = 50, limitPerUser = 1, premium = true,
        )
        assertEquals("/partner/coupons/9", server.takeRequest().path)
    }

    // ---------- Запуск в фоне ----------

    @Test
    fun `действия «в фоне» не роняют приложение и доходят до сервера`() = runBlocking {
        login()
        repeat(4) { server.enqueue(ok()) }
        // Эти вызовы ничего не возвращают: экран не ждёт ответа, запрос уходит сам.
        // Единственное требование — не упасть и не потеряться.
        ApiClient.fireUpdateName("Айгуль")
        ApiClient.fireRequestCallback("перезвоните")
        ApiClient.fireAddRecentPlace("ул. Ленина, 1", 52.5, 58.3)
        ApiClient.fireAddRecentPlace("", 0.0, 0.0)   // пустой адрес — в «Недавние» не кладём

        val paths = buildList { repeat(3) { server.takeRequest(3, TimeUnit.SECONDS)?.path?.let(::add) } }
        assertTrue("ни один фоновый запрос не ушёл: $paths", paths.isNotEmpty())
    }
}
