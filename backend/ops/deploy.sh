#!/usr/bin/env bash
#
# deploy.sh — безопасный деплой бэкенда Юлдаша с health-gate и авто-откатом.
#
# Что делает (по шагам):
#   1. git pull                — подтянуть новый код на сервере;
#   2. pip install (если менялись зависимости);
#   3. alembic upgrade head    — накатить миграции БД (идемпотентны, см. docs/deploy-migrations.md);
#   4. restart сервиса;
#   5. HEALTH-GATE             — дождаться, пока /health отвечает 200 и status=ok;
#      если не поднялся за таймаут → ОТКАТ кода на прошлый коммит + рестарт + АЛЕРТ.
#
# Запускать НА СЕРВЕРЕ, внутри каталога приложения. Пример (staging):
#   APP_DIR=/opt/yuldash-staging SERVICE=yuldash-api-staging \
#   HEALTH_URL=http://127.0.0.1:8100/health BRANCH=main bash deploy.sh
#
# Прод (осторожно, руками):
#   APP_DIR=/opt/yuldash SERVICE=yuldash-api HEALTH_URL=http://127.0.0.1:8000/health \
#   BRANCH=main bash deploy.sh
#
# Все параметры — через переменные окружения (значения по умолчанию — staging-безопасные,
# сознательно НЕ прод, чтобы случайный запуск не тронул боевой сервис).

set -Eeuo pipefail

# ── Настройки (переопределяются переменными окружения) ─────────────────────────
APP_DIR="${APP_DIR:-/opt/yuldash-staging}"                 # каталог с кодом (git-репозиторий)
SERVICE="${SERVICE:-yuldash-api-staging}"                  # имя systemd-сервиса
BRANCH="${BRANCH:-main}"                                   # какую ветку катим
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8100/health}"   # проба здоровья (см. app/routers/health.py)
VENV="${VENV:-$APP_DIR/.venv}"                             # виртуальное окружение python
RUN_USER="${RUN_USER:-yuldash}"                            # от кого крутится сервис (для chown)
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-60}"                     # сколько секунд ждём, пока поднимется
HEALTH_INTERVAL="${HEALTH_INTERVAL:-3}"                    # период опроса health, сек
ROLLBACK_DB="${ROLLBACK_DB:-0}"                            # 1 = откатывать и миграции (downgrade). По умолчанию НЕТ:
                                                           # миграции аддитивные/идемпотентные, авто-downgrade опасен.

log()  { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }
fail() { log "ОШИБКА: $*"; }

# ── Алерт в Telegram (переиспользуем инфраструктуру monitor.sh) ────────────────
# Токен бота — из $APP_DIR/.env (BOT_TOKEN/SMS_* ), chat_id — из $APP_DIR/.monitor.env.
# Оба файла НЕ в git. Нет настроек → просто молча пропускаем (деплой не падает из-за алерта).
alert() {
  local msg="$1"
  local token="" chat=""
  [ -f "$APP_DIR/.env" ]         && token="$(grep -E '^(BOT_TOKEN|TELEGRAM_BOT_TOKEN|SMS_BOT_TOKEN)=' "$APP_DIR/.env" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"'"'"'' )"
  [ -f "$APP_DIR/.monitor.env" ] && chat="$(grep -E '^ALERT_CHAT_ID=' "$APP_DIR/.monitor.env" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"'"'"'' )"
  if [ -n "$token" ] && [ -n "$chat" ]; then
    curl -fsS --max-time 10 "https://api.telegram.org/bot${token}/sendMessage" \
      -d chat_id="$chat" -d text="🚀 Деплой ${SERVICE}: ${msg}" >/dev/null 2>&1 || true
  else
    log "(алерт пропущен: нет BOT_TOKEN/.monitor.env — это нормально для staging)"
  fi
}

# ── Health-gate: ждём 200 + \"status\":\"ok\" ──────────────────────────────────────
health_ok() {
  local body
  body="$(curl -fsS --max-time 5 "$HEALTH_URL" 2>/dev/null)" || return 1
  echo "$body" | grep -q '"status"[[:space:]]*:[[:space:]]*"ok"'
}

