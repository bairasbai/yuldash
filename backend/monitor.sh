#!/bin/bash
# Мониторинг прода «Юлдаш»: пинг /health (cron раз в минуту), алерт в Telegram при падении.
# Шлёт сообщение ТОЛЬКО на смене состояния (ok→down и обратно) — без спама каждую минуту.
# Реиспользует бот @yuldash_sms_bot (токен из /opt/yuldash/.env).
#
# Настройка (твой шаг): создать /opt/yuldash/.monitor.env (НЕ в git) с одной строкой:
#   ALERT_CHAT_ID=123456789
# Как узнать свой chat_id: напиши боту @yuldash_sms_bot любое сообщение, затем на сервере:
#   curl -s "https://api.telegram.org/bot$(grep -iE '^TELEGRAM_BOT_TOKEN=' /opt/yuldash/.env|cut -d= -f2-|tr -d '\"')/getUpdates" | grep -o '"id":[0-9]*' | head -1
# Без ALERT_CHAT_ID скрипт работает, но молчит (алерт не уйдёт).
STATE=/opt/yuldash/.monitor.state
ENVF=/opt/yuldash/.env
CFG=/opt/yuldash/.monitor.env
[ -f "$CFG" ] && . "$CFG"
TOKEN=$(grep -iE '^TELEGRAM_BOT_TOKEN=' "$ENVF" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'")

send() {
  [ -n "$TOKEN" ] && [ -n "$ALERT_CHAT_ID" ] && \
    curl -s -m 10 "https://api.telegram.org/bot$TOKEN/sendMessage" \
      --data-urlencode "chat_id=$ALERT_CHAT_ID" \
      --data-urlencode "text=$1" >/dev/null 2>&1
}

code=$(curl -s -m 8 -o /dev/null -w '%{http_code}' http://127.0.0.1:8000/health)
body=$(curl -s -m 8 http://127.0.0.1:8000/health)
now="down"
[ "$code" = "200" ] && echo "$body" | grep -q '"status":"ok"' && echo "$body" | grep -q '"db":"ok"' && now="up"

prev=$(cat "$STATE" 2>/dev/null || echo "up")
echo "$now" > "$STATE"

if [ "$now" != "$prev" ]; then
  if [ "$now" = "down" ]; then
    send "🔴 Юлдаш ПРОД УПАЛ. /health=$code body=$body. Зайди на сервер: systemctl status yuldash-api"
  else
    send "🟢 Юлдаш восстановился — /health снова ok."
  fi
fi
