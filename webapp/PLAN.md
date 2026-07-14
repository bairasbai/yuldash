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

### ✅ Волна 3 — Попутка, водитель (6) (готово, сборка зелёная)
6 экранов + связки. Все состояния (загрузка/пусто/ошибка/«скоро»), два языка
(ба-черновик → `BASHKIR_DRAFT.md`), токены Canon, safe-area, тач-цели ≥48px,
мягкая деградация 404/«скоро».

**Экраны и роуты:**
- `CreateRideScreen` → `/create-ride` (RequireAuth): публикация поездки водителем.
  Маршрут (откуда/куда), тип (обычная/срочно/«В больницу» → выбор клиники из
  `GET /medical-partners`, привязка `partner_id`), дата/время, места, цена, удобства
  (чипы), «только для своих», регулярность (recurrence none/daily/weekdays/weekly).
  `POST /rides`. Успех → CTA «Поднять в ленте» (Boost) / кабинет.
- `DriverCabinetScreen` → `/driver` (RequireAuth): тумблер «Я на линии»
  (`POST /driver/online`), плашка статуса модерации (`GET /driver/status` → docs_status),
  счётчики (активные рейсы / пассажиры), быстрый доступ (Заявки пассажиров → `/requests-feed`,
  Мой заработок, Boost, Стать таксистом — заглушка волны 4), регулярные маршруты
  (`GET/POST/DELETE /driver/schedule`, добавление днями недели + время), «Мои поездки/Архив»
  (`GET /driver/rides?status=all` — маршрут/время/цена/занято + статус-пилюля).
- `DriverProfileScreen` → `/drivers/:id` (ПУБЛИЧНО): `GET /drivers/{id}/public` — фото,
  бейдж «Проверен», стаж (дни → лет/мес/дн), число поездок, средний рейтинг, отзывы (без
  телефона) + публичные регулярные маршруты (`GET /drivers/{id}/schedule`, мягко). Открывается
  тапом по водителю из `RideSheet`.
- `DriverEarningsScreen` → `/earnings` (RequireAuth): `GET /driver/earnings?period=week|month|all`
  — плитки заработано ₽ / поездок + столбики по дням (`by_day[].sum/trips`). Суммы в РУБЛЯХ
  (не копейках). Нули для новичка. 404/405 → мягкое «скоро».
- `BoostScreen` → `/boost` (RequireAuth): планы (`GET /boost/plans`), выбор своей поездки
  (`GET /driver/rides?status=active`), оплата (`POST /boost/create`). yookassa → редирект на
  `confirmation_url`, назад → «Проверить оплату» (`GET /payments/{id}/status`); sbp_manual →
  реквизиты СБП «на доверии» + «Я оплатил» (поллинг статуса); mock/dev → сразу succeeded.
- `VerifyDriverScreen` → `/verify-driver` (RequireAuth): правила + данные авто
  (`POST /driver/profile`) + загрузка фото прав/авто (`POST /upload/photo` multipart `file` →
  защищённый url) → отправка (`POST /driver/verify {license_url, car_photo_url}`). Статус
  проверки (none/pending/verified/rejected + причина) из `GET /driver/status`.

**Инфраструктура:** `api/driver.ts` (online/status/rides/public/schedule/earnings/verify/upload/
profile), `api/boost.ts` (plans/create/free/payment-status), `apiUpload` (multipart) в
`api/client.ts`, `status?` в `api/rides.ts`, новые иконки (Rocket/Calendar/Camera/Power/Wheel),
ссылка «Я водитель» в профиле, тап по водителю в `RideSheet` → публичный профиль, стили Canon
для новых блоков в `ui.css` (профиль/отзывы/бары заработка/планы/СБП/слоты фото).

**Зависит от деплоя release-2026-07:** `/driver/earnings`, `/driver/schedule`,
`/drivers/{id}/schedule`, `/boost/*` — на проде появятся после мержа release. До мержа отдают
404 → экраны показывают мягкое «скоро»/пустое состояние без краша. Оплата: провайдер (yookassa/
sbp_manual/mock) настраивается на бэке — веб честно отражает все три ветки.

