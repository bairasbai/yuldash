"""A passenger checking out at the first visible completion uses the final promo cap."""

from datetime import timedelta

import httpx
from sqlalchemy import event
from sqlmodel import Session

from app import instant_service as isv
from app.config import settings
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.payments import YOOKASSA_API
from app.timeutil import utcnow
from test_money_holes_audit import _order


def test_checkout_cannot_see_a_new_fare_with_the_old_discount(client, monkeypatch, user_factory):
    monkeypatch.setattr(settings, "promo_ride_max_price_share", 0.2)
    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    monkeypatch.setattr(settings, "yookassa_shop_id", "fixture-shop")
    monkeypatch.setattr(settings, "yookassa_secret_key", "fixture-not-a-credential")
    monkeypatch.setattr(isv, "active_tariff", lambda *_: object())
    monkeypatch.setattr(isv, "_tariff_price", lambda *_: 300)
    driver = user_factory("EarlyFinishDriver", role=UserRole.driver)
    passenger = user_factory("EarlyFinishPassenger")
    oid = _order(passenger["id"], driver["id"], status=S.onboard, price=600,
                 onboard_at=utcnow() - timedelta(minutes=10), promo_discount_kop=12000)
    seen = []

    def fake_bank(url, **kwargs):
        assert url == YOOKASSA_API
        seen.append(kwargs["json"]["amount"])
        return httpx.Response(200, request=httpx.Request("POST", url), json={
            "id": "58782bd0-66fe-4849-9045-ba115daa8a72", "status": "pending",
            "confirmation": {"confirmation_url": "https://yookassa.ru/fixture/confirm"},
        })

    monkeypatch.setattr(httpx, "post", fake_bank)

    def checkout_at_first_commit(_session):
        response = client.post(f"/instant/orders/{oid}/pay", headers=passenger["auth"], json={"method": "card"})
        assert response.status_code == 200, response.text
        assert response.json()["status"] == "pending"

    with Session(engine) as driver_session:
        order = driver_session.get(InstantOrder, oid)
        event.listen(driver_session, "after_commit", checkout_at_first_commit, once=True)
        result = isv.finish_early(driver_session, order)
        assert (result.status, result.price_final, result.promo_discount_kop) == (S.done, 300, 6000)
    assert seen == [{"value": "240.00", "currency": "RUB"}]
    receipt = client.get(f"/instant/orders/{oid}/receipt", headers=passenger["auth"])
    assert receipt.json()["amount_kop"] == 24000
