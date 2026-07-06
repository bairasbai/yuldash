"""Глобальный rate limit (Фаза 1.4): общий бюджет на IP, строгий на чувствительные
ручки, 429 с телом и Retry-After, освобождение health/вебхуков, работа без Redis.

Лимитер выключен глобально в conftest (RATE_LIMIT_ENABLED=false) — каждый тест
включает его локально через фикстуру и берёт УНИКАЛЬНЫЙ X-Real-IP, чтобы счётчики
не пересекались между тестами (middleware держит окна по IP в одном инстансе)."""
import pytest


@pytest.fixture
def rl_settings():
    """Правит настройки лимитера на время теста и откатывает после."""
    from app.config import settings
    keys = ("rate_limit_enabled", "rate_limit_per_min", "rate_limit_auth_per_min")
    saved = {k: getattr(settings, k) for k in keys}
    yield settings
    for k, v in saved.items():
        setattr(settings, k, v)


def test_general_limit_returns_429_with_retry_after(client, rl_settings):
    """Превышение общего лимита на IP → 429 с телом и заголовком Retry-After."""
    rl_settings.rate_limit_enabled = True
    rl_settings.rate_limit_per_min = 3
    rl_settings.rate_limit_auth_per_min = 10_000   # не мешаем строгим бюджетом
    ip = {"X-Real-IP": "203.0.113.10"}

    codes = [client.get("/rides", headers=ip).status_code for _ in range(6)]
    assert codes[0] != 429, codes          # под лимитом — проходит
    assert 429 in codes, codes             # после порога — отбой

    r = client.get("/rides", headers=ip)
    assert r.status_code == 429
    assert int(r.headers["Retry-After"]) >= 1        # подсказка «когда повторить»
    body = r.json()
    assert body["detail"]                            # понятное тело
    assert body["retry_after"] >= 1


def test_under_limit_all_ok(client, rl_settings):
    """Под лимитом ни один запрос не режется."""
    rl_settings.rate_limit_enabled = True
    rl_settings.rate_limit_per_min = 50
    rl_settings.rate_limit_auth_per_min = 50
    ip = {"X-Real-IP": "203.0.113.11"}

    codes = [client.get("/rides", headers=ip).status_code for _ in range(5)]
    assert all(c != 429 for c in codes), codes


def test_strict_budget_tighter_than_general(client, rl_settings):
    """Чувствительная ручка (/auth/*) режется своим строгим бюджетом,
    даже когда общий бюджет ещё далёк."""
    rl_settings.rate_limit_enabled = True
    rl_settings.rate_limit_per_min = 10_000        # общий заведомо не мешает
    rl_settings.rate_limit_auth_per_min = 3
    ip = {"X-Real-IP": "203.0.113.12"}

    codes = [client.post("/auth/vk-callback", headers=ip).status_code for _ in range(6)]
    assert codes[0] != 429, codes
    assert 429 in codes, codes


def test_health_not_hard_limited(client, rl_settings):
    """/health — проба мониторинга, освобождена от жёсткого лимита даже при лимите 1."""
    rl_settings.rate_limit_enabled = True
    rl_settings.rate_limit_per_min = 1
    rl_settings.rate_limit_auth_per_min = 1
    ip = {"X-Real-IP": "203.0.113.13"}

    codes = [client.get("/health", headers=ip).status_code for _ in range(10)]
    assert all(c == 200 for c in codes), codes      # ни одного 429


def test_webhook_not_hard_limited(client, rl_settings):
    """Вебхук Telegram начинается с /auth, но освобождён от строгого лимита —
    иначе активного бота резало бы 429. Проверяем: 429 не прилетает."""
    rl_settings.rate_limit_enabled = True
    rl_settings.rate_limit_per_min = 1
    rl_settings.rate_limit_auth_per_min = 1
    ip = {"X-Real-IP": "203.0.113.14"}

    codes = [client.post("/auth/telegram/webhook", json={}, headers=ip).status_code for _ in range(10)]
    assert 429 not in codes, codes


def test_works_without_redis(client, rl_settings):
    """Без Redis (тестовое окружение) лимитер работает in-memory и не роняет запрос."""
    assert not rl_settings.redis_url          # окружение без Redis
    rl_settings.rate_limit_enabled = True
    rl_settings.rate_limit_per_min = 5
    rl_settings.rate_limit_auth_per_min = 5
    ip = {"X-Real-IP": "203.0.113.15"}

    r = client.get("/rides", headers=ip)
    assert r.status_code != 500               # не падает, отвечает нормально
