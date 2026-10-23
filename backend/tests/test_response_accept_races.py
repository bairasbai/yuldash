"""Между чтением и записью другая сторона успела принять/сменить цену: старый запрос не побеждает."""
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier

import pytest
from fastapi import HTTPException
from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, BookingStatus, RequestResponse, Ride, RideRequest, User, UserRole
from app.routers import requests as requests_mod
from test_booking_segments import seed_route


def deal(client, user_factory, *, bound=True):
    driver = user_factory("Race bound driver", role=UserRole.driver)
    passenger = user_factory("Race bound passenger")
    rid, reqid = seed_route(driver, passenger)
    body = {"price": 450, **({"ride_id": rid} if bound else {})}
    response = client.post(f"/requests/{reqid}/respond", headers=driver["auth"], json=body)
    assert response.status_code == 200, response.text
    return driver, passenger, rid, reqid, response.json()["id"]


def assert_state(rid, reqid, respid, *, status, seats, price=None):
    with Session(engine) as session:
        response = session.get(RequestResponse, respid)
        assert response.status == status
        assert session.get(Ride, rid).seats_left == seats
        assert session.get(Ride, rid).price == 700
        req = session.get(RideRequest, reqid)
        bookings = session.exec(select(Booking).where(Booking.passenger_id == req.passenger_id)).all()
        if status == "accepted":
            assert req.status == "matched" and len(bookings) == 1
            assert bookings[0].id == response.booking_id and bookings[0].status == BookingStatus.confirmed
            if price is not None:
                assert bookings[0].price == price
        else:
            assert req.status == "active" and not bookings


@pytest.mark.parametrize("bound", [True, False])
def test_preloaded_accept_cannot_accept_the_passengers_own_counter(client, user_factory, bound):
    driver, passenger, rid, reqid, respid = deal(client, user_factory, bound=bound)
    # Две независимые сессии: accept уже прочитал старое предложение, counter успел закоммититься.
    with Session(engine) as stale:
        old = stale.get(RequestResponse, respid)
        actor = stale.get(User, passenger["id"])
        counter = client.post(f"/responses/{respid}/counter", headers=passenger["auth"], json={"price": 300})
        assert counter.status_code == 200, counter.text
        with pytest.raises(HTTPException) as refused:
            requests_mod.accept_request_response(stale, old, actor=actor)
        assert refused.value.status_code == 409
    assert_state(rid, reqid, respid, status="offered", seats=3)
    # Теперь принять встречную цену вправе водитель — правильный ход остаётся рабочим.
    accepted = client.post(f"/responses/{respid}/accept", headers=driver["auth"])
    assert accepted.status_code == 200, accepted.text
    assert_state(rid, reqid, respid, status="accepted", seats=2 if bound else 3, price=300)


@pytest.mark.parametrize("mutation", [
    {"status": "declined"}, {"status": "withdrawn"},
    {"current_price": 300, "last_offer_by": "passenger", "bargain_rounds": 1},
])
def test_stale_response_write_cannot_overwrite_an_accepted_booking(client, user_factory, mutation):
    driver, passenger, rid, reqid, respid = deal(client, user_factory)
    with Session(engine) as stale:
        old = stale.get(RequestResponse, respid)
        assert old.status == "offered"
        accepted = client.post(f"/responses/{respid}/accept", headers=passenger["auth"])
        assert accepted.status_code == 200, accepted.text
        # Это та же единственная точка записи, которой пользуются HTTP counter/decline/withdraw.
        # Проверяем условие в настоящей БД со СТАРЫМ объектом, а не последующий Python if.
        with pytest.raises(HTTPException) as refused:
            requests_mod._mutate_response(stale, old, mutation)
        assert refused.value.status_code == 409
    assert_state(rid, reqid, respid, status="accepted", seats=2, price=450)
    for path, person, data in (("counter", passenger, {"price": 300}), ("decline", passenger, None)):
        result = client.post(f"/responses/{respid}/{path}", headers=person["auth"], json=data)
        assert result.status_code == 409
    assert client.delete(f"/responses/{respid}", headers=driver["auth"]).status_code == 409


@pytest.mark.parametrize("action", ["counter", "decline", "withdraw", "accept"])
def test_concurrent_mutations_keep_booking_and_response_consistent(client, user_factory, action):
    driver, passenger, rid, reqid, respid = deal(client, user_factory)
    ready = Barrier(2)

    def attempt(which):
        ready.wait(timeout=10)
        if which == "accept":
            return client.post(f"/responses/{respid}/accept", headers=passenger["auth"]).status_code
        if which == "withdraw":
            return client.delete(f"/responses/{respid}", headers=driver["auth"]).status_code
        return client.post(f"/responses/{respid}/{which}", headers=passenger["auth"],
                           json={"price": 300} if which == "counter" else None).status_code

    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(attempt, which) for which in ("accept", action)]
        codes = [future.result(timeout=20) for future in futures]
    assert all(code in (200, 400, 409) for code in codes), codes
    assert 200 in codes, codes
    with Session(engine) as session:
        response = session.get(RequestResponse, respid)
        status = response.status
        if status == "offered":
            assert action == "counter" and response.last_offer_by == "passenger"
    assert_state(rid, reqid, respid, status=status, seats=2 if status == "accepted" else 3,
                 price=450 if status == "accepted" else None)
