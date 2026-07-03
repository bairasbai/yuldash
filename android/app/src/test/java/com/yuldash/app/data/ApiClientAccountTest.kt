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
 * Сетевой слой `ApiClient` — методы АККАУНТА / ПРОФИЛЯ / ВОДИТЕЛЯ (POST-действия) на локальном
 * MockWebServer (без реального бэкенда). Покрывает: updateName, updateAvatar, deleteAccount,
 * setDriverProfile, setOnline (я на линии), submitDriverVerify, requestCallback — путь/метод/тело
 * запроса, парсинг ответа и ветки ошибок. Базовый URL подменяется тест-хуком `ApiClient.testBaseUrl`.
 *
 * Почему это безопасно на JVM: `Analytics.log()` в requestCallback тихо выходит (`fa == null`, т.к.
 * Analytics.init в тестах не зовём) — Firebase не трогается. Кешируемые методы (me/referral) и
 * multipart-загрузки (uploadPhoto/uploadVoice) здесь НЕ трогаем — за них отвечают другие файлы.
 * После каждого теста чистим сессию (logout), чтобы токен/имя не «протекали» между тестами.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiClientAccountTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun teardown() {
        // Часть методов трогает сессию (updateName → saveName, deleteAccount → clearLocalSession).
        // Чистим сессию, чтобы тесты не зависели от порядка. logout() шлёт фоновый POST в runCatching →
        // безопасен; локальную сессию чистит синхронно.
        ApiClient.logout()
        ApiClient.testBaseUrl = null
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setResponseCode(200).setBody(body)

    // ---------- updateName ----------

    @Test
    fun updateName_sendsNameToMeUpdate() = runBlocking {
        server.enqueue(json("{}"))
        val res = ApiClient.updateName("Айгуль")
        assertTrue(res.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/me/update", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"name\":\"Айгуль\""))
    }

    @Test
    fun updateName_updatesCachedNameOnSuccess() = runBlocking {
        // Успех → saveName(name): cachedName() отдаёт новое имя (persist нет, но @Volatile-поле живёт).
        server.enqueue(json("{}"))
        ApiClient.updateName("Марат").getOrThrow()
        assertEquals("Марат", ApiClient.cachedName())
    }

    @Test
    fun updateName_serverError500_returnsFailureWithApiException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        val res = ApiClient.updateName("Гость")
        assertTrue(res.isFailure)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(500, (e as ApiException).status)
    }

    // ---------- updateAvatar ----------

    @Test
    fun updateAvatar_sendsAvatarUrlToMeUpdate() = runBlocking {
        server.enqueue(json("{}"))
        val res = ApiClient.updateAvatar("https://cdn.yuldash/a.jpg")
        assertTrue(res.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/me/update", recorded.path)
        // org.json может экранировать '/' как '\/' — убираем '\' перед сравнением, тест не зависит от версии.
        val sentBody = recorded.body.readUtf8().replace("\\", "")
        assertTrue(sentBody.contains("\"avatar_url\":\"https://cdn.yuldash/a.jpg\""))
    }

    @Test
    fun updateAvatar_serverError400_returnsFailureWithDetail() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Плохой URL"}"""))
        val res = ApiClient.updateAvatar("bad")
        assertTrue(res.isFailure)
        assertEquals("Плохой URL", res.exceptionOrNull()?.message)
    }

    // ---------- deleteAccount ----------

    @Test
    fun deleteAccount_postsEmptyBodyToMeDelete() = runBlocking {
        server.enqueue(json("{}"))
        val res = ApiClient.deleteAccount()
        assertTrue(res.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/me/delete", recorded.path)
        // Тело — пустой JSON-объект {}, но body присутствует (doOutput).
        assertEquals("{}", recorded.body.readUtf8())
    }

    @Test
    fun deleteAccount_serverError500_returnsFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        val res = ApiClient.deleteAccount()
        assertTrue(res.isFailure)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(500, (e as ApiException).status)
    }

    // ---------- setDriverProfile ----------

    @Test
    fun setDriverProfile_sendsCarFieldsToDriverProfile() = runBlocking {
        server.enqueue(json("{}"))
        val res = ApiClient.setDriverProfile(
            make = "Kia", model = "Rio", color = "белый", plate = "А123ВС", seats = 4,
        )
        assertTrue(res.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/driver/profile", recorded.path)
        val sentBody = recorded.body.readUtf8()
        assertTrue(sentBody.contains("\"car_make\":\"Kia\""))
        assertTrue(sentBody.contains("\"car_model\":\"Rio\""))
        assertTrue(sentBody.contains("\"car_color\":\"белый\""))
        assertTrue(sentBody.contains("\"car_plate\":\"А123ВС\""))
        assertTrue(sentBody.contains("\"seats\":4"))
    }

    @Test
    fun setDriverProfile_serverError400_returnsFailureWithStatus() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Номер занят"}"""))
        val res = ApiClient.setDriverProfile("Lada", "Vesta", "серый", "О000ОО", 3)
        assertTrue(res.isFailure)
        assertFalse(res.isSuccess)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(400, (e as ApiException).status)
        assertEquals("Номер занят", e.message)
    }

    // ---------- setOnline (водитель «на линии») ----------

    @Test
    fun setOnline_true_sendsOnlineFlagToDriverOnline() = runBlocking {
        server.enqueue(json("{}"))
        val res = ApiClient.setOnline(true)
        assertTrue(res.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/driver/online", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"online\":true"))
    }

    @Test
    fun setOnline_false_sendsOfflineFlag() = runBlocking {
        server.enqueue(json("{}"))
        ApiClient.setOnline(false).getOrThrow()
        val recorded = server.takeRequest()
        assertEquals("/driver/online", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"online\":false"))
    }

    // ---------- submitDriverVerify ----------

    @Test
    fun submitDriverVerify_sendsDocumentUrlsToDriverVerify() = runBlocking {
        server.enqueue(json("{}"))
        val res = ApiClient.submitDriverVerify(licenseUrl = "lic://u1", carPhotoUrl = "car://u2")
        assertTrue(res.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/driver/verify", recorded.path)
        // org.json может экранировать '/' как '\/' — убираем '\' перед сравнением, тест не зависит от версии.
        val sentBody = recorded.body.readUtf8().replace("\\", "")
        assertTrue(sentBody.contains("\"license_url\":\"lic://u1\""))
        assertTrue(sentBody.contains("\"car_photo_url\":\"car://u2\""))
    }

    // ---------- requestCallback («перезвоните мне») ----------

    @Test
    fun requestCallback_sendsNoteToCallback() = runBlocking {
        // В success-блоке Analytics.log("callback_request") — no-op без Firebase (fa == null), безопасно.
        server.enqueue(json("{}"))
        val res = ApiClient.requestCallback("Плохо вижу, перезвоните")
        assertTrue(res.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/callback", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"note\":\"Плохо вижу, перезвоните\""))
    }

    @Test
    fun requestCallback_serverError500_returnsFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        val res = ApiClient.requestCallback("зов")
        assertTrue(res.isFailure)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(500, (e as ApiException).status)
    }
}
