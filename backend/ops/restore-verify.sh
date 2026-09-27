#!/usr/bin/env bash
# =============================================================================
# Юлдаш · Репетиция восстановления (restore drill)
# -----------------------------------------------------------------------------
# ЗАЧЕМ: бэкап, который никто ни разу не восстанавливал, — это НЕ бэкап, а надежда.
# Этот скрипт регулярно (по cron) проверяет, что свежий дамп реально
# разворачивается и данные внутри целы. Он:
#   1) берёт самый свежий бэкап (или файл из аргумента $1);
#   2) поднимает его во ВРЕМЕННУЮ базу (боевую НЕ трогает);
#   3) проверяет целостность: сколько таблиц, сколько строк в ключевых
#      (user / ride / booking);
#   4) строго сверяет схему с кодом: `alembic heads` должен успешно вернуть
#      ровно одну голову; alembic_version — ровно одну такую же ревизию;
#   5) печатает итог OK / FAIL и гарантированно сносит временную базу.
#
# Секретов в скрипте нет — доступ к БД через peer (sudo -u postgres) или
# стандартные libpq-переменные окружения. Ничего в git не пишется.
#
# Запуск:
#   backend/ops/restore-verify.sh                 # проверить самый свежий дамп
#   backend/ops/restore-verify.sh /путь/дамп.sql.gz   # проверить конкретный файл
#
# Код возврата: 0 = данные восстановлены и схема совпадает с текущим кодом;
# 1 = восстановление или подтверждение совместимости не удалось. Старый дамп
# с другой ревизией не объявляется негодным: отдельно испытайте его upgrade
# на восстановленной копии. Этот скрипт не выполняет ни upgrade, ни stamp.
# =============================================================================

set -uo pipefail  # без -e: часть проверок мы обрабатываем сами и хотим дойти до итога

# --- 0) Необязательный env-файл (тот же, что у backup.sh) -------------------
BACKUP_ENV_FILE="${BACKUP_ENV_FILE:-/opt/yuldash/.backup.env}"
if [ -f "$BACKUP_ENV_FILE" ]; then
  # shellcheck disable=SC1090
  . "$BACKUP_ENV_FILE"
fi

# --- 1) Настройки (всё переопределяемо через env) ---------------------------
DB_NAME="${DB_NAME:-yuldash}"                      # имя боевой базы (для имени файлов и таблиц)
BACKUP_DIR="${BACKUP_DIR:-/opt/yuldash/backups}"   # где искать свежий дамп
APP_DIR="${APP_DIR:-/opt/yuldash}"                 # где лежат alembic.ini + alembic
PSQL_AS="${PSQL_AS:-sudo -u postgres}"             # префикс запуска psql/createdb/dropdb
MIN_TABLES="${MIN_TABLES:-15}"                     # минимум таблиц в живой базе (у нас ~26)
# Команда alembic: на проде — из venv, иначе — системная.
if [ -n "${ALEMBIC_CMD:-}" ]; then
  :
elif [ -x "$APP_DIR/.venv/bin/alembic" ]; then
  ALEMBIC_CMD="$APP_DIR/.venv/bin/alembic"
else
  ALEMBIC_CMD="alembic"
fi

# Хелперы запуска psql: -tA = «голое» значение без рамок/заголовков.
psql_tmp()  { $PSQL_AS psql -tA -d "$TMPDB" -c "$1"; }
psql_adm()  { $PSQL_AS psql -tA -d postgres -c "$1"; }

fail() { echo "[verify] FAIL: $*" >&2; exit 1; }

# --- 2) Выбираем дамп для проверки ------------------------------------------
if [ "${1:-}" != "" ]; then
  BACKUP="$1"
else
  # Самый свежий по времени файл нашей базы.
  BACKUP="$(ls -1t "$BACKUP_DIR/${DB_NAME}-"*.sql.gz 2>/dev/null | head -n1 || true)"
fi
[ -n "$BACKUP" ] && [ -f "$BACKUP" ] || fail "не нашёл ни одного бэкапа в $BACKUP_DIR (маска ${DB_NAME}-*.sql.gz)"

# Проверяем именно расшифрованный gzip до создания БД. Зашифрованные байты
# сами по себе не gzip; plaintext на диск не записываем.
backup_gzip_stream() {
  if [ "${BACKUP##*.}" = "enc" ]; then
    openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 \
      -pass env:BACKUP_ENCRYPT_PASSPHRASE -in "$BACKUP"
  else
    cat -- "$BACKUP"
  fi
}
if [ "${BACKUP##*.}" = "enc" ]; then
  [ -n "${BACKUP_ENCRYPT_PASSPHRASE:-}" ] || fail "дамп зашифрован, а BACKUP_ENCRYPT_PASSPHRASE не задан"
  # Значение из sourced env-файла тоже должно быть доступно дочернему openssl.
  export BACKUP_ENCRYPT_PASSPHRASE
fi
backup_gzip_stream | gzip -t 2>/dev/null || fail "не удалось проверить gzip или расшифровать бэкап: $BACKUP"
echo "[verify] проверяю бэкап: $BACKUP ($(du -h "$BACKUP" | cut -f1))"

