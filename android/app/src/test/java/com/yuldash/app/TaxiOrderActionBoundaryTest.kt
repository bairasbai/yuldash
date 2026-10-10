package com.yuldash.app

import android.app.Application
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.DialogInterface
import org.robolectric.shadows.ShadowAlertDialog
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
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

/** Actual picker/cancel dialog/API. Synthetic owners; held replies are bounded, not client joins. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,qualifiers="w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaxiOrderActionBoundaryTest {
    @get:Rule val compose=createComposeRule()
    private val tokenA="header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB="header.eyJzdWIiOiIxMiJ9.signature"
    private val mounted=mutableStateOf(true)
    private val slot=mutableStateOf(0)
    private lateinit var server:MockWebServer
    private val records=CopyOnWriteArrayList<Triple<String,String,String?>>()
    private val started=CountDownLatch(1);private val release=CountDownLatch(1);private val returned=CountDownLatch(1)
    @Volatile private var active=false
    @Volatile private var status="searching"
    private var holdCreate=false;private var holdCancel=false;private var rejectFirst=false;private var wrongCancelId=false
    @Before fun setup() {
        ApiClient.resetForTest();ApiClient.init(ApplicationProvider.getApplicationContext<Context>());ApiClient.saveToken(tokenA)
        LocationPrefs.lastLat=54.735;LocationPrefs.lastLng=55.958;LocationPrefs.sharingEnabled=false
        NavSignals.activeTaxiTrip.value=0;NavSignals.taxiOrderOnScreen.value=false;NavSignals.taxiTripOnScreen.value=false
        server=MockWebServer().apply {
            dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    val path=request.requestUrl!!.encodedPath
                    records+=Triple(request.method!!,path,request.getHeader("Authorization"))
                    return when {
                        path=="/instant/availability" -> json("""{"enabled":true}""")
                        path=="/instant/orders/mine" -> json(if(active) """{"items":[${order()}]}""" else """{"items":[]}""")
                        path=="/places/saved" -> json("""{"items":[{"id":10,"kind":"home","label":"Дом","address":"Адрес А","lat":54.751,"lng":56.001}]}""")
                        path=="/instant/estimate" -> json("""{"price":250,"distance_km":4.0,"eta_min":12.0,"zone":"city","category":"standard","options":[{"category":"standard","price":250,"open":true}]}""")
                        path=="/instant/schedule" -> json(order(91,"scheduled"),201)
                        path=="/instant/orders" && request.method=="POST" -> {
                            if(holdCreate) hold()
                            if(rejectFirst && posts(path)==1) json("""{"detail":"Контрольный отказ заказа"}""",503) else json(order(),201)
                        }
                        path=="/instant/orders/91/cancel" -> {
                            if(holdCancel) hold()
                            json(order(if(wrongCancelId) 92 else 91,"cancelled"))
                        }
                        path=="/instant/orders/91" -> json(order())
                        else -> json("""{"items":[],"drivers":[]}""")
                    }
                }
            };start()
        }
        ApiClient.testBaseUrl=server.url("/").toString().trimEnd('/');ApiClient.testTimeoutMs=60000
    }
    private fun hold() {started.countDown();assertTrue(release.await(35,TimeUnit.SECONDS));returned.countDown()}
    private fun json(body:String,code:Int=200)=MockResponse().setResponseCode(code).setHeader("Content-Type","application/json").setBody(body)
    private fun order(id:Int=91,s:String=status)="""{"id":$id,"status":"$s","role":"passenger","driver_name":"Водитель А","from_text":"Пункт А","to_text":"Адрес А","price_estimate":250}"""
    private fun posts(path:String)=records.count {it.first=="POST" && it.second==path}
    private fun pump(ms:Long=500) {repeat((ms/100).toInt()) {Snapshot.sendApplyNotifications();compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)};compose.waitForIdle()}
    private fun await(check:()->Boolean)=compose.waitUntil(15000) {Snapshot.sendApplyNotifications();compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();check()}
    private fun mount() {
        compose.mainClock.autoAdvance=false
        compose.setContent {if(mounted.value) key(slot.value) {YuldashTheme {InstantOrderScreen(onBack={},onLoginRequired={},embedded=true,renderNativeMap=false)}}}
        pump()
    }
    private fun retained(text:String):()->Boolean {
        await {compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
        return compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    private fun createAction():()->Boolean {
        mount();await {compose.onAllNodesWithText("Дом").fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasText("Дом") and hasClickAction()).performClick()
        await {compose.onAllNodesWithText("Заказать").fetchSemanticsNodes().any {!it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)}}
        return retained("Заказать")
    }
    private fun scheduledAction():()->Boolean {
        createAction()
        compose.onNodeWithContentDescription("Детали заказа").performClick();pump()
        compose.onNodeWithText("На время").performScrollTo().performClick()
        compose.runOnIdle {
            val date=ShadowAlertDialog.getLatestAlertDialog() as DatePickerDialog
            date.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val time=ShadowAlertDialog.getLatestAlertDialog() as TimePickerDialog
            time.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        }
        await {compose.onAllNodes(hasText("Заказать на",substring=true) and hasClickAction()).fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }}
        return compose.onNode(hasText("Заказать на",substring=true) and hasClickAction()).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    private fun cancelAction():()->Boolean {
        active=true;mount();await {compose.onAllNodesWithText("Ищем машину").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Отменить заказ").performClick()
        return retained("Пропустить")
    }
    private fun dispatchBeforeFrame(action:()->Boolean) {action();Shadows.shadowOf(Looper.getMainLooper()).idle()}
    private fun finishHeld() {release.countDown();assertTrue(returned.await(8,TimeUnit.SECONDS));pump(1500)}
    @After fun cleanup() {
        release.countDown();compose.runOnIdle {mounted.value=false};pump(300)
        LocationPrefs.lastLat=null;LocationPrefs.lastLng=null;LocationPrefs.sharingEnabled=false
        NavSignals.activeTaxiTrip.value=0;NavSignals.taxiOrderOnScreen.value=false;NavSignals.taxiTripOnScreen.value=false
        ApiClient.resetForTest();ApiClient.testTimeoutMs=null;server.shutdown()
    }
    @Test fun currentCreateWritesOrderAndRecentForItsOwner() {
        val create=createAction();compose.runOnIdle {dispatchBeforeFrame(create)}
        await {posts("/instant/orders")==1 && records.any {it.first=="POST" && it.second=="/places/recent"}}
        assertTrue(records.filter {it.first=="POST" && it.second in setOf("/instant/orders","/places/recent")}.all {it.third=="Bearer $tokenA"})
        await {compose.onAllNodesWithText("Ищем машину").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun retainedCreateCannotSendOldAddressesUnderNextAccount() {
        val create=createAction();compose.runOnIdle {ApiClient.saveToken(tokenB);dispatchBeforeFrame(create)};pump(1500)
        assertEquals(0,posts("/instant/orders"));assertEquals(0,posts("/places/recent"))
    }
    @Test fun retainedCreateCannotRunAfterLogout() {
        val create=createAction();compose.runOnIdle {ApiClient.logout();dispatchBeforeFrame(create)};pump(1500)
        assertEquals(0,posts("/instant/orders"));assertEquals(0,posts("/places/recent"))
    }
    @Test fun disposedPickerCannotCreateOnRemountedScreen() {
        val create=createAction();compose.runOnIdle {slot.value++};pump();compose.runOnIdle {dispatchBeforeFrame(create)};pump()
        assertEquals(0,posts("/instant/orders"))
    }
    @Test fun heldCreateCannotPublishOrderOrRecentToNextOwner() {
        holdCreate=true;val create=createAction();compose.runOnIdle {dispatchBeforeFrame(create)};await {started.count==0L}
        compose.runOnIdle {ApiClient.saveToken(tokenB)};pump();finishHeld()
        assertEquals(1,posts("/instant/orders"));assertFalse(records.any {it.first=="POST" && it.second=="/places/recent" && it.third=="Bearer $tokenB"})
        assertFalse(records.any {it.second=="/instant/orders/91" && it.third=="Bearer $tokenB"})
        compose.onAllNodesWithText("Ищем машину").assertCountEquals(0)
    }
    @Test fun currentCancelSendsOnceAndShowsItsTerminalOrder() {
        val cancel=cancelAction();compose.runOnIdle {dispatchBeforeFrame(cancel)}
        await {posts("/instant/orders/91/cancel")==1};await {compose.onAllNodesWithText("Заказ отменён").fetchSemanticsNodes().isNotEmpty()}
        assertEquals("Bearer $tokenA",records.single {it.second=="/instant/orders/91/cancel"}.third)
    }
    @Test fun retainedReasonCannotCancelOldOrderUnderNextAccount() {
        val cancel=cancelAction();compose.runOnIdle {ApiClient.saveToken(tokenB);dispatchBeforeFrame(cancel)};pump(1500)
        assertEquals(0,posts("/instant/orders/91/cancel"))
    }
    @Test fun retainedReasonCannotCancelAfterLogout() {
        val cancel=cancelAction();compose.runOnIdle {ApiClient.logout();dispatchBeforeFrame(cancel)};pump(1500)
        assertEquals(0,posts("/instant/orders/91/cancel"))
    }
    @Test fun duplicateReasonActionWhileReplyHeldSendsOnlyOneCancel() {
        holdCancel=true;val cancel=cancelAction();compose.runOnIdle {dispatchBeforeFrame(cancel);dispatchBeforeFrame(cancel)}
        await {started.count==0L};pump(1000);assertEquals(1,posts("/instant/orders/91/cancel"));finishHeld()
    }
    @Test fun mismatchedCancelDtoCannotReplaceDisplayedOrder() {
        wrongCancelId=true;val cancel=cancelAction();compose.runOnIdle {dispatchBeforeFrame(cancel)}
        await {posts("/instant/orders/91/cancel")==1};pump(2000)
        compose.onNodeWithText("Ищем машину").assertExists();compose.onAllNodesWithText("Заказ отменён").assertCountEquals(0)
    }
    @Test fun terminalPollRetiresPreviouslyCapturedReason() {
        val cancel=cancelAction();status="cancelled"
        await {compose.onAllNodesWithText("Заказ отменён").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle {dispatchBeforeFrame(cancel)};pump();assertEquals(0,posts("/instant/orders/91/cancel"))
    }
    @Test fun currentScheduledPickerCreatesOneScheduledOrderForItsOwner() {
        val schedule=scheduledAction();compose.runOnIdle {dispatchBeforeFrame(schedule)}
        await {posts("/instant/schedule")==1 && posts("/places/recent")==1}
        assertEquals("Bearer $tokenA",records.single {it.second=="/instant/schedule"}.third)
        await {compose.onAllNodesWithText("Мои предзаказы").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun retainedScheduledPickerCannotCreateUnderNextAccount() {
        val schedule=scheduledAction();compose.runOnIdle {ApiClient.saveToken(tokenB);dispatchBeforeFrame(schedule)};pump(1500)
        assertEquals(0,posts("/instant/schedule"));assertEquals(0,posts("/instant/orders"));assertEquals(0,posts("/places/recent"))
    }
    @Test fun retainedScheduledPickerCannotCreateAfterLogout() {
        val schedule=scheduledAction();compose.runOnIdle {ApiClient.logout();dispatchBeforeFrame(schedule)};pump(1500)
        assertEquals(0,posts("/instant/schedule"));assertEquals(0,posts("/instant/orders"))
    }

}
