# ✅ Задачи Юлдаш

## План: новый фон входа с Салаватом Юлаевым — 2026-06-29

- [x] Сгенерировать новый вертикальный bitmap-фон по референсу Александра: Салават Юлаев, Уфа/Белая, зелёные холмы, тёплый свет, без текста внутри изображения.
- [x] Сохранить фон как `android/app/src/main/res/drawable-nodpi/login_salavat_yulaev_hero.png`; старый `login_bashkir_telegram_hero.png` оставить в проекте для отката.
- [x] Переключить `LoginScreen.kt` на новый фон и убрать прежний ручной сдвиг/зум кадра.
- [x] Проверить: `:app:assembleDebug` и `:app:assembleRelease` — BUILD SUCCESSFUL; release-экран входа снят в `android/screenshots/login-salavat-bg.png`.

## План: регистрация как на референсе — 2026-06-29
- [x] Перестроить Android-экран входа под референс: высокий hero с фоном Башкортостана, крупный бренд, три преимущества и нижняя белая панель входа.
- [x] Оставить фактически рабочий сценарий: основной вход через Telegram-код; телефонная кнопка видна только при включённом `SMS_LOGIN_ENABLED`.
- [x] Сохранить двуязычие через `appTextFor(ru, ba)` и отметить новые BA-строки как черновики.
- [x] Проверить сборку: `:app:assembleDebug` — BUILD SUCCESSFUL.

### Переводы на проверку — логин 2026-06-29
- «Тиҙ һәм хәүефһеҙ инеү өсөн Telegram ҡулланығыҙ» (подпись формы)
- «йәки» (разделитель)
- Ряды на геро ОБНОВЛЕНЫ (старые повторяли онбординг → заменены на трасты про вход):
  - `Вход без пароля` → `Парольһеҙ инеү` · `Только код — ни паролей, ни анкет` → `Бары код — пароль да, анкета ла юҡ`
  - `Никакого спама` → `Спам юҡ` · `Не звоним и не шлём SMS` → `Шылтыратмайбыҙ, SMS ебәрмәйбеҙ`
  - `Данные под защитой` → `Мәғлүмәт һаҡлауҙа` · `Шифруем и не передаём третьим` → `Шифрлайбыҙ, өсөнсө яҡҡа бирмәйбеҙ`

## План: вернуть живость первого лендинга — 2026-06-29
- [x] Вернуть направление первого варианта: эмоциональный hero, живой интерфейс приложения, тёплые CTA, ощущение мобильного продукта.
- [x] Оставить улучшения второй итерации: чистый Remotion-фон без текстового шума, честный блок отзывов, аккуратная адаптация.
- [x] Проверить `npm run build`, desktop/mobile и консоль браузера.

### Ревью: возврат первого лендинга — 2026-06-29
- `web/components/YuldashLanding.tsx` восстановлен в живом направлении первого варианта: typewriter-hero «Юлдаш ведёт по республике красиво», телефонный mockup, шаги, интерактивная карта, 3D-карусель сценариев и download-блок.
- Улучшения второй итерации сохранены: Remotion-видео осталось чистым фоновым маршрутом без текстового шума; блок отзывов не выдаёт выдуманные цитаты за реальные отзывы, а честно помечен как beta-сценарии.
- `web/app/globals.css` возвращён к первой тёмно-зелёной/золотой палитре. В `web/app/layout.tsx` исправлен конфликт Яндекс.Метрики: `Script id="ym"` заменён на `id="yandex-metrika"`.
- Проверка: `npm run build` в `web/` успешен; свежий dev-сервер `http://localhost:3017` — desktop 1440 и mobile 390 без горизонтального скролла, видео `readyState=4`, секции `top/how/map/reviews/download` на месте, console warnings/errors пусто.

## План: лендинг уровня "$15k" — 2026-06-29
- [x] Убрать дешёвые визуальные признаки: typewriter-курсор, игрушечную 3D-карусель, emoji/такси в Remotion, чрезмерные скругления и перегруженный split-hero.
- [x] Пересобрать hero по правилам премиального лендинга: H1 = «Юлдаш», full-bleed карта/видео, текст поверх сцены, видимый намёк следующей секции.
- [x] Сделать дизайн-систему строже: 8px-карточки, спокойная сетка, контрастные светлые/тёмные CSS-переменные, дорогая типографика без viewport-scaling для мелких элементов.
- [x] Пересобрать секции в editorial/product-flow: «Маршрут», «Доверие», «Карта покрытия», «Отзывы/ранние сценарии», «Скачать».
- [x] Перерендерить Remotion-видео без emoji, с маршрутной графикой и продуктовым UI.
- [x] Проверить сборку, десктоп/мобайл в браузере, консоль, обновить мозг.

### Ревью: лендинг уровня "$15k" — 2026-06-29
- `web/components/YuldashLanding.tsx` полностью переписан: H1 теперь бренд «Юлдаш», hero стал full-bleed сценой с картой Башкортостана, маршрутами и строгой live-route панелью; убраны typewriter, игрушечная 3D-карусель и большие скругления.
- `web/app/globals.css` получил более дорогую палитру: тёплый чёрный, ivory-текст, латунный акцент, сине-серый баланс; карточки и панели стали строгими, без декоративных orbs.
- `promo/src/Promo.tsx` перерендерен в фоновый маршрутный ролик без emoji и текстовых слоёв; `web/public/yuldash-promo.mp4` теперь ~860 КБ.
- Блок «Отзывы» сделан честно: нет выдуманных цитат; стоят слоты под реальные отзывы после подтверждённых поездок.
- Проверка: `npm run build` в `web/` успешен; браузер `http://localhost:3007` — desktop и mobile без горизонтального скролла, видео `readyState=4`, консоль без warnings/errors.
- BA-строки новой версии остаются черновиком модели, нужна проверка Александра-носителя.

## План: новый интерактивный лендинг web/ — 2026-06-29
- [x] Зафиксировать текущую структуру web/ и оставить юридические страницы, SEO, двуязычие и честную модалку скачивания.
- [x] Поставить доступные зависимости для нового UX: `lenis`, `lucide-react`; `@21st-dev/react` не ставить, потому что npm-реестр возвращает 404.
- [x] Собрать новый первый экран: видео/анимированная карта, крупный типографический заголовок, CTA «Найти попутчика» и скачивание.
- [x] Сделать ключевые секции: как работает, интерактивная карта поездок, отзывы-карусель, скачать приложение, минималистичный footer.
- [x] Обновить Remotion-промо и отрендерить/подключить видеофон, если локальный рендер пройдёт.
- [x] Проверить `npm run build`, визуально открыть лендинг локально, обновить `architecture.md`/`tasks.md`.

### Ревью: новый интерактивный лендинг web/ — 2026-06-29
- Главная `web/app/page.tsx` теперь собирает новый `YuldashLanding`: hero с Remotion-видео `/yuldash-promo.mp4`, анимированной картой, typewriter-заголовком, CTA «Найти попутчика», блоками «Как работает», «Карта», «Отзывы», «Скачать».
- Добавлены `lenis` и `lucide-react`. Пакет `@21st-dev/react` не добавлен: npm registry вернул `404 Not Found`, поэтому 21st/shadcn-подход реализован локальными компонентами и стилями.
- Remotion-композиция `promo/src/Promo.tsx` переведена в 16:9 и отрендерена в `web/public/yuldash-promo.mp4` (1.6 МБ).
- Проверка: `npm run build` в `web/` успешен; браузер на `http://localhost:3007` показал корректный hero, видео `readyState=4`, секции `top/how/map/reviews/download`, консоль без ошибок.
- Башкирские строки нового лендинга — черновик модели, нужна проверка Александра-носителя.

> Перед нетривиальной задачей — сначала план здесь, потом код.
> Отмечай `[x]` по ходу. Раздел «Ревью» — короткий итог после выполнения.

## Сейчас в работе
- [x] 2026-06-29 Интерим-модерация оплат (буст+донат+реклама) в админ-кабинете (до ЮKassa). **ЗАДЕПЛОЕНО.**
  - [x] Реклама через очередь: поле «Цена партнёру» в форме → `Payment(ad)` pending → подтверждение публикует объявление. `payment.ad_id` (миграция применена), `_activate_payment` (boost+ad+donate). Прод flow-тест 17/17 PASS, задеплоено (models reconciled + payments + ads).
  - [x] Бэкенд: `POST /donate` (purpose=donate, заявка на подтверждение), `GET /admin/payments/summary` (счётчик), Telegram-уведомление админу при новой оплате. Буст-очередь уже была. **13/13 тестов PASS**, app импортится.
  - [x] Android: `ApiClient` (createDonation/getPendingPayments/confirm/reject/getPaymentsSummary + DTO); экран `AdminPaymentRequestsScreen` (очередь + Подтвердить/Отклонить + счётчик донатов); пункт «Заявки на оплату» в админ-кабинете; навигация. Донат-кнопка → бэкенд; `SbpTransferSheet` берёт реквизиты из ответа (хардкод-номер → фолбэк). **BUILD SUCCESSFUL.**
  - [x] Деплой: только `payments.py` (прод==база, не трогал reconciled `models.py`) + прод `.env`: `PAYMENTS_PROVIDER=sbp_manual` + `SBP_PHONE/BANK/NAME` (из текущего хардкода). Сервис active, health ok, роуты живы. Flow-тест на прод-venv 12/12 PASS.
  - [ ] Релизный APK (Александр) → вкладка появится в приложении. ЮKassa — после публикации.
- [x] 2026-06-29 Старт-интро в стиле референса (до онбординга): `IntroScreen` на фоне вектор-пейзажа Башкортостана (`res/drawable/splash_landscape.xml` — порт из `yuldash_splash_vector.svg`). Полный набор: чистый белый значок (без квадрата/тени/курая), плавный уход системного сплэша (SplashScreen API), проявление пейзажа + Ken-Burns, «Попутчик»→«Юлдаш» без наложений + золотая черта + слоган RU→BA (плотно, лого не дёргается), золотая пыльца `SkyMotes`, белые иконки статус-бара, тактильный «тук». Мёртвый код `drawKurai`/`drawRoad` вычищен. `assembleDebug` зелёная, многократно проверено на эмуляторе.
- [x] 2026-06-29 Авто-проверка водителей (Tier-1, OCR прав) — **бэкенд готов, НЕ задеплоен.**
  - [x] Выбор: Yandex Vision OCR (~0,13 ₽/фото) вместо Tesseract (точнее на фото прав).
  - [x] `backend/app/driver_check.py`: локальные гейты + OCR + разбор номера/срока прав → `pass/needs_human/reject/error`.
  - [x] `DriverProfile` +4 поля (`autocheck_*`); шов в `/driver/verify` (`_run_autocheck`); `/driver/status` и `/admin/drivers/pending` отдают результат.
  - [x] Флаги: `DRIVER_AUTOAPPROVE_ENABLED=false` (по умолчанию решает админ), `YANDEX_VISION_KEY` в `.env.example`. `validate_production` запрещает авто-одобрение без ключа.
  - [x] 18 unit-проверок PASS (без сети, OCR замокан); `py_compile` + импорт приложения OK.
  - [x] 2026-06-29 **Деплой выполнен:** миграция 4 колонок `driverprofile` применена; залиты 4 файла (config/models/drivers/driver_check — модели reconciled с прод-полями `vk_id`/`whatsapp_verified`); `YANDEX_VISION_KEY` (тестовый) в прод `.env`, `autoapprove=false`; сервис `active`, `db:ok`. **E2E на проде:** тест-фото → `pass`. Бэкап БД+кода до деплоя.
  - [x] 2026-06-29 **Клиент — админ-очередь:** в `AdminDriversScreen` бейдж `AutoCheckRow` (вердикт pass/needs_human/reject + распознанные № прав и срок из `autocheck_data`), цвета `Canon*`, два языка. DTO `PendingDriverDto`/`DriverStatusDto` + парсинг расширены. **Android BUILD SUCCESSFUL** (worktree). Обратносовместимо: старый APK поля игнорит, бэк уже отдаёт.
  - [ ] **Клиент — водителю:** показать причину на экране проверки (данные уже в `DriverStatusDto.autocheckResult/Data`, нужен только UI в `VerifyDriverScreen`). Двуязычные строки готовы (см. «Переводы на проверку»).
  - [x] 2026-06-29 Проверено живым ключом: тест-фото прав → STATUS 200, текст распознан, номер+срок разобраны, e2e через `check_driver_docs` = `pass`. Хост `ai.api.cloud.yandex.net`, `Api-Key`, folder опционален (ключ AI Studio работает без него).
  - [ ] Александр: ротировать тестовый ключ (засветился в скриншоте) → новый только в прод `.env`.
- [x] 2026-06-28 Полный QA-аудит фич: пересчитать сценарии, прогнать backend pytest, Android assembleDebug, статические проверки, эмуляторный smoke при доступном устройстве, визуально оценить ключевые экраны и записать честный отчёт.
  - [x] Инвентаризация экранов/API/сценариев.
  - [x] Backend pytest: 63 passed, 1 warning.
  - [x] Android assembleDebug: BUILD SUCCESSFUL.
  - [x] Статические проверки Android-кода.
  - [x] Эмуляторный smoke + скриншоты на `emulator-5554`.
  - [x] Визуальная дизайн-оценка по ключевым экранам.
  - [x] Итог: см. «Ревью выполненного» ниже.
- [x] 2026-06-28 Логин v2: заменить hero-картинку на новую с башкирским колоритом, сделать текст Telegram-входа актуальным про 6-значный код, убрать слишком широкое обещание «каждый водитель проходит проверку», собрать и визуально проверить экран.
- [x] 2026-06-28 Онбординг v2 под стиль входа: hero-картинка с башкирским колоритом, маленький логотип, актуальные тексты без лишних обещаний, сборка и скриншоты.
- [x] Дизайн-полировка по ТЗ 2026-06-22.
  - [x] Карта как главный первый экран.
  - [x] Компактнее верхние отступы.
  - [x] Профиль: пользовательские настройки выше, кабинет рекламы отдельно.
  - [x] Рекламные карточки проще для пользователя, подробная статистика в кабинете.
  - [x] Чат ближе к мессенджеру.
  - [x] Простой режим: 4 главных действия.
  - [x] Унификация табов и empty/loading states.
  - [x] Сборка и smoke-проверка.

## Бэклог
- [x] Плавные анимации: переходы вкладок/экранов, анимированное меню, сжатие нажатий (`bounceClick`), каскад карточек (`appearIn`) на всех 5 вкладках. ✓ Готово 2026-06-22.
- [x] Интеграция Яндекс MapKit (Этап 1): **готово, работает**. Реальный `MapView` (`YandexMapCard`), маршрут Баймаҡ→Сибай, накладки, ключ в `local.properties`→`BuildConfig`, `setApiKey` в `YuldashApplication`. Ключ **активировался** → реальные тайлы грузятся (проверено на эмуляторе). План — [map-design.md](map-design.md).
- [~] MapKit Этап 2 — маркеры поездок + карточка снизу: **код готов, сборка зелёная, маркеры-ценники рендерятся на карте** (проверено визуально на эмуляторе). Добавлены штатный `MapObjectTapListener`, `requestDisallowInterceptTouchEvent` для карты в `LazyColumn`, а также fallback hit-targets и координатная обработка touch для видимых ценников. ⚠️ Финальный `uiautomator`-прогон по ценнику на эмуляторе остался нестабильным по фокусу/логину; проверить тап→bottom sheet на реальном устройстве.
  - [x] **Геосаджест в полях «Откуда/Куда»** — СДЕЛАНО 2026-06-23 через Яндекс.Геокодер (HTTP API, ключ в `local.properties`→BuildConfig). `GeocoderClient.suggest` + `AddressSuggestField` (дебаунс + выпадающий список реальных адресов), подключён в CreateRequest/CreateRide. Проверено на эмуляторе. Коммит `42bab91`.
  - Осталось по Этапу 2 (позже): кастомный JSON-стиль карты (брендовые тона), настоящий маршрут по дорогам (Router, нужен `-full` SDK).
- [x] Убран мёртвый код: `RequestTabScreen`, `DirectionChips`, `FiltersCard` удалены (~265 строк, не вызывались). Сборка зелёная. ✓ 2026-06-22.
- [ ] Проверка башкирских переводов носителем (накопить список в отдельном разделе ниже).
- [x] **Бэкенд-интеграция (2026-06-23): СДЕЛАНО.** Сервер FastAPI на `https://yulbash.ru` (PostgreSQL, HTTPS, свой домен). Приложение подключено через `data/ApiClient.kt`: вход по SMS-коду (JWT, автологин), поездки, заявки, публикация поездки, бронь, SOS, доверенные контакты, чат, экран активной поездки (`ActiveTripScreen`: share/статус). Все эндпоинты проверены (curl/эмулятор), сборка зелёная. Детали — [server.md](server.md).
  - Осталось: реальная доставка SMS (ждёт одобрения отправителя на sms.ru — действие Александра). ✓ Инбокс диалогов сделан (`/conversations`, 2026-06-23).

