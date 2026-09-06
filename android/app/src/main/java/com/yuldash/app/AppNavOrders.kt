package com.yuldash.app

import androidx.compose.runtime.Composable

/**
 * Экраны вокруг заказа — такси и курьер — вынесенные из общего выбора экрана.
 *
 * Зачем вынесены. Выбор экрана в `YuldashApp` держал все 96 веток в одной функции, и она
 * переросла размер, который Android соглашается ускорять: в логах
 * `Method exceeds compiler instruction limit`. Такой код исполняется в медленном виде,
 * и это чувствуется на каждом переходе между экранами — особенно на слабых телефонах.
 *
 * Что здесь лежит: заказ такси и его чат, поездка водителя, чек, история поездок,
 * документы и фото машины, посылки и всё про курьера. Им нужен небольшой общий набор:
 * какой заказ открыт, в каком режиме снимаем машину, и куда идти дальше.
 *
 * Добавляешь сюда экран — добавь его И в ветку `when` внутри `YuldashApp`, которая ведёт
 * сюда. Забыть нельзя: тот `when` обязан покрывать все экраны, и Kotlin скажет на сборке.
 */
@Composable
internal fun OrdersNav(
    screen: Screen,
    instantTripOrderId: Int,
    instantChatOrderId: Int,
    taxiReceiptOrderId: Int,
    carPhotoMode: String,
    parcelChatId: Int,
    parcelChatPeerIsCourier: Boolean,
    parcelChatStatus: String,
    onBack: () -> Unit,
    onOpen: (Screen) -> Unit,
    /** Открыть чат по заказу такси: сначала запоминаем, по какому. */
    onOpenTaxiChat: (Int) -> Unit,
    /** Открыть чек поездки: сначала запоминаем, какой. */
    onOpenReceipt: (Int) -> Unit,
    /** Открыть фото машины: режим («такси» или «курьер») решает, что снимаем. */
    onOpenCarPhoto: (String) -> Unit,
) {
    when (screen) {
        Screen.InstantOrder -> InstantOrderScreen(
            onBack = { onBack() },
            onLoginRequired = { onOpen(Screen.Login) },
            onTaxiOnboarding = { onOpen(Screen.TaxiOnboarding) },
            onOpenScheduled = { onOpen(Screen.ScheduledOrders) }
        )
        Screen.InstantDriverTrip -> InstantDriverTripScreen(
            orderId = instantTripOrderId,
            onBack = { onBack() },
            onFinished = { onOpen(Screen.DriverCabinet) }
        )
        Screen.InstantChat -> InstantChatScreen(
            orderId = instantChatOrderId,
            onBack = { onBack() }
        )
        Screen.TaxiOnboarding -> TaxiOnboardingScreen(
            onBack = { onBack() },
            onOpenDriverCabinet = { onOpen(Screen.DriverCabinet) }
        )
        Screen.TaxiReceipt -> TaxiReceiptScreen(
            orderId = taxiReceiptOrderId,
            onBack = { onBack() },
            // «Забыл вещь» открыл чат заказа на 48 часов → ведём прямо туда.
            onOpenChat = { id -> onOpenTaxiChat(id) },
        )
        Screen.DriverTaxiRides -> DriverTaxiRidesScreen(
            onBack = { onBack() },
            onOpenReceipt = { id -> onOpenReceipt(id) },
        )
        // История поездок пассажира: у водителя такой экран был (DriverTaxiRides), у того,
        // кто платит, — нет. Чек жил одну сессию и терялся вместе с экраном заказа.
        Screen.MyTaxiTrips -> MyTaxiTripsScreen(
            onBack = { onBack() },
            onOpenReceipt = { id -> onOpenReceipt(id) },
        )
        // Чат по посылке. Роль и статус передаёт карточка, из которой пришли, — она их знает,
        // и лишний запрос к серверу ради заголовка экрана тут не нужен.
        Screen.ParcelChat -> ParcelChatScreen(
            parcelId = parcelChatId,
            peerIsCourier = parcelChatPeerIsCourier,
            parcelStatus = parcelChatStatus,
            onBack = { onBack() },
        )
        Screen.TaxiDocuments -> TaxiDocumentsScreen(
            onBack = { onBack() },
            onCarPhoto = { onOpenCarPhoto("taxi") },
        )
        // Фотоконтроль машины: один экран на оба режима — просим разные кадры, но
        // правила, лестница и тон одинаковые (580-ФЗ).
        Screen.CarPhoto -> CarPhotoScreen(mode = carPhotoMode, onBack = { onBack() })
        Screen.PretripCheck -> PretripCheckScreen(onBack = { onBack() })
        Screen.CourierEarnings -> CourierEarningsScreen(onBack = { onBack() })
        Screen.Parcels -> ParcelsScreen(onBack = { onBack() })
        Screen.CourierOnboarding -> CourierOnboardingScreen(
            onBack = { onBack() },
            onOpenCourier = { onOpen(Screen.Courier) },
        )
        Screen.Courier -> CourierScreen(
            onBack = { onBack() },
            onBecomeCourier = { onOpen(Screen.CourierOnboarding) },
            onEarnings = { onOpen(Screen.CourierEarnings) },
            onCarPhoto = { onOpenCarPhoto("courier") },
        )
        else -> Unit   // снаружи сюда ведут только перечисленные экраны
    }
}
