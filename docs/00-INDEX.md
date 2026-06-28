# 🧠 Второй мозг Юлдаш — карта знаний

> Это вход для агента (и для Александра). Читай ОТСЮДА перед любой задачей.
> Цель: понять проект за 1 минуту и не перечитывать весь код (экономия токенов).

## Как пользоваться (агенту)

1. Открой этот файл и нужную тему ниже — **не читай весь `MainActivity.kt` целиком**.
2. В [architecture.md](architecture.md) лежит карта кода по строкам — иди сразу в нужное место.
3. После работы **обнови мозг**:
   - новый экран/функция → [architecture.md](architecture.md)
   - принял решение → [decisions.md](decisions.md)
   - ошибся и исправил → [lessons.md](lessons.md)
   - по ходу задачи → [tasks.md](tasks.md)

## Файлы мозга

| Файл | Что внутри |
| --- | --- |
| **[system-design.md](system-design.md)** | 🏗️ **ПОЛНАЯ архитектура Юлдаша как production-готового стартапа**. Макро-уровень (3-слойная архитектура), схема БД (20+ нормализованных таблиц с индексами), REST API спецификация, WebSocket для чата, безопасность, DevOps план масштабирования, метрики. Начни отсюда для обзора. |
| [architecture.md](architecture.md) | Карта кода: где какой экран в `MainActivity.kt` (по строкам), навигация, данные, сборка |
| [glossary.md](glossary.md) | Словарь: термины проекта, бренд, цвета, RU/BA слова |
| [decisions.md](decisions.md) | Журнал решений (почему сделали так) |
| [lessons.md](lessons.md) | Уроки: ошибки и правила, чтобы не повторять |
| [tasks.md](tasks.md) | Задачи и план (чекбоксы), что уже сделано |
| [audit.md](audit.md) | Аудит экранов: что реально/имитация/сломано/блокер (готовность ~70%, единственная заглушка была — проверка водителя, уже закрыта) |
| [qa.md](qa.md) | Сборка, эмулятор, smoke-тест, проверки кода |
| [map-design.md](map-design.md) | Дизайн карты (Яндекс MapKit) и как её использовать |
| [backend.md](backend.md) | Архитектура настоящего бэкенда: роли, SMS-вход, БД, API, матчинг, порядок миграции |
| [server.md](server.md) | 🖥 Сервер и деплой: URL API, SSH-доступ по ключу, firewall, HTTPS, как подключиться/управлять. Ключ — на ноуте Александра → подключиться может только агент, работающий локально (облачный — нет) |
| [monetization.md](monetization.md) | Бизнес-модель: донаты + платное поднятие |
| [telegram-setup.md](telegram-setup.md) | 🤖 Вход через Telegram (УЖЕ настроено: бот `@yuldash_sms_bot` + .env + вебхук). Инструкция — для ротации токена. |
| **[architecture-audit.md](architecture-audit.md)** | 🏗️ **Senior-аудит (2026-06-27):** разбор архитектуры + поток данных, критичные места, стратегии рефакторинга, журнал всех фиксов/деплоя сессии (безопасность Фаза 0, краш карты, N+1, защита состояния). |

## 🎯 Текущий статус (2026-06-28) — FCM-push подтверждён живым + квота/мониторинг/бэкап

> ✅ **Push (FCM) АКТИВЕН end-to-end (проверено 2026-06-28).** Firebase-проект `yuldash-9586e`: сервер — Admin SDK инициализируется (ключ `firebase-service-account.json`, `FIREBASE_CREDENTIALS` в `.env`, firebase-admin 7.4.0); Android — `google-services.json` в сборке, **релизный APK включает push** (`processReleaseGoogleServices` ok, `assembleRelease` зелёный, подписан). Сервер шлёт push при сообщении/брони/SOS. Осталось: устройства регистрируют токен при первом запуске push-сборки. ✅ **Мониторинг ЗАМКНУТ** — алерты «прод упал» идут в Telegram (chat_id вписан, доставка проверена). ✅ **Яндекс-геокодер кеширован** (Redis 24ч — экономия квоты). ✅ Офсайт-S3-бэкап (инфра+aws-cli готовы, ждёт ключи бакета). Детали — [fcm-setup.md](fcm-setup.md), [decisions.md](decisions.md). **Ждут только тебя: #1 SMS (sms.ru api_id), #3 S3 (ключи бакета).**

