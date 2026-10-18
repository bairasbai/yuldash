"""Предложение своего опубликованного рейса не создаёт второй рейс и не теряет участок."""
from datetime import timedelta
from types import SimpleNamespace

import pytest
from sqlalchemy.sql.dml import Update
from sqlmodel import Session, select

from app.db import engine
from app.models import Block, Booking, BookingStatus, RequestResponse, Ride, RideRequest, RideStatus, User, UserRole
from app.routers import requests as requests_mod
from app.timeutil import utcnow
from test_booking_segments import seed_route


@pytest.fixture
def deal(client, user_factory):
    driver = user_factory("Bound driver", role=UserRole.driver, gender="female")
    passenger = user_factory("Bound passenger", gender="female")
    rid, reqid = seed_route(driver, passenger)
    return driver, passenger, rid, reqid


def offer(client, driver, reqid, rid):
    result = client.post(f"/requests/{reqid}/respond", headers=driver["auth"],
                         json={"ride_id": rid, "price": 450})
    assert result.status_code == 200, result.text
    return result.json()["id"]


def accept(client, passenger, respid):
    return client.post(f"/responses/{respid}/accept", headers=passenger["auth"])


def stored(rid, reqid, respid):
    with Session(engine) as session:
        return (session.get(Ride, rid).seats_left, session.get(RideRequest, reqid).status,
                session.get(RequestResponse, respid).status,
                [b.id for b in session.exec(select(Booking).where(Booking.ride_id == rid)).all()])


def test_bound_response_is_visible_before_accept_and_repeated_accept_returns_the_same_booking(client, deal):
    driver, passenger, rid, reqid = deal
    respid = offer(client, driver, reqid, rid)
    shown = client.get(f"/requests/{reqid}/responses", headers=passenger["auth"])
    assert shown.status_code == 200
    row = next(r for r in shown.json() if r["id"] == respid)
    assert (row["ride_id"], row["pickup_stop"], row["dropoff_stop"]) == (rid, "Ургаза", "Бурибай")
    assert (row["ride_from_city"], row["ride_to_city"]) == ("Сибай", "Акъяр")
    assert row["ride_depart_at"] and "phone" not in row
    with Session(engine) as session:
        before = len(session.exec(select(Ride).where(Ride.driver_id == driver["id"])).all())
    accepted = accept(client, passenger, respid)
    assert accepted.status_code == 200, accepted.text
    bid = accepted.json()["booking_id"]
    again = accept(client, passenger, respid)
    assert again.status_code == 200 and again.json()["booking_id"] == bid, again.text
    assert stored(rid, reqid, respid) == (2, "matched", "accepted", [bid])
    with Session(engine) as session:
        booking, ride, resp = session.get(Booking, bid), session.get(Ride, rid), session.get(RequestResponse, respid)
        assert booking.status == BookingStatus.confirmed
        assert (booking.pickup_stop, booking.dropoff_stop) == ("Ургаза", "Бурибай")
        assert booking.price == 450 and ride.price == 700 and resp.booking_id == bid
        assert len(session.exec(select(Ride).where(Ride.driver_id == driver["id"])).all()) == before


def test_repeat_respond_cannot_silently_rebind_an_offer(client, deal):
    driver, _, rid, reqid = deal
    respid = offer(client, driver, reqid, rid)
    same = client.post(f"/requests/{reqid}/respond", headers=driver["auth"], json={"ride_id": rid})
    assert same.status_code == 200 and same.json()["id"] == respid
    old_path = client.post(f"/requests/{reqid}/respond", headers=driver["auth"], json={})
    assert old_path.status_code == 409


def test_cannot_offer_someone_elses_ride(client, deal, user_factory):
    _, _, rid, reqid = deal
    other_driver = user_factory("Other bound driver", role=UserRole.driver)
    result = client.post(f"/requests/{reqid}/respond", headers=other_driver["auth"], json={"ride_id": rid})
    assert result.status_code == 403


@pytest.mark.parametrize("change", ["route", "depart", "request", "cancelled", "full"])
def test_changed_conditions_leave_request_and_seats_untouched(client, deal, change):
    driver, passenger, rid, reqid = deal
    respid = offer(client, driver, reqid, rid)
    with Session(engine) as session:
        ride, req = session.get(Ride, rid), session.get(RideRequest, reqid)
        if change == "route":
            ride.waypoints = "Бурибай | Ургаза"
        elif change == "depart":
            ride.depart_at += timedelta(minutes=10)
        elif change == "request":
            req.seats = 2
        elif change == "cancelled":
            ride.status = RideStatus.cancelled
        else:
            ride.seats_left = 0
        session.add(ride)
        session.add(req)
        session.commit()
    result = accept(client, passenger, respid)
    assert result.status_code == 409, result.text
    assert stored(rid, reqid, respid) == (0 if change == "full" else 3, "active", "offered", [])


def test_two_accepted_requests_cannot_take_the_last_seat_twice(client, deal, user_factory):
    driver, passenger, rid, reqid = deal
    other_passenger = user_factory("Another bound passenger")
    with Session(engine) as session:
        ride = session.get(Ride, rid)
        ride.seats_left = ride.seats_total = 1
        req = session.get(RideRequest, reqid)
        other = RideRequest(passenger_id=other_passenger["id"], from_city=req.from_city,
                            to_city=req.to_city, desired_at=req.desired_at)
        session.add(ride)
        session.add(other)
        session.commit()
        other_id = other.id
    first = offer(client, driver, reqid, rid)
    second = offer(client, driver, other_id, rid)
    assert accept(client, passenger, first).status_code == 200
    assert accept(client, other_passenger, second).status_code == 409
    assert stored(rid, other_id, second)[0:3] == (0, "active", "offered")
    assert len(stored(rid, other_id, second)[3]) == 1


