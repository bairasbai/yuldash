"""Прод-middleware Юлдаша: лимит запросов, security-заголовки, логи, единый
обработчик ошибок. Подключается в `main.create_app()`. Всё аддитивно — формат
ответов существующих эндпоинтов не меняется (ошибки остаются `{"detail": ...}`).

Лимитер — in-memory (скользящее окно на IP). Для одного-двух воркеров на MVP
этого достаточно. На несколько воркеров/серверов позже — Redis (см. system-design.md).
"""
import math
import time
from collections import defaultdict, deque

from fastapi import Request
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware

from .config import settings
from .logs import log

_WINDOW_SEC = 60  # окно счёта запросов (согласовано с *_per_min в config)

# После сбоя не создаём Redis-клиент на каждом HTTP-запросе, но и не остаёмся навсегда
# на локальном счётчике. Пять секунд достаточно, чтобы пережить короткий обрыв без шторма
# переподключений; следующий запрос после паузы снова попробует общий бюджет.
_REDIS_RETRY_SEC = 5

# INCR и срок жизни обязаны быть одной операцией. Два отдельных await оставляли бессрочный
# ключ, если ответ терялся между INCR и EXPIRE. ttl<0 чинит и уже существующие такие ключи.
_REDIS_WINDOW_LUA = """
local n = redis.call('INCR', KEYS[1])
local ttl = redis.call('TTL', KEYS[1])
if ttl < 0 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
    ttl = tonumber(ARGV[1])
end
return {n, ttl}
"""

# Префиксы, где лимит строже (перебор кодов, спам SOS, флуд админа в Telegram). Совпадение и с /api/v1.
# /callback, /donate, /boost/create шлют уведомление админу → без строгого лимита их можно заспамить.
# /waitlist — публичный без auth (ранний доступ, §11) → строгий бюджет против спама номеров.
_STRICT_PREFIXES = (
    "/auth", "/callback", "/donate", "/support/donate", "/boost/create", "/waitlist",
    "/api/v1/auth", "/api/v1/callback", "/api/v1/donate", "/api/v1/support/donate",
    "/api/v1/boost/create", "/api/v1/waitlist",
)

# SOS — СВОЙ бюджет, отдельно от /auth (аудит 2026-08-07).
#
# Раньше «красная кнопка» делила с входом один ключ и один бюджет на IP. В деревне это не
# теория: один вышкой раздаваемый интернет, общий Wi-Fi в кафе или NAT оператора — и десяток
# соседей выглядят для сервера одним адресом. Несколько попыток входа выбирали общий лимит,
# и следующий запрос SOS получал 429 «слишком много запросов». Кнопка, которая обязана
# сработать всегда, отказывала из-за чужих логинов.
#
# Спам SOS всё равно ограничиваем — но своим счётчиком, куда посторонний трафик не попадает.
# Порог заметно выше: человек в беде жмёт кнопку несколько раз подряд, и это нормально.
#
# Волна 179: тогда закрыли только половину. SOS отделили от `/auth`, но ОБЩИЙ бюджет он
# продолжал делить со всеми. Проба: сосед по сети оператора обычной работой (открыл ленту
# уведомлений три десятка раз) выбирает общий лимит — и «красная кнопка» отвечает 429.
# Логины тут ни при чём, хватает любого трафика с того же адреса.
#
# Поэтому у SOS теперь бюджет ВМЕСТО общего, а не в дополнение к нему. Свой счётчик
# остаётся: он и защищает от спама, просто чужой трафик в него не попадает.
_SOS_PREFIXES = ("/sos", "/api/v1/sos")

# Оценка цены — САМЫЙ дорогой для нас запрос: каждый вызов может уйти в платные Yandex Routing
# и Weather. Кэш там по координатам, поэтому подобранные точки его обходят: один клиент с одного
# IP превращался в усилитель расхода платного API (аудит 2026-08-03). Бюджет отдельный от auth:
# у него другая природа (не перебор кодов, а деньги за внешний вызов) и другой нормальный объём —
# человек тыкает точки на карте десятки раз за сессию, но не сотни.
_ESTIMATE_PREFIXES = (
    "/instant/estimate", "/courier/estimate",
    "/api/v1/instant/estimate", "/api/v1/courier/estimate",
)

# Приём телеметрии пишет строку в базу без входа. Такая ручка не одна: так же устроен
# /waitlist (номер с лендинга). Обе под строгим лимитом по IP — см. списки префиксов выше.
# Общего бюджета (300/мин) тут мало: это 430 тысяч строк в сутки с одного адреса, и на нашем
# маленьком сервере такой «аналитикой» забивают диск за неделю (аудит 2026-08-08). Настоящий
# клиент шлёт единицы событий на действие человека, поэтому свой бюджет ничего не ломает,
# а бессмысленный поток обрубает.
_EVENTS_PREFIXES = ("/events", "/api/v1/events")

