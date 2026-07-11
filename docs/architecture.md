# 🗺️ Карта кода Юлдаш

> Чтобы НЕ читать весь файл. Иди сразу в нужный ФАЙЛ (UI давно разрезан), `grep` по имени функции.
> ⚠️ Числа строк ниже устарели — ищи через `grep`/`rg`. Актуальная карта файлов — сразу ниже.

## 🆕 АКТУАЛЬНАЯ карта файлов (2026-06-30) — UI разрезан на ~16 файлов

`MainActivity.kt` (~664 строки) — ТОНКОЕ ядро: `onCreate`, `enum Screen/HomeTab/RideRole`, демо-сиды, `toUiRide`. Навигация — `YuldashApp.kt` (`enum Screen` + ветки `when` + старт-экран=КАРТА). Экраны в своих файлах:

| Файл | Что внутри |
|---|---|
| `YuldashApp.kt` | Корень: навигация (`when(screen)`), старт-экран, lifecycle поездок, старт/стоп `TripLocationService`, push-разрешения |
| `MapScreen.kt` (~1.8к строк; иконки → `MapPins.kt`, гео-хелперы → `MapGeo.kt`, Спринт 3) | Вкладка Карта: `YandexMapCard`, маршрут (`drawRoadRoute` + объездные), live-стрелка (chaser-эффект), ETA-чип, демо-симуляция, фильтры «Ближайшие», маркеры заявок + `RequestPreviewCard`, авто-refresh + WS `MapFeedSocket`, карусель `QuickSearchCard` (+ донаты) |
| `BookingActiveTripScreen.kt` | `BookingScreen` (детали поездки/бронь: public locked → private unlocked через `/bookings/{id}/details`; pending-бронь не открывает active trip) + `ActiveTripScreen` (чат, код посадки только для `confirmed/onboard`, статус-вотчер `getTripState`, live-баннер фазы, SOS, оценка только при `done`) |
| `RidesRequestsChatScreens.kt` | Вкладки Поездки/Заявки/Чат, `RideCard`, `RequestsFeedScreen` (чипы условий), `ResponsesScreen`, `ChatSocket`-чат; вкладка `Мои поездки` передаёт статус брони в навигацию |
| `CreateRideScreen.kt` | Публикация поездки (маршрут, цена, удобства `PrefToggleRow`, повтор) |
| `AccessibilityScreens.kt` | «Создать заявку» (+ карточка «Условия поездки», 7 предпочтений), Простой режим, голосовая заявка, за близкого, доверенные контакты, повтор маршрута |
| `ProfileScreen.kt` | Вкладка Профиль: кабинеты пассажира/водителя/рекламы, тогл «Я на линии». Кабинет пассажира — карточка входа «Быстрый заказ»; кабинет водителя — `InstantDriverOnlineController` (presence + оффер) |
| `InstantOrderScreen.kt` 🆕 (2026-07-06) | **«Быстрый заказ» (такси-режим, Фаза 2).** Пассажир `Screen.InstantOrder`: `InstantOrderScreen` (Куда едем → `instantEstimate` цена → «Ищем машину» → «Водитель едет»: `InstantRouteMap` A→B + ETA + телефон после accept + отмена; состояния searching/active/expired/cancelled/done, восстановление активного заказа через `getMyInstantOrders`). Водитель: `InstantDriverOnlineController` (heartbeat `fireInstantPresence` + опрос `getDriverOffer` пока «на линии» → полноэкранный `InstantOfferOverlay` с таймером 20с, «Взять»/«Пропустить») и `Screen.InstantDriverTrip` → `InstantDriverTripScreen` (навигация к пассажиру, Приехал/Посадил/Завершить). Гео — `rememberMyPoint` (LocationManager, как на карте); выбор точки Б — переиспользован `PickupPickerOverlay`. Бэкенд-контракт — `backend/app/routers/instant.py`, DTO/методы в `data/ApiClient.kt` |
| `SecondaryScreens.kt` | Уведомления, Безопасность, Настройки, «Фильтры по умолчанию», правила, админ-экраны |
| `SosVerifyScreens.kt` | SOS + проверка водителя (фото, OCR-баннер причин отказа) |
| `SupportBoostScreen.kt` | Поддержка, Boost, Help (буст-оплата — общий `SberPayBlock`) |
| `LoginScreen.kt` / `IntroScreen.kt` | Вход Telegram / брендовое интро |
| `Domain.kt` / `Mocks.kt` / `CanonTokens.kt` | Модели · демо-фолбэк · цвета `Canon*` |
| `data/ApiClient.kt` | REST + парсинг DTO (`BookingDetailsDto`, `RideDto`, заявки, чат); `data/ChatSocket.kt`, `data/LocationSocket.kt`, `data/MapFeedSocket.kt` — WS; `TripLocationService.kt` — foreground GPS |

**💳 Оплата (СБП/Сбербанк, единый компонент):** `SberPayBlock(phone)` в `MainActivity.kt` (рядом с `SbpTransferSheet`) — QR + кнопка «Оплатить в Сбербанке». QR генерит **ZXing** (`com.google.zxing:core:3.5.3`) на клиенте: `sberQrBitmap()`; ссылка `sberPayLink()` = `https://www.sberbank.com/sms/pbpn?requisiteNumber=<цифры SBP_PHONE_DIGITS>`. Переиспользован в 4 местах: донат (`SbpTransferSheet`), буст (`BoostResultCard`), кабинет рекламы/партнёр (`ProfileScreen.kt`), админ «Заявки на оплату» (`SecondaryScreens.kt`). Эквайринг/фискальный чек (54-ФЗ) — пока НЕ реализован (приём «на доверии» + QR).

**Бэкенд** `backend/app/routers/`: `location.py` (WS `/ws/trip/{id}/location` реле + `/ws/map` сигнал), `requests.py` (заявка + prefs + `/near` округл. коорд + лента + отзыв отклика), `bookings.py` (бронь, приватные детали `/bookings/{id}/details`, `driver_phase`, статусы), `rides.py`, `discovery.py` (`/feed` + `donations_total`), `chat.py` (WS `/ws/bookings/{id}`, REST-история, `/conversations` показывает активные брони даже до первого сообщения), `drivers.py`, `payments.py` (донат/буст СБП «на доверии»), `ads.py`, `safety.py`, `family.py`, `notifications.py` (Центр уведомлений — см. ниже), `trust.py` (доверие «между своими» — уровни/инвайты/согласия). Деплой — `docs/server.md`.

**🔔 Центр уведомлений (F5, 2026-07-05):** таблица `Notification` (models.py: `user_id`, `type` booking/ride/system/message, двуязычные `title_ru/ba`+`body_ru/ba`, `ref_kind`/`ref_id` для deep-link, `read_at`, `created_at`; alembic `0005_notification_table`, идемпотентно). Единый хелпер `services.push_notification(...)` пишет строку в СВОЕЙ сессии (чтобы commit не сбросил ORM-объект вызывающего) + шлёт FCM-push — ставится в тех же местах, что и `send_push` (бронь создана/подтверждена/отменена, водитель выехал/подъезжает/завершил, отклик пришёл, заявку приняли, новое сообщение по REST). Роутер `notifications.py`: `GET /notifications` → `{unread, items}` (непрочитанные сверху, `unread` = бейдж), `POST /notifications/read` `{id}`/`{all:true}` (только свои строки). Тесты: `tests/test_notifications.py`. **Android:** `NotificationsScreen` (`SecondaryScreens.kt`) — типизированные вкладки Все/Поездки/Сообщения/Система, непрочитанные сверху, относительное время, состояния loading/empty/error+retry, «Прочитать всё», тап → mark-read + deep-link (бронь → `Screen.Booking`, отклик → `RequestResponses`). Бейдж непрочитанного на кнопке «Система» в `ChatScreen`. DTO `NotifDto`/`NotifFeed` + `getNotifications`/`markNotificationsRead` в `data/ApiClient.kt`.

**🗂 F6 История поездок + отзыв отклика (2026-07-05, ветка `feat/ride-history`):**
- **`GET /driver/rides?status=all|done|cancelled`** (`rides.py`) — раньше отдавал только активные; добавлен фильтр `status` (дефолт/`active` = как раньше, для Boost; `done`/`cancelled`/`all` — для «Архива», сортировка по времени выезда ↓). Не ломает существующий вызов Boost.
- **`DELETE /responses/{id}`** (`requests.py::withdraw_response`) — водитель отзывает свой отклик, пока пассажир не принял. Только автор (403 иначе), после accept → **409**, нет → 404. Лента `/requests/feed` теперь отдаёт `my_response_id` (id своего отклика → кнопка «Отозвать»). pytest: `tests/test_ride_history_edges.py` (6 тестов — фильтр статусов, изоляция по водителю, отзыв pending, запрет чужого/принятого, 404).
- **UI:** раздел **«Архив»** в кабинете водителя (`ProfileScreen.kt` → `DriverCabinetContent` + `ArchiveRideCard`) со счётчиками «Рейсов сделано» / «Пассажиров отвезено» (`seats_total - seats_left` по done), все состояния (skeleton/empty/error+retry). Кнопка **«Отозвать отклик»** в `RequestsFeedContent` (`RidesRequestsChatScreens.kt`) с confirm-диалогом. `ApiClient.getDriverRides(status)` + `deleteResponse(id)`; `RideDto.status`, `RequestFeedDto.myResponseId`.

### Домен «Доверие между своими» (Фаза 4 / D5) — БЭКЕНД (ветка `feat/trust-levels`, ⏳ не в проде)
> Полная спека и политика хранения 152-ФЗ — [docs/trust-levels-backend.md](trust-levels-backend.md).
- **Уровни L0..L3 (вычисляемые + дарованный).** Логика — `app/trust_service.py::trust_level(session, user)`. L0 телефон → L1 +имя+фото → L2 +документы (переиспользует `User.verified` из модерации водителя) → L3 «свой» (по инвайту). Итог = `max(вычисленный из профиля, дарованный)`. L0 — нормальный пользователь, уровни НЕ унижают.
- **Модели** (`models.py`): `Trust(user_id, level, invited_by, updated_at)` — строка только для дарованного L3 «свой» + цепочка приглашений; `InviteCode(code, owner_id, uses_left, created_at)` — инвайт-коды (запас на юзера `MAX_INVITES_PER_USER=5`, код не бесконечен `uses_left`); `Consent(user_id, kind, granted_at)` — реестр согласий 152-ФЗ (kind: offer/privacy/geo). Плюс флаг `only_trusted` на `Ride` и `RideRequest`.
- **Эндпоинты** (`routers/trust.py`, двойной монтаж `/…` и `/api/v1/…`): `GET /me/trust` (мой уровень + что даёт следующий, двуязычно); `POST /invites` (создать, только L2+); `GET /invites/mine`; `POST /invites/redeem {code}` (→ L3, пишет invited_by, uses_left--); `GET /me/consents`; `POST /me/consents {kind}`.
- **«Только для своих»** (`only_trusted`): поездки/заявки видят и берут лишь L3. Фильтр — per-user поверх кеша в `rides.py::_hide_trusted_only` (как `_hide_blocked`), в `/rides`, `/rides/near`, `/requests/feed`, `/requests/near`, `/match/rides`; свою поездку водитель видит всегда. Прямой id не обходит (guard в `respond_to_request`).
- **Приватность (IDOR):** чужой уровень/инвайты/согласия не отдаём — все эндпоинты только про себя. Удаление аккаунта (`account.py`) стирает Trust/InviteCode/Consent и отвязывает `Trust.invited_by`.
- **Миграция** `alembic/versions/p4_trust.py` (down_revision `0004_booking_boarding_code`) — идемпотентна (проверка inspector), проходит upgrade/downgrade и поверх create_all. **Свести миграцию при merge — лид** (несколько feat-веток от 0004).

**Кабинет партнёра (реклама, План B — в разработке):** `Ad` расширен полями `owner_id` (партнёр-владелец), `reject_reason`, `package`/`budget_kop`/`period_days` (тариф), `submitted_at`/`reviewed_at`; статусы `pending_review`/`rejected` добавлены к строке `status`. `User.is_advertiser`. Тарифы — конфиг `AD_PACKAGES` в `ads.py` (не хардкод в клиенте). Миграция `alembic/versions/0003_partner_ads_columns.py`. Приватность: партнёр видит/меняет только `owner_id==self`, админ — всё.

**F10 — Договорённость об оплате в брони (2026-07-05, ветка `feat/payment-agreement`):** `Booking` +2 поля `pay_method` (`PayMethod`: cash/sbp/negotiate, дефолт negotiate) + `pay_amount` (₽, опц.). Это ЗАПИСЬ «как договорились платить», **НЕ платёж** и не движение денег — юр-модель не меняется; видно обеим сторонам, опора в споре. Бэк (`bookings.py`): `BookIn` принимает способ/сумму при брони (дефолт суммы — цена поездки, `_clean_pay_amount` валидирует ≤100k); `POST /bookings/{id}/pay-agreement` — правка любой стороной; поля отдаются в `/bookings/{id}/details` и `/bookings/mine`. Миграция `alembic/versions/f10_payment_agreement.py` (revises `0004`, идемпотентна; цепочку сведёт лид при мердже). UI (`BookingActiveTripScreen.kt`): `PayAgreementBlock` — чипы способа + поле суммы до брони (`Canon*`, переиспользует `NearbyFilterChip`), read-only показ договорённости в деталях брони и активной поездке обеим сторонам. Тексты — `appText(ru,ba)`, ба-черновик → `docs/tasks.md`.

> Полный актуальный СТАТУС реализации — в [00-INDEX.md](00-INDEX.md) (блок 2026-06-30).

## Лендинг web/ — интерактивная версия 2026-06-29
- **Production-hardening 2026-06-30:** `web/` обновлён до Next.js 16.2.9, PostCSS поднят до 8.5.x через `overrides`, потому `npm audit --omit=dev` теперь 0 vulnerabilities. Build остаётся статическим (`output: "export"`) и деплоится как `web/out/` в `/var/www/yuldash-landing`.
- Главная страница `web/app/page.tsx` стала тонкой оболочкой: `MotionConfig` → `LangProvider` → `DownloadProvider` → `YuldashLanding`.
- Новый основной файл: `web/components/YuldashLanding.tsx`. Внутри: Lenis smooth scroll, живой hero с H1 «Юлдаш ведёт по республике красиво», видеофоном `/yuldash-promo.mp4`, анимированной маршрутной сценой, телефонным mockup, секции `#how`, `#map`, `#reviews`, `#download`, footer.
- Двуязычие лендинга осталось через `useLang()` и локальные пары RU/BA в `YuldashLanding.tsx`. BA-строки этой итерации — черновик модели, вынесены в `docs/tasks.md` как требующие проверки носителем.
- 21st.dev/shadcn-требование реализовано локальными минималистичными компонентами в стиле MagicUI/shadcn, потому что проверка `npm view @21st-dev/react` вернула `404 Not Found`.
- Новые зависимости `web/package.json`: `lenis` (плавный скролл) и `lucide-react` (иконки).
- Remotion-видео: `promo/src/Root.tsx` теперь 1920×1080, `promo/src/Promo.tsx` рисует сценарий поиск → бронь → поездка. Рендер: `web/public/yuldash-promo.mp4`.
- Проверка 2026-06-29: `web/npm run build` успешен, локально `http://localhost:3007` открылся, видео загружено (`readyState=4`), консоль браузера без ошибок.
- **Итерация "$15k" (2026-06-29):** после фидбэка «выглядит дешево» `YuldashLanding.tsx` переписан ещё раз в более строгую premium/editorial-систему: H1 = «Юлдаш», full-bleed карта/маршрут как первый экран, live-route панель вместо игрушечного телефона, 8px/острые панели вместо больших скруглений, без typewriter и 3D-карусели. `promo/src/Promo.tsx` теперь фоновый маршрутный ролик без emoji и текстовых слоёв; `web/public/yuldash-promo.mp4` ~860 КБ. Блок отзывов честный: реальные отзывы появятся после подтверждённых поездок, без выдуманных цитат. Проверено: `npm run build`, desktop/mobile браузер на `localhost:3007`, видео `readyState=4`, горизонтального скролла нет, console warn/error пусто.
- **Возврат первого направления (2026-06-29):** после фидбэка «первый вариант был лучше» лендинг возвращён к живой продуктовой версии: typewriter-hero, телефонный mockup, интерактивная SVG-карта, 3D-карусель beta-сценариев, тёплые CTA. Сохранены улучшения второй итерации: чистый Remotion-фон без текстового шума и честный блок отзывов без выдуманных реальных цитат. В `web/app/layout.tsx` исправлен конфликт Метрики (`id="yandex-metrika"` вместо `id="ym"`). Проверено: `npm run build`, браузерный smoke на `localhost:3017`, видео `readyState=4`, desktop/mobile без горизонтального скролла, console warn/error пусто.

## Главное

- **UI разрезан на модули (2026-06-27, Opus). `MainActivity.kt` 8184→664 строки.** Раньше весь UI был в одном файле — теперь по файлам (тот же пакет `com.yuldash.app`, общие символы `internal`). Каждый экран = свой файл → разные агенты пилят разные экраны параллельно. Карта файлов:
  - **Фундамент:** `AppText.kt` (двуязычие), `CanonTokens.kt` (палитра/формы/тема), `Domain.kt` (модели), **`UiKit.kt`** (продакшен-компоненты: `AppButton`/`AppStateContainer`/`AppLoading`/`AppErrorState`/`AppEmptyState`/`SkeletonBox`/`SkeletonCard`/`AppCard`/`SectionHeader` — единый источник правды для кнопок и состояний loading/empty/error; команда `/ui`).
  - **Экраны:** `LoginScreen.kt`, `SupportBoostScreen.kt`, `SecondaryScreens.kt` (Уведомл/Безоп/Настр/Помощь), `AccessibilityScreens.kt` (доступность/семья), `SosVerifyScreens.kt` (SOS+проверка водителя), `CreateRideScreen.kt`, `ProfileScreen.kt` (+кабинеты), `BookingActiveTripScreen.kt`, `RidesRequestsChatScreens.kt` (Поездки+Заявки+Чат), `MapScreen.kt` (Яндекс MapKit).
  - `AccessibilityScreens.kt`: `SimpleModeScreen` закрыт как хаб доступности. Серверные действия (`VoiceRequest`, `FamilyOrder`, `TrustedContacts`, `RepeatTrip`, `CallbackHelp`, `Chat`) без токена ведут на `Login`, потому что `/requests`, `/trusted-contacts`, `/my-routes`, `/callback` требуют auth. `RepeatTripScreen` после входа грузит маршруты через `ApiClient.getMyRoutes()` (`/my-routes`), показывает loading/error/empty, при ошибке всё равно показывает быстрые варианты, а повтор создаёт серверную заявку через `ApiClient.createRequest()` (`/requests`) только после успешного ответа. `SosScreen`: звонки 112/102/101/103 доступны без входа; отправка близким/поддержке требует вход и ведёт на `Login`.
  - **Навигация:** `YuldashApp.kt` — корень (`when(screen)`) + `HomeScreen` + нижнее меню.
  - **`MainActivity.kt` (1008 строк)** — тонкий общий слой: класс `MainActivity`, `enum Screen`, сплэш/онбординг, общие модели/моки (`demoRides`/`demoPartnerAds`), OAuth-хелперы (`openTelegramLogin`…).
  - ⚠️ **Параллелим аккуратно (§12 CLAUDE.md):** разные файлы-экраны — можно разом; но `MainActivity.kt` (общий слой) — один писатель.
