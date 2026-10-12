package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.OnBackPressedDispatcher
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

/** Actual Main/default VM/App/Home and retained semantics callbacks; local HTTP/synthetic sessions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,qualifiers="w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeTabBoundaryTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val tokenA="header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB="header.eyJzdWIiOiIxMiJ9.signature"
    private val actor by lazy {Robolectric.buildActivity(MainActivity::class.java).create()}
    private lateinit var vm:YuldashViewModel
    private lateinit var server:MockWebServer
    private lateinit var hostBackDispatcher:OnBackPressedDispatcher
    private val mounted=mutableStateOf(true)
    private val records=CopyOnWriteArrayList<Triple<String,String,String?>>()
    private val started=CountDownLatch(1);private val release=CountDownLatch(1);private val dispatched=CountDownLatch(1)
    @Volatile private var heldId:Int?=null
    @Volatile private var code=200
    @Volatile private var audienceVariants=false
    private var feedId=7
    @Volatile private var heldBooking=false
    @Before fun setup() {
        ApiClient.resetForTest();ApiClient.init(context);ApiClient.saveToken(tokenA);clear()
        context.getSharedPreferences("yuldash_prefs",0).edit().clear().putBoolean("onboarding_completed",false)
            .putString("mode_last","pooling").putBoolean("mode_hint_shown",true).commit()
        server=MockWebServer().apply {
            dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    val path=request.requestUrl!!.encodedPath;records+=Triple(request.method!!,path,request.getHeader("Authorization"))
                    if(heldBooking && path=="/bookings/44/role") {
                        started.countDown();assertTrue(release.await(35,TimeUnit.SECONDS));dispatched.countDown()
                    }
                    val rid=path.removePrefix("/rides/").toIntOrNull()
                    if(rid!=null) {
                        val responseCode=code
                        if(rid==heldId) {started.countDown();assertTrue(release.await(35,TimeUnit.SECONDS));dispatched.countDown()}
                        val from=if(audienceVariants) when(request.getHeader("Authorization")) {
                            "Bearer $tokenB" -> "Вариант аккаунта Б"
                            null -> "Гостевой вариант"
                            else -> "Старый пункт А"
                        } else "Старый пункт А"
                        return json(if(responseCode==200) ride(rid,from) else """{"detail":"Controlled rejection"}""",responseCode)
                    }
                    return json(when(path) {
                        "/instant/orders/91" -> """{"id":91,"status":"accepted","role":"passenger","driver_name":"Тестовый водитель","from_text":"Пункт А","to_text":"Пункт Б"}"""
                        "/rides/near" -> """{"items":[${ride(feedId,"Новый пункт А")}],"count":1}"""
                        "/bookings/mine" -> """{"items":[{"id":77,"ride_id":7,"status":"pending","seats":1,"price":750,"from_city":"Бронь А","to_city":"Бронь Б","driver_name":"Тестовый водитель"}]}"""
                        "/bookings/44/role" -> """{"role":"passenger","status":"pending"}"""
                        "/bookings/44/details" -> """{"booking_id":44,"ride_id":42,"role":"passenger","status":"pending","from_city":"Старая бронь","to_city":"Пункт Б"}"""
                        "/bookings/77/details" -> """{"booking_id":77,"ride_id":7,"role":"passenger","status":"pending","from_city":"Бронь А","to_city":"Бронь Б","contact_unlocked":false}"""
                        "/requests/43/responses" -> """{"items":[]}"""
                        else -> """{"items":[],"role":"passenger","unread":0}"""
                    },if(path=="/instant/orders/active") 503 else 200)
                }
            };start()
        }
        ApiClient.testBaseUrl=server.url("/").toString().trimEnd('/');ApiClient.testTimeoutMs=60000
        vm=ViewModelProvider(actor.get())[YuldashViewModel::class.java];vm.screen.value=Screen.Home;vm.startHomeTab.value=HomeTab.Profile;vm.persistNav()
    }
    private fun ride(id:Int,from:String)="""{"id":$id,"from_city":"$from","to_city":"Пункт Б","depart_at":"2030-01-02T10:00:00+03:00","seats_total":3,"seats_left":3,"price":750,"category":"regular","driver_id":9,"driver_name":"Тестовый водитель","driver_verified":true}"""
    private fun json(body:String,status:Int=200)=MockResponse().setResponseCode(status).setHeader("Content-Type","application/json").setBody(body)
    private fun clear() {
        DeepLink.pendingRideId.value=null;DeepLink.pendingBookingChatId.value=null;DeepLink.pendingCompletedBookingId.value=null
        NavSignals.activeTaxiTrip.value=0;NavSignals.taxiTripOnScreen.value=false;NavSignals.taxiOrderOnScreen.value=false;NavSignals.openInstantChat.value=0;NavSignals.openInstantOrder.value=false;NavSignals.openDriverCabinet.value=false
        DeepLink.pendingParcels.value=false;DeepLink.pendingSupport.value=false;NavSignals.openAdsCabinet.value=false;NavSignals.openPartnerCabinet.value=false
        DeepLink.pendingFairness.value=false;DeepLink.pendingRequestResponsesId.value=null;DeepLink.pendingRequestsFeed.value=false;DeepLink.pendingApplicationScreen.value=null
    }
    private fun pump(ms:Long=500) {repeat((ms/100).toInt()) {compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();Snapshot.sendApplyNotifications();Thread.sleep(10)};compose.waitForIdle()}
    private fun await(check:()->Boolean)=compose.waitUntil(15000) {compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();Snapshot.sendApplyNotifications();check()}
    private fun mount() {compose.mainClock.autoAdvance=false;compose.setContent {if(mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get(),LocalPoolingNativeMapEnabled provides false) {hostBackDispatcher=LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher;YuldashTheme {YuldashApp()}}};pump()}
    private fun dispatch(i:Intent) {MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply {isAccessible=true}.invoke(actor.get(),i)}
    private fun link(id:Int=42)=compose.runOnIdle {dispatch(Intent(Intent.ACTION_VIEW,Uri.parse("https://yulbash.ru/r/$id"),context,MainActivity::class.java))}
    private fun privateDestination(type:String)=compose.runOnIdle {dispatch(Intent(context,MainActivity::class.java).putExtra("type",type).putExtra("id","43").putExtra("recipient_user_id","11").putExtra(MainActivity.EXTRA_NAVIGATION_DELIVERY_ID,"public-boundary-$type"))}
    private fun retained(text:String):()->Boolean {await {compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()};return compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!}
    private fun retainedTag(tag:String):()->Boolean {await {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()};return compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!}
    private fun homeRow():()->Boolean {
        mount();await {records.any {it.second=="/rides/near"}}
        val scroll=compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        repeat(12) {if(compose.onAllNodesWithText("Поехать").fetchSemanticsNodes().isEmpty()) {scroll.performTouchInput {swipeUp()};pump()}}
        return retained("Поехать")
    }
    private fun held() {heldId=42;link();await {started.count==0L}}
    private fun finish() {release.countDown();assertTrue(dispatched.await(8,TimeUnit.SECONDS));pump(1500)}
    private fun count(id:Int)=records.count {it.first=="GET" && it.second=="/rides/$id"}
    private fun failure(id:Int=42) {link(id);await {compose.onAllNodesWithTag("rideLinkFailure").fetchSemanticsNodes().isNotEmpty()};pump()}
    private fun selected(id:Int,from:String="Новый пункт А") {
        assertEquals(Screen.Booking,vm.screen.value);assertEquals(id.toString(),vm.selectedRide.value?.id);assertEquals(from,vm.selectedRide.value?.from)
        assertNull(vm.activeBookingId.value);assertNull(DeepLink.pendingRideId.value);compose.onAllNodesWithTag("rideLinkFailure").assertCountEquals(0)
        assertFalse(records.any {it.first!="GET" && it.second.startsWith("/bookings")})
    }
    @After fun cleanup() {release.countDown();compose.runOnIdle {mounted.value=false};pump(300);actor.destroy();vm.clearUserData();clear();ApiClient.resetForTest();ApiClient.testTimeoutMs=null;server.shutdown()}
    private fun profileAction(text:String):()->Boolean {
        repeat(14) {if(compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) {compose.onAllNodes(hasScrollToIndexAction())[0].performTouchInput {swipeUp()};pump()}}
        check(compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) {"Missing bounded profile action: $text"}
        return retained(text)
    }
    private fun back() {pump(1500);compose.runOnIdle {assertTrue(hostBackDispatcher.hasEnabledCallbacks());hostBackDispatcher.onBackPressed()};pump()}
    private fun assertNoRideFailure() {compose.onAllNodesWithTag("rideLinkFailure").assertCountEquals(0)}
    private fun publicLosesToProfileAction(text:String,destination:Screen,status:Int) {
        code=status;mount();val open=profileAction(text);held();compose.runOnIdle {open()};pump();finish()
        assertEquals(destination,vm.screen.value);assertNull(DeepLink.pendingRideId.value);assertEquals(1,count(42));assertNoRideFailure()
    }

    private fun selectTab(text:String) { compose.onNode(hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick();pump() }
    private fun assertHome(tab:HomeTab) {assertEquals(Screen.Home,vm.screen.value);assertEquals(tab,vm.startHomeTab.value);assertNull(DeepLink.pendingRideId.value);assertNoRideFailure()}
    @Test fun tabChoiceSupersedesHeldPublicSuccess() {mount();held();selectTab("Поездки");finish();assertHome(HomeTab.Rides)}
    @Test fun tabChoiceSupersedesHeldPublicFailure() {code=503;mount();held();selectTab("Чат");finish();assertHome(HomeTab.Chat)}
    @Test fun sameTabChoiceAlsoCancelsHeldPublic() {mount();held();selectTab("Профиль");finish();assertHome(HomeTab.Profile)}
    @Test fun tabChoiceSupersedesHeldPrivateNotification() {
        mount();heldBooking=true
        compose.runOnIdle {dispatch(Intent(context,MainActivity::class.java).putExtra("type","booking").putExtra("id","44").putExtra("recipient_user_id","11").putExtra(MainActivity.EXTRA_NAVIGATION_DELIVERY_ID,"tab-held-booking"))}
        await {started.count==0L};selectTab("Чат");finish();assertHome(HomeTab.Chat);assertNull(vm.pendingBookingNavigation.value);assertNull(vm.activeBookingId.value)
    }
    @Test fun tabChoiceSavesBeforeAnotherComposeEffect() {
        mount();val tab=retained("Поездки");val bundle=Bundle()
        compose.runOnIdle {DeepLink.pendingRideId.value=42;tab();actor.saveInstanceState(bundle);assertEquals(HomeTab.Rides,vm.startHomeTab.value);assertNull(DeepLink.pendingRideId.value)}
        val next=Robolectric.buildActivity(MainActivity::class.java).create(bundle)
        try {val restored=ViewModelProvider(next.get())[YuldashViewModel::class.java];assertEquals(Screen.Home,restored.screen.value);assertEquals(HomeTab.Rides,restored.startHomeTab.value);assertEquals(1L,restored.privateNavigationRevision)} finally {next.destroy()}
    }
    @Test fun earlierProfileCallbackCannotReplaceSameFrameTabChoice() {
        mount();val old=profileAction("Настройки");val tab=retained("Поездки")
        compose.runOnIdle {tab();old();assertEquals(Screen.Home,vm.screen.value);assertEquals(HomeTab.Rides,vm.startHomeTab.value)}
    }
    @Test fun outgoingAnimatedProfileCannotReplaceChosenTab() {
        mount();profileAction("Настройки");compose.onNodeWithText("Поездки").performClick()
        compose.mainClock.advanceTimeBy(16);Snapshot.sendApplyNotifications()
        // The node still exists in the outgoing AnimatedContent, so this exercises its refreshed callback.
        val outgoing=compose.onNodeWithText("Настройки").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {outgoing();assertEquals(Screen.Home,vm.screen.value);assertEquals(HomeTab.Rides,vm.startHomeTab.value)}
    }
    @Test fun retainedTabCannotChangeAnotherScreenInSameFrame() {
        mount();val old=retained("Поездки")
        compose.runOnIdle {vm.navigateLocally {vm.screen.value=Screen.Support};old();assertEquals(HomeTab.Profile,vm.startHomeTab.value);assertEquals(Screen.Support,vm.screen.value)}
    }
    @Test fun retainedTabCannotCrossNextSessionInSameFrame() {
        mount();val old=retained("Поездки")
        compose.runOnIdle {ApiClient.saveToken(tokenB);old()};pump();assertEquals(HomeTab.Profile,vm.startHomeTab.value)
    }
    @Test fun disposedHomeCannotUseOldTab() {
        mount();val old=retained("Поездки");compose.runOnIdle {mounted.value=false};pump()
        compose.runOnIdle {old();assertEquals(HomeTab.Profile,vm.startHomeTab.value)}
    }
    @Test fun currentChoiceAfterReplacementSessionWorks() {
        mount();compose.runOnIdle {ApiClient.saveToken(tokenA+"new")};pump();selectTab("Поездки");assertHome(HomeTab.Rides)
    }
    @Test fun initialSyncPreservesPendingPublicLink() {
        DeepLink.pendingRideId.value=42;mount();await {vm.selectedRide.value?.id=="42"};pump();assertEquals(Screen.Booking,vm.screen.value);assertEquals(1,count(42))
    }
    @Test fun currentTabThenProfileDestinationReturnsToProfile() {
        vm.startHomeTab.value=HomeTab.Rides;mount();selectTab("Профиль");val open=profileAction("Настройки");compose.runOnIdle {open()};pump();assertEquals(Screen.Settings,vm.screen.value);back();assertHome(HomeTab.Profile)
    }

    @Test fun backToMapSupersedesHeldPublic() {mount();held();back();finish();assertHome(HomeTab.Map)}
    @Test fun guestTabChoiceStillWorks() {ApiClient.logout();mount();await {records.any {it.first=="POST" && it.second=="/auth/logout"}};assertFalse(ApiClient.isLoggedIn());selectTab("Поездки");assertHome(HomeTab.Rides)}

    private fun barAction():()->Boolean {
        NavSignals.activeTaxiTrip.value=91;mount()
        await {compose.onAllNodesWithText("Открыть").fetchSemanticsNodes().isNotEmpty()}
        assertTrue(records.any {it.second=="/instant/orders/91"})
        return retained("Открыть")
    }
    @Test fun currentBarSupersedesHeldPublicAndOpensOrder() {
        val open=barAction();held();compose.runOnIdle {open()};pump();finish();assertEquals(Screen.InstantOrder,vm.screen.value);assertEquals(HomeTab.Map,vm.startHomeTab.value);assertNull(DeepLink.pendingRideId.value);assertNoRideFailure()
    }
    @Test fun oldBarCannotSignalUnderNextSession() {val open=barAction();compose.runOnIdle {ApiClient.saveToken(tokenB);open();assertFalse(NavSignals.openInstantOrder.value);assertEquals(HomeTab.Profile,vm.startHomeTab.value)}}
    @Test fun oldBarCannotSignalAfterAnotherHomeChoiceInSameFrame() {val open=barAction();val tab=retained("Поездки");compose.runOnIdle {tab();open();assertFalse(NavSignals.openInstantOrder.value);assertEquals(HomeTab.Rides,vm.startHomeTab.value)}}
    @Test fun oldBarCannotOpenReplacedTripInSameFrame() {val open=barAction();compose.runOnIdle {NavSignals.activeTaxiTrip.value=92;open();assertFalse(NavSignals.openInstantOrder.value);assertEquals(HomeTab.Profile,vm.startHomeTab.value)}}
    @Test fun disposedBarCannotSignalNewHome() {val open=barAction();compose.runOnIdle {mounted.value=false};pump();compose.runOnIdle {open();assertFalse(NavSignals.openInstantOrder.value);assertEquals(HomeTab.Profile,vm.startHomeTab.value)}}
}
