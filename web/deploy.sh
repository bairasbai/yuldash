#!/usr/bin/env bash
# Деплой лендинга Юлдаш на прод (yulbash.ru) — профессионально, без белого экрана.
#
# Почему так:
#  • HTML на проде кэшируется коротко (max-age=300, stale-while-revalidate) —
#    iPhone/Safari берёт страницу из кэша мгновенно и НЕ виснет на перепроверке
#    по LTE («второй раз не грузит»).
#  • Раз HTML кэшируется, старая версия HTML может ещё несколько минут ссылаться
#    на чанки предыдущей сборки. Поэтому при деплое мы НЕ стираем _next/static,
#    а НАКАПЛИВАЕМ: новые чанки + старые сосуществуют. Кэшированный HTML всегда
#    находит свои чанки → нет 404 → нет белого экрана.
#  • Старые чанки (не тронутые >2 дней) чистим — не растём бесконечно.
#  • ТСПУ роняет SSH → все шаги в retry-циклах.
#
# Запуск: bash web/deploy.sh   (из корня репо или из web/)

set -euo pipefail

HOST="root@85.239.52.55"
KEY="$HOME/.ssh/id_ed25519"
# accept-new (не no): при ПЕРВОМ подключении ключ сервера запоминается, при подмене later —
# отказ (защита от MITM). `=no` молча принимал бы любой подменённый host key. Как в deploy-backend.bat.
SSH="ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 -i $KEY"
SCP="scp -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 -i $KEY"
LIVE="/var/www/yuldash-landing"
TARBALL="/tmp/yuldash-out.tar.gz"

# перейти в web/ (скрипт может быть вызван из корня)
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

echo "== 1/4 сборка =="
npm run build

echo "== 2/4 упаковка =="
tar czf "$TARBALL" -C out .

echo "== 3/4 загрузка =="
retry "scp tarball" $SCP "$TARBALL" "$HOST:$TARBALL"

echo "== 4/4 деплой (overlay + prune) =="
retry "remote deploy" $SSH "$HOST" "bash -s" <<REMOTE
set -e
NEW=${LIVE}.new
rm -rf \$NEW && mkdir -p \$NEW
tar xzf ${TARBALL} -C \$NEW
# накопить чанки прошлой сборки (immutable, hashed — безопасно), не перетирая новые
if [ -d ${LIVE}/_next/static ]; then
  mkdir -p \$NEW/_next/static
  cp -rn ${LIVE}/_next/static/. \$NEW/_next/static/ || true
fi
# атомарная замена
rm -rf ${LIVE}.old
mv ${LIVE} ${LIVE}.old
mv \$NEW ${LIVE}
rm -rf ${LIVE}.old
# прочистить чанки старше 2 дней (окно кэша HTML — 5 мин, запас огромный)
find ${LIVE}/_next/static -type f -mtime +2 -delete 2>/dev/null || true
echo "DEPLOY_DONE, чанков: \$(ls ${LIVE}/_next/static/chunks/ | wc -l)"
REMOTE

echo "== проверка =="
retry "healthcheck" bash -c "curl -sf -o /dev/null -w 'landing: %{http_code}\n' https://yulbash.ru/"
echo "Готово."
