# 🗺️ Карта кода Юлдаш

> Чтобы НЕ читать весь файл (~5500 строк). Иди сразу на нужную строку.
> ⚠️ После сессии серверной интеграции (2026-06-23) номера строк сильно сдвинулись —
> ищи функции через `grep`/`rg` по имени; числа ниже ориентировочные.

## Главное

- **UI разрезан на модули (2026-06-27, Opus). `MainActivity.kt` 8184→664 строки.** Раньше весь UI был в одном файле — теперь по файлам (тот же пакет `com.yuldash.app`, общие символы `internal`). Каждый экран = свой файл → разные агенты пилят разные экраны параллельно. Карта файлов:
  - **Фундамент:** `AppText.kt` (двуязычие), `CanonTokens.kt` (палитра/формы/тема), `Domain.kt` (модели), **`UiKit.kt`** (продакшен-компоненты: `AppButton`/`AppStateContainer`/`AppLoading`/`AppErrorState`/`AppEmptyState`/`SkeletonBox`/`SkeletonCard`/`AppCard`/`SectionHeader` — единый источник правды для кнопок и состояний loading/empty/error; команда `/ui`).
  - **Экраны:** `LoginScreen.kt`, `SupportBoostScreen.kt`, `SecondaryScreens.kt` (Уведомл/Безоп/Настр/Помощь), `AccessibilityScreens.kt` (доступность/семья), `SosVerifyScreens.kt` (SOS+проверка водителя), `CreateRideScreen.kt`, `ProfileScreen.kt` (+кабинеты), `BookingActiveTripScreen.kt`, `RidesRequestsChatScreens.kt` (Поездки+Заявки+Чат), `MapScreen.kt` (Яндекс MapKit).
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

**Прочие новые** (`SecondaryScreens.kt`): `RulesScreen`, `PaymentInfoScreen` (СБП), `BlocklistScreen` (`/blocks`), `ReportScreen`, `FiltersScreen` (`FilterPrefs` в SharedPreferences), `ThemePickerDialog`. Код посадки — карточка в `BookingActiveTripScreen` (`getBoardingCode`). Аналитика — `data/Analytics.kt` (Firebase).

**enum `Screen`** пополнен: Rules, PaymentInfo, Blocklist, Report, Filters, AdminCabinet, AdminRequest, AdminResponses, AdminDrivers, AdminReports, RequestsFeed, RequestResponses — каждый ветка в `when(screen)` (`YuldashApp.kt`).
- **Application:** `android/app/src/main/java/com/yuldash/app/YuldashApplication.kt` — отдаёт ключ Яндекс MapKit (`MapKitFactory.setApiKey`) при старте. Прописан в манифесте как `android:name=".YuldashApplication"`.
- **Ключ карты:** `local.properties` → `YANDEX_MAPKIT_KEY` (в `.gitignore`) → пробрасывается в `BuildConfig.YANDEX_MAPKIT_KEY` через `app/build.gradle.kts` (`buildConfig = true`). В коде ключ не хардкодим.
- Тексты-ресурсы: `android/app/src/main/res/values/strings.xml` (RU) + `values-ba/strings.xml` (BA).
  Но **бо́льшая часть надписей пишется прямо в коде** через `appText(ru, ba)`.
- Стек: Jetpack Compose, Material3, minSdk 26, target/compile 36, versionName 0.1.0.

## Сервер / интеграция (слой `data/`) — добавлено 2026-06-23
- API: **`https://yulbash.ru`** (FastAPI на сервере, см. [server.md](server.md)). Клиент: **`data/ApiClient.kt`** (object, встроенный `HttpURLConnection`, БЕЗ внешних зависимостей).
- Токен JWT в **`EncryptedSharedPreferences`** (`yuldash_secure`, с миграцией старого plaintext-токена; фоллбэк на обычные prefs если шифрование недоступно), автологин. `ApiClient.init(context)` зовётся в `YuldashApplication.onCreate`.
- Методы: `requestCode`/`verifyCode` (вход), `getRides`, `createRequest`/`getMyRequests`, `publishRide`, `book`(→ booking id), `sos`, `addContact`/`getContacts`, `sendMessage`/`getMessages`, `shareTrip`/`setTripStatus`, `getConversations`/`getNotifications`/`getPopularRoutes`/`getMyRoutes`/`getAds` (списки сервер→экран, везде демо-фоллбэк), `uploadVoice`/`sendVoiceMessage`. Все POST'ы — через `fireXxx` (fire-and-forget на долгоживущем scope `ApiClient.bg`, переживают навигацию; иначе scope экрана отменял запрос).
- DTO: `RideDto`, `RequestDto`, `ContactDto`, `MessageDto` (маппинг `RideDto` — один шов `JSONObject.toRideDto()`). Геокодер адресов — через бэкенд `/geocode` (`GeocoderClient`), ключ на сервере. Мёртвый слой `Models.kt`/`Repository.kt`/`MockRepository.kt` **УДАЛЁН** 2026-06-27 (0 ссылок).
- Загрузка с сервера: поездки и заявки — `LaunchedEffect` в `YuldashApp`; контакты — там же; сообщения — в `ActiveTripScreen`.
- **`ActiveTripScreen`** (`Screen.ActiveTrip`) — экран «Моя поездка» после брони: чат по `booking_id`, поделиться с контактом, статус поездки, SOS.

