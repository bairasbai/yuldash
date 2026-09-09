"""Rejected pickup evidence must not silently assign the delivery to a courier."""
from conftest import upload_evidence
from test_parcels import _create_parcel
from test_courier import _make_courier, _order
from app.config import settings
from app.db import engine
from app.models import ParcelDelivery
from app.timeutil import utcnow
from sqlmodel import Session
from datetime import timedelta
import pytest


def test_rejected_evidence_leaves_parcel_available_to_another_carrier(client, user_factory):
    sender, first, second, photo_owner = [user_factory() for _ in range(4)]
    photo = upload_evidence(client, photo_owner["auth"])
    created = _create_parcel(client, sender)
    assert created.status_code == 200, created.text
    pid = created.json()["id"]
    rejected = client.post(f"/parcels/{pid}/accept", headers=first["auth"],
                           json={"pickup_photo_url": photo})
    assert rejected.status_code == 403, rejected.text
    mine = client.get("/parcels/mine", headers=sender["auth"])
    assert mine.status_code == 200, mine.text
    parcel = next(p for p in mine.json() if p["id"] == pid)
    assert parcel["status"] == "created", parcel
    accepted = client.post(f"/parcels/{pid}/accept", headers=second["auth"])
    assert accepted.status_code == 200, accepted.text
    assert accepted.json()["courier_id"] == second["id"]


@pytest.mark.parametrize("target,photo_field", [("in_transit", "pickup_photo_url"),
                                               ("delivered", "delivery_photo_url")])
def test_rejected_status_photo_does_not_stop_paid_waiting(client, user_factory, monkeypatch,
                                                       target, photo_field):
    monkeypatch.setattr(settings, "courier_enabled", True)
    courier = _make_courier(client, user_factory)
    sender, owner = user_factory(), user_factory()
    photo = upload_evidence(client, owner["auth"])
    created = _order(client, sender)
    assert created.status_code == 200, created.text
    pid, code = created.json()["id"], created.json()["confirm_code"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    if target == "delivered":
        assert client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                           json={"status": "in_transit"}).status_code == 200
    with Session(engine) as session:
        parcel = session.get(ParcelDelivery, pid)
        parcel.waiting_started_at = utcnow() - timedelta(minutes=8)
        session.add(parcel)
        session.commit()
    result = client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                         json={"status": target, "code": code, photo_field: photo})
    assert result.status_code == 403, result.text
    with Session(engine) as session:
        parcel = session.get(ParcelDelivery, pid)
        assert parcel.waiting_started_at is not None
        assert parcel.status == ("accepted" if target == "in_transit" else "in_transit")
