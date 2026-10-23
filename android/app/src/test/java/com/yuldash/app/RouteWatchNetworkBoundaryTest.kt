package com.yuldash.app

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
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
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real screen semantics and HTTP; deferred replies exercise screen/account boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RouteWatchNetworkBoundaryTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private val slot = mutableStateOf(0)
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    private val held = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val fixtureFailures = CopyOnWriteArrayList<String>()
    private val ownerA = "synthetic-route-owner-a"
    private val ownerB = "synthetic-route-owner-b"
    private val row = "Сибай  →  Уфа"
    private lateinit var server: MockWebServer
    @Volatile private var exists = true
    @Volatile private var deletion = "success"
    @Volatile private var createCode = 200
    @Volatile private var getCode = 200
    @Volatile private var heldMethod: String? = null

    @Before fun prepare() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        ApiClient.saveToken(ownerA)
        ShadowToast.reset()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val method = request.method.orEmpty()
                    val path = request.path.orEmpty()
                    val bearer = request.getHeader("Authorization")
                    requests += "$method $path" to bearer
                    // Capture the remote result before holding it: releasing A must not read B's state.
                    val body = if (exists && bearer == "Bearer $ownerA")
                        """[{"id":7,"from_city":"Сибай","to_city":"Уфа","direction":"forward"}]""" else "[]"
                    if (path.startsWith("/route-watch") && method == heldMethod && bearer == "Bearer $ownerA") {
                        held.countDown()
                        if (!release.await(20, TimeUnit.SECONDS)) fixtureFailures += "Held $method was not released"
                    }
                    return when {
                        method == "GET" && path == "/route-watch" -> reply(getCode, body)
                        method == "POST" && path == "/route-watch" -> {
                            if (createCode == 200) exists = true
                            reply(createCode, """{"id":7}""")
                        }
                        method == "DELETE" && path == "/route-watch/7" -> when {
                            !exists -> reply(404, "{}")
                            deletion == "unavailable" -> reply(503, "{}")
                            deletion == "lost-ack" -> { exists = false; reply(503, "{}") }
                            else -> { exists = false; reply(200, """{"ok":true}""") }
                        }
                        else -> reply(200, "{}")
                    }
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 25000
    }

    private fun reply(code: Int, body: String) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)

    @After fun cleanup() {
        release.countDown()
        compose.runOnIdle { mounted.value = false }
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
        assertTrue("Fixture timed out: $fixtureFailures", fixtureFailures.isEmpty())
    }

    private fun await(check: () -> Boolean) = compose.waitUntil(15000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        check()
    }
    private fun settle() {
        repeat(8) { Shadows.shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle(); Thread.sleep(20) }
    }
    private fun count(method: String, owner: String? = null) = requests.count {
        it.first.startsWith("$method /route-watch") && (owner == null || it.second == "Bearer $owner")
    }
    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun mount(ready: Boolean = true) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme { if (mounted.value) key(slot.value) {
                    RouteWatchesScreen({}, prefillFrom = "Сибай", prefillTo = "Уфа")
                } }
            }
        }
        if (ready) await { shown(row) || shown("Пока нет подписок") || shown("Что-то пошло не так") }
    }
    private fun action(text: String, description: Boolean = false): () -> Boolean {
        val node = if (description) compose.onNodeWithContentDescription(text)
            else compose.onNode(hasText(text) and hasClickAction())
        return node.performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    private fun invoke(action: () -> Boolean) = compose.runOnIdle { action() }
    private fun finishedDelete() = await { shown("Пока нет подписок") }

    @Test fun lostDeleteAcknowledgementThenNotFoundReachesAbsence() {
        deletion = "lost-ack"
        mount()
        invoke(action("Удалить", true))
        await { shown("Не получилось удалить подписку") }
        assertFalse(exists)
        assertTrue(shown(row))
        invoke(action("Повторить"))
        finishedDelete()
        assertEquals(2, count("DELETE"))
        assertFalse(shown(row))
    }

    @Test fun unavailableDeleteKeepsRowAndExplicitRetrySucceeds() {
        deletion = "unavailable"
        mount()
        invoke(action("Удалить", true))
        await { shown("Не получилось удалить подписку") }
        assertTrue(exists)
        assertTrue(shown(row))
        deletion = "success"
        invoke(action("Повторить"))
        finishedDelete()
        assertEquals(2, count("DELETE"))
    }

    @Test fun duplicateDeleteBeforeNextFrameSendsOnce() {
        heldMethod = "DELETE"
        mount()
        val remove = action("Удалить", true)
        compose.runOnIdle { remove(); remove() }
        await { held.count == 0L }
        settle()
        assertEquals(1, count("DELETE"))
        release.countDown()
        finishedDelete()
    }

    @Test fun duplicateCreateBeforeNextFrameSendsOnce() {
        exists = false
        heldMethod = "POST"
        mount()
        val add = action("Следить за маршрутом")
        compose.runOnIdle { add(); add() }
        await { held.count == 0L }
        settle()
        assertEquals(1, count("POST"))
        release.countDown()
        await { shown(row) }
    }

    @Test fun unavailableCreatePreservesFormAndAllowsExplicitRetry() {
        exists = false
        createCode = 503
        mount()
        invoke(action("Следить за маршрутом"))
        await { count("POST") == 1 }
        settle()
        compose.onNodeWithText("Откуда").assertExists()
        assertTrue(shown("Сибай"))
        assertTrue(shown("Уфа"))
        assertFalse(shown(row))
        createCode = 200
        invoke(action("Следить за маршрутом"))
        await { shown(row) }
        assertEquals(2, count("POST"))
    }

    private fun retainedCannotSend(method: String, beforeNextFrame: Boolean = true, change: () -> Unit) {
        mount()
        val retained = if (method == "DELETE") action("Удалить", true) else action("Следить за маршрутом")
        if (beforeNextFrame) compose.runOnIdle { change(); retained() }
        else {
            compose.runOnIdle { change() }
            compose.waitForIdle()
            invoke(retained)
        }
        settle()
        assertEquals(0, count(method))
    }
    @Test fun retainedDeleteCannotUseReplacementLogin() = retainedCannotSend("DELETE") { ApiClient.saveToken(ownerB) }
    @Test fun retainedCreateCannotUseReplacementLogin() = retainedCannotSend("POST") { ApiClient.saveToken(ownerB) }
    @Test fun retainedDeleteCannotUseGuest() = retainedCannotSend("DELETE") { ApiClient.logout() }
    @Test fun retainedCreateCannotUseGuest() = retainedCannotSend("POST") { ApiClient.logout() }
    @Test fun retainedDeleteCannotRunAfterLeavingScreen() = retainedCannotSend("DELETE", false) { mounted.value = false }
    @Test fun retainedCreateCannotRunAfterLeavingScreen() = retainedCannotSend("POST", false) { mounted.value = false }
    @Test fun disposedDeleteCannotUseRemountedScreen() = retainedCannotSend("DELETE", false) { slot.value++ }

    @Test fun heldListCannotDisplayPreviousAccountRows() {
        heldMethod = "GET"
        mount(ready = false)
        await { held.count == 0L }
        compose.runOnIdle { ApiClient.saveToken(ownerB) }
        await { count("GET", ownerB) == 1 }
        release.countDown()
        await { shown("Пока нет подписок") }
        settle()
        assertFalse(shown(row))
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test fun heldDeleteFailureDoesNotPolluteNextAccount() {
        heldMethod = "DELETE"
        deletion = "unavailable"
        mount()
        invoke(action("Удалить", true))
        await { held.count == 0L }
        compose.runOnIdle { ApiClient.saveToken(ownerB) }
        await { shown("Пока нет подписок") }
        release.countDown()
        settle()
        assertFalse(shown(row))
        assertFalse(shown("Не получилось удалить подписку"))
        assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(0, count("DELETE", ownerB))
    }

    @Test fun heldCreateSuccessDoesNotClearNextAccountFormOrToast() {
        exists = false
        heldMethod = "POST"
        mount()
        invoke(action("Следить за маршрутом"))
        await { held.count == 0L }
        compose.runOnIdle { ApiClient.saveToken(ownerB) }
        await { count("GET", ownerB) == 1 }
        release.countDown()
        settle()
        assertFalse(shown(row))
        assertTrue(shown("Сибай"))
        assertTrue(shown("Уфа"))
        assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(0, count("POST", ownerB))
    }

    @Test fun retainedListRetryCannotLoadNextAccountAgain() {
        getCode = 503
        mount()
        val retry = action("Повторить")
        getCode = 200
        compose.runOnIdle { ApiClient.saveToken(ownerB) }
        await { shown("Пока нет подписок") }
        val before = count("GET", ownerB)
        invoke(retry)
        settle()
        assertEquals(before, count("GET", ownerB))
    }
}
