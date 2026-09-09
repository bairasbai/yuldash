"""BE29: раздел «Мои данные» не должен отрицать сохранённые SOS-координаты."""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app.db import engine
from app import cleanup
from app.models import Booking, InstantOrder, Ride, SosEvent, UserRole
from app.timeutil import utcnow

from test_api import _ride


_LAT = 52.591234
_LNG = 58.317654
_MAP_LINK = f"https://yandex.ru/maps/?pt={_LNG},{_LAT}&z=16"


def _create_real_roadside_event(client, user_factory, tag: str):
    """Пройти пользовательский HTTP-сценарий и доказать запись точных координат."""
    driver = user_factory(f"{tag}Driver", role=UserRole.driver)
    passenger = user_factory(f"{tag}Passenger")
    ride_id = _ride(client, driver, seats=2, comment=tag)
    booking = client.post(
        "/bookings",
        headers=passenger["auth"],
        json={"ride_id": ride_id, "seats": 1},
    )
    assert booking.status_code == 200, booking.text
    booking_id = booking.json()["id"]

    roadside = client.post(
        f"/bookings/{booking_id}/stuck",
        headers=passenger["auth"],
        json={"lat": _LAT, "lng": _LNG, "note": "машина заглохла"},
    )
    assert roadside.status_code == 200, roadside.text

    with Session(engine) as session:
        event = session.exec(
            select(SosEvent)
            .where(SosEvent.user_id == passenger["id"])
            .order_by(SosEvent.id.desc())
        ).first()
        assert event is not None, "roadside endpoint не сохранил SosEvent"
        assert _MAP_LINK in (event.note or ""), (
            "roadside endpoint не сохранил точные координаты в карте: "
            f"{event.note!r}"
        )

    return passenger


def test_me_data_reports_location_after_real_roadside_event(client, user_factory):
    passenger = _create_real_roadside_event(client, user_factory, "Be29Data")

    response = client.get("/me/data", headers=passenger["auth"])
    assert response.status_code == 200, response.text
    data = response.json()
    assert data["location_stored"] is True
    assert data["live_location_history_stored"] is False
    assert data["route_location_points"] == 0
    assert data["route_location_points_days"] == cleanup.TRIP_DAYS
    assert data["sos_location_events"] == 1
    assert data["open_sos_location_events"] == 1
    assert data["sos_location_days_from_signal"] == cleanup.SOS_DAYS


def test_route_points_count_each_saved_point_once(client, user_factory):
    owner = user_factory("Be29RouteOwner")
    other = user_factory("Be29RouteOther", role=UserRole.driver)
    with Session(engine) as session:
        # Одна и та же Ride связана с owner и как водитель, и как пассажир.
        ride = Ride(
            driver_id=owner["id"], from_city="Баймак", to_city="Сибай",
            depart_at=utcnow() + timedelta(hours=1), pickup_lat=52.59, pickup_lng=58.31,
        )
        session.add(ride)
        session.commit()
        session.refresh(ride)
        session.add(Booking(ride_id=ride.id, passenger_id=owner["id"], seats=1, price=300))
        # Пассажиру принадлежат две точки первого заказа, водителю — две точки второго.
        session.add(InstantOrder(
            passenger_id=owner["id"], driver_id=other["id"],
            from_lat=52.1, from_lng=58.1, to_lat=52.2, to_lng=58.2,
        ))
        session.add(InstantOrder(
            passenger_id=other["id"], driver_id=owner["id"],
            from_lat=53.1, from_lng=59.1, to_lat=53.2, to_lng=59.2,
        ))
        session.commit()

    data = client.get("/me/data", headers=owner["auth"]).json()
    assert data["route_location_points"] == 5
    assert data["location_stored"] is True


@pytest.mark.parametrize(
    ("lang", "false_claim", "live_text", "route_text", "sos_text", "open_text", "handled_text"),
    [
        (
            "ru",
            "Точной геолокации — мы её не храним.",
            "Историю точной геолокации в реальном времени отдельным архивом не храним.",
            f"Точки поездок: 0. Они видны участникам поездки. Проверяем для удаления после "
            f"{cleanup.TRIP_DAYS} дней; связанные записи могут продлить срок.",
            "Точки SOS: 1.",
            "Открытые SOS с точкой: 1. Они хранятся до обработки администратором.",
            f"Закрытые SOS с точкой удаляются через {cleanup.SOS_DAYS} дней от даты сигнала.",
        ),
        (
            "ba",
            "Теүәл геолокация — беҙ уны һаҡламайбыҙ.",
            "Реаль ваҡытта теүәл геолокация тарихын айырым архив итеп һаҡламайбыҙ.",
            f"Сәфәр нөктәләре: 0. Улар сәфәрҙә ҡатнашыусыларға күренә. "
            f"{cleanup.TRIP_DAYS} көндән һуң юйыу өсөн тикшерәбеҙ; бәйле яҙмалар һаҡлау "
            "ваҡытын оҙайта ала.",
            "SOS нөктәләре: 1.",
            "Нөктәле асыҡ SOS: 1. Улар администратор эшкәрткәнгә тиклем һаҡлана.",
            f"Нөктәле ябыҡ SOS сигнал көнөнән {cleanup.SOS_DAYS} көн үткәс таҙартыла.",
        ),
    ],
)
def test_export_does_not_deny_saved_sos_location(
    client, user_factory, lang: str, false_claim: str, live_text: str,
    route_text: str, sos_text: str, open_text: str, handled_text: str,
):
    passenger = _create_real_roadside_event(client, user_factory, f"Be29Export{lang}")

    response = client.get(f"/me/export?lang={lang}", headers=passenger["auth"])
    assert response.status_code == 200, response.text
    text = response.json()["text"]
    assert false_claim not in text
    for expected in (live_text, route_text, sos_text, open_text, handled_text):
        assert expected in text
