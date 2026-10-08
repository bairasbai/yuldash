package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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

/** Actual Activity/default VM/App with bounded HTTP observation; synthetic sessions, no device/FCM. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,qualifiers="w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LegacyNotificationPrivacyFlowTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val tokenA="header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB="header.eyJzdWIiOiIxMiJ9.signature"
    private val actor by lazy { Robolectric.buildActivity(MainActivity::class.java).create() }
    private lateinit var vm:YuldashViewModel
    private lateinit var server:MockWebServer
    private val mounted=mutableStateOf(true)
    private val records=CopyOnWriteArrayList<Triple<String,String,String?>>()
    @Before fun setup() {
        ApiClient.resetForTest();ApiClient.init(context);ApiClient.saveToken(tokenA);clear()
        context.getSharedPreferences("yuldash_prefs",Context.MODE_PRIVATE).edit().putBoolean("onboarding_completed",false).commit()
        server=MockWebServer().apply {
            dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    records+=Triple(request.method!!,request.requestUrl!!.encodedPath,request.getHeader("Authorization"))
                    return MockResponse().setHeader("Content-Type","application/json").setBody("""{"items":[],"unread":0}""")
                }
            };start()
        }
        ApiClient.testBaseUrl=server.url("/").toString().trimEnd('/');ApiClient.testTimeoutMs=60000
        vm=ViewModelProvider(actor.get())[YuldashViewModel::class.java];vm.screen.value=Screen.Notifications;vm.persistNav()
    }
    private fun clear() {
        DeepLink.pendingBookingChatId.value=null;DeepLink.pendingCompletedBookingId.value=null;DeepLink.pendingRideId.value=null
        NavSignals.openInstantChat.value=0;NavSignals.openInstantOrder.value=false;NavSignals.openDriverCabinet.value=false
        DeepLink.pendingParcels.value=false;DeepLink.pendingSupport.value=false;NavSignals.openAdsCabinet.value=false;NavSignals.openPartnerCabinet.value=false
        DeepLink.pendingFairness.value=false;DeepLink.pendingRequestResponsesId.value=null;DeepLink.pendingRequestsFeed.value=false;DeepLink.pendingApplicationScreen.value=null
    }
    private fun pump(ms:Long=500) {
        repeat((ms/100).toInt()) { compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));Thread.sleep(10) }
        compose.waitForIdle()
    }
    private fun mount() {
        compose.mainClock.autoAdvance=false
        compose.setContent { if(mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get()) { YuldashTheme { YuldashApp() } } };pump()
    }
    private fun deliver(recipient:String?=null) = compose.runOnIdle {
        val i=Intent(context,MainActivity::class.java).putExtra("type","order_chat").putExtra("id","44")
            .putExtra(MainActivity.EXTRA_NAVIGATION_DELIVERY_ID,"unspent-legacy-flow")
        recipient?.let {i.putExtra("recipient_user_id",it)}
        MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply {isAccessible=true}.invoke(actor.get(),i)
    }
    private fun remainsNotificationsWithoutPrivateRead() {
        pump(1500);assertEquals(Screen.Notifications,vm.screen.value);assertNull(vm.pendingScreenNavigation.value)
        assertFalse(vm.hasHandledNotificationDelivery("unspent-legacy-flow"))
        assertFalse(records.any {it.second=="/instant/orders/44/messages"})
        compose.onNodeWithText("Уведомления").assertExists()
        assertEquals(0,NavSignals.openInstantChat.value)
    }
    private fun accepted(recipient:String,token:String) {
        deliver(recipient)
        compose.waitUntil(12000) {
            compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle()
            vm.screen.value==Screen.InstantChat && vm.pendingScreenNavigation.value==null &&
                records.any {it.first=="GET" && it.second=="/instant/orders/44/messages" && it.third=="Bearer $token"}
        }
        pump();compose.onNodeWithText("Чат с водителем").assertExists();assertEquals(44,vm.instantChatOrderId.value)
        assertTrue(vm.hasHandledNotificationDelivery("unspent-legacy-flow"))
        // Login/logout and language sync are independent account actions, not private navigation writes.
        assertFalse("Unexpected private write: ${records.map {it.first to it.second}}",
            records.any {it.first!="GET" && it.second.startsWith("/instant/orders/")})
    }
    @After fun cleanup() {
        compose.runOnIdle {mounted.value=false};pump(300);actor.destroy();clear();ApiClient.resetForTest();ApiClient.testTimeoutMs=null;server.shutdown()
    }
    @Test fun missingRecipientDoesNotLoadAndSameMarkerValidOwnerDeliveryWorks() {
        mount();deliver();remainsNotificationsWithoutPrivateRead();accepted("11",tokenA)
    }
    @Test fun missingRecipientAfterAccountSwitchCannotLoadOldOrder() {
        ApiClient.saveToken(tokenB);mount();deliver();remainsNotificationsWithoutPrivateRead();accepted("12",tokenB)
    }
    @Test fun guestDeliveryDoesNotBindAfterLogin() {
        ApiClient.logout();vm.screen.value=Screen.Login;mount();deliver();pump()
        assertEquals(Screen.Login,vm.screen.value);assertNull(vm.pendingScreenNavigation.value)
        assertFalse(vm.hasHandledNotificationDelivery("unspent-legacy-flow"))
        compose.runOnIdle {ApiClient.saveToken(tokenA);vm.screen.value=Screen.Notifications}
        remainsNotificationsWithoutPrivateRead();accepted("11",tokenA)
    }
}
