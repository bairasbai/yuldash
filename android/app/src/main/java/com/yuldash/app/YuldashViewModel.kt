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
    // H1: id активной брони переживает kill процесса. После рестарта экран ActiveTrip/Booking
    // восстанавливается по screen (тоже в handle), а по этому id YuldashApp дочитывает бронь с сервера
    // и восстанавливает activeTrip/selectedRide (сами объекты Ride в handle не кладём — тяжело/лишнее).
    val activeBookingId = mutableStateOf<Int?>(
        saved.get<Int>(KEY_ACTIVE_BID)?.takeIf { it > 0 }
    )

    /**
     * Восстанавливает язык из постоянных настроек только когда SavedState не содержит
     * валидного значения. Так поворот экрана сохраняет самое свежее состояние, а настоящий
     * холодный запуск не сбрасывает выбранный башкирский язык на русский по умолчанию.
     */
    fun restorePersistedLanguage(persisted: AppLanguage) {
        val restored = saved.get<String>(KEY_LANG)
            ?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
        if (restored == null) {
            language.value = persisted
            saved[KEY_LANG] = persisted.name
        }
    }

    /** Сохранить survival-состояние в SavedStateHandle. Зовётся из эффекта при каждом изменении —
     *  так при смерти процесса значения уже лежат в handle и VM восстановит их из них. */
    fun persistNav() {
        saved[KEY_SCREEN] = screen.value.name
        saved[KEY_LANG] = language.value.name
        saved[KEY_TAB] = startHomeTab.value.name
        saved[KEY_ACTIVE_BID] = activeBookingId.value ?: -1   // -1 = нет активной брони (null не храним примитивом)
        saved[KEY_NAV_HISTORY] = ArrayList(navHistory.map { it.name })
    }

    // --- Транзитные/бизнес: переживают поворот, не переживают kill (как и было) ---
    val selectedRide = mutableStateOf<Ride?>(null)
    val activeTrip = mutableStateOf<Ride?>(null)
    val callbackRequested = mutableStateOf(false)
    val responsesRequestId = mutableStateOf(0)
    val isAdmin = mutableStateOf(false)
    // Реклама начинается ПУСТОЙ, а не с демо-партнёров. Раньше стартовым значением стоял
    // `demoPartnerAds`, и при обрыве связи (сервер не ответил) человек видел карточку
    // выдуманной аптеки, а кнопка «Позвонить» набирала демо-номер. Демо-шаблон остаётся
    // в YuldashApp только как ОФОРМЛЕНИЕ реальных объявлений — данными он больше не бывает.
    val partnerAds = mutableStateOf(emptyList<PartnerAd>())

    // --- Back-stack (аппаратная «Назад» по трейлу экранов) ---
    val navHistory = mutableStateListOf<Screen>().apply {
        addAll(saved.get<List<String>>(KEY_NAV_HISTORY).orEmpty().mapNotNull {
            runCatching { Screen.valueOf(it) }.getOrNull()
        })
    }
    val navPopping = mutableStateOf(false)
    val navPrev = mutableStateOf(screen.value)

    // --- Коллекции данных ---
    // Лента поездок начинается ПУСТОЙ. Раньше сюда сразу клались `demoRides`, и до ответа
    // сервера — а при обрыве связи навсегда — на карте висели метки несуществующих поездок
    // («Ильдар, Lada Vesta, 350 ₽»). Нажатие вело к брони, которая всё равно не проходила:
    // у демо-поездок id 1..3 не серверные. Карта без меток честнее карты с выдуманными.
    val rides = mutableStateListOf<Ride>()
    val trustedContacts = mutableStateListOf<TrustedContact>()
    val localRequests = mutableStateListOf<LocalRequest>()
    val voiceMessages = mutableStateListOf<LocalVoiceMessage>()
    val adStats = mutableStateMapOf<String, AdStats>().apply {
        putAll(demoPartnerAds.associate { it.id to AdStats() })
    }

    /**
     * Полная очистка пользовательских данных при выходе/удалении аккаунта. VM переживает переход
     * logout→login (та же Activity), а стартовые загрузчики висят на LaunchedEffect(Unit) и при повторном
     * входе не перезапускаются — без этой очистки следующий вошедший на общем устройстве увидит контакты
     * (имена+телефоны) и заявки прошлого пользователя. Реклама (partnerAds/adStats) — публичная, не PII.
     */
    fun clearUserData() {
        selectedRide.value = null
        activeTrip.value = null
        activeBookingId.value = null
        isAdmin.value = false
        callbackRequested.value = false
        responsesRequestId.value = 0
        trustedContacts.clear()
        localRequests.clear()
        voiceMessages.clear()
        navHistory.clear()
        saved.remove<ArrayList<String>>(KEY_NAV_HISTORY)
        rides.clear()
    }

    private companion object {
        const val KEY_SCREEN = "yuldash_screen"
        const val KEY_LANG = "yuldash_lang"
        const val KEY_TAB = "yuldash_tab"
        const val KEY_ACTIVE_BID = "yuldash_active_bid"
        const val KEY_NAV_HISTORY = "yuldash_nav_history"
    }
}
