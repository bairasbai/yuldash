# -*- coding: utf-8 -*-
"""RS-02 Б: цена до своей остановки (целые рубли, D7).

Сибай → Ургаза → Бурибай → Акъяр за 500 ₽, цены до остановок [300, 400]:
Сибай→Ургаза 300, Ургаза→Бурибай 100, Бурибай→Акъяр 100, Ургаза→Акъяр 200.
Бронь платит за СВОЙ участок × места; с живыми бронями цену можно только снижать —
и целиком, и по участку каждой брони. Нигде никаких копеек: 300 — это 300 ₽, не 30000.
"""
from types import SimpleNamespace

from sqlmodel import Session, select

from app import ride_seats
from app.db import engine
from app.models import Booking, BookingStatus, Ride, UserRole
from test_segment_seats import _book, _mkride, _pax, _state


def _post_ride(client, driver, **fields):
    body = {"from_city": "Сибай", "to_city": "Акъяр", "depart_at": "2030-01-01T10:00:00",
            "seats_total": 3, "price": 500, **fields}
    return client.post("/rides", headers=driver["auth"], json=body)


def _booking(bid):
    with Session(engine) as session:
        return session.get(Booking, bid)


# ---------- разбор и математика ----------

def test_segment_price_math():
    """Без цен — полная; остановка 300/конец 500 → второй участок 200; NULL/«» — края."""
    пустой = SimpleNamespace(waypoints="Ургаза | Бурибай", price=500, stop_prices_json="")
    assert ride_seats.segment_price(пустой, "Ургаза", "Бурибай") == 500     # цен нет → полная
    рейс = SimpleNamespace(waypoints="Ургаза | Бурибай", price=500, stop_prices_json="[300, 400]")
    assert ride_seats.segment_price(рейс, "", "Ургаза") == 300              # Сибай→Ургаза
    assert ride_seats.segment_price(рейс, "Ургаза", "Акъяр") == 200         # Ургаза→Акъяр
    assert ride_seats.segment_price(рейс, "Ургаза", "Бурибай") == 100
    assert ride_seats.segment_price(рейс, None, None) == 500                # старый клиент — весь путь
    assert ride_seats.segment_price(рейс, "ЧужоеСело", None) == 500         # незнакомая посадка — от начала
    битый = SimpleNamespace(waypoints="Ургаза", price=500, stop_prices_json="{не json")
    assert ride_seats.segment_price(битый, None, None) == 500               # битое хранилище → полная


def test_validate_stop_prices_codes(client, user_factory):
    driver = _pax(user_factory, "Price driver", role=UserRole.driver)
    случаи = [
        (_post_ride(client, driver, waypoints="Ургаза", price=0, stop_prices=[100]),
         "stop_prices_need_price"),
        (_post_ride(client, driver, waypoints="Ургаза", price=500, stop_prices=[300, 400]),
         "stop_prices_count"),
        (_post_ride(client, driver, waypoints="Ургаза", price=500, stop_prices=[500]),
         "stop_prices_order"),
        (_post_ride(client, driver, waypoints="Ургаза | Бурибай", price=500, stop_prices=[300, 250]),
         "stop_prices_order"),
        (_post_ride(client, driver, waypoints="Ургаза | Ургаза", price=500, stop_prices=[300, 400]),
         "stop_prices_count"),
    ]
    for r, код in случаи:
        assert r.status_code == 400, (код, r.text)
        detail = r.json()["detail"]
        assert detail["code"] == код and detail["ru"] and detail["ba"]


# ---------- публикация: контракт ----------

