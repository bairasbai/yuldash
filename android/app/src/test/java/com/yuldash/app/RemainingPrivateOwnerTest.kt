package com.yuldash.app

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.FcmService
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Retained already shown contentIntent, synthetic message; real FCM delivery is not exercised. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class RemainingPrivateOwnerTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val tokenA="header.eyJzdWIiOiIxMSJ9.signature";private val tokenB="header.eyJzdWIiOiIxMiJ9.signature"
    private val manager get()=context.getSystemService(NotificationManager::class.java)
    @Before fun setup() { shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);ApiClient.resetForTest();ApiClient.init(context);ApiClient.saveToken(tokenA);AppPrefs.setNotifications(context,true);manager.cancelAll();clear() }
    @After fun cleanup() { manager.cancelAll();clear();ApiClient.resetForTest() }
    private fun clear() { NavSignals.openAdsCabinet.value=false;NavSignals.openPartnerCabinet.value=false;DeepLink.pendingFairness.value=false;DeepLink.pendingRequestsFeed.value=false;DeepLink.pendingRequestResponsesId.value=null;DeepLink.pendingApplicationScreen.value=null }
    private fun shown(type:String):Intent {
        val c=Robolectric.buildService(FcmService::class.java).create()
        c.get().onMessageReceived(RemoteMessage.Builder("local-only").addData("recipient_user_id","11").addData("title","Local event").addData("type",type).addData("id","43").build())
        val notification=shadowOf(manager).allNotifications.single();c.destroy();return Intent(shadowOf(notification.contentIntent).savedIntent)
    }
    private fun dispatch(i:Intent) { val c=Robolectric.buildActivity(MainActivity::class.java).create();MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply { isAccessible=true }.invoke(c.get(),i);c.destroy() }
    private fun none() { assertFalse(NavSignals.openAdsCabinet.value);assertFalse(NavSignals.openPartnerCabinet.value);assertFalse(DeepLink.pendingFairness.value);assertFalse(DeepLink.pendingRequestsFeed.value);assertNull(DeepLink.pendingRequestResponsesId.value);assertNull(DeepLink.pendingApplicationScreen.value) }
    private fun foreign(type:String) { val i=shown(type);ApiClient.saveToken(tokenB);dispatch(i);none() }
    @Test fun shownAdRejectsNextAccount()=foreign("ad")
    @Test fun shownPartnerRejectsNextAccount()=foreign("partner")
    @Test fun shownFairnessRejectsNextAccount()=foreign("incident")
    @Test fun shownRequestRejectsNextAccount()=foreign("request")
    @Test fun shownRequestWatchRejectsNextAccount()=foreign("request_watch")
    @Test fun shownTaxiApplicationRejectsNextAccount()=foreign("taxi_apply")
    @Test fun shownCourierApplicationRejectsNextAccount()=foreign("courier_apply")
    @Test fun sameRecipientShownRequestKeepsId() { dispatch(shown("request"));assertEquals(43,DeepLink.pendingRequestResponsesId.value) }
    @Test fun shownApplicationAfterLogoutNotUnbound() { val i=shown("taxi_apply");ApiClient.logout();dispatch(i);none() }
}
