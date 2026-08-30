import { Suspense, useEffect } from "react";
import { Navigate, Outlet, Route, Routes } from "react-router-dom";
import BottomNav from "./components/BottomNav";
import InstallPrompt from "./components/InstallPrompt";
import OfflineBanner from "./components/OfflineBanner";
import RequireAuth from "./components/RequireAuth";
import RequireAdmin from "./components/RequireAdmin";
import { LoadingList } from "./components/States";
import { lazyScreen, clearChunkReloadFlag } from "./lazyScreen";
import RidesScreen from "./screens/RidesScreen";
import SplashScreen from "./screens/SplashScreen";
import IntroScreen from "./screens/IntroScreen";
import OnboardingScreen from "./screens/OnboardingScreen";
import LoginScreen from "./screens/LoginScreen";
import ProfileScreen from "./screens/ProfileScreen";
import ConsentsScreen from "./screens/ConsentsScreen";
import HomeScreen from "./screens/HomeScreen";
import CreateRequestScreen from "./screens/CreateRequestScreen";
import BookingScreen from "./screens/BookingScreen";
import ActiveTripScreen from "./screens/ActiveTripScreen";
import UpdateBanner from "./components/UpdateBanner";
import { watchOutbox } from "./utils/outbox";
import InstantOrderScreen from "./screens/InstantOrderScreen";
import InstantDriverTripScreen from "./screens/InstantDriverTripScreen";
import InstantChatScreen from "./screens/InstantChatScreen";
import ChatInboxScreen from "./screens/ChatInboxScreen";
import SosScreen from "./screens/SosScreen";


/**
 * Редкие экраны грузим отдельным куском, а не вместе со всем приложением.
 *
 * Раньше первый заход тянул 862 КБ разом — вместе с админкой, калькулятором
 * дохода и правовыми текстами, которых человек может не открыть никогда.
 * В селе на слабой связи это лишние секунды перед первым экраном.
 *
 * Что осталось в основном куске: карта, лента, заказ, чат, профиль —
 * то, ради чего сайт и открывают.
 */