def test_publish_get_contract(client, user_factory):
    driver = _pax(user_factory, "Price driver", role=UserRole.driver)
    r = _post_ride(client, driver, waypoints="Ургаза", price=500, stop_prices=[300])
    assert r.status_code == 200, r.text
    rid = r.json()["id"]
    # Хранение — JSON-список в порядке частей.
    with Session(engine) as session:
        assert session.get(Ride, rid).stop_prices_json == "[300]"
    # Выход строго один: [{stop, price}] в обоих выдачах.
    card = client.get(f"/rides/{rid}")
    assert card.status_code == 200, card.text
    assert card.json()["stop_prices"] == [{"stop": "Ургаза", "price": 300}]
    assert card.json()["segment_price"] == 500   # участок не выбран — полная цена
    feed = client.get("/rides", params={"from_city": "Ургаза", "to_city": "Акъяр"})
    hits = [x for x in feed.json() if x["id"] == rid]
    assert hits and hits[0]["stop_prices"] == [{"stop": "Ургаза", "price": 300}]
    assert hits[0]["segment_price"] == 200       # участок запроса Ургаза→Акъяр


def test_publish_twin_and_series(client, user_factory):
    driver = _pax(user_factory, "Price driver", role=UserRole.driver)
    first = _post_ride(client, driver, waypoints="Ургаза", price=500, stop_prices=[300])
    assert first.status_code == 200, first.text
    # Повтор с ТЕМИ ЖЕ ценами — тот же рейс (дедуп); с другими — новый.
    twin = _post_ride(client, driver, waypoints="Ургаза", price=500, stop_prices=[300])
    assert twin.status_code == 200 and twin.json()["id"] == first.json()["id"]
    other = _post_ride(client, driver, waypoints="Ургаза", price=500, stop_prices=[250])
    assert other.status_code == 200 and other.json()["id"] != first.json()["id"]
    # Серия — цены копируются во все рейсы.
    series = _post_ride(client, driver, waypoints="Ургаза", price=500, stop_prices=[300],
                        recurrence="daily", depart_at="2030-02-01T10:00:00")
    assert series.status_code == 200, series.text
    with Session(engine) as session:
        rides = session.exec(select(Ride).where(Ride.driver_id == driver["id"],
                                                Ride.recurrence == "daily")).all()
        assert len(rides) >= 5 and all(r.stop_prices_json == "[300]" for r in rides)


# ---------- бронь ----------

def test_booking_pays_own_segment_times_seats(client, user_factory):
    driver = _pax(user_factory, "Price driver", role=UserRole.driver)
    rid = _mkride(driver, seats=4, price=500, stop_prices_json="[300, 400]")
    булат = _pax(user_factory, "Price bulat")
    # Ургаза → Акъяр, одно место: 200 ₽; два места с подтверждённой ценой: 400 ₽.
    b1 = _book(client, булат, rid, pickup_stop="Ургаза", dropoff_stop="Акъяр", seats=1)
    assert _booking(b1).price == 200 and _booking(b1).pay_amount == 200
    b2 = _book(client, _pax(user_factory, "Price p2"), rid,
               pickup_stop="Ургаза", dropoff_stop="Акъяр", seats=2, expected_price=200)
    assert _booking(b2).price == 400 and _booking(b2).pay_amount == 400
    # Сибай → Ургаза: 300 ₽; весь путь: 500 ₽.
    b3 = _book(client, _pax(user_factory, "Price p3"), rid, pickup_stop="Сибай", dropoff_stop="Ургаза")
    assert _booking(b3).price == 300
    b4 = _book(client, _pax(user_factory, "Price p4"), rid, seats=1)
    assert _booking(b4).price == 500
    # Подтверждённая цена не сошлась — 409 price_changed с актуальной в теле.
    r = client.post("/bookings", headers=_pax(user_factory, "Price p5")["auth"], json={
        "ride_id": rid, "seats": 1, "pickup_stop": "Ургаза", "dropoff_stop": "Акъяр",
        "expected_price": 500})
    assert r.status_code == 409 and r.json()["detail"]["code"] == "price_changed"
    assert r.json()["detail"]["price"] == 200
    # Квитанция — в тех же рублях, никаких 20000.
    with Session(engine) as session:
        b = session.get(Booking, b2)
        b.status = BookingStatus.done
        session.add(b)
        session.commit()
    receipt = client.get(f"/trips/{b2}/receipt", headers=_receipt_auth(b2))
    assert receipt.status_code == 200 and receipt.json()["amount"] == 400


