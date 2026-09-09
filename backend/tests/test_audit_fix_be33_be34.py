"""BE33/34: Redis limiter reconnects and gives every counter a finite TTL."""
import asyncio

import pytest
from starlette.requests import Request
from starlette.responses import PlainTextResponse

from app import middleware as mw
from app.config import settings


def _request(path: str = "/rides", ip: str = "203.0.113.233") -> Request:
    return Request({
        "type": "http",
        "http_version": "1.1",
        "method": "GET",
        "scheme": "http",
        "path": path,
        "raw_path": path.encode(),
        "query_string": b"",
        "headers": [(b"x-real-ip", ip.encode())],
        "client": (ip, 12345),
        "server": ("testserver", 80),
    })


async def _ok(_request):
    return PlainTextResponse("ok")


class _SharedRedisService:
    def __init__(self):
        self.available = False
        self.values: dict[str, int] = {}
        self.ttls: dict[str, int] = {}
        self.factory_calls = 0
        self.operation_calls = 0

    def client(self):
        self.factory_calls += 1
        return _FakeRedis(self)


class _FakeRedis:
    """Понимает и старые отдельные команды, и новый атомарный eval."""
    def __init__(self, service: _SharedRedisService):
        self.service = service

    def _ready(self):
        self.service.operation_calls += 1
        if not self.service.available:
            raise OSError("redis temporarily unavailable")

    async def incr(self, key):
        self._ready()
        self.service.values[key] = self.service.values.get(key, 0) + 1
        return self.service.values[key]

    async def expire(self, key, seconds):
        self._ready()
        self.service.ttls[key] = int(seconds)
        return True

    async def ttl(self, key):
        self._ready()
        return self.service.ttls.get(key, -1)

    async def eval(self, _script, number_of_keys, key, seconds):
        assert number_of_keys == 1
        self._ready()
        self.service.values[key] = self.service.values.get(key, 0) + 1
        ttl = self.service.ttls.get(key, -1)
        if ttl < 0:
            ttl = int(seconds)
            self.service.ttls[key] = ttl
        return [self.service.values[key], ttl]


def test_redis_recovers_after_delay_without_reconnecting_on_every_outage_request(monkeypatch):
    service = _SharedRedisService()
    clock = {"now": 100.0}
    monkeypatch.setattr(mw.time, "monotonic", lambda: clock["now"])
    monkeypatch.setattr(settings, "redis_url", "redis://fake")
    monkeypatch.setattr(settings, "rate_limit_enabled", True)
    monkeypatch.setattr(settings, "rate_limit_per_min", 1)
    monkeypatch.setattr(settings, "rate_limit_auth_per_min", 10_000)

    import redis.asyncio as aioredis
    monkeypatch.setattr(aioredis, "from_url", lambda *_a, **_k: service.client())

    first_worker = mw.RateLimitMiddleware(_ok)
    asyncio.run(first_worker.dispatch(_request(), _ok))       # Redis упал → local fallback
    assert service.factory_calls == 1

    clock["now"] += 1
    asyncio.run(first_worker.dispatch(_request(), _ok))       # ещё outage, reconnect рано
    assert service.factory_calls == 1, "outage не должен создавать connection на каждый запрос"

    service.available = True
    clock["now"] += 10_000                                    # ограниченная пауза точно прошла
    recovered = asyncio.run(first_worker.dispatch(_request(), _ok))
    assert recovered.status_code == 200
    assert service.factory_calls == 2, "первый worker не вернулся с local fallback в Redis"

    second_worker = mw.RateLimitMiddleware(_ok)
    shared_budget = asyncio.run(second_worker.dispatch(_request(), _ok))
    assert shared_budget.status_code == 429, "два worker не увидели общий Redis budget"
    assert service.factory_calls == 3


class _AtomicRedis:
    def __init__(self, *, value: int = 0, ttl: int = -1, lose_first_response: bool = False):
        self.value = value
        self.ttl_value = ttl
        self.lose_first_response = lose_first_response
        self.eval_calls = 0
        self.command_calls: list[str] = []

    async def eval(self, _script, number_of_keys, _key, seconds):
        assert number_of_keys == 1
        self.eval_calls += 1
        self.value += 1
        if self.ttl_value < 0:
            self.ttl_value = int(seconds)
        if self.lose_first_response:
            self.lose_first_response = False
            raise ConnectionError("reply lost after script committed")
        return [self.value, self.ttl_value]

    # Старый код проходит через эти команды: потеря ответа после INCR оставляет TTL=-1.
    async def incr(self, _key):
        self.command_calls.append("incr")
        self.value += 1
        if self.lose_first_response:
            self.lose_first_response = False
            raise ConnectionError("reply lost after incr committed")
        return self.value

    async def expire(self, _key, seconds):
        self.command_calls.append("expire")
        self.ttl_value = int(seconds)
        return True

    async def ttl(self, _key):
        self.command_calls.append("ttl")
        return self.ttl_value


def test_existing_counter_without_ttl_is_repaired_atomically():
    limiter = mw.RateLimitMiddleware(_ok)
    redis = _AtomicRedis(value=1, ttl=-1)

    assert asyncio.run(limiter._over_redis(redis, "rl:g:orphan", 10)) == (False, 0)
    assert redis.value == 2
    assert redis.ttl_value == mw._WINDOW_SEC
    assert redis.eval_calls == 1
    assert redis.command_calls == []


def test_lost_reply_after_first_atomic_increment_does_not_leave_permanent_key():
    limiter = mw.RateLimitMiddleware(_ok)
    redis = _AtomicRedis(lose_first_response=True)

    with pytest.raises(ConnectionError, match="reply lost"):
        asyncio.run(limiter._over_redis(redis, "rl:sos:lost-reply", 10))

    # Скрипт уже выполнился на сервере целиком, хотя клиент не получил ответ.
    assert redis.value == 1
    assert redis.ttl_value == mw._WINDOW_SEC
    assert asyncio.run(limiter._over_redis(redis, "rl:sos:lost-reply", 10)) == (False, 0)
    assert redis.value == 2
    assert redis.ttl_value == mw._WINDOW_SEC
    assert redis.eval_calls == 2
    assert redis.command_calls == []
