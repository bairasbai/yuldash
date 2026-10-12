package com.yuldash.app

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test

/** Old named VM fields are pinned in 126bb0f6; these are schema/edge tests, not observed OS Bundles. */
class LegacyNavigationStateTest {
    private fun saved(screen:Screen)=SavedStateHandle(mapOf(
        "yuldash_screen" to screen.name,"yuldash_lang" to "Ba","yuldash_tab" to "Profile",
        "yuldash_active_bid" to 42,"yuldash_nav_history" to arrayListOf("Home","Notifications")
    ))
    private fun fallback(screen:Screen) {
        val handle=saved(screen);val vm=YuldashViewModel(handle)
        assertEquals(Screen.Notifications,vm.screen.value);assertEquals(Screen.Notifications,vm.navPrev.value)
        assertEquals("Notifications",handle.get<String>("yuldash_screen"))
        assertEquals(AppLanguage.Ba,vm.language.value);assertEquals(HomeTab.Profile,vm.startHomeTab.value)
        assertEquals(42,vm.activeBookingId.value);assertNull(vm.selectedRide.value);assertNull(vm.activeTrip.value)
        assertEquals(listOf(Screen.Home),vm.navHistory.toList())
        val again=YuldashViewModel(SavedStateHandle(handle.keys().associateWith {handle.get<Any?>(it)}))
        assertEquals(Screen.Notifications,again.screen.value);assertEquals(listOf(Screen.Home),again.navHistory.toList())
    }
    @Test fun oldTaxiChatMissingNamedIdFallsBackAndPersists()=fallback(Screen.InstantChat)
    @Test fun oldSupportTicketMissingNamedIdFallsBackAndPersists()=fallback(Screen.SupportTicket)
    @Test fun oldIncidentMissingNamedIdFallsBackAndPersists()=fallback(Screen.IncidentDetail)
    @Test fun oldTransientRequestIdFallsBackAndPersists()=fallback(Screen.RequestResponses)
    private fun valid(screen:Screen,key:String) {
        val h=saved(screen);h[key]=43;val vm=YuldashViewModel(h)
        assertEquals(screen,vm.screen.value);assertEquals(listOf(Screen.Home,Screen.Notifications),vm.navHistory.toList())
        assertEquals(AppLanguage.Ba,vm.language.value)
    }
    @Test fun currentTaxiChatNamedIdKeepsItsRoute()=valid(Screen.InstantChat,"yuldash_taxi_chat_id")
    @Test fun currentSupportNamedIdKeepsItsRoute()=valid(Screen.SupportTicket,"yuldash_support_ticket_id")
    @Test fun currentIncidentNamedIdKeepsItsRoute()=valid(Screen.IncidentDetail,"yuldash_incident_id")
    @Test fun currentRequestNamedIdKeepsItsRoute()=valid(Screen.RequestResponses,"yuldash_request_responses_id")
    @Test fun unknownOrInvalidPrivateHistoryCannotReturnToIdZero() {
        val h=saved(Screen.SupportTickets)
        h["yuldash_nav_history"]=arrayListOf("Home","InstantChat","IncidentDetail","SupportTicket","RequestResponses","RemovedScreen")
        val vm=YuldashViewModel(h)
        assertEquals(Screen.SupportTickets,vm.screen.value);assertEquals(listOf(Screen.Home),vm.navHistory.toList())
        assertEquals(arrayListOf("Home"),h.get<ArrayList<String>>("yuldash_nav_history"))
    }
    @Test fun validPrivateHistoryIsKeptEvenWhenAnotherLegacyRouteFallsBack() {
        val h=saved(Screen.SupportTicket);h["yuldash_taxi_chat_id"]=43
        h["yuldash_nav_history"]=arrayListOf("Home","InstantChat","SupportTicket")
        val vm=YuldashViewModel(h)
        assertEquals(listOf(Screen.Home,Screen.InstantChat),vm.navHistory.toList());assertEquals(43,vm.instantChatOrderId.value)
    }
    @Test fun pendingFreshDestinationAndDeliveryIdentitySurviveLegacyFallback() {
        val h=saved(Screen.InstantChat)
        h["yuldash_pending_private_screen"]="SupportTicket";h["yuldash_pending_private_target"]=45
        h["yuldash_pending_private_owner"]=11;h["yuldash_pending_completed_revision"]=7L
        h["yuldash_initial_notification_delivery"]="initial";h["yuldash_last_notification_delivery"]="fresh"
        val vm=YuldashViewModel(h)
        assertEquals(Screen.Notifications,vm.screen.value)
        val p=vm.pendingScreenNavigation.value!!
        assertEquals(Screen.SupportTicket,p.destination);assertEquals(45,p.targetId);assertEquals(11,p.ownerId);assertEquals(7L,p.revision)
        assertTrue(vm.hasHandledNotificationDelivery("initial"));assertTrue(vm.hasHandledNotificationDelivery("fresh"))
    }
    @Test fun preTrailBookingFormatDoesNotInventHistoryOrClearBooking() {
        val h=saved(Screen.Booking);h.remove<ArrayList<String>>("yuldash_nav_history")
        val vm=YuldashViewModel(h)
        assertEquals(Screen.Booking,vm.screen.value);assertEquals(42,vm.activeBookingId.value);assertTrue(vm.navHistory.isEmpty())
    }
    @Test fun legacyReceiptRouteAndBookingStayOutsideThisMigration() {
        val vm=YuldashViewModel(saved(Screen.TripReceipt))
        assertEquals(Screen.TripReceipt,vm.screen.value);assertEquals(42,vm.activeBookingId.value)
        assertEquals(listOf(Screen.Home,Screen.Notifications),vm.navHistory.toList())
    }
    @Test fun wronglyTypedNamedIdIsNotParsedOrUsedForPrivateNavigation() {
        val h=saved(Screen.InstantChat);h["yuldash_taxi_chat_id"]="43"
        val vm=YuldashViewModel(h)
        assertEquals(Screen.Notifications,vm.screen.value);assertEquals(0,vm.instantChatOrderId.value)
    }
    @Test fun negativeNamedIdCannotOpenAnInvalidPrivateDestination() {
        val h=saved(Screen.SupportTicket);h["yuldash_support_ticket_id"]=-45
        val vm=YuldashViewModel(h)
        assertEquals(Screen.Notifications,vm.screen.value);assertEquals(0,vm.supportTicketId.value)
    }
}
