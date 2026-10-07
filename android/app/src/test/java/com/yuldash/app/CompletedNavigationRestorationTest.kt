package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real YuldashApp routes/holder; fresh VM from observed handle snapshot is not an OS process kill. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,qualifiers="w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompletedNavigationRestorationTest {
    @get:Rule val compose=createComposeRule()
    private val restoration=StateRestorationTester(compose)
    private class Owner:ViewModelStoreOwner { override val viewModelStore=ViewModelStore() }
    private val owner=mutableStateOf(Owner()); private val mounted=mutableStateOf(true)
    private var contentMounted=false
    private val allOwners=mutableListOf(owner.value)
    private lateinit var handle:SavedStateHandle;private lateinit var vm:YuldashViewModel
    private lateinit var server:MockWebServer
    private val records=CopyOnWriteArrayList<Triple<String,String?,String>>()
    private val mineCalls=AtomicInteger();private val started=CountDownLatch(1);private val release=CountDownLatch(1);private val dispatched=CountDownLatch(1)
    private val failures=CopyOnWriteArrayList<String>()
    private var heldMine=false;private var heldStatus=200
    @Volatile private var serverBooking=42
    @Volatile private var thanked=false
    private val tokenA="header.eyJzdWIiOiIxMSJ9.signature";private val tokenB="header.eyJzdWIiOiIzMyJ9.signature"
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    @Before fun prepare() {
        context.getSharedPreferences("yuldash_prefs",Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_completed",false).commit()
        ApiClient.resetForTest();ApiClient.init(context)
        server=MockWebServer().apply {
            dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    val path=request.requestUrl!!.encodedPath;val key="${request.method} $path"
                    records+=Triple(key,request.getHeader("Authorization"),request.body.readUtf8())
                    if(path=="/bookings/mine") {
                        val id=serverBooking;val first=mineCalls.incrementAndGet()==1
                        if(heldMine && first) {
                            started.countDown();if(!release.await(40,TimeUnit.SECONDS)) failures+="mine timeout"
                            dispatched.countDown()
                        }
                        return json("""{"items":[{"id":$id,"ride_id":9,"status":"done","seats":1,"from_city":"Уфа","to_city":"Бирск","price":400,"driver_name":"Водитель"}]}""",if(first && heldMine) heldStatus else 200)
                    }
                    val id=path.split('/').getOrNull(2)?.toIntOrNull() ?: 42
                    val body=when {
                        path=="/bookings/$id/role" -> """{"role":"passenger","status":"done","driver_phase":""}"""
                        path=="/bookings/$id/messages" -> """{"items":[]}"""
                        path=="/bookings/$id/boarding-code" -> """{"code":""}"""
                        path=="/bookings/$id/tip" -> """{"already_thanked":$thanked,"driver_name":"Водитель"}"""
                        path=="/trips/$id/receipt" -> """{"booking_id":$id,"ride_id":9,"role":"passenger","from_city":"Уфа","to_city":"Бирск","amount":400,"pay_method":"cash","paid":true,"counterparty_name":"Водитель","my_stars":0}"""
                        key=="POST /bookings/$id/thanks" -> { thanked=true;"{}" }
                        key=="POST /bookings/$id/rate" -> "{}"
                        request.method=="POST" && path.startsWith("/bookings/") -> { failures+="Unexpected write $key";return json("{}",405) }
                        else -> """{"items":[],"role":"passenger"}"""
                    }
                    return json(body)
                }
            };start()
        }
        ApiClient.testBaseUrl=server.url("/").toString().trimEnd('/');ApiClient.testTimeoutMs=60000;ApiClient.saveToken(tokenA)
        newVm(mapOf("yuldash_screen" to Screen.ActiveTrip.name,"yuldash_active_bid" to 42,"yuldash_tab" to HomeTab.Profile.name))
    }
    private fun json(body:String,status:Int=200)=MockResponse().setResponseCode(status).setHeader("Content-Type","application/json").setBody(body)
    private fun newVm(snapshot:Map<String,Any?>) {
        handle=SavedStateHandle(snapshot)
        vm=ViewModelProvider(owner.value,object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=YuldashViewModel(handle) as T
        })[YuldashViewModel::class.java]
    }
    @After fun cleanup() {
        release.countDown();if(contentMounted) {compose.runOnIdle {mounted.value=false};pump(300)}
        allOwners.forEach {it.viewModelStore.clear()};ApiClient.resetForTest();ApiClient.testTimeoutMs=null;server.shutdown()
        assertTrue(failures.toString(),failures.isEmpty())
    }
    private fun pump(ms:Long=700) {
        repeat((ms/100).toInt()) {compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));Thread.sleep(10)}
        compose.waitForIdle()
    }
    private fun await(check:()->Boolean)=compose.waitUntil(12000) {compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();check()}
    private fun mount() {
        contentMounted=true
        restoration.setContent {if(mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner.value) {YuldashTheme {YuldashApp()}}}
        await {compose.onAllNodesWithTag("rideshareCompletedHero").fetchSemanticsNodes().isNotEmpty()};pump()
    }
    private fun tag(value:String):SemanticsNodeInteraction {
        if(value!="rideshareRatingSubmit") compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasTestTag(value))
        return compose.onNodeWithTag(value)
    }
    private fun text(value:String):SemanticsNodeInteraction {
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasText(value));return compose.onNodeWithText(value)
    }
    private fun draft() {
        tag("rideshareStar4").performClick();text("Приехал вовремя").performClick();text("Добавить пару слов").performClick()
        tag("rideshareReviewText").performTextInput("Спасибо за дорогу")
    }
    private fun checkDraft() {
        assertEquals("Спасибо за дорогу",tag("rideshareReviewText").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        text("Приехал вовремя").assertIsSelected();tag("rideshareReviewText").assertIsEnabled()
    }
    private fun receipt() {text("Квитанция").performClick();await {vm.screen.value==Screen.TripReceipt};pump();compose.onNodeWithText("Квитанция").assertIsDisplayed()}
    private fun back() {compose.onNodeWithContentDescription("Назад").performClick();pump()}
    private fun awaitCompleted() {await {compose.onAllNodesWithTag("rideshareCompletedHero").fetchSemanticsNodes().isNotEmpty()};pump();assertEquals(Screen.ActiveTrip,vm.screen.value)}
    private fun posts(kind:String,id:Int=42)=records.count {it.first=="POST /bookings/$id/$kind"}
    private fun assertRating(id:Int=42) {
        tag("rideshareRatingSubmit").performClick();await {posts("rate",id)==1};pump()
        val row=records.single {it.first=="POST /bookings/$id/rate"};val p=JSONObject(row.third)
        assertEquals(4,p.getInt("stars"));assertEquals("ontime",p.getString("tags"));assertEquals("Спасибо за дорогу",p.getString("text"));assertEquals("Bearer $tokenA",row.second)
    }
    private fun freshVmRestore() {
        pump();val snapshot=handle.keys().associateWith {handle.get<Any?>(it)}
        assertEquals(Screen.TripReceipt.name,snapshot["yuldash_screen"]);assertEquals(42,snapshot["yuldash_active_bid"])
        compose.runOnIdle {val old=owner.value;owner.value=Owner();allOwners+=owner.value;newVm(snapshot);old.viewModelStore.clear()}
        restoration.emulateSavedInstanceStateRestore();pump();assertEquals(Screen.TripReceipt,vm.screen.value)
    }
    @Test fun receiptBackRetainsExactDraftAndManualPayload() {
        mount();draft();receipt();back();awaitCompleted();checkDraft();assertEquals(0,posts("rate"));assertRating()
    }
    @Test fun supportBackRetainsExactDraftWithoutAutomaticPost() {
        mount();draft();text("Нужна помощь?").performClick();await {vm.screen.value==Screen.Support};pump();back();awaitCompleted();checkDraft();assertEquals(0,posts("rate"))
    }
    @Test fun confirmedThanksSurvivesReceiptRoundTripWithoutAnotherPost() {
        mount();text("Сказать «Рәхмәт»").performClick();await {posts("thanks")==1};pump();receipt();back();awaitCompleted()
        text("Благодарность сохранена").assertIsDisplayed();text("Рәхмәт передано").performClick();assertEquals(1,posts("thanks"))
    }
    @Test fun savedCompositionWithRetainedVmRestoresReceiptBackAndDraft() {
        mount();draft();receipt();restoration.emulateSavedInstanceStateRestore();pump();back();awaitCompleted();checkDraft();assertEquals(0,posts("rate"))
    }
    @Test fun freshVmFromObservedSavedSnapshotRestoresReceiptBackDraftAndBooking() {
        mount();draft();receipt();freshVmRestore();back()
        assertEquals("Back from restored receipt must return to the completed booking",Screen.ActiveTrip,vm.screen.value)
        awaitCompleted();await {vm.selectedRide.value!=null};checkDraft();assertEquals("42",vm.selectedRide.value!!.id);assertNull(vm.activeTrip.value)
        assertEquals(0,posts("rate"));assertRating()
    }
    @Test fun anotherBookingCannotInheritSavedCompletedDraftThroughAppHolder() {
        mount();draft();receipt();compose.runOnIdle {vm.activeBookingId.value=43;vm.selectedRide.value=null;serverBooking=43};back();awaitCompleted()
        tag("rideshareRatingCard");compose.onNodeWithTag("rideshareReviewText").assertDoesNotExist();tag("rideshareRatingSubmit").assertIsNotEnabled()
        tag("rideshareStar2").performClick();tag("rideshareRatingSubmit").performClick();await {posts("rate",43)==1}
        assertEquals(0,posts("rate",42));assertEquals(2,JSONObject(records.single {it.first=="POST /bookings/43/rate"}.third).getInt("stars"))
    }
    private fun releaseMine() {release.countDown();await {dispatched.count==0L};pump()}
    @Test fun failedHeldRestoreCannotReplaceReceiptRouteWithHome() {
        heldMine=true;heldStatus=503;mount();await {started.count==0L};receipt();releaseMine()
        assertEquals(Screen.TripReceipt,vm.screen.value);compose.onNodeWithText("Квитанция").assertIsDisplayed();assertNull(vm.selectedRide.value)
    }
    @Test fun heldRestoreCannotReplaceNewBookingAfterCurrentLoaderCompletes() {
        heldMine=true;mount();await {started.count==0L}
        compose.runOnIdle {serverBooking=43;vm.activeBookingId.value=43;vm.selectedRide.value=null};pump()
        await {vm.selectedRide.value?.id=="43"};releaseMine()
        assertEquals(43,vm.activeBookingId.value);assertEquals("43",vm.selectedRide.value!!.id);assertNull(vm.activeTrip.value);assertEquals(Screen.ActiveTrip,vm.screen.value)
    }
    @Test fun heldRestoreFromPreviousLoginCannotReplaceCurrentReceiptRoute() {
        heldMine=true;mount();await {started.count==0L};receipt();compose.runOnIdle {ApiClient.saveToken(tokenB)};pump();releaseMine()
        assertEquals(Screen.TripReceipt,vm.screen.value);assertNull(vm.selectedRide.value);assertEquals(0,posts("rate"))
    }
    @Test fun savedNavigationTrailReconstructsOrderWithoutRestoringBusinessObjects() {
        val saved=SavedStateHandle();val first=YuldashViewModel(saved)
        first.screen.value=Screen.TripReceipt;first.activeBookingId.value=42;first.navHistory.addAll(listOf(Screen.Notifications,Screen.ActiveTrip));first.persistNav()
        val restored=YuldashViewModel(SavedStateHandle(saved.keys().associateWith {saved.get<Any?>(it)}))
        assertEquals(listOf(Screen.Notifications,Screen.ActiveTrip),restored.navHistory.toList());assertEquals(Screen.TripReceipt,restored.screen.value)
        assertEquals(42,restored.activeBookingId.value);assertNull(restored.selectedRide.value);assertNull(restored.activeTrip.value)
    }
    @Test fun clearingUserDataImmediatelyRemovesSavedNavigationTrail() {
        val saved=SavedStateHandle();val first=YuldashViewModel(saved);first.navHistory.add(Screen.ActiveTrip);first.persistNav();first.clearUserData()
        val restored=YuldashViewModel(SavedStateHandle(saved.keys().associateWith {saved.get<Any?>(it)}))
        assertTrue(restored.navHistory.isEmpty())
    }
}
