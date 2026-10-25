package com.yuldash.app.data

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import com.yuldash.app.Screen
import com.yuldash.app.YuldashViewModel
import com.yuldash.app.applyBookingLink
import com.yuldash.app.readBookingNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.model.MultipleFailureException
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.ServerSocket
import java.net.InetAddress
import java.net.ConnectException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Actual loopback GET/refresh/ack contracts. Never calls a real backend or launches location. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineTripSnapshotApiTest {
    private lateinit var server: MockWebServer
    private lateinit var auth: MemoryDiskPreferences
    private lateinit var disk: SnapshotTestDisk
    private val time = SnapshotClock(3, 1000, 1000, 1000)
    private val epoch = "2026-10-24T12:00:00Z"
    private fun jwt(id: Int = 7) = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$id"}""".toByteArray()) + ".test"
    private fun field(name: String, value: Any?) = ApiClient::class.java.getDeclaredField(name).apply { isAccessible = true }.set(ApiClient, value)
    private fun secureOwner() = requireNotNull(OfflineTripSnapshots.owner.value)
    private data class Served(val phase: String, val method: String?, val path: String?, val code: Int?)
    private val served = CopyOnWriteArrayList<Served>()
    private var refusedAttempts = 0
    private fun scenario(phase: String, respond: (RecordedRequest) -> MockResponse) {
        // A response belongs to its request/phase. A timed-out request cannot consume a later enqueue.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = respond(request).setHeader("Connection", "close")
                val code = if (response.socketPolicy == SocketPolicy.DISCONNECT_AFTER_REQUEST) null
                    else response.status.substringAfter(' ').substringBefore(' ').toInt()
                served += Served(phase, request.method, request.path, code)
                println("snapshot-api-evidence phase=$phase method=${request.method} path=${request.path} status=${code ?: "wire-close"}")
                return response
            }
        }
    }
    private suspend fun <T> withRefusedEndpoint(action: suspend () -> T): T {
        val unavailablePort = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val previous = ApiClient.testBaseUrl
        ApiClient.testBaseUrl = "http://127.0.0.1:$unavailablePort"
        refusedAttempts++
        println("snapshot-api-evidence phase=refused-$refusedAttempts method=GET path=/bookings/11/role status=wire-refused port=$unavailablePort")
        try { return action() } finally { ApiClient.testBaseUrl = previous }
    }
    private suspend fun warm(role: String = "passenger") {
        OfflineTripSnapshots.details(OfflineTripSnapshots.ticket(), parseBookingDetailsDto(org.json.JSONObject(details).put("role", role)))
        assertNotNull(OfflineTripSnapshots.load(secureOwner(), 11))
    }
    private val details get() = """{"booking_id":11,"ride_id":22,"role":"passenger","status":"onboard","onboard_at":"$epoch","from_city":"Уфа","to_city":"Мелеуз","depart_at":"2026-10-25T10:00:00","pickup_stop":"Стерлитамак","dropoff_stop":"Салават","ride_has_stops":true}"""
    private fun role() = MockResponse().setBody("""{"role":"passenger","status":"onboard","onboard_at":"$epoch"}""")

    @Before fun setup() {
        ApiClient.resetForTest()
        auth = MemoryDiskPreferences()
        field("appCtx", null); field("prefs", auth); field("plainAuthPrefs", auth); field("secureAuthPrefs", null); field("authStoreSource", "plain")
        disk = SnapshotTestDisk()
        OfflineTripSnapshots.installForTest(disk) { time }
        ApiClient.saveToken(jwt())
        server = MockWebServer().apply { start() }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 500
    }
    @After fun cleanup() {
        field("token", null); field("refreshToken", null)
        ApiClient.logout(); ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; ApiClient.testRetryTimeoutMs = null
        server.shutdown()
    }

    @Test fun failedVerifyCodeCredentialsCommitSuspendsOnlySnapshotTrustUntilDurableLoginAndOwnRead() = runBlocking {
        failedNewAuthWrite("verifyCode-credentials")
    }
    @Test fun failedSaveTokenFinalMarkerSuspendsOnlySnapshotTrustUntilDurableLoginAndOwnRead() = runBlocking {
        failedNewAuthWrite("saveToken-final-marker")
    }
    private suspend fun failedNewAuthWrite(kind: String) {
        fun authResponse(id: Int, name: String) = MockResponse().setBody(
            """{"access_token":"${jwt(id)}","refresh_token":"refresh-$id","user":{"name":"$name","role":"passenger"}}"""
        )
        scenario("$kind/login-A") { request ->
            if (request.path == "/auth/verify") authResponse(7, "Name A") else MockResponse().setResponseCode(404)
        }
        ApiClient.verifyCode("+70000000000", "000000", "Fallback").getOrThrow()
        val ownerA = secureOwner()
        val generationA = ApiClient.queueSessionGeneration()
        assertEquals(7, ownerA.userId)
        assertEquals(ownerA.scope, auth.restarted().getString(OfflineTripSnapshots.AUTH_SCOPE, null))
        scenario("$kind/warm-A") { request ->
            if (request.path == "/bookings/11/details") MockResponse().setBody(details) else MockResponse().setResponseCode(404)
        }
        ApiClient.getBookingDetails(11).getOrThrow()
        val shownA = requireNotNull(OfflineTripSnapshots.load(ownerA, 11))
        assertEquals("onboard", shownA.state.status)
        val shownRoute = requireNotNull(shownA.route)
        assertEquals("Уфа", shownRoute.from); assertEquals("Мелеуз", shownRoute.to)
        assertEquals("Стерлитамак", shownRoute.pickup); assertEquals("Салават", shownRoute.dropoff)
        val versionA = OfflineTripSnapshots.version.value
        val failedCommits = mutableListOf<String>()
        val failingAuth = object : SharedPreferences by auth {
            override fun edit(): SharedPreferences.Editor {
                val editor = auth.edit()
                var marker: String? = null
                var credentials = false
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        if (key == AuthStorePolicy.STATE_KEY) marker = value
                        if (key == "token" && value != null) credentials = true
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        val fail = if (kind == "verifyCode-credentials") credentials else marker == "plain"
                        if (!fail) return editor.commit()
                        assertEquals("The pending marker must really reach disk before the injected refusal", "pending",
                            auth.restarted().getString(AuthStorePolicy.STATE_KEY, null))
                        val key = if (credentials) "token" else AuthStorePolicy.STATE_KEY
                        auth.failWriteOf = key
                        val committed = try { editor.commit() } finally { auth.failWriteOf = null }
                        assertFalse(committed)
                        failedCommits += if (credentials) "credentials" else "final-marker"
                        println("snapshot-authwrite-evidence kind=$kind stage=${failedCommits.last()} commit=false durableMarker=pending")
                        return committed
                    }
                }
            }
        }
        field("prefs", failingAuth); field("plainAuthPrefs", failingAuth)
        try {
            scenario("$kind/failed-auth-B") { request ->
                if (request.path == "/auth/verify") authResponse(8, "Name B") else MockResponse().setResponseCode(404)
            }
            val requestsBefore = server.requestCount
            if (kind == "verifyCode-credentials") {
                assertTrue(ApiClient.verifyCode("+70000000000", "000000", "Fallback").exceptionOrNull() is SessionPersistenceException)
                assertTrue(served.contains(Served("$kind/failed-auth-B", "POST", "/auth/verify", 200)))
            } else {
                assertTrue(runCatching { ApiClient.saveToken(jwt(8)) }.exceptionOrNull() is IllegalStateException)
                assertEquals("saveToken is local and must not send HTTP", requestsBefore, server.requestCount)
            }
        } finally {
            field("prefs", auth); field("plainAuthPrefs", auth)
        }
        assertEquals(listOf(if (kind == "verifyCode-credentials") "credentials" else "final-marker"), failedCommits)
        assertEquals("pending", auth.restarted().getString(AuthStorePolicy.STATE_KEY, null))
        assertEquals("The failure must occur at the declared durable write stage", if (kind == "verifyCode-credentials") jwt() else jwt(8),
            auth.restarted().getString("token", null))
        fun assertMemoryA() {
            assertTrue(ApiClient.isLoggedIn()); assertEquals(jwt(), ApiClient.currentToken())
            assertEquals("Name A", ApiClient.cachedName()); assertEquals("passenger", ApiClient.cachedRole())
            assertEquals(7, ApiClient.myUserId()); assertEquals(generationA, ApiClient.queueSessionGeneration())
            assertEquals("refresh-7", ApiClient::class.java.getDeclaredField("refreshToken").apply { isAccessible = true }.get(ApiClient))
        }
        assertMemoryA()
        // Continue every security observation on RED: the first hidden-owner assertion must not
        // prevent the real refused-GET or later server200/recovery phases from executing.
        val failures = mutableListOf<Throwable>()
        fun expect(assertion: () -> Unit) { try { assertion() } catch (failure: AssertionError) { failures += failure } }
        expect { assertNull("An uncertain auth commit immediately hides the shown snapshot owner", OfflineTripSnapshots.owner.value) }
        expect { assertFalse("The old frame draw guard must become false synchronously", OfflineTripSnapshots.current(shownA.owner)) }
        expect { assertTrue("The already shown frame must receive an invalidation", OfflineTripSnapshots.version.value > versionA) }
        expect { assertNull("Uncertain auth must not issue a read/fallback ticket", OfflineTripSnapshots.ticket()) }
        assertEquals("Accepted acknowledgements still retain identity A", ownerA, OfflineTripSnapshots.ackTicket()?.owner)
        val hiddenCopy = OfflineTripSnapshots.load(ownerA, 11)
        expect { assertNull("The old mode cannot reload its data", hiddenCopy) }
        val refused = withRefusedEndpoint { readBookingNavigation(11) }
        assertTrue("The failed read must be a real wire refusal", refused.live.exceptionOrNull() is ConnectException)
        expect { assertNull("Auth uncertainty never permits the old snapshot fallback", refused.saved) }
        scenario("$kind/A-still-live") { request -> when (request.path) {
            "/bookings/11/role" -> role()
            "/bookings/11/details" -> MockResponse().setBody(details)
            else -> MockResponse().setResponseCode(404)
        } }
        val online = readBookingNavigation(11)
        val live = online.live.getOrThrow()
        val vm = YuldashViewModel(SavedStateHandle())
        assertEquals(Screen.Booking, vm.applyBookingLink(11, live.role, live.status, completed = false))
        ApiClient.getBookingDetails(11).getOrThrow()
        assertMemoryA()
        assertEquals("A trip response does not confirm an interrupted auth-store write", "pending",
            auth.restarted().getString(AuthStorePolicy.STATE_KEY, null))
        expect { assertNull("Own role/details200 must not restore trust before durable auth reconfirmation", OfflineTripSnapshots.owner.value) }
        val stillRefused = withRefusedEndpoint { readBookingNavigation(11) }
        assertTrue(stillRefused.live.exceptionOrNull() is ConnectException)
        expect { assertNull("Even a successful own trip response must not unlock offline data", stillRefused.saved) }
        scenario("$kind/reconfirm-A") { request ->
            if (request.path == "/auth/verify") authResponse(7, "Name A") else MockResponse().setResponseCode(404)
        }
        ApiClient.verifyCode("+70000000000", "000000", "Fallback").getOrThrow()
        val next = requireNotNull(OfflineTripSnapshots.ackTicket()).owner
        assertEquals(7, next.userId); assertNotEquals(ownerA.scope, next.scope)
        assertTrue(next.generation > generationA)
        assertEquals("plain", auth.restarted().getString(AuthStorePolicy.STATE_KEY, null))
        assertEquals(next.scope, auth.restarted().getString(OfflineTripSnapshots.AUTH_SCOPE, null))
        expect { assertNull("Reconfirmation waits for an own read before publishing snapshot trust", OfflineTripSnapshots.owner.value) }
        scenario("$kind/recovered-own-read") { request ->
            if (request.path == "/bookings/11/details") MockResponse().setBody(details) else MockResponse().setResponseCode(404)
        }
        ApiClient.getBookingDetails(11).getOrThrow()
        assertEquals(next, secureOwner())
        assertNull("A new session must never render the previous namespace", OfflineTripSnapshots.load(ownerA, 11))
        assertEquals("onboard", OfflineTripSnapshots.load(next, 11)!!.state.status)
        val recoveredOffline = withRefusedEndpoint { readBookingNavigation(11) }
        assertTrue(recoveredOffline.live.exceptionOrNull() is ConnectException)
        assertEquals(next, recoveredOffline.saved!!.owner)
        for ((phase, path, method) in listOf(
            Triple("login-A", "/auth/verify", "POST"), Triple("warm-A", "/bookings/11/details", "GET"),
            Triple("A-still-live", "/bookings/11/role", "GET"), Triple("A-still-live", "/bookings/11/details", "GET"),
            Triple("reconfirm-A", "/auth/verify", "POST"), Triple("recovered-own-read", "/bookings/11/details", "GET")
        )) assertTrue(served.contains(Served("$kind/$phase", method, path, 200)))
        assertEquals(3, refusedAttempts)
        MultipleFailureException.assertEmpty(failures)
    }

    @Test fun pendingNewAuthWriteSurvivesSuccessfulPairRefreshUntilFullSessionAndOwnRead() = runBlocking {
        for (failure in listOf("credentials", "final-marker")) {
            // Separate selected credentials from their plain source marker, as in the secure
            // product store. A pair-only commit cannot incidentally flush the marker's RAM.
            val marker = MemoryDiskPreferences()
            field("plainAuthPrefs", marker); field("secureAuthPrefs", auth); field("authStoreSource", "secure")
            val phase = "pending-refresh-$failure"
            fun loginResponse(id: Int) = MockResponse().setBody(
                """{"access_token":"${jwt(id)}","refresh_token":"refresh-$id","user":{"name":"Name ${if (id == 7) "A" else "B"}","role":"passenger"}}"""
            )
            scenario("$phase/login-A") { request ->
                if (request.path == "/auth/verify") loginResponse(7) else MockResponse().setResponseCode(404)
            }
            ApiClient.verifyCode("+70000000000", "000000", "Fallback").getOrThrow()
            val ownerA = secureOwner()
            val generationA = ApiClient.queueSessionGeneration()
            scenario("$phase/warm-A") { request ->
                if (request.path == "/bookings/11/details") MockResponse().setBody(details) else MockResponse().setResponseCode(404)
            }
            ApiClient.getBookingDetails(11).getOrThrow()
            assertEquals("onboard", OfflineTripSnapshots.load(ownerA, 11)!!.state.status)
            assertEquals(ownerA.scope, auth.restarted().getString(OfflineTripSnapshots.AUTH_SCOPE, null))
            assertEquals("secure", marker.restarted().getString(AuthStorePolicy.STATE_KEY, null))
            var failedCommits = 0
            val failingMarker = object : SharedPreferences by marker {
                override fun edit(): SharedPreferences.Editor {
                    val editor = marker.edit()
                    var finalMarker = false
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                            finalMarker = key == AuthStorePolicy.STATE_KEY && value == "secure"
                            editor.putString(key, value)
                            return this
                        }
                        override fun commit(): Boolean {
                            if (!finalMarker) return editor.commit()
                            assertEquals("pending", marker.restarted().getString(AuthStorePolicy.STATE_KEY, null))
                            marker.failWriteOf = AuthStorePolicy.STATE_KEY
                            val saved = try { editor.commit() } finally { marker.failWriteOf = null }
                            assertFalse(saved); failedCommits++
                            return saved
                        }
                    }
                }
            }
            scenario("$phase/failed-auth-B") { request ->
                if (request.path == "/auth/verify") loginResponse(8) else MockResponse().setResponseCode(404)
            }
            if (failure == "credentials") {
                auth.failWriteOf = "token"
                try {
                    assertTrue(ApiClient.verifyCode("+70000000000", "000000", "Fallback").exceptionOrNull() is SessionPersistenceException)
                } finally { auth.failWriteOf = null }
                assertEquals(jwt(), auth.restarted().getString("token", null))
                assertTrue(served.contains(Served("$phase/failed-auth-B", "POST", "/auth/verify", 200)))
            } else {
                field("plainAuthPrefs", failingMarker)
                val requestsBefore = server.requestCount
                try { assertTrue(runCatching { ApiClient.saveToken(jwt(8)) }.exceptionOrNull() is IllegalStateException) }
                finally { field("plainAuthPrefs", marker) }
                assertEquals(1, failedCommits)
                assertEquals(requestsBefore, server.requestCount)
                assertEquals(jwt(8), auth.restarted().getString("token", null))
            }
            println("snapshot-authwrite-evidence kind=$phase stage=$failure commit=false durableMarker=pending")
            val pendingScope = requireNotNull(auth.getString(OfflineTripSnapshots.AUTH_SCOPE, null))
            assertNotEquals(ownerA.scope, pendingScope)
            assertEquals("pending", marker.restarted().getString(AuthStorePolicy.STATE_KEY, null))
            assertEquals(jwt(), ApiClient.currentToken()); assertEquals(generationA, ApiClient.queueSessionGeneration())
            assertEquals("Name A", ApiClient.cachedName()); assertEquals("passenger", ApiClient.cachedRole())
            assertNull(OfflineTripSnapshots.owner.value); assertNull(OfflineTripSnapshots.ticket())
            val rotated = jwt().substringBeforeLast('.') + ".rotated"
            val roleRequests = AtomicInteger()
            val observedBearers = CopyOnWriteArrayList<String?>()
            scenario("$phase/pair-refresh") { request -> when (request.path) {
                "/bookings/11/role" -> {
                    observedBearers += request.getHeader("Authorization")
                    if (roleRequests.incrementAndGet() == 1) MockResponse().setResponseCode(401).setBody("{}") else role()
                }
                "/auth/refresh" -> MockResponse().setBody("""{"access_token":"$rotated","refresh_token":"rotated-refresh-7"}""")
                "/bookings/11/details" -> MockResponse().setBody(details)
                else -> MockResponse().setResponseCode(404)
            } }
            assertTrue("A legitimate refresh and its own retry remain an ordinary success", ApiClient.getTripStateForNavigation(11).result.isSuccess)
            ApiClient.getBookingDetails(11).getOrThrow()
            assertEquals(listOf("Bearer ${jwt()}", "Bearer $rotated"), observedBearers.toList())
            assertEquals(rotated, ApiClient.currentToken()); assertEquals(7, ApiClient.myUserId())
            assertEquals(generationA, ApiClient.queueSessionGeneration())
            assertEquals("Name A", ApiClient.cachedName()); assertEquals("passenger", ApiClient.cachedRole())
            assertEquals(rotated, auth.restarted().getString("token", null))
            assertEquals("rotated-refresh-7", auth.restarted().getString("refresh_token", null))
            assertEquals(pendingScope, auth.restarted().getString(OfflineTripSnapshots.AUTH_SCOPE, null))
            assertEquals("A pair-only write never confirms the interrupted session marker", "pending",
                marker.restarted().getString(AuthStorePolicy.STATE_KEY, null))
            assertNull("Pair refresh plus own role/details200 cannot restore trust in scope A", OfflineTripSnapshots.owner.value)
            assertNull(OfflineTripSnapshots.ticket()); assertFalse(OfflineTripSnapshots.current(ownerA))
            assertNull(OfflineTripSnapshots.load(ownerA, 11))
            assertEquals(ownerA, OfflineTripSnapshots.ackTicket()?.owner)
            val offline = withRefusedEndpoint { readBookingNavigation(11) }
            assertTrue(offline.live.exceptionOrNull() is ConnectException); assertNull(offline.saved)
            assertEquals(listOf(
                Served("$phase/pair-refresh", "GET", "/bookings/11/role", 401),
                Served("$phase/pair-refresh", "POST", "/auth/refresh", 200),
                Served("$phase/pair-refresh", "GET", "/bookings/11/role", 200),
                Served("$phase/pair-refresh", "GET", "/bookings/11/details", 200)
            ), served.filter { it.phase == "$phase/pair-refresh" })
            scenario("$phase/full-reconfirm") { request ->
                if (request.path == "/auth/verify") loginResponse(7) else MockResponse().setResponseCode(404)
            }
            ApiClient.verifyCode("+70000000000", "000000", "Fallback").getOrThrow()
            val next = requireNotNull(OfflineTripSnapshots.ackTicket()).owner
            assertEquals(7, next.userId); assertTrue(next.generation > generationA)
            assertNotEquals(ownerA.scope, next.scope); assertNotEquals(pendingScope, next.scope)
            assertEquals("secure", marker.restarted().getString(AuthStorePolicy.STATE_KEY, null))
            assertEquals(next.scope, auth.restarted().getString(OfflineTripSnapshots.AUTH_SCOPE, null))
            assertNull("A full new session still awaits its own read", OfflineTripSnapshots.owner.value)
            scenario("$phase/reconfirmed-own-read") { request ->
                if (request.path == "/bookings/11/details") MockResponse().setBody(details) else MockResponse().setResponseCode(404)
            }
            ApiClient.getBookingDetails(11).getOrThrow()
            assertEquals(next, secureOwner()); assertNotNull(OfflineTripSnapshots.load(next, 11))
            assertNull(OfflineTripSnapshots.load(ownerA, 11))
            assertTrue(served.contains(Served("$phase/login-A", "POST", "/auth/verify", 200)))
            assertTrue(served.contains(Served("$phase/warm-A", "GET", "/bookings/11/details", 200)))
            assertTrue(served.contains(Served("$phase/full-reconfirm", "POST", "/auth/verify", 200)))
            assertTrue(served.contains(Served("$phase/reconfirmed-own-read", "GET", "/bookings/11/details", 200)))
        }
        assertEquals(2, refusedAttempts)
    }

    @Test fun originalNetworkAndReal503ReadTheSavedCopyButBad200NeverDoes() = runBlocking {
        warm()
        val wire = withRefusedEndpoint { readBookingNavigation(11) }
        assertTrue(wire.live.exceptionOrNull() is ConnectException)
        assertEquals("onboard", wire.saved!!.state.status)
        scenario("real503") { MockResponse().setResponseCode(503).setBody("{}") }
        val unavailable = readBookingNavigation(11)
        assertEquals(503, (unavailable.live.exceptionOrNull() as ApiException).status)
        assertEquals("onboard", unavailable.saved!!.state.status)
        scenario("bad200") { MockResponse().setBody("{broken") }
        val invalid = readBookingNavigation(11)
        assertTrue(invalid.live.exceptionOrNull() is org.json.JSONException)
        assertTrue(invalid.live.isFailure); assertNull(invalid.saved)
        assertEquals(listOf(Served("real503", "GET", "/bookings/11/role", 503), Served("bad200", "GET", "/bookings/11/role", 200)), served.toList())
    }
    @Test fun refreshNetworkSynthetic502AndAuthCommitIoNeverBecomeOffline() = runBlocking {
        for (failure in listOf("wire", "parse", "commit")) {
            field("token", jwt()); field("refreshToken", "refresh")
            auth.edit().putString("token", jwt()).putString("refresh_token", "refresh").commit()
            OfflineTripSnapshots.installForTest(disk) { time }
            OfflineTripSnapshots.activate(7, java.util.UUID.randomUUID().toString(), ApiClient.queueSessionGeneration())
            warm()
            val capturedOwner = secureOwner()
            scenario("refresh-$failure") { request -> when (request.path) {
                "/bookings/11/role" -> MockResponse().setResponseCode(401).setBody("{}")
                "/auth/refresh" -> if (failure == "wire") MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else MockResponse().setBody("{}")
                else -> MockResponse().setResponseCode(404)
            } }
            if (failure == "commit") auth.failWriteOf = "refresh_rotation_id"
            val read = ApiClient.getTripStateForNavigation(11)
            assertTrue(failure, read.result.isFailure); assertFalse(failure, read.fallbackAllowed)
            assertTrue(served.any { it == Served("refresh-$failure", "GET", "/bookings/11/role", 401) })
            if (failure != "wire") assertNull("Auth/parse uncertainty hides already shown copies", OfflineTripSnapshots.owner.value)
            else assertEquals("A refresh network failure does not revoke the still valid account scope", capturedOwner, OfflineTripSnapshots.owner.value)
            when (failure) {
                "wire" -> { assertTrue(read.result.exceptionOrNull() is java.io.IOException); assertTrue(served.any { it.phase == "refresh-wire" && it.path == "/auth/refresh" && it.code == null }) }
                "parse" -> { assertEquals(502, (read.result.exceptionOrNull() as ApiException).status); assertTrue(served.contains(Served("refresh-parse", "POST", "/auth/refresh", 200))) }
                "commit" -> { assertTrue(read.result.exceptionOrNull() is java.io.IOException); assertFalse(served.any { it.phase == "refresh-commit" && it.path == "/auth/refresh" }) }
            }
            auth.failWriteOf = null
        }
    }
    @Test fun successfulRefreshPreservesScopeButFailedRetriedGetStillCannotFallback() = runBlocking {
        warm(); val scope = secureOwner().scope
        field("refreshToken", "refresh"); auth.edit().putString("refresh_token", "refresh").commit()
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"access_token":"${jwt()}","refresh_token":"next"}"""))
        server.enqueue(role())
        assertTrue(ApiClient.getTripStateForNavigation(11).result.isSuccess)
        assertEquals(scope, secureOwner().scope)
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"access_token":"${jwt()}","refresh_token":"last"}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        val failed = ApiClient.getTripStateForNavigation(11)
        assertTrue(failed.result.isFailure); assertFalse(failed.fallbackAllowed)
    }
    @Test fun bothNavigationBoundariesKeepOnlineSuccessWhenScopeStoreOrBootAreUnavailable() = runBlocking {
        for (boundary in listOf("link", "restore")) for (missing in listOf("scope", "store", "boot")) {
            disk = SnapshotTestDisk()
            OfflineTripSnapshots.installForTest(disk) { if (missing == "boot") null else time }
            if (missing == "scope") {
                auth.edit().remove(OfflineTripSnapshots.AUTH_SCOPE).commit()
                auth.failWriteOf = OfflineTripSnapshots.AUTH_SCOPE
                ApiClient::class.java.getDeclaredMethod("ensureOfflineSnapshotScope").apply { isAccessible = true }.invoke(ApiClient)
                auth.failWriteOf = null
                assertNull(OfflineTripSnapshots.owner.value)
            } else {
                OfflineTripSnapshots.activate(7, java.util.UUID.randomUUID().toString(), ApiClient.queueSessionGeneration())
                if (missing == "store") disk.unavailable = true
            }
            val phase = "$boundary/$missing/live200"
            scenario(phase) { role() }
            val read = readBookingNavigation(11)
            assertTrue("$boundary/$missing", read.live.isSuccess)
            assertTrue(served.contains(Served(phase, "GET", "/bookings/11/role", 200)))
            val vm = YuldashViewModel(SavedStateHandle())
            val state = read.live.getOrThrow()
            // The exact normal route used by both app effects; cache readiness is not a prerequisite.
            assertEquals(Screen.Booking, vm.applyBookingLink(11, state.role, state.status, completed = false))
            assertNull(vm.offlineTripSnapshot.value); assertNotNull(vm.selectedRide.value)
            val offline = withRefusedEndpoint { readBookingNavigation(11) }
            assertTrue(offline.live.exceptionOrNull() is ConnectException)
            assertNull("$boundary/$missing cache must remain closed", offline.saved)
        }
        // Six wire failures are refused before HTTP, not pending consumers in MockWebServer's queue.
        assertEquals(6, server.requestCount); assertEquals(6, refusedAttempts)
        assertEquals(12, server.requestCount + refusedAttempts)
    }
    @Test fun acceptedQueuedDoneClosesBeforeAnotherGetAndNeverResendsBusinessPost() = runBlocking {
        warm(); val owner = secureOwner()
        server.enqueue(MockResponse().setBody("[]"))
        val action = OutboxAction(1L, 11, "trip_status", "done", 1000)
        assertTrue(ApiClient.sendQueuedAction(action, ApiClient.queueSessionGeneration()).isSuccess)
        assertNull(OfflineTripSnapshots.load(owner, 11))
        assertEquals(1, server.requestCount)
        assertEquals("POST", server.takeRequest().method)
        // Reopening the same disk cannot restore old onboard.
        OfflineTripSnapshots.installForTest(disk) { time }
        OfflineTripSnapshots.activate(owner.userId, owner.scope, owner.generation)
        assertNull(OfflineTripSnapshots.load(owner, 11))
    }
    @Test fun firstDetailsInFlightCannotSurviveAcceptedWholeRideCancellation() = runBlocking {
        ApiClient.testTimeoutMs = 5000
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/bookings/11/details" -> { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); MockResponse().setBody(details) }
                "/rides/22/cancel" -> MockResponse().setBody("""{"status":"cancelled"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val owner = secureOwner()
        val pending = async(Dispatchers.Default) { ApiClient.getBookingDetails(11) }
        try {
            check(entered.await(5, TimeUnit.SECONDS))
            assertTrue(ApiClient.cancelRide(22).isSuccess)
        } finally { release.countDown() }
        assertTrue(pending.await().isSuccess)
        OfflineTripSnapshots.installForTest(disk) { time }
        OfflineTripSnapshots.activate(owner.userId, owner.scope, owner.generation)
        assertNull(OfflineTripSnapshots.load(owner, 11))
    }
    @Test fun actualDenialBeforeBrokenBodyPersistsAcrossRetryRefreshNewTraceAndColdReopen() = runBlocking {
        for (code in listOf(403, 404, 410)) for (refresh in listOf(false, true)) for (detailsRead in listOf(false, true)) {
            ApiClient.saveToken(jwt())
            warm(); val owner = secureOwner()
            ApiClient.testTimeoutMs = null; ApiClient.testRetryTimeoutMs = 1000
            val path = "/bookings/11/${if (detailsRead) "details" else "role"}"
            val phase = "deny-$code-refresh$refresh-${if (detailsRead) "details" else "role"}"
            val attempts = AtomicInteger()
            if (refresh) { field("refreshToken", "refresh"); auth.edit().putString("refresh_token", "refresh").commit() }
            scenario(phase) { request -> when (request.path) {
                "/auth/refresh" -> MockResponse().setBody("""{"access_token":"${jwt()}","refresh_token":"next"}""")
                path -> when (attempts.incrementAndGet() - if (refresh) 1 else 0) {
                    0 -> MockResponse().setResponseCode(401).setBody("{}")
                    1 -> MockResponse().setResponseCode(code).setBody("denied").setBodyDelay(3, TimeUnit.SECONDS)
                    2 -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    else -> MockResponse().setResponseCode(503).setBody("{}")
                }
                else -> MockResponse().setResponseCode(404)
            } }
            val result = if (detailsRead) ApiClient.getBookingDetails(11).map { }
                else ApiClient.getTripStateForNavigation(11).also { assertFalse(it.fallbackAllowed) }.result.map { }
            assertEquals("Retry chain must actually finish with its planned 503", 503, (result.exceptionOrNull() as ApiException).status)
            assertTrue(served.contains(Served(phase, "GET", path, code)))
            assertTrue(served.any { it.phase == phase && it.path == path && it.code == null })
            assertTrue(served.contains(Served(phase, "GET", path, 503)))
            if (refresh) assertTrue(served.contains(Served(phase, "POST", "/auth/refresh", 200)))
            assertEquals("An actually observed denial must commit the permanent booking barrier", "closed", disk.barrier(owner.barrier("booking", 11)))
            assertNull("Actual denial permanently closes before returning from its retry chain", OfflineTripSnapshots.load(owner, 11))
            OfflineTripSnapshots.installForTest(disk) { time }
            OfflineTripSnapshots.activate(owner.userId, owner.scope, owner.generation)
            ApiClient.testRetryTimeoutMs = null; ApiClient.testTimeoutMs = 1000
            val offline = withRefusedEndpoint { readBookingNavigation(11) }
            assertTrue(offline.live.exceptionOrNull() is ConnectException)
            assertNull("A new trace after actual cold reopen cannot revive denied data", offline.saved)
        }
    }
    @Test fun directAndQueuedTerminalHttpContractsCloseOnlyTheirAcceptedFinalState() = runBlocking {
        for (kind in listOf("passenger", "driver", "queuedDriver", "noShow", "completeRide", "cancelDone")) {
            ApiClient.saveToken(jwt()); warm(if (kind in setOf("driver", "queuedDriver", "noShow", "completeRide")) "driver" else "passenger"); val owner = secureOwner()
            server.enqueue(MockResponse().setBody(if (kind == "passenger") "[]" else """{"status":"${if (kind == "noShow") "cancelled" else "done"}"}"""))
            val result = when (kind) {
                "passenger" -> ApiClient.setTripStatus(11, "done")
                "driver" -> ApiClient.driverStatus(11, "done")
                "queuedDriver" -> ApiClient.sendQueuedAction(OutboxAction(1, 11, "driver_status", "done", 1000), ApiClient.queueSessionGeneration())
                "noShow" -> ApiClient.markNoShow(11)
                "completeRide" -> ApiClient.completeRide(22)
                else -> ApiClient.cancelBooking(11).map { }
            }
            assertTrue(kind, result.isSuccess)
            OfflineTripSnapshots.installForTest(disk) { time }
            OfflineTripSnapshots.activate(owner.userId, owner.scope, owner.generation)
            assertNull(kind, OfflineTripSnapshots.load(owner, 11))
        }
        ApiClient.saveToken(jwt()); warm(); val owner = secureOwner()
        server.enqueue(MockResponse().setBody("""{"status":"confirmed","contact_then_cancel":true}"""))
        assertFalse(ApiClient.cancelBooking(11).getOrThrow().cancelled)
        assertNotNull("A successful HTTP response is not automatically cancellation", OfflineTripSnapshots.load(owner, 11))
    }
    @Test fun actualPassengerConsentReceiptSurvivesColdReopenAndRejectsOldOnboardEpoch() = runBlocking {
        warm(); val owner = secureOwner()
        server.enqueue(MockResponse().setBody("""{"status":"confirmed","boarding_undo_confirmed_id":"request-A","boarding_undo_onboard_at":"$epoch"}"""))
        assertTrue(BoardingApi.respondUndo(11, "request-A", true, owner.generation).isSuccess)
        OfflineTripSnapshots.installForTest(disk) { time }
        OfflineTripSnapshots.activate(owner.userId, owner.scope, owner.generation)
        assertEquals("confirmed", OfflineTripSnapshots.load(owner, 11)!!.state.status)
        server.enqueue(MockResponse().setBody(details))
        assertTrue(ApiClient.getBookingDetails(11).isSuccess)
        assertEquals("confirmed", OfflineTripSnapshots.load(owner, 11)!!.state.status)
    }
    @Test fun actualDriverBoardingAndUndoRequestKeepTheLatestEpochUntilPassengerConsent() = runBlocking {
        warm("driver"); val owner = secureOwner()
        val newer = "2026-10-24T12:00:00.000001Z"
        server.enqueue(MockResponse().setBody("""{"status":"onboard","onboard_at":"$newer"}"""))
        assertTrue(BoardingApi.markBoarded(11, owner.generation).isSuccess)
        server.enqueue(MockResponse().setBody("""{"status":"onboard","onboard_at":"$newer","boarding_undo_request_id":"request-B"}"""))
        assertTrue(BoardingApi.undoBoarded(11, owner.generation).isSuccess)
        OfflineTripSnapshots.installForTest(disk) { time }
        OfflineTripSnapshots.activate(owner.userId, owner.scope, owner.generation)
        val saved = OfflineTripSnapshots.load(owner, 11)!!
        assertEquals("onboard", saved.state.status); assertEquals(snapshotEpoch(newer), snapshotEpoch(saved.state.maxOnboard))
    }
}

/** Synthetic storage used by boundary/UI tests; product still uses encrypted preferences. */
internal class SnapshotTestDisk : SnapshotDisk {
    private val documents = mutableMapOf<String, String>()
    private val barriers = mutableMapOf<String, String>()
    var unavailable = false
    override fun read(namespace: String): String? { check(!unavailable); return documents[namespace] }
    override fun write(namespace: String, value: String): Boolean { if (unavailable) return false; documents[namespace] = value; return true }
    override fun barrier(key: String): String? { check(!unavailable); return barriers[key] }
    override fun setBarrier(key: String, value: String?): Boolean {
        if (unavailable) return false
        if (barriers[key] in setOf("closed", "retired") && value != barriers[key]) return false
        if (value == null) barriers.remove(key) else barriers[key] = value
        return true
    }
    override fun retire(namespace: String): Boolean { if (unavailable) return false; documents.remove(namespace); return true }
}
