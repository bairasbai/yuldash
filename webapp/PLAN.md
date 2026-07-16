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

### ✅ Волна 6 — Деньги и маркетплейс (7) (готово, сборка зелёная)
7 экранов + связки. Все состояния (загрузка/пусто/ошибка/«скоро»), два языка
(ба-черновик → `BASHKIR_DRAFT.md`), токены Canon, safe-area, тач-цели ≥48px, мягкая
деградация 404/405. Зеркало `backend/app/routers/` (`wallet.py`, `coupons.py`, `promo.py`,
`ads.py`, `payments.py`).

**Экраны и роуты:**
- `WalletScreen` → `/wallet` (RequireAuth): баланс (`GET /wallet/balance`) крупной карточкой +
  история операций (`GET /wallet/ledger`): приход зелёным, списание приглушённым.
- `CouponsScreen` → `/coupons` (ПУБЛИЧНО): витрина «Скидки рядом» (`GET /coupons`, чипы городов) +
  «Мои купоны» (`GET /my/coupons`, вход). Активация `POST /coupons/{id}/activate` → крупный код +
  дисклеймер. Вход «У меня бизнес» → `/partner`.
- `PromoCodeScreen` → `/promo` (RequireAuth): ввод кода (`POST /promo/apply`) + показ применённого
  (`GET /promo/mine`). Один код на жизнь аккаунта; отказ ничего не ломает.
- `PartnerCabinetScreen` → `/partner` (RequireAuth): ветвление по `GET /partner/me` —
  нет бизнеса→форма (`POST /partner`); pending→ожидание; rejected→причина + правка (`POST /partner/{id}`);
  active→кабинет: подписка (`GET /partner/plans` + `POST /partner/subscribe` СБП «на доверии»),
  выписка (`statement` из `/partner/me`), купоны CRUD (`GET/POST /partner/coupons`, `POST …/{id}`,
  `POST …/{id}/status`), погашение кода клиента (`POST /coupons/redeem`). Premium-тумблер купона —
  только при `has_premium`. Партнёрский API — в `api/coupons.ts` (переиспользован, без дубля `partners.ts`).
- `AdsCabinetScreen` → `/ads` (RequireAuth): мои объявления (`GET /ads/mine`) + статистика
  (`GET /ads/mine/stats`, показы/клики/CTR/остаток срока), создать (`/ads/new`), править
  черновик/отклонённое (`/ads/:id/edit`), оплатить размещение (`POST /ads/{id}/pay`, СБП «на доверии»),
  продлить истёкшее.
- `AdEditorScreen` → `/ads/new` и `/ads/:id/edit` (RequireAuth): форма (текст + кнопка + цель +
  города CSV) + выбор пакета из `AD_PACKAGES` (`GET /ad-packages`). «Сохранить черновик»
  (`POST /ads` / `POST /ads/{id}`) и «На модерацию» (`POST /ads/{id}/submit`).
- `PaymentInfoScreen` → `/payment-info` (ПУБЛИЧНО): честное пояснение способов оплаты —
  СБП «на доверии» (реквизиты приходят в момент оплаты) и ЮKassa (когда включат). Реквизиты не
  хардкодятся: их отдаёт бэкенд при создании платежа.

**Инфраструктура:** API-слой `api/wallet.ts`, `api/coupons.ts` (+ партнёрская часть),
`api/promo.ts`, `api/ads.ts` (зеркало release). Добавлены CSS-классы Canon в `ui.css`
(кошелёк/ledger/карточка купона/крупный код/мои купоны/промо/кабинет бизнеса и рекламы/
статус-баннеры/выписка/способы оплаты) — раньше экраны Wallet/Coupons/Promo рендерились без
стилей, теперь оформлены. Связки: входы «Кошелёк / Скидки по пути / Промокод / Мой бизнес /
Реклама / Как оплатить» в профиле; «У меня бизнес» из витрины скидок; переходы кабинет↔редактор
рекламы; оплата → «Как оплатить».

**Зависит от деплоя release-2026-07:** `/wallet/*`, `/coupons*`, `/my/coupons`, `/partner*`,
`/promo/*`, `/ads/mine*`, `/ad-packages`, `POST /ads`, `POST /ads/{id}/pay|submit` появятся на
проде после мержа `release`. До мержа отдают 404/405 → экраны показывают мягкое «скоро»/пустое
состояние без краша. Оплата рекламы и подписки бизнеса — СБП «на доверии» (админ подтверждает в
Telegram); реквизиты бэкенд отдаёт при создании платежа.

