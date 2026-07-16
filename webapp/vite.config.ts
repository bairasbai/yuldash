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
        navigateFallback: "/index.html",
        // Подключаем наш обработчик Web Push (push / notificationclick) к
        // сгенерированному Workbox SW. autoUpdate и кеш остаются как есть —
        // мы лишь ДОБАВЛЯЕМ слушатели (см. public/push-sw.js).
        importScripts: ["push-sw.js"],
        // Ленту поездок не кешируем агрессивно — сеть в приоритете, кеш как фолбэк.
        runtimeCaching: [
          {
            urlPattern: ({ url }) => url.pathname.startsWith("/rides") || url.pathname.startsWith("/feed"),
            handler: "NetworkFirst",
            options: {
              cacheName: "yuldash-api",
              networkTimeoutSeconds: 6,
              expiration: { maxEntries: 40, maxAgeSeconds: 60 * 5 },
            },
          },
        ],
      },
      devOptions: { enabled: false },
    }),
  ],
});
