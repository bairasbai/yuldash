"""Связанные окончания заявки и отказов, 19.10.2026.

Только публичные HTTP-действия и вход Kit через Telegram mock: без SQL,
подмены ответов, дат или часов. До первой остановки короткого каталогового
рейса около 6.493 км; depart +5 минут уже допускает arriving/boarded/done.
Это API/TestClient-проверки, также совместимые с JOURNEY_BASE_URL; они не
доказывают UI, голосовой ввод, смерть процесса, реальные push или GPS.
"""
from datetime import datetime, timezone

import pytest


START = "Мукасово 1-е (Баймакский р-н)"
PICKUP = "Давлетово (Баймакский р-н)"
DROPOFF = "Старый Сибай (Баймакский р-н)"
FINISH = "Сибай"
SECTION = (PICKUP, DROPOFF)


def _get(api, who, path, **params):
    result = api.get(path, headers=who.auth, params=params)
    assert result.status_code == 200, result.text
    return result.json()


def _post(api, who, path, body=None):
    result = api.post(path, headers=who.auth, json=body or {})
    assert result.status_code == 200, result.text
    return result.json()


def _start(api, kit, label, seats=2):
    driver = kit.login(f"Водитель {label}")
    passenger = kit.login(f"Пассажир {label}")
    _post(api, driver, "/driver/profile", {
        "car_make": "Lada", "car_model": "Vesta", "car_color": "белая",
        "car_plate": "А123ВС102", "seats": 4,
    })
    ride = kit.publish(driver, from_city=START, to_city=FINISH,
                       waypoints=f"{PICKUP} | {DROPOFF}", depart_at=kit.depart_in(5),
                       seats_total=seats, price=700, comment=f"QA: {label}",
                       pickup="У остановки", pickup_lat=None, pickup_lng=None)
    assert ride["status"] == "active" and ride["seats_left"] == seats
    assert [row["id"] for row in _get(api, driver, "/driver/rides")] == [ride["id"]]
    return driver, passenger, ride


def _request(api, passenger, ride, *, reverse=False):
    # Ответ рейса хранит наивный UTC; вход заявки без пояса считается местным
    # временем старого клиента. Возвращаем явный UTC, сохраняя тот же момент/день.
    departure = datetime.fromisoformat(ride["depart_at"].replace("Z", "+00:00"))
    if departure.tzinfo is None:
        departure = departure.replace(tzinfo=timezone.utc)
    request = _post(api, passenger, "/requests", {
        "from_city": DROPOFF if reverse else PICKUP,
        "to_city": PICKUP if reverse else DROPOFF,
        "desired_at": departure.isoformat(), "seats": 1, "max_price": 900,
        "category": "regular", "comment": "QA: место между остановками",
    })
    assert request["status"] == "active"
    return request["id"]


def _request_status(api, passenger, request_id):
    return next(row["status"] for row in _get(api, passenger, "/requests/mine")
                if row["id"] == request_id)


def _offer(api, driver, passenger, ride, request_id):
    rid = ride["id"]
    matched = next(row for row in _get(api, driver, "/requests/feed", on_route=True)
                   if row["id"] == request_id)
    assert matched["matched_ride_id"] == rid
    assert (matched["matched_pickup_stop"], matched["matched_dropoff_stop"]) == SECTION
    body = {"ride_id": rid, "price": 450, "comment": "QA: место в опубликованном рейсе"}
    response_id = _post(api, driver, f"/requests/{request_id}/respond", body)["id"]
    assert _post(api, driver, f"/requests/{request_id}/respond", body)["id"] == response_id
    shown = next(row for row in _get(api, passenger, f"/requests/{request_id}/responses")
                 if row["id"] == response_id)
    assert shown["status"] == "offered" and shown["can_accept"] is True
    assert shown["ride_id"] == rid
    assert (shown["pickup_stop"], shown["dropoff_stop"]) == SECTION
    return response_id


