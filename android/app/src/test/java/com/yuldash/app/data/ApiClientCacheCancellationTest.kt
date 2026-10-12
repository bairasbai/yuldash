package com.yuldash.app.data

import android.app.Application
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/** Real public API, HTTP and cancelled IO Job; join precedes cache/disk probes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientCacheCancellationTest {
    private enum class Get(val path: String, val staticKey: String? = null) {
        ME("/me"), CONTACTS("/trusted-contacts"), REFERRAL("/referral/me"),
        POPULAR("/popular-routes"), MY_ROUTES("/my-routes"), FEED("/feed"),
        PACKAGES("/ad-packages", "ad-packages"), BOOST("/boost/plans", "boost-plans"), SETTLEMENTS("/settlements/popular-routes");
        fun body(v: Int): String = when (this) {
            ME -> """{"name":"Synthetic $v","role":"role$v"}"""
            CONTACTS -> """{"items":[{"id":$v,"name":"Synthetic $v"}]}"""
            REFERRAL -> """{"code":"$v","credits":$v}"""
            POPULAR, MY_ROUTES -> """{"items":[{"from_city":"Synthetic","to_city":"Synthetic","count":$v}]}"""
            FEED -> """{"today":$v}"""
            PACKAGES -> """{"items":[{"code":"synthetic","title":"Synthetic","title_ba":"Synthetic","amount_kop":$v,"period_days":7,"extra":"preserved"}]}"""
            BOOST -> """{"items":[{"tier":"synthetic","title":"Synthetic","price":$v,"hours":24,"extra":"preserved"}]}"""
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
    private val parent = SupervisorJob()
    private val scope = CoroutineScope(parent + Dispatchers.IO)
    private lateinit var server: MockWebServer
    private lateinit var prefs: SharedPreferences
    private val records = CopyOnWriteArrayList<String>()
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val hold = AtomicBoolean(false)
    private val version = AtomicInteger(1)
    @Volatile private var malformed = false
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    @Before fun setup() {
        ApiClient.testTrace = null; ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        prefs = ApiClient::class.java.getDeclaredField("prefs").apply { isAccessible = true }.get(ApiClient) as SharedPreferences
        prefs.edit().remove("static_ad-packages").remove("static_boost-plans").commit()
        ApiClient.saveToken(tokenA); ApiClient.saveName("Kept"); ApiClient.saveRole("kept")
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val get = Get.entries.single { it.path == request.requestUrl!!.encodedPath }
                    check(request.method == "GET"); records += get.path
                    val snapshot = version.get(); val bad = malformed
                    if (hold.compareAndSet(true, false)) { started.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(if (bad) """{"items":[7]}""" else get.body(snapshot))
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
    }
    @After fun cleanup() {
        release.countDown(); parent.cancel()
        runBlocking { withTimeout(10000) { parent.join() } }
        ApiClient.testTrace = null; ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
    }
    private fun read(get: Get) = runBlocking { get.read().getOrThrow() }
    private fun held(get: Get, atTrace: Boolean = false, newOwner: Boolean = false) {
        get.staticKey?.let { prefs.edit().putString("static_$it", get.persisted(10)).commit() }
        val cancelled = AtomicBoolean(); val returned = AtomicBoolean(); val final = AtomicBoolean()
        val jobRef = AtomicReference<Job>(); val trace = CountDownLatch(1)
        if (atTrace) ApiClient.testTrace = { line ->
            if (line.startsWith("ВЫШЛИ из вызова GET ${get.path} успешно")) { jobRef.get().cancel(); trace.countDown() }
        }
        hold.set(true)
        val job = scope.async {
            try { get.read().also { returned.set(true) } }
            catch (ce: CancellationException) { cancelled.set(true); throw ce }
            finally { final.set(true) }
        }
        jobRef.set(job); assertTrue(started.await(10, TimeUnit.SECONDS)); assertFalse(job.isCompleted)
        if (newOwner) { ApiClient.saveToken(tokenB); version.set(3); assertEquals(3, read(get)) }
        if (!atTrace) { job.cancel(); assertTrue(job.isCancelled); assertFalse(job.isCompleted) }
        version.set(2); release.countDown()
        runBlocking { withTimeout(10000) { job.join() } }
        ApiClient.testTrace = null
        assertTrue(final.get()); assertTrue(job.isCompleted); assertTrue(job.isCancelled)
        if (atTrace) assertEquals(0L, trace.count)
        get.staticKey?.let { assertEquals("Cancelled read must not overwrite persisted tariffs", get.persisted(if (newOwner) 3 else 10), prefs.getString("static_$it", null)) }
        if (get == Get.ME && !newOwner) { assertEquals("Kept", ApiClient.cachedName()); assertEquals("kept", ApiClient.cachedRole()) }
        assertEquals("Cancelled read must not fill the shared cache", if (newOwner) 3 else 2, read(get))
        assertEquals(listOf(get.path, get.path), records.toList())
        assertTrue("Cancellation must be thrown, not returned as Result", cancelled.get()); assertFalse(returned.get())
    }
    private fun warm(get: Get) {
        assertEquals(1, read(get)); val cancelled = AtomicBoolean(); val returned = AtomicBoolean()
        val job = scope.async(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            try { get.read(); returned.set(true) } catch (ce: CancellationException) { cancelled.set(true); throw ce }
        }
        runBlocking { job.join() }; assertTrue(job.isCompleted); assertTrue(job.isCancelled)
        assertTrue("Warm cache must respect an already cancelled caller", cancelled.get()); assertFalse(returned.get())
        assertEquals(1, read(get)); assertEquals(listOf(get.path), records.toList())
    }
    private fun active(get: Get) {
        assertEquals(1, read(get)); version.set(2); assertEquals(1, read(get)); assertEquals(listOf(get.path), records.toList())
        get.staticKey?.let { assertEquals(get.persisted(1), prefs.getString("static_$it", null)) }
        if (get == Get.ME) { assertEquals("Synthetic 1", ApiClient.cachedName()); assertEquals("role1", ApiClient.cachedRole()) }
    }
    private fun owner(get: Get) {
        hold.set(true); val job = scope.async { get.read() }; assertTrue(started.await(10, TimeUnit.SECONDS))
        ApiClient.saveToken(tokenB); version.set(3); assertEquals(3, read(get)); release.countDown()
        val result = runBlocking { job.await() }; assertTrue(result.isFailure); assertTrue(result.exceptionOrNull() is IllegalStateException)
        get.staticKey?.let { assertEquals(get.persisted(3), prefs.getString("static_$it", null)) }
        assertEquals(3, read(get)); assertEquals(listOf(get.path, get.path), records.toList())
    }
    private fun invalid(get: Get) {
        prefs.edit().putString("static_${get.staticKey}", get.persisted(10)).commit(); malformed = true
        val error = runCatching { read(get) }.exceptionOrNull(); assertNotNull(error)
        assertEquals("Invalid parsed tariffs must not overwrite disk", get.persisted(10), prefs.getString("static_${get.staticKey}", null))
        malformed = false; version.set(2); assertEquals(2, read(get)); assertEquals(2, records.size)
    }
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
        val holder = thread(name = "cache-acceptance-monitor", isDaemon = true) {
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
            assertTrue("Actual IO worker must wait at cachedGet acceptance monitor after fetch: ${worker.get().state} ${worker.get().stackTrace.take(8).toList()}", blockedAtCache())
            println("CACHE_ACCEPTANCE_WITNESS ${get.path} ${worker.get().state} ${worker.get().stackTrace.first { it.className == ApiClient::class.java.name }}")
            job.cancel(); assertTrue(job.isCancelled); assertFalse(job.isCompleted); assertFalse(final.get())
            unlock.countDown(); holder.join(10000); assertFalse(holder.isAlive)
            runBlocking { withTimeout(10000) { job.join() } }; ApiClient.testTrace = null
            assertTrue(job.isCompleted); assertTrue(final.get())
            get.staticKey?.let { assertEquals("Cancelled acceptance must not publish tariffs to disk", get.persisted(10), prefs.getString("static_$it", null)) }
            if (get == Get.ME) { assertEquals("Kept", ApiClient.cachedName()); assertEquals("kept", ApiClient.cachedRole()) }
            version.set(2); assertEquals("Late cancelled fetch must not seed shared cache", 2, read(get))
            assertEquals(listOf(get.path, get.path), records.toList())
            assertTrue("Cancellation must be thrown at acceptance", cancelled.get()); assertFalse(returned.get())
        } finally { leaveTrace.countDown(); unlock.countDown(); holder.join(10000) }
    }
    @Test fun afterFetchMe() = afterFetch(Get.ME)
    // Was afterFetch(CONTACTS): it proved the window by finding a `cachedGet` frame BLOCKED on ApiClient's monitor
    // in the IO thread's stack. Contacts are accepted by ContactsRepository on the caller after the HTTP returns,
    // so the caller's continuation is paused instead: the HTTP has completed, the job is cancelled before the
    // caller resumes, and nothing may reach the shared state. (Cancellation lands at the resume itself; the
    // repository's own ensureActive under its lock guards the narrower window after resume.)
    @Test fun afterFetchContacts() {
        val paused = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val caller = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { paused.add(block) }
        }
        val cancelled = AtomicBoolean(); val returned = AtomicBoolean(); val final = AtomicBoolean()
        val job = scope.async(caller) {
            try { Get.CONTACTS.read().also { returned.set(true) } }
            catch (ce: CancellationException) { cancelled.set(true); throw ce }
            finally { final.set(true) }
        }
        paused.poll(10, TimeUnit.SECONDS)!!.run() // enter the read; HTTP runs on IO
        val afterHttp = paused.poll(10, TimeUnit.SECONDS)!! // the HTTP has returned; acceptance has not run yet
        assertEquals(listOf(Get.CONTACTS.path), records.toList()); assertFalse(final.get())
        job.cancel(); assertTrue(job.isCancelled); assertFalse(job.isCompleted)
        afterHttp.run()
        while (!job.isCompleted) paused.poll(10, TimeUnit.SECONDS)!!.run()
        assertTrue(final.get())
        version.set(2); assertEquals("Late cancelled fetch must not seed shared state", 2, read(Get.CONTACTS))
        assertEquals(listOf(Get.CONTACTS.path, Get.CONTACTS.path), records.toList())
        assertTrue("Cancellation must be thrown at acceptance", cancelled.get()); assertFalse(returned.get())
    }
    @Test fun afterFetchReferral() = afterFetch(Get.REFERRAL)
    @Test fun afterFetchPopular() = afterFetch(Get.POPULAR)
    @Test fun afterFetchMyRoutes() = afterFetch(Get.MY_ROUTES)
    @Test fun afterFetchFeed() = afterFetch(Get.FEED)
    @Test fun afterFetchPackages() = afterFetch(Get.PACKAGES)
    @Test fun afterFetchBoost() = afterFetch(Get.BOOST)
    @Test fun afterFetchSettlements() = afterFetch(Get.SETTLEMENTS)
    @Test fun heldMe() = held(Get.ME)
    @Test fun heldContacts() = held(Get.CONTACTS)
    @Test fun heldReferral() = held(Get.REFERRAL)
    @Test fun heldPopular() = held(Get.POPULAR)
    @Test fun heldMyRoutes() = held(Get.MY_ROUTES)
    @Test fun heldFeed() = held(Get.FEED)
    @Test fun heldPackages() = held(Get.PACKAGES)
    @Test fun heldBoost() = held(Get.BOOST)
    @Test fun heldSettlements() = held(Get.SETTLEMENTS)
    @Test fun traceCancelledPackages() = held(Get.PACKAGES, atTrace = true)
    @Test fun traceCancelledBoost() = held(Get.BOOST, atTrace = true)
    @Test fun warmMe() = warm(Get.ME)
    @Test fun warmContacts() = warm(Get.CONTACTS)
    @Test fun warmReferral() = warm(Get.REFERRAL)
    @Test fun warmPopular() = warm(Get.POPULAR)
    @Test fun warmMyRoutes() = warm(Get.MY_ROUTES)
    @Test fun warmFeed() = warm(Get.FEED)
    @Test fun warmPackages() = warm(Get.PACKAGES)
    @Test fun warmBoost() = warm(Get.BOOST)
    @Test fun warmSettlements() = warm(Get.SETTLEMENTS)
    @Test fun activeMe() = active(Get.ME)
    @Test fun activeContacts() = active(Get.CONTACTS)
    @Test fun activeReferral() = active(Get.REFERRAL)
    @Test fun activePopular() = active(Get.POPULAR)
    @Test fun activeMyRoutes() = active(Get.MY_ROUTES)
    @Test fun activeFeed() = active(Get.FEED)
    @Test fun activePackages() = active(Get.PACKAGES)
    @Test fun activeBoost() = active(Get.BOOST)
    @Test fun activeSettlements() = active(Get.SETTLEMENTS)
    @Test fun oldOwnerMe() = owner(Get.ME)
    @Test fun oldOwnerPackages() = owner(Get.PACKAGES)
    @Test fun oldOwnerBoost() = owner(Get.BOOST)
    @Test fun invalidPackagesKeepDisk() = invalid(Get.PACKAGES)
    @Test fun invalidBoostKeepDisk() = invalid(Get.BOOST)
    private fun reinitialized(get: Get) {
        assertEquals(1, read(get)); assertEquals(get.persisted(1), prefs.getString("static_${get.staticKey}", null))
        version.set(2); ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        assertEquals("Accepted persisted raw tariffs must seed the existing typed cache", 1, read(get))
        assertEquals(listOf(get.path), records.toList())
    }
    @Test fun reinitializedPackagesReadStoredCache() = reinitialized(Get.PACKAGES)
    @Test fun reinitializedBoostReadStoredCache() = reinitialized(Get.BOOST)
    @Test fun cancelledFreshContactsKeepWarmCache() {
        assertEquals(1, read(Get.CONTACTS)); version.set(2); val cancelled = AtomicBoolean()
        val job = scope.async(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            try { TestContacts.read(force = true) }
            catch (ce: CancellationException) { cancelled.set(true); throw ce }
        }
        runBlocking { job.join() }; assertTrue(cancelled.get()); assertTrue(job.isCancelled)
        assertEquals("Cancelled forceRefresh must not evict warm contacts", 1, read(Get.CONTACTS)); assertEquals(listOf(Get.CONTACTS.path), records.toList())
    }
}
