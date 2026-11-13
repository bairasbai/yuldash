"""Direct booking for another passenger. Accounts and all IDs are synthetic."""

from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from hashlib import sha256
from threading import Event
from uuid import uuid4

import pytest
from sqlmodel import Session, select

from app.command_fingerprint import parse_body, wire_fingerprint
from app.db import engine
from app.idempotency import Scope, fingerprint
from app.models import Booking, BookingStatus, CommandReceipt, Ride, SosEvent, User, UserRole
from app.routers import bookings as bookings_api
from app.routers import family, location, safety
from app.routers.bookings import BookIn
from app.timeutil import utcnow


TRAVELER = "Ҡунаҡ Һынау"
GUARDIAN = {"minor_passenger": True, "minor_guardian_name": "Тест Взрослый",
            "minor_guardian_phone": "+1 (202) 555-0118"}
COMMAND = "/bookings/for-another/commands/v1"


@pytest.fixture
def scene(user_factory):
    driver = user_factory("Тест Водитель", role=UserRole.driver)
    owner = user_factory("Тест Заказчик", gender="female")
    stranger = user_factory("Тест Посторонний", role=UserRole.driver)
    with Session(engine) as session:
        ride = Ride(driver_id=driver["id"], from_city="Сибай", to_city="Уфа",
                    depart_at=utcnow()+timedelta(hours=2), seats_total=3, seats_left=3, price=500)
        session.add(ride)
        session.commit()
        session.refresh(ride)
        return driver, owner, stranger, ride.id


def post(client, user, ride_id, path="/bookings", key=None, **fields):
    headers = dict(user["auth"])
    if key is not None:
        headers["Idempotency-Key"] = key
    return client.post(path, headers=headers, json={"ride_id": ride_id, **fields})


def bookings_and_seats(ride_id):
    with Session(engine) as session:
        rows = session.exec(select(Booking).where(Booking.ride_id == ride_id)).all()
        return len(rows), session.get(Ride, ride_id).seats_left


def details(client, booking_id, user):
    return client.get(f"/bookings/{booking_id}/details", headers=user["auth"])


def driver_row(client, booking_id, driver):
    result = client.get("/driver/bookings", headers=driver["auth"])
    assert result.headers["Cache-Control"] == "no-store"
    return next(row for row in result.json() if row["booking_id"] == booking_id)


def set_ride_flags(ride_id, **fields):
    with Session(engine) as session:
        ride = session.get(Ride, ride_id)
        for field, value in fields.items():
            setattr(ride, field, value)
        ride.terms_version += 1
        session.add(ride)
        session.commit()


def test_private_name_has_its_own_field_and_window(client, scene):
    driver, owner, stranger, ride_id = scene
    result = post(client, owner, ride_id, traveler_name="  Ҡунаҡ   Һынау  ")
    assert result.status_code == 200, result.text
    booking_id = result.json()["id"]
    assert "traveler_name" not in result.json() and TRAVELER not in result.text
    assert result.json()["passenger_id"] == owner["id"]
    assert bookings_and_seats(ride_id) == (1, 2)
    for user in (driver, owner):
        own = details(client, booking_id, user)
        assert own.status_code == 200
        assert own.headers["Cache-Control"] == "no-store"
        assert own.json()["traveler_name"] == TRAVELER
        assert own.json()["traveler_name_remaining_ms"] == 30000
        assert own.json()["passenger_name"] == "Тест Заказчик"
    assert driver_row(client, booking_id, driver)["traveler_name"] == TRAVELER
    assert details(client, booking_id, stranger).status_code == 403
    assert TRAVELER not in client.get("/driver/bookings", headers=stranger["auth"]).text
    assert TRAVELER not in client.get(f"/rides/{ride_id}").text
    assert TRAVELER not in client.get("/bookings/mine", headers=owner["auth"]).text
    assert TRAVELER not in client.get("/notifications", headers=driver["auth"]).text
    assert TRAVELER not in client.get("/me/export", headers=owner["auth"]).text

    # Exercise the existing server visibility predicate on each persisted stage.
    with Session(engine) as session:
        booking = session.get(Booking, booking_id)
        booking.status = BookingStatus.done
        session.commit()
    for user in (driver, owner):
        assert details(client, booking_id, user).json()["traveler_name"] == TRAVELER
        receipt = client.get(f"/trips/{booking_id}/receipt", headers=user["auth"])
        assert receipt.status_code == 200 and TRAVELER not in receipt.text
    set_ride_flags(ride_id, depart_at=utcnow()-timedelta(days=10))
    for user in (driver, owner):
        data = details(client, booking_id, user).json()
        assert data["traveler_name"] is None and data["traveler_name_remaining_ms"] is None
    assert driver_row(client, booking_id, driver)["traveler_name"] is None


