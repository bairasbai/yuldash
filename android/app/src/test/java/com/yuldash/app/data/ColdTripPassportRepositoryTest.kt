package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class ColdTripPassportRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = SnapshotCoreFixture(SnapshotCoreDisk(temporary.newFolder().toPath()))

    private data class Prepared(
        val permit: SnapshotAuthorityPermit,
        val detailsTicket: SnapshotTicket,
        val receipt: TripPassCommitReceipt,
        val pass: TripPass,
    )

    private fun pass(marker: String): TripPass = TripPass.fromJson(JSONObject()
        .put("booking_id", 11).put("boarding_code", "CODE-$marker").put("driver_name", "PRIVATE-$marker")
        .put("driver_phone", "+70000000000").put("meet_note", "PRIVATE-POINT-$marker"))

    private fun candidate(pass: TripPass): TripPassSecureCandidate {
        val raw = pass.toJson().toString()
        return TripPassSecureCandidate(pass.bookingId, raw, pass, snapshotHash(raw))
    }

    private fun prepare(f: SnapshotCoreFixture, marker: String): Prepared {
        val fingerprint = snapshotHash("current-private-projection:$marker")
        val details = f.gate.beginRead(11, SnapshotAuthorityKind.DETAILS)!!
        assertNotNull(f.gate.acceptRead(f.proof(details), SnapshotCoreFixture.ONBOARD,
            22, SnapshotCoreFixture.ROUTE, contactUnlocked = true, privateFingerprint = fingerprint))
        val code = f.gate.beginRead(11, SnapshotAuthorityKind.DETAILS)!!
        val permit = f.gate.acceptRead(f.proof(code), SnapshotCoreFixture.ONBOARD,
            22, SnapshotCoreFixture.ROUTE, contactUnlocked = true, privateFingerprint = fingerprint)!!
        assertTrue(f.gate.recordCodeSuccess(f.proof(code)))
        val pass = pass(marker)
        return Prepared(permit, details.ticket,
            TripPassCommitReceipt(11, fingerprint, candidate(pass).payloadSha256), pass)
    }

    private suspend fun save(f: SnapshotCoreFixture, marker: String = "A"): Prepared {
        val prepared = prepare(f, marker)
        assertEquals(SnapshotPersistence.COMMITTED, f.repo.beginColdSave(prepared.permit,
            SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE,
            "blocked:" + snapshotHash("cold-save:$marker:${prepared.detailsTicket.sequence}")))
        assertEquals(SnapshotPersistence.COMMITTED, f.repo.observe(prepared.permit.ticket, 11,
            SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE,
            permit = prepared.permit, coldSave = true))
        assertEquals(SnapshotPersistence.COMMITTED, f.repo.commitColdGrant(prepared.permit,
            prepared.detailsTicket, prepared.receipt, SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE))
        return prepared
    }

    private suspend fun load(f: SnapshotCoreFixture, pass: TripPass): ColdTripPassport? {
        val restore = f.gate.beginColdRestore(f.owner, 11) ?: return null
        return f.repo.loadCold(f.owner, 11, candidate(pass), restore)
    }

    @Test fun newProcessRestoresOnlyColdPermitWithinSameDurableScope() = runBlocking {
        val f = fixture()
        val prepared = save(f)
        val cold = SnapshotCoreFixture(f.disk, f.owner)
        val restored = requireNotNull(load(cold, prepared.pass))
        assertTrue(cold.gate.allowsCold(restored))
        assertFalse(cold.gate.allowsPrivate(cold.owner, 11, restored.grant.sourcePrivateFingerprint))
        assertFalse(cold.gate.allowsSnapshot(cold.owner, 11, restored.snapshot.state, 22, restored.snapshot.route))
        assertNull(cold.repo.load(cold.owner, 11))
        val raw = f.disk.read(f.owner.namespace)!!
        for (private in listOf("PRIVATE-A", "PRIVATE-POINT-A", "CODE-A", "driver_phone", "boarding_code", "meet_note")) {
            assertFalse(private, raw.contains(private))
        }
        val nextGeneration = SnapshotCoreFixture(f.disk, f.owner.copy(generation = 2))
        assertNotNull(load(nextGeneration, prepared.pass))
    }

    @Test fun oldSchemaV2RecordWithoutGrantNeverBecomesColdAuthority() = runBlocking {
        val f = fixture()
        assertEquals(SnapshotPersistence.COMMITTED, f.save())
        val raw = JSONObject(f.disk.read(f.owner.namespace)!!)
        assertFalse(raw.getJSONObject("records").getJSONObject("11").has("cold_grant"))
        assertTrue(f.disk.setBarrier(f.owner.barrier("cold-booking", 11), null))
        assertNull(load(SnapshotCoreFixture(f.disk, f.owner), pass("old")))
    }

    @Test fun wrongGrantIdentityFingerprintCandidateHashAndSchemaAreRejected() = runBlocking {
        val damages = listOf<(JSONObject) -> Unit>(
            { it.put("owner", 8) },
            { it.put("origin", "https://other.test") },
            { it.put("scope", UUID.randomUUID().toString()) },
            { it.put("booking", 12) },
            { it.put("ride", 23) },
            { it.put("snapshot_candidate", "blocked:" + snapshotHash("wrong-candidate")) },
            { it.put("trippass_sha256", snapshotHash("wrong-raw")) },
            { it.put("schema", 2) },
            { it.put("unexpected", true) },
            { it.getJSONObject("code_ticket").put("sequence", 0) },
        )
        damages.forEachIndexed { index, damage ->
            val f = fixture()
            val prepared = save(f, "damage-$index")
            val doc = JSONObject(f.disk.read(f.owner.namespace)!!)
            damage(doc.getJSONObject("records").getJSONObject("11").getJSONObject("cold_grant"))
            f.disk.write(f.owner.namespace, doc.toString())
            assertNull("damage-$index", load(SnapshotCoreFixture(f.disk, f.owner), prepared.pass))
        }
    }

    @Test fun wrongSourceFingerprintFailsProjectionAndReceiptBinding() = runBlocking {
        val f = fixture()
        val prepared = save(f)
        val doc = JSONObject(f.disk.read(f.owner.namespace)!!)
        doc.getJSONObject("records").getJSONObject("11").getJSONObject("cold_grant")
            .put("source_private_fingerprint", snapshotHash("wrong-private"))
        f.disk.write(f.owner.namespace, doc.toString())
        assertNull(load(f, prepared.pass))

        val receipt = fixture()
        val current = prepare(receipt, "receipt")
        assertEquals(SnapshotPersistence.COMMITTED, receipt.repo.beginColdSave(current.permit,
            SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE, "blocked:" + snapshotHash("receipt")))
        assertEquals(SnapshotPersistence.COMMITTED, receipt.repo.observe(current.permit.ticket, 11,
            SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE, permit = current.permit, coldSave = true))
        val wrong = current.receipt.copy(sourcePrivateFingerprint = snapshotHash("wrong-receipt"))
        assertEquals(SnapshotPersistence.STALE, receipt.repo.commitColdGrant(current.permit,
            current.detailsTicket, wrong, SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE))
    }

    @Test fun wrongRawCandidateAndCorruptCandidateCannotMatchGrant() = runBlocking {
        val f = fixture()
        val prepared = save(f)
        val cold = SnapshotCoreFixture(f.disk, f.owner)
        assertNull(load(cold, pass("B")))
        val raw = prepared.pass.toJson().toString()
        val wrongHash = TripPassSecureCandidate(11, raw, prepared.pass, snapshotHash("not-the-raw"))
        assertNull(cold.repo.loadCold(cold.owner, 11, wrongHash, cold.gate.beginColdRestore(cold.owner, 11)!!))
        val corruptRaw = "{\"booking_id\":11}"
        val corrupt = TripPassSecureCandidate(11, corruptRaw, prepared.pass, snapshotHash(corruptRaw))
        assertNull(cold.repo.loadCold(cold.owner, 11, corrupt, cold.gate.beginColdRestore(cold.owner, 11)!!))
    }

    @Test fun replacementBInvalidatesAAndOldCandidateCannotOpenNewGrant() = runBlocking {
        val f = fixture()
        val a = save(f, "A")
        val b = save(f, "B")
        assertNotEquals(a.receipt.payloadSha256, b.receipt.payloadSha256)
        assertNull(load(SnapshotCoreFixture(f.disk, f.owner), a.pass))
        assertNotNull(load(SnapshotCoreFixture(f.disk, f.owner), b.pass))
    }

    @Test fun staleRevocationCannotEraseReplacementB() = runBlocking {
        val f = fixture()
        save(f, "A")
        val late = f.gate.revokeCold(f.gate.ticket()!!, 11)!!
        val b = save(f, "B")
        assertEquals(SnapshotPersistence.STALE, f.repo.revokeCold(late))
        assertNotNull(load(SnapshotCoreFixture(f.disk, f.owner), b.pass))
    }

    @Test fun ordinaryDetailsOverwriteAndExplicitRevokeRemoveGrantDurably() = runBlocking {
        val overwritten = fixture()
        val prepared = save(overwritten)
        assertEquals(SnapshotPersistence.COMMITTED, overwritten.save())
        assertNull(load(SnapshotCoreFixture(overwritten.disk, overwritten.owner), prepared.pass))
        val revoked = fixture()
        val current = save(revoked)
        val request = revoked.gate.revokeCold(revoked.gate.ticket()!!, 11)!!
        assertEquals(SnapshotPersistence.COMMITTED, revoked.repo.revokeCold(request))
        assertNotNull(revoked.disk.barrier(revoked.owner.barrier("cold-booking", 11)))
        val doc = JSONObject(revoked.disk.read(revoked.owner.namespace)!!)
        assertFalse(doc.getJSONObject("records").getJSONObject("11").has("cold_grant"))
        assertNull(load(SnapshotCoreFixture(revoked.disk, revoked.owner), current.pass))
    }

    @Test fun lockedDetailsBeforeObserveDenyNewRestore() = runBlocking {
        val f = fixture()
        val prepared = save(f)
        val saved = requireNotNull(load(f, prepared.pass))
        val details = f.gate.beginRead(11, SnapshotAuthorityKind.DETAILS)!!
        assertNotNull(f.gate.acceptRead(f.proof(details), SnapshotCoreFixture.ONBOARD,
            22, SnapshotCoreFixture.ROUTE, contactUnlocked = false))
        assertNull(f.gate.beginColdRestore(f.owner, 11))
        val entries = f.gate.javaClass.getDeclaredField("entries").apply { isAccessible = true }.get(f.gate) as Map<*, *>
        val entry = requireNotNull(entries.values.single())
        val epoch = entry.javaClass.getDeclaredField("coldEpoch").apply { isAccessible = true }.getLong(entry)
        val restore = SnapshotColdRestoreTicket(f.gate.ticket()!!, 11, epoch)
        assertFalse(f.gate.restoreCold(restore, saved))
        assertNull(f.repo.loadCold(f.owner, 11, candidate(prepared.pass), restore))
    }

    @Test fun revokeBetweenObserveAndGrantLastStaysDeniedAfterReopen() = runBlocking {
        val f = fixture()
        val prepared = prepare(f, "grant-race")
        assertEquals(SnapshotPersistence.COMMITTED, f.repo.beginColdSave(prepared.permit,
            SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE, "blocked:" + snapshotHash("grant-race")))
        assertEquals(SnapshotPersistence.COMMITTED, f.repo.observe(prepared.permit.ticket, 11,
            SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE,
            permit = prepared.permit, coldSave = true))
        val revocation = f.gate.revokeCold(f.gate.ticket()!!, 11)!!
        assertEquals(SnapshotPersistence.STALE, f.repo.commitColdGrant(prepared.permit,
            prepared.detailsTicket, prepared.receipt, SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE))
        assertNotNull(f.disk.barrier(f.owner.barrier("cold-booking", 11)))
        assertNull(load(SnapshotCoreFixture(f.disk, f.owner), prepared.pass))
        assertEquals(SnapshotPersistence.COMMITTED, f.repo.revokeCold(revocation))
        assertNull(load(SnapshotCoreFixture(f.disk, f.owner), prepared.pass))
    }

    @Test fun closeAndRestoreEpochRaceKeepColdClosed() = runBlocking {
        val f = fixture()
        val prepared = save(f)
        val racing = SnapshotCoreFixture(f.disk, f.owner)
        val restore = racing.gate.beginColdRestore(racing.owner, 11)!!
        racing.gate.revokeColdProjection(racing.owner, 11)
        assertNull(racing.repo.loadCold(racing.owner, 11, candidate(prepared.pass), restore))
        assertEquals(SnapshotPersistence.COMMITTED, f.repo.close(f.repo.ticket()!!, 11))
        assertNull(load(SnapshotCoreFixture(f.disk, f.owner), prepared.pass))
    }

    @Test fun everyColdTerminalAndScopeBarrierSuppressesRestore() = runBlocking {
        val barriers = listOf(
            Triple("cold-booking", 11, "blocked"), Triple("cold-ride", 22, "blocked"),
            Triple("cold-scope", 0, "blocked"), Triple("booking", 11, "closed"),
            Triple("ride", 22, "closed"), Triple("scope", 0, "retired"),
        )
        barriers.forEach { (kind, id, value) ->
            val f = fixture()
            val prepared = save(f, "$kind-$id")
            assertTrue(f.disk.setBarrier(f.owner.barrier(kind, id), value))
            assertNull(kind, load(SnapshotCoreFixture(f.disk, f.owner), prepared.pass))
        }
    }

    @Test fun storedDriverRoleCannotReusePassengerGrant() = runBlocking {
        val f = fixture()
        val prepared = save(f)
        val doc = JSONObject(f.disk.read(f.owner.namespace)!!)
        doc.getJSONObject("records").getJSONObject("11").put("role", "driver")
        f.disk.write(f.owner.namespace, doc.toString())
        assertNull(load(SnapshotCoreFixture(f.disk, f.owner), prepared.pass))
    }

    @Test fun expiryWritesDurableDenyAndRollbackOrAnotherBootCannotRevive() = runBlocking {
        val f = fixture()
        val prepared = save(f)
        val cold = SnapshotCoreFixture(f.disk, f.owner)
        cold.now = SnapshotClock(3, 1000 + OfflineTripSnapshotPolicy.MAX_AGE_MS,
            1000 + OfflineTripSnapshotPolicy.MAX_AGE_MS, 1000 + OfflineTripSnapshotPolicy.MAX_AGE_MS)
        assertNull(load(cold, prepared.pass))
        assertNotNull(cold.disk.barrier(cold.owner.barrier("cold-booking", 11)))
        cold.now = SnapshotClock(3, 1000, 1000, 1000)
        assertNull(load(cold, prepared.pass))
        val rolledBackProcess = SnapshotCoreFixture(f.disk, f.owner)
        assertNull(load(rolledBackProcess, prepared.pass))
        val otherBoot = SnapshotCoreFixture(f.disk, f.owner)
        otherBoot.now = SnapshotClock(4, 1000, 1000, 1000)
        assertNull(load(otherBoot, prepared.pass))
    }

    @Test fun payloadBarrierGrantAndClearFailuresNeverExposePartialPair() = runBlocking {
        val begin = fixture()
        val beginPrepared = prepare(begin, "begin")
        val coldKey = begin.owner.barrier("cold-booking", 11)
        begin.disk.barrierFault = { key, value -> key == coldKey && value != null }
        assertEquals(SnapshotPersistence.NOT_DURABLE, begin.repo.beginColdSave(beginPrepared.permit,
            SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE, "blocked:" + snapshotHash("begin")))
        assertNull(load(SnapshotCoreFixture(begin.disk, begin.owner), beginPrepared.pass))

        val payload = fixture()
        val payloadPrepared = prepare(payload, "payload")
        assertEquals(SnapshotPersistence.COMMITTED, payload.repo.beginColdSave(payloadPrepared.permit,
            SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE, "blocked:" + snapshotHash("payload")))
        payload.disk.writeFault = { _, _ -> true }
        assertEquals(SnapshotPersistence.DURABLY_BLOCKED, payload.repo.observe(payloadPrepared.permit.ticket, 11,
            SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE, permit = payloadPrepared.permit, coldSave = true))
        assertNull(load(SnapshotCoreFixture(payload.disk, payload.owner), payloadPrepared.pass))

        val grant = fixture()
        val grantPrepared = prepare(grant, "grant")
        assertEquals(SnapshotPersistence.COMMITTED, grant.repo.beginColdSave(grantPrepared.permit,
            SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE, "blocked:" + snapshotHash("grant")))
        assertEquals(SnapshotPersistence.COMMITTED, grant.repo.observe(grantPrepared.permit.ticket, 11,
            SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE, permit = grantPrepared.permit, coldSave = true))
        grant.disk.writeFault = { _, value -> value.contains("cold_grant") }
        assertEquals(SnapshotPersistence.DURABLY_BLOCKED, grant.repo.commitColdGrant(grantPrepared.permit,
            grantPrepared.detailsTicket, grantPrepared.receipt, SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE))
        assertNull(load(SnapshotCoreFixture(grant.disk, grant.owner), grantPrepared.pass))

        val clear = fixture()
        val clearPrepared = prepare(clear, "clear")
        assertEquals(SnapshotPersistence.COMMITTED, clear.repo.beginColdSave(clearPrepared.permit,
            SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE, "blocked:" + snapshotHash("clear")))
        assertEquals(SnapshotPersistence.COMMITTED, clear.repo.observe(clearPrepared.permit.ticket, 11,
            SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE, permit = clearPrepared.permit, coldSave = true))
        clear.disk.barrierFault = { key, value -> key == clear.owner.barrier("cold-booking", 11) && value == null }
        assertEquals(SnapshotPersistence.DURABLY_BLOCKED, clear.repo.commitColdGrant(clearPrepared.permit,
            clearPrepared.detailsTicket, clearPrepared.receipt, SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE))
        assertNull(load(SnapshotCoreFixture(clear.disk, clear.owner), clearPrepared.pass))
    }

    @Test fun totalRevocationFailureIsExplicitP0BoundaryNotFalseSuccess() = runBlocking {
        val f = fixture()
        val prepared = save(f)
        val request = f.gate.revokeCold(f.gate.ticket()!!, 11)!!
        f.disk.barrierFault = { key, _ -> key == f.owner.barrier("cold-booking", 11) }
        f.disk.writeFault = { _, _ -> true }
        assertEquals(SnapshotPersistence.NOT_DURABLE, f.repo.revokeCold(request))
        f.disk.recoverWrites()
        // Accepted P0 boundary: neither the barrier nor grant removal reached durable storage.
        // A new process can only be denied when at least one revocation write actually commits.
        assertNotNull(load(SnapshotCoreFixture(f.disk, f.owner), prepared.pass))
    }
}
