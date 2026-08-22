# -*- coding: utf-8 -*-
"""Деньги водителя — блокеры аудита 2026-07-26.

Три истории из жизни райцентра, которые раньше заканчивались плохо:
- водитель жал «Я оплатил» и не платил — бесконечно, комиссию можно было не платить вообще;
- водителя кинули на 300 ₽ наличными, а он ещё оставался должен нам комиссию с этой поездки;
- водитель не мог проверить, откуда взялась сумма заработка, — расшифровки не существовало.
"""
from datetime import timedelta

from sqlmodel import Session, select

from app import debt as debt_mod
from app import models as M
from app.db import engine
from app.models import DebtStatus, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow


def _done_order(passenger_id, driver_id, price=300, **kw):
    base = dict(passenger_id=passenger_id, driver_id=driver_id, status=S.done,
                from_lat=52.59, from_lng=58.31, to_lat=52.60, to_lng=58.32,
                from_text="Баймак", to_text="Сибай",
                price_estimate=price, price_final=price, done_at=utcnow())
    base.update(kw)
    with Session(engine) as s:
        o = M.InstantOrder(**base)
        s.add(o); s.commit(); s.refresh(o)
        return o.id


def _debt(driver_id, order_id=None, amount=2400, status=DebtStatus.unpaid, **kw):
    with Session(engine) as s:
        d = M.CommissionDebt(driver_id=driver_id, order_id=order_id, amount_kop=amount,
                             week="2026-W30", status=status, **kw)
        s.add(d); s.commit(); s.refresh(d)
        return d.id


# ============== 1. Комиссию нельзя не платить бесконечно ==============
def test_declare_paid_stops_unblocking_after_limit(client, user_factory):
    """Раньше: заявил «оплатил» → блок снят; админ отклонил → нажал снова → снова работает.
    Так можно было не платить комиссию НИКОГДА. Теперь доверие исчерпывается."""
    drv = user_factory("DeclDrv", role=UserRole.driver)
    # Просроченный долг — блокировка есть.
    _debt(drv["id"], amount=200_000, due_at=utcnow() - timedelta(days=1))
    with Session(engine) as s:
        assert debt_mod.taxi_block_reason(s, drv["id"]) == "overdue"
        # Первое «слово» верим — блок снят.
        debt_mod.declare_paid(s, drv["id"])
        assert debt_mod.taxi_block_reason(s, drv["id"]) is None
    # Админ отклоняет, водитель заявляет снова — и так по кругу.
    for _ in range(debt_mod.settings.debt_max_declares):
        with Session(engine) as s:
            d = s.exec(select(M.CommissionDebt).where(M.CommissionDebt.driver_id == drv["id"])).first()
            debt_mod.admin_reject(s, d.id)
            debt_mod.declare_paid(s, drv["id"])
    with Session(engine) as s:
        # Слово давали больше лимита — теперь pending блокировку НЕ снимает.
        assert debt_mod.taxi_block_reason(s, drv["id"]) == "declare_abuse"


def test_declare_count_grows_and_survives_reject(client, user_factory):
    """Счётчик обещаний не сбрасывается отказом — иначе лимит обходился бы по кругу."""
    drv = user_factory("DeclCount", role=UserRole.driver)
    did = _debt(drv["id"], amount=50_000)
    with Session(engine) as s:
        debt_mod.declare_paid(s, drv["id"])
        assert s.get(M.CommissionDebt, did).declare_count == 1
        debt_mod.admin_reject(s, did)
        debt_mod.declare_paid(s, drv["id"])
        assert s.get(M.CommissionDebt, did).declare_count == 2


