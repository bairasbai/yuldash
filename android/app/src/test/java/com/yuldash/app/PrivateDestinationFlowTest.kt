package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual App/Activity dispatch, synthetic accounts and controlled HTTP; no live provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivateDestinationFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(true)
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"; private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    private val actor by lazy { Robolectric.buildActivity(MainActivity::class.java).create() }
    private lateinit var vm: YuldashViewModel
    private lateinit var server: MockWebServer
    private val records = CopyOnWriteArrayList<Triple<String,String,String?>>()
    private val roleCount = AtomicInteger()
    private val started = CountDownLatch(1); private val release = CountDownLatch(1); private val dispatched = CountDownLatch(1)
    private var holdRole = false; private var roleStatus = 200; private var supportRow = false
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(context); ApiClient.saveToken(tokenA); clear()
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE).edit().putBoolean("onboarding_completed", false).commit()
        server = MockWebServer().apply {
            dispatcher = object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath; records += Triple(request.method!!, path, request.getHeader("Authorization"))
                    if (path == "/bookings/43/role") {
                        roleCount.incrementAndGet()
                        if (holdRole) { started.countDown(); assertTrue(release.await(35, TimeUnit.SECONDS)); dispatched.countDown() }
                        return json("""{"role":"passenger","status":"confirmed"}""", roleStatus)
                    }
                    return json(when {
                        path == "/notifications" && supportRow -> """{"items":[{"id":901,"type":"support","title_ru":"Обращение 43","title_ba":"Мөрәжәғәт 43","ref_kind":"support","ref_id":43,"read":false}],"unread":1}"""
                        path == "/support/tickets/43" -> """{"id":43,"subject":"Обращение 43","status":"open","messages":[]}"""
                        else -> """{"items":[],"role":"passenger","status":"done"}"""
                    }, if (path == "/instant/orders/active") 503 else 200)
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
        vm = ViewModelProvider(actor.get())[YuldashViewModel::class.java]; vm.screen.value = Screen.Notifications; vm.persistNav()
    }
    private fun clear() { NavSignals.openDriverCabinet.value = false; NavSignals.openInstantOrder.value = false; NavSignals.openInstantChat.value = 0; DeepLink.pendingParcels.value = false; DeepLink.pendingSupport.value = false; DeepLink.pendingBookingChatId.value = null; DeepLink.pendingCompletedBookingId.value = null; DeepLink.pendingRideId.value = null }
    private fun json(body:String,status:Int=200) = MockResponse().setResponseCode(status).setHeader("Content-Type","application/json").setBody(body)
    private fun pump(ms:Long=500) { repeat((ms/100).toInt()) { compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)); Thread.sleep(10) }; compose.waitForIdle() }
    private fun await(check:()->Boolean) = compose.waitUntil(12000) { compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); check() }
    private fun mount() { compose.mainClock.autoAdvance=false; compose.setContent { if(mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get()) { YuldashTheme { YuldashApp() } } }; pump() }
    private fun raw(type:String,id:Int=43) { MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply { isAccessible=true }.invoke(actor.get(),Intent(context,MainActivity::class.java).putExtra("type",type).putExtra("id",id.toString()).putExtra("recipient_user_id", "11")) }
    private fun deliver(type:String,id:Int=43) = compose.runOnIdle { raw(type,id) }
    private fun reached(screen:Screen) { await { vm.screen.value==screen && vm.pendingScreenNavigation.value==null }; pump() }
    private fun hasGet(path:String,token:String=tokenA)=records.any { it.first=="GET" && it.second==path && it.third=="Bearer $token" }
    @After fun cleanup() { release.countDown(); compose.runOnIdle { mounted.value=false }; pump(300); actor.destroy(); clear(); ApiClient.resetForTest(); ApiClient.testTimeoutMs=null; server.shutdown() }
    @Test fun loggedOutTaxiChatRejectedAndNewAfterLoginUsesItsOrderId() {
        ApiClient.logout(); vm.screen.value=Screen.Login; mount(); deliver("order_chat"); pump()
        assertEquals(Screen.Login,vm.screen.value); assertFalse(records.any { it.second=="/instant/orders/43/messages" })
        assertNull(vm.pendingScreenNavigation.value)
        compose.runOnIdle { ApiClient.saveToken(tokenA); vm.screen.value=Screen.Notifications }; pump(); assertEquals(Screen.Notifications,vm.screen.value)
        deliver("order_chat"); reached(Screen.InstantChat)
        await { hasGet("/instant/orders/43/messages") }; compose.onNodeWithText("Чат с водителем").assertExists(); assertEquals(43,vm.instantChatOrderId.value)
    }
    @Test fun loggedOutParcelRejectedAndNewAfterLoginConsumesOnce() {
        ApiClient.logout(); vm.screen.value=Screen.Login; mount(); deliver("parcel"); pump(); assertEquals(Screen.Login,vm.screen.value)
        assertNull(vm.pendingScreenNavigation.value)
        compose.runOnIdle { ApiClient.saveToken(tokenA); vm.screen.value=Screen.Notifications }; pump(); assertEquals(Screen.Notifications,vm.screen.value)
        deliver("parcel"); reached(Screen.Parcels)
        compose.runOnIdle { vm.screen.value=Screen.Notifications }; pump(); assertEquals(Screen.Notifications,vm.screen.value); assertFalse(DeepLink.pendingParcels.value)
    }
    @Test fun loggedOutSupportRejectedAndNewAfterLoginConsumesOnce() {
        ApiClient.logout(); vm.screen.value=Screen.Login; mount(); deliver("support"); pump(); assertEquals(Screen.Login,vm.screen.value)
        assertNull(vm.pendingScreenNavigation.value)
        compose.runOnIdle { ApiClient.saveToken(tokenA); vm.screen.value=Screen.Notifications }; pump(); assertEquals(Screen.Notifications,vm.screen.value)
        deliver("support"); reached(Screen.SupportTickets)
        compose.runOnIdle { vm.screen.value=Screen.Notifications }; pump(); assertEquals(Screen.Notifications,vm.screen.value)
    }
    @Test fun introDoesNotConsumePrivateDestination() {
        vm.screen.value=Screen.Intro; mount(); deliver("support"); pump(); assertEquals(Screen.Intro,vm.screen.value); assertNotNull(vm.pendingScreenNavigation.value)
        compose.runOnIdle { vm.screen.value=Screen.Notifications }; reached(Screen.SupportTickets)
    }
    @Test fun pendingTaxiChatCannotRebindToNextAccountInSameFrame() {
        mount(); compose.runOnIdle { raw("order_chat"); ApiClient.saveToken(tokenB) }; pump(1500)
        assertEquals(Screen.Notifications,vm.screen.value); assertNull(vm.pendingScreenNavigation.value)
        assertFalse(records.any { it.second=="/instant/orders/43/messages" })
    }
    @Test fun sameOwnerTokenReplacementKeepsTaxiChat() {
        mount(); compose.runOnIdle { raw("order_chat"); ApiClient.saveToken(tokenA+"new") }; reached(Screen.InstantChat)
        await { hasGet("/instant/orders/43/messages",tokenA+"new") }
    }
    @Test fun newestSupportBeatsQueuedTaxiInOneFrame() {
        mount(); compose.runOnIdle { raw("order_chat"); raw("support") }; reached(Screen.SupportTickets)
        assertFalse(records.any { it.second=="/instant/orders/43/messages" }); assertEquals(0,NavSignals.openInstantChat.value)
    }
    @Test fun newSupportRejectsHeldBookingSuccess() {
        holdRole=true; mount(); deliver("booking"); await { started.count==0L }; deliver("support"); reached(Screen.SupportTickets)
        release.countDown(); assertTrue(dispatched.await(8,TimeUnit.SECONDS)); pump(1500)
        assertEquals(Screen.SupportTickets,vm.screen.value); assertNull(vm.activeBookingId.value); assertNull(vm.pendingBookingNavigation.value)
    }
    @Test fun bookingReplacesUnconsumedSupportBeforeIntroFinishes() {
        vm.screen.value=Screen.Intro; mount(); compose.runOnIdle { raw("support"); raw("booking") }
        compose.runOnIdle { vm.screen.value=Screen.Notifications }; await { vm.screen.value==Screen.Booking && vm.activeBookingId.value==43 }
        assertFalse(DeepLink.pendingSupport.value); assertNull(vm.pendingScreenNavigation.value)
    }
    @Test fun retainedBookingRetryCannotReplaceNewSupportInSameFrame() {
        roleStatus=503; mount(); deliver("booking"); await { compose.onAllNodesWithTag("bookingLinkRetry").fetchSemanticsNodes().isNotEmpty() }
        val retry=compose.onNodeWithTag("bookingLinkRetry").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { raw("support"); retry() }; reached(Screen.SupportTickets); pump(1000)
        assertEquals(1,roleCount.get()); assertNull(vm.pendingBookingNavigation.value)
    }
    @Test fun retainedBookingRetryCannotReplaceConsumedSupport() {
        roleStatus=503; mount(); deliver("booking"); await { compose.onAllNodesWithTag("bookingLinkRetry").fetchSemanticsNodes().isNotEmpty() }
        val retry=compose.onNodeWithTag("bookingLinkRetry").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        deliver("support"); reached(Screen.SupportTickets); compose.runOnIdle { retry() }; pump(1500)
        assertEquals(Screen.SupportTickets,vm.screen.value); assertEquals(1,roleCount.get()); assertNull(vm.pendingBookingNavigation.value)
    }
    @Test fun supportNotificationRowOpensExactTicketThroughSavedRecord() {
        supportRow=true; mount(); await { compose.onAllNodesWithText("Обращение 43").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Обращение 43").performClick(); reached(Screen.SupportTicket); await { hasGet("/support/tickets/43") }
        assertEquals(43,vm.supportTicketId.value); assertTrue(vm.navHistory.contains(Screen.Notifications))
    }
    @Test fun retainedSupportRowCannotOpenTicketForNextAccount() {
        supportRow=true; mount(); await { compose.onAllNodesWithText("Обращение 43").fetchSemanticsNodes().isNotEmpty() }
        val row=compose.onNodeWithText("Обращение 43").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { ApiClient.saveToken(tokenB); row() }; pump(1500)
        assertEquals(Screen.Notifications,vm.screen.value); assertEquals(0,vm.supportTicketId.value); assertNull(vm.pendingScreenNavigation.value)
        assertFalse(records.any { it.second=="/support/tickets/43" })
    }
    @Test fun supportPushOpensTicketsInsteadOfDonation() {
        mount(); deliver("support"); await { vm.pendingScreenNavigation.value==null && vm.screen.value!=Screen.Notifications }; pump()
        assertEquals("Ответ поддержки должен открыть обращения",Screen.SupportTickets,vm.screen.value)
        await { hasGet("/support/tickets") }; compose.onNodeWithText("Поддержка Юлдаш").assertExists()
        compose.onAllNodesWithText("Поддержать Юлдаш 🌱").assertCountEquals(0)
    }
}
