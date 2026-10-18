# Юлдаш — бэкенд (FastAPI + PostgreSQL)

API сервиса попуток. Развёрнут на `https://yulbash.ru` (systemd `yuldash-api` + nginx + Postgres).
Полная картина — [../docs/system-design.md](../docs/system-design.md); карта кода — [../docs/architecture.md](../docs/architecture.md).

## Структура (после разрезки монолита, 2026-06-28)
```
app/
  main.py        — фабрика create_app(): CORS, медиа, middleware, lifespan, подключение роутеров
  config.py      — настройки из .env (+ validate_production: не стартовать прод с дырявой конфигурацией)
  db.py          — engine, init_db (create_all + лёгкая dev-миграция колонок для sqlite)
  models.py      — SQLModel-таблицы
  security.py    — JWT access + refresh-токены (make_token/issue_tokens/rotate_refresh/current_user), OTP-генератор
  timeutil.py    — utcnow() (наивный UTC, замена deprecated datetime.utcnow)
  services.py    — общая логика: push (FCM + Web Push)/SMS, доступ-к-броне, батч-витрина (анти-N+1), гео, seed, WebSocket-менеджер
  schemas.py     — общие pydantic-схемы (RideIn/RideOut)
  middleware.py  — rate-limit (IP), security-заголовки, access-лог, единый обработчик ошибок
  bodyguard.py   — потолок на размер тела запроса; digest.py — дневная сводка админу; observability.py — Sentry
  storage.py     — медиа: локальный диск или S3; errors.py — двуязычные ошибки API (RU/BA)
  routers/       — домены (список — routers/__init__.py, all_routers): health, auth, rides, requests, bookings,
                   drivers, chat, location, discovery, family, share, safety, reviews, payments, ads, instant,
                   wallet, debt, taxi, parcels, courier, coupons, promo, support, trust, weather и др.
  ...            — доменная логика рядом с роутерами (pricing, ledger, payments, taxi, automatch, antifraud и т.д.)
alembic/         — миграции схемы БД (единственный источник истины, см. «Деплой»)
deploy/          — systemd-юниты и таймеры (api, automatch, cleanup, doc-check, rate-reminder, taxi-worker), примеры nginx/pgbouncer/redis
ops/             — бэкапы, restore-verify, deploy.sh, preflight.py — [ops/README.md](ops/README.md)
loadtest/        — нагрузочные тесты Locust — [loadtest/README.md](loadtest/README.md)
tests/           — pytest (контракты, безопасность, регрессии аудитов)
archive/         — старые ручные SQL-миграции (историческое, не использовать)
```
Каждый роут доступен и на корне (`/rides`), и под версией (`/api/v1/rides`) — алиас для совместимости.
Вход — **Telegram-бот по коду** (SMS заморожен, оживляется флагом; есть также `/auth/vk-callback`, `/auth/whatsapp-callback`). Детали — [../docs/00-INDEX.md](../docs/00-INDEX.md).

## Запуск локально
```bash
cd backend
python -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt   # Linux/Mac: .venv/bin/python
cp .env.example .env                                       # отредактируй при желании
.venv\Scripts\python -m uvicorn app.main:app --reload
```
Swagger: http://127.0.0.1:8000/docs · здоровье: `/health` (с проверкой БД) · версия: `/version`.

### Через Docker (опционально)
```bash
docker compose up --build       # API + Postgres; см. docker-compose.yml
```

## Проверки (без отдельного сервера, через TestClient)
```bash
.venv\Scripts\python -m pytest tests/ -q   # ~530 файлов тестов (~3900 тест-функций): контракты, безопасность, регрессии аудитов
.venv\Scripts\python smoke.py              # сквозной сценарий → SMOKE OK
.venv\Scripts\python smoke_security.py     # IDOR/перебор/throttle → SECURITY SMOKE OK
```
Зависимости для тестов — `requirements-dev.txt`. CI (`.github/workflows/ci.yml`) гоняет `pytest` на SQLite с порогом покрытия 85% (`--cov-fail-under=85`) и отдельно на PostgreSQL (job `backend-tests-postgres`); линтер — `ruff check app/ --select F`.
`ws_scale_test.py` — не pytest, а ручная проверка WebSocket под gunicorn+Redis на сервере (`cd /opt/yuldash && .venv/bin/python ws_scale_test.py`).