# Освобождены от ЖЁСТКОГО лимита: пробы мониторинга (их долбит uptime-чек и деплой-гейт)
# и вебхуки внешних сервисов (Telegram/ЮKassa) — у них своя защита (секрет/подпись), а объём
# легитимного трафика может кратно превышать пользовательский. Проверяется ПЕРЕД
# _STRICT_PREFIXES. Совпадение и с /api/v1.
#
# Путь вебхука Telegram — именно "/telegram/webhook" (см. routers/auth.py). Здесь три месяца
# стояло "/auth/telegram/webhook": такого маршрута в приложении нет, поэтому исключение не
# срабатывало ни разу, и апдейты бота считались как обычный трафик пользователя. Пока бот тихий,
# это незаметно; в час пик (или когда кто-то насыпет боту сообщений с одного адреса) Telegram
# начал бы получать 429 и ретраить — вход через бота встал бы у всех, а причина выглядела бы
# как «Telegram сломался» (аудит 2026-08-08).
_EXEMPT_PREFIXES = (
    "/health", "/version",
    "/telegram/webhook", "/payments/yookassa/webhook",
    "/api/v1/health", "/api/v1/version",
    "/api/v1/telegram/webhook", "/api/v1/payments/yookassa/webhook",
)


def _client_ip(request: Request) -> str:
    # Прод за nginx: реальный IP — в X-Real-IP (nginx ставит $remote_addr, ПЕРЕЗАПИСЫВАЯ
    # любой клиентский заголовок). Приложение слушает 127.0.0.1 → видит только трафик nginx,
    # поэтому X-Real-IP доверенный. X-Forwarded-For клиент может подделать (свежий IP на запрос
    # → обход IP-rate-limit), поэтому НЕ берём его как первичный, только как фоллбэк для dev.
    real = request.headers.get("x-real-ip")
    if real:
        return real.strip()
    xff = request.headers.get("x-forwarded-for")
    if xff:
        return xff.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


