package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Synthetic unit transport only. Constructing a pass never grants authority or touches its stores. */
internal class TripPassAuthorityFixture : AutoCloseable {
    private val details = ConcurrentHashMap<Int, JSONObject>()
    private val codes = ConcurrentHashMap<Int, String>()
    private var server: MockWebServer? = null
    @Volatile private var userId = 7

    fun start(userId: Int = 7) {
        check(server == null)
        this.userId = userId
        val owned = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != "Bearer ${jwt(this@TripPassAuthorityFixture.userId)}")
                        return MockResponse().setResponseCode(403).setBody("{}")
                    val match = Regex("^/bookings/([1-9][0-9]*)/(details|boarding-code|role)$").matchEntire(request.path.orEmpty())
                        ?: return MockResponse().setResponseCode(404).setBody("{}")
                    val id = match.groupValues[1].toInt()
                    val value = details[id] ?: return MockResponse().setResponseCode(404).setBody("{}")
                    val body = when (match.groupValues[2]) {
                        "details" -> value
                        "role" -> JSONObject().put("role", value.getString("role")).put("status", value.getString("status")).put("driver_phase", "")
                        else -> JSONObject().put("code", codes[id].orEmpty())
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
                }
            }
            start()
        }
        server = owned
        installSession(owned.url("/").toString().trimEnd('/'), userId)
    }

    fun nextAccount(userId: Int = 8) {
        // This uses the isolated no-auth-store unit path. It must not run init/reset/migration on
        // the caller's TripPass preferences, including commit(false) and pending-reset fixtures.
        this.userId = userId
        ApiClient.saveToken(jwt(userId))
    }

    fun authorizePositive(pass: TripPass): TripPass = runBlocking {
        val wirePayMethod = pass.payMethod ?: "negotiate"
        val value = pass.toJson().put("ride_id", pass.bookingId + 1000).put("role", "passenger")
            .put("status", "confirmed").put("contact_unlocked", true)
            .put("pay_method", wirePayMethod)
        details[pass.bookingId] = value
        codes[pass.bookingId] = pass.boardingCode
        val actual = ApiClient.getBookingDetails(pass.bookingId).getOrThrow()
        assertTrue("Positive fixture must obtain actual current DETAILS authority", OfflineTripSnapshots.privateDetails(actual))
        if (pass.boardingCode.isNotBlank()) assertEquals(pass.boardingCode, ApiClient.getBoardingCode(pass.bookingId).getOrThrow())
        requireNotNull(OfflineTripSnapshots.privatePass(pass.bookingId)).also {
            assertTrue(OfflineTripSnapshots.matchesPass(it))
            assertEquals(pass.price, it.price)
            assertEquals(wirePayMethod, it.payMethod)
            assertEquals(pass.payAmount, it.payAmount)
        }
    }

    fun freshDetails(value: JSONObject): BookingDetailsDto = runBlocking {
        val id = value.getInt("booking_id")
        details[id] = JSONObject(value.toString())
        ApiClient.getBookingDetails(id).getOrThrow()
    }

    fun coldAuthority() {
        val previous = requireNotNull(OfflineTripSnapshots.currentOwner())
        installEmptyAuthority()
        OfflineTripSnapshots.activate(previous.userId, previous.scope, previous.generation)
    }

    override fun close() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        clearAuthReferences()
        server?.shutdown()
        server = null
    }

    companion object {
        fun jwt(id: Int): String = "e30." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"$id"}""".toByteArray()) + ".synthetic-unit"

        /** Bind an existing owned MockWebServer; never ApiClient.init or TripPassStore.initStores. */
        fun installSession(baseUrl: String, userId: Int = 7) {
            ApiClient.resetForTest()
            clearAuthReferences()
            ApiClient.testBaseUrl = baseUrl
            ApiClient.testTimeoutMs = 1000
            installEmptyAuthority()
            ApiClient.saveToken(jwt(userId))
        }

        private fun installEmptyAuthority() {
            OfflineTripSnapshots.installForTest(SnapshotPreferencesDisk(MemoryDiskPreferences(), MemoryDiskPreferences())) {
                SnapshotClock(41, 1000, 1000, 1000)
            }
        }

        private fun clearAuthReferences() {
            listOf("prefs", "plainAuthPrefs", "secureAuthPrefs", "authStoreSource", "appCtx", "token", "refreshToken").forEach {
                ApiClient::class.java.getDeclaredField(it).apply { isAccessible = true }.set(ApiClient, null)
            }
        }

        /** A disk assertion reads the restarted durable map, never TripPassStore's live projection. */
        fun durable(store: MemoryDiskPreferences, id: Int): TripPass? = store.restarted().getString("pass_$id", null)
            ?.let { TripPass.fromJson(JSONObject(it)) }

        fun durableJson(store: MemoryDiskPreferences, id: Int): JSONObject? = store.restarted().getString("pass_$id", null)?.let(::JSONObject)
    }
}
