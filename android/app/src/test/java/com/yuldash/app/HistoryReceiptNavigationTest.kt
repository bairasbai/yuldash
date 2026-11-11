package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.snapshotOrigin
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Actual YuldashApp/Home/HTTP: historical receipt has its own destination, never an active owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,qualifiers="w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryReceiptNavigationTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val mounted=mutableStateOf(true)
    private val tokenA="header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB="header.eyJzdWIiOiIxMiJ9.signature"
    private val actor by lazy { Robolectric.buildActivity(MainActivity::class.java).create() }
    private lateinit var vm:YuldashViewModel
    private lateinit var server:MockWebServer
    private val records=CopyOnWriteArrayList<Triple<String,String,String?>>()
    private var historicalStatus="done"
    private var historicalInTransit=false
    private var historicalId=42
    @Volatile private var observedMineRide:Int?=null
    @Volatile private var observedDetailsRide:Int?=null
    @Volatile private var observedDetailsValid:Boolean?=null
    @Volatile private var observedRoleEpoch:Boolean?=null
    private var loginApiOrigin:String?=null
    private var loginOwnerOrigin:String?=null
    // Booking 44 owns ride 8 on the wire; this filled Ride is not bookingLinkStubRide(44).
    private val currentOnboardAt="2031-02-03T09:00:00.123456Z"
    private val current=Ride(id="8",from="Текущая А",to="Текущая Б",time="Завтра",driver="CurrentDriver44",car="",price=900,seats=1,rating=4.9,verified=true,boosted=false)

    private fun awaitApiBackgroundCompletion()=runBlocking {
        // Observe only the test-owned API coroutine job, never auth/key fields.
        val scope=ApiClient::class.java.getDeclaredField("bg").apply {isAccessible=true}.get(ApiClient) as CoroutineScope
        val parent=requireNotNull(scope.coroutineContext[Job])
        try {
            withTimeout(10_000) {
                while(true) {
                    val children=parent.children.toList()
                    if(children.isEmpty()) break
                    children.forEach {it.join()}
                }
            }
        } catch(failure:TimeoutCancellationException) {
            throw AssertionError("Fixture API jobs still running; local origin/server retained. Abort this isolated run.",failure)
        }
    }
    private fun awaitSnapshotCleanup()=runBlocking {
        withTimeout(5_000) {while(!OfflineTripSnapshots.ensureResetCommitted()) delay(10)}
    }
    private fun serverOrigin(): String = "http://127.0.0.1:${server.port}"

    private fun observeOwnerOrigin(stage:String,captureLogin:Boolean=false) {
        val apiOrigin=snapshotOrigin(ApiClient.apiBase())
        val current=OfflineTripSnapshots.currentOwner()
        if(captureLogin) {
            loginApiOrigin=apiOrigin
            loginOwnerOrigin=current?.origin
        }
        val localOrigin=if(::server.isInitialized)snapshotOrigin(serverOrigin()) else null
        // Origins stay in RAM for equality checks only. Never print identity/address values.
        println("HISTORY_ORIGIN stage="+stage+" user11="+(ApiClient.myUserId()==11)+
            " ownerPresent="+(current!=null)+" localKnown="+(localOrigin!=null)+
            " ownerLocal="+(localOrigin!=null && current?.origin==localOrigin)+
            " apiLocal="+(localOrigin!=null && apiOrigin==localOrigin)+
            " apiOriginSame="+(apiOrigin!=null && apiOrigin==loginApiOrigin)+
            " loginOwnerMatchesApi="+(loginOwnerOrigin!=null && loginOwnerOrigin==apiOrigin)+
            " loginOwnerLocal="+(localOrigin!=null && loginOwnerOrigin==localOrigin))
    }
    @Before fun setup() {
        awaitApiBackgroundCompletion();awaitSnapshotCleanup()
        ApiClient.resetForTest()
        observeOwnerOrigin("before-local-bind")
        context.getSharedPreferences("yuldash_prefs",0).edit().clear().putBoolean("onboarding_completed",false).putString("mode","passenger").commit()
        server=MockWebServer().apply {
            dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    val path=request.requestUrl!!.encodedPath
                    val bearer=request.getHeader("Authorization")
                    records+=Triple(request.method.orEmpty(),path,bearer)
                    val a=bearer=="Bearer $tokenA"
                    val body=when {
                        path=="/bookings/mine" && a -> """{"items":[{"id":$historicalId,"ride_id":7,"status":"$historicalStatus","cancelled_in_transit":$historicalInTransit,"seats":1,"price":750,"from_city":"Историческая А","to_city":"Историческая Б","depart_at":"2030-01-02T10:00:00+03:00","driver_name":"HistoricDriver42"},{"id":44,"ride_id":8,"status":"onboard","seats":1,"price":900,"from_city":"Текущая А","to_city":"Текущая Б","driver_name":"CurrentDriver44"}]}"""
                        path=="/bookings/42/role" && a -> """{"role":"passenger","status":"$historicalStatus","cancelled_in_transit":$historicalInTransit,"problem_report_sec":3600}"""
                        path=="/bookings/42/details" && a -> """{"booking_id":42,"ride_id":7,"role":"passenger","status":"$historicalStatus","cancelled_in_transit":$historicalInTransit,"problem_report_sec":3600,"contact_unlocked":false,"from_city":"Историческая А","to_city":"Историческая Б","depart_at":"2030-01-02T10:00:00+03:00","seats":1,"price":750,"driver_name":"HistoricDriver42","driver_car":"QA Test Car","driver_plate":"TEST"}"""
                        path=="/bookings/44/role" -> """{"role":"passenger","status":"onboard","onboard_at":"$currentOnboardAt"}"""
                        path=="/bookings/44/details" && a -> """{"booking_id":44,"ride_id":8,"role":"passenger","status":"onboard","onboard_at":"$currentOnboardAt","contact_unlocked":false,"from_city":"Текущая А","to_city":"Текущая Б","depart_at":"2031-02-03T12:00:00+03:00","seats":1,"price":900,"driver_name":"CurrentDriver44"}"""
                        path=="/trips/42/receipt" && a -> """{"booking_id":42,"ride_id":7,"role":"passenger","from_city":"Историческая А","to_city":"Историческая Б","depart_at":"2030-01-02T10:00:00+03:00","seats":1,"amount":750,"pay_method":"cash","paid":false,"driver_name":"ReceiptDriver42","driver_verified":true,"counterparty_name":"ReceiptDriver42","my_stars":5,"driver_rating":4.2}"""
                        path=="/trips/42/tip" && a -> """{"already_thanked":false}"""
                        path=="/me" -> """{"id":${if(a)11 else 12},"name":"HistoryPax","role":"passenger"}"""
                        else -> """{"items":[],"role":"passenger"}"""
                    }
                    // Observe only the actual existing synthetic wire response; never feed
                    // this parsed value into ApiClient, the VM, or the authority gate.
                    if(path=="/bookings/mine") observedMineRide=runCatching {
                        val items=JSONObject(body).getJSONArray("items")
                        (0 until items.length()).map{items.getJSONObject(it)}
                            .firstOrNull{it.optInt("id")==44}?.optInt("ride_id")
                    }.getOrNull()
                    if(path=="/bookings/44/role") observedRoleEpoch=runCatching {
                        val value=JSONObject(body)
                        !value.isNull("onboard_at") && value.optString("onboard_at").isNotBlank()
                    }.getOrNull()
                    if(path=="/bookings/44/details") {
                        val value=JSONObject(body)
                        observedDetailsRide=if(value.has("ride_id"))value.optInt("ride_id") else null
                        observedDetailsValid=value.optInt("booking_id")==44 && value.optInt("ride_id")>0 &&
                            value.optString("status") in setOf("pending","confirmed","onboard","done","cancelled")
                    }
                    return MockResponse().setResponseCode(if(path=="/instant/orders/active")503 else if(path=="/trips/42/receipt" && !a)404 else 200).setHeader("Content-Type","application/json").setBody(body)
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        ApiClient.testBaseUrl=serverOrigin();ApiClient.testTimeoutMs=1000
        ApiClient.init(context)
        awaitSnapshotCleanup()
        ApiClient.saveToken(tokenA)
        observeOwnerOrigin("after-local-login",captureLogin=true)
        vm=ViewModelProvider(actor.get())[YuldashViewModel::class.java]
        vm.screen.value=Screen.Home;vm.startHomeTab.value=HomeTab.Rides;vm.persistNav()
    }
    private fun pump(ms:Long=500) {repeat((ms/100).toInt()){compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();Snapshot.sendApplyNotifications();Thread.sleep(10)};compose.waitForIdle()}
    private fun navigationDiagnostic(stage:String):String="NAV5 history "+stage+
        " screen="+vm.screen.value+" booking="+vm.activeBookingId.value+" activeRideId="+vm.activeTrip.value?.id+
        " selectedRideId="+vm.selectedRide.value?.id+" expectedRideId="+current.id+
        " activeMatches="+(vm.activeTrip.value==current)+" selectedMatches="+(vm.selectedRide.value==current)+
        " pending="+(vm.pendingBookingNavigation.value!=null || vm.pendingScreenNavigation.value!=null || vm.pendingPublicRideNavigation.value!=null)+
        " revision="+vm.privateNavigationRevision+" owner11="+(OfflineTripSnapshots.currentOwner()?.userId==11)+
        " mineGET="+records.count{it.first=="GET" && it.second=="/bookings/mine"}+
        " role44GET="+records.count{it.first=="GET" && it.second=="/bookings/44/role"}+
        " details44GET="+records.count{it.first=="GET" && it.second=="/bookings/44/details"}+
        " receipt42GET="+receiptReads()+" receipt44GET="+records.count{it.first=="GET" && it.second=="/trips/44/receipt"}+
        " sourceMineRide="+observedMineRide+" sourceDetailsRide="+observedDetailsRide+
        " sourceDetailsValid="+observedDetailsValid+" sourceRoleEpoch="+observedRoleEpoch
    private fun await(check:()->Boolean) {
        try {
            compose.waitUntil(15000){compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();Snapshot.sendApplyNotifications();check()}
        } catch(failure:ComposeTimeoutException) {
            throw AssertionError(navigationDiagnostic("await"),failure)
        }
    }
    private fun prepareCurrentActiveBooking()=runBlocking {
        // Genuine own HTTP reads establish the active fixture before UI/history actions.
        // No authority/store installation, including after any terminal/history closure.
        val role=ApiClient.getTripState(44).getOrThrow()
        assertEquals("passenger",role.role)
        assertEquals("onboard",role.status)
        assertEquals(currentOnboardAt,role.boardingUndo.onboardAt)
        val details=ApiClient.getBookingDetails(44).getOrThrow()
        assertEquals(44,details.bookingId)
        assertEquals(8,details.rideId)
        assertEquals(current.id,details.rideId.toString())
        assertEquals(role.role,details.role)
        assertEquals(role.status,details.status)
        assertEquals(role.boardingUndo.onboardAt,details.boardingUndo.onboardAt)
        assertTrue("Active fixture needs fresh participant DETAILS before mount",OfflineTripSnapshots.freshDetails(details))
        assertTrue("Active fixture needs actual ROLE + DETAILS live authority before mount",OfflineTripSnapshots.allowsLive(44))
    }
    private fun mount(language:AppLanguage=AppLanguage.Ru,active:Boolean=false,saver:StateRestorationTester?=null) {
        vm.language.value=language
        if(active){prepareCurrentActiveBooking();vm.activeBookingId.value=44;vm.activeTrip.value=current;vm.selectedRide.value=current;vm.persistNav()}
        observeOwnerOrigin("before-mount")
        compose.mainClock.autoAdvance=false
        val body:@androidx.compose.runtime.Composable ()->Unit={if(mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get(),LocalPoolingNativeMapEnabled provides false){YuldashTheme{YuldashApp()}}}
        if(saver==null)compose.setContent(body) else saver.setContent(body)
        pump();await{records.any{it.second=="/bookings/mine"}}
        if(active)println(navigationDiagnostic("after-active-mount"))
    }
    private fun click(label:String){await{compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText(label).performClick();pump()}
    private fun history(ba:Boolean=false){click(if(ba)"Актив" else "Активные");click(if(ba)"Тарих" else "История");await{compose.onAllNodesWithText(if(ba)"Маршрутты ҡабатлау" else "Повторить маршрут").fetchSemanticsNodes().isNotEmpty()}}
    private fun captureReceipt():()->Boolean {await{compose.onAllNodesWithText("Квитанция").fetchSemanticsNodes().isNotEmpty()};return compose.onNodeWithText("Квитанция").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!}
    private fun receiptReads()=records.count{it.first=="GET"&&it.second=="/trips/42/receipt"}
    private fun openReceipt(){click("Квитанция");await{vm.screen.value==Screen.TripReceipt&&compose.onAllNodesWithText("ReceiptDriver42").fetchSemanticsNodes().isNotEmpty()};println(navigationDiagnostic("after-receipt"));assertTrue(records.any{it.second=="/trips/42/receipt"&&it.third=="Bearer $tokenA"});assertFalse(records.any{it.second=="/trips/7/receipt"||it.second=="/trips/44/receipt"})}
    private fun back(ba:Boolean=false){compose.onNodeWithContentDescription(if(ba)"Артҡа" else "Назад").performClick();pump()}
    @After fun cleanup(){
        if(!::server.isInitialized) {awaitApiBackgroundCompletion();awaitSnapshotCleanup();return}
        compose.runOnIdle{mounted.value=false};pump(300);actor.destroy();vm.clearUserData()
        awaitApiBackgroundCompletion();awaitSnapshotCleanup()
        ApiClient.resetForTest();ApiClient.testTimeoutMs=null;server.shutdown()
    }

    @Test fun russianHistoryOpensExactBookingReceipt(){mount();history();openReceipt();assertEquals(Screen.TripReceipt,vm.screen.value)}
    @Test fun bashkirHistoryOpensExactBookingReceipt(){mount(AppLanguage.Ba);history(true);openReceipt();back(true);assertEquals(HomeTab.Rides,vm.startHomeTab.value);compose.onNodeWithText("Тарих").assertExists()}
    @Test fun receiptNeverReplacesAnotherActiveBooking(){mount(active=true);history();openReceipt();assertEquals(44,vm.activeBookingId.value);assertEquals(current,vm.activeTrip.value);assertEquals(current,vm.selectedRide.value)}
    @Test fun backKeepsHistoryAndRidesTab(){mount();history();openReceipt();back();assertEquals(Screen.Home,vm.screen.value);assertEquals(HomeTab.Rides,vm.startHomeTab.value);compose.onNodeWithText("История").assertExists();compose.onNodeWithText("Квитанция").assertExists()}
    @Test fun historyFilterSurvivesSavedState(){val saver=StateRestorationTester(compose);mount(saver=saver);history();saver.emulateSavedInstanceStateRestore();pump();compose.onNodeWithText("История").assertExists();compose.onNodeWithText("Повторить маршрут").assertExists()}
    @Test fun receiptTargetSurvivesSavedStateWithoutActivatingIt(){val saver=StateRestorationTester(compose);mount(active=true,saver=saver);history();openReceipt();saver.emulateSavedInstanceStateRestore();pump();await{compose.onAllNodesWithText("ReceiptDriver42").fetchSemanticsNodes().isNotEmpty()};assertEquals(Screen.TripReceipt,vm.screen.value);assertEquals(44,vm.activeBookingId.value);assertEquals(current,vm.activeTrip.value)}
    @Test fun retainedReceiptAfterTabChangeIsRejected(){mount();history();val old=captureReceipt();click("Чат");compose.runOnIdle{old()};pump();assertEquals(Screen.Home,vm.screen.value);assertEquals(HomeTab.Chat,vm.startHomeTab.value);assertEquals(0,receiptReads())}
    @Test fun retainedReceiptAfterLeavingHomeIsRejected(){mount();history();val old=captureReceipt();compose.runOnIdle{vm.screen.value=Screen.Settings;old()};pump();assertEquals(Screen.Settings,vm.screen.value);assertEquals(0,receiptReads())}
    @Test fun retainedReceiptAfterAccountSwitchIsRejected(){mount();history();val old=captureReceipt();compose.runOnIdle{ApiClient.saveToken(tokenB);old()};pump();assertTrue(vm.screen.value!=Screen.TripReceipt);assertEquals(0,receiptReads())}
    @Test fun retainedReceiptAfterLogoutIsRejected(){mount();history();val old=captureReceipt();compose.runOnIdle{ApiClient.logout();old()};pump();assertTrue(vm.screen.value!=Screen.TripReceipt);assertEquals(0,receiptReads())}
    @Test fun doubleTapMakesOneReceiptRequest(){mount();history();val open=captureReceipt();compose.runOnIdle{open();open()};await{receiptReads()==1&&vm.screen.value==Screen.TripReceipt};pump();assertEquals(1,receiptReads())}
    @Test fun cancelledHistoryKeepsRepeatWithoutReceipt(){historicalStatus="cancelled";mount();history();compose.onAllNodesWithText("Квитанция").assertCountEquals(0);assertEquals(0,receiptReads())}
    @Test fun invalidHistoricalIdHasNoReceiptDoor(){historicalId=0;mount();history();compose.onAllNodesWithText("Квитанция").assertCountEquals(0);assertEquals(0,receiptReads())}
    @Test fun repeatRouteStillSelectsRequests(){mount(active=true);history();click("Повторить маршрут");assertEquals(Screen.Home,vm.screen.value);assertEquals(HomeTab.Request,vm.startHomeTab.value);assertEquals(44,vm.activeBookingId.value);assertEquals(0,receiptReads())}
    @Test fun allFilterOffersReceiptOnlyForCompletedBooking(){mount();click("Активные");click("Все");await{compose.onAllNodesWithText("Квитанция").fetchSemanticsNodes().isNotEmpty()};compose.onAllNodesWithText("Квитанция").assertCountEquals(1);openReceipt()}

    @Test fun cancelledInTransitHistoryReopensHelpThroughFreshBookingRead() {
        historicalStatus = "cancelled"
        historicalInTransit = true
        mount()
        history()
        compose.onNodeWithText("Повторить маршрут").assertExists()
        click("Открыть помощь")
        await {
            vm.screen.value == Screen.ActiveTrip &&
                compose.onAllNodesWithText("Поездку отменили в пути").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(42, vm.activeBookingId.value)
        assertTrue(records.any { it.first == "GET" && it.second == "/bookings/42/role" && it.third == "Bearer $tokenA" })
        assertEquals(0, receiptReads())
    }

    @Test fun retainedCancelledHelpActionCannotOpenAfterAccountSwitch() {
        historicalStatus = "cancelled"
        historicalInTransit = true
        mount()
        history()
        val open = compose.onNodeWithText("Открыть помощь").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val roleReads = records.count { it.second == "/bookings/42/role" }
        compose.runOnIdle { ApiClient.saveToken(tokenB); open() }
        pump()
        assertEquals(roleReads, records.count { it.second == "/bookings/42/role" })
        assertNotEquals(42, vm.activeBookingId.value)
        assertTrue(vm.screen.value != Screen.ActiveTrip)
    }
}
