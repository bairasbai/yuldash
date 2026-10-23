"""Отзыв ссылки сходится к закрытому доступу после потерянного ответа.

Первый DELETE действительно проходит через HTTPX/TestClient к FastAPI и БД.
Транспорт отбрасывает только уже успешный ответ: удаление проверяется до повтора.
Подготовка поездки синтетическая; внешние SMS здесь выключены явно.
"""
from datetime import timedelta

import httpx
import pytest
from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, Ride, RideStatus, TripShare, UserRole
from app.routers import family
from app.timeutil import utcnow


def _trip(driver_id, passenger_id):
    with Session(engine) as session:
        ride = Ride(driver_id=driver_id, from_city="Баймак", to_city="Сибай",
                    depart_at=utcnow() + timedelta(hours=2), seats_total=3,
                    seats_left=2, price=300, status=RideStatus.active)
        session.add(ride)
        session.commit()
        session.refresh(ride)
        booking = Booking(ride_id=ride.id, passenger_id=passenger_id, seats=1,
                          price=300, status=BookingStatus.confirmed)
        session.add(booking)
        session.commit()
        session.refresh(booking)
        return booking.id


@pytest.fixture
def shared_trip(client, user_factory, monkeypatch):
    monkeypatch.setattr(family, "send_text", lambda *args, **kwargs: None)
    driver = user_factory("RevokeRetryDriver", role=UserRole.driver)
    passenger = user_factory("RevokeRetryPassenger")
    outsider = user_factory("RevokeRetryOutsider")
    booking_id = _trip(driver["id"], passenger["id"])
    contact = client.post("/trusted-contacts", headers=passenger["auth"], json={
        "name": "Мама", "relation": "мама", "phone": "+79170000991",
    })
    assert contact.status_code == 200, contact.text
    response = client.post(f"/bookings/{booking_id}/share", headers=passenger["auth"],
                           json={"contact_id": contact.json()["id"]})
    assert response.status_code == 200, response.text
    return {"driver": driver, "passenger": passenger, "outsider": outsider,
            "booking_id": booking_id, "share": response.json()}


class _LoseFirstSuccessfulDelete(httpx.BaseTransport):
    def __init__(self, delegate, path):
        self.delegate = delegate
        self.path = path
        self.deleted_statuses = []
        self.lost = False

    def handle_request(self, request):
        response = self.delegate.handle_request(request)
        if request.method == "DELETE" and request.url.path == self.path:
            self.deleted_statuses.append(response.status_code)
            if not self.lost and response.status_code == 200:
                response.read()
                response.close()
                self.lost = True
                raise httpx.ReadError("Successful DELETE response lost", request=request)
        return response


def test_lost_successful_revoke_response_retry_converges_to_no_link(client, shared_trip, monkeypatch):
    case = shared_trip
    booking_id, share = case["booking_id"], case["share"]
    path = f"/bookings/{booking_id}/share/{share['id']}"
    transport = _LoseFirstSuccessfulDelete(client._transport, path)
    monkeypatch.setattr(client, "_transport", transport)

    with pytest.raises(httpx.ReadError, match="Successful DELETE response lost"):
        client.delete(path, headers=case["passenger"]["auth"])
    assert transport.deleted_statuses == [200]
    with Session(engine) as session:
        assert session.get(TripShare, share["id"]) is None
    refresh = client.get(f"/bookings/{booking_id}/shares", headers=case["passenger"]["auth"])
    assert refresh.status_code == 200 and refresh.json() == []
    assert client.get(f"/t/{share['token']}").status_code == 404

    retry = client.delete(path, headers=case["passenger"]["auth"])
    assert retry.status_code == 200, retry.text
    assert retry.json()["ok"] is True
    assert transport.deleted_statuses == [200, 200]
    assert client.get(f"/bookings/{booking_id}/shares", headers=case["passenger"]["auth"]).json() == []
    assert client.get(f"/t/{share['token']}").status_code == 404


@pytest.mark.parametrize("actor", ["driver", "outsider"])
def test_existing_share_does_not_bypass_booking_passenger_gate(client, shared_trip, actor):
    case = shared_trip
    path = f"/bookings/{case['booking_id']}/share/{case['share']['id']}"
    forbidden = client.delete(path, headers=case[actor]["auth"])
    assert forbidden.status_code in (403, 404)
    with Session(engine) as session:
        assert session.get(TripShare, case["share"]["id"]) is not None


@pytest.mark.parametrize("actor", ["driver", "outsider"])
def test_missing_share_does_not_bypass_booking_passenger_gate(client, shared_trip, actor):
    case = shared_trip
    path = f"/bookings/{case['booking_id']}/share/{case['share']['id']}"
    assert client.delete(path, headers=case["passenger"]["auth"]).status_code == 200
    forbidden = client.delete(path, headers=case[actor]["auth"])
    assert forbidden.status_code in (403, 404)


def test_share_of_another_booking_is_not_silently_revoked(client, shared_trip):
    case = shared_trip
    own_other_booking = _trip(case["driver"]["id"], case["passenger"]["id"])
    wrong_path = f"/bookings/{own_other_booking}/share/{case['share']['id']}"
    result = client.delete(wrong_path, headers=case["passenger"]["auth"])
    assert result.status_code == 404
    with Session(engine) as session:
        assert session.get(TripShare, case["share"]["id"]) is not None
    listed = client.get(f"/bookings/{case['booking_id']}/shares", headers=case["passenger"]["auth"])
    assert [row["id"] for row in listed.json()] == [case["share"]["id"]]


def test_nonexistent_booking_does_not_accept_missing_share(client, shared_trip):
    case = shared_trip
    missing_booking_id = case["booking_id"] + 10_000_000
    result = client.delete(f"/bookings/{missing_booking_id}/share/{case['share']['id']}",
                           headers=case["passenger"]["auth"])
    assert result.status_code == 404
    with Session(engine) as session:
        assert session.get(TripShare, case["share"]["id"]) is not None


def test_own_booking_does_not_authorize_revoking_another_passengers_existing_share(client, shared_trip):
    case = shared_trip
    foreign_booking_id = _trip(case["driver"]["id"], case["outsider"]["id"])
    contact = client.post("/trusted-contacts", headers=case["outsider"]["auth"], json={
        "name": "Брат", "relation": "брат", "phone": "+79170000992",
    })
    assert contact.status_code == 200, contact.text
    created = client.post(f"/bookings/{foreign_booking_id}/share", headers=case["outsider"]["auth"],
                          json={"contact_id": contact.json()["id"]})
    assert created.status_code == 200, created.text
    foreign_share = created.json()

    result = client.delete(f"/bookings/{case['booking_id']}/share/{foreign_share['id']}",
                           headers=case["passenger"]["auth"])
    assert result.status_code == 404
    with Session(engine) as session:
        assert session.get(TripShare, foreign_share["id"]) is not None
        assert session.get(TripShare, case["share"]["id"]) is not None