const AdEditorScreen = lazyScreen(() => import("./screens/AdEditorScreen"));
const AdminAdsScreen = lazyScreen(() => import("./screens/AdminAdsScreen"));
const AdminCabinetScreen = lazyScreen(() => import("./screens/AdminCabinetScreen"));
const AdminCourierScreen = lazyScreen(() => import("./screens/AdminCourierScreen"));
const AdminDebtsScreen = lazyScreen(() => import("./screens/AdminDebtsScreen"));
const AdminDriversScreen = lazyScreen(() => import("./screens/AdminDriversScreen"));
const AdminIncidentsScreen = lazyScreen(() => import("./screens/AdminIncidentsScreen"));
const AdminModerationScreen = lazyScreen(() => import("./screens/AdminModerationScreen"));
const AdminParcelsScreen = lazyScreen(() => import("./screens/AdminParcelsScreen"));
const AdminPartnersScreen = lazyScreen(() => import("./screens/AdminPartnersScreen"));
const AdminPaymentRequestsScreen = lazyScreen(() => import("./screens/AdminPaymentRequestsScreen"));
const AdminPretripScreen = lazyScreen(() => import("./screens/AdminPretripScreen"));
const AdminPromoScreen = lazyScreen(() => import("./screens/AdminPromoScreen"));
const AdminReportsScreen = lazyScreen(() => import("./screens/AdminReportsScreen"));
const AdminRequestScreen = lazyScreen(() => import("./screens/AdminRequestScreen"));
const AdminResponsesScreen = lazyScreen(() => import("./screens/AdminResponsesScreen"));
const AdminReviewsScreen = lazyScreen(() => import("./screens/AdminReviewsScreen"));
const AdminSosScreen = lazyScreen(() => import("./screens/AdminSosScreen"));
const AdminSupportScreen = lazyScreen(() => import("./screens/AdminSupportScreen"));
const AdminTaxiPulseScreen = lazyScreen(() => import("./screens/AdminTaxiPulseScreen"));
const AdminTaxiScreen = lazyScreen(() => import("./screens/AdminTaxiScreen"));
const AdminTextFlagsScreen = lazyScreen(() => import("./screens/AdminTextFlagsScreen"));
const AdminWaitlistScreen = lazyScreen(() => import("./screens/AdminWaitlistScreen"));
const AdsCabinetScreen = lazyScreen(() => import("./screens/AdsCabinetScreen"));
const AppReviewScreen = lazyScreen(() => import("./screens/AppReviewScreen"));
const BlocklistScreen = lazyScreen(() => import("./screens/BlocklistScreen"));
const ClinicRidesScreen = lazyScreen(() => import("./screens/ClinicRidesScreen"));
const CouponsScreen = lazyScreen(() => import("./screens/CouponsScreen"));
const CourierOnboardingScreen = lazyScreen(() => import("./screens/CourierOnboardingScreen"));
const FairnessCenterScreen = lazyScreen(() => import("./screens/FairnessCenterScreen"));
const IncidentDetailScreen = lazyScreen(() => import("./screens/IncidentDetailScreen"));
const IncomeCalculatorScreen = lazyScreen(() => import("./screens/IncomeCalculatorScreen"));
const InvitesScreen = lazyScreen(() => import("./screens/InvitesScreen"));
const MyStatsScreen = lazyScreen(() => import("./screens/MyStatsScreen"));
const PartnerCabinetScreen = lazyScreen(() => import("./screens/PartnerCabinetScreen"));
const PayDoneScreen = lazyScreen(() => import("./screens/PayDoneScreen"));
const PaymentInfoScreen = lazyScreen(() => import("./screens/PaymentInfoScreen"));
const PretripCheckScreen = lazyScreen(() => import("./screens/PretripCheckScreen"));
const PricingInfoScreen = lazyScreen(() => import("./screens/PricingInfoScreen"));
const PrivacyScreen = lazyScreen(() => import("./screens/PrivacyScreen"));
const PromoCodeScreen = lazyScreen(() => import("./screens/PromoCodeScreen"));
const RepeatTripScreen = lazyScreen(() => import("./screens/RepeatTripScreen"));
const ReportScreen = lazyScreen(() => import("./screens/ReportScreen"));
const RouteWatchesScreen = lazyScreen(() => import("./screens/RouteWatchesScreen"));
const RulesScreen = lazyScreen(() => import("./screens/RulesScreen"));
const SavedPlacesScreen = lazyScreen(() => import("./screens/SavedPlacesScreen"));
const SimpleModeScreen = lazyScreen(() => import("./screens/SimpleModeScreen"));
const SupportYuldashScreen = lazyScreen(() => import("./screens/SupportYuldashScreen"));
const TaxiDocumentsScreen = lazyScreen(() => import("./screens/TaxiDocumentsScreen"));
const CarPhotoScreen = lazyScreen(() => import("./screens/CarPhotoScreen"));
const TaxiOnboardingScreen = lazyScreen(() => import("./screens/TaxiOnboardingScreen"));
const TrustScreen = lazyScreen(() => import("./screens/TrustScreen"));
const VoiceRequestScreen = lazyScreen(() => import("./screens/VoiceRequestScreen"));