## Основные эндпоинты
- **Auth:** `POST /auth/tg/start` → `request_id`; `POST /auth/tg/verify {request_id,code}` → `{access_token,user}`; `POST /auth/refresh`, `POST /auth/logout`. (`/auth/request-code`+`/auth/verify` — SMS, заморожен.) `GET /me`, `POST /push/register` (+ `/push/web/subscribe` для Web Push из PWA).
- **Поездки:** `POST /rides`, `GET /rides` (фильтры), `GET /rides/near`, `GET /rides/price_hint`, `GET /rides/{id}`.
- **Заявки/матчинг:** `POST /requests`, `GET /requests/mine`, `GET /match/rides?request_id`.
- **Брони:** `POST /bookings`, `/bookings/{id}/confirm|cancel`, `GET /bookings/mine`, `GET /driver/bookings`.
- **Чат:** `GET|POST /bookings/{id}/messages`, `WS /ws/bookings/{id}` (токен первым сообщением `{"type":"auth","token":…}`), `GET /conversations`, `GET /notifications`. Другие WebSocket: `/ws/map`, `/ws/trip/{id}/location`, `/ws/instant/{id}/chat|location`, `/ws/parcel/{id}/chat|location`.
- **Водитель:** `POST /driver/profile|verify|online`, `GET /driver/status`, `POST /upload/photo`, `POST /admin/drivers/{id}/moderate` (admin).
- **Рейтинг/семья/безопасность:** `POST /bookings/{id}/rate|share|trip-status`, `POST /trusted-contacts`, `POST /sos|reports|blocks`.
- **Витрина:** `GET /feed`, `GET /popular-routes`, `GET /my-routes`, `GET /ads`, `GET /geocode`, `POST /voice`.
- **Остальные домены** (быстрый заказ/такси `/instant/*`, кошелёк, посылки, курьер, купоны, промо, поддержка, доверие и др.) — полный список: `grep '@router' app/routers/*.py` или Swagger `/docs`.

## Деплой на прод
`deploy-backend.bat` (с машины Александра, SSH-ключ локально): `scp -r app\ alembic\ alembic.ini` + `chown` + `alembic upgrade head` (идемпотентно) + `systemctl restart yuldash-api` + health-проверка (`/health` на сервере и публично). Схема БД меняется **только через Alembic** (`alembic revision -m "…"` → ревизия в `alembic/versions/`; `SQLModel.create_all` колонки в существующие таблицы не добавляет). Старые ручные `migrate_*.sql` — в `archive/` (не использовать). Инструкция и откат — [../docs/deploy-migrations.md](../docs/deploy-migrations.md). Другие скрипты: `ops/deploy.sh` (git pull + health-gate + авто-откат), `verify-deploy.bat` / `verify-server.bat` (проверка прода), `deploy-automatch-timer.bat` (разовая установка таймера автоподбора). Подробности сервера — [../docs/server.md](../docs/server.md).

## Прод-безопасность (config.py: validate_production)
В `ENV=prod` сервис не стартует, если: дефолтный/dev/короткий (<16) `JWT_SECRET`, `CORS_ORIGINS=*`, sqlite, не-HTTPS `MEDIA_BASE_URL`, `SEED_DEMO` включён, Telegram-бот без `TELEGRAM_WEBHOOK_SECRET`, провайдер платежей/SMS/выплат/S3 выбран без своих ключей, `TAXI_ENABLED` без `REDIS_URL`, и ещё ряд проверок — полный список в `validate_production` (`app/config.py`). Неблокирующие предупреждения (нет `SENTRY_DSN`, `FIREBASE_CREDENTIALS`, `VAPID_PRIVATE_KEY` и т.п.) тоже выводятся. Заранее проверить без запуска сервиса: `python ops/preflight.py`. Анти-абуз — rate-limit (`RATE_LIMIT_*` в `.env`).
