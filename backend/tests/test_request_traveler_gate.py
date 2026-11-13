"""Origin and compatibility checks for requests for another passenger."""

from concurrent.futures import ThreadPoolExecutor
from threading import Barrier

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, BookingStatus, RequestResponse, Ride, RideRequest, UserRole
from app.routers import requests as requests_mod


def test_request_dedup_keeps_explicit_and_legacy_passenger_origins_separate(client, user_factory):
    owner = user_factory()
    body = {"from_city": "Уфа", "to_city": "Сибай", "for_relative_name": "Тест Пассажир"}
    old = client.post("/requests", json=body, headers=owner["auth"])
    new = client.post("/requests/for-another", json=body, headers=owner["auth"])
    repeat = client.post("/requests/for-another", json=body, headers=owner["auth"])
    assert old.status_code == new.status_code == repeat.status_code == 200
    assert old.json()["id"] != new.json()["id"] == repeat.json()["id"]
    assert "traveler_origin" not in old.json() and "traveler_origin" not in new.json()
    with Session(engine) as session:
        assert session.get(RideRequest, old.json()["id"]).traveler_origin is None
        assert session.get(RideRequest, new.json()["id"]).traveler_origin == "other"


def test_legacy_named_request_cannot_create_a_booking_without_owner_choice(client, user_factory):
    owner = user_factory()
    driver = user_factory(role=UserRole.driver)
    admin = user_factory(role=UserRole.admin)
    with Session(engine) as session:
        request = RideRequest(passenger_id=owner["id"], from_city="Уфа", to_city="Сибай",
                              for_relative_name="Тест Пассажир")
        session.add(request)
        session.flush()
        response = RequestResponse(request_id=request.id, driver_id=driver["id"], price=300)
        session.add(response)
        session.commit()
        request_id, response_id = request.id, response.id

    result = client.post(f"/responses/{response_id}/accept", headers=owner["auth"])
    assert result.status_code == 409
    assert result.json()["detail"]["code"] == "request_traveler_confirmation_required"
    edit = client.patch(f"/requests/{request_id}", headers=admin["auth"], json={})
    assert edit.status_code == 200 and edit.json()["for_relative_name"] is None
    with Session(engine) as session:
        assert session.get(RideRequest, request_id).status == "active"
        row = session.get(RequestResponse, response_id)
        assert row.status == "offered" and row.booking_id is None and row.ride_id is None


@pytest.mark.parametrize("traveler_name", [None, "Другой Пассажир"])
def test_legacy_named_request_uses_only_the_explicit_owner_choice(client, user_factory, traveler_name):
    owner = user_factory()
    driver = user_factory(role=UserRole.driver)
    request = client.post("/requests", headers=owner["auth"], json={
        "from_city": "Уфа", "to_city": "Сибай", "for_relative_name": "Прежнее Имя",
    })
    assert request.status_code == 200, request.text
    response = client.post(f"/requests/{request.json()['id']}/respond", headers=driver["auth"], json={"price": 300})
    assert response.status_code == 200, response.text
    path = f"/responses/{response.json()['id']}/traveler-confirmation"
    offer = client.get(path, headers=owner["auth"])
    assert offer.status_code == 200, offer.text
    assert offer.json()["for_another"] is False
    assert "Прежнее Имя" not in offer.text
    headers = {**owner["auth"], "Idempotency-Key": "legacy-owner-traveler-choice"}
    confirmed = client.post(path, headers=headers, json={
        "version": offer.json()["version"], "for_another": traveler_name is not None,
        "traveler_name": traveler_name,
    })
    assert confirmed.status_code == 200, confirmed.text
    with Session(engine) as session:
        booking = session.get(Booking, confirmed.json()["booking_id"])
        assert booking.passenger_id == owner["id"]
        assert booking.status == BookingStatus.confirmed
        assert booking.traveler_name == traveler_name