const BoostScreen = lazyScreen(() => import("./screens/BoostScreen"));
const CallbackHelpScreen = lazyScreen(() => import("./screens/CallbackHelpScreen"));
const CourierEarningsScreen = lazyScreen(() => import("./screens/CourierEarningsScreen"));
const CourierScreen = lazyScreen(() => import("./screens/CourierScreen"));
const CreateRideScreen = lazyScreen(() => import("./screens/CreateRideScreen"));
const DriverCabinetScreen = lazyScreen(() => import("./screens/DriverCabinetScreen"));
const DriverEarningsScreen = lazyScreen(() => import("./screens/DriverEarningsScreen"));
const DriverProfileScreen = lazyScreen(() => import("./screens/DriverProfileScreen"));
const DriverResponsesScreen = lazyScreen(() => import("./screens/DriverResponsesScreen"));
const DriverTaxiRidesScreen = lazyScreen(() => import("./screens/DriverTaxiRidesScreen"));
const EditProfileScreen = lazyScreen(() => import("./screens/EditProfileScreen"));
const EditRequestScreen = lazyScreen(() => import("./screens/EditRequestScreen"));
const FamilyOrderScreen = lazyScreen(() => import("./screens/FamilyOrderScreen"));
const FiltersScreen = lazyScreen(() => import("./screens/FiltersScreen"));
const HelpScreen = lazyScreen(() => import("./screens/HelpScreen"));
const MyTaxiTripsScreen = lazyScreen(() => import("./screens/MyTaxiTripsScreen"));
const NotificationsScreen = lazyScreen(() => import("./screens/NotificationsScreen"));
const ParcelChatScreen = lazyScreen(() => import("./screens/ParcelChatScreen"));
const ParcelsScreen = lazyScreen(() => import("./screens/ParcelsScreen"));
const PassengerCabinetScreen = lazyScreen(() => import("./screens/PassengerCabinetScreen"));
const RequestResponsesScreen = lazyScreen(() => import("./screens/RequestResponsesScreen"));
const RequestsFeedScreen = lazyScreen(() => import("./screens/RequestsFeedScreen"));
const SafetyScreen = lazyScreen(() => import("./screens/SafetyScreen"));
const ScheduledOrdersScreen = lazyScreen(() => import("./screens/ScheduledOrdersScreen"));
const SettingsScreen = lazyScreen(() => import("./screens/SettingsScreen"));
const SupportTicketScreen = lazyScreen(() => import("./screens/SupportTicketScreen"));
const SupportTicketsScreen = lazyScreen(() => import("./screens/SupportTicketsScreen"));
const TaxiReceiptScreen = lazyScreen(() => import("./screens/TaxiReceiptScreen"));
const TripReceiptScreen = lazyScreen(() => import("./screens/TripReceiptScreen"));
const TrustedContactsScreen = lazyScreen(() => import("./screens/TrustedContactsScreen"));
const VerifyDriverScreen = lazyScreen(() => import("./screens/VerifyDriverScreen"));
const WalletScreen = lazyScreen(() => import("./screens/WalletScreen"));

/** Оболочка с нижней навигацией — для «вкладочных» экранов. */
function Shell() {
  return (
    <div className="app-shell">
      <OfflineBanner />
      {/* Новая версия скачана — применится только после перезагрузки.
          Пока человек не нажал, исправленный баг у него всё ещё есть. */}
      <UpdateBanner />
      <main className="app-main">
        {/* Экран из отдельного куска ещё летит — показываем скелетон,
            а навигация остаётся на месте: моргает только контент. */}
        <Suspense fallback={<LoadingList count={3} />}>
          <Outlet />
        </Suspense>
      </main>
      <InstallPrompt />
      <BottomNav />
    </div>
  );
}

