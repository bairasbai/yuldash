"""A replacement courier must not inherit the previous courier's approach or clock."""
from datetime import timedelta

import pytest
from sqlalchemy import event
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import CourierProfile, ParcelDelivery, UserRole
from app.timeutil import utcnow
from test_courier_c4 import _make_courier, _order


@pytest.fixture(autouse=True)
def courier_enabled(monkeypatch):
    monkeypatch.setattr(settings, "courier_enabled", True)


def _city(user_id, city):
    with Session(engine) as session:
        profile = session.exec(select(CourierProfile).where(
            CourierProfile.user_id == user_id)).one()
        profile.work_city = city
        session.add(profile)
        session.commit()


def _parcel(parcel_id):
    with Session(engine) as session:
        return session.get(ParcelDelivery, parcel_id)


@pytest.mark.parametrize("release_by", ["courier", "admin", "timeout"])
def test_replacement_does_not_charge_previous_approach_or_wait(
        client, user_factory, release_by):
    sender = user_factory("Assignment sender")
    first = _make_courier(client, user_factory, name="Distant courier")
    second = _make_courier(client, user_factory, name="Local courier")
    _city(first["id"], "Стерлитамак")
    _city(second["id"], "Уфа")
    response = _order(client, sender)
    assert response.status_code == 200, response.text
    parcel_id = response.json()["id"]
    base_price = response.json()["price_kop"]
    assert client.post(f"/parcels/{parcel_id}/accept", headers=first["auth"]).status_code == 200
    assert _parcel(parcel_id).pickup_fee_kop > 0
    assert client.post(f"/parcels/{parcel_id}/arrived", headers=first["auth"]).status_code == 200
    with Session(engine) as session:
        parcel = session.get(ParcelDelivery, parcel_id)
        parcel.waiting_started_at = utcnow() - timedelta(minutes=20)
        if release_by == "timeout":
            parcel.created_at = parcel.accepted_at = utcnow() - timedelta(
                hours=settings.parcel_stuck_hours + 1)
        session.add(parcel)
        session.commit()

    if release_by == "timeout":
        from app.taxi_worker import close_stuck_parcels
        with Session(engine) as session:
            assert parcel_id in close_stuck_parcels(session)
    else:
        actor = first if release_by == "courier" else user_factory("Assignment admin", role=UserRole.admin)
        endpoint = (f"/parcels/{parcel_id}/release" if release_by == "courier"
                    else f"/admin/parcels/{parcel_id}/release-courier")
        released = client.post(endpoint, headers=actor["auth"])
        assert released.status_code == 200, released.text

    released = _parcel(parcel_id)
    assert released.waiting_started_at is None, "the old courier's clock is still running"
    assert released.pickup_fee_kop == 0
    assert released.pickup_km == 0
    assert released.pickup_pending is True
    assert released.delivery_price_kop == base_price
    accepted = client.post(f"/parcels/{parcel_id}/accept", headers=second["auth"])
    assert accepted.status_code == 200, accepted.text
    assert client.post(f"/parcels/{parcel_id}/status", headers=second["auth"],
                       json={"status": "in_transit"}).status_code == 200
    delivered = client.post(f"/parcels/{parcel_id}/status", headers=second["auth"],
                            json={"status": "delivered", "code": response.json()["confirm_code"]})
    assert delivered.status_code == 200, delivered.text
    final = _parcel(parcel_id)
    assert final.delivery_price_kop == base_price
    assert final.pickup_fee_kop == 0
    assert final.waiting_sender_kop == 0
    assert final.waiting_receiver_kop == 0


def test_replacement_approach_is_recalculated_once(client, user_factory):
    sender = user_factory("New approach sender")
    first = _make_courier(client, user_factory, name="First local courier")
    second = _make_courier(client, user_factory, name="New distant courier")
    _city(first["id"], "Уфа")
    _city(second["id"], "Стерлитамак")
    response = _order(client, sender)
    assert response.status_code == 200, response.text
    parcel_id = response.json()["id"]
    base_price = response.json()["price_kop"]
    assert client.post(f"/parcels/{parcel_id}/accept", headers=first["auth"]).status_code == 200
    assert _parcel(parcel_id).pickup_fee_kop == 0
    assert client.post(f"/parcels/{parcel_id}/release", headers=first["auth"]).status_code == 200
    assert client.post(f"/parcels/{parcel_id}/accept", headers=second["auth"]).status_code == 200
    assigned = _parcel(parcel_id)
    assert assigned.pickup_fee_kop > 0, "new courier's approach was never calculated"
    assert assigned.delivery_price_kop == base_price + assigned.pickup_fee_kop
    assert client.post(f"/parcels/{parcel_id}/accept", headers=second["auth"]).status_code == 409
    assert _parcel(parcel_id).delivery_price_kop == assigned.delivery_price_kop


