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

# Префиксы, где лимит строже (перебор кодов, спам SOS). Совпадение и с /api/v1.
_STRICT_PREFIXES = ("/auth", "/sos", "/api/v1/auth", "/api/v1/sos")


def _client_ip(request: Request) -> str:
    # За nginx реальный IP — в X-Forwarded-For (первый). Иначе peer.
    xff = request.headers.get("x-forwarded-for")
    if xff:
        return xff.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


class RateLimitMiddleware(BaseHTTPMiddleware):
    """Скользящее окно 60с на IP. Два бюджета: общий и строгий (auth/sos)."""

    def __init__(self, app):
        super().__init__(app)
        self._hits: dict[str, deque] = defaultdict(deque)
        self._hits_strict: dict[str, deque] = defaultdict(deque)

    def _over(self, store: dict[str, deque], key: str, limit: int, now: float) -> bool:
        dq = store[key]
        edge = now - 60.0
        while dq and dq[0] < edge:
            dq.popleft()
        if len(dq) >= limit:
            return True
        dq.append(now)
        return False

    async def dispatch(self, request: Request, call_next):
        if not settings.rate_limit_enabled:
            return await call_next(request)
        ip = _client_ip(request)
        now = time.monotonic()
        path = request.url.path
        strict = path.startswith(_STRICT_PREFIXES)
        if strict and self._over(self._hits_strict, ip, settings.rate_limit_auth_per_min, now):
            return JSONResponse({"detail": "Слишком много запросов. Подожди немного."}, status_code=429)
        if self._over(self._hits, ip, settings.rate_limit_per_min, now):
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
