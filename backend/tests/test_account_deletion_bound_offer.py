# -*- coding: utf-8 -*-
"""Удаление аккаунта после принятого «водитель предложил свой рейс» (ревью PR190, 23.10.2026).

Принятый отклик со своим рейсом держит внешний ключ `requestresponse.booking_id` → `booking`
(миграция ca_booking_segments_20261018). Каскад `delete_user_account` удалял брони
РАНЬШЕ откликов, и удаление аккаунта падало по внешнему ключу: человек не мог
стереть свои данные (152-ФЗ). Лечится порядком: отклики — до броней.

SQLite в тестах идёт с включёнными внешними ключами (conftest), поэтому падение видно и здесь;
на Postgres то же самое проверяет CI-джоба и ручной прогон.
"""
import pytest
from sqlmodel import Session

from app import models as M
from app.db import engine
from app.models import User, UserRole
from test_account_deletion import _residual_user_refs
from test_booking_segments import seed_route


def _bound_deal(client, user_factory, closed: str):
    """Водитель откликнулся своим рейсом → пассажир принял → бронь закрыта (cancelled | done)."""
    driver = user_factory("Del bound driver", role=UserRole.driver, gender="female")
    passenger = user_factory("Del bound passenger", gender="female")
    rid, reqid = seed_route(driver, passenger)
    r = client.post(f"/requests/{reqid}/respond", headers=driver["auth"],
                    json={"ride_id": rid, "price": 450})
    assert r.status_code == 200, r.text
    respid = r.json()["id"]
    a = client.post(f"/responses/{respid}/accept", headers=passenger["auth"])
    assert a.status_code == 200, a.text
    bid = a.json()["booking_id"]
    if closed == "cancelled":
        c = client.post(f"/bookings/{bid}/cancel", headers=passenger["auth"], json={"reason": "changed_mind"})
        assert c.status_code == 200, c.text
    else:
        with Session(engine) as s:
            booking = s.get(M.Booking, bid)
            booking.status = M.BookingStatus.done
            s.add(booking)
            s.commit()
    # Связь отклика с бронью действительно есть — иначе тест ничего не доказывал бы.
    with Session(engine) as s:
        assert s.get(M.RequestResponse, respid).booking_id == bid
    return driver, passenger, rid, reqid, respid, bid


@pytest.mark.parametrize("closed", ["cancelled", "done"])
def test_passenger_can_delete_account_after_bound_offer_was_accepted(client, user_factory, closed):
    driver, passenger, rid, reqid, respid, bid = _bound_deal(client, user_factory, closed)
    d = client.post("/me/delete", headers=passenger["auth"])
    assert d.status_code == 200, d.text
    with Session(engine) as s:
        assert s.get(User, passenger["id"]) is None
        assert s.get(M.Booking, bid) is None
        assert s.get(M.RequestResponse, respid) is None
        assert s.get(M.RideRequest, reqid) is None
        # Водитель и его опубликованный рейс живут: удалился только пассажир.
        assert s.get(User, driver["id"]) is not None
        assert s.get(M.Ride, rid) is not None
    assert _residual_user_refs(passenger["id"]) == []


@pytest.mark.parametrize("closed", ["cancelled", "done"])
def test_driver_can_delete_account_after_bound_offer_was_accepted(client, user_factory, closed):
    driver, passenger, rid, reqid, respid, bid = _bound_deal(client, user_factory, closed)
    d = client.post("/me/delete", headers=driver["auth"])
    assert d.status_code == 200, d.text
    with Session(engine) as s:
        assert s.get(User, driver["id"]) is None
        assert s.get(M.Ride, rid) is None
        assert s.get(M.Booking, bid) is None
        assert s.get(M.RequestResponse, respid) is None
        # Пассажир и его заявка живут: удалился только водитель.
        assert s.get(User, passenger["id"]) is not None
        assert s.get(M.RideRequest, reqid) is not None
    assert _residual_user_refs(driver["id"]) == []