@pytest.mark.parametrize("release_by", ["admin", "timeout"])
def test_purchased_goods_cannot_be_reassigned(client, user_factory, release_by):
    sender = user_factory("Purchased goods sender")
    courier = _make_courier(client, user_factory, name="Purchasing courier")
    response = _order(client, sender, delivery_type="buy_bring", cod_amount_kop=200_000)
    assert response.status_code == 200, response.text
    parcel_id = response.json()["id"]
    assert client.post(f"/parcels/{parcel_id}/accept", headers=courier["auth"]).status_code == 200
    assert client.post(f"/courier/orders/{parcel_id}/goods-cost", headers=courier["auth"],
                       json={"actual_kop": 180_000}).status_code == 200
    if release_by == "timeout":
        from app.taxi_worker import close_stuck_parcels
        with Session(engine) as session:
            parcel = session.get(ParcelDelivery, parcel_id)
            parcel.created_at = parcel.accepted_at = utcnow() - timedelta(
                hours=settings.parcel_stuck_hours + 1)
            session.add(parcel)
            session.commit()
            assert parcel_id not in close_stuck_parcels(session)
    else:
        admin = user_factory("Purchased goods admin", role=UserRole.admin)
        released = client.post(f"/admin/parcels/{parcel_id}/release-courier", headers=admin["auth"])
        assert released.status_code == 409, released.text
    parcel = _parcel(parcel_id)
    assert parcel.status == "accepted"
    assert parcel.courier_id == courier["id"]
    assert parcel.goods_actual_kop == 180_000


@pytest.mark.parametrize("delivery_type", ["courier", "buy_bring"])
def test_reset_preserves_recorded_money(delivery_type):
    from app.routers.courier import reset_uncollected_courier_approach

    parcel = ParcelDelivery(
        sender_id=1, courier_id=2, status="accepted", delivery_type=delivery_type,
        delivery_price_kop=51_000, pickup_fee_kop=10_000, pickup_km=15,
        distance_km=20, waiting_sender_kop=700, waiting_receiver_kop=300,
        waiting_started_at=utcnow(), weather_fee_kop=500,
        goods_actual_kop=180_000 if delivery_type == "buy_bring" else 0,
        commission_kop=1_200, cancel_fee_kop=2_000, return_fee_kop=3_000,
    )
    for _ in range(2):  # повтор сброса не вычитает дорогу второй раз
        reset_uncollected_courier_approach(parcel)
        assert parcel.delivery_price_kop == 41_000
        assert parcel.waiting_sender_kop == 700
        assert parcel.waiting_receiver_kop == 300
        assert parcel.weather_fee_kop == 500
        assert parcel.goods_actual_kop == (180_000 if delivery_type == "buy_bring" else 0)
        assert parcel.commission_kop == 1_200
        assert parcel.cancel_fee_kop == 2_000
        assert parcel.return_fee_kop == 3_000
        assert parcel.waiting_started_at is None
        assert parcel.pickup_fee_kop == parcel.pickup_km == 0
        assert parcel.pickup_pending is True


def test_goods_cost_locks_assignment_before_checking_owner(client, user_factory):
    """SQLite проверяет запрос блокировки, не PostgreSQL-сериализацию двух транзакций."""
    sender = user_factory("Locked purchase sender")
    courier = _make_courier(client, user_factory, name="Locked purchasing courier")
    response = _order(client, sender, delivery_type="buy_bring", cod_amount_kop=200_000)
    assert response.status_code == 200, response.text
    parcel_id = response.json()["id"]
    assert client.post(f"/parcels/{parcel_id}/accept", headers=courier["auth"]).status_code == 200
    reads = []

    def capture(state):
        if state.is_select and any(getattr(table, "name", None) == ParcelDelivery.__tablename__
                                   for table in state.statement.get_final_froms()):
            reads.append(state.statement._for_update_arg is not None)

    event.listen(Session, "do_orm_execute", capture)
    try:
        result = client.post(f"/courier/orders/{parcel_id}/goods-cost", headers=courier["auth"],
                             json={"actual_kop": 180_000})
    finally:
        event.remove(Session, "do_orm_execute", capture)
    assert result.status_code == 200, result.text
    assert reads and reads[0], "owner/status must be read under the same lock as reassignment"
