import { Navigate, Outlet, Route, Routes } from "react-router-dom";
import { useLang } from "./i18n/lang";
import BottomNav from "./components/BottomNav";
import InstallPrompt from "./components/InstallPrompt";
import RequireAuth from "./components/RequireAuth";
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

/** Оболочка с нижней навигацией — для «вкладочных» экранов. */
function Shell() {
  return (
    <div className="app-shell">
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
        {/* Фильтры — локальная UX-настройка, вход не нужен */}
        <Route path="/filters" element={<FiltersScreen />} />
        {/* Клиники — публичная витрина «поездки к клинике» */}
        <Route path="/clinics" element={<ClinicRidesScreen />} />
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
      </Route>

      {/* Старт → сплэш решает, куда дальше */}
      <Route path="/" element={<Navigate to="/splash" replace />} />
      <Route path="*" element={<Navigate to="/splash" replace />} />
    </Routes>
  );
}