def test_concurrent_traveler_confirmations_of_two_offers_commit_only_one_booking(client, user_factory, monkeypatch):
    owner = user_factory()
    drivers = [user_factory(role=UserRole.driver), user_factory(role=UserRole.driver)]
    request = client.post("/requests/for-another", headers=owner["auth"], json={
        "from_city": "Уфа", "to_city": "Сибай", "for_relative_name": "Тест Пассажир",
    })
    assert request.status_code == 200, request.text
    request_id = request.json()["id"]
    offers = []
    for driver, price in zip(drivers, (300, 350), strict=True):
        response = client.post(f"/requests/{request_id}/respond", headers=driver["auth"], json={"price": price})
        assert response.status_code == 200, response.text
        response_id = response.json()["id"]
        path = f"/responses/{response_id}/traveler-confirmation"
        offer = client.get(path, headers=owner["auth"])
        assert offer.status_code == 200, offer.text
        offers.append((response_id, path, offer.json()["version"]))

    ready = Barrier(2)
    # PostgreSQL должен встретить два запроса перед блокировкой заявки.
    # SQLite не блокирует чтение строки: доводим оба запроса до записи.
    step = "_locked_response" if engine.dialect.name == "postgresql" else "_mutate_response"
    original = getattr(requests_mod, step)

    def meet_before_write(*args, **kwargs):
        ready.wait(timeout=10)
        return original(*args, **kwargs)

    def confirm(offer):
        response_id, path, version = offer
        return client.post(path, headers={**owner["auth"], "Idempotency-Key": f"request-traveler-race-{response_id}"},
                           json={"version": version, "for_another": True, "traveler_name": "Тест Пассажир"})

    with monkeypatch.context() as patch, ThreadPoolExecutor(max_workers=2) as pool:
        patch.setattr(requests_mod, step, meet_before_write)
        futures = [pool.submit(confirm, offer) for offer in offers]
        results = [future.result(timeout=20) for future in futures]
    codes = [result.status_code for result in results]
    assert codes.count(200) == 1 and set(codes) <= {200, 400, 409}, [result.text for result in results]
    winner = codes.index(200)
    retry = confirm(offers[winner])
    assert retry.status_code == 200 and retry.json() == results[winner].json()
    with Session(engine) as session:
        assert session.get(RideRequest, request_id).status == "matched"
        bookings = session.exec(select(Booking).where(Booking.passenger_id == owner["id"])).all()
        rides = session.exec(select(Ride).where(Ride.driver_id.in_([driver["id"] for driver in drivers]))).all()
        assert len(bookings) == len(rides) == 1
        booking, ride = bookings[0], rides[0]
        assert booking.id == retry.json()["booking_id"] and booking.ride_id == ride.id
        assert booking.status == BookingStatus.confirmed and booking.traveler_name == "Тест Пассажир"
        assert ride.driver_id == drivers[winner]["id"] and ride.seats_left == 0
        assert booking.price == ride.price == (300, 350)[winner]
        accepted = session.get(RequestResponse, offers[winner][0])
        closed = session.get(RequestResponse, offers[1 - winner][0])
        assert (accepted.status, accepted.booking_id) == ("accepted", booking.id)
        assert (closed.status, closed.booking_id) == ("closed", None)


def test_owner_confirms_once_and_driver_must_consent_to_the_exact_counter_offer(client, user_factory):
    owner = user_factory()
    driver = user_factory(role=UserRole.driver)
    third = user_factory()
    with Session(engine) as session:
        request = RideRequest(passenger_id=owner["id"], from_city="Уфа", to_city="Сибай",
                              for_relative_name="Тест Пассажир", traveler_origin="other")
        session.add(request)
        session.flush()
        response = RequestResponse(request_id=request.id, driver_id=driver["id"], price=350,
                                   current_price=300, last_offer_by="passenger", bargain_rounds=1)
        session.add(response)
        session.commit()
        response_id = response.id

    confirmation = f"/responses/{response_id}/traveler-confirmation"
    consent = f"/responses/{response_id}/traveler-driver-consent"
    offer = client.get(confirmation, headers=owner["auth"])
    assert offer.status_code == 200 and offer.headers["Cache-Control"] == "no-store"
    assert client.get(confirmation, headers=third["auth"]).status_code == 403
    body = {"version": offer.json()["version"], "for_another": True, "traveler_name": "Тест Пассажир"}
    headers = {**owner["auth"], "Idempotency-Key": "test-traveler-confirmation"}
    no_consent = client.post(confirmation, headers=headers, json=body)
    assert no_consent.status_code == 409
    assert no_consent.json()["detail"]["code"] == "request_driver_consent_required"
    old_consent = client.post(f"/responses/{response_id}/accept", headers=driver["auth"])
    assert old_consent.status_code == 409
    assert client.post(consent, headers=driver["auth"], json={"version": "0" * 64}).status_code == 409
    assert client.post(consent, headers=driver["auth"], json={"version": body["version"]}).json() == {
        "state": "waiting_for_owner",
    }
    waiting = client.get(confirmation, headers=owner["auth"]).json()
    assert waiting["state"] == "waiting_for_owner"
    with Session(engine) as session:
        request_id = session.get(RequestResponse, response_id).request_id
    for comment in ("changed", ""):
        result = client.patch(f"/requests/{request_id}", headers=owner["auth"], json={"comment": comment})
        assert result.status_code == 200, result.text
    changed = client.get(confirmation, headers=owner["auth"]).json()
    assert changed["version"] != body["version"]
    assert changed["state"] == "offered" and not changed["driver_consented"]
    assert client.post(confirmation, headers=headers, json=body).json()["detail"]["code"] == "request_offer_changed"
    body["version"] = changed["version"]
    assert client.post(consent, headers=driver["auth"], json={"version": body["version"]}).status_code == 200
    assert client.post(confirmation, headers={**driver["auth"], "Idempotency-Key": "test-driver-forbidden"},
                       json=body).status_code == 403
    invalid_minor = client.post(confirmation, headers=headers, json={**body, "minor_passenger": True})
    assert invalid_minor.status_code == 400
    created = client.post(confirmation, headers=headers, json=body)
    assert created.status_code == 200, created.text
    assert client.post(confirmation, headers=headers, json=body).json() == created.json()
    assert client.post(confirmation, headers=headers, json={**body, "traveler_name": "Другой Пассажир"}).status_code == 409
    assert client.get(confirmation, headers=owner["auth"]).json() == {"state": "accepted", **created.json()}
    with Session(engine) as session:
        saved = session.get(Booking, created.json()["booking_id"])
        assert saved.passenger_id == owner["id"] and saved.traveler_name == body["traveler_name"]
        assert session.get(Ride, saved.ride_id).seats_left == 0
