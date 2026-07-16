import { Navigate, Outlet, Route, Routes } from "react-router-dom";
import { useLang } from "./i18n/lang";
import BottomNav from "./components/BottomNav";
import InstallPrompt from "./components/InstallPrompt";
import OfflineBanner from "./components/OfflineBanner";
import RequireAuth from "./components/RequireAuth";
import RequireAdmin from "./components/RequireAdmin";
import RidesScreen from "./screens/RidesScreen";
import StubScreen from "./screens/StubScreen";
import SplashScreen from "./screens/SplashScreen";
import IntroScreen from "./screens/IntroScreen";
import OnboardingScreen from "./screens/OnboardingScreen";
import LoginScreen from "./screens/LoginScreen";
import ProfileScreen from "./screens/ProfileScreen";
import ConsentsScreen from "./screens/ConsentsScreen";
import TrustScreen from "./screens/TrustScreen";
import InvitesScreen from "./screens/InvitesScreen";
import HomeScreen from "./screens/HomeScreen";
import CreateRequestScreen from "./screens/CreateRequestScreen";
import RequestsFeedScreen from "./screens/RequestsFeedScreen";
import RequestResponsesScreen from "./screens/RequestResponsesScreen";
import BookingScreen from "./screens/BookingScreen";
import ActiveTripScreen from "./screens/ActiveTripScreen";
import TripReceiptScreen from "./screens/TripReceiptScreen";
import FiltersScreen from "./screens/FiltersScreen";
import SavedPlacesScreen from "./screens/SavedPlacesScreen";
import RepeatTripScreen from "./screens/RepeatTripScreen";
import MyStatsScreen from "./screens/MyStatsScreen";
import RouteWatchesScreen from "./screens/RouteWatchesScreen";
import ClinicRidesScreen from "./screens/ClinicRidesScreen";
import PassengerCabinetScreen from "./screens/PassengerCabinetScreen";
import CreateRideScreen from "./screens/CreateRideScreen";
import DriverCabinetScreen from "./screens/DriverCabinetScreen";
import DriverProfileScreen from "./screens/DriverProfileScreen";
import DriverEarningsScreen from "./screens/DriverEarningsScreen";
import BoostScreen from "./screens/BoostScreen";
import VerifyDriverScreen from "./screens/VerifyDriverScreen";
import InstantOrderScreen from "./screens/InstantOrderScreen";
import InstantDriverTripScreen from "./screens/InstantDriverTripScreen";
import InstantChatScreen from "./screens/InstantChatScreen";
import ScheduledOrdersScreen from "./screens/ScheduledOrdersScreen";
import TaxiOnboardingScreen from "./screens/TaxiOnboardingScreen";
import CourierOnboardingScreen from "./screens/CourierOnboardingScreen";
import CourierScreen from "./screens/CourierScreen";
import ParcelsScreen from "./screens/ParcelsScreen";
import WalletScreen from "./screens/WalletScreen";
import CouponsScreen from "./screens/CouponsScreen";
import PromoCodeScreen from "./screens/PromoCodeScreen";
import PartnerCabinetScreen from "./screens/PartnerCabinetScreen";
import AdsCabinetScreen from "./screens/AdsCabinetScreen";
import AdEditorScreen from "./screens/AdEditorScreen";
import PaymentInfoScreen from "./screens/PaymentInfoScreen";
import SosScreen from "./screens/SosScreen";
import TrustedContactsScreen from "./screens/TrustedContactsScreen";
import FamilyOrderScreen from "./screens/FamilyOrderScreen";
import CallbackHelpScreen from "./screens/CallbackHelpScreen";
import VoiceRequestScreen from "./screens/VoiceRequestScreen";
import SimpleModeScreen from "./screens/SimpleModeScreen";
import NotificationsScreen from "./screens/NotificationsScreen";
import SupportTicketsScreen from "./screens/SupportTicketsScreen";
import SupportTicketScreen from "./screens/SupportTicketScreen";
import HelpScreen from "./screens/HelpScreen";
import AppReviewScreen from "./screens/AppReviewScreen";
import SettingsScreen from "./screens/SettingsScreen";
import PrivacyScreen from "./screens/PrivacyScreen";
import RulesScreen from "./screens/RulesScreen";
import BlocklistScreen from "./screens/BlocklistScreen";
import ReportScreen from "./screens/ReportScreen";
import AdminCabinetScreen from "./screens/AdminCabinetScreen";
import AdminRequestScreen from "./screens/AdminRequestScreen";
import AdminResponsesScreen from "./screens/AdminResponsesScreen";
import AdminDriversScreen from "./screens/AdminDriversScreen";
import AdminReportsScreen from "./screens/AdminReportsScreen";
import AdminPaymentRequestsScreen from "./screens/AdminPaymentRequestsScreen";
import AdminReviewsScreen from "./screens/AdminReviewsScreen";
import AdminAdsScreen from "./screens/AdminAdsScreen";
import AdminTaxiScreen from "./screens/AdminTaxiScreen";
import AdminWaitlistScreen from "./screens/AdminWaitlistScreen";
import AdminTaxiPulseScreen from "./screens/AdminTaxiPulseScreen";
import IncomeCalculatorScreen from "./screens/IncomeCalculatorScreen";
import AdminPartnersScreen from "./screens/AdminPartnersScreen";
import AdminPromoScreen from "./screens/AdminPromoScreen";
import AdminParcelsScreen from "./screens/AdminParcelsScreen";
import AdminCourierScreen from "./screens/AdminCourierScreen";

/** Оболочка с нижней навигацией — для «вкладочных» экранов. */
function Shell() {
  return (
    <div className="app-shell">
      <OfflineBanner />
      <main className="app-main">
        <Outlet />
      </main>
      <InstallPrompt />
      <BottomNav />
    </div>
  );
}

export default function App() {
  const { t } = useLang();
  return (
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
        <Route path="/chat" element={<StubScreen title={t("navChat")} emoji="💬" />} />
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
      </Route>

      {/* Старт → сплэш решает, куда дальше */}
      <Route path="/" element={<Navigate to="/splash" replace />} />
      <Route path="*" element={<Navigate to="/splash" replace />} />
    </Routes>
  );
}
