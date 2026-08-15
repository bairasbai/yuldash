import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App";
import { LangProvider } from "./i18n/lang";
import { AuthProvider } from "./auth/AuthProvider";
import { applyFontScale } from "./fontScale";
import { applyTheme } from "./theme";
import "./index.css";
import "./ui.css";

// Тема и крупный шрифт — применяем ДО первого кадра, чтобы не мигало.
applyTheme();
applyFontScale();

// Service worker регистрирует UpdateBanner: ему же нужен колбэк «есть новая
// версия», а две регистрации подряд — лишний повод для гонки.

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <BrowserRouter>
      <LangProvider>
        <AuthProvider>
          <App />
        </AuthProvider>
      </LangProvider>
    </BrowserRouter>
  </StrictMode>
);
