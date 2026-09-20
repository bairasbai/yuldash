"""Короткая история курьера: права, лимиты и доступные финальные действия."""

import pytest
from sqlmodel import Session

from app.db import engine
from app.models import ParcelDelivery


def _seed(sender_id, courier_id, statuses):
    with Session(engine) as session:
        rows = [ParcelDelivery(
            sender_id=sender_id, courier_id=courier_id, status=status,
            from_city="Сибай", to_city="Уфа", receiver_name="Получатель",
            receiver_phone="+79170009999", delivery_price_kop=30000,
        ) for status in statuses]
        session.add_all(rows)
        session.commit()
        return [row.id for row in rows]


def test_recent_history_contains_only_own_finals_and_keeps_active(client, user_factory):
    sender = user_factory("RecentSender")
    courier = user_factory("RecentCourier")
    other = user_factory("RecentOther")
    active = _seed(sender["id"], courier["id"], ["accepted", "in_transit", "returning"])
    finals = _seed(sender["id"], courier["id"], ["delivered", "returned", "canceled"])
    other_rows = _seed(sender["id"], other["id"], ["accepted", "delivered", "returned", "canceled"])

    response = client.get("/parcels/carrying", headers=courier["auth"])
    assert response.status_code == 200, response.text
    assert [row["id"] for row in response.json()] == sorted(active, reverse=True)

    response = client.get("/parcels/carrying?include_recent=true", headers=courier["auth"])
    assert response.status_code == 200, response.text
    assert [row["id"] for row in response.json()] == sorted(active + finals, reverse=True)
    assert {row["status"] for row in response.json()} == {
        "accepted", "in_transit", "returning", "delivered", "returned", "canceled",
    }
    assert not set(other_rows).intersection(row["id"] for row in response.json())

    response = client.get("/parcels/carrying?include_recent=true", headers=sender["auth"])
    assert response.status_code == 200, response.text
    assert response.json() == []  # Отправитель не становится назначенным курьером.


@pytest.mark.parametrize("query,expected", [
    ("", 10), ("&recent_limit=-1", 1), ("&recent_limit=0", 10),
    ("&recent_limit=1", 1), ("&recent_limit=20", 20), ("&recent_limit=999", 20),
])
def test_recent_limit_applies_only_to_finals(client, user_factory, query, expected):
    sender = user_factory("LimitSender")
    courier = user_factory("LimitCourier")
    active = _seed(sender["id"], courier["id"], ["accepted", "in_transit", "returning"])
    finals = _seed(sender["id"], courier["id"], ["delivered"] * 23)

    response = client.get("/parcels/carrying?include_recent=true" + query,
                          headers=courier["auth"])
    assert response.status_code == 200, response.text
    assert [row["id"] for row in response.json()] == sorted(
        active + finals[-expected:], reverse=True,
    )


@pytest.mark.parametrize("role", ["sender", "courier"])
def test_canceled_parcel_has_no_receipt(client, user_factory, role):
    sender = user_factory("CanceledSender")
    courier = user_factory("CanceledCourier")
    parcel_id = _seed(sender["id"], courier["id"], ["canceled"])[0]
    participant = sender if role == "sender" else courier
    response = client.get(f"/parcels/{parcel_id}/receipt", headers=participant["auth"])
    assert response.status_code == 409, response.text
