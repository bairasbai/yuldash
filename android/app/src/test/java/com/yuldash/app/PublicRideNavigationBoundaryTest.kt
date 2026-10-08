package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
class PublicRideNavigationBoundaryTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val tokenA="header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB="header.eyJzdWIiOiIxMiJ9.signature"
    private val actor by lazy {Robolectric.buildActivity(MainActivity::class.java).create()}
    private lateinit var vm:YuldashViewModel
    private lateinit var server:MockWebServer
    private val mounted=mutableStateOf(true)
    private val records=CopyOnWriteArrayList<Triple<String,String,String?>>()
    private val started=CountDownLatch(1);private val release=CountDownLatch(1);private val dispatched=CountDownLatch(1)
    @Volatile private var heldId:Int?=null
    @Volatile private var code=200
    @Volatile private var audienceVariants=false
    private var feedId=7
    @Before fun setup() {
        ApiClient.resetForTest();ApiClient.init(context);ApiClient.saveToken(tokenA);clear()
        context.getSharedPreferences("yuldash_prefs",0).edit().clear().putBoolean("onboarding_completed",false)
            .putString("mode_last","pooling").putBoolean("mode_hint_shown",true).commit()
        server=MockWebServer().apply {
            dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    val path=request.requestUrl!!.encodedPath;records+=Triple(request.method!!,path,request.getHeader("Authorization"))
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
                        "/rides/near" -> """{"items":[${ride(feedId,"Новый пункт А")}],"count":1}"""
                        "/bookings/mine" -> """{"items":[{"id":77,"ride_id":7,"status":"pending","seats":1,"price":750,"from_city":"Бронь А","to_city":"Бронь Б","driver_name":"Тестовый водитель"}]}"""
                        "/bookings/77/details" -> """{"booking_id":77,"ride_id":7,"role":"passenger","status":"pending","from_city":"Бронь А","to_city":"Бронь Б","contact_unlocked":false}"""
                        "/requests/43/responses" -> """{"items":[]}"""
                        else -> """{"items":[],"role":"passenger","unread":0}"""
                    },if(path=="/instant/orders/active") 503 else 200)
                }
            };start()
        }
        ApiClient.testBaseUrl=server.url("/").toString().trimEnd('/');ApiClient.testTimeoutMs=60000
        vm=ViewModelProvider(actor.get())[YuldashViewModel::class.java];vm.screen.value=Screen.Home;vm.startHomeTab.value=HomeTab.Map;vm.persistNav()
    }
    private fun ride(id:Int,from:String)="""{"id":$id,"from_city":"$from","to_city":"Пункт Б","depart_at":"2030-01-02T10:00:00+03:00","seats_total":3,"seats_left":3,"price":750,"category":"regular","driver_id":9,"driver_name":"Тестовый водитель","driver_verified":true}"""
    private fun json(body:String,status:Int=200)=MockResponse().setResponseCode(status).setHeader("Content-Type","application/json").setBody(body)
    private fun clear() {
        DeepLink.pendingRideId.value=null;DeepLink.pendingBookingChatId.value=null;DeepLink.pendingCompletedBookingId.value=null
        NavSignals.openInstantChat.value=0;NavSignals.openInstantOrder.value=false;NavSignals.openDriverCabinet.value=false
        DeepLink.pendingParcels.value=false;DeepLink.pendingSupport.value=false;NavSignals.openAdsCabinet.value=false;NavSignals.openPartnerCabinet.value=false
        DeepLink.pendingFairness.value=false;DeepLink.pendingRequestResponsesId.value=null;DeepLink.pendingRequestsFeed.value=false;DeepLink.pendingApplicationScreen.value=null
    }
    private fun pump(ms:Long=500) {repeat((ms/100).toInt()) {compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();Snapshot.sendApplyNotifications();Thread.sleep(10)};compose.waitForIdle()}
    private fun await(check:()->Boolean)=compose.waitUntil(15000) {compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();Snapshot.sendApplyNotifications();check()}
    private fun mount() {compose.mainClock.autoAdvance=false;compose.setContent {if(mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get(),LocalPoolingNativeMapEnabled provides false) {YuldashTheme {YuldashApp()}}};pump()}
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
    private fun homeWins(status:Int,sameId:Boolean=false) {
        code=status;if(sameId) feedId=42
        val open=homeRow();held();compose.runOnIdle {open();assertEquals(feedId.toString(),vm.selectedRide.value?.id)};pump();finish()
        selected(feedId);assertEquals(1,count(42));assertTrue(vm.navHistory.contains(Screen.Home))
    }
    @Test fun heldSuccessCannotReplaceActualHomeRide()=homeWins(200)
    @Test fun heldFailureCannotOverlayActualHomeRide()=homeWins(503)
    @Test fun heldSuccessCannotReplaceNewHomeRideWithTheSameId()=homeWins(200,true)
    @Test fun guestHomeSelectionSupersedesHeldLink() {ApiClient.logout();homeWins(200);assertFalse(records.any {it.second=="/rides/42" && it.third!=null})}
    @Test fun homeExistingBookingSupersedesHeldPublicAndSavesBeforeNextEffect() {
        mount();compose.onNodeWithText("Поездки").performClick();pump();val open=retained("Подробнее");held();val bundle=Bundle()
        compose.runOnIdle {open();actor.saveInstanceState(bundle)}
        val next=Robolectric.buildActivity(MainActivity::class.java).create(bundle)
        try {val restored=ViewModelProvider(next.get())[YuldashViewModel::class.java];assertEquals(Screen.Booking,restored.screen.value);assertEquals(77,restored.activeBookingId.value);assertEquals(HomeTab.Rides,restored.startHomeTab.value);assertTrue(restored.navHistory.contains(Screen.Home))} finally {next.destroy()}
        finish();assertEquals(Screen.Booking,vm.screen.value);assertEquals(77,vm.activeBookingId.value);assertEquals("77",vm.selectedRide.value?.id);assertNull(DeepLink.pendingRideId.value);assertEquals(1,count(42))
    }
    private fun privateWins(type:String,destination:Screen,status:Int) {
        code=status;mount();held();privateDestination(type);await {vm.screen.value==destination && vm.pendingScreenNavigation.value==null};finish()
        assertEquals(destination,vm.screen.value);assertNull(DeepLink.pendingRideId.value);assertEquals(1,count(42));compose.onAllNodesWithTag("rideLinkFailure").assertCountEquals(0)
    }
    @Test fun privateSupportSupersedesHeldPublicSuccess()=privateWins("support",Screen.SupportTickets,200)
    @Test fun privateRequestSupersedesHeldPublicFailure()=privateWins("request",Screen.RequestResponses,503)
    @Test fun oldPublicRetryCannotResurrectInSameFrameAsHomeSelection() {
        code=503;val open=homeRow();failure();val retry=retainedTag("rideLinkRetry");code=200
        compose.runOnIdle {open();retry()};pump(1500);selected(7);assertEquals(1,count(42))
    }
    @Test fun oldPublicFailureDisappearsAfterHomeSelection() {
        code=503;val open=homeRow();failure();compose.runOnIdle {open()};pump(1500);selected(7);assertEquals(1,count(42))
    }
    @Test fun oldRetryCannotReturnAfterBackToTheSameHomeScreen() {
        code=503;val open=homeRow();failure();val retry=retainedTag("rideLinkRetry")
        compose.runOnIdle {open()};pump();compose.onNodeWithContentDescription("Назад").performClick();pump()
        code=200;compose.runOnIdle {retry()};pump(1500);assertEquals(Screen.Home,vm.screen.value);assertEquals(1,count(42));assertNull(DeepLink.pendingRideId.value)
    }
    @Test fun oldRetryCannotSpendANewerFailureOfTheSameRide() {
        code=503;mount();failure();val old=retainedTag("rideLinkRetry");compose.onNodeWithTag("rideLinkRetry").performClick()
        await {count(42)==2 && compose.onAllNodesWithTag("rideLinkFailure").fetchSemanticsNodes().isNotEmpty()};pump()
        compose.runOnIdle {old()};pump(1500);assertEquals(2,count(42));compose.onNodeWithTag("rideLinkFailure").assertExists()
    }
    @Test fun oldCloseCannotDismissNewerDifferentRideFailure() {
        code=503;mount();failure();val old=retainedTag("rideLinkClose");failure(43)
        compose.runOnIdle {old()};pump();compose.onNodeWithTag("rideLinkFailure").assertExists();assertEquals(1,count(43))
    }
    @Test fun oldCloseCannotDismissNewerSameRideFailure() {
        code=503;mount();failure();val old=retainedTag("rideLinkClose");compose.onNodeWithTag("rideLinkRetry").performClick()
        await {count(42)==2 && compose.onAllNodesWithTag("rideLinkFailure").fetchSemanticsNodes().isNotEmpty()};pump()
        compose.runOnIdle {old()};pump();compose.onNodeWithTag("rideLinkFailure").assertExists();assertEquals(2,count(42))
    }
    @Test fun currentRetryRecoversSamePublicRide() {
        code=503;mount();failure();code=200;compose.onNodeWithTag("rideLinkRetry").performClick();await {vm.selectedRide.value?.id=="42"};pump()
        selected(42,"Старый пункт А");assertEquals(2,count(42))
    }
    @Test fun currentCloseKeepsHomeWithoutAnotherRequest() {
        code=503;mount();failure();compose.onNodeWithTag("rideLinkClose").performClick();pump();assertEquals(Screen.Home,vm.screen.value);assertEquals(1,count(42));compose.onAllNodesWithTag("rideLinkFailure").assertCountEquals(0)
    }
    @Test fun guestPublicUriLoadsWithoutBearerOrBookingWrite() {
        ApiClient.logout();mount();link();await {vm.selectedRide.value?.id=="42"};pump();selected(42,"Старый пункт А")
        assertFalse(records.any {it.second=="/rides/42" && it.third!=null});assertEquals(1,count(42))
    }
    @Test fun newPublicRideSupersedesOlderHeldSuccess() {
        mount();held();link(43);await {vm.selectedRide.value?.id=="43"};finish();selected(43,"Старый пункт А");assertEquals(1,count(43))
    }
    @Test fun newAccountAndLocalChoiceRejectOldPublicFailure() {
        code=503;mount();held();compose.runOnIdle {ApiClient.saveToken(tokenB);vm.navigateLocally {vm.screen.value=Screen.Notifications}};finish()
        assertEquals(Screen.Notifications,vm.screen.value);assertNull(DeepLink.pendingRideId.value);compose.onAllNodesWithTag("rideLinkFailure").assertCountEquals(0)
    }
    @Test fun retainedRetryCannotCrossAccountInTheSameFrame() {
        code=503;mount();failure();val retry=retainedTag("rideLinkRetry");code=200
        compose.runOnIdle {ApiClient.saveToken(tokenB);retry()};pump(1500);assertEquals(1,count(42));assertNull(DeepLink.pendingRideId.value)
    }
    @Test fun sameOwnerTokenReplacementCanRetryWithCurrentCallback() {
        code=503;mount();failure();compose.runOnIdle {ApiClient.saveToken(tokenA+"new")};pump();code=200
        compose.onNodeWithTag("rideLinkRetry").performClick();await {vm.selectedRide.value?.id=="42"};pump();selected(42,"Старый пункт А")
    }
    @Test fun disposedHostDoesNotPublishHeldPublicResponse() {
        mount();held();compose.runOnIdle {mounted.value=false};pump();finish();assertEquals(Screen.Home,vm.screen.value);assertNull(vm.selectedRide.value)
    }
    @Test fun newAccountRequeriesOutstandingPublicRideWithItsOwnBearer() {
        audienceVariants=true;mount();held();compose.runOnIdle {ApiClient.saveToken(tokenB)}
        await {records.any {it.second=="/rides/42" && it.third=="Bearer $tokenB"}};finish()
        selected(42,"Вариант аккаунта Б");assertEquals(2,count(42))
    }
    @Test fun logoutRequeriesOutstandingPublicRideWithoutOldBearer() {
        audienceVariants=true;mount();held();compose.runOnIdle {ApiClient.logout()}
        await {records.any {it.second=="/rides/42" && it.third==null}};finish()
        selected(42,"Гостевой вариант");assertEquals(2,count(42))
    }
    @Test fun invalidPrivateBookingDoesNotDiscardPublicDestination() {
        DeepLink.pendingRideId.value=42;vm.requestBookingDestination(0,11,false)
        assertEquals(42,DeepLink.pendingRideId.value);assertNull(vm.pendingBookingNavigation.value);assertEquals(0L,vm.privateNavigationRevision)
    }
    @Test fun invalidPrivateScreenDoesNotDiscardPublicDestination() {
        DeepLink.pendingRideId.value=42;vm.requestScreenDestination(Screen.InstantChat,11,0)
        assertEquals(42,DeepLink.pendingRideId.value);assertNull(vm.pendingScreenNavigation.value);assertEquals(0L,vm.privateNavigationRevision)
    }
    @Test fun validPrivateBookingSupersedesPublicBeforeComposition() {
        DeepLink.pendingRideId.value=42;vm.requestBookingDestination(77,11,false)
        assertNull(DeepLink.pendingRideId.value);assertEquals(77,vm.pendingBookingNavigation.value?.bookingId);assertEquals(1L,vm.privateNavigationRevision)
    }
    @Test fun localChoiceClearsPublicAndPersistsBeforeComposition() {
        vm.recordNavigationChange() // Direct VM fixture: establish the Home entry normally tracked by the mounted App.
        DeepLink.pendingRideId.value=42;vm.navigateLocally {vm.screen.value=Screen.Notifications};assertNull(DeepLink.pendingRideId.value)
        val bundle=Bundle();actor.saveInstanceState(bundle);val next=Robolectric.buildActivity(MainActivity::class.java).create(bundle)
        try {val restored=ViewModelProvider(next.get())[YuldashViewModel::class.java];assertEquals(Screen.Notifications,restored.screen.value);assertEquals(1L,restored.privateNavigationRevision);assertTrue(restored.navHistory.contains(Screen.Home))} finally {next.destroy()}
        assertEquals(0,count(42))
    }
    @Test fun disposedPublicRetryCannotQueueWorkForANewHost() {
        code=503;mount();failure();val retry=retainedTag("rideLinkRetry")
        compose.runOnIdle {mounted.value=false};pump()
        compose.runOnIdle {retry();assertNull("Disposed callback must not set global public intent",DeepLink.pendingRideId.value)}
        compose.runOnIdle {mounted.value=true};pump(1500);assertEquals(1,count(42));assertEquals(Screen.Home,vm.screen.value)
    }
}
