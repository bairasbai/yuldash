"""Восстановление собственного голоса без раскрытия оценки второй стороны."""
from datetime import timedelta

import pytest
from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.models import ParcelDelivery, SafetyProfile
from app.timeutil import utcnow
from test_courier_c3 import _deliver, _make_courier


@pytest.fixture
def delivered(client, user_factory, monkeypatch):
    monkeypatch.setattr(settings, "courier_enabled", True)
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="Отправитель")
    pid, _ = _deliver(client, courier, sender)
    return pid, sender, courier


@pytest.mark.parametrize("actor", [1, 2])
def test_own_rating_is_null_until_this_participant_rates(client, delivered, actor):
    pid, sender, courier = delivered
    other = courier if actor == 1 else sender
    assert client.post(f"/parcels/{pid}/rate", headers=other["auth"], json={"stars": 2}).status_code == 200
    response = client.get(f"/parcels/{pid}/my-rating", headers=delivered[actor]["auth"])
    assert response.status_code == 200, response.text
    assert response.json() == {"stars": None}


def test_mutual_ratings_survive_new_requests_and_duplicate_write(client, delivered):
    pid, sender, courier = delivered
    for participant, stars in [(sender, 5), (courier, 2)]:
        assert client.post(f"/parcels/{pid}/rate", headers=participant["auth"], json={"stars": stars}).status_code == 200
    # Each request opens its own database session; no client-local state is reused.
    for _ in range(2):
        for participant, stars in [(sender, 5), (courier, 2)]:
            response = client.get(f"/parcels/{pid}/my-rating", headers=participant["auth"])
            assert response.status_code == 200, response.text
            assert response.json() == {"stars": stars}
    assert client.post(f"/parcels/{pid}/rate", headers=sender["auth"], json={"stars": 1}).status_code == 409
    assert client.get(f"/parcels/{pid}/my-rating", headers=sender["auth"]).json() == {"stars": 5}


def test_stranger_and_missing_parcel_have_same_private_response(client, user_factory, delivered):
    pid, sender, _ = delivered
    assert client.post(f"/parcels/{pid}/rate", headers=sender["auth"], json={"stars": 5}).status_code == 200
    stranger = user_factory()
    found = client.get(f"/parcels/{pid}/my-rating", headers=stranger["auth"])
    missing = client.get("/parcels/999999999/my-rating", headers=stranger["auth"])
    assert found.status_code == missing.status_code == 404
    assert found.json() == missing.json()
    assert "ru" in found.json()["detail"] and "ba" in found.json()["detail"]


def test_anonymous_cannot_read_rating(client, delivered):
    response = client.get(f"/parcels/{delivered[0]}/my-rating")
    assert response.status_code == 401


@pytest.mark.parametrize("actor", [1, 2])
def test_reading_old_rating_allowed_during_account_pause(client, delivered, actor):
    pid = delivered[0]
    participant = delivered[actor]
    assert client.post(f"/parcels/{pid}/rate", headers=participant["auth"], json={"stars": 4}).status_code == 200
    with Session(engine) as session:
        session.add(SafetyProfile(user_id=participant["id"], suspended_until=utcnow() + timedelta(days=7)))
        parcel = session.get(ParcelDelivery, pid)
        parcel.created_at = parcel.delivered_at = utcnow() - timedelta(days=90)
        session.add(parcel)
        session.commit()
    response = client.get(f"/parcels/{pid}/my-rating", headers=participant["auth"])
    assert response.status_code == 200, response.text
    assert response.json() == {"stars": 4}