### Волна 7А — Безопасность и доступность (СДЕЛАНО, 6 экранов)
- `SosScreen` → `/sos` (ПУБЛИЧНО): экстренные звонки 112/103/102/101 через `tel:` (без входа) +
  «Сообщить близким» (`POST /sos`, `safety.py`) — только со входом (гостю мягко на Login).
  Крупные тач-цели, спокойный дизайн, категория (здоровье/трасса/другое) + заметка.
- `TrustedContactsScreen` → `/trusted` (RequireAuth): список/добавить(имя+тел+кто)/удалить.
  `GET/POST /trusted-contacts` (`family.py`). DELETE — best-effort (на бэке пока нет): при 404/405
  контакт честно возвращается + мягкая подсказка «удаление появится позже».
- `FamilyOrderScreen` → `/family-order` (RequireAuth): заявка «за близкого» (`POST /requests` с
  `for_relative_name` + `assisted:true`; телефон близкого — в комментарии, у RequestIn нет поля phone).
- `CallbackHelpScreen` → `/callback` (ПУБЛИЧНО): `POST /callback` (`safety.py`) — крупно, для пожилых.
  Ручка требует аккаунт (телефон берётся из профиля) → гостю мягко предлагаем войти. Показываем номер,
  на который перезвонят.
- `VoiceRequestScreen` → `/voice` (RequireAuth): запись через браузерный `MediaRecorder` → загрузка в
  `POST /voice` (multipart `file`, `discovery.py`) → `voice_url` в `POST /requests` (`assisted:true`).
  Отказ микрофона / неподдержка / 404-405 загрузки → честный текстовый фолбэк на ту же заявку.
- `SimpleModeScreen` → `/simple` (ПУБЛИЧНО): хаб доступности крупными карточками (Найти поездку/Вызвать/
  Голосовая/За близкого/Доверенные/Перезвоните/Чат/SOS) + переключатель размера шрифта.

**Крупный шрифт — единая точка правды:** `src/fontScale.ts` (`getFontScale/setFontScale/applyFontScale/
useFontScale`). Значение (`normal|large|xlarge`) в localStorage; применяется на старте (`main.tsx`) ДО
первого кадра. Дизайн-система на px, поэтому масштабируем весь UI глобальным множителем через CSS `zoom`
на `<html>` (растёт весь текст и заодно тач-цели — для пожилых даже лучше). Хук переиспользуется в
Настройках. Множители: 1 / 1.15 / 1.3.

**Связки:** входы SOS / Простой режим / Доверенные / За близкого / Голосовая / Перезвоните — добавлены
в Профиль. Онбординг: выбор простого режима на финальном шаге ведёт на `/simple`. Простой режим —
карточки-ссылки на все ключевые действия. Новые CSS-классы Canon в `ui.css` (sos/hub/fontscale/voice/
callback/safe-note — только токены, светлая/тёмная тема).

**Зависит от деплоя release:** `DELETE /trusted-contacts/{id}` (на бэке пока только GET/POST) → удаление
контакта деградирует мягко. `POST /voice` есть на release — до мержа отдаёт 404/405 → голосовая
автоматически уходит в текстовый фолбэк. `POST /sos`, `POST /callback`, `POST /requests` — уже на проде.

### ✅ Волна 7Б-1 — Поддержка и помощь (5 экранов) (готово, сборка зелёная)
5 экранов + связки. Все состояния (загрузка/пусто/ошибка/404), два языка
(ба-черновик → `BASHKIR_DRAFT.md`), токены Canon, safe-area, тач-цели ≥48px, мягкая
деградация. Зеркало `backend/app/routers/` (`notifications.py`, `support.py`, `reviews.py`).

**Экраны и роуты:**
- `NotificationsScreen` → `/notifications` (RequireAuth): `GET /notifications` — лента (непрочитанные
  сверху с бэка), вкладки Все/Поездки/Сообщения/Система (client-side по `type`), относительное время
  (`formatRelative` в `utils/format.ts`). Тап → оптимистичный mark-read (`POST /notifications/read {id}`)
  + deep-link по `ref_kind`: booking→`/booking/:ref_id`, request→`/requests/:ref_id/responses`,
  support→`/support/:ref_id`. «Прочитать всё» (`{all:true}`). Бейдж непрочитанного в шапке.
