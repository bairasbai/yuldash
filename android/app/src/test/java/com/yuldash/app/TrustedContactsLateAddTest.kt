package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Actual App/default VM observer + external real API Deferred. Native tests cover the Editor's form/Job. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrustedContactsLateAddTest {
    @get:Rule val compose = createComposeRule()
    private enum class Reply { SUCCESS, MALFORMED, TIMEOUT }
    private data class Row(val id: Int, val name: String, val phone: String, val relation: String = "Синтетический", val notify: Boolean = true)
    private data class Record(val method: String, val path: String, val bearer: String?, val body: String)
    private val a = Row(7, "Контакт А", "+70000000007")
    private val b = Row(8, "Контакт Б", "+70000000008")
    private val c = Row(10, "Контакт В", "+70000000010")
    private val accountB = Row(9, "Контакт другого входа", "+70000000009")
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val actor by lazy { Robolectric.buildActivity(MainActivity::class.java).create() }
    private lateinit var vm: YuldashViewModel
    private lateinit var server: MockWebServer
    private val mounted = mutableStateOf(true)
    private val state = AtomicReference(listOf(a))
    private val records = CopyOnWriteArrayList<Record>()
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val returned = CountDownLatch(1)
    private val failNextGet = AtomicBoolean(false)
    private var mode = Reply.MALFORMED
    private var apply = true
    private var language = AppLanguage.Ru
    private val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var caller: Deferred<Result<Unit>>
    private val retry get() = if (language == AppLanguage.Ru) "Повторить" else "Ҡабатлау"
    private val add get() = if (language == AppLanguage.Ru) "Добавить контакт" else "Контакт өҫтәү"
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun row(r: Row) = """{"id":${r.id},"name":"${r.name}","relation":"${r.relation}","phone":"${r.phone}","notify_by_default":${r.notify}}"""
    @Before fun setup() {
        ApiClient.testTrace = null; ApiClient.resetForTest(); ApiClient.init(context); ApiClient.saveToken(tokenA)
        context.getSharedPreferences("yuldash_prefs", 0).edit().clear().putBoolean("onboarding_completed", false)
            .putString("mode_last", "pooling").putBoolean("mode_hint_shown", true).commit()
        NavSignals.openInstantOrder.value = false; NavSignals.openInstantChat.value = 0
        DeepLink.pendingRideId.value = null; DeepLink.pendingBookingChatId.value = null
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val record = Record(request.method!!, request.requestUrl!!.encodedPath, request.getHeader("Authorization"), request.body.readUtf8()); records += record
                    if (record.path == "/trusted-contacts" && record.method == "GET") {
                        if (failNextGet.compareAndSet(true, false)) return json("{}", 503)
                        val snapshot = if (record.bearer == "Bearer $tokenB") listOf(accountB) else state.get()
                        return json(snapshot.joinToString(prefix = "[", postfix = "]", transform = ::row))
                    }
                    if (record.path == "/trusted-contacts" && record.method == "POST") {
                        check(record.bearer == "Bearer $tokenA")
                        val body = JSONObject(record.body)
                        val id = if (body.getString("phone") == c.phone) c.id else b.id
                        val addition = Row(id, body.getString("name"), body.getString("phone"), body.getString("relation"), body.getBoolean("notify_by_default"))
                        started.countDown(); check(release.await(30, TimeUnit.SECONDS))
                        if (apply) state.updateAndGet { previous -> previous.filterNot { it.phone == addition.phone } + addition }
                        returned.countDown()
                        return when (mode) {
                            Reply.SUCCESS -> json(row(addition))
                            Reply.MALFORMED -> json("{\"id\":")
                            Reply.TIMEOUT -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        }
                    }
                    if (record.path.startsWith("/trusted-contacts/") && record.method == "DELETE") {
                        state.updateAndGet { previous -> previous.filterNot { it.id == record.path.substringAfterLast('/').toInt() } }; return json("{}")
                    }
                    return json("""{"items":[],"unread":0}""", if (record.path == "/instant/orders/active") 503 else 200)
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
        vm = ViewModelProvider(actor.get())[YuldashViewModel::class.java]
        vm.screen.value = Screen.Home; vm.startHomeTab.value = HomeTab.Profile; vm.persistNav()
    }
    private fun pump(ms: Long = 500) {
        repeat((ms / 100).toInt()) { compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); Snapshot.sendApplyNotifications(); Thread.sleep(10) }; compose.waitForIdle()
    }
    private fun await(check: () -> Boolean) = compose.waitUntil(15000) { compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); Snapshot.sendApplyNotifications(); check() }
    private fun visible(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun click(text: String) {
        await { compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        val action = compose.onAllNodes(hasText(text) and hasClickAction()).onFirst().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { action() }
    }
    private fun count(method: String) = records.count { it.path.startsWith("/trusted-contacts") && it.method == method }
    private fun vmRows() = vm.trustedContacts.map { Row(it.id, it.name, it.phone, it.relation, it.notifyByDefault) }
    private fun mount(lang: AppLanguage) {
        language = lang; vm.language.value = lang; AppPrefs.setLanguage(context, lang); compose.mainClock.autoAdvance = false
        compose.setContent { if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get(), LocalPoolingNativeMapEnabled provides false) { YuldashTheme { YuldashApp() } } }
        await { vmRows() == state.get() }; pump()
        open(); settled(state.get()); assertEquals(2, count("GET"))
    }
    private fun open() { compose.runOnIdle { vm.navigateLocally { vm.screen.value = Screen.TrustedContacts } } }
    private fun leave() {
        compose.runOnIdle { vm.navigateLocally { vm.screen.value = Screen.Support } }
        if (::caller.isInitialized && !caller.isCompleted) caller.cancel()
        pump(); assertEquals(Screen.Support, vm.screen.value)
    }
    private fun settled(expected: List<Row>) {
        await { vmRows() == expected && expected.all { visible(it.name) } && compose.onAllNodes(hasText(add) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        pump(700); assertEquals(Screen.TrustedContacts, vm.screen.value); assertEquals(expected, vmRows())
        listOf(a, b, c, accountB).forEach { candidate -> assertEquals(expected.any { it.name == candidate.name }, visible(candidate.name)) }
    }
    private fun beginHeld(addition: Row = b) {
        val owner = ApiClient.queueSessionGeneration()
        caller = callerScope.async { ApiClient.addContact(addition.name, addition.relation, addition.phone, addition.notify, owner) }
        await { started.count == 0L }; assertEquals(1, count("POST")); assertEquals(1L, returned.count)
        val body = JSONObject(records.single { it.path == "/trusted-contacts" && it.method == "POST" }.body)
        assertEquals(4, body.length()); assertEquals(addition.name, body.getString("name")); assertEquals(addition.relation, body.getString("relation"))
        assertEquals(addition.phone, body.getString("phone")); assertTrue(body.getBoolean("notify_by_default")); ShadowToast.reset()
    }
    private fun releaseAdd() {
        release.countDown(); assertTrue(returned.await(5, TimeUnit.SECONDS))
        runBlocking { withTimeout(5000) { caller.join() } }; assertTrue(caller.isCompleted); pump(1500)
    }
    private fun late(lang: AppLanguage, reply: Reply = Reply.MALFORMED, failedRead: Boolean = false, committed: Boolean = true, upsert: Boolean = false) {
        mode = reply; apply = committed; if (reply == Reply.TIMEOUT) ApiClient.testTimeoutMs = 2000
        val old = if (upsert) listOf(a, b.copy(relation = "Прежняя связь")) else listOf(a); state.set(old)
        mount(lang); beginHeld(); assertEquals(old, state.get()); leave(); open(); settled(old)
        assertEquals(3, count("GET")); assertEquals(1L, returned.count); val revision = vm.privateNavigationRevision; val owner = ApiClient.queueSessionGeneration()
        if (failedRead) failNextGet.set(true)
        releaseAdd(); assertEquals(if (committed) listOf(a, b) else old, state.get())
        if (failedRead) {
            await { visible(retry) }; pump(500); assertEquals(old, vmRows()); assertEquals(4, count("GET")); assertFalse(visible(b.name) && !upsert); click(retry)
        }
        val expected = if (committed) listOf(a, b) else old; settled(expected)
        assertEquals(if (failedRead) 5 else 4, count("GET")); assertEquals(1, count("POST")); assertEquals(0, count("DELETE"))
        assertEquals(revision, vm.privateNavigationRevision); assertEquals(owner, ApiClient.queueSessionGeneration()); assertFalse(ApiClient.sessionExpired.value)
        assertNull("Retired add action must not show success Toast in the new screen", ShadowToast.getTextOfLatestToast())
        assertTrue(records.filter { it.path.startsWith("/trusted-contacts") }.all { it.bearer == "Bearer $tokenA" })
        assertEquals(expected.map { it.id }, kotlinx.coroutines.runBlocking { ApiClient.getContacts().getOrThrow().map { it.id } }); assertEquals(if (failedRead) 5 else 4, count("GET"))
    }
    private fun own(reply: Reply, failedRead: Boolean = false) {
        mode = reply; mount(AppLanguage.Ru); beginHeld(); if (failedRead) failNextGet.set(true); releaseAdd()
        if (failedRead) { await { visible(retry) }; pump(700); assertEquals(3, count("GET")); assertEquals(listOf(a), vmRows()); click(retry) }
        settled(listOf(a, b)); assertEquals(if (failedRead) 4 else 3, count("GET")); assertEquals(1, count("POST"))
        assertNull("External API action has no Editor success Toast", ShadowToast.getTextOfLatestToast())
    }
    private fun ownerChanged(lang: AppLanguage) {
        mount(lang); beginHeld(); leave(); compose.runOnIdle { ApiClient.saveToken(tokenB) }; await { vmRows() == listOf(accountB) }
        open(); settled(listOf(accountB)); val reads = count("GET"); val owner = ApiClient.queueSessionGeneration(); releaseAdd(); settled(listOf(accountB))
        assertEquals(reads, count("GET")); assertEquals(owner, ApiClient.queueSessionGeneration()); assertEquals(tokenB, ApiClient.currentToken()); assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals("Bearer $tokenA", records.single { it.path == "/trusted-contacts" && it.method == "POST" }.bearer)
    }
    private fun retired(logout: Boolean) {
        mount(AppLanguage.Ru); beginHeld(); leave(); val revision = vm.privateNavigationRevision
        if (logout) { compose.runOnIdle { ApiClient.logout(); vm.clearUserData() }; pump() }
        val reads = count("GET"); val snapshot = vmRows(); releaseAdd(); assertEquals(reads, count("GET")); assertEquals(snapshot, vmRows()); assertNull(ShadowToast.getTextOfLatestToast())
        if (logout) assertNull(ApiClient.currentToken()) else { assertEquals(Screen.Support, vm.screen.value); assertEquals(revision, vm.privateNavigationRevision); open(); settled(listOf(a, b)); assertEquals(reads + 1, count("GET")) }
    }
    private fun coalescedAddDelete() {
        mount(AppLanguage.Ru); release.countDown()
        compose.runOnIdle { kotlinx.coroutines.runBlocking { ApiClient.addContact(b.name, b.relation, b.phone, true); ApiClient.deleteContact(a.id) } }
        settled(listOf(b)); assertEquals(3, count("GET")); assertEquals(1, count("POST")); assertEquals(1, count("DELETE"))
    }
    @After fun cleanup() {
        release.countDown()
        try { compose.runOnUiThread { mounted.value = false }; pump(300); actor.destroy(); vm.clearUserData() }
        finally { callerScope.cancel(); ApiClient.testTrace = null; ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown(); ShadowToast.reset() }
    }
    @Test fun lateSuccessfulAdditionUpdatesReopenedFreshListRu() = late(AppLanguage.Ru, Reply.SUCCESS)
    @Test fun lateSuccessfulAdditionUpdatesReopenedFreshListBa() = late(AppLanguage.Ba, Reply.SUCCESS)
    @Test fun lateMalformedAdditionUpdatesReopenedFreshListRu() = late(AppLanguage.Ru)
    @Test fun lateMalformedAdditionUpdatesReopenedFreshListBa() = late(AppLanguage.Ba)
    @Test fun lateTimedOutAdditionReconcilesWithoutRepeatingPost() = late(AppLanguage.Ru, Reply.TIMEOUT)
    @Test fun failedLateReadWaitsForExplicitRetryRu() = late(AppLanguage.Ru, failedRead = true)
    @Test fun failedLateReadWaitsForExplicitRetryBa() = late(AppLanguage.Ba, failedRead = true)
    @Test fun cancelledUncommittedAdditionOnlyReadsServerTruth() = late(AppLanguage.Ru, committed = false)
    @Test fun externalSuccessUpdatesCurrentEditorWithSingleRead() = own(Reply.SUCCESS)
    @Test fun externalMalformedAdditionUpdatesCurrentEditorWithSingleRead() = own(Reply.MALFORMED)
    @Test fun externalFailedAdditionReadWaitsForExplicitRetry() = own(Reply.MALFORMED, failedRead = true)
    @Test fun oldAdditionDoesNotReadOrPublishIntoNewOwnerRu() = ownerChanged(AppLanguage.Ru)
    @Test fun oldAdditionDoesNotReadOrPublishIntoNewOwnerBa() = ownerChanged(AppLanguage.Ba)
    @Test fun loggedOutParentReceivesNoLateAddition() = retired(logout = true)
    @Test fun retiredParentReceivesNoLateReadAndNextOpenReadsFresh() = retired(logout = false)
    @Test fun matchingNamePhoneAndIdStillRequiresFreshUpsertFields() = late(AppLanguage.Ru, upsert = true)
    @Test fun settledAddAndDeleteBeforeUiFrameUseSingleReconciliationRead() = coalescedAddDelete()
}
