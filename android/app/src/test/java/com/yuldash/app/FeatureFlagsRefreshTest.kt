package com.yuldash.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
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
 * Флаги направлений с настоящим HTTP: что пришло — то и включено, запомнено на диск и видно
 * на следующем холодном старте; сбой сети не превращает «неизвестно» во «включено».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FeatureFlagsRefreshTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        ApiClient.resetForTest(); ApiClient.init(context)
        context.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer().apply { start() }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 5_000
        FeatureFlags.coldStartForTest()
    }

    @After fun tearDown() {
        server.shutdown()
        ApiClient.testTimeoutMs = null
        ApiClient.resetForTest()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `ответ сервера включает своё и запоминается до следующего запуска`() = runBlocking {
        server.enqueue(json("""{"rides":true,"taxi":false,"courier":false,"parcels":true,"ads":false,"business":true}"""))
        FeatureFlags.refresh(context) { 42_000L }
        val request = server.takeRequest()
        assertEquals("/config/features", request.requestUrl!!.encodedPath)
        assertNull("флаги публичные — токен туда не уходит", request.getHeader("Authorization"))
        assertEquals(setOf(Feature.Parcels, Feature.Business), FeatureFlags.current.on)
        assertTrue(FeatureFlags.current.known)

        // Новый процесс: память пуста, сети нет — берём запомненное с диска.
        FeatureFlags.coldStartForTest()
        assertFalse(FeatureFlags.current.known)
        FeatureFlags.ensureLoaded(context)
        assertTrue(FeatureFlags.current.known)
        assertEquals(setOf(Feature.Parcels, Feature.Business), FeatureFlags.current.on)
        assertEquals(42_000L, FeatureFlags.current.fetchedAtMs)
    }

    @Test
    fun `первый запуск без связи — всё, кроме попуток, закрыто`() = runBlocking {
        server.enqueue(json("""{"detail":"boom"}""", code = 500))
        assertFalse("до первой попытки гейт показывает загрузку", FeatureFlags.settled.value)
        FeatureFlags.refresh(context)
        assertFalse("ошибка сервера не должна делать состояние «известным»", FeatureFlags.current.known)
        assertTrue(FeatureFlags.current.on.isEmpty())
        assertTrue("попытка кончилась — дальше честное «скоро», а не вечная загрузка", FeatureFlags.settled.value)
    }

    @Test
    fun `сбой после удачного ответа не сбрасывает известное`() = runBlocking {
        server.enqueue(json("""{"ads":true}"""))
        FeatureFlags.refresh(context) { 1L }
        server.enqueue(json("{}", code = 503))
        FeatureFlags.refresh(context) { 2L }
        assertEquals(setOf(Feature.Ads), FeatureFlags.current.on)
        assertEquals(1L, FeatureFlags.current.fetchedAtMs)
    }
}
