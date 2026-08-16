#!/usr/bin/env bash
# =============================================================================
# Юлдаш · Ежедневный бэкап PostgreSQL
# -----------------------------------------------------------------------------
# Что делает:
#   1) снимает дамп базы через pg_dump и сразу сжимает его gzip;
#   2) кладёт файл в каталог бэкапов с датой в имени (yuldash-ГГГГММДД-ЧЧММ.sql.gz);
#   3) ротация: удаляет локальные дампы старше N дней (по умолчанию 14);
#   4) офсайт-копия в S3 (если настроен .backup-s3.env) — страховка на случай смерти сервера.
#
# ВАЖНО ПРО СЕКРЕТЫ:
#   Скрипт НЕ содержит паролей/ключей. Всё берётся из окружения или из
#   env-файлов вне git (см. BACKUP_ENV_FILE и S3_ENV_FILE ниже).
#   Пароль БД — через стандартные переменные libpq (PGPASSWORD/.pgpass) или
#   через локальный peer-доступ (sudo -u postgres), как на нашем сервере.
#
# Запуск (обычно из cron, см. ops/README.md):
#   backend/ops/backup.sh
#
# Ручной запуск с переопределением базы/каталога:
#   DB_NAME=yuldash BACKUP_DIR=/opt/yuldash/backups backend/ops/backup.sh
# =============================================================================

set -euo pipefail

# --- 0) Необязательный env-файл с настройками (НЕ в git) --------------------
# Если рядом есть файл с переменными окружения — подхватываем его. Так Александр
# может держать все пути/имена в одном месте, а не прокидывать через cron.
BACKUP_ENV_FILE="${BACKUP_ENV_FILE:-/opt/yuldash/.backup.env}"
if [ -f "$BACKUP_ENV_FILE" ]; then
  # shellcheck disable=SC1090
  . "$BACKUP_ENV_FILE"
fi

# --- 1) Настройки (значения по умолчанию — под наш прод, всё переопределяемо) -
DB_NAME="${DB_NAME:-yuldash}"                     # имя базы для дампа
BACKUP_DIR="${BACKUP_DIR:-/opt/yuldash/backups}"  # куда складывать дампы
KEEP_DAYS="${KEEP_DAYS:-14}"                       # сколько дней хранить локально
# Как запускать pg_dump/psql:
#   - на сервере база доступна пользователю postgres по peer-авторизации,
#     поэтому по умолчанию заходим через `sudo -u postgres` (без пароля в git);
#   - если задан PGUSER/PGPASSWORD или свой PSQL_AS — используется он.
PSQL_AS="${PSQL_AS:-sudo -u postgres}"             # префикс запуска клиентов PG (можно очистить: PSQL_AS="")

# --- 2) Готовим каталог и имя файла -----------------------------------------
mkdir -p "$BACKUP_DIR"
TS="$(date +%Y%m%d-%H%M)"
FILE="$BACKUP_DIR/${DB_NAME}-${TS}.sql.gz"

# Если дамп упадёт на полпути (pg_dump недоступен, база отвалилась) — удаляем
# недописанный файл, чтобы он потом не «прикинулся» самым свежим бэкапом.
cleanup_partial() { [ -n "${DUMP_OK:-}" ] || rm -f "$FILE"; }
trap cleanup_partial EXIT

echo "[backup] база=$DB_NAME → $FILE"

# --- 3) Сам дамп: pg_dump | gzip --------------------------------------------
# pg_dump пишет в stdout, gzip сжимает на лету — без промежуточного большого файла.
# set -o pipefail (выше) гарантирует, что падение pg_dump в пайпе не пройдёт молча.
# shellcheck disable=SC2086
$PSQL_AS pg_dump "$DB_NAME" | gzip > "$FILE"

# Санити-чек: файл существует и не подозрительно мал (пустой gzip ~20 байт — провал).
if [ ! -s "$FILE" ] || [ "$(stat -c%s "$FILE" 2>/dev/null || echo 0)" -lt 100 ]; then
  echo "[backup] ОШИБКА: дамп пустой или не создан ($FILE)" >&2
  rm -f "$FILE"
  exit 1
fi

DUMP_OK=1  # дамп валиден — снимаем «уборку недописанного файла» (см. trap выше)
echo "[backup] ok: $FILE ($(du -h "$FILE" | cut -f1))"