- Тема: `android/app/src/main/java/com/yuldash/app/ui/theme/Theme.kt`

### 🆕 Новые экраны и поток заявок (2026-06-28, большая сессия)
> Номера строк по файлам устарели после рефактора — ориентируйся по именам функций (грепай), не по строкам.

**Поток заявок «заявка → водитель → отклик → поездка»** (замкнут, был «в никуда»):
- `RequestsFeedScreen` (`RidesRequestsChatScreens.kt`) — водитель видит заявки пассажиров (`getRequestsFeed`), откликается (`respondToRequest`). Вход: Кабинет водителя → «Заявки пассажиров».
- `ResponsesScreen` (там же) — пассажир видит отклики на свою заявку (`getRequestResponses`), принимает (`acceptResponse` → booking_id → активная поездка). Вход: Чат → вкладка «Заявки» → тап по заявке.
- Бэкенд: таблица `RequestResponse`; `GET /requests/feed`, `POST /requests/{id}/respond`, `GET /requests/{id}/responses`, `POST /responses/{id}/accept` (создаёт Ride+Booking с чатом/кодом посадки).

**Кабинет админа** (`SecondaryScreens.kt`, вход: Настройки → «Кабинет админа», только `isAdmin` из `/me`):
- `AdminCabinetScreen` — хаб; `AdminRequestScreen` (заявка за юзера по телефону, `/admin/request-for-phone`); `AdminResponsesScreen` (принять отклик ЗА юзера без интернета); `AdminDriversScreen` (модерация: фото прав/авто через Coil+Bearer к `/secure/docs`, одобрить/отклонить); `AdminReportsScreen` (жалобы `/admin/reports`).
- **Автоадмин:** вход через Telegram-id (`ADMIN_TELEGRAM_CHAT_ID`) ИЛИ телефон (`ADMIN_PHONES`) → роль admin сама (`_maybe_promote_admin` в auth.py).

**Профиль/доверие** (`ProfileScreen.kt`): имя редактируется (карандаш, `updateName`→`/me/update`); аватар (пикер→`uploadChatPhoto`→`updateAvatar`, Coil) — везде через `SmallAvatar`; онлайн-водитель — тумблер в `DriverCabinetScreen` (`setOnline`→`/driver/online`), бейдж `OnlineBadge`, `RideOut.driver_online`.
**Удаление аккаунта** (danger-zone в `ProfileScreen`, только для залогиненных): `deleteAccount()`→`POST /me/delete` (auth) → сервис `app/account.py::delete_user_account` стирает строки во ВСЕХ таблицах юзера в FK-безопасном порядке + best-effort медиа (аватар/доки водителя/его голосовые); отвязывает чужие ссылки (`referred_by`, `Ad.created_by`→NULL). Клиент после успеха чистит сессию (`clearLocalSession`, реюз с logout) → `Screen.Login`. Необратимо (152-ФЗ). Тест: `test_delete_account_wipes_all_data`.
**Ориентация:** приложение зафиксировано в портрет (`android:screenOrientation="portrait"` на `MainActivity`) — как Яндекс Такси/inDrive/Uber; ландшафт-вёрстки нет, поворот не ломает UI.
`PassengerCabinetScreen` теперь даёт прямой вход в «Мои поездки», показывает loading/error для `/bookings/mine`, а ближайшая бронь открывает реальный booking-flow: `pending` → `BookingScreen`, `confirmed/onboard` → `ActiveTripScreen`.

**Вкладка «Мои поездки»** (`RidesRequestsChatScreens.kt`): список берётся из `ApiClient.getMyBookingsDetailed()` (`/bookings/mine`). `pending` открывает приватные детали брони через `BookingScreen` + `bookingId`; `confirmed/onboard` открывают активную поездку/чат через `ActiveTripScreen`; история (`done/cancelled`) ведёт на повтор маршрута через создание заявки. `BookingScreen` больше не использует моковую карту деталей: `/bookings/{id}/details` отдаёт координаты концов маршрута (`from_lat/from_lng/to_lat/to_lng`), а если старая поездка без координат — endpoint сам lazy-backfill-ит их через `geocode_city` и сохраняет; Android рисует настоящий `MapView`. Точная точка встречи (`pickup_lat/lng`) всё ещё закрыта до подтверждения.

**Прочие новые** (`SecondaryScreens.kt`): `RulesScreen`, `PaymentInfoScreen` (СБП), `BlocklistScreen` (`/blocks`), `ReportScreen`, `FiltersScreen` (`FilterPrefs` в SharedPreferences), `ThemePickerDialog`. Код посадки — карточка в `BookingActiveTripScreen` (`getBoardingCode`). Аналитика — `data/Analytics.kt` (Firebase).

### 🆕 F7 — Текстовые отзывы + публичный профиль водителя (ветка `feat/reviews-driver-profile`)
- **Экран `DriverProfileScreen.kt`** (`Screen.DriverProfile`): публичная витрина доверия — фото, бейдж «Проверен», стаж в Юлдаше (плитка из даты регистрации), число завершённых поездок, средний рейтинг★, последние текстовые отзывы (прошедшие модерацию). БЕЗ ПДн (телефона нет). Все состояния: загрузка/ошибка+повтор/успех/пусто. Данные — `ApiClient.getDriverPublic(id)` → `GET /drivers/{id}/public`.
- **Открытие** — тапом по строке водителя в карточке поездки (`RideCard` и `NearbyRideCard` в `RidesRequestsChatScreens.kt`). Навигация без протаскивания колбэков: `LocalOpenDriverProfile` (CompositionLocal в `MainActivity.kt`), провайдится в `YuldashApp.kt`; `RideDto.driverId`/`Ride.driverId` (из `driver_id` бэкенда) — кого открыть.
- **Текстовый отзыв** — карточка оценки в `BookingActiveTripScreen` (при `bookingStatus==done`): звёзды уходят сразу, опц. поле отзыва (≤500) → `ApiClient.rateBooking(id, stars, text)`. Текст идёт на модерацию (в профиле появляется только после одобрения).
- **Бэкенд:** `Rating.text` + `Rating.text_published` (модель + alembic `0005_rating_text_review`, идемпотентная). `family.py::rate_booking` принимает `text`; смена текста → снова на модерацию. `drivers.py::GET /drivers/{id}/public` (без auth, без телефона): агрегаты (done-брони по поездкам водителя, средний рейтинг, стаж в днях) + последние N отзывов с `text_published=True`. Модерация текста — `reviews.py`: `GET /admin/ratings/pending`, `POST /admin/ratings/{id}/publish` (паттерн 1-в-1 как у `AppReview`). Тесты — `tests/test_reviews_driver_profile.py`.

**enum `Screen`** пополнен: Rules, PaymentInfo, Blocklist, Report, Filters, AdminCabinet, AdminRequest, AdminResponses, AdminDrivers, AdminReports, RequestsFeed, RequestResponses, **DriverProfile** — каждый ветка в `when(screen)` (`YuldashApp.kt`).

- **Application:** `android/app/src/main/java/com/yuldash/app/YuldashApplication.kt` — отдаёт ключ Яндекс MapKit (`MapKitFactory.setApiKey`) при старте. Прописан в манифесте как `android:name=".YuldashApplication"`.
- **Ключ карты:** `local.properties` → `YANDEX_MAPKIT_KEY` (в `.gitignore`) → пробрасывается в `BuildConfig.YANDEX_MAPKIT_KEY` через `app/build.gradle.kts` (`buildConfig = true`). В коде ключ не хардкодим.
- Тексты-ресурсы: `android/app/src/main/res/values/strings.xml` (RU) + `values-ba/strings.xml` (BA).
  Но **бо́льшая часть надписей пишется прямо в коде** через `appText(ru, ba)`.
- Стек: Jetpack Compose, Material3, minSdk 26, target/compile 36, versionName 0.1.0.

