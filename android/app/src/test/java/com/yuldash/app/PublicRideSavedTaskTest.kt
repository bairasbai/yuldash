package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
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

/** Real saved Activity tasks: outstanding public delivery belongs to its window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
class PublicRideSavedTaskTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val actors=mutableListOf<ActivityController<MainActivity>>()
    @Before fun setup() { ApiClient.resetForTest();ApiClient.init(context);ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature") }
    @After fun cleanup() { actors.reversed().forEach { it.destroy() };DeepLink.pendingBookingChatId.value=null;DeepLink.pendingCompletedBookingId.value=null;NavSignals.openInstantOrder.value=false;ApiClient.resetForTest() }
    private fun create(state:Bundle?=null,intent:Intent=Intent(context,MainActivity::class.java))=Robolectric.buildActivity(MainActivity::class.java,intent).also { actors+=it }.create(state)
    private fun vm(actor:ActivityController<MainActivity>)=ViewModelProvider(actor.get())[YuldashViewModel::class.java]
    private fun deliver(actor:ActivityController<MainActivity>,id:Int) {
        val intent=Intent(Intent.ACTION_VIEW,Uri.parse("https://yulbash.ru/r/$id"),context,MainActivity::class.java)
        MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply { isAccessible=true }.invoke(actor.get(),intent)
    }
    private fun save(actor:ActivityController<MainActivity>)=Bundle().also { actor.saveInstanceState(it) }
    @Test fun outstandingLinkSurvivesRealSavedTaskWithoutReplayingOriginalIntent() {
        val actor=create();vm(actor).screen.value=Screen.Home;vm(actor).persistNav();deliver(actor,42)
        val request=vm(actor).pendingPublicRideNavigation.value!!
        val next=create(save(actor));assertNotSame(vm(actor),vm(next))
        assertEquals(request,vm(next).pendingPublicRideNavigation.value)
        assertEquals(Screen.Home,vm(next).screen.value)
    }
    @Test fun repeatedSameLinkAfterRestoreIsANewDelivery() {
        val actor=create();vm(actor).screen.value=Screen.Home;vm(actor).persistNav();deliver(actor,42)
        val next=create(save(actor));val previous=vm(next).pendingPublicRideNavigation.value!!
        deliver(next,42);val fresh=vm(next).pendingPublicRideNavigation.value!!
        assertEquals(42,fresh.rideId);assertTrue(fresh.revision>previous.revision)
        assertEquals(previous,vm(actor).pendingPublicRideNavigation.value)
    }
    @Test fun localChoiceCancelsOnlyItsOwnWindowAndPersistsCancellation() {
        val lower=create();val upper=create();deliver(lower,42);deliver(upper,43)
        vm(lower).navigateLocally { vm(lower).screen.value=Screen.Notifications }
        assertNull(vm(lower).pendingPublicRideNavigation.value)
        assertEquals(43,vm(upper).pendingPublicRideNavigation.value?.rideId)
        val next=create(save(lower));assertNull(vm(next).pendingPublicRideNavigation.value)
        assertEquals(Screen.Notifications,vm(next).screen.value)
    }
    @Test fun privateBookingCancelsOnlyItsOwnPublicLink() {
        val lower=create();val upper=create();deliver(lower,42);deliver(upper,43)
        vm(lower).requestBookingDestination(77,11,false)
        assertNull(vm(lower).pendingPublicRideNavigation.value)
        assertEquals(77,vm(lower).pendingBookingNavigation.value?.bookingId)
        assertEquals(43,vm(upper).pendingPublicRideNavigation.value?.rideId)
    }
    @Test fun privateScreenCancelsOnlyItsOwnPublicLink() {
        val lower=create();val upper=create();deliver(lower,42);deliver(upper,43)
        vm(lower).requestScreenDestination(Screen.InstantOrder,11)
        assertNull(vm(lower).pendingPublicRideNavigation.value)
        assertEquals(Screen.InstantOrder,vm(lower).pendingScreenNavigation.value?.destination)
        assertEquals(43,vm(upper).pendingPublicRideNavigation.value?.rideId)
    }
    @Test fun invalidLinkDoesNotEraseValidOutstandingDelivery() {
        val actor=create();deliver(actor,42);val request=vm(actor).pendingPublicRideNavigation.value
        deliver(actor,0);deliver(actor,-1);assertEquals(request,vm(actor).pendingPublicRideNavigation.value)
    }
    @Test fun consumedPublicLinkDoesNotComeBackFromSavedTask() {
        val actor=create();deliver(actor,42);vm(actor).navigateLocally { vm(actor).screen.value=Screen.Support }
        val next=create(save(actor));assertNull(vm(next).pendingPublicRideNavigation.value)
        assertEquals(Screen.Support,vm(next).screen.value)
    }
    @Test fun savedCurrentViewIntentDoesNotReplayCancelledPublicLink() {
        for (screen in listOf(Screen.Home,Screen.Notifications,Screen.Support)) {
            val actor=create();deliver(actor,42)
            vm(actor).navigateLocally { vm(actor).screen.value=screen }
            val next=create(save(actor),Intent(actor.get().intent))
            assertEquals(screen,vm(next).screen.value)
            assertNull("Copied VIEW must not replay over $screen",vm(next).pendingPublicRideNavigation.value)
        }
    }
    @Test fun savedCurrentViewIntentKeepsTheSamePendingDeliveryWithoutReplay() {
        val actor=create();vm(actor).screen.value=Screen.Home;vm(actor).persistNav();deliver(actor,42)
        val pending=vm(actor).pendingPublicRideNavigation.value
        val next=create(save(actor),Intent(actor.get().intent))
        assertEquals(pending,vm(next).pendingPublicRideNavigation.value)
    }
    @Test fun savedBaseViewIntentDoesNotReplayAfterANewerWarmDelivery() {
        val base=Intent(Intent.ACTION_VIEW,Uri.parse("https://yulbash.ru/r/42"),context,MainActivity::class.java)
        val actor=create(intent=Intent(base));deliver(actor,43)
        vm(actor).navigateLocally { vm(actor).screen.value=Screen.Home }
        val next=create(save(actor),base)
        assertNull(vm(next).pendingPublicRideNavigation.value)
        deliver(next,42);assertEquals(42,vm(next).pendingPublicRideNavigation.value?.rideId)
    }
    @Test fun newDifferentViewAfterRestoreRemainsAvailableWithoutReplayOfTheOldOne() {
        val actor=create();deliver(actor,42);vm(actor).navigateLocally { vm(actor).screen.value=Screen.Home }
        val fresh=Intent(Intent.ACTION_VIEW,Uri.parse("https://yulbash.ru/r/43"),context,MainActivity::class.java)
        val next=create(save(actor),fresh);assertEquals(43,vm(next).pendingPublicRideNavigation.value?.rideId)
    }
    @Test fun manifestReusesTheTopMainActivityForExternalLinks() {
        val info=context.packageManager.getActivityInfo(android.content.ComponentName(context,MainActivity::class.java),0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP,info.launchMode)
    }
}
