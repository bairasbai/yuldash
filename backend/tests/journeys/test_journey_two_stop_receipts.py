"""Связанный путь 18.10: поиск двух остановок → бронь → посадка → две квитанции.

Все люди входят через Telegram mock Kit; профиль, рейс, бронь, статусы и оценки
создаются только HTTP-запросами. Прямых чтений/записей SQL нет. Сумма и paid
проверяются без оплаты. Это API-путь, не доказательство нажатий в Android/PWA.

После подтверждения QA-часы приложения сдвигаются на 90 минут: водитель успел
доехать до промежуточной остановки. Меняется только utcnow в текущем процессе,
не даты рейса/посадки в БД, не правила допуска и не ответы API. Поэтому этот
тест выполняется с TestClient на SQLite/PostgreSQL; отдельный uvicorn по TCP
явно пропускается — monkeypatch не управляет часами другого процесса.
"""
from datetime import timedelta
import os

import pytest

from app import services, timeutil
from app.routers import bookings, rides


def test_two_stop_booking_keeps_its_section_through_both_receipts(api, kit, monkeypatch):
    if os.environ.get("JOURNEY_BASE_URL", "").strip():
        pytest.skip("QA-часы действуют только в процессе TestClient, не в отдельном uvicorn")

    driver = kit.login("Ильдар Две остановки")
    passenger = kit.login("Гульнара Две остановки")
    stranger = kit.login("Посторонний Две остановки")
    assert len({driver.id, passenger.id, stranger.id}) == 3

    profile = api.post("/driver/profile", headers=driver.auth, json={
        "car_make": "Lada", "car_model": "Vesta", "car_color": "белая",
        "car_plate": "А123ВС102", "seats": 4,
    })
    assert profile.status_code == 200, profile.text
    ride = kit.publish(
        driver, from_city="Сибай", to_city="Акъяр", waypoints="Ургаза | Бурибай",
        seats_total=2, price=700, comment="QA: две остановки и личные квитанции",
    )
    rid = ride["id"]
    assert ride["seats_left"] == 2

    # Поиск участка находит полный рейс и предлагает обе нужные точки.
    seen = kit.find_ride(rid, who=passenger, from_city="Ургаза", to_city="Бурибай")
    assert seen is not None
    assert (seen["from_city"], seen["to_city"]) == ("Сибай", "Акъяр")
    assert (seen["matched_stop"], seen["matched_dropoff_stop"]) == ("Ургаза", "Бурибай")
    assert seen["matched_from_start"] is False and seen["matched_to_end"] is False
    public = api.get(f"/rides/{rid}")
    assert public.status_code == 200, public.text
    assert "pickup_stop" not in public.json() and "dropoff_stop" not in public.json()
    assert public.json()["pickup"] == ""
    assert public.json()["pickup_lat"] is None and public.json()["pickup_lng"] is None
    assert driver.phone not in public.text and passenger.phone not in public.text

    # Явный договорённый участок сохраняется, повтор не списывает второе место.
    payload = {"ride_id": rid, "seats": 1, "pickup_stop": "Ургаза", "dropoff_stop": "Бурибай"}
    created = api.post("/bookings", headers=passenger.auth, json=payload)
    assert created.status_code == 200, created.text
    booking = created.json()
    bid = booking["id"]
    assert booking["status"] == "pending" and booking["price"] == ride["price"]
    assert (booking["pickup_stop"], booking["dropoff_stop"]) == ("Ургаза", "Бурибай")
    repeated = api.post("/bookings", headers=passenger.auth, json=payload)
    assert repeated.status_code == 200, repeated.text
    assert repeated.json()["id"] == bid
    assert (repeated.json()["pickup_stop"], repeated.json()["dropoff_stop"]) == ("Ургаза", "Бурибай")
    assert api.get(f"/rides/{rid}", headers=passenger.auth).json()["seats_left"] == 1

    # До подтверждения контакты/номер/точный пин закрыты, квитанция ещё недоступна.
    for person, role in ((passenger, "passenger"), (driver, "driver")):
        pending = kit.details(person, bid)
        assert pending["role"] == role and pending["status"] == "pending"
        assert pending["contact_unlocked"] is False
        assert pending["driver_phone"] == "" and pending["driver_plate"] == ""
        assert pending["pickup"] == ""
        assert pending["pickup_lat"] is None and pending["pickup_lng"] is None
        assert (pending["pickup_stop"], pending["dropoff_stop"]) == ("Ургаза", "Бурибай")
        assert api.get(f"/trips/{bid}/receipt", headers=person.auth).status_code == 409
    assert api.get(f"/bookings/{bid}/details", headers=stranger.auth).status_code == 403
    assert api.get(f"/trips/{bid}/receipt", headers=stranger.auth).status_code == 403

    assert kit.confirm(driver, bid)["status"] == "confirmed"
    assert kit.notified(passenger, "Бронь подтверждена", ref_id=bid)
    for person, role in ((passenger, "passenger"), (driver, "driver")):
        confirmed = kit.details(person, bid)
        assert confirmed["role"] == role and confirmed["status"] == "confirmed"
        assert confirmed["contact_unlocked"] is True
        assert (confirmed["pickup_stop"], confirmed["dropoff_stop"]) == ("Ургаза", "Бурибай")
    assert kit.details(passenger, bid)["driver_phone"] == driver.phone
    passenger_code = api.get(f"/bookings/{bid}/boarding-code", headers=passenger.auth)
    driver_code = api.get(f"/bookings/{bid}/boarding-code", headers=driver.auth)
    assert passenger_code.status_code == driver_code.status_code == 200
    assert passenger_code.json()["code"] == driver_code.json()["code"]
    assert len(passenger_code.json()["code"]) == 6

    departed = kit.driver_status(driver, bid, "departed")
    assert departed.status_code == 200, departed.text
    assert kit.role(passenger, bid)["driver_phase"] == "departed"

    # После выезда реальное время в пути заменяют только явные QA-часы приложения.
    arrived_at = timeutil.utcnow() + timedelta(minutes=90)
    for module in (bookings, rides, services, timeutil):
        monkeypatch.setattr(module, "utcnow", lambda: arrived_at)
    arriving = kit.driver_status(driver, bid, "arriving")
    assert arriving.status_code == 200, arriving.text
    assert kit.role(passenger, bid)["driver_phase"] == "arriving"
    assert kit.notified(passenger, "Водитель подъезжает", ref_id=bid)
    boarded = kit.driver_status(driver, bid, "boarded")
    assert boarded.status_code == 200, boarded.text
    assert kit.notified(passenger, "Водитель отметил посадку", ref_id=bid)

    # Новые HTTP-чтения обеих ролей сохраняют посадку и обе точки без локального состояния.
    for person in (passenger, driver):
        assert kit.role(person, bid)["status"] == "onboard"
        reopened = kit.details(person, bid)
        assert reopened["status"] == "onboard"
        assert (reopened["pickup_stop"], reopened["dropoff_stop"]) == ("Ургаза", "Бурибай")
    for _ in range(2):
        completed = kit.driver_status(driver, bid, "done")
        assert completed.status_code == 200, completed.text
        assert completed.json()["status"] == "done"
    assert kit.notified(passenger, "Поездка завершена", ref_id=bid)

    # Разные оценки отличают собственную оценку от оценки второй стороны.
    for person, other, stars in ((passenger, driver, 5), (driver, passenger, 3)):
        rated = api.post(f"/bookings/{bid}/rate", headers=person.auth,
                         json={"stars": stars, "text": "", "tags": ""})
        assert rated.status_code == 200, rated.text
        assert rated.json()["ratee_id"] == other.id
    for person, other, role, stars in (
        (passenger, driver, "passenger", 5), (driver, passenger, "driver", 3),
    ):
        receipt = api.get(f"/trips/{bid}/receipt", headers=person.auth)
        assert receipt.status_code == 200, receipt.text
        body = receipt.json()
        assert body["booking_id"] == bid and body["ride_id"] == rid and body["role"] == role
        assert (body["from_city"], body["to_city"]) == ("Сибай", "Акъяр")
        assert (body["pickup_stop"], body["dropoff_stop"]) == ("Ургаза", "Бурибай")
        assert body["my_stars"] == stars and body["counterparty_id"] == other.id
        assert body["amount"] == booking["price"] == ride["price"]
        assert body["seats"] == 1 and body["paid"] is False
        assert driver.phone not in receipt.text and passenger.phone not in receipt.text

    # История обеих сторон хранит ту же единственную бронь с тем же участком.
    for person, path, id_field in (
        (passenger, "/bookings/mine", "id"), (driver, "/driver/bookings", "booking_id"),
    ):
        history = api.get(path, headers=person.auth)
        assert history.status_code == 200, history.text
        matches = [row for row in history.json() if row[id_field] == bid]
        assert len(matches) == 1
        row = matches[0]
        assert row["status"] == "done"
        assert (row["from_city"], row["to_city"]) == ("Сибай", "Акъяр")
        if person is passenger:
            assert row["ride_id"] == rid
        else:
            assert row["my_stars"] == 3
        assert (row["pickup_stop"], row["dropoff_stop"]) == ("Ургаза", "Бурибай")
    assert api.get(f"/bookings/{bid}/details", headers=stranger.auth).status_code == 403
    assert api.get(f"/trips/{bid}/receipt", headers=stranger.auth).status_code == 403

    closed = api.post(f"/rides/{rid}/complete", headers=driver.auth)
    assert closed.status_code == 200, closed.text
    assert closed.json()["status"] == "done"
    assert kit.find_ride(rid, who=passenger, from_city="Ургаза", to_city="Бурибай") is None
    assert kit.role(passenger, bid)["status"] == kit.role(driver, bid)["status"] == "done"
