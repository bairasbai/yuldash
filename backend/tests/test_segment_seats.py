# -*- coding: utf-8 -*-
"""RS-02 А: место занято только на своём участке маршрута.

Сибай → Ургаза → Бурибай → Акъяр, 3 места. Трое берут Сибай → Ургаза — раньше поездка
становилась «полной» целиком и Булат из Ургазы её не находил, хотя дальше машина пустая.
Теперь участок Ургаза → Акъяр свободен, а весь путь — честно нет. Отмена, неявка, пауза
пассажира и удаление аккаунта возвращают место на СВОЁМ участке. Правда — живые брони;
снимок `leg_free` на рейсе — кэш для ленты, пишет его только `ride_seats.py` (CAS).
"""
from datetime import timedelta

from sqlmodel import Session, select

from app.account import delete_user_account
from app.db import engine
from app.models import Ride, User, UserRole
from app.safety_logic import cancel_upcoming_after_suspension
from app.timeutil import utcnow


def _mkride(driver, seats=3, price=700, **extra):
    """Рейс Сибай → Ургаза → Бурибай → Акъяр (2 остановки, 3 участка)."""
    with Session(engine) as session:
        ride = Ride(driver_id=driver["id"], from_city="Сибай", to_city="Акъяр",
                    waypoints="Ургаза | Бурибай", depart_at=utcnow() + timedelta(days=4),
                    seats_total=seats, seats_left=seats, price=price, **extra)
        session.add(ride)
        session.commit()
        session.refresh(ride)
        return ride.id


def _state(rid):
    """(leg_free, seats_left, seats_free_max, terms_version) рейса из базы."""
    with Session(engine) as session:
        r = session.get(Ride, rid)
        return r.leg_free, r.seats_left, r.seats_free_max, r.terms_version


def _book(client, passenger, rid, **fields):
    r = client.post("/bookings", headers=passenger["auth"], json={"ride_id": rid, **fields})
    assert r.status_code == 200, r.text
    return r.json()["id"]


def _pax(user_factory, name, **kw):
    return user_factory(name, gender=kw.pop("gender", "female"), **kw)


def test_full_first_leg_still_lets_you_book_the_tail(client, user_factory):
    driver = _pax(user_factory, "Seg driver", role=UserRole.driver)
    rid = _mkride(driver)
    трое = [_pax(user_factory, f"Seg p{i}") for i in range(3)]
    for p in трое:
        _book(client, p, rid, pickup_stop="Сибай", dropoff_stop="Ургаза")
    leg_free, seats_left, seats_free_max, version = _state(rid)
    assert (leg_free, seats_left, seats_free_max) == ("0,3,3", 0, 3)
    assert version == 3   # каждое взятие места — рост версии условий

    # Булат из Ургазы: участок свободен, хотя «весь путь» полон.
    булат = _pax(user_factory, "Seg bulat")
    _book(client, булат, rid, pickup_stop="Ургаза", dropoff_stop="Акъяр")
    assert _state(rid)[0] == "0,2,2"

    # А на ВЕСЬ путь — честный отказ с кодом и числом свободных на участке.
    пятый = _pax(user_factory, "Seg p5")
    r = client.post("/bookings", headers=пятый["auth"], json={
        "ride_id": rid, "seats": 1, "pickup_stop": "Сибай", "dropoff_stop": "Акъяр"})
    assert r.status_code == 400, r.text
    detail = r.json()["detail"]
    assert detail["code"] == "seats_not_enough" and detail["free"] == 0
    assert detail["ru"] and detail["ba"]


def test_cancel_releases_only_own_leg(client, user_factory):
    driver = _pax(user_factory, "Seg driver", role=UserRole.driver)
    rid = _mkride(driver)
    p1, p2 = _pax(user_factory, "Seg p1"), _pax(user_factory, "Seg p2")
    b1 = _book(client, p1, rid, pickup_stop="Сибай", dropoff_stop="Ургаза")
    _book(client, p2, rid, pickup_stop="Ургаза", dropoff_stop="Акъяр", seats=2)
    assert _state(rid)[0] == "2,1,1"

    r = client.post(f"/bookings/{b1}/cancel", headers=p1["auth"], json={"reason": "plans_changed"})
    assert r.status_code == 200, r.text
    leg_free, seats_left, seats_free_max, _ = _state(rid)
    assert (leg_free, seats_left, seats_free_max) == ("3,1,1", 1, 3)   # вернулся только первый участок


def test_no_show_releases_only_own_leg(client, user_factory):
    driver = _pax(user_factory, "Seg driver", role=UserRole.driver)
    rid = _mkride(driver)
    p1, p2 = _pax(user_factory, "Seg p1"), _pax(user_factory, "Seg p2")
    b1 = _book(client, p1, rid, pickup_stop="Сибай", dropoff_stop="Ургаза")
    _book(client, p2, rid, pickup_stop="Ургаза", dropoff_stop="Акъяр", seats=2)
    ok = client.post(f"/bookings/{b1}/confirm", headers=driver["auth"])
    assert ok.status_code == 200, ok.text
    # Срок «не пришёл» вышел: выезд был давно (бронь в начале маршрута — «Подъезжаю» не нужно).
    with Session(engine) as session:
        ride = session.get(Ride, rid)
        ride.depart_at = utcnow() - timedelta(days=1)
        session.add(ride)
        session.commit()

    r = client.post(f"/bookings/{b1}/no-show", headers=driver["auth"])
    assert r.status_code == 200, r.text
    assert _state(rid)[0] == "3,1,1"   # вернулся только первый участок


