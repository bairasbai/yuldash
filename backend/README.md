# Юлдаш — бэкенд (FastAPI + PostgreSQL)

API сервиса попуток. Развёрнут на `https://yulbash.ru` (systemd `yuldash-api` + nginx + Postgres).
Полная картина — [../docs/system-design.md](../docs/system-design.md); карта кода — [../docs/architecture.md](../docs/architecture.md).

## Структура (после разрезки монолита, 2026-06-28)
```
app/
  main.py        — фабрика create_app(): CORS, медиа, middleware, lifespan, подключение роутеров
  config.py      — настройки из .env (+ validate_production: не стартовать прод с дырявой конфигурацией)
  db.py          — engine, init_db (create_all + лёгкая dev-миграция колонок для sqlite)
  security.py    — JWT (make_token/verify_token/current_user), OTP-генератор
  timeutil.py    — utcnow() (наивный UTC, замена deprecated datetime.utcnow)
  services.py    — общая логика: push/SMS, доступ-к-броне, батч-витрина (анти-N+1), гео, seed, WebSocket-менеджер
  schemas.py     — общие pydantic-схемы (RideIn/RideOut)
  middleware.py  — rate-limit (IP), security-заголовки, access-лог, единый обработчик ошибок
  routers/       — домены: health, auth, rides, requests, bookings, drivers, chat, discovery, family, safety
```
Каждый роут доступен и на корне (`/rides`), и под версией (`/api/v1/rides`) — алиас для совместимости.
Вход — **Telegram-бот по коду** (SMS заморожен, оживляется флагом). Детали — [../docs/00-INDEX.md](../docs/00-INDEX.md).

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
.venv\Scripts\python -m pytest tests/ -q   # 42 контрактных теста по всем доменам
.venv\Scripts\python smoke.py              # сквозной сценарий → SMOKE OK
.venv\Scripts\python smoke_security.py     # IDOR/перебор/throttle → SECURITY SMOKE OK
```

## Основные эндпоинты
- **Auth:** `POST /auth/tg/start` → `request_id`; `POST /auth/tg/verify {request_id,code}` → `{access_token,user}`. (`/auth/request-code`+`/auth/verify` — SMS, заморожен.) `GET /me`, `POST /push/register`.
- **Поездки:** `POST /rides`, `GET /rides` (фильтры), `GET /rides/near`, `GET /rides/price_hint`, `GET /rides/{id}`.
- **Заявки/матчинг:** `POST /requests`, `GET /requests/mine`, `GET /match/rides?request_id`.
- **Брони:** `POST /bookings`, `/bookings/{id}/confirm|cancel`, `GET /bookings/mine`, `GET /driver/bookings`.
- **Чат:** `GET|POST /bookings/{id}/messages`, `WS /ws/bookings/{id}` (токен первым сообщением), `GET /conversations`, `GET /notifications`.
- **Водитель:** `POST /driver/profile|verify|online`, `GET /driver/status`, `POST /upload/photo`, `POST /admin/drivers/{id}/moderate` (admin).
- **Рейтинг/семья/безопасность:** `POST /bookings/{id}/rate|share|trip-status`, `POST /trusted-contacts`, `POST /sos|reports|blocks`.
- **Витрина:** `GET /feed`, `GET /popular-routes`, `GET /my-routes`, `GET /ads`, `GET /geocode`, `POST /voice`.

## Деплой на прод
`deploy-backend.bat` (с машины Александра, SSH-ключ локально): `scp -r app\` + миграции `migrate_*.sql` (`psql -f`) + `systemctl restart yuldash-api` + health-проверка. Схема: новые **таблицы** создаёт `create_all` при старте; новые **колонки** — отдельным `migrate_*.sql` (`SQLModel.create_all` колонки не добавляет). Подробности сервера — [../docs/server.md](../docs/server.md).

## Прод-безопасность (config.py: validate_production)
В `ENV=prod` сервис не стартует, если: дефолтный/короткий `JWT_SECRET`, `CORS_ORIGINS=*`, sqlite, не-HTTPS `MEDIA_BASE_URL`, Telegram-бот без `TELEGRAM_WEBHOOK_SECRET`. Анти-абуз — rate-limit (`RATE_LIMIT_*` в `.env`).
