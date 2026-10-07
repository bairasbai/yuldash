package com.yuldash.app

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
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

    data class PendingBookingNavigation(val bookingId: Int, val ownerId: Int?, val revision: Long, val completed: Boolean)
    val pendingBookingNavigation = mutableStateOf(
        saved.get<Int>(KEY_PENDING_COMPLETED)?.takeIf { it > 0 }?.let { bid ->
            saved.get<Long>(KEY_PENDING_REVISION)?.takeIf { it > 0 }?.let { revision ->
                PendingBookingNavigation(bid, saved.get<Int>(KEY_PENDING_OWNER)?.takeIf { it > 0 }, revision,
                    saved.get<Boolean>(KEY_PENDING_KIND) ?: true)
            }
        }
    )
    // Совместимость с уже сохранёнными booking_done task и прежними внутренними callers.
    val pendingCompletedNavigation = derivedStateOf { pendingBookingNavigation.value?.takeIf { it.completed } }
    val hasHandledBookingIntent: Boolean get() = (saved.get<Long>(KEY_PENDING_REVISION) ?: 0L) > 0L
    val privateNavigationRevision: Long get() = saved.get<Long>(KEY_PENDING_REVISION) ?: 0L

    data class PendingScreenNavigation(val destination: Screen, val targetId: Int, val ownerId: Int?, val revision: Long)
    val pendingScreenNavigation = mutableStateOf(
        saved.get<String>(KEY_PENDING_SCREEN)?.let { runCatching { Screen.valueOf(it) }.getOrNull() }
            ?.takeIf { it in PRIVATE_SCREENS }?.let { destination ->
                saved.get<Long>(KEY_PENDING_REVISION)?.takeIf { it > 0 }?.let { revision ->
                    PendingScreenNavigation(destination, saved.get<Int>(KEY_PENDING_TARGET) ?: 0,
                        saved.get<Int>(KEY_SCREEN_OWNER)?.takeIf { it > 0 }, revision)
                }
            }
    )
    val instantChatOrderId = mutableStateOf(saved.get<Int>(KEY_TAXI_CHAT) ?: 0)
    val supportTicketId = mutableStateOf(saved.get<Int>(KEY_SUPPORT_TICKET) ?: 0)

    fun requestScreenDestination(destination: Screen, ownerId: Int?, targetId: Int = 0) {
        if (destination !in PRIVATE_SCREENS || (destination in ID_SCREENS && targetId <= 0)) return
        pendingBookingNavigation.value?.let(::clearPendingBooking)
        clearScreenBridges()
        val revision = (saved.get<Long>(KEY_PENDING_REVISION) ?: 0L) + 1L
        saved[KEY_PENDING_REVISION] = revision
        saved[KEY_PENDING_SCREEN] = destination.name
        saved[KEY_PENDING_TARGET] = targetId
        saved[KEY_SCREEN_OWNER] = ownerId ?: -1
        pendingScreenNavigation.value = PendingScreenNavigation(destination, targetId, ownerId, revision)
        publishPendingScreen(pendingScreenNavigation.value!!)
    }

    fun pendingScreenForOwner(ownerId: Int?): PendingScreenNavigation? {
        val pending = pendingScreenNavigation.value ?: return null
        if (pending.ownerId != null && pending.ownerId != ownerId) {
            clearPendingScreen(pending)
            return null
        }
        val bound = if (pending.ownerId == null && ownerId != null) pending.copy(ownerId = ownerId) else pending
        if (bound != pending) { saved[KEY_SCREEN_OWNER] = ownerId; pendingScreenNavigation.value = bound }
        publishPendingScreen(bound)
        return bound
    }

    fun consumePendingScreen(expected: PendingScreenNavigation, applyRoute: () -> Unit): Boolean {
        if (pendingScreenNavigation.value != expected) return false
        applyRoute()
        recordNavigationChange()
        persistNav()
        clearPendingScreen(expected)
        return true
    }

    private fun clearScreenBridges() {
        NavSignals.openInstantOrder.value = false
        NavSignals.openInstantChat.value = 0
        NavSignals.openDriverCabinet.value = false
        DeepLink.pendingParcels.value = false
        DeepLink.pendingSupport.value = false
    }
    private fun publishPendingScreen(pending: PendingScreenNavigation) {
        NavSignals.openInstantOrder.value = pending.destination == Screen.InstantOrder
        NavSignals.openInstantChat.value = if (pending.destination == Screen.InstantChat) pending.targetId else 0
        NavSignals.openDriverCabinet.value = pending.destination == Screen.DriverCabinet
        DeepLink.pendingParcels.value = pending.destination == Screen.Parcels
        DeepLink.pendingSupport.value = pending.destination == Screen.SupportTickets
    }
    private fun clearPendingScreen(expected: PendingScreenNavigation) {
        if (pendingScreenNavigation.value != expected) return
        pendingScreenNavigation.value = null
        saved.remove<String>(KEY_PENDING_SCREEN)
        saved.remove<Int>(KEY_PENDING_TARGET)
        saved.remove<Int>(KEY_SCREEN_OWNER)
        clearScreenBridges()
    }

    /** Записываем до HTTP/перекомпозиции: новое уведомление должно пережить saved task. */
    fun requestCompletedBooking(bookingId: Int, ownerId: Int?) = requestBookingDestination(bookingId, ownerId, completed = true)

    fun requestBookingDestination(bookingId: Int, ownerId: Int?, completed: Boolean) {
        if (bookingId <= 0) return
        pendingScreenNavigation.value?.let(::clearPendingScreen)
        clearScreenBridges()
        val revision = (saved.get<Long>(KEY_PENDING_REVISION) ?: 0L) + 1L
        saved[KEY_PENDING_REVISION] = revision
        saved[KEY_PENDING_COMPLETED] = bookingId
        saved[KEY_PENDING_OWNER] = ownerId ?: -1
        saved[KEY_PENDING_KIND] = completed
        pendingBookingNavigation.value = PendingBookingNavigation(bookingId, ownerId, revision, completed)
        publishPendingBooking(pendingBookingNavigation.value!!)
    }

    /** Без владельца — назначение до входа; привязываем при первом известном аккаунте. */
    fun pendingCompletedForOwner(ownerId: Int?): PendingBookingNavigation? = pendingBookingForOwner(ownerId)?.takeIf { it.completed }

    fun pendingBookingForOwner(ownerId: Int?): PendingBookingNavigation? {
        val pending = pendingBookingNavigation.value ?: return null
        if (pending.ownerId != null && pending.ownerId != ownerId) {
            clearPendingBooking(pending)
            return null
        }
        val bound = if (pending.ownerId == null && ownerId != null) pending.copy(ownerId = ownerId) else pending
        if (bound != pending) {
            saved[KEY_PENDING_OWNER] = ownerId
            pendingBookingNavigation.value = bound
        }
        publishPendingBooking(bound)
        return bound
    }

    /** Один main-thread участок без suspend: маршрут сохранён до удаления pending. */
    fun consumePendingCompleted(expected: PendingBookingNavigation, applyRoute: () -> Unit): Boolean =
        expected.completed && consumePendingBooking(expected, applyRoute)

    fun consumePendingBooking(expected: PendingBookingNavigation, applyRoute: () -> Unit): Boolean {
        if (pendingBookingNavigation.value != expected) return false
        applyRoute()
        recordNavigationChange()
        persistNav()
        clearPendingBooking(expected)
        return true
    }

    private fun publishPendingBooking(pending: PendingBookingNavigation) {
        DeepLink.pendingCompletedBookingId.value = pending.bookingId.takeIf { pending.completed }
        DeepLink.pendingBookingChatId.value = pending.bookingId.takeIf { !pending.completed }
    }

    private fun clearPendingBooking(expected: PendingBookingNavigation) {
        if (pendingBookingNavigation.value != expected) return
        pendingBookingNavigation.value = null
        saved.remove<Int>(KEY_PENDING_COMPLETED)
        saved.remove<Int>(KEY_PENDING_OWNER)
        saved.remove<Boolean>(KEY_PENDING_KIND)
        if (DeepLink.pendingCompletedBookingId.value == expected.bookingId) DeepLink.pendingCompletedBookingId.value = null
        if (DeepLink.pendingBookingChatId.value == expected.bookingId) DeepLink.pendingBookingChatId.value = null
    }

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
        saved[KEY_TAXI_CHAT] = instantChatOrderId.value
        saved[KEY_SUPPORT_TICKET] = supportTicketId.value
        saved[KEY_NAV_HISTORY] = ArrayList(navHistory.map { it.name })
    }

    /** Тот же tracker для обычного эффекта и синхронного consume до saved task. */
    fun recordNavigationChange() {
        val previous = navPrev.value
        val current = screen.value
        val transient = previous in listOf(Screen.Splash, Screen.Login, Screen.Onboarding, Screen.Intro)
        if (current == Screen.Login) navHistory.clear()
        else if (!navPopping.value && current != previous && !transient) navHistory.add(previous)
        navPopping.value = false
        navPrev.value = current
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
        pendingScreenNavigation.value?.let(::clearPendingScreen)
        clearScreenBridges()
        instantChatOrderId.value = 0
        supportTicketId.value = 0
        saved.remove<Int>(KEY_TAXI_CHAT)
        saved.remove<Int>(KEY_SUPPORT_TICKET)
        pendingBookingNavigation.value?.let(::clearPendingBooking)
        DeepLink.pendingCompletedBookingId.value = null
        DeepLink.pendingBookingChatId.value = null
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
        const val KEY_PENDING_COMPLETED = "yuldash_pending_completed"
        const val KEY_PENDING_OWNER = "yuldash_pending_completed_owner"
        const val KEY_PENDING_REVISION = "yuldash_pending_completed_revision"
        // Старые ключи сохраняем; отсутствие kind означает ранее сохранённый booking_done.
        const val KEY_PENDING_KIND = "yuldash_pending_booking_completed"
        const val KEY_PENDING_SCREEN = "yuldash_pending_private_screen"
        const val KEY_PENDING_TARGET = "yuldash_pending_private_target"
        const val KEY_SCREEN_OWNER = "yuldash_pending_private_owner"
        const val KEY_TAXI_CHAT = "yuldash_taxi_chat_id"
        const val KEY_SUPPORT_TICKET = "yuldash_support_ticket_id"
        val ID_SCREENS = setOf(Screen.InstantChat, Screen.SupportTicket)
        val PRIVATE_SCREENS = ID_SCREENS + setOf(Screen.InstantOrder, Screen.DriverCabinet, Screen.Parcels, Screen.SupportTickets)
    }
}
