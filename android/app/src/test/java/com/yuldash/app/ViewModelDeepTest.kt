package com.yuldash.app

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Углублённые JVM-тесты [YuldashViewModel] — сценарии, не покрытые [YuldashViewModelTest]:
 * частичная порча SavedState, round-trip «смерть процесса», дефолты флагов, реклама/статистика,
 * back-stack операции. Чистый JVM (SavedStateHandle тестируется без Android-рантайма).
 */
class ViewModelDeepTest {

    // --- Частично битый SavedState: одно поле валидно, другое мусор ---------

    @Test
    fun validScreenWithCorruptLangKeepsScreenAndDefaultsLang() {
        // screen читается, lang — мусор → screen восстановлен, lang и tab дефолтные
        val vm = YuldashViewModel(
            SavedStateHandle(
                mapOf(
                    "yuldash_screen" to Screen.Home.name,
                    "yuldash_lang" to "XXX",
                )
            )
        )

        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(AppLanguage.Ru, vm.language.value)
        assertEquals(HomeTab.Map, vm.startHomeTab.value)
    }

    @Test
    fun validLangWithCorruptScreenKeepsLangAndDefaultsScreen() {
        // зеркально: lang валиден, screen — мусор → lang восстановлен, screen дефолтный
        val vm = YuldashViewModel(
            SavedStateHandle(
                mapOf(
                    "yuldash_screen" to "NOPE",
                    "yuldash_lang" to AppLanguage.Ba.name,
                )
            )
        )

        assertEquals(Screen.Splash, vm.screen.value)
        assertEquals(AppLanguage.Ba, vm.language.value)
        assertEquals(HomeTab.Map, vm.startHomeTab.value)
    }

    // --- Round-trip: изменения переживают «смерть процесса» через тот же handle ---

    @Test
    fun navStateSurvivesProcessDeathViaSameSavedStateHandle() {
        // 1-я VM меняет и сохраняет nav-состояние; 2-я VM из ТОГО ЖЕ handle его восстанавливает
        val saved = SavedStateHandle()

        val first = YuldashViewModel(saved)
        first.screen.value = Screen.CreateRide
        first.language.value = AppLanguage.Ba
        first.startHomeTab.value = HomeTab.Profile
        first.persistNav()

        val restored = YuldashViewModel(saved)

        assertEquals(Screen.CreateRide, restored.screen.value)
        assertEquals(AppLanguage.Ba, restored.language.value)
        assertEquals(HomeTab.Profile, restored.startHomeTab.value)
    }

    @Test
    fun persistNavIsIdempotentOnRepeatedCalls() {
        // повторный persistNav без изменений не портит уже сохранённые значения
        val saved = SavedStateHandle()
        val vm = YuldashViewModel(saved)

        vm.screen.value = Screen.Home
        vm.startHomeTab.value = HomeTab.Rides
        vm.persistNav()
        vm.persistNav()

        assertEquals(Screen.Home.name, saved.get<String>("yuldash_screen"))
        assertEquals(HomeTab.Rides.name, saved.get<String>("yuldash_tab"))
    }

    // --- Дефолты флагов/полей сразу после создания -------------------------

    @Test
    fun freshViewModelHasCleanDefaultFlags() {
        val vm = YuldashViewModel(SavedStateHandle())

        assertFalse(vm.callbackRequested.value)
        assertEquals(0, vm.responsesRequestId.value)
        assertFalse(vm.isAdmin.value)
        assertNull(vm.selectedRide.value)
        assertNull(vm.activeTrip.value)
        assertNull(vm.activeBookingId.value)
        assertFalse(vm.navPopping.value)
    }

    // --- Реклама и статистика показов -------------------------------------

    @Test
    fun partnerAdsExposeDemoListAndAdStatsHasEntryPerAd() {
        val vm = YuldashViewModel(SavedStateHandle())

        // список рекламы = демо-данные
        assertEquals(demoPartnerAds, vm.partnerAds.value)
        // на каждый demo-ad — запись в статистике со значением по умолчанию
        assertEquals(demoPartnerAds.size, vm.adStats.size)
        demoPartnerAds.forEach { ad ->
            assertEquals(AdStats(), vm.adStats[ad.id])
        }
    }

    // --- Back-stack (навигационная история) --------------------------------

    @Test
    fun navHistoryPreservesInsertionOrderAndClears() {
        val vm = YuldashViewModel(SavedStateHandle())

        vm.navHistory.add(Screen.Home)
        vm.navHistory.add(Screen.CreateRide)
        vm.navHistory.add(Screen.Settings)

        assertEquals(listOf(Screen.Home, Screen.CreateRide, Screen.Settings), vm.navHistory.toList())

        vm.navHistory.clear()
        assertTrue(vm.navHistory.isEmpty())
    }

    @Test
    fun navPrevDefaultsToCurrentScreenValue() {
        // navPrev инициализируется текущим screen.value (обычно Splash по умолчанию)
        val vm = YuldashViewModel(SavedStateHandle())

        assertEquals(vm.screen.value, vm.navPrev.value)
        assertEquals(Screen.Splash, vm.navPrev.value)
    }
}
