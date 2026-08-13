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
 * Мёртвая сессия должна ЗАКАНЧИВАТЬСЯ, а не тянуться бесконечно.
 *
 * Как это выглядело у человека (поймано вживую 2026-08-13, прокси между приложением и сервером).
 * Токен перестал действовать — сервер сменил секрет, сессию отозвали, прошёл срок. Приложение
 * об этом не узнавало НИКОГДА:
 *
 * - каждый авторизованный запрос отвечал 401: `/me`, `/requests/mine`, `/trusted-contacts`, пуши;
 * - приложение продолжало считать себя залогиненным — профиль показывал имя из кэша;
 * - экраны без кэша писали «Не удалось загрузить. Проверь интернет» — при живом интернете;
 * - кнопки «выйти» на этих экранах нет, сама сессия не заканчивалась.
 *
 * То есть единственным лечением была переустановка приложения.
 *
 * Причина в одном условии. Ветка «завершить сессию» срабатывала только когда есть refresh-токен:
 *
 *     code == 401 && auth && !isRetry && !refreshToken.isNullOrBlank()
 *
 * Оно описывало ЧАСТЫЙ случай (access протух, refresh жив), а не ВСЕ. Если refresh пуст — сессия
 * из старой версии, не сохранился, вычищен — 401 уходил обычной ошибкой и всё зависало.
 *
 * Тест держит правило: **401 на авторизованном запросе — это конец сессии, чем бы её ни лечили.**
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiClientDeadSessionTest {

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
        server.shutdown()
    }

    @Test
    fun `401 без refresh-токена завершает сессию, а не выдаёт очередную ошибку`() = runBlocking {
        // saveToken кладёт ТОЛЬКО access — ровно тот случай, который раньше не обрабатывался.
        ApiClient.saveToken("мёртвый_токен")
        assertTrue("до вызова считаем себя залогиненными", ApiClient.isLoggedIn())
        // 401 на сам вызов + запас на фоновые запросы logout (/push/unregister, /auth/logout).
        repeat(4) { server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Неверный токен"}""")) }

        val res = ApiClient.deleteContact(7)

        assertTrue("вызов обязан вернуть ошибку", res.isFailure)
        assertEquals(401, (res.exceptionOrNull() as ApiException).status)
        // Главное: приложение больше НЕ считает себя залогиненным и сказало об этом UI.
        assertTrue("UI должен получить сигнал «сессия истекла»", ApiClient.sessionExpired.value)
        assertFalse("локальная сессия должна быть очищена", ApiClient.isLoggedIn())
    }

    @Test
    fun `успешный ответ сессию не трогает`() = runBlocking {
        // Обратная сторона: сторож не должен выкидывать человека на ровном месте.
        ApiClient.saveToken("живой_токен")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        val res = ApiClient.deleteContact(7)

        assertTrue(res.isSuccess)
        assertFalse("сессия жива — сигнала быть не должно", ApiClient.sessionExpired.value)
        assertTrue(ApiClient.isLoggedIn())
    }

    @Test
    fun `чужая ошибка сервера сессию не завершает`() = runBlocking {
        // 500 — это сбой сервера, а не конец сессии. Выкинуть человека на вход тут было бы хуже
        // самой ошибки: он потерял бы вход из-за чужой поломки.
        ApiClient.saveToken("живой_токен")
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"упало"}"""))

        val res = ApiClient.deleteContact(7)

        assertTrue(res.isFailure)
        assertEquals(500, (res.exceptionOrNull() as ApiException).status)
        assertFalse(ApiClient.sessionExpired.value)
        assertTrue("вход должен остаться", ApiClient.isLoggedIn())
    }
}
