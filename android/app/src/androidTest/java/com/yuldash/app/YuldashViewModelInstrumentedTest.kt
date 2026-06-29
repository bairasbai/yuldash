package com.yuldash.app

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Инструментальный тест YuldashViewModel (на устройстве/эмуляторе, БЕЗ Espresso/Compose-UI-rule →
 * работает и на bleeding-edge API 37). Проверяет survival-логику состояния, вынесенного из god-composable:
 * восстановление screen из SavedStateHandle, дефолты, запись persistNav.
 * На device (не JVM), т.к. инициализация VM тянет demo-данные с Android-иконками.
 * Запуск: gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class YuldashViewModelInstrumentedTest {

    @Test
    fun defaultsToSplashAndRu_whenNoSavedState() {
        val vm = YuldashViewModel(SavedStateHandle())
        assertEquals(Screen.Splash, vm.screen.value)
        assertEquals(AppLanguage.Ru, vm.language.value)
        assertEquals(HomeTab.Map, vm.startHomeTab.value)
    }

    @Test
    fun restoresState_fromSavedStateHandle() {
        val saved = SavedStateHandle(
            mapOf(
                "yuldash_screen" to Screen.Home.name,
                "yuldash_lang" to AppLanguage.Ba.name,
                "yuldash_tab" to HomeTab.Profile.name,
            )
        )
        val vm = YuldashViewModel(saved)
        assertEquals(Screen.Home, vm.screen.value)        // переживает смерть процесса
        assertEquals(AppLanguage.Ba, vm.language.value)
        assertEquals(HomeTab.Profile, vm.startHomeTab.value)
    }

    @Test
    fun persistNav_writesCurrentStateToHandle() {
        val saved = SavedStateHandle()
        val vm = YuldashViewModel(saved)
        vm.screen.value = Screen.CreateRide
        vm.language.value = AppLanguage.Ba
        vm.persistNav()
        assertEquals(Screen.CreateRide.name, saved.get<String>("yuldash_screen"))
        assertEquals(AppLanguage.Ba.name, saved.get<String>("yuldash_lang"))
    }

    @Test
    fun corruptSavedValue_fallsBackToDefault() {
        val vm = YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to "NOT_A_SCREEN")))
        assertEquals(Screen.Splash, vm.screen.value)   // битое значение → дефолт, без краша
    }
}
