#!/bin/bash
# Автобэкап PostgreSQL «Юлдаш». Запускается по cron на сервере (см. server.md).
# Дамп → gzip → /opt/yuldash/backups, хранится последние 14.
set -e
DIR=/opt/yuldash/backups
mkdir -p "$DIR"
TS=$(date +%Y%m%d-%H%M)
FILE="$DIR/yuldash-$TS.sql.gz"
sudo -u postgres pg_dump yuldash | gzip > "$FILE"
# Чистим старые бэкапы: оставляем 14 свежих.
ls -1t "$DIR"/yuldash-*.sql.gz 2>/dev/null | tail -n +15 | xargs -r rm -f
# Чистим протухшие одноразовые записи (OTP-коды, tg-сессии) — иначе таблицы растут бесконечно.
sudo -u postgres psql -d yuldash -c "DELETE FROM otpcode WHERE expires_at < now() - interval '1 day'; DELETE FROM tgauth WHERE expires_at < now() - interval '1 day';" >/dev/null 2>&1 || true
echo "backup ok: $FILE ($(du -h "$FILE" | cut -f1))"