- `SupportTicketsScreen` → `/support` (RequireAuth): `GET /support/tickets` — список обращений
  (статус Открыто/Закрыто, превью, точка непрочитанного, относительное время). «Новое обращение»
  (тема+текст → `POST /support/tickets`) → сразу в тред. Бейдж `unread`.
- `SupportTicketScreen` → `/support/:id` (RequireAuth): `GET /support/tickets/{id}` — тред пузырями
  (переиспользован паттерн `.chat`/`.bubble` из чата такси; user справа, поддержка слева + метка «Поддержка»
  + время). Ввод+отправка `POST /support/tickets/{id}/messages`, «Закрыть обращение»
  `POST /support/tickets/{id}/close`; закрытый — можно снова написать (бэк переоткрывает). 404 → мягко.
- `HelpScreen` → `/help` (ПУБЛИЧНО): FAQ-эндпоинта на бэке нет → статичный двуязычный список 7 частых
  вопросов (аккордеон) + client-side поиск. Крупная кнопка «Написать в поддержку» → `/support`
  (гостю мягко на `/login`), Telegram-ссылка доп.вариантом.
- `AppReviewScreen` → `/app-review` (RequireAuth): оценка звёздами + отзыв + город → `POST /reviews`
  (`reviews.py`). Текст ≥ 10 символов (валидация клиентом до отправки, чтобы не ловить 400). Спасибо-состояние
  («после модерации попадёт на лендинг»).

**Инфраструктура:** API-слой `api/notifications.ts` (fetch/markRead/markAllRead + `fetchNotifUnread`),
`api/support.ts` (tickets/thread/create/message/close + `fetchSupportUnread`), `api/reviews.ts`
(`submitAppReview`, `POST /reviews`). Хелпер `formatRelative` в `utils/format.ts`. Новые CSS-классы Canon
в `ui.css` (notif-list/row/dot, support-list/row/compose, faq-list/item, help-cta, `list-row__badge`,
`badge--muted`, `bubble__time`, `rate-star--on`, `rate-card__hint` — только токены, светлая/тёмная тема).
Связки в Профиле: «Уведомления» (с бейджем непрочитанного) вверху списка; «Помощь / Поддержка Юлдаш
(с бейджем) / Оценить приложение» рядом с Согласиями. Deep-link support из Уведомлений открывает тред.

**Зависит от деплоя release:** `/notifications`, `/notifications/read`, `/support/tickets*`, `/reviews` —
контракты есть в `release/backend`; на проде `yulbash.ru` появятся после мержа `release`. До мержа отдают
404/405 → экраны показывают мягкое пустое/ошибку без краша, бейджи в профиле просто не появляются.

### ✅ Волна 7Б-2 — Настройки и правовое (5 экранов) (готово, сборка зелёная — завершает волну 7)
5 экранов + связки. Все состояния (загрузка/пусто/ошибка/404), два языка (ба-черновик →
`BASHKIR_DRAFT.md`), токены Canon, safe-area, тач-цели ≥48px, мягкая деградация. Зеркало
`backend/app/routers/safety.py` (`/blocks`, `/reports`, `/reportable-users`), `auth.py`
(`/me/delete`); юр-тексты — зеркало `web/components/legal-content.ts`.

**Экраны и роуты:**
- `SettingsScreen` → `/settings` (RequireAuth): Язык РУС/БАШ (`useLang`), Тема светлая/тёмная/
  системная (`src/theme.ts`), Размер текста (`useFontScale` — переиспользован, не продублирован),
  тумблеры уведомления/звуки (`src/uiPrefs.ts`, локально), ссылки Приватность/Правила/Чёрный список/
  Согласия/Как оплатить/Пожаловаться, Выход, «Удалить аккаунт» (danger, двойное подтверждение →
  `POST /me/delete` → чистка сессии → `/splash`).
- `PrivacyScreen` → `/privacy` (ПУБЛИЧНО) и `RulesScreen` → `/rules` (ПУБЛИЧНО): общий `LegalScreen`
  рендерит `LegalDoc` из `src/legal.ts` (PRIVACY/TERMS, тело RU, заголовки двуязычны). Плашка «полный
  текст на сайте» + ссылка на `${API_BASE}/<slug>`.
- `BlocklistScreen` → `/blocklist` (RequireAuth): `GET /blocks`, разблокировать `DELETE /blocks/{id}`
  (оптимистично). Пусто → «Никто не заблокирован».