def test_cancel_releases_seat_and_hides_name(client, scene):
    driver, owner, _, ride_id = scene
    created = post(client, owner, ride_id, traveler_name=TRAVELER)
    assert created.status_code == 200, created.text
    booking_id = created.json()["id"]
    result = client.post(f"/bookings/{booking_id}/cancel", headers=owner["auth"])
    assert result.status_code == 200 and TRAVELER not in result.text
    assert bookings_and_seats(ride_id) == (1, 3)
    assert details(client, booking_id, owner).json()["traveler_name"] is None
    assert details(client, booking_id, driver).json()["traveler_name"] is None
    assert driver_row(client, booking_id, driver)["traveler_name"] is None


def test_account_deletion_removes_the_traveler_name(client, scene):
    driver, owner, _, ride_id = scene
    created = post(client, owner, ride_id, traveler_name=TRAVELER)
    assert created.status_code == 200, created.text
    booking_id = created.json()["id"]
    assert client.post(f"/bookings/{booking_id}/cancel", headers=owner["auth"]).status_code == 200
    assert client.post("/me/delete", headers=owner["auth"]).status_code == 200
    assert details(client, booking_id, driver).status_code == 404
    with Session(engine) as session:
        assert session.get(Booking, booking_id) is None


@pytest.mark.parametrize("language", ["ru", "ba"])
def test_pending_share_and_sos_keep_the_owners_rights_and_hide_traveler(client, scene, monkeypatch, language):
    driver, owner, stranger, ride_id = scene
    owner_phone = "+12025550117" if language == "ru" else "+12025550116"
    family_messages, duty_messages, sos_messages = [], [], []
    monkeypatch.setattr(family, "send_text", lambda phone, text: family_messages.append((phone, text)))
    monkeypatch.setattr(safety, "notify_admin_telegram", duty_messages.append)
    monkeypatch.setattr(safety, "_send_sos_sms", lambda phones, text: sos_messages.append((phones, text)))
    created = post(client, owner, ride_id, COMMAND, "cmd1_"+uuid4().hex, traveler_name=TRAVELER, **GUARDIAN)
    assert created.status_code == 200, created.text
    booking_id = created.json()["id"]
    with Session(engine) as session:
        account = session.get(User, owner["id"])
        account.phone = owner_phone
        account.language = language
        session.commit()
    notifications = client.get("/notifications", headers=driver["auth"])
    assert TRAVELER not in notifications.text
    assert "бронирует за другого" in notifications.text and "башҡа кеше өсөн бронлай" in notifications.text
    contact = client.post("/trusted-contacts", headers=owner["auth"], json={
        "name": "Тест Близкий", "phone": "+12025550119",
    })
    assert contact.status_code == 200
    share_path = f"/bookings/{booking_id}/share"
    body = {"contact_id": contact.json()["id"]}
    for user in (driver, stranger):
        assert client.post(share_path, headers=user["auth"], json=body).status_code == 403
    response = client.post(share_path, headers=owner["auth"], json=body)
    assert response.status_code == 200 and TRAVELER not in response.text
    (_, invitation), = family_messages
    assert TRAVELER not in invitation
    assert ("отправил тебе ссылку на попутку" if language == "ru" else "һылтанма ебәрҙе") in invitation
    share = response.json()
    public = f"/t/{share['token']}"
    page = client.get(public)
    assert page.status_code == 200 and TRAVELER not in page.text
    state = client.get(f"{public}/state.json")
    assert state.status_code == 200 and TRAVELER not in state.text
    assert set(state.json()) == {"kind", "status", "phase_text", "from", "to", "car",
                                 "passenger_first_name", "updated_at"}
    assert (state.json()["status"], state.json()["car"], state.json()["passenger_first_name"]) == ("wait", None, "")
    assert client.post(f"/bookings/{booking_id}/cancel").status_code == 401
    sos = {"category": "other", "note": "", "booking_id": booking_id, "lat": 55.22, "lng": 55.33}
    assert client.post("/sos", json=sos).status_code == 401
    assert client.post("/sos", headers=owner["auth"], json=sos).status_code == 200
    assert len(duty_messages) == 1 and TRAVELER not in duty_messages[0]
    assert f"Тел: {owner_phone}" in duty_messages[0] and "Перезвони заказчику" in duty_messages[0]
    assert "yandex.ru/maps/?pt=" not in duty_messages[0]
    (_, sos_text), = sos_messages
    assert ("просит помощи для пассажира" if language == "ru" else "юлсыға ярҙам һорай") in sos_text
    assert TRAVELER not in sos_text and "yandex.ru/maps/?pt=" not in sos_text
    with Session(engine) as session:
        event = session.exec(select(SosEvent).where(SosEvent.booking_id == booking_id)).one()
        assert event.user_id == owner["id"] and "yandex.ru/maps/?pt=" not in event.note

    set_ride_flags(ride_id, depart_at=utcnow()+timedelta(minutes=10))
    assert client.post(f"/bookings/{booking_id}/confirm", headers=driver["auth"]).status_code == 200
    boarded = client.post(f"/bookings/{booking_id}/driver-status", headers=driver["auth"], json={"status": "boarded"})
    assert boarded.status_code == 200, boarded.text
    assert len(family_messages) == 2
    (_, sat) = family_messages[-1]
    assert ("Пассажир сел в машину" if language == "ru" else "Юлсы машинаға ултырҙы") in sat
    assert TRAVELER not in sat and "Тест Заказчик" not in sat
    monkeypatch.setattr(location, "driver_point_for_booking", lambda *_: {"lat": 52.44, "lng": 53.66})
    assert client.post("/sos", headers=owner["auth"], json=sos).status_code == 200
    assert "pt=53.66,52.44" in sos_messages[-1][1] and "pt=55.33,55.22" not in sos_messages[-1][1]
    assert "pt=53.66,52.44" in duty_messages[-1]
    assert client.post(f"/bookings/{booking_id}/cancel", headers=driver["auth"],
                       json={"reason": "other"}).status_code == 200
    (guardian_sms,) = [text for phone, text in family_messages if phone == "+12025550118"]
    assert ("Поездку отменили в пути" if language == "ru" else "Сәфәр юлда кире алынды") in guardian_sms
    assert len(guardian_sms) <= 134 and TRAVELER not in guardian_sms and "Тест Заказчик" not in guardian_sms
    assert client.delete(f"{share_path}/{share['id']}", headers=owner["auth"]).status_code == 200
    assert client.get(f"{public}/state.json").status_code == 404


