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
class RemainingPrivateFlowTest {
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
    private var holdRole = false; private var roleStatus = 200; private var rowKind: String? = null; private var rowType: String? = null
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
                        path == "/notifications" && rowKind != null -> """{"items":[{"id":901,"type":"${rowType ?: rowKind}","title_ru":"Событие 43","title_ba":"Ваҡиға 43","ref_kind":"$rowKind","ref_id":43,"read":false}],"unread":1}"""
                        path == "/support/tickets/43" -> """{"id":43,"subject":"Обращение 43","status":"open","messages":[]}"""
                        else -> """{"items":[],"role":"passenger","status":"done"}"""
                    }, if (path == "/instant/orders/active") 503 else 200)
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
        vm = ViewModelProvider(actor.get())[YuldashViewModel::class.java]; vm.screen.value = Screen.Notifications; vm.persistNav()
    }
    private fun clear() { NavSignals.openDriverCabinet.value = false; NavSignals.openInstantOrder.value = false; NavSignals.openInstantChat.value = 0; DeepLink.pendingParcels.value = false; DeepLink.pendingSupport.value = false; DeepLink.pendingBookingChatId.value = null; DeepLink.pendingCompletedBookingId.value = null; DeepLink.pendingRideId.value = null; NavSignals.openAdsCabinet.value=false; NavSignals.openPartnerCabinet.value=false; DeepLink.pendingFairness.value=false; DeepLink.pendingRequestsFeed.value=false; DeepLink.pendingRequestResponsesId.value=null; DeepLink.pendingApplicationScreen.value=null }
    private fun json(body:String,status:Int=200) = MockResponse().setResponseCode(status).setHeader("Content-Type","application/json").setBody(body)
    private fun pump(ms:Long=500) { repeat((ms/100).toInt()) { compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)); Thread.sleep(10) }; compose.waitForIdle() }
    private fun await(check:()->Boolean) = compose.waitUntil(12000) { compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); check() }
    private fun mount() { compose.mainClock.autoAdvance=false; compose.setContent { if(mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get()) { YuldashTheme { YuldashApp() } } }; pump() }
    private fun raw(type:String,id:Int=43) { MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply { isAccessible=true }.invoke(actor.get(),Intent(context,MainActivity::class.java).putExtra("type",type).putExtra("id",id.toString()).putExtra("recipient_user_id", "11")) }
    private fun deliver(type:String,id:Int=43) = compose.runOnIdle { raw(type,id) }
    private fun reached(screen:Screen) { await { vm.screen.value==screen && vm.pendingScreenNavigation.value==null }; pump() }
    private fun hasGet(path:String,token:String=tokenA)=records.any { it.first=="GET" && it.second==path && it.third=="Bearer $token" }
    @After fun cleanup() { release.countDown(); compose.runOnIdle { mounted.value=false }; pump(300); actor.destroy(); clear(); ApiClient.resetForTest(); ApiClient.testTimeoutMs=null; server.shutdown() }
    private fun guest(type:String,destination:Screen) {
        ApiClient.logout();vm.screen.value=Screen.Login;mount();deliver(type);pump()
        assertEquals(Screen.Login,vm.screen.value)
        if(type=="request") assertFalse(records.any { it.second=="/requests/43/responses" })
        assertNull(vm.pendingScreenNavigation.value)
        compose.runOnIdle { ApiClient.saveToken(tokenA);vm.screen.value=Screen.Notifications };pump();assertEquals(Screen.Notifications,vm.screen.value)
        deliver(type);reached(destination)
        if(type=="request") { assertEquals(43,vm.responsesRequestId.value);await { hasGet("/requests/43/responses") };compose.onNodeWithText("Отклики на заявку").assertExists() }
        compose.runOnIdle { vm.screen.value=Screen.Notifications };pump();assertEquals(Screen.Notifications,vm.screen.value)
    }
    @Test fun adRejectsLoggedOutAndNewAfterLoginConsumesOnce()=guest("ad",Screen.AdsCabinet)
    @Test fun partnerRejectsLoggedOutAndNewAfterLoginConsumesOnce()=guest("partner",Screen.PartnerCabinet)
    @Test fun fairnessRejectsLoggedOutAndNewAfterLoginConsumesOnce()=guest("incident",Screen.FairnessCenter)
    @Test fun requestRejectsLoggedOutAndNewAfterLoginKeepsItsId()=guest("request",Screen.RequestResponses)
    @Test fun requestWatchRejectsLoggedOutAndNewAfterLoginConsumesOnce()=guest("request_watch",Screen.RequestsFeed)
    @Test fun taxiApplicationRejectsLoggedOutAndNewAfterLoginConsumesOnce()=guest("taxi_apply",Screen.TaxiOnboarding)
    @Test fun courierApplicationRejectsLoggedOutAndNewAfterLoginConsumesOnce()=guest("courier_apply",Screen.CourierOnboarding)
    @Test fun pendingAdCannotRebindToNextAccountInSameFrame() {
        mount();compose.runOnIdle { raw("ad");ApiClient.saveToken(tokenB) };pump(1500)
        assertEquals(Screen.Notifications,vm.screen.value);assertNull(vm.pendingScreenNavigation.value)
        assertFalse(records.any { it.second=="/ads/mine" })
    }
    @Test fun pendingRequestCannotRebindToNextAccountInSameFrame() {
        mount();compose.runOnIdle { raw("request");ApiClient.saveToken(tokenB) };pump(1500)
        assertEquals(Screen.Notifications,vm.screen.value);assertNull(vm.pendingScreenNavigation.value)
        assertFalse(records.any { it.second=="/requests/43/responses" })
    }
    @Test fun newestPartnerBeatsAdInOneFrame() {
        mount();compose.runOnIdle { raw("ad");raw("partner") };reached(Screen.PartnerCabinet)
        assertFalse(NavSignals.openAdsCabinet.value);assertFalse(records.any { it.second=="/ads/mine" })
    }
    @Test fun requestReplacesHeldBookingSuccess() {
        holdRole=true;mount();deliver("booking");await { started.count==0L };deliver("request");reached(Screen.RequestResponses)
        release.countDown();assertTrue(dispatched.await(8,TimeUnit.SECONDS));pump(1500)
        assertEquals(Screen.RequestResponses,vm.screen.value);assertEquals(43,vm.responsesRequestId.value);assertNull(vm.activeBookingId.value)
    }
    @Test fun retainedBookingRetryCannotReplaceNewAd() {
        roleStatus=503;mount();deliver("booking");await { compose.onAllNodesWithTag("bookingLinkRetry").fetchSemanticsNodes().isNotEmpty() }
        val retry=compose.onNodeWithTag("bookingLinkRetry").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { raw("ad");retry() };reached(Screen.AdsCabinet);pump(1000);assertEquals(1,roleCount.get())
    }
    private fun retainedRow(kind:String):()->Boolean {
        rowKind=kind;mount();await { compose.onAllNodesWithText("Событие 43").fetchSemanticsNodes().isNotEmpty() }
        return compose.onNodeWithText("Событие 43").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    @Test fun notificationRequestUsesRefIdAndDurableRecord() {
        rowType="ride";val row=retainedRow("request")
        compose.runOnIdle { row();assertEquals(Screen.RequestResponses,vm.pendingScreenNavigation.value?.destination);assertEquals(43,vm.pendingScreenNavigation.value?.targetId) }
        reached(Screen.RequestResponses);await { hasGet("/requests/43/responses") };assertEquals(43,vm.responsesRequestId.value);assertTrue(vm.navHistory.contains(Screen.Notifications))
    }
    @Test fun notificationIncidentKeepsDetailContractThroughRecord() {
        rowType="safety";val row=retainedRow("incident")
        compose.runOnIdle { row();assertEquals(Screen.IncidentDetail,vm.pendingScreenNavigation.value?.destination);assertEquals(43,vm.pendingScreenNavigation.value?.targetId) }
        reached(Screen.IncidentDetail);await { hasGet("/incidents/43") };compose.onNodeWithText("Разбор спора").assertExists()
    }
    @Test fun legacyRequestWatchRowUsesFeedInsteadOfPassengerResponses() {
        rowType="request_watch";val row=retainedRow("request")
        compose.runOnIdle { row();assertEquals(Screen.RequestsFeed,vm.pendingScreenNavigation.value?.destination) };reached(Screen.RequestsFeed)
        assertFalse(records.any { it.second=="/requests/43/responses" })
    }
    @Test fun retainedRequestRowCannotOpenForNextAccount() {
        val row=retainedRow("request");compose.runOnIdle { ApiClient.saveToken(tokenB);row() };pump(1500)
        assertEquals(Screen.Notifications,vm.screen.value);assertNull(vm.pendingScreenNavigation.value);assertFalse(records.any { it.second=="/requests/43/responses" })
    }
    @Test fun notificationAdAlsoSupersedesHeldBooking() {
        val row=retainedRow("ad");holdRole=true;deliver("booking");await { started.count==0L }
        compose.runOnIdle { row() };reached(Screen.AdsCabinet);release.countDown();assertTrue(dispatched.await(8,TimeUnit.SECONDS));pump(1500)
        assertEquals(Screen.AdsCabinet,vm.screen.value);assertNull(vm.pendingBookingNavigation.value)
    }
}
