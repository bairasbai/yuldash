package com.yuldash.app

import android.app.Application
import com.yuldash.app.data.TestContacts
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

/** Same default VM and session; dispose/remount real App accepts warm cache with no contacts HTTP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrustedContactsCacheReentryTest {
    @get:Rule val compose = createComposeRule()
    private val actor = Robolectric.buildActivity(ComponentActivity::class.java)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(true)
    private lateinit var vm: YuldashViewModel
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<String>()
    private val token = "header.eyJzdWIiOiIxMSJ9.signature"
    @Volatile private var empty = false
    @Volatile private var rejectContacts = false
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(context); ApiClient.saveToken(token)
        context.getSharedPreferences("yuldash_prefs", 0).edit().clear().putBoolean("onboarding_completed", false)
            .putString("mode_last", "pooling").putBoolean("mode_hint_shown", true).commit()
        NavSignals.openInstantOrder.value = false; NavSignals.openInstantChat.value = 0
        DeepLink.pendingRideId.value = null; DeepLink.pendingBookingChatId.value = null
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath; requests += "${request.method} $path"
                    var body = """{"items":[],"unread":0}"""; var code = if (path == "/instant/orders/active") 503 else 200
                    if (path == "/trusted-contacts") {
                        check(request.method == "GET" && request.getHeader("Authorization") == "Bearer $token")
                        check(request.body.size == 0L)
                        if (rejectContacts) { code = 503; body = "{}" }
                        else body = if (empty) """{"items":[]}""" else """{"items":[{"id":7,"name":"Synthetic 7","phone":"+70000000007"}]}"""
                    }
                    return MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
        actor.setup(); vm = ViewModelProvider(actor.get())[YuldashViewModel::class.java]
        vm.screen.value = Screen.Home; vm.startHomeTab.value = HomeTab.Profile; vm.persistNav()
    }
    @After fun cleanup() {
        try { compose.runOnIdle { mounted.value = false }; pump(300); actor.destroy(); vm.clearUserData() }
        finally { ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown() }
    }
    private fun pump(ms: Long = 500) {
        repeat((ms / 100).toInt()) { compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); Snapshot.sendApplyNotifications(); Thread.sleep(10) }
        compose.waitForIdle()
    }
    private fun await(check: () -> Boolean) = compose.waitUntil(15000) {
        compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); Snapshot.sendApplyNotifications(); check()
    }
    private fun scenario(language: AppLanguage, noContacts: Boolean) {
        empty = noContacts; val expected = if (empty) emptyList() else listOf(7)
        assertEquals(expected, runBlocking { TestContacts.read().getOrThrow().map { it.id } })
        val owner = ApiClient.queueSessionGeneration(); rejectContacts = true
        compose.runOnIdle { vm.trustedContacts.add(TrustedContact("Sentinel", "Synthetic", "+70000000777", true, 777)) }
        vm.language.value = language; AppPrefs.setLanguage(context, language); compose.mainClock.autoAdvance = false
        compose.setContent { if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get(), LocalPoolingNativeMapEnabled provides false) { YuldashTheme { YuldashApp() } } }
        await { vm.trustedContacts.map { it.id } == expected }; pump()
        assertEquals(Screen.Home, vm.screen.value)
        // Changing the list alone does not restart effect keys. Observe this independently before remount.
        compose.runOnIdle { vm.trustedContacts.clear(); vm.trustedContacts.add(TrustedContact("Sentinel", "Synthetic", "+70000000777", true, 777)) }
        pump(1000); assertEquals(listOf(777), vm.trustedContacts.map { it.id })
        compose.runOnIdle { mounted.value = false }; pump()
        compose.runOnIdle { vm.clearUserData(); assertTrue(vm.trustedContacts.isEmpty())
            vm.trustedContacts.add(TrustedContact("Sentinel", "Synthetic", "+70000000777", true, 777)); mounted.value = true }
        await { vm.trustedContacts.map { it.id } == expected }; pump(1000)
        assertEquals(listOf("GET /trusted-contacts"), requests.filter { it.contains("/trusted-contacts") })
        assertEquals(owner, ApiClient.queueSessionGeneration()); assertEquals(token, ApiClient.currentToken())
        assertSame(vm, ViewModelProvider(actor.get())[YuldashViewModel::class.java]); assertEquals(Screen.Home, vm.screen.value)
        assertEquals(expected, runBlocking { TestContacts.read().getOrThrow().map { it.id } })
        assertEquals(1, requests.count { it == "GET /trusted-contacts" })
    }
    @Test fun warmNonemptyAfterManualVmClearAndAppReentryRu() = scenario(AppLanguage.Ru, false)
    @Test fun warmNonemptyAfterManualVmClearAndAppReentryBa() = scenario(AppLanguage.Ba, false)
    @Test fun warmEmptyAfterManualVmClearAndAppReentryRu() = scenario(AppLanguage.Ru, true)
    @Test fun warmEmptyAfterManualVmClearAndAppReentryBa() = scenario(AppLanguage.Ba, true)
}