class RateLimitMiddleware(BaseHTTPMiddleware):
    """Лимит запросов на IP, окно 60с. Три бюджета: общий, строгий (auth/sos) и оценка цены
    (`/instant/estimate`, `/courier/estimate` — за ними платный внешний API).

    Пробы мониторинга (`/health`, `/version`) и вебхуки (Telegram/ЮKassa) освобождены
    от жёсткого лимита (`_EXEMPT_PREFIXES`) — у них своя защита и высокий легитимный поток.

    Если задан `REDIS_URL` — счётчики в Redis (общие на все воркеры/серверы).
    Иначе/при сбое Redis — in-memory скользящее окно (на воркер). Сбой Redis НЕ
    роняет запрос: тихо падаем в in-memory. При отбое — 429 с телом и `Retry-After`."""

    def __init__(self, app):
        super().__init__(app)
        self._hits: dict[str, deque] = defaultdict(deque)
        self._hits_strict: dict[str, deque] = defaultdict(deque)
        self._hits_sos: dict[str, deque] = defaultdict(deque)
        self._hits_estimate: dict[str, deque] = defaultdict(deque)
        self._hits_events: dict[str, deque] = defaultdict(deque)
        self._redis = None
        self._redis_next_retry_at = 0.0

    def _get_redis(self):
        if self._redis is not None:
            return self._redis
        if not settings.redis_url or time.monotonic() < self._redis_next_retry_at:
            return None
        try:
            import redis.asyncio as aioredis
            self._redis = aioredis.from_url(
                settings.redis_url, encoding="utf-8", decode_responses=True,
            )
        except Exception as e:  # noqa: BLE001
            log.warning(f"[RATELIMIT] redis init failed, fallback in-memory: {e}")
            self._redis = None
            self._redis_next_retry_at = time.monotonic() + _REDIS_RETRY_SEC
        return self._redis

    def _over_mem(self, store: dict[str, deque], key: str, limit: int, now: float) -> tuple[bool, int]:
        """Скользящее окно. Возвращает (превышен?, сек до освобождения слота)."""
        dq = store[key]
        edge = now - _WINDOW_SEC
        while dq and dq[0] < edge:
            dq.popleft()
        if len(dq) >= limit:
            retry = max(1, math.ceil(dq[0] + _WINDOW_SEC - now))
            return True, retry
        dq.append(now)
        return False, 0

    async def _over_redis(self, client, key: str, limit: int) -> tuple[bool, int]:
        """Фиксированное окно 60с в Redis: атомарные INCR + проверка/установка TTL.
        Возвращает (превышен?, сек до сброса окна = TTL ключа)."""
        result = await client.eval(_REDIS_WINDOW_LUA, 1, key, _WINDOW_SEC)
        n, ttl = int(result[0]), int(result[1])
        if n > limit:
            return True, (ttl if ttl and ttl > 0 else _WINDOW_SEC)
        return False, 0

    async def dispatch(self, request: Request, call_next):
        path = request.url.path
        # Выключен глобально ИЛИ путь освобождён (мониторинг/вебхуки) → без лимита.
        if not settings.rate_limit_enabled or path.startswith(_EXEMPT_PREFIXES):
            return await call_next(request)
        ip = _client_ip(request)
        strict = path.startswith(_STRICT_PREFIXES)
        sos = path.startswith(_SOS_PREFIXES)
        estimate = path.startswith(_ESTIMATE_PREFIXES)
        events = path.startswith(_EVENTS_PREFIXES)
        client = self._get_redis()
        over, retry_after = False, 0
        if client is not None:
            try:
                if strict:
                    over, retry_after = await self._over_redis(client, f"rl:s:{ip}", settings.rate_limit_auth_per_min)
                if not over and sos:
                    over, retry_after = await self._over_redis(client, f"rl:sos:{ip}",
                                                               settings.rate_limit_sos_per_min)
                    # Свой бюджет ВМЕСТО общего: чужой трафик с того же адреса не должен
                    # закрывать красную кнопку (волна 179).
                    return await self._ответ(over, retry_after, request, call_next)
                if not over and estimate:
                    over, retry_after = await self._over_redis(client, f"rl:e:{ip}",
                                                               settings.rate_limit_estimate_per_min)
                if not over and events:
                    over, retry_after = await self._over_redis(client, f"rl:ev:{ip}",
                                                               settings.rate_limit_events_per_min)
                if not over:
                    over, retry_after = await self._over_redis(client, f"rl:g:{ip}", settings.rate_limit_per_min)
            except Exception as e:  # noqa: BLE001 — Redis недоступен → in-memory
                log.warning(f"[RATELIMIT] redis error, fallback in-memory: {e}")
                failed_client = client
                self._redis = None
                self._redis_next_retry_at = time.monotonic() + _REDIS_RETRY_SEC
                client = None
                # Не копим connection pools при долгом outage. Ошибка закрытия вторична:
                # запрос уже безопасно продолжит работу на локальном лимите.
                try:
                    close = getattr(failed_client, "aclose", None)
                    if close is not None:
                        await close()
                except Exception:  # noqa: BLE001
                    pass
        if client is None:
            now = time.monotonic()
            if strict:
                over, retry_after = self._over_mem(self._hits_strict, ip, settings.rate_limit_auth_per_min, now)
            if not over and sos:
                over, retry_after = self._over_mem(self._hits_sos, ip,
                                                   settings.rate_limit_sos_per_min, now)
                return await self._ответ(over, retry_after, request, call_next)
            if not over and estimate:
                over, retry_after = self._over_mem(self._hits_estimate, ip,
                                                   settings.rate_limit_estimate_per_min, now)
            if not over and events:
                over, retry_after = self._over_mem(self._hits_events, ip,
                                                   settings.rate_limit_events_per_min, now)
            if not over:
                over, retry_after = self._over_mem(self._hits, ip, settings.rate_limit_per_min, now)
        return await self._ответ(over, retry_after, request, call_next)

    async def _ответ(self, over: bool, retry_after: int, request: Request, call_next):
        """Пропустить запрос или вернуть 429 — на двух языках.

        Раньше `detail` был русской строкой. Приложение, увидев одноязычный отказ,
        показывает башкироязычному общую заглушку по коду (волна 177) — то есть человек
        в момент, когда что-то не работает, читает не наш текст. Здесь это особенно обидно:
        429 приходит там, где ничего не сломалось, и человеку важно понять, что делать.
        """
        if not over:
            return await call_next(request)
        retry_after = retry_after or _WINDOW_SEC
        return JSONResponse(
            {"detail": {"ru": "Слишком много запросов. Подожди немного.",
                        "ba": "Артыҡ күп һорау. Бер аҙ көт."},
             "retry_after": retry_after},
            status_code=429,
            headers={"Retry-After": str(retry_after)},
        )


# ----------------------------- Лимит НА ПОЛЬЗОВАТЕЛЯ -----------------------------
# Лимитер выше считает по IP, но IP — расходник: прокси меняет его на каждый запрос, и один
# аккаунт спокойно качает нам счёт за платный Yandex API. Аккаунт сменить дороже — поэтому
# у дорогих ручек есть и второй, персональный бюджет. Хранилище in-memory, как и у IP-лимитера
# (на воркер): для MVP достаточно, точность здесь не нужна — нужен потолок.
_user_hits: dict[str, deque] = defaultdict(deque)


