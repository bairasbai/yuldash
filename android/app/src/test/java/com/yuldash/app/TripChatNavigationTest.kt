package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
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

/**
 * Переписка поездки — отдельный экран (Screen.TripChat), как в мессенджерах. Полное приложение,
 * настоящая навигация и HTTP: диалог из вкладки «Чат» открывается сразу чатом, «Написать» на
 * экране поездки — тоже; «Назад» и кнопка «Открыть поездку» не водят по кругу.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TripChatNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(true)
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val actor by lazy { Robolectric.buildActivity(MainActivity::class.java).create() }
    private lateinit var vm: YuldashViewModel
    private lateinit var server: MockWebServer
    private val records = CopyOnWriteArrayList<String>()
    @Volatile private var status = "confirmed"

    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(context); ApiClient.saveToken(tokenA)
        context.getSharedPreferences("yuldash_prefs", 0).edit().clear()
            .putBoolean("onboarding_completed", false).putString("mode_last", "pooling")
            .putBoolean("mode_hint_shown", true).commit()
        context.getSharedPreferences("yuldash", Context.MODE_PRIVATE).edit().putBoolean("chat_safety_seen", true).commit()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    records += "${request.method} $path"
                    return json(when (path) {
                        "/conversations" -> """{"items":[{"booking_id":42,"peer_name":"Ильдар","route":"Пункт А → Пункт Б","last_message":"Буду в семь"}]}"""
                        "/bookings/mine" -> """{"items":[{"id":42,"ride_id":7,"status":"confirmed","seats":1,"price":750,"from_city":"Пункт А","to_city":"Пункт Б","driver_name":"Ильдар"}]}"""
                        "/bookings/42/details" -> """{"booking_id":42,"ride_id":7,"role":"passenger","status":"confirmed","from_city":"Пункт А","to_city":"Пункт Б","driver_name":"Ильдар","contact_unlocked":false}"""
                        "/bookings/42/role" -> """{"role":"passenger","status":"$status"}"""
                        "/bookings/42/messages" -> """{"items":[{"id":1,"sender_id":22,"text":"Буду в семь"}]}"""
                        "/bookings/42/boarding-code" -> """{"code":"1234"}"""
                        else -> """{"items":[],"role":"passenger"}"""
                    }, if (path == "/instant/orders/active") 503 else 200)
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
        vm = ViewModelProvider(actor.get())[YuldashViewModel::class.java]
        vm.screen.value = Screen.Home; vm.startHomeTab.value = HomeTab.Map; vm.persistNav()
    }
    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }; pump(300); actor.destroy(); vm.clearUserData()
        ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
        context.getSharedPreferences("yuldash", Context.MODE_PRIVATE).edit().remove("chat_safety_seen").commit()
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun pump(ms: Long = 500) { repeat((ms / 100).toInt()) { compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); Snapshot.sendApplyNotifications(); Thread.sleep(10) }; compose.waitForIdle() }
    private fun await(check: () -> Boolean) = compose.waitUntil(15000) { compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); Snapshot.sendApplyNotifications(); check() }
    private fun mount() { compose.mainClock.autoAdvance = false; compose.setContent { if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get(), LocalPoolingNativeMapEnabled provides false) { YuldashTheme { YuldashApp() } } }; pump() }
    private fun click(text: String) { await { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }; compose.onNodeWithText(text).performClick(); pump() }
    private fun back() { compose.onNodeWithContentDescription("Назад").performClick(); pump(1000) }
    private fun chatShown() = compose.onAllNodesWithTag("tripChatScreen").fetchSemanticsNodes().isNotEmpty()

    /** Чат на экране, строка ввода у нижнего края, ни следа экрана поездки. */
    private fun assertFullScreenChat() {
        await { chatShown() && compose.onAllNodesWithText("Буду в семь").fetchSemanticsNodes().isNotEmpty() }
        pump(1000)
        assertEquals(Screen.TripChat, vm.screen.value)
        compose.onNodeWithText("Буду в семь").assertIsDisplayed()
        val mic = compose.onNodeWithContentDescription("Записать голос").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val bottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Строка ввода не у нижнего края", with(compose.density) { (bottom - mic.bottom).toDp().value } <= 24f)
        compose.onAllNodesWithText("Моя поездка").assertCountEquals(0)
    }

    private fun openTripFromRides() {
        vm.startHomeTab.value = HomeTab.Rides; mount()
        click("Открыть поездку")
        await { vm.screen.value == Screen.ActiveTrip && compose.onAllNodesWithText("Написать").fetchSemanticsNodes().isNotEmpty() }
        pump(1000)
    }

    @Test fun chatTabConversationOpensStraightIntoTheChat() {
        vm.startHomeTab.value = HomeTab.Chat; mount()
        click("Ильдар")
        assertFullScreenChat()
        // В шапке — собеседник и маршрут.
        compose.onNodeWithText("Ильдар").assertIsDisplayed()
        compose.onNodeWithText("Пункт А → Пункт Б").assertIsDisplayed()
        back()
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(HomeTab.Chat, vm.startHomeTab.value)
    }

    @Test fun tripWriteOpensChatAndBackReturnsToTheTripThenHome() {
        openTripFromRides()
        click("Написать")
        assertFullScreenChat()
        back()
        await { vm.screen.value == Screen.ActiveTrip }
        compose.onNodeWithText("Моя поездка").assertIsDisplayed()
        assertFalse(chatShown())
        back()
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(HomeTab.Rides, vm.startHomeTab.value)
    }

    @Test fun headerTripActionReplacesTheChatWithoutABackLoop() {
        vm.startHomeTab.value = HomeTab.Chat; mount()
        click("Ильдар")
        assertFullScreenChat()
        compose.onNodeWithContentDescription("Открыть поездку").performClick(); pump(1000)
        assertEquals(Screen.ActiveTrip, vm.screen.value)
        compose.onNodeWithText("Моя поездка").assertIsDisplayed()
        assertFalse("Чат остался в истории «Назад»", vm.navHistory.contains(Screen.TripChat))
        back()
        assertEquals(Screen.Home, vm.screen.value)
    }

    /** Поездку закрыли, пока открыт чат: показываем завершение видом поездки, чат не остаётся в «Назад». */
    @Test fun tripClosedWhileChatIsOpenReplacesTheChatWithTheTrip() {
        vm.startHomeTab.value = HomeTab.Chat; mount()
        click("Ильдар")
        assertFullScreenChat()
        status = "done"
        pump(13000)   // следующий опрос статуса поездки
        await { vm.screen.value == Screen.ActiveTrip }
        pump(1000)
        assertFalse(chatShown())
        assertFalse(vm.navHistory.contains(Screen.TripChat))
    }

    /** Ревью: второй тап «Открыть поездку» за время анимации ломал историю — «Назад» из чата проскакивал поездку. */
    @Test fun doubleTapOnOpenTripKeepsTheBackTrail() {
        openTripFromRides()
        click("Написать")
        assertFullScreenChat()
        val open = compose.onNodeWithContentDescription("Открыть поездку").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.runOnIdle { open(); open() }
        pump(1000)
        assertEquals(Screen.ActiveTrip, vm.screen.value)
        click("Написать")
        assertFullScreenChat()
        back()
        assertEquals("«Назад» из чата должен вести к поездке", Screen.ActiveTrip, vm.screen.value)
        back()
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test fun tripChatCardOpensTheChat() {
        openTripFromRides()
        val list = compose.onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
        list.performScrollToNode(hasTestTag("tripChatCard")); pump()
        compose.onNodeWithTag("tripChatCard").performClick(); pump(1000)
        assertFullScreenChat()
    }
}
