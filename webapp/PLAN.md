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

### ✅ Волна 1 — Вход и старт (готово)
Splash, Intro (морф «Попутчик»→«Юлдаш»), Onboarding (слайды + язык + роль + простой режим),
Login (вход по коду через Telegram + токен), Consents (152-ФЗ), Trust (уровни L0–L3), Invites (инвайт-коды).
Плюс инфраструктура авторизации: `me()`, `AuthProvider` (точка правды сессии), `RequireAuth` (защита приватных
маршрутов), тихий refresh access-токена, logout.

**Как реализовано (по реальному backend):**
- **Вход = Telegram** (`auth.py`): `POST /auth/tg/start` → `request_id` → открыть `t.me/<VITE_TELEGRAM_BOT>?start=<id>` →
  бот шлёт 6-значный код → `POST /auth/tg/verify {request_id, code}` → `{access_token, refresh_token, user}`.
  Ошибки различаем: 403 phone_required (нужен номер в боте), 409 код ещё идёт, 410 истёк, 429 много попыток, 400 неверный.
  SMS-вход — спокойная заглушка (нет юрлица).
- **Сессия** — `AuthProvider` (`src/auth/`): на старте `GET /me` при наличии токена; хранит `user/status`,
  `login/logout/refresh/isAuthed`. Токены — в `client.ts` (`yuldash.token` + `yuldash.refresh`).
  Тихий refresh: на 401 клиент один раз дергает `POST /auth/refresh` и повторяет запрос; иначе — logout.
- **Защита маршрутов** — `RequireAuth`: приватное (`/trust`, `/invites`) без токена → `/login` (с возвратом).
  Публичное (лента `/rides`, карта) — без входа. `/consents` доступны и гостю (локальные).
- **Invites** = реальный реферал (`referral.py`): `GET /referral/me` (code/invited/credits/redeemed),
  `POST /referral/redeem {code}`.
- **Trust**: у backend нет `/me/trust` — уровень L0–L3 честно считаем из реальных полей `GET /me`
  (номер, `verified`) и числа приглашённых (`/referral/me`). Без выдуманного API.
- **Consents (152-ФЗ)**: у backend нет эндпоинта согласий — отметку храним локально (`flags.ts`),
  когда появится `/me/consents` — заменим один слой (api).

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
