
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from uuid import uuid4

from alembic import command
from sqlalchemy import inspect, text
from sqlmodel import Session

from app.card_payments import card_blockers
from app.models import User, UserRole
from app.timeutil import utcnow
from test_last_seat_race import _ride, _seats_left
from test_migration_owner_switches import _isolated_database


def _settings(client, user, methods, expected=None):
    return client.put("/driver/pay-settings", headers=user["auth"], json={
        "methods": methods,
        "expected_updated_at": expected,
    })


def test_settings_store_only_the_users_choice_and_repeat_without_a_new_revision(client, user_factory):
    driver = user_factory("Pay settings owner", role=UserRole.driver)
    other = user_factory("Other settings owner", role=UserRole.driver)
    url = "/driver/pay-settings"
    empty = {"methods": [], "updated_at": None, "card_switch_on": False, "accepts_card": False,
             "card_blockers": card_blockers()}
    assert client.get(url, headers=driver["auth"]).json() == empty
    assert client.get(url).status_code == 401

    initial = _settings(client, driver, ["sbp", "cash"])
    assert initial.status_code == 200, initial.text
    saved = initial.json()
    assert saved["methods"] == ["cash", "sbp"] and saved["updated_at"]
    assert client.get(url, headers=other["auth"]).json() == empty
    assert _settings(client, driver, ["cash", "sbp"]).json() == saved

    conflict = _settings(client, driver, ["cash"])
    assert conflict.status_code == 409
    assert conflict.json()["detail"]["code"] == "pay_settings_changed"
    assert conflict.json()["detail"]["current"] == saved
    for methods in (["cash", "cash"], ["negotiate"], ["card"]):
        bad = _settings(client, driver, methods, saved["updated_at"])
        assert bad.status_code == 400 and bad.json()["detail"]["code"] == "pay_method_unknown"

    cleared = _settings(client, driver, [], saved["updated_at"])
    assert cleared.status_code == 200
    assert cleared.json()["methods"] == []
    assert cleared.json()["updated_at"] != saved["updated_at"]


def test_driver_pay_settings_accepts_pwa_preflight(client):
    response = client.options("/driver/pay-settings", headers={
        "Origin": "http://localhost:5173",
        "Access-Control-Request-Method": "PUT",
        "Access-Control-Request-Headers": "Authorization, Content-Type",
    })
    assert response.status_code == 200
    assert "PUT" in response.headers["access-control-allow-methods"]


def test_two_first_writes_cannot_overwrite_each_other(client, user_factory):
    driver = user_factory("Concurrent settings owner", role=UserRole.driver)
    ready = Barrier(2)

    def choose(method):
        ready.wait(timeout=5)
        return _settings(client, driver, [method])

    with ThreadPoolExecutor(max_workers=2) as pool:
        replies = list(pool.map(choose, ("cash", "sbp")))
    assert sorted(reply.status_code for reply in replies) == [200, 409]
    saved = next(reply.json() for reply in replies if reply.status_code == 200)
    after = client.get("/driver/pay-settings", headers=driver["auth"]).json()
    assert after == saved
    assert next(reply.json()["detail"]["current"] for reply in replies if reply.status_code == 409) == saved


