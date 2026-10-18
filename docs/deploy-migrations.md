# 🗄️ Миграции БД — единый путь через alembic (WP-2)

> Зачем: раньше схема прода собиралась тремя способами (create_all + 22 ручных `migrate_*.sql` +
> alembic), и деплой применял только 5 SQL-файлов, а alembic-ревизии на прод не доезжали.
> Итог — колонка `Booking.boarding_code` в модели есть, а на проде её нет → риск 500.
> Теперь **единственный источник истины — `backend/alembic/versions/`**, деплой сам зовёт
> `alembic upgrade head`. `migrate_*.sql` перенесены в `backend/archive/` (не использовать).
>
> 🔄 *Актуализация 2026-10-10 (по коду):* цепочка выросла с четырёх ревизий до **130** (`backend/alembic/versions/`), голова одна — `bv_refresh_recovery`; `0004_booking_boarding_code` ниже — это пример из момента написания, а не текущая голова. Кроме `deploy-backend.bat` есть второй штатный путь — `backend/ops/deploy.sh` (на сервере: `git reset` → `pip install` → `alembic upgrade head` → рестарт → health-gate; при падении health откатывает код, а миграции — только при `ROLLBACK_DB=1`), см. [staging.md](staging.md). CI проверяет одну голову и цикл `upgrade → downgrade base → upgrade` на Postgres (`.github/workflows/ci.yml`, джоба `backend-tests-postgres`).

## Что уже сделано в коде (в этой ветке)
- Добавлена ревизия **`0004_booking_boarding_code`** — идемпотентно добавляет `booking.boarding_code`.
- `deploy-backend.bat`: теперь копирует `alembic/` + `alembic.ini` на сервер и выполняет
  `alembic upgrade head` вместо ручного psql.
- 22 `migrate_*.sql` → `backend/archive/`.

## Почему это безопасно (проверено на симуляции прод-БД)
Все ревизии **идемпотентны**:
- `0001` — `SQLModel.metadata.create_all` (существующие таблицы не трогает, создаёт только недостающие);
- `0002`–`0004` — `inspect`-before-alter (добавляют/убирают колонку, только если её состояние отличается).

Проверено локально (на момент написания, когда ревизий было четыре): на БД, собранной через `create_all` без таблицы `alembic_version` (ровно как прод),
`alembic upgrade head` проходит всю цепочку `0001→0004`, ставит версию `0004`, а повторный запуск = **0 миграций**.

## 🚀 Первый прогон на проде (онбординг на alembic) — делает Александр дома

Прод БД на момент написания **не под alembic** (нет таблицы `alembic_version`) — *актуальное состояние прода по коду не проверить (`alembic current` на сервере; [backend.md](backend.md) §8.5 говорит о штампе baseline на проде)*. Первый `alembic upgrade head`
безопасно прогонит всю цепочку. Порядок:

1. **Бэкап (обязательно):**
   ```
   ssh root@85.239.52.55 "/opt/yuldash/backup-db.sh"
   ```
   (создаёт дамп в `/opt/yuldash/backups/` + офсайт-копию в S3; см. server.md §бэкап)

2. **Деплой с миграцией** — просто запусти `backend/deploy-backend.bat` (двойной клик).
   Он скопирует alembic-файлы и выполнит `alembic upgrade head`.
   Либо вручную:
   ```
   ssh root@85.239.52.55 "cd /opt/yuldash && sudo -u yuldash ./.venv/bin/alembic upgrade head"
   ```

3. **Проверка:**
   ```
   ssh root@85.239.52.55 "cd /opt/yuldash && sudo -u yuldash ./.venv/bin/alembic current"
   # ждём: текущую голову из репо (`alembic heads`; на 2026-10-10 — bv_refresh_recovery (head))
   ssh root@85.239.52.55 "sudo -u postgres psql -d yuldash -c \"\\d booking\" | grep boarding_code"
   # ждём: строку boarding_code | character varying
   curl -s https://yulbash.ru/health   # {status:ok,...}
   ```

## ↩️ Откат
- **По схеме:** `sudo -u yuldash ./.venv/bin/alembic downgrade -1` (откатит последнюю ревизию; на момент написания — `boarding_code`).
  Все downgrade-функции ревизий тоже идемпотентны.
  > Откат проверяется целиком в CI (`backend-tests-postgres`): `upgrade head` → `downgrade base`
  > → `upgrade head`. До 2026-08-07 полный откат падал на SQLite — пять ревизий удаляли
  > колонку раньше, чем индекс по ней (детали — `docs/lessons.md`). Пишешь новую ревизию —
  > прогоняй цикл в обе стороны локально, одного `upgrade head` мало.
- **Полный откат БД из бэкапа** (если что-то пошло не так):
  ```
  gunzip -c /opt/yuldash/backups/ФАЙЛ.sql.gz | sudo -u postgres psql yuldash
  systemctl restart yuldash-api
  ```

## Дальше (как менять схему)
```
# в backend/, локально:
alembic revision -m "что меняем"     # написать идемпотентную upgrade/downgrade (inspect-before-alter)
alembic upgrade head                 # проверить локально
# затем деплой (deploy-backend.bat) сам применит на проде
```
Никаких ручных `migrate_*.sql` и `psql -f` — только alembic.