def test_passenger_pause_releases_own_leg(client, user_factory):
    driver = _pax(user_factory, "Seg driver", role=UserRole.driver)
    rid = _mkride(driver)
    p1 = _pax(user_factory, "Seg p1")
    _book(client, p1, rid, pickup_stop="Сибай", dropoff_stop="Ургаза", seats=2)
    assert _state(rid)[0] == "1,3,3"

    with Session(engine) as session:
        cancel_upcoming_after_suspension(session, p1["id"])
    # Пауза пассажира освобождает его участок (раньше место зависало занятым навсегда).
    assert _state(rid)[0] == "3,3,3"


def test_account_deletion_returns_the_seat(client, user_factory):
    driver = _pax(user_factory, "Seg driver", role=UserRole.driver)
    rid = _mkride(driver)
    p1 = _pax(user_factory, "Seg p1")
    _book(client, p1, rid, pickup_stop="Ургаза", dropoff_stop="Акъяр", seats=2)
    assert _state(rid)[0] == "3,1,1"

    with Session(engine) as session:
        delete_user_account(session, session.get(User, p1["id"]))
        session.commit()
    # Живая броня на чужом рейсе исчезла вместе с аккаунтом — и место вернулось в продажу
    # (дыра, найденная разбором RS-02: раньше delete(Booking) не возвращал мест).
    leg_free, seats_left, seats_free_max, _ = _state(rid)
    assert (leg_free, seats_left, seats_free_max) == ("3,3,3", 3, 3)


def test_legacy_ride_and_booking_behave_as_before(client, user_factory):
    """Снимок ещё не заведён (старый рейс) и бронь без участков (старый клиент):
    бронь занимает весь путь, отмена возвращает весь путь — поведение 1-в-1 прежнее."""
    driver = _pax(user_factory, "Seg driver", role=UserRole.driver)
    rid = _mkride(driver, seats=2)
    p1, p2 = _pax(user_factory, "Seg p1"), _pax(user_factory, "Seg p2")
    b1 = _book(client, p1, rid, seats=1)              # без pickup/dropoff — весь рейс
    assert _state(rid)[0] == "1,1,1"
    r = client.post("/bookings", headers=p2["auth"], json={"ride_id": rid, "seats": 2})
    assert r.status_code == 400 and r.json()["detail"]["code"] == "seats_not_enough"
    _book(client, p2, rid, seats=1)
    assert _state(rid)[0] == "0,0,0"
    client.post(f"/bookings/{b1}/cancel", headers=p1["auth"], json={})
    assert _state(rid)[0] == "1,1,1"


def test_booking_with_stale_terms_version_gets_409(client, user_factory):
    driver = _pax(user_factory, "Seg driver", role=UserRole.driver)
    rid = _mkride(driver)
    p1 = _pax(user_factory, "Seg p1")
    _book(client, p1, rid, pickup_stop="Ургаза", dropoff_stop="Акъяр")   # версия стала 1
    p2 = _pax(user_factory, "Seg p2")
    # Клиент p2 видел рейс ДО брони p1 (версия 0) — его подтверждение по устаревшим условиям.
    r = client.post("/bookings", headers=p2["auth"], json={
        "ride_id": rid, "seats": 1, "expected_terms_version": 0})
    assert r.status_code == 409 and r.json()["detail"]["code"] == "ride_terms_changed", r.text
    # А с актуальной версией — проходит.
    version = _state(rid)[3]
    ok = client.post("/bookings", headers=p2["auth"], json={
        "ride_id": rid, "seats": 1, "expected_terms_version": version})
    assert ok.status_code == 200, ok.text


