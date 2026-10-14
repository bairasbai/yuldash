package com.yuldash.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
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
 * на диске; холодный старт и любой отказ требуют нового живого подтверждения.
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
    fun `живой ответ включает своё, но enabled-кеш не открывает холодный процесс`() = runBlocking {
        server.enqueue(json("""{"rides":true,"taxi":false,"courier":false,"parcels":true,"ads":false,"business":true}"""))
        FeatureFlags.refresh(context) { 42_000L }
        val request = server.takeRequest()
        assertEquals("/config/features", request.requestUrl!!.encodedPath)
        assertNull("флаги публичные — токен туда не уходит", request.getHeader("Authorization"))
        assertEquals(setOf(Feature.Parcels, Feature.Business), FeatureFlags.current.on)
        assertTrue(FeatureFlags.current.known)

        // Новый процесс без сети: даже enabled на диске не открывает направления.
        FeatureFlags.coldStartForTest()
        assertFalse(FeatureFlags.current.known)
        FeatureFlags.ensureLoaded(context)
        assertFalse(FeatureFlags.current.known)
        assertTrue(FeatureFlags.current.on.isEmpty())
        assertEquals(0L, FeatureFlags.current.fetchedAtMs)
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
    fun `ошибка сервера закрывает ранее включённое и очищает enabled-кеш`() = runBlocking {
        server.enqueue(json("""{"ads":true}"""))
        FeatureFlags.refresh(context) { 1L }
        server.enqueue(json("{}", code = 503))
        FeatureFlags.refresh(context) { 2L }
        assertTrue(FeatureFlags.current.on.isEmpty())
        assertEquals(0L, FeatureFlags.current.fetchedAtMs)
        assertEquals("{}", context.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE).getString("features_json", null))
    }

    @Test fun `битый успешный ответ закрывает ранее включённое`() = runBlocking {
        server.enqueue(json("""{"taxi":true,"ads":true}"""))
        FeatureFlags.refresh(context)
        server.enqueue(json("broken JSON"))
        FeatureFlags.refresh(context)
        assertTrue(FeatureFlags.current.on.isEmpty())
        assertTrue(FeatureFlags.settled.value)
    }

    @Test fun `молчащий HTTP закрывается по общему сроку и не открывает кеш`() = runBlocking {
        server.enqueue(json("""{"taxi":true}"""))
        FeatureFlags.refresh(context)
        ApiClient.testTimeoutMs = 100
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        withTimeout(3_000) { FeatureFlags.refresh(context) }
        assertTrue(FeatureFlags.current.on.isEmpty())
        assertTrue(FeatureFlags.settled.value)
        assertEquals(2, server.requestCount)
    }

    @Test fun `одновременные читатели используют один успешный запрос`() = runBlocking {
        server.enqueue(json("""{"courier":true}""").setBodyDelay(200, TimeUnit.MILLISECONDS))
        (1..5).map { async { FeatureFlags.refresh(context) } }.awaitAll()
        assertEquals(1, server.requestCount)
        assertEquals(setOf(Feature.Courier), FeatureFlags.current.on)
    }

    @Test fun `одновременные читатели используют один запрос даже при отказе`() = runBlocking {
        server.enqueue(json("{}", 503).setHeadersDelay(200, TimeUnit.MILLISECONDS))
        (1..5).map { async { FeatureFlags.refresh(context) } }.awaitAll()
        assertEquals(1, server.requestCount)
        assertTrue(FeatureFlags.current.on.isEmpty())
    }

    @Test fun `обрыв сети имеет приоритет над поздним enabled-ответом`() = runBlocking {
        server.enqueue(json("""{"ads":true}"""))
        FeatureFlags.refresh(context)
        server.takeRequest()
        server.enqueue(json("""{"taxi":true}""").setBodyDelay(1, TimeUnit.SECONDS))
        val pending = async { FeatureFlags.refresh(context) }
        withContext(Dispatchers.IO) { assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null) }
        FeatureFlags.markOffline(context)
        assertTrue(FeatureFlags.current.on.isEmpty())
        withTimeout(3_000) { pending.await() }
        assertTrue(FeatureFlags.current.on.isEmpty())
    }

    @Test fun `отмена читателя отменяет HTTP и закрывает ранее включённое`() = runBlocking {
        server.enqueue(json("""{"taxi":true}"""))
        FeatureFlags.refresh(context)
        server.takeRequest()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async { FeatureFlags.refresh(context) }
        withContext(Dispatchers.IO) { assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null) }
        withTimeout(3_000) { pending.cancelAndJoin() }
        assertTrue(FeatureFlags.current.on.isEmpty())
    }
}
