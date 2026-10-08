package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Real Activity/default VM/observed Bundle; process termination is a separate native check. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemainingPrivateRestorationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val controllers = mutableListOf<ActivityController<MainActivity>>()
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    @Before fun setup() { ApiClient.resetForTest(); ApiClient.init(context); ApiClient.saveToken(tokenA); clear() }
    @After fun cleanup() { controllers.reversed().forEach { it.destroy() }; clear(); ApiClient.resetForTest() }
    private fun clear() {
        NavSignals.openAdsCabinet.value=false; NavSignals.openPartnerCabinet.value=false
        NavSignals.openDriverCabinet.value=false; NavSignals.openInstantOrder.value=false; NavSignals.openInstantChat.value=0
        DeepLink.pendingFairness.value=false; DeepLink.pendingRequestsFeed.value=false
        DeepLink.pendingRequestResponsesId.value=null; DeepLink.pendingApplicationScreen.value=null
        DeepLink.pendingParcels.value=false; DeepLink.pendingSupport.value=false
        DeepLink.pendingBookingChatId.value=null; DeepLink.pendingCompletedBookingId.value=null; DeepLink.pendingRideId.value=null
    }
    private fun intent(type:String,id:Int=43) = Intent(context,MainActivity::class.java).putExtra("type",type).putExtra("id",id.toString()).putExtra("recipient_user_id", "11")
    private fun create(i:Intent=Intent(context,MainActivity::class.java),state:Bundle?=null) =
        Robolectric.buildActivity(MainActivity::class.java,i).also { controllers+=it }.create(state)
    private fun vm(c:ActivityController<MainActivity>)=ViewModelProvider(c.get())[YuldashViewModel::class.java]
    private fun deliver(c:ActivityController<MainActivity>,i:Intent) = MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply { isAccessible=true }.invoke(c.get(),i)
    private fun save(c:ActivityController<MainActivity>)=Bundle().also { c.saveInstanceState(it) }
    private fun receipt()=create().also { vm(it).screen.value=Screen.TripReceipt; vm(it).activeBookingId.value=42; vm(it).recordNavigationChange(); vm(it).persistNav() }
    private fun bridge(type:String)=when(type) {
        "ad" -> NavSignals.openAdsCabinet.value
        "partner" -> NavSignals.openPartnerCabinet.value
        "incident" -> DeepLink.pendingFairness.value
        "request" -> DeepLink.pendingRequestResponsesId.value==43
        "request_watch" -> DeepLink.pendingRequestsFeed.value
        "taxi_apply" -> DeepLink.pendingApplicationScreen.value==Screen.TaxiOnboarding
        "courier_apply" -> DeepLink.pendingApplicationScreen.value==Screen.CourierOnboarding
        else -> error(type)
    }
    private fun survives(type:String) {
        val old=receipt();deliver(old,intent(type));assertTrue("Initial dispatch $type",bridge(type))
        val state=save(old);clear();val next=create(state=state)
        assertNotSame(vm(old),vm(next));assertEquals(Screen.TripReceipt,vm(next).screen.value)
        assertTrue("Lost $type after observed Bundle restore",bridge(type))
    }
    @Test fun adSurvivesBundle()=survives("ad")
    @Test fun partnerSurvivesBundle()=survives("partner")
    @Test fun fairnessSurvivesBundle()=survives("incident")
    @Test fun requestIdSurvivesBundle()=survives("request")
    @Test fun requestWatchSurvivesBundle()=survives("request_watch")
    @Test fun taxiApplicationSurvivesBundle()=survives("taxi_apply")
    @Test fun courierApplicationSurvivesBundle()=survives("courier_apply")
    @Test fun newestPartnerClearsAd() { val c=receipt();deliver(c,intent("ad"));deliver(c,intent("partner"));assertFalse(bridge("ad"));assertTrue(bridge("partner")) }
    @Test fun newestRequestClearsBooking() { val c=receipt();deliver(c,intent("booking"));deliver(c,intent("request"));assertNull(vm(c).pendingBookingNavigation.value);assertNull(DeepLink.pendingBookingChatId.value);assertTrue(bridge("request")) }
    @Test fun newestSupportClearsRequest() { val c=receipt();deliver(c,intent("request"));deliver(c,intent("support"));assertFalse(bridge("request"));assertTrue(DeepLink.pendingSupport.value) }
    @Test fun newestBookingClearsApplication() { val c=receipt();deliver(c,intent("taxi_apply"));deliver(c,intent("booking"));assertNull(DeepLink.pendingApplicationScreen.value);assertEquals(43,DeepLink.pendingBookingChatId.value) }
    @Test fun clearImmediatelyDropsAdAndRequest() { val c=receipt();deliver(c,intent("ad"));deliver(c,intent("request"));vm(c).clearUserData();assertFalse(bridge("ad"));assertFalse(bridge("request"));assertNull(vm(c).pendingScreenNavigation.value) }
    private fun copied(type:String) {
        val original=intent(type);val c=create(Intent(original));vm(c).clearUserData();ApiClient.logout()
        vm(c).screen.value=Screen.Login;vm(c).persistNav();val state=save(c);clear();create(Intent(original),state)
        assertFalse("Copied $type returned after logout",bridge(type))
    }
    @Test fun copiedAdDoesNotReturnAfterLogout()=copied("ad")
    @Test fun copiedRequestDoesNotReturnAfterLogout()=copied("request")
    @Test fun copiedApplicationDoesNotReturnAfterLogout()=copied("courier_apply")
    @Test fun loggedOutRequestRejectedAcrossSaveAndNewAfterLoginAccepted() {
        ApiClient.logout();val c=create(intent("request"));vm(c).screen.value=Screen.Login;vm(c).persistNav()
        assertNull(vm(c).pendingScreenNavigation.value);val state=save(c);clear();val next=create(state=state);assertFalse(bridge("request"))
        ApiClient.saveToken(tokenA);deliver(next,intent("request"));assertTrue(bridge("request"))
    }
    @Test fun sameOwnerTokenReplacementKeepsAd() { val c=receipt();deliver(c,intent("ad"));val state=save(c);ApiClient.saveToken(tokenA+"new");clear();create(state=state);assertTrue(bridge("ad")) }
    @Test fun savedRequestRejectsNextAccountAndCopiedBase() { val original=intent("request");val c=create(Intent(original));vm(c).screen.value=Screen.Login;vm(c).persistNav();val state=save(c);ApiClient.saveToken(tokenB);clear();create(Intent(original),state);assertFalse(bridge("request")) }
    @Test fun freshAdWithoutBundleStillAccepted() { create(intent("ad"));assertTrue(bridge("ad")) }
    @Test fun freshApplicationOnNewIntentAcceptedOverHandledRoute() { val c=receipt();deliver(c,intent("support"));deliver(c,intent("taxi_apply"));assertTrue(bridge("taxi_apply"));assertFalse(DeepLink.pendingSupport.value) }
    @Test fun invalidRequestIdDoesNotDisplaceSupport() { val c=receipt();deliver(c,intent("support"));deliver(c,intent("request",0));assertTrue(DeepLink.pendingSupport.value);assertNull(DeepLink.pendingRequestResponsesId.value) }
    @Test fun consumedRequestIdAndBackTrailSurviveBundle() {
        val c=receipt();deliver(c,intent("request"));val v=vm(c);val p=v.pendingScreenNavigation.value
        assertNotNull("Request must have a durable pending record",p)
        assertTrue(v.consumePendingScreen(p!!) { v.responsesRequestId.value=43;v.screen.value=Screen.RequestResponses })
        val state=save(c);clear();val restored=vm(create(state=state))
        assertEquals(Screen.RequestResponses,restored.screen.value);assertEquals(43,restored.responsesRequestId.value)
        assertTrue(restored.navHistory.contains(Screen.TripReceipt));assertNull(restored.pendingScreenNavigation.value)
    }
    @Test fun consumedIncidentDetailIdAndBackTrailSurviveBundle() {
        val c=receipt();val v=vm(c);v.requestScreenDestination(Screen.IncidentDetail,11,43)
        val p=v.pendingScreenNavigation.value!!
        assertTrue(v.consumePendingScreen(p) { v.incidentId.value=43;v.screen.value=Screen.IncidentDetail })
        val state=save(c);clear();val restored=vm(create(state=state))
        assertEquals(Screen.IncidentDetail,restored.screen.value);assertEquals(43,restored.incidentId.value)
        assertTrue(restored.navHistory.contains(Screen.TripReceipt));assertNull(restored.pendingScreenNavigation.value)
    }
}
