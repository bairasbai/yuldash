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
    keys = ("rate_limit_enabled", "rate_limit_per_min", "rate_limit_auth_per_min",
            "rate_limit_estimate_per_min", "rate_limit_estimate_per_user_per_min")
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


def test_estimate_has_its_own_tight_budget(client, rl_settings, user_factory):
    """Оценка цены режется своим бюджетом, пока общий ещё далёк.

    Почему отдельный: за каждым /instant/estimate может стоять платный запрос в Yandex
    Routing и Weather, а кэш обходится чуть сдвинутыми координатами — один клиент с одного
    IP превращался в усилитель расхода платного API (аудит 2026-08-03).
    """
    rl_settings.rate_limit_enabled = True
    rl_settings.rate_limit_per_min = 10_000        # общий заведомо не мешает
    rl_settings.rate_limit_auth_per_min = 10_000   # строгий тоже
    rl_settings.rate_limit_estimate_per_min = 3
    rl_settings.rate_limit_estimate_per_user_per_min = 10_000   # проверяем именно IP-бюджет
    u = user_factory("ОценщикЦены")
    ip = {"X-Real-IP": "203.0.113.20", **u["auth"]}
    body = {"from_lat": 54.73, "from_lng": 55.95, "to_lat": 53.63, "to_lng": 55.95}

    codes = [client.post("/instant/estimate", json=body, headers=ip).status_code for _ in range(6)]
    assert codes[0] != 429, codes
    assert 429 in codes, codes

    # Обычная ручка с того же IP при этом работает — режем только дорогую.
    assert client.get("/rides", headers=ip).status_code != 429


def test_courier_estimate_is_limited_too(client, rl_settings, user_factory):
    """У курьерской оценки тот же бюджет — она стоит рядом в клиенте и в лимитере."""
    rl_settings.rate_limit_enabled = True
    rl_settings.rate_limit_per_min = 10_000
    rl_settings.rate_limit_auth_per_min = 10_000
    rl_settings.rate_limit_estimate_per_min = 3
    rl_settings.rate_limit_estimate_per_user_per_min = 10_000
    u = user_factory("ОценщикДоставки")
    ip = {"X-Real-IP": "203.0.113.21", **u["auth"]}
    q = "?from_lat=54.73&from_lng=55.95&to_lat=53.63&to_lng=55.95"

    codes = [client.get(f"/courier/estimate{q}", headers=ip).status_code for _ in range(6)]
    assert 429 in codes, codes


def test_estimate_limited_per_user_even_when_ip_changes(client, rl_settings, user_factory):
    """Смена IP через прокси не помогает: у аккаунта свой потолок.

    IP — расходник, аккаунт — нет. Без персонального бюджета один пользователь с пулом прокси
    спокойно качал бы нам счёт за платный внешний API.
    """
    rl_settings.rate_limit_enabled = True
    rl_settings.rate_limit_per_min = 10_000
    rl_settings.rate_limit_auth_per_min = 10_000
    rl_settings.rate_limit_estimate_per_min = 10_000     # IP-бюджет заведомо не мешает
    rl_settings.rate_limit_estimate_per_user_per_min = 3
    u = user_factory("ОценщикСПрокси")
    body = {"from_lat": 54.73, "from_lng": 55.95, "to_lat": 53.63, "to_lng": 55.95}

    codes = []
    for i in range(6):                                    # каждый запрос — с нового IP
        headers = {"X-Real-IP": f"198.51.100.{i}", **u["auth"]}
        codes.append(client.post("/instant/estimate", json=body, headers=headers).status_code)
    assert 429 in codes, codes
    # Отказ — понятный и на двух языках (это видит живой человек, а не бот).
    last = client.post("/instant/estimate", json=body,
                       headers={"X-Real-IP": "198.51.100.200", **u["auth"]})
    assert last.status_code == 429
    detail = last.json()["detail"]
    assert detail["ru"] and detail["ba"] and detail["ru"] != detail["ba"]


def test_works_without_redis(client, rl_settings):
    """Без Redis (тестовое окружение) лимитер работает in-memory и не роняет запрос."""
    assert not rl_settings.redis_url          # окружение без Redis
    rl_settings.rate_limit_enabled = True
    rl_settings.rate_limit_per_min = 5
    rl_settings.rate_limit_auth_per_min = 5
    ip = {"X-Real-IP": "203.0.113.15"}

    r = client.get("/rides", headers=ip)
    assert r.status_code != 500               # не падает, отвечает нормально