def test_feeds_see_a_ride_with_a_free_segment(client, user_factory):
    driver = _pax(user_factory, "Seg driver", role=UserRole.driver)
    # Буст: в общей базе прогона тысячи рейсов, а у ленты потолок — наш обязан попасть в неё.
    rid = _mkride(driver, boosted_until=utcnow() + timedelta(days=1))
    for i in range(3):
        _book(client, _pax(user_factory, f"Seg p{i}"), rid, pickup_stop="Сибай", dropoff_stop="Ургаза")
    assert _state(rid)[1] == 0   # на всём пути мест нет

    # Лента без фильтра: рейс виден — место есть хотя бы на одном участке.
    feed = client.get("/rides")
    assert feed.status_code == 200 and rid in [r["id"] for r in feed.json()]
    # Однозначный запрос «Ургаза → Акъяр»: рейс виден, свободных на участке — 3.
    seg = client.get("/rides", params={"from_city": "Ургаза", "to_city": "Акъяр"})
    hits = [r for r in seg.json() if r["id"] == rid]
    assert hits and hits[0]["seats_on_segment"] == 3 and hits[0]["seats_by_leg"] == [0, 3, 3]
    # Однозначный запрос «Сибай → Акъяр» (весь путь): рейса нет — на его участке мест нет.
    whole = client.get("/rides", params={"from_city": "Сибай", "to_city": "Акъяр"})
    assert rid not in [r["id"] for r in whole.json()]
    # «Ближайшие» — те же правила.
    near_seg = client.get("/rides/near", params={"from_city": "Ургаза", "to_city": "Акъяр"})
    assert rid in [r["id"] for r in near_seg.json()["items"]]
    near_whole = client.get("/rides/near", params={"from_city": "Сибай", "to_city": "Акъяр"})
    assert rid not in [r["id"] for r in near_whole.json()["items"]]


def test_match_rides_for_a_request_respects_the_segment(client, user_factory):
    driver = _pax(user_factory, "Seg driver", role=UserRole.driver)
    rid = _mkride(driver)
    for i in range(3):
        _book(client, _pax(user_factory, f"Seg p{i}"), rid, pickup_stop="Сибай", dropoff_stop="Ургаза")

    tail = _pax(user_factory, "Seg tail")
    whole = _pax(user_factory, "Seg whole")
    with Session(engine) as session:
        ride = session.get(Ride, rid)
        from app.models import RideRequest
        session.add(RideRequest(passenger_id=tail["id"], from_city="Ургаза", to_city="Акъяр",
                                desired_at=ride.depart_at, seats=1))
        session.add(RideRequest(passenger_id=whole["id"], from_city="Сибай", to_city="Акъяр",
                                desired_at=ride.depart_at, seats=1))
        session.commit()
        tail_id = session.exec(select(RideRequest).where(RideRequest.passenger_id == tail["id"])).all()[-1].id
        whole_id = session.exec(select(RideRequest).where(RideRequest.passenger_id == whole["id"])).all()[-1].id

    # Подбор для заявки «Ургаза → Акъяр» — рейс есть; для «Сибай → Акъяр» (весь путь) — нет.
    seg = client.get("/match/rides", params={"request_id": tail_id}, headers=tail["auth"])
    assert seg.status_code == 200 and rid in [r["id"] for r in seg.json()], seg.text
    целиком = client.get("/match/rides", params={"request_id": whole_id}, headers=whole["auth"])
    assert целиком.status_code == 200 and rid not in [r["id"] for r in целиком.json()], целиком.text


def test_edit_ride_capacity_regressions(client, user_factory):
    driver = _pax(user_factory, "Seg driver", role=UserRole.driver)
    rid = _mkride(driver, seats=4)
    p1 = _pax(user_factory, "Seg p1")
    _book(client, p1, rid, pickup_stop="Сибай", dropoff_stop="Ургаза", seats=1)

    # Ручная смена числа мест при живой брони — по-прежнему 409.
    r = client.patch(f"/rides/{rid}", headers=driver["auth"], json={"seats_total": 3})
    assert r.status_code == 409, r.text
    # Прислано прежнее число — no-op (не правка).
    ok = client.patch(f"/rides/{rid}", headers=driver["auth"], json={"seats_total": 4})
    assert ok.status_code == 200, ok.text


def test_cap_reduction_uses_max_leg_occupancy(client, user_factory):
    driver = _pax(user_factory, "Seg driver", role=UserRole.driver)
    rid = _mkride(driver, seats=4)
    p1 = _pax(user_factory, "Seg p1")
    _book(client, p1, rid, pickup_stop="Сибай", dropoff_stop="Ургаза", seats=3)

    # «Сзади двое» (потолок 3): на каждом участке занято не больше 3 — можно; остатки честные.
    ok = client.patch(f"/rides/{rid}", headers=driver["auth"], json={"back_max_two": True})
    assert ok.status_code == 200, ok.text
    leg_free, seats_left, seats_free_max, _ = _state(rid)
    assert (leg_free, seats_left, seats_free_max) == ("0,3,3", 0, 3)
    with Session(engine) as session:
        assert session.get(Ride, rid).seats_total == 3


def test_cap_reduction_refused_when_a_leg_is_over(client, user_factory):
    driver = _pax(user_factory, "Seg driver", role=UserRole.driver)
    rid = _mkride(driver, seats=4)
    p1 = _pax(user_factory, "Seg p1")
    _book(client, p1, rid, pickup_stop="Сибай", dropoff_stop="Ургаза", seats=4)

    # Потолок 3, а на первом участке занято 4 — 409, и ничего не записалось.
    r = client.patch(f"/rides/{rid}", headers=driver["auth"], json={"back_max_two": True})
    assert r.status_code == 409, r.text
    with Session(engine) as session:
        ride = session.get(Ride, rid)
        assert ride.back_max_two is False and ride.seats_total == 4
        assert ride.leg_free == "0,4,4"
