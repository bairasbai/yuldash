package com.yuldash.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ColdTripPassport
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.OfflineTripSnapshotPolicy
import com.yuldash.app.data.SnapshotClock
import com.yuldash.app.data.SnapshotPersistence
import com.yuldash.app.data.SnapshotPreferencesDisk
import com.yuldash.app.data.SnapshotWiringFixture
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.TripPassAuthorityFixture
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1600dp")
internal class ColdTripPassportUiTest : SnapshotWiringFixture() {
    private val effectContext = ClientJobContext()
    @get:Rule val compose = createComposeRule(effectContext = effectContext)
    private var passPlain = MemoryDiskPreferences()
    private var passSecure = MemoryDiskPreferences()

    private class ClientJobContext : ThreadContextElement<Job?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ClientJobContext>
        private val current = ThreadLocal<Job?>()
        override fun updateThreadContext(context: CoroutineContext): Job? = current.get().also { current.set(context[Job]) }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Job?) { current.set(oldState) }
        fun currentJob(): Job = requireNotNull(current.get())
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before fun installStores() {
        TripPassStore.initStores(passPlain, passSecure)
    }

    private suspend fun saveCold(): ColdTripPassport {
        OfflineTripSnapshots.init(context)
        ApiClient.getBookingDetails(11).getOrThrow()
        ApiClient.getBoardingCode(11).getOrThrow()
        val pass = requireNotNull(OfflineTripSnapshots.privatePass(11))
        assertEquals(SnapshotPersistence.COMMITTED, OfflineTripSnapshots.saveColdPassport(context, pass,
            expectedGeneration = ApiClient.queueSessionGeneration()))
        passPlain = passPlain.restarted(); passSecure = passSecure.restarted()
        TripPassStore.initStores(passPlain, passSecure)
        reopenAuthority()
        OfflineTripSnapshots.init(context)
        return requireNotNull(OfflineTripSnapshots.loadColdPassport(owner(), 11))
    }

    private fun mount(saved: ColdTripPassport, language: AppLanguage, dark: Boolean, lifecycleOwner: TestOwner) {
        val vm = YuldashViewModel(SavedStateHandle())
        vm.openOfflineTrip(saved.snapshot, 0)
        compose.setContent {
            YuldashTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppLanguage provides language, LocalLifecycleOwner provides lifecycleOwner) {
                    GuardedActiveTripDestination(vm, ApiClient.queueSessionGeneration(), contacts = emptyList(),
                        terminalHint = null, chatOpen = false, onBack = {}, onTripEnd = {}, onSos = {}, onSupport = {},
                        onTrustedContacts = {}, onTripStopped = {}, onOpenReceipt = {}, currentReceiptBookingId = { 0 })
                }
            }
        }
        compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED }
    }

    private fun show(text: String) {
        compose.waitUntil(5000) {
            compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        runCatching {
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .performScrollToNode(hasText(text))
        }
        compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun cardDisplaysAllPrivateFields() {
        val saved = runBlocking { saveCold() }
        compose.setContent {
            YuldashTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    ColdTripPassportCard(saved, isCurrent = { true })
                }
            }
        }
        show("Паспорт поездки")
        show("QA-plate")
        show("QA RU · current-pin")
        show("4821")
        show("200 ₽ · Наличными")
    }

    private fun journey(language: AppLanguage, dark: Boolean) {
        val saved = runBlocking { saveCold() }
        server.shutdown()
        val lifecycleOwner = TestOwner()
        mount(saved, language, dark, lifecycleOwner)
        val calls = requests.size
        show(if (language == AppLanguage.Ba) "Сәфәр паспорты" else "Паспорт поездки")
        assertTrue(OfflineTripSnapshots.canDrawCold(saved))
        assertFalse(OfflineTripSnapshots.allowsLive(11))
        assertNull(OfflineTripSnapshots.privatePass(11))
        assertNull(TripPassStore.load(context, 11))
        assertEquals("Cold UI must not issue network or mutation requests", calls, requests.size)
        assertTrue(requests.none { it.method == "POST" })
        compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED }
    }

    @Test fun coldPassportIsReadOnlyInRussianDark() = journey(AppLanguage.Ru, true)

    @Test fun coldPassportIsReadOnlyInBashkirLight() = journey(AppLanguage.Ba, false)

    @Test fun backgroundHidesColdPrivatePixelsAndAccessibility() {
        val saved = runBlocking { saveCold() }
        server.shutdown()
        val lifecycleOwner = TestOwner()
        mount(saved, AppLanguage.Ru, true, lifecycleOwner)
        assertTrue(OfflineTripSnapshots.canDrawCold(saved))
        show("4821")
        compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        val hidden = SemanticsMatcher.keyIsDefined(SemanticsProperties.InvisibleToUser)
        for (marker in listOf("4821", "QA-plate", "current-pin", "+70000000000")) {
            compose.onAllNodes(hasText(marker, substring = true) and !hidden and !hasAnyAncestor(hidden),
                useUnmergedTree = true).assertCountEquals(0)
        }
        compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED }
    }

    @Test fun repeatedDetailsWithSameCodeRewriteColdGrant() {
        OfflineTripSnapshots.init(context)
        val details = runBlocking { ApiClient.getBookingDetails(11).getOrThrow() }
        runBlocking {
            ApiClient.getBoardingCode(11).getOrThrow()
            val pass = requireNotNull(OfflineTripSnapshots.privatePass(11))
            assertEquals(SnapshotPersistence.COMMITTED, OfflineTripSnapshots.saveColdPassport(context, pass,
                expectedGeneration = ApiClient.queueSessionGeneration()))
        }
        val initialCodeRevision = requireNotNull(OfflineTripSnapshots.projection(11)).codeProof?.request?.ticket?.sequence
        val latest = mutableStateOf(details)
        compose.setContent {
            OfflineTripSnapshots.version.collectAsState().value
            val codeRevision = OfflineTripSnapshots.projection(11)?.codeProof?.request?.ticket?.sequence
            rememberActiveTripOfflinePass(11, ApiClient.queueSessionGeneration(), "4821", null, "passenger",
                latest.value, "onboard", codeRevision) { OfflineTripSnapshots.allowsLive(11) }
        }
        runBlocking {
            val next = ApiClient.getBookingDetails(11).getOrThrow()
            compose.runOnIdle { latest.value = next }
            ApiClient.getBoardingCode(11).getOrThrow()
        }
        compose.waitUntil(5000) {
            val raw = SnapshotPreferencesDisk(meta, secure).read(owner().namespace)
            raw != null && org.json.JSONObject(raw).getJSONObject("records").getJSONObject("11").has("cold_grant")
        }
        val currentCodeRevision = requireNotNull(OfflineTripSnapshots.projection(11)).codeProof?.request?.ticket?.sequence
        assertTrue(currentCodeRevision != null && currentCodeRevision > requireNotNull(initialCodeRevision))
        assertNotNull(runBlocking { OfflineTripSnapshots.loadColdPassport(owner(), 11) })
        compose.waitForIdle()
        val settled = OfflineTripSnapshots.version.value
        compose.waitForIdle()
        assertEquals(settled, OfflineTripSnapshots.version.value)
    }

    @Test fun loadingPrecedesColdPassportContent() {
        val saved = runBlocking { saveCold() }
        server.shutdown()
        val lifecycleOwner = TestOwner()
        compose.mainClock.autoAdvance = false
        try {
            mount(saved, AppLanguage.Ru, false, lifecycleOwner)
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                useUnmergedTree = true).assertExists()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        show("Паспорт поездки")
        compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED }
    }

    @Test fun removedGrantShowsUnavailableWithoutPrivateFields() {
        val saved = runBlocking { saveCold() }
        assertEquals(TripPassStore.RemovalResult.CLEARED,
            TripPassStore.requestRemoval(context, 11, expectedGeneration = ApiClient.queueSessionGeneration()))
        server.shutdown()
        val lifecycleOwner = TestOwner()
        val calls = requests.size
        mount(saved, AppLanguage.Ru, false, lifecycleOwner)
        show("Сохранённые данные устарели или недоступны")
        compose.onAllNodes(hasText("4821"), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasText("QA-plate"), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasText("+70000000000"), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Повторить").assertIsEnabled()
        assertFalse(OfflineTripSnapshots.allowsLive(11))
        assertEquals(calls, requests.size)
        compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED }
    }

    @Test fun hideClearsPrivateAndDoesNotRestoreAfterReopen() {
        val saved = runBlocking { saveCold() }
        server.shutdown()
        val lifecycleOwner = TestOwner()
        mount(saved, AppLanguage.Ru, false, lifecycleOwner)
        show("Паспорт поездки")
        val calls = requests.size
        compose.onNodeWithText(HIDE).performClick()
        show(CLEARED)
        assertNoPrivate()
        assertEquals(calls, requests.size)
        reopenColdStores()
        assertNull(runBlocking { OfflineTripSnapshots.loadColdPassport(owner(), 11) })
        compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED }
    }

    @Test fun failedHideWarnsAndRetriesOnlyLocalRemoval() {
        val saved = runBlocking { saveCold() }
        server.shutdown()
        val lifecycleOwner = TestOwner()
        mount(saved, AppLanguage.Ru, true, lifecycleOwner)
        show("Паспорт поездки")
        val calls = requests.size
        passPlain.failWriteOf = com.yuldash.app.data.TripPassDeletion.KEY
        try {
            compose.onNodeWithText(HIDE).performClick()
            show(NOT_SAVED)
            assertNoPrivate()
            compose.onNodeWithText("Повторить").assertDoesNotExist()
            compose.onNodeWithText("Повторить скрытие").assertIsDisplayed()
            passPlain.failWriteOf = null
            compose.onNodeWithText("Повторить скрытие").performClick()
            show(CLEARED)
            assertEquals(calls, requests.size)
            reopenColdStores()
            assertNull(runBlocking { OfflineTripSnapshots.loadColdPassport(owner(), 11) })
        } finally {
            passPlain.failWriteOf = null
            compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED }
        }
    }

    @Test fun deferredHideCanFinishWithoutClaimingDeletionOrNetwork() {
        val saved = runBlocking { saveCold() }
        server.shutdown()
        val lifecycleOwner = TestOwner()
        mount(saved, AppLanguage.Ru, false, lifecycleOwner)
        show("Паспорт поездки")
        val calls = requests.size
        passSecure.failRemovalOf = "pass_11"
        try {
            compose.onNodeWithText(HIDE).performClick()
            show(DEFERRED)
            assertNoPrivate()
            assertTrue(passSecure.restarted().contains("pass_11"))
            show("Приложение повторит очистку при следующем запуске.")
            compose.onAllNodes(hasText("Приложение завершит очистку при следующем запуске."),
                useUnmergedTree = true).assertCountEquals(0)
            compose.onAllNodes(hasText("удалено", substring = true, ignoreCase = true),
                useUnmergedTree = true).assertCountEquals(0)
            assertEquals(TripPassStore.RemovalResult.DEFERRED,
                TripPassStore.requestRemoval(context, 11, expectedGeneration = ApiClient.queueSessionGeneration()))
            reopenColdStores(preserveRemovalFailure = true)
            assertEquals(TripPassStore.RemovalResult.DEFERRED,
                TripPassStore.requestRemoval(context, 11, expectedGeneration = ApiClient.queueSessionGeneration()))
            passSecure.failRemovalOf = null
            compose.onNodeWithText("Завершить очистку").performClick()
            show(CLEARED)
            assertEquals(calls, requests.size)
            reopenColdStores()
            assertNull(runBlocking { OfflineTripSnapshots.loadColdPassport(owner(), 11) })
        } finally {
            passSecure.failRemovalOf = null
            compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED }
        }
    }

    @Test fun pendingHideImmediatelyHidesPrivateAndHasNoSecondAction() {
        val saved = runBlocking { saveCold() }
        server.shutdown()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocked = blockingPreferences(passSecure, "pass_11", started, release)
        TripPassStore.initStores(passPlain, blocked)
        val lifecycleOwner = TestOwner()
        mount(saved, AppLanguage.Ru, false, lifecycleOwner)
        show("Паспорт поездки")
        val calls = requests.size
        try {
            compose.onNodeWithText(HIDE).performClick()
            compose.waitUntil(5000) { started.count == 0L }
            show(RUNNING)
            assertNoPrivate()
            compose.onNodeWithText(HIDE).assertDoesNotExist()
            assertEquals(calls, requests.size)
        } finally {
            release.countDown()
        }
        show(CLEARED)
        assertNoPrivate()
        compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED }
    }

    @Test fun lateHideResultCannotChangeAnotherOwnerOrDeleteReplacement() {
        val saved = runBlocking { saveCold() }
        val ownerA = owner()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocked = blockingSnapshotDisk(SnapshotPreferencesDisk(meta, secure), started, release)
        OfflineTripSnapshots.installForTest(blocked) { clock }
        OfflineTripSnapshots.activate(ownerA.userId, ownerA.scope, ownerA.generation)
        OfflineTripSnapshots.init(context)
        server.shutdown()
        val (vm, lifecycleOwner) = mountForRace(saved, AppLanguage.Ru, false)
        show("Паспорт поездки")
        val calls = requests.size
        try {
            compose.onNodeWithText(HIDE).performClick()
            compose.waitUntil(5000) { started.count == 0L }
            val ownerB = ownerA.copy(userId = 8, scope = java.util.UUID.randomUUID().toString())
            compose.runOnIdle {
                OfflineTripSnapshots.activate(ownerB.userId, ownerB.scope, ownerB.generation)
                vm.openOfflineTrip(saved.snapshot.copy(owner = ownerB), 0)
            }
            assertTrue(passSecure.edit().putString("pass_11",
                saved.pass.copy(boardingCode = "B").toJson().toString()).commit())
        } finally {
            release.countDown()
        }
        compose.waitForIdle()
        assertNoPrivate()
        compose.onAllNodes(hasText(CLEARED, substring = true), useUnmergedTree = true).assertCountEquals(0)
        assertEquals("B", TripPassAuthorityFixture.durable(passSecure, 11)?.boardingCode)
        assertEquals(calls, requests.size)
        compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED }
    }

    @Test fun expiredPassportStillAllowsExplicitLocalHide() {
        val saved = runBlocking { saveCold() }
        val expired = 1000 + OfflineTripSnapshotPolicy.MAX_AGE_MS
        clock = SnapshotClock(3, expired, expired, expired)
        assertFalse(OfflineTripSnapshots.canDrawCold(saved))
        var calls = 0
        compose.setContent {
            ColdTripPassportCard(saved, isCurrent = { true }, onHidePrivate = { calls++ })
        }
        compose.onNodeWithText(HIDE).performClick()
        assertEquals(1, calls)
    }

    private fun assertNoPrivate() {
        compose.waitForIdle()
        for (marker in listOf("4821", "QA-plate", "current-pin", "+70000000000", "200 ₽", "Наличными")) {
            compose.onAllNodes(hasText(marker, substring = true), useUnmergedTree = true).assertCountEquals(0)
        }
    }

    private fun reopenColdStores(preserveRemovalFailure: Boolean = false) {
        passSecure = passSecure.restarted()
        if (preserveRemovalFailure) passSecure.failRemovalOf = "pass_11"
        TripPassStore.initStores(passPlain.restarted(), passSecure)
        reopenAuthority()
        OfflineTripSnapshots.init(context)
    }

    private fun mountForRace(saved: ColdTripPassport, language: AppLanguage, dark: Boolean): Pair<YuldashViewModel, TestOwner> {
        val vm = YuldashViewModel(SavedStateHandle())
        vm.openOfflineTrip(saved.snapshot, 0)
        val lifecycleOwner = TestOwner()
        compose.setContent {
            YuldashTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppLanguage provides language, LocalLifecycleOwner provides lifecycleOwner) {
                    GuardedActiveTripDestination(vm, ApiClient.queueSessionGeneration(), contacts = emptyList(),
                        terminalHint = null, chatOpen = false, onBack = {}, onTripEnd = {}, onSos = {}, onSupport = {},
                        onTrustedContacts = {}, onTripStopped = {}, onOpenReceipt = {}, currentReceiptBookingId = { 0 })
                }
            }
        }
        compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED }
        return vm to lifecycleOwner
    }

    @Test fun hideWorksInBashkirDark() {
        val saved = runBlocking { saveCold() }
        server.shutdown()
        val lifecycleOwner = TestOwner()
        mount(saved, AppLanguage.Ba, true, lifecycleOwner)
        show("Сәфәр паспорты")
        val calls = requests.size
        compose.onNodeWithText("Шәхси мәғлүмәтте был ҡоротманан йәшереү").performClick()
        show("Шәхси мәғлүмәт был ҡоротмала йәшерелде")
        assertNoPrivate()
        assertEquals(calls, requests.size)
        compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED }
    }

    @Test fun parentRoleFailureCannotFetchDetailsAfterHide() {
        val saved = runBlocking { saveCold() }
        val roleStarted = java.util.concurrent.CountDownLatch(1)
        val roleRelease = java.util.concurrent.CountDownLatch(1)
        val roleResponded = java.util.concurrent.CountDownLatch(1)
        handler = { request ->
            if (request.path == "/bookings/11/role") {
                roleStarted.countDown()
                check(roleRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "ROLE was not released" }
                roleResponded.countDown()
                okhttp3.mockwebserver.MockResponse().setResponseCode(503).setBody("{}")
            } else defaultResponse(request)
        }
        ApiClient.testTimeoutMs = 10000
        val (vm, lifecycleOwner) = mountForRace(saved, AppLanguage.Ru, false)
        try {
            show("Паспорт поездки")
            compose.waitUntil(5000) { roleStarted.count == 0L }
            val calls = requests.size
            compose.onNodeWithText(HIDE).performClick()
            show(CLEARED)
            assertNoPrivate()
            assertEquals(calls, requests.size)
            roleRelease.countDown()
            compose.waitUntil(5000) { roleResponded.count == 0L }
            compose.waitForIdle()
            repeat(50) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                compose.mainClock.advanceTimeBy(50)
                Thread.sleep(10)
            }
            compose.waitForIdle()
            assertEquals("A delayed parent ROLE failure must not fetch DETAILS after hiding", calls, requests.size)
            assertTrue(requests.none { it.method == "POST" })
            assertNotNull(vm.offlineTripSnapshot.value)
        } finally {
            roleRelease.countDown()
            ApiClient.testTimeoutMs = 500
            compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED }
        }
    }

    @Test fun parentGateBeforePublishSendsNoRequests() {
        OfflineTripSnapshots.init(context)
        val slot = ParentTransportGate()
        slot.close()
        handler = { okhttp3.mockwebserver.MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("{}") }
        val result = runCatching { runBlocking { readBookingNavigation(11, singleAttemptTransport = true, transportSlot = slot) } }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(0, requests.size)
    }

    @Test fun parentGateBeforeRefreshSendsNoRoleOrRefresh() {
        OfflineTripSnapshots.init(context)
        installRefresh()
        val gate = ParentTransportGate()
        val revocations = java.util.concurrent.atomic.AtomicInteger()
        val authority = object : com.yuldash.app.data.SnapshotTransportAuthority {
            override val gate: com.yuldash.app.data.SnapshotAuthorityGate
                get() = OfflineTripSnapshots.gate
            override fun currentOwner(): com.yuldash.app.data.SnapshotOwner? = OfflineTripSnapshots.currentOwner()
            override fun cacheEnabled(owner: com.yuldash.app.data.SnapshotOwner): Boolean =
                OfflineTripSnapshots.cacheEnabled(owner)
            override fun revoked(owner: com.yuldash.app.data.SnapshotOwner, bookingId: Int) {
                revocations.incrementAndGet()
                gate.close()
                OfflineTripSnapshots.revoked(owner, bookingId)
            }
            override suspend fun coldDenied(ticket: com.yuldash.app.data.SnapshotTicket, bookingId: Int) =
                OfflineTripSnapshots.coldDenied(ticket, bookingId)
            override suspend fun close(ticket: com.yuldash.app.data.SnapshotTicket?, bookingId: Int) =
                OfflineTripSnapshots.close(ticket, bookingId)
        }
        val roleCalls = java.util.concurrent.atomic.AtomicInteger()
        val refreshCalls = java.util.concurrent.atomic.AtomicInteger()
        handler = { request ->
            when (request.path) {
                "/bookings/11/role" -> {
                    roleCalls.incrementAndGet()
                    if (roleCalls.get() == 1) okhttp3.mockwebserver.MockResponse().setResponseCode(401).setBody("{}")
                    else okhttp3.mockwebserver.MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("{}")
                }
                "/auth/refresh" -> {
                    refreshCalls.incrementAndGet()
                    okhttp3.mockwebserver.MockResponse().setBody("""{"access_token":"qa-new","token":"qa-new","refresh_token":"qa-new-refresh"}""")
                }
                else -> defaultResponse(request)
            }
        }
        ApiClient.testTimeoutMs = 10000
        try {
            val read = com.yuldash.app.data.SnapshotTransportRead(11, com.yuldash.app.data.SnapshotAuthorityKind.ROLE,
                ApiClient.queueSessionGeneration(), authority)
            assertNotNull("ROLE must have current authority before the request", read.ticket)
            val result = runCatching {
                runBlocking {
                    ApiClient.readTripSnapshotJson("/bookings/11/role", read,
                        singleAttemptTransport = true, transportSlot = gate)
                }
            }
            val failure = result.exceptionOrNull() ?: result.getOrNull()?.exceptionOrNull()
            assertTrue("result=$result, revocations=$revocations, role=$roleCalls, refresh=$refreshCalls",
                failure is kotlinx.coroutines.CancellationException || failure is java.io.IOException)
            assertEquals(1, revocations.get())
            assertTrue("gate must be closed", runCatching { gate.ensureOpen() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals(1, roleCalls.get())
            assertEquals(0, refreshCalls.get())
            assertEquals(0, requests.count { it.path == "/bookings/11/details" })
        } finally {
            ApiClient.testTimeoutMs = 500
        }
    }

    @Test fun parentDetailsCannotFinishAfterHide() {
        OfflineTripSnapshots.init(context)
        val detailsStarted = java.util.concurrent.CountDownLatch(1)
        val detailsRelease = java.util.concurrent.CountDownLatch(1)
        val traces = java.util.concurrent.CopyOnWriteArrayList<String>()
        handler = { request ->
            if (request.path == "/bookings/11/details") {
                detailsStarted.countDown()
                check(detailsRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "DETAILS was not released" }
                okhttp3.mockwebserver.MockResponse().setBody(detailsJson().toString())
            } else defaultResponse(request)
        }
        ApiClient.testTimeoutMs = 10000
        ApiClient.testTrace = { traces += it }
        val gate = ParentTransportGate()
        try {
            runBlocking {
                val read = async(kotlinx.coroutines.Dispatchers.Default) {
                    ApiClient.getBookingDetails(11, singleAttemptTransport = true, transportSlot = gate)
                }
                assertTrue(detailsStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                gate.close()
                detailsRelease.countDown()
                kotlinx.coroutines.withTimeout(5000) { read.join() }
                assertTrue("DETAILS must not complete after the parent gate closes: $traces",
                    traces.none { it.startsWith("ВЫШЛИ") })
            }
        } finally {
            gate.close()
            detailsRelease.countDown()
            ApiClient.testTimeoutMs = 500
            ApiClient.testTrace = null
        }
    }

    @Test fun parentDetailsBeforePublishSendsNoRequests() {
        OfflineTripSnapshots.init(context)
        val gate = ParentTransportGate()
        gate.close()
        handler = { defaultResponse(it) }
        val result = runCatching {
            runBlocking { ApiClient.getBookingDetails(11, singleAttemptTransport = true, transportSlot = gate) }
        }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(0, requests.size)
    }

    @Test fun parentDetailsCannotRefreshAfterHide() {
        OfflineTripSnapshots.init(context)
        installRefresh()
        val detailsStarted = java.util.concurrent.CountDownLatch(1)
        val detailsRelease = java.util.concurrent.CountDownLatch(1)
        val refreshCalls = java.util.concurrent.atomic.AtomicInteger()
        handler = { request ->
            when (request.path) {
                "/bookings/11/details" -> {
                    detailsStarted.countDown()
                    check(detailsRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "DETAILS was not released" }
                    okhttp3.mockwebserver.MockResponse().setResponseCode(401).setBody("{}")
                }
                "/auth/refresh" -> {
                    refreshCalls.incrementAndGet()
                    okhttp3.mockwebserver.MockResponse().setBody("""{"access_token":"qa-new","token":"qa-new","refresh_token":"qa-new-refresh"}""")
                }
                else -> defaultResponse(request)
            }
        }
        ApiClient.testTimeoutMs = 10000
        val gate = ParentTransportGate()
        try {
            runBlocking {
                val read = async(kotlinx.coroutines.Dispatchers.Default) {
                    ApiClient.getBookingDetails(11, singleAttemptTransport = true, transportSlot = gate)
                }
                assertTrue(detailsStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                gate.close()
                detailsRelease.countDown()
                kotlinx.coroutines.withTimeout(5000) { read.join() }
                assertEquals("DETAILS must not refresh after the parent gate closes; paths=${requests.map { it.path }}",
                    0, refreshCalls.get())
            }
        } finally {
            gate.close()
            detailsRelease.countDown()
            ApiClient.testTimeoutMs = 500
        }
    }

    @Test fun closedParentGateCannotStartColdAfterWarmRead() {
        val saved = runBlocking { saveCold() }
        val owner = saved.snapshot.owner
        val warmStarted = java.util.concurrent.CountDownLatch(1)
        val warmRelease = java.util.concurrent.CountDownLatch(1)
        val coldReads = java.util.concurrent.atomic.AtomicInteger()
        val firstRead = java.util.concurrent.atomic.AtomicBoolean(true)
        val actual = SnapshotPreferencesDisk(meta, secure)
        val disk = object : com.yuldash.app.data.SnapshotDisk {
            override fun read(namespace: String): String? {
                if (firstRead.compareAndSet(true, false)) {
                    warmStarted.countDown()
                    check(warmRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Warm read was not released" }
                    return null
                }
                return actual.read(namespace)
            }
            override fun write(namespace: String, value: String) = actual.write(namespace, value)
            override fun barrier(key: String) = actual.barrier(key)
            override fun setBarrier(key: String, value: String?) = actual.setBarrier(key, value)
            override fun retire(namespace: String) = actual.retire(namespace)
            override fun purgeExcept(namespace: String?) = actual.purgeExcept(namespace)
        }
        OfflineTripSnapshots.installForTest(disk) { clock }
        OfflineTripSnapshots.activate(owner.userId, owner.scope, owner.generation)
        OfflineTripSnapshots.init(context)
        val countedSecure = java.lang.reflect.Proxy.newProxyInstance(
            android.content.SharedPreferences::class.java.classLoader,
            arrayOf(android.content.SharedPreferences::class.java)) { _, method, args ->
            if (method.name == "getString" && args?.firstOrNull() == "pass_11") coldReads.incrementAndGet()
            if (args == null) method.invoke(passSecure) else method.invoke(passSecure, *args)
        } as android.content.SharedPreferences
        TripPassStore.initStores(passPlain, countedSecure)
        coldReads.set(0)
        handler = { request ->
            if (request.path == "/bookings/11/role") okhttp3.mockwebserver.MockResponse().setResponseCode(503).setBody("{}")
            else defaultResponse(request)
        }
        val gate = ParentTransportGate()
        runBlocking {
            try {
                val read = async(kotlinx.coroutines.Dispatchers.Default) {
                    readBookingNavigation(11, singleAttemptTransport = true, transportSlot = gate)
                }
                val reached = warmStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)
                val diagnostic = if (read.isCompleted) runCatching { read.await() }.toString() else "pending"
                assertTrue("paths=${requests.map { it.path }}, read=$diagnostic, ownerCurrent=${OfflineTripSnapshots.current(owner)}", reached)
                gate.close()
                warmRelease.countDown()
                val result = kotlinx.coroutines.withTimeoutOrNull(5000) { read.await() }
                assertNotNull(result)
                assertEquals("A closed parent must not admit a new cold restore after warm read", 0, coldReads.get())
            } finally {
                gate.close()
                warmRelease.countDown()
            }
        }
    }

    @Test fun parentRoleRetryAfterCannotRepeatAfterHide() {
        val saved = runBlocking { saveCold() }
        val roleStarted = java.util.concurrent.CountDownLatch(1)
        val roleRelease = java.util.concurrent.CountDownLatch(1)
        val roleResponded = java.util.concurrent.CountDownLatch(1)
        handler = { request ->
            if (request.path == "/bookings/11/role") {
                roleStarted.countDown()
                check(roleRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "ROLE was not released" }
                roleResponded.countDown()
                okhttp3.mockwebserver.MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("{}")
            } else defaultResponse(request)
        }
        ApiClient.testTimeoutMs = 10000
        val (vm, lifecycleOwner) = mountForRace(saved, AppLanguage.Ru, false)
        try {
            show("Паспорт поездки")
            compose.waitUntil(5000) { roleStarted.count == 0L }
            val calls = requests.size
            val detailsBefore = requests.count { it.path == "/bookings/11/details" }
            compose.onNodeWithText(HIDE).performClick()
            show(CLEARED)
            assertNoPrivate()
            assertEquals(calls, requests.size)
            roleRelease.countDown()
            compose.waitUntil(5000) { roleResponded.count == 0L }
            compose.waitForIdle()
            repeat(50) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                compose.mainClock.advanceTimeBy(50)
                Thread.sleep(10)
            }
            compose.waitForIdle()
            assertEquals("503 + Retry-After: 0 must not repeat ROLE after hiding", calls, requests.size)
            assertEquals("No DETAILS may follow the delayed ROLE", detailsBefore,
                requests.count { it.path == "/bookings/11/details" })
            assertTrue(requests.none { it.method == "POST" })
            assertNotNull(vm.offlineTripSnapshot.value)
        } finally {
            roleRelease.countDown()
            ApiClient.testTimeoutMs = 500
            compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED }
        }
    }

    @Test fun hideCancelsParentTransportBeforeItsAutomaticRetry() {
        val saved = runBlocking { saveCold() }
        val roleStarted = java.util.concurrent.CountDownLatch(1)
        val roleRelease = java.util.concurrent.CountDownLatch(1)
        val traces = java.util.concurrent.CopyOnWriteArrayList<String>()
        handler = { request ->
            if (request.path == "/bookings/11/role") {
                roleStarted.countDown()
                check(roleRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "ROLE was not released" }
                okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
            } else defaultResponse(request)
        }
        ApiClient.testTimeoutMs = null
        ApiClient.testRetryTimeoutMs = 10000
        var transport: Job? = null
        ApiClient.testTrace = { line ->
            traces += line
            if (line == "вошли в вызов GET /bookings/11/role") transport = effectContext.currentJob()
        }
        val (vm, lifecycleOwner) = mountForRace(saved, AppLanguage.Ru, false)
        try {
            show("Паспорт поездки")
            compose.waitUntil(5000) { roleStarted.count == 0L }
            compose.onNodeWithText(HIDE).performClick()
            show(CLEARED)
            val roleBefore = requests.count { it.path == "/bookings/11/role" }
            assertEquals("Only one ROLE may be in flight before hiding", 1, roleBefore)
            assertTrue("Transport must be cancelled: $transport", transport?.isCancelled == true)
            val detailsBefore = requests.count { it.path == "/bookings/11/details" }
            roleRelease.countDown()
            Thread.sleep(1600)
            compose.waitForIdle()
            assertEquals("Parent must not retry ROLE after hiding: ${traces.toList()}", roleBefore,
                requests.count { it.path == "/bookings/11/role" })
            assertEquals("Parent must not fetch DETAILS after hiding", detailsBefore,
                requests.count { it.path == "/bookings/11/details" })
        } finally {
            roleRelease.countDown()
            ApiClient.testTrace = null
            ApiClient.testRetryTimeoutMs = null
            ApiClient.testTimeoutMs = 500
            compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED }
        }
    }

    @Test fun retryRemovalRequiresResumedLifecycle() {
        val saved = runBlocking { saveCold() }
        server.shutdown()
        val (_, lifecycleOwner) = mountForRace(saved, AppLanguage.Ru, false)
        show("Паспорт поездки")
        val calls = requests.size
        passSecure.failRemovalOf = "pass_11"
        try {
            compose.onNodeWithText(HIDE).performClick()
            show(DEFERRED)
            passSecure.failRemovalOf = null
            compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.STARTED }
            compose.onNodeWithText("Завершить очистку").performClick()
            compose.waitForIdle()
            compose.onNodeWithText(DEFERRED).assertIsDisplayed()
            assertTrue("STARTED must not remove the saved private pass", passSecure.restarted().contains("pass_11"))
            compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED }
            compose.onNodeWithText("Завершить очистку").performClick()
            show(CLEARED)
            assertFalse(passSecure.restarted().contains("pass_11"))
            assertEquals(calls, requests.size)
            assertNoPrivate()
        } finally {
            passSecure.failRemovalOf = null
            compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED }
        }
    }

    private fun blockingSnapshotDisk(
        actual: com.yuldash.app.data.SnapshotDisk,
        started: java.util.concurrent.CountDownLatch,
        release: java.util.concurrent.CountDownLatch,
    ) = object : com.yuldash.app.data.SnapshotDisk {
        var first = true
        override fun read(namespace: String) = actual.read(namespace)
        override fun write(namespace: String, value: String): Boolean {
            if (first) {
                first = false
                started.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Blocked snapshot write was not released" }
            }
            return actual.write(namespace, value)
        }
        override fun barrier(key: String) = actual.barrier(key)
        override fun setBarrier(key: String, value: String?) = actual.setBarrier(key, value)
        override fun retire(namespace: String) = actual.retire(namespace)
        override fun purgeExcept(namespace: String?) = actual.purgeExcept(namespace)
    }

    private fun blockingPreferences(
        delegate: android.content.SharedPreferences,
        target: String,
        started: java.util.concurrent.CountDownLatch,
        release: java.util.concurrent.CountDownLatch,
    ): android.content.SharedPreferences {
        val targeted = java.util.concurrent.atomic.AtomicBoolean(false)
        fun editorProxy(editor: android.content.SharedPreferences.Editor): android.content.SharedPreferences.Editor =
            java.lang.reflect.Proxy.newProxyInstance(
                android.content.SharedPreferences.Editor::class.java.classLoader,
                arrayOf(android.content.SharedPreferences.Editor::class.java)) { _, method, args ->
                if ((method.name == "putString" || method.name == "remove") && args?.firstOrNull() == target) {
                    targeted.set(true)
                }
                if ((method.name == "commit" || method.name == "apply") && targeted.compareAndSet(true, false)) {
                    started.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Blocked removal was not released" }
                }
                if (args == null) method.invoke(editor) else method.invoke(editor, *args)
            } as android.content.SharedPreferences.Editor
        return java.lang.reflect.Proxy.newProxyInstance(
            android.content.SharedPreferences::class.java.classLoader,
            arrayOf(android.content.SharedPreferences::class.java)) { _, method, args ->
            if (method.name == "edit") editorProxy(delegate.edit()) else {
                if (args == null) method.invoke(delegate) else method.invoke(delegate, *args)
            }
        } as android.content.SharedPreferences
    }

    private companion object {
        const val HIDE = "Скрыть личные данные на этом устройстве"
        const val RUNNING = "Скрываем личные данные…"
        const val CLEARED = "Личные данные скрыты на этом устройстве"
        const val DEFERRED = "Доступ к личным данным закрыт. Очистка не завершена."
        const val NOT_SAVED = "Не удалось подтвердить скрытие. После перезапуска старая копия может появиться снова."
    }
}