## Лендинг для скачивания (`web/`) — добавлено 2026-06-27
- **Отдельная веб-зона**, не Android. Премиум-лендинг для скачивания APK напрямую.
- **Стек:** Next.js 16 (App Router) + framer-motion + Tailwind. `output: "export"` → чистая статика (`web/out/`), раздаётся Nginx на yulbash.ru (Node в проде НЕ нужен).
- **Цвета** синхронизированы с приложением (`Theme.kt`) через `web/tailwind.config.ts` (green #0B6B3A/#2FB36E, gold #D89B12, тёмный фон).
- **Двуязычие:** RU/БА через контекст `web/components/lang.tsx` (`dict` + `useLang`), переключатель в шапке. БА — черновик, на проверке (tasks.md → «Переводы на проверку»).
- **Структура:** `app/page.tsx` (обёрнут в `MotionConfig reducedMotion="user"`) собирает `Hero`/`Features`/`HowItWorks`/`Testimonials`/`Trust`/`FAQ`/`Download`/`Footer` + `Aurora` (фон) + `Header` + `StickyDownloadBar`. Появления — `Reveal.tsx` (whileInView). Мокап телефона — `PhoneMockup.tsx` (живая карта со слоями + едущая машина на CSS `offset-path`, анимации в `globals.css` `.mockup-*`; координаты маршрута SVG = offset-path машины).
- **Маркетинг-блоки:** герой — мини-метрики + платформы (Android доступно, iPhone «скоро»). `Testimonials.tsx` (3 отзыва, соцдоказательство). `FAQ.tsx` (аккордеон, 5 вопросов, снимает возражения). `StickyDownloadBar.tsx` (залипающая «Скачать» на мобиле, `scrollY>640`). `Download.tsx` — APK + iPhone «скоро» (disabled). `Footer.tsx` — разделы/документы/контакты + соцсети.
- **Конфиг ссылок:** `config.ts` — `SOCIAL` (telegram/vk/email), `LEGAL` (privacy/terms, заглушки `#`), `METRIKA_ID` (пусто = Метрика выключена) — подставить реальные.
- **Премиум-слой ($15k-проход, 2026-06-27):**
  - `StructuredData.tsx` — JSON-LD (SoftwareApplication + Organization + FAQPage) в `<head>` через layout → rich-сниппеты Google.
  - OG-картинка `public/og.png` (1200×630) — отрендерена из `web/og-source.html` через Playwright (источник держим для перегенерации; в `out/` не попадает). В метаданных `og:image`/`twitter summary_large_image` + `canonical`.
  - `Features.tsx` — **bento-сетка** (1 крупная featured-карточка с аватарами «свои» + 4.9★, остальные стандартные).
  - `Counter.tsx` — count-up метрик героя (0→100%, 0→4.9), `useInView`, уважает reduced-motion.
  - `ScrollProgress.tsx` — полоса прогресса прокрутки сверху (`useScroll`+`useSpring`).
  - `CookieConsent.tsx` — баннер согласия (152-ФЗ), `localStorage`, двуязычный.
  - `app/not-found.tsx` — кастомная брендовая 404 (двуязычная).
  - `layout.tsx` — skip-link (a11y) + слот Яндекс.Метрики (грузится только при `METRIKA_ID`).
- **Юр.страницы:** `app/privacy/page.tsx` + `app/terms/page.tsx` (статик-маршруты `/privacy`, `/terms`, в sitemap). Контент — `components/legal-content.ts` (`PRIVACY`/`TERMS`, тело RU — юридически значимый язык, заголовки двуязычные). Рендер — `components/LegalView.tsx` (шапка, переключатель RU/БА, двуязычная пометка про язык). ⚠️ В тексте плейсхолдер реквизитов оператора `[укажите ИП/ООО…]` — заполнить. Контакты в документах: Telegram @bairas_ntv, VK bairas_ntv.
- **Контакты (config `SOCIAL`):** Telegram `https://t.me/bairas_ntv`, VK `https://vk.com/bairas_ntv` (email убран из футера). `LEGAL` → `/privacy`, `/terms`.
- **Письмо от создателя (2026-06-27):** `FounderLetter.tsx` (секция `#founder`, после «Доверие») — фото `public/founder.jpg` (кадр 4:5 из оригинала, ~67 КБ) + личное письмо Байраса. Контент — `founder-content.ts` (двуязычный, BA — черновик). Сильный триггер доверия: фото, имя/роль, цитата-акцент, подпись, CTA «Скачать» + «Написать Байрасу» (Telegram).
- **Финальная полировка (2026-06-27):**
  - `Header.tsx` — полностью переписан: уплотнение при скролле (glass+тень), подсветка активной секции (IntersectionObserver + `layoutId` подчёркивание), **мобильное меню** (бургер → дровер справа, скрим, блок body-скролла, lang+CTA внизу). Шапка на `z-[60]` (выше cookie/sticky — иначе stacking-context съедал z, см. lessons.md).
  - Якоря: `section[id]{scroll-margin-top:88px}` в globals.css — заголовки не прячутся под шапку.
  - `PhoneMockup` — 3D-тилт за курсором (`useMotionValue`+`useSpring`, reduced-motion off).
  - `StickyDownloadBar` ↔ `CookieConsent` синхронизированы через событие `yuldash-cookie-ok` (sticky не появляется, пока cookie не принят).
- **Доделка до релиза без APK (2026-06-27):**
  - `APP_READY` (config) = false. Все кнопки «Скачать» идут через `DownloadProvider`/`useDownload`: при false → модалка «Приложение почти готово» + «Написать в Telegram» (ловит спрос); при true → реальное скачивание APK. Положишь APK → `APP_READY=true`.
  - `Comparison.tsx` (секция `#why`, после «Как это работает») — таблица Юлдаш vs Такси vs Автобус (колонка Юлдаш подсвечена), states yes/partial/no.
  - PWA: `app/manifest.ts` (Next отдаёт `/manifest.webmanifest` + link), иконки `public/icon-192/512.png`, `apple-touch-icon.png`, `favicon.ico` (сгенерены из logo через PIL). Метаданные иконок в `layout.tsx`.
  - `analytics.ts` `track(goal)` — цели Метрики (срабатывает только при `METRIKA_ID`). Трекаются: `download`/`download_intent`/`notify_telegram`.
- **Иконки (2026-06-27):** единый набор `icons.tsx` (Lucide-стиль, stroke 2, БЕЗ эмодзи): BadgeCheck/Star/LifeBuoy/Lock/ShieldHeart/Tag/UserGlyph. Trust — эмодзи (✅⭐🆘🔒) заменены на SVG в зелёных плашках + ховер-scale. Features: «Честная цена» → Tag, «Спокойствие» → ShieldHeart (отлично от Trust-бейджа), иконки масштабируются на hover. Аватары (отзывы/мокап) → монограммы (буква имени) в градиентном круге; ряд «свои» в featured → UserGlyph-круги. Декор-эмодзи в тексте (💚🛣🐎) оставлены как тёплые акценты, не UI-иконки.
- **21st.dev-приёмы (2026-06-27):** `BorderBeam.tsx` (бегущий свет по рамке — conic-gradient + mask-exclude + `@property --beam-a`, утилита `.shine-border` в globals) на CTA-блоке скачивания, фото создателя, featured-карточке возможностей. `SpotlightCard.tsx` + утилита `.spotlight` (свечение radial за курсором через CSS-переменные `--mx/--my`, ставит `spotMove`) на карточках «Возможности». Всё с reduced-motion.
- **Премиум-моушн (motionsites-стиль, 2026-06-27):** `Loader.tsx` (брендовый intro — курай+«Юлдаш»+прогресс, 1 раз/сессию через sessionStorage + модульный флаг против StrictMode-double-mount; таймер НЕ чистим в cleanup), `Marquee.tsx` (бегущая лента городов РБ с курай-разделителями, край-fade), `Reveal.tsx` апгрейд (blur+scale «фокус-ин» по всему сайту), герой: пословное появление заголовка (stagger+blur), scroll-parallax (телефон/текст разной скоростью + fade), cursor-spotlight (зелёное свечение за курсором), `DownloadButton` — магнитный эффект (тянется к курсору). Всё уважает reduced-motion.
- **Башкирский колорит (2026-06-27):** `Ornament.tsx` — `KuraiBloom` (цветок курая нарисован с нуля по референсу герба: веер из 7 листьев + ромб + завитки-рога/кускар; `variant="filled"` эмблема / `"outline"` контур для фона), `OrnamentKicker` (курай+линии над заголовками центр-секций: Features/How/Comparison/Testimonials/FAQ), `OrnamentBand` (кускар-разделитель: ромбы+завитки-рога, SVG pattern) — 2 шт в `page.tsx`. Курай-водяной знак в `Aurora` (медленно вращается, opacity ~0.05). Футер: курай + строка `kurai_meaning` («7 лепестков — 7 родов, ставших своими»). Цвета бренда (зелёный/золото) = цвета флага РБ + курай. Кнопки «Скачать»: при `APP_READY=false` подпись авто «Скоро запуск».
- **SEO/e2e:** `app/robots.ts` + `app/sitemap.ts` (force-static → `out/robots.txt`, `out/sitemap.xml`), OG/Twitter-метаданные + `metadataBase` в `layout.tsx`. Базовый URL — `SITE_URL` в `config.ts`.
- **APK:** `web/public/yuldash.apk` (в `.gitignore`), качается с `/yuldash.apk`. Размер/ссылка — `web/components/config.ts`. Деплой и Nginx — `web/README.md`.
- **`web/hero.html`** — отдельный standalone-герой (один файл, без сборки) для быстрого превью/правок; не часть Next-сборки.
- Сборка зелёная (6 статик-страниц), визуал всех секций прогнан через Playwright (RU/БА, десктоп+мобайл).
- ⚠️ Next 14.2.x держим намеренно: CVE из аудита — про self-hosted Node-сервер (SSR/Image Optimizer/middleware), у нас статик-экспорт → не применимы. На next@16 (мажор) не прыгаем.

## Сервер / интеграция (слой `data/`) — добавлено 2026-06-23
- API: **`https://yulbash.ru`** (FastAPI на сервере, см. [server.md](server.md)). Клиент: **`data/ApiClient.kt`** (object, встроенный `HttpURLConnection`, БЕЗ внешних зависимостей).
- Токен JWT в **`EncryptedSharedPreferences`** (`yuldash_secure`, с миграцией старого plaintext-токена; фоллбэк на обычные prefs если шифрование недоступно), автологин. `ApiClient.init(context)` зовётся в `YuldashApplication.onCreate`.
- Методы: `requestCode`/`verifyCode` (вход), `getRides`, `createRequest`/`getMyRequests`, `publishRide`, `book`(→ booking id), `sos`, `addContact`/`getContacts`, `sendMessage`/`getMessages`, `shareTrip`/`setTripStatus`, `getConversations`/`getNotifications`/`getPopularRoutes`/`getMyRoutes`/`getAds` (списки сервер→экран, везде демо-фоллбэк), `uploadVoice`/`sendVoiceMessage`. Все POST'ы — через `fireXxx` (fire-and-forget на долгоживущем scope `ApiClient.bg`, переживают навигацию; иначе scope экрана отменял запрос).
- DTO: `RideDto`, `RequestDto`, `ContactDto`, `MessageDto` (маппинг `RideDto` — один шов `JSONObject.toRideDto()`). Геокодер адресов — через бэкенд `/geocode` (`GeocoderClient`), ключ на сервере. Мёртвый слой `Models.kt`/`Repository.kt`/`MockRepository.kt` **УДАЛЁН** 2026-06-27 (0 ссылок).
- Загрузка с сервера: поездки и заявки — `LaunchedEffect` в `YuldashApp`; контакты — там же; сообщения — в `ActiveTripScreen`.
- **`ActiveTripScreen`** (`Screen.ActiveTrip`) — экран «Моя поездка» после брони: чат по `booking_id`, поделиться с контактом, статус поездки, SOS.

## 🔀 Переключатель режимов пассажира: Такси ↔ Попутка (2026-07-09, ветка `feat/mode-switch`)
- **Где:** вверху вкладки «Карта» (главный экран пассажира). Файл `ModeSwitchHome.kt`: `enum RideMode { Pooling, Taxi }`, `PassengerModeHome` (обёртка), `ModeSwitchBar` (2 больших сегмента ≥66dp: 🚗 Попутка зелёная / 🚕 Такси жёлтая), `ModeSegment`, `ModeHintSheet`.
- **Логика:** `HomeTab.Map` в `HomeScreen` теперь рендерит `PassengerModeHome` (раньше сразу `MapScreen`). Попутка (по умолчанию) → `MapScreen` (поиск плановых поездок); Такси → `InstantOrderScreen(embedded=true)` (без своей шапки — контекст задаёт переключатель). Смена — `AnimatedContent`. Аппаратная «Назад» в такси → к попутке (`BackHandler`). Вход требуется только для такси (`onInstantLogin` → `Screen.Login`).
- **Цвета:** токены `CanonTaxi/CanonTaxiBg/CanonTaxiInk` (жёлтый) и `CanonPooling/CanonPoolingBg` (зелёный бренд) в `CanonTokens.kt` (светлый/тёмный). Активный режим красит CTA и полоску-индикатор. CTA такси = «Вызвать машину» (жёлтая), попутки = «Найти попутку» (зелёная).
- **Подсказка первого входа:** `ModeHintSheet` (bottom-sheet, крупный текст) — один раз, флаг `mode_hint_shown` в `yuldash_prefs`. Повтор — ссылкой «Чем отличается?» у переключателя.

## Навигация (как устроены экраны)

- Нет навигационной библиотеки. Всё через `enum Screen` + `when(screen)` в `YuldashApp()`. **Первый экран — `Screen.Splash`** (зелёный мост → цель `splashTarget`; первый запуск → `Screen.Intro` морф-интро → онбординг; повтор → сразу Home/Login). Состояние навигации — в `YuldashViewModel` (переживает поворот/смерть процесса).
- **Интро на фоне пейзажа (2026-06-29):** `IntroScreen` теперь рисуется поверх вектор-пейзажа Башкортостана `res/drawable/splash_landscape.xml` (горы, долина, золотая дорога-маршрут, сосны, курай — порт из `yuldash_splash_vector.svg`, viewport 1080×1920; blur/тень SVG не переносятся). Фон: мягкий Ken-Burns (`sceneScale` 1.08→1.0) + полупрозрачная вуаль/виньетка под читаемость белого текста. Премиум-моушн: «Попутчик»→«Юлдаш» с золотым бликом, золотая черта, слоган RU→BA, тап-скип, reduce-motion. Значок — чистый белый круг с лого (курай-веер и дорога-стрелка в `BrandHero` убраны по просьбе Александра 2026-06-29; функции `drawKurai`/`drawRoad` оставлены неиспользуемыми). Контракт `onComplete→Onboarding` и `Screen.Splash` (тонкий зелёный мост) не менялись.
- Внутри главного экрана 5 вкладок — `enum HomeTab` (Map, Rides, Request, Chat, Profile).
- Состояние держится в `remember { mutableStateOf(...) }`. Сохраняется на диск только флаг онбординга (`SharedPreferences "yuldash_prefs" → onboarding_completed`).
- Язык: `enum AppLanguage` (Ru/Ba), переключается кнопкой, раздаётся через `LocalAppLanguage`.

## Опорные точки (строки в MainActivity.kt)

> ⚠️ 2026-06-22: после дизайн-полировки ключевые якоря обновлены по `rg`; мелкие номера ниже остаются приблизительными.

### Основа
- `MainActivity.onCreate` — стр. 161
- enum `Screen` — 193 · `HomeTab` — 216 · `AppLanguage` — ~224 · `RideRole` — ~229
- цвета `Canon*` (палитра) — ~236 · формы `CanonCardShape/ItemShape` — ~245
- `appText(ru, ba)` (хелпер двух языков) — 249
- `data class Ride` — 298 · `AdPlacement` — 241 · `AdStatus` — 250 · `data class PartnerAd` — 353 · `demoPartnerAds` — 386 · `demoRides` (моки поездок) — 482
- Модели доступности: `TrustedContact` — 290 · `FrequentTrip` — 297 · `LocalRequest` — 305 · `LocalVoiceMessage` — 314
- **`YuldashApp()` — 525** ← корень навигации (`when(screen)`), вся маршрутизация, локальные счётчики рекламы и локальные состояния новых модулей

### Онбординг (4 слайда)
- `OnboardingScreen` — 4 слайда перед входом. V2 от 2026-06-28: большой photo/illustration hero `drawable-nodpi/onboarding_bashkir_hero.png` с дорогой/машиной/башкирским орнаментом, маленький логотип поверх, story-карточки шагов, актуальные тексты про Telegram-вход, проверку водителя, скрытый номер, SOS, заявки→отклики→поездку.

### Логин
- `LoginScreen` — **реальный вход через Telegram-код** (`LoginFormCard`: открыть Telegram-бота → получить 6-значный код → ввести код → JWT, автологин). SMS-вход заморожен флагом, основной рабочий канал — Telegram. `BrandHero` использует `drawable-nodpi/login_salavat_yulaev_hero.png` — сгенерированный вертикальный фон по референсу Александра с Салаватом Юлаевым, Уфой/Белой и дорогой; старый `login_bashkir_telegram_hero.png` оставлен для отката. `TrustCard` не обещает проверку каждого водителя, а честно показывает текущую модерацию прав/авто. Логика — `data/ApiClient.kt`.

### Главный экран (оболочка + нижнее меню)
- `HomeScreen` — 1294 (Scaffold + вкладки) · `YuldashBottomBar` — 1416

### Вкладка «Карта»
- `MapScreen` — структура: `Column` { ФИКС: шапка + `MapHero` (карта вне прокрутки!) ; `LazyColumn(weight 1f)`: «Ближайшие поездки» + реклама }. Карта закреплена, чтобы её жесты не конфликтовали со скроллом. `selectedRide` + `ModalBottomSheet` (`RideCard fullWidth`) при тапе по маркеру.
- **«Ближайшие поездки»** — реальные данные с сервера (`ApiClient.getNearbyRides` → `/rides/near`). Фокус-маршрут = `activeTrip` (если едет — показываем альтернативы на ЕГО маршруте, сценарий «водитель сломался»), иначе все. Сортировка по времени выезда ↑ (самая ранняя — первой, помечена «ближайшая»). Гео: `LocationPrefs.lastLat/lng` (из карты) → дистанция «N км» в карточке. Карточки `NearbyRideCard` строго 1-в-1 (фикс 290×190dp: маршрут+проверен · время+«ближайшая» · водитель+рейтинг · дистанция+цена+«Поехать»). Состояния: `NearbySkeletonCard` (загрузка), `NearbyEmptyCard` (пусто+Обновить). Хелперы `RideDto.toUiRide()`, `fmtKm()`.
- `MapHero` — `Column { Box(карта 350dp: `YandexMapCard`/`MapPreview` + плавающая снизу **лента** `QuickSearchCard` — свайп вправо→язычок `cardCollapsed`, тап→назад) ; Row **кнопки** «Найти поездку»/«Я водитель» ОТДЕЛЬНЫМ блоком ПОД картой (не плавают, как Яндекс; «Найти» берёт `activeRoute` ленты) }`. На холодном старте сначала рисуется лёгкий `MapPreview`, настоящий `YandexMapCard` создаётся после первого кадра и короткой паузы, чтобы `MapView` не блокировал первый экран. Зум `+/-` и FAB «к себе» — в верхнем правом углу карты.
- `QuickSearchCard` — лента-карусель из **6 карточек** (`mapFeedFrom(popular, liveFeed)` → `List<MapFeedCard>`). **Единый макет** у всех: бейдж+пилюля (верх) · заголовок ФИКС.высоты (2 строки) · подпись+точки (низ) → карточки одного размера, карусель не прыгает. Типы (`FeedKind`): Route (маршрут, тапается→`activeRoute`), Live (поездок за день), Top (хит недели — топ-маршрут), Fact (факт), Community ×2 (за месяц / за год). Периоды день/неделя/месяц/год. Авто-прокрутка 4.5с, бесконечная. **Числа реальные с сервера** — `MapHero` тянет `ApiClient.getFeed()` (`/feed`) раз в 60с; офлайн → демо-значения (142/4700/38500), чтоб лента не пустовала. Русский плурал — `plRu()`/`ridesRu()`.
- `cityPoint(city)` — 1824 ← город→`Point` (mock-геоданные: Баймаҡ/Сибай/Темясово/Уфа/Учалы/Магнитогорск). `ridePinBitmap(price, boosted)` — 1836 ← маркер-«ценник» (белая пилюля + цена, золото для boosted), рисуется на Android Canvas.
- **`YandexMapCard`** ← НАСТОЯЩАЯ Яндекс-карта (MapKit). `MapKitFactory.initialize` + `MapView` через `AndroidView`, ЖЦ через `DisposableEffect`. **Маркеры-ценники поездок** (`addPlacemark`+`MapObjectTapListener`→`onRideTap`; координаты `cityPoint` + Яндекс-геокодер с кэшем). **Контролы:** зум `＋/−` (`MapZoomControls`), FAB «к себе» (`NearMe`, `zIndex(6)` — выше хит-таргетов). **Геолокация «я тут»:** СВОЙ `PlacemarkMapObject` (зелёный кружок `userPuckBitmap`) через android `LocationManager` (GPS+NETWORK) — НЕ `UserLocationLayer` (его стрелку-курс lite-SDK не перекрасить → тёмный треугольник). Флаг `LocationPrefs.sharingEnabled` (Профиль→Конфиденциальность ↔ карта), дефолт ВЫКЛ; при включении центрируем (цель чуть южнее → точка над плашкой). `view.setOnTouchListener`→`requestDisallowInterceptTouchEvent` (чтобы `LazyColumn` не съедал жесты).
- `MapPreview` — 1998 ← рисованный **фолбэк**: показывается если ключ MapKit пустой (`BuildConfig.YANDEX_MAPKIT_KEY.isBlank()`), а на главной карте ещё используется как короткий лёгкий каркас перед созданием тяжёлого `MapView`.

### Вкладка «Поездки»
- `RidesScreen` — 2081 · `SegmentedTabs` — ~2350 · `MyTripCard` — ~2620 · `RideCard` — ~2200 · `FullRideCard` — ~2650. Табы: `Активные / История / Все`; при пустом списке используется `EmptyStateCard`.

### Вкладка «Заявка»
- `MyRequestsScreen` — 2482 · `RequestSummaryCard` — ~2520 · `DraftRequestCard` — ~2580. Табы: `Мои заявки / Отклики / Черновики`.

### Вкладка «Чат»
- `ChatScreen` — 2769 · `ChatComposer` — 2896 · `VoiceMessageCard` — ~2940 · `ChatCard` — ~2850

### Вкладка «Профиль»
- `ProfileScreen` — 3442 · `ProfileActionCard` — ~3548 · `AdsCabinetScreen` — 3585 · `AdsAdminPreview` — 3622 · `CompactProfileBanner` — ~4400

### Вторичные экраны
- Новые сценарии доступности: `SimpleModeScreen` — 2957 · `VoiceRequestScreen` — 3066 · `FamilyOrderScreen` — 3136 · `TrustedContactsScreen` — 3192 · `RepeatTripScreen` — 3251 · `CallbackHelpScreen` — 3303
- `CreateRideScreen` — 3271 · `BookingScreen` — 3872 · `NotificationsScreen` — 4002 · `SafetyScreen` — 4094 · `SettingsScreen` — 4149 · `HelpScreen` — 4200 · `SosScreen` — 4356 · `VerifyDriverScreen` — 4434 · `SupportScreen` (донаты, мок) — 4541 · `BoostScreen` (поднятие, мок) — 4617

### Переиспользуемые куски
- `InfoCard` — 3746 · `EmptyStateCard` — 3776 · `PartnerAdCard` — 3814 · `DetailMeta` — ~4000 · `SettingsGroup` — ~4370 · `SettingsNavRow` — ~4380 · `SettingSwitchRow` — ~4390

### Реклама / партнёры (добавлено 2026-06-22, Ads 2.0)
- Модель: `AdPlacement`, `AdStatus`, `PartnerAd`, `AdStats`, `demoPartnerAds` — ~213–500.
- `PartnerAd` хранит: рекламодатель, `erid`, город, маршрут, категорию, даты, статус, места показа, пакет, бюджет, целевое действие, контакт, точку карты, CTA.
- Локальная статистика показов/кликов: `adStats`, `trackAdImpression`, `trackAdClick` внутри `YuldashApp()` — ~520–560.
- Универсальная карточка рекламы: `PartnerAdCard` — 3814. В пользовательских местах показывает `Реклама · erid`, короткие чипы, название, описание, город/адрес, партнёрскую подпись и CTA. Подробные поля для управления не выводятся в обычном контенте.
- Кабинет рекламы: отдельный экран `AdsCabinetScreen` — 3585; внутри `AdsAdminPreview`, `AdSummaryMetric`, `AdsLaunchChecklist`, `AdChecklistRow` — ~3622–3730.
- Подбор: `activeAds()`, `forPlacement()`, `forCity()`, `forRoute()`, `forCategory()` — 3933–3948.
- Размещения фильтруются по `AdPlacement`: `MapScreen` после ближайших поездок, `RidesScreen` после обычной карточки, `BookingScreen` ниже карты маршрута, `ProfileScreen` блок «Партнёры Юлдаш», `HelpScreen` полезный партнёр.
- Не размещать рекламу в SOS, безопасности, активном чате, кнопках подтверждения и оплате.

## Мёртвый код — удалён (2026-06-22)
- `RequestTabScreen`, `DirectionChips`, `FiltersCard` удалены (не вызывались, ~265 строк). При необходимости — в истории git.

## Доступность / семья (добавлено 2026-06-22)
- Входы: карточка `SeniorAccessCard` на вкладке Карта и раздел «Для родителей и близких» в Профиле.
- `SimpleModeScreen`: крупные действия для пожилых пользователей.
- `VoiceRequestScreen`: локальная имитация голосового ввода, автозаполнение маршрута и добавление `LocalRequest`.
- `FamilyOrderScreen`: локальная заявка за близкого с пассажиром, телефоном и доверенным контактом.
- `TrustedContactsScreen`: список доверенных контактов и локальное добавление контакта.
- `RepeatTripScreen`: повтор частого маршрута из `demoFrequentTrips`.
- `CallbackHelpScreen`: локальный статус заявки на обратный звонок.
- `ChatScreen`: локальное голосовое сообщение сохраняется как `LocalVoiceMessage` с текстовой расшифровкой.

## Что РЕАЛЬНО на сервере (обновлено 2026-06-23)
Подключено к бэкенду `https://yulbash.ru` через `data/ApiClient.kt`: **вход через Telegram-код** (JWT, автологин; SMS-код заморожен флагом), **поездки** (список), **заявки** (создать+список), **публикация поездки**, **бронь**, **SOS**, **доверенные контакты**, **чат**, **экран активной поездки** (share/статус). Данные живут на сервере (PostgreSQL), не пропадают при перезапуске.

### 🆕 UI доверия «между своими» — уровни L0–L3, инвайты, «только для своих», согласия (2026-07-06, ветка `feat/trust-ui` от `feat/trust-levels`)
Клиентский UI поверх бэкенда доверия (Фаза 4). Новый файл **`TrustScreens.kt`** — три экрана + `enum Screen` пополнен `Trust`, `Invites`, `Consents` (ветки в `when` внутри `YuldashApp()`).
- **`TrustScreen`** (`Screen.Trust`, вход: Профиль → «Доверие»): текущий уровень L0–L3 (карточка с бейджем + горизонтальная лесенка `TrustLadder` из 4 сегментов, анимация заполнения), «что тебе доступно» (benefits с сервера), карточка «следующий уровень» (что даёт + `how` + действие: L0→L1 профиль, L1→L2 проверка, L2→L3 инвайт), для L2+ — callout «ты можешь звать своих». Данные — `ApiClient.getMyTrust()` (`GET /me/trust`). Деликатно: L0 — полноправный участник, тексты мотивируют, не унижают.
- **`InvitesScreen`** (`Screen.Invites`, «Позвать своего»): для не-L3 — поле «ввести код» → `redeemInvite` → «Теперь ты свой»; для L2+ — список моих кодов (`getMyInvites`) с шерингом через системный share-sheet + «Создать код» (`createInvite`); для непроверенных — деликатная заглушка «пройди проверку». 
- **`ConsentsScreen`** (`Screen.Consents`, вход: Профиль → «Согласия и данные» и Настройки → приватность): оферта/политика/гео с датами согласия (`getConsents`), кнопка «Отметить» (`setConsent`). 152-ФЗ.
- **Тумблер «Только для своих»** (`only_trusted`) в создании поездки (`CreateRideScreen`/`CreateRideFormContent`) и заявки (`CreatePassengerRequestScreen`/`Content`) — отдельная карточка с пояснением «увидят только проверенные свои (L3)». Прокинут в `ApiClient.publishRide`/`createRequest`.
- **`ApiClient`**: `getMyTrust`/`createInvite`/`getMyInvites`/`redeemInvite`/`getConsents`/`setConsent` + DTO `TrustSummaryDto`/`TrustNextDto`/`Bilingual`/`InviteDto`/`ConsentDto`; `only_trusted` в теле `publishRide`/`createRequest`.
- Все надписи двуязычны (`appText`); черновой башкирский → `docs/tasks.md` «Переводы на проверку — Доверие». Все состояния (загрузка/ошибка/пусто), тач-цели ≥48dp, `Canon*`. ⏳ Собрать APK перед мержем; вливать ПОСЛЕ бэкенд-PR #38 (эта ветка от `feat/trust-levels`).

## F8 — Бейджи и «стаж своего» (2026-07-05, ветка `feat/trust-badges`, draft PR)
- **Агрегаты, БЕЗ новых таблиц.** `RideOut` (`schemas.py`) +2 поля: `driver_trips` (завершённых поездок = distinct done-броней водителя) и `driver_since` (`"YYYY-MM"` из `User.created_at`). Считаются батчем в `services.py`: новый хелпер `driver_trips_agg()` + расширены `drivers_bundle()` (теперь 4-кортеж) и `ride_out_with()` (принимает `trips_agg`). Один запрос на весь список карточек — без N+1. Проходят через `public_ride_payload` (не приватные).
- **Почему done-брони, а не done-поездки:** завершение ставит `Booking.status=done`, а `Ride.status` остаётся `active` (см. `bookings.py::driver-status`), поэтому «N поездок» меряем по завершённым броням (distinct по `ride_id`).
- **UI** (`RidesRequestsChatScreens.kt`): `DriverTrustBadges` (FlowRow-чипы Canon: Проверен · N поездок · с <мес год>) в детальной `RideCard`; `CompactTrustLine` (одна строка в weight-зоне, не растит фикс-высоту) в `NearbyRideCard`. RU-плюрал `tripsWordRu`, месяцы `f8MonthsRu/Ba`. `RideDto`/`Ride`/`toUiRide` проброшены. Длинный башкирский переносится/обрезается — вёрстка цела.
- **Пропущено честно:** «Земляк» (нет города у `User`/`DriverProfile`), «Отвечает быстро» (нет `confirmed_at`). Появятся данные → добавим.
- Тесты: `backend/tests/test_trust_badges.py` (6). Android — собрать (нет SDK в worktree). Черновой башкирский → `docs/tasks.md` «Переводы на проверку — F8».

## F9 «Женщинам — водитель-женщина» (ветка `feat/women-driver`, 2026-07-05) — opt-in
> Чувствительная тема → строго добровольно (opt-in). Показываем только полезный сигнал «женщина за рулём»; мужской пол наружу не выпячиваем.
- **Бэкенд:** `DriverProfile.gender` (`""` не указан / `female` / `male`, дефолт `""`). Миграция `alembic/versions/f9_driver_gender.py` (revision `f9_driver_gender`, down_revision `0004_booking_boarding_code`, идемпотентна). Эндпоинт `POST /driver/gender` (меняет только сам водитель, валидация значений); `GET /driver/status` отдаёт `gender` (виден только владельцу). `RideOut.driver_is_woman` = `gender=="female"` — БИНАРНЫЙ публичный сигнал (male и «не указан» неотличимы → приватность). Фильтр `GET /rides?women_only=true` теперь `Ride.women_only == True OR DriverProfile.gender=='female'` (OUTER JOIN). Тесты `tests/test_women_driver.py` (opt-in, поиск женщины, приватность мужчины) — pytest зелёный.
- **Android:** `Ride.driverIsWoman`/`RideDto.driver_is_woman` (парсинг+маппинг в `toUiRide`/inline). Бейдж `WomanDriverBadge()` (токены `CanonWoman`/`CanonWomanBg` в `CanonTokens.kt`, иконка `Woman`) в `RideCard` и `NearbyRideCard` (`RidesRequestsChatScreens.kt`). Тумблер opt-in «Я — женщина за рулём» в `DriverCabinetScreen` (`ProfileScreen.kt`, `ApiClient.setDriverGender`). Клиент-фильтр «Только женщины» в `MapScreen.kt` теперь пропускает и женщин за рулём + пояснительная подпись. Всё двуязычно (`appText`), черновой ба → `docs/tasks.md` «Переводы на проверку — F9».
- ⚠️ **Цепочку миграций сведёт лид** (несколько фича-веток ответвлены от `0004` → параллельные alembic heads).

## Бэкенд: премиум-поля и проверка водителя (2026-06-24, ✅ ЗАДЕПЛОЕНО на yulbash.ru)
- `Ride` + `RideIn`/`RideOut`: новые булевы поля предпочтений — `pets_allowed` (животные), `child_seat` (детское кресло/бустер), `women_only` (только женщины), `smoking`, `baggage`, `air_conditioner`. `GET /rides` принимает их как фильтры. Дефолты `False` → обратносовместимо.
- Проверка водителя (реальная, без платного KYC): `POST /upload/photo` (base64→`media/docs`), `POST /driver/profile` (реальное авто), `POST /driver/verify` (→`docs_status=pending` + `license_url`/`car_photo_url`), `GET /driver/status`, `POST /admin/drivers/{id}/moderate` (роль admin → `User.verified=True`). `DriverProfile` расширен (`license_url`, `car_photo_url`, `verify_submitted_at`, `docs_status` дефолт→`none`).
- ✅ Задеплоено на `yulbash.ru` (`deploy-backend.bat` scp+ssh + `migrate_premium.sql` = 9× `ALTER TABLE`: 6 колонок `ride` + 3 `driverprofile`). Проверено server-side: `/driver/status`, `/upload/photo` → 401 (живы, нужен токен), `/rides/near` отдаёт `women_only`/`pets_allowed`. **Прод-API проверять server-side через ssh** (`curl localhost:8000/...`) — Windows-curl к домену = `000` (ТСПУ).
- ✅ Android ИСПОЛЬЗУЕТ (сборка зелёная на ПК): чипы предпочтений `RidePrefChips`/`PrefChip` в `FullRideCard`, тумблеры `PrefToggleRow` в «Создать поездку», фильтр `NearbyFilterChip` над «Ближайшими», переписанный `VerifyDriverScreen` (загрузка фото прав/авто `UploadTile`→`/upload/photo`, `/driver/profile`, `/driver/verify`, статус-баннер `StatusBanner` из `/driver/status`). Работает по-настоящему (бэк живой). Осталось: водительский UI оценки пассажира, фильтр вкладок чата.

### 🆕 Авто-проверка водителя — OCR прав (2026-06-29, Opus) — ✅ ЗАДЕПЛОЕНО на yulbash.ru
> Цель: снять с админа рутину. Сервер сам читает права при `/driver/verify`, помечает заявку.
> **✅ Живо на проде (2026-06-29):** миграция 4 колонок применена, код залит (только 4 файла, не вся `app/` — прод впереди: `content.py`/`referral.py`/`vk_id`/`whatsapp_verified` сохранены), `YANDEX_VISION_KEY` в прод `.env`, сервис `active`/`db:ok`. **E2E на самом проде:** тест-фото прав → `result:pass`, номер+срок разобраны. Тестовый ключ Александр ротирует.
- **Новый модуль `backend/app/driver_check.py`:** `check_driver_docs(license_url, car_photo_url)` → `{result, score, data}`. `result`: `pass` / `needs_human` / `reject` / `error`. OCR — **Yandex Vision** (`ai.api.cloud.yandex.net/ocr/v1/recognizeText`, заголовок `Authorization: Api-Key`, ~0,13 ₽/фото). `x-folder-id` опционален: ключ AI Studio работает без него (ключ SA — нужен). **Проверено вживую (2026-06-29):** STATUS 200, текст прав распознан, номер+срок разобраны, e2e через код = `pass`. Локальные гейты бесплатны.
- **Логика (консервативная, доверие важнее скорости):** авто-**reject** ТОЛЬКО на явный мусор (нет текста/не похоже на права); просрочка и сомнения → **needs_human**; **pass** = есть номер прав + срок в будущем + score≥порог. OCR недоступен/ошибка → `needs_human`/`error`, **заявку не валим** (остаётся pending).
- **`DriverProfile` +4 поля:** `autocheck_result`, `autocheck_score`, `autocheck_data` (JSON: распознанные поля + коды причин), `autocheck_at`. Для sqlite авто-мигрируются (`_migrate_sqlite_add_columns`); **на проде PG нужен `ALTER TABLE ADD COLUMN`** (см. lessons.md).
- **Шов:** `drivers.py::_run_autocheck()` в `/driver/verify`. Флаги в `config.py`/`.env`: `DRIVER_AUTOCHECK_ENABLED` (вкл), `DRIVER_AUTOREJECT_ENABLED` (вкл), **`DRIVER_AUTOAPPROVE_ENABLED=false`** (по умолчанию ВЫКЛ — финальную кнопку жмёт админ), `YANDEX_VISION_KEY`/`YANDEX_VISION_FOLDER_ID`. Без ключа всё уходит к человеку.
- **Админ-очередь** `/admin/drivers/pending` и `/driver/status` теперь отдают `autocheck_*` (подсказка админу + статус водителю). Проверено: 18 unit-проверок PASS (разбор номера/срока, все ветки решения, безопасность autoapprove-OFF), `py_compile` + импорт приложения OK.
- ✅ **OCR проверен живым ключом** + **e2e на проде** (тест-фото прав → 200, `pass`, разбор номера/срока). Хост/авторизация/формат ответа подтверждены.
- ✅ **Задеплоено + миграция применена** (см. блок выше).
- ✅ **Клиент — админ-очередь:** `AdminDriversScreen` (`SecondaryScreens.kt`) показывает бейдж `AutoCheckRow` — авто-вердикт + распознанные № прав/срок (парсит `autocheck_data`). `PendingDriverDto`/`DriverStatusDto` расширены. Android BUILD SUCCESSFUL. В APK при следующей сборке релиза (бэк уже отдаёт поля, старый APK игнорит).
- ⚠️ **Осталось:** (1) Александр — ротировать тестовый `YANDEX_VISION_KEY`; (2) клиент — UI причины водителю в `VerifyDriverScreen` (данные уже в `DriverStatusDto`).

### 🆕 Интерим-модерация оплат (буст+донат+реклама) — ✅ ЗАДЕПЛОЕНО (2026-06-29, Opus)
> До ЮKassa (нужна публикация): СБП вручную, но доведено до рабочего — оплата→заявка админу→сверил карту→подтвердил→запустилось. Подробности — decisions.md.
- **Бэкенд** (`routers/payments.py`): `POST /donate` (purpose=donate, заявка pending), `GET /admin/payments/summary` (счётчик подтверждённых донатов/буста), Telegram-уведомление админу при новой оплате (`_notify_new_payment`→`notify_admin_telegram`). Буст-очередь (`/admin/payments/pending|confirm|reject`, `_activate_boost`) была раньше. **Деплой:** только `payments.py` (прод==база), прод `.env` → `PAYMENTS_PROVIDER=sbp_manual` + `SBP_PHONE/BANK/NAME`. Flow-тест на прод-venv 12/12 PASS.
- **Android** (`SecondaryScreens.kt`): `AdminPaymentRequestsScreen` — очередь заявок (бейдж Буст/Донат/Реклама, сумма, плательщик, Подтвердить/Отклонить) + карточка-счётчик донатов; пункт «Заявки на оплату» в `AdminCabinetScreen`; `enum Screen.AdminPaymentRequests` + ветка в `YuldashApp`. `ApiClient`: `createDonation`/`getPendingPayments`/`confirmPayment`/`rejectPayment`/`getPaymentsSummary` + DTO. Донат-кнопка (`SupportScreen`) → `createDonation` (заявка), `SbpTransferSheet` берёт реквизиты из ответа (хардкод `SBP_*` в MainActivity → лишь фолбэк). BUILD SUCCESSFUL.
- **Реклама партнёров через очередь** (`ads.py` + `AdminAdsScreen.kt`): поле «Цена партнёру, ₽» в форме. Цена>0 → `Payment(purpose="ad", ad_id)` pending → в «Заявки на оплату» (с партнёром+названием); подтвердил → `_activate_payment` публикует объявление (`Ad.status=active`). Цена 0 → ручная публикация как раньше. Поле `payment.ad_id` (миграция `migrate_ad_payment.sql`). `_activate_boost`→`_activate_payment` (boost+ad+donate). Прод flow-тест 17/17 PASS, задеплоено.
- **Редактирование объявлений** (`AdminAdsScreen.kt`, только Android): кнопка «Изменить» на карточке → форма предзаполняется (`AdminAdDto` + парсинг расширены `button`/`target`/`cities`) → `ApiClient.updateAd`→`POST /admin/ads/{id}` (эндпоинт уже был). Бэкенд НЕ менялся.
- ⚠️ **Осталось:** релизный APK (бэк уже отдаёт, старый APK игнорит). ЮKassa — после публикации (код готов, `payments.py`).

## Push-уведомления (FCM) — реализованы, Firebase настроен (2026-06-27)
Полный end-to-end, активируется наличием конфигов (без них — тихо выключено, сборка не падает):
- **Android:** `data/FcmService.kt` (`FirebaseMessagingService` — показ нотификации), регистрация токена `ApiClient.registerPushToken`/`getFcmToken` (`FirebaseMessaging.getInstance().token` → `POST /push/register`), permission `POST_NOTIFICATIONS` (Android 13+). Плагин `com.google.gms.google-services` применяется в `build.gradle.kts` **только при наличии** `app/google-services.json`; зависимости `firebase-bom` + `firebase-messaging-ktx`.
- **Бэкенд:** `services.send_push(session, user_id, title, body)` через `firebase_admin` → шлёт на все `DeviceToken` юзера. Триггеры: новая бронь (`bookings.py`), новое сообщение чата (`chat.py`), SOS. `POST /push/register` сохраняет токен. Конфиг `firebase_credentials` (путь к service-account JSON).
- **Конфиги на месте:** прод `/opt/yuldash/firebase-service-account.json` + `FIREBASE_CREDENTIALS` в `.env` (бэкенд шлёт); `android/app/google-services.json` — в главном чекауте Александра (в `.gitignore`, потому в worktree-сборках FCM скомпилён, но неактивен → токен не регистрируется).
- ⚠️ **Осталось:** проверить реальную доставку на устройство (сборка из главного чекаута). Для теста в worktree — скопировать `google-services.json` в `android/app/`.

## F13 — Подписка на маршрут «карауль поездку» (2026-07-05, ветка `feat/route-watch`)
Retention-двигатель: юзер подписывается на маршрут (Сибай→Уфа), и как только водитель публикует подходящую поездку — приходит push + запись в ленте уведомлений.
- **Бэкенд:** модель `RouteWatch` (user_id, from_city, to_city, опц. `watch_date`, `direction` forward/both, анти-спам `last_notified_at`, `expires_at`=+14 дней) + `Notification` (персистентная лента). Роутер `app/routers/route_watch.py`: `POST /route-watch` (создать; дубль того же маршрута → продлеваем, не плодим), `GET /route-watch` (мои непротухшие), `DELETE /route-watch/{id}` (только свою). Матчинг — `services.notify_route_watchers(session, ride)`, вызывается из `rides.create_ride` после коммита. Анти-спам: ≤1 пуш на подписку в сутки; протухшие (>14 дней) не матчатся и скрыты. Пуш/запись двуязычны по `user.language`. Города — как есть. Лента `/notifications` (chat.py) теперь мержит `Notification`. Миграция `alembic/versions/0005_route_watch.py` (идемпотентна). Тесты `tests/test_route_watch.py` (10): CRUD, матчинг right/not-wrong, self-skip, both-направление, анти-спам, протухание, дата-фильтр, валидация.
- **Android:** `Screen.RouteWatches` + ветка в `YuldashApp.kt`. Экран `RouteWatchesScreen` (`SecondaryScreens.kt`): форма (откуда/куда + тумблер «туда-обратно») + список подписок с удалением, все состояния (loading/error/empty). Вход: карточка «Мои подписки на маршрут» в `NotificationsScreen` + кнопка «Следить за маршрутом» на пустой выдаче «Ближайших» (`NearbyEmptyCard` в `RidesRequestsChatScreens.kt`, проброс `onRouteWatch(from,to)` через `MapScreen`→`HomeScreen`). `ApiClient`: `createRouteWatch`/`getRouteWatches`/`deleteRouteWatch` + `RouteWatchDto`. Двуязычие через `appText` (черновой ба → `docs/tasks.md` «Переводы — F13»).

## Что ещё фейковое (только UI)
Платежи (донат/Boost — мок), проверка водителя, реальное распознавание голоса (имитация), звонок оператору, «скрытый номер»/код посадки (UI). Реклама — локальные счётчики показов/кликов. **SMS** — код провайдера готов (`sms.ru`), но без ключа работает мок (код в логе сервера). Полные чат-треды списком диалогов — `ChatScreen` всё ещё показывает мок-карточки (реальный тред — в `ActiveTripScreen`).

**Карта — настоящая и работает** (Яндекс MapKit, `YandexMapCard`). 2026-06-22 ключ **активировался** — реальные тайлы Башкортостана грузятся (Баймаҡ/Сибай/Тубинский и т.д.), на карте видны маркеры-ценники поездок. Маршрут пока прямая линия (настоящий роутинг по дорогам — позже, нужен `-full` SDK + Router). Тап по маркеру → нижняя карточка поездки (`ModalBottomSheet`); добавлен фикс перехвата касаний для карты внутри прокручиваемого списка.

## Анимации (добавлено 2026-06-22)
- Переходы экранов: `AnimatedContent` вокруг `when(screen)` в `YuldashApp` и `when(selectedTab)` в `HomeScreen`.
- Нижнее меню `YuldashBottomItem`: анимация пилюли и масштаба иконки (`animateColorAsState`, `animateFloatAsState`).
- Хелпер `Modifier.bounceClick(onClick)` (рядом с `YuldashBottomItem`) — лёгкое сжатие при нажатии; применён к карточкам/строкам/SOS вместо обычного `clickable`.
- Хелпер `Modifier.appearIn(index)` (рядом с `bounceClick`) — карточки каскадом всплывают снизу при появлении; применён к спискам всех 5 вкладок (Карта, Поездки, Заявка, Чат, Профиль) через `Box(Modifier.appearIn(i)) { ... }`.

## Дизайн-спринт (добавлено 2026-06-23)
- **Тёмная тема (Material 3).** Палитра `Canon*` адаптивна: каждый цвет — `@Composable`-геттер `if (isSystemInDarkTheme()) тёмный else светлый` (вверху MainActivity.kt). 410 использований не тронуты. `Theme.kt` — `darkColorScheme`. Карточки: `Color.White` → адаптивный `CanonSurface`. Подводный камень: `@Composable`-геттер нельзя вне composable (Canvas/DrawScope, не-composable хелперы) — см. lessons.
- **Сплэш + брендовое интро (2026-06-29).** Раньше: `Screen.Splash` + `SplashScreen()` заново анимировал лого поверх системного сплэша → **двойное лого** (жалоба Александра). Теперь: `Screen.Splash` = тонкий зелёный «мост» (продолжает системный сплэш, БЕЗ повторной анимации лого). На ПЕРВОМ запуске → **`Screen.Intro`** (`IntroScreen.kt`): 3 акта ~3.25с, тап = скип — лого мягко проявляется (без scale-pop) → слово-смысл **«Попутчик»** → морф (crossfade+scale) в бренд **«Юлдаш»** → слоган **RU→BA** («Поездки между своими»→«Үҙебеҙҙекеләр араһында юллашыу») → онбординг. Уважает reduced-motion (анимации выкл. → сразу финальный кадр). На повторных запусках интро НЕ показывается (сразу Home/Login). `splashTarget`: первый запуск (`!onboarding_completed`) → `Intro` (раньше debug-обхода). Старый `SplashScreen()` удалён. Системный сплэш Android 12 — `styles.xml` (`windowSplashScreenBackground`=`@color/yuldash_splash`). Шрифт — Montserrat (`res/font/`, сабсет Cyrillic+Latin, башкирский проверен). Бренд «Юлдаш» приходит целым (tracking-in + золотой sheen-блик) + золотая черта.
- **Премиум-герой интро (`IntroHero.kt`, 2026-06-29).** `BrandHero` — поверх лого: **курай-соцветие** (герб РБ: центр-втулка + **7 шаров на стеблях = 7 родов**, веер в верхней полусфере) распускается за лого через `scale(bloom)` — «вырастает» из-за пина (центр скрыт за лого); цвет золотой `#F0C840`. + **дорога-маршрут** чертится снизу к пину (`PathMeasure` trim, белая пунктирная осевая), по её голове едет **нав-стрелка** — курсор навигатора («как машина на карте», стиль Яндекс): двухцветный сложенный наконечник, ориентирован по касательной маршрута (`atan2` направления). Дорога короткая (стрелка — главное, дорога = её след), после прихода к пину гаснет (`roadFade` при `showMeaning`). Референсы дал Александр (скрин курай-герба + скрин нав-стрелки). **Перф-урок (важно):** курай/дорогу рисует ОТДЕЛЬНЫЙ дешёвый `Canvas` (только вектор), а лого — эффективный `Image`-композабл с `graphicsLayer` (НЕ `drawImage(PNG)` в Canvas, НЕ непрерывная анимация всего Canvas) — иначе тяжёлый Canvas забивает главный поток, а на нём же корутины таймлайна → таймлайн стынет (ловили «Skipped 122-140 frames», лого висло). Курай/стрелка завязаны на `bloom`/`road` (после анимации значения статичны → Compose перестаёт перерисовывать). Остаточный старт-джанк на эмуляторе — холодный старт (софт-GPU + загрузка шрифтов + сетевая инициализация app), на реальном устройстве кратно меньше; чистый кадр для проверки ловили временной задержкой таймлайна (`delay`, потом откат).
- **Фон-пейзаж интро (`drawable/splash_landscape.xml`, обогащён 2026-06-29).** Векторный пейзаж Башкортостана под лого/текстом интро (грузится `Image` в `IntroScreen`, проявляется alpha+push-in). Был плоский (горы-треугольники, пятно воды) → стал глубоким и «дорогим»: дальний хребет в дымке (аэро-перспектива) + левая гора для баланса + **рёбра-контуры** на пиках (светлая/тёмная грани), широкий яркий **изгиб реки** ловит свет неба, плотный **сосновый лес** взбегает по склону холма (два тона = глубина) + малая роща слева, **дымка-туман** у подножия (атмосфера), **северное сияние** — мягкие занавеси по краям неба (4 path, lin-градиент, `fillAlpha`×альфа-стопов, не спорят с лого), доп. контуры рельефа холмов, **звёзды**, **курай** рамкой в обоих нижних углах. Палитра/золотая дорога/курай-стиль прежние. ⚠️ VectorDrawable без `<circle>` — звёзды/соцветия = двухдуговые path (см. lessons). Делалось через SVG-превью в браузере (3+ итерации) → перенос в вектор → проверка реального рендера на эмуляторе (вектор ≠ SVG 1:1).
- **Онбординг.** `OnboardingHeroCard(slide, pageOffset: () -> Float)` — параллакс героя на свайпе (deferred read `currentPageOffsetFraction`). `OnboardingDots` — анимированные ширина+цвет активной точки.
- **Иконка.** `drawable/ic_launcher_bg.xml` — мягкий радиальный мятный градиент вместо плоского белого; adaptive-icon background обновлён.

## Сборка и запуск

```powershell
cd C:\Users\Bayra\Yuldash\android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug --no-daemon
```

### Релиз (подписанный, для Google Play) — добавлено 2026-06-23
- Ключ подписи: `android/yuldash.jks` + `android/keystore.properties` (пароль). **ОБА в `.gitignore`, НЕ в git.** ⚠️ Беречь: потеря ключа = НЕЛЬЗЯ обновлять приложение в Google Play. Сделать бэкап в надёжное место (пароль-менеджер/облако).
- Сборка: `gradlew :app:assembleRelease :app:bundleRelease`. Артефакты: `app/build/outputs/apk/release/app-release.apk` (прямая установка), `app/build/outputs/bundle/release/app-release.aab` (загрузка в Play). Подпись: `CN=Yuldash` (проверено `apksigner verify`).
- `isMinifyEnabled=false` (надёжность > размер). Уменьшение (R8/ABI-split/strip) — позже; сейчас APK ~117 МБ из-за нативных либ MapKit (`libmaps-mobile.so`).

ADB: `C:\Users\Bayra\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Подробности запуска/эмулятора/smoke-теста — в `../CONTINUE_FOR_AI.md`.

---

## 🏗️ ДЛЯ ОБЗОРА: System Design

Полная архитектура Юлдаша (для инвесторов, новых разработчиков, planning scale-up) лежит в **[system-design.md](system-design.md)**.

Там: 
- Макро-архитектура (3-слойная: Presentation / Data / Service)
- Схема БД (20+ таблиц PostgreSQL, индексы, партиционирование)
- REST API полная спецификация (JSON примеры для всех эндпоинтов)
- WebSocket для реал-тайм чата
- Безопасность & 152-ФЗ compliance
- DevOps: текущий стек → масштабирование до 1М DAU
- Дорожная карта (Q3 2026 — Q2 2027+)

Это — ваш blueprint. MainActivity.kt детали → в [architecture.md](architecture.md), стратегия → в [system-design.md](system-design.md).
## 2026-07-01 — приватные документы водителя

`backend/app/routers/drivers.py`: `/upload/photo` сохраняет приватные документы в `private/docs` с префиксом `user_id_...`; `/secure/docs/{name}` отдаёт файл только админу или владельцу; `/driver/verify` принимает только собственные загруженные файлы. Это закрывает IDOR-сценарий с подстановкой чужого `license_url`/`car_photo_url`.

## 2026-07-01 — тесты, lint и coverage

- Backend coverage измеряется через `coverage run --source=app -m pytest tests`; последний факт: **91%** по `backend/app`.
- Android debug coverage включён в `android/app/build.gradle.kts` для unit и instrumented tests; отчёты: `android/app/build/reports/coverage/test/debug/` и `android/app/build/reports/coverage/androidTest/debug/connected/`. Последний Android unit line coverage: **4.63%** (`546 / 11794`).
- Web lint после Next 16 работает через ESLint CLI: `web/eslint.config.mjs` + `npm run lint`.

## 2026-07-01 — Android unit-тесты бизнес-логики

- `CoreLogicTest.kt`: appText, CTR рекламы, дата поездки, километры, backend-категория → UI, live-feed карты, фильтры рекламы.
- `YuldashViewModelTest.kt`: survival-состояние навигации/языка/вкладки и начальные коллекции.
- Последний факт: **21 unit-тест**, Android unit coverage **4.63%**. Последний объединённый Android line coverage после instrumented run пока не пересчитан.

## 2026-07-02 — чат pending-брони и nullable voice URL

- `BookingActiveTripScreen.kt`: `messages` хранится с ключом `remember(bookingId)`, чтобы история одной брони не протекала в другую; pending-бронь может открыть чат, но live-действия (`Код посадки`, статусы, отмена, share) остаются скрыты до подходящего статуса.
- `data/ApiClient.kt`: `getMessages()` использует `parseMessageDto(...)`; `voice_url: null` / отсутствующее поле / пустая строка нормализуются в `null` через `normalizeOptionalJsonString(...)`. Это убирает ложное отображение текстового сообщения как `Голосовое`.
- `CoreLogicTest.kt`: добавлена регрессия `normalizeOptionalJsonString_treatsJsonNullVoiceUrlAsNoVoiceMessage`.
- Последняя проверка Android: `:app:testDebugUnitTest :app:assembleDebug :app:installDebug` → `BUILD SUCCESSFUL`; ручной эмуляторный сценарий `Чат → Айгуль / Темясово → Уфа` показывает `Codex_test_message`, без `Голосовое`.
## 2026-07-01 — backend test coverage after audit pass

- `backend/app/routers/content.py` removed as dead duplicate router. Source verification: it was not imported in `backend/app/routers/__init__.py` and not included in `all_routers`; product endpoints are served by `discovery.py`/`ads.py`.
- New regression suites:
  - `test_payments_edges.py`
  - `test_payment_provider.py`
  - `test_requests_edges.py`
  - `test_safety_edges.py`
  - `test_driver_check.py`
  - `test_location_edges.py`
  - `test_rides_edges.py`
  - `test_chat_edges.py`
  - `test_services_edges.py`
  - `test_ads_edges.py`
  - `test_auth_edges.py`
- Latest backend verification: `pytest tests -q` → `148 passed, 1 skipped`; coverage for `backend/app` → `91%` (`3186` statements, `300` missed).
- High-covered active modules after this pass: `routers/ads.py` 99%, `routers/auth.py` 98%, `payments.py` 100%, `routers/payments.py` 95%, `routers/requests.py` 95%, `routers/safety.py` 99%, `driver_check.py` 96%, `routers/rides.py` 93%.

## 2026-07-06 — 🚕 Домен «Быстрый заказ» (такси-режим, Фаза 2, БЭКЕНД) — ветка `feat/instant-order`

Флагман Фазы 2. **Отдельный поток** от плановых поездок (Ride/Booking — не тронут). Полный статус, конфиг и «что осталось» — в [instant-order-backend.md](instant-order-backend.md).

**Новые файлы:**
- `backend/app/instant_service.py` — сервисный слой: presence (Redis GEO), тариф (сервер считает сам), machine состояний + matcher.
- `backend/app/routers/instant.py` — эндпоинты (`/instant/*`), зарегистрирован в `routers/__init__.py`.
- `backend/alembic/versions/p2_instant_order.py` — миграция (rev `p2_instant_order`, down `0004`), идемпотентна: на свежей БД create_all уже создал таблицы → no-op; на проде создаёт `tariff` + `instantorder` c индексами.
- `backend/tests/test_instant.py` — 25 тестов (тариф/presence/matcher/машина/гонка/таймаут/отмены/приватность).

**Модели (`models.py`):** `Tariff(zone, category, base, per_km, per_min, min_price, k, active)`; `InstantOrder(...+ таймстампы переходов)`; `InstantOrderStatus` (created→searching→offered→accepted→arriving→onboard→done, терминальные cancelled/expired). **Presence — только Redis** (эфемерно, в БД нет).

**Presence:** `POST /instant/presence` (водитель «на линии») → `GEOADD presence` + `SET presence:hb:{id} EX 60`. Переиспользует `services._cache_client()`. Без Redis — graceful (заказ не находит водителей, не падает).

**Тариф:** `POST /instant/estimate` — сервер считает `max(min_price, base + per_km·dist + per_min·eta)·k`, округл. до 10 ₽, `dist = haversine × road_k`. **Клиенту не верит** (в схеме нет поля цены). Зоны город/межгород по порогу дистанции. Сид тарифов — в lifespan (`seed_tariffs`, всегда, не под `seed_demo`). **Стартовые цены (₽, сильно ниже конкурентов, правятся в БД):** город base=70/per_km=11/per_min=3/min=100; межгород base=80/per_km=9/per_min=2/min=150; k=1.0.

**Заказ + matcher:** `POST /instant/orders` считает цену → matcher `GEOSEARCH` c расширением 3→7→15 км → фильтр (online/verified/не занят/не в блоке) → скоринг (подача/рейтинг) → оффер top-1 через `send_push` (data-payload) с таймаутом 20с. Переходы: `/accept /decline /arrived /onboard /done /cancel` — под row-lock (`with_for_update`) + атомарный условный UPDATE (гонка двух accept → второму **409**, корректно и на SQLite). Ленивый таймаут оффера (reconcile при чтении) — работает **без arq-воркера**. Приватность: телефоны сторон раскрываются ТОЛЬКО после accept; координаты не логируем.

**Тесты:** backend `pytest -q` → **206 passed, 1 skipped**. Alembic `upgrade head` на чистой БД проходит + идемпотентен (оба пути проверены).
- Remaining lower areas are mostly integration-heavy/infrastructure: `services.py`, `db.py`, `middleware.py`, and WebSocket internals in `routers/chat.py`.

## 2026-07-06 — 💰 Домен «Деньги v1» (ledger + комиссия + оплата done + сверка, Фаза 3, БЭКЕНД) — ветка `feat/payments-ledger`

Реализация D3 (v1, БЕЗ hold/capture — это v2). Пассажир платит за **завершённую** поездку картой/СБП через СУЩЕСТВУЮЩУЮ ЮKassa-инфру (`payments.py` create/fetch/webhook); водителю начисляется через **append-only ledger** (кошелёк). Полный статус — в [payments-ledger-backend.md](payments-ledger-backend.md).

**Новые файлы:**
- `backend/app/ledger.py` — деньги: `fee_kop_for` (комиссия, целые копейки, ROUND_HALF_UP), `driver_balance` (= SUM), `settle_instant_order`/`settle_booking` (идемпотентно, под row-lock), `reconcile` (сверка за период).
- `backend/app/routers/wallet.py` — эндпоинты оплаты/кошелька/сверки, зарегистрирован в `routers/__init__.py`.
- `backend/alembic/versions/p3_ledger.py` — миграция (rev `p3_ledger`, down `p2_instant_order`), идемпотентна: свежая БД create_all → no-op; прод создаёт `ledgerentry` c 5 индексами и добавляет колонки в `payment`/`instantorder`/`booking`.
- `backend/tests/test_ledger.py` — 19 тестов (комиссия/начисление/только-done/наличные/идемпотентность webhook/append-only/сверка/IDOR/бронь).

**Модели (`models.py`):** `LedgerEntry(id, driver_id, order_id NULL, booking_id NULL, kind[earn|fee|payout|adj], amount_kop, created_at, note)` — **append-only, баланс = SUM(amount_kop)**, историю НЕ редактируем (правка → запись `adj`). Расширены: `Payment(+order_id,+booking_id,+method, purpose=ride|booking)`, `InstantOrder(+paid,+payment_method)`, `Booking(+paid,+payment_method)`. Деньги — только int-копейки, без float.

**Комиссия:** `service_fee_percent` в config (дефолт **8%** — втрое ниже Яндекса ~24–30%; правится без пересборки, финальный процент утверждает Александр). Успешная безналичная оплата → в ledger две записи: `earn` (+вся сумма водителю) и `fee` (−комиссия). Баланс водителя за поездку = earn − fee.

**«Поддержать Юлдаш» (добровольная поддержка):** `POST /support/donate {amount_kop}` (`routers/payments.py`) — `Payment(purpose="support")`, доход платформы (НЕ водителю): `_activate_payment` только помечает succeeded, **ledger не трогает**. Через ту же ЮKassa-инфру (карта/СБП), без ключей — СБП-фолбэк по номеру (подтверждает админ). Границы 10–5000 ₽, идемпотентно по деньгам (повторный webhook → no-op). UI — `SupportScreen` (`SupportBoostScreen.kt`, пресеты 20/50/100 ₽ + своя сумма, `ApiClient.supportDonate`), вход: профиль + мягкое дисмиссируемое предложение после done-поездки (`BookingActiveTripScreen`, колбэк `onSupport`).

**Оплата после done:** `POST /instant/orders/{id}/pay` и `POST /bookings/{id}/pay` (метод `cash|card|sbp`). Только владелец-пассажир (анти-IDOR), только статус **done**. Безнал → ЮKassa (mock/dev → succeeded сразу; прод → confirmation_url, начисление по webhook). **Наличные** → заказ помечается `paid`, но ledger НЕ двигаем (деньги мимо нас). **Идемпотентность:** повторный webhook не задваивает — `_activate_payment` фиксирует succeeded, затем `settle_*` под row-lock гейтит по флагу `paid` (второй раз → «already»).

**Кошелёк/сверка:** `GET /wallet/balance` и `GET /wallet/ledger` — только СВОИ записи (анти-IDOR). `GET /admin/ledger/reconcile?days=N` (админ) — сверка `SUM(earn)` ↔ `SUM(успешных безналичных Payment)`; `diff≠0` → расхождение (алерт вешает Александр). Наличные в сверку не входят.

**Тесты:** backend `pytest -q` → **225 passed, 1 skipped** (+19 денежных). Alembic `upgrade head` — оба пути (baseline no-op + прод create-table с 5 индексами), идемпотентно.

**Что НЕ входит (v2/за Александром):** hold→capture («безопасная сделка»), выплаты водителям (payout API), UI экрана оплаты, ЮKassa-чеки 54-ФЗ для поездок, финальный процент комиссии + оферта (юр.).

## 2026-07-09 — 🧾 Домен «Долг по комиссии за такси» (Модель А «на доверии», Фаза 3) — ветка `feat/driver-debt`

**Суть:** за завершённый ТАКСИ-заказ (instant) водитель получает деньги напрямую (нал/прямой СБП), а комиссию 8% ДОЛЖЕН платформе. Раз в неделю переводит долг Александру по СБП → «Я оплатил» → админ подтверждает. Не оплатил в срок → режим ТАКСИ блокируется. **ПОПУТКА (плановые Ride/Booking) этим НЕ блокируется** — отдельный поток.

Новые файлы:
- `backend/app/debt.py` — логика: `order_commission_kop` (8% с цены, int-копейки), `accrue_for_order` (начисление в done, идемпотентно по order_id), `taxi_block_reason` (просрочка / сумма unpaid > порога), `debt_summary`, `declare_paid`, `admin_confirm`, `admin_reject`.
- `backend/app/routers/debt.py` — эндпоинты, зарегистрирован в `routers/__init__.py`.
- `backend/alembic/versions/p3_debt.py` — миграция (rev `p3_debt`, down `p3_ledger`), идемпотентна (baseline create_all no-op / прод create_table `commissiondebt` c 5 индексами).
- `backend/tests/test_debt.py` — **16 тестов** (начисление/идемпотентность/блок presence·offer·accept/ПОПУТКА-не-блокируется/цикл оплаты→confirm→разблок/reject→снова-блок/анти-IDOR/границы).

**Модель (`models.py`):** `CommissionDebt(id, driver_id, order_id NULL, amount_kop, week, status[unpaid|pending|paid], created_at, due_at, paid_declared_at, confirmed_at)` — одна строка = комиссия одного заказа. `DebtStatus` enum. Деньги — int-копейки.

**Начисление:** в `/instant/orders/{id}/done` (router) после успешного перехода → `debt.accrue_for_order` (гейт по order_id → повторный done не задваивает; нулевая комиссия долг не создаёт). База = `price_final || price_estimate` (₽ → копейки), процент = `service_fee_percent` (8%).

**Блок такси (guard `_guard_taxi_not_blocked`):** `/instant/presence`, `/instant/driver/offer` (возвращает `offer:None`), `/instant/orders/{id}/accept` → 403 «Оплати долг сервису, чтобы возить такси», если есть просроченный unpaid ИЛИ `SUM(unpaid) > debt_block_threshold_kop`. `pending` (заявил оплату) НЕ блокирует — работаем «на доверии» (разблок сразу после «Я оплатил», окончательно — после админ-confirm). `/rides` и брони guard НЕ трогает.

**Эндпоинты:** водитель — `GET /driver/debt` (сумма/срок/реквизиты СБП/блок, по своему токену), `POST /driver/debt/paid` (unpaid→pending, Telegram админу). Админ — `GET /admin/debts` (pending, группировка по водителю, representative `debt_id`), `POST /admin/debts/{id}/confirm` (весь pending водителя→paid, push «Долг подтверждён»), `/reject` (→unpaid). Все `/admin/*` — только роль admin.

**Config (`config.py`, «уточнит Александр»):** `owner_sbp_phone`, `owner_sbp_name` (реквизиты СБП — из `.env`, НЕ хардкод), `debt_due_days=7`, `debt_block_threshold_kop=100000` (1000 ₽).

**UI:** `DriverDebtBanner` (`ProfileScreen.kt`, в `DriverCabinetContent`) — «К оплате X ₽» + реквизиты СБП + «Я оплатил»; заблокированное такси красным + «попутка работает как обычно». Админ — секция «Долги за такси» в `AdminPaymentRequestsScreen` (`SecondaryScreens.kt`): Подтвердить/Отклонить. DTO/методы — `ApiClient.kt` (`getDriverDebt`, `declareDebtPaid`, `getAdminDebts`, `confirmDebt`, `rejectDebt`).

**Тесты:** backend `pytest -q` → **249 passed, 1 skipped** (+16 долговых). Android-сборку прогнать на машине Александра (в Linux-песочнице нет Android SDK). Вливать ПОСЛЕ #34/#36/#42.

## 2026-07-10 — 🛡 Домен «Качество: жалобы + лестница наказаний» (волна 2, батч B5, §9 бизнес-плана) — ветка `feat/quality-ladder`

**Принципы (утвердил Александр):** честно/прозрачно/анонимно; человек в контуре (разбор у админа, право объяснения); попутка мягче такси (все паузы — ТОЛЬКО такси); SOS/безопасность — железно. **Цель жалобы НИКОГДА не видит автора**: `reporter_id` отдаётся только в `/admin/reports`; пуш цели — категория без имени/деталей.

**Модели (`models.py`):** `Report` + `category` (перечень из 11: rude/kicked_out/dangerous_driving/price_fraud/dirty_car/late/safety_threat/no_show/damage/unpaid/other; default other — совместимость), `order_id NULL`/`booking_id NULL` (привязка к поездке), `status` (new|reviewing|resolved|rejected), `resolution NULL`, `resolved_at`. `Rating.booking_id` → NULLABLE + `order_id NULL` (взаимные оценки instant-заказов; одна оценка на (rater, order) — повтор обновляет). `DriverProfile` + `taxi_paused_until`/`taxi_pause_reason` (reports|review|admin), `low_rating_advice_at` (дедуп 🟡-совета).

**Ядро — `app/quality.py`:** категории+лейблы RU/BA, `SEVERE_CATEGORIES` (safety_threat/kicked_out/dangerous_driving), `PASSENGER_STRIKE_CATEGORIES` (no_show/unpaid/damage), `pause_taxi` (пауза только удлиняется)/`unpause_taxi`/`guard_taxi_quality`, `escalate_severe` (Telegram админу + пауза до разбора), `apply_ladder_after_resolve`, `passenger_pause_until` (страйки B3 + resolved-жалобы одним счётчиком), `maybe_low_rating_advice`, `restrictions_payload`.

**Лестница (§9, все цифры — конфиг):** 🟡 `rating < quality_advice_rating(4.8)` → мягкий пуш-совет, дедуп `quality_advice_interval_days(7)` (хук в обоих rate-эндпоинтах). 🟠 `rating < matcher_low_rating(4.6)` → `_score -= matcher_penalty_low_rating(1.0)` в matcher (`instant_service._score`) — реже получает заказы, не блок. 🔴 ≥`quality_pause_reports(3)` resolved-жалоб за `quality_window_days(30)` → авто-пауза такси `quality_pause_hours(72)` + пуш (в `admin_resolve_report`). ⛔ тяжёлая категория при создании жалобы → немедленный `notify_admin_telegram` + пауза такси «до разбора» (reason=review, until не показываем как дату); resolve с `keep_pause` снимает/переводит в таймерную, reject снимает (если нет других открытых тяжёлых). Гейт — как долговой/отдыха: `_guard_taxi_driver` (presence/accept) + `driver_offer` (`offer:None`); активный заказ доводится; ПОПУТКА работает всегда.

**Жалобы:** `POST /reports` — category + `order_id`/`booking_id` (сервер проверяет участие, цель = вторая сторона; несовпадение переданного target → 400; self → 400); старое тело `{target_user_id, reason}` совместимо (category=other). Ответ автору — `ReportCreatedOut` без reporter-полей. Пуш цели «Поступила жалоба: <категория>» — анонимный, двуязычный. Свободный `reason` (детали) остаётся.

**Оценки заказов:** `POST /instant/orders/{id}/rate {stars}` — обе стороны, только после done (иначе 409), не участник → 403; агрегат `user_rating` считает ВСЕ Rating по `ratee_id` (попутка + заказы) → `DriverProfile.rating`; в ответе только агрегат (rater не раскрывается).

**Пассажирские страйки:** resolved-жалобы категорий no_show/unpaid/damage = страйк пассажиру; общий счётчик с B3 (`order_strike_times` + отчёты, те же `strike_limit/strike_window_days/strike_pause_hours`) → гейт `POST /instant/orders` (403, тёплый текст B3).

**Право объяснения:** `GET /me/restrictions` → `{items:[{kind: taxi_pause|orders_pause, reason, category(+RU/BA), until (null=«до разбора»), title/note RU/BA}], support RU/BA}` — без автора.

**Админ:** `GET /admin/reports` (+фильтры `?status=&category=`, новые поля category/status/resolution/order_id/booking_id/target_user_id; старые поля не тронуты — совместимость), `POST /admin/reports/{id}/resolve {resolution, keep_pause}`, `/reject`, `POST /admin/quality/{user_id}/pause {hours}`, `/unpause`. Всё — только роль admin.

**Config:** `quality_advice_rating=4.8`, `quality_advice_interval_days=7`, `matcher_low_rating=4.6`, `matcher_penalty_low_rating=1.0`, `quality_pause_reports=3`, `quality_window_days=30`, `quality_pause_hours=72`.

**UI (Android):** `SecondaryScreens.kt` — `ReportCategoryDialog` (категории с иконками, двуязычно, 48dp, «жалоба анонимна», «Другое» требует текста) + перечни `reportCategoriesDriver/Passenger/All`; `ReportScreen` использует диалог; `AdminReportsContent` — чип категории (тяжёлая красным), статус, Подтвердить/Отклонить (+«оставить паузу» для тяжёлой), пауза 72ч/снять (совместимость со старыми вызовами — новые параметры с дефолтами). `InstantOrderScreen.kt` — `InstantRateAndReport` в done-карточках ОБЕИХ сторон (звёзды 40dp с анимацией, «оценка анонимна», «Пожаловаться» → диалог категорий с привязкой order_id); `InstantFinalCard` получил слот `extra` + прокрутку. `ProfileScreen.kt` — `RestrictionsCard` («Мои ограничения»: что/категория/до когда/«попутка работает» + диалог «Написать в поддержку» → requestCallback) в кабинетах водителя И пассажира (виден только при непустом `/me/restrictions`). `ApiClient.kt`: `reportUser(+category/orderId/bookingId)`, `rateInstantOrder`, `getMyRestrictions`, `adminResolveReport/adminRejectReport/adminQualityPause/adminQualityUnpause`, DTO `RestrictionDto/RestrictionsDto`, `AdminReportDto` + category/status/resolution/targetUserId.

**Миграция:** `alembic/versions/w2_quality.py` (down=`w2_work_hours`), идемпотентна оба пути (проверено up→down→up→no-op на SQLite): +6 колонок `report`, `rating.order_id` + booking_id→NULLABLE (batch), +3 колонки `driverprofile`, индексы; FK-колонки на SQLite без констрейнта (ALTER ADD CONSTRAINT там не работает), на Postgres — честный FK.

**Тесты:** `pytest -q` → **368 passed, 1 skipped** (+19 в `test_quality.py`: категории/привязка/участие/несовпадение цели/совместимость старого тела/422 на мусорную категорию; анонимность (ответ автору, /me/restrictions цели, admin-only); оценки заказов (агрегат, unique-повтор, guard'ы); 🟡 дедуп совета, 🟠 штраф в score, 🔴 3 resolved → пауза+гейт+попутка работает, ⛔ тяжёлая → Telegram+пауза, resolve keep/release, reject снимает; пассажирские страйки за no_show-жалобы; админ-права/IDOR). Вливать ПОСЛЕ #51 (feat/work-hours).

## 2026-07-10 — 🚀 Домен «Запуск: ранний доступ + „Скоро в городе"» (волна 2, батч B6, §11 бизнес-плана) — ветка `feat/launch-tools`

**Суть:** у Александра сильный медиа-охват → трафик пускаем волнами через лист ожидания. Попутка — на всю РБ сразу, такси — по городам (per-city флаги из B1). Водителей набираем первыми («0% комиссии первые 3 месяца»). СМС на этом этапе НЕ шлём — invite только помечает волну, рассылку Александр делает сам.

**Модель (`models.py`):** `WaitlistEntry` — `phone` (unique, index), `city NULL`, `role` (passenger|driver), `created_at`, `invited_at NULL`. Телефоны отдаются ТОЛЬКО админу, в логи не пишутся (152-ФЗ).

**Роутер `app/routers/waitlist.py`:** `POST /waitlist {phone, city?, role}` — ПУБЛИЧНЫЙ (без auth: лендинг + приложение до входа), телефон нормализуется (пробелы/дефисы/скобки) и валидируется (`^\+?\d{10,15}$`, как family.py); дедуп по номеру — повтор обновляет city/role (город только если передан), не дублирует. Строгий rate-limit: `/waitlist` добавлен в `_STRICT_PREFIXES` (`middleware.py`). Админ: `GET /admin/waitlist?city=&role=&invited=` (счётчики total/invited/by_city[отсортирован]/by_role — по всей базе; items — по фильтрам), `GET /admin/waitlist.csv` (те же фильтры, attachment), `POST /admin/waitlist/invite {ids}` (проставить invited_at; уже позванных не перетирает — сохраняется номер волны; потолок 500 id).

**Availability+город:** `app/taxi.py availability()` теперь возвращает аддитивное поле `city` (ближайший из CITY_COORDS) — для предзаполнения города в форме листа. Старые клиенты поле игнорируют.

**UI (Android):** `InstantOrderScreen.kt` — `TaxiComingSoonCard` дополнен формой раннего доступа: телефон (предзаполнен из `/me`, tg-плейсхолдер не подставляется), город (из availability), чипы «Я пассажир»/«Я водитель», успех «Ты в списке! 🎉» (AnimatedVisibility); CTA-блок «Стань первым таксистом города 🚖» (0% комиссии 3 мес) переключает роль, при `reason=city_off` — кнопка «Пройти проверку таксиста заранее» → `Screen.TaxiOnboarding` (колбэк прокинут через HomeScreen→PassengerModeHome→InstantOrderScreen и из `Screen.InstantOrder`). Админ: `AdminWaitlistScreen.kt` (`Screen.AdminWaitlist`, вход из кабинета админа «Лист ожидания» рядом с «Таксисты») — счётчики (всего/ждут/позваны, пассажиры/водители, чипы городов), фильтры, чекбоксы + «Пометить волну (N)», все состояния. `ApiClient.kt`: `joinWaitlist` (auth=false), `getAdminWaitlist`, `adminWaitlistInvite`, DTO `WaitlistEntryDto`/`AdminWaitlistDto`, `TaxiAvailabilityDto.city`.

**Web (лендинг `web/`):** `components/EarlyAccess.tsx` — секция «Ранний доступ» (после CoverageMap): табы пассажир/водитель, телефон+город, POST на относительный `/waitlist` (тот же домен, как /landing-stats), успех «Ты в списке!», двуязычно через `dict` (`ea_*` в `lang.tsx`), цели Метрики `waitlist_passenger/driver`.

**Миграция:** `alembic/versions/w2_waitlist.py` (down=`w2_quality`), идемпотентна оба пути (проверено up→down→up на SQLite): create_table `waitlistentry` + 3 индекса, только если нет; downgrade дропает таблицу.

**Тесты:** `pytest -q` → **379 passed, 1 skipped** (+11 в `test_waitlist.py`: публичность без токена, валидация/нормализация телефона, дедуп-обновление и «город не затирается», rate-limit (нормальная подача проходит, спам 429), админ-счётчики/фильтры/CSV/invite (повтор не перетирает метку), 403/401 для не-админа и анонима, city в availability). `npm run build` (web) зелёный. Вливать ПОСЛЕ #52 (feat/quality-ladder) — последний батч волны 2.

## 2026-07-10 — 🌙 Домен «8-часовой лимит + отдых водителя» (волна 2, батч B4, §8 бизнес-плана) — ветка `feat/work-hours`

**Суть:** безопасность = продукт. 8 часов на линии ТАКСИ за местный день → отдых до утра. Активный заказ не рубим, попутка вне блока не ограничена вообще. Все цифры — конфиг: `taxi_shift_limit_hours=8`, `rest_hours=8`, `rest_unlock_hour=6`, `local_tz_offset_hours=5` (Уфа UTC+5), `workday_step_cap_sec=60`.

**Учёт (`app/workday.py` + модель `TaxiWorkDay`, `models.py`):** строка на (driver_id, местный день) — unique. На каждом `POST /instant/presence` `record_heartbeat` прибавляет интервал от прошлого пинга с кэпом ≤`workday_step_cap_sec` (редкие heartbeat не накручивают; первый пинг дня времени не даёт). Считается ТОЛЬКО такси-время: попутка presence не шлёт. Граница дня — местная полночь (UTC+`local_tz_offset_hours`); БД, как везде, наивный UTC (`timeutil`).

**Лимит и гейт:** `seconds_online ≥ 8ч` → `limit_reached_at` (+пуш «Хорошо поработал 👏», один раз). `guard_taxi_rested` добавлен в `_guard_taxi_driver` (`routers/instant.py`) — в стиле долгового гейта держит `/instant/presence` (403), `/instant/driver/offer` (`offer:None`), `/accept` (403) с тёплым двуязычным текстом. Переходы активного заказа (`arrived/onboard/done`) через гейт НЕ ходят — начатую поездку доводим.

**Разблокировка (`unlock_at`):** `max(следующий местный день в rest_unlock_hour(06:00); last_heartbeat_at дня лимита + rest_hours(8ч))` — покрывает все три условия §8 одной точкой времени; `blocking_workday` = последний лимитный день, пока `now < unlock_at`. После разблокировки первый heartbeat заводит новый `TaxiWorkDay` с нуля.

**«Один попутчик домой»:** во время блока `POST /rides` (`guard_publish_ride`, `routers/rides.py`) пропускает ОДНУ публикацию попутки (ставит `return_ride_used`; флаг коммитится вместе с поездкой — упавшая публикация попытку не съедает), вторая → мягкий 403; отклик на заявку (`/requests/{id}/respond`, `guard_respond_request`) во время блока → 403. ВНЕ блока оба guard'а мгновенно пропускают — попутка не ограничена (rides-тесты не тронуты).

**Вежливые пуши (дедуп флагами на строке дня):** `warned_60`/`warned_15` — «остался час»/«осталось 15 минут» по одному разу за смену; `winter_push_sent` — при блоке зимней ночью (ноя–мар, 20:00–07:00 местного) один совет про тепло/заряд. Итого за период отдыха ≤2 пуша (лимит + зимний).

**Эндпоинты:** `GET /instant/workday` → `{day, seconds_online, limit_sec, remaining_sec, limit_hours, blocked, unlock_at, return_ride_used}`; `POST /instant/presence` теперь отдаёт ещё `shift_seconds_online`/`shift_remaining_sec`.

**UI (Android):** `ProfileScreen.kt`, кабинет водителя — `TaxiShiftProgressCard` («На линии 6 ч 20 мин из 8», анимированный прогресс: спокойный зелёный, в последний час — тёплый оранжевый `CanonWarn`; переопрос сводки раз в 60с пока «на линии») и `TaxiRestCard` (блок: «Ты сегодня за рулём 8 часов 🌙» + время разблокировки из `unlock_at` + карточка «Возьми одного попутчика домой» с кнопкой на создание поездки, пока `return_ride_used=false`; после — «уже опубликован 💚»). Показываются только одобренному таксисту. `ApiClient.kt`: `getTaxiWorkday()` + `TaxiWorkdayDto`.

**Миграция:** `alembic/versions/w2_work_hours.py` (down=`w2_money_rules`), идемпотентна оба пути (проверено up→down→up на SQLite): create_table `taxiworkday` (+unique driver_id+day, index driver_id).

**Тесты:** `pytest -q` → **349 passed, 1 skipped** (+15 в `test_work_hours.py`: инкремент/кэп шага, новый день — новая строка, лимит на heartbeat → гейт presence/offer/accept, активный заказ доводится до done, разблокировка «следующий день/06:00/полные 8ч отдыха» (время мокается monkeypatch `workday.utcnow`), «один попутчик домой» первая/вторая/отклик/после разблокировки, попутка вне блока без ограничений, дедуп предупреждений и зимнего совета, сводка `/instant/workday`). Вливать ПОСЛЕ #50 (feat/taxi-money-rules).

## 2026-07-10 — 💸 Домен «Деньги-тонкости» (волна 2, батч B3, §5+§6 бизнес-плана) — ветка `feat/taxi-money-rules`

Четыре части поверх `feat/geo-catalog`. Все цифры — в конфиге (`app/config.py`) или в БД (тарифы): Александр правит без пересборки. ПОПУТКА не затронута. Деньги — только целые копейки (int `*_kop`).

**1. Комиссия лесенкой 3/5/8 (`app/debt.py`):** `driver_fee_percent(session, driver_id)` — стаж = дни с ПЕРВОГО done instant-заказа водителя: ≤`fee_tier1_days`(30) → `fee_tier1_percent`(3%); ≤`fee_tier2_days`(60) → `fee_tier2_percent`(5%); дальше `service_fee_percent`(8%). Промо запуска: заявка таксиста approved до `launch_promo_until` (ISO-дата, `""`=выкл — дефолт) → `launch_promo_percent`(0%) первые `launch_promo_days`(90) от одобрения. `accrue_for_order` берёт процент лесенки; 0% → долг не создаётся.

**2. Сурж (`app/instant_service.py`):** `surge_k_for(session, lat, lng)` — спрос (`searching/created` заказы за `surge_window_min`=10 мин в радиусе `surge_radius_km`=7 км, haversine) / предложение (живые presence из Redis GEOSEARCH, знаменатель ≥1) → ступени `SURGE_STEPS`: <1→1.0; ≥1→1.1; ≥1.5→1.2; ≥2→1.3; ≥3→1.5; потолок `surge_max_k`=1.5, флаг `surge_enabled`. Без Redis → 1.0 (не падаем и не наживаемся вслепую). Формула цены: `max(min_price, (base+per_km·d+per_min·t) · Tariff.k · surge_k)` — статичный `Tariff.k` остаётся АВАРИЙНЫМ множителем (всегда, дефолт 1.0), двойного счёта нет. `estimate` отдаёт `surge_k`, `surge_note{ru,ba}` (прозрачно ДО заказа) и `options[{category,price}]` (обе цены классов одним запросом); `POST /instant/orders` фиксирует `InstantOrder.surge_k` (price_estimate уже с ним).

**3. Отмены/ожидание/страйки (Модель А — деньги НЕ двигаем, только фиксируем + страйки; решение Александра):**
- Поля `InstantOrder`: `waiting_started_at` (ставится на переходе `arrived`→`arriving` = «Я на месте»), `waiting_fee_kop` (фикс на onboard: полные минуты сверх `wait_free_minutes`=5 × `wait_fee_rub_per_min`=5 ₽), `cancel_fee_kop`, `no_show`. На done `price_final = price_estimate + waiting_fee` (сурж уже внутри estimate).
- **Семантика фаз уточнена (как Яндекс):** accepted = водитель едет к пассажиру, arriving = «машина на месте, ждёт» (эндпоинт `/arrived` = кнопка «Я на месте»), onboard = в пути. UI-лейблы обеих сторон обновлены.
- Отмена пассажиром (`cancel_order`): бесплатно если ≤`cancel_free_minutes`(3) от accepted ИЛИ водитель ещё не «на месте»; иначе `cancel_fee_kop = Tariff.base × 100`. Payload отдаёт `cancel_fee_now_kop` — UI предупреждает ДО тапа (диалог).
- No-show: водитель `POST /instant/orders/{id}/cancel {reason:"no_show"}` — только из arriving после `wait_free_minutes + no_show_extra_minutes`(3) (иначе 409); заказ cancelled + `no_show=true` + штраф-подача. Кнопка «Пассажир не вышел» появляется в UI по серверному `no_show_at`.
- Страйки: `strike_pause_until` — платная отмена пассажира ИЛИ no-show = страйк (считается запросом по InstantOrder, без новой таблицы); ≥`strike_limit`(3) за `strike_window_days`(7) → `POST /instant/orders` 403 (тёплый текст RU+BA) на `strike_pause_hours`(24) от последнего страйка. Обычная отмена водителем штрафа/страйка не даёт.

**4. Классы Эконом/Комфорт (§6):** сид `seed_tariffs` теперь идемпотентен ПО СТРОКАМ (прод досеет Комфорт сам): comfort город 90/14/4/130, межгород 100/12/3/200. `DriverProfile.car_class` (`economy|comfort`, NULL=economy): водитель заявляет в `/taxi/apply` (`car_class`), админ подтверждает/меняет в `/admin/taxi-applications/{id}/approve {car_class}` (+ поле в admin-списке). Matcher (`eligible`): comfort-заказ → только `car_class=comfort`; standard → все. `category` в `EstimateIn/OrderIn` ужат до `Literal["standard","comfort"]`.

**Payload заказа (`order_payload`) добавил:** `surge_k, waiting_started_at, waiting_fee_kop, cancel_fee_kop, no_show, wait_free_min, wait_fee_rub_per_min, no_show_at, cancel_fee_now_kop`.

**UI (Android):** `InstantOrderScreen.kt` — выбор класса (две карточки с ценами из `options`), плашка суржа ДО заказа (серверный текст RU/BA), живой таймер ожидания у ОБЕИХ сторон (`InstantWaitingRow`: «Бесплатное ожидание 3:12» → «Платное +5 ₽/мин», тикает по `waiting_started_at` + `rememberNowMs`), платная отмена с предупреждающим диалогом, кнопка «Пассажир не вышел» по таймингу `no_show_at` (+диалог), честные финальные карточки (no-show/платная отмена/бесплатно), бейдж «Комфорт» в оффере. `TaxiOnboardingScreen.kt` — выбор класса машины в заявке. `ApiClient.kt` — новые поля DTO + `car_class` в `applyTaxi`.

**Миграция:** `alembic/versions/w2_money_rules.py` (down=`w2_geo`), идемпотентна оба пути (проверено up→down→up на SQLite): +5 колонок `instantorder`, +`driverprofile.car_class`.

**Тесты:** `pytest -q` → **334 passed, 1 skipped** (+47 в `test_money_rules.py`: границы 30/60 дней и промо, ступени суржа/потолок/без Redis/фикс на заказе, окно отмены/ожидание/no-show тайминги/страйки→пауза→истечение, классы: сид/estimate/matcher/apply-approve). Обновлены 2 старых теста под новые правила (3% новичку; сид с category). Вливать ПОСЛЕ #49 (feat/geo-catalog).

## 2026-07-10 — 🗺 Домен «География РБ + соседние регионы» (волна 2, батч B2) — ветка `feat/geo-catalog`

**Суть (план §4):** единый справочник населённых пунктов `Settlement` (21 город респ. значения РБ + центры 54 районов + 18 приграничных городов соседей) + зона работы таксиста (🏙 город / 🛣 межгород / 🌍 регион) в matcher'е + автоподсказки городов и пресеты популярных маршрутов. ПОПУТКА не ломается: подсказки аддитивны, свободный ввод остаётся.

**Файлы (бэкенд):** `app/geo.py` — данные сида `SETTLEMENTS_SEED` (координаты райцентров сверены по открытым данным, точность ~0.01°), `POPULAR_ROUTES` (10 пар), `seed_settlements` (идемпотентный insert-if-missing по name_ru, зовётся из lifespan как `seed_tariffs`; правки строк в БД не затирает), `search_settlements` (префикс RU/BA без регистра; фильтр в Python — SQL `lower()` в SQLite не знает кириллицу, строк ~80), `by_exact_name`, `nearest_settlement` (≤30 км, `NEAREST_KM`). Модель `Settlement(name_ru idx, name_ba, region, kind[city|district_center|neighbor], lat, lng, active)` + поля `DriverProfile.work_zone/work_city/work_direction_id` (`models.py`). Миграция `alembic/versions/w2_geo.py` (down=`w2_taxi_gate`, идемпотентна оба пути; downgrade колонок — через `batch_alter_table`, SQLite не снимает FK-колонку простым ALTER).

**Эндпоинты:** `GET /settlements?q=&limit=10` (ПУБЛИЧНЫЙ — справочник не перс.данные, rate-limit общий) → `{items:[{id,name_ru,name_ba,region,kind,lat,lng}]}`; `GET /settlements/popular-routes` → `{routes:[{from:{…},to:{…}}]}` (`app/routers/settlements.py`). Зона: `GET/POST /instant/zone` (`routers/instant.py`) — POST только водителю с approved-заявкой таксиста (409 нет профиля / 403 не одобрен / 404 направление не найдено); `work_zone=city` чистит направление.

**geocode_city (`services.py`):** приоритет Settlement (точное имя RU/BA, без регистра) → старый `CITY_COORDS` → Яндекс-геокодер. Координаты городов, пересекающихся с `CITY_COORDS`, в сиде 1:1 те же — поведение не «уезжает»; запрос к БД в try/except (юнит-тесты без БД падают в фолбэк).

**Matcher (`instant_service.py`, `eligible`)**: зона заказа = `zone_for_km(distance_km)` (порог 40 км дороги); «город» точек А/Б = `nearest_settlement` ≤30 км. Правила: city-заказ → водители `city` этого города (алиасы RU/BA) ИЛИ `intercity/region` БЕЗ направления; intercity-заказ → `intercity/region` с направлением = город точки Б или без направления; `city`-водитель межгород не получает; `work_zone=NULL` → прежнее поведение (все старые matcher-тесты живут без правок). Город не определён (глушь, нет НП ≤30 км) → fail-open, подбор не режем.

**UI (Android):** `GeoUi.kt` — `DriverZoneSheet` (шторка «Где вожу»: 🏙 город / 🛣 межгород+направление / 🌍 регион; сохранение через `/instant/zone`, состояния сохранение/ошибка) и `DriverZoneChip` (чип текущей зоны под тумблером «Я на линии» в `DriverCabinetContent`, `ProfileScreen.kt`; при выходе на линию без зоны шторка открывается сама, мягко). Автоподсказки: `AddressSuggestField` (`AccessibilityScreens.kt`) двухэшелонный — сперва справочник `/settlements` (с 1-го символа, дебаунс 250мс, имя по языку BA/RU), ниже Яндекс-геокодер без дублей; поле общее для создания поездки И заявки, свободный ввод не тронут. Чипы популярных маршрутов — `PopularRouteChips` (`CreateRideScreen.kt`, обратно-совместимый слот `routeChips`). `ApiClient.kt`: `searchSettlements`, `getInstantZone`, `setInstantZone`, `getSettlementPopularRoutes` (имя — чтобы не столкнуться со старым `getPopularRoutes`/`PopularRouteDto`, живой статистикой маршрутов) + DTO `SettlementDto`/`InstantZoneDto`/`SettlementRouteDto`. Android-сборку прогнать на машине Александра (в Linux-песочнице нет Android SDK; баланс скобок всех правленых .kt = 0, символы/импорты сверены).

**Тесты:** backend `pytest -q` → **287 passed, 1 skipped** (+21 в `test_geo.py`: сид 21/40/18 без дублей + повторный no-op, поиск RU/BA/limit/inactive, приоритет geocode, популярные маршруты, права `/instant/zone`, матрица зон в matcher'е: свой/чужой город, направление совпало/не совпало, межгород без направления берёт всё, NULL = прежнее поведение). Миграция прогнана: upgrade → повторный upgrade → downgrade → upgrade (SQLite). Вливать ПОСЛЕ #48 (feat/taxi-gate).

## 2026-07-10 — 🚦 Домен «Гейт такси + онбординг таксиста» (580-ФЗ, волна 2, батч B1) — ветка `feat/taxi-gate`

**Суть:** такси включается только когда Александр оформил документы (580-ФЗ). Два гейта, ПОПУТКА не затрагивается вообще:
- **(a) Флаг/город:** `taxi_enabled=false` (config, по умолчанию) → такси «Скоро» ВЕЗДЕ. `true` + таблица `TaxiCity` пуста → такси везде; есть записи → только города с `enabled=true` (город юзера = ближайший из `CITY_COORDS` в радиусе `taxi_city_radius_km=30`; дальше → «город неизвестен» → выкл). RU/BA-имена города (Баймак/Баймаҡ) — алиасы одной точки.
- **(b) Онбординг таксиста:** возить такси может только водитель с approved `TaxiApplication` (ИНН самозанятого 10–12 цифр, № разрешения, фото разрешения/ОСАГО через приватный `/upload/photo`→`/secure/docs`, возраст 20+, стаж 2+). Модерация — вручную админом; после reject повторная подача разрешена (та же строка → снова pending).

**Файлы (бэкенд):** `app/taxi.py` (логика гейтов + двуязычные сообщения), `app/routers/taxi.py` (эндпоинты), модели `TaxiCity`/`TaxiApplication` в `models.py`, миграция `alembic/versions/w2_taxi_gate.py` (down=`p3_debt`, идемпотентна: baseline no-op / прод create_table, проверены оба пути).

**Эндпоинты:** `GET /instant/availability?lat&lng` (auth) → `{enabled, reason: ok|global_off|city_off, message: {ru,ba}}`. Водитель: `POST /taxi/apply` (валидация 400 понятной строкой; чужой документ → 403 анти-IDOR), `GET /taxi/application` (404 если нет). Админ: `GET /admin/taxi-applications?status=`, `POST .../{id}/approve|reject {comment}` (+двуязычный push заявителю), `GET|POST /admin/taxi-cities` (upsert по имени без дублей), `DELETE /admin/taxi-cities/{id}`.

**Гейты в `routers/instant.py`:** пассажирские ручки (`/instant/estimate`, `POST /instant/orders`) — только (a) по точке А; водительские (`/instant/presence`, `/instant/driver/offer` → `offer:None`, `/accept`) — (a) + (b) + долг (`_guard_taxi_driver`). Остальные переходы поездки не гейтятся (начатую поездку не рубим).

**UI:** `TaxiOnboardingScreen.kt` (`Screen.TaxiOnboarding`) — правила простыми словами (комиссия 3→5→8%, СБП раз в неделю, лимит 8 ч, 580-ФЗ) + форма (ИНН/разрешение/год прав/дата рождения/2 фото) + статусы pending/approved/rejected (комментарий + «Подать снова»), `AnimatedContent`. Вход: кабинет водителя — без approved-заявки вместо тумблера «Я на линии» рисуется CTA `TaxiOnboardingCta` (`ProfileScreen.kt`), presence-контроллер выключен. Пассажир: `InstantOrderScreen` перед пикером дёргает `getTaxiAvailability` → выключено → `TaxiComingSoonCard` («Такси скоро 🚕», серверный текст RU/BA, кнопка к попутке; сеть упала → фолбэк-пикер, сервер гейтит сам). Админ: `AdminTaxiScreen.kt` (`Screen.AdminTaxi`, вход из кабинета админа «Таксисты») — заявки с фильтрами/фото/Approve/Reject-комментарием + секция «Города такси» (тумблер/добавить/удалить). DTO/методы — `ApiClient.kt` (`getTaxiAvailability`, `applyTaxi`, `getMyTaxiApplication`, `adminTaxi*`).

**Тесты:** backend `pytest -q` → **266 passed, 1 skipped** (+17 в `test_taxi_gate.py`: глобальный флаг/города/алиасы, валидация, цикл pending→approve/reject→повторная подача, попутка не блокируется, анти-IDOR, админ-права, CRUD городов). В `conftest.py` тестовое окружение включает `TAXI_ENABLED=true`, водителям user_factory авто-одобряет заявку (`taxi_approved=False` — для тестов гейта). **Активация такси: Александру после документов — `TAXI_ENABLED=true` в `.env` + города в админке.** Вливать ПОСЛЕ #47 (feat/taxi2-base).

## 2026-07-11 — 🚕 Домен «Такси-полировка end-to-end» (батч B7a) — ветка `feat/taxi-polish`

Полировка такси до уровня «настоящего таксопарка»: фоновая линия, оффер как звонок, live-машина, навигатор, рейтинг пассажира. От `feat/launch-tools` (вершина стека волны 2); миграция НЕ нужна (нет новых колонок — рейтинг считается агрегатом).

**① Фоновый режим «на линии» (Android):**
- `TaxiLineService.kt` 🆕 — foreground location-сервис (образец `TripLocationService`): двуязычное постоянное уведомление «Юлдаш · Ты на линии 🚕» (канал `taxi_line`, LOW), presence-heartbeat `instantPresence` ~15с + опрос `getDriverOffer` ~5с ИЗ ФОНА. Самоглушение: presence вернул 401/403/409 (выход, долг, 8ч-лимит, пауза качества, снятый тумблер) + сторож 12ч. Тумблер «Я на линии» (`DriverCabinetScreen`, `ProfileScreen.kt`) синкает сервис ПОСЛЕ загрузки статуса (`onlineLoaded`).
- Манифест: `TaxiLineService` (`foregroundServiceType="location"`), `USE_FULL_SCREEN_INTENT`, `VIBRATE`.
- `AppPrefs.language/setLanguage` (`SecondaryScreens.kt`) — язык для мира вне Compose; `YuldashApp` пишет при смене.

**② Полноэкранный оффер при свёрнутом приложении:**
- `TaxiOfferNotifier.kt` 🆕 — канал «Заказы такси» (`taxi_offers`, HIGH: звук+вибро) + полноэкранное уведомление (`CATEGORY_CALL`, full-screen intent, `setTimeoutAfter(ttl)`); `NavSignals.openDriverCabinet` — сигнал из уведомления в Compose.
- `FcmService.kt`: data-пуш `type=instant_offer` → `TaxiOfferNotifier.show(...)`. `MainActivity.onCreate/onNewIntent` → `NavSignals` → `YuldashApp` открывает `Screen.DriverCabinet` (после сплэша), где существующий `InstantOfferOverlay`.
- Бэкенд: `send_push(..., data_only=True)` (`services.py`) — оффер идёт БЕЗ блока notification + `AndroidConfig(priority=high)`, иначе `onMessageReceived` в фоне не зовётся; title/body дублируются в data. `_push_offer` (`instant_service.py`) → data-only.

**③ Live-трек машины + «Навигатор»:**
- Бэкенд: WS `/ws/instant/{order_id}/location` (`routers/location.py`) — зеркало `/ws/trip/...`: токен первым сообщением, только участники (пассажир + НАЗНАЧЕННЫЙ водитель), только accepted/arriving/onboard, направленные ключи без self-эхо в namespace `INSTANT_LOC_BASE=1_000_000_000` (booking-трек и чат не пересекаются), перепроверка токена/статуса раз в 15 кадров. Координаты не хранятся.
- Android: `data/InstantLocationSocket.kt` 🆕 (реконнект с backoff, «Order not active» — мягкий ретрай). Водитель (`InstantDriverTripScreen`) шлёт позицию ~5с (курс из двух фиксов); пассажир (`InstantDriverEnRouteCard`) видит движущуюся нав-стрелку: `InstantRouteMap(car, carBearing)` — один placemark, двигаем geometry (без пересоздания). Кнопка «Навигатор» (`openNavigator`): до посадки → к подаче, после — к точке Б; `yandexnavi://` → `yandexmaps://` → `geo:`.

**④ Рейтинг пассажира в оффере:**
- Бэкенд: `passenger_stats` (`instant_service.py`) — анонимный ★-агрегат (`user_rating`) + поездки (done такси-заказы + done брони); в `order_payload` (только витрине водителя: `passenger_rating` null=новичок, `passenger_trips`) и в data пуша оффера. Телефон/имя до accept — по-прежнему пусто.
- Android: поля в `InstantOrderDto`; в `InstantOfferOverlay` строка «Пассажир: ★ 4.9 · 12 поездок» / «новичок 🌱».

**Тесты:** `backend/tests/test_taxi_polish.py` 🆕 — 8 шт: WS-реле водитель→пассажир, чужой/до accept/битый токен → закрытие, booking-трек цел рядом с такси-каналом; агрегат в оффере (4★ + 2 поездки, включая бронь), null-кейс новичка, приватность оффера, витрина пассажира без лишних вычислений. **Полный прогон: 387 passed, 1 skipped** (база 379 + 8).

## 2026-07-11 — 🚕 Домен «Такси-полировка: связь и контроль» (батч B7b) — ветка `feat/taxi-polish-2`

Финал такси end-to-end: чат в заказе, SOS/шаринг, пульс-панель админа, чек самозанятого. От `feat/taxi-polish` (вершина B7a). Миграция `w2_polish2` (down=`w2_waitlist`, идемпотентная, оба пути): `message.order_id` + `message.booking_id`→nullable, `tripshare.order_id` + `booking_id`→nullable, `sosevent.order_id`, `driverprofile.receipt_reminder_at`. FK-колонки на SQLite — без констрейнта (паттерн w2_quality).

**① Чат в такси-заказе (B7b-1):**
- Бэкенд (`routers/chat.py`): `Message` теперь с ровно ОДНОЙ привязкой (booking_id ИЛИ order_id). REST `GET/POST /instant/orders/{id}/messages` + WS `/ws/instant/{order_id}/chat` — зеркало booking-чата (токен первым сообщением, блокировки, пуш второй стороне через threadpool). Доступ: только участники (пассажир + НАЗНАЧЕННЫЙ водитель; кандидату с оффером — 403), писать — только accepted/arriving/onboard (`ORDER_CHAT_WRITABLE`), после done/отмены — read-only (GET ок, POST 409); до accept — 409. Namespace ключей `ConnectionManager`: `INSTANT_CHAT_KEY_BASE=1_500_000_000 + order_id` (не пересекается с booking-чатом >0, трек-каналами <0 и MAP_FEED_KEY=2e9). WS перепроверяет окно записи на каждом сообщении (заказ мог завершиться).
- Android: `data/ChatSocket.forOrder(orderId)` (тот же протокол, путь параметром), `ApiClient.getOrderMessages/sendOrderMessage`, `InstantChatScreen.kt` 🆕 (`Screen.InstantChat`) — реюз чистой ленты `ChatContent`: оптимистичная отправка (WS → REST-фолбэк, откат при сбое), дотяжка истории после реконнекта, баннеры «соединение восстанавливается» и «read-only». Кнопки «Написать» (круглая, рядом с телефоном): пассажиру в `InstantDriverEnRouteCard`, водителю в `InstantDriverTripScreen` — навигация через `NavSignals.openInstantChat` (работает и из встроенного в главную режима).

**② SOS и «Поделиться поездкой» в такси (B7b-2):**
- SOS: `SosIn.order_id` (+`SosEvent.order_id`) — только участник заказа (403 чужому); админу в Telegram добавляется строка «Такси-заказ: #id A→B (статус)». Android: `SosScreen(orderId)`, `ApiClient.sos(..., orderId)`, кнопка SOS в активном заказе у ОБЕИХ сторон (`InstantSafetyRow`, `NavSignals.openSosForOrder`; `openSos()` в `YuldashApp` чистит контекст).
- Шаринг: `POST /instant/orders/{id}/share` + `GET .../shares` (`routers/family.py`) — только пассажир, только свой контакт, дедуп (как booking-share). Близкий получает SMS сразу («едет на такси A→Б») и на переходах — `_notify_order_shares` (`instant_service.py`): onboard→«сел(а) в такси», done→«доехал(а)», отмена→«отменилась»; дедуп по `TripShare.last_status` (идемпотентные переходы дублей не шлют). Android: «Поделиться поездкой» в карточке пассажира → `InstantShareDialog` (доверенные контакты, состояния загрузка/пусто/ошибка) → `ApiClient.shareInstantTrip`.

**③ Пульс-панель админа (B7b-3):**
- `GET /admin/taxi/pulse` (`routers/taxi.py`, только админ): `drivers_online` (живой presence: Redis GEO `zrange` + heartbeat-фильтр; без Redis честно 0), `orders_active` (searching..onboard), `orders_today/done_today/cancelled_today/no_show_today` (от начала дня UTC), `avg_search_sec_today` (created→accepted, аномалии задним числом отфильтрованы), `by_city` — ближайший `Settlement` (как в availability): онлайн-водители по живым координатам, активные заказы по точке подачи. Координаты не логируются — наружу только агрегаты.
- Android: `AdminTaxiPulseScreen.kt` 🆕 (`Screen.AdminTaxiPulse`, вход из кабинета админа «Пульс такси») — плитки цифр (акцентные «на линии»/«активные»), список городов с точками-счётчиками, автообновление 30с (сбой сети при живых данных не пугает), скелетон/ошибка/пусто, Canon*.

**④ Чек самозанятого (B7b-4, напоминание — НЕ интеграция):**
- Бэкенд: `isv.maybe_receipt_reminder` — после done пуш водителю «Не забудь чек в „Мой налог" 🧾» (RU+BA), дедуп 1/сутки (`DriverProfile.receipt_reminder_at`, паттерн `low_rating_advice_at`). Зовётся из `POST /instant/orders/{id}/done`.
- Android: пункт «Чек после каждой поездки» в правилах онбординга таксиста (`TaxiOnboardingScreen`) + карточка `InstantReceiptReminder` на экране завершения у водителя.

**Тесты:** `backend/tests/test_taxi_polish2.py` 🆕 — 18 шт: чат (REST/WS, чужой 403, до accept 409, read-only после done, booking-чат цел), SOS (участники обеих сторон, чужой 403), шаринг (создание/дедуп/403/404, SMS на переходах без дублей, booking-share цел), пульс (агрегаты + динамика done/active, только админ, без Redis не падает), чек (пуш после done, дедуп в сутки, снова через сутки). **Полный прогон: 405 passed, 1 skipped** (база 387 + 18).

## 2026-07-11 — 🔗 Домен «Live-ссылка поездки для близких» (батч B7c) — ветка `feat/trip-live-link`

Пассажир жмёт «Поделиться поездкой» → близкий получает SMS со ссылкой `https://yulbash.ru/t/{токен}` → открывает В БРАУЗЕРЕ (без приложения) живую карту поездки. Работает для такси (order) и попутки (booking). От `feat/taxi-polish-2` (вершина B7b). Миграция `w2_livelink` (down=`w2_polish2`, идемпотентная): `tripshare.token` (NULL, unique) — старые строки получают токен лениво при следующем share.

**① Публичные ручки (`routers/share.py` 🆕, без auth, только по токену):**
- `GET /t/{token}` — server-rendered самодостаточная HTML-страница (inline CSS/JS, RU основной + BA подписи, мобильная, тёмная тема через `prefers-color-scheme`). Карта — **Leaflet + OpenStreetMap-тайлы** (решение: без API-ключей; Яндекс JS-API требует ключ — для одноразовой публичной странички OSM прагматичнее). Нет CDN — статусы работают без карты. Все данные страница тянет из state.json и вставляет через `textContent` (анти-XSS), в HTML user-контента нет.
- `GET /t/{token}/state.json` — `{status, phase_text{ru,ba}, from{lat,lng,text}, to{lat,lng,text}, car{lat,lng,bearing}|null, passenger_first_name, updated_at}`; страница поллит ~5с и двигает маркер машины. Фазы упрощённые: search / wait / to_pickup / onboard / finished (внутренние статусы наружу не отдаются).
- Заголовки: `Cache-Control: no-store`, `X-Robots-Tag: noindex`.

**② Позиция машины (`livepos.py` 🆕):** WS-хендлеры `location.py` при кадре ВОДИТЕЛЯ дополнительно кладут last-position в Redis (`livepos:order:{id}` / `livepos:booking:{id}`, TTL 120с; клиент — общий с presence/matcher, тесты подменяют через `isv._redis_override`). В БД координаты по-прежнему НЕ пишутся. Без Redis — `car=null`, страница показывает статусы без машины, не падает.

**③ Безопасность:** токен — `secrets.token_urlsafe(16)` (≥16 случайных байт, unique-индекс); короткий/пустой токен даже не ищется (анти-перебор). Пока поездка активна — маршрут+машина; после done/отмены — «Поездка завершена ✅» БЕЗ координат (ключей from/to/car в ответе нет). Наружу ТОЛЬКО имя пассажира (первое слово `User.name`) — ни фамилий, ни телефонов, ни внутренних id. Отзыв share — `DELETE /instant/orders/{id}/share/{share_id}` и `DELETE /bookings/{id}/share/{share_id}` 🆕 (`family.py`, только пассажир, только свой контакт) — удаляет строку → токен «сгорает» (404). Access-лог и лог 500 маскируют `/t/{token}` → `/t/***` (`middleware.py`). Координаты не логируются.

**④ SMS близкому (`family.py`):** к SMS при share (такси — было, попутка — добавлено) дописана ссылка «Следи за поездкой: {public_base_url}/t/{token}»; `public_base_url` — новый конфиг (дефолт `https://yulbash.ru`).

**⑤ Android:** `ApiClient.shareTrip/shareInstantTrip` теперь возвращают live-ссылку (`BASE/t/{token}`; на старом сервере null → прежнее поведение). `TripLiveLink.kt` 🆕 — `LiveLinkCard` (ссылка + «Скопировать» + системный share-sheet ACTION_SEND, RU+BA). Листы «Поделиться поездкой» такси (`InstantShareDialog`) и попутки (`BookingActiveTripScreen`) после выбора близкого показывают ссылку, закрытие — «Готово».

**Тесты:** `backend/tests/test_live_link.py` 🆕 — 11 шт: токен+SMS со ссылкой (такси и попутка), страница/state по валидному токену, невалидный/короткий токен 404, телефоны/фамилии/id не текут, WS-кадр водителя пишет livepos-кэш (fakeredis) и state отдаёт машину, без Redis car=null не падает, после done координат нет вообще, отзыв гасит токен (чужой/водитель 403), ленивый токен для строк до миграции. **Полный прогон: 416 passed, 1 skipped** (база 405 + 11).

**Прод:** `alembic upgrade head`; проверить, что nginx фолбэчит `/t/…` на FastAPI (как остальные неизвестные пути).

## 2026-07-11 — 🛡 Домен «Анти-фрод» (батч B8) — ветка `feat/anti-fraud`

Защита от мошенников с обеих сторон (водитель и пассажир), прагматичный v1 без ML. **Принцип: автоматика только ПОМЕЧАЕТ (флаги/сигналы/счётчики админу), жёстко банит человек.** От `feat/trip-live-link` (вершина B7c). Миграция `w2_antifraud` (down=`w2_livelink`, идемпотентная, оба пути): таблицы `deviceban`, `referralbonus`; `user.last_device_id`; `message.flag`, `message.from_admin`; `booking/instantorder.unpaid_reported + contact_then_cancel`; `booking.cancelled_at`. Ядро — `app/antifraud.py` 🆕, админ-ручки — `routers/antifraud.py` 🆕.

**① Бан устройства (обход бана новым номером).** Android шлёт стабильный `X-Device-Id` (ANDROID_ID) со ВСЕМИ запросами (`ApiClient.call/callMultipart/logout`). Логин/регистрация (`/auth/request-code`, `/auth/verify`, `/auth/tg/verify`) фиксируют `last_device_id` и режутся 403 «Аккаунт заблокирован — напиши в поддержку», если устройство в `DeviceBan`. Админ: `POST /admin/bans/device {device_id|user_id, reason}` (по user_id баним его последнее устройство — «блокируешь юзера → баним и устройство»), `DELETE /admin/bans/device/{device_id}`, `GET /admin/bans`. Старый клиент без заголовка не наказывается. device_id наружу/в логи не отдаётся.

**② Сигнал нового устройства.** Вход с device_id ≠ последнего → push + SMS «Вход в Юлдаш с нового устройства. Это не ты — смени номер и напиши в поддержку» (`antifraud.remember_login_device`). Не блокируем — только сигнал; первый вход тишина.

**③ Анти-телепорт GPS.** Скорость между последовательными точками > 200 км/ч → точка фейковая: presence (`isv.presence_heartbeat` → `antifraud.teleport_filter`, якорь+счётчик в Redis) её НЕ публикует (водитель не прыгает в GEO, ok=false, запрос не падает); WS-треки (попутка+такси, `location.py` → `TrackGuard` пер-соединение) кадр не ретранслируют. Якорь — последняя честная точка (два телепорта подряд не «легализуются»); первая точка после паузы проходит (время выросло → скорость упала). 3+ телепорта/час → флаг в суточный Redis-набор + лог (без координат); админ-пульс: `gps_suspects_today`.

**④ Реферал-фрод.** `reward_driver_referral` (`routers/referral.py`): бонус пригласившему — только когда приглашённый водитель сделал ≥3 «живых» done-поездок (такси: `distance_km`>1 ИЛИ onboard→done >5 мин; попутка: маршрут >1 км) с ≥3 РАЗНЫМИ пассажирами. Один бонус на приглашённого (`ReferralBonus.invited_user_id` unique) + ≤5 бонусов/месяц на пригласившего. Хуки на done: `instant.done`, `bookings.driver-status`, `family.trip-status`. Накрутка той же парой не проходит.

**⑤ Кап оценок пары.** `services.user_rating` + `drivers_bundle` (`_capped_stars`): от одной пары rater→ratee в агрегат идут только первые 3 оценки за скользящие 30 дней; остальные пишутся, но не влияют. Легаси-строки без даты не режутся.

**⑥ Анти-фишинг чата.** `antifraud.phishing_flag`: узкие паттерны (просьба кода из SMS/подтверждения/входа, 16-значный номер карты, «переведи на другой номер/карту») → `Message.flag="warn"` во всех 4 путях отправки (WS+REST, бронь+заказ) и при редактировании. НЕ блокируем. Android: плашка «⚠️ Никому не сообщай коды из SMS…» под чужим warn-сообщением (`PhishingWarnPlate`, оба чата) + дисклеймер при первом открытии чата (`ChatSafetyDisclaimer`, prefs `chat_safety_seen`). Честные «код посадки» / «буду через 5 минут» / обычный СБП не флажатся.

**⑦ «Пассажир не заплатил» одним тапом.** `POST /reports {category:"unpaid", order_id|booking_id}`: только водитель, только done-поездка, одна жалоба на заказ/бронь (повтор идемпотентен), пометка `unpaid_reported`. Страйк пассажиру — через СУЩЕСТВУЮЩУЮ механику B3/B5 (`quality.passenger_pause_until` + `unpaid_tap_strike_times`): свежие unpaid (new/reviewing) считаются сразу, reject админа снимает страйк, resolved считаются старым путём (не двоятся). Android: `UnpaidReportButton` (такси done-экран + карточки пассажиров попутки в кабинете водителя).

**⑧ Увод мимо приложения (contact-then-cancel).** Такси: отмена ПОСЛЕ accept → `contact_then_cancel` на заказе (в `isv.cancel_order`). Попутка: отмена confirmed/onboard-брони ИЛИ pending с перепиской → флаг + `cancelled_at`. Админ-пульс: `contact_then_cancel_today` (такси+попутка). Android: пассажиру после такой отмены — мягкий баннер «Договорились ехать? Заверши поездку в приложении — так работает защита и SOS 💚» (`ContactCancelSoftBanner` + тост при отмене брони).

**⑨ Официальность «Юлдаш ✓».** `Message.from_admin` ставит ТОЛЬКО сервер по роли отправителя (admin) → клиент рисует бейдж `YuldashOfficialBadge` над пузырём (оба чата). Прикинуться поддержкой нельзя.

**Тесты:** `backend/tests/test_antifraud.py` 🆕 — 50 шт (баны+IDOR, сигнал устройства, телепорт-фильтр/пульс, реферал (пара/живость/кэп/интеграция через done), кап рейтинга, фишинг-паттерны и не-флаг честных, unpaid (страйк/дедуп/права/reject), contact-then-cancel (такси/попутка/пульс), бейдж админа). **Полный прогон: 466 passed, 1 skipped** (база 416 + 50 новых). Alembic `w2_antifraud`: upgrade/downgrade/upgrade — зелёно. Баланс скобок изменённых .kt — дельта 0.

**Прод:** `alembic upgrade head`. Банит человек: `POST /admin/bans/device`; пульс расширен полями `gps_suspects_today`, `contact_then_cancel_today`.
