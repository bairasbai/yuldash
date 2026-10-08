package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.FcmService
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Actual Activity/default VM and observed current Bundle; synthetic extras, no live FCM or OS kill. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class LegacyNotificationIntentTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val controllers=mutableListOf<ActivityController<MainActivity>>()
    private val tokenA="header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB="header.eyJzdWIiOiIxMiJ9.signature"
    private val marker=MainActivity.EXTRA_NAVIGATION_DELIVERY_ID
    private val privateKinds=listOf("booking","chat","booking_done","order_chat","instant","instant_status","instant_payment","instant_im_coming",
        "support","debt","ad","partner","incident","request","request_watch","taxi_apply","courier_apply","parcel_chat","flag_offer","flag_order","flag_parcels")
    @Before fun setup() { ApiClient.resetForTest();ApiClient.init(context);ApiClient.saveToken(tokenA);clear() }
    @After fun cleanup() { controllers.reversed().forEach {it.destroy()};clear();ApiClient.resetForTest() }
    private fun clear() {
        DeepLink.pendingBookingChatId.value=null;DeepLink.pendingCompletedBookingId.value=null;DeepLink.pendingRideId.value=null
        NavSignals.openInstantChat.value=0;NavSignals.openInstantOrder.value=false;NavSignals.openDriverCabinet.value=false
        DeepLink.pendingParcels.value=false;DeepLink.pendingSupport.value=false;NavSignals.openAdsCabinet.value=false;NavSignals.openPartnerCabinet.value=false
        DeepLink.pendingFairness.value=false;DeepLink.pendingRequestResponsesId.value=null;DeepLink.pendingRequestsFeed.value=false;DeepLink.pendingApplicationScreen.value=null
    }
    private fun intent(kind:String,recipient:String?=null,delivery:String?=null,id:Int=44):Intent = Intent(context,MainActivity::class.java).apply {
        when(kind) {
            "flag_offer" -> putExtra(TaxiOfferNotifier.EXTRA_OPEN_OFFER,true)
            "flag_order" -> putExtra(TaxiOfferNotifier.EXTRA_OPEN_ORDER,true)
            "flag_parcels" -> putExtra(FcmService.EXTRA_OPEN_PARCELS,true)
            else -> putExtra("type",kind)
        }
        putExtra("id",id.toString());recipient?.let {putExtra("recipient_user_id",it)};delivery?.let {putExtra(marker,it)}
    }
    private fun create(i:Intent=Intent(context,MainActivity::class.java),state:Bundle?=null)=
        Robolectric.buildActivity(MainActivity::class.java,i).also {controllers+=it}.create(state)
    private fun vm(c:ActivityController<MainActivity>)=ViewModelProvider(c.get())[YuldashViewModel::class.java]
    private fun save(c:ActivityController<MainActivity>)=Bundle().also {c.saveInstanceState(it)}
    private fun deliver(c:ActivityController<MainActivity>,i:Intent) {
        MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply {isAccessible=true}.invoke(c.get(),i)
    }
    private fun assertEmpty(c:ActivityController<MainActivity>) {
        assertNull(vm(c).pendingBookingNavigation.value);assertNull(vm(c).pendingScreenNavigation.value);assertEquals(0L,vm(c).privateNavigationRevision)
        assertNull(DeepLink.pendingBookingChatId.value);assertNull(DeepLink.pendingCompletedBookingId.value);assertEquals(0,NavSignals.openInstantChat.value)
        assertFalse(NavSignals.openInstantOrder.value);assertFalse(NavSignals.openDriverCabinet.value);assertFalse(DeepLink.pendingSupport.value)
        assertFalse(DeepLink.pendingParcels.value);assertFalse(NavSignals.openAdsCabinet.value);assertFalse(NavSignals.openPartnerCabinet.value)
        assertFalse(DeepLink.pendingFairness.value);assertNull(DeepLink.pendingRequestResponsesId.value);assertFalse(DeepLink.pendingRequestsFeed.value);assertNull(DeepLink.pendingApplicationScreen.value)
    }
    private fun rejectedCold(kind:String) { val c=create(intent(kind));assertEmpty(c);assertEquals(Screen.Splash,vm(c).screen.value) }
    @Test fun missingRecipientBookingIsRejected()=rejectedCold("booking")
    @Test fun missingRecipientChatIsRejected()=rejectedCold("chat")
    @Test fun missingRecipientCompletedIsRejected()=rejectedCold("booking_done")
    @Test fun missingRecipientTaxiChatIsRejected()=rejectedCold("order_chat")
    @Test fun missingRecipientInstantIsRejected()=rejectedCold("instant")
    @Test fun missingRecipientTaxiStatusIsRejected()=rejectedCold("instant_status")
    @Test fun missingRecipientTaxiPaymentIsRejected()=rejectedCold("instant_payment")
    @Test fun missingRecipientTaxiComingIsRejected()=rejectedCold("instant_im_coming")
    @Test fun missingRecipientSupportIsRejected()=rejectedCold("support")
    @Test fun missingRecipientDebtIsRejected()=rejectedCold("debt")
    @Test fun missingRecipientAdIsRejected()=rejectedCold("ad")
    @Test fun missingRecipientPartnerIsRejected()=rejectedCold("partner")
    @Test fun missingRecipientIncidentIsRejected()=rejectedCold("incident")
    @Test fun missingRecipientRequestIsRejected()=rejectedCold("request")
    @Test fun missingRecipientRequestWatchIsRejected()=rejectedCold("request_watch")
    @Test fun missingRecipientTaxiApplicationIsRejected()=rejectedCold("taxi_apply")
    @Test fun missingRecipientCourierApplicationIsRejected()=rejectedCold("courier_apply")
    @Test fun missingRecipientParcelIsRejected()=rejectedCold("parcel_chat")
    @Test fun missingRecipientOfferFlagIsRejected()=rejectedCold("flag_offer")
    @Test fun missingRecipientOrderFlagIsRejected()=rejectedCold("flag_order")
    @Test fun missingRecipientParcelFlagIsRejected()=rejectedCold("flag_parcels")
    @Test fun missingRecipientCannotSpendAValidDeliveryMarker() {
        val c=create(intent("request",delivery="unverified"));assertEmpty(c);assertFalse(vm(c).hasHandledNotificationDelivery("unverified"))
        deliver(c,intent("request","11","unverified"));assertEquals(44,vm(c).pendingScreenNavigation.value?.targetId)
        assertTrue(vm(c).hasHandledNotificationDelivery("unverified"))
    }
    @Test fun missingRecipientFcmTypeAliasIsAlsoRejected() {
        val i=intent("chat").apply {removeExtra("type");removeExtra("id");putExtra(FcmService.EXTRA_PUSH_TYPE,"chat");putExtra(FcmService.EXTRA_PUSH_ID,"44")}
        assertEmpty(create(i))
    }
    private fun oldPending():ActivityController<MainActivity> = create(intent("support","11","initial",45)).also {
        vm(it).screen.value=Screen.Notifications;vm(it).navHistory.add(Screen.Home);vm(it).persistNav()
    }
    private fun unchanged(c:ActivityController<MainActivity>,revision:Long) {
        assertEquals(Screen.Notifications,vm(c).screen.value);assertEquals(listOf(Screen.Home),vm(c).navHistory.toList())
        assertEquals(Screen.SupportTickets,vm(c).pendingScreenNavigation.value?.destination);assertEquals(45,vm(c).pendingScreenNavigation.value?.targetId)
        assertEquals(11,vm(c).pendingScreenNavigation.value?.ownerId);assertEquals(revision,vm(c).privateNavigationRevision)
        assertNull(vm(c).pendingBookingNavigation.value);assertFalse(vm(c).hasHandledNotificationDelivery("unverified"))
        assertTrue(vm(c).hasHandledNotificationDelivery("initial"))
    }
    @Test fun missingRecipientOnNewIntentKeepsCurrentPendingAndRevision() {
        val c=oldPending();val revision=vm(c).privateNavigationRevision
        deliver(c,intent("chat",delivery="unverified"));unchanged(c,revision)
    }
    @Test fun missingRecipientFreshCreateKeepsObservedSavedPending() {
        val c=oldPending();val revision=vm(c).privateNavigationRevision;val state=save(c);clear()
        unchanged(create(intent("chat",delivery="unverified"),state),revision)
    }
    @Test fun missingRecipientIsNotReboundToTheNextAccount() {
        val old=intent("request");ApiClient.saveToken(tokenB);val c=create(old);assertEmpty(c)
        ApiClient.saveToken(tokenA);deliver(c,Intent(old));assertEmpty(c)
    }
    @Test fun missingRecipientGuestCannotCreateAnUnboundPrivateRecord() {
        ApiClient.logout();val c=create(intent("booking_done"));assertEmpty(c)
        ApiClient.saveToken(tokenB);deliver(c,Intent(context,MainActivity::class.java));assertEmpty(c)
    }
    @Test fun unknownLocalIdentityCannotAcceptAnExplicitRecipient() {
        ApiClient.saveToken("opaque-session");assertEmpty(create(intent("request","11")))
    }
    private fun destination(kind:String)=when(kind) {
        "order_chat" -> Screen.InstantChat
        "debt","flag_offer" -> Screen.DriverCabinet
        "instant","instant_status","instant_payment","instant_im_coming","flag_order" -> Screen.InstantOrder
        "parcel_chat","flag_parcels" -> Screen.Parcels
        "support" -> Screen.SupportTickets
        "ad" -> Screen.AdsCabinet
        "partner" -> Screen.PartnerCabinet
        "incident" -> Screen.FairnessCenter
        "request" -> Screen.RequestResponses
        "request_watch" -> Screen.RequestsFeed
        "taxi_apply" -> Screen.TaxiOnboarding
        "courier_apply" -> Screen.CourierOnboarding
        else -> null
    }
    private fun accepted(c:ActivityController<MainActivity>,kind:String) {
        if(kind in setOf("booking","chat","booking_done")) {
            assertEquals(44,vm(c).pendingBookingNavigation.value?.bookingId);assertEquals(11,vm(c).pendingBookingNavigation.value?.ownerId)
            assertEquals(kind=="booking_done",vm(c).pendingBookingNavigation.value?.completed)
        } else {assertEquals(destination(kind),vm(c).pendingScreenNavigation.value?.destination);assertEquals(44,vm(c).pendingScreenNavigation.value?.targetId);assertEquals(11,vm(c).pendingScreenNavigation.value?.ownerId)}
        assertTrue(vm(c).privateNavigationRevision>0)
    }
    @Test fun explicitSameRecipientLegacyColdStartSupportsEveryPrivateKind() {
        for(kind in privateKinds) {clear();accepted(create(intent(kind,"11")),kind)}
    }
    @Test fun explicitSameRecipientLegacyOnNewIntentSupportsEveryPrivateKind() {
        for(kind in privateKinds) {clear();val c=create();vm(c).screen.value=Screen.Notifications;deliver(c,intent(kind,"11"));accepted(c,kind)}
    }
    private fun restoredRoute(destination:Screen) {
        val c=create();vm(c).screen.value=destination;vm(c).startHomeTab.value=HomeTab.Rides
        if(destination==Screen.RequestResponses)vm(c).responsesRequestId.value=43
        vm(c).navHistory.add(Screen.Home);vm(c).persistNav();val state=save(c);clear()
        val next=create(intent("chat","11"),state)
        assertEquals("An unidentified task intent cannot replace a meaningful restored route",destination,vm(next).screen.value)
        assertEquals(HomeTab.Rides,vm(next).startHomeTab.value);assertEquals(listOf(Screen.Home),vm(next).navHistory.toList())
        assertNull(vm(next).pendingBookingNavigation.value);assertNull(vm(next).pendingScreenNavigation.value);assertEquals(0L,vm(next).privateNavigationRevision)
        if(destination==Screen.RequestResponses)assertEquals(43,vm(next).responsesRequestId.value)
    }
    @Test fun unmarkedPrivateCopyDoesNotOverrideSavedHomeWithoutBooking()=restoredRoute(Screen.Home)
    @Test fun unmarkedPrivateCopyDoesNotOverrideSavedSupportTickets()=restoredRoute(Screen.SupportTickets)
    @Test fun unmarkedPrivateCopyDoesNotOverrideSavedNamedRequest()=restoredRoute(Screen.RequestResponses)
    @Test fun identifiedSameOwnerDeliveryStillOverridesSavedHome() {
        val c=create();vm(c).screen.value=Screen.Home;vm(c).persistNav();val state=save(c);clear()
        accepted(create(intent("chat","11","actually-new"),state),"chat")
    }
    @Test fun publicRideUriRemainsAvailableWithoutRecipientAfterRestore() {
        val c=create();vm(c).screen.value=Screen.Home;vm(c).persistNav();val state=save(c);clear();ApiClient.logout()
        val next=create(Intent(Intent.ACTION_VIEW,Uri.parse("https://yulbash.ru/r/77"),context,MainActivity::class.java),state)
        assertEquals(77,DeepLink.pendingRideId.value);assertNull(vm(next).pendingBookingNavigation.value);assertNull(vm(next).pendingScreenNavigation.value)
    }
    @Test fun rejectingPrivateExtrasDoesNotDiscardTheSeparatePublicUri() {
        ApiClient.logout();val c=create(intent("chat").setData(Uri.parse("https://yulbash.ru/r/77")))
        assertEmpty(c);assertEquals(77,DeepLink.pendingRideId.value)
    }
    @Test fun malformedForeignOrWronglyTypedRecipientsDoNotSpendTheMarker() {
        for(value in listOf<Any>("","invalid","0","-1","12",11)) {
            clear();val i=intent("request",delivery="bad-owner").apply {if(value is Int)putExtra("recipient_user_id",value) else putExtra("recipient_user_id",value as String)}
            val c=create(i);assertEmpty(c);assertFalse(vm(c).hasHandledNotificationDelivery("bad-owner"))
        }
    }
    @Test fun invalidLegacyTargetKeepsExistingOwnedPending() {
        val c=oldPending();val revision=vm(c).privateNavigationRevision;deliver(c,intent("request","11",id=0));unchanged(c,revision)
    }
    @Test fun legacyOnNewIntentHasNoInventedDeliveryDeduplication() {
        val c=create(intent("chat","11"));val revision=vm(c).privateNavigationRevision
        deliver(c,intent("chat","11"));accepted(c,"chat");assertTrue(vm(c).privateNavigationRevision>revision)
    }
}