@pytest.mark.parametrize("path", ["/bookings", "/bookings/commands/v1", COMMAND])
def test_women_only_refuses_booking_for_other_even_for_female_owner(client, scene, path):
    _, owner, _, ride_id = scene
    set_ride_flags(ride_id, women_only=True)
    key = "cmd1_"+uuid4().hex if path != "/bookings" else None
    result = post(client, owner, ride_id, path, key, traveler_name=TRAVELER)
    assert result.status_code == 409 and result.json()["detail"]["code"] == "booking_traveler_women_only"
    if key is not None:
        stored = client.get(f"/command-intents/{key}", headers=owner["auth"]).json()
        assert stored["state"] == "rejected" and stored["result_code"] == "booking_traveler_women_only"
    assert bookings_and_seats(ride_id) == (0, 3)
    assert post(client, owner, ride_id).status_code == 200
    assert bookings_and_seats(ride_id) == (1, 2)


@pytest.mark.parametrize("name", ["", "   ", "Тест 2025550118", "Тест\nСтрока", "Тест\u202eИмя", "Я"*121])
def test_invalid_name_does_not_reserve_a_seat(client, scene, name):
    _, owner, _, ride_id = scene
    result = post(client, owner, ride_id, COMMAND, "cmd1_"+uuid4().hex, traveler_name=name)
    assert result.status_code == 422
    detail = result.json()["detail"]
    assert set(detail) == {"ru", "ba"}
    assert all(text.endswith("traveler_name") for text in detail.values())
    assert bookings_and_seats(ride_id) == (0, 3)


