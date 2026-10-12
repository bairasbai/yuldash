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

/** Real App/Profile confirmation and HTTP; synthetic users/local server, no real account deletion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,qualifiers="w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountDeletionBoundaryTest {
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
    @Volatile private var holdDelete=false
    @Volatile private var deleteCode=200
    private val deleteStarted=CountDownLatch(1);private val deleteRelease=CountDownLatch(1)
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
                    if(path=="/me/delete") {
                        deleteStarted.countDown()
                        if(holdDelete) assertTrue(deleteRelease.await(35,TimeUnit.SECONDS))
                        return json("{}",deleteCode)
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
        NavSignals.openInstantChat.value=0;NavSignals.openInstantOrder.value=false;NavSignals.openDriverCabinet.value=false
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
    @After fun cleanup() {deleteRelease.countDown();release.countDown();compose.runOnIdle {mounted.value=false};pump(300);actor.destroy();vm.clearUserData();clear();ApiClient.resetForTest();ApiClient.testTimeoutMs=null;server.shutdown()}
    private fun profileAction(text:String):()->Boolean {
        // Bounded real scrolling avoids Compose's unbounded diagnostic tree dump on a missing target.
        repeat(18) {
            if(compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) {
                assertTrue(compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty())
                compose.onAllNodes(hasScrollToIndexAction())[0].performTouchInput {swipeUp()};pump()
            }
        }
        assertTrue("Profile action must be visible after bounded scroll",compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty())
        return retained(text)
    }
    private fun confirmation():()->Boolean {
        mount();val open=profileAction("Удалить аккаунт");compose.runOnIdle {open()};pump()
        return retained("Удалить навсегда")
    }
    private fun deleteCount()=records.count {it.first=="POST" && it.second=="/me/delete"}
    private fun seedB() {vm.activeBookingId.value=88;vm.responsesRequestId.value=89;vm.isAdmin.value=true}
    private fun assertB() {
        assertEquals(tokenB,ApiClient.currentToken());assertEquals(Screen.Home,vm.screen.value)
        assertEquals(88,vm.activeBookingId.value);assertEquals(89,vm.responsesRequestId.value);assertTrue(vm.isAdmin.value)
    }
    @Test fun retainedConfirmationCannotDeleteNextAccountInTheSameFrame() {
        val confirm=confirmation()
        compose.runOnIdle {ApiClient.saveToken(tokenB);seedB();confirm()};pump(1500)
        assertEquals(0,deleteCount());assertB()
    }
    @Test fun retainedConfirmationCannotDeleteReplacementSessionOfSameOwner() {
        val confirm=confirmation()
        compose.runOnIdle {ApiClient.saveToken(tokenA);confirm()};pump(1500)
        assertEquals(0,deleteCount());assertEquals(tokenA,ApiClient.currentToken());assertEquals(Screen.Home,vm.screen.value)
    }
    @Test fun retainedConfirmationCannotSubmitAfterLogout() {
        val confirm=confirmation();compose.runOnIdle {ApiClient.logout();confirm()};pump(1500)
        await {records.any {it.first=="POST" && it.second=="/auth/logout"}}
        assertEquals(0,deleteCount());assertNull(ApiClient.currentToken())
    }
    @Test fun currentDeletionClearsActualHomeAndSavedPrivateIds() {
        holdDelete=true;val confirm=confirmation();compose.runOnIdle {confirm()};await {deleteStarted.count==0L}
        compose.runOnIdle {vm.activeBookingId.value=77;vm.responsesRequestId.value=43;vm.supportTicketId.value=44;vm.isAdmin.value=true;vm.navHistory.add(Screen.Settings);vm.persistNav()}
        deleteRelease.countDown();await {vm.screen.value==Screen.Login};pump()
        assertNull(ApiClient.currentToken());assertNull(vm.activeBookingId.value);assertEquals(0,vm.responsesRequestId.value)
        assertEquals(0,vm.supportTicketId.value);assertFalse(vm.isAdmin.value);assertEquals(HomeTab.Map,vm.startHomeTab.value);assertTrue(vm.navHistory.isEmpty())
        assertEquals(1,deleteCount());assertEquals("Bearer $tokenA",records.single {it.second=="/me/delete"}.third)
        val bundle=Bundle();compose.runOnIdle {actor.saveInstanceState(bundle)}
        val next=Robolectric.buildActivity(MainActivity::class.java).create(bundle)
        try {val restored=ViewModelProvider(next.get())[YuldashViewModel::class.java];assertEquals(Screen.Login,restored.screen.value);assertEquals(HomeTab.Map,restored.startHomeTab.value);assertNull(restored.activeBookingId.value);assertTrue(restored.navHistory.isEmpty())} finally {next.destroy()}
    }
    private fun heldDeleteCannotClearB(status:Int) {
        holdDelete=true;deleteCode=status;val confirm=confirmation();compose.runOnIdle {confirm()};await {deleteStarted.count==0L}
        compose.runOnIdle {ApiClient.saveToken(tokenB);seedB()};pump();deleteRelease.countDown();pump(1500)
        assertB();assertEquals(1,deleteCount());assertEquals("Bearer $tokenA",records.single {it.second=="/me/delete"}.third)
    }
    @Test fun heldSuccessForACannotClearB()=heldDeleteCannotClearB(200)
    @Test fun heldFailureForACannotClearB()=heldDeleteCannotClearB(503)
    @Test fun currentFailureKeepsSessionAndAllowsRetry() {
        deleteCode=503;val confirm=confirmation();compose.runOnIdle {confirm()};await {deleteCount()==1};pump(1500)
        assertEquals(tokenA,ApiClient.currentToken());assertEquals(Screen.Home,vm.screen.value)
        deleteCode=200;compose.onNodeWithText("Удалить навсегда").performClick();await {vm.screen.value==Screen.Login}
        assertEquals(2,deleteCount());assertNull(ApiClient.currentToken())
    }
    @Test fun disposedProfileCannotSubmitRetainedConfirmation() {
        val confirm=confirmation();compose.runOnIdle {mounted.value=false};pump()
        compose.runOnIdle {confirm()};pump(1500);assertEquals(0,deleteCount());assertEquals(tokenA,ApiClient.currentToken())
    }
    @Test fun freshConfirmationAfterSameOwnerTokenReplacementStillWorks() {
        mount();compose.runOnIdle {ApiClient.saveToken(tokenA)};pump()
        val open=profileAction("Удалить аккаунт");compose.runOnIdle {open()};pump()
        compose.onNodeWithText("Удалить навсегда").performClick();await {vm.screen.value==Screen.Login}
        assertEquals(1,deleteCount());assertNull(ApiClient.currentToken())
    }
    @Test fun publicLinkCannotReturnAfterSuccessfulDeletion() {
        holdDelete=true;val confirm=confirmation();heldId=42;link();await {started.count==0L}
        compose.runOnIdle {confirm()};await {deleteStarted.count==0L};deleteRelease.countDown()
        await {vm.screen.value==Screen.Login};release.countDown();pump(1500)
        assertEquals(Screen.Login,vm.screen.value);assertNull(DeepLink.pendingRideId.value);assertEquals(1,count(42));assertNull(ApiClient.currentToken())
    }
}
