package com.yuldash.app

import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit diagnostics over the current synthetic account; never prints private payload or credentials. */
@RunWith(AndroidJUnit4::class)
class ColdTripPassportDiagnosticInstrumentedTest {
    @Test fun inspectCurrentSyntheticBooking() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("coldPassportDiagnostic")
        require(phase in setOf("read", "save", "remove")) { "Explicit QA phase required" }
        val bookingId = arguments.getString("coldPassportBooking")?.toIntOrNull() ?: error("Booking required")
        require(bookingId > 0)
        val context = instrumentation.targetContext.applicationContext
        ApiClient.init(context)
        OfflineTripSnapshots.init(context)
        TripPassStore.init(context)
        val owner = requireNotNull(OfflineTripSnapshots.currentOwner()) { "Current durable owner unavailable" }
        val saveResult = if (phase == "save") {
            require(ApiClient.apiBase() == "http://127.0.0.1:8000") { "Only the isolated local API may be used" }
            ApiClient.getBookingDetails(bookingId).getOrThrow()
            ApiClient.getBoardingCode(bookingId).getOrThrow()
            val pass = requireNotNull(OfflineTripSnapshots.privatePass(bookingId))
            OfflineTripSnapshots.saveColdPassport(context, pass, expectedGeneration = ApiClient.queueSessionGeneration())
        } else null
        val removalResult = if (phase == "remove") {
            require(ApiClient.apiBase() == "http://127.0.0.1:8000") { "Only the isolated local API may be used" }
            TripPassStore.requestRemoval(context, bookingId, expectedGeneration = ApiClient.queueSessionGeneration())
        } else null
        val disk = SnapshotPreferencesDisk.open(context)
        val document = disk.read(owner.namespace)?.let(::JSONObject)
        val record = document?.getJSONObject("records")?.optJSONObject(bookingId.toString())
        val grant = record?.optJSONObject("cold_grant")
        val candidate = TripPassStore.readSecureCandidate(context, owner, bookingId)
        val restored = OfflineTripSnapshots.loadColdPassport(owner, bookingId)
        val snapshot = OfflineTripSnapshots.load(owner, bookingId)
        val barriers = listOf("scope" to 0, "booking" to bookingId, "cold-booking" to bookingId,
            "cold-scope" to 0, "cold-ride" to (record?.optInt("ride") ?: 0)).associate { (kind, id) ->
            kind to (disk.barrier(owner.barrier(kind, id)) != null)
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putInt("diagnostic_pid", Process.myPid())
            putInt("diagnostic_booking", bookingId)
            putString("save_result", saveResult?.name)
            putString("removal_result", removalResult?.name)
            putBoolean("owner_valid", owner.valid)
            putBoolean("document_present", document != null)
            putBoolean("record_present", record != null)
            putBoolean("grant_present", grant != null)
            putBoolean("secure_candidate_present", candidate != null)
            putBoolean("secure_candidate_canonical", candidate != null && candidate.raw == candidate.pass.toJson().toString())
            putBoolean("candidate_hash_matches_grant", candidate != null && candidate.payloadSha256 == grant?.optString("trippass_sha256"))
            putBoolean("cold_passport_restored", restored != null)
            putBoolean("cold_passport_allowed", restored?.let(OfflineTripSnapshots::canDrawCold) == true)
            putBoolean("ordinary_snapshot_restored", snapshot != null)
            putBoolean("live_allowed", OfflineTripSnapshots.allowsLive(bookingId))
            putBoolean("private_projection_present", OfflineTripSnapshots.privatePass(bookingId) != null)
            putString("closed_bookings", document?.getJSONArray("closed_bookings")?.toString())
            putString("closed_rides", document?.getJSONArray("closed_rides")?.toString())
            putString("barriers", JSONObject(barriers).toString())
            putString("grant_clock", grant?.getJSONObject("grant_stamp")?.toString())
            putString("snapshot_clock", record?.getJSONObject("state_stamp")?.toString())
            putString("current_clock", OfflineTripSnapshots.clock()?.let {
                JSONObject().put("boot", it.boot).put("wall", it.wall).put("before", it.before).put("after", it.after).toString()
            })
        })
    }
}
