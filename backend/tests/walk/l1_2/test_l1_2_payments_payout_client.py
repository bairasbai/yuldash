"""leaf-1.2 — backend/app/payments.py: клиент выплат (create_payout/payout_keys_present).

Пробел обхода: все существующие тесты выплат (test_payout_safety.py,
test_the_payout_that_never_left.py, test_payouts.py) подменяют саму функцию
`app.payments.create_payout` целиком — её РЕАЛЬНОЕ тело (сборка суммы в рублях,
Idempotence-Key, запрос к ЮKassa Payout API, mock-ответ без ключей) не выполняется
НИ РАЗУ ни в одном тесте. Для `create_payment`/`fetch_payment` такой прямой тест есть
(test_payment_provider.py), для `create_payout` — нет. Здесь закрываем этот пробел тем
же приёмом: подменяем только httpx, само тело функции выполняется по-настоящему.
"""
import httpx

from app.config import settings
from app import payments


class FakeResponse:
    def __init__(self, payload):
        self.payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self.payload


def test_mock_payout_without_keys_is_immediate_success_no_network(monkeypatch):
    """Нет ключей выплат → фиктивный успех, БЕЗ похода в сеть (никаких реальных денег)."""
    monkeypatch.setattr(settings, "yookassa_payout_agent_id", "")
    monkeypatch.setattr(settings, "yookassa_payout_secret_key", "")

    def boom(*a, **k):
        raise AssertionError("mock-выплата не должна стучаться в сеть")

    monkeypatch.setattr(httpx, "post", boom)

    assert payments.payout_keys_present() is False
    result = payments.create_payout(5000, "tok_123", "Юлдаш · выплата", {"driver_id": "1"})
    assert result["payout_id"].startswith("mock_payout_")
    assert result["status"] == "succeeded"
    assert result["mock"] is True


def test_real_payout_sends_amount_token_and_idempotence_key(monkeypatch):
    """С реальными ключами тело запроса обязано нести сумму в рублях, payout_token
    (не полный номер карты — его у нас и нет) и свой Idempotence-Key."""
    captured = {}

    def fake_post(url, json, auth, headers, timeout):
        captured.update(url=url, json=json, auth=auth, headers=headers, timeout=timeout)
        return FakeResponse({"id": "po_42", "status": "pending"})

    monkeypatch.setattr(httpx, "post", fake_post)
    monkeypatch.setattr(settings, "yookassa_payout_agent_id", "agent")
    monkeypatch.setattr(settings, "yookassa_payout_secret_key", "secret")

    assert payments.payout_keys_present() is True
    result = payments.create_payout(
        12345, "tok_abc", "Юлдаш · выплата водителю #9", {"driver_id": "9"},
        idempotence_key="payout:9:retry-1",
    )

    assert result == {"payout_id": "po_42", "status": "pending", "mock": False}
    assert captured["url"] == payments.YOOKASSA_PAYOUT_API
    assert captured["auth"] == ("agent", "secret")
    assert captured["headers"]["Idempotence-Key"] == "payout:9:retry-1"
    assert captured["json"]["amount"] == {"value": "123.45", "currency": "RUB"}
    assert captured["json"]["payout_token"] == "tok_abc"
    assert "card_number" not in captured["json"] and "pan" not in captured["json"], (
        "полный номер карты у нас не хранится — наружу уходит только токен провайдера"
    )


def test_payout_without_idempotence_key_still_gets_one(monkeypatch):
    """Пустой ключ на входе не должен уйти к провайдеру пустым (иначе повтор не защищён)."""
    captured = {}

    def fake_post(url, json, auth, headers, timeout):
        captured["headers"] = headers
        return FakeResponse({"id": "po_1", "status": "pending"})

    monkeypatch.setattr(httpx, "post", fake_post)
    monkeypatch.setattr(settings, "yookassa_payout_agent_id", "agent")
    monkeypatch.setattr(settings, "yookassa_payout_secret_key", "secret")

    payments.create_payout(1000, "tok", "d", {})
    assert captured["headers"]["Idempotence-Key"], "пустой ключ ушёл бы к провайдеру как есть"