def user_over_limit(bucket: str, user_id: int, limit: int, window_sec: int = _WINDOW_SEC) -> bool:
    """Превышен ли персональный бюджет `bucket` у пользователя. Лимитер выключен глобально
    (RATE_LIMIT_ENABLED=false) → всегда False: тесты и dev не должны упираться в потолок."""
    if not settings.rate_limit_enabled or limit <= 0:
        return False
    key = f"{bucket}:{user_id}"
    dq = _user_hits[key]
    now = time.monotonic()
    edge = now - window_sec
    while dq and dq[0] < edge:
        dq.popleft()
    if len(dq) >= limit:
        return True
    dq.append(now)
    return False


class SecurityHeadersMiddleware(BaseHTTPMiddleware):
    """Базовые защитные заголовки. API не отдаёт HTML, но заголовки дёшевы и полезны."""

    async def dispatch(self, request: Request, call_next):
        resp = await call_next(request)
        resp.headers.setdefault("X-Content-Type-Options", "nosniff")
        resp.headers.setdefault("X-Frame-Options", "DENY")
        resp.headers.setdefault("Referrer-Policy", "no-referrer")
        # HSTS (только прод): браузер запоминает, что к нам ходят ТОЛЬКО по https, и второй раз
        # уже не отправит первый запрос открытым текстом. Для нас это не абстракция: ссылку
        # слежения близкий получает сообщением, а в адресе этой ссылки лежит ключ доступа
        # к живым координатам человека — один http-переход по ней означал бы ключ в открытом
        # виде (аудит 2026-08-12, волна 33). В dev не ставим: локально ходят по http, и браузер
        # заблокировал бы себе доступ к localhost на год.
        if settings.is_prod:
            resp.headers.setdefault(
                "Strict-Transport-Security", "max-age=31536000; includeSubDomains"
            )
        return resp


class AccessLogMiddleware(BaseHTTPMiddleware):
    """Лёгкий лог доступа: метод, путь, статус, длительность. Без тел и query
    (чтобы не утекли токены/телефоны в логи, 152-ФЗ)."""

    async def dispatch(self, request: Request, call_next):
        start = time.monotonic()
        resp = await call_next(request)
        ms = (time.monotonic() - start) * 1000.0
        path = request.url.path
        # Live-ссылка близкому (/t/{token}, B7c) — capability-URL: токен в пути = секрет,
        # в лог не пишем (тот же принцип, что «без query», 152-ФЗ).
        if path.startswith("/t/") or path.startswith("/api/v1/t/"):
            path = path[: path.index("/t/") + 3] + "***"
        log.info(f"[REQ] {request.method} {path} -> {resp.status_code} {ms:.0f}ms")
        # Явные серверные ошибки (500/503 и т.п.) считаем для алерта о всплеске.
        # /health* исключаем: 503 от readiness-пробы — ожидаемый сигнал (его отслеживает monitor.sh).
        if resp.status_code >= 500 and not path.startswith("/health"):
            from .services import record_server_error
            record_server_error(path)
        return resp


async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    """Любая необработанная ошибка → 500 без утечки стека наружу (стек — в лог)."""
    path = request.url.path
    if path.startswith("/t/") or path.startswith("/api/v1/t/"):
        path = path[: path.index("/t/") + 3] + "***"   # токен live-ссылки — секрет (B7c)
    # Текст ошибки и стек — через скруб: в `IntegrityError` SQLAlchemy кладёт параметры
    # запроса, то есть телефон и имя человека. `exc_info` не используем намеренно — он
    # печатает исходный текст мимо очистки (аудит 2026-08-08, волна 14).
    from .observability import scrub_exc
    log.error("[ERR] %s %s: %s\n%s", request.method, path, type(exc).__name__, scrub_exc(exc))
    # Обработчик bare Exception мог бы «съесть» авто-захват Sentry — шлём явно.
    from .observability import capture
    capture(exc)
    # Реальный краш (проброшенное исключение) до AccessLogMiddleware не доходит —
    # считаем его здесь, у источника 500.
    # ВАЖНО: передаём уже замаскированный `path`, а не `request.url.path` — иначе
    # секретный токен live-ссылки /t/{token} уезжает в Telegram админа (алерт о всплеске
    # 5xx подставляет путь в текст). Маскировка выше была бы бессмысленной.
    from .services import record_server_error
    record_server_error(path)
    return JSONResponse({"detail": "Внутренняя ошибка сервера"}, status_code=500)
