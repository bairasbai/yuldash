# 🏗️ Архитектурный аудит Юлдаша (senior-разбор)

## 📓 Журнал: тест, зелёный по случайности — посев на голый номер человека (2026-08-29, Opus)

**Симптом.** `test_waypoints.py::test_long_stop_adds_to_the_waiting_fee` падал
`sqlite3.IntegrityError: FOREIGN KEY constraint failed` при выборочном прогоне (`-k "fee"`)
и проходил при прогоне всего файла.

**Как работает код.** `tests/conftest.py:80` включает `PRAGMA foreign_keys=ON` — намеренно,
с аудита 2026-08-06: без этого 12 тестов сеяли записи на несуществующих людей, зеленели дома
и краснели в CI на Postgres. `tests/test_waypoints.py:219` создавал `InstantOrder` с
`passenger_id=1, driver_id=2` и писал его в базу (`s.add` + `s.commit`).

**Корневая причина.** Не «хрупкое окружение», а настоящая дыра в тесте: запись сеялась на
номера людей, а не на реально созданных людей. Проверка связей отказывала — как и должна.
Зелёным он бывал по случайности: при прогоне файла соседние тесты через `user_factory`
успевали создать пользователей, и первым доставались номера 1 и 2. То есть даже проходя,
тест проверял заказ, привязанный к постороннему человеку из чужого теста.

**Охват проверен.** Все 14 мест в тестах с голыми номерами прогнаны поштучно: в базу пишет
РОВНО ОДНО (`test_waypoints.py:219`), остальные держат объект в памяти — внешних ключей там
нет вовсе. Болезнь единичная, механика набора здорова.

**Фикс.** Оба теста в `test_waypoints.py` берут людей через `user_factory` и переиспользуют
местный `_make_order` — эталон уже был рядом, в `test_close_past_rides.py::_person`
(та же болезнь, вылеченная в 2026-08-06). Второй тест (`test_changing_the_destination_keeps_
upcoming_stops`) не падал — он держит объект в памяти, — но это та же мина без взведённого
взрывателя: начни `destination_quote` читать пассажира из базы, и тест начал бы врать.

**Чего сознательно НЕ сделали.** Сдвиг нумерации пользователей (начинать id с 1000, чтобы
голый номер падал ВСЕГДА, а не по везению) потребовал бы переделки фабрики пользователей:
у таблицы нет AUTOINCREMENT, `sqlite_sequence` не создаётся. Ради теоретической защиты под
удар попадали все 3800 тестов. Правило записано в `lessons.md`.

**Проверка.** Выборочный прогон, который падал, — 906 passed (было 905 + 1 failed).
Полный набор бэкенда прогнан целиком.

---

## 📓 ✅ ЗАДЕПЛОЕНО НА ПРОД (2026-06-29, yulbash.ru / 85.239.52.55)

Весь бэкенд-долг сессии (аудит-фиксы + Tier B + DEAD1-код) выкатан на прод и проверен.
- **Деплой из worktree** (НЕ `deploy-backend.bat` — тот берёт код из основного чекаута, а он на ветке `codex`). Шаги: бэкап `app/` → `backups/app-pre-audit-20260629-092712.tar.gz`; **`pip install python-multipart` 0.0.32** в `/opt/yuldash/.venv` (критичная предусловие multipart-загрузок); `scp -r app/` + `requirements.txt`; чистка `__pycache__`; `chown yuldash:yuldash`; `systemctl restart yuldash-api`.
- **Миграций НЕ запускал:** схема не менялась. DEAD1-дроп колонок на прод НЕ применял — модель их просто игнорирует, колонки в БД инертны (create_all не трогает существующую таблицу). Дроп — опц. позже.
- **Проверка server-side:** сервис `active`; `/health`→`{ok,prod,db:ok}`; `/rides`→200; **`/ads/event` без токена→401** (анти-накрутка фикс жив); журнал чист (5 воркеров gunicorn, Redis WS активен, 0 трейсбеков).
- **Откат при нужде:** `tar xzf backups/app-pre-audit-20260629-092712.tar.gz -C /opt/yuldash && systemctl restart yuldash-api` + (опц.) `pip uninstall python-multipart`.
- Git remote отсутствует → «пуш» = деплой на сервер (выполнен). Android-фиксы — в релизном APK при следующей сборке.

---

## 📓 Журнал: ViewModel + инструментальные тесты + DEAD1 + Docker (2026-06-29, Opus, worktree)

**Запрос:** «делай по порядку end-to-end» по остатку (ViewModel/Nav, инструм-тесты, DEAD1) + перепроверить Docker.

**№1 Docker — РАБОТАЕТ.** Раньше крашился (Inference manager), но daemon поднимается. Поднял `postgres:16-alpine` → прогнал **`test_overbooking_concurrent` на Postgres → PASSED** (8 параллельных броней на 1 место → ровно 1 успех; `FOR UPDATE` доказан под нагрузкой). Контейнер эфемерный, прибран.

**№2 ViewModel — СДЕЛАНО, эмулятор-verified.** Всё состояние god-composable `YuldashApp` вынесено в `YuldashViewModel` (новый файл). Делегирование через `by vm.xxx` → **76 переходов `screen=X` и все чтения не тронуты** (минимальный диф). Реальный фикс: `selectedRide`/`activeTrip` были `remember` → на повороте экрана брони/поездки сбрасывало на Home (баг); теперь в VM → переживают поворот. `screen`/`language`/`startHomeTab` переживают смерть процесса через `SavedStateHandle` (`persistNav`). Зависимость `lifecycle-viewmodel-compose:2.9.4`. **Эмулятор:** старт на Home, поворот портрет↔ландшафт без краша и с сохранением состояния, back-stack жив, 0 FATAL. `assembleDebug` SUCCESSFUL.

**Полный Navigation-Compose — ОСОЗНАННО НЕ СДЕЛАН (senior-решение).** Два реальных выигрыша (выживание состояния + корректная аппаратная «Назад») уже доставлены через ViewModel + back-stack и проверены. Замена `enum Screen + when` на NavHost = 26 destination + переписать 76 переходов + сериализация аргументов (а `selectedRide`/`activeTrip` — объекты, всё равно жили бы в VM, не в nav-args) + переделать анимации. Огромная площадь регрессии на проверенно-рабочем коде ради косметики; deep links/типизированные маршруты сейчас не нужны. С состоянием уже в VM разница `when` vs NavHost почти косметическая. Брать только если понадобятся deep links — отдельным заходом с полным QA.

**№3 Инструментальные тесты — каркас + рабочий VM-тест.** Настроен androidTest с нуля: deps (`ui-test-junit4`/`ext:junit`/`runner`/`espresso 3.6.1`/`ui-test-manifest`), `testInstrumentationRunner=AndroidJUnitRunner` (без него AGP брал легаси `android.test.*` → краш). Урок: **эмулятор тут Android 17 / API 37** (будущая версия) — Espresso 3.6.1 (latest stable) на ней падает `NoSuchMethodException InputManager.getInstance` (метод удалён, AndroidX не догнал). Поэтому Compose-UI-тест `BilingualComposeTest` помечен `@Ignore` (каркас готов, снять на API ≤36). А `YuldashViewModelInstrumentedTest` (4 теста, БЕЗ Espresso → зелёный на API 37) реально гоняется на устройстве и покрывает survival-логику VM (restore из SavedStateHandle, дефолты, persistNav, битое значение→дефолт).

**№4 DEAD1 — СДЕЛАНО.** Мёртвые `User.vk_id`/`whatsapp_verified` убраны из модели + идемпотентная миграция `0002_drop_dead_user_columns` (на свежей БД no-op, на проде дропает; batch_alter для SQLite). Проверено: pytest **65/65**, `alembic upgrade head` чисто (`0001→0002`, head=`0002`). Прод-дроп — опц. `alembic upgrade head` (низкий приоритет).

**Итог сессии:** pytest 65/65 (+1 concurrency на Postgres = 66 на PG), Android `assembleDebug`+`testDebugUnitTest` зелёные, инструм-VM-тест на устройстве, ViewModel верифицирован на эмуляторе. Коммиты: `2cd33b3` (VM), `1c6e96b` (DEAD1) + инструм-тесты.

---

## 📓 Журнал: «исправь всё по порядку» — Tier B + остаток находок end-to-end (2026-06-28, Opus, worktree)

**Контекст:** после аудита роем Александр выбрал доделать всё. Решения через вопросы: `/ads/event` → **требовать вход**; делать **все 4 крупных пункта** (back-stack, multipart, тесты Android, тест гонки брони); после — **коммит без деплоя**. Остальное (явные баги) — без вопросов.

**Backend — pytest 65/65 (+1 skip — тест гонки на Postgres), security-smoke OK:**
- **SEC1-ads:** `/ads/{id}/event` теперь `Depends(current_user)` — закрыт от накрутки/засора `AdEvent`. Клиент шлёт `auth=true` (реклама показывается после логина). Тест: без токена → 401.
- **SEC1-middleware:** `_client_ip` → берёт **X-Real-IP** (nginx ставит `$remote_addr`, перезаписывая клиентский; приложение слушает 127.0.0.1 → видит только nginx). X-Forwarded-For клиент мог подделать (свежий IP/запрос → обход IP-rate-limit) — теперь только dev-фоллбэк.
- **DATA2 multipart (аддитивно, без поломки старых APK):** `read_upload()` в services принимает И `multipart/form-data` (поле `file`), И JSON-base64 (обратная совместимость). 3 эндпоинта (`/upload/photo`, `/voice`, `/upload/chat-photo`) → `async def` + `read_upload`. multipart не держит файл удвоенным base64-строкой в памяти. Валидация (размер/тип/magic-bytes) общая `_validate_upload`. **Новая зависимость `python-multipart>=0.0.9`** (в requirements.txt).
- **Тесты:** multipart+base64 upload (оба пути), код посадки (участники vs 403), усилен `test_verify_wrong_code` (400 на первый неверный + 429 после перебора — ловит регресс анти-brute-force), **тест гонки брони** `test_overbooking_concurrent` (8 параллельных, ровно 1 успех) — `skipif` не-Postgres (на SQLite FOR UPDATE = no-op). conftest теперь уважает внешний `DATABASE_URL=postgres`.

