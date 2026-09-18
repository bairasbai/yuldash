#!/usr/bin/env bash
# Разовая настройка сервера под PWA (app.yulbash.ru) — одна команда вместо получаса руками.
# Повторный запуск безопасен: что уже сделано — пропускается, скрипт продолжает с места обрыва
# (ТСПУ рвёт SSH, DNS может ещё не доехать — обычные причины запустить второй раз).
#
# Что делает (по docs/deploy-pwa.md):
#   0. на ноуте: есть .env.production; SSH до сервера (0 мс ping = отвечает VPN, не сервер);
#      это ИМЕННО сервер Юлдаша (hostname msk-1-vm-3azu);
#   1. nginx: временный HTTP-конфиг + каталог /var/www/letsencrypt под проверку Let's Encrypt.
#      Боевой конфиг сразу ставить нельзя: в нём прописаны сертификаты, которых ещё нет,
#      `nginx -t` падает, и certbot с nginx-плагином падает вместе с ним;
#   2. бэкенд: CORS_ORIGINS += https://app.yulbash.ru; VAPID-ключи из .env.vapid-server
#      (только если на сервере их ещё нет); restart yuldash-api; /health. Резервная копия .env рядом;
#   3. DNS: app.yulbash.ru должен смотреть на сервер — иначе стоп с подсказкой, без certbot;
#   4. HTTPS: certbot certonly --webroot (без nginx-плагина). Продление — штатный таймер
#      certbot, после продления nginx перечитывается (deploy-hook записан в renewal-конфиг);
#   5. nginx: боевой конфиг из nginx.conf.example (SPA, кеш, security-заголовки), reload;
#   6. первая выкатка сайта — bash deploy.sh (сборка → заливка → проверка с сервера).
#
# Запуск: bash webapp/server-setup.sh   (из корня репо или из webapp/)
# Опционально: CERTBOT_EMAIL=почта bash webapp/server-setup.sh — письма Let's Encrypt о продлении.

set -euo pipefail

HOST="root@85.239.52.55"
SERVER_IP="85.239.52.55"
SERVER_HOSTNAME="msk-1-vm-3azu"            # docs/server.md: признак НАШЕГО сервера
DOMAIN="app.yulbash.ru"
API_ORIGIN="https://yulbash.ru"
KEY="$HOME/.ssh/id_ed25519"
# accept-new: первый раз ключ сервера запоминается, подмена потом — отказ (как в deploy.sh).
# BatchMode: никаких вопросов в терминале — скрипт либо проходит, либо честно падает.
SSH="ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 -o BatchMode=yes -i $KEY"
SCP="scp -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 -o BatchMode=yes -i $KEY"
SITE_AVAIL="/etc/nginx/sites-available/$DOMAIN"
SITE_ENABLED="/etc/nginx/sites-enabled/$DOMAIN"
ACME_ROOT="/var/www/letsencrypt"            # тот же путь — в nginx.conf.example
LIVE_DIR="/var/www/yuldash-webapp/dist"     # root из nginx.conf.example и deploy.sh
BACKEND_ENV="/opt/yuldash/.env"
CERT_PEM="/etc/letsencrypt/live/$DOMAIN/fullchain.pem"
CERTBOT_EMAIL="${CERTBOT_EMAIL:-}"
VAPID_TMP="/root/.yuldash-vapid.tmp"

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
# и повторная попытка после обрыва SSH получила бы пустой скрипт и «успех» на ровном месте.
ssh_script()  { $SSH "$HOST" "bash -s" < "$1"; }
ssh_capture() { local out="$1"; shift; $SSH "$HOST" "$@" > "$out"; }

echo "== 0/6 проверки на ноуте =="
[ -f .env.production ] || { echo "!!! Нет webapp/.env.production — скопируй .env.example и заполни (docs/deploy-pwa.md)."; exit 1; }
[ -f nginx.conf.example ] || { echo "!!! Нет webapp/nginx.conf.example."; exit 1; }
[ -f "$KEY" ] || { echo "!!! Нет SSH-ключа $KEY."; exit 1; }
if [ -f .env.vapid-server ]; then
  if grep -q '^VAPID_PRIVATE_KEY=.\+' .env.vapid-server; then
    echo "    VAPID: .env.vapid-server найден — уедет на сервер, если там ключей ещё нет"
  else
    echo "!!! В .env.vapid-server нет VAPID_PRIVATE_KEY — пересоздай: backend/tools/vapid_keys.py"; exit 1
  fi
else
  echo "    ⚠ .env.vapid-server нет — пуши в браузере останутся выключенными (backend/tools/vapid_keys.py)"
fi

if ! retry "SSH до сервера" ssh_capture "$WORK/hostname" "hostname"; then
  cat <<'HINT'
!!! До сервера не достучаться. Чаще всего это не сервер:
    • `ping 85.239.52.55` отвечает за 0 мс → отвечает VPN-клиент, не сервер. Выключи VPN и повтори.
    • Российский оператор режет SSH (ТСПУ) → смени сеть (мобильный интернет ↔ домашний), повтори.
    • Подробно: docs/server.md, раздел «Не достучаться до сервера».
