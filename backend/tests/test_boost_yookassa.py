"""F21 — Boost через ЮKassa: с ключами (мок httpx) идёт путь ЮKassa (create → ссылка,
webhook/fetch → go-live), без ключей — СБП-fallback как раньше. Boost активен ТОЛЬКО
после оплаты (нет honor-system при доступной ЮKassa)."""

import httpx
from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.models import Payment, Ride, UserRole

from test_flows import _publish


class _FakeResp:
    def __init__(self, payload):
        self.payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self.payload


def _yookassa_on():
    """Включить провайдер yookassa + фиктивные ключи. Возврат — старые значения для restore."""
    old = (settings.payments_provider, settings.yookassa_shop_id, settings.yookassa_secret_key)
    settings.payments_provider = "yookassa"
    settings.yookassa_shop_id = "shop"
    settings.yookassa_secret_key = "secret"
    return old


def _restore(old):
    settings.payments_provider, settings.yookassa_shop_id, settings.yookassa_secret_key = old


def test_boost_yookassa_is_pending_until_webhook_confirms(client, user_factory, monkeypatch):
    """С ключами: create → pending + confirmation_url, поездка НЕ поднята; вебхук (fetch=succeeded) → go-live."""
    driver = user_factory("YkBoostDriver", role=UserRole.driver)
    ride = _publish(client, driver, frm="YkCity", to="Ufa")
    old = _yookassa_on()
    try:
        monkeypatch.setattr(httpx, "post", lambda *a, **k: _FakeResp(
            {"id": "yk_boost_1", "status": "pending",
             "confirmation": {"confirmation_url": "https://pay.example/1"}}))
        created = client.post("/boost/create", headers=driver["auth"],
                              json={"ride_id": ride["id"], "tier": "quick"})
        assert created.status_code == 200
        body = created.json()
        assert body["status"] == "pending"
        assert body["method"] == "yookassa"
        assert body["confirmation_url"] == "https://pay.example/1"
        payment_id = body["payment_id"]

        # Пока не оплачено — поездка НЕ поднята (honor-system убран при доступной ЮKassa).
        with Session(engine) as s:
            assert s.get(Payment, payment_id).provider_id == "yk_boost_1"
            assert s.get(Ride, ride["id"]).boosted_until is None

        # Вебхук ЮKassa: телу не доверяем → перепроверка статуса (мок) → succeeded → go-live boost.
        monkeypatch.setattr("app.routers.payments.fetch_payment",
                            lambda pid: {"status": "succeeded", "metadata": {}})
        assert client.post("/payments/yookassa/webhook",
                           json={"object": {"id": "yk_boost_1"}}).status_code == 200
        with Session(engine) as s:
            assert s.get(Payment, payment_id).status == "succeeded"
            assert s.get(Ride, ride["id"]).boosted_until is not None
    finally:
        _restore(old)


def test_boost_yookassa_status_poll_confirms_go_live(client, user_factory, monkeypatch):
    """Клиент вернулся из браузера → поллит /payments/{id}/status → fetch подтверждает → boost активен.
    Первый поллинг (ЮKassa ещё pending) поездку не поднимает."""
    driver = user_factory("YkPollDriver", role=UserRole.driver)
    ride = _publish(client, driver, frm="YkPollCity", to="Ufa")
    old = _yookassa_on()
    try:
        monkeypatch.setattr(httpx, "post", lambda *a, **k: _FakeResp(
            {"id": "yk_poll_1", "status": "pending",
             "confirmation": {"confirmation_url": "https://pay.example/2"}}))
        created = client.post("/boost/create", headers=driver["auth"],
                              json={"ride_id": ride["id"], "tier": "day"})
        payment_id = created.json()["payment_id"]

        # Поллинг №1: у ЮKassa ещё pending → boost не активируется.
        monkeypatch.setattr("app.routers.payments.fetch_payment",
                            lambda pid: {"status": "pending", "metadata": {}})
        r1 = client.get(f"/payments/{payment_id}/status", headers=driver["auth"])
        assert r1.status_code == 200
        assert r1.json()["status"] == "pending"
        assert r1.json()["boosted_until"] is None
        with Session(engine) as s:
            assert s.get(Ride, ride["id"]).boosted_until is None

        # Поллинг №2: оплата прошла → fetch=succeeded → активируем на go-live.
        monkeypatch.setattr("app.routers.payments.fetch_payment",
                            lambda pid: {"status": "succeeded", "metadata": {}})
        r2 = client.get(f"/payments/{payment_id}/status", headers=driver["auth"])
        assert r2.status_code == 200
        assert r2.json()["status"] == "succeeded"
        assert r2.json()["boosted_until"] is not None
        with Session(engine) as s:
            assert s.get(Ride, ride["id"]).boosted_until is not None
    finally:
        _restore(old)


