# -*- coding: utf-8 -*-
"""Ночная чистка и принятый отклик «водитель предложил свой рейс» (ревью PR190, 23.10.2026).

Отклик держит внешние ключи на бронь (`requestresponse.booking_id`) и рейс
(`requestresponse.ride_id`). Правила чистки броней и рейсов обязаны пропускать строки, на
которые отклик ещё ссылается, а отклики должны уходить РАНЬШЕ них. Иначе один такой отклик
роняет весь пакет удаления — каждую ночь заново, молча (см. test_cleanup_covers_every_foreign_key).

Здесь правила гоняются прямым вызовом `_delete_batched` (а не `cleanup.main()`, который ловит
ошибки и пишет их в лог): ошибка внешнего ключа должна выпасть наружу и покрасить тест.
"""
from datetime import timedelta

from sqlmodel import Session

from app import cleanup
from app.db import engine
from app.models import Booking, BookingStatus, RequestResponse, Ride, RideRequest, RideStatus, UserRole
from app.timeutil import utcnow
from test_booking_segments import seed_route

_TABLES = ("requestresponse", "riderequest", "booking", "ride")   # в порядке правил чистки


def _run_trip_rules():
    deleted = {}
    for _label, table, where, params in cleanup._rules(utcnow()):
        if table in _TABLES:
            deleted[table] = deleted.get(table, 0) + cleanup._delete_batched(table, where, params)
    return deleted


def _bound_deal(client, user_factory, response_age_days: int):
    driver = user_factory("Cleanup bound driver", role=UserRole.driver, gender="female")
    passenger = user_factory("Cleanup bound passenger", gender="female")
    rid, reqid = seed_route(driver, passenger)
    r = client.post(f"/requests/{reqid}/respond", headers=driver["auth"], json={"ride_id": rid, "price": 450})
    assert r.status_code == 200, r.text
    respid = r.json()["id"]
    a = client.post(f"/responses/{respid}/accept", headers=passenger["auth"])
    assert a.status_code == 200, a.text
    bid = a.json()["booking_id"]
    old = utcnow() - timedelta(days=400)
    with Session(engine) as s:
        booking, ride, req = s.get(Booking, bid), s.get(Ride, rid), s.get(RideRequest, reqid)
        booking.status, booking.created_at = BookingStatus.cancelled, old
        ride.status, ride.created_at = RideStatus.cancelled, old
        req.created_at = old
        resp = s.get(RequestResponse, respid)
        resp.created_at = utcnow() - timedelta(days=response_age_days)
        for row in (booking, ride, req, resp):
            s.add(row)
        s.commit()
    return rid, reqid, respid, bid


def test_cleanup_skips_booking_and_ride_while_bound_response_is_alive(client, user_factory):
    """Отклик свежий (его правило не берёт), бронь и рейс старые: они обязаны остаться."""
    rid, reqid, respid, bid = _bound_deal(client, user_factory, response_age_days=1)
    try:
        _run_trip_rules()          # не должно упасть по внешнему ключу
        with Session(engine) as s:
            assert s.get(RequestResponse, respid) is not None
            assert s.get(Booking, bid) is not None
            assert s.get(Ride, rid) is not None
            assert s.get(RideRequest, reqid) is not None
    finally:
        # Общая база сессии: за собой убираем (дети -> родители), чтобы старые строки этого
        # теста не попали в чужие прогоны правил чистки.
        with Session(engine) as s:
            for model, pk in ((RequestResponse, respid), (Booking, bid), (Ride, rid), (RideRequest, reqid)):
                row = s.get(model, pk)
                if row is not None:
                    s.delete(row)
                    s.commit()


def test_cleanup_removes_old_response_first_then_booking_and_ride(client, user_factory):
    """Отклик тоже старый: уходит первым, следом (в тот же прогон) — бронь, рейс и заявка."""
    rid, reqid, respid, bid = _bound_deal(client, user_factory, response_age_days=400)
    _run_trip_rules()
    with Session(engine) as s:
        assert s.get(RequestResponse, respid) is None
        assert s.get(Booking, bid) is None
        assert s.get(Ride, rid) is None
        assert s.get(RideRequest, reqid) is None