HINT
  exit 1
fi
REMOTE_HOSTNAME="$(tr -d '\r\n' < "$WORK/hostname")"
if [ "$REMOTE_HOSTNAME" != "$SERVER_HOSTNAME" ]; then
  echo "!!! По адресу отвечает '$REMOTE_HOSTNAME', а сервер Юлдаша — '$SERVER_HOSTNAME'. Стоп: не тот сервер (docs/server.md)."
  exit 1
fi
echo "    сервер: $REMOTE_HOSTNAME ✓"

echo "== 1/6 nginx: каталоги и временный HTTP-конфиг под выпуск сертификата =="
cat > "$WORK/r1.sh" <<REMOTE
set -e
grep -q "sites-enabled" /etc/nginx/nginx.conf || { echo "!!! nginx без sites-enabled — конфиг класть некуда, посмотри /etc/nginx/nginx.conf"; exit 1; }
mkdir -p ${ACME_ROOT}/.well-known/acme-challenge ${LIVE_DIR}
chown -R www-data:www-data ${ACME_ROOT} 2>/dev/null || true
if [ -f ${CERT_PEM} ] && [ -f ${SITE_AVAIL} ] && grep -q "ssl_certificate" ${SITE_AVAIL}; then
  echo "    сертификат и боевой конфиг уже стоят — временный не нужен"
else
  cat > ${SITE_AVAIL} <<'CONF'
# ВРЕМЕННЫЙ конфиг app.yulbash.ru (server-setup.sh): только проверка Let's Encrypt по HTTP.
# После выпуска сертификата заменяется на боевой из webapp/nginx.conf.example.
server {
    listen 80;
    listen [::]:80;
    server_name app.yulbash.ru;
    location ^~ /.well-known/acme-challenge/ {
        root /var/www/letsencrypt;
        default_type "text/plain";
    }
    location / {
        return 404;
    }
}
CONF
  ln -sf ${SITE_AVAIL} ${SITE_ENABLED}
  nginx -t && systemctl reload nginx
  echo "    временный конфиг поставлен, nginx перечитан"
fi
REMOTE
retry "nginx bootstrap" ssh_script "$WORK/r1.sh"

echo "== 2/6 бэкенд: CORS + VAPID в ${BACKEND_ENV} =="
if [ -f .env.vapid-server ]; then
  retry "scp VAPID" $SCP .env.vapid-server "$HOST:$VAPID_TMP"
fi
cat > "$WORK/r2.sh" <<REMOTE
set -e
ENV=${BACKEND_ENV}
[ -f "\$ENV" ] || { echo "!!! Нет \$ENV — бэкенд стоит не там, где ожидалось"; exit 1; }
CHANGED=0
BACKUP="\$ENV.bak-\$(date +%Y%m%d-%H%M%S)"

# --- CORS: добавить origin сайта, не трогая остальное ---
if grep -q '^CORS_ORIGINS=' "\$ENV"; then
  CUR="\$(grep '^CORS_ORIGINS=' "\$ENV" | head -1 | cut -d= -f2- | tr -d "\r \"'")"
  case ",\$CUR," in
    *,https://${DOMAIN},*) echo "    CORS: https://${DOMAIN} уже есть ✓" ;;
    *)
      cp -a "\$ENV" "\$BACKUP"
      if [ -z "\$CUR" ] || [ "\$CUR" = "*" ]; then NEW="${API_ORIGIN},https://${DOMAIN}"; else NEW="\$CUR,https://${DOMAIN}"; fi
      sed -i "s|^CORS_ORIGINS=.*|CORS_ORIGINS=\$NEW|" "\$ENV"
      echo "    CORS: было '\$CUR' → стало '\$NEW'"; CHANGED=1 ;;
  esac
else
  cp -a "\$ENV" "\$BACKUP"
  printf '\nCORS_ORIGINS=%s,https://%s\n' "${API_ORIGIN}" "${DOMAIN}" >> "\$ENV"
  echo "    CORS: строки не было, добавлена"; CHANGED=1
fi

# --- VAPID: только если на сервере ключей ещё нет (чужую пару не перетираем) ---
if grep -q '^VAPID_PRIVATE_KEY=.\+' "\$ENV"; then
  echo "    VAPID: на сервере уже есть своя пара — не трогаю."
  echo "    ⚠ Проверь, что VITE_VAPID_PUBLIC_KEY в webapp/.env.production совпадает с серверным:"
  echo "      сервер: \$(grep '^VAPID_PUBLIC_KEY=' "\$ENV" | cut -d= -f2-)"
elif [ -f ${VAPID_TMP} ]; then
  [ -f "\$BACKUP" ] || cp -a "\$ENV" "\$BACKUP"
  sed -i '/^VAPID_PRIVATE_KEY=/d;/^VAPID_PUBLIC_KEY=/d;/^VAPID_SUBJECT=/d' "\$ENV"
  { printf '\n# Web Push (VAPID) — добавлено server-setup.sh %s\n' "\$(date +%F)"; tr -d '\r' < ${VAPID_TMP}; } >> "\$ENV"
  echo "    VAPID: пара записана (приватный ключ — только здесь)"; CHANGED=1