def test_boost_status_poll_is_owner_only(client, user_factory, monkeypatch):
    """Статус платежа виден только владельцу; чужой/несуществующий → 404."""
    driver = user_factory("YkOwnerDriver", role=UserRole.driver)
    other = user_factory("YkOtherUser")
    ride = _publish(client, driver, frm="YkOwnerCity", to="Ufa")
    old = _yookassa_on()
    try:
        monkeypatch.setattr(httpx, "post", lambda *a, **k: _FakeResp(
            {"id": "yk_owner_1", "status": "pending",
             "confirmation": {"confirmation_url": "https://pay.example/3"}}))
        created = client.post("/boost/create", headers=driver["auth"],
                              json={"ride_id": ride["id"], "tier": "quick"})
        payment_id = created.json()["payment_id"]
        assert client.get(f"/payments/{payment_id}/status", headers=other["auth"]).status_code == 404
        assert client.get("/payments/99999999/status", headers=driver["auth"]).status_code == 404
    finally:
        _restore(old)


def test_boost_without_keys_uses_sbp_fallback(client, user_factory):
    """Без ключей ЮKassa → СБП-fallback как раньше; boost активен только после подтверждения админом."""
    driver = user_factory("SbpFallbackDriver", role=UserRole.driver)
    admin = user_factory("SbpFallbackAdmin", role=UserRole.admin)
    ride = _publish(client, driver, frm="SbpFbCity", to="Ufa")
    old = (settings.payments_provider, settings.sbp_phone)
    settings.payments_provider = "sbp_manual"
    settings.sbp_phone = "+79990000009"
    try:
        created = client.post("/boost/create", headers=driver["auth"],
                              json={"ride_id": ride["id"], "tier": "quick"})
        assert created.status_code == 200
        body = created.json()
        assert body["status"] == "pending"
        assert body["method"] == "sbp_manual"
        assert body["payee"]["phone"] == "+79990000009"
        payment_id = body["payment_id"]

        # Пока админ не подтвердил — поездка НЕ поднята.
        with Session(engine) as s:
            assert s.get(Ride, ride["id"]).boosted_until is None

        # Админ подтвердил получение перевода → boost активен.
        assert client.post(f"/admin/payments/{payment_id}/confirm",
                           headers=admin["auth"]).status_code == 200
        with Session(engine) as s:
            assert s.get(Payment, payment_id).status == "succeeded"
            assert s.get(Ride, ride["id"]).boosted_until is not None
    finally:
        settings.payments_provider, settings.sbp_phone = old


def test_yookassa_outage_keeps_payment_for_safe_retry(client, user_factory, monkeypatch):
    """Неизвестный исход create → мягкая 503 и тот же счёт остаётся для повтора с тем же ключом."""
    import httpx
    driver = user_factory("YkOutageDriver", role=UserRole.driver)
    ride = _publish(client, driver, frm="YkOut", to="Ufa")
    old = _yookassa_on()
    try:
        def _boom(*a, **k):
            raise httpx.ConnectError("yookassa down")
        monkeypatch.setattr(httpx, "post", _boom)
        r = client.post("/boost/create", headers=driver["auth"], json={"ride_id": ride["id"], "tier": "quick"})
        assert r.status_code == 503
        # Строка остаётся: провайдер мог принять запрос до обрыва ответа. Способ отличает её
        # от ручного СБП и сохраняет Payment.id для повторного Idempotence-Key.
        with Session(engine) as s:
            from sqlmodel import select
            rows = s.exec(select(Payment).where(
                Payment.purpose == "boost", Payment.status == "pending",
                Payment.user_id == driver["id"])).all()
            assert len(rows) == 1
            assert rows[0].method == "yookassa"
            assert rows[0].provider_id == ""
    finally:
        _restore(old)
