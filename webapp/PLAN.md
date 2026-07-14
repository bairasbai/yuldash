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

#### ✅ Волна 2А — Ядро попутки, пассажир (готово, сборка зелёная)
7 экранов ядра + карта. Все состояния (загрузка/пусто/ошибка), два языка (ба-черновик → `BASHKIR_DRAFT.md`),
токены Canon, safe-area, тач-цели ≥48px, анимации появления/шторки.

**Экраны и роуты:**
- `HomeScreen` → `/map` (публично): карта Яндекс + пины ближайших заявок, карусель быстрых действий,
  фильтр «Ближайшие» (геолокация), список поездок рядом, тап → шторка `RideSheet` с бронированием.
- `CreateRequestScreen` → `/request` (RequireAuth): форма заявки + сворачиваемый блок «Дополнительно»
  (условия / «только для своих» / комментарий). `POST /requests`. Успех → ссылка на отклики.
- `RequestsFeedScreen` → `/requests-feed` (RequireAuth): лента заявок для водителя `GET /requests/feed`
  + отклик `POST /requests/{id}/respond` (шторка цена+коммент).
- `RequestResponsesScreen` → `/requests/:id/responses` (RequireAuth): отклики на мою заявку
  `GET /requests/{id}/responses`, принять `POST /responses/{id}/accept` → `booking_id` → активная поездка.
- `BookingScreen` → `/booking/:id` (RequireAuth): `GET /bookings/{id}/details`, карта концов маршрута,
  телефон/точка после подтверждения (`contact_unlocked`), отмена брони.
- `ActiveTripScreen` → `/trip/:id` (RequireAuth): live-статус (поллинг `GET /bookings/{id}/role`),
  карта маршрута + live-точка водителя (WS `/ws/trip/{id}/location`), код посадки
  (`GET /bookings/{id}/boarding-code`), чат брони (REST `GET/POST /bookings/{id}/messages` + WS
  `/ws/bookings/{id}`, поллинг-фолбэк), оплата read-only, SOS-заглушка, оценка `POST /bookings/{id}/rate`.
- `TripReceiptScreen` → `/receipt/:id` (RequireAuth): `GET /trips/{id}/receipt`; 404 (нет на проде до
  release-2026-07) / 409 (не завершена) → мягкая деградация без краша.

**Инфраструктура:**
- `components/YandexMap.tsx` — грузит Яндекс JS API 2.1 по `VITE_YANDEX_MAPS_JS_KEY` (единожды).
  Маршрут A→B (зелёная линия), назначение (золотой), «моё место» (зелёный), свободные маркеры.
  Без ключа / ошибка загрузки → брендовый плейсхолдер «Карта подключится с ключом» (экран цел).
- API-слой (зеркало release-бэка): `api/requests.ts`, `api/bookings.ts`, `api/chat.ts` (+WS),
  `api/discovery.ts` (near/geocode/popular), `api/share.ts` (превью), расширен `api/rides.ts`.
- `utils/format.ts` (дата/цена/оплата), `components/StatusPill.tsx`, `RideSheet.tsx`.
- `.env.example` + `vite-env.d.ts`: добавлен `VITE_YANDEX_MAPS_JS_KEY`.

**Зависит от деплоя release-2026-07:** квитанция `GET /trips/{id}/receipt` — до мержа отдаёт 404,
экран показывает мягкое состояние «квитанция появится после обновления».

#### ✅ Волна 2Б — Доп. экраны пассажира (готово, сборка зелёная)
7 экранов + фильтры на ленте/карте. Все состояния (загрузка/пусто/ошибка), два языка
(ба-черновик → `BASHKIR_DRAFT.md`), токены Canon, safe-area, тач-цели ≥48px, мягкая деградация 404.

**Экраны и роуты:**
- `FiltersScreen` → `/filters` (публично): фильтры по умолчанию (город/цена/удобства/«только свои»),
  хранятся локально (`src/filterPrefs.ts`, localStorage). Применяются клиентски к ленте `RidesScreen`
  и списку «Поездки рядом» на `HomeScreen` (чип «Фильтры», отдельное пустое состояние «ничего под фильтры»).
- `SavedPlacesScreen` → `/places` (RequireAuth): «Мои адреса» (Дом/Работа/свои). `GET/POST /places/saved`,
  `DELETE /places/saved/{id}`. Ручной ввод адреса + подсказки геокодера (`/geocode`, мягко: нет — просто ввод).
- `RepeatTripScreen` → `/repeat` (RequireAuth): частые маршруты `GET /my-routes`, фолбэк `GET /places/recent`.
  Тап → `POST /requests` из выбранного → отклики.
- `MyStatsScreen` → `/stats` (RequireAuth): `GET /me/stats` — км/поездки/₽/CO₂, звание + прогресс,
  «Поделиться» (Web Share API, фолбэк — копирование). Нули для новичка.
- `RouteWatchesScreen` → `/route-watches` (RequireAuth): подписки на маршрут. `GET/POST /route-watch`,
  `DELETE /route-watch/{id}`. Направление forward/both.
- `ClinicRidesScreen` → `/clinics` (публично): `GET /medical-partners`, выбор клиники →
  `GET /medical-partners/{id}/rides` (публичная витрина + шторка брони). 404 → «скоро».
- `PassengerCabinetScreen` → `/cabinet` (RequireAuth): `GET /bookings/mine` — активная бронь
  (pending→Booking, confirmed/onboard→ActiveTrip), история (done→квитанция), плюс карточки-ссылки
  на Адреса/Повтор/Мой Юлдаш/Подписки/Квитанции/Кошелёк (заглушка до волны 6).

**Инфраструктура:** `api/places.ts`, `api/stats.ts`, `api/routeWatch.ts`, `api/medical.ts`,
`fetchMyRoutes` в `api/discovery.ts`, `apiDelete` в `api/client.ts`, `filterPrefs.ts` (localStorage +
`applyRideFilters`), новые иконки в `components/Icons.tsx`. Профиль связан со всеми разделами.

**Зависит от деплоя release-2026-07:** `/places/*`, `/me/stats`, `/route-watch`, `/medical-partners*`,
`/my-routes` — на проде появятся после мержа release. До мержа отдают 404 → экраны показывают
мягкое пустое/«скоро» состояние без краша.

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
