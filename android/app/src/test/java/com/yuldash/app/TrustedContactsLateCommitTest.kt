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

/** DELETE is held BEFORE server commit. UI drain is not claimed as an actual Editor Job join. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrustedContactsLateCommitTest {
    @get:Rule val compose = createComposeRule()
    private enum class Mutation(val method: String) { DELETE("DELETE"), ADD("POST") }
    private enum class Reply { MALFORMED, TIMEOUT, SUCCESS }
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
    private var commitBeforeHold = false
    private val failNextGet = AtomicBoolean(false)
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
                        val snapshot = if (record.bearer == "Bearer $tokenB") listOf(accountB) else state.get()
                        if (holdNextGet.compareAndSet(true, false)) {
                            readStarted.countDown(); check(readRelease.await(30, TimeUnit.SECONDS)); readReturned.countDown()
                        }
                        return json(rows(snapshot))
                    }
                    if ((p.startsWith("/trusted-contacts/") && record.method == "DELETE") || (p == "/trusted-contacts" && record.method == "POST")) {
                        check(record.bearer == "Bearer $tokenA")
                        fun apply() { if (applyMutation) state.updateAndGet { list -> list.filterNot { it.id == p.substringAfterLast('/').toInt() } } }
                        if (commitBeforeHold) apply()
                        committed.countDown(); check(mutationRelease.await(30, TimeUnit.SECONDS))
                        if (!commitBeforeHold) apply()
                        responseReturned.countDown()
                        return when (mode) {
                            Reply.MALFORMED -> json("{\"ok\":")
                            Reply.TIMEOUT -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
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
        assertEquals(2, initialGets); assertEquals(listOf(7), vm.trustedContacts.map { it.id })
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
        await { committed.count == 0L }; assertEquals(1, count("DELETE")); assertEquals(1L, responseReturned.count)
        assertEquals(if (commitBeforeHold) emptyList() else listOf(a), state.get())
        assertEquals(listOf(7), vm.trustedContacts.map { it.id })
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
    private fun late(lang: AppLanguage, reply: Reply = Reply.MALFORMED, failRead: Boolean = false, apply: Boolean = true) {
        if (reply == Reply.TIMEOUT) ApiClient.testTimeoutMs = 2000
        mode = reply; applyMutation = apply; mount(lang); beginHeld(); leave(); open(); settled(listOf(a))
        assertEquals(initialGets + 1, count("GET")); assertEquals(listOf(a), state.get()); assertEquals(1L, responseReturned.count)
        val revision = vm.privateNavigationRevision; val owner = ApiClient.queueSessionGeneration()
        if (failRead) failNextGet.set(true)
        releaseMutation()
        if (failRead) {
            await { visible(retry) }; assertEquals(listOf(7), vm.trustedContacts.map { it.id })
            assertFalse(visible(empty)); assertEquals(initialGets + 2, count("GET")); click(retry)
        }
        val expected = if (apply) emptyList() else listOf(a)
        settled(expected)
        assertEquals("One read-only reconciliation of the late commit, never repeat DELETE", initialGets + if (failRead) 3 else 2, count("GET"))
        assertEquals(1, count("DELETE")); assertEquals(revision, vm.privateNavigationRevision); assertEquals(owner, ApiClient.queueSessionGeneration())
        assertFalse(ApiClient.sessionExpired.value); assertTrue(records.filter { it.path.startsWith("/trusted-contacts") }.all { it.bearer == "Bearer $tokenA" })
        assertEquals(expected.map { it.id }, kotlinx.coroutines.runBlocking { TestContacts.read().getOrThrow().map { it.id } })
        assertEquals(initialGets + if (failRead) 3 else 2, count("GET"))
    }
    private fun own(reply: Reply) {
        mode = reply; mount(AppLanguage.Ru); beginHeld(); releaseMutation(); settled(emptyList())
        assertEquals(initialGets + 1, count("GET")); assertEquals(1, count("DELETE"))
    }
    private fun alreadyObserved() {
        commitBeforeHold = true; mount(AppLanguage.Ru); beginHeld(); leave(); open(); settled(emptyList())
        assertEquals(initialGets + 1, count("GET")); releaseMutation(); settled(emptyList())
        assertEquals("A known DELETE already reflected in the accepted snapshot needs no extra GET", initialGets + 1, count("GET"))
    }
    private fun ownerChanged(lang: AppLanguage) {
        mount(lang); beginHeld(); leave(); compose.runOnIdle { ApiClient.saveToken(tokenB) }
        await { vm.trustedContacts.map { it.id } == listOf(9) }; open(); settled(listOf(accountB))
        val reads = count("GET"); val owner = ApiClient.queueSessionGeneration(); releaseMutation(); settled(listOf(accountB))
        assertEquals(reads, count("GET")); assertEquals(owner, ApiClient.queueSessionGeneration()); assertEquals(tokenB, ApiClient.currentToken())
        assertEquals("Bearer $tokenA", records.single { it.method == "DELETE" }.bearer)
    }
    private fun leftAtCommit() {
        mount(AppLanguage.Ru); beginHeld(); leave(); val revision = vm.privateNavigationRevision; releaseMutation()
        assertEquals(initialGets, count("GET")); assertEquals(Screen.Support, vm.screen.value); assertEquals(revision, vm.privateNavigationRevision)
        open(); settled(emptyList()); assertEquals(initialGets + 1, count("GET")); assertEquals(1, count("DELETE"))
    }
    private fun conflatedDeletes() {
        mount(AppLanguage.Ru); leave(); state.set(listOf(a, b)); open(); settled(listOf(a, b))
        mutationRelease.countDown()
        // Keep the UI queue paused while two real API calls settle. The latest ID99 was absent already.
        compose.runOnIdle {
            kotlinx.coroutines.runBlocking {
                TestContacts.delete(7)
                TestContacts.delete(99)
            }
        }
        settled(listOf(b)); assertEquals(initialGets + 2, count("GET")); assertEquals(2, count("DELETE"))
        assertEquals(listOf(8), state.get().map { it.id })
    }
    private fun ownFailedRead() {
        mount(AppLanguage.Ru); beginHeld(); failNextGet.set(true); releaseMutation()
        await { visible(retry) }; pump(1000)
        assertEquals("A failed own reconciliation must wait for explicit Retry", initialGets + 1, count("GET"))
        assertEquals(listOf(7), vm.trustedContacts.map { it.id }); assertFalse(visible(empty))
        click(retry); settled(emptyList()); assertEquals(initialGets + 2, count("GET")); assertEquals(1, count("DELETE"))
    }
    @After fun cleanup() {
        mutationRelease.countDown(); readRelease.countDown()
        try { compose.runOnIdle { mounted.value = false }; pump(300); actor.destroy(); vm.clearUserData() }
        finally { ApiClient.testTrace = null; ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown() }
    }
    @Test fun reopenedFreshSnapshotIsUpdatedAfterLateDeleteSuccessRu() = late(AppLanguage.Ru, Reply.SUCCESS)
    @Test fun reopenedFreshSnapshotIsUpdatedAfterLateDeleteSuccessBa() = late(AppLanguage.Ba, Reply.SUCCESS)
    @Test fun reopenedFreshSnapshotIsUpdatedAfterLateMalformedDeleteRu() = late(AppLanguage.Ru)
    @Test fun reopenedFreshSnapshotIsUpdatedAfterLateMalformedDeleteBa() = late(AppLanguage.Ba)
    @Test fun reopenedFreshSnapshotIsUpdatedAfterTimedOutDeleteResponse() = late(AppLanguage.Ru, Reply.TIMEOUT)
    @Test fun uncommittedCancelledDeleteOnlyRereadsServerTruth() = late(AppLanguage.Ru, apply = false)
    @Test fun failedLateReconciliationShowsRetryWithoutEmptyPublication() = late(AppLanguage.Ru, failRead = true)
    @Test fun ownSuccessfulDeleteUsesItsExistingSingleRead() = own(Reply.SUCCESS)
    @Test fun ownMalformedDeleteUsesItsExistingSingleRead() = own(Reply.MALFORMED)
    @Test fun alreadyObservedDeletionDoesNotAddAnExtraRead() = alreadyObserved()
    @Test fun oldLateDeleteCannotTriggerReadOrPublicationInNewOwnerRu() = ownerChanged(AppLanguage.Ru)
    @Test fun oldLateDeleteCannotTriggerReadOrPublicationInNewOwnerBa() = ownerChanged(AppLanguage.Ba)
    @Test fun retiredScreenDoesNotReadAndNextOpenReadsFresh() = leftAtCommit()
    @Test fun conflatedDifferentDeletesForceReadEvenIfLatestIdWasAbsent() = conflatedDeletes()
    @Test fun ownFailedReconciliationWaitsForExplicitRetry() = ownFailedRead()
}