def test_existing_live_booking_is_not_merged_with_a_different_request(client, deal):
    driver, passenger, rid, reqid = deal
    respid = offer(client, driver, reqid, rid)
    booked = client.post("/bookings", headers=passenger["auth"],
                         json={"ride_id": rid, "pickup_stop": "Сибай", "dropoff_stop": "Акъяр"})
    assert booked.status_code == 200
    result = accept(client, passenger, respid)
    assert result.status_code == 409
    assert stored(rid, reqid, respid) == (2, "active", "offered", [booked.json()["id"]])


def test_failed_conditional_seat_claim_rolls_back_the_request_claim(client, deal, monkeypatch):
    driver, passenger, rid, reqid = deal
    respid = offer(client, driver, reqid, rid)
    real_execute = Session.execute

    def raced(session, statement, *args, **kwargs):
        if isinstance(statement, Update) and statement.table.name == "ride":
            return SimpleNamespace(rowcount=0)
        return real_execute(session, statement, *args, **kwargs)

    monkeypatch.setattr(Session, "execute", raced)
    result = accept(client, passenger, respid)
    assert result.status_code == 409
    assert stored(rid, reqid, respid) == (3, "active", "offered", [])


@pytest.mark.parametrize("guard", ["block", "women_request", "women_ride", "trust_request", "trust_ride"])
def test_common_accept_rechecks_pair_restrictions(client, deal, guard, monkeypatch):
    driver, passenger, rid, reqid = deal
    respid = offer(client, driver, reqid, rid)
    with Session(engine) as session:
        req, ride = session.get(RideRequest, reqid), session.get(Ride, rid)
        if guard == "block":
            session.add(Block(user_id=passenger["id"], blocked_user_id=driver["id"]))
        elif guard == "women_request":
            req.women_only = True
            user = session.get(User, driver["id"])
            user.gender = "male"
            session.add(user)
        elif guard == "women_ride":
            ride.women_only = True
            user = session.get(User, passenger["id"])
            user.gender = "male"
            session.add(user)
        elif guard == "trust_request":
            req.only_trusted = True
        else:
            ride.only_trusted = True
        session.add(req)
        session.add(ride)
        session.commit()
    monkeypatch.setattr(requests_mod, "trust_level", lambda *a: 0)
    # Вызываем именно общее ядро: Telegram/автоподбор не зависят от проверок HTTP-обёртки.
    with Session(engine) as session:
        from fastapi import HTTPException
        with pytest.raises(HTTPException) as refused:
            requests_mod.accept_request_response(session, session.get(RequestResponse, respid))
        assert refused.value.status_code in (403, 409)
    assert stored(rid, reqid, respid) == (3, "active", "offered", [])


def test_duplicate_accept_is_still_private(client, deal, user_factory):
    driver, passenger, rid, reqid = deal
    respid = offer(client, driver, reqid, rid)
    assert accept(client, passenger, respid).status_code == 200
    stranger = user_factory("Not a bound party")
    assert accept(client, stranger, respid).status_code == 403


def test_null_ride_id_keeps_the_existing_request_to_new_ride_flow(client, deal):
    driver, passenger, rid, reqid = deal
    response = client.post(f"/requests/{reqid}/respond", headers=driver["auth"], json={"price": 450})
    assert response.status_code == 200
    result = accept(client, passenger, response.json()["id"])
    assert result.status_code == 200
    with Session(engine) as session:
        booking = session.get(Booking, result.json()["booking_id"])
        assert booking.ride_id != rid and booking.dropoff_stop is None
        assert session.get(Ride, rid).seats_left == 3


@pytest.mark.parametrize("closed", ["cancelled", "old_done", "recent_done"])
def test_accepted_response_respects_booking_privacy_window(client, deal, closed):
    driver, passenger, rid, reqid = deal
    respid = offer(client, driver, reqid, rid)
    bid = accept(client, passenger, respid).json()["booking_id"]
    with Session(engine) as session:
        booking, ride = session.get(Booking, bid), session.get(Ride, rid)
        booking.status = BookingStatus.cancelled if closed == "cancelled" else BookingStatus.done
        ride.depart_at = utcnow() - timedelta(days=30) if closed == "old_done" else utcnow()
        session.add(booking)
        session.add(ride)
        session.commit()
    responses = client.get("/responses/mine", headers=driver["auth"])
    assert responses.status_code == 200, responses.text
    row = next(r for r in responses.json() if r["id"] == respid)
    if closed == "recent_done":
        assert (row["pickup_stop"], row["dropoff_stop"]) == ("Ургаза", "Бурибай")
    else:
        assert row["pickup_stop"] is None and row["dropoff_stop"] is None
    own = client.get(f"/requests/{reqid}/responses", headers=passenger["auth"])
    assert own.status_code == 200, own.text
    own_row = next(r for r in own.json() if r["id"] == respid)
    assert (own_row["pickup_stop"], own_row["dropoff_stop"]) == ("Ургаза", "Бурибай")
