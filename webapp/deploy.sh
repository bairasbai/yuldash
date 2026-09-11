#!/usr/bin/env bash
# Деплой PWA Юлдаш (app.yulbash.ru) — по образцу web/deploy.sh для лендинга.
#
# Что делает:
#   1. собирает dist/ с настройками из webapp/.env.production (API, бот, ключ карт, VAPID);
#   2. упаковывает и заливает на сервер (ТСПУ роняет SSH → все шаги в retry);
#   3. на сервере кладёт рядом с живой папкой, ДОКЛАДЫВАЕТ старые assets/ (хешированные,
#      их ещё может просить старый service worker), атомарно меняет папки, чистит assets
#      старше 2 дней, перечитывает nginx;
#   4. проверяет: главная 200, sw.js без кэша, manifest на месте.
#
# Перед первым запуском — docs/deploy-pwa.md (DNS, nginx, HTTPS, CORS на бэкенде).
# Запуск: bash webapp/deploy.sh   (из корня репо или из webapp/)

set -euo pipefail

HOST="root@85.239.52.55"
KEY="$HOME/.ssh/id_ed25519"
SSH="ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 -i $KEY"
SCP="scp -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 -i $KEY"
LIVE="/var/www/yuldash-webapp/dist"       # root из nginx.conf.example
SITE_URL="https://app.yulbash.ru"
TARBALL="/tmp/yuldash-webapp.tar.gz"

cd "$(dirname "$0")"

retry() { # retry <описание> <команда...>
  local desc="$1"; shift
  for i in 1 2 3 4 5; do
    echo ">>> $desc (попытка $i)"
    if "$@"; then return 0; fi
    echo "    неудача, пауза 5с..."; sleep 5
  done
  echo "!!! $desc — не удалось после 5 попыток"; return 1
}

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
retry "remote deploy" $SSH "$HOST" "bash -s" <<REMOTE
set -e
NEW=${LIVE}.new
rm -rf \$NEW && mkdir -p \$NEW
tar xzf ${TARBALL} -C \$NEW
# Старый service worker у людей ещё может запросить прежние хешированные файлы —
# докладываем их к новой сборке (cp -n: новые не перетираем).
if [ -d ${LIVE}/assets ]; then
  mkdir -p \$NEW/assets
  cp -rn ${LIVE}/assets/. \$NEW/assets/ || true
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

echo "== 5/5 проверка =="
retry "главная" bash -c "curl -sf -o /dev/null -w 'index: %{http_code}\n' $SITE_URL/"
retry "manifest" bash -c "curl -sf -o /dev/null -w 'manifest: %{http_code}\n' $SITE_URL/manifest.webmanifest"
echo -n "sw.js cache-control: "
curl -sI "$SITE_URL/sw.js" | tr -d '\r' | awk -F': ' 'tolower($1)=="cache-control"{print $2}' | grep -q "no-cache" \
  && echo "no-cache ✓" || echo "!!! sw.js кэшируется — проверь location = /sw.js в nginx"
echo "Готово."