## 🔪 План: резать MainActivity.kt на модули (разблокировать параллелизм)

> Цель: разные экраны → разные файлы → разные агенты без конфликтов. **Правила:** один экран за шаг; `private`→`internal` при выносе; сборка зелёная и коммит после КАЖДОГО шага; поведение не меняется (чистый перенос). Пакет `com.yuldash.app`, файлы в одном модуле → импорты почти не нужны.

> ✅ **РАЗРЕЗКА ЗАВЕРШЕНА 2026-06-27 (Opus). Фазы 0,1,2,3 — все готовы.** Вынесено **15 файлов**, `MainActivity.kt` **8184→727 строк (−7457, −91%)**. Остаток — тонкое общее ядро (вход, `enum Screen`, сплэш, хелперы-расширения, `VoiceRecorder`, `SbpTransferSheet`, OAuth-хелперы). Дальше дробить — чистая косметика, не нужно. Чистая полная сборка (`clean assembleDebug`) зелёная. ~20 коммитов, каждый шаг проверен сборкой, поведение 1-в-1. Подход: новый файл = `package` + полный блок импортов (лишние = варнинги) + код; входной composable `internal`, хелперы `private`; общие хелперы открыты `internal` на месте. Урок: **граница блока = закрывающая `}`, НЕ следующая `@Composable`/`@OptIn`** (один раз срезал аннотацию → откат+редо). В `MainActivity.kt` остался тонкий общий слой: класс `MainActivity`, `enum Screen`, онбординг/сплэш, общие модели/моки, OAuth-хелперы. **Экраны теперь по файлам → параллелить агентами можно.**

**Фаза 0 — фундамент. ✅ СДЕЛАНО:**
- [x] `AppText.kt` ← `appText`/`appTextFor`/`AppLanguage`/`LocalAppLanguage` (internal).
- [x] `CanonTokens.kt` ← `Canon*` + формы + `ThemePrefs`/`appIsDark` (internal).
- [x] `Domain.kt` ← `Ride`/`PopularRoute`/`TrustedContact`/`FrequentTrip`/`LocalRequest`/`LocalVoiceMessage` (internal). (Ads-модели `PartnerAd`/`AdStats`/`AdPlacement`/`AdStatus` открыты `internal` на месте; моки `demoRides`/`demoPartnerAds` пока в MainActivity — переедут с картой/нав.)
- [~] Общие компоненты (`EmptyStateCard`/`InfoCard`/`PartnerAdCard`/`DetailMeta`/`SettingsGroup`/`bounceClick`/`appearIn`/`ScreenTopBar`/`YuldashBottomBar`/`SegmentedTabs`) — открыты `internal` на месте (в отдельный `Common.kt` вынести позже, не обязательно для параллелизма).

**Фаза 1 — экраны-листья:**
- [x] `LoginScreen.kt` (LoginScreen+BrandHero/TrustCard/LoginFormCard/SafetyFooter).
- [x] `SupportBoostScreen.kt` (SupportScreen+BoostScreen+BoostPlan; `SbpTransferSheet`→internal).
- [x] `SecondaryScreens.kt` (NotificationsScreen+SafetyScreen+SettingsScreen+HelpScreen).
- [x] `AccessibilityScreens.kt` (SimpleMode/VoiceRequest/CreatePassengerRequest/FamilyOrder/TrustedContacts/RepeatTrip/CallbackHelp).
- [x] `SosVerifyScreens.kt` (SosScreen + VerifyDriverScreen). **Фаза 1 закрыта.**

**Фаза 2 — крупные экраны. ✅ СДЕЛАНО:**
- [x] `CreateRideScreen.kt` (+PrivacyScreen).
- [x] `ProfileScreen.kt` (Профиль + кабинет рекламы + кабинеты пассажира/водителя).
- [x] `BookingActiveTripScreen.kt` (BookingScreen + ActiveTripScreen + MessageBubble).
- [x] `RidesRequestsChatScreens.kt` (RidesScreen + MyRequestsScreen + ChatScreen + карточки).

**Фаза 3 — карта и навигация. ✅ СДЕЛАНО:**
- [x] `MapScreen.kt` (MapScreen + Яндекс MapKit инфра).
- [x] `YuldashApp.kt` (нав-корень `YuldashApp` + `HomeScreen` + нижнее меню).
- [ ] (Опц., косметика, НЕ блокирует параллелизм) общие модели/моки/онбординг/OAuth-хелперы из `MainActivity.kt` (1008 строк) → `Domain.kt`/`OnboardingScreen.kt`/`Mocks.kt`. Можно позже.

**Фаза 2 — крупные экраны (больше связей):**
- [ ] `CreateRideScreen`, `BookingScreen`, `VerifyDriverScreen`, `AdsCabinetScreen`, `ProfileScreen`, `ChatScreen`, `MyRequestsScreen`, `RidesScreen`.

**Фаза 3 — карта и навигация (самые связные, в конце):**
- [ ] `ui/screens/MapScreen.kt` + `ui/map/` (`YandexMapCard`, `MapHero`, `cityPoint`, `ridePinBitmap`, `userPuckBitmap`, контролы).
- [ ] `ui/YuldashApp.kt` ← корень навигации (`YuldashApp`, `enum Screen`, `HomeTab`, `HomeScreen`, `YuldashBottomBar`). `MainActivity.kt` остаётся тонким (только `onCreate`).

**Риски/подводные камни:**
- `@Composable`-геттеры `Canon*` нельзя звать в не-composable (Canvas/DrawScope) — уже учтено в коде, при переносе сохранить.
- Видимость: `private`→`internal` (модуль), не `public`. Без `internal` другой файл не увидит.
- Новый экран = `enum Screen` + ветка `when` в `YuldashApp` (§6) — при выносе не потерять ветку.
- Параллелить выносы между собой НЕЛЬЗЯ, пока всё в одном файле (см. §12 золотое правило). Параллелизм включается ПОСЛЕ разрезки.

## QA — ручная проверка (чеклист)
> Команды — в [qa.md](qa.md).
- [x] Полный uiautomator QA подэкранов приложения на эмуляторе.
- [ ] Все вкладки нижнего меню: Карта, Поездки, Заявка, Чат, Профиль.
- [ ] Первый запуск после `pm clear` → онбординг снова появляется.
- [ ] После онбординга повторный запуск → сразу логин (онбординг не показан).
- [x] Launcher-иконка качественно выглядит в ресурсах Android и подключена в манифесте.