# --- 4) Ротация: чистим локальные дампы старше KEEP_DAYS ---------------------
# Удаляем только наши файлы (по маске ИМЯБАЗЫ-*.sql.gz), чужое в каталоге не трогаем.
find "$BACKUP_DIR" -maxdepth 1 -name "${DB_NAME}-*.sql.gz" -type f -mtime "+${KEEP_DAYS}" -print -delete \
  | sed 's/^/[backup] удалён старый: /' || true

# --- 5) Офсайт-копия в S3 (опционально) -------------------------------------
# ЗАЧЕМ: локальные бэкапы лежат на том же сервере → умер сервер = данные пропали.
# Копия в облако = страховка. Настройка (ключи S3, НЕ в git) — в S3_ENV_FILE:
#   S3_ENDPOINT=https://s3.twcstorage.ru
#   S3_BUCKET=yuldash-backups
#   AWS_ACCESS_KEY_ID=...
#   AWS_SECRET_ACCESS_KEY=...
#
# ШИФРОВАНИЕ ПЕРЕД ОТПРАВКОЙ (аудит 2026-08-08, волна 130).
# В дампе — весь район: имена, телефоны, адреса, координаты поездок, переписка, ссылки
# на документы водителей. Раньше он уходил в чужое облако КАК ЕСТЬ: один утёкший ключ S3
# (или ошибка в правах бакета) = вся база у постороннего, и никто об этом даже не узнает.
# Поэтому в облако едет только зашифрованный файл, а без ключа мы туда просто не идём —
# локальная копия при этом остаётся, её никто не отменяет.
#
# Ключ — длинная случайная строка в BACKUP_ENV_FILE (НЕ в git и НЕ в облаке):
#   BACKUP_ENCRYPT_PASSPHRASE=...
# Хранить его надо ОТДЕЛЬНО от бэкапов (иначе смысла нет): например, в менеджере паролей.
# Без него расшифровать дамп будет нельзя — это цена настоящего шифрования.
S3_ENV_FILE="${S3_ENV_FILE:-/opt/yuldash/.backup-s3.env}"
if [ -f "$S3_ENV_FILE" ]; then
  # shellcheck disable=SC1090
  . "$S3_ENV_FILE"
  if [ -z "${BACKUP_ENCRYPT_PASSPHRASE:-}" ]; then
    echo "[backup] s3 ПРОПУЩЕН: не задан BACKUP_ENCRYPT_PASSPHRASE." >&2
    echo "[backup] В дампе персональные данные всего района — в облако он уходит только" >&2
    echo "[backup] зашифрованным. Заведи длинную случайную строку в $BACKUP_ENV_FILE" >&2
    echo "[backup] и храни её ОТДЕЛЬНО от бэкапов. Локальная копия уже сделана." >&2
  elif ! command -v openssl >/dev/null 2>&1; then
    echo "[backup] s3 ПРОПУЩЕН: нет openssl, шифровать нечем (локальная копия цела)" >&2
  elif command -v aws >/dev/null 2>&1 && [ -n "${S3_BUCKET:-}" ]; then
    ENC_FILE="${FILE}.enc"
    # AES-256 с нормальным выводом ключа из пароля (pbkdf2 + соль). Пароль передаём
    # через переменную окружения, а не аргументом: аргументы видны в списке процессов.
    if BACKUP_ENCRYPT_PASSPHRASE="$BACKUP_ENCRYPT_PASSPHRASE" openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt          -in "$FILE" -out "$ENC_FILE" -pass env:BACKUP_ENCRYPT_PASSPHRASE 2>/dev/null; then
      export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
      if aws --endpoint-url "${S3_ENDPOINT:-}" s3 cp "$ENC_FILE" "s3://$S3_BUCKET/$(basename "$ENC_FILE")" >/dev/null 2>&1; then
        echo "[backup] s3 ok (зашифровано): s3://$S3_BUCKET/$(basename "$ENC_FILE")"
      else
        echo "[backup] s3 FAILED (локальный бэкап цел — проверь $S3_ENV_FILE / aws-cli)" >&2
      fi
      rm -f "$ENC_FILE"          # шифрованную копию на диске не держим: в облаке она уже есть
    else
      echo "[backup] s3 ПРОПУЩЕН: не удалось зашифровать дамп (локальная копия цела)" >&2
      rm -f "$ENC_FILE"
    fi
  else
    echo "[backup] s3 skip: нет aws-cli или не задан S3_BUCKET в $S3_ENV_FILE"
  fi
fi

echo "[backup] готово."
