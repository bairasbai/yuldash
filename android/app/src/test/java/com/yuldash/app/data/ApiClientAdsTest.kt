package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
 * MockWebServer-тесты для POST/action методов РЕКЛАМЫ / МОДЕРАЦИИ / ЖАЛОБ / ОТЗЫВОВ в `ApiClient`.
 * Проверяем: правильный HTTP-метод + путь + тело запроса и разбор ответа (id / amount_kop / DTO).
 * Не-кешируемые вызовы (без `cachedGet`), поэтому каждый идёт в сеть → `takeRequest()` их видит.
 * Базовый URL подменяется тест-хуком `ApiClient.testBaseUrl` (в проде null → реальный бэкенд).
 *
 * Токен не задаём: `auth = true` шлёт заголовок только если токен есть (в проде он есть),
 * а для проверки метода/пути/тела/ответа наличие Authorization роли не играет.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiClientAdsTest {

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

    // ---------- Реклама: админ-управление ----------

    @Test
    fun createAd_postsAllFieldsToAdminAds() = runBlocking {
        server.enqueue(json("{}"))
        val res = ApiClient.createAd(
            partnerName = "Кафе Урал", title = "Скидка", text = "10% по промо", button = "Открыть",
            plan = "founder", placements = "map,feed", erid = "ABC123", target = "https://ural.ru",
            city = "Уфа", price = 5000,
        )
        assertTrue(res.isSuccess)
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/admin/ads", rec.path)
        val body = rec.body.readUtf8()
        assertTrue(body.contains("\"partner_name\":\"Кафе Урал\""))
        assertTrue(body.contains("\"title\":\"Скидка\""))
        assertTrue(body.contains("\"plan\":\"founder\""))
        assertTrue(body.contains("\"placements\":\"map,feed\""))
        assertTrue(body.contains("\"erid\":\"ABC123\""))
        assertTrue(body.contains("\"cities\":\"Уфа\""))
        assertTrue(body.contains("\"price\":5000"))
    }

    @Test
    fun updateAd_postsToIdPath_withoutPrice() = runBlocking {
        server.enqueue(json("{}"))
        val res = ApiClient.updateAd(
            id = "42", partnerName = "Партнёр", title = "Новый", text = "текст", button = "Жми",
            plan = "standard", placements = "map", erid = "", target = "", city = "Казань",
        )
        assertTrue(res.isSuccess)
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/admin/ads/42", rec.path)
        val body = rec.body.readUtf8()
        assertTrue(body.contains("\"partner_name\":\"Партнёр\""))
        assertTrue(body.contains("\"plan\":\"standard\""))
        assertTrue(body.contains("\"cities\":\"Казань\""))
        // Правка контента не пере-выставляет оплату → поля price в теле быть не должно.
        assertFalse(body.contains("\"price\""))
    }

    @Test
    fun setAdStatus_postsStatusToStatusPath() = runBlocking {
        server.enqueue(json("{}"))
        ApiClient.setAdStatus(id = "7", status = "paused").getOrThrow()
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/admin/ads/7/status", rec.path)
        assertTrue(rec.body.readUtf8().contains("\"status\":\"paused\""))
    }

    @Test
    fun approveAd_sendsEridToApprovePath() = runBlocking {
        server.enqueue(json("{}"))
        ApiClient.approveAd(id = "9", erid = "ORD-555").getOrThrow()
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/admin/ads/9/approve", rec.path)
        assertTrue(rec.body.readUtf8().contains("\"erid\":\"ORD-555\""))
    }

    @Test
    fun rejectAd_sendsReasonToRejectPath() = runBlocking {
        server.enqueue(json("{}"))
        ApiClient.rejectAd(id = "9", reason = "Нет erid").getOrThrow()
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/admin/ads/9/reject", rec.path)
        assertTrue(rec.body.readUtf8().contains("\"reason\":\"Нет erid\""))
    }

    @Test
    fun deleteAd_sendsDeleteToIdPath() = runBlocking {
        server.enqueue(json("{}"))
        ApiClient.deleteAd(id = "13").getOrThrow()
        val rec = server.takeRequest()
        assertEquals("DELETE", rec.method)
        assertEquals("/admin/ads/13", rec.path)
    }

    // ---------- Реклама: кабинет партнёра (self-serve) ----------

    @Test
    fun createMyAd_postsToAdsAndParsesDto() = runBlocking {
        server.enqueue(json("""{"id":"m1","title":"Мойка","text":"скидка","button":"Открыть","target":"https://x.ru","package":"week","status":"draft","budget_kop":30000,"period_days":7}"""))
        val res = ApiClient.createMyAd(
            title = "Мойка", text = "скидка", button = "Открыть", target = "https://x.ru",
            pkg = "week", cities = "Уфа",
        )
        assertTrue(res.isSuccess)
        val dto = res.getOrThrow()
        assertEquals("m1", dto.id)
        assertEquals("Мойка", dto.title)
        assertEquals("draft", dto.status)
        assertEquals(30000, dto.budgetKop)
        assertEquals(7, dto.periodDays)
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/ads", rec.path)
        val body = rec.body.readUtf8()
        assertTrue(body.contains("\"title\":\"Мойка\""))
        assertTrue(body.contains("\"package\":\"week\""))
        assertTrue(body.contains("\"cities\":\"Уфа\""))
    }

    @Test
    fun updateMyAd_postsToIdPathAndParsesDto() = runBlocking {
        server.enqueue(json("""{"id":"m2","title":"Правка","text":"t","button":"b","target":"","package":"month","status":"draft"}"""))
        val res = ApiClient.updateMyAd(
            id = "m2", title = "Правка", text = "t", button = "b", target = "", pkg = "month", cities = "Казань",
        )
        assertTrue(res.isSuccess)
        assertEquals("m2", res.getOrThrow().id)
        assertEquals("Правка", res.getOrThrow().title)
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/ads/m2", rec.path)
        assertTrue(rec.body.readUtf8().contains("\"package\":\"month\""))
    }

    @Test
    fun submitMyAd_postsToSubmitPathAndParsesDto() = runBlocking {
        server.enqueue(json("""{"id":"m3","title":"На модерацию","text":"t","button":"b","target":"","status":"pending","submitted_at":"2026-07-01T10:00:00"}"""))
        val res = ApiClient.submitMyAd(id = "m3")
        assertTrue(res.isSuccess)
        val dto = res.getOrThrow()
        assertEquals("m3", dto.id)
        assertEquals("pending", dto.status)
        assertEquals("2026-07-01T10:00:00", dto.submittedAt)
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/ads/m3/submit", rec.path)
    }

    @Test
    fun payAd_postsToPayPathAndReturnsAmountKop() = runBlocking {
        server.enqueue(json("""{"amount_kop":30000}"""))
        val res = ApiClient.payAd(id = "m4")
        assertTrue(res.isSuccess)
        assertEquals(30000, res.getOrThrow())
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/ads/m4/pay", rec.path)
    }

    // ---------- Отзыв о приложении ----------

    @Test
    fun submitAppReview_postsStarsTextCity() = runBlocking {
        server.enqueue(json("{}"))
        val res = ApiClient.submitAppReview(stars = 5, text = "Отлично", city = "Уфа")
        assertTrue(res.isSuccess)
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/reviews", rec.path)
        val body = rec.body.readUtf8()
        assertTrue(body.contains("\"stars\":5"))
        assertTrue(body.contains("\"text\":\"Отлично\""))
        assertTrue(body.contains("\"city\":\"Уфа\""))
    }

    // ---------- Жалобы и чёрный список ----------

    @Test
    fun reportUser_postsTargetAndReason() = runBlocking {
        server.enqueue(json("{}"))
        val res = ApiClient.reportUser(targetUserId = 77, reason = "спам")
        assertTrue(res.isSuccess)
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/reports", rec.path)
        val body = rec.body.readUtf8()
        assertTrue(body.contains("\"target_user_id\":77"))
        assertTrue(body.contains("\"reason\":\"спам\""))
    }

    @Test
    fun blockUser_postsBlockedUserId() = runBlocking {
        server.enqueue(json("{}"))
        ApiClient.blockUser(userId = 88).getOrThrow()
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/blocks", rec.path)
        assertTrue(rec.body.readUtf8().contains("\"blocked_user_id\":88"))
    }

    @Test
    fun unblockUser_sendsDeleteToBlockIdPath() = runBlocking {
        server.enqueue(json("{}"))
        ApiClient.unblockUser(userId = 88).getOrThrow()
        val rec = server.takeRequest()
        assertEquals("DELETE", rec.method)
        assertEquals("/blocks/88", rec.path)
    }

    // ---------- Админ: заявка за пользователя по телефону ----------

    @Test
    fun adminRequestForPhone_postsAllFields() = runBlocking {
        server.enqueue(json("{}"))
        val res = ApiClient.adminRequestForPhone(
            phone = "+79990001122", name = "Гуль", fromCity = "Уфа", toCity = "Сибай",
            seats = 2, comment = "с багажом",
        )
        assertTrue(res.isSuccess)
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/admin/request-for-phone", rec.path)
        val body = rec.body.readUtf8()
        assertTrue(body.contains("\"phone\":\"+79990001122\""))
        assertTrue(body.contains("\"name\":\"Гуль\""))
        assertTrue(body.contains("\"from_city\":\"Уфа\""))
        assertTrue(body.contains("\"to_city\":\"Сибай\""))
        assertTrue(body.contains("\"seats\":2"))
    }

    // ---------- Ветки ошибок ----------

    @Test
    fun createAd_serverError500_returnsFailureWithApiException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        val res = ApiClient.createAd(
            partnerName = "П", title = "т", text = "т", button = "б",
            plan = "founder", placements = "map", erid = "", target = "", city = "Уфа",
        )
        assertTrue(res.isFailure)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(500, (e as ApiException).status)
    }

    @Test
    fun payAd_error400_returnsFailureWithStatusAndDetail() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Уже оплачено"}"""))
        val res = ApiClient.payAd(id = "m4")
        assertTrue(res.isFailure)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(400, (e as ApiException).status)
        assertEquals("Уже оплачено", e.message)
    }

    @Test
    fun reportUser_error400_returnsFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Нельзя пожаловаться на себя"}"""))
        val res = ApiClient.reportUser(targetUserId = 1, reason = "x")
        assertTrue(res.isFailure)
        assertEquals("Нельзя пожаловаться на себя", res.exceptionOrNull()?.message)
    }
}
