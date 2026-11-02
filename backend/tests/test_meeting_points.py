"""RS-03 А: точное место встречи/высадки при брони.

Что было. Бронь знала только село («Ургаза») — водитель въезжал и звонил «ты где?».
Что стало. Пассажир по желанию ставит точку на карте или выбирает курируемый ориентир и пишет
пояснение; точка не может уехать за пределы места посадки/высадки (село — 3 км, город — 15 км
от опоры: остановка из справочника / пин / геокод концов; опора неизвестна — коридор 25 км от
прямой). Повтор с той же точкой — та же броня, с другой — 409 `meeting_point_conflict` + booking_id.
Пуш водителю — «место встречи указано» без координат и пояснения.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

import app.services as svc
from app.db import engine
from app.geo import snapshot_cached
from app.models import Booking, PickupPoint, Ride, UserRole
from app.ride_stops import stop_point
from app.timeutil import utcnow
from datetime import timedelta

STOPS = "Ургаза | Бурибай"
SIBAY = (52.716, 58.664)


def _when(days: int = 400) -> str:
    return (utcnow() + timedelta(days=days)).replace(microsecond=0).isoformat() + "+00:00"


def _publish(client, drv, tag: str, *, seats: int = 3):
    body = {"from_city": "Сибай", "to_city": "Акъяр", "depart_at": _when(), "seats_total": seats,
            "price": 700, "comment": f"meet-{tag}", "waypoints": STOPS, "pickup": "У автовокзала",
            "pickup_lat": SIBAY[0], "pickup_lng": SIBAY[1]}
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _book(client, pax, ride_id: int, **extra):
    return client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": 1, **extra})


def _booking(bid: int) -> Booking:
    with Session(engine) as s:
        return s.get(Booking, bid)


def _urgaza(ride_id: int):
    """Координаты остановки Ургаза на ЭТОЙ поездке — тем же путём, что сервер (справочник +
    коридор маршрута). Точку для теста ставим рядом с ней."""
    with Session(engine) as s:
        ride = s.get(Ride, ride_id)
        sp = stop_point(ride, "Ургаза", snapshot_cached())
    assert sp is not None, "справочник должен знать Ургазу на этом маршруте"
    return sp.lat, sp.lng


def test_point_near_the_stop_is_stored(client, user_factory):
    drv = user_factory("MP Driver", role=UserRole.driver)
    pax = user_factory("MP Pax")
    ride = _publish(client, drv, "near")
    ulat, ulng = _urgaza(ride["id"])
    r = _book(client, pax, ride["id"], pickup_stop="Ургаза",
              meeting={"lat": ulat + 0.003, "lng": ulng + 0.003, "note": "синие ворота напротив школы"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["pickup_lat"] == pytest.approx(ulat + 0.003)
    assert body["pickup_note"] == "синие ворота напротив школы"
    assert body["pickup_source"] == "pin"
    assert body["meeting_version"] == 1
    b = _booking(body["id"])
    assert b.meet_lat == pytest.approx(ulat + 0.003) and b.meet_landmark_id is None


def test_point_far_from_the_stop_is_400_and_seats_untouched(client, user_factory):
    drv = user_factory("MP Far Driver", role=UserRole.driver)
    pax = user_factory("MP Far Pax")
    ride = _publish(client, drv, "far")
    ulat, ulng = _urgaza(ride["id"])
    r = _book(client, pax, ride["id"], pickup_stop="Ургаза",
              meeting={"lat": ulat + 0.5, "lng": ulng + 0.5})   # ~50 км — за пределами села
    assert r.status_code == 400 and r.json()["detail"]["code"] == "meeting_point_far", r.text
    with Session(engine) as s:
        assert s.get(Ride, ride["id"]).seats_left == 3, "места не тронуты"


def test_zero_and_nan_points_rejected(client, user_factory):
    drv = user_factory("MP Zero Driver", role=UserRole.driver)
    pax = user_factory("MP Zero Pax")
    ride = _publish(client, drv, "zero")
    r = _book(client, pax, ride["id"], meeting={"lat": 0.0, "lng": 0.0})
    assert r.status_code == 400 and r.json()["detail"]["code"] == "meeting_point_bad", r.text
    # NaN/inf по JSON не доезжают вовсе (клиент их не сериализует) — проверка конечности в
    # validate_meeting стоит на случай не-JSON производителей.


def test_note_only_is_allowed(client, user_factory):
    drv = user_factory("MP Note Driver", role=UserRole.driver)
    pax = user_factory("MP Note Pax")
    ride = _publish(client, drv, "note")
    r = _book(client, pax, ride["id"], meeting={"note": "у магазина, синяя калитка"})
    assert r.status_code == 200, r.text
    assert r.json()["pickup_source"] == "note" and r.json()["pickup_lat"] is None


def test_landmark_only_seed_is_accepted(client, user_factory):
    drv = user_factory("MP LM Driver", role=UserRole.driver)
    pax = user_factory("MP LM Pax")
    ride = _publish(client, drv, "lm")
    with Session(engine) as s:
        seed = PickupPoint(city="Сибай", title_ru="у мечети", title_ba="мәсет янында",
                           lat=SIBAY[0], lng=SIBAY[1], is_seed=True)
        s.add(seed)
        s.commit()
        s.refresh(seed)
        seed_id = seed.id
    r = _book(client, pax, ride["id"], meeting={"landmark_id": seed_id})
    assert r.status_code == 200, r.text
    assert r.json()["pickup_source"] == "pin" and r.json()["pickup_landmark_ru"] == "у мечети"
    # Чужой/не-currated ориентир — 400
    with Session(engine) as s:
        mine = PickupPoint(city="Сибай", title_ru="мой двор", title_ba="минең ихата", is_seed=False)
        s.add(mine)
        s.commit()
        s.refresh(mine)
        mine_id = mine.id
    r = _book(client, pax, ride["id"], meeting={"landmark_id": mine_id})
    # вторая бронь того же пассажира — уже конфликт/повтор; чужой ориентир ловим отдельным пассажиром
    pax2 = user_factory("MP LM Pax2")
    r2 = _book(client, pax2, ride["id"], meeting={"landmark_id": mine_id})
    assert r2.status_code == 400 and r2.json()["detail"]["code"] == "meeting_landmark_unknown", r2.text


def test_retry_same_point_is_idempotent_other_point_409(client, user_factory):
    drv = user_factory("MP Retry Driver", role=UserRole.driver)
    pax = user_factory("MP Retry Pax")
    ride = _publish(client, drv, "retry")
    ulat, ulng = _urgaza(ride["id"])
    first = _book(client, pax, ride["id"], pickup_stop="Ургаза",
                  meeting={"lat": ulat, "lng": ulng, "note": "у школы"})
    assert first.status_code == 200, first.text
    same = _book(client, pax, ride["id"], pickup_stop="Ургаза",
                 meeting={"lat": ulat, "lng": ulng, "note": "у школы"})
    assert same.status_code == 200 and same.json()["id"] == first.json()["id"]
    silent = _book(client, pax, ride["id"])       # повтор вообще без точки — та же броня
    assert silent.status_code == 200 and silent.json()["id"] == first.json()["id"]
    other = _book(client, pax, ride["id"], pickup_stop="Ургаза",
                  meeting={"lat": ulat + 0.002, "lng": ulng})
    assert other.status_code == 409 and other.json()["detail"]["code"] == "meeting_point_conflict", other.text
    assert other.json()["detail"]["booking_id"] == first.json()["id"]
    assert _booking(first.json()["id"]).meet_note == "у школы", "старая точка не тронута"


def test_push_marks_meeting_point_without_details(client, user_factory, monkeypatch):
    got: list = []
    monkeypatch.setattr(svc, "send_push", lambda session, uid, title, body, **k: got.append(body))
    drv = user_factory("MP Push Driver", role=UserRole.driver)
    pax = user_factory("MP Push Pax")
    ride = _publish(client, drv, "push")
    ulat, ulng = _urgaza(ride["id"])
    r = _book(client, pax, ride["id"], pickup_stop="Ургаза",
              meeting={"lat": ulat, "lng": ulng, "note": "синие ворота"})
    assert r.status_code == 200, r.text
    assert got and "место встречи указано" in got[-1]
    assert "синие ворота" not in got[-1] and str(ulat) not in got[-1], "координаты и пояснение — не в пуш"


def test_no_meeting_payload_behaves_as_before(client, user_factory):
    drv = user_factory("MP None Driver", role=UserRole.driver)
    pax = user_factory("MP None Pax")
    ride = _publish(client, drv, "none")
    r = _book(client, pax, ride["id"], pickup_stop="Ургаза")
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["pickup_lat"] is None and body["pickup_note"] == "" and body["pickup_source"] == ""
    assert body["meeting_point_set"] is False and body["meeting_version"] == 0
    assert "meet_lat" not in body and "alight_lat" not in body, "сырые поля не утекают в ответ"


def test_remote_seed_landmark_is_rejected_before_reserving_seats(client, user_factory):
    drv = user_factory("MP Remote Driver", role=UserRole.driver)
    pax = user_factory("MP Remote Pax")
    ride = _publish(client, drv, "remote-seed")
    with Session(engine) as session:
        point = PickupPoint(city="Сибай", title_ru="дальний ориентир", title_ba="алыҫ билге",
                            lat=SIBAY[0] + 1, lng=SIBAY[1] + 1, is_seed=True)
        session.add(point)
        session.commit()
        pid = point.id
    response = _book(client, pax, ride["id"], meeting={"landmark_id": pid})
    assert response.status_code == 400, response.text
    assert response.json()["detail"]["code"] == "meeting_point_far"
    with Session(engine) as session:
        assert session.get(Ride, ride["id"]).seats_left == 3


def test_race_retry_compares_validated_points(client, user_factory):
    from app import meeting_points
    from app.routers.bookings import BookIn, _retry_after_booking_race
    drv = user_factory("MP Retry Driver", role=UserRole.driver)
    pax = user_factory("MP Retry Pax")
    ride = _publish(client, drv, "race-retry")
    lat, lng = _urgaza(ride["id"])
    raw = {"ride_id": ride["id"], "seats": 1, "pickup_stop": "Ургаза",
           "meeting": {"lat": lat, "lng": lng}}
    created = client.post("/bookings", headers=pax["auth"], json=raw)
    assert created.status_code == 200, created.text
    with Session(engine) as session:
        from app.models import User
        booking = session.get(Booking, created.json()["id"])
        row = session.get(Ride, ride["id"])
        body = BookIn(**raw)
        point = meeting_points.validate_meeting(session, row, "pickup", body.meeting,
                                                stop_name="Ургаза", skip_moderation=True)
        retry = _retry_after_booking_race(session, row, session.get(User, booking.passenger_id),
                                         body, "Ургаза", snapshot_cached(), point, None)
        assert retry.id == booking.id


@pytest.mark.parametrize("lat,lng", [(91, 58.664), (-91, 58.664), (52.716, 418.664), (52.716, -181)])
def test_out_of_range_coordinates_do_not_reserve_seats(client, user_factory, lat, lng):
    drv = user_factory("MP Bounds Driver", role=UserRole.driver)
    pax = user_factory("MP Bounds Pax")
    ride = _publish(client, drv, "bounds")
    response = _book(client, pax, ride["id"], meeting={"lat": lat, "lng": lng})
    assert response.status_code == 400, response.text
    assert response.json()["detail"]["code"] == "meeting_point_bad"
    with Session(engine) as session:
        assert session.get(Ride, ride["id"]).seats_left == 3
