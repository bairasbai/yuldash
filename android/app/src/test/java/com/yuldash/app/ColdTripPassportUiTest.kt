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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
import com.yuldash.app.data.SnapshotPersistence
import com.yuldash.app.data.SnapshotPreferencesDisk
import com.yuldash.app.data.SnapshotWiringFixture
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.runBlocking
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
    @get:Rule val compose = createComposeRule()
    private var passPlain = MemoryDiskPreferences()
    private var passSecure = MemoryDiskPreferences()

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
        vm.openOfflineTrip(saved.snapshot, 0, saved)
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
        compose.waitForIdle()
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
}