wait_for_health() {
  local waited=0
  while [ "$waited" -lt "$HEALTH_TIMEOUT" ]; do
    if health_ok; then
      log "health OK ($HEALTH_URL)"
      return 0
    fi
    sleep "$HEALTH_INTERVAL"
    waited=$((waited + HEALTH_INTERVAL))
    log "жду health… ${waited}/${HEALTH_TIMEOUT}с"
  done
  return 1
}

# ── Начало ─────────────────────────────────────────────────────────────────────
log "деплой ${SERVICE} из ветки ${BRANCH} в ${APP_DIR}"
cd "$APP_DIR"

# Запомнить, куда откатываться (текущий коммит + текущая alembic-ревизия) ДО изменений.
PREV_SHA="$(git rev-parse HEAD)"
PREV_REV="$(sudo -u "$RUN_USER" "$VENV/bin/alembic" current 2>/dev/null | awk '{print $1}' | head -1 || true)"
log "текущий коммит: ${PREV_SHA:0:12}  alembic: ${PREV_REV:-нет}"

# 1) Код
log "git fetch + reset на origin/${BRANCH}"
git fetch --quiet origin "$BRANCH"
git reset --hard "origin/${BRANCH}"
chown -R "$RUN_USER:$RUN_USER" "$APP_DIR" 2>/dev/null || true
# Файлы с секретами читает ТОЛЬКО владелец (аудит 2026-08-08, волна 130). `chown` ставит
# хозяина, но не права: если .env создали обычным способом, он получается 644 — то есть
# любой пользователь сервера (и любой процесс от его имени) читает ключи ЮKassa, Firebase,
# Telegram и JWT-секрет. Тот же довод, что и с бэкапом: секреты не должны лежать открыто.
for _secret in "$APP_DIR/.env" "$APP_DIR/.backup.env" "$APP_DIR/.backup-s3.env"; do
  [ -f "$_secret" ] && chmod 600 "$_secret" 2>/dev/null || true
done

# 2) Зависимости (тихо; если requirements не менялись — pip быстроно-оп)
log "pip install -r requirements.txt"
sudo -u "$RUN_USER" "$VENV/bin/pip" install -q -r requirements.txt

# 3) Миграции БД
log "alembic upgrade head"
sudo -u "$RUN_USER" "$VENV/bin/alembic" upgrade head

# 4) Рестарт
log "systemctl restart ${SERVICE}"
systemctl restart "$SERVICE"

# 5) Health-gate
if wait_for_health; then
  log "✅ деплой успешен (${PREV_SHA:0:12} → $(git rev-parse --short HEAD))"
  alert "успех, $(git rev-parse --short HEAD)"
  exit 0
fi

# ── ОТКАТ ──────────────────────────────────────────────────────────────────────
fail "health не поднялся за ${HEALTH_TIMEOUT}с — откатываюсь на ${PREV_SHA:0:12}"
alert "❌ health упал, откат на ${PREV_SHA:0:12}"

git reset --hard "$PREV_SHA"
chown -R "$RUN_USER:$RUN_USER" "$APP_DIR" 2>/dev/null || true
sudo -u "$RUN_USER" "$VENV/bin/pip" install -q -r requirements.txt || true

# Откат миграций — только если явно попросили (ROLLBACK_DB=1) и знаем прошлую ревизию.
if [ "$ROLLBACK_DB" = "1" ] && [ -n "$PREV_REV" ]; then
  log "alembic downgrade → ${PREV_REV}"
  sudo -u "$RUN_USER" "$VENV/bin/alembic" downgrade "$PREV_REV" || fail "downgrade не удался — проверь БД руками"
else
  log "миграции НЕ откатывались (ROLLBACK_DB!=1). Они идемпотентны/аддитивны — обычно безопасны."
fi

systemctl restart "$SERVICE"

if wait_for_health; then
  log "↩️  откат успешен, сервис снова здоров на ${PREV_SHA:0:12}"
  alert "↩️ откат успешен, сервис здоров"
  exit 1
fi

fail "сервис НЕ поднялся даже после отката — нужна ручная проверка (journalctl -u ${SERVICE})"
alert "🔥 КРИТИЧНО: сервис не поднялся после отката, нужна ручная проверка"
exit 2
