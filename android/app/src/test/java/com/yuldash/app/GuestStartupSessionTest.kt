package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.awaitOfflineWriteSnapshotReset02
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuestStartupSessionTest {
    @get:Rule val compose = createComposeRule()
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
    private val mounted = mutableStateOf(true)
    private val privateRequests = listOf("/me", "/requests/mine", "/trusted-contacts", "/me/update")
        .associateWith { AtomicInteger() }
    private val meRequests = privateRequests.getValue("/me")
    private val myRequests = privateRequests.getValue("/requests/mine")
    private val authenticatedMyRequests = AtomicInteger()
    private val authenticatedMeRequests = AtomicInteger()
    private lateinit var server: MockWebServer
    private lateinit var vm: YuldashViewModel
    private var contentSet = false
    private val originalMotion = CanonMotion.enabled
    @Volatile private var meStatus = 401
    @Volatile private var requestsStatus = 200

    @Before fun prepare() {
        val context: Context = ApplicationProvider.getApplicationContext()
        ApiClient.resetForTest()
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE).edit()
            .clear().putBoolean("onboarding_completed", false)
            .putString("preferred_role", RideRole.Passenger.name).commit()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath
                    privateRequests[path]?.incrementAndGet()
                    val authenticated = request.getHeader("Authorization") == "Bearer local-startup-test-account"
                    if (path == "/requests/mine") {
                        if (authenticated) authenticatedMyRequests.incrementAndGet()
                        val status = if (authenticated) requestsStatus else 401
                        return MockResponse().setResponseCode(status)
                            .setHeader("Content-Type", "application/json")
                            .setBody(if (status == 200) """[{"id":72,"from_city":"Уфа","to_city":"Казань","seats":1,"category":"ride","status":"active"}]""" else "{}")
                    }
                    if (path == "/me") {
                        if (request.getHeader("Authorization") == "Bearer local-startup-test-account") {
                            authenticatedMeRequests.incrementAndGet()
                        }
                        return MockResponse().setResponseCode(meStatus)
                            .setHeader("Content-Type", "application/json")
                            .setBody(if (meStatus == 200) """{"id":91,"role":"admin"}""" else "{}")
                    }
                    if (path in privateRequests) {
                        return MockResponse().setResponseCode(if (authenticated) 200 else 401)
                            .setHeader("Content-Type", "application/json")
                            .setBody(if (path == "/trusted-contacts") "[]" else "{}")
                    }
                    return MockResponse().setResponseCode(503).setBody("{}")
                }
            }
            start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        }
        ApiClient.testBaseUrl = server.url("/").newBuilder().host("127.0.0.1")
            .build().toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1_000
        ApiClient.init(context)
        ApiClient.logout()
        awaitOfflineWriteSnapshotReset02()
        ApiClient.sessionExpired.value = false
        CanonMotion.enabled = false
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle()) as T
        })[YuldashViewModel::class.java]
    }

    @After fun cleanup() {
        try {
            if (contentSet) {
                compose.runOnIdle { mounted.value = false }
                compose.waitForIdle()
            }
        } finally {
            CanonMotion.enabled = originalMotion
            try {
                owner.viewModelStore.clear()
                ApiClient.logout()
            } finally {
                try {
                    ApiClient.resetForTest()
                    ApiClient.testTimeoutMs = null
                } finally {
                    if (::server.isInitialized) server.shutdown()
                }
            }
        }
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        contentSet = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(
                LocalViewModelStoreOwner provides owner,
                LocalPoolingNativeMapEnabled provides false
            ) { YuldashTheme { YuldashApp() } }
        }
    }

    private fun pump() {
        compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        Thread.sleep(10)
    }

    private fun awaitState(condition: () -> Boolean) {
        compose.waitUntil(10_000) { pump(); condition() }
    }

    @Test fun guestColdStartKeepsOnboardingAndNeverRequestsPrivateData() {
        mount()
        awaitState { vm.screen.value == Screen.Onboarding || vm.screen.value == Screen.Login }
        repeat(100) { pump() }
        if (myRequests.get() > 0) awaitState { vm.screen.value == Screen.Login }
        compose.runOnIdle {
            assertEquals("A guest must not request /me; screen=${vm.screen.value}; requests=${meRequests.get()}", 0, meRequests.get())
            for ((path, count) in privateRequests) {
                assertEquals("A guest must not request $path; screen=${vm.screen.value}", 0, count.get())
            }
            assertEquals(Screen.Onboarding, vm.screen.value)
            assertFalse(ApiClient.isLoggedIn())
        }
    }

    @Test fun signedInColdStartStillLoadsTheProfileRole() {
        meStatus = 200
        ApiClient.saveToken("local-startup-test-account")
        mount()
        awaitState {
            meRequests.get() > 0 && vm.isAdmin.value && vm.localRequests.any { it.serverId == 72 }
        }
        compose.runOnIdle {
            assertTrue("The profile request must carry this test session", authenticatedMeRequests.get() > 0)
            assertTrue("Requests must still load for this test session", authenticatedMyRequests.get() > 0)
            assertTrue(vm.localRequests.any { it.serverId == 72 })
            assertTrue(ApiClient.isLoggedIn())
            assertTrue(vm.isAdmin.value)
        }
    }

    @Test fun privateRequests401StillExpiresAnAuthenticatedSession() {
        meStatus = 200
        requestsStatus = 401
        ApiClient.saveToken("local-startup-test-account")
        mount()
        awaitState {
            authenticatedMyRequests.get() > 0 && !ApiClient.isLoggedIn() && vm.screen.value == Screen.Login
        }
        compose.runOnIdle {
            assertTrue("The failed private request must belong to the test session", authenticatedMyRequests.get() > 0)
            assertFalse(ApiClient.isLoggedIn())
            assertEquals(Screen.Login, vm.screen.value)
        }
    }

    @Test fun expiredSignedInColdStartStillReturnsToLogin() {
        ApiClient.saveToken("local-startup-test-account")
        mount()
        awaitState {
            meRequests.get() > 0 && !ApiClient.isLoggedIn() && vm.screen.value == Screen.Login
        }
        compose.runOnIdle {
            assertTrue("The expired request must have belonged to the test session", authenticatedMeRequests.get() > 0)
            assertFalse(ApiClient.isLoggedIn())
            assertEquals(Screen.Login, vm.screen.value)
        }
    }
}
