package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

/** Real MainActivity dispatch/default VM and YuldashApp; no forced remount or destination change after repeat. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiptRepeatedBookingNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val actor by lazy { Robolectric.buildActivity(MainActivity::class.java).create() }
    private lateinit var vm: YuldashViewModel
    private lateinit var server: MockWebServer
    private val mounted = mutableStateOf(true)
    private val records = CopyOnWriteArrayList<Triple<String, String, String?>>()
    private val unexpectedWrites = CopyOnWriteArrayList<String>()
    private val token = "header.eyJzdWIiOiIxMSJ9.signature"
    @Volatile private var role = "driver"
    private var contentMounted = false

    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(context)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    records += Triple(request.method ?: "", path, request.getHeader("Authorization"))
                    if (request.method != "GET" && request.method != "HEAD" &&
                        (path.startsWith("/bookings/") || path.startsWith("/rides/") ||
                            path.startsWith("/trips/") || path.startsWith("/trusted-contacts"))) {
                        unexpectedWrites += "${request.method} $path"
                        return json("{}", 405)
                    }
                    return json(when (path) {
                        "/bookings/42/role" -> """{"role":"$role","status":"done","driver_phase":""}"""
                        "/bookings/42/details" -> """{"booking_id":42,"ride_id":9,"role":"$role","status":"done","from_city":"Уфа","to_city":"Бирск","driver_name":"SyntheticDriver42","passenger_name":"SyntheticPassenger42"}"""
                        "/bookings/mine" -> """{"items":[{"id":42,"ride_id":9,"status":"done","seats":1,"from_city":"Уфа","to_city":"Бирск","price":400,"driver_name":"SyntheticDriver42"}]}"""
                        "/trips/42/receipt" -> """{"booking_id":42,"ride_id":9,"role":"$role","from_city":"Уфа","to_city":"Бирск","amount":400,"pay_method":"cash","paid":true,"driver_name":"SyntheticDriver42","counterparty_name":"SyntheticPeer42","my_stars":0}"""
                        "/bookings/42/tip" -> """{"already_thanked":false,"driver_name":"SyntheticDriver42"}"""
                        "/bookings/42/boarding-code" -> """{"code":""}"""
                        "/config/features" -> "{}"
                        else -> """{"items":[],"messages":[],"code":"","unread":0}"""
                    })
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 2000; ApiClient.saveToken(token)
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_completed", true).putBoolean("mode_hint_shown", true)
            .putString("mode_last", "pooling").commit()
        AppPrefs.setLanguage(context, AppLanguage.Ru)
        DeepLink.pendingCompletedBookingId.value = null; DeepLink.pendingBookingChatId.value = null
        vm = ViewModelProvider(actor.get())[YuldashViewModel::class.java]
        vm.screen.value = Screen.Home; vm.persistNav()
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setHeader("Connection", "close").setBody(body)

    private fun pump() {
        Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(10)
    }
    private fun await(check: () -> Boolean) = compose.waitUntil(15000) { pump(); check() }
    private fun count(path: String) = records.count { it.first == "GET" && it.second == path }
    private fun receiptReads() = count("/trips/42/receipt")
    private fun dispatch(type: String) {
        val intent = Intent(context, MainActivity::class.java).putExtra("type", type)
            .putExtra("id", "42").putExtra("recipient_user_id", "11")
            .putExtra(MainActivity.EXTRA_NAVIGATION_DELIVERY_ID, UUID.randomUUID().toString())
        MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }.invoke(actor.get(), intent)
    }
    private fun completed() = await {
        vm.pendingBookingNavigation.value == null && vm.screen.value == Screen.ActiveTrip &&
            vm.activeBookingId.value == 42 && receiptReads() > 0 &&
            compose.onAllNodesWithTag("rideshareCompletedList").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithTag("rideshareReceiptLoading").fetchSemanticsNodes().isEmpty()
    }
    private fun receiptButton(): SemanticsNodeInteraction {
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasText("Квитанция"))
        return compose.onNodeWithText("Квитанция").assertIsDisplayed().assertIsEnabled()
    }
    private fun assertCompletedActorRole() {
        compose.runOnIdle {
            assertEquals(11, ApiClient.myUserId())
            assertEquals(token, ApiClient.currentToken())
            assertEquals(AppLanguage.Ru, vm.language.value)
        }
        assertTrue(records.any { it.first == "GET" && it.second == "/bookings/42/role" && it.third == "Bearer $token" })
        assertTrue(records.any { it.first == "GET" && it.second == "/trips/42/receipt" && it.third == "Bearer $token" })
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasTestTag("rideshareRatingCard"))
        val caption = if (role == "driver") "Как всё прошло с попутчиком" else "Как всё прошло с водителем"
        await { compose.onAllNodesWithText(caption).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(caption).assertIsDisplayed()
    }
    private fun draft() {
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasTestTag("rideshareStar4"))
        compose.onNodeWithTag("rideshareStar4").performClick()
        await { compose.onAllNodesWithText("Приехал вовремя").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasText("Приехал вовремя"))
        compose.onNodeWithText("Приехал вовремя").performClick()
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasText("Добавить пару слов"))
        compose.onNodeWithText("Добавить пару слов").performClick()
        await { compose.onAllNodesWithTag("rideshareReviewText").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasTestTag("rideshareReviewText"))
        compose.onNodeWithTag("rideshareReviewText").performTextInput("Спасибо за дорогу")
    }
    private fun assertDraft() {
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasTestTag("rideshareReviewText"))
        assertEquals("Спасибо за дорогу", compose.onNodeWithTag("rideshareReviewText").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasText("Приехал вовремя"))
        compose.onNodeWithText("Приехал вовремя").assertIsSelected()
        compose.onNodeWithTag("rideshareRatingSubmit").assertIsEnabled()
    }

    private fun repeatedReceipt(actorRole: String, type: String, keepDraft: Boolean) {
        role = actorRole
        compose.mainClock.autoAdvance = false
        contentMounted = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get(),
                LocalPoolingNativeMapEnabled provides false) { YuldashTheme { YuldashApp() } }
        }
        compose.runOnIdle { dispatch(type) }; completed()
        assertCompletedActorRole()
        if (keepDraft) draft()
        val oldReceipt = frozenReceiptClick(receiptButton())
        val roleReads = count("/bookings/42/role")
        val beforeRepeatReceipts = receiptReads()
        // One main-thread block: the retained tap runs immediately after dispatch, before any repaint.
        compose.runOnIdle {
            dispatch(type)
            oldReceipt()
            assertEquals("An old tap cannot select a receipt after a new intent", Screen.ActiveTrip, vm.screen.value)
        }
        assertEquals(beforeRepeatReceipts, receiptReads())
        await { count("/bookings/42/role") > roleReads && vm.pendingBookingNavigation.value == null }
        completed(); compose.waitForIdle()
        val beforeCurrentTap = receiptReads()
        compose.runOnIdle {
            oldReceipt()
            assertEquals("Repaint must not revive the retained old tap", Screen.ActiveTrip, vm.screen.value)
        }
        assertEquals(beforeCurrentTap, receiptReads())
        // Fetch the real current action; no second delivery, remount, Home detour, or retry tap.
        receiptButton().performClick()
        compose.runOnIdle {
            assertEquals("The current receipt button must work after a same-booking intent", Screen.TripReceipt, vm.screen.value)
        }
        await { receiptReads() > beforeCurrentTap }
        assertEquals(beforeCurrentTap + 1, receiptReads())
        assertTrue(records.filter { it.second == "/trips/42/receipt" }.all { it.third == "Bearer $token" })
        assertFalse(records.any { it.second.startsWith("/trips/") && it.second.endsWith("/receipt") && it.second != "/trips/42/receipt" })
        assertTrue(unexpectedWrites.toString(), unexpectedWrites.isEmpty())
        if (keepDraft) {
            // Both destinations coexist during AnimatedContent; advance ordinary frames until receipt owns the UI.
            await {
                vm.screen.value == Screen.TripReceipt &&
                    compose.onAllNodesWithTag("rideshareCompletedList").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithText("Квитанция").fetchSemanticsNodes().size == 1 &&
                    compose.onAllNodesWithText("Рейс").fetchSemanticsNodes().size == 1 &&
                    compose.onAllNodesWithContentDescription("Назад").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText("Квитанция").assertIsDisplayed()
            compose.onNodeWithContentDescription("Назад").assertIsDisplayed().assertIsEnabled().performClick()
            completed()
            // Wait for the outgoing receipt to leave before inspecting the restored rating draft.
            await {
                vm.screen.value == Screen.ActiveTrip &&
                    compose.onAllNodesWithTag("rideshareCompletedList").fetchSemanticsNodes().size == 1 &&
                    compose.onAllNodesWithText("Рейс").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithContentDescription("Назад").fetchSemanticsNodes().size == 1
            }
            assertDraft()
            assertTrue("Opening and returning from a receipt must not submit rating/thanks", unexpectedWrites.isEmpty())
        }
    }

    @Test fun driverCurrentReceiptWorksAfterRepeatedBookingIntentAndRetainedTapStaysRejected() =
        repeatedReceipt("driver", "booking", keepDraft = false)

    @Test fun passengerCurrentReceiptWorksAfterRepeatedCompletedIntentAndKeepsRatingDraft() =
        repeatedReceipt("passenger", "booking_done", keepDraft = true)

    @After fun cleanup() {
        if (contentMounted) { compose.runOnIdle { mounted.value = false }; compose.waitForIdle() }
        actor.destroy()
        DeepLink.pendingCompletedBookingId.value = null; DeepLink.pendingBookingChatId.value = null
        ApiClient.resetForTest(); ApiClient.testTimeoutMs = null
        server.shutdown()
        assertTrue(unexpectedWrites.toString(), unexpectedWrites.isEmpty())
    }
}
/** Snapshot the real callback, not Compose's node-bound semantic dispatcher. Fail closed on ABI change. */
internal fun frozenReceiptClick(button: SemanticsNodeInteraction): () -> Unit {
    val action = button.fetchSemanticsNode().config[SemanticsActions.OnClick].action
        ?: error("Receipt semantic action missing")
    val nodeType = Class.forName("androidx.compose.foundation.AbstractClickableNode")
    val capturedNodeFields = action.javaClass.declaredFields.filter { it.type == nodeType }
    check(capturedNodeFields.size == 1) { "Unexpected Compose semantic dispatcher layout: ${action.javaClass.name}" }
    val node = capturedNodeFields.single().apply { isAccessible = true }.get(action)
    check(nodeType.isInstance(node)) { "Receipt dispatcher node missing" }
    val field = nodeType.getDeclaredField("onClick")
    check(field.type == kotlin.jvm.functions.Function0::class.java) { "Unexpected Compose onClick field type" }
    val callback = field.apply { isAccessible = true }.get(node)
    check(callback is Function0<*>) { "Receipt original callback missing" }
    // Capture the value now. Do not read node.onClick when the retained action is executed.
    return { callback.invoke(); Unit }
}