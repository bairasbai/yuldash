package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
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

/** Real App/default VM/LoginScreen/auth commit; external Telegram intent is captured. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoginContactsBootstrapTest {
    @get:Rule val compose = createComposeRule()
    private val actor = Robolectric.buildActivity(ComponentActivity::class.java)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(true)
    private lateinit var vm: YuldashViewModel
    private lateinit var server: MockWebServer
    private data class Record(val method: String, val path: String, val bearer: String?, val body: String)
    private val records = CopyOnWriteArrayList<Record>()
    private val opened = CopyOnWriteArrayList<String>()
    private val freshStarted = CountDownLatch(1); private val freshRelease = CountDownLatch(1)
    private val oldStarted = CountDownLatch(1); private val oldRelease = CountDownLatch(1); private val oldReturned = CountDownLatch(1)
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    @Volatile private var empty = false
    @Volatile private var rejectFirst = false
    @Volatile private var holdFresh = true
    @Volatile private var language = AppLanguage.Ru
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    @Before fun setup() {
        ApiClient.testTrace = null; ApiClient.resetForTest(); ApiClient.init(context); ApiClient.logout()
        TelegramLoginBot.testName = "yuldash_test_bot"
        context.getSharedPreferences("yuldash_prefs", 0).edit().clear().putBoolean("onboarding_completed", false)
            .putString("mode_last", "pooling").putBoolean("mode_hint_shown", true).commit()
        NavSignals.openInstantOrder.value = false; NavSignals.openInstantChat.value = 0
        DeepLink.pendingRideId.value = null; DeepLink.pendingBookingChatId.value = null
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val r = Record(request.method!!, request.requestUrl!!.encodedPath, request.getHeader("Authorization"), request.body.readUtf8()); records += r
                    return when (r.path) {
                        "/auth/tg/start" -> json("""{"request_id":"synthetic-request"}""")
                        "/auth/tg/verify" -> if (rejectFirst && records.count { it.path == r.path } == 1) json("{}", 400)
                            else json("""{"access_token":"$tokenB","refresh_token":"synthetic-refresh","user":{"name":"Synthetic B","role":"passenger"}}""")
                        "/trusted-contacts" -> {
                            check(r.method == "GET")
                            if (r.bearer == "Bearer $tokenA") {
                                oldStarted.countDown(); check(oldRelease.await(40, TimeUnit.SECONDS)); oldReturned.countDown()
                                json("""{"items":[{"id":7,"name":"Контакт старого входа","phone":"+70000000007"}]}""")
                            } else {
                                check(r.bearer == "Bearer $tokenB"); freshStarted.countDown()
                                if (holdFresh) check(freshRelease.await(40, TimeUnit.SECONDS))
                                json(if (empty) """{"items":[]}""" else """{"items":[{"id":9,"name":"Контакт нового входа","phone":"+70000000009"}]}""")
                            }
                        }
                        "/me" -> if (r.bearer == "Bearer $tokenB") json("""{"id":12,"name":"Synthetic B","role":"passenger"}""") else json("{}", 503)
                        else -> json("""{"items":[],"unread":0}""", if (r.path == "/instant/orders/active") 503 else 200)
                    }
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
        actor.setup(); vm = ViewModelProvider(actor.get())[YuldashViewModel::class.java]
        vm.screen.value = Screen.Login; vm.persistNav()
    }
    @After fun cleanup() {
        oldRelease.countDown(); freshRelease.countDown()
        try { compose.runOnIdle { mounted.value = false }; pump(300); actor.destroy(); vm.clearUserData() }
        finally { TelegramLoginBot.testName = null; ApiClient.testTrace = null; ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown() }
    }
    private fun pump(ms: Long = 500) {
        repeat((ms / 100).toInt()) { compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); Snapshot.sendApplyNotifications(); Thread.sleep(10) }
        compose.waitForIdle()
    }
    private fun await(check: () -> Boolean) = compose.waitUntil(15000) {
        compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); Snapshot.sendApplyNotifications(); check()
    }
    private fun countB() = records.count { it.path == "/trusted-contacts" && it.bearer == "Bearer $tokenB" }
    private fun mount(lang: AppLanguage) {
        language = lang; vm.language.value = lang; AppPrefs.setLanguage(context, lang)
        val opener = object : ContextWrapper(actor.get()) {
            override fun startActivity(intent: Intent) {
                if (intent.action == Intent.ACTION_VIEW && intent.dataString?.startsWith("https://t.me/") == true) opened += intent.dataString!!
                else super.startActivity(intent)
            }
        }
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get(), LocalContext provides opener, LocalPoolingNativeMapEnabled provides false) {
                YuldashTheme { YuldashApp() }
            }
        }
        pump(); assertEquals(Screen.Login, vm.screen.value)
    }
    private fun login() {
        val before = ApiClient.queueSessionGeneration()
        compose.onNodeWithTag(TAG_LOGIN_TELEGRAM_BTN).performScrollTo().performClick()
        await { compose.onAllNodesWithTag(TAG_LOGIN_TG_VERIFY_BTN).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(if (language == AppLanguage.Ru) "Код из Telegram" else "Telegram коды").performTextInput("123456")
        compose.onNodeWithTag(TAG_LOGIN_TG_VERIFY_BTN).performScrollTo().performClick()
        if (rejectFirst) {
            await { records.count { it.path == "/auth/tg/verify" } == 1 && compose.onAllNodes(hasTestTag(TAG_LOGIN_TG_VERIFY_BTN) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(Screen.Login, vm.screen.value); assertFalse(ApiClient.isLoggedIn()); assertEquals(0, countB())
            compose.onNodeWithTag(TAG_LOGIN_TG_VERIFY_BTN).performScrollTo().performClick()
        }
        try { await { vm.screen.value == Screen.Home && ApiClient.currentToken() == tokenB && freshStarted.count == 0L } }
        catch (failure: Throwable) {
            throw AssertionError("Login completion: screen=${vm.screen.value}, tokenB=${ApiClient.currentToken() == tokenB}, epoch=${ApiClient.queueSessionGeneration()}, before=$before, freshStarted=${freshStarted.count}, records=${records.map { Pair(it.method, it.path) }}", failure)
        }
        pump(1500)
        assertEquals(before + 1, ApiClient.queueSessionGeneration()); assertEquals(12, ApiClient.myUserId())
        assertEquals("One completed actual login must start one contacts bootstrap", 1, countB())
        assertEquals(1, records.count { it.path == "/auth/tg/start" }); assertEquals(if (rejectFirst) 2 else 1, records.count { it.path == "/auth/tg/verify" })
        assertEquals(listOf("https://t.me/yuldash_test_bot?start=synthetic-request"), opened.toList())
        records.filter { it.path == "/auth/tg/verify" }.forEach {
            assertNull(it.bearer); val o = JSONObject(it.body); assertEquals("synthetic-request", o.getString("request_id")); assertEquals("123456", o.getString("code"))
        }
    }
    private fun scenario(lang: AppLanguage, noContacts: Boolean = false, retry: Boolean = false, oldOwner: Boolean = false) {
        empty = noContacts; rejectFirst = retry
        if (oldOwner) ApiClient.saveToken(tokenA)
        mount(lang)
        if (oldOwner) {
            assertTrue(oldStarted.await(10, TimeUnit.SECONDS))
            compose.runOnIdle { ApiClient.logout(); vm.clearUserData(); vm.screen.value = Screen.Login }; pump()
        } else assertEquals(0, records.count { it.path == "/trusted-contacts" })
        login(); assertTrue(vm.trustedContacts.isEmpty())
        if (empty) compose.runOnIdle { vm.trustedContacts.add(TrustedContact("Synthetic stale sentinel", "Synthetic", "+70000000777", true, 777)) }
        freshRelease.countDown()
        await { vm.trustedContacts.map { it.id } == if (empty) emptyList<Int>() else listOf(9) }
        pump(1500); assertEquals(1, countB())
        if (oldOwner) {
            oldRelease.countDown(); assertTrue(oldReturned.await(10, TimeUnit.SECONDS)); pump(1500)
            assertEquals(listOf(9), vm.trustedContacts.map { it.id }); assertEquals(tokenB, ApiClient.currentToken()); assertEquals(1, countB())
        }
        compose.runOnIdle { vm.navigateLocally { vm.screen.value = Screen.TrustedContacts } }
        await { compose.onAllNodesWithText(if (empty) if (language == AppLanguage.Ru) "Пока нет контактов" else "Әлегә контакттар юҡ" else "Контакт нового входа").fetchSemanticsNodes().isNotEmpty() }
        pump(); assertEquals(2, countB())
        assertEquals(if (empty) emptyList<Int>() else listOf(9), vm.trustedContacts.map { it.id })
        assertEquals(0, records.count { it.method in listOf("POST", "DELETE") && it.path.startsWith("/trusted-contacts") })
    }
    @Test fun heldBootstrapAfterRealLoginRu() = scenario(AppLanguage.Ru)
    @Test fun heldBootstrapAfterRealLoginBa() = scenario(AppLanguage.Ba)
    @Test fun emptyBootstrapAfterRealLoginRu() = scenario(AppLanguage.Ru, noContacts = true)
    @Test fun emptyBootstrapAfterRealLoginBa() = scenario(AppLanguage.Ba, noContacts = true)
    @Test fun rejectedCodeThenRealLoginRu() = scenario(AppLanguage.Ru, retry = true)
    @Test fun rejectedCodeThenRealLoginBa() = scenario(AppLanguage.Ba, retry = true)
    @Test fun oldOwnerBootstrapThenRealLoginRu() = scenario(AppLanguage.Ru, oldOwner = true)
    @Test fun oldOwnerBootstrapThenRealLoginBa() = scenario(AppLanguage.Ba, oldOwner = true)
}