def _accept(api, passenger, response_id):
    bid = _post(api, passenger, f"/responses/{response_id}/accept")["booking_id"]
    assert _post(api, passenger, f"/responses/{response_id}/accept")["booking_id"] == bid
    return bid


def _seats(api, driver, ride, expected):
    shown = _get(api, driver, f"/rides/{ride['id']}")
    assert shown["seats_left"] == expected
    assert 0 <= shown["seats_left"] <= shown["seats_total"]
    assert shown["price"] == 700
    # Ни принятие, ни повтор/ошибка не должны незаметно создать второй рейс.
    assert [row["id"] for row in _get(api, driver, "/driver/rides", status="all")] == [ride["id"]]


def _driver_bookings(api, driver):
    return _get(api, driver, "/driver/bookings")


def _book(api, passenger, ride, pickup=PICKUP, dropoff=DROPOFF):
    return _post(api, passenger, "/bookings", {
        "ride_id": ride["id"], "seats": 1, "pickup_stop": pickup, "dropoff_stop": dropoff,
    })


def _cancel_request(api, driver, passenger, request_id):
    for _ in range(2):
        # Закрытие откликов делает дополнительный commit: тело action-ответа
        # не заменяет чтение сохранённого состояния после каждого из двух 200.
        _post(api, passenger, f"/requests/{request_id}/cancel")
        assert _request_status(api, passenger, request_id) == "cancelled"
        assert all(row["id"] != request_id for row in _get(api, driver, "/requests/feed"))


def _cancel_booking(api, kit, actor, driver, passenger, ride, bid, expected_seats):
    for _ in range(2):
        body = _post(api, actor, f"/bookings/{bid}/cancel", {"reason": "not_going"})
        assert body["status"] == "cancelled" and body["cancelled_by"] == actor.id
        _seats(api, driver, ride, expected_seats)
    for person in (passenger, driver):
        assert kit.role(person, bid)["status"] == "cancelled"
        details = kit.details(person, bid)
        assert details["contact_unlocked"] is False and details["driver_phone"] == ""
        assert details["driver_plate"] == "" and details["pickup_lat"] is None
        assert details["pickup_lng"] is None
        assert api.get(f"/trips/{bid}/receipt", headers=person.auth).status_code == 409
    history = [row for row in _get(api, passenger, "/bookings/mine") if row["id"] == bid]
    assert len(history) == 1 and history[0]["status"] == "cancelled"
    assert next(row for row in _driver_bookings(api, driver)
                if row["booking_id"] == bid)["status"] == "cancelled"


def _cancel_ride(api, kit, driver, passenger, ride):
    for _ in range(2):
        assert _post(api, driver, f"/rides/{ride['id']}/cancel")["status"] == "cancelled"
    assert kit.find_ride(ride["id"], who=passenger, from_city=PICKUP, to_city=DROPOFF) is None
    assert [row["id"] for row in _get(api, driver, "/driver/rides", status="all")] == [ride["id"]]