def _receipt_auth(bid):
    with Session(engine) as session:
        b = session.get(Booking, bid)
        from app.security import make_token
        return {"Authorization": f"Bearer {make_token(b.passenger_id)}"}


# ---------- правка цен ----------

def test_edit_lowers_every_segment_and_rewrites_bookings(client, user_factory):
    driver = _pax(user_factory, "Price driver", role=UserRole.driver)
    rid = _mkride(driver, seats=4, price=500, stop_prices_json="[300, 400]")
    b1 = _book(client, _pax(user_factory, "Price p1"), rid, pickup_stop="Сибай", dropoff_stop="Ургаза")
    b2 = _book(client, _pax(user_factory, "Price p2"), rid,
               pickup_stop="Ургаза", dropoff_stop="Акъяр", seats=2)
    # 500→450 и остановки [280, 380]: участки дешевеют у всех — проходит; брони пересчитаны.
    ok = client.patch(f"/rides/{rid}", headers=driver["auth"],
                      json={"price": 450, "stop_prices": [280, 380]})
    assert ok.status_code == 200, ok.text
    assert _booking(b1).price == 280
    assert _booking(b2).price == (450 - 280) * 2   # 340
    with Session(engine) as session:
        assert session.get(Ride, rid).stop_prices_json == "[280, 380]"


def test_edit_refuses_any_price_rise_with_bookings(client, user_factory):
    driver = _pax(user_factory, "Price driver", role=UserRole.driver)
    rid = _mkride(driver, seats=4, price=500, stop_prices_json="[300, 400]")
    _book(client, _pax(user_factory, "Price p1"), rid, pickup_stop="Сибай", dropoff_stop="Ургаза")
    # Полная вверх с живыми бронями — 409 price_up_with_bookings.
    r = client.patch(f"/rides/{rid}", headers=driver["auth"], json={"price": 600})
    assert r.status_code == 409 and r.json()["detail"]["code"] == "price_up_with_bookings", r.text
    # Перестановка цен остановок, поднимающая чей-то участок, — тоже 409.
    r = client.patch(f"/rides/{rid}", headers=driver["auth"], json={"stop_prices": [350, 400]})
    assert r.status_code == 409 and r.json()["detail"]["code"] == "price_up_with_bookings", r.text
    # И ничего не записалось.
    with Session(engine) as session:
        ride = session.get(Ride, rid)
        assert ride.price == 500 and ride.stop_prices_json == "[300, 400]"


def test_edit_terms_version_guard(client, user_factory):
    driver = _pax(user_factory, "Price driver", role=UserRole.driver)
    rid = _mkride(driver, price=500, stop_prices_json="[300, 400]")
    version = _state(rid)[3]
    r = client.patch(f"/rides/{rid}", headers=driver["auth"],
                     json={"price": 450, "expected_terms_version": version + 5})
    assert r.status_code == 409 and r.json()["detail"]["code"] == "ride_terms_changed", r.text
    ok = client.patch(f"/rides/{rid}", headers=driver["auth"],
                      json={"price": 450, "expected_terms_version": version})
    assert ok.status_code == 200, ok.text


# ---------- подсказка цены по остановкам ----------

def test_price_hint_with_stops(client, user_factory):
    driver = _pax(user_factory, "Price driver", role=UserRole.driver)
    _post_ride(client, driver, from_city="Сибай", to_city="Ургаза", price=400)
    r = client.get("/rides/price_hint", params={"from_city": "Сибай", "stops": "Ургаза,Бурибай"})
    assert r.status_code == 200, r.text
    legs = r.json()["stops"]
    assert [leg["stop"] for leg in legs] == ["Ургаза", "Бурибай"]
    assert legs[0]["distance_km"] and legs[0]["distance_km"] > 0   # свой справочник, не Яндекс
    assert legs[0]["avg"] == 400 and legs[0]["count"] == 1         # прошлая поездка до Ургазы
