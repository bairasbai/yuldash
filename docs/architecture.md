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

## Лендинг для скачивания (`web/`) — добавлено 2026-06-27
- **Отдельная веб-зона**, не Android. Премиум-лендинг для скачивания APK напрямую.
- **Стек:** Next.js 14 (App Router) + framer-motion + Tailwind. `output: "export"` → чистая статика (`web/out/`), раздаётся Nginx на yulbash.ru (Node в проде НЕ нужен).
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
