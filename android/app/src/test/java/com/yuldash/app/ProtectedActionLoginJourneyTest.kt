package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

/** PATH-01: real protected action, login form and HTTP; no injected successful login. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProtectedActionLoginJourneyTest {
    private val accessToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIn0.synthetic-signature"
    @get:Rule val compose = createComposeRule()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val mounted = mutableStateOf(true)
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    private val verificationBodies = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer
    private lateinit var vm: YuldashViewModel
    private var rejectFirstCode = false

    @Before fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("onboarding_completed", true).commit()
        ApiClient.resetForTest()
        TelegramLoginBot.testName = "yuldash_test_bot"
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path?.substringBefore('?')
                    requests.add("${request.method} $path" to request.getHeader("Authorization"))
                    return when (path) {
                        "/auth/tg/start" -> json("""{"request_id":"synthetic-request"}""")
                        "/auth/tg/verify" -> {
                            verificationBodies.add(request.body.readUtf8())
                            if (rejectFirstCode && verificationBodies.size == 1) json("{}", 400)
                            else json("""{"access_token":"$accessToken","refresh_token":"synthetic-refresh","user":{"name":"Synthetic User","role":"passenger"}}""")
                        }
                        "/places/saved" -> json("""{"items":[]}""")
                        "/me" -> json("""{"id":1,"name":"Synthetic User","role":"passenger"}""")
                        "/bookings/mine", "/requests/mine", "/contacts", "/ads" -> json("""{"items":[]}""")
                        "/auth/logout", "/push/register", "/push/unregister" -> json("{}")
                        else -> json("{}", 404)
                    }
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1_000
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.init(context)
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to Screen.PassengerCabinet.name))) as T
        })[YuldashViewModel::class.java]
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        owner.viewModelStore.clear()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TelegramLoginBot.testName = null
        server.shutdown()
    }

    private fun mountAndRequestPlaces() {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                YuldashTheme {
                    // Only the defective destination is a sentinel: avoid loading native MapKit
                    // on the JVM after an incorrect jump Home. Expected destination stays real.
                    if (vm.screen.value == Screen.Home) Text("Unexpected home destination") else YuldashApp()
                }
            }
        }
        awaitText("Мои адреса")
        compose.onNodeWithText("Мои адреса").performScrollTo().performClick()
        compose.waitUntil(15_000) { settle(); vm.screen.value == Screen.Login }
        assertFalse(ApiClient.isLoggedIn())
        assertFalse(requests.any { it.first == "GET /places/saved" })
        compose.onNodeWithTag(TAG_LOGIN_TELEGRAM_BTN).performScrollTo().performClick()
        awaitText("Код из Telegram")
        compose.onNodeWithText("Код из Telegram").performTextInput("123456")
    }

    @Test fun protectedDestinationSurvivesRealTelegramLogin() {
        mountAndRequestPlaces()
        compose.onNodeWithTag(TAG_LOGIN_TG_VERIFY_BTN).performScrollTo().performClick()
        assertResumedPlaces(1)
    }

    @Test fun explicitDestinationHasPriorityOverPreviouslyChosenDriverRole() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE).edit()
            .putString("preferred_role", RideRole.Driver.name).commit()
        mountAndRequestPlaces()
        compose.onNodeWithTag(TAG_LOGIN_TG_VERIFY_BTN).performScrollTo().performClick()
        assertResumedPlaces(1)
    }

    @Test fun invalidCodeDoesNotConsumeDestinationAndRetryResumesIt() {
        rejectFirstCode = true
        mountAndRequestPlaces()
        compose.onNodeWithTag(TAG_LOGIN_TG_VERIFY_BTN).performScrollTo().performClick()
        awaitText("Неверный код. Проверь и введи снова.")
        assertEquals(Screen.Login, vm.screen.value)
        assertFalse(ApiClient.isLoggedIn())
        assertFalse(requests.any { it.first == "GET /places/saved" })
        compose.onNodeWithTag(TAG_LOGIN_TG_VERIFY_BTN).performScrollTo().performClick()
        assertResumedPlaces(2)
    }

    private fun assertResumedPlaces(attempts: Int) {
        compose.waitUntil(15_000) { settle(); vm.screen.value != Screen.Login }
        assertEquals("Login must resume the requested action", Screen.SavedPlaces, vm.screen.value)
        compose.waitUntil(15_000) {
            settle(); requests.any { it == ("GET /places/saved" to "Bearer $accessToken") }
        }
        awaitText("Добавить адрес")
        assertTrue(ApiClient.isLoggedIn())
        assertEquals(1, ApiClient.myUserId())
        assertEquals(attempts, verificationBodies.size)
        verificationBodies.forEach {
            assertEquals("synthetic-request", JSONObject(it).getString("request_id"))
            assertEquals("123456", JSONObject(it).getString("code"))
        }
        assertEquals(1, requests.count { it.first == "POST /auth/tg/start" })
    }

    private fun settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
    }
    private fun awaitText(text: String) = compose.waitUntil(15_000) {
        settle(); compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)
}
