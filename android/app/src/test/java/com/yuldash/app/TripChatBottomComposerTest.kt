package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Жалоба 13.10.2026: в чате поездки строка ввода и кнопка голосового стояли посередине экрана
 * (чат был пунктом длинного списка «Моя поездка»). Здесь — настоящий экран, настоящие HTTP и
 * WebSocket: строка ввода прибита к низу и при пустой, и при длинной ленте; новое сообщение —
 * сразу над ней; на экране поездки — карточка с непрочитанными; отправка идёт прежним путём.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h740dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TripChatBottomComposerTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(false)
    private val requests = CopyOnWriteArrayList<String>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val auth = LinkedBlockingQueue<String>()
    private val sent = LinkedBlockingQueue<String>()
    private lateinit var server: MockWebServer
    @Volatile private var historySize = 0
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before fun prepare() = runBlocking {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    requests += "${request.method} $path"
                    if (path == "/ws/bookings/42") return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { sockets += webSocket }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val body = JSONObject(text)
                            if (body.getString("type") == "auth") auth.offer(body.getString("token"))
                            else sent.offer(body.getString("text"))
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                    })
                    val body = when (path) {
                        "/auth/verify" -> """{"access_token":"$tokenA","refresh_token":"refresh-A"}"""
                        "/auth/logout", "/push/unregister" -> "{}"
                        "/bookings/42/role" -> """{"role":"passenger","status":"confirmed","driver_phase":""}"""
                        "/bookings/42/messages" -> JSONObject().put("items", JSONArray().apply {
                            // Чужие и свои вперемешку — как в живой переписке.
                            for (i in 1..historySize) put(JSONObject().put("id", i).put("sender_id", if (i % 2 == 0) 11 else 22).put("text", "msg-$i"))
                        }).toString()
                        "/bookings/42/boarding-code" -> """{"code":"1234"}"""
                        "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"confirmed","from_city":"Город А","to_city":"Город Б","driver_name":"Ильдар","pay_method":"cash","pay_amount":700}"""
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setBody(body).setHeader("Content-Type", "application/json")
                }
            }
            start()
        }
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 10000
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.verifyCode("+70000000000", "000000", "A").getOrThrow()
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        Outbox.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        // Дисклеймер безопасности уже прочитан: он закрывается «Понятно» и в геометрии не участвует.
        context.getSharedPreferences("yuldash", Context.MODE_PRIVATE).edit().putBoolean("chat_safety_seen", true).commit()
        Unit
    }

    @After fun cleanup() {
        compose.runOnIdle {
            mounted.value = false
            if (owner.registry.currentState != Lifecycle.State.INITIALIZED) owner.registry.currentState = Lifecycle.State.DESTROYED
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        sockets.forEach { it.close(1000, null) }
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        Outbox.initStores(MemoryDiskPreferences(), null)
        context.getSharedPreferences("yuldash", Context.MODE_PRIVATE).edit().remove("chat_safety_seen").commit()
        server.shutdown()
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15000) {
        compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        condition()
    }
    private fun pump(millis: Long = 1000) {
        repeat((millis / 100).toInt()) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }

    private fun mount(fontScale: Float? = null) {
        mounted.value = true
        compose.setContent {
            if (mounted.value) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.Ru,
                    LocalLifecycleOwner provides owner,
                    LocalDensity provides if (fontScale == null) density else Density(density.density, fontScale),
                ) {
                    ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42, onBack = {}, onTripEnd = {}, onSos = {})
                }
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        assertEquals(tokenA, auth.poll(10, TimeUnit.SECONDS))
        waitFor { requests.contains("GET /bookings/42/details") && compose.onAllNodesWithText("Я сел").fetchSemanticsNodes().isNotEmpty() }
        pump(500)
    }

    private fun openChat() {
        compose.onNodeWithText("Написать").performClick()
        pump(1000)
        compose.onNodeWithTag("tripChatScreen").assertIsDisplayed()
    }

    private fun root(): Rect = compose.onRoot().fetchSemanticsNode().boundsInRoot
    private fun bounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInRoot
    private fun dp(px: Float) = with(compose.density) { px.toDp().value }
    private fun mic() = compose.onNodeWithContentDescription("Записать голос")
    private fun tripList() = compose.onNode(hasScrollToNodeAction() and
        SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))

    /** Нижний край кнопки микрофона почти совпадает с нижним краем экрана — строка прибита к низу. */
    private fun assertComposerPinnedToBottom() {
        mic().assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        val gap = dp(root().bottom - bounds(mic()).bottom)
        // 40dp: при крупном шрифте поле выше кнопки, и кнопка стоит по центру строки ввода.
        assertTrue("Строка ввода не у нижнего края: до низа $gap dp", gap in 0f..40f)
        val fieldGap = dp(root().bottom - bounds(compose.onNode(hasSetTextAction())).bottom)
        assertTrue("Поле ввода не у нижнего края: до низа $fieldGap dp", fieldGap in 0f..64f)
    }

    @Test fun emptyChatKeepsComposerAtTheBottomAndEmptyStateAboveIt() {
        historySize = 0
        mount(); openChat()
        assertComposerPinnedToBottom()
        val empty = compose.onNodeWithText("Пока нет сообщений. Напиши первым")
        empty.assertIsDisplayed()
        // Пустая лента прижата к низу: заглушка стоит там, где появится первое сообщение.
        assertTrue("Заглушка пустой ленты висит наверху", bounds(empty).top > root().height / 2)
        assertTrue(bounds(empty).bottom <= bounds(compose.onNode(hasSetTextAction())).top)
    }

    @Test fun longChatOpensAtTheNewestMessageRightAboveTheComposer() {
        historySize = 40
        mount(); openChat()
        assertComposerPinnedToBottom()
        val newest = compose.onNodeWithText("msg-40")
        newest.assertIsDisplayed()
        val gap = dp(bounds(compose.onNode(hasSetTextAction())).top - bounds(newest).bottom)
        assertTrue("Между последним сообщением и строкой ввода $gap dp", gap in 0f..140f)
        // Начало переписки ушло вверх — без прокрутки его не видно.
        compose.onAllNodesWithText("msg-1").fetchSemanticsNodes().forEach { node ->
            assertTrue("Старое сообщение видно без прокрутки", node.boundsInRoot.bottom <= bounds(compose.onNodeWithTag("chatFeed")).top)
        }
    }

    @Test fun incomingMessageAppearsAboveTheComposerWithoutScrolling() {
        historySize = 40
        mount(); openChat()
        assertTrue(sockets.first().send("""{"type":"message","id":41,"sender_id":22,"text":"msg-41"}"""))
        waitFor { compose.onAllNodesWithText("msg-41").fetchSemanticsNodes().isNotEmpty() }
        pump(1000)
        compose.onNodeWithText("msg-41").assertIsDisplayed()
        assertTrue(bounds(compose.onNodeWithText("msg-41")).bottom <= bounds(compose.onNode(hasSetTextAction())).top)
        assertComposerPinnedToBottom()
    }

    @Test fun bottomComposerKeepsTheSocketSendAndQuickReplyPath() {
        historySize = 2
        mount(); openChat()
        compose.onNode(hasSetTextAction()).performTextInput("Выхожу через минуту")
        compose.onNodeWithContentDescription("Отправить").performClick()
        // Outbox commits on IO before returning to the main looper for WS send.
        waitFor { sent.isNotEmpty() }
        assertEquals("Выхожу через минуту", sent.poll())
        waitFor { compose.onAllNodesWithText("Выхожу через минуту").fetchSemanticsNodes().isNotEmpty() }
        // Поле очистилось, кнопка снова микрофон — строка осталась внизу.
        compose.onNode(hasSetTextAction()).assertTextEquals("")
        assertComposerPinnedToBottom()
        compose.onNodeWithText("Выезжаю").performClick()   // первая готовая фраза — видна без прокрутки ряда
        // Outbox commits on IO before returning to the main looper for WS send.
        waitFor { sent.isNotEmpty() }
        assertEquals("Выезжаю", sent.poll())
    }

    @Test fun tripCardCountsUnreadAndReadingTheChatClearsIt() {
        historySize = 2
        mount()
        tripList().performScrollToNode(hasTestTag("tripChatCard"))
        // Карточка показывает последнее сообщение; история при первом показе прочитана — бейджа нет.
        compose.onNodeWithText("Ты: msg-2").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Новых сообщений: 1").assertCountEquals(0)
        assertTrue(sockets.first().send("""{"type":"message","id":3,"sender_id":22,"text":"Подъезжаю к рынку"}"""))
        waitFor { compose.onAllNodesWithContentDescription("Новых сообщений: 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Подъезжаю к рынку").assertIsDisplayed()
        compose.onNodeWithTag("tripChatCard").performClick()
        pump(1000)
        compose.onNodeWithTag("tripChatScreen").assertIsDisplayed()
        compose.onNodeWithText("Подъезжаю к рынку").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        pump(1000)
        compose.onNodeWithTag("tripChatCard").assertExists()
        compose.onAllNodesWithContentDescription("Новых сообщений: 1").assertCountEquals(0)
        compose.onAllNodesWithTag("tripChatScreen").assertCountEquals(0)
    }

    @Test fun headerTripActionReturnsToTheTripAndKeepsTheDraft() {
        historySize = 2
        mount(); openChat()
        compose.onNode(hasSetTextAction()).performTextInput("черновик")
        compose.onNodeWithContentDescription("Открыть поездку").performClick()
        pump(1000)
        compose.onAllNodesWithTag("tripChatScreen").assertCountEquals(0)
        compose.onNodeWithText("Я сел").assertExists()
        openChat()
        compose.onNode(hasSetTextAction()).assertTextEquals("черновик")
    }

    /** Ревью: позиция ленты помнится, и пришедшие без человека сообщения оказывались под краем экрана. */
    @Test fun reopeningTheChatShowsMessagesThatArrivedWhileItWasClosed() {
        historySize = 40
        mount(); openChat()
        compose.onNodeWithContentDescription("Открыть поездку").performClick()
        pump(1000)
        for (i in 41..43) assertTrue(sockets.first().send("""{"type":"message","id":$i,"sender_id":22,"text":"msg-$i"}"""))
        // Бейдж на «Написать» в нижней панели: три новых, пока человек смотрел поездку.
        waitFor { compose.onAllNodesWithContentDescription("Новых сообщений: 3").fetchSemanticsNodes().isNotEmpty() }
        openChat()
        compose.onNodeWithText("msg-43").assertIsDisplayed()
        assertTrue(bounds(compose.onNodeWithText("msg-43")).bottom <= bounds(compose.onNode(hasSetTextAction())).top)
        assertComposerPinnedToBottom()
        compose.onAllNodesWithContentDescription("К новым сообщениям").assertCountEquals(0)
    }

    /** Читает старое — лента не дёргается; новое ждёт под кнопкой «к новым» с числом. */
    @Test fun scrolledUpReaderIsNotYankedAndGetsAJumpButtonWithTheCount() {
        historySize = 40
        mount(); openChat()
        tripList().performScrollToNode(hasText("msg-5"))
        pump(500)
        assertTrue(sockets.first().send("""{"type":"message","id":41,"sender_id":22,"text":"msg-41"}"""))
        waitFor { compose.onAllNodesWithContentDescription("Новых сообщений: 1").fetchSemanticsNodes().isNotEmpty() }
        pump(500)
        compose.onNodeWithText("msg-5").assertIsDisplayed()
        val feed = bounds(compose.onNodeWithTag("chatFeed"))
        assertTrue("Новое сообщение выдернуло читающего вниз",
            compose.onAllNodesWithText("msg-41").fetchSemanticsNodes().none { feed.overlaps(it.boundsInRoot) })
        compose.onNodeWithContentDescription("К новым сообщениям").performClick()
        pump(1000)
        compose.onNodeWithText("msg-41").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Новых сообщений: 1").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("К новым сообщениям").assertCountEquals(0)
    }

    @Config(qualifiers = "w320dp-h640dp")
    @Test fun narrowScreenWithLargeFontKeepsComposerUsable() {
        historySize = 40
        mount(fontScale = 1.3f); openChat()
        assertComposerPinnedToBottom()
        assertTrue("Кнопка микрофона ушла за правый край", bounds(mic()).right <= root().right)
        assertTrue("Кнопка микрофона меньше 48dp", dp(bounds(mic()).width) >= 48f && dp(bounds(mic()).height) >= 48f)
    }
}
