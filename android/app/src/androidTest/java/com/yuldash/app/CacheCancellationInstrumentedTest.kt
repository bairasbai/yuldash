package com.yuldash.app

import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/** Actual Android HTTP/caller Job; acceptance-monitor witness, not a UI lifetime surrogate. */
@RunWith(AndroidJUnit4::class)
class CacheCancellationInstrumentedTest {
    private enum class Get(val path: String, val staticKey: String? = null) {
        ME("/me"), CONTACTS("/trusted-contacts"), FEED("/feed"), PACKAGES("/ad-packages", "ad-packages"), BOOST("/boost/plans", "boost-plans");
        fun body(v: Int) = when (this) {
            ME -> """{"id":$v,"name":"Synthetic $v","role":"role$v"}"""
            CONTACTS -> """{"items":[{"id":$v,"name":"Synthetic $v"}]}"""
            FEED -> """{"today":$v}"""
            PACKAGES -> """{"items":[{"code":"synthetic","title":"Synthetic","title_ba":"Synthetic","amount_kop":$v,"period_days":7,"extra":"preserved"}]}"""
            BOOST -> """{"items":[{"tier":"synthetic","title":"Synthetic","price":$v,"hours":24,"extra":"preserved"}]}"""
        }
        suspend fun read(): Result<Int> = when (this) {
            ME -> ApiClient.me().map { it.getInt("id") }
            CONTACTS -> ApiClient.getContacts().map { it.single().id }
            FEED -> ApiClient.getFeed().map { it.today }
            PACKAGES -> ApiClient.getAdPackages().map { it.single().amountKop }
            BOOST -> ApiClient.getBoostPlans().map { it.single().price }
        }
        fun persisted(v: Int) = JSONObject(body(v)).getJSONArray("items").toString()
    }
    private val parent = SupervisorJob()
    private val scope = CoroutineScope(parent + Dispatchers.IO)
    private lateinit var server: Loopback
    private lateinit var prefs: SharedPreferences
    @Before fun setup() {
        ApiClient.testTrace = null; ApiClient.resetForTest()
        ApiClient.init(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext)
        prefs = ApiClient::class.java.getDeclaredField("prefs").apply { isAccessible = true }.get(ApiClient) as SharedPreferences
        prefs.edit().remove("static_ad-packages").remove("static_boost-plans").commit()
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature"); ApiClient.saveName("Kept"); ApiClient.saveRole("kept")
        server = Loopback(); ApiClient.testBaseUrl = server.url; ApiClient.testTimeoutMs = 60000
    }
    @After fun cleanup() {
        parent.cancel(); runBlocking { withTimeout(10000) { parent.join() } }
        server.close(); ApiClient.testTrace = null; ApiClient.resetForTest(); ApiClient.testTimeoutMs = null
    }
    private fun read(get: Get) = runBlocking { get.read().getOrThrow() }
    private fun afterFetch(get: Get) {
        get.staticKey?.let { prefs.edit().putString("static_$it", get.persisted(10)).commit() }
        val atTrace = CountDownLatch(1); val leaveTrace = CountDownLatch(1)
        val lockHeld = CountDownLatch(1); val unlock = CountDownLatch(1)
        val worker = AtomicReference<Thread>(); val cancelled = AtomicBoolean(); val returned = AtomicBoolean(); val final = AtomicBoolean()
        val owner = ApiClient.queueSessionGeneration()
        ApiClient.testTrace = { line ->
            if (line.startsWith("ВЫШЛИ из вызова GET ${get.path} успешно")) {
                worker.set(Thread.currentThread()); atTrace.countDown(); check(leaveTrace.await(20, TimeUnit.SECONDS))
            }
        }
        val job = scope.async {
            try { get.read().also { returned.set(true) } }
            catch (ce: CancellationException) { cancelled.set(true); throw ce }
            finally { final.set(true) }
        }
        assertTrue(atTrace.await(10, TimeUnit.SECONDS))
        val holder = thread(name = "native-cache-acceptance-monitor", isDaemon = true) {
            check(ApiClient.runIfCurrentSession(owner) { lockHeld.countDown(); check(unlock.await(20, TimeUnit.SECONDS)) })
        }
        try {
            assertTrue(lockHeld.await(10, TimeUnit.SECONDS)); leaveTrace.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            fun blockedAtCache(): Boolean {
                val t = worker.get(); val frame = t.stackTrace.firstOrNull { it.className == ApiClient::class.java.name }
                return t.state == Thread.State.BLOCKED && frame?.methodName?.contains("cachedGet") == true
            }
            while (!blockedAtCache() && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000)
            assertTrue("Actual Android worker must be blocked at cachedGet after fetch", blockedAtCache())
            job.cancel(); assertTrue(job.isCancelled); assertFalse(job.isCompleted); assertFalse(final.get())
            unlock.countDown(); holder.join(10000); assertFalse(holder.isAlive)
            runBlocking { withTimeout(10000) { job.join() } }; ApiClient.testTrace = null
            assertTrue(job.isCompleted); assertTrue(final.get())
            get.staticKey?.let { assertEquals("Cancelled acceptance must preserve stored tariffs", get.persisted(10), prefs.getString("static_$it", null)) }
            if (get == Get.ME) { assertEquals("Kept", ApiClient.cachedName()); assertEquals("kept", ApiClient.cachedRole()) }
            server.version.set(2); assertEquals("Cancelled late response must not seed shared cache", 2, read(get))
            assertEquals(listOf(get.path, get.path), server.records.toList())
            assertTrue("API must throw cancellation", cancelled.get()); assertFalse(returned.get()); assertTrue(server.errors.toString(), server.errors.isEmpty())
        } finally { leaveTrace.countDown(); unlock.countDown(); holder.join(10000) }
    }
    private fun warm(get: Get) {
        assertEquals(1, read(get)); val cancelled = AtomicBoolean(); val returned = AtomicBoolean()
        val job = scope.async(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            try { get.read(); returned.set(true) } catch (ce: CancellationException) { cancelled.set(true); throw ce }
        }
        runBlocking { job.join() }; assertTrue(job.isCompleted); assertTrue(job.isCancelled)
        assertTrue("Warm API must throw cancellation", cancelled.get()); assertFalse(returned.get())
        assertEquals(1, read(get)); assertEquals(listOf(get.path), server.records.toList()); assertTrue(server.errors.toString(), server.errors.isEmpty())
    }
    @Test fun lateMe() = afterFetch(Get.ME)
    @Test fun lateContacts() = afterFetch(Get.CONTACTS)
    @Test fun lateFeed() = afterFetch(Get.FEED)
    @Test fun latePackages() = afterFetch(Get.PACKAGES)
    @Test fun lateBoost() = afterFetch(Get.BOOST)
    @Test fun warmMe() = warm(Get.ME)
    @Test fun warmContacts() = warm(Get.CONTACTS)
    @Test fun warmFeed() = warm(Get.FEED)
    private class Loopback : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool { r -> Thread(r, "cache-http").apply { isDaemon = true } }
        private val sockets = CopyOnWriteArrayList<Socket>()
        val records = CopyOnWriteArrayList<String>(); val errors = CopyOnWriteArrayList<String>(); val version = AtomicInteger(1)
        @Volatile private var closed = false
        val url = "http://127.0.0.1:${listener.localPort}"
        private val accept = thread(name = "cache-http-accept", isDaemon = true) {
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
            while (line().isNotEmpty()) { /* Drain request headers. */ }
            val get = Get.entries.single { it.path == request[1] }; records += get.path
            val body = get.body(version.get()).toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(body); flush() }
        }
        override fun close() {
            closed = true; listener.close(); sockets.forEach { runCatching { it.close() } }
            workers.shutdownNow(); check(workers.awaitTermination(10, TimeUnit.SECONDS)); accept.join(10000); check(!accept.isAlive)
        }
    }
}
