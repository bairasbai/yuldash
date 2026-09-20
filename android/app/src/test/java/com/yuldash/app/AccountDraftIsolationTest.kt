package com.yuldash.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountDraftIsolationTest {
    @get:Rule val compose = createComposeRule()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val mounted = mutableStateOf(true)
    private lateinit var server: MockWebServer
    private lateinit var vm: YuldashViewModel
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val nameLabel = "Фамилия и имя как в документе"
    private val plateLabel = "Госномер машины"

    private fun token(id: Int): String = "e30." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"sub\":\"$id\"}".toByteArray()) + ".local"

    @Before fun prepare() {
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_completed", false).commit()
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/courier/application" -> MockResponse().setResponseCode(200).setBody("{\"application\":null}")
                    "/auth/logout", "/auth/push/unregister" -> MockResponse().setResponseCode(200).setBody("{\"ok\":true}")
                    else -> MockResponse().setResponseCode(503).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1_000
        ApiClient.init(context)
        ApiClient.saveToken(token(101))
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to Screen.CourierOnboarding.name))) as T
        })[YuldashViewModel::class.java]
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                YuldashTheme { YuldashApp() }
            }
        }
        awaitForm()
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        owner.viewModelStore.clear()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun awaitForm() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Селфи с документом в руках")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(nameLabel))
        compose.onNode(hasText(nameLabel) and hasSetTextAction()).assertExists()
    }

    private fun fieldValue(label: String): String {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(label))
        return compose.onNode(hasText(label) and hasSetTextAction()).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text
    }

    private fun enterDraftAndLeave() {
        compose.onNode(hasText(nameLabel) and hasSetTextAction()).performTextInput("Анна Локальная")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(plateLabel))
        compose.onNode(hasText(plateLabel) and hasSetTextAction()).performTextInput("А123ВС102")
        compose.runOnIdle { vm.screen.value = Screen.Notifications }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    @Test fun courierDraftIsPreservedWhenTheSameAccountReturns() {
        enterDraftAndLeave()
        compose.runOnIdle { vm.screen.value = Screen.CourierOnboarding }
        awaitForm()
        assertEquals("Анна Локальная", fieldValue(nameLabel))
        assertEquals("А123ВС102", fieldValue(plateLabel))
    }

    @Test fun refreshedTokenForTheSameAccountKeepsTheCourierDraft() {
        enterDraftAndLeave()
        compose.runOnIdle {
            ApiClient.saveToken(token(101).removeSuffix(".local") + ".refreshed-local")
            vm.screen.value = Screen.CourierOnboarding
        }
        awaitForm()
        assertEquals(101, ApiClient.myUserId())
        assertEquals("Token refresh must preserve the same person's name", "Анна Локальная", fieldValue(nameLabel))
        assertEquals("Token refresh must preserve the same person's vehicle", "А123ВС102", fieldValue(plateLabel))
    }

    @Test fun courierDraftDoesNotCrossLogoutAndAnotherAccountLogin() {
        enterDraftAndLeave()
        compose.runOnIdle {
            ApiClient.logout()
            endSession(context, vm)
            vm.screen.value = Screen.Login
        }
        compose.waitForIdle()
        compose.runOnIdle {
            ApiClient.saveToken(token(202))
            vm.screen.value = Screen.CourierOnboarding
        }
        awaitForm()
        assertEquals(202, ApiClient.myUserId())
        assertEquals("Previous account's name must not reappear", "", fieldValue(nameLabel))
        assertEquals("Previous account's vehicle must not reappear", "", fieldValue(plateLabel))
    }
}
