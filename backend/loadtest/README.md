# Нагрузочное тестирование Юлдаша (Locust) — Фаза 5 «Масштаб до 100k»

Проверяем, что горячие пути API держат нагрузку: **p95 < 300 мс при ~200 RPS**,
и что убийство одного uvicorn-воркера не роняет сервис.

> ⚠️ **Только против staging.** Никогда не гоняем против прода `yulbash.ru` —
> там реальные пользователи, реальные SMS и реальные брони. Нужен отдельный
> staging-инстанс (тот же образ/конфиг, отдельная БД и Redis).

---

## 0. Что моделируем

| Роль (класс) | Вес | Что делает | Зачем |
|---|---|---|---|
| `ReadUser` | 6 | `/rides/near`, `/rides`, `/requests/near`, `/feed`, `/popular-routes`, `/rides/price_hint` | Самый массовый и самый тяжёлый для БД путь (лента) |
| `RiderUser` | 3 | Логин (OTP), `/me`, `/bookings/mine`, иногда `POST /bookings` | Авторизованный трафик + запись под row-lock |
| `InstantUser` | 2* | `POST /instant/estimate` | Один quote smoke на процесс; только disposable loopback fixture |
| `ChatWSUser` | 1* | WebSocket `/ws/bookings/{id}` | Проверка ip_hash-стикинга + Redis pub/sub |

\* `InstantUser` и `ChatWSUser` включаются переменными окружения (см. ниже).

---

## 1. Установка (отдельное окружение)

```bash
cd backend/loadtest
python -m venv .venv-load
. .venv-load/bin/activate        # Windows: .venv-load\Scripts\activate
pip install -r requirements.txt
```

---

## 2. Авторизация тестовых пользователей — 2 способа

`RiderUser` нуждается в токене. Выбери один:

**Способ A (рекомендуется на staging): dev-режим OTP.**
Если staging поднят с `ENV=dev`, ручка `/auth/request-code` возвращает `dev_code`
прямо в ответе — Locust логинится сам, без SMS. Ничего задавать не надо.

**Способ Б: пул заранее выданных токенов.**
Если staging не в dev-режиме — засиди тестовых пользователей и раздай их access-токены:

```bash
export YULDASH_LOADTEST_TOKENS="eyJ...tokenA,eyJ...tokenB,eyJ...tokenC"
```

(Токены выдаёт обычный `/auth/verify`; насиди 50–200 тестовых номеров скриптом на staging.)

---

## 3. Запуск

**Веб-интерфейс (ручной прогон, смотрим графики):**
```bash
locust -f locustfile.py --host http://STAGING_HOST:8000
# открой http://localhost:8089 → Users=200, Spawn rate=20 → Start
```

**Без UI (для CI / воспроизводимый прогон на цель):**
```bash
locust -f locustfile.py --host http://STAGING_HOST:8000 \
  --headless --users 200 --spawn-rate 20 --run-time 5m \
  --csv results/yuldash
```

По завершении печатается вердикт:
```
[Юлдаш loadtest] p95=… ms (цель <300), RPS=…, ошибок=…%
[Юлдаш loadtest] ✅ цель достигнута   (или ❌ с ненулевым кодом выхода)
```
Код выхода ≠ 0, если p95 > 300 мс **или** доля ошибок > 1% — удобно вешать в CI-гейт.

---

## 4. Переменные окружения (переключатели поведения)

| Переменная | По умолчанию | Смысл |
|---|---|---|
| `YULDASH_LOADTEST_TOKENS` | — | Пул токенов через запятую (способ Б) |
| `YULDASH_LOADTEST_INSTANT` | `0` | `1` → разрешить ограниченный quote smoke (не matcher) |
| `YULDASH_LOADTEST_WS` | `0` | `1` → включить WS-сценарий чата |
| `YULDASH_LOADTEST_WS_BOOKING` | — | `booking_id` живой брони на staging (для WS) |
| `YULDASH_LOADTEST_WS_TOKEN` | — | Токен участника этой брони (для WS) |
| `YULDASH_LOADTEST_P95_MS` | `300` | Порог p95 для вердикта |

