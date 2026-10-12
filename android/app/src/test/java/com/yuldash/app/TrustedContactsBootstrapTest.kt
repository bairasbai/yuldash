package com.yuldash.app

import android.app.Application
import com.yuldash.app.data.TestContacts
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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

/** Bootstrap is held before any editor. App/default VM are real; trace/pump is not a UI Job join. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrustedContactsBootstrapTest {
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
    private val bootStarted = CountDownLatch(1)
    private val bootRelease = CountDownLatch(1)
    private val bootReturned = CountDownLatch(1)
    private val bootParsed = CountDownLatch(1)
    private val getSequence = AtomicInteger()
    private var holdBootstrap = true
    private var bootstrapCode = 200
    private var mode = Reply.SUCCESS
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
                        if (getSequence.incrementAndGet() == 1) {
                            bootStarted.countDown()
                            if (holdBootstrap) check(bootRelease.await(45, TimeUnit.SECONDS))
                            bootReturned.countDown(); return json(if (bootstrapCode == 200) rows(snapshot) else "{}", bootstrapCode)
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
        ApiClient.testTrace = { line ->
            if (line.startsWith("ВЫШЛИ из вызова GET /trusted-contacts") && (!holdBootstrap || bootRelease.count == 0L)) bootParsed.countDown()
        }
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
        language = lang; vm.language.value = lang; AppPrefs.setLanguage(context, lang); compose.mainClock.autoAdvance = false
        compose.setContent { if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get(), LocalPoolingNativeMapEnabled provides false) { YuldashTheme { YuldashApp() } } }
        await { bootStarted.count == 0L }; pump()
    }
    private fun open(expected: List<Row>) {
        compose.runOnIdle { vm.navigateLocally { vm.screen.value = Screen.TrustedContacts } }
        await { vm.trustedContacts.map { it.id } == expected.map { it.id } &&
            (if (expected.isEmpty()) visible(empty) else expected.all { visible(it.name) }) &&
            compose.onAllNodes(hasText(if (language == AppLanguage.Ru) "Добавить контакт" else "Контакт өҫтәү") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        pump(); assertEquals(Screen.TrustedContacts, vm.screen.value)
    }
    private fun leave() { compose.runOnIdle { vm.navigateLocally { vm.screen.value = Screen.Support } }; pump(); assertEquals(Screen.Support, vm.screen.value) }
    private fun cachedIds() = runBlocking { TestContacts.read().getOrThrow().map { it.id } }
    private fun releaseOldBootstrap() {
        bootRelease.countDown(); assertTrue(bootReturned.await(5, TimeUnit.SECONDS))
        assertTrue(bootParsed.await(5, TimeUnit.SECONDS)); pump(1500)
    }
    private fun confirmation(m: Mutation): () -> Boolean {
        if (m == Mutation.DELETE) {
            val delete = action(hasContentDescription(if (language == AppLanguage.Ru) "Убрать контакт" else "Контактты алыу")); compose.runOnIdle { delete() }
            return action(hasText(remove))
        }
        error("Only DELETE belongs to this JVM fixture; ADD runs on native Android")
    }

    private fun lateDelete(lang: AppLanguage, malformed: Boolean = false) {
        mode = if (malformed) Reply.MALFORMED else Reply.SUCCESS
        mount(lang); assertEquals(1L, bootReturned.count); open(listOf(a)); assertEquals(2, count("GET"))
        val confirm = confirmation(Mutation.DELETE); compose.runOnIdle { confirm() }; await { committed.count == 0L }
        assertEquals(emptyList<Row>(), state.get()); mutationRelease.countDown(); openSettledEmpty()
        assertEquals(3, count("GET")); assertEquals(1, count("DELETE")); leave()
        val owner = ApiClient.queueSessionGeneration(); val revision = vm.privateNavigationRevision
        releaseOldBootstrap()
        assertEquals("Late bootstrap must not restore a deleted contact in the parent VM", emptyList<Int>(), vm.trustedContacts.map { it.id })
        assertEquals(emptyList<Int>(), cachedIds()); assertEquals(3, count("GET")); assertEquals(1, count("DELETE"))
        assertEquals(Screen.Support, vm.screen.value); assertEquals(revision, vm.privateNavigationRevision); assertEquals(owner, ApiClient.queueSessionGeneration())
        assertEquals("Bearer $tokenA", records.single { it.method == "DELETE" }.bearer)
        open(emptyList()); assertFalse(visible(a.name)); assertEquals(4, count("GET"))
    }
    private fun openSettledEmpty() {
        await { vm.trustedContacts.isEmpty() && visible(empty) &&
            compose.onAllNodes(hasText(if (language == AppLanguage.Ru) "Добавить контакт" else "Контакт өҫтәү") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        pump(); assertEquals(Screen.TrustedContacts, vm.screen.value)
    }
    private fun lateFresh(lang: AppLanguage) {
        mount(lang); state.set(listOf(b)); open(listOf(b)); assertEquals(1L, bootReturned.count); leave()
        releaseOldBootstrap(); assertEquals(listOf(8), vm.trustedContacts.map { it.id }); assertEquals(listOf(8), cachedIds())
        assertEquals(2, count("GET")); assertEquals(0, count("DELETE")); open(listOf(b)); assertFalse(visible(a.name)); assertEquals(3, count("GET"))
    }
    private fun accepted(emptyResult: Boolean, warm: Boolean) {
        holdBootstrap = false; if (emptyResult) state.set(emptyList())
        if (warm) assertEquals(state.get().map { it.id }, cachedIds())
        vm.trustedContacts.add(TrustedContact(b.name, "Synthetic", b.phone, true, b.id))
        mount(AppLanguage.Ru); await { vm.trustedContacts.map { it.id } == state.get().map { it.id } }; pump()
        assertEquals(1, count("GET")); assertEquals(0, count("DELETE")); assertEquals(state.get().map { it.id }, cachedIds()); assertEquals(1, count("GET"))
    }
    private fun failed() {
        holdBootstrap = false; bootstrapCode = 503
        vm.trustedContacts.add(TrustedContact(b.name, "Synthetic", b.phone, true, b.id))
        mount(AppLanguage.Ru); assertTrue(bootReturned.await(5, TimeUnit.SECONDS)); pump(1500)
        assertEquals(listOf(8), vm.trustedContacts.map { it.id }); assertEquals(1, count("GET")); assertEquals(0, count("DELETE"))
    }
    private fun ownerChanged() {
        mount(AppLanguage.Ru); compose.runOnIdle { ApiClient.saveToken(tokenB) }
        await { vm.trustedContacts.map { it.id } == listOf(9) }; pump()
        val owner = ApiClient.queueSessionGeneration(); assertEquals(listOf(9), cachedIds()); assertEquals(1L, bootReturned.count)
        bootRelease.countDown(); assertTrue(bootReturned.await(5, TimeUnit.SECONDS)); pump(1500)
        assertEquals(listOf(9), vm.trustedContacts.map { it.id }); assertEquals(listOf(9), cachedIds()); assertEquals(owner, ApiClient.queueSessionGeneration())
        assertEquals(listOf("Bearer $tokenA", "Bearer $tokenB"), records.filter { it.path == "/trusted-contacts" }.map { it.bearer })
    }
    private fun retired(logout: Boolean) {
        mount(AppLanguage.Ru)
        compose.runOnIdle {
            if (logout) { ApiClient.logout(); vm.clearUserData() } else mounted.value = false
        }; pump(); val snapshot = vm.trustedContacts.toList(); val owner = ApiClient.queueSessionGeneration()
        bootRelease.countDown(); assertTrue(bootReturned.await(5, TimeUnit.SECONDS)); pump(1500)
        assertEquals(snapshot, vm.trustedContacts.toList()); assertEquals(owner, ApiClient.queueSessionGeneration()); assertEquals(1, count("GET"))
        if (logout) { assertNull(ApiClient.currentToken()); assertTrue(vm.trustedContacts.isEmpty()) }
    }
    @After fun cleanup() {
        bootRelease.countDown(); mutationRelease.countDown()
        try { compose.runOnIdle { mounted.value = false }; pump(300); actor.destroy(); vm.clearUserData() }
        finally { ApiClient.testTrace = null; ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown() }
    }
    @Test fun lateBootstrapCannotRestoreSuccessfullyDeletedContactRu() = lateDelete(AppLanguage.Ru)
    @Test fun lateBootstrapCannotRestoreSuccessfullyDeletedContactBa() = lateDelete(AppLanguage.Ba)
    @Test fun lateBootstrapCannotRestoreUnconfirmedDeletedContactRu() = lateDelete(AppLanguage.Ru, true)
    @Test fun lateBootstrapCannotRestoreUnconfirmedDeletedContactBa() = lateDelete(AppLanguage.Ba, true)
    @Test fun lateBootstrapCannotReplaceFreshScreenSnapshotRu() = lateFresh(AppLanguage.Ru)
    @Test fun lateBootstrapCannotReplaceFreshScreenSnapshotBa() = lateFresh(AppLanguage.Ba)
    @Test fun coldBootstrapPublishesServerContacts() = accepted(false, false)
    @Test fun coldEmptyBootstrapClearsPreviousParentContacts() = accepted(true, false)
    @Test fun warmBootstrapPublishesCachedContactsWithoutHttp() = accepted(false, true)
    @Test fun warmEmptyBootstrapClearsParentWithoutHttp() = accepted(true, true)
    @Test fun failedBootstrapDoesNotClearParentOrRetry() = failed()
    @Test fun oldBootstrapCannotOverwriteNewOwnerContacts() = ownerChanged()
    @Test fun disposedAppCannotPublishHeldBootstrap() = retired(false)
    @Test fun loggedOutAppCannotPublishHeldBootstrap() = retired(true)
}
