"""Регресс-тесты фиксов аудита release-2026-07 (PR #73 hardening).

B2 — онлайн-оплата дедупит pending (два тапа ≠ два списания).
B4 — matcher не предлагает водителя с открытым оффером (корень «дубля назначения»);
     accept второго заказа при активном первом → 409.
B3 покрыт обновлёнными test_ledger (лесенка вместо плоских 8%).
"""
from sqlmodel import Session, select

from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, Payment, UserRole

from test_ledger import _make_done_order  # noqa: F401  (готовый done-заказ прямо в БД)


# ------------------------------ B2: дедуп онлайн-оплаты ------------------------------
def test_pay_dedups_pending_no_double_charge(client, user_factory, monkeypatch):
    from app.routers import wallet
    monkeypatch.setattr(wallet.settings, "payments_provider", "yookassa")
    calls = {"start": 0}

    def fake_start(session, payment, desc, phone):
        calls["start"] += 1
        return {"status": "pending", "provider_id": f"pid_{payment.id}", "confirmation_url": "http://pay"}

    monkeypatch.setattr(wallet, "_start_yookassa", fake_start)
    monkeypatch.setattr(wallet, "fetch_payment", lambda pid: {"status": "pending", "confirmation_url": "http://pay2"})

    drv = user_factory("DedupDrv", role=UserRole.driver)
    pax = user_factory("DedupPax")
    oid = _make_done_order(drv["id"], pax["id"], price_rub=200)

    r1 = client.post(f"/instant/orders/{oid}/pay", headers=pax["auth"], json={"method": "card"})
    r2 = client.post(f"/instant/orders/{oid}/pay", headers=pax["auth"], json={"method": "card"})
    assert r1.status_code == 200 and r2.status_code == 200
    assert r1.json()["status"] == "pending"
    assert r1.json()["payment_id"] == r2.json()["payment_id"], "второй тап должен вернуть ТОТ ЖЕ платёж"
    assert calls["start"] == 1, "yookassa-платёж создаётся один раз, не на каждый тап"
    with Session(engine) as s:
        pays = s.exec(select(Payment).where(Payment.order_id == oid)).all()
    assert len(pays) == 1, "ровно один Payment на заказ (не два списания)"


# ------------------------------ B4: анти-дубль назначения ------------------------------
def test_busy_driver_ids_excludes_offered(client, user_factory):
    """Водитель с открытым оффером — «занят» для matcher (иначе его предложат второму заказу)."""
    from app import instant_service as isv
    drv = user_factory("OfferDrv", role=UserRole.driver)
    pax = user_factory("OfferPax")
    with Session(engine) as s:
        o = InstantOrder(
            passenger_id=pax["id"], current_offer_driver_id=drv["id"], status=S.offered,
            from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6, from_text="А", to_text="Б",
        )
        s.add(o)
        s.commit()
        busy = isv.busy_driver_ids(s, [drv["id"]])
    assert drv["id"] in busy


def test_accept_blocked_when_driver_has_active_order(client, user_factory):
    """Водитель с активным заказом не может принять второй → 409 (анти-дубль назначения)."""
    drv = user_factory("TwoDrv", role=UserRole.driver)
    pax1 = user_factory("Pax1")
    pax2 = user_factory("Pax2")
    with Session(engine) as s:
        active = InstantOrder(
            passenger_id=pax1["id"], driver_id=drv["id"], status=S.accepted,
            from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6, from_text="А", to_text="Б",
        )
        s.add(active)
        offered = InstantOrder(
            passenger_id=pax2["id"], current_offer_driver_id=drv["id"], status=S.offered,
            from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6, from_text="В", to_text="Г",
        )
        s.add(offered)
        s.commit()
        s.refresh(offered)
        offered_id = offered.id
    r = client.post(f"/instant/orders/{offered_id}/accept", headers=drv["auth"])
    assert r.status_code == 409, r.text