### ✅ Волна 4 — Такси (5) (готово, сборка зелёная)
5 экранов + связки. Все состояния (загрузка/пусто/ошибка/«скоро»/гейт города), два языка
(ба-черновик → `BASHKIR_DRAFT.md`), токены Canon, safe-area, тач-цели ≥48px, мягкая деградация 404.

**Экраны и роуты:**
- `InstantOrderScreen` → `/taxi` (RequireAuth): гейт города (`GET /instant/availability`) →
  «Куда едем» (точка Б через геокодер `/geocode` + быстрые адреса Дом/Работа/недавние
  `/places/saved`+`/places/recent`, карта + анонимные машинки `GET /instant/nearby-drivers`) →
  оценка (`POST /instant/estimate`, обе цены из `options`) → выбор класса Эконом/Комфорт →
  «Сейчас / На время». «Сейчас» → `POST /instant/orders`, «На время» → `POST /instant/schedule`
  (ISO). Отслеживание: поллинг `GET /instant/orders/{id}` (3.5с) — searching/offered → «Ищем
  машину»; accepted/arriving → карта A→B + ETA + карточка водителя (телефон `tel:` ПОСЛЕ accept,
  чат, отмена с `cancel_fee_now_kop`); onboard → «В пути»; done → цена + оценка
  (`POST /instant/orders/{id}/rate`); expired/cancelled → мягкие финалы. Восстановление активного
  заказа при входе (`GET /instant/orders/mine`).
- `InstantDriverTripScreen` → `/taxi-drive` (RequireAuth): гейт заявки таксиста
  (`GET /taxi/application` → approved, иначе онбординг) → тумблер «Я на линии» (`POST /driver/online`)
  → presence-heartbeat (`POST /instant/presence`, `watchPosition`, каждые 15с) + опрос оффера
  (`GET /instant/driver/offer`, 3с). Оффер — полноэкранный оверлей с таймером из `offer_expires_at`,
  «Взять»(`accept`)/«Пропустить»(`decline`), звук (WebAudio) + вибро вместо звонка. Поездка:
  навигация к пассажиру, «Приехал»(`arrived`)/«Посадил»(`onboard`)/«Завершить»(`done`), телефон
  пассажира после accept, чат. Активный заказ восстанавливается через `localStorage`+`GET /instant/orders/{id}`.
- `InstantChatScreen` → `/taxi-chat/:orderId` (RequireAuth): чат такси-заказа
  (REST `GET/POST /instant/orders/{id}/messages` + WS `/ws/instant/{id}/chat`, поллинг-фолбэк).
  До accept — «чат откроется» (409), после done/cancelled — read-only.
- `ScheduledOrdersScreen` → `/scheduled` (RequireAuth): `GET /instant/scheduled` — список с
  обратным отсчётом, «Начать поиск сейчас» (`activate` → `/taxi`) и «Отменить» (`cancel`).
  Блок «Пора ехать» для наступивших (бэк лениво активирует их в `activated`).
- `TaxiOnboardingScreen` → `/taxi-onboarding` (RequireAuth): гейт города → правила + заявка
  (ИНН/разрешение/дата рожд./год прав/класс авто + фото разрешения/ОСАГО/селфи через
  `POST /upload/photo`) → `POST /taxi/apply`. Статус (`GET /taxi/application`):
  pending/approved(«Выйти на линию»)/rejected(причина + «Подать снова»).

**Инфраструктура:** `api/instant.ts` (availability/estimate/orders/schedule/presence/offer/
переходы/rate/nearby + taxi/apply/application), расширен `api/chat.ts` (order-чат: REST + WS),
иконки `IconCar`/`IconPhone`, стили Canon для такси в `ui.css` (маршрут/классы/оффер-оверлей/
карточка водителя/предзаказы). Связки: `/taxi` из Home (быстрое действие) и профиля
(«Быстрый заказ»/«Я на линии (такси)»/«Мои предзаказы»); в кабинете водителя тайлы
«Я на линии (такси)» и «Стать таксистом» → `/taxi-onboarding`.

**Зависит от деплоя release-2026-07:** все `instant/*`, `/taxi/apply`, `/taxi/application`,
`/instant/availability` появятся на проде после мержа `release`. До мержа отдают 404/405 →
экраны показывают мягкий гейт «Такси скоро»/пустое/«скоро» без краша.