def test_bound_request_finishes_original_ride_with_two_own_receipts(api, kit):
    driver, passenger, ride = _start(api, kit, "Заявка до квитанций")
    stranger = kit.login("Посторонний заявка до квитанций")
    rid = ride["id"]
    request_id = _request(api, passenger, ride)
    response_id = _offer(api, driver, passenger, ride, request_id)
    _seats(api, driver, ride, 2)
    assert _driver_bookings(api, driver) == []
    bid = _accept(api, passenger, response_id)
    assert _request_status(api, passenger, request_id) == "matched"
    _seats(api, driver, ride, 1)
    assert [row["booking_id"] for row in _driver_bookings(api, driver)] == [bid]
    for person, role in ((passenger, "passenger"), (driver, "driver")):
        details = kit.details(person, bid)
        assert details["ride_id"] == rid and details["role"] == role
        assert details["status"] == "confirmed" and details["contact_unlocked"] is True
        assert details["price"] == 450
        assert (details["pickup_stop"], details["dropoff_stop"]) == SECTION
        assert api.get(f"/trips/{bid}/receipt", headers=person.auth).status_code == 409
    assert api.get(f"/bookings/{bid}/details", headers=stranger.auth).status_code == 403
    too_early = api.post(f"/bookings/{bid}/no-show", headers=driver.auth)
    assert too_early.status_code == 409 and too_early.json()["detail"]["code"] == "no_show_needs_arriving", too_early.text

    # Короткий реальный каталоговый участок: часы/геолокация не подменяются.
    for phase in ("departed", "arriving"):
        result = kit.driver_status(driver, bid, phase)
        assert result.status_code == 200, result.text
        assert kit.role(passenger, bid)["driver_phase"] == phase
    assert kit.role(passenger, bid)["arrival_verified"] is False
    too_early = api.post(f"/bookings/{bid}/no-show", headers=driver.auth)
    assert too_early.status_code == 409 and too_early.json()["detail"]["code"] == "no_show_too_early", too_early.text
    assert too_early.json()["detail"]["no_show_wait_sec"] > 0
    assert kit.driver_status(driver, bid, "boarded").status_code == 200
    assert kit.role(passenger, bid)["status"] == "onboard"
    assert api.post(f"/bookings/{bid}/boarded/undo", headers=passenger.auth).status_code == 403
    for _ in range(2):
        undo = _post(api, driver, f"/bookings/{bid}/boarded/undo")
        assert undo["status"] == "confirmed"
    assert kit.notified(passenger, "Отметку посадки отменили", ref_id=bid)
    assert kit.driver_status(driver, bid, "boarded").status_code == 200
    for person in (passenger, driver):
        assert kit.role(person, bid)["status"] == "onboard"
        assert (kit.details(person, bid)["pickup_stop"], kit.details(person, bid)["dropoff_stop"]) == SECTION
    assert api.post(f"/bookings/{bid}/cancel", headers=passenger.auth, json={"reason": "not_going"}).status_code == 409
    assert api.post(f"/bookings/{bid}/no-show", headers=driver.auth).status_code == 409
    for _ in range(2):
        done = kit.driver_status(driver, bid, "done")
        assert done.status_code == 200 and done.json()["status"] == "done", done.text
    _seats(api, driver, ride, 1)
    assert _get(api, driver, f"/rides/{rid}")["status"] == "active"
    for person, other, role, stars in (
        (passenger, driver, "passenger", 5), (driver, passenger, "driver", 3),
    ):
        rating = _post(api, person, f"/bookings/{bid}/rate", {"stars": stars, "text": "", "tags": ""})
        assert rating["ratee_id"] == other.id
        receipt = _get(api, person, f"/trips/{bid}/receipt")
        assert (receipt["booking_id"], receipt["ride_id"], receipt["role"]) == (bid, rid, role)
        assert (receipt["pickup_stop"], receipt["dropoff_stop"]) == SECTION
        assert receipt["my_stars"] == stars and receipt["counterparty_id"] == other.id
        assert receipt["amount"] == 450 and receipt["paid"] is False
        assert driver.phone not in str(receipt) and passenger.phone not in str(receipt)
    for _ in range(2):
        assert _post(api, driver, f"/rides/{rid}/complete")["status"] == "done"
    assert [row["id"] for row in _get(api, driver, "/driver/rides", status="all")] == [rid]
    assert kit.find_ride(rid, who=passenger, from_city=PICKUP, to_city=DROPOFF) is None
    for person, path, id_field, stars in (
        (passenger, "/bookings/mine", "id", 5), (driver, "/driver/bookings", "booking_id", 3),
    ):
        rows = [row for row in _get(api, person, path) if row[id_field] == bid]
        assert len(rows) == 1 and rows[0]["status"] == "done"
        assert (rows[0]["pickup_stop"], rows[0]["dropoff_stop"]) == SECTION
        receipt = _get(api, person, f"/trips/{bid}/receipt")
        assert receipt["my_stars"] == stars and receipt["ride_id"] == rid
    assert [row["booking_id"] for row in _driver_bookings(api, driver)] == [bid]
    assert api.get(f"/trips/{bid}/receipt", headers=stranger.auth).status_code == 403


