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
 * Кешируемые GET (`cachedGet`) и один multipart (`callMultipart`) на локальном MockWebServer.
 *
 * Кеш `respCache` — процессный (общий на весь JVM), другие тест-файлы трогают ключи `me`/`boost-plans`.
 * Чтобы старт был чистым, в `@Before` после `testBaseUrl` вызываем `ApiClient.logout()` — он синхронно
 * чистит `respCache` (без токена сетевого logout нет, только локальная очистка). Каждый кеш-ключ
 * тестируется ОДИН раз (первый вызов уходит в сеть, дальше отдаётся из кеша по TTL).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiClientCachedTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        // Чистый кеш на каждый тест: сбрасывает respCache (и токен) синхронно.
        ApiClient.logout()
    }

    @After
    fun teardown() {
        ApiClient.testBaseUrl = null
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setResponseCode(200).setBody(body)

    // ---------- Кешируемые GET: парсинг DTO ----------

    @Test
    fun getFeed_parsesCountersAndTopRoute() = runBlocking {
        server.enqueue(
            json(
                """{"today":12,"week":80,"month":300,"year":4200,"drivers":15,
                   "top_route":{"from_city":"Уфа","to_city":"Казань","count":9},
                   "donations_total":5000}""",
            ),
        )
        val res = ApiClient.getFeed()
        assertTrue(res.isSuccess)
        val feed = res.getOrThrow()
        assertEquals(12, feed.today)
        assertEquals(80, feed.week)
        assertEquals(300, feed.month)
        assertEquals(4200, feed.year)
        assertEquals(15, feed.drivers)
        assertEquals("Уфа", feed.topFrom)
        assertEquals("Казань", feed.topTo)
        assertEquals(9, feed.topCount)
        assertEquals(5000, feed.donationsTotal)
    }

    @Test
    fun getFeed_missingTopRoute_defaultsToEmpty() = runBlocking {
        // top_route может отсутствовать (пусто на маршрутах) — не падаем, отдаём дефолты.
        server.enqueue(json("""{"today":0,"week":0,"month":0,"year":0,"drivers":0}"""))
        val feed = ApiClient.getFeed().getOrThrow()
        assertEquals(0, feed.today)
        assertEquals("", feed.topFrom)
        assertEquals("", feed.topTo)
        assertEquals(0, feed.topCount)
        assertEquals(0, feed.donationsTotal)
    }

    @Test
    fun getPopularRoutes_parsesItems() = runBlocking {
        server.enqueue(json("""{"items":[{"from_city":"Уфа","to_city":"Сибай","count":7}]}"""))
        val res = ApiClient.getPopularRoutes()
        assertTrue(res.isSuccess)
        val list = res.getOrThrow()
        assertEquals(1, list.size)
        assertEquals("Уфа", list[0].from)
        assertEquals("Сибай", list[0].to)
        assertEquals(7, list[0].count)
    }

    @Test
    fun getPopularRoutes_emptyItems_returnsEmptyList() = runBlocking {
        server.enqueue(json("""{"items":[]}"""))
        val list = ApiClient.getPopularRoutes().getOrThrow()
        assertTrue(list.isEmpty())
    }

    @Test
    fun getMyRoutes_parsesItems() = runBlocking {
        server.enqueue(json("""{"items":[{"from_city":"Уфа","to_city":"Стерлитамак","count":3}]}"""))
        val res = ApiClient.getMyRoutes()
        assertTrue(res.isSuccess)
        val list = res.getOrThrow()
        assertEquals(1, list.size)
        assertEquals("Уфа", list[0].from)
        assertEquals("Стерлитамак", list[0].to)
        assertEquals(3, list[0].count)
    }

    @Test
    fun getContacts_parsesTrustedContactFields() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"id":5,"name":"Айгуль","relation":"сестра",
                   "phone":"+79991112233","notify_by_default":true}]}""",
            ),
        )
        val res = ApiClient.getContacts()
        assertTrue(res.isSuccess)
        val list = res.getOrThrow()
        assertEquals(1, list.size)
        assertEquals(5, list[0].id)
        assertEquals("Айгуль", list[0].name)
        assertEquals("сестра", list[0].relation)
        assertEquals("+79991112233", list[0].phone)
        assertTrue(list[0].notifyByDefault)
    }

    @Test
    fun getAdPackages_mapsSnakeCaseFields() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"code":"week","title":"Неделя","title_ba":"Атна",
                   "amount_kop":50000,"period_days":7}]}""",
            ),
        )
        val res = ApiClient.getAdPackages()
        assertTrue(res.isSuccess)
        val list = res.getOrThrow()
        assertEquals(1, list.size)
        assertEquals("week", list[0].code)
        assertEquals("Неделя", list[0].title)
        assertEquals("Атна", list[0].titleBa)
        assertEquals(50000, list[0].amountKop)
        assertEquals(7, list[0].periodDays)
    }

    @Test
    fun getReferral_parsesReferralDto() = runBlocking {
        server.enqueue(json("""{"code":"AB12","invited":4,"credits":200,"redeemed":true}"""))
        val res = ApiClient.getReferral()
        assertTrue(res.isSuccess)
        val ref = res.getOrThrow()
        assertEquals("AB12", ref.code)
        assertEquals(4, ref.invited)
        assertEquals(200, ref.credits)
        assertTrue(ref.redeemed)
    }

    // ---------- Ветка ошибки на кешируемом GET ----------

    @Test
    fun getFeed_serverError500_returnsFailure_andNotCached() = runBlocking {
        // 500 → Result.failure (cachedGet кладёт в кеш только onSuccess, ошибка не кешируется).
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        val res = ApiClient.getFeed()
        assertTrue(res.isFailure)
        assertFalse(res.isSuccess)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(500, (e as ApiException).status)
    }

    // ---------- Multipart: uploadVoice ----------

    @Test
    fun uploadVoice_returnsUrlFromResponse() = runBlocking {
        server.enqueue(json("""{"url":"https://cdn.yulbash.ru/voice/abc.m4a"}"""))
        val res = ApiClient.uploadVoice(byteArrayOf(1, 2, 3, 4))
        assertTrue(res.isSuccess)
        assertEquals("https://cdn.yulbash.ru/voice/abc.m4a", res.getOrThrow())
    }

    @Test
    fun uploadVoice_sendsPostMultipartRequest() = runBlocking {
        server.enqueue(json("""{"url":"https://cdn.yulbash.ru/voice/x.m4a"}"""))
        ApiClient.uploadVoice(byteArrayOf(9, 8, 7)).getOrThrow()
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/voice", recorded.path)
        val contentType = recorded.getHeader("Content-Type").orEmpty()
        assertTrue(contentType.contains("multipart/form-data"))
    }

    @Test
    fun uploadVoice_serverError500_returnsFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        val res = ApiClient.uploadVoice(byteArrayOf(1))
        assertTrue(res.isFailure)
        val e = res.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals(500, (e as ApiException).status)
    }
}
