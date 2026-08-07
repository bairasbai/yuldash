"""«Красная кнопка» не должна отказывать из-за чужих попыток входа (аудит 2026-08-07).

Что было не так. Лимит запросов считается на IP-адрес. У `/auth/*` он строгий (20/мин) —
это защита от перебора кодов входа, она нужна. Но `/sos` сидел в ТОМ ЖЕ ведре и с тем же
бюджетом: один ключ `rl:s:{ip}` на двоих.

Почему это не теория. В деревне интернет часто общий: одна вышка, Wi-Fi в кафе, NAT
оператора — и десяток соседей выглядят для сервера одним адресом. Несколько человек подряд
входят в приложение, бюджет на этот IP выбран, и следующий сигнал SOS получает 429
«Слишком много запросов». Кнопка, которая обязана срабатывать всегда, молча отказывает —
причём именно в тот момент, когда людей вокруг много.

Как починено. У SOS свой ключ и свой бюджет (`rate_limit_sos_per_min`), куда посторонний
трафик не попадает. Спам SOS по-прежнему ограничен, но порог выше: человек в беде жмёт
кнопку несколько раз подряд, и это нормально.
"""
import pytest

from app.config import settings
from app.middleware import _SOS_PREFIXES, _STRICT_PREFIXES


def test_sos_is_not_in_the_auth_bucket():
    """Главное: SOS больше не делит ведро с входом."""
    for prefix in _STRICT_PREFIXES:
        assert not prefix.rstrip("/").endswith("/sos"), (
            f"«{prefix}» снова попал в строгое ведро /auth — чужие попытки входа опять "
            f"смогут выбрать бюджет «красной кнопки»"
        )
    assert "/sos" in _SOS_PREFIXES
    assert "/api/v1/sos" in _SOS_PREFIXES, "версионированный путь тоже должен иметь свой бюджет"


def test_sos_budget_is_not_stricter_than_auth():
    """Порог SOS должен быть не ниже, чем у входа: человек в беде жмёт несколько раз."""
    assert settings.rate_limit_sos_per_min >= settings.rate_limit_auth_per_min


def test_login_attempts_do_not_exhaust_the_sos_budget(client, user_factory, monkeypatch):
    """Живая проверка: выбираем бюджет входа до отказа — SOS обязан продолжать работать."""
    monkeypatch.setattr(settings, "rate_limit_enabled", True)
    monkeypatch.setattr(settings, "rate_limit_auth_per_min", 3)   # маленькие числа: тест быстрый
    monkeypatch.setattr(settings, "rate_limit_sos_per_min", 30)
    monkeypatch.setattr(settings, "rate_limit_per_min", 10_000)   # общий лимит не мешает

    u = user_factory("Пострадавший")

    # Выбираем бюджет ВХОДА до отказа — как сосед за тем же Wi-Fi.
    saw_429 = False
    for _ in range(12):
        if client.post("/auth/request-code", json={"phone": "+79990009999"}).status_code == 429:
            saw_429 = True
            break
    assert saw_429, "лимит входа не сработал — тест не проверяет то, что должен"

    # А SOS в этот момент обязан пройти.
    r = client.post("/sos", headers=u["auth"], json={"lat": 52.59, "lng": 58.31})
    assert r.status_code != 429, (
        "«красная кнопка» отказала из-за чужих попыток входа — ровно та дыра, "
        "которую закрывали"
    )