## Навигация (как устроены экраны)

- Нет навигационной библиотеки. Всё через `enum Screen` + `when(screen)` в `YuldashApp()`. **Первый экран — `Screen.Splash`** (анимированное лого ~1.3с → онбординг/логин/хоум, цель `splashTarget` вычисляется заранее).
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
- `OnboardingScreen` — 408 · тексты слайдов `onboardingSlides()` — 717

### Логин
- `LoginScreen` — **реальный вход по SMS-коду** (`LoginFormCard`: телефон → «Получить код» → код → «Войти» → JWT, автологин). Логика — `data/ApiClient.kt`. · `BrandHero` · `TrustCard`

### Главный экран (оболочка + нижнее меню)
- `HomeScreen` — 1294 (Scaffold + вкладки) · `YuldashBottomBar` — 1416

### Вкладка «Карта»
- `MapScreen` — структура: `Column` { ФИКС: шапка + `MapHero` (карта вне прокрутки!) ; `LazyColumn(weight 1f)`: «Ближайшие поездки» + реклама }. Карта закреплена, чтобы её жесты не конфликтовали со скроллом. `selectedRide` + `ModalBottomSheet` (`RideCard fullWidth`) при тапе по маркеру.
- **«Ближайшие поездки»** — реальные данные с сервера (`ApiClient.getNearbyRides` → `/rides/near`). Фокус-маршрут = `activeTrip` (если едет — показываем альтернативы на ЕГО маршруте, сценарий «водитель сломался»), иначе все. Сортировка по времени выезда ↑ (самая ранняя — первой, помечена «ближайшая»). Гео: `LocationPrefs.lastLat/lng` (из карты) → дистанция «N км» в карточке. Карточки `NearbyRideCard` строго 1-в-1 (фикс 290×190dp: маршрут+проверен · время+«ближайшая» · водитель+рейтинг · дистанция+цена+«Поехать»). Состояния: `NearbySkeletonCard` (загрузка), `NearbyEmptyCard` (пусто+Обновить). Хелперы `RideDto.toUiRide()`, `fmtKm()`.
- `MapHero` — `Column { Box(карта 350dp: `YandexMapCard`/`MapPreview` + плавающая снизу **лента** `QuickSearchCard` — свайп вправо→язычок `cardCollapsed`, тап→назад) ; Row **кнопки** «Найти поездку»/«Я водитель» ОТДЕЛЬНЫМ блоком ПОД картой (не плавают, как Яндекс; «Найти» берёт `activeRoute` ленты) }`. Зум `+/-` и FAB «к себе» — в верхнем правом углу карты.
- `QuickSearchCard` — лента-карусель из **6 карточек** (`mapFeedFrom(popular, liveFeed)` → `List<MapFeedCard>`). **Единый макет** у всех: бейдж+пилюля (верх) · заголовок ФИКС.высоты (2 строки) · подпись+точки (низ) → карточки одного размера, карусель не прыгает. Типы (`FeedKind`): Route (маршрут, тапается→`activeRoute`), Live (поездок за день), Top (хит недели — топ-маршрут), Fact (факт), Community ×2 (за месяц / за год). Периоды день/неделя/месяц/год. Авто-прокрутка 4.5с, бесконечная. **Числа реальные с сервера** — `MapHero` тянет `ApiClient.getFeed()` (`/feed`) раз в 60с; офлайн → демо-значения (142/4700/38500), чтоб лента не пустовала. Русский плурал — `plRu()`/`ridesRu()`.
- `cityPoint(city)` — 1824 ← город→`Point` (mock-геоданные: Баймаҡ/Сибай/Темясово/Уфа/Учалы/Магнитогорск). `ridePinBitmap(price, boosted)` — 1836 ← маркер-«ценник» (белая пилюля + цена, золото для boosted), рисуется на Android Canvas.
- **`YandexMapCard`** ← НАСТОЯЩАЯ Яндекс-карта (MapKit). `MapKitFactory.initialize` + `MapView` через `AndroidView`, ЖЦ через `DisposableEffect`. **Маркеры-ценники поездок** (`addPlacemark`+`MapObjectTapListener`→`onRideTap`; координаты `cityPoint` + Яндекс-геокодер с кэшем). **Контролы:** зум `＋/−` (`MapZoomControls`), FAB «к себе» (`NearMe`, `zIndex(6)` — выше хит-таргетов). **Геолокация «я тут»:** СВОЙ `PlacemarkMapObject` (зелёный кружок `userPuckBitmap`) через android `LocationManager` (GPS+NETWORK) — НЕ `UserLocationLayer` (его стрелку-курс lite-SDK не перекрасить → тёмный треугольник). Флаг `LocationPrefs.sharingEnabled` (Профиль→Конфиденциальность ↔ карта), дефолт ВЫКЛ; при включении центрируем (цель чуть южнее → точка над плашкой). `view.setOnTouchListener`→`requestDisallowInterceptTouchEvent` (чтобы `LazyColumn` не съедал жесты).
- `MapPreview` — 1998 ← рисованный **фолбэк**, показывается только если ключ MapKit пустой (`BuildConfig.YANDEX_MAPKIT_KEY.isBlank()`).

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
Подключено к бэкенду `https://yulbash.ru` через `data/ApiClient.kt`: **вход по SMS-коду** (JWT, автологин), **поездки** (список), **заявки** (создать+список), **публикация поездки**, **бронь**, **SOS**, **доверенные контакты**, **чат**, **экран активной поездки** (share/статус). Данные живут на сервере (PostgreSQL), не пропадают при перезапуске.

