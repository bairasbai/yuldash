package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.InstantReceiptDto
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual shared card and receipt binding, local HTTP only. No payment provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,qualifiers="w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PayOnlineOwnerBoundaryTest {
    @get:Rule val compose=createComposeRule()
    private val tokenA="header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB="header.eyJzdWIiOiIxMiJ9.signature"
    private val mounted=mutableStateOf(true);private val slot=mutableStateOf(0);private val target=mutableStateOf(91)
    private lateinit var server:MockWebServer
    private val records=CopyOnWriteArrayList<Triple<String,String,String?>>()
    private val invocations=CopyOnWriteArrayList<Long>()
    private val started=CountDownLatch(1);private val release=CountDownLatch(1);private val returned=CountDownLatch(1)
    private var pending=false;private var wrongStatusId=false;private var reject=false;private var held=false
    private var receiptScreen=false;private var heldHealth=false;private var refreshOnce=false
    private val renewed="header.eyJzdWIiOiIxMSJ9.renewed"
    @Before fun setup() {
        ApiClient.resetForTest();ApiClient.init(ApplicationProvider.getApplicationContext<Context>());ApiClient.saveToken(tokenA)
        OnlinePayGate.asked=false;OnlinePayGate.unavailable=false
        server=MockWebServer().apply {
            dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    val path=request.requestUrl!!.encodedPath;records+=Triple(request.method!!,path,request.getHeader("Authorization"))
                    return when {
                        path=="/auth/verify" -> json("""{"access_token":"$tokenA","refresh_token":"local-refresh-1"}""")
                        path=="/auth/refresh" -> json("""{"access_token":"$renewed","refresh_token":"local-refresh-2"}""")
                        path=="/health" -> {
                            if(heldHealth) {started.countDown();assertTrue(release.await(35,TimeUnit.SECONDS));returned.countDown()}
                            json("""{"payments":"yookassa"}""")
                        }
                        path.endsWith("/pay") -> {
                            if(held) {started.countDown();assertTrue(release.await(35,TimeUnit.SECONDS));returned.countDown()}
                            if(refreshOnce && request.getHeader("Authorization")=="Bearer $tokenA") json("""{"detail":"expired"}""",401)
                            else if(reject) json("""{"detail":"Контрольный отказ оплаты"}""",503)
                            else json("""{"status":"${if(pending) "pending" else "paid"}","method":"card","payment_id":31}""")
                        }
                        path=="/payments/31/status" -> json("""{"payment_id":${if(wrongStatusId) 32 else 31},"status":"succeeded","purpose":"trip"}""")
                        else -> json("""{"items":[]}""")
                    }
                }
            };start()
        }
        ApiClient.testBaseUrl=server.url("/").toString().trimEnd('/');ApiClient.testTimeoutMs=60000
    }
    private fun json(body:String,code:Int=200)=MockResponse().setResponseCode(code).setHeader("Content-Type","application/json").setBody(body)
    private fun pump(ms:Long=500) {repeat((ms/100).toInt()) {Snapshot.sendApplyNotifications();compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)};compose.waitForIdle()}
    private fun await(check:()->Boolean)=compose.waitUntil(15000) {Snapshot.sendApplyNotifications();compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();check()}
    private fun receipt(id:Int)=InstantReceiptDto(orderId=id,role="passenger",fromText="Пункт А",toText="Пункт Б",doneAt="2026-10-10T10:00:00Z",distanceKm=4.0,amount=250,amountKop=25000,waitingFeeKop=0,paymentMethod="card",paid=false,driverName="Водитель",driverVerified=true)
    private fun mount() {
        compose.mainClock.autoAdvance=false
        compose.setContent {if(mounted.value) key(slot.value) {YuldashTheme {
            if(receiptScreen) TaxiReceiptScreen(orderId=target.value,onBack={},initialReceipt=receipt(target.value),loadRemote=false)
            else PayOnlineCard(amountKop=25000,pay={method, owner -> invocations+=ApiClient.queueSessionGeneration();ApiClient.payInstantOrder(91,method,expectedGeneration=owner)})
        }}}
        pump()
    }
    private fun retained(text:String):()->Boolean {
        await {compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
        if(receiptScreen) compose.onNodeWithText(text).performScrollTo()
        return compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    private fun dispatch(action:()->Boolean) {action();Shadows.shadowOf(Looper.getMainLooper()).idle()}
    private fun start():()->Boolean {mount();return retained("Оплатить 250 ₽")}
    private fun waiting():()->Boolean {pending=true;val pay=start();compose.runOnIdle {dispatch(pay)};return retained("Проверить оплату")}
    @After fun cleanup() {
        release.countDown();compose.runOnIdle {mounted.value=false};pump(300)
        OnlinePayGate.asked=false;OnlinePayGate.unavailable=false;ApiClient.resetForTest();ApiClient.testTimeoutMs=null;server.shutdown()
    }
    @Test fun currentOwnerPaysOnceAndSeesPaid() {
        val pay=start();compose.runOnIdle {dispatch(pay)};await {compose.onAllNodesWithText("Оплачено — спасибо!").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(1,records.count {it.second=="/instant/orders/91/pay"});assertEquals("Bearer $tokenA",records.single {it.second.endsWith("/pay")}.third)
    }
    @Test fun retainedPayCannotInvokeUnderNextAccountBeforeFrame() {
        val pay=start();compose.runOnIdle {ApiClient.saveToken(tokenB);dispatch(pay)};pump(1500)
        assertTrue(invocations.isEmpty());assertFalse(records.any {it.second.endsWith("/pay")})
    }
    @Test fun retainedPayCannotInvokeAfterLogoutBeforeFrame() {
        val pay=start();compose.runOnIdle {ApiClient.logout();dispatch(pay)};pump(1500);assertTrue(invocations.isEmpty())
    }
    @Test fun disposedCardCannotInvokePayOnRemountedCard() {
        val pay=start();compose.runOnIdle {slot.value++};pump();compose.runOnIdle {dispatch(pay)};pump();assertTrue(invocations.isEmpty())
    }
    @Test fun retainedCheckCannotReadPreviousPaymentAsNextOwner() {
        val check=waiting();compose.runOnIdle {ApiClient.saveToken(tokenB);dispatch(check)};pump(1500)
        assertFalse(records.any {it.second=="/payments/31/status"})
    }
    @Test fun wrongPaymentIdCannotConfirmPaid() {
        wrongStatusId=true;val check=waiting();compose.runOnIdle {dispatch(check)};await {records.any {it.second=="/payments/31/status"}};pump(2000)
        compose.onAllNodesWithText("Оплачено — спасибо!").assertCountEquals(0);compose.onNodeWithText("Проверить оплату").assertExists()
    }
    @Test fun matchingPaymentIdConfirmsPaid() {
        val check=waiting();compose.runOnIdle {dispatch(check)};await {compose.onAllNodesWithText("Оплачено — спасибо!").fetchSemanticsNodes().isNotEmpty()}
        assertEquals("Bearer $tokenA",records.single {it.second=="/payments/31/status"}.third)
    }
    @Test fun previousOwnerUnavailableGateDoesNotHideNewOwner() {
        reject=true;val pay=start();compose.runOnIdle {dispatch(pay)};await {OnlinePayGate.unavailable}
        reject=false;compose.runOnIdle {ApiClient.saveToken(tokenB);slot.value++};pump(1500)
        compose.onNodeWithText("Оплатить 250 ₽").assertExists();assertEquals(2,records.count {it.second=="/health"})
    }
    @Test fun duplicatePayWhileHeldSendsOnlyOneRequest() {
        held=true;val pay=start();compose.runOnIdle {dispatch(pay);dispatch(pay)};await {started.count==0L};pump(500)
        assertEquals(1,records.count {it.second.endsWith("/pay")});release.countDown();assertTrue(returned.await(8,TimeUnit.SECONDS))
    }
    @Test fun receiptTargetChangeResetsOldPaymentAndRetiresCheck() {
        receiptScreen=true;val check=waiting();compose.runOnIdle {target.value=92};pump(1500)
        compose.onAllNodesWithText("Проверить оплату").assertCountEquals(0);compose.onNodeWithText("Оплатить 250 ₽").assertExists()
        compose.runOnIdle {dispatch(check)};pump();assertFalse(records.any {it.second=="/payments/31/status"})
    }
    @Test fun heldOldPayCannotDisableFreshOwnerCard() {
        held=true;reject=true;val pay=start();compose.runOnIdle {dispatch(pay)};await {started.count==0L}
        compose.runOnIdle {ApiClient.saveToken(tokenB);slot.value++};pump();release.countDown();assertTrue(returned.await(8,TimeUnit.SECONDS));pump(1500)
        compose.onNodeWithText("Оплатить 250 ₽").assertExists();assertFalse(OnlinePayGate.unavailable)
    }
    @Test fun retainedIdlePayCannotStartSecondPaymentWhileWaiting() {
        pending=true;val pay=start();compose.runOnIdle {dispatch(pay)};retained("Проверить оплату")
        compose.runOnIdle {dispatch(pay)};pump(1500);assertEquals(1,records.count {it.second.endsWith("/pay")})
    }
    @Test fun retainedWaitingCheckCannotRunAfterChoosingAnotherMethod() {
        val check=waiting();compose.onNodeWithText("Выбрать другой способ").performClick();retained("Оплатить 250 ₽")
        compose.runOnIdle {dispatch(check)};pump(1500);assertFalse(records.any {it.second=="/payments/31/status"})
    }
    @Test fun lateEnabledHealthCannotUndoCurrentOwnerPay503() {
        heldHealth=true;reject=true;val pay=start();await {started.count==0L}
        compose.runOnIdle {dispatch(pay)};await {OnlinePayGate.unavailable}
        release.countDown();assertTrue(returned.await(8,TimeUnit.SECONDS));pump(2000)
        assertTrue(OnlinePayGate.unavailable);compose.onAllNodesWithText("Оплатить 250 ₽").assertCountEquals(0)
    }
    @Test fun real401RefreshKeepsMountedOwnerAndCompletesPayment() {
        runBlocking {ApiClient.verifyCode("70000000000","0000","Local payment user").getOrThrow()}
        val owner=ApiClient.queueSessionGeneration();refreshOnce=true;val pay=start();compose.runOnIdle {dispatch(pay)}
        await {compose.onAllNodesWithText("Оплачено — спасибо!").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(owner,ApiClient.queueSessionGeneration());assertEquals(1,records.count {it.second=="/auth/refresh"})
        assertEquals(listOf("Bearer $tokenA","Bearer $renewed"),records.filter {it.second.endsWith("/pay")}.map {it.third})
    }

    @Test fun retainedWaitingResetCannotReopenPaidOrStartAnotherPayment() {
        pending=true;val pay=start();compose.runOnIdle {dispatch(pay)}
        val choose=retained("Выбрать другой способ");val check=retained("Проверить оплату")
        compose.runOnIdle {dispatch(check)}
        await {compose.onAllNodesWithText("Оплачено — спасибо!").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle {dispatch(choose);dispatch(pay)};pump(1500)
        assertEquals(1,records.count {it.second.endsWith("/pay")})
        compose.onNodeWithText("Оплачено — спасибо!").assertExists();compose.onAllNodesWithText("Оплатить 250 ₽").assertCountEquals(0)
    }

}
