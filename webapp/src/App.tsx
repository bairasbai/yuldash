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
        <Route path="/map" element={<StubScreen title={t("navMap")} emoji="🗺️" />} />
        <Route path="/request" element={<StubScreen title={t("navRequest")} emoji="📝" />} />
        <Route path="/chat" element={<StubScreen title={t("navChat")} emoji="💬" />} />
        <Route path="/profile" element={<ProfileScreen />} />
        {/* Согласия — локальные (152-ФЗ), доступны и гостю */}
        <Route path="/consents" element={<ConsentsScreen />} />
        {/* Приватное — только с токеном */}
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
      </Route>

      {/* Старт → сплэш решает, куда дальше */}
      <Route path="/" element={<Navigate to="/splash" replace />} />
      <Route path="*" element={<Navigate to="/splash" replace />} />
    </Routes>
  );
}
