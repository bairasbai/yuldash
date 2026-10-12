package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Actual Activity create/save/restore before attaching Compose; native separately proves OS kill. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class MainActivityLaunchRestorationTest {
    private val controllers=mutableListOf<ActivityController<MainActivity>>()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    @Before fun setup() {
        ApiClient.resetForTest();ApiClient.init(context);ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        clearSignals()
    }
    @After fun cleanup() {controllers.reversed().forEach {it.destroy()};clearSignals();ApiClient.resetForTest()}
    private fun clearSignals() {
        DeepLink.pendingCompletedBookingId.value=null;DeepLink.pendingBookingChatId.value=null;DeepLink.pendingRideId.value=null
    }
    private fun notification(id:Int=42)=Intent(context,MainActivity::class.java).putExtra("type","booking_done").putExtra("id",id.toString()).putExtra("recipient_user_id", "11")
    private fun ride(id:Int=9)=Intent(Intent.ACTION_VIEW,Uri.parse("https://yulbash.ru/r/$id"),context,MainActivity::class.java)
    private fun create(intent:Intent,state:Bundle?=null):ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java,intent).also {controllers+=it}.create(state)
    private fun vm(c:ActivityController<MainActivity>)=ViewModelProvider(c.get())[YuldashViewModel::class.java]
    private fun saved(screen:Screen,intent:Intent=notification(),bookingId:Int?=42):Bundle {
        val c=create(intent);val model=vm(c)
        // Fixture seeds an already consumed booking route; dispatch now has durable pending.
        if(screen !in setOf(Screen.Splash,Screen.Intro,Screen.Onboarding,Screen.Login)) {
            model.pendingCompletedNavigation.value?.let { model.consumePendingCompleted(it) {} }
        }
        model.screen.value=screen;model.activeBookingId.value=bookingId;model.startHomeTab.value=HomeTab.Profile
        if(screen==Screen.TripReceipt || screen==Screen.Support) model.navHistory.add(Screen.ActiveTrip)
        model.persistNav();clearSignals()
        return Bundle().also {c.saveInstanceState(it)}
    }
    private fun newIntent(c:ActivityController<MainActivity>,intent:Intent) {
        MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply {isAccessible=true}.invoke(c.get(),intent)
    }
    @Test fun coldCompletedNotificationStillDispatches() {create(notification());assertEquals(42,DeepLink.pendingCompletedBookingId.value)}
    @Test fun coldRideLinkStillDispatches() {create(ride());assertEquals(9,DeepLink.pendingRideId.value)}
    @Test fun emptySavedBundleDoesNotDiscardInitialDestination() {create(notification(),Bundle());assertEquals(42,DeepLink.pendingCompletedBookingId.value)}
    @Test fun savedSplashKeepsPendingOriginalNotification() {val state=saved(Screen.Splash);create(notification(),state);assertEquals(42,DeepLink.pendingCompletedBookingId.value)}
    @Test fun savedLoginKeepsPendingOriginalNotification() {val state=saved(Screen.Login);create(notification(),state);assertEquals(42,DeepLink.pendingCompletedBookingId.value)}
    @Test fun savedIntroKeepsPendingOriginalNotification() {val state=saved(Screen.Intro);create(notification(),state);assertEquals(42,DeepLink.pendingCompletedBookingId.value)}
    @Test fun savedOnboardingKeepsPendingOriginalNotification() {val state=saved(Screen.Onboarding);create(notification(),state);assertEquals(42,DeepLink.pendingCompletedBookingId.value)}
    @Test fun savedHomeWithoutBookingRejectsUnmarkedPrivateCopy() {
        val state=saved(Screen.Home,intent=Intent(context,MainActivity::class.java),bookingId=null);val c=create(notification(),state)
        assertEquals(Screen.Home,vm(c).screen.value);assertNull(vm(c).pendingBookingNavigation.value);assertNull(DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun savedHomeWithBookingDoesNotReplayOldDestination() {val state=saved(Screen.Home);create(notification(),state);assertNull(DeepLink.pendingCompletedBookingId.value)}
    @Test fun restoredReceiptDoesNotReplayCopiedOriginalNotification() {
        val original=notification();val state=saved(Screen.TripReceipt,Intent(original));val c=create(Intent(original),state)
        assertEquals(Screen.TripReceipt,vm(c).screen.value);assertEquals(listOf(Screen.ActiveTrip),vm(c).navHistory)
        assertNull("Old task intent must not overwrite restored receipt",DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun restoredSupportDoesNotReplayCopiedOriginalRideUri() {
        val original=ride();val state=saved(Screen.Support,Intent(original));val c=create(Intent(original),state)
        assertEquals(Screen.Support,vm(c).screen.value);assertNull(DeepLink.pendingRideId.value)
    }
    @Test fun newNotificationStillDispatchesAfterRestoredReceipt() {
        val state=saved(Screen.TripReceipt);val c=create(Intent(context,MainActivity::class.java),state)
        newIntent(c,notification(43));assertEquals(43,DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun newRideLinkStillDispatchesAfterRestoredReceipt() {
        val state=saved(Screen.TripReceipt);val c=create(Intent(context,MainActivity::class.java),state)
        newIntent(c,ride(10));assertEquals(10,DeepLink.pendingRideId.value)
    }
}
