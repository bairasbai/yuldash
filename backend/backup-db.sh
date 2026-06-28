#!/bin/bash
# Автобэкап PostgreSQL «Юлдаш». Запускается по cron на сервере (см. server.md).
# Дамп → gzip → /opt/yuldash/backups, хранится последние 14. + офсайт-копия в S3 (если настроено).
set -e
DIR=/opt/yuldash/backups
mkdir -p "$DIR"
TS=$(date +%Y%m%d-%H%M)
FILE="$DIR/yuldash-$TS.sql.gz"
sudo -u postgres pg_dump yuldash | gzip > "$FILE"
# Чистим старые локальные бэкапы: оставляем 14 свежих.
ls -1t "$DIR"/yuldash-*.sql.gz 2>/dev/null | tail -n +15 | xargs -r rm -f
# Чистим протухшие одноразовые записи (OTP-коды, tg-сессии) — иначе таблицы растут бесконечно.
sudo -u postgres psql -d yuldash -c "DELETE FROM otpcode WHERE expires_at < now() - interval '1 day'; DELETE FROM tgauth WHERE expires_at < now() - interval '1 day';" >/dev/null 2>&1 || true
echo "backup ok: $FILE ($(du -h "$FILE" | cut -f1))"

# --- Офсайт-копия в S3-совместимый бакет (Timeweb/Selectel/Backblaze и т.п.) ---
# ЗАЧЕМ: локальные бэкапы лежат на том же сервере → умер сервер = данные пропали.
# Копия в облако = страховка. Без настройки этот блок просто пропускается (локальный бэкап цел).
# Конфиг (НЕ в git!): /opt/yuldash/.backup-s3.env с переменными:
#   S3_ENDPOINT=https://s3.twcstorage.ru
#   S3_BUCKET=yuldash-backups
#   AWS_ACCESS_KEY_ID=...
#   AWS_SECRET_ACCESS_KEY=...
CFG=/opt/yuldash/.backup-s3.env
if [ -f "$CFG" ]; then
  set +e
  . "$CFG"
  if command -v aws >/dev/null 2>&1 && [ -n "$S3_BUCKET" ]; then
    export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
    if aws --endpoint-url "$S3_ENDPOINT" s3 cp "$FILE" "s3://$S3_BUCKET/$(basename "$FILE")" >/dev/null 2>&1; then
      echo "s3 upload ok: s3://$S3_BUCKET/$(basename "$FILE")"
    else
      echo "s3 upload FAILED (локальный бэкап сохранён — проверь .backup-s3.env / aws cli)"
    fi
  else
    echo "s3 skip: нет aws-cli или не задан S3_BUCKET в $CFG"
  fi
  set -e
fi
