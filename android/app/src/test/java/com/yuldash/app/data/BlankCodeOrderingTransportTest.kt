package com.yuldash.app.data

import android.app.Application
import com.yuldash.app.currentTripCode
import com.yuldash.app.currentTripPrivateDetails
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real transport ordering only. Observers never issue requests or install authority.
 * The historical blank UI failure does not prove which ordering occurred there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class BlankCodeOrderingTransportTest : SnapshotWiringFixture() {
    private enum class Read { ROLE, DETAILS, CODE, OTHER }
    private val passPlain = MemoryDiskPreferences()
    private val passSecure = MemoryDiskPreferences()
    private val departure = java.time.Instant.now().plusSeconds(TimeUnit.DAYS.toSeconds(1)).toString()
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val heldOnce = AtomicBoolean()
    private val badAuth = AtomicInteger()
    private val order = CopyOnWriteArrayList<Read>()
    @Volatile private var held: Read? = null
    @Volatile private var blankCode = false
    @Volatile private var responseRole = "passenger"
    @Volatile private var responseStatus = "confirmed"
    @Volatile private var responseMarker = "ordering-private-point"
    @Volatile private var responseUser = 7
    @Volatile private var responseSession = "qa"
    private var oldBytes: String? = null
    private lateinit var initialProjection: TripPrivateProjection
    private var preparedCodeReads = 0
    private var cleanupReady = false
    override fun permitWiringCleanup(): Boolean = cleanupReady

    @Before fun configureOrdering() {
        ApiClient.testTimeoutMs = 12000
        TripPassStore.initStores(passPlain, passSecure)
        handler = ::respond
    }
    @After fun releaseAndDrainOwnedWork() = runBlocking {
        requireHealthyWiringFixture()
        release.countDown()
        // Session controls can schedule snapshot retirement; drain both known owned scopes.
        val scopes = listOf(
            ApiClient::class.java.getDeclaredField("bg").apply { isAccessible = true }.get(ApiClient) as CoroutineScope,
            OfflineTripSnapshots::class.java.getDeclaredField("jobs").apply { isAccessible = true }.get(OfflineTripSnapshots) as CoroutineScope,
        )
        withTimeout(10000) {
            scopes.forEach { scope ->
                val parent = requireNotNull(scope.coroutineContext[Job])
                while (true) {
                    val children = parent.children.toList()
                    if (children.isEmpty()) break
                    children.joinAll()
                }
            }
        }
        cleanupReady = true
    }
    private fun respond(request: RecordedRequest): MockResponse {
        val match = Regex("^/bookings/(11|12)/(role|details|boarding-code)$").matchEntire(request.requestUrl!!.encodedPath)
        val id = match?.groupValues?.get(1)?.toInt() ?: 0
        val kind = when (match?.groupValues?.get(2)) {
            "role" -> Read.ROLE
            "details" -> Read.DETAILS
            "boarding-code" -> Read.CODE
            else -> Read.OTHER
        }
        order += kind
        if (request.method != "GET" || request.getHeader("Authorization") != "Bearer " + jwt(responseUser, responseSession)) {
            badAuth.incrementAndGet()
            return MockResponse().setResponseCode(403).setBody("{}")
        }
        // Freeze this actual response BEFORE the barrier. A later mode/session change
        // must not turn the older held blank into a nonblank success.
        val body = when (kind) {
            Read.ROLE -> roleJson(role = responseRole, status = responseStatus).apply {
                if (responseStatus != "onboard") remove("onboard_at")
            }
            Read.DETAILS -> detailsJson(id = id, role = responseRole, status = responseStatus, marker = responseMarker).apply {
                put("ride_id", if (id == 12) 44 else 33)
                put("depart_at", departure)
                if (responseStatus != "onboard") remove("onboard_at")
            }
            Read.CODE -> JSONObject().put("code", if (blankCode) "" else "4821")
            Read.OTHER -> return MockResponse().setResponseCode(404).setBody("{}")
        }
        if (kind == held && heldOnce.compareAndSet(false, true)) {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Synthetic ordering barrier timed out" }
        }
        return MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
    }
    private fun codeReads() = order.count { it == Read.CODE }
    private fun durableBytes() = passSecure.restarted().getString("pass_11", null)
    private fun assertDurableAndReadonly() {
        assertTrue("Original committed bytes must remain exact", oldBytes != null && durableBytes() == oldBytes)
        assertTrue("Independent durable reader must retain the synthetic old code",
            TripPassAuthorityFixture.durable(passSecure, 11)?.boardingCode == "4821")
        assertEquals(0, badAuth.get())
        assertTrue("No write or extra endpoint may occur", requests.all { it.method == "GET" } && order.none { it == Read.OTHER })
    }
    private suspend fun prepareCurrentProjection() {
        ApiClient.getTripState(11).getOrThrow()
        val details = ApiClient.getBookingDetails(11).getOrThrow()
        assertTrue(OfflineTripSnapshots.privateDetails(details))
        assertTrue(ApiClient.getBoardingCode(11).getOrThrow() == "4821")
        assertTrue(TripPassStore.save(context, requireNotNull(OfflineTripSnapshots.privatePass(11))))
        oldBytes = durableBytes()
        // Refresh through actual HTTP, so both competing DETAILS have the SAME fingerprint:
        // no attached-code difference is mistaken for a different private projection.
        ApiClient.getBookingDetails(11).getOrThrow()
        initialProjection = requireNotNull(OfflineTripSnapshots.projection(11))
        assertTrue(OfflineTripSnapshots.allowsLive(11))
        assertTrue(OfflineTripSnapshots.privateDetails(initialProjection.details))
        preparedCodeReads = codeReads()
        assertEquals(1, preparedCodeReads)
        blankCode = true
        assertDurableAndReadonly()
    }
    private fun probe(stage: String) {
        val current = OfflineTripSnapshots.projection(11)
        // Scalar metadata only. No DTO, fingerprint, code, header, private value or disk bytes.
        println("BLANK_ORDER stage="+stage+" order="+order.toList()+
            " baselineProjectionTicket="+initialProjection.proof.request.ticket.sequence+
            " currentProjectionTicket="+(current?.proof?.request?.ticket?.sequence ?: 0)+
            " sameDetailsFingerprint="+(current?.fingerprint == initialProjection.fingerprint)+
            " sameProjectionRef="+(current === initialProjection)+
            " live="+OfflineTripSnapshots.allowsLive(11)+
            " private="+(current?.details?.let(OfflineTripSnapshots::privateDetails) == true))
    }
    private fun assertDenied() {
        assertFalse("A blank code response must withdraw live authority", OfflineTripSnapshots.allowsLive(11))
        assertTrue("A blank code response must withdraw private pass", OfflineTripSnapshots.privatePass(11) == null)
        assertTrue("A blank code response must withdraw private details", currentTripPrivateDetails(11) == null)
        assertTrue("Old code must not be a current display value", currentTripCode(11, "4821").isEmpty())
        assertDurableAndReadonly()
    }
    private suspend fun newerReadBeforeBlank(kind: Read) = coroutineScope {
        prepareCurrentProjection()
        held = Read.CODE
        val detailsBeforeCode = order.count { it == Read.DETAILS }
        val code = async(Dispatchers.IO) { ApiClient.getBoardingCode(11) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertEquals(preparedCodeReads + 1, codeReads())
            assertEquals("Held CODE must use the already current projection", detailsBeforeCode, order.count { it == Read.DETAILS })
            probe("codeHeldBeforeNewer"+kind)
            when (kind) {
                Read.ROLE -> ApiClient.getTripState(11).getOrThrow()
                Read.DETAILS -> ApiClient.getBookingDetails(11).getOrThrow()
                else -> error("Only ROLE or DETAILS is a competing read")
            }
            val current = requireNotNull(OfflineTripSnapshots.projection(11))
            assertTrue("Competing response must have the same complete private fingerprint",
                current.fingerprint == initialProjection.fingerprint)
            assertTrue(OfflineTripSnapshots.allowsLive(11))
            assertTrue(OfflineTripSnapshots.privateDetails(current.details))
            assertFalse("Blank response must not complete before the competing publication", code.isCompleted)
            probe("newerPublishedBeforeBlank"+kind)
            release.countDown()
            assertTrue(withTimeout(12000) { code.await() }.isFailure)
            assertEquals("No newer successful CODE action was requested", preparedCodeReads + 1, codeReads())
            probe("afterBlank"+kind)
            assertDenied()
        } finally { release.countDown() }
    }
    @Test fun newerSameRolePublicationCannotSuppressBlankCodeWithdrawal() = runBlocking { newerReadBeforeBlank(Read.ROLE) }
    @Test fun newerSameDetailsPublicationCannotSuppressBlankCodeWithdrawal() = runBlocking { newerReadBeforeBlank(Read.DETAILS) }

    @Test fun detailsStartedBeforeBlankDenialCannotRegrantAfterItsLateCompletion() = runBlocking {
        coroutineScope {
            prepareCurrentProjection()
            held = Read.DETAILS
            val lateDetails = async(Dispatchers.IO) { ApiClient.getBookingDetails(11) }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertFalse(lateDetails.isCompleted)
                probe("oldDetailsHeldBeforeBlank")
                assertTrue(ApiClient.getBoardingCode(11).isFailure)
                assertEquals(preparedCodeReads + 1, codeReads())
                assertDenied()
                probe("deniedBeforeOldDetailsRelease")
                release.countDown()
                val failure = withTimeout(12000) { lateDetails.await() }.exceptionOrNull()
                assertTrue("A transport failure must not stand in for authority supersession", failure is ApiException)
                val rejection = failure as ApiException
                assertEquals(409, rejection.status)
                assertEquals("snapshot_superseded", rejection.detailCode)
                probe("afterOldDetailsCompletion")
                assertDenied()
            } finally { release.countDown() }
        }
    }
    @Test fun genuinelyPostDenialFreshDetailsCanRecoverWithoutAnotherCodeAction() = runBlocking {
        prepareCurrentProjection()
        assertTrue(ApiClient.getBoardingCode(11).isFailure)
        assertDenied()
        val codesAtDenial = codeReads()
        val fresh = ApiClient.getBookingDetails(11).getOrThrow()
        assertTrue(OfflineTripSnapshots.freshDetails(fresh))
        assertTrue(OfflineTripSnapshots.privateDetails(fresh))
        assertTrue(OfflineTripSnapshots.allowsLive(11))
        assertEquals("Fresh DETAILS control must not request a new CODE", codesAtDenial, codeReads())
        assertTrue("Fresh generic details must not reattach the old saved code", currentTripCode(11, "4821").isEmpty())
        probe("freshDetailsAfterDenial")
        assertDurableAndReadonly()
    }

    /** All competing requests are explicit scenario actions. This helper observes only
     * the completed new context and then releases/awaits the older actual blank response.
     */
    private suspend fun preserveNewContextBeforeOldBlank(
        sessionChanged: Boolean = false,
        action: suspend () -> Unit,
    ) = coroutineScope {
        held = Read.CODE
        blankCode = true
        val oldBlank = async(Dispatchers.IO) { ApiClient.getBoardingCode(11) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            action()
            val current = requireNotNull(OfflineTripSnapshots.projection(11))
            val currentOwner = owner()
            val currentSession = ApiClient.queueSessionGeneration()
            assertTrue(OfflineTripSnapshots.privateDetails(current.details))
            assertTrue(OfflineTripSnapshots.allowsLive(11))
            assertFalse(oldBlank.isCompleted)
            val codesBeforeRelease = codeReads()
            probe("newContextBeforeOldBlankRelease")
            release.countDown()
            val failure = withTimeout(12000) { oldBlank.await() }.exceptionOrNull()
            if (sessionChanged) {
                assertTrue("A session replacement must finish as the concrete client session fence",
                    failure is IllegalStateException && failure.javaClass.simpleName == "SessionChangedException")
            } else {
                assertTrue("The old response must remain an actual decoded blank, not a transport error", failure is IllegalArgumentException)
            }
            assertEquals("The old blank must not retry or turn into another successful CODE", codesBeforeRelease, codeReads())
            assertTrue("Old blank changed the newly published projection", OfflineTripSnapshots.projection(11) === current)
            assertTrue(OfflineTripSnapshots.privateDetails(current.details))
            assertTrue(OfflineTripSnapshots.allowsLive(11))
            assertTrue(owner() == currentOwner)
            assertEquals(currentSession, ApiClient.queueSessionGeneration())
            probe("newContextAfterOldBlankCompletion")
            if (oldBytes != null) assertDurableAndReadonly()
            else {
                assertTrue("A driver's code must never enter durable passenger bytes", durableBytes() == null)
                assertEquals(0, badAuth.get())
                assertTrue(requests.all { it.method == "GET" } && order.none { it == Read.OTHER })
            }
        } finally { release.countDown() }
    }
    @Test fun newerSameValuePassengerCodeProtectsIdenticalFingerprintFromOldBlank() = runBlocking {
        prepareCurrentProjection()
        blankCode = false
        assertTrue(ApiClient.getBoardingCode(11).getOrThrow() == "4821")
        initialProjection = requireNotNull(OfflineTripSnapshots.projection(11))
        preserveNewContextBeforeOldBlank {
            blankCode = false
            assertTrue(ApiClient.getBoardingCode(11).getOrThrow() == "4821")
            val newer = requireNotNull(OfflineTripSnapshots.projection(11))
            assertTrue("Same-value CODE must leave the complete fingerprint identical", newer.fingerprint == initialProjection.fingerprint)
            assertTrue(newer.permit!!.ticket.sequence > initialProjection.permit!!.ticket.sequence)
        }
        assertTrue(currentTripCode(11, "4821") == "4821")
    }
    @Test fun newerCodeSuccessSurvivesDetailsReturningToOldBlankFingerprintAndRole() = runBlocking {
        prepareCurrentProjection()
        preserveNewContextBeforeOldBlank {
            blankCode = false
            assertTrue(ApiClient.getBoardingCode(11).getOrThrow() == "4821")
            assertTrue(requireNotNull(OfflineTripSnapshots.projection(11)).fingerprint != initialProjection.fingerprint)
            ApiClient.getBookingDetails(11).getOrThrow()
            assertTrue("Later DETAILS must return exactly to the older blank's full fingerprint",
                requireNotNull(OfflineTripSnapshots.projection(11)).fingerprint == initialProjection.fingerprint)
            ApiClient.getTripState(11).getOrThrow()
        }
        assertTrue(currentTripCode(11, "4821").isEmpty())
    }
    @Test fun changedPrivateFingerprintCannotBeRevokedByOlderBlank() = runBlocking {
        prepareCurrentProjection()
        preserveNewContextBeforeOldBlank {
            responseMarker = "ordering-new-private-point"
            ApiClient.getBookingDetails(11).getOrThrow()
            assertTrue(requireNotNull(OfflineTripSnapshots.projection(11)).fingerprint != initialProjection.fingerprint)
        }
    }
    @Test fun changedOnboardStateCannotBeRevokedByOlderBlank() = runBlocking {
        prepareCurrentProjection()
        preserveNewContextBeforeOldBlank {
            responseStatus = "onboard"
            ApiClient.getTripState(11).getOrThrow()
            val next = ApiClient.getBookingDetails(11).getOrThrow()
            assertEquals("onboard", next.status)
            assertTrue(requireNotNull(OfflineTripSnapshots.projection(11)).fingerprint != initialProjection.fingerprint)
        }
    }
    @Test fun replacementOwnerCurrentDetailsCannotBeRevokedByOlderBlank() = runBlocking {
        prepareCurrentProjection()
        preserveNewContextBeforeOldBlank(sessionChanged = true) {
            responseUser = 8
            ApiClient.saveToken(jwt(8))
            val next = ApiClient.getBookingDetails(11).getOrThrow()
            assertTrue(OfflineTripSnapshots.privateDetails(next))
            assertEquals(8, owner().userId)
        }
    }
    @Test fun replacementSameOwnerSessionCannotBeRevokedByOlderBlank() = runBlocking {
        prepareCurrentProjection()
        val previousSession = ApiClient.queueSessionGeneration()
        preserveNewContextBeforeOldBlank(sessionChanged = true) {
            responseSession = "ordering-replacement"
            ApiClient.saveToken(jwt(7, responseSession))
            val next = ApiClient.getBookingDetails(11).getOrThrow()
            assertTrue(OfflineTripSnapshots.privateDetails(next))
            assertEquals(7, owner().userId)
            assertTrue(ApiClient.queueSessionGeneration() != previousSession)
        }
    }
    @Test fun semanticBlankForOneBookingNeverRevokesAnotherBooking() = runBlocking {
        coroutineScope {
            prepareCurrentProjection()
            held = Read.CODE
            val oldBlank = async(Dispatchers.IO) { ApiClient.getBoardingCode(11) }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val other = ApiClient.getBookingDetails(12).getOrThrow()
                val otherProjection = requireNotNull(OfflineTripSnapshots.projection(12))
                assertTrue(OfflineTripSnapshots.privateDetails(other))
                assertTrue(OfflineTripSnapshots.allowsLive(12))
                assertFalse(oldBlank.isCompleted)
                release.countDown()
                assertTrue(withTimeout(12000) { oldBlank.await() }.exceptionOrNull() is IllegalArgumentException)
                assertDenied()
                assertTrue(OfflineTripSnapshots.projection(12) === otherProjection)
                assertTrue(OfflineTripSnapshots.privateDetails(other))
                assertTrue(OfflineTripSnapshots.allowsLive(12))
            } finally { release.countDown() }
        }
    }
    @Test fun acceptedDriverCodeSuccessProtectsSamePrivateFingerprintFromOlderBlank() = runBlocking {
        responseRole = "driver"
        ApiClient.getTripState(11).getOrThrow()
        val details = ApiClient.getBookingDetails(11).getOrThrow()
        assertTrue(OfflineTripSnapshots.privateDetails(details))
        assertTrue(ApiClient.getBoardingCode(11).getOrThrow() == "4821")
        initialProjection = requireNotNull(OfflineTripSnapshots.projection(11))
        assertTrue(currentTripCode(11, "4821") == "4821")
        assertTrue(durableBytes() == null)
        preserveNewContextBeforeOldBlank {
            blankCode = false
            assertTrue(ApiClient.getBoardingCode(11).getOrThrow() == "4821")
            assertTrue(requireNotNull(OfflineTripSnapshots.projection(11)).fingerprint == initialProjection.fingerprint)
        }
        assertTrue(currentTripCode(11, "4821") == "4821")
        assertTrue(OfflineTripSnapshots.privatePass(11) == null)
    }
}