# ============== 2. Кинули на нал → комиссию снимаем ==============
def test_confirmed_unpaid_report_voids_commission(client, user_factory, monkeypatch):
    """Водитель отметил «пассажир не заплатил», админ подтвердил → долг по ЭТОЙ поездке снят.
    Раньше жалоба ставила только пометку, и водитель оставался должен за поездку, где его кинули."""
    monkeypatch.setattr("app.routers.safety.push_bilingual", lambda *a, **k: None)
    drv = user_factory("UnpaidDrv", role=UserRole.driver)
    pax = user_factory("UnpaidPax")
    admin = user_factory("UnpaidAdmin", role=UserRole.admin)
    oid = _done_order(pax["id"], drv["id"])
    did = _debt(drv["id"], order_id=oid, amount=2400)
    r = client.post("/reports", headers=drv["auth"],
                    json={"order_id": oid, "category": "unpaid", "reason": "не заплатил наличными"})
    assert r.status_code in (200, 201), r.text
    rid = r.json()["id"]
    assert client.post(f"/admin/reports/{rid}/resolve", headers=admin["auth"],
                       json={"resolution": "подтверждено"}).status_code == 200
    with Session(engine) as s:
        assert s.get(M.CommissionDebt, did).status == DebtStatus.paid, "комиссия должна быть снята"


def test_admin_can_forgive_debt(client, user_factory):
    """У админа появилась кнопка «простить». Раньше были только «подтвердить»/«отклонить»:
    списать долг честно было нечем, а «подтвердить» врало бы в отчёте о собранной комиссии."""
    drv = user_factory("ForgiveDrv", role=UserRole.driver)
    admin = user_factory("ForgiveAdmin", role=UserRole.admin)
    plain = user_factory("ForgivePlain")
    did = _debt(drv["id"], amount=3300)
    assert client.post(f"/admin/debts/{did}/forgive", headers=plain["auth"],
                       json={"reason": "не наш"}).status_code == 403
    r = client.post(f"/admin/debts/{did}/forgive", headers=admin["auth"],
                    json={"reason": "пассажир не заплатил"})
    assert r.status_code == 200 and r.json()["forgiven_kop"] == 3300
    with Session(engine) as s:
        d = s.get(M.CommissionDebt, did)
        assert d.status == DebtStatus.paid and "пассажир не заплатил" in d.note
    # Идемпотентно: повтор не ломается и не «прощает» дважды.
    assert client.post(f"/admin/debts/{did}/forgive", headers=admin["auth"]).json()["already"] is True


# ============== 3. Водитель видит, откуда сумма ==============
def test_driver_rides_shows_price_fee_net(client, user_factory):
    """«Юлдаш говорит 4200, я насчитал 4600» — теперь есть расшифровка по каждой поездке."""
    drv = user_factory("RidesDrv", role=UserRole.driver)
    pax = user_factory("RidesPax")
    o1 = _done_order(pax["id"], drv["id"], price=300)
    o2 = _done_order(pax["id"], drv["id"], price=500)
    _debt(drv["id"], order_id=o1, amount=2400)      # 8% с 300 ₽
    _debt(drv["id"], order_id=o2, amount=4000)      # 8% с 500 ₽
    r = client.get("/driver/taxi-rides", headers=drv["auth"])
    assert r.status_code == 200
    data = r.json()
    assert data["total_price"] == 800
    assert data["total_fee_kop"] == 6400
    assert data["total_net_kop"] == 800 * 100 - 6400
    ids = {ride["order_id"] for ride in data["rides"]}
    assert {o1, o2} <= ids
    one = next(x for x in data["rides"] if x["order_id"] == o1)
    assert one["price"] == 300 and one["fee_kop"] == 2400 and one["net_kop"] == 27_600
    assert one["from"] == "Баймак" and one["to"] == "Сибай"


def test_driver_rides_are_private(client, user_factory):
    """Чужие поездки в свою расшифровку не попадают."""
    drv = user_factory("PrivDrv", role=UserRole.driver)
    other = user_factory("PrivOther", role=UserRole.driver)
    pax = user_factory("PrivPax")
    mine = _done_order(pax["id"], drv["id"], price=200)
    foreign = _done_order(pax["id"], other["id"], price=900)
    data = client.get("/driver/taxi-rides", headers=drv["auth"]).json()
    ids = {ride["order_id"] for ride in data["rides"]}
    assert mine in ids and foreign not in ids
