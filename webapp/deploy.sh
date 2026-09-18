#!/usr/bin/env bash
# Деплой PWA Юлдаш (app.yulbash.ru) — по образцу web/deploy.sh для лендинга.
#
# Что делает:
#   1. собирает dist/ с настройками из webapp/.env.production (API, бот, ключ карт, VAPID);
#   2. упаковывает и заливает на сервер (ТСПУ роняет SSH → все шаги в retry);
#   3. на сервере кладёт рядом с живой папкой, ДОКЛАДЫВАЕТ старые assets/ (хешированные,
#      их ещё может просить старый service worker), атомарно меняет папки, чистит assets
#      старше 2 дней, перечитывает nginx;
#   4. проверяет С СЕРВЕРА (curl по 127.0.0.1 с именем сайта): главная 200, sw.js без кэша,
#      manifest на месте. С ноута публичный домен часто не виден (ТСПУ/VPN, docs/lessons.md),
#      поэтому проверка с ноута — только справочно, деплой она не валит.
#
# Перед первым запуском — bash webapp/server-setup.sh (DNS, nginx, HTTPS, CORS, VAPID —
# одной командой; подробности в docs/deploy-pwa.md).
# Запуск: bash webapp/deploy.sh   (из корня репо или из webapp/)

set -euo pipefail

HOST="root@85.239.52.55"
KEY="$HOME/.ssh/id_ed25519"
SSH="ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 -o BatchMode=yes -i $KEY"
SCP="scp -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 -o BatchMode=yes -i $KEY"
LIVE="/var/www/yuldash-webapp/dist"       # root из nginx.conf.example
DOMAIN="app.yulbash.ru"
SITE_URL="https://$DOMAIN"
TARBALL="/tmp/yuldash-webapp.tar.gz"

cd "$(dirname "$0")"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

retry() { # retry <описание> <команда...>
  local desc="$1"; shift
  for i in 1 2 3 4 5; do
    echo ">>> $desc (попытка $i)"
    if "$@"; then return 0; fi
    echo "    неудача, пауза 5с..."; sleep 5
  done
  echo "!!! $desc — не удалось после 5 попыток"; return 1
}
# Удалённый скрипт — из ФАЙЛА, не из heredoc на stdin: heredoc вычитывается первой попыткой,
# и повтор после обрыва SSH получил бы пустой скрипт и ложный «успех».
ssh_script() { $SSH "$HOST" "bash -s" < "$1"; }

echo "== 0/5 настройки сборки =="
if [ ! -f .env.production ]; then
  echo "!!! Нет webapp/.env.production — скопируй .env.example и заполни (docs/deploy-pwa.md)."; exit 1
fi
# shellcheck disable=SC1091
set -a; . ./.env.production; set +a
: "${VITE_API_BASE:?VITE_API_BASE пуст — сайт не будет знать, где сервер}"
[ -n "${VITE_YANDEX_MAPS_JS_KEY:-}" ] || echo "    ⚠ VITE_YANDEX_MAPS_JS_KEY пуст — карта покажет заглушку «подключится с ключом»"
[ -n "${VITE_VAPID_PUBLIC_KEY:-}" ]   || echo "    ⚠ VITE_VAPID_PUBLIC_KEY пуст — пуши в браузере честно скажут «после настройки на сервере»"
echo "    API: $VITE_API_BASE"

echo "== 1/5 сборка =="
npm run build

echo "== 2/5 упаковка =="
tar czf "$TARBALL" -C dist .

echo "== 3/5 загрузка =="
retry "scp tarball" $SCP "$TARBALL" "$HOST:$TARBALL"

echo "== 4/5 деплой (overlay + prune + reload nginx) =="
cat > "$WORK/deploy-remote.sh" <<REMOTE
set -e
NEW=${LIVE}.new
rm -rf \$NEW && mkdir -p \$NEW
tar xzf ${TARBALL} -C \$NEW
# Старый service worker у людей ещё может запросить прежние хешированные файлы —
# докладываем их к новой сборке (cp -n: новые не перетираем; -p: сохраняем даты,
# иначе каждая выкатка «омолаживает» старые файлы и чистка по -mtime их никогда не тронет).
if [ -d ${LIVE}/assets ]; then
  mkdir -p \$NEW/assets
  cp -rpn ${LIVE}/assets/. \$NEW/assets/ || true
fi
mkdir -p "\$(dirname ${LIVE})"
rm -rf ${LIVE}.old
[ -d ${LIVE} ] && mv ${LIVE} ${LIVE}.old
mv \$NEW ${LIVE}
rm -rf ${LIVE}.old
find ${LIVE}/assets -type f -mtime +2 -delete 2>/dev/null || true
chown -R www-data:www-data "\$(dirname ${LIVE})" 2>/dev/null || true
nginx -t && systemctl reload nginx
echo "DEPLOY_DONE, файлов в assets: \$(ls ${LIVE}/assets | wc -l)"
REMOTE
retry "remote deploy" ssh_script "$WORK/deploy-remote.sh"

echo "== 5/5 проверка (с сервера по 127.0.0.1 — ноут за ТСПУ/VPN домен может не видеть) =="
cat > "$WORK/verify-remote.sh" <<REMOTE
set -e
R="--resolve ${DOMAIN}:443:127.0.0.1"
code() { curl -sk -o /dev/null -m 10 -w '%{http_code}' \$R "${SITE_URL}\$1"; }
INDEX=\$(code /);                    echo "index: \$INDEX"
MANI=\$(code /manifest.webmanifest); echo "manifest: \$MANI"
SW=\$(curl -skI -m 10 \$R "${SITE_URL}/sw.js" | tr -d '\r' | awk -F': ' 'tolower(\$1)=="cache-control"{print \$2}')
echo "sw.js cache-control: \${SW:-<нет заголовка>}"
[ "\$INDEX" = 200 ] || { echo "!!! главная не 200"; exit 1; }
[ "\$MANI" = 200 ]  || { echo "!!! manifest не 200"; exit 1; }
echo "\$SW" | grep -q "no-cache" && echo "sw.js: no-cache ✓" || { echo "!!! sw.js кэшируется — проверь location = /sw.js в nginx"; exit 1; }
REMOTE
retry "проверка с сервера" ssh_script "$WORK/verify-remote.sh"
echo -n "с ноута (справочно): "
curl -s -o /dev/null -m 10 -w 'index: %{http_code}\n' "$SITE_URL/" || echo "нет ответа (обычно ТСПУ/VPN, не поломка)"
echo "Готово."
