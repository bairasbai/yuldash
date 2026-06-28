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

## 🎯 Текущий статус (2026-06-28) — scale-tier

> 🚀 **Масштабные фичи сделаны end-to-end и ЗАДЕПЛОЕНЫ на прод (2026-06-28, Opus).** ① **Refresh-токены**: короткий access + ротируемый refresh (хеш в БД) + `/auth/refresh` + logout-ревокация; Android авто-refresh на 401 (Mutex). ② **Redis rate-limit**: общий на воркеры, фолбэк in-memory (на проде Redis уже стоял от соседней сессии — код подхватил, ключи `rl:*` живые). ③ **PostGIS**: геокод концов маршрута при публикации (`from_lat/lng,to_lat/lng`) + `ST_DWithin`/GiST-префильтр с haversine-фолбэком; extension+индекс на проде. ④ **load-more**: `limit/offset` на списках + Android `NearbyMoreCard` в «Ближайших». **Прод здоров:** workers стартуют, health `db:ok`, refreshtoken-таблица + 4 geo-колонки + postgis + GiST-индекс + redis-ключи — всё проверено server-side. **Поймал+починил баг:** гонка 2 воркеров gunicorn на `create_all` (`DuplicateTable`) → `init_db` теперь идемпотентен. Backend pytest **47/47**, Android BUILD SUCCESSFUL. Детали — [decisions.md](decisions.md), [lessons.md](lessons.md), [architecture-audit.md](architecture-audit.md).

## 🎯 Статус (2026-06-28)

> 🏗️ **Рефакторинг + прод-харднинг бэкенда ЗАВЕРШЕНЫ (2026-06-28, Opus, worktree).** Монолит `app/main.py` (~1400 строк, ~50 роутов) разрезан на домены: фабрика `create_app()` + `app/routers/*` (health/auth/rides/requests/bookings/drivers/chat/discovery/family/safety) + общий `app/services.py` + схемы `app/schemas.py`. **API версионирован** — каждый роут и на корне (клиент не ломается), и под `/api/v1` (алиас). **Прод-харднинг (раз пользователей ещё нет — делаем до релиза):** `app/middleware.py` — rate-limit на IP (общий 300/мин + строгий 20/мин на `/auth`+`/sos`), security-заголовки, access-лог без утечек, единый обработчик ошибок (500 без стека наружу); `/health` пингует БД (`db: ok/down`). + `Dockerfile`+`docker-compose.yml`, `/version`. **Поведение 1:1, всё зелёное:** pytest **44/44** (контрактные тесты по всем доменам, `tests/test_flows.py`), smoke OK, security OK, паритет root↔/api/v1 OK, прод-конфиг-гейт OK, **Android BUILD SUCCESSFUL**. **Долг `datetime.utcnow()` закрыт** (`timeutil.utcnow()`, warnings 157→1). **Добито до конца:** ① **logout/ревокация токенов** end-to-end (backend `tokens_valid_from`+`iat`+`POST /auth/logout`, Android `ApiClient.logout`→сервер) — migrate_logout.sql; ② **пагинация** `limit/offset` на `/rides`,`/bookings/mine`,`/requests/mine` (обратносовместимо, дефолт=всё); ③ **Alembic починен** — чистый baseline `0001` (create_all из моделей, sqlite+postgres), `upgrade head`==модели, прод-катовер `alembic stamp head`. README бэкенда переписан. Деплой → `scp -r app\`, энтрипоинт прежний `app.main:app`. + команда `/architect`. **✅ ЗАДЕПЛОЕНО на прод `yulbash.ru` (2026-06-28):** бэкап БД (`/root/yuldash-predeploy-*.sql.gz`) → `scp -r app/` из worktree → `migrate_logout.sql` (ALTER TABLE ok) → рестарт `yuldash-api` (active). Server-side: `/health`→`db:ok`, `/version`→0.1.0, `/api/v1/health`→200, `/me`→401, `/auth/logout`→401, ноль трейсбеков. Прод под gunicorn+uvicorn (rate-limit in-memory per-worker). Опц. Alembic-катовер разово: `alembic stamp head`. Детали — [decisions.md](decisions.md), [architecture-audit.md](architecture-audit.md), [system-design.md](system-design.md).

## 🎯 Прежний статус (2026-06-27)

> 🔒 **Аудит безопасности + Фаза 0 + критичный краш-фикс ЗАДЕПЛОЕНЫ на прод (2026-06-27, Opus).** Закрыты: WS-IDOR (чужой чат), перебор OTP, утечка телефонов в логах, овербукинг, ключ геокодера в APK (→ прокси `/geocode`), JWT шифруется, краш возврата на карту (`MapKitFactory.setLocale`). + N+1 в выдаче (батч). Подробно — [architecture-audit.md](architecture-audit.md). Подписанный APK: `yuldash-release-2026-06-27.apk`. main `f4f74d7`.

> 🛠 **Debug-проход (2026-06-27, worktree):** закрыта тихая потеря сообщений чата — `ActiveTripScreen` теперь надёжно доставляет (WS→REST с проверкой) и показывает «Не доставлено · Повторить» вместо ложного «отправлено». + команда `/debug` (`.claude/commands/debug.md`) — промт senior-инженера по отладке под Юлдаш. Сборка зелёная. Детали — [architecture-audit.md](architecture-audit.md).

> 🚀 **Подготовка к запуску с наплывом (2026-06-28, Opus) — ЗАДЕПЛОЕНО.** Сервер 2 ядра/3.8ГБ. **A:** TTL-кеш `/feed`(30с)+`/popular-routes`(60с) — снял основную read-амплификацию (их поллит каждый клиент); тюнинг пула БД (`pool_pre_ping`+размер под воркеры); индексы `ride(status,depart_at)`. **B:** Redis pub/sub для WS-чата между воркерами + **gunicorn 2 воркера** (было 1 uvicorn) + nginx `worker_connections` 4096. **C:** cross-worker WS-тест **6/6 OK**, нагрузка 50 парал. → **466 rps, 0 ошибок, p95 193мс**. Бэкапы на сервере (phaseA/B). Детали+что осталось для большего масштаба — [architecture-audit.md](architecture-audit.md).

> ⚡ **Перф-проход #3 (2026-06-28, worktree, Opus):** бэкенд. **SMS близким → `BackgroundTasks`** (SOS/статусы): ответ мгновенный, рассылка после (не ждём sms.ru по 10с/контакт). **Пагинация** (`/rides` limit/offset, `/messages` limit+before_id, `/conversations` limit) — аддитивно, старый клиент не ломается, чат на 10к сообщений больше не грузится целиком. SMOKE OK + SECURITY SMOKE OK. **✅ ЗАДЕПЛОЕНО на прод 2026-06-28** (бэкап+scp+рестарт, `/rides?limit=1`→1 проверено, ошибок нет, без миграций). Redis(WS)/PostGIS(гео) на текущем 1-воркерном деплое НЕ нужны. Детали — [architecture-audit.md](architecture-audit.md).

> ⚡ **Перф-проход #2 (2026-06-28, worktree, Opus):** добит главный неснятый горячий путь — поллинг ленты карты (`MapHero`: популярные маршруты 45с + `/feed` 60с) больше не дёргает сервер **в фоне**: обёрнут в `repeatOnLifecycle(RESUMED)` → пауза при сворачивании, авто-возврат. Меньше нагрузки на бэк/батарею на масштабе. Зависимость `lifecycle-runtime-compose:2.9.4` (версия из графа, 0 конфликта). Сборка зелёная, форграунд-поведение без изменений. Детали — [architecture-audit.md](architecture-audit.md).

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