## Бэкенд: премиум-поля и проверка водителя (2026-06-24, ✅ ЗАДЕПЛОЕНО на yulbash.ru)
- `Ride` + `RideIn`/`RideOut`: новые булевы поля предпочтений — `pets_allowed` (животные), `child_seat` (детское кресло/бустер), `women_only` (только женщины), `smoking`, `baggage`, `air_conditioner`. `GET /rides` принимает их как фильтры. Дефолты `False` → обратносовместимо.
- Проверка водителя (реальная, без платного KYC): `POST /upload/photo` (base64→`media/docs`), `POST /driver/profile` (реальное авто), `POST /driver/verify` (→`docs_status=pending` + `license_url`/`car_photo_url`), `GET /driver/status`, `POST /admin/drivers/{id}/moderate` (роль admin → `User.verified=True`). `DriverProfile` расширен (`license_url`, `car_photo_url`, `verify_submitted_at`, `docs_status` дефолт→`none`).
- ✅ Задеплоено на `yulbash.ru` (`deploy-backend.bat` scp+ssh + `migrate_premium.sql` = 9× `ALTER TABLE`: 6 колонок `ride` + 3 `driverprofile`). Проверено server-side: `/driver/status`, `/upload/photo` → 401 (живы, нужен токен), `/rides/near` отдаёт `women_only`/`pets_allowed`. **Прод-API проверять server-side через ssh** (`curl localhost:8000/...`) — Windows-curl к домену = `000` (ТСПУ).
- ✅ Android ИСПОЛЬЗУЕТ (сборка зелёная на ПК): чипы предпочтений `RidePrefChips`/`PrefChip` в `FullRideCard`, тумблеры `PrefToggleRow` в «Создать поездку», фильтр `NearbyFilterChip` над «Ближайшими», переписанный `VerifyDriverScreen` (загрузка фото прав/авто `UploadTile`→`/upload/photo`, `/driver/profile`, `/driver/verify`, статус-баннер `StatusBanner` из `/driver/status`). Работает по-настоящему (бэк живой). Осталось: водительский UI оценки пассажира, фильтр вкладок чата.

## Push-уведомления (FCM) — реализованы, Firebase настроен (2026-06-27)
Полный end-to-end, активируется наличием конфигов (без них — тихо выключено, сборка не падает):
- **Android:** `data/FcmService.kt` (`FirebaseMessagingService` — показ нотификации), регистрация токена `ApiClient.registerPushToken`/`getFcmToken` (`FirebaseMessaging.getInstance().token` → `POST /push/register`), permission `POST_NOTIFICATIONS` (Android 13+). Плагин `com.google.gms.google-services` применяется в `build.gradle.kts` **только при наличии** `app/google-services.json`; зависимости `firebase-bom` + `firebase-messaging-ktx`.
- **Бэкенд:** `services.send_push(session, user_id, title, body)` через `firebase_admin` → шлёт на все `DeviceToken` юзера. Триггеры: новая бронь (`bookings.py`), новое сообщение чата (`chat.py`), SOS. `POST /push/register` сохраняет токен. Конфиг `firebase_credentials` (путь к service-account JSON).
- **Конфиги на месте:** прод `/opt/yuldash/firebase-service-account.json` + `FIREBASE_CREDENTIALS` в `.env` (бэкенд шлёт); `android/app/google-services.json` — в главном чекауте Александра (в `.gitignore`, потому в worktree-сборках FCM скомпилён, но неактивен → токен не регистрируется).
- ⚠️ **Осталось:** проверить реальную доставку на устройство (сборка из главного чекаута). Для теста в worktree — скопировать `google-services.json` в `android/app/`.

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
- **Сплэш.** `Screen.Splash` + `SplashScreen()` (лого scale+alpha, текст следом, ~1.3с). Системный сплэш Android 12 брендирован в `styles.xml` (`windowBackground` + `windowSplashScreenBackground` = `@color/yuldash_splash`) — без белой вспышки.
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
