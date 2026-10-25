package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.*
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Executes both real YuldashApp effects, including the legacy SavedState restore boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineTripSnapshotNavigationBoundaryTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val actor by lazy { Robolectric.buildActivity(MainActivity::class.java).create() }
    private lateinit var vm: YuldashViewModel
    private lateinit var server: MockWebServer
    private val mounted = mutableStateOf(true)
    private val records = CopyOnWriteArrayList<String>()
    @Volatile private var failing = false
    private val stamp = SnapshotClock(3, 1000, 1000, 1000)
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(context); ApiClient.saveToken("header.eyJzdWIiOiI3In0.signature")
        DeepLink.pendingBookingChatId.value = null; DeepLink.pendingCompletedBookingId.value = null
        context.getSharedPreferences("yuldash_prefs", 0).edit().putBoolean("onboarding_completed", true)
            .putString("mode_last", "pooling").putBoolean("mode_hint_shown", true).commit()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    records += "${request.method} $path"
                    if (path == "/bookings/11/role") return if (failing)
                        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    else MockResponse().setBody("""{"role":"passenger","status":"confirmed"}""")
                    if (path == "/bookings/11/details") return MockResponse().setBody("""{"booking_id":11,"ride_id":22,"role":"passenger","status":"confirmed","from_city":"Уфа","to_city":"Мелеуз","depart_at":"2026-10-25T10:00:00"}""")
                    return MockResponse().setBody("[]")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 1000
        vm = ViewModelProvider(actor.get())[YuldashViewModel::class.java]
        vm.screen.value = Screen.Home; vm.persistNav()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get(), LocalPoolingNativeMapEnabled provides false) {
                YuldashTheme { YuldashApp() }
            }
        }
        pump()
    }
    private fun pump() {
        compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle(); Snapshot.sendApplyNotifications()
        Thread.sleep(10)
    }
    private fun waitFor(check: () -> Boolean) = compose.waitUntil(15000) { pump(); check() }
    private fun missing(kind: String) {
        val disk = SnapshotTestDisk().apply { unavailable = kind == "store" }
        OfflineTripSnapshots.installForTest(disk) { if (kind == "boot") null else stamp }
        if (kind == "scope") {
            val field = ApiClient::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            val real = field.get(ApiClient) as SharedPreferences
            real.edit().remove(OfflineTripSnapshots.AUTH_SCOPE).commit()
            val token = ApiClient.currentToken()
            field.set(ApiClient, object : SharedPreferences by real {
                override fun edit(): SharedPreferences.Editor {
                    val edit = real.edit()
                    return object : SharedPreferences.Editor by edit {
                        private var scopeWrite = false
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                            if (key == OfflineTripSnapshots.AUTH_SCOPE) scopeWrite = true
                            edit.putString(key, value); return this
                        }
                        override fun commit() = if (scopeWrite) false else edit.commit()
                    }
                }
            })
            try {
                ApiClient::class.java.getDeclaredMethod("ensureOfflineSnapshotScope").apply { isAccessible = true }.invoke(ApiClient)
                assertNull(OfflineTripSnapshots.owner.value); assertEquals(token, ApiClient.currentToken())
            } finally { field.set(ApiClient, real) }
        } else OfflineTripSnapshots.activate(7, UUID.randomUUID().toString(), ApiClient.queueSessionGeneration())
    }
    private fun request(restore: Boolean) {
        compose.runOnIdle { vm.navigateLocally { vm.screen.value = Screen.Home }; vm.selectedRide.value = null; vm.activeTrip.value = null }
        pump()
        compose.runOnIdle {
            if (restore) { vm.activeBookingId.value = 11; vm.screen.value = Screen.Booking }
            else vm.requestBookingDestination(11, 7, completed = false)
        }
    }
    private fun boundary(restore: Boolean) {
        for (kind in listOf("scope", "store", "boot")) {
            missing(kind); failing = false
            val before = records.count { it == "GET /bookings/11/role" }
            request(restore)
            waitFor { records.count { it == "GET /bookings/11/role" } > before && vm.selectedRide.value != null }
            assertEquals("$restore/$kind normal live", Screen.Booking, vm.screen.value)
            assertNull(vm.offlineTripSnapshot.value)
            failing = true
            val beforeFailure = records.count { it == "GET /bookings/11/role" }
            request(restore)
            waitFor { records.count { it == "GET /bookings/11/role" } > beforeFailure &&
                (if (restore) vm.screen.value == Screen.Home else vm.pendingBookingNavigation.value == null) }
            assertNull("$restore/$kind failure stays cache-closed", vm.offlineTripSnapshot.value)
            assertNull(vm.selectedRide.value)
        }
    }
    @Test fun linkKeepsLive200AndClosesCacheOnNetworkFailureForAllOptionalFailures() = boundary(restore = false)
    @Test fun restoreKeepsLive200AndClosesCacheOnNetworkFailureForAllOptionalFailures() = boundary(restore = true)
    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }; pump()
        actor.destroy(); vm.clearUserData()
        DeepLink.pendingBookingChatId.value = null; DeepLink.pendingCompletedBookingId.value = null
        ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
    }
}
