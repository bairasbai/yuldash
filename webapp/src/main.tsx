import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { registerSW } from "virtual:pwa-register";
import App from "./App";
import { LangProvider } from "./i18n/lang";
import { AuthProvider } from "./auth/AuthProvider";
import "./index.css";
import "./ui.css";

// Service worker: автообновление (registerType: autoUpdate в vite.config.ts).
registerSW({ immediate: true });

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