else
  echo "    VAPID: ключей нет ни на сервере, ни в .env.vapid-server — пуши в браузере пока выключены"
fi
rm -f ${VAPID_TMP}
chmod 600 "\$ENV" 2>/dev/null || true

if [ "\$CHANGED" = 1 ]; then
  echo "    резервная копия: \$BACKUP"
  systemctl restart yuldash-api
  sleep 3
fi
systemctl is-active yuldash-api >/dev/null || { echo "!!! yuldash-api не поднялся — journalctl -u yuldash-api -n 50"; exit 1; }
echo "    /health: \$(curl -s -m 10 http://127.0.0.1:8000/health)"
REMOTE
retry "backend env" ssh_script "$WORK/r2.sh"

echo "== 3/6 DNS: ${DOMAIN} → ${SERVER_IP}? =="
# Спрашиваем с сервера: ему же и выпускать сертификат; ноут за VPN/ТСПУ может видеть другое.
retry "DNS-проверка (на сервере)" ssh_capture "$WORK/dns" "getent ahostsv4 ${DOMAIN} | awk 'NR==1{print \$1}'; true"
RESOLVED="$(tr -d '\r\n' < "$WORK/dns")"
if [ "$RESOLVED" != "$SERVER_IP" ]; then
  cat <<HINT
!!! ${DOMAIN} пока не смотрит на сервер (сейчас: '${RESOLVED:-не резолвится}').
    У регистратора домена yulbash.ru добавь запись:   A   app   ${SERVER_IP}
    Подождать 5–30 минут и снова запустить:  bash webapp/server-setup.sh
    (шаги 1–2 уже сделаны и повторно не трогаются; без DNS сертификат выпустить нельзя).
HINT
  exit 2
fi
echo "    DNS ✓ (${RESOLVED})"

echo "== 4/6 HTTPS: сертификат Let's Encrypt =="
cat > "$WORK/r4.sh" <<REMOTE
set -e
if [ -f ${CERT_PEM} ]; then
  echo "    сертификат уже есть: \$(openssl x509 -in ${CERT_PEM} -noout -enddate 2>/dev/null || echo ok)"
  exit 0
fi
command -v certbot >/dev/null || { echo "    ставлю certbot..."; apt-get update -qq && apt-get install -y -qq certbot; }
ACCOUNT_OPT="--register-unsafely-without-email"
ls /etc/letsencrypt/accounts/*/directory/*/regr.json >/dev/null 2>&1 && ACCOUNT_OPT=""
[ -n "${CERTBOT_EMAIL}" ] && ACCOUNT_OPT="-m ${CERTBOT_EMAIL}"
certbot certonly --webroot -w ${ACME_ROOT} -d ${DOMAIN} -n --agree-tos --keep-until-expiring \
  \$ACCOUNT_OPT --deploy-hook "systemctl reload nginx"
[ -f ${CERT_PEM} ] || { echo "!!! certbot отработал, а ${CERT_PEM} нет"; exit 1; }
echo "    сертификат выпущен ✓ (продление — таймер certbot: systemctl list-timers | grep certbot)"
REMOTE
retry "certbot" ssh_script "$WORK/r4.sh"

echo "== 5/6 nginx: боевой конфиг =="
retry "scp nginx.conf" $SCP nginx.conf.example "$HOST:${SITE_AVAIL}.new"
cat > "$WORK/r5.sh" <<REMOTE
set -e
sed -i 's/\r\$//' ${SITE_AVAIL}.new
if [ -f ${SITE_AVAIL} ] && cmp -s ${SITE_AVAIL} ${SITE_AVAIL}.new; then
  rm -f ${SITE_AVAIL}.new; echo "    конфиг не изменился ✓"
else
  [ -f ${SITE_AVAIL} ] && cp -a ${SITE_AVAIL} ${SITE_AVAIL}.bak-\$(date +%Y%m%d-%H%M%S)
  mv ${SITE_AVAIL}.new ${SITE_AVAIL}
  ln -sf ${SITE_AVAIL} ${SITE_ENABLED}
  nginx -t && systemctl reload nginx
  echo "    боевой конфиг поставлен, nginx перечитан ✓"
fi
echo -n "    https://${DOMAIN} с сервера: "
curl -sk -o /dev/null -m 10 -w '%{http_code}\n' --resolve ${DOMAIN}:443:127.0.0.1 https://${DOMAIN}/ || echo "(нет ответа)"
REMOTE
retry "nginx switch" ssh_script "$WORK/r5.sh"

echo "== 6/6 первая выкатка сайта (deploy.sh) =="
bash ./deploy.sh
echo "SETUP_DONE — дальше каждая выкатка: bash webapp/deploy.sh"
