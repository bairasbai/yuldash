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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Actual App/default VM/API. Held response return is not claimed as a client Job join. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrustedContactsLifetimeTest {
    @get:Rule val compose = createComposeRule()
    private enum class Mutation(val method: String) { DELETE("DELETE"), ADD("POST") }
    private enum class Reply { MALFORMED, TIMEOUT, REJECTED, SUCCESS }
    private data class Row(val id: Int, val name: String, val phone: String)
    private data class Record(val method: String, val path: String, val bearer: String?, val body: String)
    private val a = Row(7, "Контакт А", "+70000000007")
    private val b = Row(8, "Контакт Б", "+70000000008")
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
    private val committed = CountDownLatch(1)
    private val mutationRelease = CountDownLatch(1)
    private val responseReturned = CountDownLatch(1)
    private val holdNextGet = AtomicBoolean(false)
    private val readStarted = CountDownLatch(1)
    private val readRelease = CountDownLatch(1)
    private val readReturned = CountDownLatch(1)
    private var mode = Reply.MALFORMED
    private var applyMutation = true
    private var language = AppLanguage.Ru
    private var initialGets = 0
    private val retry get() = if (language == AppLanguage.Ru) "Повторить" else "Ҡабатлау"
    private val empty get() = if (language == AppLanguage.Ru) "Пока нет контактов" else "Әлегә контакттар юҡ"
    private val remove get() = if (language == AppLanguage.Ru) "Убрать" else "Алырға"
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun rows(list: List<Row>) = list.joinToString(prefix = "[", postfix = "]") {
        """{"id":${it.id},"name":"${it.name}","relation":"Синтетический","phone":"${it.phone}","notify_by_default":true}"""
    }
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(context); ApiClient.saveToken(tokenA)
        context.getSharedPreferences("yuldash_prefs", 0).edit().clear().putBoolean("onboarding_completed", false)
            .putString("mode_last", "pooling").putBoolean("mode_hint_shown", true).commit()
        NavSignals.openInstantOrder.value = false; NavSignals.openInstantChat.value = 0
        DeepLink.pendingRideId.value = null; DeepLink.pendingBookingChatId.value = null
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val p = request.requestUrl!!.encodedPath
                    val record = Record(request.method!!, p, request.getHeader("Authorization"), request.body.readUtf8()); records += record
                    if (p == "/trusted-contacts" && record.method == "GET") {
                        val snapshot = if (record.bearer == "Bearer $tokenB") listOf(accountB) else state.get()
                        if (holdNextGet.compareAndSet(true, false)) {
                            readStarted.countDown(); check(readRelease.await(30, TimeUnit.SECONDS)); readReturned.countDown()
                        }
                        return json(rows(snapshot))
                    }
                    if ((p.startsWith("/trusted-contacts/") && record.method == "DELETE") || (p == "/trusted-contacts" && record.method == "POST")) {
                        check(record.bearer == "Bearer $tokenA")
                        if (applyMutation && mode != Reply.REJECTED) {
                            if (record.method == "DELETE") state.updateAndGet { list -> list.filterNot { it.id == p.substringAfterLast('/').toInt() } }
                            else state.set(listOf(a, b))
                        }
                        committed.countDown(); check(mutationRelease.await(30, TimeUnit.SECONDS)); responseReturned.countDown()
                        return when (mode) {
                            Reply.MALFORMED -> json("{\"ok\":")
                            Reply.TIMEOUT -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                            Reply.REJECTED -> json("{}", 403)
                            Reply.SUCCESS -> json("{\"ok\":true}")
                        }
                    }
                    return json("""{"items":[],"unread":0}""", if (p == "/instant/orders/active") 503 else 200)
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
        vm = ViewModelProvider(actor.get())[YuldashViewModel::class.java]
        vm.screen.value = Screen.Home; vm.startHomeTab.value = HomeTab.Profile; vm.persistNav()
    }
    private fun pump(ms: Long = 500) {
        repeat((ms / 100).toInt()) { compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); Snapshot.sendApplyNotifications(); Thread.sleep(10) }
        compose.waitForIdle()
    }
    private fun await(check: () -> Boolean) = compose.waitUntil(15000) {
        compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); Snapshot.sendApplyNotifications(); check()
    }
    private fun visible(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun action(matcher: SemanticsMatcher): () -> Boolean {
        await { compose.onAllNodes(matcher and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodes(matcher and hasClickAction()).onFirst().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    private fun click(text: String) { val callback = action(hasText(text)); compose.runOnIdle { callback() } }
    private fun count(method: String) = records.count { it.path.startsWith("/trusted-contacts") && it.method == method }
    private fun mount(lang: AppLanguage) {
        language = lang; vm.language.value = lang; AppPrefs.setLanguage(context, lang)
        compose.mainClock.autoAdvance = false
        compose.setContent { if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get(), LocalPoolingNativeMapEnabled provides false) { YuldashTheme { YuldashApp() } } }
        await { vm.trustedContacts.any { it.id == 7 } }; pump()
        compose.runOnIdle { vm.navigateLocally { vm.screen.value = Screen.TrustedContacts } }
        await { visible(a.name) }; pump(); initialGets = count("GET")
        assertTrue(initialGets >= 1); assertEquals(listOf(7), vm.trustedContacts.map { it.id })
    }
    private fun confirmation(m: Mutation): () -> Boolean {
        if (m == Mutation.DELETE) {
            val delete = action(hasContentDescription(if (language == AppLanguage.Ru) "Убрать контакт" else "Контактты алыу")); compose.runOnIdle { delete() }
            return action(hasText(remove))
        }
        error("Only DELETE belongs to this JVM fixture; ADD runs on native Android")
    }

    private fun beginHeld() {
        val confirm = confirmation(Mutation.DELETE); compose.runOnIdle { confirm() }
        await { committed.count == 0L }; assertEquals(emptyList<Row>(), state.get()); assertEquals(1, count("DELETE"))
        assertEquals(1L, responseReturned.count); assertEquals(listOf(7), vm.trustedContacts.map { it.id })
    }
    private fun open() { compose.runOnIdle { vm.navigateLocally { vm.screen.value = Screen.TrustedContacts } } }
    private fun leave() { compose.runOnIdle { vm.navigateLocally { vm.screen.value = Screen.Support } }; pump(); assertEquals(Screen.Support, vm.screen.value) }
    private fun settled(expected: List<Row>) {
        await { vm.trustedContacts.map { it.id } == expected.map { it.id } &&
            (if (expected.isEmpty()) visible(empty) else expected.all { visible(it.name) }) &&
            compose.onAllNodes(hasText(if (language == AppLanguage.Ru) "Добавить контакт" else "Контакт өҫтәү") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        pump(700); assertEquals(Screen.TrustedContacts, vm.screen.value); assertEquals(expected.map { it.id }, vm.trustedContacts.map { it.id })
        listOf(a, b, accountB).forEach { assertEquals(it in expected, visible(it.name)) }
    }
    private fun releaseMutation() { mutationRelease.countDown(); assertTrue(responseReturned.await(5, TimeUnit.SECONDS)); pump(1500) }
    private fun switchOwner() {
        val old = ApiClient.queueSessionGeneration(); compose.runOnIdle { ApiClient.saveToken(tokenB) }
        await { vm.trustedContacts.map { it.id } == listOf(9) }; pump()
        assertNotEquals(old, ApiClient.queueSessionGeneration()); assertEquals(tokenB, ApiClient.currentToken())
    }
    private fun unchangedAfterLateResponse(expected: List<Row>, release: () -> Unit) {
        val reads = count("GET"); val revision = vm.privateNavigationRevision; val owner = ApiClient.queueSessionGeneration()
        release(); settled(expected); assertEquals(reads, count("GET")); assertEquals(1, count("DELETE"))
        assertEquals(revision, vm.privateNavigationRevision); assertEquals(owner, ApiClient.queueSessionGeneration()); assertFalse(ApiClient.sessionExpired.value)
    }
    private fun reopenDuringMutation(lang: AppLanguage) {
        mount(lang); beginHeld(); leave(); open()
        // New screen must read the already-committed server state before old response can return.
        await { compose.onAllNodes(hasText(if (language == AppLanguage.Ru) "Добавить контакт" else "Контакт өҫтәү") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("New screen must not revive a committed deletion from warm cache", emptyList<Int>(), vm.trustedContacts.map { it.id })
        assertTrue(visible(empty)); settled(emptyList()); assertEquals(1L, responseReturned.count); assertEquals(initialGets + 1, count("GET"))
        unchangedAfterLateResponse(emptyList()) { releaseMutation() }
        assertTrue(records.filter { it.path.startsWith("/trusted-contacts") }.all { it.bearer == "Bearer $tokenA" })
    }
    private fun ownerDuringMutation(lang: AppLanguage) {
        mount(lang); beginHeld(); switchOwner(); open(); settled(listOf(accountB))
        assertEquals(1L, responseReturned.count); unchangedAfterLateResponse(listOf(accountB)) { releaseMutation() }
        assertEquals("Bearer $tokenA", records.single { it.method == "DELETE" }.bearer)
        assertTrue(records.filter { it.path == "/trusted-contacts" && it.bearer == "Bearer $tokenB" }.all { it.method == "GET" })
    }
    private fun beginReadHeld() {
        holdNextGet.set(true); beginHeld(); mutationRelease.countDown()
        await { readStarted.count == 0L }; assertEquals(listOf(7), vm.trustedContacts.map { it.id }); assertEquals(initialGets + 1, count("GET"))
    }
    private fun releaseRead() { readRelease.countDown(); assertTrue(readReturned.await(5, TimeUnit.SECONDS)); pump(1500) }
    private fun parentDuringRead(lang: AppLanguage) {
        mount(lang); beginReadHeld(); leave(); state.set(listOf(b)); open(); settled(listOf(b))
        assertEquals(1L, readReturned.count); assertEquals(initialGets + 2, count("GET"))
        unchangedAfterLateResponse(listOf(b)) { releaseRead() }
    }
    private fun ownerDuringRead(lang: AppLanguage) {
        mount(lang); beginReadHeld(); switchOwner(); open(); settled(listOf(accountB))
        assertEquals(1L, readReturned.count); unchangedAfterLateResponse(listOf(accountB)) { releaseRead() }
    }
    @After fun cleanup() {
        mutationRelease.countDown(); readRelease.countDown()
        try { compose.runOnIdle { mounted.value = false }; pump(300); actor.destroy(); vm.clearUserData() }
        finally { ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown() }
    }
    @Test fun reopenBeforeCancelledDeleteResponseReturnsReadsCurrentServerRu() = reopenDuringMutation(AppLanguage.Ru)
    @Test fun reopenBeforeCancelledDeleteResponseReturnsReadsCurrentServerBa() = reopenDuringMutation(AppLanguage.Ba)
    @Test fun newOwnerBeforeDeleteResponseCannotReceiveOldPublicationRu() = ownerDuringMutation(AppLanguage.Ru)
    @Test fun newOwnerBeforeDeleteResponseCannotReceiveOldPublicationBa() = ownerDuringMutation(AppLanguage.Ba)
    @Test fun newScreenBeforeOldReadReturnsKeepsItsOwnSnapshotRu() = parentDuringRead(AppLanguage.Ru)
    @Test fun newScreenBeforeOldReadReturnsKeepsItsOwnSnapshotBa() = parentDuringRead(AppLanguage.Ba)
    @Test fun newOwnerBeforeOldReadCannotReceiveOldPublicationRu() = ownerDuringRead(AppLanguage.Ru)
    @Test fun newOwnerBeforeOldReadCannotReceiveOldPublicationBa() = ownerDuringRead(AppLanguage.Ba)
}
