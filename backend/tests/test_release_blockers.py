# -*- coding: utf-8 -*-
"""Тесты на фиксы блокеров release-2026-07 (senior-ревью).

B1 — фантомная онлайн-оплата: в проде при provider != yookassa оплата поездки → 503
     (клиент по 503 прячет карту), иначе mock/sbp_manual вернул бы «succeeded» без денег.
B4 — 152-ФЗ: документы таксиста (справка о несудимости/ОСАГО/селфи/разрешение) стираются
     с диска при удалении аккаунта.
B5 — «Купи и привези»: отмена посылки после закупки товара курьером запрещена (409).
"""
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app import account as acct
from app import models as M
from app.models import InstantOrder, InstantOrderStatus, TaxiApplication, User, UserRole

from test_courier_c2 import _make_courier, _order, _accept


# ------------------------------- B1 -------------------------------

def _make_done_order(passenger_id: int, driver_id: int, price_rub: int = 150) -> int:
    with Session(engine) as s:
        order = InstantOrder(
            passenger_id=passenger_id, driver_id=driver_id,
            status=InstantOrderStatus.done, price_final=price_rub,
            from_lat=54.0, from_lng=55.0, to_lat=54.1, to_lng=55.1,
        )
        s.add(order); s.commit(); s.refresh(order)
        return order.id


def test_online_pay_blocked_in_sbp_manual_prod(client, user_factory, monkeypatch):
    """B1: prod + sbp_manual → онлайн-оплата поездки 503, заказ НЕ помечен оплаченным."""
    passenger = user_factory("PayPax")
    driver = user_factory("PayDrv", role=UserRole.driver)
    oid = _make_done_order(passenger["id"], driver["id"])

    monkeypatch.setattr(settings, "env", "prod")
    monkeypatch.setattr(settings, "payments_provider", "sbp_manual")
    r = client.post(f"/instant/orders/{oid}/pay", headers=passenger["auth"], json={"method": "card"})
    assert r.status_code == 503, r.text
    with Session(engine) as s:
        assert s.get(InstantOrder, oid).paid is False   # фантомного начисления не было


def test_online_pay_allowed_in_dev_mock(client, user_factory, monkeypatch):
    """Контроль: в dev (is_prod=False) mock-оплата по-прежнему работает — фикс не ломает разработку/тесты."""
    passenger = user_factory("PayPaxDev")
    driver = user_factory("PayDrvDev", role=UserRole.driver)
    oid = _make_done_order(passenger["id"], driver["id"])

    monkeypatch.setattr(settings, "env", "dev")
    monkeypatch.setattr(settings, "payments_provider", "sbp_manual")
    r = client.post(f"/instant/orders/{oid}/pay", headers=passenger["auth"], json={"method": "card"})
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "succeeded"


# ------------------------------- B4 -------------------------------

def test_delete_account_wipes_taxi_documents(client, user_factory, monkeypatch):
    """B4: селфи/разрешение/ОСАГО/справка о несудимости таксиста стираются с диска при удалении."""
    u = user_factory("TaxiDel", role=UserRole.driver)   # user_factory заводит approved TaxiApplication
    uid = u["id"]
    docs = {
        "selfie": "secure/docs/taxi_selfie_del.jpg",
        "permit": "secure/docs/taxi_permit_del.jpg",
        "osago": "secure/docs/taxi_osago_del.jpg",
        "crim": "secure/docs/taxi_spravka_del.jpg",
    }
    with Session(engine) as s:
        ta = s.exec(select(TaxiApplication).where(TaxiApplication.user_id == uid)).first()
        ta.selfie_url = docs["selfie"]; ta.permit_photo_url = docs["permit"]
        ta.osago_url = docs["osago"]; ta.criminal_record_url = docs["crim"]
        s.add(ta); s.commit()

    deleted: list[str] = []

    class _FakeStorage:
        def delete(self, name):
            deleted.append(name)

    monkeypatch.setattr(acct, "get_storage", lambda: _FakeStorage())
    with Session(engine) as s:
        acct.delete_user_account(s, s.get(User, uid))

    for key, url in docs.items():
        base = url.split("/")[-1]
        assert any(base in d for d in deleted), f"документ таксиста {key} ({base}) не стёрт: {deleted}"


# ------------------------------- B5 -------------------------------

