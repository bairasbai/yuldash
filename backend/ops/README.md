# 🛟 Юлдаш · Бэкапы и восстановление (DR-runbook)

> Простыми словами: что делать, чтобы данные не пропали, и как поднять сервис, если сервер умер.
> Эти доки — для Александра, только на русском (не двуязычим — это ops, не UI).

Файлы этой папки (`backend/ops/`):

| Файл | Что делает |
|---|---|
| `backup.sh` | Ежедневный дамп базы → gzip → каталог бэкапов + офсайт-копия в S3. Ротация N дней. |
| `restore-verify.sh` | «Репетиция восстановления»: поднимает свежий дамп во временную базу и проверяет, что данные целы. |
| `crontab.example` | Готовые строки cron (раскомментировать, подставить пути). |
| `systemd.example` | То же через systemd-таймеры (альтернатива cron). |

---

## 1. Как это устроено (в двух словах)

- **Раз в сутки** (04:00) `backup.sh` снимает сжатый дамп PostgreSQL в `/opt/yuldash/backups/`
  и, если настроен S3, кладёт копию в облако (Timeweb-бакет `yuldash-backups`).
- **Локально** хранится последние `KEEP_DAYS` дней (по умолчанию 14). Старое чистится само.
- **Раз в неделю** `restore-verify.sh` берёт свежий дамп и реально его разворачивает во
  временную базу — так мы заранее знаем, что бэкап рабочий, а не «файл-пустышка».
- **Офсайт** (копия в другом месте) — обязательна: если сервер физически умрёт,
  локальные бэкапы умрут вместе с ним. Спасает только облачная копия.

Никаких паролей и ключей в этих скриптах нет. Настройки — через переменные окружения
или через файлы **вне git**:
- `/opt/yuldash/.backup.env` — общие настройки (имя базы, каталог, сколько дней хранить);
- `/opt/yuldash/.backup-s3.env` — ключи доступа к S3 (права `600`, **никогда** не в git).

---

## 2. Настройки (переменные окружения)

Все с разумными значениями по умолчанию под наш прод — можно ничего не задавать.

| Переменная | По умолчанию | Смысл |
|---|---|---|
| `DB_NAME` | `yuldash` | Имя базы. |
| `BACKUP_DIR` | `/opt/yuldash/backups` | Куда складывать дампы. |
| `KEEP_DAYS` | `14` | Сколько дней хранить локальные дампы. |
| `PSQL_AS` | `sudo -u postgres` | Как заходить в PG. На сервере — peer-доступ без пароля. Для доступа по паролю: `PSQL_AS=""` + libpq-переменные (`PGHOST/PGUSER/PGPASSWORD` или `~/.pgpass`). |
| `APP_DIR` | `/opt/yuldash` | Где лежат `alembic.ini` + `alembic/` (для сверки схемы). |
| `MIN_TABLES` | `15` | Порог «база не пустая» для restore-verify (у нас ~26 таблиц). |

Пример `/opt/yuldash/.backup.env` (не в git):
```sh
DB_NAME=yuldash
BACKUP_DIR=/opt/yuldash/backups
KEEP_DAYS=14
```

Пример `/opt/yuldash/.backup-s3.env` (не в git, права 600):
```sh
S3_ENDPOINT=https://s3.twcstorage.ru
S3_BUCKET=yuldash-backups
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...
```

---

## 3. Расписание (cron)

Готовые строки — в `crontab.example`. Кратко:
```cron
# бэкап каждый день в 04:00
0 4 * * *  /opt/yuldash/backend/ops/backup.sh          >> /opt/yuldash/backups/backup.log 2>&1
# репетиция восстановления по воскресеньям в 05:00
0 5 * * 0  /opt/yuldash/backend/ops/restore-verify.sh  >> /opt/yuldash/backups/restore-verify.log 2>&1
```
Установка: `crontab -e` → вставить → сохранить. Проверка: `crontab -l`.
Альтернатива через systemd — см. `systemd.example`.

---

## 4. Как проверить, что бэкап валиден

Есть два уровня — быстрый и полный.