> Базовый адрес staging задаётся флагом `--host`, **не** переменной — секретов и адресов
> в коде нет.

---

## 5. Как читать результат и что делать, если не уложились

Цель: **p95 < 300 мс** на `/rides/near` и `/rides` при 200 RPS.

Если p95 выше цели — идём по чеклисту в
[`docs/scale-ops-runbook.md`](../../docs/scale-ops-runbook.md):
1. `EXPLAIN (ANALYZE, BUFFERS)` по горячим запросам — есть ли Seq Scan там, где ждём Index Scan.
2. Проверить индексы (`from_city/to_city/status/depart_at`, PostGIS-GiST по координатам).
3. Поднять число uvicorn-воркеров (`gunicorn_conf.py`) и PgBouncer (pool exhaustion?).
4. Вынести Redis/PostgreSQL на отдельные инстансы.

---

## 6. Отказоустойчивость: «убили воркер»

Во время headless-прогона на staging убей один воркер и убедись, что RPS/ошибки не проваливаются:
```bash
# на staging-сервере:
systemctl status yuldash-api          # смотрим PID мастера
pkill -f 'uvicorn.*app.main' -n       # убить один дочерний воркер (gunicorn поднимет новый)
```
Ожидаемо: короткий всплеск, gunicorn форкает замену, клиентские запросы продолжают идти.
Это и есть критерий «готово» Фазы 5: *убийство одного воркера не роняет сервис*.


## 7. InstantUser: положительный контракт quote, НЕ нагрузочный прогон

Этот класс больше не создаёт заказ и не маскирует 401/404/422/429 под успех.
Только HTTP 200 с числовыми конечными неотрицательными `price`, `distance_km`,
`eta_min` и `category=standard` проходит проверку. `eta_min` — длительность поездки,
не время подачи. Дополнительные поля ответа разрешены.

**Не использовать прежний default mix ролей для этой проверки.** Другие роли выше
сохранены без изменений и могут создавать брони/авторизоваться. Quote тоже не
read-only: сервер пишет price-freeze/funnel в Redis и может вызвать платный routing.
Перед будущим отдельно согласованным запуском требуется disposable локальный сервер,
отдельные БД/Redis, синтетические координаты и токен тестового пользователя,
отключённые внешние провайдеры и техническое ограничение исходящей сети сервера.
Переменная-декларация ниже сама эту изоляцию НЕ обеспечивает.

Конфигурация InstantUser (никаких значений по умолчанию):
- `YULDASH_LOADTEST_INSTANT=1`;
- `YULDASH_LOADTEST_INSTANT_FIXTURE=isolated-no-external-providers`;
- `YULDASH_LOADTEST_INSTANT_ORIGIN`: точный `http://127.0.0.1:PORT`, совпадает с host;
- `YULDASH_LOADTEST_INSTANT_TOKEN`: заранее выданный fixture access token, не OTP;
- `YULDASH_LOADTEST_INSTANT_ROUTE`: JSON ровно с четырьмя полями `from_lat`,
  `from_lng`, `to_lat`, `to_lng` для синтетической разрешённой сцены.

Только InstantUser, один процесс/один пользователь, без distributed/workers.
Не production, внешний staging или reverse proxy к ним. Адрес запроса абсолютный,
redirect выключен, timeout 5 секунд, максимум одна попытка **на Python-процесс**,
включая отказ/429/ошибку транспорта. Это не общий distributed лимит и не измерение p95
или пропускной способности. Ошибка конфигурации блокирует запрос. Для повторной
попытки нужен новый отдельно разрешённый запуск, не автоматический retry.

Данный пакет проверен только локальными synthetic unit-тестами, без HTTP и нагрузки:
`python backend/loadtest/test_instant_quote_contract.py`.
Locust подменён минимальным stub только в тесте; исполняется настоящий модуль и метод.
Реальный Locust/server smoke и полные backend gates отдельно, здесь не заявлены.