- `ReportScreen` → `/report` (RequireAuth): форма жалобы (категория §9 + описание) → `POST /reports`.
  Цель: предзаполнена `?user=&name=` (из профиля водителя) / контекст поездки `?booking=&order=` /
  выбор из `GET /reportable-users`. Опция «заблокировать тоже» (`POST /blocks`). Спасибо-состояние.

**Тема — единая точка правды:** `src/theme.ts` (`getTheme/setTheme/applyTheme/useTheme`). Режим
(`system|light|dark`) в localStorage; применяется на старте (`main.tsx`) ДО первого кадра через
`data-theme` на `<html>`. `index.css`: тёмные токены — под `@media (prefers-color-scheme:dark)
:root:not([data-theme])` (система) И `:root[data-theme="dark"]` (ручной), `:root[data-theme="light"]`
остаётся светлым даже под тёмной системой. Язык и размер шрифта — как раньше (`i18n/lang`, `fontScale`),
всё переживает перезагрузку.

**Связки:** «Настройки» (шестерёнка `IconSettings`) — верхняя строка в Профиле; Приватность/Правила/
Чёрный список/Согласия/Как оплатить/Пожаловаться — из Настроек; «Пожаловаться на водителя» —
кнопка внизу `DriverProfileScreen` (гостю мягко на `/login`). Новые CSS-классы Canon в `ui.css`
(danger-zone, legal, report-target, report-link, btn-soft--sm, select.field__input — только токены).

**Зависит от деплоя release:** `/blocks*`, `/reports`, `/reportable-users`, `/me/delete` — контракты
есть в `release/backend`; на проде `yulbash.ru` появятся после мержа `release`. До мержа отдают 404/405
→ Чёрный список показывает «пусто», Жалоба/Удаление — мягкое «скоро, напиши в поддержку», без краша.
Юр-страницы `/privacy`, `/rules` полностью автономны (текст в приложении).

### ✅ Волна 8 — Админка (16 экранов) (ГОТОВО — вся админка, сборка зелёная)
AdminCabinet, AdminRequest, AdminResponses, AdminDrivers, AdminReports, AdminPaymentRequests, AdminReviews,
AdminAds, AdminTaxi, AdminWaitlist, AdminTaxiPulse, IncomeCalculator, AdminPartners, AdminPromo, AdminParcels, AdminCourier.

#### ✅ Волна 8А — Ядро модерации (готово)
Доступ ко всему разделу — `components/RequireAdmin.tsx`: проверяет `Me.role === "admin"` (поле `role` из
`GET /me`; авто-админ на бэке по telegram_id/телефону — `auth.py::_maybe_promote_admin`). Гость → `/login`;
не-админ → мягкий редирект на `/profile`. Вход «Кабинет админа» показывается ТОЛЬКО админу — в Профиле
(верхняя строка) и в Настройках (секция «Администрирование»).

Экраны и роуты (все под `RequireAdmin`, шапка `SubHeader` с «назад», все состояния, двуязычно):
- **AdminCabinet** → `/admin` — хаб-меню. Волна 8А активна (5 ссылок), остальные разделы (отзывы, реклама,
  такси, лист ожидания, пульс, калькулятор дохода, бизнесы, промо, посылки, курьеры) — плашки «скоро».
- **AdminRequest** → `/admin/request` — заявка за юзера по телефону: `POST /admin/request-for-phone`
  `{phone, name?, from_city, to_city, seats, desired_at?, comment?}` → `{id}`.
- **AdminResponses** → `/admin/responses` — ввод номера заявки → `GET /requests/{id}/responses` (админ видит
  любые) → принять отклик за юзера `POST /responses/{id}/accept` → `{booking_id}` (создаёт поездку+бронь).
- **AdminDrivers** → `/admin/drivers` — `GET /admin/drivers/pending`; фото прав/авто защищены
  (`GET /secure/docs/{name}`, только админ/владелец) → грузим с Bearer через `fetchSecureDoc` → blob-URL
  (`<img>` не шлёт заголовки); модерация `POST /admin/drivers/{id}/moderate {approve:bool}`. Автопроверка
  (`autocheck_result/score`) подсвечена бейджем-подсказкой. Причина отказа бэком не принимается — только
  approve/reject.
