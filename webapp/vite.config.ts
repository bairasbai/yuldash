import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";

// Юлдаш PWA. Service worker кеширует app shell (офлайн-оболочка).
// registerType: autoUpdate — новая версия подтягивается сама.
export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: "autoUpdate",
      includeAssets: ["favicon.ico", "apple-touch-icon.png"],
      manifest: false, // используем свой public/manifest.webmanifest
      workbox: {
        globPatterns: ["**/*.{js,css,html,ico,png,svg,woff2}"],
        // Тяжёлые hero-картинки (> 2 МБ) не кладём в precache app-shell —
        // это экранные ассеты (онбординг/логин), грузятся по требованию.
        globIgnores: [
          "**/onboarding_bashkir_hero.png",
          "**/login_salavat_yulaev_hero.png",
        ],
        navigateFallback: "/index.html",
        // Подключаем наш обработчик Web Push (push / notificationclick) к
        // сгенерированному Workbox SW. autoUpdate и кеш остаются как есть —
        // мы лишь ДОБАВЛЯЕМ слушатели (см. public/push-sw.js).
        importScripts: ["push-sw.js"],
        // P1-1: приватные ленты (/rides, /feed — имена, маршруты, координаты попутчиков) НЕ
        // кешируем на диск. Иначе на общем устройстве кеш переживает logout и читается через
        // DevTools → Cache Storage. App-shell (precache выше) достаточно для офлайн-оболочки.
        runtimeCaching: [],
      },
      devOptions: { enabled: false },
    }),
  ],
});