@pytest.mark.parametrize("actor_role", ["passenger", "driver"])
def test_decline_ends_only_the_offer_and_repeat_has_one_effect(api, kit, actor_role):
    driver, passenger, ride = _start(api, kit, f"Отказ {actor_role}")
    request_id = _request(api, passenger, ride)
    response_id = _offer(api, driver, passenger, ride, request_id)
    actor, other = (passenger, driver) if actor_role == "passenger" else (driver, passenger)
    _post(api, actor, f"/responses/{response_id}/decline")
    assert _post(api, actor, f"/responses/{response_id}/decline")["already"] is True
    assert _request_status(api, passenger, request_id) == "active"
    for person, path in ((passenger, f"/requests/{request_id}/responses"), (driver, "/responses/mine")):
        row = next(row for row in _get(api, person, path) if row["id"] == response_id)
        assert row["status"] == "declined" and row["can_accept"] is False
    assert api.post(f"/responses/{response_id}/accept", headers=passenger.auth).status_code == 409
    notices = [row for row in kit.notifications(other)
               if row["title_ru"] == "Не договорились по цене" and row.get("ref_id") == request_id]
    assert len(notices) == 1
    assert _driver_bookings(api, driver) == []
    _seats(api, driver, ride, 2)
    _cancel_request(api, driver, passenger, request_id)
    _cancel_ride(api, kit, driver, passenger, ride)


def test_withdrawn_offer_cannot_be_accepted_but_a_new_offer_can(api, kit):
    driver, passenger, ride = _start(api, kit, "Отзыв и новое предложение")
    request_id = _request(api, passenger, ride)
    old = _offer(api, driver, passenger, ride, request_id)
    assert api.delete(f"/responses/{old}", headers=passenger.auth).status_code == 403
    for _ in range(2):
        result = api.delete(f"/responses/{old}", headers=driver.auth)
        assert result.status_code == 200, result.text
    assert _get(api, passenger, f"/requests/{request_id}/responses") == []
    assert next(row for row in _get(api, driver, "/responses/mine") if row["id"] == old)["status"] == "withdrawn"
    assert api.post(f"/responses/{old}/accept", headers=passenger.auth).status_code == 409
    assert _request_status(api, passenger, request_id) == "active"
    _seats(api, driver, ride, 2)
    assert _driver_bookings(api, driver) == []
    fresh = _offer(api, driver, passenger, ride, request_id)
    assert fresh != old
    bid = _accept(api, passenger, fresh)
    assert api.delete(f"/responses/{fresh}", headers=driver.auth).status_code == 409
    assert api.post(f"/responses/{fresh}/decline", headers=passenger.auth).status_code == 409
    assert [row["booking_id"] for row in _driver_bookings(api, driver)] == [bid]
    _seats(api, driver, ride, 1)
    _cancel_booking(api, kit, passenger, driver, passenger, ride, bid, 2)
    _cancel_request(api, driver, passenger, request_id)
    _cancel_ride(api, kit, driver, passenger, ride)


