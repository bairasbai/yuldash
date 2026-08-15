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
        globPatterns: ["**/*.{js,css,html,ico,png,webp,svg,woff2}"],
        // Картинки онбординга и входа человек видит один раз, и они уже лёгкие
        // (были PNG по 2 МБ, стали WebP по ~130 КБ). В офлайн-оболочку их
        // всё равно не кладём — грузятся по требованию, когда экран открылся.
        globIgnores: [
          "**/onboarding_bashkir_hero.webp",
          "**/login_salavat_yulaev_hero.webp",
          // Админка — отдельными кусками и НЕ в офлайн-оболочке: её открывает
          // один человек, у которого всегда есть сеть. Класть её всем в кеш —
          // это лишние мегабайты мобильного трафика при первом визите.
          "**/assets/Admin*.js",
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