def test_minor_needs_responsible_adult_and_ride_must_allow_minors(client, scene):
    driver, owner, _, ride_id = scene
    set_ride_flags(ride_id, no_minors=True)
    refuse = post(client, owner, ride_id, traveler_name=TRAVELER, **GUARDIAN)
    assert refuse.status_code == 409
    set_ride_flags(ride_id, no_minors=False)
    bad = dict(GUARDIAN, minor_guardian_phone="123")
    refuse = post(client, owner, ride_id, traveler_name=TRAVELER, **bad)
    assert refuse.status_code == 400 and refuse.json()["detail"]["code"] == "guardian_phone_invalid"
    assert bookings_and_seats(ride_id) == (0, 3)
    created = post(client, owner, ride_id, traveler_name=TRAVELER, **GUARDIAN)
    assert created.status_code == 200, created.text
    booking_id = created.json()["id"]
    view = details(client, booking_id, driver).json()
    assert view["traveler_name"] == TRAVELER and view["minor_guardian_phone"] == "+12025550118"
    assert view["passenger_phone"] == ""
    assert details(client, booking_id, owner).json()["minor_guardian_phone"] == ""


def test_existing_booking_does_not_change_traveler_or_adult_contact(client, scene):
    _, owner, _, ride_id = scene
    first = post(client, owner, ride_id, traveler_name=TRAVELER, **GUARDIAN)
    assert first.status_code == 200, first.text
    booking_id = first.json()["id"]
    repeated = post(client, owner, ride_id, traveler_name=TRAVELER,
                    **dict(GUARDIAN, minor_guardian_phone="+12025550118"))
    assert repeated.status_code == 200 and repeated.json()["id"] == booking_id
    for fields in (
        {}, {"traveler_name": "Другое Имя", **GUARDIAN}, {"traveler_name": TRAVELER},
        {"traveler_name": TRAVELER, **dict(GUARDIAN, minor_guardian_name="Другой Взрослый")},
        {"traveler_name": TRAVELER, **dict(GUARDIAN, minor_guardian_phone="+12025550119")},
    ):
        result = post(client, owner, ride_id, "/bookings/commands/v1", "cmd1_"+uuid4().hex, **fields)
        assert result.status_code == 409 and result.json()["detail"]["booking_id"] == booking_id
        assert result.json()["detail"]["code"] == "booking_traveler_conflict"
    assert bookings_and_seats(ride_id) == (1, 2)


def test_rejected_repeat_points_only_to_original_active_booking(client, scene):
    _, owner, stranger, ride_id = scene
    existing = post(client, owner, ride_id)
    booking_id = existing.json()["id"]
    key = "cmd1_"+uuid4().hex
    for _ in range(2):
        conflict = post(client, owner, ride_id, COMMAND, key, traveler_name=TRAVELER)
        assert conflict.status_code == 409 and conflict.json()["detail"]["booking_id"] == booking_id
        assert TRAVELER not in conflict.text
    intent = f"/command-intents/{key}"
    recovery = client.get(intent, headers=owner["auth"])
    assert recovery.headers["Cache-Control"] == "no-store"
    rejected = recovery.json()
    assert (rejected["state"], rejected["result_code"], rejected["booking_id"]) == (
        "rejected", "booking_traveler_conflict", booking_id,
    )
    assert client.get(intent, headers=stranger["auth"]).json()["state"] == "unseen"

    assert client.post(f"/bookings/{booking_id}/cancel", headers=owner["auth"]).status_code == 200
    replacement = post(client, owner, ride_id)
    assert replacement.status_code == 200 and replacement.json()["id"] != booking_id
    conflict = post(client, owner, ride_id, COMMAND, key, traveler_name=TRAVELER)
    assert conflict.status_code == 409 and "booking_id" not in conflict.json()["detail"]
    assert "booking_id" not in client.get(intent, headers=owner["auth"]).json()
    assert bookings_and_seats(ride_id) == (2, 2)