## 🎯 Статус (2026-06-28) — выжали максимум из сервера под запуск

> 🚀 **Подготовка к наплыву (~10к) на текущем железе (2 ядра, БЕЗ апгрейда — Александр добавит сервер после 5к подписчиков). Всё задеплоено+проверено на проде.** ① **Throughput:** воркеры `gunicorn 2→5`+`--preload`, кеш `/rides` в Redis (TTL 20с), `pool_pre_ping`. Замер: лёгкие чтения **400→594 зап/с (+50%)**. ② **Чат → Redis pub/sub** (на 5 воркерах живой чат больше не рвётся между процессами; проверено 2 WS-клиента). ③ **Вход:** бэкенд уже спайк-безопасен (код шлётся ответом на вебхук, без исходящего HTTP); узкое место = лимит Telegram ~30/с (клиентский UX). Индексы БД проверены — все на месте. **Реалистичный потолок: ~1500–2300 одновременно активных; база ~10–20к при размазанном входе.** pytest 48/48, прод 0 трейсбеков. Детали — [decisions.md](decisions.md).

## 🎯 Статус (2026-06-28) — тех-лид аудит + фиксы

> 🧠 **Команда `/techlead` + аудит всего кода + безопасные фиксы (2026-06-28, Opus, worktree).** Адаптировал промт «senior tech lead» под Юлдаш → `.claude/commands/techlead.md` (режим «думай как техлид, не генератор кода»). Прогнал end-to-end (2 субагента: backend+Android). **Топ-«блокеры» субагентов оказались ложными/уже закрытыми** (чат-сокет `DisposableEffect`, logout `disconnect`, revoke — 1 commit) — рабочий код не трогал зря. **Реализовал по запросу:** backend scale (`.limit` анти-OOM на feed/popular/my-routes; N+1→батч в `conversations`/`driver_bookings`/`my-routes`; SOS rate-limit SMS без блокировки самого SOS) — **pytest 48/48, smoke+security OK**; Android UI-долг — 11 хардкод-цветов→токены (`CanonStar`/`CanonHairlineGreen`/`CanonGreen2`), Canvas-арт raw оставлен (DrawScope, lessons.md) — **BUILD SUCCESSFUL**. ✅ **Backend ЗАДЕПЛОЕН на `yulbash.ru`** (бэкап→scp app/ из worktree→рестарт; `/health` db:ok, `/feed`+`/popular-routes` живы, 0 трейсбеков). Android-цвета — в APK при следующей сборке релиза. Детали — [architecture-audit.md](architecture-audit.md).

## 🎯 Статус (2026-06-28) — scale-tier

> 🚀 **Масштабные фичи сделаны end-to-end и ЗАДЕПЛОЕНЫ на прод (2026-06-28, Opus).** ① **Refresh-токены**: короткий access + ротируемый refresh (хеш в БД) + `/auth/refresh` + logout-ревокация; Android авто-refresh на 401 (Mutex). ② **Redis rate-limit**: общий на воркеры, фолбэк in-memory (на проде Redis уже стоял от соседней сессии — код подхватил, ключи `rl:*` живые). ③ **PostGIS**: геокод концов маршрута при публикации (`from_lat/lng,to_lat/lng`) + `ST_DWithin`/GiST-префильтр с haversine-фолбэком; extension+индекс на проде. ④ **load-more**: `limit/offset` на списках + Android `NearbyMoreCard` в «Ближайших». **Прод здоров:** workers стартуют, health `db:ok`, refreshtoken-таблица + 4 geo-колонки + postgis + GiST-индекс + redis-ключи — всё проверено server-side. **Поймал+починил баг:** гонка 2 воркеров gunicorn на `create_all` (`DuplicateTable`) → `init_db` теперь идемпотентен. Backend pytest **47/47**, Android BUILD SUCCESSFUL. Детали — [decisions.md](decisions.md), [lessons.md](lessons.md), [architecture-audit.md](architecture-audit.md).

## 🎯 Статус (2026-06-28)

