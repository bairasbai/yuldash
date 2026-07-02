"""Unit tests for the low-level payment provider adapter."""

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


def test_mock_payment_provider_is_immediate_success():
    old_provider, old_shop, old_secret = settings.payments_provider, settings.yookassa_shop_id, settings.yookassa_secret_key
    settings.payments_provider = "mock"
    settings.yookassa_shop_id = ""
    settings.yookassa_secret_key = ""
    try:
        result = payments.create_payment(12345, "Test payment", {"payment_id": "1"}, customer_phone="+79990000000")
        assert result["provider_id"].startswith("mock_")
        assert result["confirmation_url"] == ""
        assert result["status"] == "succeeded"
        assert result["mock"] is True
        assert payments.fetch_payment("anything") == {"status": "succeeded", "metadata": {}}
    finally:
        settings.payments_provider = old_provider
        settings.yookassa_shop_id = old_shop
        settings.yookassa_secret_key = old_secret


def test_yookassa_create_payment_sends_receipt_and_returns_confirmation(monkeypatch):
    captured = {}

    def fake_post(url, json, auth, headers, timeout):
        captured["url"] = url
        captured["json"] = json
        captured["auth"] = auth
        captured["headers"] = headers
        captured["timeout"] = timeout
        return FakeResponse({
            "id": "yk_123",
            "status": "pending",
            "confirmation": {"confirmation_url": "https://pay.example/123"},
        })

    monkeypatch.setattr(httpx, "post", fake_post)
    old_provider, old_shop, old_secret = settings.payments_provider, settings.yookassa_shop_id, settings.yookassa_secret_key
    settings.payments_provider = "yookassa"
    settings.yookassa_shop_id = "shop"
    settings.yookassa_secret_key = "secret"
    try:
        result = payments.create_payment(
            12345,
            "Very long payment description " * 10,
            {"payment_id": "42"},
            customer_phone="+7 (999) 000-00-00",
        )
    finally:
        settings.payments_provider = old_provider
        settings.yookassa_shop_id = old_shop
        settings.yookassa_secret_key = old_secret

    assert result == {
        "provider_id": "yk_123",
        "confirmation_url": "https://pay.example/123",
        "status": "pending",
        "mock": False,
    }
    assert captured["url"] == payments.YOOKASSA_API
    assert captured["auth"] == ("shop", "secret")
    assert "Idempotence-Key" in captured["headers"]
    assert captured["json"]["amount"] == {"value": "123.45", "currency": "RUB"}
    assert captured["json"]["metadata"] == {"payment_id": "42"}
    assert captured["json"]["receipt"]["customer"]["phone"] == "79990000000"
    assert len(captured["json"]["receipt"]["items"][0]["description"]) == 128


def test_yookassa_create_payment_without_phone_omits_receipt(monkeypatch):
    captured = {}

    def fake_post(url, json, auth, headers, timeout):
        captured["json"] = json
        return FakeResponse({"id": "yk_no_phone", "status": "pending", "confirmation": {}})

    monkeypatch.setattr(httpx, "post", fake_post)
    old_provider, old_shop, old_secret = settings.payments_provider, settings.yookassa_shop_id, settings.yookassa_secret_key
    settings.payments_provider = "yookassa"
    settings.yookassa_shop_id = "shop"
    settings.yookassa_secret_key = "secret"
    try:
        result = payments.create_payment(1000, "No phone", {}, customer_phone="")
    finally:
        settings.payments_provider = old_provider
        settings.yookassa_shop_id = old_shop
        settings.yookassa_secret_key = old_secret

    assert result["provider_id"] == "yk_no_phone"
    assert result["confirmation_url"] == ""
    assert "receipt" not in captured["json"]


def test_yookassa_fetch_payment_returns_status_and_metadata(monkeypatch):
    captured = {}

    def fake_get(url, auth, timeout):
        captured["url"] = url
        captured["auth"] = auth
        captured["timeout"] = timeout
        return FakeResponse({"status": "succeeded", "metadata": {"payment_id": "42"}})

    monkeypatch.setattr(httpx, "get", fake_get)
    old_provider, old_shop, old_secret = settings.payments_provider, settings.yookassa_shop_id, settings.yookassa_secret_key
    settings.payments_provider = "yookassa"
    settings.yookassa_shop_id = "shop"
    settings.yookassa_secret_key = "secret"
    try:
        result = payments.fetch_payment("yk_123")
    finally:
        settings.payments_provider = old_provider
        settings.yookassa_shop_id = old_shop
        settings.yookassa_secret_key = old_secret

    assert result == {"status": "succeeded", "metadata": {"payment_id": "42"}}
    assert captured["url"] == f"{payments.YOOKASSA_API}/yk_123"
    assert captured["auth"] == ("shop", "secret")
