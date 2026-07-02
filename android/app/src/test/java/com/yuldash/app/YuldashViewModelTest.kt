package com.yuldash.app

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YuldashViewModelTest {

    @Test
    fun defaultsToSplashRussianAndMapTab() {
        val vm = YuldashViewModel(SavedStateHandle())

        assertEquals(Screen.Splash, vm.screen.value)
        assertEquals(AppLanguage.Ru, vm.language.value)
        assertEquals(HomeTab.Map, vm.startHomeTab.value)
    }

    @Test
    fun restoresScreenLanguageAndTabFromSavedState() {
        val vm = YuldashViewModel(
            SavedStateHandle(
                mapOf(
                    "yuldash_screen" to Screen.Home.name,
                    "yuldash_lang" to AppLanguage.Ba.name,
                    "yuldash_tab" to HomeTab.Profile.name,
                )
            )
        )

        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(AppLanguage.Ba, vm.language.value)
        assertEquals(HomeTab.Profile, vm.startHomeTab.value)
    }

    @Test
    fun persistNavWritesAllSurvivalState() {
        val saved = SavedStateHandle()
        val vm = YuldashViewModel(saved)

        vm.screen.value = Screen.CreateRide
        vm.language.value = AppLanguage.Ba
        vm.startHomeTab.value = HomeTab.Rides
        vm.persistNav()

        assertEquals(Screen.CreateRide.name, saved.get<String>("yuldash_screen"))
        assertEquals(AppLanguage.Ba.name, saved.get<String>("yuldash_lang"))
        assertEquals(HomeTab.Rides.name, saved.get<String>("yuldash_tab"))
    }

    @Test
    fun corruptSavedValuesFallBackToDefaults() {
        val vm = YuldashViewModel(
            SavedStateHandle(
                mapOf(
                    "yuldash_screen" to "BAD_SCREEN",
                    "yuldash_lang" to "BAD_LANG",
                    "yuldash_tab" to "BAD_TAB",
                )
            )
        )

        assertEquals(Screen.Splash, vm.screen.value)
        assertEquals(AppLanguage.Ru, vm.language.value)
        assertEquals(HomeTab.Map, vm.startHomeTab.value)
    }

    @Test
    fun startsWithDemoRidesButNoMockTrustedContactsOrSelectedTrip() {
        val vm = YuldashViewModel(SavedStateHandle())

        assertFalse(vm.rides.isEmpty())
        assertTrue(vm.trustedContacts.isEmpty())
        assertEquals(demoPartnerAds.size, vm.partnerAds.value.size)
        assertEquals(demoPartnerAds.size, vm.adStats.size)
        assertNull(vm.selectedRide.value)
        assertNull(vm.activeTrip.value)
        assertNull(vm.activeBookingId.value)
    }

    @Test
    fun transientTripStateSurvivesInsideViewModelInstance() {
        val vm = YuldashViewModel(SavedStateHandle())
        val ride = vm.rides.first()

        vm.selectedRide.value = ride
        vm.activeTrip.value = ride
        vm.activeBookingId.value = 777
        vm.callbackRequested.value = true
        vm.responsesRequestId.value = 555
        vm.isAdmin.value = true

        assertEquals(ride, vm.selectedRide.value)
        assertEquals(ride, vm.activeTrip.value)
        assertEquals(777, vm.activeBookingId.value)
        assertTrue(vm.callbackRequested.value)
        assertEquals(555, vm.responsesRequestId.value)
        assertTrue(vm.isAdmin.value)
    }

    @Test
    fun mutableCollectionsAcceptUserGeneratedData() {
        val vm = YuldashViewModel(SavedStateHandle())

        vm.localRequests.add(
            LocalRequest(
                title = "Need ride",
                route = "A -> B",
                time = "today",
                passenger = "Tester",
                status = "active",
                price = 500,
                trustedContact = "Contact",
                voiceUrl = "voice.m4a",
                serverId = 123,
            )
        )
        vm.voiceMessages.add(LocalVoiceMessage(author = "Tester", transcript = "hello", time = "10:00", durationSec = 3))

        assertEquals(1, vm.localRequests.size)
        assertEquals(123, vm.localRequests.first().serverId)
        assertEquals("voice.m4a", vm.localRequests.first().voiceUrl)
        assertEquals(1, vm.voiceMessages.size)
        assertEquals(3, vm.voiceMessages.first().durationSec)
    }

    @Test
    fun navHistoryAndPoppingFlagsAreExplicitState() {
        val vm = YuldashViewModel(SavedStateHandle())

        vm.navHistory.add(Screen.Home)
        vm.navHistory.add(Screen.Settings)
        vm.navPopping.value = true
        vm.navPrev.value = Screen.Home

        assertEquals(listOf(Screen.Home, Screen.Settings), vm.navHistory.toList())
        assertTrue(vm.navPopping.value)
        assertEquals(Screen.Home, vm.navPrev.value)
    }
}