def test_cancelling_active_and_matched_announcements_keeps_their_booking_boundary(api, kit):
    for matched in (False, True):
        driver, passenger, ride = _start(api, kit, f"Отмена объявления matched={matched}")
        request_id = _request(api, passenger, ride)
        response_id = _offer(api, driver, passenger, ride, request_id)
        bid = _accept(api, passenger, response_id) if matched else None
        _cancel_request(api, driver, passenger, request_id)
        _seats(api, driver, ride, 1 if matched else 2)
        assert _get(api, driver, f"/rides/{ride['id']}")["status"] == "active"
        response = next(row for row in _get(api, driver, "/responses/mine") if row["id"] == response_id)
        if matched:
            assert response["status"] == "accepted"
            assert kit.role(passenger, bid)["status"] == kit.role(driver, bid)["status"] == "confirmed"
            assert [row["booking_id"] for row in _driver_bookings(api, driver)] == [bid]
            _cancel_booking(api, kit, passenger, driver, passenger, ride, bid, 2)
        else:
            assert response["status"] == "closed" and response["can_accept"] is False
            assert _driver_bookings(api, driver) == []
            assert api.post(f"/responses/{response_id}/accept", headers=passenger.auth).status_code == 400
        _cancel_ride(api, kit, driver, passenger, ride)


@pytest.mark.parametrize("state,actor_role", [("pending", "passenger"), ("pending", "driver"), ("confirmed", "passenger")])
def test_booking_cancel_returns_one_seat_and_both_sides_see_terminal_history(api, kit, state, actor_role):
    driver, passenger, ride = _start(api, kit, f"Отмена брони {state} {actor_role}")
    if state == "confirmed":
        request_id = _request(api, passenger, ride)
        bid = _accept(api, passenger, _offer(api, driver, passenger, ride, request_id))
    else:
        request_id = None
        booking = _book(api, passenger, ride)
        bid = booking["id"]
        assert booking["status"] == "pending"
    _seats(api, driver, ride, 1)
    actor, other = (passenger, driver) if actor_role == "passenger" else (driver, passenger)
    _cancel_booking(api, kit, actor, driver, passenger, ride, bid, 2)
    cancelled = _post(api, actor, f"/bookings/{bid}/cancel", {"reason": "not_going"})
    assert cancelled["contact_then_cancel"] is (state == "confirmed")
    assert len([row for row in kit.notifications(other)
                if row["title_ru"] == "Бронь отменена" and row.get("ref_id") == bid]) == 1
    if request_id is not None:
        assert _request_status(api, passenger, request_id) == "matched"
        _cancel_request(api, driver, passenger, request_id)
    _cancel_ride(api, kit, driver, passenger, ride)


def test_cancelling_original_ride_cascades_pending_and_request_confirmed_bookings(api, kit):
    driver, first, ride = _start(api, kit, "Отмена всего рейса", seats=3)
    second = kit.login("Пассажир ожидающей брони каскада")
    request_id = _request(api, first, ride)
    accepted = _accept(api, first, _offer(api, driver, first, ride, request_id))
    pending = _book(api, second, ride)["id"]
    assert accepted != pending
    _seats(api, driver, ride, 1)
    _cancel_ride(api, kit, driver, first, ride)
    for passenger, bid in ((first, accepted), (second, pending)):
        for person in (passenger, driver):
            assert kit.role(person, bid)["status"] == "cancelled"
            assert kit.details(person, bid)["contact_unlocked"] is False
        # Читаем сохранённое авторство через идемпотентную отмену, не создаём новую отмену.
        stored = _post(api, driver, f"/bookings/{bid}/cancel")
        assert stored["status"] == "cancelled" and stored["cancelled_by"] == driver.id
        assert stored["cancel_reason"] == "not_going"
        assert len([row for row in kit.notifications(passenger)
                    if row["title_ru"] == "Поездка отменена" and row.get("ref_id") == bid]) == 1
        history = [row for row in _get(api, passenger, "/bookings/mine") if row["id"] == bid]
        assert len(history) == 1 and history[0]["status"] == "cancelled"
    rows = _driver_bookings(api, driver)
    assert {row["booking_id"] for row in rows} == {accepted, pending}
    assert all(row["status"] == "cancelled" for row in rows)
    # Каскад не обещает вернуть seats_left отменённого рейса: он больше не продаётся.
    assert _request_status(api, first, request_id) == "matched"
    _cancel_request(api, driver, first, request_id)


