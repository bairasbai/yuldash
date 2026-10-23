package com.yuldash.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * Приёмка #192 (P3): вход истёк, и сервер ответил на SOS 401 — сигнал не принят, приложение ведёт ко
 * входу. Вошёл ТОТ ЖЕ человек — возвращаем его на экран SOS (без автоотправки: нажать ещё раз).
 * Вошёл другой аккаунт — он попадает к себе, чужой SOS ему не открываем (ExpiredSessionBackHistoryTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SosExpiredLoginReturnTest {
    @get:Rule val compose = createComposeRule()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val mounted = mutableStateOf(true)
    private val sosRequests = AtomicInteger()
    private lateinit var vm: YuldashViewModel
    private lateinit var server: MockWebServer
    private val user11 = "header.eyJzdWIiOiIxMSJ9.signature"
    private val user11Again = "header.eyJzdWIiOiIxMSJ9.fresh-signature"
    private val user33 = "header.eyJzdWIiOiIzMyJ9.signature"

    @Before fun prepare() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_completed", true).remove("preferred_role").commit()
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/sos" && request.method == "POST") {
                        sosRequests.incrementAndGet()
                        return MockResponse().setResponseCode(401).setBody("{}")
                    }
                    return MockResponse().setResponseCode(503).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1_000
        ApiClient.init(context)
        ApiClient.saveToken(user11)
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to Screen.Sos.name))) as T
        })[YuldashViewModel::class.java]
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        owner.viewModelStore.clear()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun awaitState(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            condition()
        }
    }

    /** SOS с истёкшим входом → 401 → экран входа; затем входит [nextToken] и открывается другой экран. */
    private fun expireOnSosThenLoginAs(nextToken: String) {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                YuldashTheme { YuldashApp() }
            }
        }
        val label = "Сообщить поддержке"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        compose.onNode(hasText(label) and hasClickAction()).assertIsDisplayed().performClick()
        awaitState { vm.screen.value == Screen.Login }
        compose.runOnIdle { assertFalse(ApiClient.isLoggedIn()) }
        compose.runOnIdle {
            ApiClient.saveToken(nextToken)
            vm.screen.value = Screen.Notifications   // любой экран после входа (как в SosLoginReturnTest)
        }
    }

    @Test fun samePersonReturnsToSosWithoutAutomaticSend() {
        expireOnSosThenLoginAs(user11Again)
        awaitState { vm.screen.value == Screen.Sos }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        assertEquals("Возврат на SOS не отправляет сигнал сам", 1, sosRequests.get())
    }

    @Test fun anotherAccountStaysWhereLoginTookIt() {
        expireOnSosThenLoginAs(user33)
        compose.mainClock.advanceTimeBy(2_000)
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        compose.runOnIdle {
            assertEquals("Чужой аккаунт не попадает в SOS прежнего", Screen.Notifications, vm.screen.value)
            assertFalse(Screen.Sos in vm.navHistory)
        }
        assertEquals(1, sosRequests.get())
    }
}