def test_cancel_buy_bring_after_purchase_blocked(client, user_factory):
    """B5: отмена buy_bring после ввода фактической стоимости товара (закупки) → 409 (только спор)."""
    prev = settings.courier_enabled
    settings.courier_enabled = True
    try:
        courier = _make_courier(client, user_factory, name="B5Курьер")
        sender = user_factory(name="B5Покупатель")
        pid = _order(client, sender).json()["id"]
        assert _accept(client, courier, pid).status_code == 200
        # курьер закупил товар
        assert client.post(f"/courier/orders/{pid}/goods-cost", headers=courier["auth"],
                           json={"actual_kop": 150000}).status_code == 200
        # отправитель пытается отменить после закупки → запрещено
        r = client.post(f"/parcels/{pid}/cancel", headers=sender["auth"])
        assert r.status_code == 409, r.text
    finally:
        settings.courier_enabled = prev


def test_cancel_buy_bring_before_purchase_allowed(client, user_factory):
    """Контроль: до закупки (goods_actual_kop=0) отмена buy_bring по-прежнему разрешена."""
    prev = settings.courier_enabled
    settings.courier_enabled = True
    try:
        courier = _make_courier(client, user_factory, name="B5Курьер2")
        sender = user_factory(name="B5Покупатель2")
        pid = _order(client, sender).json()["id"]
        assert _accept(client, courier, pid).status_code == 200
        # без goods-cost — отмена проходит
        r = client.post(f"/parcels/{pid}/cancel", headers=sender["auth"])
        assert r.status_code == 200, r.text
    finally:
        settings.courier_enabled = prev


# ------------------------------- B2 -------------------------------

def test_online_pay_voids_commission_debt(user_factory):
    """B2: онлайн-оплата (cashless) завершённого такси-заказа гасит долг Модели А по этому заказу —
    иначе водитель обложен комиссией дважды (ledger fee + долг), а фантомный долг блокирует такси."""
    from app import ledger, debt
    from app.models import CommissionDebt, DebtStatus
    from app.timeutil import utcnow
    passenger = user_factory("B2Pax")
    driver = user_factory("B2Drv", role=UserRole.driver)
    with Session(engine) as s:
        order = InstantOrder(
            passenger_id=passenger["id"], driver_id=driver["id"],
            status=InstantOrderStatus.done, price_final=1000, done_at=utcnow(),
            from_lat=54.0, from_lng=55.0, to_lat=54.1, to_lng=55.1,
        )
        s.add(order); s.commit(); s.refresh(order)
        oid = order.id
        d = debt.accrue_for_order(s, order)                       # долг Модели А (как на done)
        assert d is not None and d.amount_kop > 0
        ledger.settle_instant_order(s, oid, "card", 1000 * 100)   # пассажир платит онлайн
    with Session(engine) as s:
        dd = s.exec(select(CommissionDebt).where(CommissionDebt.order_id == oid)).first()
        assert dd is not None and dd.status == DebtStatus.paid    # долг снят → не двойная комиссия
        assert debt.taxi_block_reason(s, driver["id"]) is None    # фантомный долг не блокирует такси


# ------------------------------- V5 -------------------------------

def test_get_ride_hides_only_trusted_from_outsiders(client, user_factory):
    """V5: поездка «только для своих» (only_trusted) не отдаётся по прямому /rides/{id} тому, кто не L3."""
    from app.models import Ride, RideStatus
    from app.timeutil import utcnow
    driver = user_factory("V5Drv", role=UserRole.driver)
    outsider = user_factory("V5Out")
    with Session(engine) as s:
        ride = Ride(driver_id=driver["id"], from_city="Аҡ", to_city="Бе", depart_at=utcnow(),
                    price=100, seats_total=3, seats_left=3, only_trusted=True, status=RideStatus.active)
        s.add(ride); s.commit(); s.refresh(ride)
        rid = ride.id
    assert client.get(f"/rides/{rid}", headers=outsider["auth"]).status_code == 404   # чужой не L3 → скрыта
    assert client.get(f"/rides/{rid}", headers=driver["auth"]).status_code == 200      # владелец видит


# ------------------------------- V10 -------------------------------

def test_unknown_env_is_rejected(monkeypatch):
    """V10: нераспознанный ENV → RuntimeError (не тихий fail-open); ENV c пробелом всё равно = prod."""
    import pytest as _pytest
    monkeypatch.setattr(settings, "env", "production1")            # опечатка
    with _pytest.raises(RuntimeError):
        settings.validate_production()
    monkeypatch.setattr(settings, "env", "prod ")                 # случайный пробел
    assert settings.is_prod is True                               # всё равно прод (гварды не отключились)
