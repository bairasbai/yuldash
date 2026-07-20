"""Причина отмены брони + неявка (no-show) — сигнал доверия «между своими» и аргумент в споре.

- отмена с причиной сохраняет booking.cancel_reason;
- отмена без тела (старый клиент) по-прежнему работает — причина остаётся None (обратная совместимость);
- no-show: только водитель поездки, только по подтверждённой брони; ставит no_show + cancel_reason='no_show'.
"""
from sqlmodel import Session

from app.db import engine
from app.models import Booking, UserRole

from test_flows import _book, _publish


def test_cancel_booking_stores_reason(client, user_factory):
    pax = user_factory("CRPax")
    drv = user_factory("CRDrv", role=UserRole.driver)
    ride = _publish(client, drv, frm="ПричА", to="ПричБ", seats=2)
    b = _book(client, pax, ride["id"])
    r = client.post(f"/bookings/{b['id']}/cancel", headers=pax["auth"], json={"reason": "found_other"})
    assert r.status_code == 200 and r.json()["status"] == "cancelled"
    with Session(engine) as s:
        assert s.get(Booking, b["id"]).cancel_reason == "found_other"


def test_cancel_booking_without_body_backward_compatible(client, user_factory):
    """Старый клиент шлёт /cancel без тела → отмена проходит, причина = None (не ломаем контракт)."""
    pax = user_factory("CRPax2")
    drv = user_factory("CRDrv2", role=UserRole.driver)
    ride = _publish(client, drv, frm="ПричВ", to="ПричГ", seats=2)
    b = _book(client, pax, ride["id"])
    r = client.post(f"/bookings/{b['id']}/cancel", headers=pax["auth"])   # без json-тела
    assert r.status_code == 200 and r.json()["status"] == "cancelled"
    with Session(engine) as s:
        assert s.get(Booking, b["id"]).cancel_reason is None


def test_no_show_only_driver_on_confirmed(client, user_factory):
    pax = user_factory("NSPax")
    drv = user_factory("NSDrv", role=UserRole.driver)
    ride = _publish(client, drv, frm="НеявкаА", to="НеявкаБ", seats=3)
    b = _book(client, pax, ride["id"])
    # по неподтверждённой брони отметить неявку нельзя → 409
    assert client.post(f"/bookings/{b['id']}/no-show", headers=drv["auth"]).status_code == 409
    assert client.post(f"/bookings/{b['id']}/confirm", headers=drv["auth"]).status_code == 200
    # пассажир не может отметить неявку (не водитель поездки) → 403
    assert client.post(f"/bookings/{b['id']}/no-show", headers=pax["auth"]).status_code == 403
    # водитель отмечает неявку → бронь cancelled, no_show=True, cancel_reason='no_show'
    r = client.post(f"/bookings/{b['id']}/no-show", headers=drv["auth"])
    assert r.status_code == 200 and r.json()["status"] == "cancelled"
    with Session(engine) as s:
        bb = s.get(Booking, b["id"])
        assert bb.no_show is True and bb.cancel_reason == "no_show"
