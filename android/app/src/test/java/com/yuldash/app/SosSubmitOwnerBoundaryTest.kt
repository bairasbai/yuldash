package com.yuldash.app

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SosSubmitOwnerBoundaryTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private val posts = CopyOnWriteArrayList<RecordedRequest>()
    private lateinit var server: MockWebServer
    private var dropReply = false
    private var loginRequests = 0
    private val releaseReply = CountDownLatch(1)
    private val replyHeld = CountDownLatch(1)
    @Volatile private var holdReply = false
    @Volatile private var unauthorizedReply = false
    @Before fun prepare() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        ApiClient.saveToken("synthetic-owner-a")
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST" && request.path == "/sos") {
                        posts += request
                        if (holdReply) { replyHeld.countDown(); releaseReply.await(10, TimeUnit.SECONDS) }
                        // Токен истёк, обновить нечем: сервер сигнал не принимает.
                        if (unauthorizedReply) return MockResponse().setResponseCode(401).setBody("{\"detail\":\"expired\"}")
                    }
                    if (dropReply) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    return MockResponse().setBody("{\"id\":7,\"status\":\"new\"}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
    }
    @After fun cleanup() {
        releaseReply.countDown()
        compose.runOnIdle { mounted.value = false }
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }
    private fun sendLabel(lang: AppLanguage) = if (lang == AppLanguage.Ba) "Ярҙамға хәбәр итеү" else "Сообщить поддержке"
    private fun retainedSend(lang: AppLanguage = AppLanguage.Ru): () -> Boolean {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides lang) {
                YuldashTheme { if (mounted.value) SosScreen({}, { loginRequests++ }, bookingId = 42) }
            }
        }
        return compose.onNode(hasText(sendLabel(lang)) and hasClickAction())
            .performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    private fun settle() {
        compose.runOnIdle { Shadows.shadowOf(Looper.getMainLooper()).idle() }
        compose.waitForIdle()
    }
    @Test fun currentOwnerMaySendOneSignal() {
        val send = retainedSend()
        compose.runOnIdle { send(); send() }
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); posts.size == 1 }
        assertEquals("Bearer synthetic-owner-a", posts.single().getHeader("Authorization"))
    }
    @Test fun retainedHandlerCannotSendUnderReplacementAccount() {
        val send = retainedSend()
        ApiClient.saveToken("synthetic-owner-b")
        compose.runOnIdle { send() }
        settle()
        assertTrue(posts.isEmpty())
    }
    @Test fun retainedHandlerCannotSendAfterLogout() {
        val send = retainedSend()
        ApiClient.logout()
        compose.runOnIdle { send() }
        settle()
        assertTrue(posts.isEmpty())
    }
    @Test fun retainedHandlerCannotSendAfterLeavingScreen() {
        val send = retainedSend()
        compose.runOnIdle { mounted.value = false }
        compose.runOnIdle { send() }
        settle()
        assertTrue(posts.isEmpty())
    }
    private fun scrollTo(text: String) = compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))

    // Приёмка 23.10.2026 (P3): вход истёк, пока экран SOS был открыт, — сразу ко входу, без «закрой и открой».
    @Test fun expiredLoginLeadsStraightToLogin() {
        val send = retainedSend()
        ApiClient.logout()
        compose.runOnIdle { send() }
        settle()
        assertEquals(1, loginRequests)
        assertTrue(posts.isEmpty())
        compose.onAllNodesWithText("Вход в аккаунт сменился").assertCountEquals(0)
    }
    // Вошли другим аккаунтом: сигнал не ушёл — честная карточка, а не экран входа.
    @Test fun anotherAccountGetsNotSentCard() {
        val send = retainedSend()
        ApiClient.saveToken("synthetic-owner-b")
        compose.runOnIdle { send() }
        settle()
        assertEquals(0, loginRequests)
        assertTrue(posts.isEmpty())
        scrollTo("Вход в аккаунт сменился")
        compose.onNodeWithText("Вход в аккаунт сменился").assertIsDisplayed()
        compose.onNodeWithText("Сигнал не отправлен. Закрой экран и открой SOS снова. Позвонить 112 можно и без входа.").assertIsDisplayed()
    }
    // Вход сменился, пока запрос шёл: сервер мог сигнал сохранить — «не отправлен» было бы неправдой.
    @Test fun loginChangedWhileSignalWasOnTheRoadDoesNotClaimNotSent() {
        ApiClient.testTimeoutMs = 10000
        holdReply = true
        val send = retainedSend()
        compose.runOnIdle { send() }
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); replyHeld.count == 0L }
        ApiClient.saveToken("synthetic-owner-b")
        releaseReply.countDown()
        val after = "Вход в аккаунт сменился, пока сигнал отправлялся, — он мог дойти. Если можешь, позвони 112 и открой SOS снова."
        compose.waitUntil(10000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(after).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Не удалось подтвердить отправку").assertIsDisplayed()
        compose.onAllNodesWithText("Сигнал не отправлен", substring = true).assertCountEquals(0)
        assertEquals(1, posts.size)
        assertEquals(0, loginRequests)
    }
    // Приёмка #192 (P3): токен истёк и обновить нечем — сервер отвечает на /sos 401, сигнал НЕ сохранён,
    // вход закрыт. Это «до отправки»: сразу экран входа (с возвратом к SOS), а не «мог дойти… открой SOS снова».
    private fun expiredTokenOnSosLeadsToLogin(lang: AppLanguage) {
        unauthorizedReply = true
        val ba = lang == AppLanguage.Ba
        val unconfirmed = if (ba) "Ебәрелеүҙе раҫлап булманы" else "Не удалось подтвердить отправку"
        val send = retainedSend(lang)
        compose.runOnIdle { send() }
        compose.waitUntil(10000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            loginRequests > 0 || compose.onAllNodesWithText(unconfirmed).fetchSemanticsNodes().isNotEmpty()
        }
        settle()
        assertEquals("401 на SOS — сразу экран входа", 1, loginRequests)
        assertEquals(1, posts.size)
        assertFalse(ApiClient.isLoggedIn())
        compose.onAllNodesWithText(if (ba) "барып етеүе мөмкин" else "он мог дойти", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText(unconfirmed).assertCountEquals(0)
        compose.onAllNodesWithText(if (ba) "Аккаунтҡа инеү алмашынды" else "Вход в аккаунт сменился").assertCountEquals(0)
        // Кнопка не висит на «Отправляем…»: после входа можно нажать снова.
        assertTrue(compose.onAllNodesWithText(sendLabel(lang)).fetchSemanticsNodes().isNotEmpty())
    }
    @Test fun expiredTokenOnSosLeadsToLoginRu() = expiredTokenOnSosLeadsToLogin(AppLanguage.Ru)
    @Test fun expiredTokenOnSosLeadsToLoginBa() = expiredTokenOnSosLeadsToLogin(AppLanguage.Ba)
    @Test fun lostReplyShowsUnconfirmedInsteadOfClaimingNothingWasSent() {
        dropReply = true
        val send = retainedSend()
        compose.runOnIdle { send() }
        compose.waitUntil(10000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Не удалось подтвердить отправку").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, posts.size)
    }
}