@pytest.mark.skipif(engine.dialect.name != "sqlite", reason="PostgreSQL locks the ride before reserving")
@pytest.mark.parametrize("changes, existing, code", [
    ({"women_only": True}, True, "booking_traveler_women_only"),
    ({"no_minors": True}, True, "booking_traveler_no_minors"),
    ({"price": 550}, False, "ride_terms_changed"),
])
def test_legacy_race_rechecks_changed_ride_before_reusing_or_reserving(
    client, scene, monkeypatch, changes, existing, code,
):
    _, owner, _, ride_id = scene
    reached, resume = Event(), Event()
    take = bookings_api.ride_seats.take

    def pause_before_reservation(*args, **kwargs):
        if not reached.is_set():
            reached.set()
            assert resume.wait(timeout=10)
        return take(*args, **kwargs)

    monkeypatch.setattr(bookings_api.ride_seats, "take", pause_before_reservation)
    traveler = {"traveler_name": TRAVELER, **(GUARDIAN if "no_minors" in changes else {})}
    with ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(post, client, owner, ride_id, **traveler)
        try:
            assert reached.wait(timeout=10)
            if existing:
                accepted = post(client, owner, ride_id, **traveler)
                assert accepted.status_code == 200, accepted.text
            set_ride_flags(ride_id, **changes)
        finally:
            resume.set()
        rejected = pending.result(timeout=20)
    assert rejected.status_code == 409 and rejected.json()["detail"]["code"] == code
    assert bookings_and_seats(ride_id) == ((1, 2) if existing else (0, 3))


def test_applied_key_returns_same_booking_after_ride_changes_and_keeps_name_private(client, scene):
    _, owner, _, ride_id = scene
    key = "cmd1_"+uuid4().hex
    first = post(client, owner, ride_id, COMMAND, key, traveler_name=TRAVELER)
    assert first.status_code == 200, first.text
    booking_id = first.json()["id"]
    set_ride_flags(ride_id, women_only=True)
    replay = post(client, owner, ride_id, COMMAND, key, traveler_name=TRAVELER)
    assert replay.status_code == 200 and replay.json()["id"] == booking_id
    assert TRAVELER not in replay.text
    wrong = post(client, owner, ride_id, COMMAND, key, traveler_name="Другое Имя")
    assert wrong.status_code == 409
    saved = client.get(f"/command-intents/{key}", headers=owner["auth"])
    assert saved.json()["state"] == "applied" and saved.json()["current"]["id"] == booking_id
    assert "traveler_name" not in saved.json()["current"] and TRAVELER not in saved.text
    with Session(engine) as session:
        row = session.exec(select(CommandReceipt).where(CommandReceipt.request_key == key)).one()
        assert len(row.payload_hash) == 64 and TRAVELER not in str(row.model_dump())
    assert bookings_and_seats(ride_id) == (1, 2)


def test_capability_and_ordinary_booking_fingerprints_are_compatible(client, scene):
    _, owner, _, ride_id = scene
    capability = client.get("/bookings/capabilities")
    assert capability.json() == {"traveler_name": True}
    assert capability.headers["Cache-Control"] == "no-store"
    missing = post(client, owner, ride_id, COMMAND, "cmd1_"+uuid4().hex)
    assert missing.status_code == 422 and bookings_and_seats(ride_id) == (0, 3)
    body = b'{"ride_id":73,"seats":2}'
    expected = sha256(b'{"body":{"ride_id":73,"seats":2},"scope":"booking.create","target_id":73}').hexdigest()
    assert wire_fingerprint(Scope.BOOKING_CREATE.value, 73, parse_body(body)) == expected
    assert fingerprint(Scope.BOOKING_CREATE, 73, BookIn.model_validate_json(body)) == expected
    assert post(client, owner, ride_id).status_code == 200
    assert bookings_and_seats(ride_id) == (1, 2)
