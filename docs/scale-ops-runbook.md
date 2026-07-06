# 📈 Runbook: масштаб-ops до 100k (Фаза 5)

> Цель фазы: горячие пути держат **p95 < 300 мс при ~200 RPS**, убийство одного
> воркера не роняет сервис. Это **операционный** документ (что и как крутить на сервере) —
> код приложения (`app/`) при этом почти не меняется: масштаб достигается процессами,
> пулами и индексами, а не переписыванием.

Связанные файлы:
- Нагрузочное: [`backend/loadtest/`](../backend/loadtest/) (Locust + README).
- Многопроцессность: [`backend/deploy/gunicorn_conf.py`](../backend/deploy/gunicorn_conf.py), [`yuldash-api.service.example`](../backend/deploy/yuldash-api.service.example).
- nginx ip_hash: [`backend/deploy/nginx-yuldash-scale.conf.example`](../backend/deploy/nginx-yuldash-scale.conf.example).
- PgBouncer: [`backend/deploy/pgbouncer.ini.example`](../backend/deploy/pgbouncer.ini.example).
- Redis: [`backend/deploy/redis.conf.example`](../backend/deploy/redis.conf.example).

Текущее состояние прод-инфры — [`docs/server.md`](server.md) (systemd `yuldash-api`, uvicorn на `127.0.0.1:8000`, nginx, Let's Encrypt).

---

## 1. Многопроцессность (uvicorn-воркеры за nginx)

**Что даёт:** один uvicorn = одно ядро. N воркеров = используем все ядра + отказоустойчивость
(упал воркер — мастер поднял новый, сервис жив).

**Как включить:**
1. Приложение уже stateless — WS-события между процессами разносит **Redis pub/sub**
   (`app/services.py`: канал `_CHAT_CHANNEL`, `publish`/`_chat_subscribe_loop`). Отдельного
   sticky-состояния в памяти процесса нет → воркеры взаимозаменяемы.
2. Меняем запуск с одиночного uvicorn на **gunicorn + UvicornWorker**:
   - конфиг: `backend/deploy/gunicorn_conf.py` (число воркеров из `WEB_CONCURRENCY`, иначе автоформула);
   - systemd: `backend/deploy/yuldash-api.service.example` → `/etc/systemd/system/yuldash-api.service`.
3. Старт с `WEB_CONCURRENCY=4`, дальше тюнинг по нагрузочному прогону.

**WS-стикинг (ip_hash):** пока все воркеры на одном порту (`127.0.0.1:8000`) под мастером
gunicorn — nginx проксирует на один адрес, ip_hash не нужен. Он нужен, **когда появятся
несколько адресов бэкенда** (второй хост или второй gunicorn-мастер): тогда `ip_hash`
в `upstream` прибивает клиента к одному апстриму, чтобы WS-сессия жила на одном процессе.
Даже без него чат не сломается (Redis pub/sub разносит события) — это оптимизация.
Фрагмент конфига — `backend/deploy/nginx-yuldash-scale.conf.example`.

**Проверка отказоустойчивости** (критерий «готово»): во время нагрузочного прогона убить
один воркер (`pkill -f 'uvicorn.*app.main' -n`) → RPS/ошибки не проваливаются.

---

## 2. PostgreSQL: managed-инстанс + PgBouncer

### 2.1 Отдельный/managed инстанс
- Сейчас Postgres, вероятно, на том же хосте, что и app. Под ростом — вынести на
  **отдельный или managed** инстанс (Yandex Managed PostgreSQL / VK Cloud / Selectel):
  бэкапы, реплики, мониторинг, обновления — на стороне провайдера.
- Плюс: app-хост и БД масштабируются независимо; можно добавить read-реплику под тяжёлые
  чтения (лента) позже.
- Минимум: не выставлять 5432 в интернет; доступ только с app-хостов (firewall/security-group).

### 2.2 PgBouncer (пул соединений)
- **Зачем:** N воркеров × пул SQLModel-соединений быстро упираются в `max_connections`.
  PgBouncer держит горстку реальных коннектов и мультиплексирует на них сотни клиентских.
- Конфиг-пример: `backend/deploy/pgbouncer.ini.example`, **`pool_mode = transaction`**
  (соединение возвращается в пул после каждой транзакции — идеально для веб).
- Приложение потом смотрит в PgBouncer (порт 6432), а не напрямую в Postgres:
  `DATABASE_URL=postgresql://yuldash:PASSWORD@127.0.0.1:6432/yuldash` (значение — в `.env`, вне git).
- ⚠️ transaction-режим несовместим с session-фичами (server-side prepared statements,
  session `LISTEN/NOTIFY`, advisory-locks на сессию). У нас их нет; если появятся —
  либо `pool_mode=session` для этих коннектов, либо отключить кэш prepared-стейтментов драйвера.

### 2.3 EXPLAIN-чеклист по горячим запросам
Прогнать на staging с реалистичным объёмом (десятки-сотни тыс. строк `ride`/`riderequest`),
не на пустой БД. Формат: `EXPLAIN (ANALYZE, BUFFERS) <запрос>`.

**A. Лента `/rides` и `/rides/near`** (`app/routers/rides.py`):
```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM ride
WHERE status = 'active' AND seats_left > 0
  AND from_city LIKE '%Уфа%' AND to_city LIKE '%Стерлитамак%'
ORDER BY boosted_until DESC NULLS LAST, depart_at ASC;
```
Что искать:
- **Seq Scan по всей `ride`** там, где ждём Index Scan → индекса не хватает или он не берётся.
- ⚠️ **`from_city`/`to_city` фильтруются через `.contains()` = `LIKE '%x%'`** (ведущий `%`).
  Обычный btree-индекс (`index=True` на этих полях) под leading-wildcard **НЕ работает**.
  → Варианты: (а) индекс **pg_trgm GIN** (`CREATE EXTENSION pg_trgm; CREATE INDEX ... USING gin (from_city gin_trgm_ops)`),
  (б) перейти на точное совпадение/префикс там, где UI это позволяет.
  При малом числе active-поездок это может быть неважно (фильтр `status='active'` уже сильно режет) —
  решаем по EXPLAIN на реальном объёме, не гадаем.
- **Сортировка** `boosted_until, depart_at` — при большом наборе полезен составной индекс
  `(status, depart_at)` или `(status, boosted_until, depart_at)`, чтобы убрать явный Sort.

**B. Гео-префильтр `/rides/near` (PostGIS)**:
```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM ride
WHERE from_lat IS NULL
   OR ST_DWithin(ST_MakePoint(from_lng, from_lat)::geography,
                 ST_MakePoint(55.97, 54.73)::geography, 70000);
```
Что искать/проверить:
- Есть ли **GiST-индекс** под это выражение. Функциональный ST_DWithin по `::geography`
  использует GiST только с соответствующим **функциональным индексом**:
  ```sql
  CREATE EXTENSION IF NOT EXISTS postgis;
  CREATE INDEX ix_ride_geog ON ride
    USING gist ((ST_MakePoint(from_lng, from_lat)::geography));
  ```
  Без него — Seq Scan + пересчёт geography на каждой строке (дорого на больших объёмах).
- Код уже фолбэчит на Python-haversine, если PostGIS/индекса нет (`app/routers/rides.py`),
  так что это про **скорость**, не про корректность.

**C. Заявки `/requests/near`** (`app/routers/requests.py`) — те же вопросы, что у `ride`:
`status`-фильтр берёт индекс? `from_city/to_city` LIKE — та же история с trigram.

**D. Matcher «Быстрого заказа» `/instant/*`** (ветка `feat/instant-order`):
подбор водителей идёт **не по SQL, а по Redis `GEOSEARCH`** (presence-набор). Здесь узкое
место — Redis (см. §3) и размер presence-набора, а не Postgres. По БД в matcher проверить
только точечные `SELECT ... FOR UPDATE` по `driverprofile`/заказу (индекс по PK — уже есть).

**Индексы, которые точно есть** (из `app/models.py`, `index=True`):
`ride.from_city`, `ride.to_city`, `ride.status`, `ride.depart_at`, `ride.boosted_until`,
`ride.driver_id`; `riderequest.*` аналогично; FK/уникальные — на месте.
**Чего может не хватать** (создать миграцией после подтверждения EXPLAIN):
GiST по geography (B), pg_trgm GIN на city-поля (A/C), составной `(status, depart_at)`.

> Правило: **не плодим индексы вслепую.** Каждый индекс — по факту Seq Scan в EXPLAIN на
> реальном объёме. Лишние индексы замедляют запись (публикация поездки) и едят место.

---

## 3. Redis: пароль + persistence + вынос

**Сейчас:** Redis используется для pub/sub (чат/карта), кэша ленты, rate-limit; с «Быстрым
заказом» добавится GEO-presence водителей. Подключение — `REDIS_URL` (`app/config.py`),
без Redis приложение деградирует мягко (локальная доставка WS, без кэша).

**Что сделать (runbook):**
1. **Пароль.** По умолчанию Redis без пароля — если порт доступен извне, это дыра.
   `requirepass` + `bind 127.0.0.1` (пока на одном хосте). Конфиг-пример:
   `backend/deploy/redis.conf.example`. В `.env`: `REDIS_URL=redis://:PASSWORD@127.0.0.1:6379/0`.
2. **Persistence (AOF).** `appendonly yes`, `appendfsync everysec` — переживаем рестарт/падение,
   не теряя presence/кэш/rate (максимум ~1с данных при аварии). RDB-снапшоты оставляем как бэкап.
3. **Память.** `maxmemory 512mb` + `maxmemory-policy volatile-lru` (вытесняем только ключи с TTL —
   кэш/presence с `EX`, данные без TTL не трогаем).
4. **Вынос с app-сервера при росте.** Когда CPU/память начнут конкурировать с API —
   вынести Redis на отдельный инстанс (managed Redis или своя VM): `bind` на приватный IP,
   firewall только с app-хостов, пароль обязателен, TLS если сеть недоверенная. `REDIS_URL`
   меняется на новый адрес — код не трогаем.

**Проверка после включения пароля:** `redis-cli -a PASSWORD ping` → `PONG`; рестарт API
(`systemctl restart yuldash-api`) → в логах нет `redis init failed`.

---

## 4. Порядок внедрения (безопасно, по одному шагу)

1. **Нагрузочное на staging сейчас** — снять базовую линию (baseline) p95/RPS до изменений.
2. **Индексы по EXPLAIN** (§2.3) — самый дешёвый выигрыш, миграцией alembic.
3. **Redis пароль + AOF** (§3) — безопасность и надёжность, без простоя.
4. **gunicorn N воркеров** (§1) — прирост пропускной способности; проверить «убили воркер».
5. **PgBouncer** (§2.2) — когда воркеров/коннектов станет много.
6. **Вынос БД/Redis на отдельные инстансы** (§2.1, §3) — при конкуренции за ресурсы (>5k MAU).
7. **Повторный нагрузочный прогон** — подтвердить p95<300мс@200RPS и отказоустойчивость.

Каждый шаг — обратимый (есть откат в примерах конфигов), катим по одному, между шагами —
короткий прогон Locust, чтобы видеть эффект.