- **AdminReports** → `/admin/reports` — `GET /admin/reports?status=`; фильтр-чипы (new/reviewing/resolved/
  rejected/все); `POST /admin/reports/{id}/resolve {keep_pause}` и `/reject`; ручные меры по цели
  `POST /admin/quality/{id}/pause {hours:72}` / `/unpause` (§9 лестница мер срабатывает на бэке при resolve).
- **AdminPaymentRequests** → `/admin/payment-requests` — СБП «на доверии»: `GET /admin/payments/pending`,
  сводка `GET /admin/payments/summary`; подтвердить/отклонить `POST /admin/payments/{id}/confirm|reject`.

API-слой — `src/api/admin.ts` (все ручки + `fetchSecureDoc`). Новые CSS-классы Canon в `ui.css`
(`admin-cards/admin-card*`, `doc-photos/doc-photo*`, `admin-check`, `chip-scroll`) — только токены.

**Зависит от деплоя release:** все `/admin/*`, `/secure/docs/*`, `/responses/{id}/accept` контракты есть в
`release/backend`; на проде `yulbash.ru` появятся после мержа `release`. До мержа → 404/405: экраны
показывают ошибку/пусто, без краша. Роль `admin` пользователю на проде тоже проставит бэк (по
telegram_id/телефону из конфига) — без этого раздел просто не виден.

#### ✅ Волна 8Б — Отзывы, реклама, такси, лист ожидания, пульс, доход (готово)
6 новых экранов под `RequireAdmin` (шапка `SubHeader`, все состояния, двуязычно, тач-цели ≥48px). В
`AdminCabinet` их 6 ссылок переведены из «скоро» в активные (осталось «скоро»: бизнесы, промо, посылки, курьеры).

- **AdminReviews** → `/admin/reviews` — две вкладки (`.seg`): «О поездках» (`GET /admin/ratings/pending`,
  `POST /admin/ratings/{id}/publish {published:true}`) и «О приложении» (`GET /admin/reviews/pending`,
  `POST /admin/reviews/{id}/publish {published:true}`). Опубликовать = одобрить к показу; звёзды текстом.
- **AdminAds** → `/admin/ads` — `GET /admin/ads` (→ `{founder_used, founder_limit, items}`) + `GET /ads/stats`
  (показы/клики/CTR). Фильтр-чипы (pending_review/active/rejected/все). Модерация: одобрить с erid-маркировкой
  ОРД `POST /admin/ads/{id}/approve {erid}`, отклонить с причиной `POST /admin/ads/{id}/reject {reason}`.
  Активные/пауза: `POST /admin/ads/{id}/status {status}`, архив `DELETE /admin/ads/{id}`.
- **AdminTaxi** → `/admin/taxi` — две вкладки. «Заявки» (580-ФЗ): `GET /admin/taxi-applications?status=`
  (pending/approved/rejected/all), документы (разрешение/ОСАГО/селфи/справка) защищены → `fetchSecureDoc` →
  blob-URL; одобрить `POST …/approve`, отклонить с комментарием `POST …/reject {comment}`. «Города»:
  `GET /admin/taxi-cities`, вкл/выкл/добавить `POST /admin/taxi-cities {city, enabled}`, `DELETE …/{id}`.
- **AdminWaitlist** → `/admin/waitlist` — `GET /admin/waitlist?role=&invited=&limit=500` (счётчики по всей
  базе + список по фильтрам). Чекбоксы → плавающая панель «Отметить волну» `POST /admin/waitlist/invite {ids}`.
  CSV-экспорт `GET /admin/waitlist.csv` (fetch с Bearer → blob download). Телефоны видит только админ (152-ФЗ).
- **AdminTaxiPulse** → `/admin/taxi-pulse` — `GET /admin/taxi/pulse` (на линии/активные заказы/счётчики дня/
  средний подбор/анти-фрод/по городам). Плитки + список городов, автообновление раз в 20 сек (silent refetch).
  Без Redis presence = 0 (панель честно показывает, не падает).
- **IncomeCalculator** → `/admin/income` — чистый клиентский расчёт (зеркало android
  `IncomeCalculatorScreen.kt`), БЕЗ бэкенда. Ползунки (`input[type=range]`): поездки/день, партнёры, цена
  подписки, Boost, маршруты + вкл/выкл такси (средний чек, комиссия). Формула: `couponsIncome = partners*subPrice`,
  `boostIncome = boosts*70*30`, `taxiIncome = rides*avgCheck*(comm%/100)*30`, `gross = (сумма)*routes`,
  `net = gross*(1−15%)`. Коэффициенты (Boost 70 ₽, расходы 15%) — константы в файле.

