package com.yuldash.app

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic local HTTP and actual components. Server release + bounded pump, not client join. */
abstract class ShareHelpBoundaryHarness {
    @get:Rule val compose = createComposeRule()
    protected val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    protected val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    protected val mounted = mutableStateOf(true)
    protected val slot = mutableStateOf(0)
    protected val target = mutableStateOf(91)
    protected val parentCurrent = mutableStateOf(true)
    protected val link = mutableStateOf("https://example.invalid/t/synthetic-a")
    protected val records = CopyOnWriteArrayList<List<String?>>()
    protected var dismisses = 0
    protected var holdPath = ""
    protected var wrongContact = false
    protected var rotateLink = false
    protected var nullShare = false
    protected var failContacts = false
    protected var failMutation = false
    protected var sharesResponse: String? = null
    protected open fun customResponse(request: RecordedRequest, path: String): MockResponse? = null
    protected val started = CountDownLatch(1)
    protected val release = CountDownLatch(1)
    private val returned = CountDownLatch(1)
    private lateinit var server: MockWebServer
    protected val app: Application get() = ApplicationProvider.getApplicationContext()
    protected val clipboard: ClipboardManager get() = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    @Before fun setupBoundary() {
        ApiClient.resetForTest(); ApiClient.init(app); ApiClient.saveToken(tokenA)
        LocationPrefs.lastLat = 54.735; LocationPrefs.lastLng = 55.958
        NavSignals.activeTaxiTrip.value = 0
        clipboard.clearPrimaryClip(); ShadowToast.reset()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(q: RecordedRequest): MockResponse {
                    val p = q.requestUrl!!.encodedPath
                    records += listOf(q.method, p, q.getHeader("Authorization"), q.body.readUtf8())
                    if (p == holdPath) { started.countDown(); assertTrue(release.await(35, TimeUnit.SECONDS)); returned.countDown() }
                    customResponse(q, p)?.let { return it }
                    val response = when {
                        p == "/trusted-contacts" -> if (failContacts) "{}" else """{"items":[{"id":7,"name":"Контакт А","relation":"Синтетический","phone":"+70000000007"}]}"""
                        p.endsWith("/shares") -> sharesResponse ?: """{"items":[]}"""
                        p.endsWith("/share") -> if (nullShare) "{}" else {
                            val n = if (rotateLink) records.count { it[0] == "POST" && it[1]!!.endsWith("/share") } else 0
                            """{"id":${501 + n},"contact_id":${if (wrongContact) 8 else 7},"token":"synthetic-share${if (rotateLink) "-$n" else ""}"}"""
                        }
                        p.endsWith("/stuck") -> """{"contacts_notified":1,"contacts_total":2}"""
                        p == "/instant/orders/91" -> """{"id":91,"role":"passenger","status":"onboard","from_text":"Пункт А","to_text":"Пункт Б","price_estimate":250,"payment_method":"cash"}"""
                        else -> "{}"
                    }
                    val failure = (p == "/trusted-contacts" && failContacts) || (q.method != "GET" && failMutation)
                    return MockResponse().setHeader("Content-Type", "application/json").setResponseCode(if (failure) 503 else 200).setBody(response)
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 60000
    }
    protected fun pump(ms: Long = 500) {
        repeat((ms / 100).toInt()) { Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        compose.waitForIdle()
    }
    protected fun await(check: () -> Boolean) = compose.waitUntil(15000) {
        Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); check()
    }
    protected fun retain(matcher: SemanticsMatcher): () -> Boolean {
        await { compose.onAllNodes(matcher and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodes(matcher and hasClickAction()).onFirst().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    protected fun text(s: String) = retain(hasText(s))
    protected fun dispatch(a: () -> Boolean) { a(); Shadows.shadowOf(Looper.getMainLooper()).idle() }
    protected fun click(s: String) { val a = text(s); compose.runOnIdle { dispatch(a) } }
    protected fun count(method: String, suffix: String) = records.count { it[0] == method && it[1]!!.endsWith(suffix) }
    protected fun changeOwner(a: () -> Boolean, guest: Boolean = false) {
        compose.runOnIdle { if (guest) ApiClient.logout() else ApiClient.saveToken(tokenB); dispatch(a) }; pump(1500)
    }
    protected fun finishHeld() { release.countDown(); assertTrue(returned.await(8, TimeUnit.SECONDS)); pump(1500) }
    protected fun mountShare() {
        compose.mainClock.autoAdvance = false
        compose.setContent { if (mounted.value) key(slot.value) { YuldashTheme { InstantShareDialog(target.value, onDismiss = { dismisses++ }) } } }
        pump()
    }
    protected fun mountLink() {
        compose.mainClock.autoAdvance = false
        compose.setContent { if (mounted.value) key(slot.value) { YuldashTheme { LiveLinkCard(link.value) } } }; pump()
    }
    protected fun mountTaxiSafety() {
        val order = runBlocking { ApiClient.getInstantOrder(91).getOrThrow() }
        compose.mainClock.autoAdvance = false
        compose.setContent { if (mounted.value) YuldashTheme {
            TaxiTripScreen(order, onCancel = {}, onMinimize = {}, enableLiveTracking = false, mapContent = { Box(it) }, isCurrentParent = { parentCurrent.value })
        } }
        pump(); compose.onNodeWithTag("taxiSheetDragArea").performTouchInput { swipe(center, center.copy(y = center.y - 160f), 300) }; pump()
        val safety = retain(hasContentDescription("Безопасность"))
        compose.runOnIdle { dispatch(safety) }
    }
    protected fun shareAndLink() { mountShare(); click("Контакт А"); await { compose.onAllNodesWithText("Скопировать").fetchSemanticsNodes().isNotEmpty() } }
    @After fun cleanupBoundary() {
        release.countDown(); compose.runOnIdle { mounted.value = false }; pump(300)
        NavSignals.activeTaxiTrip.value = 0; NavSignals.taxiTripOnScreen.value = false; NavSignals.taxiOrderOnScreen.value = false
        LocationPrefs.lastLat = null; LocationPrefs.lastLng = null
        ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstantShareOwnerBoundaryTest : ShareHelpBoundaryHarness() {
    @Test fun currentShareCopiesAndRoutesSyntheticLink() {
        shareAndLink(); click("Скопировать"); click("Отправить")
        assertEquals(ApiClient.apiBase() + "/t/synthetic-share", clipboard.primaryClip!!.getItemAt(0).text.toString())
        val chooser = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action); assertEquals("text/plain", send.type)
        assertTrue(send.getStringExtra(Intent.EXTRA_TEXT)!!.endsWith("/t/synthetic-share"))
        val q = records.single { it[0] == "POST" && it[1]!!.endsWith("/share") }
        assertEquals("Bearer $tokenA", q[2]); assertEquals(7, JSONObject(q[3]!!).getInt("contact_id"))
    }
    @Test fun oldContactCannotUseNextAccount() { mountShare(); changeOwner(text("Контакт А")); assertEquals(0, count("POST", "/share")) }
    @Test fun oldContactCannotUseGuest() { mountShare(); changeOwner(text("Контакт А"), true); assertEquals(0, count("POST", "/share")) }
    @Test fun oldContactCannotUseReplacementOrder() { mountShare(); val a = text("Контакт А"); compose.runOnIdle { target.value = 92 }; pump(); compose.runOnIdle { dispatch(a) }; pump(); assertEquals(0, count("POST", "/share")) }
    @Test fun disposedContactCannotUseRemountedDialog() { mountShare(); val a = text("Контакт А"); compose.runOnIdle { slot.value++ }; pump(); compose.runOnIdle { dispatch(a) }; pump(); assertEquals(0, count("POST", "/share")) }
    @Test fun closeRetiresContactBeforeParentFrame() { mountShare(); val a = text("Контакт А"); val close = text("Закрыть"); compose.runOnIdle { dispatch(close); dispatch(a) }; pump(); assertEquals(1, dismisses); assertEquals(0, count("POST", "/share")) }
    @Test fun oldCloseCannotDismissNextOwner() { mountShare(); changeOwner(text("Закрыть")); assertEquals(0, dismisses) }
    @Test fun duplicateShareWhileHeldSendsOnce() { holdPath = "/instant/orders/91/share"; mountShare(); val a = text("Контакт А"); compose.runOnIdle { dispatch(a); dispatch(a) }; await { started.count == 0L }; pump(); assertEquals(1, count("POST", "/share")); finishHeld() }
    @Test fun wrongContactReplyCannotPublishLink() { wrongContact = true; mountShare(); click("Контакт А"); await { count("POST", "/share") == 1 }; pump(1500); assertTrue(compose.onAllNodesWithText("Скопировать").fetchSemanticsNodes().isEmpty()); assertNull(ShadowToast.getTextOfLatestToast()) }
    @Test fun oldServerNullShareStillDismissesCurrentDialog() { nullShare = true; mountShare(); click("Контакт А"); await { dismisses == 1 }; assertEquals(1, count("POST", "/share")) }
    @Test fun currentShareFailureKeepsExistingDismissBehavior() { failMutation = true; mountShare(); click("Контакт А"); await { dismisses == 1 }; assertNotNull(ShadowToast.getTextOfLatestToast()) }
    @Test fun heldShareCannotPublishAfterNextOwner() { holdPath = "/instant/orders/91/share"; mountShare(); click("Контакт А"); await { started.count == 0L }; compose.runOnIdle { ApiClient.saveToken(tokenB) }; finishHeld(); assertEquals(0, dismisses); assertNull(ShadowToast.getTextOfLatestToast()); assertTrue(compose.onAllNodesWithText("Скопировать").fetchSemanticsNodes().isEmpty()) }
    @Test fun heldContactsCannotFetchSharesUnderNextOwner() { holdPath = "/trusted-contacts"; mountShare(); await { started.count == 0L }; compose.runOnIdle { ApiClient.saveToken(tokenB) }; finishHeld(); assertFalse(records.any { it[1]!!.endsWith("/shares") && it[2] == "Bearer $tokenB" }) }
    @Test fun currentRevokeRetiresLinkAndRetainedRoutes() { shareAndLink(); val copy = text("Скопировать"); val send = text("Отправить"); click("Отозвать"); await { count("DELETE", "/share/501") == 1 }; pump(1500); compose.runOnIdle { dispatch(copy); dispatch(send) }; pump(); assertFalse(clipboard.hasPrimaryClip()); assertNull(Shadows.shadowOf(app).nextStartedActivity); assertTrue(compose.onAllNodesWithText("Скопировать").fetchSemanticsNodes().isEmpty()) }
    @Test fun duplicateRevokeWhileHeldSendsOnce() { shareAndLink(); holdPath = "/instant/orders/91/share/501"; val a = text("Отозвать"); compose.runOnIdle { dispatch(a); dispatch(a) }; await { started.count == 0L }; pump(); assertEquals(1, count("DELETE", "/share/501")); finishHeld() }
    @Test fun oldRevokeCannotUseNextAccount() { shareAndLink(); changeOwner(text("Отозвать")); assertEquals(0, count("DELETE", "/share/501")) }
    @Test fun currentRevokeErrorPreservesLinkAndCanRetry() { shareAndLink(); sharesResponse = """{"items":[{"id":501,"contact_id":7,"token":"synthetic-share"}]}"""; ShadowToast.reset(); failMutation = true; click("Отозвать"); await { ShadowToast.getTextOfLatestToast() != null }; assertTrue(compose.onAllNodesWithText("Скопировать").fetchSemanticsNodes().isNotEmpty()); failMutation = false; click("Отозвать"); await { count("DELETE", "/share/501") == 2 }; pump(1000); assertTrue(compose.onAllNodesWithText("Скопировать").fetchSemanticsNodes().isEmpty()) }
    @Test fun oldRetryCannotFetchForNextAccount() { failContacts = true; mountShare(); val a = text("Повторить"); val before = count("GET", "/trusted-contacts"); changeOwner(a); assertEquals(before, count("GET", "/trusted-contacts")) }
    @Test fun standaloneLinkCannotCopyOrRouteForNextOwner() { mountLink(); val copy = text("Скопировать"); val send = text("Отправить"); compose.runOnIdle { ApiClient.saveToken(tokenB); dispatch(copy); dispatch(send) }; pump(); assertFalse(clipboard.hasPrimaryClip()); assertNull(Shadows.shadowOf(app).nextStartedActivity) }
    @Test fun previousLinkCannotCopyAfterReplacement() { mountLink(); val copy = text("Скопировать"); compose.runOnIdle { link.value = "https://example.invalid/t/synthetic-b" }; pump(); compose.runOnIdle { dispatch(copy) }; pump(); assertFalse(clipboard.hasPrimaryClip()) }
    @Test fun disposedLinkCannotRoute() { mountLink(); val a = text("Отправить"); compose.runOnIdle { mounted.value = false }; pump(); compose.runOnIdle { dispatch(a) }; pump(); assertNull(Shadows.shadowOf(app).nextStartedActivity) }
    @Test fun actualTaxiSafetyCanOpenAndShare() { mountTaxiSafety(); click("Поделиться поездкой"); click("Контакт А"); await { count("POST", "/share") == 1 }; await { compose.onAllNodesWithText("Скопировать").fetchSemanticsNodes().isNotEmpty() } }
    @Test fun actualTaxiShareRejectsParentRetirementBeforeFrame() { mountTaxiSafety(); click("Поделиться поездкой"); val a = text("Контакт А"); compose.runOnIdle { parentCurrent.value = false; dispatch(a) }; pump(); assertEquals(0, count("POST", "/share")) }
    @Test fun contactsRejectStaleGenerationBeforeColdHttp() { val generation = ApiClient.queueSessionGeneration(); ApiClient.saveToken(tokenB); assertTrue(runBlocking { ApiClient.getContacts(expectedGeneration = generation) }.isFailure); assertEquals(0, count("GET", "/trusted-contacts")) }
    @Test fun contactsRejectStaleGenerationBeforeHotCache() { val generation = ApiClient.queueSessionGeneration(); ApiClient.saveToken(tokenB); val current = runBlocking { ApiClient.getContacts().getOrThrow() }; assertEquals(7, current.single().id); assertTrue(runBlocking { ApiClient.getContacts(expectedGeneration = generation) }.isFailure); assertEquals(1, count("GET", "/trusted-contacts")) }
    @Test fun currentContactsCanStillUseHotCache() { val generation = ApiClient.queueSessionGeneration(); val first = runBlocking { ApiClient.getContacts(expectedGeneration = generation).getOrThrow() }; val second = runBlocking { ApiClient.getContacts().getOrThrow() }; assertEquals(first, second); assertEquals(1, count("GET", "/trusted-contacts")) }
    @Test fun allScopedPrivateApiCallsRejectCapturedOldOwner() {
        val generation = ApiClient.queueSessionGeneration(); ApiClient.saveToken(tokenB)
        val results = runBlocking { listOf(ApiClient.shareInstantTrip(91, 7, generation), ApiClient.getInstantShares(91, generation), ApiClient.revokeInstantShare(91, 501, generation), ApiClient.instantRoadsideHelp(91, null, null, expectedGeneration = generation), ApiClient.roadsideHelp(91, null, null, "", generation), ApiClient.parcelRoadsideHelp(91, null, null, expectedGeneration = generation)) }
        assertTrue(results.all { it.isFailure }); assertTrue(records.isEmpty())
    }
    @Test fun oldShareMoreCannotClearReplacementLink() {
        rotateLink = true; shareAndLink(); val old = text("Поделиться ещё")
        compose.runOnIdle { dispatch(old) }; pump(); click("Контакт А")
        await { compose.onAllNodesWithText(ApiClient.apiBase() + "/t/synthetic-share-2").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { dispatch(old) }; pump()
        assertTrue(compose.onAllNodesWithText(ApiClient.apiBase() + "/t/synthetic-share-2").fetchSemanticsNodes().isNotEmpty())
        click("Поделиться ещё"); pump(); assertTrue(compose.onAllNodesWithText("Скопировать").fetchSemanticsNodes().isEmpty())
    }
}