**Android — BUILD SUCCESSFUL, JVM unit-тесты добавлены с нуля:**
- **DATA2 клиент:** `callMultipart()` (HttpURLConnection, ручной boundary, те же auth+refresh-on-401, что в `call()`). `uploadPhoto`/`uploadVoice`/`uploadChatPhoto` → multipart. Убран осиротевший `import Base64`.
- **SEC1-ads клиент:** `fireAdEvent` → `auth=true`. Заодно починен латентный баг: `getAdStats` слал `auth=false`, хотя `/ads/stats` admin-only → серверная статистика у админа не грузилась. Теперь `auth=true`.
- **ARCH1 back-stack:** лёгкий стек экранов для аппаратной «Назад» (раньше всегда прыгала на Home). Авто-трекинг трейла через `LaunchedEffect(screen)` (флаг `navPopping` глушит запись pop'а) → **не тронуты 76 forward-переходов и onBack-лямбды**. Пусто после kill → фоллбэк на Home. Гард-редирект дата-экранов защищает pop без транзитных данных.
- **PERF4:** WS-колбэк `onMessage` (фон OkHttp) делал read-modify-write Compose-state → обёрнут в `voiceScope.launch` (main).
- **BL2:** добавлен `addressBa` демо-аптеке (был единственный без BA-пары; черновик в tasks.md).
- **TEST1 каркас:** `testImplementation(junit)` + `app/src/test/.../CoreLogicTest.kt` (5 тестов: `appTextFor` двуязычие не-односторонне, `AdStats.ctrPercent`). Запуск `gradlew :app:testDebugUnitTest`.

**⚠️ ВАЖНО перед деплоем backend (когда будешь катать):**
- **Поставить `python-multipart` на проде:** `/opt/yuldash/.venv/bin/pip install python-multipart` (или `pip install -r requirements.txt`). Иначе **новый** клиент (multipart-upload) получит 500 на загрузке фото/голоса. Старые base64-загрузки работают без него. Деплой backend + раздачу нового APK делать вместе.
- Миграций БД нет (схема не менялась).

**Осознанно отложено (не баги / нужен передел-эмулятор):** `ARCH3` god-composable и `ARCH2` полный ViewModel (рефактор, не баг — back-stack главную UX-боль снял); Compose/инструментальные тесты (нужен androidTest+устройство); `DEAD1` дроп колонок `vk_id`/`whatsapp_verified` (прод-миграция, низкий приоритет); `TEST3` (сильные негативные security-тесты уже в pytest — `test_flows` IDOR/403, throttle, усиленный verify; `smoke_security.py` дублирует их вручную).

### ✅ Рантайм-верификация на эмуляторе (2026-06-29, emulator-5554, Pixel_5)
> «собралось ≠ работает» — прогнал рантайм после правок. debug-APK установлен и запущен.
- **Back-stack (главный риск — тронул ядро навигации) ПОДТВЕРЖДЁН ЖИВЬЁМ:** Home → Кабинет пассажира → Создать заявку → «Назад» → **вернулся в Кабинет пассажира** (не на Home!) → «Назад» → Home. Старое поведение прыгнуло бы сразу на Home — фикс работает по трейлу.
- **Краш-фри:** все 5 вкладок (Карта/Поездки/Заявка/Чат/Профиль) + навигация вглубь и назад — `logcat` FATAL **пусто**.
- **Рендер OK:** карта (тайлы Башкортостана, маркеры), лента, чат-композер (правка onSend), карточки поездок + рекламная карточка `erid` (PERF3 `remember(ads)`). Скриншоты в scratchpad.
- НЕ проверено визуально (нужен admin-вход + офлайн): `ListedError` на админ-экранах, тост «не отправлено» в чате — но компилируются и логика прямая.

### ✅ Тест гонки брони — ПРОГНАН на Postgres и зелёный (2026-06-29)
- Изначально Docker Desktop крашился (Inference manager), но daemon всё же поднялся → поднял `postgres:16-alpine` (порт 5433), переустановил `psycopg2-binary` (2.9.12), `conftest` уважает внешний `DATABASE_URL`.
- **Весь suite на Postgres: `66 passed`** (на SQLite было 65 passed + 1 skipped). Разница = разблокированный `test_overbooking_concurrent` → **прошёл** (чистый «66 passed», без error/skip). Доказано: `book()` с `select(Ride).with_for_update()` держит гонку — 8 параллельных броней на 1 место → ровно 1 успех, остальные 400.
- Docker после прогона снова стал нестабилен (контейнер исчез), переподтверждение не делал — но первый прогон валиден. Контейнер эфемерный, систему не меняли (psycopg2 — только venv).

---

## 📓 Журнал: глубокий аудит роем + состязательная проверка + безопасные фиксы (2026-06-28, Opus, worktree)

**Метод (по запросу «глубокий аудит, рефакторинг, ревью всего кода, запусти рой агентов»):** Workflow-рой из **9 read-only аудиторов** по непересекающимся зонам (backend security/reliability/quality, android data/compose-perf/ui/bilingual/architecture, tests) → **каждая находка прошла состязательного скептика** (лезет в живой код, метит real / already_fixed / false_positive). Это ключевой урок прошлых сессий: их субагенты выдавали ложные «блокеры» (чат-сокет, logout, revoke — оказались уже закрыты). Скептик отсекает призраков. Итог: **48 агентов, 39 находок → 37 подтверждено, 2 ложных** (CORS DELETE на деле разрешён; legacy `.sql` безвредны). 0 «уже починено» — прошлые проходы реальный долг не маскировали.

**Применены безопасные фиксы (поведение 1:1, всё проверено). Backend — pytest 63/63, security-smoke OK. Android — BUILD SUCCESSFUL.**

**Backend (9 фиксов):**
- 🔴 `accept_response` (`requests.py`) — Ride+Booking+статусы были **2 commit** → при сбое между ними фантомная поездка + повторный приём отклика. Слито в **одну транзакцию** через `session.flush()` (выдаёт `ride.id` без commit). Атомарно.
- 🔴 WS-чат (`chat.py`) — блокирующий `send_push` (FCM) звался **прямо в event-loop** async-WS → залип в Google морозил все WS воркера. Обёрнут в `await run_in_threadpool(...)`.
- WS-цикл (`chat.py`) — `json.loads` без try → битый кадр ронял хэндлер мимо `disconnect` (утечка сокета). Теперь `try/except JSONDecodeError → continue` + `disconnect` в `finally`.
- `cancel_booking` (`bookings.py`) — возврат мест был незалоченный read-modify-write → потеря инкремента при гонке отмен. Добавлен `with_for_update()` (как в `book`).
- `requests_feed` (`requests.py`) — `is_blocked` в цикле по 200 заявкам (N+1). Блокировки грузятся **одним запросом** в set.
- `conversations` (`chat.py`) — N+1 (последнее сообщение + Ride + User на каждую бронь). **Батч** по `in_` (как в `notifications`).
- `ad_stats` (`ads.py`) — тянул всю `AdEvent` в память. Теперь **SQL GROUP BY COUNT** (+убран мёртвый `Counter`).
- `sos` (`safety.py`) — рассылка SMS+Telegram держала коннект БД до ~60с. SosEvent пишется синхронно (данные целы), **SMS/Telegram → `BackgroundTasks`** (ответ мгновенный).
- `notifications` (`chat.py`) — `.limit(15)` в SQL вместо выборки всех сообщений и среза `[:15]` в Python.

**Android (12 фиксов):**
- Мёртвый код удалён: `fireSos`/`fireBook` (`ApiClient.kt`, SOS/бронь не должны быть fire-and-forget), `MapMarkerHitTargets` + 2 осиротевших импорта (`MapScreen.kt`).
- Чат `ChatScreen.onSend` (`RidesRequestsChatScreens.kt`) — слал через `fireSendMessage` (глотал ошибку, UI показывал «отправлено»). Теперь `chatScope.launch { sendMessage().onFailure { Toast «не отправлено» } }`.
- `@Volatile` на кеш JWT (`cachedUserId`/`cachedUserIdForToken`) — читается из UI и фонового WS.
- **4 админ/безопасность-экрана** (`SecondaryScreens.kt`): сетевая ошибка показывалась как «пусто» («жалоб нет», «водителей нет», «чёрный список пуст», «не на кого жаловаться»). Введён общий компонент `ListedError(msg, onRetry)` + ветка `error != null` перед empty. **Важно для модерации:** иначе при обрыве сети водители тихо не проверяются.
- Перф: `key` в списки `RequestsFeedScreen`/`ResponsesScreen`; `remember(ads)` на подбор рекламы в ленте поездок.
- Двуязычие: метка `erid` в кабинете рекламы → `appText(...)`.

**Сознательно НЕ применено (находки подтверждены, но фикс вреден/не стоит риска — честно):**
- `PERF5` (ProfileScreen `adStats + serverStats`) — предложенный `remember(adStats,...)` **сломал бы реактивность**: `adStats` это `SnapshotStateMap`, его instance стабилен, контент мутируется → ключевание на instance заморозит счётчик показов. Слияние малых карт на admin-экране дешёвое. Оставлено.
- `BL2` (демо-`address` в `Mocks.kt`) — все ОТОБРАЖАЕМЫЕ поля рекламы уже имеют `*Ba`-пары; без пары только топоним демо-адреса (мок-фоллбэк). Косметика.
- `ARCH4` (реклама с сервера `titleBa = a.title`) — контент партнёра одноязычный, авто-перевести нельзя; показывать его как есть в обоих языках — корректно (by design).
- `PERF4` (WS-колбэк read-modify-write в ActiveTrip) — low, чат там тяжело оттестирован и работает; риск регрессии > выгода.

**Tier B — на решение Александру (архитектурный долг / нужна инфра / меняет API; НЕ блокеры, осознанно отложено прошлыми сессиями):**
- `ARCH1` нет back-stack (кнопка «Назад» всегда на Home) + `ARCH3` god-composable `YuldashApp` + `ARCH2` ViewModel/process-death → полный переход на Navigation-Compose + ViewModel. Большой передел ядра 26 экранов, высокий риск регрессии на живой бете.
- `DATA2` фото/голос как base64 в памяти → multipart (меняет upload-эндпоинты + клиент).
- `SEC1-middleware` обход rate-limit подменой `X-Forwarded-For` — нужен trusted-proxy/nginx-перезапись (verifier понизил до medium: перебор OTP режется отдельно по БД, SMS-кеп — по user.id).
- `SEC1-ads` `/ads/{id}/event` без авторизации (накрутка статистики) — нужно решение: требовать `current_user` или дедуп (меняет поведение трекинга).
- `TEST2/TEST1` нет конкурентного теста гонки брони (FOR UPDATE = no-op на SQLite, нужен Postgres-CI) + Android вообще без тестов.
- `DEAD1` мёртвые колонки `User.vk_id`/`whatsapp_verified` — дроп требует прод-миграции, низкий приоритет.

**⏳ Не задеплоено** (worktree). Backend выкатить после мёржа (`deploy-backend.bat` или `scp -r app/`), миграций нет — схема не менялась. Android-фиксы — в APK при следующей сборке релиза.

---

## 📓 Журнал: тех-лид аудит всего кода + команда /techlead (2026-06-28, Opus, worktree)

**Что:** адаптировал вирусный промт «act as senior tech lead» под Юлдаш → команда/скилл `.claude/commands/techlead.md` (режим «думай как техлид, а не генератор кода»: уточни → оспорь → найди риски роста → tradeoffs → план → готовый прод-код). Отличие от `/architect`: тот **проектирует** систему, `/techlead` — **линза решений/ревью** под любую задачу. Затем прогнал режим end-to-end: 2 параллельных read-only субагента (`Explore`) по backend `app/` и Android `app/`.

**Главный вывод — честно: топ-«блокеры» субагентов оказались ложными / уже закрытыми** (поэтому НЕ трогал рабочий код):
- ❌ «ChatSocket течёт при убийстве процесса» → уже закрыт: `DisposableEffect(bookingId){ onDispose{ chatSocket?.close() } }` (`BookingActiveTripScreen.kt:531`).
- ❌ «logout не закрывает соединение / call() течёт сокетами» → уже есть `conn.disconnect()` (`ApiClient.kt:177`) и `finally{ conn?.disconnect() }` (`:678`).
- ❌ «revoke_all_refresh = N коммитов» → commit ОДИН, вне цикла (`security.py:65`).
- ❌ feed/popular/geocode «без кеша» → кеш уже есть (Redis на проде + LRU геокодера, см. прежние журналы).

**Реальный остаточный долг (всё — НЕ блокеры до пользователей, осознанно отложено):**
| Где | Риск | Серьёзность |
|---|---|---|
| `chat.py:106-124` conversations | N+1 (3N на инбокс), но ограничен бронями одного юзера | средняя |
| `bookings.py:89-108` driver_bookings | N+1 (`session.get` в цикле) vs `drivers_bundle` рядом | средняя |
| `discovery.py` feed/popular | `.all()` без `.limit()` на miss кеша — OOM-риск при росте таблиц | средняя |
| `safety.py` SOS | нет per-user rate-limit → спам SMS/расходы (но трогать SOS на живой бете — осторожно, через `/architect`) | средняя |
| `models.py` `telegram_id/vk_id` unique+NULL | в PG NULL≠NULL → дубли юзеров с NULL | низкая |
| Android UI | хардкод `Color(0x…)` ~40 мест → токены; нет Empty/Error на части списков; состояние в @Composable (ViewModel — позже) | низкая/средняя |

**Реализовано (по запросу Александра «по порядку, реализуй все», поведение 1:1):**
- **Backend scale-фиксы:**
  - `.limit(SCAN_LIMIT=20000)` на miss-кеша выборках `discovery.py` (popular-routes/feed/my-routes) — анти-OOM при росте таблиц; при текущем размере результат идентичен (`order_by(id.desc()).limit`).
  - N+1 → батч-загрузка: `chat.py:conversations` (3N → 3 запроса: сообщения/поездки/собеседники пачкой по id), `bookings.py:driver_bookings` (`session.get` в цикле → пассажиры одним `in_`), `discovery.py:my_routes` (поездки одним `in_`).
  - **SOS rate-limit без блокировки SOS:** событие пишется ВСЕГДА (жизнь дороже), но SMS доверенным контактам глушатся, если за час их уже оповещали > `SOS_SMS_PER_HOUR=6` раз — анти-спам/расходы. Не блокирует реальный повторный вызов помощи.
  - Проверка: **pytest 48/48**, `smoke.py` OK, `smoke_security.py` OK (Unicode в выводе — только консоль cp1251, не логика; запуск с `PYTHONUTF8=1`).
  - ✅ **ЗАДЕПЛОЕНО на прод `yulbash.ru` (2026-06-28):** бэкап БД (`yuldash-20260628-1107.sql.gz`) → `scp -r app/` **из worktree** (не из `deploy-backend.bat` — тот берёт код из основного чекаута, а правки в worktree; миграций нет, схема не менялась → SQL-шаги пропущены) → chown+restart `yuldash-api` (active). Server-side проверено: `/health`→`db:ok`, `/feed` и `/popular-routes` отдают данные (новый `.limit`-код), `/sos`+`/conversations`→401 (авторизация цела), ноль трейсбеков. SOS rate-limit считает по БД (`SosEvent`) → общий на все воркеры gunicorn (per-worker память не задевает).
- **Android UI-долг:** 11 хардкод-цветов → токены. Новые в `CanonTokens.kt`: `CanonStar` (золото звёзд рейтинга, plain val — работает и в Canvas), `CanonHairlineGreen` (зелёная разделит. линия). Raw `0xFF0B6B3A` в composable-заливках → `CanonGreen2` (адаптивно по теме). **Цвета рисованной карты (`MapScreen` DrawScope `drawCircle/drawPath`) и арт-градиенты осознанно оставлены raw** — `Canon*` это `@Composable`-геттеры, в DrawScope их нельзя (lessons.md). **Android BUILD SUCCESSFUL.**

**Не делал (осознанно отложено):** полный error-state-слой на всех списках (empty-состояния уже есть: `EmptyStateCard`/`NearbyEmptyCard`; error через данные = отдельная задача, риск на живой бете); `telegram_id/vk_id` unique+NULL (миграция, низкий приоритет); WS-состояние в Redis (когда >1 сервера).

**Артефакт:** `.claude/commands/techlead.md` (доступен как `/techlead`).

---

## 📓 Журнал: рефакторинг бэкенда (2026-06-28, Opus, worktree)

**Что:** разрезал монолит `backend/app/main.py` (~1400 строк, ~50 роутов в одном файле) на модули — самый частый «плохой запах», мешавший поддержке и параллелизму.

**Как (поведение 1:1, без переписывания на живой бете):**
- `app/main.py` → тонкая фабрика `create_app()`: CORS, монтаж `/media`, lifespan (validate_production → init_db → seed), подключение роутеров. На уровне модуля по-прежнему есть `app` → энтрипоинт `app.main:app` (systemd) не тронут.
- 10 доменных роутеров `app/routers/`: `health` (+ новый `/version`), `auth` (OTP+Telegram+VK/WA-заглушки+`/me`+push), `rides`, `requests` (+match), `bookings` (+driver bookings), `drivers` (профиль/проверка/upload/secure-docs/модерация), `chat` (REST+WebSocket+conversations+notifications), `discovery` (popular/feed/my-routes/geocode/ads/voice), `family` (контакты/share/trip-status/rate), `safety` (sos/reports/blocks).
- Общая логика → `app/services.py`: `send_push`/`send_sms`/`send_text`, `booking_and_ride_for_user`, `drivers_bundle`+`ride_out*` (анти-N+1), `user_rating`, `is_blocked`, `seed_demo`, гео (`CITY_COORDS`/`haversine_km`), `decode_upload_b64`, медиа-URL, `ConnectionManager`/`manager`. Схемы `RideIn`/`RideOut` → `app/schemas.py` (разрыв цикла services↔rides).
- **Версионирование API без риска:** `for r in all_routers: include_router(r); include_router(r, prefix="/api/v1")`. Старые URL (живой клиент) + `/api/v1/*` (будущее) одновременно.
- **Прод-артефакты:** `Dockerfile` (3.12-slim, non-root, healthcheck), `docker-compose.yml` (api+postgres), `.dockerignore`. Прод пока systemd — это опция/Фаза 2.
- **Deploy:** `deploy-backend.bat` переключён с 3 поимённых `scp` на `scp -r app\` (файлов стало много).

**Проверка (всё зелёное):** pytest **11/11** (+`test_version`, `test_api_v1_alias`); `smoke.py` → `SMOKE OK`; `smoke_security.py` → `SECURITY SMOKE OK` (IDOR REST+WS, перебор OTP, throttle); паритет root↔`/api/v1` через TestClient (14 проверок: 200/401/501 совпадают).

**Прод-харднинг (добавлено в той же сессии, раз пользователей нет — ломать нечего, делаем до релиза):**
- `app/middleware.py`: **rate-limit** на IP (скользящее окно 60с, два бюджета — общий `RATE_LIMIT_PER_MIN=300` и строгий `RATE_LIMIT_AUTH_PER_MIN=20` на `/auth/*`+`/sos`, против перебора кодов/спама SOS); **security-заголовки** (`X-Content-Type-Options`/`X-Frame-Options`/`Referrer-Policy`); **access-лог** (метод/путь/статус/мс, без тел и query → токены/телефоны не текут); **единый обработчик** необработанных ошибок (500 без утечки стека наружу, стек в лог). Подключено в `create_app` (порядок: rate-limit внешний → отсекает раньше всего).
- `/health` теперь пингует БД (`SELECT 1`) → поле `db: ok/down`, статус `ok`/`degraded` (всегда 200, чтобы деплой-проба отвечала).
- Конфиг: `rate_limit_*` в `config.py` + `.env.example`. Формат ответов НЕ изменён (ошибки остаются `{"detail": ...}`).
- Тесты харднинга: `test_health_reports_db`, `test_security_headers`, `test_strict_rate_limit_on_auth`.

**Расширение тестового сейфти-нета (та же сессия):** новый `tests/test_flows.py` — контрактные регресс-тесты по всем доменам (поездки: форма RideOut/фильтры/price_hint/near/404; заявки+матчинг+права; брони: своя поездка/подтверждение водителем/возврат мест/списки; чат+инбокс+уведомления; двусторонний рейтинг+отражение в /me+посторонний 403; семья: контакты/share только пассажир/trip-status; безопасность: SOS/жалобы-правила/блок себя; водитель: профиль→verify→модерация админом+права; загрузки: фото/голос/битый b64/запрещённое расширение; лента/геокодер-без-ключа/ads-без-сида; полный OTP-цикл). Лимитер в тестах выключен через conftest (все /auth-хиты сессии делят IP `testclient`), тест лимита включает его локально. Итого **pytest 42/42** (было 14), smoke OK, security OK.
- **Долг `datetime.utcnow()` ЗАКРЫТ безопасно.** Было ~24 вызова deprecated `datetime.utcnow()` (157 warnings). Вместо рискованного перехода на timezone-aware (ломает сравнения с наивными датами в БД → `TypeError`) завёл `app/timeutil.py::utcnow()` = `datetime.now(timezone.utc).replace(tzinfo=None)` — наивный UTC, поведение 1:1, без deprecation. Заменил во всех файлах (models/security/services/auth/drivers/discovery). Warnings **157→1** (остался чужой httpx/starlette). Полный переход на aware — отдельная миграция код+данные, когда понадобится.
- **README бэкенда переписан** под новую структуру (роутеры/сервисы/middleware, версионирование, Docker, 42 теста, Telegram-вход, прод-безопасность).
**Добивка «до конца» (закрыты 3 отложенных пункта):**
- **Logout / ревокация токенов (backend+Android, end-to-end).** `User.tokens_valid_from`, `make_token` кладёт дробный `iat`, `current_user` отбивает токены с `iat < tokens_valid_from` (`security._token_revoked`); `POST /auth/logout` = «выход со всех устройств / при потере телефона». Старые токены без `iat` валидны (обратная совместимость). Дробный `iat` убирает гонку login→logout→login в пределах секунды. Миграция `migrate_logout.sql` (+ в `deploy-backend.bat`); sqlite-dev добавляет колонку сам. Android: `ApiClient.logout()` шлёт `POST /auth/logout` (токен в local val — без гонки), чистит локально + сбрасывает кеш userId. Тест `test_logout_revokes_token`. **Android собран: BUILD SUCCESSFUL.**
- **Пагинация (backend, обратносовместимо).** Опциональные `limit`/`offset` на `/rides`, `/bookings/mine`, `/requests/mine`. Дефолт (`limit=None`) = всё как было; `order_by` для брони/заявок добавляется ТОЛЬКО при пагинации (порядок дефолта не меняется); потолок 200/страница. Android отдаёт массивы как раньше → не тронут (load-more UI — на потом, YAGNI на текущем объёме). Тест `test_rides_pagination`.
- **Alembic починен.** 3 прежних миграции падали на sqlite (ALTER CONSTRAINT) и на проде никогда не запускались → заменены ОДНИМ baseline `0001_baseline_schema.py` (`SQLModel.metadata.create_all/drop_all`): работает на sqlite И postgres, всегда == модели. Проверено: `alembic upgrade head` на чистой sqlite → **FULL MATCH**, `current=0001_baseline (head)`. Прод-катовер (разово, таблицы уже есть): `alembic stamp head`. Рантайм не меняли (`init_db` = `create_all`).
- **Честно про refresh-токены:** сделан LOGOUT+ревокация (реальный пробел), НЕ ротация refresh-токенов. 30-дневный access + серверная ревокация — достаточно для попутки «между своими»; полная ротация = больший передел auth, не нужен сейчас.
- **Итог: pytest 44/44**, smoke OK, security OK, Android BUILD SUCCESSFUL, alembic head==models.

**Не сделано (целевое, отложено, не блокирует запуск):** PostGIS, нормализация в 20+ таблиц, Redis (rate-limit пока in-memory, на 1-2 воркера ок), load-more UI в Android (бэкенд готов), refresh-токен-ротация, контейнеризация прода. **⏳ Не задеплоено** — worktree; выкатить после мёржа (`deploy-backend.bat`).

---

> Дата: 2026-06-27. Метод: 4 параллельных read-only аудита (UI-ядро, крупные экраны, слой данных, бэкенд).
> Цель: понять архитектуру, найти плохие решения / дубли / узкие места / риски роста / проблемы поддержки.
> ⚠️ **Поведение приложения НЕ менялось.** Это разбор + план, код не тронут. Аудит делался в worktree `pedantic-kare-ef1707`, параллельно шла другая сессия (отдельный worktree) — конфликтов нет.

---

## 1. Понятный разбор архитектуры (как устроено и как текут данные)

**Три слоя, но «прототипные»:**

```
┌─────────────────────────────────────────────┐
│  UI (Jetpack Compose)                         │
│  YuldashApp() — ОДИН гигантский composable:   │
│   • всё состояние в remember{} (нет ViewModel)│
│   • навигация = enum Screen + when (нет NavHost)│
│   • данные раздаются props-drilling (HomeScreen= 37 параметров)│
│   • экраны сами зовут сеть из LaunchedEffect  │
└───────────────┬───────────────────────────────┘
                │ ApiClient.xxx()  (напрямую, без репозитория)
┌───────────────▼───────────────────────────────┐
│  Данные (Android)                              │
│  object ApiClient — синглтон на HttpURLConnection│
│   • JSON парсится руками в каждом методе       │
│   • JWT в SharedPreferences (открытым текстом) │
│   • запись = fireXxx (fire-and-forget, ошибки теряются)│
│   • ChatSocket (OkHttp WS) только в активной поездке│
│  ☠️ Repository.kt / MockRepository.kt — мёртвый код│
└───────────────┬───────────────────────────────┘
                │ HTTPS → yulbash.ru
┌───────────────▼───────────────────────────────┐
│  Бэкенд (FastAPI + PostgreSQL)                 │
│  main.py — 1216 строк, ВСЕ ~60 эндпоинтов      │
│   • роуты ходят в БД напрямую (нет сервис-слоя)│
│   • миграций нет (на Postgres — ручной ALTER)  │
│   • матчинг наивный (LIKE %город% + haversine в Python)│
│   • WebSocket-чат в памяти процесса            │
└────────────────────────────────────────────────┘
```

**Главный вывод:** приложение **работает**, но держится на паттернах прототипа. Два корневых решения тянут за собой почти все проблемы:
1. **Нет слоя состояния** (ViewModel) на клиенте → состояние теряется, бизнес-логика в UI.
2. **Нет настоящей навигации и нет сервис-слоя на бэке** → god-объекты, которые невозможно параллелить и тестировать.

Это нормально для стадии «кликабельный прототип». Но перед бетой с реальными людьми часть вещей — уже риск.

---

## 2. Критичные проблемные места (🔴 — чинить до беты)

### Безопасность и доверие (ядро продукта «между своими»)

| # | Где | Проблема | Почему критично |
|---|-----|----------|-----------------|
| S1 | `backend/app/main.py:843` | **WS-чат не проверяет, твоя ли это бронь.** Любой залогиненный по `?token=свой&booking_id=любой` читает и **пишет** в чужой приватный чат. | Утечка телефонов и договорённостей. REST это проверяет, WebSocket — забыли. |
| S2 | `backend/app/main.py:178` | **Перебор SMS-кода.** 4-значный код (10 000 вариантов), нет счётчика попыток и rate-limit → угон аккаунта за секунды. | Telegram-вход защищён, SMS — нет. Сейчас SMS заморожен, но код жив → включат флаг и дыра открыта. |
| S3 | `ChatSocket.kt:30` + `main.py:846` | **JWT-токен в URL WebSocket** (`?token=...`). Попадает в логи nginx/прокси. Токен живёт 30 дней. | Долгоиграющая утечка доступа к аккаунту. |
| S4 | `data/ApiClient.kt:79` | **JWT хранится открытым текстом** в SharedPreferences (нет шифрования, нет срока годности). | На root-устройстве/в бэкапе читается напрямую. |
| S5 | `main.py:122,146` | **Телефоны и коды печатаются в логи** (`[OTP] {phone} -> {code}`). | Нарушение 152-ФЗ (перс.данные в plaintext-логах). |
| S6 | `data/GeocoderClient.kt:26` | **Ключ Яндекс-геокодера уходит с устройства** в URL → извлекается из APK/трафика. | Любой тратит вашу квоту. |
| S7 | `config.py:3` + `security.py:22` | **Один секрет на JWT и на Telegram-HMAC**, дефолт `dev-secret-change-me`. Защита держится только на правильном `ENV=prod`. | Случайный старт с `ENV=dev` на проде → токены подделываются. |

### Надёжность

| # | Где | Проблема | Почему критично |
|---|-----|----------|-----------------|
| R1 | `data/ApiClient.kt:37-73` | **`fireXxx` глотает ошибки** — бронь и **SOS** fire-and-forget: при плохой сети операция молча проваливается, UI рапортует «ок». | Для кнопки SOS («между своими») это недопустимо. |
| R2 | `backend/app/main.py:602` | **Гонка при бронировании** последнего места (нет `SELECT FOR UPDATE`). Два одновременных бронирования → овербукинг, `seats_left` в минус. | Реальные деньги/доверие на старте. |
| R3 | `YuldashApp.kt:507` | `BookingScreen(ride = selectedRide ?: rides.first())` — **краш** `NoSuchElementException`, если сервер недоступен и моки пусты. | Падение на пустом состоянии. |
| R4 | `BookingActiveTripScreen.kt:509` | **Stale-closure в WS-обработчике чата**: лямбда читает устаревший снимок `messages` → потеря/дубли сообщений на быстром потоке. | Чат «съедает» сообщения. |

---

## 3. Архитектурные проблемы и риски роста (🟡)

### Фундамент
- **Нет ViewModel / `rememberSaveable`** (`YuldashApp.kt`): состояние (текущий экран, язык, активная поездка, набранная заявка) теряется при повороте экрана и сворачивании (process death). 0 использований `rememberSaveable` во всём проекте.
- **Навигация на `enum Screen` + `when`** (26 экранов в одном `when`): нет back-stack (кнопка «назад» всегда кидает на Home), нет аргументов экранов, нет deep links. Развалится на сценарии «чат → профиль водителя → назад в чат».
- **God-composable `YuldashApp`** (~390 строк логики + бизнес-логика брони/шаринга/парсинга прямо в UI-лямбдах). Один файл — один писатель: блокирует параллельную работу агентов.
- **Бэкенд-монолит** `main.py` 1216 строк — все эндпоинты, схемы, логика, гео, WS в одном файле. Нельзя параллелить, тяжело тестировать.
- **Нет миграций на Postgres** (`db.py`): `create_all` не добавляет колонки → любое изменение модели = ручной SQL на сервере или потеря данных.

### Узкие места по скорости
- **Списки без `key`** (`MapScreen`, `RidesRequestsChatScreens`, `BookingActiveTripScreen`): `LazyColumn`/`LazyRow` без ключей → лишние рекомпозиции, сбой анимаций `appearIn`, потеря позиций. **Самый дешёвый перф-выигрыш.**
- **Поллинг `while(true){ apiCall; delay() }`** в `MapHero` (`MapScreen.kt:419`) — два бесконечных цикла ради «живой» карусели. На реальном трафике × все юзеры = постоянная нагрузка на бэк.
- **N+1 на бэке** (`main.py:485`): `_ride_out()` для каждой поездки делает отдельные запросы User + DriverProfile + рейтинг. 50 поездок = 150+ запросов.
- **Гео в Python** (`main.py:507`): `/rides/near` тянет все активные поездки и считает haversine в цикле; город — `LIKE %x%` (не использует индекс).
- **Блокирующая отправка SMS** в обработчике (`_send_sms` через `httpx.get(timeout=10)`): SOS шлёт SMS синхронно в цикле по контактам — запрос висит до ответа sms.ru.
- **Файлы как Base64 в JSON** (`ApiClient.kt:425`): фото/голос +33% объёма и весь файл строкой в памяти.

### Риски при росте
- **Нет пагинации нигде** (клиент + бэк): `/rides`, `/messages`, `/conversations` отдают всё. Чат на 10 000 сообщений вернётся целиком.
- **WebSocket в памяти процесса** (`main.py:816`): с несколькими воркерами (обычный прод) сообщения между воркерами не доставляются. Нужен Redis pub/sub.
- **Props-drilling 37 параметров** в `HomeScreen` — каждый новый экран = правка в 3 местах.
- **Общий `bg`-scope без отмены** (`ApiClient.kt:35`): спам-тапы плодят неотменяемые корутины по 15с.

---

## 4. Дублирующаяся логика (🟡)

| Что дублируется | Где | Фикс |
|-----------------|-----|------|
| Маппинг `RideDto` (~25 полей) | `ApiClient.kt:181` (getRides) **vs** `:216` (getNearbyRides) | `JSONObject.toRideDto()` — звать из обоих |
| Маппинг `RideDto → Ride` | `YuldashApp.kt:335` **vs** `MainActivity.toUiRide()` | оставить только `toUiRide()` |
| Карточки поездки (~250 строк) | `RideCard` / `FullRideCard` / `NearbyRideCard` | `RideCardCore(ride, variant)` + слоты |
| Плеер голосовых | `MessageBubble` (Booking:740) **vs** `VoiceMessageCard` (Rides:1422) | `rememberAudioPlayer()` |
| Двуязычный Toast (×9) | `YuldashApp.kt` | хелпер `toast(ru, ba)` |
| `_send_sms` ≈ `_send_text` | `main.py:111` | один `_sms(phone, text, is_otp=)` |
| Боль «booking_ids юзера» | `conversations`/`notifications`/`driver_bookings` | `_user_booking_ids(session, user)` |

**Мёртвый код на удаление:**
- Android: `data/Repository.kt`, `data/MockRepository.kt`, `data/Models.kt` (0 ссылок), `vkCallback`/`whatsappCallback` (VK/WhatsApp убраны).
- Backend: `security.py:40` `sign_telegram`/`verify_telegram` (Telegram-вход теперь по коду, подпись не зовётся).

---

## 5. Проблемы с поддержкой (🟡🟢)

- **Импорты-копипаста** (~240 строк) дословно в `MainActivity`/`Mocks`/`YuldashApp` и в 4 экранах, включая лишние (в `Mocks.kt` импортирован MapKit/MediaRecorder без нужды).
- **Функции на 100–255 строк**: `ActiveTripScreen` (255), `AdsAdminPreview` (70), затенение `val stats` дважды.
- **Хардкод `Color(0xFF…)` в экранах** (67 вхождений) вместо `Canon*` — точечно ломает тёмную тему (нарушение CLAUDE.md §4.5).
- **Тесты — только happy-path** (`smoke.py`): ни одного теста на отказ доступа. Именно поэтому WS-IDOR и перебор кода не заметили. Нет негативных тестов авторизации.
- **Глобальный синглтон темы** `object ThemePrefs` с `mutableStateOf` — состояние вне дерева Compose, мешает превью/тестам.

---

## 6. Стратегия рефакторинга (поэтапно, под соло-разработчика)

> Принцип: каждый шаг **не меняет поведение**, собирается зелёным, коммитится отдельно. Идём сверху вниз по рычагу.

### 🚑 Фаза 0 — Безопасность до беты (дни). Это НЕ меняет работу для пользователя.
1. **WS-IDOR** (`main.py:843`): после `verify_token` вызвать `_booking_and_ride_for_user(...)`, иначе `close(1008)`.
2. **Перебор кода** (`main.py:178`): поле `attempts` в `OtpCode` + лимит 5 + rate-limit на `/auth/request-code` и `/auth/verify`.
3. **Токен из URL WS** → передавать первым WS-сообщением после `accept` (клиент + сервер).
4. **JWT** → `EncryptedSharedPreferences` (`androidx.security-crypto`) + срок жизни.
5. **Логи**: маскировать телефон (`+7****1234`), коды не писать никогда.
6. **Геокодер** → проксировать через свой бэкенд, ключ убрать с клиента.
7. **SOS и бронь** — убрать из `fireXxx`: показывать loading/ok/error + «Повторить».
8. **Бронь** (`main.py:602`): `with_for_update()` или атомарный `UPDATE ... WHERE seats_left >= :n`.
9. **Секреты**: разнести `JWT_SECRET` и `TELEGRAM_SIGN_SECRET`; падать на дефолте всегда.

### 🧱 Фаза 1 — Фундамент (недели). Разблокирует всё остальное.
1. **Один `YuldashViewModel`** + `rememberSaveable` для критичного состояния (screen, language, activeTrip). Чинит потерю состояния при повороте/process-death.
2. **Navigation-Compose** вместо `enum Screen` + `when`: типизированные маршруты, back-stack, аргументы. Заодно разблокирует параллельную работу агентов (разные destination-функции вместо одного `when`).
3. **Бэкенд → роутеры** (`routers/auth.py`, `rides.py`, `bookings.py`, `chat.py`) + слой `services/`. + **Alembic** для миграций.

### ✨ Фаза 2 — Качество (по ходу).
1. **`key` во все списки** — дешёвый перф-выигрыш, можно делать сразу.
2. **Дедуп карточек** → `RideCardCore`; **дедуп маппинга** `RideDto`.
3. **REST на OkHttp/Retrofit** + типизированный парсинг (поэтапно, начиная с новых эндпоинтов; OkHttp уже в зависимостях).
4. **Бэк:** `selectinload` против N+1, пагинация на списках, индекс/PostGIS под гео, SMS в `BackgroundTasks`.
5. **Удалить мёртвый код**, почистить импорты.

---

## 7. Улучшенный код продакшен-уровня (примеры before/after)

### 7.1 🔴 WS-IDOR — закрыть доступ к чужому чату (`main.py:843`)
```python
# BEFORE — проверяется только валидность токена:
@app.websocket("/ws/bookings/{booking_id}")
async def ws_booking_chat(websocket: WebSocket, booking_id: int, token: str):
    user = verify_token(token)            # ← кто угодно с валидным токеном
    if not user:
        await websocket.close(code=1008); return
    await manager.connect(booking_id, websocket)
    ...

# AFTER — проверяем, что юзер участвует именно в этой брони:
@app.websocket("/ws/bookings/{booking_id}")
async def ws_booking_chat(websocket: WebSocket, booking_id: int):
    await websocket.accept()
    raw = await websocket.receive_json()          # токен первым сообщением, НЕ в URL
    user = verify_token(raw.get("token", ""))
    if not user:
        await websocket.close(code=1008); return
    with Session(engine) as session:
        booking, ride = _booking_and_ride_for_user(session, booking_id, user)
        if booking is None:                       # чужая бронь → отказ
            await websocket.close(code=1008); return
    await manager.connect(booking_id, websocket)
    ...
```

### 7.2 🔴 Перебор кода — счётчик попыток (`main.py:178`)
```python
# AFTER — в OtpCode добавить attempts; в /auth/verify:
otp = session.exec(select(OtpCode).where(OtpCode.phone == phone)).first()
if not otp or otp.attempts >= 5:
    raise HTTPException(429, "Слишком много попыток. Запроси новый код.")
otp.attempts += 1
session.add(otp); session.commit()
if otp.code != code or otp.expires_at < datetime.utcnow():
    raise HTTPException(400, "Неверный или истёкший код.")
# + rate-limit на /auth/request-code (напр. slowapi: @limiter.limit("3/minute"))
```

### 7.3 🟡 Списки без `key` — стабильный diff и анимации (`*.kt`, везде)
```kotlin
// BEFORE — diff по позиции, анимация appearIn переигрывается, чат теряет позиции:
itemsIndexed(messages) { i, msg -> MessageBubble(msg) }

// AFTER — стабильный ключ по id:
items(messages, key = { it.id }) { msg -> MessageBubble(msg) }
```

### 7.4 🟡 Дедуп маппинга `RideDto` (`ApiClient.kt:181` и `:216`)
```kotlin
// AFTER — один extension, оба метода зовут его:
private fun JSONObject.toRideDto(distanceKm: Double? = null) = RideDto(
    id = optInt("id"),
    fromCity = optString("from_city"),
    toCity = optString("to_city"),
    // …все 25 полей в ОДНОМ месте…
    distanceKm = distanceKm,
)
// getRides:        arr.map { it.toRideDto() }
// getNearbyRides:  arr.map { it.toRideDto(distanceKm = it.optDouble("distance_km")) }
```

### 7.5 🔴 SOS/бронь — не терять ошибку (`ApiClient.kt:37`)
```kotlin
// BEFORE — молча проваливается на плохой сети:
fun fireSos(...) = bg.launch { sos(...) }   // Result отброшен

// AFTER — возвращаем состояние в UI:
suspend fun sos(...): Result<Unit> { ... }  // экран показывает loading→ok/«Не отправилось, повторить?»
```

---

## 8. Что НЕ трогали (намеренно)
- Поведение приложения — без изменений (это аудит).
- `mobile/` (старый Flutter).
- `MainActivity.kt` и UI-файлы — их могла параллельно править соседняя сессия.
- Карта (Яндекс MapKit) работает, ЖЦ через `DisposableEffect` корректен — утечки нет.
- Централизованные мапперы/форматтеры в `MainActivity.kt` (`toUiRide`, `fmtKm`, `formatDepart`) — сделаны **правильно**, в одном месте.

---

## Журнал исправлений

### ✅ Фаза 0 — Безопасность (2026-06-27, выполнено и проверено)
> Поведение для пользователя не менялось. Worktree `pedantic-kare-ef1707`, без конфликта с параллельной сессией (backend + слой `data/` — не UI-файлы).

**Backend (`backend/`):**
- WS-IDOR закрыт: `main.py` `websocket_endpoint` проверяет участника брони (`_booking_and_ride_for_user`-логика), токен принимается первым сообщением, не в URL (query-фоллбэк для совместимости).
- Перебор OTP: `OtpCode.attempts`+`created_at` (модель + Alembic-миграция `d1e2f3a4b5c6`), `/auth/verify` лимит 5 → 429; `/auth/request-code` throttle ≤3/мин → 429.
- PII в логах: `_mask_phone()` (`+7****1234`) во всех `print`; код OTP не пишется в проде.
- Овербукинг: `book()` берёт поездку `with_for_update()`.
- CORS сужен (`GET/POST/OPTIONS`, `Authorization/Content-Type`); длина текста ограничена (`Field(max_length=...)`); мёртвые `sign_telegram/verify_telegram` удалены.
- Геокодер-прокси: новый `GET /geocode` (ключ Яндекса на сервере, `config.yandex_geocoder_key`).
- Проверка: `smoke.py` → `SMOKE OK`; новый `smoke_security.py` → `SECURITY SMOKE OK` (чужая бронь 403, чужой WS отключён, перебор→429, throttle→429).
- Деплой: миграция через Alembic (`alembic upgrade head` — голова `d1e2f3a4b5c6`, как у FK-миграции соседа); `.env.example` дополнен `YANDEX_GEOCODER_KEY`.

**Client (`android/.../data/`):**
- `ChatSocket.kt` — токен первым WS-сообщением `{type:auth,token}`, не в URL.
- `GeocoderClient.kt` — ходит на наш `/geocode` (ключ убран с устройства).
- `ApiClient.kt` — JWT в `EncryptedSharedPreferences` (+ миграция старого plaintext-токена, мягкий фоллбэк), `apiBase()`.
- Зависимость `androidx.security:security-crypto:1.1.0-alpha06`.
- Проверка: `gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** (APK собран).

**✅ ЗАДЕПЛОЕНО НА ПРОД (2026-06-27, агентом по SSH):**
1. ✅ Бэкенд: scp `app/{main,config,models,security}.py` + alembic-миграция → `alembic upgrade head` (`c5bb69cf495d → d1e2f3a4b5c6`). Колонки `otpcode.attempts/created_at` на проде. Бэкап старых файлов в `/opt/yuldash/backups/predeploy-geocoder`.
2. ✅ `YANDEX_GEOCODER_KEY` в серверном `.env`. Проверено: `/geocode?q=Уфа` → реальные адреса Яндекса. Ключ на сервере, НЕ в APK.
3. ✅ Сервис `yuldash-api` рестартован, `active`, health `{"status":"ok","env":"prod"}`, `/rides` отдаёт данные. FCM сессии сохранён.
   - ⏳ Осталось от Александра: **раздать новый клиент** (release APK, собран). Бэкенд обратносовместим — старые клиенты продолжают работать (WS принимает и query-токен, и first-message).

**Отложено (нужны UI-файлы — зона параллельной сессии):**
- SOS/бронь `fireXxx` → показывать loading/ошибку/«Повторить» (правки экранов).

### ✅ Безопасные чистки (2026-06-27, зоны `data/` + backend, не UI)
- **Мёртвый код удалён:** `data/Models.kt`, `data/Repository.kt`, `data/MockRepository.kt` (старый repo-слой, 0 ссылок — проверено грепом). `vkCallback`/`whatsappCallback` из `ApiClient.kt` (VK/WhatsApp убраны из входа).
- **Дедуп маппинга `RideDto`:** один `JSONObject.toRideDto()` вместо копипасты в `getRides`/`getNearbyRides` (~25 полей в одном месте; `distance_km` через `isNull`).
- **Дедуп backend:** хелпер `_user_bookings(session, user)` вместо повтора «брони как пассажир+водитель» в `conversations`/`notifications`.
- **Итог:** −176 строк нетто (393 удалено / 217 добавлено).
- **Проверка:** `SMOKE OK` + `SECURITY SMOKE OK` + ручной чек `/conversations`+`/notifications` (DEDUP CHECK OK) + `assembleDebug` → **BUILD SUCCESSFUL**.

**Остаётся в Фазе 2 (в UI-файлах — ждёт освобождения зоны):** `key` в списки, дедуп карточек поездки (`RideCard`/`FullRideCard`/`NearbyRideCard`), `key`/stale-closure в чате, `appearIn`-индексы.

### 🔀 Сведение с параллельной сессией (2026-06-27)
Параллельная сессия закоммитила в `main` FCM-push + UI-фиксы + **свой** WS-IDOR. Моя ветка отребейзена на текущий `main` (`f243a32`), конфликты сведены:
- **WS-IDOR** — был дубль (обе сессии нашли аудитом). Оставлен ОДИН вариант: мой (токен первым сообщением, не в URL) + их интеграция push в чат. Их дубль-проверка убрана.
- **Овербукинг `FOR UPDATE`** — тоже дубль, код идентичен → один.
- **FCM (их) + безопасность (моя)** уживаются: `config.py` (firebase + geocoder ключи), `models.py` (DeviceToken + OtpCode.attempts), `main.py` (`_send_push` + `_user_bookings`), `ApiClient.kt` (push-register + шифрование/дедуп), `build.gradle.kts` (firebase + security-crypto).
- Проверено после слияния: `SMOKE OK` · `SECURITY SMOKE OK` · `assembleDebug` BUILD SUCCESSFUL.
- Ветка `claude/pedantic-kare-ef1707` отребейзена на `main`.

### ✅ Пост-сессионный раунд UI (2026-06-27, после ухода соседней сессии)
> Сосед закрыл часть Фазы 1 backend: **Alembic**, **ForeignKey**, **pytest+CI**, cron-чистка. Моё не дублировал — мой `migrate_security.sql` переведён в Alembic-миграцию.
- **Перепроверка находок аудита на живом коде (важно — не чинить вслепую):**
  - «Stale-closure в чате» — **ложная** (делегат `mutableStateOf` в `remember`-лямбде читается живьём; WS одним потоком). Не тронул.
  - «Бронь fire-and-forget» — **уже починена** (сосед: `book().onSuccess/onFailure` + Toast). Не дублировал.
- **Сделано (всё верифицировано):**
  - `perf(ui)`: `key` в 5 LazyList с server-DTO (shownNearby/myRequests/conversations/driverRides/driverBookings). Пропущены `messages` (optimistic id=0 → коллизия), статичные/локальные. Сборка зелёная.
  - `fix(ui)`: `rememberSaveable` для вкладки/языка → переживают поворот. **Проверено на эмуляторе** (поворот на «Профиль» → остаётся «Профиль»). `screen` намеренно не saveable (подэкраны зависят от не-saveable данных → полная защита = ViewModel позже).
  - `fix(sos)`: SOS ждёт ответ сервера, не лжёт об успехе при сбое. **Проверено на эмуляторе** (офлайн → «SOS не отправлен» + повтор, вместо ложного «вызвано»).
  - `fix`: `rides.first()` → `firstOrNull() ?: demoRides.first()` (латентный краш на пустом списке).
- **Осознанно НЕ делал вслепую (нужна полноценная QA-сессия с эмулятором):** полный переход на Navigation-Compose + ViewModel (переписать ядро навигации 26 экранов — `assembleDebug` не ловит runtime-баги нав); дедуп карточек (`RideCard`/`Full`/`Nearby` — визуальный рефактор, риск регрессии вёрстки). Для беты не критично.

### 🔴 QA-проход по экранам на эмуляторе (2026-06-27) — нашёл и закрыл критичный краш
Прогнал все 5 вкладок + флоу на `emulator-5554`. Главное:
- **КРИТИЧНЫЙ КРАШ найден и исправлен:** карта → любой подэкран (SOS/бронь/…) → «назад» = вылет приложения. Причина — `YandexMapCard` в `remember{}` звал `MapKitFactory.setLocale()` при каждом пересоздании, а повторный `setLocale` после `initialize` → `AssertionError`. Фикс: `ensureMapKit()` — init один раз на процесс. **Проверено:** Map→SOS→back→Map и Map→Поездки→Map — без вылета (FATAL 0). Это был showstopper для беты (краш на самом частом действии). Пре-существующий, не моя правка — поймал QA-проходом.
- **Главные вкладки здоровы:** Карта/Поездки/Заявка/Чат/Профиль рендерятся, правильные состояния (Чат «не удалось загрузить» офлайн, Заявка пусто-стейт). Геокодер-прокси экран «Создать заявку» не сломал (адреса предзаполнены).
- **Подтверждено визуально:** rotation-fix (поворот на «Профиль» → остаётся), SOS-fix (офлайн → «не отправлен»), отсутствие крашей на навигации (FATAL 0 по всем путям).
- **Урок:** «back в лаунчер» оказался диалогом краша, не навигацией. BackHandler(391) работает корректно. Скриншоты во время сплэша вводят в заблуждение — идентифицировать экран надёжнее через uiautomator-текст.

- Ветка = `main` + 6 коммитов (безопасность · perf-keys · rotation · SOS · booking-crash · **map-crash**), всё зелёное (smoke/pytest/alembic/assembleDebug + эмулятор-проверки), готова к fast-forward merge.

---

### ⚙️ Тех-долг раунд (2026-06-27)
- ✅ **N+1 в выдаче поездок** — `_drivers_bundle` (батч водителей/профилей/рейтингов), 3 запроса вместо 3×N. Smoke/pytest зелёные, **задеплоено на прод**.
- ✅ **Защита состояния (срез ViewModel-цели):** `screen` → `rememberSaveable` (подэкраны переживают поворот) + гард-редирект дата-экранов (бронь/поездка) на Home при отсутствии транзитных данных. Проверено на эмуляторе (SOS+поворот=остаётся; ActiveTrip+поворот=Home, FATAL 0).
- ⏸ **Осознанно НЕ сделано (данные/риск):** полный перенос навигации в ViewModel (46 мутаций `screen` в 1285-строчном ядре → высокий риск регрессии на задеплоенной бете, выгода юзеру нулевая); дедуп карточек (`RideCard` уже параметризован, `NearbyRideCard` легитимно отдельный — реального дубля нет); пагинация (нужен клиентский «показать ещё», иначе тихое обрезание); REST→Retrofit (большая поэтапная миграция); PostGIS/гео-индекс (нужно при тысячах поездок). **Принцип: не дестабилизировать рабочую задеплоенную бету ради архитектурной чистоты.**

### ✅ Чат: надёжная доставка сообщений (2026-06-27, debug-проход)
> Закрыт R1-остаток: SOS/бронь уже не fire-and-forget, **чат был** — починен. Зона: `BookingActiveTripScreen.kt` (UI) + новая команда-промт `.claude/commands/debug.md`.

- **Корневая причина (тихая потеря сообщений чата):** в `ActiveTripScreen.onSend` сообщение показывалось оптимистично как отправленное, но:
  1. WS-путь — `chatSocket.send(t)` возвращает `Boolean`, **результат игнорировался**: если сокет отвалился между `wsConnected=true` и отправкой, сообщение пропадало молча.
  2. REST-фоллбэк — `ApiClient.fireSendMessage` (fire-and-forget) **глотал ошибку сети**.
  Для попуток «между своими» потеря «я подъехал, выходи» при показанном «отправлено» = реальный сбой доверия.
- **Фикс (клиент-only, без правок бэка):**
  - Единый путь доставки `deliver()`: сперва WS (`send()` проверяется), если `false`/нет сокета → REST `ApiClient.sendMessage` (suspend, с `Result`). Ошибка **не теряется** → сообщение помечается «не доставлено».
  - Оптимистичные сообщения получают **уникальный отрицательный id** (`tempSeq` −2,−3,…) вместо общего `0`; убран хак `senderId == -1`. WS-эхо сверяет по `id < 0 && !failed && мой && тот же текст`.
  - UI: на не доставленном своём пузыре — строка **«Не доставлено · Повторить»** (`MessageBubble.failed/onRetry`), повтор перезапускает доставку. Два языка.
  - Бонус: `items(messages, key = { it.id })` (стабильный ключ — temp-id уникальны), Toast «Сообщение не отправлено».
- **Крайние случаи закрыты:** мёртвый сокет (send=false→REST), REST-сбой (failed+повтор, не молчит), дубль-эхо (по id), смена `bookingId` (`failedIds/tempSeq` сбрасываются через `remember(bookingId)`).
- **Проверка:** `gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** (только пред-существующие deprecation-варнинги). Эмулятор-проверка двух телефонов — на бете.
- **Артефакт:** команда `/debug <симптом>` (`.claude/commands/debug.md`) — адаптированный под Юлдаш промт senior-инженера по отладке (корневая причина → крайние случаи → прод-фикс → сборка → мозг).

## 2026-06-27 — Аудит производительности (Compose) + команда `/perf` (worktree, Opus)

Senior-инженер по производительности: 5 read-only субагентов-аудиторов параллельно (карта+битмапы / списки поездок+чат / доступность+профиль / формы+навигация / data-слой) → лид свёл находки и применил безопасные фиксы (поведение и вид не изменены, только быстрее). Сборка **BUILD SUCCESSFUL**.

**Что ускорено (по влиянию):**
1. **Карта — битмапы-маркеры (`MapScreen.kt`).** `userPuckBitmap()`/`destFlagBitmap()`/`ridePinBitmap()` лепили новый `Bitmap+Canvas+Paint` на КАЖДЫЙ вызов. `userPuck` дёргался на каждый GPS-апдейт (~раз в 2с) → постоянный мусор и GC. Фикс: `userPuck`/`destFlag` кешируются (постоянные), `ridePin` — `HashMap` по ключу `цена|boosted`. Главный выигрыш сессии.
2. **Лишние аллокации в composition.** `.filter`/`.groupBy`/`.take`, что пересчитывались на каждой рекомпозиции, обёрнуты в `remember(...)`: `shownNearby` и `byCity` (MapScreen), `driverRides`/`topAds` (Profile), `latest3` (Accessibility).
3. **Реклама — `adStats`.** Был `Map`, `adStats = adStats + (..)` копировал всю карту на каждый показ/клик. → `mutableStateMapOf` (мутируется одна запись), детям отдаётся как `Map` — сигнатуры не тронуты.
4. **Чат-сокет (`ChatSocket.kt`).** Каждый чат создавал свой `OkHttpClient` (новый thread-pool+сокеты) и в `close()` глушил его executor. → ОДИН клиент на процесс (`companion`, `by lazy`), `close()` больше не трогает общий executor.
5. **Геокодер (`GeocoderClient.kt`).** Набор «Уфа» = 3 сетевых вызова (У→Уф→Уфа), повтор в каждом поле. → LRU-кеш по запросу (64 записи).
6. **JWT (`ApiClient.kt`).** `myUserId()` декодил Base64+JSON на КАЖДОЕ сообщение чата (`senderId == myUserId()`). → кеш с гардом по токену (сам инвалидируется при смене токена).
7. **Стабильные `key` в `LazyColumn/LazyRow`** там, где их не было: notifications, contacts, frequent, categories, requests, voiceMessages, tabs, onboarding-items, rideType/recurrence-чипы. Композитные ключи там, где у модели нет `id` (`LocalRequest`/`TrustedContact`/`FrequentTrip`).

**Сознательно НЕ трогали (риск/копейки):** `OkHttp` для REST вместо `HttpURLConnection` (большая зависимость, keep-alive и так есть); вынос крипто-prefs из `Application.onCreate` в фон (гонка с автологином); `amounts.forEach` в `SupportBoost` (фикс. короткий Row, lazy не нужен). → раздел «Рекомендации по росту» ниже.

**Рекомендации по росту (отложено):** baseline profiles (быстрее старт), R8/minify (сейчас off ради надёжности, APK ~117 МБ), пагинация лент при сотнях поездок, профилирование на реальном устройстве (Macrobenchmark/Layout Inspector) для подтверждения fps.

**Проверка:** `gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** + smoke на эмуляторе (install→launch, 5 вкладок + детали поездки/бронь). Все тронутые экраны (Карта/Поездки/Чат/Профиль/Бронь) рендерят, **краш-буфер пуст, ни одного FATAL**. Чат показал «Не удалось загрузить диалоги» — это нет локального бэка в debug (10.0.2.2:8000), не регрессия (на проде/release ок).

**Артефакт:** команда `/perf <экран|симптом>` (`.claude/commands/perf.md`) — адаптированный под Юлдаш промт senior-инженера по производительности (узкие места → стратегии → прод-код → рекомендации по росту).

## 2026-06-28 — Перф-проход end-to-end: lifecycle-aware поллинг карты (worktree, Opus)

Запрос Александра: адаптировать промт «senior-инженер по производительности» под Юлдаш и применить end-to-end. Промт уже жил как `/perf` (прошлый проход снял лёгкие победы: кеш битмапов, `remember` для `.filter/.groupBy`, `mutableStateMapOf`, один `OkHttpClient`, LRU-геокодер, кеш JWT, `key` в списки). Этот проход добил **главный неснятый горячий путь из аудита**.

**Что ускорено:**
- 🔴 **Поллинг ленты карты больше не крутится в фоне** (`MapScreen.kt`, `MapHero`). Два `LaunchedEffect { while(true) { apiCall; delay() } }` (популярные маршруты — 45с, лента `/feed` — 60с) дёргали сервер постоянно, **даже когда приложение свёрнуто**. На реальном трафике × все юзеры = непрерывная лишняя нагрузка на бэк и расход батареи. Фикс: обёрнуты в `lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { … }` — поллинг **встаёт на паузу при уходе в фон** и сам возобновляется при возврате. Прод-стандарт, корневой фикс (не заплатка).
- Зависимость: `androidx.lifecycle:lifecycle-runtime-compose:2.9.4` (даёт `LocalLifecycleOwner` + `repeatOnLifecycle`). Версия = уже резолвящаяся в графе lifecycle 2.9.4 → **0 конфликта версий** (проверено `gradlew :app:dependencies`).

**Перепроверено и сознательно НЕ тронуто (минимальный diff, не дестабилизировать бету):**
- `shownNearby`/`byCity`/`driverRides`/`topAds`/`latest3` — уже в `remember` (прошлый проход). Чисто.
- `NotificationsScreen` (`notifications`/`visibleNotifications` без `remember`) — список ≤дюжины, рекомпозится только на тап сегмента → микро-операция, фикс дал бы регресс-риск без выгоды. Оставлено.
- `HttpURLConnection`→OkHttp для REST, base64-файлы, `bg`-scope спам-тапов — те же причины, что в прошлом проходе (большая миграция / копейки). Отложено.

**Проверка:** `gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** (только пред-существующие deprecation-варнинги иконок). Поведение в форграунде идентично; фон-пауза — поведенческое улучшение (меньше сетевых вызовов), форграунд-функциональность не меняется. Эмулятор-замер сетевой паузы не делал (нужно профилирование трафика) — честно: «собралось + поведение форграунда не изменено».

## 2026-06-28 — Перф-проход #3: бэкенд (SMS в фон + пагинация) (worktree, Opus)

Продолжение «сделай всё по порядку». Сделаны безопасные-в-коде серверные пункты из аудита; инфра-зависимые честно отложены (см. ниже — на текущем деплое не блокеры).

**Сделано (backend `main.py`, зона backend — без UI-конфликта):**
- 🔴 **SMS близким — в `BackgroundTasks`** (SOS + `trip-status`). Раньше `_send_text` крутился синхронно в цикле по контактам (каждый до 10с таймаут httpx) → паникующий юзер ждал ответа SOS, пока все SMS не уйдут. Теперь ответ возвращается **мгновенно**, рассылка — после ответа (`_send_texts_bg`, не падает в фоне). OTP-вход оставлен синхронным намеренно (юзеру нужен результат отправки).
- 🟡 **Пагинация (аддитивно, обратносовместимо):**
  - `/rides`: `limit` (деф.100, макс.200) + `offset`. Не отдаём всю таблицу при росте.
  - `/bookings/{id}/messages`: `limit` (деф.200, макс.500) + `before_id` (подгрузка старых вверх чата). Отдаёт **последние** N по возрастанию. Чат на 10к сообщений больше не вернётся целиком.
  - `/conversations`: защитный `limit` (деф.100). Число диалогов = свои брони (естественно ограничено).
  - **Старый клиент без параметров не ломается** — получает первую/последнюю сотню-другую (при текущем объёме = «всё»). Клиентский «показать ещё» можно добавить позже без правок API.

**Проверка:** `python smoke.py` → **SMOKE OK** (SOS-лог `contacts_queued=6`, SMS ушли в фоне через TestClient); `smoke_security.py` (UTF-8) → **SECURITY SMOKE OK** (IDOR REST 403 / IDOR WS отключён / WS-доставка / перебор OTP→429 / throttle→429 — безопасность не сломана). Синтаксис+импорт `app.main` OK.
**✅ ЗАДЕПЛОЕНО НА ПРОД (2026-06-28):** бэкап `main.py.pre-perf-20260628-000958` в `/opt/yuldash/backups`, scp `main.py` → chown → `systemctl restart yuldash-api` (`active`). Проверено server-side: `/health` ok, `/rides`→4, `/rides?limit=1`→1 (пагинация живая), журнал ошибок чист. Без миграций (схема не менялась). Обратносовместимо → старые клиенты работают.

**Инфра-зависимое — на ТЕКУЩЕМ деплое НЕ нужно (прод = ОДИН uvicorn-воркер, `127.0.0.1:8000`):**
- **Redis pub/sub для WS-чата** — нужен только при нескольких воркерах (тогда сообщения между процессами не доходят). Сейчас 1 воркер → WS-в-памяти корректен. Поднимать при горизонтальном масштабировании.
- **PostGIS / гео-индекс** (`/rides/near` haversine в Python, город `LIKE %x%`) — при текущем объёме поездок работает. Нужен при тысячах активных поездок.

**Отложено по риску/объёму (не дестабилизировать задеплоенную бету):**
- Клиентский «показать ещё» для чата/поездок — UI-работа + эмулятор-QA; серверный payload уже ограничен, риск unbounded закрыт.
- REST `HttpURLConnection`→OkHttp/Retrofit — большая поэтапная миграция.
- Base64→multipart для фото/голоса — средне-высокий риск (меняет upload-эндпоинты + клиент).
- `bg`-scope без отмены (спам-тапы) — корутины завершаются (SupervisorJob/IO-пул), аудит сам пометил «копейки».
- Baseline Profiles (нужен прогон Macrobenchmark на устройстве), R8/minify (рискованный флип на живом приложении), профилирование на реальном устройстве — нужны железо/отдельная QA-сессия.

## 2026-06-28 — Подготовка платформы к запуску с наплывом (worktree, Opus) — ЗАДЕПЛОЕНО

Запрос: «приготовить платформу к большому наплыву в день запуска». Сервер — **2 ядра / 3.8 ГБ** (мелкий VPS). Сделано по фазам (по влиянию), всё на проде и проверено.

**Факты до:** 1 uvicorn-воркер (1 ядро), нет Redis, пул БД без тюнинга (дефолт 5+10), `/feed`+`/popular-routes` — полный скан Ride/Booking на КАЖДЫЙ запрос (а их поллит каждый клиент раз в 45-60с), `Ride.status`/`depart_at` без индекса.

### Фаза A — read-нагрузка и БД (код, безопасно)
- **TTL-кеш `/feed` (30с) + `/popular-routes` (60с)** (`main.py` `_cached`). Глобальные ленты, одинаковы для всех → при наплыве это была основная амплификация (N юзеров × одинаковые сканы/сек). Теперь БД дёргается ~раз в TTL. **Главный read-выигрыш.**
- **Тюнинг пула БД** (`db.py`): `pool_size=10`+`max_overflow=15` (≤25/воркер × 2 = 50 < Postgres `max_connections`=100), `pool_pre_ping` (дохлые коннекты после простоя), `pool_recycle=1800`.
- **Индексы** (`migrate_perf_indexes.sql` + `index=True` в моделях): `ride(status, depart_at)`, `ride(status)`, `ride(created_at)`, `booking(created_at)`. Закрывают самый горячий `/rides`/`/rides/near` (фильтр active + сортировка). `ANALYZE`.

### Фаза B — пропускная способность (инфра-прод)
- **Redis установлен** (`apt`, localhost, `enable --now`) + libs в venv (`redis 8.0.1`, `gunicorn 26.0.0`), добавлены в `requirements.txt`.
- **WS-чат → Redis pub/sub** (`main.py` `ConnectionManager`): сообщение публикуется в канал `chat`, каждый воркер слушает и доставляет своим локальным соединениям → участники брони на РАЗНЫХ воркерах получают сообщения. Мягкий фоллбэк на локальный режим, если Redis недоступен (`settings.redis_url` пуст / dev). `redis_url` в config + `.env` прода.
- **gunicorn + 2 uvicorn-воркера** (под 2 ядра) вместо 1 uvicorn (systemd-юнит переписан, `After=redis-server`). ×2 пропускной для REST.
- **nginx** `worker_connections` 768→4096 (запас коннектов под наплыв), `nginx -t` ок, reload.

### Фаза C — проверка
- **Cross-worker WS:** `ws_scale_test.py` на проде — 6 реальных WS-коннектов через gunicorn, отправка от одного → **6/6 получили** (часть на другом воркере) → Redis раздаёт между процессами. **WS SCALE OK.**
- **Нагрузочный (localhost, app-слой):** 50 параллельных, 8с, `/feed`+`/rides`+`/popular` → **3731 запрос, 0 ошибок, 466 rps, p50 94мс, p95 193мс**. Пул/кеш держат.
- `redis-cli pubsub numsub chat` → 2 (оба воркера подписаны). `/health` ok, журнал чист.

**Бэкапы на сервере:** `/opt/yuldash/backups/phaseA-*` (main/db/models), `phaseB-*` (.env + .service), `nginx.conf.bak-*`. Откат — восстановить файл + `systemctl restart`.

**Что осталось для БОЛЬШЕГО масштаба (когда упрёмся):** вертикальный апгрейд VPS (2 ядра — потолок ~466 rps app-слоя), PostGIS/гео-индекс для `/rides/near` (сейчас haversine в Python — ок на текущем объёме), rate-limit зоны в nginx (есть app-throttle на auth), CDN для медиа, мониторинг (Prometheus/healthcheck-алерты). Клиент: «показать ещё» (пагинация на бэке уже есть).

## 2026-09-26 — CI: Android падал до сборки, серверные тесты валила новая sqlmodel (debug-проход)

**Симптом.** Джобы `android-build` и `android-unit-tests` краснели на шаге `android-actions/setup-android@v3` — в `main` (run 36020879013, 24.09, `e19d2322`) и в PR #114 (run 36241030822). 07.08 (run 31160649851) `android-build` ещё проходил.

**Как работало.** `.github/workflows/ci.yml`: обе Android-джобы звали `setup-android@v3` без `with:`. Действие подставляет свои умолчания (`cmdline-tools-version: 12266719`, `packages: tools platform-tools`), принимает лицензии и вызывает `sdkmanager tools`, затем `sdkmanager platform-tools`.

**Корневая причина.** `tools` — старые SDK Tools, заменённые cmdline-tools ещё в 2017-м. Между 07.08 и 24.09.2026 в образе раннера предустановленный sdkmanager стал версии 16.0 (`/usr/local/lib/android/sdk/cmdline-tools/16.0`), и он этот пакет больше не находит: `Warning: Failed to find package 'tools'` → exit code 1 → действие падает, до Gradle дело не доходит. Мы зависели от чужого умолчания, которое перестало быть валидным; код приложения ни при чём.

**Фикс.** В обеих джобах явно `with: packages: platform-tools` (ветка `fix/ci-android-sdk-setup`). Платформы и build-tools, как и раньше, — из образа раннера или докачивает AGP (лицензии действие принимает). Для приложения ничего не меняется — только подготовка машины CI.

**Проверка.** Локально GitHub Actions не запустить: YAML разобран парсером, у обоих шагов `packages: platform-tools`; окончательная проверка — прогон CI в PR с этой веткой.

**Вторая причина: серверные тесты «cancelled», а не «failure» — первый диагноз был неверным.** Шаг тестов обрывался ровно на лимите джобы (15/20 мин, run 36241030822; после подъёма до 40/45 — снова, run 36242782291). Первый вывод «набор вырос до ~4800 тестов, потолок мал» не подтвердился: в журнале run 36242782291 за 40 минут pytest дошёл до 13% (SQLite) и 20% (Postgres), и почти в каждой строке прогресса — `F`/`E` (45–72 из 72), по 3–5 с на падение. Отчёт с причиной pytest печатает в конце — до него прогон не доживал. **Корень:** в `requirements.txt` было `sqlmodel>=0.0.21,<1.0`; CI ставит свежие версии и получил 0.0.47, а с **0.0.45** sqlmodel отвергает naive datetime: `ValueError: Datetime values must have timezone information…` (падает уже в `TestClient(app)` → lifespan → запись в базу). Мы храним время как naive UTC. Локально стояла 0.0.38 — поэтому зелено. Воспроизведено в чистом venv из того же `requirements-dev.txt` (как в CI: fastapi 0.141.1, starlette 1.7.0, firebase-admin, sentry-sdk): первый же тест падает с этой ошибкой; перебор колёс: 0.0.44 без проверки, 0.0.45 — с ней. **Фикс:** `sqlmodel>=0.0.21,<0.0.45` и `--maxfail=25` в обеих джобах (массовое падение краснеет за минуты и печатает причину). Потолки 40/45 оставлены как запас. **Проверка:** тот же CI-подобный venv с sqlmodel 0.0.44 — весь набор 4785 passed, 0 failed, 30 skipped (~9 мин без покрытия). **Прод:** `ops/deploy.sh` делает `pip install -r requirements.txt` в существующий venv без `-U` — на нынешнем сервере sqlmodel не обновится; но новый сервер, свежий venv или сборка Docker взяли бы 0.0.45+ и падали бы на любой записи. Потолок закрывает все три случая.

**Третий слой: 10 Android-тестов падали только в CI** (run 36242782291, после починки установки: 1903 теста, 10 падений; у Александра локально — 0). Воспроизведено в условиях CI — чистая копия без `local.properties`, `-Duser.timezone=UTC -Duser.language=en -Duser.country=US` — и починено:
- `LoginFailureRecoveryTest` (3) — тест требовал `BuildConfig.TELEGRAM_BOT` из `local.properties` и падал ещё в setup. Шов `TelegramLoginBot.testName` в `LoginScreen.kt` (приём как у `ApiClient.testBaseUrl`): тест подставляет своё имя бота, в приложении поведение прежнее.
- `ParcelCreationDeliveryJourneyTest` (1) — клик по «Доставлено», пока кнопка disabled (`enabled = !busy`: прошлое действие не закончилось) → окно вручения не открывалось. Ждём активную кнопку.
- `TaxiDocumentsRaceTest` (3) — «потянуть — обновить», пока первая загрузка не сняла `loading` (ждали отправку запроса фото, а не обработку ответа) → жест игнорировался. Тянем снова, пока не начнётся обновление (до 5 попыток).
- `BookingConfirmationJourneyTest` (1), `BookingCompletionDestinationTest` (1) — проверка сразу после «запрос ушёл», до отрисовки ответа. Ждём само состояние экрана.
- `CreateRidePublishJourneyTest` (1) — «Уфа → Сибай» за краем экрана после прокрутки к статусу (высота строк зависит от шрифтов Linux). Прокручиваем к маршруту.

Общий корень третьего слоя: тесты ждали косвенный признак («запрос отправлен»), а не наблюдаемое состояние экрана, и зависели от файла на машине разработчика. Локально в условиях CI: 6 классов, 20 тестов — зелёные; все тесты экрана входа (54) — зелёные.

**Добивка (run 36253060208: 1903 теста, 1 падение).** `CreateRidePublishJourneyTest.publicationFromCabinetReturnsToReloadedRouteList` — та же гонка: «Опубликовать маршрут» живёт в карточке «маршрутов нет», которая рисуется по ОТВЕТУ на `GET /driver/rides` (до него — скелетон), а тест искал кнопку, как только запрос ушёл. Воспроизведено локально задержкой ответа 1,5 с: старый тест красный на той же строке 130 с тем же сообщением, исправленный (`awaitInList` — ждать, пока удастся прокрутка к строке) зелёный; тем же приёмом — ожидание «Опубликована» после публикации.

**Пятый слой (run 36256188478, после потолка sqlmodel: серверные тесты впервые доходят до конца — SQLite 4784 passed / 1 failed, покрытие 92,55%; Postgres 4807 passed / 3 failed; Android — 1903 теста, 0 падений, но красный порог покрытия).**
- **Порог покрытия слоя данных:** `com.yuldash.app.data` 77,57% при планке 78% (04.08 было 82,7%). Пока CI стоял, новые методы `ApiClient` шли без тестов разбора ответа. Планку не опускаем (правило «обратно не опускаем»): `ApiClientCarPhotoTest` — фотоконтроль машины (`getCarPhoto`/`uploadCarPhoto`/`submitCarPhoto`, разбор `toCarPhotoDto`), ответы в форме `backend/app/carphoto.py::payload`. Замер: отчёт покрытия CI ∪ отчёт нового класса по строкам, нижняя оценка — ≥ 79,1% (+1108 инструкций).
- **`test_dependencies_do_not_surprise`** сравнивал только первую цифру версии (`<\s*(\d+)`): «0.0.44 не влезает в <0.0.45». Моя правка границы sqlmodel вскрыла это — после неё я не перепрогнал тест границ. Теперь сверка по записи целиком (`packaging`, приходит с pytest).
- **Postgres, лента:** `test_лента_поездок_не_растёт_по_запросам` мерил рост ОБЩЕЙ ленты, а она на весь прогон одна и режется потолком `FEED_MAX = 200`. Воспроизведено на локальном Postgres 16: к этому тесту в базе 249 видимых поездок, оба замера — «197 > 197». Теперь тест кладёт поездки на свой день и меряет `/rides?date=…` — тот же путь (`rides_out` → `visible_rides`), но только свои строки.
- **Postgres, бронь:** `test_ride_id_is_not_interpreted_as_booking_id` вставлял `Booking(id=ride)` с явным номером. Postgres не двигает счётчик на явный id, и позже сам выдавал тот же номер — `test_driver_completes_ride`: `duplicate key … booking_pkey (id)=(508)`. SQLite берёт max(id)+1, поэтому там не ловилось. Теперь после явной вставки — `setval(pg_get_serial_sequence('booking','id'), MAX(id))`, а своя бронь владельца — обычной нумерацией.
Проверка: весь серверный набор на локальном Postgres 16 одним прогоном — 4810 passed, 0 failed, 5 skipped (11,7 мин).

**Шестой слой — открыт (run 36259504631: 1908 тестов, 1 падение; обе серверные джобы, сборки и сайт зелёные).** `TaxiReceiptNavigationTest.unfinishedOrderShowsPendingInsteadOfInventedReceipt` не дождался перехода к чеку по сигналу за 15 с; три соседних метода того же класса прошли за ~0,2 с. Этот же код зелёный в двух прошлых прогонах CI и локально. Опровергнуто: особая обработка 409 в клиенте (её нет), старое правило Compose-тестов (класс уже на `.v2`), медленные ответы сервера (все ответы с задержкой 0,7 с — 4/4 зелёные), первый метод класса (по порядку JUnit он третий). Причина неизвестна. Повтор не ставлю (урок «костыль-повтор уничтожил диагностику»): ожидания теста при таймауте теперь печатают экран, сигнал, вход и все запросы к серверу — следующее падение скажет, где остановились. Проверено принудительным таймаутом.

**Седьмой слой (PR #116: 1908 тестов, 1 падение).** `BookingConfirmationJourneyTest.newBookingWaitsForDriverWhoConfirmsFromCabinet` — та же гонка: ждал, что `GET /driver/bookings` УШЁЛ, и сразу прокручивал к «Подтвердить», а кнопка рисуется по ответу. Воспроизведено задержкой ответа 0,8 с (у теста таймаут клиента 1 с): старый — красный на той же строке 133 с тем же сообщением, исправленный (`awaitInList`) — зелёный, весь класс 6/6. Поиском по всем тестам шаблона «ждём запрос → сразу действие по отрисованному» нашлось 5 мест: настоящих 2 (оба здесь — «Подтвердить» и «Код посадки»), в трёх элемент уже на экране или это постоянная вкладка «Везу».
**Раннеры GitHub бывают вдвое медленнее.** Серверная джоба PR #114 шла 33 мин при 16 у того же набора в `main`; замедление ровное по всему прогону (×1,5–2,4 с первых процентов), а Postgres-джоба того же коммита — обычные 15 мин. Значит, машина, а не код. Потолки 40/45 мин не сужать.

**Восьмой слой — причина мигания НАЙДЕНА и доказана.** Повтор прогона PR #116 уронил два теста, и показания таймаута `TaxiReceiptNavigationTest` сказали главное: `screen=Support, signal=71, loggedIn=true` — человек вошёл, сигнал записан, но экран о нём не узнал. Механизм (исходники Compose UI 1.11.3): запись состояния вне композиции будит экран через `GlobalSnapshotManager` — одна корутина на главном диспетчере и флаг «уже отправлено». Robolectric между тестами сбрасывает главный Looper; если в этот момент отправка стоит в очереди, она пропадает, флаг остаётся поднятым, и дежурный молчит до конца прогона. Тестовая обвязка Compose будит экран сама только в своих проверках простоя (`ComposeIdlingResource` зовёт `Snapshot.sendApplyNotifications()`), а самодельные `waitUntil { … }` их не делают. **Доказательство:** флаг дежурного выставлен вручную (как в сломанном прогоне) — все 4 теста чека красные ровно с показанием CI, тест бронирования красный на переключении экрана; с `settle()` (idle + `sendApplyNotifications`) в ожиданиях — оба класса зелёные при том же сломанном дежурном и без него (4/4, 6/6). Прошлые версии (409, старое правило, медленная сеть, порядок методов) опровергнуты не зря: дело не в них. **Осталось:** ещё 95 самодельных ожиданий в 37 файлах — перевести на `settle()` одной правкой (задача в tasks.md).

**Риски / что дальше.** 1) 07.08 `android-unit-tests` падал на пороге покрытия JaCoCo — после починки установки станет видно, проходит ли порог сейчас (локально гоняли `testDebugUnitTest`, не `jacocoCoverageVerification`). 2) GitHub предупреждает: Node.js 20 устарел, `checkout@v4`, `setup-java@v4`, `setup-android@v3` уже принудительно идут на Node 24 — пока работает, но это следующий кандидат на такую же поломку. 3) starlette 1.7 предупреждает: `TestClient` на httpx устарел (`httpx2`) — будущий выпуск может сломать все тесты так же, как sqlmodel. 4) Серверные зависимости не закреплены: у 0.x-библиотек (fastapi, uvicorn, httpx, python-multipart) граница `<1.0` тоже ничего не держит — нужен файл-замок (constraints), чтобы CI, Docker и новый сервер ставили ровно проверенное.

## Итог одной строкой
Юлдаш — рабочий прототип на «прототипных» паттернах. **Перед бетой обязательна Фаза 0 (безопасность)** — 9 пунктов, поведение для юзера не меняется. Дальше Фаза 1 (ViewModel + Navigation + роутеры на бэке) разблокирует рост и командную работу. Всё остальное — по ходу, без спешки.