export default function App() {
  // Приложение поднялось — значит куски грузятся. Снимаем метку разовой
  // перезагрузки, чтобы следующее обновление сайта тоже сработало.
  useEffect(() => {
    clearChunkReloadFlag();
  }, []);

  // Накопленные без сети «выехал»/«доехал» и сообщения досылаем на СТАРТЕ, а не
  // только на экране поездки: человек мог закрыть его и больше не открывать.
  useEffect(() => watchOutbox(() => {}), []);

  return (
    <Suspense fallback={<div className="app-main"><LoadingList count={2} /></div>}>
    <Routes>
      {/* Полноэкранные экраны входа/старта — без нижней навигации */}
      <Route path="/splash" element={<SplashScreen />} />
      <Route path="/intro" element={<IntroScreen />} />
      <Route path="/onboarding" element={<OnboardingScreen />} />
      <Route path="/login" element={<LoginScreen />} />

      {/* Приложение с нижней навигацией */}
      <Route element={<Shell />}>
        <Route path="/rides" element={<RidesScreen />} />
        {/* Home-витрина (карта) — публична, гость тоже видит */}
        <Route path="/map" element={<HomeScreen />} />
        <Route path="/chat" element={<ChatInboxScreen />} />
        <Route path="/profile" element={<ProfileScreen />} />
        {/* Согласия — локальные (152-ФЗ), доступны и гостю */}
        <Route path="/consents" element={<ConsentsScreen />} />
        {/* --- Волна 7Б-2: настройки и правовое --- */}
        {/* Приватность и Правила — публичны (юр-документы доступны всем) */}
        <Route path="/privacy" element={<PrivacyScreen />} />
        <Route path="/rules" element={<RulesScreen />} />
        <Route
          path="/settings"
          element={
            <RequireAuth>
              <SettingsScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/blocklist"
          element={
            <RequireAuth>
              <BlocklistScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/report"
          element={
            <RequireAuth>
              <ReportScreen />
            </RequireAuth>
          }
        />
        {/* Фильтры — локальная UX-настройка, вход не нужен */}
        <Route path="/filters" element={<FiltersScreen />} />
        {/* --- Волна 7А: безопасность и доступность --- */}
        {/* SOS — публичен: звонки в службы доступны без входа; «сообщить своим» мягко зовёт войти */}
        <Route path="/sos" element={<SosScreen />} />
        {/* Простой режим — публичный хаб доступности + крупный шрифт */}
        <Route path="/simple" element={<SimpleModeScreen />} />
        {/* «Перезвоните мне» — публичный вход, POST требует аккаунт (мягко на Login) */}
        <Route path="/callback" element={<CallbackHelpScreen />} />
        <Route
          path="/trusted"
          element={
            <RequireAuth>
              <TrustedContactsScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/family-order"
          element={
            <RequireAuth>
              <FamilyOrderScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/voice"
          element={
            <RequireAuth>
              <VoiceRequestScreen />
            </RequireAuth>
          }
        />
        {/* Клиники — публичная витрина «поездки к клинике» */}
        <Route path="/clinics" element={<ClinicRidesScreen />} />
        {/* --- Волна 7Б: поддержка и помощь --- */}
        {/* Помощь / FAQ — публична (частые вопросы полезны и гостю) */}
        <Route path="/help" element={<HelpScreen />} />
        <Route
          path="/notifications"
          element={
            <RequireAuth>
              <NotificationsScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/support"
          element={
            <RequireAuth>
              <SupportTicketsScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/support/:id"
          element={
            <RequireAuth>
              <SupportTicketScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/app-review"
          element={
            <RequireAuth>
              <AppReviewScreen />
            </RequireAuth>
          }
        />
        {/* Публичный профиль водителя — открывается тапом с карточки поездки */}
        <Route path="/drivers/:id" element={<DriverProfileScreen />} />
        {/* Приватное — только с токеном */}
        <Route
          path="/request"
          element={
            <RequireAuth>
              <CreateRequestScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/requests-feed"
          element={
            <RequireAuth>
              <RequestsFeedScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/requests/:id/responses"
          element={
            <RequireAuth>
              <RequestResponsesScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/requests/:id/edit"
          element={
            <RequireAuth>
              <EditRequestScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/booking/:id"
          element={
            <RequireAuth>
              <BookingScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/trip/:id"
          element={
            <RequireAuth>
              <ActiveTripScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/receipt/:id"
          element={
            <RequireAuth>
              <TripReceiptScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/trust"
          element={
            <RequireAuth>
              <TrustScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/invites"
          element={
            <RequireAuth>
              <InvitesScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/cabinet"
          element={
            <RequireAuth>
              <PassengerCabinetScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/places"
          element={
            <RequireAuth>
              <SavedPlacesScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/repeat"
          element={
            <RequireAuth>
              <RepeatTripScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/stats"
          element={
            <RequireAuth>
              <MyStatsScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/route-watches"
          element={
            <RequireAuth>
              <RouteWatchesScreen />
            </RequireAuth>
          }
        />
        {/* --- Волна 3: водитель --- */}
        <Route
          path="/driver"
          element={
            <RequireAuth>
              <DriverCabinetScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/create-ride"
          element={
            <RequireAuth>
              <CreateRideScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/earnings"
          element={
            <RequireAuth>
              <DriverEarningsScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/boost"
          element={
            <RequireAuth>
              <BoostScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/support-yuldash"
          element={
            <RequireAuth>
              <SupportYuldashScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/verify-driver"
          element={
            <RequireAuth>
              <VerifyDriverScreen />
            </RequireAuth>
          }
        />

        {/* --- Волна 4: такси --- */}
        <Route
          path="/taxi"
          element={
            <RequireAuth>
              <InstantOrderScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/taxi-drive"
          element={
            <RequireAuth>
              <InstantDriverTripScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/taxi-chat/:orderId"
          element={
            <RequireAuth>
              <InstantChatScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/scheduled"
          element={
            <RequireAuth>
              <ScheduledOrdersScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/taxi-onboarding"
          element={
            <RequireAuth>
              <TaxiOnboardingScreen />
            </RequireAuth>
          }
        />

        {/* --- Волна Е1: чеки, история, деньги и документы такси --- */}
        <Route
          path="/taxi-receipt/:orderId"
          element={
            <RequireAuth>
              <TaxiReceiptScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/my-taxi"
          element={
            <RequireAuth>
              <MyTaxiTripsScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/taxi-rides"
          element={
            <RequireAuth>
              <DriverTaxiRidesScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/taxi-docs"
          element={
            <RequireAuth>
              <TaxiDocumentsScreen />
            </RequireAuth>
          }
        />
        {/* Фотоконтроль машины (580-ФЗ). Режим — в адресе: ?mode=courier для доставки. */}
        <Route
          path="/car-photo"
          element={
            <RequireAuth>
              <CarPhotoScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/pretrip"
          element={
            <RequireAuth>
              <PretripCheckScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/my-responses"
          element={
            <RequireAuth>
              <DriverResponsesScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/courier-earnings"
          element={
            <RequireAuth>
              <CourierEarningsScreen />
            </RequireAuth>
          }
        />

        {/* --- Волна Е2: доверие, споры, чат посылки --- */}
        <Route
          path="/profile/edit"
          element={
            <RequireAuth>
              <EditProfileScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/safety"
          element={
            <RequireAuth>
              <SafetyScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/fairness"
          element={
            <RequireAuth>
              <FairnessCenterScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/incidents/:id"
          element={
            <RequireAuth>
              <IncidentDetailScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/parcel-chat/:parcelId"
          element={
            <RequireAuth>
              <ParcelChatScreen />
            </RequireAuth>
          }
        />

        {/* --- Волна 5: курьер и посылки --- */}
        <Route
          path="/parcels"
          element={
            <RequireAuth>
              <ParcelsScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/courier"
          element={
            <RequireAuth>
              <CourierScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/courier-onboarding"
          element={
            <RequireAuth>
              <CourierOnboardingScreen />
            </RequireAuth>
          }
        />

        {/* --- Волна 6: деньги и маркетплейс --- */}
        {/* «Скидки по пути» и «Как оплатить» — публичные витрины */}
        <Route path="/coupons" element={<CouponsScreen />} />
        <Route path="/payment-info" element={<PaymentInfoScreen />} />
        {/* Сюда банк возвращает после оплаты картой (payment_return_url на сервере).
            Роута не было — человек попадал на заставку и не знал, прошла ли оплата. */}
        <Route path="/pay/done" element={<PayDoneScreen />} />
        {/* «Честно о цене» — публичная витрина (паритет с android PricingInfo) */}
        <Route path="/pricing" element={<PricingInfoScreen />} />
        <Route
          path="/wallet"
          element={
            <RequireAuth>
              <WalletScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/promo"
          element={
            <RequireAuth>
              <PromoCodeScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/partner"
          element={
            <RequireAuth>
              <PartnerCabinetScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/ads"
          element={
            <RequireAuth>
              <AdsCabinetScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/ads/new"
          element={
            <RequireAuth>
              <AdEditorScreen />
            </RequireAuth>
          }
        />
        <Route
          path="/ads/:id/edit"
          element={
            <RequireAuth>
              <AdEditorScreen />
            </RequireAuth>
          }
        />

        {/* --- Волна 8А: админка (ядро модерации) — только role == "admin" --- */}
        <Route
          path="/admin"
          element={
            <RequireAdmin>
              <AdminCabinetScreen />
            </RequireAdmin>
          }
        />
        <Route
          path="/admin/request"
          element={
            <RequireAdmin>
              <AdminRequestScreen />
            </RequireAdmin>
          }
        />
        <Route
          path="/admin/responses"
          element={
            <RequireAdmin>
              <AdminResponsesScreen />
            </RequireAdmin>
          }
        />
        <Route
          path="/admin/drivers"
          element={
            <RequireAdmin>
              <AdminDriversScreen />
            </RequireAdmin>
          }
        />
        <Route
          path="/admin/reports"
          element={
            <RequireAdmin>
              <AdminReportsScreen />
            </RequireAdmin>
          }
        />
        <Route
          path="/admin/payment-requests"
          element={
            <RequireAdmin>
              <AdminPaymentRequestsScreen />
            </RequireAdmin>
          }
        />

        {/* --- Волна 8Б: отзывы, реклама, такси, лист ожидания, пульс, доход --- */}
        <Route
          path="/admin/reviews"
          element={
            <RequireAdmin>
              <AdminReviewsScreen />
            </RequireAdmin>
          }
        />
        <Route
          path="/admin/ads"
          element={
            <RequireAdmin>
              <AdminAdsScreen />
            </RequireAdmin>
          }
        />
        <Route
          path="/admin/taxi"
          element={
            <RequireAdmin>
              <AdminTaxiScreen />
            </RequireAdmin>
          }
        />
        <Route
          path="/admin/waitlist"
          element={
            <RequireAdmin>
              <AdminWaitlistScreen />
            </RequireAdmin>
          }
        />
        <Route
          path="/admin/taxi-pulse"
          element={
            <RequireAdmin>
              <AdminTaxiPulseScreen />
            </RequireAdmin>
          }
        />
        <Route
          path="/admin/income"
          element={
            <RequireAdmin>
              <IncomeCalculatorScreen />
            </RequireAdmin>
          }
        />

        {/* --- Волна 8В: бизнесы, промо, посылки, курьеры (завершает админку) --- */}
        <Route
          path="/admin/partners"
          element={
            <RequireAdmin>
              <AdminPartnersScreen />
            </RequireAdmin>
          }
        />
        <Route
          path="/admin/promo"
          element={
            <RequireAdmin>
              <AdminPromoScreen />
            </RequireAdmin>
          }
        />
        <Route
          path="/admin/parcels"
          element={
            <RequireAdmin>
              <AdminParcelsScreen />
            </RequireAdmin>
          }
        />
        <Route
          path="/admin/courier"
          element={
            <RequireAdmin>
              <AdminCourierScreen />
            </RequireAdmin>
          }
        />

        {/* --- Волна Е3: SOS, споры, помеченные тексты, очередь модерации --- */}
        <Route
          path="/admin/sos"
          element={
            <RequireAdmin>
              <AdminSosScreen />
            </RequireAdmin>
          }
        />
        <Route
          path="/admin/incidents"
          element={
            <RequireAdmin>
              <AdminIncidentsScreen />
            </RequireAdmin>
          }
        />
        <Route
          path="/admin/text-flags"
          element={
            <RequireAdmin>
              <AdminTextFlagsScreen />
            </RequireAdmin>
          }
        />
        <Route
          path="/admin/pretrip"
          element={
            <RequireAdmin>
              <AdminPretripScreen />
            </RequireAdmin>
          }
        />
        <Route
          path="/admin/debts"
          element={
            <RequireAdmin>
              <AdminDebtsScreen />
            </RequireAdmin>
          }
        />
        <Route
          path="/admin/support"
          element={
            <RequireAdmin>
              <AdminSupportScreen />
            </RequireAdmin>
          }
        />
        <Route
          path="/admin/moderation"
          element={
            <RequireAdmin>
              <AdminModerationScreen />
            </RequireAdmin>
          }
        />
      </Route>

      {/* Старт → сплэш решает, куда дальше */}
      <Route path="/" element={<Navigate to="/splash" replace />} />
      <Route path="*" element={<Navigate to="/splash" replace />} />
    </Routes>
    </Suspense>
  );
}
