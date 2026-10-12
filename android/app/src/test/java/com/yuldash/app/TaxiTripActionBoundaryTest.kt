package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.InstantOrderDto
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.runBlocking
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

/** Actual trip/sheets and synthetic HTTP. Held server release + bounded pump, not client joins. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,qualifiers="w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaxiTripActionBoundaryTest {
    @get:Rule val compose=createComposeRule()
    private val tokenA="header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB="header.eyJzdWIiOiIxMiJ9.signature"
    private val mounted=mutableStateOf(true);private val slot=mutableStateOf(0)
    private lateinit var shown:MutableState<InstantOrderDto>
    private lateinit var server:MockWebServer
    private val records=CopyOnWriteArrayList<List<String?>>()
    private val updates=CopyOnWriteArrayList<InstantOrderDto>()
    private val started=CountDownLatch(1);private val release=CountDownLatch(1);private val returned=CountDownLatch(1)
    private var holdPath="";private var wrongId=false;private var withdrew=false
    private var payments=0;private var cancels=0;private var minimized=0
    @Volatile private var phase="arriving"
    @Before fun setup() {
        ApiClient.resetForTest();ApiClient.init(ApplicationProvider.getApplicationContext<Context>());ApiClient.saveToken(tokenA)
        LocationPrefs.lastLat=54.735;LocationPrefs.lastLng=55.958;LocationPrefs.sharingEnabled=false
        clearSignals()
        server=MockWebServer().apply {dispatcher=object:Dispatcher() {
            override fun dispatch(q:RecordedRequest):MockResponse {
                val path=q.requestUrl!!.encodedPath;val body=q.body.readUtf8()
                records+=listOf(q.method,path,q.getHeader("Authorization"),body)
                if(path==holdPath) {started.countDown();assertTrue(release.await(35,TimeUnit.SECONDS));returned.countDown()}
                return when {
                    path=="/instant/availability" -> json("""{"enabled":true}""")
                    path=="/instant/orders/mine" -> json("""{"items":[${order()}]}""")
                    path=="/instant/orders/91/destination/withdraw" -> {withdrew=true;json("{}")}
                    path=="/instant/orders/91/passenger-done" -> json(order(if(wrongId)92 else 91,"done"))
                    path=="/instant/orders/91/im-coming" -> json("""{"ok":true}""")
                    path.endsWith("/destination") || path.endsWith("/waypoints") -> json("""{"price":300,"old_price":250,"applied":true,"driven_km":1,"rest_km":4}""")
                    path=="/geocode" -> json("""{"items":[{"title":"Новая точка","lat":54.8,"lon":56.1}]}""")
                    path=="/instant/orders/91" -> json(order(if(wrongId && withdrew)92 else 91))
                    else -> json("""{"items":[],"drivers":[]}""")
                }
            }
        };start()}
        ApiClient.testBaseUrl=server.url("/").toString().trimEnd('/');ApiClient.testTimeoutMs=60000
        shown=mutableStateOf(runBlocking {ApiClient.getInstantOrder(91).getOrThrow()})
    }
    private fun order(id:Int=91,status:String=phase)="""{"id":$id,"status":"$status","role":"passenger","driver_name":"Водитель А","driver_phone":"+70000000001","from_text":"Пункт А","to_text":"Пункт Б","price_estimate":250,"payment_method":"cash","passenger_can_close":true,"pending_destination":${if(withdrew)"null" else """{"to_text":"Прежний вопрос","price":300,"asked_at":"2026-10-10T10:00:00Z"}"""},"stops":[{"lat":54.1,"lng":55.1,"text":"Проехали","done":true},{"lat":54.2,"lng":55.2,"text":"Первая активная"},{"lat":54.3,"lng":55.3,"text":"Вторая активная"}]}"""
    private fun json(body:String)=MockResponse().setHeader("Content-Type","application/json").setBody(body)
    private fun clearSignals() {NavSignals.openInstantChat.value=0;NavSignals.openSosForOrder.value=0;NavSignals.activeTaxiTrip.value=0;NavSignals.taxiOrderOnScreen.value=false;NavSignals.taxiTripOnScreen.value=false}
    private fun posts(suffix:String)=records.count {it[0]=="POST" && it[1]=="/instant/orders/91/$suffix"}
    private fun pump(ms:Long=500) {repeat((ms/100).toInt()){Snapshot.sendApplyNotifications();compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)};compose.waitForIdle()}
    private fun await(check:()->Boolean)=compose.waitUntil(15000){Snapshot.sendApplyNotifications();compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();check()}
    private fun mount(status:String="arriving",parent:Boolean=false) {
        phase=status;shown.value=shown.value.copy(status=status)
        compose.mainClock.autoAdvance=false
        compose.setContent {if(mounted.value) key(slot.value) {YuldashTheme {
            if(parent) InstantOrderScreen(onBack={},onLoginRequired={},embedded=true,renderNativeMap=false)
            else TaxiTripScreen(order=shown.value,onCancel={cancels++},onMinimize={minimized++},onOpenPayments={payments++},onOrderUpdated={updates+=it},enableLiveTracking=false,mapContent={Box(it)})
        }}}
        pump()
        compose.onNodeWithTag("taxiSheetDragArea").performTouchInput {
            swipe(center, center.copy(y = center.y - 160f), 300)
        }
        pump()
    }
    private fun click(s: String) { val a = text(s); compose.runOnIdle { dispatch(a) } }
    private fun retain(matcher:SemanticsMatcher):()->Boolean {
        await {compose.onAllNodes(matcher and hasClickAction()).fetchSemanticsNodes().isNotEmpty()}
        return compose.onAllNodes(matcher and hasClickAction()).onFirst().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    private fun text(s:String)=retain(hasText(s))
    private fun desc(s:String)=retain(hasContentDescription(s))
    private fun dispatch(a:()->Boolean){a();Shadows.shadowOf(Looper.getMainLooper()).idle()}
    private fun routing():List<()->Boolean> = listOf(desc("Написать водителю"),desc("Позвонить водителю"),desc("Сменить способ"),text("SOS"))
    private fun nextOwner(actions:List<()->Boolean>,logout:Boolean=false) {compose.runOnIdle {if(logout)ApiClient.logout() else ApiClient.saveToken(tokenB);actions.forEach(::dispatch)};pump(1500)}
    private fun noRouting() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        assertEquals(listOf(0,0,0,null),listOf(NavSignals.openInstantChat.value,NavSignals.openSosForOrder.value,payments,Shadows.shadowOf(app).nextStartedActivity?.action))
    }
    private fun finishHeld(){release.countDown();assertTrue(returned.await(8,TimeUnit.SECONDS));pump(1500)}
    private fun doneAction():()->Boolean {mount("onboard");click("Поездка закончилась");return text("Да, закончилась")}
    private fun withdrawAction():()->Boolean {mount();return text("Передумал, едем как ехали")}
    private fun destinationAction():()->Boolean {
        mount("onboard");click("Изменить адрес");pump()
        compose.onNode(hasSetTextAction()).performTextInput("Уфа "+System.nanoTime())
        click("Новая точка")
        await {compose.onAllNodesWithText("Было 250 ₽ → станет 300 ₽").fetchSemanticsNodes().isNotEmpty()}
        return compose.onAllNodes(hasText("Изменить адрес") and hasClickAction()).onLast().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    @After fun cleanup(){release.countDown();compose.runOnIdle{mounted.value=false};pump(300);clearSignals();LocationPrefs.lastLat=null;LocationPrefs.lastLng=null;LocationPrefs.sharingEnabled=false;ApiClient.resetForTest();ApiClient.testTimeoutMs=null;server.shutdown()}

    @Test fun currentOwnerRoutesChatDialPaymentAndSos() {
        mount();val actions=routing();compose.runOnIdle {actions.forEach(::dispatch)};pump()
        assertEquals(91,NavSignals.openInstantChat.value);assertEquals(91,NavSignals.openSosForOrder.value);assertEquals(1,payments)
        val intent=Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL,intent.action);assertEquals("tel:+70000000001",intent.data.toString())
    }
    @Test fun retainedRoutingCannotUseNextAccount(){mount();nextOwner(routing());noRouting()}
    @Test fun retainedRoutingCannotUseGuest(){mount();nextOwner(routing(),true);noRouting()}
    @Test fun disposedRoutingCannotUseRemountedScreen(){mount();val a=routing();compose.runOnIdle{slot.value++};pump();compose.runOnIdle{a.forEach(::dispatch)};pump();noRouting()}
    @Test fun oldTargetRoutingCannotUseNewOrder(){mount();val a=routing();compose.runOnIdle{shown.value=shown.value.copy(id=92)};pump();compose.runOnIdle{a.forEach(::dispatch)};pump();noRouting()}
    @Test fun terminalPhaseRetiresRouting(){mount();val a=routing();compose.runOnIdle{shown.value=shown.value.copy(status="done")};pump();compose.runOnIdle{a.forEach(::dispatch)};pump();noRouting()}
    @Test fun oldCancelAndMinimizeCannotEscapeNextOwner(){mount();val a=listOf(text("Отменить заказ"),desc("Свернуть поездку"));nextOwner(a);assertEquals(0,cancels);assertEquals(0,minimized)}
    @Test fun actualParentCurrentOwnerCanWarnDriver(){mount(parent=true);click("Уже выхожу");await{posts("im-coming")==1};await{compose.onAllNodesWithText("Водитель предупреждён").fetchSemanticsNodes().isNotEmpty()}}
    @Test fun parentFinishedPublicationRetiresOldTripActions(){mount(parent=true);val a=listOf(desc("Написать водителю"),text("Уже выхожу"));compose.runOnIdle{val trip=TaxiNavigationState.currentTrip()!!;assertTrue(TaxiNavigationState.finishTrip(trip));a.forEach(::dispatch)};pump(1500);assertEquals(0,NavSignals.openInstantChat.value);assertEquals(0,posts("im-coming"))}
    @Test fun currentImComingIsOwnedAndShowsSuccess(){mount();click("Уже выхожу");await{posts("im-coming")==1};await{compose.onAllNodesWithText("Водитель предупреждён").fetchSemanticsNodes().isNotEmpty()};assertEquals("Bearer $tokenA",records.single{it[1]!!.endsWith("/im-coming")}[2])}
    @Test fun retainedImComingCannotUseNextOwner(){mount();nextOwner(listOf(text("Уже выхожу")));assertEquals(0,posts("im-coming"))}
    @Test fun retainedImComingCannotUseGuest(){mount();nextOwner(listOf(text("Уже выхожу")),true);assertEquals(0,posts("im-coming"))}
    @Test fun onboardRetiresOldImComing(){mount();val a=text("Уже выхожу");compose.runOnIdle{shown.value=shown.value.copy(status="onboard")};pump();compose.runOnIdle{dispatch(a)};pump();assertEquals(0,posts("im-coming"))}
    @Test fun duplicateImComingWhileHeldSendsOnce(){holdPath="/instant/orders/91/im-coming";mount();val a=text("Уже выхожу");compose.runOnIdle{dispatch(a);dispatch(a)};await{started.count==0L};pump();assertEquals(1,posts("im-coming"));finishHeld()}
    @Test fun currentWithdrawFetchesAndPublishesMatchingOrder(){val a=withdrawAction();compose.runOnIdle{dispatch(a)};await{updates.isNotEmpty()};assertEquals(91,updates.single().id);assertEquals("",updates.single().pendingToText);assertEquals(1,posts("destination/withdraw"));assertTrue(records.filter{it[1]!!.startsWith("/instant/orders/91")}.all{it[2]=="Bearer $tokenA"})}
    @Test fun retainedWithdrawCannotUseNextOwner(){val a=withdrawAction();nextOwner(listOf(a));assertEquals(0,posts("destination/withdraw"));assertTrue(updates.isEmpty())}
    @Test fun duplicateWithdrawWhileHeldSendsOnce(){holdPath="/instant/orders/91/destination/withdraw";val a=withdrawAction();compose.runOnIdle{dispatch(a);dispatch(a)};await{started.count==0L};pump();assertEquals(1,posts("destination/withdraw"));finishHeld()}
    @Test fun wrongWithdrawDtoCannotPublishAnotherOrder(){wrongId=true;val a=withdrawAction();compose.runOnIdle{dispatch(a)};await{posts("destination/withdraw")==1};pump(1500);assertTrue(updates.isEmpty())}
    @Test fun removedPendingRequestRetiresWithdraw(){val a=withdrawAction();compose.runOnIdle{shown.value=shown.value.copy(pendingToText="",pendingAskedAt=null)};pump();compose.runOnIdle{dispatch(a)};pump();assertEquals(0,posts("destination/withdraw"))}
    @Test fun heldWithdrawCannotFetchOrPublishOldTargetAfterReplacement(){holdPath="/instant/orders/91/destination/withdraw";val a=withdrawAction();compose.runOnIdle{dispatch(a)};await{started.count==0L};val gets=records.count{it[0]=="GET"&&it[1]=="/instant/orders/91"};compose.runOnIdle{shown.value=shown.value.copy(id=92)};pump();finishHeld();assertEquals(gets,records.count{it[0]=="GET"&&it[1]=="/instant/orders/91"});assertTrue(updates.isEmpty())}
    @Test fun currentPassengerDonePublishesMatchingTerminalOrder(){val a=doneAction();compose.runOnIdle{dispatch(a)};await{updates.isNotEmpty()};assertEquals(91,updates.single().id);assertEquals("done",updates.single().status);assertEquals(1,posts("passenger-done"))}
    @Test fun retainedPassengerDoneCannotUseNextOwner(){val a=doneAction();nextOwner(listOf(a));assertEquals(0,posts("passenger-done"));assertTrue(updates.isEmpty())}
    @Test fun retainedPassengerDoneCannotUseGuest(){val a=doneAction();nextOwner(listOf(a),true);assertEquals(0,posts("passenger-done"))}
    @Test fun duplicatePassengerDoneWhileHeldSendsOnce(){holdPath="/instant/orders/91/passenger-done";val a=doneAction();compose.runOnIdle{dispatch(a);dispatch(a)};await{started.count==0L};pump();assertEquals(1,posts("passenger-done"));finishHeld()}
    @Test fun wrongPassengerDoneDtoCannotPublishAnotherOrder(){wrongId=true;val a=doneAction();compose.runOnIdle{dispatch(a)};await{posts("passenger-done")==1};pump(1500);assertTrue(updates.isEmpty())}
    @Test fun lostServerPermissionRetiresPassengerDone(){val a=doneAction();compose.runOnIdle{shown.value=shown.value.copy(passengerCanClose=false)};pump();compose.runOnIdle{dispatch(a)};pump();assertEquals(0,posts("passenger-done"))}
    @Test fun removesSelectedActiveStopAfterCompletedStop(){mount();val a=desc("Убрать остановку");compose.runOnIdle{dispatch(a)};await{posts("waypoints")==1};val points=JSONObject(records.single{it[1]!!.endsWith("/waypoints")}[3]!!).getJSONArray("waypoints");assertEquals(1,points.length());assertEquals("Вторая активная",points.getJSONObject(0).getString("text"));assertEquals(54.3,points.getJSONObject(0).getDouble("lat"),0.0001)}
    @Test fun retainedRemoveStopCannotUseNextOwner(){mount();nextOwner(listOf(desc("Убрать остановку")));assertEquals(0,posts("waypoints"))}
    @Test fun duplicateRemoveStopWhileHeldSendsOnce(){holdPath="/instant/orders/91/waypoints";mount();val a=desc("Убрать остановку");compose.runOnIdle{dispatch(a);dispatch(a)};await{started.count==0L};pump();assertEquals(1,posts("waypoints"));finishHeld()}
    @Test fun currentDestinationQuoteThenConfirmSendsExactOwnedPayload(){val a=destinationAction();compose.runOnIdle{dispatch(a)};await{posts("destination")==2};val rows=records.filter{it[1]!!.endsWith("/destination")};assertTrue(rows.all{it[2]=="Bearer $tokenA"});assertTrue(JSONObject(rows[0][3]!!).getBoolean("preview"));val body=JSONObject(rows[1][3]!!);assertFalse(body.getBoolean("preview"));assertEquals("Новая точка",body.getString("to_text"));assertEquals(54.8,body.getDouble("to_lat"),0.0001);await{compose.onAllNodesWithText("Куда едем?").fetchSemanticsNodes().isEmpty()}}
    @Test fun retainedDestinationConfirmCannotUseNextOwner(){val a=destinationAction();nextOwner(listOf(a));assertEquals(1,posts("destination"))}
    @Test fun duplicateDestinationConfirmWhileHeldSendsOnce(){val a=destinationAction();holdPath="/instant/orders/91/destination";compose.runOnIdle{dispatch(a);dispatch(a)};await{started.count==0L};pump();assertEquals(2,posts("destination"));finishHeld()}
    @Test fun currentAddStopUsesOwnedRouteAndActualPickedAddress(){mount("onboard");click("Ещё остановка");pump();compose.onNode(hasSetTextAction()).performTextInput("Стоп "+System.nanoTime());click("Новая точка");await{posts("waypoints")==1};val body=JSONObject(records.single{it[1]!!.endsWith("/waypoints")}[3]!!).getJSONArray("waypoints");assertEquals(3,body.length());assertEquals("Новая точка",body.getJSONObject(2).getString("text"));assertEquals("Bearer $tokenA",records.single{it[1]!!.endsWith("/waypoints")}[2])}
    @Test fun retainedPickedStopCannotUseNextOwner(){mount("onboard");click("Ещё остановка");pump();compose.onNode(hasSetTextAction()).performTextInput("Стоп "+System.nanoTime());val a=text("Новая точка");nextOwner(listOf(a));assertEquals(0,posts("waypoints"))}
    @Test fun previousAddressSuggestionCannotPreviewAfterNewInput() {
        mount("onboard"); click("Изменить адрес"); pump()
        compose.onNode(hasSetTextAction()).performTextInput("Первый " + System.nanoTime())
        val previous = text("Новая точка")
        compose.onNode(hasSetTextAction()).performTextReplacement("Другой " + System.nanoTime())
        pump(100)
        compose.runOnIdle { dispatch(previous) }
        pump(1500)
        assertEquals(0, posts("destination"))
    }

}