def test_rejected_booking_stays_rejected_after_driver_changes_methods(client, user_factory):
    driver = user_factory("Ride settings owner", role=UserRole.driver)
    passenger = user_factory("Choosing rider")
    ride_id = _ride(client, driver, seats=2)
    details = f"/rides/{ride_id}"
    assert client.get(details, headers=passenger["auth"]).json()["driver_pay_methods"] == []
    saved = _settings(client, driver, ["cash"]).json()
    assert client.get(details, headers=passenger["auth"]).json()["driver_pay_methods"] == ["cash"]
    payload = {"ride_id": ride_id, "pay_method": "sbp"}

    def book(key):
        return client.post("/bookings", headers={**passenger["auth"], "Idempotency-Key": key}, json=payload)

    refused_key = str(uuid4())
    refused = book(refused_key)
    assert refused.status_code == 409
    assert refused.json()["detail"]["code"] == "pay_method_not_accepted"
    assert refused.json()["detail"]["accepted"] == ["cash"]
    assert _seats_left(ride_id) == 2

    changed = _settings(client, driver, ["sbp"], saved["updated_at"]).json()
    replay = book(refused_key)
    assert replay.status_code == 409 and replay.json()["detail"]["code"] == "pay_method_not_accepted"
    assert "accepted" not in replay.json()["detail"]
    assert client.get(details, headers=passenger["auth"]).json()["driver_pay_methods"] == ["sbp"]
    assert _seats_left(ride_id) == 2
    intent = client.get(f"/command-intents/{refused_key}", headers=passenger["auth"]).json()
    assert (intent["state"], intent["result_code"]) == ("rejected", "pay_method_not_accepted")

    accepted_key = str(uuid4())
    accepted = book(accepted_key)
    assert accepted.status_code == 200, accepted.text
    booking_id = accepted.json()["id"]
    assert _seats_left(ride_id) == 1
    assert _settings(client, driver, ["cash"], changed["updated_at"]).status_code == 200
    replay = book(accepted_key)
    assert replay.status_code == 200 and replay.json()["id"] == booking_id
    assert _seats_left(ride_id) == 1
    booking_view = client.get(f"/bookings/{booking_id}/details", headers=passenger["auth"]).json()
    assert booking_view["driver_pay_methods"] == ["cash"] and booking_view["pay_method"] == "sbp"


def test_passenger_agreement_rejects_unsupported_method_while_driver_can_record_it(client, user_factory):
    driver = user_factory("Agreement settings owner", role=UserRole.driver)
    passenger = user_factory("Agreement rider")
    ride_id = _ride(client, driver)
    assert _settings(client, driver, ["cash"]).status_code == 200
    booked = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id})
    assert booked.status_code == 200 and booked.json()["pay_method"] == "negotiate"
    path = f"/bookings/{booked.json()['id']}/pay-agreement"
    refused = client.post(path, headers=passenger["auth"], json={"pay_method": "sbp", "pay_amount": 500})
    assert refused.status_code == 409 and refused.json()["detail"]["accepted"] == ["cash"]
    saved = client.post(path, headers=driver["auth"], json={"pay_method": "sbp"})
    assert saved.status_code == 200 and saved.json()["pay_method"] == "sbp"
    assert saved.json()["pay_amount"] == booked.json()["pay_amount"]


def test_pay_settings_migration_keeps_existing_driver_on_upgrade_and_rollback(tmp_path, monkeypatch):
    db, config = _isolated_database(tmp_path, monkeypatch, "YULDASH_DRIVER_PAY_MIGRATION_DB", "driver-pay.sqlite3")
    previous, current = "cq_paid_external_switches", "cr_driver_pay_settings"
    try:
        assert not inspect(db).get_table_names()
        command.upgrade(config, previous)
        with Session(db) as session:
            driver = User(phone="+79990001061", role=UserRole.driver)
            session.add(driver)
            session.commit()
            uid = driver.id
        command.upgrade(config, current)
        assert any(
            constraint["column_names"] == ["user_id"]
            for constraint in inspect(db).get_unique_constraints("driverpaysettings")
        )
        with Session(db) as session:
            assert session.get(User, uid).role == UserRole.driver
            assert session.execute(text("SELECT id FROM driverpaysettings")).first() is None
            session.execute(
                text("INSERT INTO driverpaysettings (user_id, methods, updated_at) VALUES (:uid, 'sbp', :updated)"),
                {"uid": uid, "updated": utcnow()},
            )
            session.commit()
        command.downgrade(config, previous)
        assert not inspect(db).has_table("driverpaysettings")
        with Session(db) as session:
            assert session.get(User, uid).role == UserRole.driver
        command.upgrade(config, current)
        with Session(db) as session:
            assert session.execute(text("SELECT id FROM driverpaysettings")).first() is None
    finally:
        db.dispose()