API-слой — расширен `src/api/admin.ts` (reviews/ads/taxi/waitlist/pulse + `apiDelete`). Новые CSS-классы Canon
в `ui.css` (`review-stars`, `ad-stat-row`, `wait-invite-bar`, `pulse-grid`/`stat-tile--hl`/`pulse-updated`,
`income-hero`/`income-breakdown`/`calc-*`) — только токены, светлая/тёмная тема.

**Зависит от деплоя release:** контракты `reviews.py`, `ads.py`, `taxi.py`, `waitlist.py` есть в
`release/backend`; на проде `yulbash.ru` появятся после мержа `release`. До мержа → 404/405, экраны
показывают ошибку/пусто без краша. IncomeCalculator работает офлайн (расчёт на клиенте).

#### ✅ Волна 8В — Бизнесы, промо, посылки, курьеры (готово — завершает волну 8, всю админку)
4 новых экрана под `RequireAdmin` (шапка `SubHeader`, все состояния, двуязычно, тач-цели ≥48px). В
`AdminCabinet` последние 4 ссылки переведены из «скоро» в активные — раздел «Скоро» убран, вся админка активна.

- **AdminPartners** → `/admin/partners` (`coupons.py` admin) — `GET /admin/partners` (pending сверху с бэка),
  плитки (на проверке/активных/всего), фильтр-чипы (pending/active/rejected/все). Одобрить
  `POST /admin/partners/{id}/approve` (→ active); отклонить с причиной `POST /admin/partners/{id}/reject {reason}`
  (→ rejected). Карточка: категория/город/адрес/телефон бизнеса/описание/статус подписки/причина отказа.
- **AdminPromo** → `/admin/promo` (`promo.py` admin) — `GET /admin/promo` со счётчиками applied/active
  (по active платим блогеру «на результат»). Плитки-статистика. Форма создания (сворачиваемая):
  code (→upper)/название/тип бонуса welcome|boost/perk_value/телефон блогера/кампания/лимиты/сроки →
  `POST /admin/promo`. Список: код моноширинно, applied/active/лимит, вкл/выкл `POST /admin/promo/{id}/status {active}`.
- **AdminParcels** → `/admin/parcels` (`parcels.py` admin) — `GET /admin/parcels` → `{parcels, statement}`.
  Плашка дохода (`income-hero`): собрано ₽ (`collected_fee_kop`) + доставлено N + в работе. Активные сверху
  (client-sort по стадии created→accepted→in_transit, затем delivered/canceled), фильтр-чипы. Карточка:
  маршрут, размер, тип доставки, сбор, получатель+телефон, **код вручения** (для поддержки/споров), курьер.
- **AdminCourier** → `/admin/courier` (`courier.py` admin) — `GET /admin/courier-applications?status=`
  (pending/approved/rejected/all). Селфи с документом защищено → `fetchSecureDoc` (Bearer→blob-URL, как в
  AdminDrivers). Одобрить `POST …/approve`; отклонить с причиной `POST …/reject {reason}`. Карточка:
  имя/телефон/транспорт/«кто пригласил» (доверие «между своими»)/селфи/причина отказа.

API-слой — расширен `src/api/admin.ts` (partners/promo/parcels/courier, `import type Parcel`). Переиспользованы
`sizeLabel`/`StatusPillParcel` из `components/parcelUi.tsx`, `income-hero`/`stat-grid`/`stat-tile`/`admin-card*`/
`chip-scroll`/`field__area` из `ui.css` — новых CSS-классов не заводили.

**Зависит от деплоя release:** контракты `coupons.py` (admin-часть), `promo.py`, `parcels.py`, `courier.py`
есть в `release/backend`; на проде `yulbash.ru` появятся после мержа `release`. До мержа → 404/405, экраны
показывают ошибку/пусто без краша. `/admin/parcels` (базовая M3) частично уже на проде; admin-модерация
бизнесов/промо/курьеров активируется после мержа release.

### ✅ Волна 9 — Полировка + деплой (готово, сборка зелёная) — PWA ЗАВЕРШЕНА
Финальная волна: клиентский Web Push, офлайн-полировка, финальная консистентность, деплой-доки, CI-джоба.

