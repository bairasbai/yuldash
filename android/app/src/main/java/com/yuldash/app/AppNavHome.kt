package com.yuldash.app

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/**
 * Главный экран как отдельная «остановка» навигации.
 *
 * Зачем вынесен. Выбор экрана в `YuldashApp` держал все 96 веток в одной функции, и эта
 * ветка была самой большой — 154 строки из 640. Функция целиком не влезала в то, что
 * Android соглашается ускорять (`Method exceeds compiler instruction limit`), и работала
 * в медленном виде. Чувствуется на каждом переходе между экранами, больнее всего на
 * слабых телефонах — а в районе таких большинство.
 *
 * Почему параметров немного. Почти всё, что нужно главному экрану, и так лежит в «мозге»
 * приложения (`YuldashViewModel`) — его и передаём целиком. Отдельно приходят только те
 * вещи, которые живут в самой `YuldashApp` и никуда больше не относятся.
 */
@Composable
internal fun HomeRoute(
    vm: YuldashViewModel,
    prefs: SharedPreferences,
    appScope: CoroutineScope,
    requestsLoading: Boolean,
    requestsError: Boolean,
    payMethod: String,
    onRetryRequests: () -> Unit,
    onLoginRequired: (Screen) -> Unit,
    /** Статус брони, которую открываем следующей: его читает экран брони. */
    onBookingStatus: (String) -> Unit,
    /** Заготовка «следить за маршрутом»: откуда и куда. */
    onRouteWatchPrefill: (String?, String?) -> Unit,
    /** Открыть публикацию поездки: вкладка возврата и, если есть, готовая дата. */
    onCreateRide: (HomeTab, String?) -> Unit,
    onSos: () -> Unit,
    onTrustedContacts: () -> Unit,
    onAdImpression: (PartnerAd) -> Unit,
    onAdClick: (PartnerAd) -> Unit,
) {
    val context = LocalContext.current
    // Те же самые поля мозга, что и в `YuldashApp` — экран читает и пишет их напрямую,
    // никакой копии состояния здесь не заводится.
    val rides = vm.rides
    val localRequests = vm.localRequests
    val voiceMessages = vm.voiceMessages
    val adStats = vm.adStats
    var screen by vm.screen
    var language by vm.language
    var selectedRide by vm.selectedRide
    var activeTrip by vm.activeTrip
    var activeBookingId by vm.activeBookingId
    var startHomeTab by vm.startHomeTab
    var responsesRequestId by vm.responsesRequestId
    var isAdmin by vm.isAdmin
    var partnerAds by vm.partnerAds

    val homeSession = ApiClient.sessionChanges.collectAsState().value
    val homeScope = rememberCoroutineScope()
    var homeDestinationEpoch by remember { mutableIntStateOf(0) }
    val callbackEpoch = homeDestinationEpoch
    fun openHomeDestination(requireLogin: Boolean = true, applyRoute: () -> Unit) {
        val applyIfHome = {
            if (homeScope.isActive && screen == Screen.Home && callbackEpoch == homeDestinationEpoch) {
                vm.navigateLocally(applyRoute)
                // Только принятый выбор Home отменяет прежние кнопки; входящее уведомление — нет.
                if (screen != Screen.Home) homeDestinationEpoch++
            }
        }
        if (requireLogin) ApiClient.runIfCurrentSession(homeSession, applyIfHome)
        else ApiClient.runIfUnchangedSession(homeSession, applyIfHome)
    }

    fun openHomeScreen(applyRoute: () -> Unit) = openHomeDestination(requireLogin = false, applyRoute)

    HomeScreen(
            rides = rides,
            activeTrip = activeTrip,
            requests = localRequests,
            requestsLoading = requestsLoading,
            requestsError = requestsError,
            onRetryRequests = onRetryRequests,
            ads = partnerAds,
            adStats = adStats,
            voiceMessages = voiceMessages,
            initialTab = startHomeTab,
            onTabChange = { startHomeTab = it },   // «Назад» с под-экранов вернётся на активную вкладку Home
            onCreateRide = { openHomeScreen { onCreateRide(HomeTab.Request, null) } },
            onSeasonalPublish = { date -> openHomeScreen { onCreateRide(HomeTab.Request, date) } },   // F15: дата праздника уже в форме
            onCreateRequest = { openHomeScreen { screen = Screen.CreateRequest } },
            onSupport = { openHomeScreen { screen = Screen.Support } },
            onMyStats = { openHomeScreen { screen = Screen.MyStats } },
            onCoupons = { openHomeScreen { screen = Screen.Coupons } },
            onPromo = { openHomeScreen { screen = Screen.PromoCode } },
            onParcels = { openHomeScreen { screen = Screen.Parcels } },
            onCourier = { openHomeScreen { screen = Screen.Courier } },
            onPartnerCabinet = { openHomeScreen { screen = Screen.PartnerCabinet } },
            onMyData = { openHomeScreen { screen = Screen.MyData } },
            onReview = { openHomeScreen { screen = Screen.AppReview } },
            onAdminReviews = { openHomeScreen { screen = Screen.AdminReviews } },
            onAdminAds = { openHomeScreen { screen = Screen.AdminAds } },
            onBoost = { openHomeScreen { screen = Screen.Boost } },
            onPublishRide = { ride ->
                rides.add(0, ride)
                Toast.makeText(context, if (language == AppLanguage.Ba) "Заявка баҫтырылды" else "Заявка опубликована", Toast.LENGTH_SHORT).show()
            },
            onBookRide = { ride ->
                openHomeDestination(requireLogin = false) {
                selectedRide = ride
                activeBookingId = null
                onBookingStatus("")
                screen = Screen.Booking
                }
            },
            onOpenBookingDetails = { ride, status ->
                openHomeDestination {
                selectedRide = ride
                activeTrip = null
                activeBookingId = ride.id.toIntOrNull()
                onBookingStatus(status)
                screen = Screen.Booking
                }
            },
            onOpenActiveTrip = { ride, status ->
                openHomeDestination {
                selectedRide = ride
                // Живое гео гейтится на activeTrip != null (см. эффект TripLocationService выше):
                // для подтверждённой поездки, открытой из списка, ставим activeTrip = ride, иначе
                // сервис заглохнет и попутчик не увидит позицию. Для неактивной брони — null (как было).
                // done остаётся доступным экраном оценки, но уже не включает живую геолокацию.
                activeTrip = if (bookingStatusAllowsBoarding(status)) ride else null
                activeBookingId = ride.id.toIntOrNull()
                onBookingStatus(status)
                screen = if (bookingStatusAllowsActiveTrip(status)) Screen.ActiveTrip else Screen.Booking
                }
            },
            onShareRide = { ride ->
                val rideTime = if (language == AppLanguage.Ba) ride.timeBa ?: ride.time else ride.time
                // Красивая расшариваемая ссылка: откроется в приложении (deep-link) либо покажет
                // веб-превью с OG-карточкой в WhatsApp/Telegram. Хост — из конфига, не localhost.
                val link = "${BuildConfig.YULDASH_WEB_BASE_URL.trimEnd('/')}/r/${ride.id}"
                val shareText = if (language == AppLanguage.Ba) {
                    "Юлдаш: ${ride.from} → ${ride.to}, $rideTime, йөрөтөүсе ${ride.driver}, ${ride.price} ₽, буш урын: ${ride.seats}.\n$link"
                } else {
                    "Юлдаш: ${ride.from} → ${ride.to}, $rideTime, водитель ${ride.driver}, ${ride.price} ₽, свободно ${ride.seats} места.\n$link"
                }
                shareRide(
                    context = context,
                    text = shareText,
                    chooserTitle = if (language == AppLanguage.Ba) "Сәфәр менән бүлешеү" else "Поделиться поездкой"
                )
            },
            onAdImpression = onAdImpression,
            onAdClick = onAdClick,
            onAddVoiceMessage = { message ->
                voiceMessages.add(0, message)
                Toast.makeText(context, if (language == AppLanguage.Ba) "Тауыш хәбәре ебәрелде" else "Голосовое отправлено", Toast.LENGTH_SHORT).show()
            },
            onSos = { openHomeScreen { onSos() } },
            onVerifyDriver = { openHomeScreen { screen = Screen.VerifyDriver } },
            onTaxiOnboarding = { openHomeScreen { screen = Screen.TaxiOnboarding } },
            onOpenScheduled = { openHomeScreen { screen = Screen.ScheduledOrders } },
            onSavedPlaces = { openHomeScreen { onLoginRequired(Screen.SavedPlaces) } },
            payMethod = payMethod,
            onOpenPayments = { openHomeScreen { screen = Screen.PaymentMethods } },
            onCourierMode = { openHomeScreen { onLoginRequired(Screen.Courier) } },
            onNotifications = { openHomeScreen { screen = Screen.Notifications } },
            onRouteWatch = { from, to ->
                openHomeScreen {
                    onRouteWatchPrefill(from, to)
                    screen = Screen.RouteWatches
                }
            },
            onOpenChat = { bid, peer, route ->
                openHomeDestination {
                val parts = route.split("→").map { it.trim() }
                selectedRide = Ride(id = bid.toString(), from = parts.getOrElse(0) { "" }, to = parts.getOrElse(1) { "" }, time = "", driver = peer, car = "", price = 0, seats = 1, rating = 0.0, verified = false, boosted = false)
                activeBookingId = bid
                onBookingStatus("")
                screen = Screen.ActiveTrip
                }
            },
            onOpenResponses = { id ->
                if (id > 0) openHomeDestination { responsesRequestId = id; screen = Screen.RequestResponses }
            },
            onCancelRequest = { id ->
                // Отмена заявки: успех — по факту сервера (убираем из списка), при сбое — серверная причина
                // (matched-заявку нельзя отменить тут → бэк вернёт понятный текст).
                appScope.launch {
                    ApiClient.cancelRequest(id)
                        .onSuccess {
                            localRequests.removeAll { it.serverId == id }
                            Toast.makeText(context, if (language == AppLanguage.Ba) "Заявка кире алынды" else "Заявка отменена", Toast.LENGTH_SHORT).show()
                        }
                        .onFailure { e ->
                            Toast.makeText(context, (e as? com.yuldash.app.data.ApiException)?.message ?: if (language == AppLanguage.Ba) "Булманы. Ҡабатла" else "Не получилось. Повтори", Toast.LENGTH_LONG).show()
                        }
                }
            },
            onEditRequest = { id, from, to, price, comment ->
                // F3: правка заявки. Успех — оптимистично обновляем карточку; сбой — серверная причина.
                appScope.launch {
                    ApiClient.editRequest(id, fromCity = from, toCity = to, maxPrice = price, comment = comment)
                        .onSuccess {
                            val idx = localRequests.indexOfFirst { it.serverId == id }
                            if (idx >= 0) {
                                val r = localRequests[idx]
                                localRequests[idx] = r.copy(route = "$from → $to", price = price, trustedContact = comment.ifBlank { null })
                            }
                            Toast.makeText(context, if (language == AppLanguage.Ba) "Заявка үҙгәртелде" else "Заявка обновлена", Toast.LENGTH_SHORT).show()
                        }
                        .onFailure { e ->
                            Toast.makeText(context, (e as? com.yuldash.app.data.ApiException)?.message ?: if (language == AppLanguage.Ba) "Булманы. Ҡабатла" else "Не получилось. Повтори", Toast.LENGTH_LONG).show()
                        }
                }
            },
            onSafety = { openHomeScreen { screen = Screen.Safety } },
            onSettings = { openHomeScreen { screen = Screen.Settings } },
            onPrivacy = { openHomeScreen { screen = Screen.Privacy } },
            onTrust = { openHomeScreen { onLoginRequired(Screen.Trust) } },
            onFairness = { openHomeScreen { onLoginRequired(Screen.FairnessCenter) } },
            onConsents = { openHomeScreen { onLoginRequired(Screen.Consents) } },
            onHelp = { openHomeScreen { screen = Screen.Help } },
            onPassengerCabinet = { openHomeScreen { prefs.edit().putString("preferred_role", RideRole.Passenger.name).apply(); screen = Screen.PassengerCabinet } },
            onDriverCabinet = { openHomeScreen { prefs.edit().putString("preferred_role", RideRole.Driver.name).apply(); screen = Screen.DriverCabinet } },
            onClinicRides = { openHomeScreen { screen = Screen.ClinicRides } },
            onSimpleMode = { openHomeScreen { screen = Screen.SimpleMode } },
            onTrustedContacts = { openHomeScreen { onTrustedContacts() } },
            onCallbackHelp = { openHomeScreen { screen = Screen.CallbackHelp } },
            onAdsCabinet = { openHomeScreen { screen = Screen.AdsCabinet } },
            onToggleLanguage = {
                language = if (language == AppLanguage.Ru) AppLanguage.Ba else AppLanguage.Ru
            },
            onAccountDeleted = {
                endSession(context, vm)             // гасим весь live-GPS и чистим PII из памяти
                isAdmin = false
                startHomeTab = HomeTab.Map
                screen = Screen.Login
            },
            onInstantLogin = { openHomeScreen { startHomeTab = HomeTab.Map; onLoginRequired(Screen.Home) } }
        )
}