def test_last_seat_stale_offers_and_invalid_stops_never_create_extra_bookings(api, kit):
    # Принятие заявки: два уже открытых отклика держат одно последнее место.
    driver, first, ride = _start(api, kit, "Последнее место заявки", seats=1)
    second = kit.login("Второй пассажир последнего места заявки")
    requests = [_request(api, person, ride) for person in (first, second)]
    offers = [_offer(api, driver, person, ride, req) for person, req in zip((first, second), requests)]
    first_bid = _accept(api, first, offers[0])
    refused = api.post(f"/responses/{offers[1]}/accept", headers=second.auth)
    assert refused.status_code == 409, refused.text
    assert _request_status(api, second, requests[1]) == "active"
    assert next(row for row in _get(api, second, f"/requests/{requests[1]}/responses")
                if row["id"] == offers[1])["status"] == "offered"
    assert _get(api, second, "/bookings/mine") == []
    assert [row["booking_id"] for row in _driver_bookings(api, driver)] == [first_bid]
    _seats(api, driver, ride, 0)
    _cancel_booking(api, kit, first, driver, first, ride, first_bid, 1)
    second_bid = _accept(api, second, offers[1])
    assert second_bid != first_bid
    _seats(api, driver, ride, 0)
    _cancel_booking(api, kit, second, driver, second, ride, second_bid, 1)
    for person, req in zip((first, second), requests):
        _cancel_request(api, driver, person, req)
    _cancel_ride(api, kit, driver, first, ride)

    # Обычная бронь — отдельный механизм резерва. Недопустимые точки не тратят место.
    driver, first, ride = _start(api, kit, "Последнее место обычной брони", seats=1)
    second = kit.login("Второй пассажир последнего места обычной брони")
    for pickup, dropoff, code in (
        (PICKUP, PICKUP, "dropoff_stop_order"),
        (DROPOFF, PICKUP, "dropoff_stop_order"),
        ("Чужая остановка", DROPOFF, "pickup_stop_unknown"),
    ):
        invalid = api.post("/bookings", headers=first.auth, json={
            "ride_id": ride["id"], "seats": 1, "pickup_stop": pickup, "dropoff_stop": dropoff,
        })
        assert invalid.status_code == 400 and invalid.json()["detail"]["code"] == code, invalid.text
        _seats(api, driver, ride, 1)
        assert _driver_bookings(api, driver) == [] and _get(api, first, "/bookings/mine") == []
    reverse = _request(api, first, ride, reverse=True)
    assert all(row["id"] != reverse for row in _get(api, driver, "/requests/feed", on_route=True))
    assert api.post(f"/requests/{reverse}/respond", headers=driver.auth,
                    json={"ride_id": ride["id"], "price": 450}).status_code == 409
    _seats(api, driver, ride, 1)
    assert _driver_bookings(api, driver) == []
    _cancel_request(api, driver, first, reverse)
    first_bid = _book(api, first, ride)["id"]
    refused = api.post("/bookings", headers=second.auth, json={
        "ride_id": ride["id"], "seats": 1, "pickup_stop": PICKUP, "dropoff_stop": DROPOFF,
    })
    assert refused.status_code == 400, refused.text
    _seats(api, driver, ride, 0)
    assert [row["booking_id"] for row in _driver_bookings(api, driver)] == [first_bid]
    assert _get(api, second, "/bookings/mine") == []
    _cancel_booking(api, kit, first, driver, first, ride, first_bid, 1)
    second_booking = _book(api, second, ride)
    assert second_booking["status"] == "pending"
    assert (second_booking["pickup_stop"], second_booking["dropoff_stop"]) == SECTION
    _seats(api, driver, ride, 0)
    _cancel_booking(api, kit, second, driver, second, ride, second_booking["id"], 1)
    assert {row["booking_id"] for row in _driver_bookings(api, driver)} == {first_bid, second_booking["id"]}
    _cancel_ride(api, kit, driver, second, ride)
