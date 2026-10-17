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
    private val restoredScreen = saved.get<String>(KEY_SCREEN)
        ?.let { runCatching { Screen.valueOf(it) }.getOrNull() } ?: Screen.Splash
    val screen = mutableStateOf(
        restoredScreen.let { if (hasRestorableId(it)) it else Screen.Notifications }
            .also { if (saved.contains(KEY_SCREEN)) saved[KEY_SCREEN] = it.name }
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

    // Публичное назначение принадлежит окну, получившему Intent, а не всему процессу.
    data class PendingPublicRideNavigation(val rideId: Int, val revision: Long)
    val pendingPublicRideNavigation = mutableStateOf(
        saved.get<Int>(KEY_PUBLIC_RIDE)?.takeIf { it > 0 }?.let { rideId ->
            saved.get<Long>(KEY_PUBLIC_REVISION)?.takeIf { it > 0 }?.let { PendingPublicRideNavigation(rideId, it) }
        }
    )
    fun requestPublicRideIntent(rideId: Int, restoring: Boolean = false) {
        if (rideId <= 0) return
        if (restoring && (saved.get<Int>(KEY_INITIAL_PUBLIC_RIDE) == rideId || saved.get<Int>(KEY_LAST_PUBLIC_RIDE) == rideId)) return
        if (saved.get<Int>(KEY_INITIAL_PUBLIC_RIDE) == null) saved[KEY_INITIAL_PUBLIC_RIDE] = rideId
        saved[KEY_LAST_PUBLIC_RIDE] = rideId
        requestPublicRide(rideId)
    }
    fun requestPublicRide(rideId: Int) {
        if (rideId <= 0) return
        pendingBookingNavigation.value?.let(::clearPendingBooking)
        pendingScreenNavigation.value?.let(::clearPendingScreen)
        saved[KEY_PENDING_REVISION] = privateNavigationRevision + 1L
        val revision = (saved.get<Long>(KEY_PUBLIC_REVISION) ?: 0L) + 1L
        saved[KEY_PUBLIC_REVISION] = revision
        saved[KEY_PUBLIC_RIDE] = rideId
        pendingPublicRideNavigation.value = PendingPublicRideNavigation(rideId, revision)
    }
    fun clearPendingPublicRide() {
        pendingPublicRideNavigation.value = null
        saved.remove<Int>(KEY_PUBLIC_RIDE)
    }

    // Первая и последняя принятые доставки переживают consume/logout и saved task.
    fun hasHandledNotificationDelivery(deliveryId: String): Boolean =
        saved.get<String>(KEY_INITIAL_DELIVERY) == deliveryId || saved.get<String>(KEY_LAST_DELIVERY) == deliveryId

    fun recordHandledNotificationDelivery(deliveryId: String) {
        if (saved.get<String>(KEY_INITIAL_DELIVERY) == null) saved[KEY_INITIAL_DELIVERY] = deliveryId
        saved[KEY_LAST_DELIVERY] = deliveryId
    }

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
    private fun restoredId(key: String): Int = (saved.get<Any?>(key) as? Int)?.takeIf { it > 0 } ?: 0
    private fun hasRestorableId(destination: Screen): Boolean = when (destination) {
        Screen.InstantChat -> restoredId(KEY_TAXI_CHAT) > 0
        Screen.SupportTicket -> restoredId(KEY_SUPPORT_TICKET) > 0
        Screen.RequestResponses -> restoredId(KEY_REQUEST_RESPONSES) > 0
        Screen.IncidentDetail -> restoredId(KEY_INCIDENT) > 0
        else -> true
    }
    val instantChatOrderId = mutableStateOf(restoredId(KEY_TAXI_CHAT))
    val supportTicketId = mutableStateOf(restoredId(KEY_SUPPORT_TICKET))
    val responsesRequestId = mutableStateOf(restoredId(KEY_REQUEST_RESPONSES))
    val incidentId = mutableStateOf(restoredId(KEY_INCIDENT))

    fun requestScreenDestination(destination: Screen, ownerId: Int?, targetId: Int = 0) {
        if (destination !in PRIVATE_SCREENS || (destination in ID_SCREENS && targetId <= 0)) return
        clearPendingPublicRide()
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
        if (PrivateNavigationBridges.screen.isForeign(this)) return
        NavSignals.openInstantOrder.value = false
        NavSignals.openInstantChat.value = 0
        NavSignals.openDriverCabinet.value = false
        DeepLink.pendingParcels.value = false
        DeepLink.pendingSupport.value = false
        NavSignals.openAdsCabinet.value = false
        NavSignals.openPartnerCabinet.value = false
        DeepLink.pendingFairness.value = false
        DeepLink.pendingRequestResponsesId.value = null
        DeepLink.pendingRequestsFeed.value = false
        DeepLink.pendingApplicationScreen.value = null
        PrivateNavigationBridges.screen.release(this)
    }
    private fun publishPendingScreen(pending: PendingScreenNavigation) {
        PrivateNavigationBridges.screen.claim(this)
        NavSignals.openInstantOrder.value = pending.destination == Screen.InstantOrder
        NavSignals.openInstantChat.value = if (pending.destination == Screen.InstantChat) pending.targetId else 0
        NavSignals.openDriverCabinet.value = pending.destination == Screen.DriverCabinet
        DeepLink.pendingParcels.value = pending.destination == Screen.Parcels
        DeepLink.pendingSupport.value = pending.destination == Screen.SupportTickets
        NavSignals.openAdsCabinet.value = pending.destination == Screen.AdsCabinet
        NavSignals.openPartnerCabinet.value = pending.destination == Screen.PartnerCabinet
        DeepLink.pendingFairness.value = pending.destination == Screen.FairnessCenter
        DeepLink.pendingRequestResponsesId.value = pending.targetId.takeIf { pending.destination == Screen.RequestResponses }
        DeepLink.pendingRequestsFeed.value = pending.destination == Screen.RequestsFeed
        DeepLink.pendingApplicationScreen.value = pending.destination.takeIf { it in setOf(Screen.TaxiOnboarding, Screen.CourierOnboarding) }
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
        clearPendingPublicRide()
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
        PrivateNavigationBridges.booking.claim(this)
        DeepLink.pendingCompletedBookingId.value = pending.bookingId.takeIf { pending.completed }
        DeepLink.pendingBookingChatId.value = pending.bookingId.takeIf { !pending.completed }
    }

    private fun clearPendingBooking(expected: PendingBookingNavigation) {
        if (pendingBookingNavigation.value != expected) return
        pendingBookingNavigation.value = null
        saved.remove<Int>(KEY_PENDING_COMPLETED)
        saved.remove<Int>(KEY_PENDING_OWNER)
        saved.remove<Boolean>(KEY_PENDING_KIND)
        if (!PrivateNavigationBridges.booking.isForeign(this)) {
            if (DeepLink.pendingCompletedBookingId.value == expected.bookingId) DeepLink.pendingCompletedBookingId.value = null
            if (DeepLink.pendingBookingChatId.value == expected.bookingId) DeepLink.pendingBookingChatId.value = null
            PrivateNavigationBridges.booking.release(this)
        }
    }

    private fun clearBookingBridges() {
        if (PrivateNavigationBridges.booking.isForeign(this)) return
        DeepLink.pendingCompletedBookingId.value = null
        DeepLink.pendingBookingChatId.value = null
        PrivateNavigationBridges.booking.release(this)
    }

    override fun onCleared() {
        if (PrivateNavigationBridges.booking.belongsTo(this)) clearBookingBridges()
        if (PrivateNavigationBridges.screen.belongsTo(this)) clearScreenBridges()
        super.onCleared()
    }

    /** Явный выбор внутри приложения отменяет прежнее ожидающее назначение, не серверную операцию. */
    fun navigateLocally(applyRoute: () -> Unit) {
        clearPendingPublicRide()
        pendingBookingNavigation.value?.let(::clearPendingBooking)
        pendingScreenNavigation.value?.let(::clearPendingScreen)
        clearScreenBridges()
        clearBookingBridges()
        saved[KEY_PENDING_REVISION] = privateNavigationRevision + 1L
        applyRoute()
        recordNavigationChange()
        persistNav()
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
        saved[KEY_REQUEST_RESPONSES] = responsesRequestId.value
        saved[KEY_INCIDENT] = incidentId.value
        saved[KEY_NAV_HISTORY] = ArrayList(navHistory.map { it.name })
    }

    /** Тот же tracker для обычного эффекта и синхронного consume до saved task. */
    fun recordNavigationChange() {
        val previous = navPrev.value
        val current = screen.value
        val transient = previous in listOf(Screen.Splash, Screen.Login, Screen.Onboarding, Screen.Intro)
        if (current == Screen.Login) navHistory.clear()
        // Чат поездки под самой поездкой в историю не кладём: это тот же экран (см. showTripChat),
        // иначе «Назад» с поездки, открытой поверх чата, сперва вёл бы обратно в чат.
        else if (!navPopping.value && current != previous && !transient &&
            !(previous == Screen.TripChat && current == Screen.ActiveTrip)) navHistory.add(previous)
        navPopping.value = false
        navPrev.value = current
    }

    /**
     * Чат поездки и «Моя поездка» — два вида ОДНОГО живого экрана (общие сокет, опрос, черновик).
     * Открыть чат — обычный шаг вперёд. Вернуться к поездке — ЗАМЕНИТЬ чат поездкой: если пришли
     * из неё, снимаем её с трейла, иначе «Назад» водил бы по кругу поездка → чат → поездка.
     */
    fun showTripChat(open: Boolean) {
        // Поздний тап с уходящего экрана (или второй тап за анимацию) — экран уже другой, не трогаем.
        if (screen.value != (if (open) Screen.ActiveTrip else Screen.TripChat)) return
        if (open) { screen.value = Screen.TripChat; return }
        if (navHistory.lastOrNull() == Screen.ActiveTrip) navHistory.removeAt(navHistory.lastIndex)
        navPopping.value = true
        screen.value = Screen.ActiveTrip
    }

    // --- Транзитные/бизнес: переживают поворот, не переживают kill (как и было) ---
    val selectedRide = mutableStateOf<Ride?>(null)
    val activeTrip = mutableStateOf<Ride?>(null)
    // Бронь, в которой я ВОДИТЕЛЬ (её поездку открыли из кабинета или по ссылке с ролью driver). Живому гео
    // нужна роль: пульс стоянки шлёт только водитель (TripLocationService). Привязка к номеру, а не флаг:
    // открыл потом свою пассажирскую бронь — номер другой, роль сама становится «пассажир».
    val activeDriverBookingId = mutableStateOf<Int?>(null)
    fun isDriverOf(bookingId: Int?): Boolean = bookingId != null && activeDriverBookingId.value == bookingId
    val callbackRequested = mutableStateOf(false)
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
        }.filter(::hasRestorableId))
        while (screen.value != restoredScreen && lastOrNull() == screen.value) removeAt(lastIndex)
        if (saved.contains(KEY_NAV_HISTORY)) saved[KEY_NAV_HISTORY] = ArrayList(map { it.name })
    }
    val navPopping = mutableStateOf(false)
    val navPrev = mutableStateOf(screen.value)

    /** Старое анонимное Compose-поле нельзя считать доказанным ID другого назначения. */
    fun recoverMissingRootDestination(expected: Screen, unavailable: Set<Screen> = setOf(expected)) {
        if (screen.value != expected) return
        navHistory.removeAll { it in unavailable }
        while (navHistory.lastOrNull() == Screen.Notifications) navHistory.removeAt(navHistory.lastIndex)
        screen.value = Screen.Notifications
        navPrev.value = Screen.Notifications
        navPopping.value = false
        persistNav()
    }

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
        clearBookingBridges()
        selectedRide.value = null
        activeTrip.value = null
        activeBookingId.value = null
        activeDriverBookingId.value = null
        isAdmin.value = false
        callbackRequested.value = false
        responsesRequestId.value = 0
        incidentId.value = 0
        saved.remove<Int>(KEY_REQUEST_RESPONSES)
        saved.remove<Int>(KEY_INCIDENT)
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
        const val KEY_INITIAL_PUBLIC_RIDE = "yuldash_initial_public_ride"
        const val KEY_LAST_PUBLIC_RIDE = "yuldash_last_public_ride"
        const val KEY_PUBLIC_RIDE = "yuldash_pending_public_ride"
        const val KEY_PUBLIC_REVISION = "yuldash_public_ride_revision"
        const val KEY_INITIAL_DELIVERY = "yuldash_initial_notification_delivery"
        const val KEY_LAST_DELIVERY = "yuldash_last_notification_delivery"
        // Старые ключи сохраняем; отсутствие kind означает ранее сохранённый booking_done.
        const val KEY_PENDING_KIND = "yuldash_pending_booking_completed"
        const val KEY_PENDING_SCREEN = "yuldash_pending_private_screen"
        const val KEY_PENDING_TARGET = "yuldash_pending_private_target"
        const val KEY_SCREEN_OWNER = "yuldash_pending_private_owner"
        const val KEY_TAXI_CHAT = "yuldash_taxi_chat_id"
        const val KEY_SUPPORT_TICKET = "yuldash_support_ticket_id"
        const val KEY_REQUEST_RESPONSES = "yuldash_request_responses_id"
        const val KEY_INCIDENT = "yuldash_incident_id"
        val ID_SCREENS = setOf(Screen.InstantChat, Screen.SupportTicket, Screen.RequestResponses, Screen.IncidentDetail)
        val PRIVATE_SCREENS = ID_SCREENS + setOf(Screen.InstantOrder, Screen.DriverCabinet, Screen.Parcels, Screen.SupportTickets,
            Screen.AdsCabinet, Screen.PartnerCabinet, Screen.FairnessCenter, Screen.RequestsFeed, Screen.TaxiOnboarding, Screen.CourierOnboarding)
    }
}

/**
 * Ключ содержимого в общей анимации экранов. TripChat рисует тот же живой экран, что ActiveTrip
 * (вид «переписка»), поэтому делит с ним композицию: переход поездка ↔ чат не пересоздаёт сокет,
 * историю и черновик, а свою анимацию «вглубь» экран играет сам.
 */
internal fun Screen.contentScreen(): Screen = if (this == Screen.TripChat) Screen.ActiveTrip else this

/** Чем экран поездки показан сейчас: чат / поездка; null — на экране другой экран (идёт уход). */
internal fun Screen.tripChatView(): Boolean? = when (this) {
    Screen.TripChat -> true
    Screen.ActiveTrip -> false
    else -> null
}
