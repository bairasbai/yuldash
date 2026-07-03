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
 * Сетевой слой `ApiClient` — методы АВТОРИЗАЦИИ на локальном MockWebServer (без реального бэкенда).
 * Покрывает: requestCode, verifyCode, tgStart, tgVerify — путь/метод/тело запроса, парсинг ответа,
 * сохранение токена и ветки ошибок. Базовый URL подменяется тест-хуком `ApiClient.testBaseUrl`.
 *
 * Почему это безопасно на JVM: `Analytics.log()` в success-блоках verify/tgStart тихо выходит
 * (`fa == null`, т.к. Analytics.init в тестах не зовём) — Firebase не трогается. `applyAuth()` в
 * tgVerify вызывает `registerCurrentPushToken()`, но тот обёрнут в runCatching → без Firebase не падает.
 * После каждого теста чистим сессию (logout), чтобы сохранённый токен не «протекал» между тестами.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiClientAuthTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun teardown() {
        // verifyCode/tgVerify сохраняют токен в @Volatile-поле синглтона (prefs=null → без персиста,
        // но поле живёт весь JVM). Чистим сессию, чтобы тесты не зависели от порядка. logout() шлёт
        // фоновый POST в runCatching → безопасен; локальную сессию чистит синхронно.
        ApiClient.logout()
        ApiClient.testBaseUrl = null
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setResponseCode(200).setBody(body)

    // ---------- requestCode ----------

    @Test
    fun requestCode_sendsPhoneToRequestCodePath() = runBlocking {
        server.enqueue(json("{}"))
        val res = ApiClient.requestCode("+79990001122")
        assertTrue(res.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/auth/request-code", recorded.path)
        val sentBody = recorded.body.readUtf8()
        assertTrue(sentBody.contains("\"phone\":\"+79990001122\""))
    }

    @Test
    fun requestCode_noAuthHeaderSent() = runBlocking {
        // auth=false → токен не шлём, даже если бы он был.
        server.enqueue(json("{}"))
        ApiClient.requestCode("+70000000000").getOrThrow()
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun requestCode_serverError400_returnsFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Неверный телефон"}"""))
        val res = ApiClient.requestCode("bad")
        assertTrue(res.isFailure)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(400, (e as ApiException).status)
        assertEquals("Неверный телефон", e.message)
    }

    // ---------- verifyCode ----------

    @Test
    fun verifyCode_sendsPhoneCodeNameAndParsesUser() = runBlocking {
        server.enqueue(json("""{"access_token":"jwt_abc","refresh_token":"ref_xyz","name":"Айгуль"}"""))
        val res = ApiClient.verifyCode(phone = "+79991234567", code = "1234", name = "Айгуль")
        assertTrue(res.isSuccess)
        val obj = res.getOrThrow()
        assertEquals("jwt_abc", obj.optString("access_token"))
        assertEquals("Айгуль", obj.optString("name"))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/auth/verify", recorded.path)
        val sentBody = recorded.body.readUtf8()
        assertTrue(sentBody.contains("\"phone\":\"+79991234567\""))
        assertTrue(sentBody.contains("\"code\":\"1234\""))
        assertTrue(sentBody.contains("\"name\":\"Айгуль\""))
    }

    @Test
    fun verifyCode_savesTokenSoSubsequentAuthedCallSendsBearer() = runBlocking {
        // Успешный verify → saveToken(access_token). Проверяем реально: следующий auth=true запрос
        // (me) уходит с заголовком Authorization: Bearer <тот самый токен>.
        server.enqueue(json("""{"access_token":"jwt_LOGIN","refresh_token":"ref","name":"Марат"}"""))
        ApiClient.verifyCode("+79995553311", "0000", "Марат").getOrThrow()
        server.takeRequest() // проглатываем сам /auth/verify

        server.enqueue(json("""{"name":"Марат"}"""))
        ApiClient.me().getOrThrow()
        val meReq = server.takeRequest()
        assertEquals("/me", meReq.path)
        assertEquals("Bearer jwt_LOGIN", meReq.getHeader("Authorization"))
    }

    @Test
    fun verifyCode_serverError401_returnsFailureWithStatus() = runBlocking {
        // Неверный код → 401. auth=false, поэтому нет ветки refresh — просто ошибка наверх.
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Неверный код"}"""))
        val res = ApiClient.verifyCode("+79991234567", "9999", "Гость")
        assertTrue(res.isFailure)
        assertFalse(res.isSuccess)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(401, (e as ApiException).status)
        assertEquals("Неверный код", e.message)
    }

    // ---------- tgStart ----------

    @Test
    fun tgStart_postsAndReturnsRequestId() = runBlocking {
        server.enqueue(json("""{"request_id":"req_42"}"""))
        val res = ApiClient.tgStart()
        assertTrue(res.isSuccess)
        assertEquals("req_42", res.getOrThrow())
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/auth/tg/start", recorded.path)
        // Тело — пустой JSON-объект {}, но body присутствует (doOutput).
        assertEquals("{}", recorded.body.readUtf8())
    }

    @Test
    fun tgStart_missingRequestId_returnsEmptyString() = runBlocking {
        // optString("request_id") без ключа → "" (не падаем).
        server.enqueue(json("{}"))
        val res = ApiClient.tgStart()
        assertTrue(res.isSuccess)
        assertEquals("", res.getOrThrow())
    }

    @Test
    fun tgStart_serverError500_returnsFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        val res = ApiClient.tgStart()
        assertTrue(res.isFailure)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(500, (e as ApiException).status)
    }

    // ---------- tgVerify ----------

    @Test
    fun tgVerify_sendsRequestIdAndCode_parsesResponse() = runBlocking {
        server.enqueue(json("""{"access_token":"tg_jwt","refresh_token":"tg_ref","user":{"name":"Айдар"}}"""))
        val res = ApiClient.tgVerify(requestId = "req_99", code = "654321")
        assertTrue(res.isSuccess)
        val obj = res.getOrThrow()
        assertEquals("tg_jwt", obj.optString("access_token"))
        assertEquals("Айдар", obj.optJSONObject("user")?.optString("name"))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/auth/tg/verify", recorded.path)
        val sentBody = recorded.body.readUtf8()
        assertTrue(sentBody.contains("\"request_id\":\"req_99\""))
        assertTrue(sentBody.contains("\"code\":\"654321\""))
    }

    @Test
    fun tgVerify_savesTokenViaApplyAuth() = runBlocking {
        // applyAuth() сохраняет access_token → следующий auth=true запрос уходит с Bearer.
        server.enqueue(json("""{"access_token":"tg_LOGIN","refresh_token":"r","user":{"name":"Тимур"}}"""))
        ApiClient.tgVerify("req_1", "111111").getOrThrow()
        server.takeRequest() // /auth/tg/verify

        server.enqueue(json("""{"name":"Тимур"}"""))
        ApiClient.me().getOrThrow()
        val meReq = server.takeRequest()
        assertEquals("Bearer tg_LOGIN", meReq.getHeader("Authorization"))
    }

    @Test
    fun tgVerify_serverError400_returnsFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Код не тот"}"""))
        val res = ApiClient.tgVerify("req_1", "000000")
        assertTrue(res.isFailure)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(400, (e as ApiException).status)
        assertEquals("Код не тот", e.message)
    }
}
