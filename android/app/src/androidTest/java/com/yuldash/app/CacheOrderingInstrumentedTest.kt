package com.yuldash.app

import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ContactsRepository
import com.yuldash.app.data.TestContacts
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.SharedPreferences
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Two live HTTP reads; actual Job completion precedes cache/publication/disk probes. */
@RunWith(AndroidJUnit4::class)
class CacheOrderingInstrumentedTest {
    private enum class Get(val path: String, val key: String, val staticKey: String? = null) {
        ME("/me", "me"), CONTACTS("/trusted-contacts", "contacts"), REFERRAL("/referral/me", "referral"),
        POPULAR("/popular-routes", "popular-routes"), MY_ROUTES("/my-routes", "my-routes"), FEED("/feed", "feed"),
        PACKAGES("/ad-packages", "ad-packages", "ad-packages"), BOOST("/boost/plans", "boost-plans", "boost-plans"),
        SETTLEMENTS("/settlements/popular-routes", "settlement-popular-routes");
        fun body(v: Int): String = when (this) {
            ME -> """{"name":"Synthetic $v","role":"role$v"}"""
            CONTACTS -> """{"items":[{"id":$v,"name":"Synthetic $v"}]}"""
            REFERRAL -> """{"code":"$v","credits":$v}"""
            POPULAR, MY_ROUTES -> """{"items":[{"from_city":"Synthetic","to_city":"Synthetic","count":$v}]}"""
            FEED -> """{"today":$v}"""
            PACKAGES -> """{"items":[{"code":"synthetic","title":"Synthetic","title_ba":"Synthetic","amount_kop":$v,"period_days":7,"extra":"kept$v"}]}"""
            BOOST -> """{"items":[{"tier":"synthetic","title":"Synthetic","price":$v,"hours":24,"extra":"kept$v"}]}"""
            SETTLEMENTS -> """{"routes":[{"from":{"id":$v,"name_ru":"Synthetic"},"to":{"id":9,"name_ru":"Synthetic"}}]}"""
        }
        suspend fun read(): Result<Int> = when (this) {
            ME -> ApiClient.me().map { it.getString("name").substringAfterLast(' ').toInt() }
            CONTACTS -> TestContacts.read().map { it.single().id }
            REFERRAL -> ApiClient.getReferral().map { it.credits }
            POPULAR -> ApiClient.getPopularRoutes().map { it.single().count }
            MY_ROUTES -> ApiClient.getMyRoutes().map { it.single().count }
            FEED -> ApiClient.getFeed().map { it.today }
            PACKAGES -> ApiClient.getAdPackages().map { it.single().amountKop }
            BOOST -> ApiClient.getBoostPlans().map { it.single().price }
            SETTLEMENTS -> ApiClient.getSettlementPopularRoutes().map { it.single().from.id }
        }
        fun persisted(v: Int) = JSONObject(body(v)).getJSONArray("items").toString()
    }
    private data class Record(val path: String, val method: String?, val bearer: String?, val body: String)
    private val parent = SupervisorJob()
    private val scope = CoroutineScope(parent + Dispatchers.IO)
    private lateinit var server: Loopback
    private lateinit var prefs: SharedPreferences
    private val records = CopyOnWriteArrayList<Record>()
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val publications = CopyOnWriteArrayList<List<Int>>()
    private val sequence = AtomicInteger()
    private val token = "header.eyJzdWIiOiIxMSJ9.signature"
    private var owner = 0L
    @Volatile private var holdIndex = 1
    @Volatile private var failureIndex = 0
    @Volatile private var emptyIndex = 0
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext)
        prefs = ApiClient::class.java.getDeclaredField("prefs").apply { isAccessible = true }.get(ApiClient) as SharedPreferences
        prefs.edit().remove("static_ad-packages").remove("static_boost-plans").commit()
        ApiClient.saveToken(token); owner = ApiClient.queueSessionGeneration()
        server = Loopback(); ApiClient.testBaseUrl = server.url; ApiClient.testTimeoutMs = 60000
    }
    @After fun cleanup() {
        release.countDown(); parent.cancel(); runBlocking { withTimeout(10000) { parent.join() } }
        server.close(); ApiClient.resetForTest(); ApiClient.testTimeoutMs = null
    }
    private fun read(get: Get) = runBlocking { get.read().getOrThrow() }
    private fun finish(job: Deferred<Result<Int>>, value: Int) {
        release.countDown(); runBlocking { withTimeout(10000) { job.join() } }
        assertTrue(job.isCompleted); assertFalse(job.isCancelled)
        assertEquals(value, runBlocking { job.await().getOrThrow() })
    }
    private fun wire(get: Get, count: Int) {
        assertEquals(count, records.size); assertEquals(List(count) { get.path }, records.map { it.path })
        assertTrue(records.all { it.method == "GET" && it.body.isEmpty() })
        val private = get in listOf(Get.ME, Get.CONTACTS, Get.REFERRAL, Get.MY_ROUTES)
        assertEquals(List(count) { if (private) "Bearer $token" else null }, records.map { it.bearer })
        assertEquals(owner, ApiClient.queueSessionGeneration())
    }
    private fun reverse(get: Get, failedLater: Boolean = false) {
        if (failedLater) failureIndex = 2
        val old = scope.async { get.read() }; assertTrue(started.await(10, TimeUnit.SECONDS)); assertFalse(old.isCompleted)
        val later = runBlocking { get.read() }
        if (failedLater) assertTrue(later.isFailure) else assertEquals(2, later.getOrThrow())
        finish(old, 1)
        val expected = if (failedLater) 1 else 2
        get.staticKey?.let { assertEquals(get.persisted(expected), prefs.getString("static_$it", null)) }
        if (get == Get.ME) { assertEquals("Synthetic $expected", ApiClient.cachedName()); assertEquals("role$expected", ApiClient.cachedRole()) }
        assertEquals("Late older success must not replace a newer accepted snapshot", expected, read(get))
        assertEquals(expected, read(get)); wire(get, 2)
    }
    private fun forward(get: Get) {
        holdIndex = 2
        // Two cold reads: hold second, while the first is traced inside call after JSON parse, before DTO/acceptance.
        val firstAtTrace = CountDownLatch(1); val firstLeaveTrace = CountDownLatch(1)
        val traced = AtomicInteger()
        ApiClient.testTrace = { line -> if (line.startsWith("ВЫШЛИ из вызова GET ${get.path} успешно") && traced.incrementAndGet() == 1) {
            firstAtTrace.countDown(); check(firstLeaveTrace.await(20, TimeUnit.SECONDS))
        } }
        try {
            val first = scope.async { get.read() }; assertTrue(firstAtTrace.await(10, TimeUnit.SECONDS))
            val second = scope.async { get.read() }; assertTrue(started.await(10, TimeUnit.SECONDS))
            firstLeaveTrace.countDown(); runBlocking { withTimeout(10000) { first.join() } }; assertEquals(1, runBlocking { first.await().getOrThrow() })
            assertEquals(1, read(get)); assertFalse(second.isCompleted)
            finish(second, 2); assertEquals(2, read(get)); wire(get, 2)
        } finally { firstLeaveTrace.countDown(); ApiClient.testTrace = null }
    }
    // Layering pilot: publication is an accepted list of this account appearing in ContactsRepository.state
    // (what every screen observes), not ApiClient calling back into one caller.
    private fun published(emptyLater: Boolean, fresh: Boolean = false) {
        if (emptyLater) emptyIndex = 2
        val recorder = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        recorder.launch {
            ContactsRepository.shared.state.filter { it.owner == owner }.mapNotNull { it.contacts }.distinctUntilChanged()
                .collect { list -> publications += list.map { row -> row.id } }
        }
        try {
            val old = scope.async { TestContacts.read(owner) }
            assertTrue(started.await(10, TimeUnit.SECONDS)); assertFalse(old.isCompleted)
            val expected = if (emptyLater) emptyList() else listOf(2)
            assertEquals(expected, runBlocking { TestContacts.read(owner, force = fresh).getOrThrow().map { it.id } })
            release.countDown(); runBlocking { withTimeout(10000) { old.join() } }; assertFalse(old.isCancelled)
            assertEquals(listOf(1), runBlocking { old.await().getOrThrow().map { it.id } })
            assertEquals(listOf(expected), publications.toList())
            assertEquals(expected, runBlocking { TestContacts.read().getOrThrow().map { it.id } }); wire(Get.CONTACTS, 2)
        } finally { recorder.cancel() }
    }
    // Was: reflection into ApiClient's private respCache entry to age it. Now the repository takes a clock.
    private var now = 1_000_000L
    private val clocked by lazy { ContactsRepository(clock = { now }) }
    private fun clockedRead() = runBlocking { clocked.read(owner).getOrThrow().single().id }
    private fun ttl(expired: Boolean, failure: Boolean = false) {
        holdIndex = 0; assertEquals(1, clockedRead()); if (failure) failureIndex = 2
        // Synthetic clock only, actual cache path. 10s margin around the 90s personal TTL.
        now += if (expired) 100_000 else 80_000
        val result = runBlocking { clocked.read(owner) }
        if (failure) {
            assertTrue(result.isFailure); assertEquals(3, clockedRead()); wire(Get.CONTACTS, 3)
        } else {
            assertEquals(if (expired) 2 else 1, result.getOrThrow().single().id)
            assertEquals(if (expired) 2 else 1, clockedRead()); wire(Get.CONTACTS, if (expired) 2 else 1)
        }
    }
    @Test fun reverseMe() = reverse(Get.ME)
    @Test fun reverseContacts() = reverse(Get.CONTACTS)
    @Test fun reverseReferral() = reverse(Get.REFERRAL)
    @Test fun reversePopular() = reverse(Get.POPULAR)
    @Test fun reverseMyRoutes() = reverse(Get.MY_ROUTES)
    @Test fun reverseFeed() = reverse(Get.FEED)
    @Test fun reversePackages() = reverse(Get.PACKAGES)
    @Test fun reverseBoost() = reverse(Get.BOOST)
    @Test fun reverseSettlements() = reverse(Get.SETTLEMENTS)
    @Test fun forwardContacts() = forward(Get.CONTACTS)
    @Test fun forwardFeed() = forward(Get.FEED)
    @Test fun failedLaterContactsKeepSuccessfulOlderRead() = reverse(Get.CONTACTS, true)
    @Test fun failedLaterPackagesKeepSuccessfulOlderRead() = reverse(Get.PACKAGES, true)
    @Test fun ordinaryLaterContactsRejectOlderPublication() = published(false)
    @Test fun ordinaryLaterEmptyContactsRejectOlderPublication() = published(true)
    @Test fun forcedLaterContactsRejectOlderPublication() = published(false, true)
    @Test fun forcedLaterEmptyContactsRejectOlderPublication() = published(true, true)
    @Test fun contactsInsidePersonalTtlHitCache() = ttl(false)
    @Test fun contactsBeyondPersonalTtlReadNetwork() = ttl(true)
    @Test fun expiredContactsFailureDoesNotRenewTtl() = ttl(true, true)
    @Test fun independentKeysDoNotSuppressHeldSuccess() {
        val contacts = scope.async { Get.CONTACTS.read() }; assertTrue(started.await(10, TimeUnit.SECONDS))
        assertEquals(2, read(Get.FEED)); finish(contacts, 1); assertEquals(1, read(Get.CONTACTS)); assertEquals(2, read(Get.FEED))
        assertEquals(listOf(Get.CONTACTS.path, Get.FEED.path), records.map { it.path }); assertEquals(2, records.size)
    }
    private inner class Loopback : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool { r -> Thread(r, "ordering-http").apply { isDaemon = true } }
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val errors = CopyOnWriteArrayList<String>()
        @Volatile private var closed = false
        val url = "http://127.0.0.1:${listener.localPort}"
        private val accept = thread(name = "ordering-http-accept", isDaemon = true) {
            while (!closed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                sockets += socket; workers.submit {
                    try { respond(socket) } catch (e: Exception) { if (!closed) errors += e.toString() }
                    finally { sockets.remove(socket) }
                }
            }
        }
        private fun respond(socket: Socket) = socket.use {
            socket.soTimeout = 10000; val input = socket.getInputStream().buffered()
            fun line(): String {
                val bytes = ArrayList<Byte>()
                while (true) { val n = input.read(); check(n >= 0); if (n == 10) break; if (n != 13) bytes += n.toByte() }
                return bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            val request = line().split(' '); check(request[0] == "GET")
            val headers = mutableMapOf<String, String>()
            while (true) { val header = line(); if (header.isEmpty()) break
                headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim() }
            check((headers["content-length"] ?: "0").toInt() == 0)
            val get = Get.entries.single { it.path == request[1] }
            records += Record(get.path, request[0], headers["authorization"], "")
            val index = sequence.incrementAndGet()
            val text = if (index == emptyIndex) """{"items":[]}""" else get.body(index)
            val status = if (index == failureIndex) 503 else 200
            if (index == holdIndex) { started.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
            val body = text.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $status Synthetic\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(body); flush() }
        }
        override fun close() {
            closed = true; listener.close(); sockets.forEach { runCatching { it.close() } }
            workers.shutdownNow(); check(workers.awaitTermination(10, TimeUnit.SECONDS)); accept.join(10000); check(!accept.isAlive)
            assertTrue(errors.toString(), errors.isEmpty())
        }
    }
}
