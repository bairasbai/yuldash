# 🗺️ Карта кода Юлдаш

> Чтобы НЕ читать весь файл (~3400 строк). Иди сразу на нужную строку.
> Если правишь код и строки сдвинулись — обнови числа здесь (примерно, ±20 ок).

## Главное

- **Весь UI в одном файле:** `android/app/src/main/java/com/yuldash/app/MainActivity.kt`
- Тема: `android/app/src/main/java/com/yuldash/app/ui/theme/Theme.kt`
- **Application:** `android/app/src/main/java/com/yuldash/app/YuldashApplication.kt` — отдаёт ключ Яндекс MapKit (`MapKitFactory.setApiKey`) при старте. Прописан в манифесте как `android:name=".YuldashApplication"`.
- **Ключ карты:** `local.properties` → `YANDEX_MAPKIT_KEY` (в `.gitignore`) → пробрасывается в `BuildConfig.YANDEX_MAPKIT_KEY` через `app/build.gradle.kts` (`buildConfig = true`). В коде ключ не хардкодим.
- Тексты-ресурсы: `android/app/src/main/res/values/strings.xml` (RU) + `values-ba/strings.xml` (BA).
  Но **бо́льшая часть надписей пишется прямо в коде** через `appText(ru, ba)`.
- Стек: Jetpack Compose, Material3, minSdk 26, target/compile 36, versionName 0.1.0.

## Навигация (как устроены экраны)

- Нет навигационной библиотеки. Всё через `enum Screen` + `when(screen)` в `YuldashApp()`.
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
- `LoginScreen` — 788 (фейковый: любой ввод → главная) · `BrandHero` — 853 · `TrustCard` — 910

### Главный экран (оболочка + нижнее меню)
- `HomeScreen` — 1294 (Scaffold + вкладки) · `YuldashBottomBar` — 1416

### Вкладка «Карта»
- `MapScreen` — 1533 (внутри: `selectedRide` + `ModalBottomSheet` с `RideCard(fullWidth=true)` при тапе по маркеру) · `MapHero` — 1634 · `QuickSearchCard` — 1722 · `SeniorAccessCard`.
- `MapHero` — карта стала главным первым блоком вкладки: реальный `YandexMapCard` или `MapPreview`, поверх короткий статус `Геолокация скрыта` и компактная карточка маршрута.
- `cityPoint(city)` — 1824 ← город→`Point` (mock-геоданные: Баймаҡ/Сибай/Темясово/Уфа/Учалы/Магнитогорск). `ridePinBitmap(price, boosted)` — 1836 ← маркер-«ценник» (белая пилюля + цена, золото для boosted), рисуется на Android Canvas.
- **`YandexMapCard` — 1890** ← НАСТОЯЩАЯ Яндекс-карта (MapKit). Ленивый `MapKitFactory.initialize` + `MapView` через `AndroidView`, жизненный цикл через `DisposableEffect`. Маршрут Баймаҡ→Сибай (прямая зелёная линия) + круги-зоны старт/финиш + **маркеры-ценники поездок** (`addPlacemark` + `MapObjectTapListener` → `onRideTap`) + накладки (метки городов, «43 км», замок). `view.setOnTouchListener` → `requestDisallowInterceptTouchEvent` (чтобы `LazyColumn` не съедал жесты карты). Координаты — `BaymakPoint`/`SibayPoint`/`MapMidPoint`.
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

## Что фейковое (логики нет, только UI)
Логин, платежи (донат/Boost), SOS, проверка водителя, «скрытый номер», код посадки, реальное распознавание голоса, звонок оператору, SMS/push близким. Поездки, заявки, доверенные контакты, реклама и голосовые сообщения добавляются в память и пропадают при перезапуске.

**Карта — настоящая и работает** (Яндекс MapKit, `YandexMapCard`). 2026-06-22 ключ **активировался** — реальные тайлы Башкортостана грузятся (Баймаҡ/Сибай/Тубинский и т.д.), на карте видны маркеры-ценники поездок. Маршрут пока прямая линия (настоящий роутинг по дорогам — позже, нужен `-full` SDK + Router). Тап по маркеру → нижняя карточка поездки (`ModalBottomSheet`); добавлен фикс перехвата касаний для карты внутри прокручиваемого списка.

## Анимации (добавлено 2026-06-22)
- Переходы экранов: `AnimatedContent` вокруг `when(screen)` в `YuldashApp` и `when(selectedTab)` в `HomeScreen`.
- Нижнее меню `YuldashBottomItem`: анимация пилюли и масштаба иконки (`animateColorAsState`, `animateFloatAsState`).
- Хелпер `Modifier.bounceClick(onClick)` (рядом с `YuldashBottomItem`) — лёгкое сжатие при нажатии; применён к карточкам/строкам/SOS вместо обычного `clickable`.
- Хелпер `Modifier.appearIn(index)` (рядом с `bounceClick`) — карточки каскадом всплывают снизу при появлении; применён к спискам всех 5 вкладок (Карта, Поездки, Заявка, Чат, Профиль) через `Box(Modifier.appearIn(i)) { ... }`.

## Сборка и запуск

```powershell
cd C:\Users\Bayra\Yuldash\android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug --no-daemon
```

ADB: `C:\Users\Bayra\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Подробности запуска/эмулятора/smoke-теста — в `../CONTINUE_FOR_AI.md`.