**Быстрый (руками, 10 секунд):**
```sh
# самый свежий файл существует и не пустой:
ls -lt /opt/yuldash/backups/yuldash-*.sql.gz | head
# gzip не битый:
gzip -t /opt/yuldash/backups/$(ls -t /opt/yuldash/backups | grep '\.sql\.gz$' | head -1)
```

**Полный (репетиция восстановления):**
```sh
/opt/yuldash/backend/ops/restore-verify.sh
```
Скрипт сам поднимет свежий дамп во временную базу, посчитает таблицы и строки в
ключевых таблицах (`user`, `ride`, `booking`), сверит схему с кодом (`alembic heads`)
и напишет в конце `OK` или `FAIL`. Временную базу удалит за собой в любом случае.
Код возврата: `0` = ок, `1` = бэкап негоден (повод разобраться немедленно).

---

## 5. 🚨 Сервер умер — как поднять сервис (пошагово)

Цель — снова принимать трафик на новом сервере за **1–3 часа**. Порядок:

### Шаг 0. Взять последний бэкап
- Если старый сервер ещё доступен по SSH — забрать локальный дамп:
  ```sh
  scp root@85.239.52.55:/opt/yuldash/backups/yuldash-*.sql.gz .
  ```
- Если сервер мёртв — забрать офсайт-копию из S3:
  ```sh
  aws --endpoint-url https://s3.twcstorage.ru s3 ls s3://yuldash-backups/
  aws --endpoint-url https://s3.twcstorage.ru s3 cp s3://yuldash-backups/ИМЯ-ФАЙЛА.sql.gz .
  ```

### Шаг 1. Новый сервер: базовое окружение
```sh
apt update && apt install -y postgresql python3-venv nginx
```

### Шаг 2. Создать базу и пользователя
```sh
sudo -u postgres psql -c "CREATE DATABASE yuldash;"
# (при необходимости создать роль приложения — как на старом сервере)
```

### Шаг 3. Восстановить данные из дампа
```sh
gunzip -c yuldash-ДАТА.sql.gz | sudo -u postgres psql yuldash
```

### Шаг 4. Развернуть код бэкенда
```sh
# скопировать /opt/yuldash (app/, alembic/, alembic.ini, .env, .venv) со старого
# сервера ИЛИ выкатить заново через backend/deploy-backend.bat.
cd /opt/yuldash && sudo -u yuldash ./.venv/bin/alembic upgrade head   # добить схему до головы
```
> `.env` (секреты: JWT_SECRET, ключи SMS/Telegram/YooKassa, DATABASE_URL) в git нет —
> держи его резервную копию в надёжном месте (менеджер паролей). Без него сервис не стартует.

### Шаг 5. Поднять сервис и nginx
```sh
systemctl restart yuldash-api && systemctl is-active yuldash-api
# nginx-конфиг: /etc/nginx/sites-available/yuldash (см. docs/server.md)
```

### Шаг 6. Проверка «живой»
```sh
curl -s https://yulbash.ru/health     # ждём {status:ok, db:ok, ...}
```

### Шаг 7. Убедиться, что данные на месте
```sh
sudo -u postgres psql -d yuldash -c "SELECT count(*) FROM \"user\";"
sudo -u postgres psql -d yuldash -c "SELECT count(*) FROM ride;"
```

Если `/health` = ok и счётчики совпадают с ожидаемыми — сервис восстановлен.

---

## 6. Ориентиры RPO / RTO (простыми словами)

- **RPO** (сколько данных максимум потеряем) ≈ **24 часа** — бэкап раз в сутки.
  Хочешь меньше — увеличь частоту `backup.sh` в cron (напр. каждые 6 часов).
- **RTO** (за сколько поднимемся) ≈ **1–3 часа** при наличии свежего дампа и `.env`.

---

## 7. Частые грабли

- **Нет `.env` → сервис не стартует.** Держи резервную копию `.env` отдельно от сервера.
- **Бэкапы только локально → умер сервер, умерли и они.** Всегда держи офсайт-копию (S3).
- **«Бэкап есть, а восстановить не пробовали».** Для этого и нужен `restore-verify.sh`
  по расписанию — он ловит негодный бэкап заранее, а не в момент аварии.
- **S3-ключи в git.** Никогда. Только `/opt/yuldash/.backup-s3.env` с правами `600`.
