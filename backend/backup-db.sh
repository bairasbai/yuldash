#!/bin/bash
# Автобэкап PostgreSQL «Юлдаш». Запускается по cron на сервере (см. server.md).
# Дамп → gzip → /opt/yuldash/backups, хранится последние 14.
set -e
DIR=/opt/yuldash/backups
mkdir -p "$DIR"
TS=$(date +%Y%m%d-%H%M)
FILE="$DIR/yuldash-$TS.sql.gz"
sudo -u postgres pg_dump yuldash | gzip > "$FILE"
# Чистим старые: оставляем 14 свежих.
ls -1t "$DIR"/yuldash-*.sql.gz 2>/dev/null | tail -n +15 | xargs -r rm -f
echo "backup ok: $FILE ($(du -h "$FILE" | cut -f1))"
