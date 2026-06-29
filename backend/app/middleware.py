"""Прод-middleware Юлдаша: лимит запросов, security-заголовки, логи, единый
обработчик ошибок. Подключается в `main.create_app()`. Всё аддитивно — формат
ответов существующих эндпоинтов не меняется (ошибки остаются `{"detail": ...}`).

Лимитер — in-memory (скользящее окно на IP). Для одного-двух воркеров на MVP
этого достаточно. На несколько воркеров/серверов позже — Redis (см. system-design.md).
"""
import time
from collections import defaultdict, deque

from fastapi import Request
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware

from .config import settings

# Префиксы, где лимит строже (перебор кодов, спам SOS, флуд админа в Telegram). Совпадение и с /api/v1.
# /callback, /donate, /boost/create шлют уведомление админу → без строгого лимита их можно заспамить.
_STRICT_PREFIXES = (
    "/auth", "/sos", "/callback", "/donate", "/boost/create",
    "/api/v1/auth", "/api/v1/sos", "/api/v1/callback", "/api/v1/donate", "/api/v1/boost/create",
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
    """Лимит запросов на IP, окно 60с. Два бюджета: общий и строгий (auth/sos).

    Если задан `REDIS_URL` — счётчики в Redis (общие на все воркеры/серверы).
    Иначе/при сбое Redis — in-memory скользящее окно (на воркер). Сбой Redis НЕ
    роняет запрос: тихо падаем в in-memory."""

    def __init__(self, app):
        super().__init__(app)
        self._hits: dict[str, deque] = defaultdict(deque)
        self._hits_strict: dict[str, deque] = defaultdict(deque)
        self._redis = None
        self._redis_tried = False

    def _get_redis(self):
        if self._redis_tried:
            return self._redis
        self._redis_tried = True
        if settings.redis_url:
            try:
                import redis.asyncio as aioredis
                self._redis = aioredis.from_url(settings.redis_url, encoding="utf-8", decode_responses=True)
            except Exception as e:  # noqa: BLE001
                print(f"[RATELIMIT] redis init failed, fallback in-memory: {e}")
                self._redis = None
        return self._redis

    def _over_mem(self, store: dict[str, deque], key: str, limit: int, now: float) -> bool:
        dq = store[key]
        edge = now - 60.0
        while dq and dq[0] < edge:
            dq.popleft()
        if len(dq) >= limit:
            return True
        dq.append(now)
        return False

    async def _over_redis(self, client, key: str, limit: int) -> bool:
        """Фиксированное окно 60с в Redis: INCR + EXPIRE на первом хите."""
        n = await client.incr(key)
        if n == 1:
            await client.expire(key, 60)
        return n > limit

    async def dispatch(self, request: Request, call_next):
        if not settings.rate_limit_enabled:
            return await call_next(request)
        ip = _client_ip(request)
        path = request.url.path
        strict = path.startswith(_STRICT_PREFIXES)
        client = self._get_redis()
        over = False
        if client is not None:
            try:
                if strict:
                    over = await self._over_redis(client, f"rl:s:{ip}", settings.rate_limit_auth_per_min)
                if not over:
                    over = await self._over_redis(client, f"rl:g:{ip}", settings.rate_limit_per_min)
            except Exception as e:  # noqa: BLE001 — Redis недоступен → in-memory
                print(f"[RATELIMIT] redis error, fallback in-memory: {e}")
                self._redis = None
                client = None
        if client is None:
            now = time.monotonic()
            if strict and self._over_mem(self._hits_strict, ip, settings.rate_limit_auth_per_min, now):
                over = True
            elif self._over_mem(self._hits, ip, settings.rate_limit_per_min, now):
                over = True
        if over:
            return JSONResponse({"detail": "Слишком много запросов. Подожди немного."}, status_code=429)
        return await call_next(request)


class SecurityHeadersMiddleware(BaseHTTPMiddleware):
    """Базовые защитные заголовки. API не отдаёт HTML, но заголовки дёшевы и полезны."""

    async def dispatch(self, request: Request, call_next):
        resp = await call_next(request)
        resp.headers.setdefault("X-Content-Type-Options", "nosniff")
        resp.headers.setdefault("X-Frame-Options", "DENY")
        resp.headers.setdefault("Referrer-Policy", "no-referrer")
        return resp


class AccessLogMiddleware(BaseHTTPMiddleware):
    """Лёгкий лог доступа: метод, путь, статус, длительность. Без тел и query
    (чтобы не утекли токены/телефоны в логи, 152-ФЗ)."""

    async def dispatch(self, request: Request, call_next):
        start = time.monotonic()
        resp = await call_next(request)
        ms = (time.monotonic() - start) * 1000.0
        print(f"[REQ] {request.method} {request.url.path} -> {resp.status_code} {ms:.0f}ms")
        return resp


async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    """Любая необработанная ошибка → 500 без утечки стека наружу (стек — в лог)."""
    print(f"[ERR] {request.method} {request.url.path}: {type(exc).__name__}: {exc}")
    return JSONResponse({"detail": "Внутренняя ошибка сервера"}, status_code=500)
