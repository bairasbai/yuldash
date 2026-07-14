# 📱 Юлдаш PWA — план «1 в 1 как приложение» для айфона

> Веб-версия приложения (Vite+React+PWA), которую пользователь открывает в Safari и добавляет
> на экран «Домой» → работает как приложение. Тот же бэкенд `yulbash.ru`, дизайн Canon 1:1, RU/BA.
> Ветка: `feat/webapp-pwa`. Папка: `webapp/`. Android/backend/web/mobile не трогаем.

## Архитектура (единая)
- Vite + React + TS + vite-plugin-pwa (Workbox), react-router-dom.
- Точка правды API — `src/api/client.ts` (base `VITE_API_BASE`, Bearer-токен, apiGet/apiPost, 401).
- Точка правды i18n — `src/i18n/dict.ts` + `useLang()`/`appText(ru,ba)`; черновой ба помечен DRAFT.
- Тема Canon (светлая/тёмная), safe-area, тач-цели ≥48px, крупный шрифт.
- Карта — Яндекс Карты **JS API** (не нативный MapKit).
- Realtime (чат/гео/лента) — WebSocket, как в приложении.

## Волны (порядок = зависимости). После каждой — зелёный `npm run build` + пуш.

### ✅ Волна 0 — Фундамент (готово)
Каркас, тема, i18n, API-клиент, оболочка + нижняя навигация, установка на экран, живая лента `GET /rides`.

### Волна 1 — Вход и старт (7)
Splash, Intro (морф-заставка), Onboarding (+ предложение простого режима), Login (вход по коду + токен),
Consents (152-ФЗ), Trust (уровни L0–L3), Invites (инвайт-коды). Плюс `me()` и защита приватных маршрутов.

### Волна 2 — Попутка, пассажир (14)
Home (карта Яндекс JS), CreateRequest (форма + «Дополнительно»), RequestsFeed, RequestResponses,
Booking, ActiveTrip (чат брони + код посадки + live-статус), TripReceipt, Filters, SavedPlaces (Дом/Работа),
RepeatTrip, MyStats, RouteWatches, ClinicRides, PassengerCabinet.

### Волна 3 — Попутка, водитель (6)
CreateRide, DriverCabinet, DriverProfile (публичный), DriverEarnings, Boost, VerifyDriver.

### Волна 4 — Такси (5)
InstantOrder (пассажир), InstantDriverTrip, InstantChat, ScheduledOrders (предзаказ), TaxiOnboarding.

### Волна 5 — Курьер и посылки (4)
CourierOnboarding, Courier (режим курьера), Parcels (отправить/возить), кабинет курьера.

### Волна 6 — Деньги и маркетплейс (7)
Wallet, Coupons («Скидки по пути»), PartnerCabinet, PromoCode, AdsCabinet, AdEditor, PaymentInfo.

### Волна 7 — Доверие/безопасность/поддержка/настройки (17)
Sos, TrustedContacts, FamilyOrder, CallbackHelp, VoiceRequest, SimpleMode + крупный шрифт, Notifications,
Support, SupportTickets, SupportTicket, Settings, Privacy, Rules, Blocklist, Report, Help, AppReview.

### Волна 8 — Админка (16)
AdminCabinet, AdminRequest, AdminResponses, AdminDrivers, AdminReports, AdminPaymentRequests, AdminReviews,
AdminAds, AdminTaxi, AdminWaitlist, AdminTaxiPulse, IncomeCalculator, AdminPartners, AdminPromo, AdminParcels, AdminCourier.

### Волна 9 — Полировка + деплой
Web Push (iOS 16.4+, только после установки на экран, VAPID), офлайн-кеш, тёмная тема/крупный шрифт финал,
деплой статики `dist/` на `app.yulbash.ru` (nginx).

## Веб-ограничения (адаптируем, не буквально 1:1)
- Фоновый GPS-трекинг поездки — только при открытом приложении (iOS PWA).
- Полноэкранный «звонок» оффера такси — баннер + звук/вибро.
- Карта — Яндекс JS вместо нативного MapKit.

## Операционка за Александром (когда дойдём до деплоя)
- Ключ Яндекс Карт **JS** для домена `app.yulbash.ru` (отдельный от мобильного).
- CORS: добавить `app.yulbash.ru` в `CORS_ORIGINS` на бэке.
- VAPID-ключи для веб-пушей.
- Поддомен `app.yulbash.ru` (DNS + nginx).
