# Юлдаш — бэкенд (FastAPI)

Фаза 1 настоящего сервиса. Реализует ядро из [../docs/backend.md](../docs/backend.md):
вход по SMS-коду (OTP), поездки, заявки, брони, матчинг, статус водителя.

> SMS пока **мок**: код пишется в лог сервера и (в `ENV=dev`) возвращается в ответе `request-code`, чтобы тестировать без реального SMS. Реальный провайдер подключим отдельно.

## Запуск локально (Windows / любой)
```bash
cd backend
python -m venv .venv
# Windows:
.venv\Scripts\python -m pip install -r requirements.txt
# Linux/Mac:
# .venv/bin/python -m pip install -r requirements.txt

cp .env.example .env          # отредактируй при желании
.venv\Scripts\python -m uvicorn app.main:app --reload
```
Документация API сама: открой http://127.0.0.1:8000/docs (Swagger).

## Проверка без сервера (смоук)
```bash
.venv\Scripts\python smoke.py
```
Должно напечатать `SMOKE OK` — значит весь сценарий (логин → поездка → поиск → заявка → матчинг → бронь → подтверждение) работает.

## Эндпоинты (Фаза 1)
- `POST /auth/request-code` `{phone}` → отправляет код (в dev — в ответе)
- `POST /auth/verify` `{phone, code, name?}` → `{access_token, user}`
- `GET /me` (Bearer)
- `POST /rides`, `GET /rides?from_city&to_city&category`, `GET /rides/{id}`
- `POST /requests`, `GET /requests/mine`
- `GET /match/rides?request_id`
- `POST /bookings`, `POST /bookings/{id}/confirm`, `GET /bookings/mine`
- `POST /driver/online`

## Деплой на сервер (когда будем разворачивать)
1. На сервере: Python 3.11+, склонировать репозиторий.
2. `python -m venv .venv && .venv/bin/pip install -r requirements.txt`.
3. `.env`: `ENV=prod`, `DATABASE_URL=postgresql://...` (Postgres), свой `JWT_SECRET` (`python -c "import secrets;print(secrets.token_urlsafe(48))"`).
4. Запуск под прод: `uvicorn app.main:app --host 0.0.0.0 --port 8000` (или gunicorn с uvicorn-воркерами), за `nginx` (HTTPS) и `systemd` (автозапуск).
5. Открыть только 443 (HTTPS), бэкенд слушает локально.

> Когда дойдём до разворота — мне понадобится доступ к серверу (SSH). **Не присылай пароли/ключи в чат.** Заведи мне SSH-доступ по ключу и скажи IP + ОС сервера; ключ передашь безопасным каналом. Тогда подключусь и разверну.

## Дальше (следующие фазы, backend.md §9)
Чат + голос → семейный контроль → безопасность (проверка документов) → админка → платежи → партнёры маршрута. И заменить ключевую таблицу `from_city/to_city` на гео-точки для настоящего матчинга по радиусу.