> 🏗️ **Рефакторинг + прод-харднинг бэкенда ЗАВЕРШЕНЫ (2026-06-28, Opus, worktree).** Монолит `app/main.py` (~1400 строк, ~50 роутов) разрезан на домены: фабрика `create_app()` + `app/routers/*` (health/auth/rides/requests/bookings/drivers/chat/discovery/family/safety) + общий `app/services.py` + схемы `app/schemas.py`. **API версионирован** — каждый роут и на корне (клиент не ломается), и под `/api/v1` (алиас). **Прод-харднинг (раз пользователей ещё нет — делаем до релиза):** `app/middleware.py` — rate-limit на IP (общий 300/мин + строгий 20/мин на `/auth`+`/sos`), security-заголовки, access-лог без утечек, единый обработчик ошибок (500 без стека наружу); `/health` пингует БД (`db: ok/down`). + `Dockerfile`+`docker-compose.yml`, `/version`. **Поведение 1:1, всё зелёное:** pytest **44/44** (контрактные тесты по всем доменам, `tests/test_flows.py`), smoke OK, security OK, паритет root↔/api/v1 OK, прод-конфиг-гейт OK, **Android BUILD SUCCESSFUL**. **Долг `datetime.utcnow()` закрыт** (`timeutil.utcnow()`, warnings 157→1). **Добито до конца:** ① **logout/ревокация токенов** end-to-end (backend `tokens_valid_from`+`iat`+`POST /auth/logout`, Android `ApiClient.logout`→сервер) — migrate_logout.sql; ② **пагинация** `limit/offset` на `/rides`,`/bookings/mine`,`/requests/mine` (обратносовместимо, дефолт=всё); ③ **Alembic починен** — чистый baseline `0001` (create_all из моделей, sqlite+postgres), `upgrade head`==модели, прод-катовер `alembic stamp head`. README бэкенда переписан. Деплой → `scp -r app\`, энтрипоинт прежний `app.main:app`. + команда `/architect`. **✅ ЗАДЕПЛОЕНО на прод `yulbash.ru` (2026-06-28):** бэкап БД (`/root/yuldash-predeploy-*.sql.gz`) → `scp -r app/` из worktree → `migrate_logout.sql` (ALTER TABLE ok) → рестарт `yuldash-api` (active). Server-side: `/health`→`db:ok`, `/version`→0.1.0, `/api/v1/health`→200, `/me`→401, `/auth/logout`→401, ноль трейсбеков. Прод под gunicorn+uvicorn (rate-limit in-memory per-worker). Опц. Alembic-катовер разово: `alembic stamp head`. Детали — [decisions.md](decisions.md), [architecture-audit.md](architecture-audit.md), [system-design.md](system-design.md).

## 🎯 Прежний статус (2026-06-27)

> 🔒 **Аудит безопасности + Фаза 0 + критичный краш-фикс ЗАДЕПЛОЕНЫ на прод (2026-06-27, Opus).** Закрыты: WS-IDOR (чужой чат), перебор OTP, утечка телефонов в логах, овербукинг, ключ геокодера в APK (→ прокси `/geocode`), JWT шифруется, краш возврата на карту (`MapKitFactory.setLocale`). + N+1 в выдаче (батч). Подробно — [architecture-audit.md](architecture-audit.md). Подписанный APK: `yuldash-release-2026-06-27.apk`. main `f4f74d7`.

> 🛠 **Debug-проход (2026-06-27, worktree):** закрыта тихая потеря сообщений чата — `ActiveTripScreen` теперь надёжно доставляет (WS→REST с проверкой) и показывает «Не доставлено · Повторить» вместо ложного «отправлено». + команда `/debug` (`.claude/commands/debug.md`) — промт senior-инженера по отладке под Юлдаш. Сборка зелёная. Детали — [architecture-audit.md](architecture-audit.md).

> ⚡ **Перф-проход (2026-06-27, worktree, Opus):** аудит производительности Compose (5 параллельных read-only субагентов) + фиксы. Кеш битмапов-маркеров карты (`userPuck`/`destFlag`/`ridePin` больше не лепятся на каждый GPS-апдейт), мемоизация `.filter`/`.groupBy`/`.take` в composition, `adStats`→`mutableStateMapOf`, один `OkHttpClient` на чат-сокеты, LRU-кеш геокодера, кеш декода JWT, стабильные `key` в списках. Поведение/вид не изменены. Сборка зелёная. + команда `/perf`. Детали — [architecture-audit.md](architecture-audit.md).

> ⚠️ **Честная оценка после перепроверки (Opus).** «Сборка зелёная» = только компилируется, ≠ работает end-to-end.

**Реально работает (проверено):**
- ✅ Вход **только через Telegram — LIVE, по 4-значному коду**: бот `@yuldash_sms_bot` шлёт код в чат, юзер вводит в приложении. E2E проверен на проде (start→код→verify→JWT, неверный→400). **VK и WhatsApp убраны** (2026-06-27): VK требует ИНН (бизнес), WhatsApp — WhatsApp Business API; физлицу недоступны. SMS **заморожен** (та же причина — нет юр.лица для sms.ru) — код цел, оживляется флагом `SMS_LOGIN_ENABLED`+`SMS_PROVIDER=smsru`.
- ✅ Поездки & заявки (реальные данные с сервера yulbash.ru)
- ✅ Матчинг, карта Яндекс (маркеры-ценники), **чат по REST**
- ✅ Проверка водителя (фото), бронь, рейтинги, SOS, доверенные контакты
- ✅ Boost UI (платежи на Q4)
- ✅ Сборка `gradlew assembleDebug` зелёная (компиляция)

**Telegram-вход — ВКЛЮЧЁН end-to-end (бот `@yuldash_sms_bot` зарегистрирован, токен в `.env` прода):**
- ✅ Поток по 4-значному коду (как SMS): app `/auth/tg/start` → открывает бота → юзер жмёт Старт → бот шлёт код в чат → app `/auth/tg/verify` → JWT. Проверено на проде (start→код→verify→JWT, неверный→400, защита от перебора).
- ✅ Единственный рабочий вход. VK/WhatsApp **убраны** (требуют ИНН/бизнес). SMS заморожен (нет юр.лица).
- ✅ Сборка зелёная.

**WebSocket-чат — РАБОТАЕТ:**
- ✅ `/ws/bookings/{id}` через nginx (wss), uvicorn+websockets, проверено end-to-end на проде.
- ✅ Проверка участника брони (только пассажир/водитель — закрыта дыра утечки чужих чатов, аудит 2026-06-27).
- ✅ Android: OkHttp + `ChatSocket.kt`, ActiveTripScreen — живой приём/отправка. Двусторонний тест 2 телефонов — на бете.

**Что дальше (приоритет):**
1. OAuth-блокеры: зарегать Telegram-бота/VK-приложение (Александр) → вход замкнётся.
2. SMS-отправитель sms.ru (модерация) — оживит SMS-вход.
3. Бета в Баймаке на текущем SMS-входе.

**Коммиты сессии:** `9936c5d` OAuth · `97bc7a2` WebSocket · `93b6efd` WhatsApp · `ef568b0` фиксы Opus · (+ клиент-петля).

## Проект в двух словах

**Юлдаш** — приложение попуток «между своими» (Башкортостан: Баймаҡ, Сибай, Уфа). Двуязычное (русский + башкирский). Android — Kotlin + Jetpack Compose.

- **Рабочий код:** `android/` ← тут работаем.
- **Бэкенд:** FastAPI (`backend/`) на PostgreSQL, **развёрнут**: API `https://yulbash.ru` (см. [server.md](server.md)).
- **Приложение ПОДКЛЮЧЕНО к серверу:** вход (SMS→JWT→автологин), поездки, заявки, бронь, SOS, доверенные контакты, чат, активная поездка (share/статус), **рейтинги** (отзывы после поездки), **проверка водителя** (фото прав/авто→модерация), **премиум-предпочтения поездки** (животные/кресло/только женщины/багаж/кондиционер/курение), лента карты (`/feed`), ближайшие по маршруту+гео (`/rides/near`) — всё реально на сервере. Слой `data/ApiClient.kt`. Базовый URL — `BuildConfig.YULDASH_API_BASE_URL` (debug→`10.0.2.2:8000`, release→`yulbash.ru`). SMS — код готов (нужен буквенный отправитель sms.ru).
- **Не трогать:** `mobile/` (старый Flutter, заброшен).
