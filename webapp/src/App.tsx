import { Navigate, Route, Routes } from "react-router-dom";
import { useLang } from "./i18n/lang";
import BottomNav from "./components/BottomNav";
import InstallPrompt from "./components/InstallPrompt";
import RidesScreen from "./screens/RidesScreen";
import StubScreen from "./screens/StubScreen";

export default function App() {
  const { t } = useLang();
  return (
    <div className="app-shell">
      <main className="app-main">
        <Routes>
          <Route path="/" element={<Navigate to="/rides" replace />} />
          <Route path="/rides" element={<RidesScreen />} />
          <Route
            path="/map"
            element={<StubScreen title={t("navMap")} emoji="🗺️" />}
          />
          <Route
            path="/request"
            element={<StubScreen title={t("navRequest")} emoji="📝" />}
          />
          <Route
            path="/chat"
            element={<StubScreen title={t("navChat")} emoji="💬" />}
          />
          <Route
            path="/profile"
            element={<StubScreen title={t("navProfile")} emoji="👤" />}
          />
          <Route path="*" element={<Navigate to="/rides" replace />} />
        </Routes>
      </main>

      <InstallPrompt />
      <BottomNav />
    </div>
  );
}
