package com.yuldash.app.data

import android.app.Application
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

/** Real mutation API, held GET, joined caller; invalidation must survive removal of the cached entry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientCacheInvalidationTest {
    private enum class Get(val path: String) {
        ME("/me"), REFERRAL("/referral/me");
        fun body(v: Int): String = when (this) {
            ME -> """{"version":$v,"name":"Snapshot $v","role":"role$v"}"""
            REFERRAL -> """{"code":"synthetic$v","credits":$v}"""
        }
        suspend fun read(): Result<Int> = when (this) {
            ME -> ApiClient.me().map { it.getInt("version") }
            REFERRAL -> ApiClient.getReferral().map { it.credits }
        }
    }
    private enum class Mutation(val get: Get, val path: String, val field: String, val value: Any) {
        NAME(Get.ME, "/me/update", "name", "Mutation 3"), AVATAR(Get.ME, "/me/update", "avatar_url", "synthetic-avatar"),
        CITY(Get.ME, "/me/update", "city", "Synthetic city"), GENDER(Get.ME, "/me/update", "gender", "female"),
        LANGUAGE(Get.ME, "/me/update", "language", "ba"),
        REDEEM(Get.REFERRAL, "/referral/redeem", "code", "SYNTHETIC"), FREE_BOOST(Get.REFERRAL, "/boost/free", "ride_id", 7)
    }
    private data class Record(val method: String?, val path: String, val bearer: String?, val body: String)
    private val parent = SupervisorJob()
    private val scope = CoroutineScope(parent + Dispatchers.IO)
    private lateinit var server: MockWebServer
    private val records = CopyOnWriteArrayList<Record>()
    private val started = CountDownLatch(1); private val release = CountDownLatch(1)
    private val heldOnce = AtomicBoolean()
    private val me = AtomicInteger(1); private val referral = AtomicInteger(1)
    private val token = "header.eyJzdWIiOiIxMSJ9.signature"
    private var owner = 0L
    @Volatile private var mutationStatus = 200
    @Volatile private var changedName = false
    private fun state(get: Get) = if (get == Get.ME) me else referral
    @Before fun setup() {
        ApiClient.testTrace = null; ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>()); ApiClient.saveToken(token)
        ApiClient.saveName("Kept"); ApiClient.saveRole("kept"); owner = ApiClient.queueSessionGeneration()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val r = Record(request.method, request.requestUrl!!.encodedPath, request.getHeader("Authorization"), request.body.readUtf8()); records += r
                    var body: String; var status = 200
                    if (r.method == "GET") {
                        val get = Get.entries.single { it.path == r.path }; val v = state(get).get()
                        body = get.body(v)
                        if (get == Get.ME && v == 3 && changedName) body = JSONObject(body).put("name", "Mutation 3").toString()
                        if (heldOnce.compareAndSet(false, true)) { started.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
                    } else {
                        check(r.method == "POST"); status = mutationStatus
                        if (status == 200) {
                            if (r.path == "/me/update") { me.set(3); changedName = JSONObject(r.body).has("name") }
                            else { check(r.path in listOf("/referral/redeem", "/boost/free")); referral.set(3) }
                        }
                        body = """{"credits":3}"""
                    }
                    return MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
    }
    @After fun cleanup() {
        release.countDown(); ApiClient.testTrace = null; parent.cancel()
        runBlocking { withTimeout(10000) { parent.join() } }; ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
    }
    private fun mutate(m: Mutation): Result<*>? = when (m) {
        Mutation.NAME -> runBlocking { ApiClient.updateName("Mutation 3") }
        Mutation.AVATAR -> runBlocking { ApiClient.updateAvatar("synthetic-avatar") }
        Mutation.CITY -> runBlocking { ApiClient.updateCity(" Synthetic city ") }
        Mutation.GENDER -> runBlocking { ApiClient.updateGender(" Female ") }
        Mutation.REDEEM -> runBlocking { ApiClient.redeemReferral("SYNTHETIC") }
        Mutation.FREE_BOOST -> runBlocking { ApiClient.boostFree(7) }
        Mutation.LANGUAGE -> {
            val entered = CountDownLatch(1); val leaveTrace = CountDownLatch(1)
            ApiClient.testTrace = { line -> if (line == "вошли в вызов POST /me/update") { entered.countDown(); check(leaveTrace.await(20, TimeUnit.SECONDS)) } }
            try {
                val bg = ApiClient::class.java.getDeclaredField("bg").apply { isAccessible = true }.get(ApiClient) as CoroutineScope
                // Сброс прошлого теста отменяет задачи общего `bg`, но не ждёт их: задача, застрявшая в сети,
                // ещё числится среди детей. Берём ровно ту, что создал этот вызов, — по разнице до и после.
                val before = bg.coroutineContext[Job]!!.children.toSet()
                ApiClient.fireUpdateLanguage(" BA "); assertTrue(entered.await(10, TimeUnit.SECONDS))
                val child = (bg.coroutineContext[Job]!!.children.toSet() - before).single()
                assertTrue(child.isActive); assertFalse(child.isCompleted); leaveTrace.countDown()
                runBlocking { withTimeout(10000) { child.join() } }; assertTrue(child.isCompleted); assertFalse(child.isCancelled)
                null // Public fire-and-forget returns Unit: no fabricated Result.failure/success.
            } finally { leaveTrace.countDown(); ApiClient.testTrace = null }
        }
    }
    private fun read(get: Get) = runBlocking { get.read().getOrThrow() }
    private fun start(get: Get): Deferred<Result<Int>> {
        val old = scope.async { get.read() }; assertTrue(started.await(10, TimeUnit.SECONDS)); assertFalse(old.isCompleted); return old
    }
    private fun finish(old: Deferred<Result<Int>>) {
        release.countDown(); runBlocking { withTimeout(10000) { old.join() } }
        assertTrue(old.isCompleted); assertFalse(old.isCancelled); assertEquals(1, runBlocking { old.await().getOrThrow() })
    }
    private fun wire(m: Mutation, gets: Int, get: Get = m.get) {
        assertEquals(gets + 1, records.size); val mutation = records.single { it.method == "POST" }
        assertEquals(m.path, mutation.path); val payload = JSONObject(mutation.body)
        assertEquals(1, payload.length()); assertEquals(m.value, payload.get(m.field))
        assertEquals(List(gets) { get.path }, records.filter { it.method == "GET" }.map { it.path })
        assertTrue(records.filter { it.method == "GET" }.all { it.body.isEmpty() })
        assertEquals(List(records.size) { "Bearer $token" }, records.map { it.bearer }); assertEquals(owner, ApiClient.queueSessionGeneration())
    }
    private fun success(m: Mutation, warmBefore: Boolean, freshAfter: Boolean = false) {
        val old = start(m.get)
        if (warmBefore) { state(m.get).set(2); assertEquals(2, read(m.get)) }
        val result = mutate(m); if (result != null) assertTrue(result.toString(), result.isSuccess)
        if (freshAfter) assertEquals(3, read(m.get))
        val name = ApiClient.cachedName(); val role = ApiClient.cachedRole()
        finish(old)
        if (m.get == Get.ME) { assertEquals("Old onValue must not overwrite accepted profile/name mutation", name, ApiClient.cachedName()); assertEquals(role, ApiClient.cachedRole()) }
        assertEquals("Invalidation must prevent old GET from refilling the cache", 3, read(m.get)); assertEquals(3, read(m.get))
        wire(m, if (warmBefore) 3 else 2)
    }
    private fun failure(m: Mutation, warmBefore: Boolean) {
        val old = start(m.get)
        if (warmBefore) { state(m.get).set(2); assertEquals(2, read(m.get)) }
        mutationStatus = 503; val result = mutate(m)
        if (result != null) assertTrue(result.toString(), result.isFailure)
        finish(old); assertEquals(if (warmBefore) 2 else 1, read(m.get)); assertEquals(if (warmBefore) 2 else 1, read(m.get))
        wire(m, if (warmBefore) 2 else 1)
    }
    @Test fun nameEvictionAfterNewerRead() = success(Mutation.NAME, true)
    @Test fun avatarEvictionAfterNewerRead() = success(Mutation.AVATAR, true)
    @Test fun cityEvictionAfterNewerRead() = success(Mutation.CITY, true)
    @Test fun genderEvictionAfterNewerRead() = success(Mutation.GENDER, true)
    @Test fun languageEvictionAfterNewerRead() = success(Mutation.LANGUAGE, true)
    @Test fun redeemEvictionAfterNewerRead() = success(Mutation.REDEEM, true)
    @Test fun boostEvictionAfterNewerRead() = success(Mutation.FREE_BOOST, true)
    @Test fun nameInvalidationBeforeFreshRead() = success(Mutation.NAME, false)
    @Test fun avatarInvalidationBeforeFreshRead() = success(Mutation.AVATAR, false)
    @Test fun cityInvalidationBeforeFreshRead() = success(Mutation.CITY, false)
    @Test fun genderInvalidationBeforeFreshRead() = success(Mutation.GENDER, false)
    @Test fun languageInvalidationBeforeFreshRead() = success(Mutation.LANGUAGE, false)
    @Test fun redeemInvalidationBeforeFreshRead() = success(Mutation.REDEEM, false)
    @Test fun boostInvalidationBeforeFreshRead() = success(Mutation.FREE_BOOST, false)
    @Test fun failedNameKeepsWarmCache() = failure(Mutation.NAME, true)
    @Test fun failedAvatarKeepsWarmCache() = failure(Mutation.AVATAR, true)
    @Test fun failedCityKeepsWarmCache() = failure(Mutation.CITY, true)
    @Test fun failedGenderKeepsWarmCache() = failure(Mutation.GENDER, true)
    @Test fun failedLanguageKeepsWarmCache() = failure(Mutation.LANGUAGE, true)
    @Test fun failedRedeemKeepsWarmCache() = failure(Mutation.REDEEM, true)
    @Test fun failedBoostKeepsWarmCache() = failure(Mutation.FREE_BOOST, true)
    @Test fun failedNameAllowsOlderSuccessfulRead() = failure(Mutation.NAME, false)
    @Test fun failedAvatarAllowsOlderSuccessfulRead() = failure(Mutation.AVATAR, false)
    @Test fun failedCityAllowsOlderSuccessfulRead() = failure(Mutation.CITY, false)
    @Test fun failedGenderAllowsOlderSuccessfulRead() = failure(Mutation.GENDER, false)
    @Test fun failedLanguageAllowsOlderSuccessfulRead() = failure(Mutation.LANGUAGE, false)
    @Test fun failedRedeemAllowsOlderSuccessfulRead() = failure(Mutation.REDEEM, false)
    @Test fun failedBoostAllowsOlderSuccessfulRead() = failure(Mutation.FREE_BOOST, false)
    @Test fun freshProfileAfterMutationStaysAccepted() = success(Mutation.NAME, true, true)
    @Test fun freshReferralAfterMutationStaysAccepted() = success(Mutation.REDEEM, true, true)
    private fun independent(get: Get, m: Mutation) {
        val old = start(get); assertTrue(mutate(m)!!.isSuccess); finish(old); assertEquals(1, read(get)); wire(m, 1, get)
    }
    @Test fun referralMutationDoesNotInvalidateHeldProfile() = independent(Get.ME, Mutation.REDEEM)
    @Test fun profileMutationDoesNotInvalidateHeldReferral() = independent(Get.REFERRAL, Mutation.NAME)
    @Test fun invalidGenderDoesNotInvalidateHeldProfile() {
        val old = start(Get.ME); assertTrue(runBlocking { ApiClient.updateGender("not-a-gender") }.isSuccess)
        finish(old); assertEquals(1, read(Get.ME)); assertEquals(1, records.size); assertEquals("GET", records.single().method)
    }
}
