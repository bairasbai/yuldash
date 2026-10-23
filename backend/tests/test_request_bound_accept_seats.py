# -*- coding: utf-8 -*-
"""Принятие «своего рейса»: места списываются условием внутри UPDATE (ревью PR190, 23.10.2026).

Проверка `Ride.seats_left >= req.seats` в UPDATE мест — последняя линия против гонки: между
чтением рейса и списанием места мог занять другой пассажир. Прежние тесты подменяли сам UPDATE
(`rowcount=0`) и условие не проверяли: убери его — они зелёные. Здесь настоящий UPDATE по
настоящей базе: «чужая бронь» уже забрала последнее место, а проверка свободных мест в
`request_segments.segment` (она стоит РАНЬШЕ и ловит это в обычном случае) намеренно слепа —
так воспроизводится именно гонка между проверкой и списанием.
"""
import pytest
from sqlmodel import Session, select

from app import request_segments
from app.db import engine
from app.models import Booking, RequestResponse, Ride, RideRequest, UserRole
from test_booking_segments import seed_route


@pytest.fixture
def deal(client, user_factory):
    driver = user_factory("Seats driver", role=UserRole.driver, gender="female")
    passenger = user_factory("Seats passenger", gender="female")
    rid, reqid = seed_route(driver, passenger)
    return driver, passenger, rid, reqid


def _stored(rid, reqid, respid):
    with Session(engine) as session:
        return (session.get(Ride, rid).seats_left, session.get(RideRequest, reqid).status,
                session.get(RequestResponse, respid).status,
                [b.id for b in session.exec(select(Booking).where(Booking.ride_id == rid)).all()])


def test_last_seat_taken_between_check_and_update_refuses_accept_and_rolls_back(client, deal, monkeypatch):
    driver, passenger, rid, reqid = deal
    offered = client.post(f"/requests/{reqid}/respond", headers=driver["auth"], json={"ride_id": rid, "price": 450})
    assert offered.status_code == 200, offered.text
    respid = offered.json()["id"]
    # Пока предложение висело, рейс заполнили (в обход приложения: нужен именно момент гонки).
    with Session(engine) as session:
        ride = session.get(Ride, rid)
        ride.seats_left = 0
        session.add(ride)
        session.commit()

    real = request_segments.segment

    def blind_to_free_seats(ride, req, entry=None):
        """Как если бы проверка мест прошла ДО чужой брони: участок тот же, мест «хватает»."""
        return real(ride.model_copy(update={"seats_left": req.seats}), req, entry)

    monkeypatch.setattr(request_segments, "segment", blind_to_free_seats)
    refused = client.post(f"/responses/{respid}/accept", headers=passenger["auth"])
    assert refused.status_code == 409, refused.text
    assert refused.json()["detail"]["ru"] and refused.json()["detail"]["ba"]
    # Откат целиком: места не ушли в минус, заявка снова активна, бронь не создана.
    assert _stored(rid, reqid, respid) == (0, "active", "offered", [])


def test_accept_with_a_free_seat_still_books_and_takes_exactly_one_seat(client, deal):
    """Обратная сторона: при свободном месте принятие работает и списывает ровно req.seats."""
    driver, passenger, rid, reqid = deal
    offered = client.post(f"/requests/{reqid}/respond", headers=driver["auth"], json={"ride_id": rid, "price": 450})
    respid = offered.json()["id"]
    accepted = client.post(f"/responses/{respid}/accept", headers=passenger["auth"])
    assert accepted.status_code == 200, accepted.text
    seats, request_status, response_status, bookings = _stored(rid, reqid, respid)
    assert (seats, request_status, response_status, bookings) == (2, "matched", "accepted", [accepted.json()["booking_id"]])