**1. Web Push (клиент, честно + мягкая деградация):**
- `src/push/webPush.ts` — точка правды: проверка поддержки (`Notification`+`serviceWorker`+`PushManager`),
  `navigator.standalone`/`display-mode` (на iOS пуши ТОЛЬКО после установки на «Домой»),
  `Notification.requestPermission()` → `pushManager.subscribe({userVisibleOnly, applicationServerKey})`.
- `src/api/push.ts` — `POST /push/web/subscribe` (стандартная форма endpoint+keys). Эндпоинта на проде **НЕТ**
  (есть только FCM `/push/register`) → 404/405 глотается как `PushBackendMissing` (не ошибка клиента).
- `src/components/PushToggle.tsx` — секция «Пуш-уведомления» в Настройках: кнопка «Включить пуши», честные
  состояния (iOS без установки / браузер без Push / нет VAPID / запрет / «включится после настройки на сервере»).
- `public/push-sw.js` — обработчик `push` + `notificationclick`, подключён к Workbox SW через
  `workbox.importScripts` (autoUpdate/generateSW НЕ сломан — только добавлены слушатели).
- `VITE_VAPID_PUBLIC_KEY` в `.env.example` + `vite-env.d.ts`. **Пуши НЕ работают до серверной части** (см. ниже).

**2. Офлайн-полировка:** `src/components/OfflineBanner.tsx` (в оболочке `App.tsx`) — `navigator.onLine` +
события `online`/`offline`, аккуратный баннер «Нет сети» вместо белого экрана. App shell кешируется SW
(precache + `navigateFallback`), лента `GET /rides|/feed` — NetworkFirst (проверено).

**3. Консистентность:** аудит экранов — хардкод-цветов в экранах нет (только пины карты `YandexMap` и
градиент логотипа `BrandMark` — легитимно), select-SVG (`%23686f66`) — известное исключение. Шапки
единообразны (`SubHeader`/`ScreenHeader`; Privacy/Rules → `LegalScreen`). Крупный шрифт — глобальный `zoom`.

**4. Деплой-доки:** `README.md` доведён (сборка `npm ci && npm run build` → `dist/`, `.env.production`,
чек-лист операционки за Александром). `nginx.conf.example` — SPA-fallback, кеш assets/no-cache для SW.

**5. CI:** джоба `webapp-build` в `.github/workflows/ci.yml` (setup-node 22 → `npm ci` → `npm run build`,
аддитивно, параллельно backend-tests). Ветка защищена сборкой фронта.

**⚠️ Требует серверной настройки (клиент готов, ждёт бэк/операционку):**
- **Пуши:** сгенерировать VAPID-пару (public → `VITE_VAPID_PUBLIC_KEY`, private → `.env` бэка);
  добавить эндпоинт `POST /push/web/subscribe` (приём подписки) + научить `send_push` слать web-push.
- **CORS:** `app.yulbash.ru` в `CORS_ORIGINS` бэка.
- **Ключ Яндекс JS** для домена `app.yulbash.ru`.
- **Поддомен** `app.yulbash.ru` (DNS + nginx-статика из `dist/`, HTTPS).

## Веб-ограничения (адаптируем, не буквально 1:1)
- Фоновый GPS-трекинг поездки — только при открытом приложении (iOS PWA).
- Полноэкранный «звонок» оффера такси — баннер + звук/вибро.
- Карта — Яндекс JS вместо нативного MapKit.

## Операционка за Александром (когда дойдём до деплоя)
- Ключ Яндекс Карт **JS** для домена `app.yulbash.ru` (отдельный от мобильного).
- CORS: добавить `app.yulbash.ru` в `CORS_ORIGINS` на бэке.
- VAPID-ключи для веб-пушей.
- Поддомен `app.yulbash.ru` (DNS + nginx).

---

## Паритет дизайна — D0 (токены + иконки)

Цель: чтобы веб выглядел как Android-приложение. Источник правды (только чтение) —
`android/app/src/main/java/com/yuldash/app/CanonTokens.kt`, `UiKit.kt`, `ui/theme/Theme.kt`,
`res/drawable/yu_*.xml`.

