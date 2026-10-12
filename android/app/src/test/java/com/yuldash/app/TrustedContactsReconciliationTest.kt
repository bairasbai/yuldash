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

/** Actual App/default VM/open screen/API; synthetic server state is independent of UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrustedContactsReconciliationTest {
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
    private val failNextGet = AtomicBoolean(false)
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
                        if (failNextGet.compareAndSet(true, false)) return json("{}", 503)
                        return json(rows(if (record.bearer == "Bearer $tokenB") listOf(accountB) else state.get()))
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
    private fun begin(m: Mutation, twice: Boolean = false) {
        val confirm = confirmation(m)
        if (mode == Reply.TIMEOUT) ApiClient.testTimeoutMs = 1000
        compose.runOnIdle { confirm(); if (twice) confirm() }
        await { committed.count == 0L }; assertEquals(if (!applyMutation || mode == Reply.REJECTED) listOf(a) else if (m == Mutation.DELETE) emptyList<Row>() else listOf(a, b), state.get())
        assertEquals(1, count(m.method)); mutationRelease.countDown()
        assertTrue(responseReturned.await(5, TimeUnit.SECONDS))
    }
    private fun settled(expected: List<Row>) {
        await { vm.trustedContacts.map { it.id } == expected.map { it.id } && (if (expected.isEmpty()) visible(empty) else expected.all { visible(it.name) }) &&
            compose.onAllNodes(hasText(if (language == AppLanguage.Ru) "Добавить контакт" else "Контакт өҫтәү") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        pump(1000); assertEquals(Screen.TrustedContacts, vm.screen.value)
        assertFalse(visible("Черновик")); assertEquals(expected.map { it.id }, vm.trustedContacts.map { it.id })
        assertEquals(a in expected, visible(a.name)); assertEquals(b in expected, visible(b.name)); assertFalse(visible(accountB.name))
    }
    private fun contract(m: Mutation, reads: Int) {
        assertEquals(initialGets + reads, count("GET")); assertEquals(1, count(m.method))
        val mutations = records.filter { it.path.startsWith("/trusted-contacts") && it.method != "GET" }
        assertEquals(1, mutations.size); val r = mutations.single()
        assertEquals(m.method, r.method); assertEquals(if (m == Mutation.DELETE) "/trusted-contacts/7" else "/trusted-contacts", r.path)
        assertEquals("Bearer $tokenA", r.bearer)
        if (m == Mutation.DELETE) assertEquals("", r.body) else {
            val body = JSONObject(r.body); assertEquals(4, body.length()); assertEquals("Черновик", body.getString("name"))
            assertEquals("Синтетический", body.getString("relation")); assertEquals(b.phone, body.getString("phone")); assertTrue(body.getBoolean("notify_by_default"))
        }
    }
    private fun scenario(m: Mutation, lang: AppLanguage, reply: Reply = Reply.MALFORMED, commit: Boolean = true, retryRead: Boolean = false, twice: Boolean = false) {
        mode = reply; applyMutation = commit; mount(lang); if (retryRead) failNextGet.set(true)
        begin(m, twice)
        if (retryRead) { await { visible(retry) }; assertEquals(1, count(m.method)); click(retry) }
        settled(state.get()); contract(m, if (reply == Reply.REJECTED) 0 else if (retryRead) 2 else 1)
    }
    private fun retiredBeforeFrame(m: Mutation, changeOwner: Boolean) {
        mount(AppLanguage.Ru); val confirm = confirmation(m)
        val previousOwner = ApiClient.queueSessionGeneration()
        var boundaryOwner = previousOwner
        compose.runOnIdle {
            if (changeOwner) ApiClient.saveToken(tokenB)
            else vm.navigateLocally { vm.screen.value = Screen.Support }
            boundaryOwner = ApiClient.queueSessionGeneration()
            val snapshot = vm.trustedContacts.toList(); val screen = vm.screen.value
            confirm()
            assertEquals(snapshot, vm.trustedContacts.toList()); assertEquals(screen, vm.screen.value)
            assertEquals(boundaryOwner, ApiClient.queueSessionGeneration())
        }
        pump(1500); assertEquals(0, count(m.method)); assertEquals(listOf(a), state.get())
        assertEquals(boundaryOwner, ApiClient.queueSessionGeneration())
        if (changeOwner) assertNotEquals(previousOwner, boundaryOwner) else assertEquals(Screen.Support, vm.screen.value)
    }
    @After fun cleanup() {
        mutationRelease.countDown()
        try { compose.runOnIdle { mounted.value = false }; pump(300); actor.destroy(); vm.clearUserData() }
        finally { ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown() }
    }
    @Test fun malformedDeleteUpdatesOpenScreenRu() = scenario(Mutation.DELETE, AppLanguage.Ru)
    @Test fun malformedDeleteUpdatesOpenScreenBa() = scenario(Mutation.DELETE, AppLanguage.Ba)
    @Test fun lostDeleteResponseUpdatesOpenScreenRu() = scenario(Mutation.DELETE, AppLanguage.Ru, Reply.TIMEOUT)
    @Test fun lostDeleteResponseUpdatesOpenScreenBa() = scenario(Mutation.DELETE, AppLanguage.Ba, Reply.TIMEOUT)
    @Test fun failedDeleteReconciliationRetriesReadOnlyRu() = scenario(Mutation.DELETE, AppLanguage.Ru, retryRead = true)
    @Test fun failedDeleteReconciliationRetriesReadOnlyBa() = scenario(Mutation.DELETE, AppLanguage.Ba, retryRead = true)
    @Test fun unknownUncommittedDeleteRestoresServerListRu() = scenario(Mutation.DELETE, AppLanguage.Ru, commit = false)
    @Test fun unknownUncommittedDeleteRestoresServerListBa() = scenario(Mutation.DELETE, AppLanguage.Ba, commit = false)
    @Test fun explicitRejectedDeletePreservesListRu() = scenario(Mutation.DELETE, AppLanguage.Ru, Reply.REJECTED)
    @Test fun explicitRejectedDeletePreservesListBa() = scenario(Mutation.DELETE, AppLanguage.Ba, Reply.REJECTED)
    @Test fun successfulDeletePublishesEmptyListRu() = scenario(Mutation.DELETE, AppLanguage.Ru, Reply.SUCCESS)
    @Test fun successfulDeletePublishesEmptyListBa() = scenario(Mutation.DELETE, AppLanguage.Ba, Reply.SUCCESS)
    @Test fun repeatedDeleteConfirmationSendsOneRequest() = scenario(Mutation.DELETE, AppLanguage.Ru, twice = true)
    @Test fun oldDeleteCallbackCannotStartUnderNewOwner() = retiredBeforeFrame(Mutation.DELETE, true)
    @Test fun oldDeleteCallbackCannotStartAfterLeavingBeforeFrame() = retiredBeforeFrame(Mutation.DELETE, false)
}
