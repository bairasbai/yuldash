package com.yuldash.app

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real receipt + local HTTP. Retained actions are invoked after ownership changes, before recomposition too. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,qualifiers="w411dp-h1600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiptOwnerBoundaryTest {
    @get:Rule val compose=createComposeRule()
    private val app:Application get()=ApplicationProvider.getApplicationContext()
    private val tokenA="header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB="header.eyJzdWIiOiIxMiJ9.signature"
    private val mounted=mutableStateOf(true)
    private val booking=mutableStateOf(42)
    private val records=CopyOnWriteArrayList<Triple<String,String,String?>>()
    private lateinit var server:MockWebServer
    private val held=CountDownLatch(1);private val release=CountDownLatch(1);private val returned=CountDownLatch(1)
    private val holdClaimed=AtomicBoolean()
    @Volatile private var holdPath=""
    @Volatile private var heldStatus=200
    @Volatile private var receiptStatus=200
    @Volatile private var pickupRaw="\"Ургаза\""
    @Volatile private var dropoffRaw="\"Бурибай\""

    @Before fun setup() {
        ApiClient.resetForTest();ApiClient.init(app);ApiClient.saveToken(tokenA);ShadowToast.reset()
        while(Shadows.shadowOf(app).nextStartedActivity!=null) { /* only test setup intents */ }
        server=MockWebServer().apply {
            dispatcher=object:Dispatcher(){override fun dispatch(q:RecordedRequest):MockResponse {
                val path=q.requestUrl!!.encodedPath;records+=Triple(q.method!!,path,q.getHeader("Authorization"))
                val id=path.split('/').getOrNull(2)?.toIntOrNull()?:42
                val isReceipt=path.startsWith("/trips/") && path.endsWith("/receipt")
                val code=if(q.getHeader("Authorization")=="Bearer $tokenB" && isReceipt)403 else if(isReceipt)receiptStatus else 200
                val body=if(isReceipt) receipt(id) else if(path.endsWith("/tip")) """{"already_thanked":false}""" else "{}"
                if(path==holdPath && holdClaimed.compareAndSet(false,true)) {
                    held.countDown()
                    if(!release.await(35,TimeUnit.SECONDS))return MockResponse().setResponseCode(503).setBody("{}")
                    returned.countDown()
                    return MockResponse().setResponseCode(heldStatus).setBody(body)
                }
                return MockResponse().setResponseCode(code).setHeader("Content-Type","application/json").setBody(body)
            }};start()
        }
        ApiClient.testBaseUrl=server.url("/").toString().trimEnd('/');ApiClient.testTimeoutMs=60000
    }
    private fun receipt(id:Int)="""{"booking_id":$id,"ride_id":9,"role":"passenger","from_city":"Сибай","to_city":"Акъяр","depart_at":"2026-10-15T10:00:00Z","seats":1,"amount":300,"pay_method":"cash","paid":false,"driver_name":"Водитель$id","driver_verified":true,"my_stars":5,"pickup_stop":$pickupRaw,"dropoff_stop":$dropoffRaw}"""
    @After fun cleanup() {
        release.countDown();compose.runOnIdle{mounted.value=false};pump(200)
        ApiClient.resetForTest();ApiClient.testTimeoutMs=null;server.shutdown()
    }
    private fun mount() {
        compose.mainClock.autoAdvance=false
        compose.setContent{CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru){YuldashTheme{if(mounted.value)TripReceiptScreen(booking.value,{})}}}
    }
    private fun pump(ms:Long=700) {
        repeat((ms/100).toInt().coerceAtLeast(1)){Snapshot.sendApplyNotifications();compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
        compose.waitForIdle()
    }
    private fun await(check:()->Boolean) {
        try { compose.waitUntil(15000){Snapshot.sendApplyNotifications();compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();check()} }
        catch(e:Throwable){throw AssertionError("Receipt wait failed; HTTP=$records; tree="+compose.onRoot().printToString(),e)}
    }
    private fun shown(s:String)=compose.onAllNodesWithText(s).fetchSemanticsNodes().isNotEmpty()
    private fun loaded(id:Int=42){await{shown("Водитель$id")}}
    private fun retain(s:String):()->Boolean {
        await{compose.onAllNodes(hasText(s) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()}
        val node=compose.onAllNodes(hasText(s) and hasClickAction()).onFirst();node.performScrollTo()
        return node.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    private fun count(method:String,path:String)=records.count{it.first==method && it.second==path}
    private fun assertNoOldActions() {
        assertFalse("Old receipt actions issued HTTP: $records",records.any{it.first=="POST" && it.second in setOf("/bookings/42/lost-item","/bookings/42/thanks")})
        assertNull("Old receipt action launched an Intent",Shadows.shadowOf(app).nextStartedActivity)
        assertNull("Old receipt action published a toast",ShadowToast.getTextOfLatestToast())
    }
    private fun retainedReceiptActions():List<()->Boolean> {
        loaded();return listOf(retain("Сказать рәхмәт"),retain("Я забыл вещь в машине"),retain("Поделиться"))
    }
    private fun oldActionsAfter(change:()->Unit) {
        mount();val old=retainedReceiptActions()
        compose.runOnIdle{change();old.forEach{it()}};pump()
        assertNoOldActions()
    }
    @Test fun retainedActionsRejectNewAccountBeforeHttpOrIntent()=oldActionsAfter{ApiClient.saveToken(tokenB)}
    @Test fun retainedActionsRejectFreshLoginOfSameAccountBeforeHttpOrIntent()=oldActionsAfter{ApiClient.saveToken(tokenA)}
    @Test fun retainedActionsRejectReplacementBookingBeforeHttpOrIntent() {
        mount();val old=retainedReceiptActions();compose.runOnIdle{booking.value=43};loaded(43)
        compose.runOnIdle{old.forEach{it()}};pump();assertNoOldActions()
    }
    @Test fun retainedActionsRejectUnmountBeforeHttpOrIntent() {
        mount();val old=retainedReceiptActions();compose.runOnIdle{mounted.value=false};pump(200)
        compose.runOnIdle{old.forEach{it()}};pump();assertNoOldActions()
    }
    @Test fun loadedReceiptIsRetiredWhenAccountChanges() {
        mount();loaded();compose.runOnIdle{ApiClient.saveToken(tokenB)};pump()
        compose.onNodeWithText("Водитель42").assertDoesNotExist()
    }
    @Test fun loadedReceiptIsRetiredWhenSameAccountLogsInAgain() {
        mount();loaded();receiptStatus=503
        compose.runOnIdle{ApiClient.saveToken(tokenA)}
        await{shown("Повторить")};compose.onNodeWithText("Водитель42").assertDoesNotExist()
    }
    private fun retainedRetry():()->Boolean {receiptStatus=503;mount();return retain("Повторить")}
    private fun retryAfter(change:()->Unit) {
        val old=retainedRetry();compose.runOnIdle{change()};pump()
        val before=records.size;compose.runOnIdle{old()};pump()
        assertEquals("Old retry started HTTP after owner changed: $records",before,records.size)
    }
    @Test fun retainedRetryRejectsNewAccount()=retryAfter{ApiClient.saveToken(tokenB)}
    @Test fun retainedRetryRejectsFreshLoginOfSameAccount()=retryAfter{ApiClient.saveToken(tokenA)}
    @Test fun retainedRetryRejectsReplacementBooking()=retryAfter{booking.value=43}
    @Test fun retainedRetryRejectsUnmount()=retryAfter{mounted.value=false}

    @Test fun failedRefreshRemovesPreviouslyLoadedPrivateReceipt() {
        val retry=retainedRetry();receiptStatus=200
        compose.runOnIdle{retry()};loaded();receiptStatus=503
        val previous=count("GET","/trips/42/receipt")
        compose.runOnIdle{retry()};await{count("GET","/trips/42/receipt")>previous};pump()
        compose.onNodeWithText("Водитель42").assertDoesNotExist()
        compose.onNodeWithText("Повторить").assertExists()
    }
    private fun releaseOldReceipt() {release.countDown();assertTrue(returned.await(8,TimeUnit.SECONDS));pump()}
    private fun lateReceiptAfterAccountChange(code:Int,sameAccount:Boolean) {
        holdPath="/trips/42/receipt";heldStatus=code;mount();await{held.count==0L}
        receiptStatus=503
        compose.runOnIdle{ApiClient.saveToken(if(sameAccount)tokenA else tokenB)};releaseOldReceipt()
        await{count("GET","/trips/42/receipt")>=2 && records.any{it.first=="GET" && it.second=="/trips/42/receipt" && it.third=="Bearer ${if(sameAccount)tokenA else tokenB}"} && shown("Повторить")}
        compose.onNodeWithText("Водитель42").assertDoesNotExist()
        assertNull(ShadowToast.getTextOfLatestToast())
    }
    @Test fun lateSuccessCannotPublishForNewAccount()=lateReceiptAfterAccountChange(200,false)
    @Test fun lateErrorCannotPublishForFreshLoginOfSameAccount()=lateReceiptAfterAccountChange(503,true)
    @Test fun lateSuccessCannotPublishForFreshLoginOfSameAccount()=lateReceiptAfterAccountChange(200,true)
    @Test fun lateErrorCannotPublishForNewAccount()=lateReceiptAfterAccountChange(503,false)
    private fun lateReceiptAfterBookingChange(code:Int) {
        holdPath="/trips/42/receipt";heldStatus=code;mount();await{held.count==0L}
        compose.runOnIdle{booking.value=43};loaded(43);releaseOldReceipt()
        compose.onNodeWithText("Водитель43").assertExists();compose.onNodeWithText("Водитель42").assertDoesNotExist()
        compose.onNodeWithText("Повторить").assertDoesNotExist()
    }
    @Test fun lateSuccessCannotReplaceNewBooking()=lateReceiptAfterBookingChange(200)
    @Test fun lateErrorCannotReplaceNewBooking()=lateReceiptAfterBookingChange(503)

    private fun lateActionDoesNotPublish(path:String,label:String,code:Int,sameAccount:Boolean) {
        holdPath=path;heldStatus=code;mount();loaded();val old=retain(label)
        compose.runOnIdle{old()};await{held.count==0L}
        receiptStatus=503;compose.runOnIdle{ApiClient.saveToken(if(sameAccount)tokenA else tokenB)};releaseOldReceipt()
        assertNull("Late action emitted toast after owner changed; HTTP=$records",ShadowToast.getTextOfLatestToast())
        compose.onNodeWithText("Водитель42").assertDoesNotExist()
        compose.runOnIdle{old()};pump()
        assertEquals("Old action repeated HTTP under replacement session: $records",1,count("POST",path))
    }
    @Test fun lateThanksSuccessCannotPublishForNewAccount()=lateActionDoesNotPublish("/bookings/42/thanks","Сказать рәхмәт",200,false)
    @Test fun lateLostItemErrorCannotPublishForFreshLogin()=lateActionDoesNotPublish("/bookings/42/lost-item","Я забыл вещь в машине",503,true)

    @Test fun currentShareContainsParentRouteButNoPrivateStops() {
        mount();loaded();val share=retain("Поделиться");compose.runOnIdle{share()};pump()
        val chooser=Shadows.shadowOf(app).nextStartedActivity;assertNotNull(chooser);assertEquals(Intent.ACTION_CHOOSER,chooser.action)
        val send=chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!;val text=send.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(text.contains("Сибай → Акъяр"));assertFalse(text.contains("Ургаза"));assertFalse(text.contains("Бурибай"))
    }
    @Test fun receiptShowsPersonalSegmentSeparatelyFromFullRideAndKeepsOwnRating() {
        mount();loaded()
        compose.onNodeWithText("Твой участок").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ургаза → Бурибай").assertExists()
        compose.onNodeWithText("Рейс").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Сибай → Акъяр").onFirst().assertExists()
        compose.onNodeWithText("Твоя оценка").performScrollTo().assertIsDisplayed();compose.onNodeWithText("5 звёзд").assertExists()
    }
    @Test fun legacyUnknownDropoffDoesNotInventPersonalSegment() {
        dropoffRaw="null";mount();loaded()
        compose.onNodeWithText("Твой участок").assertDoesNotExist();compose.onNodeWithText("Ургаза → Акъяр").assertDoesNotExist()
    }
}