### Сделано
- **Токены Canon* 1:1 с андроидом** (`src/index.css`). Выверены/добавлены hex светлой и тёмной темы:
  - Исправлено: `--bg` (dark) `#0e1512 → #0f1613` (CanonBg); `--border` `0.1/0.14 → 0.122/0.141`
    (CanonBorder `0x1F` чёрного / `0x24` белого).
  - `--canon-gold` стал адаптивным: light `#f5b301`, dark `#e8c36b` (CanonGold).
  - Добавлены недостающие Canon-цвета (свет/тьма): `--muted-strong` `#4c534b`/`#c2cbc3`,
    `--green2` `#0b6b3a`/`#27a463`, `--gold-ink` `#0b3d20`, `--taxi` `#e8a200`/`#f2c14e`,
    `--taxi-bg` `#ffefc2`/`#3a2e12`, `--taxi-ink` `#3a2a00`, `--pooling` `#0b6b3a`/`#27a463`,
    `--pooling-bg` `#e7f5ec`/`#0f2419`, `--woman` `#8e3b6b`/`#e39bc4`,
    `--woman-bg` `#f7e9f1`/`#2e1a27`, `--danger-border`, `--hairline-green`.
  - Уже совпадали: bg light, surface, text, muted, mint, green, green-btn, warn*, danger*,
    star, header-gradient — не трогал.
- **Радиусы = андроид** (`CanonCardShape 28dp`, `CanonItemShape 22dp`). `--radius-card:28px`,
  `--radius-item:22px` уже были; применил `--radius-card` к шапке (`.screen-header`) и
  нижней шторке (`.sheet`) вместо хардкода `26px`. **Кнопки оставлены на `16px`** — это точное
  значение `AppButton` в `UiKit.kt` (`RoundedCornerShape(16.dp)`), а не 22px.
- **Порт брендовых иконок** → `src/components/BrandIcons.tsx`. Портированы ВСЕ 27 `yu_*.xml`
  1:1 (viewBox `0 0 48 48`, `currentColor`, strokeWidth 2.2 / 2.4 у маршрута):
  YuMapTab, YuTripList, YuRequestAdd, YuChat, YuProfile, YuModeRideshare/Taxi/Courier/Parcel,
  YuStar, YuRoute, YuSafeTrip, YuSupport, YuSun, YuMoon, YuAc, YuChildSeat, YuLuggage, YuPet,
  YuSmokeFree, YuWomenOnly, YuQuiet, YuMultiStop, YuCourierWalk, YuAccessible, YuService, YuMapCar.
- **Нижняя навигация** (`BottomNav.tsx`) переведена на брендовые иконки:
  Карта→YuMapTab, Поездки→YuTripList, Заявка→YuRequestAdd, Чат→YuChat, Профиль→YuProfile.
- **Hero-картинки** скопированы в `public/`: `yuldash_logo.png`, `login_car_hero_square.png`,
  `onboarding_bashkir_hero.png`, `login_salavat_yulaev_hero.png`.
  Два тяжёлых (>2 МБ) исключены из PWA-precache через `workbox.globIgnores` (грузятся по требованию).
- `npm run build` — **зелёный**.

### Осталось (пер-экранные проходы)
- **Заменить эмодзи/самодельные SVG на брендовые** в экранах (BrandIcons уже готов):
  - переключатель режимов (seg/ModeSwitch) в `CreateRideScreen`, `CreateRequestScreen`,
    `InstantOrderScreen` → YuModeRideshare/Taxi/Courier/Parcel;
  - удобства поездки (opt-chip) в `CreateRideScreen`, `FiltersScreen`, `RideSheet` →
    YuAc/YuChildSeat/YuLuggage/YuPet/YuSmokeFree/YuWomenOnly/YuQuiet;
  - тема (Настройки) → YuSun/YuMoon; поддержка → YuSupport; безопасная поездка → YuSafeTrip;
    маршрут → YuRoute; звёзды рейтинга → YuStar (сейчас `IconStar`, визуально эквивалентен);
  - простой режим (`SimpleModeScreen`) — заменить 🚗📌🎤💚📞 на yu_* где есть.
- **Подставить hero-PNG** в `IntroScreen` / `OnboardingScreen` / `LoginScreen` / `SplashScreen`
  вместо самодельных градиентов-заглушек.
- Использовать новые токены (`--taxi*`, `--pooling*`, `--woman*`, `--muted-strong`,
  `--hairline-green`) в соответствующих экранах вместо частичных хардкодов.
- Ландшафт `src/theme.ts` (nav bar цвет и т.п.) — при желании довести до Theme.kt.
- Оптимизировать вес hero-PNG (сейчас 2.2–2.3 МБ) — нет инструмента в этой среде; сделать при
  наличии sharp/imagemagick или заранее ужать исходники.
