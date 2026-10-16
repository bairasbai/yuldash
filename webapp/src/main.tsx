import StorageBoundary from "./components/StorageBoundary";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App";
import { LangProvider } from "./i18n/lang";
import { AuthProvider } from "./auth/AuthProvider";
import { OwnBookingsProvider } from "./booking/OwnBookingsProvider";
import { applyFontScale } from "./fontScale";
import { applyTheme } from "./theme";
import "./index.css";
import "./ui.css";

// Тема и крупный шрифт — применяем ДО первого кадра, чтобы не мигало.
function AppBootstrap() {
  applyTheme();
  applyFontScale();
  return (

// Service worker регистрирует UpdateBanner: ему же нужен колбэк «есть новая
// версия», а две регистрации подряд — лишний повод для гонки.

  <StrictMode>
    <BrowserRouter>
      <LangProvider>
        <AuthProvider>
          <OwnBookingsProvider>
            <App />
          </OwnBookingsProvider>
        </AuthProvider>
      </LangProvider>
    </BrowserRouter>
  </StrictMode>
  );
}

createRoot(document.getElementById("root")!).render(<StorageBoundary><AppBootstrap /></StorageBoundary>);
