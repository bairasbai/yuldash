package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.SnapshotRoute
import com.yuldash.app.data.OfflinePassportFixture03
import com.yuldash.app.data.Outbox
import com.yuldash.app.data.SnapshotWiringCleanupGuard
import com.yuldash.app.data.TripPassAuthorityFixture
import com.yuldash.app.data.awaitOfflineWriteSnapshotReset02
import com.yuldash.app.data.finishOfflineWriteFixture02
import com.yuldash.app.data.poisonOfflinePassportFixture03
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.net.InetAddress
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real confirmed BookingScreen -> guarded warm snapshot; cold legacy bytes stay unverified. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingOfflinePaymentJourneyTest {
    companion object { private val cleanupGuard = SnapshotWiringCleanupGuard() }
    private val requestJobs = RequestJobs()
    @get:Rule val compose = createComposeRule(effectContext = requestJobs)
    private var contentSet = false
    private var serverStartAttempted = false
    private var apiInitAttempted = false
    private lateinit var passportFixture: OfflinePassportFixture03
    private val vm = YuldashViewModel(SavedStateHandle())
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var plain = MemoryDiskPreferences()
    private var secure = MemoryDiskPreferences()
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<String>()
    private val preparationRequests = CopyOnWriteArrayList<String>()
    @Volatile private var preparing = true
    private val ownTrace: (String) -> Unit = { requestJobs.observe(it) }
    private val mounted = mutableStateOf(false)
    private val active = mutableStateOf(false)
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    @Volatile private var offline = false
    @Volatile private var method = "cash"
    @Volatile private var amount: Int? = 400
    @Volatile private var codeValue = "4821"
    @Volatile private var holdCode = false
    private val held = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val returned = CountDownLatch(1)

    @Before fun prepare() {
        cleanupGuard.requireHealthy()
        check(ApiClient.testTrace == null)
        passportFixture = OfflinePassportFixture03(context)
        passportFixture.prepareClock()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    val user = when (request.getHeader("Authorization")) {
                        "Bearer " + TripPassAuthorityFixture.jwt(7) -> 7
                        "Bearer " + TripPassAuthorityFixture.jwt(8) -> 8
                        else -> null
                    }
                    (if (preparing && path in setOf("/bookings/42/details", "/bookings/42/boarding-code"))
                        preparationRequests else requests) += "${request.method} $path"
                    if (path in setOf("/auth/logout", "/push/unregister")) return json(JSONObject())
                    if (request.method != "GET" || user == null) return MockResponse().setResponseCode(403)
                    if (offline) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    // Capture A's response before the latch; B's own preflight is never held.
                    val data = when (path) {
                        "/bookings/42/role" -> JSONObject().put("role", "passenger")
                            .put("status", "confirmed").put("driver_phase", "")
                        "/bookings/42/details" -> JSONObject()
                            .put("booking_id", 42).put("ride_id", 17).put("role", "passenger")
                            .put("status", "confirmed").put("contact_unlocked", true)
                            .put("from_city", "Город А").put("to_city", "Город Б")
                            .put("depart_at", "2026-11-04T09:00:00Z")
                            .put("driver_name", "Тестовый водитель").put("price", if (user == 8) 900 else 700).put("seats", 1)
                            .put("pay_method", if (user == 8) "sbp" else method)
                            .put("pay_amount", if (user == 8) 900 else amount ?: JSONObject.NULL)
                        "/bookings/42/boarding-code" -> JSONObject().put("code", if (user == 8) "B-only" else codeValue)
                        "/bookings/42/messages" -> JSONObject().put("items", org.json.JSONArray())
                        else -> return MockResponse().setResponseCode(404)
                    }
                    if (holdCode && user == 7 && path == "/bookings/42/boarding-code") {
                        held.countDown()
                        if (!release.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                        returned.countDown()
                    }
                    return json(data)
                }
            }
        }
        serverStartAttempted = true
        server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
        ApiClient.testTimeoutMs = 700
        apiInitAttempted = true
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.init(context)
        awaitOfflineWriteSnapshotReset02()
        finishOfflineWriteFixture02(cleanupGuard) {}
        ApiClient.saveToken(TripPassAuthorityFixture.jwt(7))
        passportFixture.captureAuthBacking()
        TripPassStore.initStores(plain, secure)
        Outbox.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        assertEquals(7, OfflineTripSnapshots.currentOwner()?.userId)
        assertTrue(ApiClient.isCurrentSession(ApiClient.queueSessionGeneration()))
        assertFalse(OfflineTripSnapshots.allowsLive(42))
        ApiClient.serverUnreachable.value = false
        ApiClient.testTrace = ownTrace
        requests.clear() // completed setup traffic is separate from the original business period
        preparing = false
    }

    @After fun cleanup() {
        try {
            cleanupGuard.requireHealthy()
            release.countDown()
            if (contentSet) {
                compose.runOnIdle {
                    mounted.value = false
                    if (owner.registry.currentState.isAtLeast(Lifecycle.State.CREATED))
                        owner.registry.currentState = Lifecycle.State.DESTROYED
                }
                settle()
                compose.waitForIdle()
            }
            requestJobs.awaitCompleted()
            offline = false
            secure.failWriteOf = null
            if (apiInitAttempted) ApiClient.logout()
            finishOfflineWriteFixture02(cleanupGuard) {
                check(ApiClient.testTrace == null || ApiClient.testTrace === ownTrace)
                if (serverStartAttempted) server.shutdown()
                ApiClient.resetForTest()
                ApiClient.testTimeoutMs = null
                TripPassStore.initStores(MemoryDiskPreferences(), null)
                Outbox.initStores(MemoryDiskPreferences(), null)
                if (::passportFixture.isInitialized) passportFixture.restoreClock()
            }
        } catch (failure: Throwable) {
            poisonOfflinePassportFixture03(cleanupGuard, failure)
        } finally {
            if (ApiClient.testTrace === ownTrace) ApiClient.testTrace = null
        }
    }

    /** Observe the actual Compose withContext(IO) job and caller, without creating either. */
    private class RequestJobs : ThreadContextElement<Job?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RequestJobs>
        private val current = ThreadLocal<Job?>()
        val reads = CopyOnWriteArrayList<Read>()
        class Read(val path: String, val transport: Job, val caller: Job) {
            @Volatile var phase = 1 // 1 entered, 2 decoded HTTP200, 3 transport cancellation
        }
        override fun updateThreadContext(context: CoroutineContext): Job? = current.get().also { current.set(context[Job]) }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Job?) { current.set(oldState) }
        fun observe(line: String) {
            val job = current.get() ?: return // synchronous preflight is already complete on return
            val prefix = "вошли в вызов GET "
            if (line.startsWith(prefix)) {
                reads += Read(line.removePrefix(prefix), job, requireNotNull(job.parent))
            } else reads.lastOrNull { it.transport === job }?.let { read ->
                if (line == "ВЫШЛИ из вызова GET ${read.path} успешно (код 200)") read.phase = 2
                if (line == "вызов GET ${read.path} ОТМЕНЁН (корутину закрыли снаружи)") read.phase = 3
            }
        }
        /** Exactly the initial BookingScreen reads; transport completion alone is insufficient. */
        fun initialBookingCompleted(): Boolean {
            val size = reads.size
            check(size <= 2) { "PAYMENT_PHASE_A_SETUP initialReads=EXCESS" }
            if (size < 2) return false
            val details = reads[0]
            val code = reads[1]
            check(details.path == "/bookings/42/details" && code.path == "/bookings/42/boarding-code" &&
                details.caller === code.caller) { "PAYMENT_PHASE_A_SETUP initialReads=UNEXPECTED" }
            return details.phase == 2 && code.phase == 2 &&
                details.transport.isCompleted && code.transport.isCompleted && details.caller.isCompleted &&
                !details.transport.isCancelled && !code.transport.isCancelled && !details.caller.isCancelled
        }
        fun awaitCompleted() = runBlocking {
            withTimeout(5_000) {
                // ApiClient GET uses synchronous HttpURLConnection; its IO Job includes disconnect.
                while (reads.any { !it.transport.isCompleted || !it.caller.isCompleted }) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    Snapshot.sendApplyNotifications()
                    delay(10)
                }
            }
        }
    }

    private val ride = Ride(id = "17", from = "Город А", to = "Город Б", time = "09:00",
        driver = "Тестовый водитель", car = "", price = 700, seats = 1,
        rating = 4.9, verified = false, boosted = false)
    private fun activeCandidate() = vm.navigateLocally {
        vm.selectedRide.value = ride
        vm.activeBookingId.value = 42
        vm.activeTrip.value = null
        vm.activeDriverBookingId.value = null
        vm.screen.value = Screen.ActiveTrip
    }
    private fun mount(language: AppLanguage) {
        mounted.value = true
        contentSet = true
        compose.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language, LocalLifecycleOwner provides owner) {
                    if (mounted.value) {
                        if (active.value) GuardedActiveTripDestination(vm, ApiClient.queueSessionGeneration(),
                            contacts = emptyList(), terminalHint = null, chatOpen = false,
                            onBack = {}, onTripEnd = {}, onSos = {}, onSupport = {}, onTrustedContacts = {},
                            onTripStopped = {}, onOpenReceipt = {}, currentReceiptBookingId = { 0 })
                        else BookingScreen(
                            ride = ride,
                            bookingId = 42, bookingStatus = "confirmed", ads = emptyList(), adStats = emptyMap(),
                            onBack = {}, onMessage = {}, onAdImpression = {}, onAdClick = {},
                            onConfirmRide = { _, _, _, _, _ -> activeCandidate(); active.value = true })
                    }
                }
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
    }

    private fun json(data: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(data.toString())
    private fun settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
    }
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15000) {
        settle()
        condition()
    }
    private fun show(text: String) {
        if (active.value) compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        else {
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assertIsDisplayed()
        }
    }
    private val saveWarning = "Не удалось сохранить поездку без интернета"
    /**
     * The save warning is the first card of the list, on screen without scrolling. Do not poll
     * show() for it: scrolling the list while the card animates in could leave the card's reported
     * position above the list top, and performScrollToNode (no timeout) then scrolled up forever —
     * 37 min of Compose frames until OutOfMemoryError in CI (12.10.2026).
     */
    private fun awaitSaveWarning() {
        waitFor { compose.onAllNodesWithText(saveWarning).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText(saveWarning).assertIsDisplayed()
    }
    private fun diskJson() = JSONObject(requireNotNull(secure.restarted().getString("pass_42", null)))
    @Suppress("DEPRECATION")
    private fun noVisiblePrivateText(text: String) {
        val hidden = SemanticsMatcher.keyIsDefined(SemanticsProperties.InvisibleToUser)
        compose.onAllNodes(hasText(text, substring = true) and !hidden and !hasAnyAncestor(hidden),
            useUnmergedTree = true).assertCountEquals(0)
    }

    /** B-ENTRY-DISCLAIMER: only entry/readiness changes; all seven money UI oracles remain. */
    private fun awaitOfflineBanner(language: AppLanguage) {
        val banner = if (language == AppLanguage.Ba) "Һуңғы һаҡланған хәлде күрһәтәбеҙ. Ул үҙгәргән булыуы мөмкин."
            else "Показываем последнее сохранённое состояние. Оно могло измениться."
        waitFor { ApiClient.serverUnreachable.value && requests.contains("GET /bookings/42/role") &&
            vm.offlineTripSnapshot.value != null &&
            compose.onAllNodesWithText(banner).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(banner).assertIsDisplayed()
        compose.onNodeWithText("Город А → Город Б").assertIsDisplayed()
        assertNull(vm.activeTrip.value)
        assertNotNull(OfflineTripSnapshots.warmFallback(42))
    }

    /** Own HTTP only; may run before a fault or for B while A remains held, never under fault. */
    private fun positivePass(user: Int): TripPass {
        passportFixture.assertOriginalBacking()
        assertEquals(user, OfflineTripSnapshots.currentOwner()?.userId)
        val before = preparationRequests.size
        preparing = true
        try {
            val actual = runBlocking {
                val details = ApiClient.getBookingDetails(42).getOrThrow()
                assertTrue(OfflineTripSnapshots.privateDetails(details))
                assertTrue(ApiClient.getBoardingCode(42).getOrThrow().isNotBlank())
                requireNotNull(OfflineTripSnapshots.privatePass(42))
            }
            assertEquals(listOf("GET /bookings/42/details", "GET /bookings/42/boarding-code"),
                preparationRequests.drop(before))
            assertTrue(OfflineTripSnapshots.matchesPass(actual))
            return actual
        } finally { preparing = false }
    }

    private fun journey(language: AppLanguage, payMethod: String, payAmount: Int?, expected: String) {
        method = payMethod
        amount = payAmount
        val expectedOwner = requireNotNull(OfflineTripSnapshots.currentOwner()) { "PAYMENT_PHASE_A_SETUP owner=ABSENT" }
        val generation = ApiClient.queueSessionGeneration()
        check(expectedOwner.userId == 7 && expectedOwner.generation == generation && ApiClient.isCurrentSession(generation)) {
            "PAYMENT_PHASE_A_SETUP ownerCurrent=false"
        }
        mount(language)
        waitFor { requestJobs.initialBookingCompleted() && TripPassStore.load(context, 42)?.boardingCode == "4821" &&
            compose.onAllNodesWithText(if (language == AppLanguage.Ba) "Сәфәр раҫланыуын яңыртабыҙ" else "Обновляем подтверждение поездки").fetchSemanticsNodes().isEmpty() }
        val openLabel = if (language == AppLanguage.Ba) "Сәфәрҙе асыу" else "Открыть поездку"
        compose.onNodeWithText(openLabel).assertIsDisplayed()
        // Warm preference reopen only: process authority stays alive; this is not a new PID.
        plain = plain.restarted(); secure = secure.restarted()
        TripPassStore.initStores(plain, secure)
        passportFixture.assertOriginalBacking()
        check(OfflineTripSnapshots.currentOwner() == expectedOwner && ApiClient.isCurrentSession(generation)) {
            "PAYMENT_PHASE_A_SETUP ownerUnchanged=false"
        }
        val persistenceBefore = OfflineTripSnapshots.persistence.value
        val clockAvailable = OfflineTripSnapshots.clock() != null
        val secureBacking = runCatching {
            val repository = OfflineTripSnapshots.javaClass.getDeclaredField("repository")
                .apply { isAccessible = true }.get(OfflineTripSnapshots) ?: return@runCatching "UNKNOWN"
            val disk = repository.javaClass.getDeclaredField("disk").apply { isAccessible = true }.get(repository)
            if (disk !is com.yuldash.app.data.SnapshotPreferencesDisk) "UNKNOWN"
            else if (disk.javaClass.getDeclaredField("secure").apply { isAccessible = true }.get(disk) == null) "ABSENT"
            else "PRESENT"
        }.getOrDefault("UNKNOWN")
        val canonical = runBlocking { OfflineTripSnapshots.load(expectedOwner, 42) }
        runCatching {
            println("PAYMENT_PHASE_A_OBSERVATION08 persistenceBefore=$persistenceBefore clockAvailable=$clockAvailable " +
                "secureBacking=$secureBacking persistenceAfter=${OfflineTripSnapshots.persistence.value} " +
                "canonicalPresent=${canonical != null} " +
                "ownerSessionUnchanged=${OfflineTripSnapshots.currentOwner() == expectedOwner && ApiClient.isCurrentSession(generation)}")
        }
        val saved = requireNotNull(canonical) {
            "PAYMENT_PHASE_A_SETUP canonicalPresent=false storage=UNKNOWN persistence=${OfflineTripSnapshots.persistence.value}"
        }
        check(saved.owner == expectedOwner && saved.bookingId == 42 && saved.rideId == 17 &&
            saved.state.role == "passenger" && saved.state.status == "confirmed" &&
            saved.route == SnapshotRoute("Город А", "Город Б", "2026-11-04T09:00:00Z", null, null, false)) {
            "PAYMENT_PHASE_A_SETUP identityAndRouteValid=false"
        }
        val now = requireNotNull(OfflineTripSnapshots.clock()) { "PAYMENT_PHASE_A_SETUP clockAvailable=false" }
        check(saved.remaining(now) != null && OfflineTripSnapshots.canDrawSnapshot(saved)) {
            "PAYMENT_PHASE_A_SETUP clockAndGateValid=false"
        }
        check(OfflineTripSnapshots.currentOwner() == expectedOwner && ApiClient.isCurrentSession(generation) &&
            requestJobs.initialBookingCompleted() && requests.size == 2 &&
            requests[0] == "GET /bookings/42/details" && requests[1] == "GET /bookings/42/boarding-code") {
            "PAYMENT_PHASE_A_SETUP ownerAndInitialReadsUnchanged=false"
        }
        assertEquals(1, requests.count { it == "GET /bookings/42/details" })
        offline = true
        compose.onNodeWithText(openLabel).performClick()
        awaitOfflineBanner(language)
        val disk = diskJson()
        assertEquals("cash/sbp must survive confirmed-save and reopening", payMethod, disk.optString("pay_method"))
        if (payAmount == null) assertTrue(disk.isNull("pay_amount")) else assertEquals(payAmount, disk.optInt("pay_amount", -1))
        assertEquals("Original booking price is separately retained", 700, disk.optInt("price"))
        show(expected) // The warm server agreement must remain visible, even if current APP is red.
        // The separate read-only agreement must not contradict the saved passport.
        show(if (language == AppLanguage.Ba) "Түләү тураһында нисек килешкәнбеҙ" else "Как договорились платить")
        show("${payAmount ?: 700} ₽")
        val methodLabel = expected.substringAfter(" · ")
        compose.onNodeWithText(methodLabel).assertIsDisplayed()
        assertFalse(requests.any { it.startsWith("POST ") })
    }

    @Test fun cashAgreementSurvivesConfirmedSaveAndOfflineRu() = journey(AppLanguage.Ru, "cash", 400, "400 ₽ · Наличными")
    @Test fun cashAgreementSurvivesConfirmedSaveAndOfflineBa() = journey(AppLanguage.Ba, "cash", 400, "400 ₽ · Наличный менән")
    @Test fun explicitZeroSurvivesOfflineRu() = journey(AppLanguage.Ru, "cash", 0, "0 ₽ · Наличными")
    @Test fun explicitZeroSurvivesOfflineBa() = journey(AppLanguage.Ba, "cash", 0, "0 ₽ · Наличный менән")
    @Test fun nullAgreementUsesOriginalAmountWithoutInventingSbp() = journey(AppLanguage.Ru, "negotiate", null, "700 ₽ · Договоримся")
    @Test fun nullAgreementUsesOriginalAmountWithoutInventingSbpBa() = journey(AppLanguage.Ba, "negotiate", null, "700 ₽ · Килешербеҙ")
    @Test fun explicitSbpRemainsSbp() = journey(AppLanguage.Ru, "sbp", 400, "400 ₽ · Перевод по СБП")

    @Test fun refusedConfirmedSaveWarnsThenRetryPersistsAgreementWithoutPost() {
        method = "sbp"
        amount = 700
        codeValue = "old"
        assertTrue(TripPassStore.save(context, positivePass(7)))
        assertEquals("sbp", diskJson().getString("pay_method"))
        assertEquals(700, diskJson().getInt("pay_amount"))
        method = "cash"
        amount = 400
        codeValue = "4821"
        secure.failWriteOf = "pass_42"
        mount(AppLanguage.Ru)
        val warning = saveWarning
        awaitSaveWarning()
        assertEquals("sbp", diskJson().getString("pay_method"))
        assertEquals(700, diskJson().getInt("pay_amount"))
        secure.failWriteOf = null
        show("Сохранить ещё раз")
        compose.onNodeWithText("Сохранить ещё раз").performClick()
        waitFor { diskJson().optInt("pay_amount") == 400 &&
            compose.onAllNodesWithText(warning).fetchSemanticsNodes().isEmpty() }
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertEquals("cash", requireNotNull(TripPassStore.load(context, 42)).toJson().getString("pay_method"))
        assertEquals(400, diskJson().getInt("pay_amount"))
        assertEquals(2, requests.count { it == "GET /bookings/42/boarding-code" })
        assertEquals(1, requests.count { it == "GET /bookings/42/details" })
        assertFalse(requests.any { it.startsWith("POST ") })
    }

    @Test fun heldCodeFromAccountACannotReplaceNewAccountBAgreement() {
        assertEquals(7, ApiClient.myUserId())
        holdCode = true
        ApiClient.testTimeoutMs = 45000
        mount(AppLanguage.Ru)
        waitFor { held.count == 0L }
        val oldRead = requestJobs.reads.single { it.path == "/bookings/42/boarding-code" }
        fun assertAHeld() {
            assertEquals(0L, held.count)
            assertEquals(1L, release.count)
            assertEquals(1L, returned.count)
            assertFalse(oldRead.transport.isCompleted)
            assertFalse(oldRead.caller.isCompleted)
        }
        assertAHeld()
        val generationA = ApiClient.queueSessionGeneration()
        compose.runOnIdle {
            ApiClient.logout()
            ApiClient.saveToken(TripPassAuthorityFixture.jwt(8))
        }
        assertNotEquals(generationA, ApiClient.queueSessionGeneration())
        assertAHeld()
        plain = MemoryDiskPreferences(); secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, secure)
        assertTrue(TripPassStore.save(context, positivePass(8)))
        assertEquals("sbp", diskJson().getString("pay_method"))
        assertEquals(900, diskJson().getInt("pay_amount"))
        assertEquals(900, diskJson().getInt("price"))
        assertEquals("B-only", diskJson().getString("boarding_code"))
        val bRaw = secure.restarted().getString("pass_42", null)
        assertAHeld()
        release.countDown()
        assertTrue(returned.await(5, TimeUnit.SECONDS))
        // NOT_SAME_ASSERT B-HELD-COMPLETION: old A UI cannot be a completion signal for B.
        waitFor { oldRead.transport.isCompleted && oldRead.caller.isCompleted }
        compose.waitForIdle()
        println("PAYMENT_HELD_COMPLETION phase=${oldRead.phase} cancelled=${oldRead.transport.isCancelled}")
        // A server dispatch returning is not a claim that its body was applied to B.
        noVisiblePrivateText("400 ₽")
        noVisiblePrivateText("Наличными")
        noVisiblePrivateText("4821")
        noVisiblePrivateText(saveWarning)
        assertFalse(OfflineTripSnapshots.currentOwner()?.userId == 7)
        assertEquals(bRaw, secure.restarted().getString("pass_42", null))
        assertEquals("sbp", diskJson().getString("pay_method"))
        assertEquals(900, diskJson().getInt("pay_amount"))
        assertEquals("B-only", diskJson().getString("boarding_code"))
        assertFalse(requests.any { it.startsWith("POST /bookings/") })
    }

    @Test fun readonlyAgreementShowsExplicitZero() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PayAgreementBlock(editable = false, method = "cash", amountText = "", summaryAmount = 0,
                    onMethod = {}, onAmount = {})
            }
        }
        compose.onNodeWithText("Наличными").assertIsDisplayed()
        compose.onNodeWithText("0 ₽").assertIsDisplayed()
    }

    /** NOT_SAME_ASSERT B-COLD-LEGACY: empty-gate model, not proof of a new process. */
    @Test fun legacySnapshotDoesNotInventAPaymentMethodRu() = legacyUnverified(AppLanguage.Ru)
    @Test fun legacySnapshotDoesNotInventAPaymentMethodBa() = legacyUnverified(AppLanguage.Ba)
    private fun legacyUnverified(language: AppLanguage) {
        assertTrue(secure.edit().putString("pass_42", JSONObject()
            .put("booking_id", 42).put("price", 700).put("boarding_code", "4821")
            .put("payment_note", "наличными").toString()).commit())
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        val raw = secure.restarted().getString("pass_42", null)
        offline = true
        activeCandidate()
        active.value = true
        mount(language)
        val error = if (language == AppLanguage.Ba) "Сәфәр мәғлүмәттәрен йөкләп булманы"
            else "Не удалось загрузить данные поездки"
        val retry = if (language == AppLanguage.Ba) "Мәғлүмәттәрҙе яңынан йөкләргә" else "Повторить загрузку данных"
        waitFor { requests.contains("GET /bookings/42/role") &&
            compose.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(error).assertIsDisplayed()
        compose.onNodeWithText(retry).assertIsDisplayed()
        assertNull(vm.offlineTripSnapshot.value)
        assertNull(vm.activeTrip.value)
        assertFalse(OfflineTripSnapshots.allowsLive(42))
        assertNull(TripPassStore.load(context, 42))
        val heading = if (language == AppLanguage.Ba) "Түләү тураһында нисек килешкәнбеҙ" else "Как договорились платить"
        compose.onAllNodesWithText(heading).assertCountEquals(0)
        compose.onAllNodesWithText("700 ₽", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("4821", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText(if (language == AppLanguage.Ba) "Наличный менән" else "Наличными").assertCountEquals(0)
        compose.onAllNodesWithText("Перевод по СБП").assertCountEquals(0)
        assertEquals(raw, secure.restarted().getString("pass_42", null))
        assertTrue(diskJson().isNull("pay_method"))
        assertTrue(diskJson().isNull("pay_amount"))
        assertFalse(requests.any { it == "GET /bookings/42/boarding-code" })
        assertFalse(requests.any { it.startsWith("POST ") })
    }
}