# --- 3) Временная база + гарантированная уборка за собой --------------------
TMPDB="verify_${DB_NAME}_$(date +%s)_$$"
cleanup() {
  # Сносим временную базу в любом случае (успех/ошибка/прерывание).
  $PSQL_AS psql -q -d postgres -c "DROP DATABASE IF EXISTS \"$TMPDB\";" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

echo "[verify] создаю временную базу $TMPDB"
psql_adm "CREATE DATABASE \"$TMPDB\";" >/dev/null || fail "не смог создать временную базу $TMPDB"

# --- 4) Разворачиваем дамп во временную базу --------------------------------
# ON_ERROR_STOP=1 — падаем на первой же реальной ошибке SQL (битый дамп/несовместимость).
# Возможные безобидные NOTICE про роли/владельцев не считаются ошибками.
echo "[verify] восстанавливаю дамп во временную базу…"
# Дамп из облака зашифрован (волна 130): там персональные данные всего района, и в чужое
# хранилище он уходит только под шифром. Расшифровываем на лету тем же ключом, что и шифровали.
# Локальные дампы лежат как есть — они на нашем сервере, под правами yuldash.
if ! backup_gzip_stream | gunzip -c | $PSQL_AS psql -q -v ON_ERROR_STOP=1 -d "$TMPDB" >/dev/null; then
  fail "psql не смог применить дамп (см. вывод выше) — бэкап негоден"
fi

# --- 5) Проверка целостности ------------------------------------------------
# 5.1 Сколько таблиц восстановилось.
TABLES="$(psql_tmp "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';" | tr -d '[:space:]')"
echo "[verify] таблиц в public: ${TABLES:-?}"
[ -n "$TABLES" ] && [ "$TABLES" -ge "$MIN_TABLES" ] 2>/dev/null \
  || fail "таблиц слишком мало (${TABLES:-0} < $MIN_TABLES) — дамп неполный"

# 5.2 Ключевые таблицы должны существовать и читаться. "user" — зарезервированное
#     слово, поэтому в кавычках. Пустая таблица (0 строк) — это ОК (новая база),
#     провал только если запрос падает (таблицы нет / данные битые).
CHECK_TABLES=("user" "ride" "booking")
for t in "${CHECK_TABLES[@]}"; do
  cnt="$(psql_tmp "SELECT count(*) FROM \"$t\";" 2>/dev/null | tr -d '[:space:]')"
  if [ -z "$cnt" ]; then
    fail "ключевая таблица \"$t\" отсутствует или не читается"
  fi
  echo "[verify]   $t: $cnt строк"
done

# --- 6) Сверка схемы с кодом (alembic) --------------------------------------
# 6.1 Граф миграций в коде цел и голова одна (не разъехались ветки ревизий).
schema_fail() {
  fail "данные восстановились, но совместимость схемы с текущим кодом не подтверждена: $*. Для исторического дампа испытайте upgrade на отдельной восстановленной копии; stamp не заменяет миграции."
}
[ -f "$APP_DIR/alembic.ini" ] || schema_fail "нет alembic.ini в $APP_DIR"
if ! HEAD_LINES="$(cd "$APP_DIR" && "$ALEMBIC_CMD" heads)"; then
  schema_fail "не удалось выполнить alembic heads — проверьте окружение и граф миграций"
fi
HEAD_COUNT="$(printf '%s\n' "$HEAD_LINES" | awk 'NF {n++} END {print n+0}')"
[ "$HEAD_COUNT" = 1 ] || schema_fail "ожидалась одна голова миграций, получено $HEAD_COUNT"
CODE_HEAD="$(printf '%s\n' "$HEAD_LINES" | awk 'NF && $NF == "(head)" {print $1}')"
[ -n "$CODE_HEAD" ] || schema_fail "не распознан результат alembic heads"
echo "[verify] голова миграций в коде: $CODE_HEAD"

# 6.2 Проверяем все строки: LIMIT 1 скрывал разошедшиеся ветки в самом дампе.
if ! DB_REVISIONS="$(psql_tmp "SELECT version_num FROM alembic_version;" 2>/dev/null)"; then
  schema_fail "не удалось прочитать alembic_version"
fi
REV_COUNT="$(printf '%s\n' "$DB_REVISIONS" | awk 'NF {n++} END {print n+0}')"
[ "$REV_COUNT" = 1 ] || schema_fail "ожидалась одна применённая ревизия, получено $REV_COUNT"
DB_REV="$(printf '%s\n' "$DB_REVISIONS" | tr -d '[:space:]')"
[ "$DB_REV" = "$CODE_HEAD" ] || schema_fail "ревизия дампа ($DB_REV) не совпадает с головой кода ($CODE_HEAD)"
echo "[verify] схема совпадает с кодом (ревизия $DB_REV = голова)"

# --- 7) Итог ----------------------------------------------------------------
echo "[verify] OK: бэкап $BACKUP успешно восстановлен и прошёл проверку целостности."
exit 0
