package com.yuldash.app

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/**
 * Состояние приложения вынесено из `YuldashApp` (god-composable) в ViewModel. Зачем:
 * - **Переживает поворот экрана** (config change) — в т.ч. `selectedRide`/`activeTrip`.
 *   Раньше они были в `remember` → поворот на экране брони/активной поездки сбрасывал их в null,
 *   и гард-редирект кидал на Home (видимый баг). Теперь VM переживает поворот → остаёшься на месте.
 * - `screen`/`language`/`startHomeTab` переживают и **смерть процесса** (через `SavedStateHandle`,
 *   как раньше делал `rememberSaveable`).
 * - Навигационная история (back-stack) и бизнес-данные в одном месте → тестируемо, composable только рисует.
 *
 * Состояние держим как `MutableState`/`SnapshotState*`, чтобы в `YuldashApp` делегировать через `by` —
 * тогда десятки существующих переходов `screen = Screen.X` и чтений не меняются (минимальный диф).
 */
internal class YuldashViewModel(private val saved: SavedStateHandle) : ViewModel() {

    // --- Survival-критичные: переживают поворот И kill процесса (SavedStateHandle) ---
    val screen = mutableStateOf(
        saved.get<String>(KEY_SCREEN)?.let { runCatching { Screen.valueOf(it) }.getOrNull() } ?: Screen.Splash
    )
    val language = mutableStateOf(
        saved.get<String>(KEY_LANG)?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: AppLanguage.Ru
    )
    val startHomeTab = mutableStateOf(
        saved.get<String>(KEY_TAB)?.let { runCatching { HomeTab.valueOf(it) }.getOrNull() } ?: HomeTab.Map
    )

    /** Сохранить survival-состояние в SavedStateHandle. Зовётся из эффекта при каждом изменении —
     *  так при смерти процесса значения уже лежат в handle и VM восстановит их из них. */
    fun persistNav() {
        saved[KEY_SCREEN] = screen.value.name
        saved[KEY_LANG] = language.value.name
        saved[KEY_TAB] = startHomeTab.value.name
    }

    // --- Транзитные/бизнес: переживают поворот, не переживают kill (как и было) ---
    val selectedRide = mutableStateOf<Ride?>(null)
    val activeBookingId = mutableStateOf<Int?>(null)
    val activeTrip = mutableStateOf<Ride?>(null)
    val callbackRequested = mutableStateOf(false)
    val responsesRequestId = mutableStateOf(0)
    val isAdmin = mutableStateOf(false)
    val partnerAds = mutableStateOf(demoPartnerAds)

    // --- Back-stack (аппаратная «Назад» по трейлу экранов) ---
    val navHistory = mutableStateListOf<Screen>()
    val navPopping = mutableStateOf(false)
    val navPrev = mutableStateOf(screen.value)

    // --- Коллекции данных ---
    val rides = mutableStateListOf<Ride>().apply { addAll(demoRides) }
    val trustedContacts = mutableStateListOf<TrustedContact>().apply { addAll(demoTrustedContacts) }
    val localRequests = mutableStateListOf<LocalRequest>()
    val voiceMessages = mutableStateListOf<LocalVoiceMessage>()
    val adStats = mutableStateMapOf<String, AdStats>().apply {
        putAll(demoPartnerAds.associate { it.id to AdStats() })
    }

    private companion object {
        const val KEY_SCREEN = "yuldash_screen"
        const val KEY_LANG = "yuldash_lang"
        const val KEY_TAB = "yuldash_tab"
    }
}