### ✅ Волна 5 — Курьер и посылки (3 экрана) (готово, сборка зелёная)
3 экрана + связки. Все состояния (загрузка/пусто/ошибка/«скоро»/гейт), два языка
(ба-черновик → `BASHKIR_DRAFT.md`), токены Canon, safe-area, тач-цели ≥48px, мягкая
деградация 404/403/405.

**Экраны и роуты:**
- `CourierOnboardingScreen` → `/courier-onboarding` (RequireAuth): правила + выбор транспорта
  (легковой/грузовой, `seg`) + селфи с документом (`POST /upload/photo` → `uploadDoc`) →
  `POST /courier/apply`. Статус заявки из `GET /courier/application`:
  pending / approved («Выйти на линию» → `/courier`) / rejected (причина `reject_reason` +
  «Подать снова»). 404 (нет на проде до release) → мягко пускаем к форме.
- `CourierScreen` → `/courier` (RequireAuth): гейт `GET /courier/me` (403 → онбординг,
  404/405 → «Курьер скоро»). Тумблер «Я на линии» (`POST /courier/online|offline`) + выбор
  зоны чипами (city/intercity/region) — при смене зоны пере-запрос списка (и `online` →
  повторный `/courier/online` с новой зоной). Плашка мягкой паузы по качеству (`paused_until`,
  тумблер заблокирован). Вкладки: **Заказы** (`GET /courier/available`, БЕЗ телефона,
  «Взять» → `POST /parcels/{id}/accept`); **Везу** (`GET /parcels/carrying`, телефон виден,
  «В пути» → `/parcels/{id}/status in_transit`, «Доставлено» → диалог кода вручения →
  `.../status delivered`; для `buy_bring` — ввод фактической стоимости товара
  `POST /courier/orders/{id}/goods-cost` перед вручением); **Кабинет** (рейтинг из `me.rating`,
  выписка earned/owed/paid из `me.statement`, «сейчас платишь N%» + ступень tier1/2/3/promo,
  «Оплатить комиссию» → `POST /courier/pay-commission`: succeeded → обновляем; sbp_manual →
  реквизиты СБП «на доверии»; yookassa → редирект на `confirmation_url`; 409/503 → мягкий текст).
- `ParcelsScreen` → `/parcels` (RequireAuth): 3 вкладки. **Отправить** — форма (города, размер
  карточками small/medium/large, что за посылка, получатель имя+тел, обязательный чекбокс правил)
  → `POST /parcels` → крупный моноширинный **КОД вручения** (копировать) + сбор Юлдаша.
  **Мои** — `GET /parcels/mine`: статус-пилюля, свой код вручения, курьер (имя/рейтинг/звонок)
  если принята, «Отменить» (`POST /parcels/{id}/cancel`). **Возить** («по пути», poputka):
  под-вкладки «Доступные» (`GET /parcels/available`, БЕЗ телефона, «Взять») и «Везу»
  (`GET /parcels/carrying`, телефон + статусы + диалог кода).

**Инфраструктура:** `api/parcels.ts` (create/mine/cancel/available/accept/status/carrying/rate),
`api/courier.ts` (application/apply/online/offline/available/me/goods-cost/pay-commission),
`components/parcelUi.tsx` (подписи размера/статуса, `AvailableParcelCard`, `CarryParcelCard`,
`CodeDialog`), `rubLabel` (копейки→₽) в `utils/format.ts`, иконка `IconBox`, стили Canon для
курьера/посылок в `ui.css` (карточки, крупный код, кабинет, пауза). Взаимная оценка доставки —
эндпоинт `POST /parcels/{id}/rate` есть в API-слое (в UI встроим в общую систему оценок волны 7/
экрана завершения — контракт готов). Связки: входы «Посылки» и «Режим курьера» из профиля.

**Зависит от деплоя release-2026-07:** `/courier/*` (application/apply/online/offline/available/
me/goods-cost/pay-commission) появятся на проде после мержа `release`. До мержа отдают 404/403/405
→ экраны показывают мягкий гейт «Курьер скоро»/онбординг без краша. `/parcels/*` (create/mine/
cancel/available/accept/status/carrying) — базовая M3 уже на проде; курьер-типы (courier/buy_bring)
в `/parcels/*` активируются гейтом `_guard_courier` после мержа.

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
