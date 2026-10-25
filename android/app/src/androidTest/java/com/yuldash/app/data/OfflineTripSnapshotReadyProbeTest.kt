package com.yuldash.app.data

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/** Readiness only: existing synthetic account/data, no reset, fake auth, business HTTP or queue mutation.
 * Use the normal AndroidJUnitRunner/YuldashApplication; StorageAuditRunner is intentionally rejected.
 */
@RunWith(AndroidJUnit4::class)
class OfflineTripSnapshotReadyProbeTest {
    private data class Expected(val ownerId: Int, val bookingId: Int, val role: String, val status: String,
        val from: String, val to: String, val departAt: String, val pickup: String?, val dropoff: String?, val hasStops: Boolean, val onboardAt: String)

    private fun expected(): Expected = try {
        val arg = requireNotNull(InstrumentationRegistry.getArguments().getString("snapshotProbe"))
        require(arg.isNotEmpty() && arg.length <= 16_384 && arg.matches(Regex("[A-Za-z0-9_-]+")))
        val bytes = Base64.getUrlDecoder().decode(arg)
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes))
        val json = JSONObject(text)
        require(json.keys().asSequence().toSet() == setOf("schema", "ownerId", "bookingId", "role", "status", "from", "to", "departAt", "pickup", "dropoff", "hasStops", "onboardAt"))
        fun integer(key: String) = json.get(key).let { require(it is Int && it > 0); it }
        fun string(key: String) = json.get(key).let { require(it is String && it.isNotBlank() && it.length <= 500); it }
        fun nullable(key: String): String? = json.get(key).let {
            if (it == JSONObject.NULL) null else { require(it is String && it.length <= 500); it }
        }
        require(integer("schema") == 1 && json.get("hasStops") is Boolean)
        Expected(integer("ownerId"), integer("bookingId"), string("role"), string("status"), string("from"), string("to"),
            string("departAt"), nullable("pickup"), nullable("dropoff"), json.getBoolean("hasStops"), string("onboardAt")).also {
            require(it.role in setOf("driver", "passenger") && it.status == "onboard" && snapshotEpoch(it.onboardAt) != null && snapshotEpoch(it.departAt) != null)
        }
    } catch (_: Exception) { throw AssertionError("Invalid snapshotProbe contract; no payload is printed") }

    @Test fun savedOwnOnboardAndReadableEmptyQueue() = runBlocking {
        val expected = expected()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Do not explicitly reinitialize a guest/unknown account: init would legitimately erase its private stores.
        assertTrue("Probe requires the already resolved real synthetic login", ApiClient.isLoggedIn() && ApiClient.myUserId() == expected.ownerId)
        val before = OfflineTripSnapshots.owner.value
        assertTrue("Probe requires a trusted actual cache owner", before != null && before.userId == expected.ownerId && OfflineTripSnapshots.current(before))
        ApiClient.init(context)
        val owner = OfflineTripSnapshots.owner.value
        assertTrue("Reopen must preserve the same durable auth scope", owner != null && owner.userId == expected.ownerId && owner.namespace == before!!.namespace && OfflineTripSnapshots.current(owner))
        val saved = OfflineTripSnapshots.load(requireNotNull(owner), expected.bookingId)
        assertTrue("Encrypted saved trip must be readable and unexpired", saved != null)
        saved!!
        assertTrue("Saved trip owner/booking/role/status mismatch", saved.owner == owner && saved.bookingId == expected.bookingId && saved.state.role == expected.role && saved.state.status == expected.status)
        assertTrue("Saved boarding epoch mismatch", snapshotEpoch(saved.state.maxOnboard) == snapshotEpoch(expected.onboardAt))
        val route = saved.route
        assertTrue("Saved own route/stops mismatch", route != null && route.from == expected.from && route.to == expected.to &&
            snapshotEpoch(route.departAt)?.let { it == snapshotEpoch(expected.departAt) } == true &&
            route.pickup == expected.pickup && route.dropoff == expected.dropoff && route.hasStops == expected.hasStops)
        val readAll = Outbox::class.java.getDeclaredMethod("readAll", android.content.Context::class.java).apply { isAccessible = true }
        val queue = synchronized(Outbox) { readAll.invoke(Outbox, context) }
        assertTrue("Existing Outbox must be readable; null is not empty", queue is List<*>)
        val count = (queue as List<*>).size
        assertEquals("Existing readable Outbox must be empty", 0, count)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("snapshotReady", "true"); putInt("bookingId", saved.bookingId)
            putString("role", saved.state.role); putString("status", saved.state.status)
            putString("outboxReadable", "true"); putInt("outboxCount", count)
        })
    }
}