## Сделано
- [x] 2026-06-29 **ВЕРИФИКАЦИЯ вживую (Opus): emulator smoke + e2e-симуляция на проде 20/20.**
  - **Android smoke (emulator-5554, debug):** ставится, грузится, 5 вкладок, **0 крашей** (logcat чист). Подтверждено визуально: карта+ценники поездок (#7), «Мои поездки» — честная пустота без фейка «Рамиль» (P0), чат без композера в инбоксе (P0), Настройки — кнопка «Выйти» + версия из BuildConfig + живой тумблер «Звуки» (P1). Authed-UI (реферал-карточка, реальные брони) в debug не виден — логин минуется, токена нет (корректно скрыто, не баг).
  - **E2E-симуляция на ЖИВОМ проде (`/tmp/sim_e2e.py`, прод-venv):** создал 3 тест-юзера + токены (`issue_tokens`), прогнал весь authed-флоу через `http://127.0.0.1:8000`, **20/20 PASS**, тест-данные удалены. Проверено реально: F1 реферал (redeem→оба +1, invited, повтор отбит), F1 boost-бонус (тратит/отбивает), F2 роль+driver-статус (200 водителю, 403 пассажиру), P0 `/bookings/mine` джойн маршрута+водителя, P1 дедуп брони, P1 завершение→бронь done, P1 чёрный список (скрыт после блока, аноним видит).
  - **Единственное непокрытое:** доставка push на реальный телефон (эндпоинт driver-status 200, конвейер вызывается, но без FCM-токена устройства отправка = no-op). Нужен вход по Telegram на телефоне Александра.
  - **Вердикт:** код production-ready подтверждён двумя слоями (UI рендер + прод-API 20/20). Для закрытой беты остаётся 1 ручной шаг: release на телефон → вход → проверить, что пуш пришёл.
- [x] 2026-06-29 **3 фичи под запуск (реферал / driver-статус-push / цена-ориентир) — e2e + ЗАДЕПЛОЕНО merge'ем (Opus).**
  - **F3 Цена-ориентир** — оказался уже реализован (`CreateRideScreen`: `getPriceHint` с дебаунсом + чип «Обычно ~N₽ · нажми»). Проверено.
  - **F1 Реферал «позови своего»** — User+`referral_code/referred_by/referral_credits`; роутер `referral.py` (`/referral/me`, `/referral/redeem` — оба получают бонус); `POST /boost/free` (1 бонус = бесплатное поднятие 24ч). Клиент: карточка в Профиле (код, «Позвал N», «Бонусов M», поделиться/ввести код) + BoostScreen «Поднять бесплатно». Миграция `migrate_referral.sql`.
  - **F2 Push «водитель выехал/подъезжает»** — `GET /bookings/{id}/role` + `POST /bookings/{id}/driver-status` (водитель→push пассажиру). Клиент: в активной поездке водитель видит «Я выехал/Подъезжаю», пассажир получает push.
  - **⚠️ Инцидент: прод разошёлся.** Перед деплоем обнаружил — параллельная сессия передеплоила прод и затёрла мои сегодняшние выкаты (джойн `/bookings/mine`, дедуп, чёрный список #6, завершение брони), добавив своё (рефактор загрузки `_validate_upload`, row-lock в `cancel_booking`). Не стал затирать вслепую → **аккуратный merge**: прод-версии `services.py`/`bookings.py` как база + мои хунки поверх; остальные файлы — безопасные суперсеты. **pytest 69/69 на объединённом**, задеплоено, проверено server-side: мои фичи живы И чужая работа сохранена (`_validate_upload`, cancel row-lock на месте). Урок — `lessons.md`.
  - Android: assembleDebug BUILD SUCCESSFUL. Backend pytest **69/69**.
- [x] 2026-06-29 **E2E-аудит всех 39 фич + батч фиксов (Opus, worktree).** 6 параллельных read-only аудиторов прошли каждую фичу насквозь (экран→ApiClient→роут→БД→ответ→UI), нашли реальные дефекты (ложные/by-design отсеяны). **Починено и проверено (Android BUILD SUCCESSFUL, pytest 63/63):**
  - **P0 «Мои поездки» — был фейк-список** (захардкожен «Рамиль/12 мая», табы не фильтровали, `/bookings/mine` не звался). Теперь: бэк `/bookings/mine` джойнит сводку поездки (маршрут/водитель/время, батч против N+1, поле `id` сохранено → обратносовместимо); клиент `getMyBookingsDetailed()`+`BookingMineDto`; `RidesScreen` рисует реальные брони, табы Активные/История/Все фильтруют по статусу, `id`=booking_id (попутно фикс неверного booking_id при открытии активной поездки). Тест `test_booking_lists` расширен. ⏳ **Бэк надо задеплоить** (клиент устойчив к старому серверу — поля сводки пустые → «Поездка №id»).
  - **P0 Чат-инбокс слал не в тот диалог** — композер во вкладке «Чат» отправлял в `latestBookingId` (последняя бронь), у водителя в никуда. Композер из инбокса убран; общение — только в открытой поездке (`ActiveTripScreen`, тред по booking_id — он был корректен).
  - **P1 Настройки/Безопасность врали** — тумблеры Уведомления/Звуки/Скрыть телефон/Только проверенные были локальный `remember` (сбрасывались). Заведён `AppPrefs` (персист). Уведомления/Звуки реально гасят пуш (`FcmService` читает перед показом). «Только проверенные» реально фильтрует «Ближайшие» на карте.
  - **P1 Нет выхода из аккаунта** — кнопка «Выйти» в Настройках (`logout()`→Login, с подтверждением).
  - **P1 Тихие потери сети** — имя/аватар/«Я на линии» (`ProfileScreen`) глотали `onFailure` → тост + откат тумблера.
  - **P1 Жалоба вслепую** — Toast «отправлено» был безусловный → теперь по результату.
  - **P1 Мёртвые кнопки Помощи** — «Связаться с поддержкой»→`/callback`, «Написать в Telegram»→t.me; фейк-поиск→реальный фильтр FAQ + раскрывающиеся ответы.
  - **P1 Демо-уведомления маскировали пустоту** (новый юзер видел фейк «Рамиль едет») → честная пустота + загрузка.
  - **P1 Онбординг: выбор роли мёртвый** → роль персистится, водитель стартует на вкладке «Поездки».
  - **P1 `getAdStats` auth=false** на admin-роуте → статистика рекламы была всегда 0 → `auth=true`.
  - **P2** хардкод-цвета `0x33D93025`/`0xFFEDEDED`→`Canon*`; версия «О приложении» из `BuildConfig`.
  - **🟢 Подтверждено аудитом (не трогал):** SOS (службы+SMS+гео), проверка водителя, поток заявок→отклик→accept, чёрный список в брони/чате, отзывы+модерация, **admin-авторизация (все `/admin/*` реально проверяют роль — P0-дыр нет)**, приватность, создание поездки (дата сохраняется), голос/семья/callback реально доходят до сервера.
- [x] 2026-06-29 **Добивка P1 (6 из 8) + деплой + ВАЖНАЯ правка процесса (Opus, worktree).**
  - ⚠️ **Поймал свою ошибку процесса:** всю сессию собирал/тестил `C:\Users\Bayra\Yuldash\` (главный чекаут, БЕЗ моих правок) вместо worktree → мои Android-правки **ни разу не компилировались**. Перепроверил по-настоящему в worktree: нашёл и починил баг (FAQ `listOf(appText…)` стоял в теле `LazyColumn` — не-composable контекст → вынес выше). Теперь **worktree `:app:assembleDebug` BUILD SUCCESSFUL**, **pytest 65/65** (в папке worktree). Прод-деплой `/bookings/mine` был верным (scp брал worktree-файл, проверено server-side). Урок — в `lessons.md`.
  - [x] **#1 Заказ за близкого** — реальные поля Откуда/Куда (`AddressSuggestField`), имя близкого → `for_relative_name` (добавлен опц. параметр в `createRequest`/`fireCreateRequest`), телефон → комментарий. Кнопка блокируется без маршрута. Убран хардкод «Баймаҡ→Сибай».
  - [x] **#2 Повтор поездки** — `onRepeat` теперь шлёт серверную заявку `fireRequestFromRoute(request.route)` (не только память).
  - [x] **#3 Доверенные контакты** — диалог-форма (имя/кто/телефон) вместо хардкода «Гульназ»; реальные контакты уже грузятся с сервера (`getContacts`), добавление идёт через `fireAddContact`.
  - [x] **#4 «Завершить поездку» закрывает бронь** (бэк `family.py`: `trip-status==done` → `Booking.status=done`, идемпотентно). ✅ Задеплоено + тест `test_finish_trip_closes_booking`.
  - [x] **#5 Дубль брони** (бэк `bookings.py`: повторная активная бронь того же пассажира → возвращаем существующую, мест не списываем; чинит двойной тап). ✅ Задеплоено + тест `test_no_duplicate_booking`.
  - [x] **#8 Простой режим: тач-цель ≥56dp** (`SimpleSmallAction` `heightIn(min=64dp)`) — для пожилых.
  - [x] **#6 Чёрный список прячет водителя из поиска** — опц-авторизация `current_user_optional` (токен есть → знаем юзера, нет → аноним видит всё), `blocked_user_ids` (сет без N+1), `_hide_blocked` на `/rides` (и кеш, и свежее — кеш не портим) и `/rides/near` (до пагинации); клиент шлёт токен (`getRides`/`getNearbyRidesPaged` `auth=true`). ✅ Задеплоено (security/services/rides) + тест `test_blocked_driver_hidden_from_search` (бронь/чат уже блокировали — теперь и выдача).
  - [x] **#7 Ценники поездок на карте** — `YandexMapCard`/`MapHero` получают `rides`; `DisposableEffect(rides)` рисует пины-ценники (`ridePinBitmap`) по «Ближайшим» (макс 20, координаты `cityPoint` синхронно, неизвестный город — пропуск; чистка `onDispose`); `tapListener` ищет тап и в активной, и в ленте → тап открывает карточку. ✅ Android assembleDebug BUILD SUCCESSFUL.
  - **Итог: все 8 P1 из аудита закрыты.** Backend pytest 66/66, Android assembleDebug зелёный, бэк задеплоен и проверен server-side.
  - [x] **P2-добивка (Android, assembleDebug зелёный):**
    - [x] Кабинеты пассажира/водителя — **реальные метрики** вместо демо: пассажир — `getMyBookingsDetailed` (Активные = активные брони) + `getMyRequests` (Заявки) + карточка ближайшей из реальной брони; водитель — `getDriverRides` (Мои маршруты/Свободно = seats_left). Раньше показывало демо-числа/«Рамиль».
    - [x] Админ-экраны Drivers/Reports — состояние «ошибка сети» + «Повторить» (`EmptyStateCard`), `onFailure` больше не маскируется под «пусто».
    - [x] Создание поездки — кнопка «Опубликовать» заблокирована без Откуда/Куда; мест ≥1 (`coerceAtLeast(1)`).
    - [x] «Завершить»/статусы поездки + «поделиться» — теперь **по результату** (`setTripStatus`/`shareTrip` через `voiceScope`): «Завершить» уходит с экрана только при реальном закрытии брони на сервере, при сбое — тост «Проверь сеть»; share показывает «отправлено» только по факту, иначе ошибку. Было fire-and-forget с ложным успехом.
  - [ ] P2 (нерешаемо кодом): Boost по СБП — ручное подтверждение (нужен эквайринг/54-ФЗ — бизнес-решение, у Александра нет юр.лица).
- [x] 2026-06-28 **Логин v2 под стиль онбординга.** `LoginScreen.kt`: `BrandHero` использует `android/app/src/main/res/drawable-nodpi/login_bashkir_telegram_hero.png` — фото из файла Александра `C:\Users\Bayra\Downloads\a432a300-30f2-416a-b204-d8e1f7b4fc73.png` (без текста внутри изображения, с башкирским визуальным колоритом). Тексты входа актуализированы под 6-значный Telegram-код; кнопка входа — фирменная зелёная; `TrustCard` больше не обещает «каждый водитель проходит проверку», а честно пишет про модерацию прав и авто. Скриншот проверки: `android/screenshots/login-user-photo.png`.
- [x] 2026-06-29 **Логин v2: кадрирование hero-фото.** В `LoginScreen.kt` фон `login_bashkir_telegram_hero.png` слегка увеличивался и сдвигался вниз (`graphicsLayer scale 1.07 + translationY 34f`), чтобы памятник Салавату Юлаеву не упирался в статус-бар. Позже в тот же день заменено новым фоном `login_salavat_yulaev_hero.png` без ручного зума/сдвига. Проверка: `:app:assembleDebug :app:assembleRelease` — BUILD SUCCESSFUL.
- [x] 2026-06-28 **Онбординг v2 под стиль входа.** Сгенерирована и подключена картинка `android/app/src/main/res/drawable-nodpi/onboarding_bashkir_hero.png` (дорога по Башкортостану, машина, горы/степь, орнамент, без текста/логотипа внутри изображения). `YuldashApp.kt`: `OnboardingHeroCard` теперь как `BrandHero` на входе — большое изображение, затемнение, маленький логотип в углу, story-плашка и иконка слайда; карточки шагов получили номера. Тексты актуализированы: убрано широкое обещание «подтверждённые участники», оставлено фактическое — Telegram-вход, проверка водителя, скрытый номер, SOS, заявки→отклики→поездка. Проверка: `:app:assembleDebug` и `:app:assembleRelease` — BUILD SUCCESSFUL; release установлен на эмулятор, `pm clear`, первый запуск показывает онбординг. Скриншоты: `android/screenshots/onboarding-v2-final-1.png` … `onboarding-v2-final-4.png`.
- [x] 2026-06-28 **БОЛЬШАЯ СЕССИЯ: продукт-полнота для беты (Opus, всё на проде, всё зелёное pytest 61/61).** ① **Поток заявок замкнут** (был «в никуда»): `RequestResponse` + `/requests/feed`,`/requests/{id}/respond`,`/requests/{id}/responses`,`/responses/{id}/accept`→Ride+Booking; `RequestsFeedScreen`+`ResponsesScreen`. ② **Кабинет админа** (Настройки→единый вход, `isAdmin`): заявка за юзера (`/admin/request-for-phone`), отклики (принять ЗА юзера), модерация водителей (`/admin/drivers/pending`+фото Coil+Bearer), жалобы (`/admin/reports`), реклама. **Автоадмин** по tg-id/телефону. ③ **Профиль:** имя (`/me/update`+поле при входе), аватар (`User.avatar_url`+`migrate_avatar.sql`, `SmallAvatar` везде), онлайн-водитель (`/driver/online`+бейдж). ④ **Заглушки закрыты:** звонок→Telegram (`/callback`), код посадки (`/bookings/{id}/boarding-code`), STT уже был. ⑤ **8 мёртвых кнопок** оживлены + чёрный список/жалобы (`/blocks`,`/reportable-users`). ⑥ **Уведомления админу** о срочных/«помощь»-заявках (`assisted`). ⑦ **Firebase Analytics** (события воронки). ⑧ **Инфра выжата** (воркеры 5+preload, кеш rides/geocode, pool_pre_ping; БЕЗ апгрейда до 5к) + мониторинг (`monitor.sh`→Telegram) + офсайт-S3-бэкап. `.aab` пересобран. **Рекомендация: ЗАПУСК** (Play→юзеры→аналитика). Детали — decisions/architecture/backend/lessons.
- [x] 2026-06-28 **Scale-tier end-to-end + ЗАДЕПЛОЕНО (Opus).** ① **Refresh-токены**: access короткий + refresh ротируемый (хеш в БД) + `/auth/refresh` + logout-ревокация всех refresh; Android авто-refresh на 401 (Mutex, повтор 1 раз). ② **Redis rate-limit**: общий на воркеры (фикс-окно), фолбэк in-memory; на проде Redis уже был (соседняя ветка) → подхватил. ③ **PostGIS**: геокод концов при публикации (`services.geocode_city` → `ride.from_lat/lng,to_lat/lng`) + `ST_DWithin`/GiST-префильтр с haversine-фолбэком; extension+индекс на проде. ④ **load-more**: `limit/offset` на `/rides`,`/bookings/mine`,`/requests/mine`,`/rides/near` + Android `NearbyMoreCard`. **Баг найден+починен:** гонка 2 воркеров gunicorn на `create_all`→`DuplicateTable` → `init_db` идемпотентен (try/except ProgrammingError). Миграции `migrate_geo.sql` + RefreshToken-таблица (auto). **Прод проверен:** workers стартуют, health `db:ok`, refreshtoken+4 geo-колонки+postgis+GiST+redis-ключи живые. pytest **47/47**, Android BUILD SUCCESSFUL. `.env.example`+`deploy-backend.bat` обновлены. Детали — decisions/lessons/architecture-audit.
- [x] 2026-06-28 **Полный рефакторинг + прод-харднинг бэкенда (Opus, worktree) + команда `/architect`.** Монолит `app/main.py` (~1400 строк, ~50 роутов) разрезан: фабрика `create_app()` + 10 доменных роутеров `app/routers/*` + общий `app/services.py` + схемы `app/schemas.py`. **API версионирован** (каждый роут и на корне, и под `/api/v1` — алиас, не перенос). **Харднинг до релиза** (пользователей нет): `app/middleware.py` — rate-limit на IP (общий 300/мин + строгий 20/мин на `/auth`+`/sos`), security-заголовки, access-лог без утечек, единый обработчик ошибок (500 без стека); `/health` пингует БД. + `/version`, `Dockerfile`+`docker-compose.yml`+`.dockerignore`. **Сейфти-нет:** `tests/test_flows.py` — контрактные тесты по всем доменам → pytest **44/44** (было 9). **Долг `datetime.utcnow()` закрыт** (`app/timeutil.py::utcnow()`, warnings 157→1). **Добито до конца:** ① **logout/ревокация токенов** end-to-end — `User.tokens_valid_from`+дробный `iat`+`POST /auth/logout`, `current_user` гасит старые токены; Android `ApiClient.logout()`→сервер (захват токена без гонки); `migrate_logout.sql`; **Android BUILD SUCCESSFUL**. ② **пагинация** `limit/offset` на `/rides`,`/bookings/mine`,`/requests/mine` (обратносовместимо, дефолт=всё, потолок 200). ③ **Alembic починен** — чистый baseline `0001` (create_all из моделей, sqlite+postgres), `upgrade head`==модели; прод-катовер разово `alembic stamp head`. README бэкенда переписан. **Всё зелёное:** pytest 44/44, smoke OK, security OK, паритет OK, прод-гейт OK, Android собран. `deploy-backend.bat` → `scp -r app\` + 4 migrate_*.sql. Промт со скрина → `.claude/commands/architect.md`. ⏳ **Не задеплоено** (worktree) — выкатить после мёржа. Детали — decisions/architecture-audit/lessons.
- [x] 2026-06-27 **Senior-аудит + Фаза 0 безопасности + QA + ДЕПЛОЙ на прод (Opus).** Полный разбор архитектуры → [architecture-audit.md](architecture-audit.md). **Закрыто и задеплоено на прод:** WS-IDOR (чужой чат), перебор OTP (attempts+throttle, alembic `d1e2f3a4b5c6`), маскировка телефонов в логах, овербукинг (`FOR UPDATE`), CORS, длина текста, геокодер-прокси `/geocode` (ключ на сервере), N+1 в выдаче поездок (батч `_drivers_bundle`). **Клиент:** JWT в `EncryptedSharedPreferences`, WS-токен первым сообщением, геокодер через бэк, удалён мёртвый слой `data/` (Models/Repository/MockRepository), дедуп `RideDto`, `key` в списки, `screen` `rememberSaveable`+гард. **QA на эмуляторе нашёл+закрыл КРИТИЧНЫЙ КРАШ:** возврат на карту с подэкрана = `MapKitFactory.setLocale` после `initialize` → вылет; фикс `ensureMapKit` (init раз на процесс). Проверки: smoke · pytest 9/9 · alembic · assembleDebug · эмулятор (поворот/SOS/краш/тёмная тема). Подписанный релиз APK пересобран (`yuldash-release-2026-06-27.apk`). main = `f4f74d7`. Осознанно отложено (риск/данные): полный nav-ViewModel rewrite (46 sites), пагинация (нужен «показать ещё»), REST→Retrofit, PostGIS. Детали и решения — в `architecture-audit.md` + `decisions.md` + `lessons.md`.
  - [x] Android: firebase-messaging, `FcmService` (приём+уведомление), регистрация токена (после входа+старт), `POST_NOTIFICATIONS`. `google-services.json` в `android/app/` (gitignored), плагин активен. Релизный APK пересобран (49 МБ, с Firebase).
  - [x] Бэкенд: `DeviceToken`, `POST /push/register`, `_send_push` (firebase-admin). Хуки: новое сообщение (REST+WS)→другой стороне, новая бронь→водителю. Сервисный ключ на сервере (`/opt/yuldash/firebase-service-account.json`, gitignored, `.env FIREBASE_CREDENTIALS`).
  - [x] Проверено на проде: firebase-admin init OK (project yuldash-9586e), send-конвейер рабочий (фейк-токен→InvalidArgument = дошло до FCM, авторизация ок).
  - [ ] ⏳ Финальный тест на телефоне: поставить новый APK → войти (токен зарегается) → пусть друг напишет/забронирует → придёт пуш.
- [x] 2026-06-27 **Вход упрощён: только Telegram + SMS(выкл).** VK и WhatsApp убраны — оба требуют юр.лицо/бизнес (VK просит ИНН, WhatsApp — Business API), физлицу недоступны, как и SMS. Кнопки VK/WhatsApp удалены с экрана входа, мёртвый код вычищен (`openVKLogin`/`openWhatsAppLogin` + параметры). Бэкенд `/auth/vk-callback`/`/auth/whatsapp-callback` остаются 501 (не зовутся). Telegram-вход (код) — основной и единственный рабочий. Сборка зелёная.
- [x] 2026-06-27 **Релизный билд готов к бете + ужат.** `app-release.apk` **49 МБ** (arm64-фильтр в release, было 145), подписан `CN=Yuldash` V2 — раздавать друзьям напрямую. `app-release.aab` — в Google Play. Содержит все фиксы после аудита (дата, публикация, инбокс, профиль, имена). Debug остаётся универсальным (эмулятор x86_64).
- [x] 2026-06-27 **Realtime-чат по WebSocket (ActiveTripScreen).**
  - [x] Сервер проверен end-to-end через `wss://yulbash.ru/ws/bookings/{id}?token=JWT` (подключение+broadcast+persist). nginx настроен на WS upgrade (`map $http_upgrade` + заголовки + `proxy_read_timeout 3600s`). uvicorn с `websockets 16.0`.
  - [x] Android: OkHttp 4.12.0 (только для WS), `data/ChatSocket.kt` (connect/send/close, ping 20с), `ApiClient.currentToken/wsBase/myUserId`. ActiveTripScreen: история REST + живой приём/отправка WS, оптимистичная отправка с заменой по эхо, фоллбэк REST.
  - [x] Сборка зелёная. ⏳ Полный двусторонний тест (2 телефона) — на бете.
- [x] 2026-06-27 **Telegram-вход переведён на 4-значный КОД (как SMS) — по просьбе Александра.**
  - [x] Поток: app `POST /auth/tg/start`→request_id → открывает `t.me/yuldash_sms_bot?start=<request_id>` → юзер жмёт Старт → бот шлёт **4-значный код** в чат → юзер вводит → `POST /auth/tg/verify` → JWT.
  - [x] Новая таблица `TgAuth` (request_id↔telegram_id↔код, создалась авто через create_all). Код 4 цифры, TTL 5 мин, ≤5 попыток (защита от перебора). request_id — UUID.
  - [x] Надёжнее deep-link: не зависит от возврата `yuldash://` (его убрали — вместе с подписью/return-страницей/манифест-фильтром/PendingAuth).
  - [x] Android: кнопка Telegram → tgStart → открывает бота → карточка переходит в ввод кода (поле 4 цифры, «Войти», «Открыть Telegram ещё раз», «Назад»).
  - [x] **E2E на проде:** start→код «6796»→verify→JWT; неверный код→400; чистка. Сборка зелёная (37s).
- [x] 2026-06-27 **Telegram-вход ВКЛЮЧЁН end-to-end (Opus).** Бот `@yuldash_sms_bot` (deep-link версия — заменена кодом выше).
  - [x] Токен бота в `/opt/yuldash/.env` (`TELEGRAM_BOT_TOKEN`/`TELEGRAM_WEBHOOK_SECRET`, chmod 600, НЕ в git). Александр ротирует токен позже сам.
  - [x] Вебхук зарегистрирован: `https://yulbash.ru/telegram/webhook` (`getWebhookInfo` ok, ошибок нет).
  - [x] Вебхук отвечает методом прямо в HTTP-ответе (`sendMessage` с кнопкой) — без отдельного исходящего вызова. Проверено: исходящий доступ к api.telegram.org с сервера есть, `getMe` ok.
  - [x] `YULDASH_TELEGRAM_BOT=yuldash_sms_bot` в `local.properties` (gitignored) → `BuildConfig.TELEGRAM_BOT`. Кнопка открывает реального бота.
  - [x] **E2E на проде:** вебхук без секрета→403; /start→подписанная кнопка; return-страница→deeplink; callback с подписью→200+JWT; подделка→401; тест-юзер удалён. Всё зелёное.
  - [x] Android собран, `BuildConfig.TELEGRAM_BOT=yuldash_sms_bot`. Осталось Александру: проверить на телефоне (открыть бота, Старт → вернуться в приложение вошедшим).
- [x] 2026-06-27 **SMS заморожен, вход через мессенджеры — основной (Opus).**
  - [x] Причина: sms.ru заключает договор только с юр.лицом/ИП → у Александра нет → SMS отпадает.
  - [x] **Заморозка, код цел и оживляется одним флагом:** `BuildConfig.SMS_LOGIN_ENABLED` (из `YULDASH_SMS_LOGIN` в local.properties, по умолч. **false**). При false — SMS-форма скрыта, вход только мессенджеры. Оживить позже: `YULDASH_SMS_LOGIN=true` + на сервере `SMS_PROVIDER=smsru` + ключ. Логика входа по SMS не удалена.
  - [x] Backend: `validate_production` больше НЕ требует SMS в проде (mock допустим = заморожен). `request_code` в проде+mock → понятный 503 «Войдите через мессенджер» (не 500). Задеплоено, health ok.
  - [x] UI `LoginFormCard`: мессенджеры (Telegram/VK/WhatsApp) — крупными кнопками вверху как основной вход; «Войти по номеру телефона» — за флагом, свёрнуто.
  - [x] Реальность каналов для физлица: **Telegram** ✅ (бот бесплатный, готов), **VK** ✅ (приложение бесплатно, серверную часть достроить), **WhatsApp** ⚠️ нужен WhatsApp Business API (та же стена что SMS — платно/бизнес). До этого кнопки показывают «скоро».
  - [x] Сборка зелёная (46s).
- [x] 2026-06-27 **Telegram-вход безопасно + закрыты дыры авторизации (Opus).**
  - [x] **Security-фикс (критично):** `/auth/whatsapp-callback` отдавал токен по чужому номеру → **угон аккаунта**. Отключён (501). `/auth/vk-callback` тоже отключён (501) до настоящего OAuth. Задеплоено — проверено curl.
  - [x] **Telegram-вход через бота, с подписью:** бот-вебхук `/telegram/webhook` берёт подтверждённый Telegram'ом `from.id`, подписывает HMAC (`sign_telegram`), отдаёт кнопку-возврат через `/auth/telegram/return` → app шлёт на `/auth/telegram-callback`, сервер **проверяет подпись** (`verify_telegram`) → JWT. Войти за чужой telegram_id нельзя. Юнит-проверка подписи: валид принят, подделка/протухание/пусто отбиты.
  - [x] Конфиг `TELEGRAM_BOT_TOKEN`/`TELEGRAM_WEBHOOK_SECRET` (`.env`, не в git), `.env.example` обновлён.
  - [x] Android: `telegramCallback` шлёт `auth_date`+`sig`; приёмник прокидывает их из DeepLink.
  - [x] Задеплоено: telegram-callback без подписи → 401, whatsapp/vk → 501, health ok.
  - [x] Инструкция для Александра: **[telegram-setup.md](telegram-setup.md)** (3 шага, ~10 мин).
  - [x] Сборка `assembleDebug` → BUILD SUCCESSFUL.
  - [ ] ⏳ **Чтобы включить:** Александр регистрирует бота у @BotFather + 3 шага из telegram-setup.md.
- [x] 2026-06-27 **Перепроверка (Opus) + фиксы OAuth/WebSocket + клиент-петля.**
  - [x] **Нашли при ревью:** `verify_token` не было в `security.py` (WS-чат всегда отбивал токен); миграции `ALTER TABLE user` без кавычек (синтакс-баг PG); мои deploy-инструкции с выдуманными кредами `yuldash_app/yuldash_prod`.
  - [x] **Починили:** `verify_token` добавлен; `"user"`+`IF NOT EXISTS` в миграциях; миграции вшиты в `deploy-backend.bat`; удалён кривой дубль.
  - [x] **Задеплоено на yulbash.ru:** колонки `telegram_id`/`vk_id`/`whatsapp_verified` есть, эндпоинты `/auth/*-callback` живы (curl вернул токен), сервис active.
  - [x] **OAuth-петля замкнута на клиенте:** `MainActivity` ловит DeepLink `yuldash://auth/<provider>` (`onNewIntent`+холдер `PendingAuth`) → `ApiClient.telegramCallback/vkCallback/whatsappCallback` → логин. Манифест расширен на все auth-пути.
  - [x] **Конфиг вместо хардкода:** `BuildConfig.TELEGRAM_BOT`/`VK_APP_ID` из `local.properties`; пусто → тост «скоро» (не ломаный поток). Тосты двуязычные.
  - [x] **Честно:** WhatsApp-вход через `wa.me` невозможен (не возвращает личность) → заглушка «скоро», нужен WhatsApp Business API.
  - [x] Сборка `assembleDebug` → **BUILD SUCCESSFUL in 1m14s**.
  - [ ] ⏳ **Нужно от Александра, чтобы вход реально заработал** (см. ниже «Блокеры OAuth»).
- [x] 2026-06-27 **WebSocket чат — реальное время.**
  - [x] Backend: `/ws/bookings/{booking_id}` эндпоинт, ConnectionManager, broadcast.
  - [x] Token верификация через query параметр (`verify_token` — починен Opus 27.06).
  - [x] Android: требует OkHttp для WebSocket (позже).
  - [x] Коммит: `97bc7a2`.

### Авторизация — итог (2026-06-27)
- **Telegram:** ✅ ВКЛЮЧЁН, end-to-end на проде (бот `@yuldash_sms_bot`, код входа; текущий Android-инпут — 6 цифр). Единственный рабочий вход.
- **SMS:** заморожен (нет юр.лица для sms.ru). Код цел, оживить: `YULDASH_SMS_LOGIN=true` + `SMS_PROVIDER=smsru` + ключ.
- **VK:** ❌ убран. Требует ИНН (бизнес-аккаунт) + только VK ID с PKCE (сложно). Физлицу недоступен. Эндпоинт 501.
- **WhatsApp:** ❌ убран. Требует WhatsApp Business API (платно/бизнес). Эндпоинт 501.

- [x] 2026-06-27 **OAuth вход: Telegram/VK/WhatsApp — полный выбор.**
  - [x] **Android:** 3 кнопки входа (голубая Telegram, синяя VK, зелёная WhatsApp); openTelegramLogin() + openVKLogin() + openWhatsAppLogin().
  - [x] **Backend API:** /auth/telegram-callback, /auth/vk-callback, /auth/whatsapp-callback; JWT автологин.
  - [x] **БД:** User модель +telegram_id +vk_id +whatsapp_verified поля; миграции migrate_oauth.sql + migrate_whatsapp.sql.
  - [x] **Причина:** физ.лицо не может договор с SMS → социальные сети; WhatsApp у большинства.
  - [x] Сборка: `gradlew assembleDebug` ✅ зелёная.
  - [x] Коммиты: `9936c5d` + `93b6efd`.

- [x] 2026-06-27 **Telegram/VK вход + полная архитектура системы.**
  - [x] `docs/system-design.md`: архитектура на миллионы DAU (3-слойная, PostgreSQL 20+ таблиц, REST API спецификация, WebSocket plan, DevOps масштабирование).
  - [x] Платежи переосмыслены: только для донатов + Boost, сама поездка платится наличными/переводами.
  - [x] Коммит: `b1850d9`.

- [x] 2026-06-27 **Точка сбора на карте.** Водитель: пикер (Яндекс-карта + центр-пин) из «Создать поездку»; координаты в `Ride.pickup_lat/lng` (миграция). Пассажир: «Открыть точку на карте» (geo-интент). Проверено end-to-end на эмуляторе. Коммит `337e02c`.
- [x] 2026-06-26 **Ценовой ориентир по маршруту.** `GET /rides/price_hint` (среднее цен, price>0). В «Создать поездку» чип «Обычно по маршруту ~N ₽» (тап → ставит цену), появляется при вводе откуда+куда. Подсказка, не навязываем. Коммит `a2a32e8`.
- [x] 2026-06-26 **«Позвать соседа» (шэр поездки).** В деталях поездки иконка Share → системный chooser с текстом маршрута + ссылка yulbash.ru. Двуязычно, переиспользует shareRide(). Коммит `8cf486a`.
- [x] 2026-06-26 **Место встречи (точка сбора).** `Ride.pickup` (миграция). Водитель в «Создать поездку» поле «Где встречаемся»; пассажир видит на экране брони (раньше хардкод «Автовокзал, вход 2» → теперь реальное, пусто=«Уточнить у водителя»). Коммит `4e2effa`.
- [x] 2026-06-26 **Типы поездки + бесплатная модель.** Виды помощи (НЕ тарифы): Пассажиры/Посылка/Груз/Больница. Бэкенд `RideCategory`+`cargo`. Селектор типа в «Создать поездку» (иконки) + «Груз» в категориях заявки; `publishRide` шлёт category. Бейдж типа на карточке «Ближайших» (если не обычная). «Цена»→«Бензин, ₽» (по желанию, поездки бесплатные). Детское кресло — премиум-переключатель (было). Коммит `aec9bb4`.
- [x] 2026-06-26 **Регулярные (повторяющиеся) поездки.** `Ride.recurrence` (none/weekdays/daily/weekly), `create_ride` создаёт ближайшие 4 рейса серии (миграция применена). Android: чипы «Повтор» в «Создать поездку». Коммит `255467b`.
- [x] 2026-06-26 **Отмена поездки + правило.** `POST /bookings/{id}/cancel` (статус cancelled, место возвращается). Android: кнопка «Отменить поездку» + правило + диалог в активной поездке. Коммит `73c1838`.
- [x] 2026-06-24 **Маршрут по дорогам (full MapKit SDK + DrivingRouter).** lite→full, для активной поездки маршрут строится по дорогам (DrivingRouter), фоллбэк на прямую при ошибке/нет квоты роутинга. ⚠️ Нужна включённая маршрутизация (Directions) на ключе Yandex — иначе прямая линия. Коммит `bd3ff75`.
- [x] 2026-06-24 **Кабинет рекламы → реальная статистика на сервере.** Таблица `AdEvent` + `POST /ads/{id}/event` + `GET /ads/stats` (развёрнуто). Android: `fireAdEvent` в трекинге показов/кликов; кабинет тянет `/ads/stats` и показывает реальные числа. Проверено (кабинет: «Всего показов 2 · CTR 50%»). Коммит `4ee68be`.
- [x] 2026-06-24 **Зелёный календарь и в «Создать поездку»** (водитель) — тот же пикер, что в заявке. Коммит `006c633`.
- [x] 2026-06-25 **Голосовая заявка: `voice_url` + `transcript` сохраняются на сервере (end-to-end).** Бэкенд: поля `voice_url`/`transcript` в `RideRequest` (models.py) + `RequestIn`/ответ `RideRequest` (main.py) — `POST /requests` принимает и персистит, `GET /requests/mine` возвращает. Android (`MainActivity.kt`, `ApiClient.kt`): голосовая заявка шлёт ссылку на запись + распознанный текст. `smoke.py` расширен явной проверкой round-trip (заявка с `voice_url`+`transcript` → проверка в ответе и в `/requests/mine`). **Проверка:** `python smoke.py` → `voice request: 6 voice_url+transcript persisted` → `SMOKE OK`. ⚠️ Android debug-сборку на этой машине запустить нельзя (`JAVA_HOME` не задан, `java` не в PATH) — собрать на ПК Александра через `build-debug.bat`.
- [x] 2026-06-24 **Звонок оператору через конфиг.** `YULDASH_SUPPORT_PHONE` (local.properties→BuildConfig). CallbackHelp: номер задан → «Позвонить в поддержку» (`ACTION_DIAL`); пусто → прежнее «Попросить звонок». ⚠️ Александру: вписать номер в local.properties. Коммит `ac16472`.
- [x] 2026-06-24 **Водитель оценивает пассажиров.** Бэкенд `GET /driver/bookings` (брони на поездки водителя: имя/рейтинг пассажира, маршрут) — развёрнут. Android: `getDriverBookings`/`DriverBookingDto` + секция «Пассажиры — оцените» в кабинете водителя (5 звёзд → `rateBooking`). Двусторонний рейтинг закрыт. Коммит `f873dca`.
- [x] 2026-06-24 **Чат: вкладки «Активные»/«Заявки» фильтруют.** Было — не влияли (все диалоги). Стало: Активные → /conversations+голосовые; Заявки → `getMyRequests` (реальные заявки юзера) + пусто-состояние; Система → уведомления. `myRequests` + `when(selected)`. Коммит `7d8d39c`.
- [x] 2026-06-24 **Чат: композер только на «Активные» + подпись.** Был всегда виден и слал в последнюю бронь без контекста. Теперь только на «Активные», с подписью «Сообщение по активной поездке». Коммит `58e1dff`.
- [x] 2026-06-24 **Голосовая/семейная заявка → на сервер.** Было: только `localRequests.add` (в памяти). Хелпер `fireRequestFromRoute` (парсит «Откуда → Куда» → `fireCreateRequest`) в 3 местах (распознанный текст, голос+upload, «за близкого»). Сборка зелёная, на эмуляторе. Коммит `cf37db1`.
- [x] 2026-06-24 (Cowork) **Фильтр поиска по условиям — на «Ближайших поездках» (Карта), СБОРКА ЗЕЛЁНАЯ.** Переключаемые чипы (только женщины / детское кресло / с животным / багаж) над списком; клиентская фильтрация загруженных `RideDto` (поля уже приходят с сервера); счётчик «N рядом» считает отфильтрованные; ветка «нет поездок с такими условиями». Новое: `NearbyFilterChip`, состояние `prefFilter`, `shownNearby`. `BUILD SUCCESSFUL 1m17s`, exit 0. Свежий APK переустановлен на эмулятор. (Чипы видны, когда «Ближайшие» не пусты.)
- [x] 2026-06-24 (Cowork) **Реклама: реальное действие по клику** (было — только всплывашка `title: button`). `trackAdClick`: телефон в `contact` → звонилка (`ACTION_DIAL tel:`), иначе координаты `mapPoint` → карта (`geo:`), иначе подсказка; try/catch если нет приложения. Сборка зелёная (`BUILD SUCCESSFUL 1m15s`, exit 0). ⏳ Осталось из мелочи: фильтр поиска по условиям (нужен аккуратный заход в сложный `RidesScreen`), фильтр вкладок чата.
- [x] 2026-06-24 (Cowork) **Бэкенд ЗАДЕПЛОЕН на прод `yulbash.ru`.** Залиты `main.py`+`models.py`, миграция БД (`migrate_premium.sql`: 9× `ALTER TABLE` — 6 колонок `ride` + 3 `driverprofile`), `chown yuldash`, рестарт `yuldash-api` → **active**, серверный `curl localhost:8000/health` → `{"status":"ok","env":"prod"}`, **DEPLOY OK (exit 0)**. Теперь премиум-поля поездки и эндпоинты `/driver/*`, `/upload/photo` живут на сервере → Android-фичи работают по-настоящему. Скрипты: `backend/deploy-backend.bat` (scp+migrate+chown+restart), `backend/migrate_premium.sql`, `backend/verify-server.bat`. ⚠️ Публичный `curl` с ноута к `yulbash.ru` = `000` (ТСПУ режет curl.exe с Windows; приложение/эмулятор сервер видят) → проверять прод-API **server-side через ssh**, не Windows-curl.
- [x] 2026-06-24 (Cowork) **Проверка водителя — настоящий экран вместо заглушки, СБОРКА ЗЕЛЁНАЯ** (`BUILD SUCCESSFUL 1m30s`, exit 0). `VerifyDriverScreen` переписан: поля авто (марка/модель/цвет/госномер/мест), загрузка фото прав и авто из галереи (`GetContent`→`uploadPhoto` base64→`/upload/photo`), «Отправить на проверку» (`setDriverProfile`+`submitDriverVerify`→pending), статус-баннер (нет/на проверке/подтверждён/отклонён) из `getDriverStatus`. Новые методы в `ApiClient` + `DriverStatusDto`, хелперы `StatusBanner`/`UploadTile`. Старые `DocumentRow`/`StepDot` стали неиспользуемыми (warning). ⚠️ Полностью заработает после деплоя бэка; без деплоя экран открывается, статус=none, аплоад вернёт ошибку — без краша. Модерация: `POST /admin/drivers/{id}/moderate` (роль admin).
- [x] 2026-06-24 (Cowork) **Премиум-предпочтения поездки — Android END-TO-END, СБОРКА ЗЕЛЁНАЯ** (на ПК Александра через `build-debug.bat`: `BUILD SUCCESSFUL in 1m17s`, exit 0). Поля в UI-модели `Ride` + `RideDto` + маппинг `toUiRide` + `publishRide`/`firePublishRide`. Чипы условий (`RidePrefChips`/`PrefChip`) в `FullRideCard`. Тумблеры в «Создать поездку» (`PrefToggleRow`): только женщины, детское кресло/бустер, животные, багаж, кондиционер, курение. Демо-поездки засеяны флагами (видно сразу). Серверные поездки покажут флаги после деплоя бэка. Иконки: Woman/ChildCare/Pets/Luggage/AcUnit/SmokingRooms (material-icons-extended). ⏳ Осталось: фильтр по условиям в поиске (UI), экран загрузки документов водителя. BA-строки чипов/тумблеров — в разделе «Переводы на проверку».
  - **Аудит:** `docs/audit.md` — карта по каждому экрану (реально/имитация/сломано/блокер). Главное: приложение готово ~на 70%; единственная настоящая заглушка — проверка водителя; распознавание речи/запись голоса оказались РЕАЛЬНЫМИ (доки врали); оплата СБП — на доверии; звонка оператору нет; CTA рекламы пустой.
  - **Бэкенд (локально, НЕ задеплоено на yulbash.ru):** [⚠️ УСТАРЕЛО — позже ЗАДЕПЛОЕНО, см. запись «Бэкенд ЗАДЕПЛОЕН на прод» выше] ① премиум-предпочтения поездки — поля `pets_allowed/child_seat/women_only/smoking/baggage/air_conditioner` в `Ride` (models.py) + `RideIn`/`RideOut` + фильтры в `GET /rides` (main.py). ② настоящая проверка водителя: `POST /upload/photo` (base64→media/docs), `POST /driver/profile` (реальное авто вместо хардкода Kia Rio), `POST /driver/verify` (→ docs_status=pending), `GET /driver/status`, `POST /admin/drivers/{id}/moderate` (админ → verified). `DriverProfile` получил `license_url/car_photo_url/verify_submitted_at`. `smoke.py` расширен (premium round-trip + driver verify + модерация).
  - ⚠️ **Проверка:** базовый `smoke` зелёный (стек ок); НОВЫЕ пути НЕ прогнаны в песочнице из-за mount-кэша (см. lessons.md) → прогнать `smoke.py` на ПК / в свежей сессии / после деплоя.
  - ⚠️ **Деплой:** изменения бэка нужно выкатить на `yulbash.ru` (SSH-ключ на ноуте Александра).
  - ⚠️ **Миграция Postgres:** `SQLModel.create_all` НЕ добавляет колонки в существующие таблицы → на проде нужен `ALTER TABLE` для новых полей `ride`/`driverprofile` (или пересоздать таблицы в dev).
  - ⏳ **Android-сторона** премиум-фич (чипы предпочтений в CreateRide, в карточках поездок, в фильтрах поиска) + UI загрузки документов водителя — НЕ начаты (требуют сборки на ПК Александра).
- [x] 2026-06-24 **Реальные рейтинги (отзывы после поездки).** Было: рейтинг — статичный сид (мок), отзывов нет, рейтинга пассажира нет. Стало end-to-end: модель `Rating` + `POST /bookings/{id}/rate` (пассажир→водитель, водитель→пассажир); рейтинг = среднее реальных оценок (до отзывов — сид); `RideOut.driver_rating` живой; `/me` отдаёт свой рейтинг. Android: `rateBooking`, ActiveTrip «Оцените водителя» (5★→POST), Профиль — свой рейтинг. Проверено: 4★→4.0, +5★→4.5 (БД avg), карточка реальная. Коммит `1b7deb8`.
- [x] 2026-06-24 **Единый жёлтый: навбар выбранной вкладки = «Я водитель» (CanonGold).** Пилюля навигации была бледный CanonYellow → CanonGold (как кнопка). Коммит `249118d`.
- [x] 2026-06-24 **Аудит кнопок «Ближайших» + убраны заглушки в Booking.** Флоу проверен end-to-end (с реальным токеном): nearby «Поехать» → BookingScreen → подтверждение → POST /bookings → ActiveTrip (статус/чат/поделиться/SOS реальные). Фиксы: карта Booking была хардкод «Баймаҡ→Сибай 43км» → реальный маршрут поездки (Темясово→Уфа 250км, `cityDistanceText`); аватар «Р» → буква имени водителя. Бронь требует входа (DEV-обход без токена → 401). Коммит `e2b8728`. ⚠️ Остаток: «Место встречи: Автовокзал, вход 2» и «Опытный водитель» — текст-плейсхолдеры (нет поля в БД, нужен бэкенд).
- [x] 2026-06-24 **Карточка ближайшей поездки ужата** 190→152dp (паузы стянуты: spacedBy 8→6, padding верт 14→12, аватар 34→30, кнопка 38→36). Цена/«Поехать» полностью над нав-баром. Коммит `56d9313`.
- [x] 2026-06-24 **Радиус-фильтр «Ближайших» (50 км).** `NEARBY_RADIUS_KM=50` (константа) → `/rides/near` отсекает поездки с точкой выезда дальше 50 км от клиента (когда есть позиция). Дистанция <1 км → «рядом». Проверено: клиент в Сибае → Учалы (184 км) отсечён, 3→2. Коммит `8a30bc3`.
- [x] 2026-06-24 **Ближайшие поездки: маршрут клиента + сортировка по времени + гео-радиус (end-to-end).** Бэкенд `GET /rides/near` (фильтр маршрут, haversine-дистанция от клиента, фильтр радиус, сорт. по depart_at ↑, count+distance_km; координаты городов БашРТ), развёрнут. Android: `getNearbyRides`+`RideDto.distanceKm`, `LocationPrefs.lastLat/lng` из карты, focusRoute=activeTrip; `NearbyRideCard` 1-в-1 (290×190), «ближайшая» на ранней, «N км», skeleton/empty. Сценарий «водитель сломался → ближайшая машина». Проверено: эмулятор (3 рядом, 244/287 км из Уфы) + curl. Коммит `b0f159a`.
- [x] 2026-06-24 **Компактнее «Ближайшие поездки».** Заголовок секции 18→16sp, счётчик «N рядом» 13sp, заголовок карточки поездки 22→17sp (из-за него бейдж «Проверен» не влезал). «4 рядом» = `rides.size` — **реальное** (с сервера `/rides`, демо офлайн). Коммит `1a383e4`.
- [x] 2026-06-24 **Имя клиента вместо хардкода «Байрас».** `ApiClient.cachedName()` (имя вошедшего в prefs, из `verifyCode`/`/me`) → приветствие шапки + хедер профиля (имя + буква-аватар); фоллбэк «Байрас» когда не вошёл. `logout` чистит. Проверено seed'ом (Алмаз). Коммит `da7047e`.
- [x] 2026-06-24 **Кнопка «Я водитель» — золотая** (бренд-акцент, выделить). Новый токен `CanonGold` (золото в обеих темах) + `CanonGoldInk` (фикс. тёмно-зелёный текст). Пара с зелёной «Найти». Коммит `f25423f`.
- [x] 2026-06-24 **Приветствие по времени суток.** `timeGreeting()` по часу: утро (5-10)/день (11-16)/вечер (17-22)/ночь (23-4) вместо захардкоженного «Доброе утро». Двуязычно. Проверено сменой часов эмулятора (все 4 периода). Коммит `08651ea`.
- [x] 2026-06-24 **Лента: реальные цифры с сервера + 6-я карточка (год).** Бэкенд `GET /feed` (поездок за день/неделю/месяц/год + топ-маршрут недели + водители), развёрнут на yulbash.ru. `ApiClient.getFeed()`/`FeedDto`; `MapHero` тянет раз в 60с; `mapFeedFrom(popular, feed)` — реальные числа, офлайн → демо. 6 карточек (день/неделя/месяц/год + факт + маршрут), русский плурал `plRu`. Числа малы (прод почти пустой) — честные, растут с трафиком. Проверено на эмуляторе + curl. Коммит `15a1fc8`.
- [x] 2026-06-24 **Лента-карусель на карте: единый макет + 5 карточек микса.** Было — карточки разного размера, только маршруты. Стало — `MapFeedCard`/`mapFeedFrom`, единый макет (бейдж+пилюля·заголовок фикс.высоты·подпись+точки), 5 карточек по кругу: Популярно (маршрут), Сегодня (142 поездки/день), Хит недели (топ 320×), Факт (78% домой), Сообщество (4 700/мес). Периоды день/неделя/месяц. Реальные числа — бэкенд позже (шов mapFeedFrom). BA-строки → на проверку. Проверено на эмуляторе. Коммит `05cbbeb`.
- [x] 2026-06-24 **Тумблер день/ночь в шапке.** Кнопка-иконка (луна/солнце) между «Куда поедем?» и SOS → переключает тему всего приложения + карты вручную (override системной). `ThemePrefs.darkOverride` + `appIsDark()`, `YuldashTheme(darkTheme=)`, persist в SharedPreferences (переживает перезапуск). Двуязычный contentDescription. Проверено на эмуляторе (свет→тьма→перезапуск). Коммит `23efc00`.
- [x] 2026-06-24 **Карта/Профиль доводка (4 правки Александра):** ① зум `+/-` (top→14) и FAB «к себе» (top→100) в верхний правый угол; ② «Простой режим» (карточка-тумблер `SeniorAccessCard`) убран с Карты → в Профиль, секция «Для родителей и близких» (включать только там); ③ на Карте вместо Простого режима — кнопки «Найти/Я водитель» (под картой); ④ карта выше 280→350dp. Проверено на эмуляторе (Карта + Профиль). Коммит `12ee9dc`.
- [x] 2026-06-24 **Карта компактнее + кнопки отдельным блоком под картой.** Высота карты 430→280dp (занимала много места) → низ (Простой режим, Ближайшие поездки) поднят, виден без прокрутки. Кнопки «Найти поездку»/«Я водитель» вынесены из-под карты в отдельный блок ПОД ней (не плавают, как Яндекс); подсказка-карусель плавает в низу карты. FAB «к себе» поднят (top 108) под короткую карту. Проверено на эмуляторе. Коммиты `9713e9c`, `0a1ffa3`.
- [x] 2026-06-24 **Карта: маршрут только для активной поездки + флажок назначения (как Яндекс Такси).** Браузинг-карта чистая (без ценников); маршрут+ценник+флажок показываются ТОЛЬКО для подтверждённой поездки (`activeTrip`): линия from→to + ценник у отправления (под городом) + `destFlagBitmap` на финише + камера охватывает маршрут. Завершил (статус done) → `activeTrip=null` → всё исчезает (`DisposableEffect.onDispose`). Ставится в `onConfirmRide`, сбрасывается через `onTripEnd`. Протянут Home→Map→MapHero→YandexMapCard. Убраны стале-`MapMarkerHitTargets`. Коммиты `2e545fe`, `645f35f`. ✅ Проверено на эмуляторе: карта чистая без поездки; «Мои поездки → Подтверждена → Подробнее» → ActiveTrip + на Карте рисуется зелёная линия Баймаҡ→Сибай + флажок назначения. `onOpenActiveTrip` (Home→Rides) ставит activeTrip. Завершение (done) чистит. Кластеризация не нужна — на карте max ОДНА активная поездка.
- [x] 2026-06-24 **Карта: ценник под городом + линия по реальным заказам.** ① `ridePinBitmap` — остриё наверх + якорь `(0.5,0)` → цена ПОД названием города (не перекрывает). ② Зелёная линия: убран хардкод `Баймаҡ→Сибай` + 2 демо-круга; теперь для каждой поездки линия `from→to` по реальным городам (`cityPoint`+геокодер с кэшем для обоих концов), ценник у города отправления. Коммит `d378e93`. (На будущее для «идеала»: кластеризация при нескольких заказах из одного города — сейчас пилюли могут наложиться; маркер пункта назначения — опционально.)
- [x] 2026-06-23 **Карта закреплена (жесты гарантированы).** Было: вся `MapScreen` = один `LazyColumn`, карта-`AndroidView` внутри прокрутки → вертикальный пан конфликтовал со скроллом (мог залипать; на эмуляторе синтетика вообще не двигала карту). Стало: шапка+карта в фикс. `Column`, низ (Простой режим, Ближайшие, реклама) в отдельном `LazyColumn(weight 1f)`. Карта вне скролла → пан во все стороны + зум без конфликта (паттерн Яндекс/Uber). Проверено на эмуляторе: вертик.пан двигает тайлы, низ скроллится не трогая карту. Коммит `ba24d22`. (У Александра нет Android-телефона → проверка только на эмуляторе, потому архитектурно убрал конфликт, а не полагался на `requestDisallowIntercept`.)
- [x] 2026-06-23 **Карта: кнопки поездки постоянны + карточка-подсказка сворачивается.** UX-разделение (по совету дизайнера, выбор Александра): «Найти поездку»/«Я водитель» вынесены из карточки в **постоянную панель внизу карты** (ядро — всегда видно). Карточка «Популярный маршрут» = чистая подсказка (карусель без кнопок): свайп вправо → язычок-шеврон у правого края (`AnimatedVisibility` slide+fade), тап → назад. Карусель `userScrollEnabled=false` (авто-прокрутка), чтобы горизонт.свайп = сворачивание. «Найти» берёт текущий маршрут карусели (`onRouteChange`→`activeRoute`). Проверено на эмуляторе (свайп→язычок→тап→назад, кнопки не прячутся). Коммит `45486cf`. ⚠️ Башкирский черновик: «Популяр маршрутты күрһәтеү» (показать популярный маршрут).
- [x] 2026-06-23 **Щипок-зум пальцами держит точку.** `CameraListener` (reason=GESTURES, finished): при изменении zoom (щипок) камера возвращается на мою точку — как кнопки. Панорама пальцем свободна. Убран хрупкий `followUser` (OnTouchListener не получает MOVE/UP после false на DOWN — мёртвая логика). Кнопки/FAB центрируют (проверено). Коммит `d830974`. (Щипок проверять на устройстве — adb не воспроизводит.)
- [x] 2026-06-23 **Зум держит точку по центру.** Было: зум вокруг `cam.target` + recenter со смещением `-0.0022°` → при зуме смещение в пикселях растёт, точка уезжала вверх/за экран. Стало: при вкл. геолокации зум `＋/−` и центрирование целятся ТОЧНО в `lastUserPoint` (без смещения) → точка остаётся по центру на любом зуме. Проверено (зум+×2, зум-×3). Коммит `274db03`. (Кнопки; pinch-жест зумит вокруг пальцев — by design.)
- [x] 2026-06-23 **Кнопка «к себе» — надёжное центрирование.** Было: FAB пересоздавал весь `DisposableEffect` (`recenterTick`) → центрирование зависело от нового GPS-фикса, ломалось после зума/пана. Стало: храню `lastUserPoint` (обновляется на каждом фиксе), FAB напрямую `map.move` на неё (zoom 15). Проверено: отдалил+сдвинул → тап → вернулось на точку. Коммит `c9aedb6`.
- [x] 2026-06-23 **Карта-финиш: чистка + своя метка геопозиции.** ① Убраны фейк-плашки `Баймаҡ`/`Сибай` (хардкод-ярлыки) + чип `«43 км»` с реальной карты — карта показывает только живые данные + контролы (`05ecce2`,`6517d0a`). ② Метка «я тут»: отказался от `UserLocationLayer` — MapKit lite НЕ даёт перекрасить стрелку-курс (эмулятор всегда в режиме движения → дефолтный тёмный треугольник; спрятать стрелку = метки нет вовсе). Решение: рисую СВОЙ `PlacemarkMapObject` (зелёный кружок `userPuckBitmap`) через android `LocationManager` (GPS+NETWORK), обновляю `geometry` по фиксам, центрирую на первом фиксе (цель ~-0.0022° южнее → точка над нижней плашкой). Всегда зелёный кружок, без треугольника, и стоя и в движении. Проверено на эмуляторе (Уфа). Коммиты `e7d4ae9`,`bca95dd`,`135c4bc`. ⚠️ Запись «Геолокация v2» ниже частично устарела: там был `UserLocationLayer`+`RotationType.ROTATE` (стрелка) — ЗАМЕНЕНО своим плейсмарком.
- [x] 2026-06-23 **Геолокация v2 (как Яндекс):** ① стрелка по курсу — `getArrow()`=бренд-стрелка `userArrowBitmap` с `RotationType.ROTATE` (крутится по движению), `getPin()`=точка на стоянке; ② кнопка «к себе» FAB (`NearMe` в круге, правый верх под зумом) → центр на текущей позиции, если выкл — включает; `zIndex(6)` чтобы не перехватывал `MapMarkerHitTargets`; ③ плашку «Геолокация скрыта» убрал с карты → **Профиль → Конфиденциальность** (новый `Screen.Privacy`): свитч «Моя геолокация» + пояснение; общий флаг `LocationPrefs.sharingEnabled` (двусторонняя связь карта↔профиль). Проверено на эмуляторе (geo fix Уфа): FAB центрирует + зелёная стрелка; свитч ON↔OFF синхронен. Коммит `364449b`. ⚠️ **Башкирский черновик (на проверку):** «Хосусилыҡ» (конфиденциальность), «Минең геолокация», «Картала минең нөктәне күрһәтеү», «Мин ҡайҙа» (где я), «Геолокация башта йәшерелгән».
- [x] 2026-06-23 **Карта-полировка (по запросам Александра):** бесконечная карусель маршрутов (виртуальный pageCount, всегда вперёд + автообновление 45с под актуальные поездки); камера южнее — маршрут над плашкой; кнопки масштаба ＋/− (Yandex-стиль, 38dp, внутри YandexMapCard); плашка маршрутов плотнее (/ui-ux-pro-max: зазор 8→5, lineHeight 22, чипы ниже); **реальная геолокация** — плашка «Геолокация скрыта» стала тумблером: тап → запрос разрешения → Yandex `UserLocationLayer` (точка «я тут»), повторный тап — скрыть; приватность по согласию, дефолт ВЫКЛ. Всё проверено на эмуляторе. Коммиты `cc9122a`,`ad0eebd`,`a039188`,`f0835e6`,`63ae73b`,`feae5e6`. ⚠️ **Башкирский черновик (на проверку):** «Геопозицияң күренә» (геопозиция видна), «Геопозицияны күрһәтеү/йәшереү» (показать/скрыть геопозицию). 🛠 **Урок:** Git-Bash MSYS портит пути `adb shell ... /sdcard/...` → ставь `MSYS_NO_PATHCONV=1` (иначе `uiautomator dump`/`pull` молча мимо).
- [x] 2026-06-23 **Все моки → реально (end-to-end, по порядку):** ① 🆘 SOS реально шлёт SMS доверенным (`/sos`→`_send_text`, лог `contacts_notified`, фоллбэк-лог до одобрения sms.ru); ② 📲 статусы поездки близким (`/bookings/{id}/trip-status`); ③ 💬 инбокс чатов (`/conversations`, тап→чат поездки через ActiveTrip); ④ 🔔 уведомления (`/notifications` из входящих сообщений); ⑤ 🗺 популярные маршруты (`/popular-routes` из реальных поездок); ⑥ 📢 реклама сервер-управляемая (`/ads`, демо-шаблон оформления); ⑦ 📍 маркеры карты по реальным координатам (14 городов РБ в `cityPoint` + Яндекс-геокодер с кэшем по городу = экономия квоты); ⑧ частые поездки из истории (`/my-routes`). Везде демо-фоллбэк (новый юзер/офлайн). `trustedContacts`/`rides` уже были серверными. Все эндпоинты curl-проверены (бронь→сообщение→инбокс; SOS→`contacts_notified=1`+SMS контакту), сборки зелёные. Коммиты `9d822e2`,`f2fe3bb`,`4bb5411`,`b2bb982`,`4427b8d`. ⚠️ **Черновой башкирский (на проверку Александру):** реклама `titleBa`/`descriptionBa` = копия RU (реальный рекламодатель даст оба языка); `FrequentTrip` «Йыш сәфәр»; популярные «Сәфәрҙәр».
- [x] 2026-06-23 **Реализованы 3 мок-фичи end-to-end (по порядку):** ① 💳 **Платежи** — СБП-перевод по номеру `+7 (999) 134-82-75` (P2P, без мерчанта/54-ФЗ): общий `SbpTransferSheet` (получатель+номер+инструкция+копи-номер+«Я перевёл»), подключён к Донату и Boost; проверено на эмуляторе (шит показывает «Перевод по СБП · 30 ₽ · +7 (999) 134-82-75»). Коммит `ba7e60f`. ② 🎤 **Голос** — реальная запись (`MediaRecorder`→m4a, RECORD_AUDIO рантайм-запрос) + воспроизведение (`MediaPlayer`); `VoiceMessageCard` с play/длительностью; проверено: разрешение→запись→карточка «15 сек ▶». Коммит `e8659f8`. ③ 📲 **SMS** — код уже end-to-end (smsru+фоллбэк+`sms_from`); остался ТОЛЬКО внешний шаг — одобрение отправителя на sms.ru (действие Александра), потом `sms_from=ИМЯ`. Все сборки зелёные.
- [x] 2026-06-23 **Дизайн-спринт (5 задач по порядку):** ① тёмная тема (Material 3, адаптивная `Canon*`-палитра через `@Composable`-геттеры — 410 использований не тронуты; проверено на эмуляторе свет/тьма); ② брендированный сплэш (`Screen.Splash`, анимлого + зелёный системный сплэш без белой вспышки); ③ полировка анимаций (стаггер в `ActiveTripScreen`; остальное уже было — экраны/вкладки/нижний бар/списки); ④ онбординг (параллакс героя на свайпе + анимированные точки); ⑤ иконка (мягкий мятный радиальный градиент вместо плоского белого). Все `assembleDebug` зелёные, подписанный релиз пересобран (APK+AAB). Коммиты `b79a85a`, `9ab4449`, `f5f9fac`, `8b482ef`, `4dbcb24`.
- [x] 2026-06-22 Логин: фикс размещения карточек. Было: `Box(height=530)` + карта по `BottomCenter` → на длинном тексте (башкирский) / крупном шрифте карта росла вверх и налезала на пилюли «Скрытый номер/Код посадки»; иконка-машинка пряталась под картой. Стало: `Column` + hero 360dp + `LoginFormCard.offset(y=-44)` — верх карты закреплён, пилюли больше не перекрываются на любом языке/шрифте; машинка поднята. Сборка зелёная, краша нет. Визуальную проверку на эмуляторе перенёс (эмулятор был занят другой сессией).
- [x] 2026-06-22 Бэкенд Фаза 2 (FastAPI, `backend/`): чат (сообщения по брони), семейный контроль (доверенные контакты, шаринг поездки `/bookings/{id}/share`, статусы «сел/доехал» `/trip-status`), безопасность (`/sos`, `/reports`, `/blocks`). Модели Message/TrustedContact/TripShare/SosEvent/Report/Block. Проверено: расширенный `smoke.py` → `SMOKE OK`.
- [x] 2026-06-22 Полный uiautomator-проход подэкранов после повторного запроса. Подтверждены дампами: вкладки `Карта`, `Поездки`, `Заявка`, `Чат`, `Профиль`; `Кабинет пассажира`, `Кабинет водителя`, `Проверка водителя`, `Безопасность`, `Поддержать Юлдаш`, `Простой режим`, `Доверенные контакты`, `Попросить звонок`, `Настройки`, `Помощь`, `Кабинет рекламы`; действия `Заявка → Создать новую`, `Заявка → Посмотреть отклики`, `Поездки → Подробнее`, `Поездки → Связаться`, `Карта → Я водитель`, простой режим → `Сказать маршрут`, `За близкого`, `Контакты`, `Частые маршруты`. Исправлено: добавлены fallback hit-targets и координатная обработка touch для MapKit-ценников; `assembleDebug` → `BUILD SUCCESSFUL`. Ограничение: финальный автоматический dump по MapKit-ценнику не подтвердил bottom sheet из-за нестабильного фокуса эмулятора, поэтому этот пункт оставлен как проверка на реальном устройстве.
- [x] 2026-06-22 Бэкенд Фаза 1 (FastAPI) в `backend/`: вход по SMS-коду (OTP мок), поездки, заявки, брони, матчинг, статус водителя. JWT, БД (SQLite dev / Postgres prod). Проверено: `smoke.py` → `SMOKE OK`. Ждёт деплоя на купленный сервер (нужен SSH). Слой `data/` в Android готов под подключение.
- [x] 2026-06-22 Продолжение QA после запроса «Сделай»: `assembleDebug` → `BUILD SUCCESSFUL in 46s`, установка APK на `emulator-5554` → `Success`. UI-dump подтвердил основные вкладки `Карта`, `Поездки`, `Заявка`, `Чат`, `Профиль`; профиль содержит кабинеты пассажира/водителя; вкладки поездок и заявок содержат ожидаемые CTA. Исправлено: `SettingsNavRow` больше не показывает стрелку у строк без `onClick`, чтобы статичные строки не выглядели как неработающие кнопки. Launcher-иконка проверена по `AndroidManifest.xml`, adaptive icon XML и `mipmap-xxxhdpi/ic_launcher.png` 192x192. Статический поиск пустых обработчиков в `MainActivity.kt` не дал совпадений. Ограничения: полный проход всех подэкранов через `uiautomator` не закрыт из-за повторяющегося `null root node`; тап по MapKit-маркеру по-прежнему требует проверки на реальном устройстве; backend/SMS/платежи и проверку башкирского носителем локально подтвердить нельзя.
- [x] 2026-06-22 End-to-end проверка кнопок, профиля и кабинетов. Исправлено: пассажирские кнопки `Создать заявку` и `Создать новую` больше не открывают водительскую публикацию; добавлен отдельный `CreatePassengerRequestScreen`, который создает локальную `LocalRequest` и возвращает во вкладку `Заявка`. Проверено: `assembleDebug` → `BUILD SUCCESSFUL in 43s`; UI-dump подтвердил `Кабинет пассажира → Заявка пассажира`, `Заявка → Создать новую → Заявка пассажира`, `Кабинет водителя → Создать поездку`, `Кабинет водителя → Проверка водителя`, `Кабинет водителя → Поднять маршрут`, `Кабинет рекламы`. Статический поиск пустых обработчиков в `MainActivity.kt` не дал совпадений. Ограничение: реальные backend/SMS/платежи не проверялись, в текущем приложении это локальный прототип.
- [x] 2026-06-22 Поездки: рекламный блок между поездками стал менее агрессивным. Убрано первое крупное рекламное размещение сразу после поездки; вместо второй большой карточки добавлен компактный `InlinePartnerAdCard` с мягким фоном, маленькой маркировкой `Реклама · erid`, кратким описанием и небольшой CTA. Проверено: `assembleDebug` → `BUILD SUCCESSFUL in 45s`; UI-dump подтвердил новый inline-label и отсутствие старой `Спонсорская карточка`.
- [x] 2026-06-22 SOS: исправлен перенос категории `Другое`. Чипы причин стали равномерными по ширине, текст ограничен одной строкой с аккуратным размером. Проверено: `assembleDebug` → `BUILD SUCCESSFUL in 47s`; UI-dump содержит единый узел `text="Другое"` без разрыва на `Друг`/`ое`.
- [x] 2026-06-22 Карта: блок популярного маршрута стал каруселью. Добавлены `PopularRoute` и `demoPopularRoutes`; карточка в `QuickSearchCard` свайпается через `HorizontalPager`, автоматически переключается каждые 4.5 секунды и кнопка `Найти поездку` открывает поиск по текущему маршруту. Проверено: `assembleDebug` → `BUILD SUCCESSFUL in 57s`; UI-dump подтвердил автопереход `Баймаҡ → Сибай` → `Сибай → Баймаҡ` и открытие вкладки `Мои поездки`.
- [x] 2026-06-22 Карта: большая карточка `Простой режим` заменена компактной строкой-переключателем. На вкладке больше нет отдельной кнопки `Позвоните мне`; вход в простой режим открывается нажатием на строку или switch. Проверено: `assembleDebug` OK; UI-dump подтвердил компактную строку и переход в `SimpleModeScreen`.
- [x] 2026-06-22 Дизайн-полировка по ТЗ: главный экран перестроен вокруг карты (`MapHero`) с коротким статусом геолокации без пересечения с меткой расстояния; профиль разделён на пользовательские настройки и отдельный `AdsCabinetScreen`; пользовательские рекламные карточки упрощены, подробности оставлены в кабинете; чат получил компактный composer с голосовой кнопкой; простой режим сокращён до 4 основных действий; табы поездок/заявок унифицированы, добавлены empty-state элементы. Проверено: `assembleDebug` → `BUILD SUCCESSFUL in 47s`; UI-dump подтвердил карту, профиль, кабинет рекламы, чат, поездки, заявки и простой режим.
- [x] 2026-06-22 UI-аудит вкладок и подэкранов: проверены Карта, Поездки, Заявка, Чат, Профиль и ключевые переходы через `adb`/`uiautomator dump` и скриншоты. Исправлено: кнопка `Я водитель` теперь открывает `Создать поездку`, убрана плавающая кнопка на вкладке Поездки, которая перекрывала рекламу, табы чата растягиваются по ширине и не обрезают `Система`, `Настройки и помощь` в Профиле подняты выше рекламного кабинета.
- [x] 2026-06-22 Доступность и семейные сценарии без backend: добавлены простой режим, голосовая заявка, заказ за близкого, доверенные контакты, повтор частой поездки, обратный звонок и голосовые сообщения в чате. Все данные хранятся локально в памяти приложения и пропадают после перезапуска. Smoke-тест на эмуляторе подтвердил переходы и локальное создание заявок/контакта/голосового сообщения.
- [x] 2026-06-22 Ads 2.0: рекламный блок проработан как модуль. Добавлены `AdPlacement`, `AdStatus`, пакет размещения, бюджет, целевое действие, контакт, точка карты, CTR, фильтрация объявлений по разрешённым местам показа. Карточка рекламы показывает пакет, размещения, erid, рекламодателя, действие, контакт, период, показы, клики и CTR. Кабинет рекламы показывает активные/модерацию/клики, общий CTR, пакеты, чеклист запуска и объявления со статусами. Сборка OK, smoke-тест на эмуляторе OK.
- [x] 2026-06-22 Рекламная система партнёров: модель `PartnerAd`, мок-таблица объявлений, таргетинг по городу/маршруту/категории, карточка `Реклама · erid`, рекламодатель, CTA, показы/клики в памяти. Размещения: Карта после ближайших поездок, Поездки после обычных карточек, Детали поездки ниже карты, Профиль «Партнёры Юлдаш», Помощь. Добавлен прототип «Кабинет рекламы» с пакетами размещения и статистикой. Сборка OK, smoke-тест на эмуляторе OK.
- [x] 2026-06-22 Анимации: плавные переходы вкладок и экранов (`AnimatedContent`), анимированное нижнее меню (пилюля + масштаб иконки), сжатие при нажатии (`Modifier.bounceClick`), каскадное появление карточек (`Modifier.appearIn`) на всех 5 вкладках. Сборка OK, проверено на эмуляторе (краша нет).
- [x] 2026-06-21 Новый логотип (прозрачный `yuldash_logo.png`) внедрён: онбординг (`R.drawable.yuldash_logo`) + иконка приложения (адаптивная foreground + legacy + round, все плотности). Сборка `BUILD SUCCESSFUL`, проверено на эмуляторе.

## Сделано (из прошлых сессий)
- [x] Онбординг перед логином + флаг `onboarding_completed` (показ один раз).
- [x] Логотип в онбординге через `R.mipmap.ic_launcher_foreground`; launcher-иконки сгенерированы.
- [x] Починены пустые кнопки: «Связаться/Написать» → чат; «Посмотреть отклики» → чат; табы Активные/Черновики/Архив; «Очистить всё» в уведомлениях.
- [x] Нижняя навигация на подэкранах (Уведомления/Безопасность/Настройки/Помощь/Проверка водителя) переключает вкладки через `onSelectTab`, кнопка «назад» возвращает в правильный раздел.

## Решили НЕ делать (пока)
- Вынос всех надписей в ресурсы `values/` + `values-ba/`. Оставляем `appText(ru, ba)` в коде (выбор от 2026-06-21).

## Переводы на проверку
### Авто-проверка водителя — статусы/причины (2026-06-29) — на проверку
> Бэкенд отдаёт коды; клиент рисует через `appText(ru, ba)`. BA — черновик, подтверди.
- `Документы на проверке` → `Документтар тикшереүҙә`
- `Документы подтверждены` → `Документтар раҫланды`
- `Заявка отклонена` → `Ғариза кире ҡағылды`
- `Не удалось распознать права на фото` → `Фотола водитель таныҡлығын танып булманы`
- `Похоже, срок прав истёк` → `Водитель таныҡлығының ваҡыты үткән кеүек`
- `Не разобрали номер прав` → `Таныҡлыҡ номерын уҡып булманы`
- `Сними права при хорошем свете, без бликов` → `Таныҡлыҡты яҡшы яҡтыла, ялтырауһыҙ төшөр`
### Авто-проверка: бейдж в админ-очереди (2026-06-29) — на проверку
- `🤖 Авто: похоже на действительные права` → `🤖 Авто: ысын права кеүек`
- `🤖 Авто: фото не распознано` → `🤖 Авто: фото танылманы`
- `🤖 Авто: проверка недоступна` → `🤖 Авто: тикшереп булманы`
- `🤖 Авто: нужна ручная проверка` → `🤖 Авто: ҡул менән тикшерергә`
- `№ прав ` → `права № ` · `срок до ` → `ваҡыты `
### Заявки на оплату / донат (2026-06-29) — на проверку
- `Заявки на оплату` → `Түләү заявкалары`
- `Подтвердить оплату буста и донаты` → `Буст түләүен раҫлау һәм донаттар`
- `Оплата подтверждена` → `Түләү раҫланды`
- `Сверь свою карту по сумме и имени, потом подтверди — буст запустится. Донаты просто засчитываются.` → `Картаңды сумма һәм исем буйынса тикшер, аҙаҡ раҫла — буст эшләй. Донаттар иҫәпләнә.`
- `Донаты подтверждённые` → `Раҫланған донаттар` · `чел.` → `кеше`
- `Нет заявок на оплату` → `Түләү заявкалары юҡ`
- `Здесь появятся оплаты буста и донаты на подтверждение.` → `Бында буст түләүҙәре һәм донаттар раҫлауға күренер`
- `Буст поездки` → `Сәфәр бусты` · `Без имени` → `Исемһеҙ`
- `Заявка отправлена. Когда админ увидит перевод — донат засчитается. Деньги идут на серверы, карты и SMS.` → `Заявка ебәрелде. Админ күсереүҙе күргәс — донат иҫәпләнә. Аҡса серверҙарға, карталарға һәм SMS-ҡа китә.`
### Логин — строка согласия с офертой (2026-06-29) — на проверку
> Ссылки ведут на yulbash.ru/terms/ и /privacy/. BA — черновик, подтверди.
- `Входя, ты принимаешь` → `Инеп, һин ҡабул итәһең:`
- `Условия` → `Шарттарҙы`
- `Политику конфиденциальности` → `Конфиденциаллек сәйәсәтен`
### Онбординг — новые/правленые строки (2026-06-29) — на проверку
> Слайд 2 (4-я карточка) + ценностная полоса слайда 4. BA — черновик, подтверди.
- `Близкий видит поездку` → `Яҡының сәфәрҙе күрә`
- `Поделись маршрутом — родной человек на связи всю дорогу.` → `Маршрут менән бүлеш — яҡының юл буйы бәйләнештә.`
- Полоса ценности: `Между своими` → `Үҙ кеше араһында` · `Без комиссии` → `Комиссия юҡ` · `Весь Башкортостан` → `Бөтә Башҡортостан`
### Имя при входе (2026-06-28) — на проверку
- `Ваше имя (необязательно)` → `Исемегеҙ (мотлаҡ түгел)`
### SOS: звонок в госслужбы (2026-06-28) — на проверку
- `Звонок в экстренные службы с твоего номера.` → `Ашығыс хеҙмәттәргә үҙ номерыңдан шылтырау.`
- `Прямой вызов службы` → `Хеҙмәткә туранан-тура`
- `Полиция` → `Полиция`; `Пожарные` → `Янғын`; `Скорая` → `Тиҙ ярҙам`
- `Что случилось?` → `Нимә булды?`
- `Продиктуй оператору` → `Операторға әйт`; `Координаты: ` → `Координаталар: `; `Скопировать` → `Күсереү`; `Скопировано` → `Күсерелде`
- `Позвонить 112` → `112 — шылтыратыу`
- `Звонок идёт с твоего номера. 112 — единый номер всех служб.` → `Шылтырау үҙ номерыңдан бара. 112 — бөтә хеҙмәттәрҙең уртаҡ номеры.`
- `Не удалось открыть звонок` → `Шылтырауҙы аса алманыҡ`
- `Геолокация выключена — включи, чтобы продиктовать координаты.` → `Геолокация һүндерелгән — координаталарҙы әйтер өсөн ҡабыҙ.`
- `Обновить` → `Яңыртыу`; `Обновляю…` → `Яңыртам…`; `Включить гео` → `Геоны ҡабыҙыу`
- `Сообщить близким и поддержке` → `Яҡындарға һәм ярҙамға хәбәр итеү`
- `SMS твоим доверенным контактам + сигнал поддержке Юлдаш с твоими координатами.` → `Ышаныслы контакттарыңа SMS + Юлдаш ярҙамына координаталарың менән сигнал.`
- `Уведомление отправлено` → `Хәбәр ебәрелде`; `Доверенные контакты получат SMS, поддержка увидит сигнал с координатами.` → `Ышаныслы контакттар SMS алыр, ярҙам координаталар менән сигналды күрер.`
- `Сигнал не отправлен` → `Сигнал ебәрелмәне`; `Похоже, нет сети. Проверь связь и нажми ещё раз.` → `Бәйләнеш юҡ кеүек. Тикшереп, тағы баҫ.`
- `Ложный вызов экстренных служб наказуем по закону.` → `Ялған ашығыс саҡырыу закон буйынса язаға тарттырыла.`

### Чёрный список/Жалоба (2026-06-28) — на проверку
- BlocklistScreen, ReportScreen (жалоба на попутчика), кнопки Заблокировать/Разблокировать/Пожаловаться — башкирский черновой.

### Новые экраны Правила/Оплата/Тема (2026-06-28) — на проверку
- RulesScreen (5 правил), PaymentInfoScreen (оплата СБП), диалог темы (Светлая/Тёмная/Как в системе) — башкирский черновой, проверить носителю.

### Вход: номер обязателен (403 phone_required) (2026-06-28) — на проверку
- `Для безопасности нужен номер. В Telegram нажми «📱 Поделиться номером», потом вернись и нажми «Войти».` → `Хәүефһеҙлек өсөн номер кәрәк. Telegram'да «📱 Номер менән бүлешергә» баҫ, аҙаҡ кире ҡайтып «Инеү» баҫ.`
- `Открыть Telegram и поделиться номером` → `Telegram'ды асып, номер менән бүлешергә`

### Аудит-рой: новые строки (2026-06-28) — на проверку
- `erid (маркировка)` → `erid (билдәләмә)` (поле в кабинете рекламы админа)
- `Сообщение не отправлено` → `Хәбәр ебәрелмәне` (тост в чате при сбое отправки)
- `Баймаҡ, ул. Ленина, 12` → `Баймаҡ, Ленин урамы, 12` (адрес демо-рекламы аптеки, `addressBa`)
- *(уже устоявшиеся, не новые: `Не удалось загрузить. Проверь интернет.` → `Йөкләп булманы. Интернетты тикшер.`, `Повторить` → `Ҡабатларға` — взяты из существующего `AdminReviewsScreen`)*

### ✅ BACKEND ЗАДЕПЛОЕН НА ПРОД (2026-06-29)
- ✅ `python-multipart` 0.0.32 **установлен** на проде (`/opt/yuldash/.venv`) — multipart-загрузки работают.
- ✅ Код (аудит-фиксы + Tier B + DEAD1) выкатан из worktree, сервис `active`, health ok, `/ads/event` без токена→401, журнал чист. Бэкап: `backups/app-pre-audit-20260629-092712.tar.gz`.
- **DEAD1 дроп колонок на проде — опц., НЕ сделан** (низкий приоритет): `vk_id`/`whatsapp_verified` в БД инертны, модель их игнорирует. Захочешь дропнуть — `scp` alembic + `alembic upgrade head`.
- ⏳ **Android-фиксы** (ViewModel, multipart-клиент, back-stack и пр.) → в прод при **следующей сборке релизного APK** (`bundleRelease`) и раздаче.

### Онбординг слайд 4 — вход через Telegram (2026-06-28) — на проверку
- `Войдите через Telegram — быстро и безопасно, без SMS и паролей.` → `Telegram аша инегеҙ — тиҙ һәм хәүефһеҙ, SMS-һыҙ һәм паролһеҙ.`

### Онбординг v2 (2026-06-28) — на проверку
- `Дорога по Башкортостану` → `Башҡортостан буйлап юл`
- `Едешь с юлдашом, не с незнакомцем` → `Ят кеше менән түгел, юлдаш менән бараһың`
- `Поездки и заявки между своими: Баймак, Сибай, Уфа и другие привычные маршруты рядом.` → `Үҙ кешеләр араһында сәфәрҙәр һәм заявкалар: Баймаҡ, Сибай, Өфө һәм яҡын маршруттар.`
- `Нашёл маршрут` → `Маршрут таптың`
- `Смотри ближайшие поездки или оставь заявку, если машины ещё нет.` → `Яҡындағы сәфәрҙәрҙе ҡара йәки машина юҡ икән заявка ҡалдыр.`
- `Доверие важнее скорости` → `Ышаныс тиҙлектән мөһимерәк`
- `Безопасность перед дорогой` → `Юл алдынан хәүефһеҙлек`
- `Водитель может пройти проверку, номер не раскрывается заранее, а в поездке есть SOS и связь с близкими.` → `Водитель тикшереү үтә ала, номер алдан асылмай, ә сәфәрҙә SOS һәм яҡындар менән бәйләнеш бар.`
- `Когда машины ещё нет` → `Машина әле юҡ икән`
- `Заявка не пропадает в пустоту` → `Заявка бушҡа юғалмай`
- `Так закрывается путь: заявка → отклик → поездка` → `Шулай юл ябыла: заявка → яуап → сәфәр`
- `Войди через Telegram: бот пришлёт код, а Юлдаш откроет карту, заявки, чат и профиль.` → `Telegram аша ин: бот код ебәрер, ә Юлдаш карта, заявкалар, чат һәм профилде асыр.`

### Логин v2 (2026-06-28) — на проверку
- `Открой Telegram, нажми «Старт» — бот пришлёт 6-значный код. Введи его сюда.` → `Telegram'ды ас, «Старт» баҫ — бот 6 һанлы код ебәрер. Шуны индер.`
- `Telegram пришлёт 6-значный код для входа.` → `Telegram инеү өсөн 6 һанлы код ебәрер.`
- `Telegram-код` → `Telegram коды`
- `6 цифр — без паролей и SMS` → `6 һан — пароль һәм SMS-һыҙ`
- `Откроется после брони` → `Брондән һуң асыла`
- `Появляется в активной поездке` → `Актив сәфәрҙә күренә`
- `Проверка водителя` → `Водителде тикшереү`
- `Права и авто уходят на модерацию` → `Права һәм авто модерацияға китә`

### UiKit — состояния по умолчанию (2026-06-28) — на проверку
- `Что-то пошло не так` → `Нимәлер дөрөҫ булманы`
- `Проверь интернет и повтори` → `Интернетты тикшереп ҡабатла`
- `Повторить` → `Ҡабатлау`
- `Пока пусто` → `Әлегә буш`
- `Здесь скоро появятся данные` → `Бында тиҙҙән мәғлүмәт күренер`
### Логин: код Telegram ещё идёт (2026-06-28) — на проверку
- `Код ещё идёт от Telegram — подожди пару секунд и нажми «Войти» снова.` → `Код Telegram'дан килә — бер-ике секунд көт тә «Инеү» баҫ.`

### Чат: статус доставки (2026-06-27) — на проверку
- `Не доставлено · Повторить` → `Ебәрелмәне · Ҡабатларға`
- `Сообщение не отправлено` → `Хәбәр ебәрелмәне`

### Точка сбора на карте (2026-06-27) — на проверку
- `Двигай карту — пин на месте встречи` → `Картаны күсер — пин осрашыу урынында`
- `Готово — точка здесь` → `Әҙер — нөктә бында`
- `Отметить на карте` → `Картала билдәләргә`
- `Точка на карте отмечена · изменить` → `Картала билдәләнде · үҙгәртергә`
- `Точка на карте` → `Картала нөктә`
- `Открыть точку на карте` → `Нөктәне картала асырға`

### Ценовой ориентир (2026-06-26) — на проверку
- `Обычно по маршруту ~N ₽ · нажми, чтобы подставить` → `Был юл буйынса ғәҙәттә ~N ₽ · ҡуйыр өсөн баҫ`

### «Позвать соседа» (2026-06-26) — на проверку
- `Позвать соседа` → `Күршене саҡырырға`
- Текст шэра: `Еду {откуда} → {куда}, {время}. {цена} ₽. Поехали вместе в Юлдаше` → `{откуда} → {куда}, {время}. {цена} ₽. Әйҙә бергә — Юлдашта`

### Место встречи (2026-06-26) — на проверку
- `Где встречаемся` → `Ҡайҙа осрашабыҙ`
- Плейсхолдер `Напр.: у автовокзала, АЗС на выезде` → `Мәҫәлән: автовокзал янында, сығыштағы АЗС`
- `Уточнить у водителя` → `Водителдән асыҡларға`

### Поле «Что везёте» для Посылка/Груз (2026-06-26) — на проверку
- `Что везёте` → `Нимә алып бараһығыҙ`
- Плейсхолдер `Напр.: диван и 2 коробки, хрупкое` → `Мәҫәлән: диван һәм 2 ҡумта, һынғыс`

### Типы поездки + Срочно (2026-06-26) — ✅ ПОДТВЕРЖДЕНО Александром (носитель)
- `Срочно` → `Ашығыс`
- `Груз` → `Йөк`
- Пояснение цены: `Цену ставишь ты. Оплата — напрямую тебе после поездки. Юлдаш комиссию не берёт.` → `Хаҡты үҙең ҡуяһың. Түләү — сәфәрҙән һуң тура һиңә. Юлдаш комиссия алмай.`
 (башкирский)
<!-- агент кидает сюда новые BA-строки со статусом «черновик», Александр подтверждает -->
- Черновик 2026-06-24 (премиум-предпочтения поездки, на проверку носителю): «Хайуан менән» (можно с животным), «Балалар ултырғысы / бустер» (детское кресло/бустер), «Тик ҡатын-ҡыҙ өсөн» (только для женщин), «Тәмәке тартырға ярай» (курение разрешено), «Багаж урыны бар» (есть место под багаж), «Кондиционер бар» (есть кондиционер). ⚠️ Машинные черновики — проверить живость.
- Черновик 2026-06-24 (проверка водителя): «Водитель документтарын тикшереү» (проверка документов водителя), «Права фотоһы» (фото прав), «Машина фотоһы» (фото авто), «Тикшереүгә ебәрелде» (отправлено на проверку), «Раҫланды» (подтверждён), «Кире ҡағылды» (отклонён).
- Черновик 2026-06-23: массовая i18n-доводка `MainActivity.kt` после аудита вкладок — внутренние табы, toast-сообщения, экран «Создать поездку», «Поддержать Юлдаш», Boost, SOS-категории, чат, уведомления и партнёрская реклама. Проверить живость формулировок вроде `Заявка баҫтырылды`, `Сәфәр баҫтырылды`, `Ирекле ярҙам`, `Күтәреү ҡабыҙылды`, `Машина боҙолдо`, `Сәфәр ебәрелде`, `Ҡала + категория`, `Исемлек спонсоры`.
- Черновик: рекламные строки `Яҡындағы партнёр`, `Маршрут партнёры`, `Спонсор карточкаһы`, `Ҡала партнёры`, `Реклама кабинеты`, `Урынлаштырыу пакеттары`, `Файҙалы партнёр`, `күрһәткән/баҫыу`-формулировки в статистике.
- Черновик Ads 2.0: `Модерацияла`, `Туҡтатылған`, `Төп партнёр`, `Күрһәткәнгә тиклем тикшереү`, `Ҡала, маршрут йәки категория`, `Бөтә күрһәтеү`, `дөйөм CTR`.
- Черновик лента-карусель карты (6 карточек): `Популяр`, `Бөгөн`, `Көнөнә N сәфәр`, `Яҡташтар юлда`, `24 сәғәт`, `Аҙна хиты`, `Аҙнаның иң йыш маршруты`, `N тапҡыр`, `Факт`, `Һәр 3-сө сәфәр — өйгә`, `Яҡташтар тыуғандарға бара`, `Айға`, `Айына N сәфәр`, `Юлда N водитель`, `ай`, `Йылға`, `Йылына N сәфәр`, `Бергә булғанға рәхмәт`, `йыл`. ⚠️ Башкирский плурал/счёт сейчас как «N сәфәр» (без согласования) — проверь, нужно ли иначе.
- Черновик приветствий по времени: `Хәйерле көн` (добрый день), `Хәйерле кис` (добрый вечер), `Тыныс төн` (доброй ночи). `Хәйерле иртә` (утро) — уже используется.
- Черновик оценок: `Водителде баһалағыҙ` (оцените водителя), `Баһа өсөн рәхмәт` (спасибо за оценку), `Баһалап булманы` (не получилось оценить).
- Черновик «Ближайшие поездки»: `иң яҡыны` (ближайшая), `Был маршрутта әлегә машина юҡ`, `Яҡында сәфәрҙәр әлегә юҡ`, `Барлыҡҡа килһә — бында күрһәтәбеҙ`, `Яңыртыу` (обновить), `Барырға` (поехать), `янда` (рядом, для дистанции <1 км).
- Черновик доступности: `Ябай режим`, `Юлдаш еңел`, `Тауыш менән әйтеү`, `Яҡын кеше өсөн заказ`, `Ышаныслы контакттар`, `Шылтыратыу һорау`, `Тауыш хәбәрҙәре`, `Водитель өсөн текст`.

### Telegram-вход по коду (2026-06-27) — на проверку
- `Открой Telegram, нажми «Старт» — бот пришлёт код. Введи его сюда.` → `Telegram'ды ас, «Старт» баҫ — бот код ебәрер. Шуны индер.`
- `Код из Telegram` → `Telegram коды`
- `Введите код из Telegram` → `Telegram кодын индерегеҙ`
- `Неверный код. Проверь и введи снова.` → `Код дөрөҫ түгел. Тикшереп, ҡабат индер.`
- `Код истёк. Получи новый — открой Telegram ещё раз.` → `Код ваҡыты бөттө. Яңыһын ал — Telegram'ды тағы ас.`
- `Слишком много попыток. Получи новый код.` → `Бик күп омтылыш. Яңы код ал.`
- `Открыть Telegram ещё раз` → `Telegram'ды тағы асырға`
- `Назад` → `Кире`
- `Не удалось начать вход. Повтори.` → `Инеүҙе башлап булманы. Ҡабатла.`
- (бот, RU) `Твой код для входа в Юлдаш: NNNNNN` / `Открой приложение Юлдаш и нажми «Вход через Telegram» — я пришлю код.`

### Экран входа — мессенджеры основные (2026-06-27) — на проверку
- `Войти в Юлдаш` → `Юлдашҡа инеү`
- `Быстрый вход — выбери мессенджер` → `Тиҙ инеү — мессенджер һайла`
- `Вход через Telegram` → `Telegram аша инеү`
- `Вход через VK` → `VK аша инеү`
- `Вход через WhatsApp` → `WhatsApp аша инеү`
- `Войти по номеру телефона` → `Телефон номеры аша инеү`

### OAuth-вход — тосты (2026-06-27) — на проверку
- `Вход через Telegram скоро` → `Telegram аша инеү тиҙҙән`
- `Вход через VK скоро` → `VK аша инеү тиҙҙән`
- `Вход через WhatsApp скоро` → `WhatsApp аша инеү тиҙҙән`
- `Не удалось открыть Telegram` → `Telegram асып булманы`
- `Не удалось открыть VK` → `VK асып булманы`
  ⚠️ Машинные черновики — проверить живость.

## Ревью выполненного
<!-- краткий итог после каждой завершённой задачи -->
- 2026-06-28 Полный QA-аудит фич (Codex): `backend/.venv/Scripts/python.exe -m pytest tests -q` → **63 passed, 1 warning**; `gradlew :app:assembleDebug --no-daemon` → **BUILD SUCCESSFUL in 53s**; debug APK установлен на `emulator-5554`, пройдены вкладки Карта/Поездки/Заявка/Чат/Профиль и глубокие экраны SOS, создание заявки, кабинет пассажира, кабинет водителя, проверка водителя. Скриншоты: `android/screenshots/qa_*.png`. `logcat` без `FATAL EXCEPTION`. Подтверждено кодом: SOS/помощь открывают звонилку через `ACTION_DIAL`, фото/аватар/документы через `GetContent`, голос через `RecognizerIntent`, реклама/boost/чат/рейтинги/блокировки/заявки имеют API и тесты. Нельзя подтвердить локально: реальную доставку FCM на физическое устройство, живой Telegram-login в release без debug-bypass, реальные внешние платежи ЮKassa/СБП и реальные действия в приложениях-звонилках/Telegram/галерее. Найдено: hardware Back с подэкранов прыгает на Home по текущему `BackHandler`; визуально основной UI выглядит цельно и дорого, но на длинных списках стоит проверить/усилить нижний safe-padding, чтобы последний контент не казался спрятанным под нижней навигацией.
- 2026-06-22 MapKit Этап 1: `assembleDebug` → `BUILD SUCCESSFUL in 1m 24s` (SDK `4.39.0-lite` скачался с Maven Central, `libmaps-mobile.so` в APK). Установил на эмулятор, дошёл до вкладки «Карта» — реальный `MapView` рендерится без краша (прямая линия маршрута + накладки видны), `logcat` без `FATAL`. Единственный блокер — `Invalid api key` от сервера Yandex (ключ совпадает со скрином символ-в-символ, опечатки нет → активация ключа). Брендовый JSON-стиль карты перенесён в Этап 2.
- 2026-06-22 MapKit Этап 2: `assembleDebug` → `BUILD SUCCESSFUL` (с фиксом касаний). Ключ **активировался** — на эмуляторе загрузились настоящие тайлы (Тубинский, Сибай, watermark Yandex Maps), на карте виден маркер-ценник «300 ₽». Маркеры поездок + `ModalBottomSheet` реализованы. Добавлены `requestDisallowInterceptTouchEvent`, fallback hit-targets и координатная обработка touch. ⚠️ Финальный автоматический tap→sheet через `uiautomator` на эмуляторе не подтверждён стабильно из-за фокуса/логина; проверить на реальном устройстве.
- 2026-06-22 UI-аудит: `assembleDebug` → `BUILD SUCCESSFUL in 15s`; визуально подтверждены исправленные экраны `audit-rides-fixed2.png`, `audit-chat-fixed.png`, `audit-profile-order-final2.png`; функциональный smoke подтвердил переходы `Найти поездку`, `Я водитель`, `SOS`, `Простой режим`, `Связаться`, `Посмотреть отклики`, `Создать новую`, `Записать голос`, `Система`, `Проверка водителя`, `Безопасность`. `rg` не нашёл пустых обработчиков и старого floating action button.
- 2026-06-22 Доступность/семья: проверено `assembleDebug` → `BUILD SUCCESSFUL`; UI-dump подтвердил `Простой режим`, `Голосовая заявка` → `Распознано` → `Последние заявки`, `Заказ за близкого`, добавление `Гульназ`, `Повтор: В больницу`, `Звонок запрошен`, `Голосовое от Байрас`.
- 2026-06-22 Ads: реализовано в прототипе без бэкенда. Проверено: `assembleDebug` → `BUILD SUCCESSFUL`; UI-dump подтвердил рекламные блоки на карте, в поездках, профиле и деталях поездки; `logcat` без `FATAL EXCEPTION`; `rg` не нашёл пустых обработчиков.
- 2026-06-22 Ads 2.0: проверено `assembleDebug` → `BUILD SUCCESSFUL`; UI-dump подтвердил на карте `Реклама · erid`, пакет `Город + категория`, места размещения, действие, контакт и CTR; профиль подтвердил кабинет со статусами, модерацией, точками карты и stats; `logcat` без `FATAL EXCEPTION`.
- 2026-06-28 Security-аудит (senior security-инженер, прод): построчно проверен весь `backend/app/**` + security-поверхность Android. Критичных дыр нет. Найдено и починено 3 реальные уязвимости в WebSocket-чате (V1 обход logout-ревокации, V2 обход блокировки, V3 утечка сессий БД→DoS) — только бэкенд, клиент не тронут. Отчёт: `docs/security-audit.md`. Верификация: `python -m pytest` → **54 passed** (включая новый регресс `test_ws_rejects_token_revoked_by_logout`). Рекомендации на потом (не ломая живой клиент): R1 OTP 6-значный, R2 magic-bytes+квота загрузок, R3 `/ads/stats` под admin, R4 убрать `?token=` в WS.
- 2026-06-28 Security R2+R3 (бэкенд, ship-safe): R2 — защита загрузок: magic-bytes для фото (`_looks_like_image` в `decode_upload_b64(sniff_image=True)` для `/upload/photo` и `/upload/chat-photo`) + суточная квота на юзера (`UploadEvent` + `enforce_upload_quota`, `MAX_UPLOADS_PER_DAY=60`) на всех 3 upload-эндпоинтах. R3 — `/ads/stats` закрыт под admin (был публичный). Новая таблица `uploadevent` создаётся `create_all` при рестарте (миграция не нужна). Тесты: **56 passed** (+`test_ad_stats_admin_only`, +`test_upload_daily_quota`, +magic-bytes assert). R1 (OTP 6 цифр) и R4 (убрать `?token=` в WS) + клиентский `getAdStats` auth=true — ОТЛОЖЕНЫ в следующий релиз APK (трогают живой клиент).

## План: Boost-поездки + реклама через реальную оплату (самозанятый, ЮKassa) — 2026-06-28
Юр.основа: самозанятый монетизирует СВОИ услуги (Boost/реклама), НЕ посредничество за поездки.
Провайдер: ЮKassa v3 REST (поддерживает самозанятых, авто-чек «Мой налог»). Ключи — в .env, mock-фолбэк в dev.

**Фаза 1 — бэкенд (эта сессия, ship-safe, mock-тест, без правки живого APK):**
1. `Ride.boosted_until` (+`boost_tier`) — поднятая поездка и срок. Миграция `migrate_boost.sql` (PG ALTER; sqlite авто).
2. Сортировка `/rides` и `/rides/near`: boosted (boosted_until>now) — первыми, затем по depart_at.
3. Boost-тарифы на бэке (CLAUDE.md): quick=20₽/2ч, day=50₽/24ч, urgent=70₽/6ч.
4. `Payment` модель: user_id, purpose (boost|ad), provider_id, ride_id, tier, amount_kop, status, created_at.
5. `app/payments.py`: ЮKassa-клиент (`create_payment`→confirmation_url; `fetch_payment` для верификации). Mock-режим: pending→авто-succeeded в dev.
6. `routers/payments.py`: `POST /boost/create {ride_id,tier}` (owner-check → создаёт платёж → confirmation_url), `POST /payments/yookassa/webhook` (GET платёж в ЮKassa по id → succeeded → активирует boost). Безопасность вебхука: доверяем не телу, а перепроверке через API ЮKassa.
7. Тесты: сортировка boosted-first, создание boost (mock), активация по webhook.
**Действие Александра:** ЮKassa для самозанятых → shop_id + secret_key → `.env` на проде. Без них — mock (boost в dev).

**Фаза 2 — Android (следующий релиз APK):** заменить `SbpTransferSheet` в `BoostScreen` на реальный редирект ЮKassa (`POST /boost/create`→открыть `confirmation_url`→вернуться→poll статус). Аналогично для платной рекламы.
- 2026-06-28 Boost Фаза 1 ЗАДЕПЛОЕНО: миграция `migrate_boost.sql` (ALTER ride + индекс) прошла, `payment` создана `create_all`, сервис active, `/boost/plans` отдаёт 3 тарифа на проде, health ok. Прод-провайдер=mock → `/boost/create` отдаёт 503 (бесплатных бустов нет). 58 тестов зелёные. Ждёт: ключи ЮKassa от Александра + Android Фаза 2 (реальный редирект вместо фейкового СБП).
- 2026-06-28 Платежи СБП-интерим + ЮKassa-материалы:
  * ЮKassa — ВСЁ готово к подключению (бэкенд+авто-чек задеплоено ранее). Анкета ЮKassa: текст «что продаёте» + 2 скрина с ценами готовы → `C:\Users\Bayra\Yuldash\yookassa-anketa\` (01_uslugi_boost_ceny.png — тарифы 20/50/70₽, 02_prilozhenie_poezdki.png). Блокер анкеты: поле «ссылка на приложение» — нужна публикация (RuStore). Ключи ЮKassa Александр добавит позже.
  * СБП-интерим ЗАДЕПЛОЕН (бэкенд): `PAYMENTS_PROVIDER=sbp_manual` → `/boost/create` отдаёт pending+реквизиты (`sbp_phone/bank/name` из .env, НЕ в git), активирует админ: `GET /admin/payments/pending`, `POST /admin/payments/{id}/confirm|reject`. 59 тестов зелёные. Прод пока mock (буст 503) — флипнем на sbp_manual + номер когда Android Фаза 2 пойдёт в стор.
  * APK debug собран (BUILD SUCCESSFUL 1m5s), скрины сняты на эмуляторе.
  Осталось: Android Фаза 2 (экран Boost: реквизиты СБП + «я оплатил»→pending; редирект ЮKassa на будущее) + RuStore-пакет/публикация.
- 2026-06-28 Boost Фаза 2 (Android платёжный флоу) — код готов, бэкенд задеплоен:
  * Backend: `GET /driver/rides` (свои активные поездки, owner-scoped) задеплоен, 401 без токена. 60 тестов.
  * Android: `BoostScreen` переписан — фейковый `SbpTransferSheet` убран. Загрузка тарифов (`/boost/plans`) + своих поездок (`/driver/rides`), выбор поездки+тарифа → `/boost/create`. sbp_manual → карточка реквизитов СБП (номер/банк/имя + копировать) + pending; yookassa → редирект; succeeded → «поднято». Все состояния (загрузка/ошибка/пусто/результат), 2 языка, Canon, анимации выбора. `ApiClient`: getBoostPlans/getDriverRides/createBoost + DTO; RideDto.boosted.
  * Верификация: assembleDebug BUILD SUCCESSFUL; новый экран отрендерился на эмуляторе (состояние ошибки против реального бэкенда — экран+сеть+стейт-машина работают, без краша). Полный визуальный happy-path не снят: гостевой режим приложения сделал авто-логин на эмуляторе непрактичным → покрыто тестами. Личный СБП-номер в dev-`.env` НЕ коммитился (gitignored, удалён).
  Осталось: на проде флипнуть `PAYMENTS_PROVIDER=sbp_manual`+`SBP_PHONE` когда пойдём в стор; RuStore-публикация (разблокирует анкету ЮKassa).
- 2026-06-28 R1+R4 ИСПРАВЛЕНО (server+client, приложение не в сторе → синхронно):
  * R1 — 6-значный OTP: `gen_otp` k=4→6 (SMS/Telegram/посадочный код). Клиент: tg-инпут `.take(4)`→`.take(6)` + гейты `<4`→`<6` (SMS-инпут уже был 6). На проде: `gen_otp k=6` подтверждён.
  * R4 — `?token=` в WS убран: токен только первым сообщением `{type:auth,token}`. Клиент (ChatSocket) уже так делал. На проде: `query_params` в chat.py = 0.
  * Тесты обновлены на message-auth + извлечение tg-кода через `\d{6}`. 61 зелёный. assembleDebug BUILD SUCCESSFUL.
  * ⚠️ Старый APK (`.take(4)`): Telegram-вход требует обновления APK; SMS-вход работает. Свежий APK: `yookassa-anketa/yuldash-debug-latest.apk`.
## Audit 2026-06-28 full-code pass
- [x] Android debug build: `gradlew.bat :app:assembleDebug --no-daemon` -> BUILD SUCCESSFUL.
- [x] Backend tests: `backend/.venv/Scripts/python.exe -m pytest tests -q` -> 63 passed, 1 warning.
- [x] Web build: `npm ci` + `npm run build` in `web/` -> build OK.
- [x] Promo audit: `npm audit --json` in `promo/` -> 0 vulnerabilities.
- [x] Full audit report written: `docs/audit-2026-06-28-full-code.md`.
- [x] **Fix Android lint error** (2026-06-28, Opus): `android/gradle.properties:2` → `org.gradle.java.home=C\:/Program Files/...` (escape двоеточия). `lintDebug` теперь **0 errors** (было 1), `assembleDebug` зелёный.
- [x] **`.codex/config.toml` (Stitch-ключ) убран из-под git** (2026-06-28, Opus): добавил `.codex/` в `.gitignore`. Файл не был закоммичен, теперь и не попадёт. `git check-ignore` подтверждает.
- [x] **Android App Bundle language-split решён** (2026-06-28, Opus): добавил `bundle { language { enableSplit = false } }` в `app/build.gradle.kts` → Play не дробит ресурсы по языку, рантайм-переключатель RU/BA (`appText`/`AppLanguage`) не сломается в AAB. `assembleDebug` зелёный.
- [~] **web Next.js audit — осознанно отложено** (2026-06-28, Opus): фикс только `npm audit fix --force` → next@16 (major, ломающий 14→16). Все CVE — runtime Next.js (DoS/SSRF/XSS/cache-poison через работающий сервер). Лендинг = `output: "export"` (статика, nginx отдаёт готовый HTML, **сервера Next нет**) → класс неэксплуатируем на задеплоенном сайте. Major-апгрейд вслепую = риск сломать живой лендинг ради неприменимого. Делать отдельной задачей с проверкой сборки/визуала. PostCSS — транзитивно через next, уйдёт с ним.
- [ ] Verify release Telegram-login and FCM delivery on a physical device. (только физустройство — не код)
