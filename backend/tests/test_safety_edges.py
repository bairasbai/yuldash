"""Regression tests for safety, reports, blocks, and reportable users."""

from sqlmodel import Session

from app.db import engine
from app.models import TrustedContact, UserRole

from test_flows import _trip


def test_sos_notifies_contacts_then_applies_hourly_sms_cap(client, user_factory, monkeypatch):
    sent_sms = []
    admin_messages = []

    monkeypatch.setattr("app.routers.safety.send_text", lambda phone, text: sent_sms.append((phone, text)))
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda text: admin_messages.append(text))

    user = user_factory("SosCapUser")
    with Session(engine) as session:
        session.add(TrustedContact(user_id=user["id"], name="Trusted", phone="+79990000200"))
        session.commit()

    for idx in range(7):
        response = client.post("/sos", headers=user["auth"], json={"category": "other", "note": f"event {idx}"})
        assert response.status_code == 200

    assert len(sent_sms) == 6
    assert all(phone == "+79990000200" for phone, _text in sent_sms)
    assert len(admin_messages) == 7


def test_sos_rejects_unknown_category(client, user_factory):
    """WP-9: категория SOS — закрытый список; произвольная строка отклоняется (422)."""
    user = user_factory("SosCat")
    assert client.post("/sos", headers=user["auth"], json={"category": "other"}).status_code == 200
    assert client.post("/sos", headers=user["auth"], json={"category": "<script>"}).status_code == 422


def test_sos_booking_access_is_limited_to_trip_participants(client, user_factory, monkeypatch):
    monkeypatch.setattr("app.routers.safety.send_text", lambda _phone, _text: None)
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda _text: None)
    _driver, passenger, _ride, booking = _trip(client, user_factory)
    outsider = user_factory("SosOutsider")

    assert client.post(
        "/sos",
        headers=outsider["auth"],
        json={"category": "medical", "booking_id": booking["id"]},
    ).status_code == 403
    assert client.post(
        "/sos",
        headers=passenger["auth"],
        json={"category": "medical", "booking_id": booking["id"]},
    ).status_code == 200
    assert client.post(
        "/sos",
        headers=passenger["auth"],
        json={"category": "medical", "booking_id": 99999999},
    ).status_code == 404


def test_callback_and_admin_reports(client, user_factory, monkeypatch):
    admin_messages = []
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda text: admin_messages.append(text))

    reporter = user_factory("ReportReporter")
    target = user_factory("ReportTarget")
    admin = user_factory("ReportAdmin", role=UserRole.admin)

    callback = client.post("/callback", headers=reporter["auth"], json={"note": "Call me"})
    assert callback.status_code == 200
    assert callback.json() == {"ok": True}
    assert admin_messages

    assert client.get("/admin/reports", headers=reporter["auth"]).status_code == 403
    created = client.post("/reports", headers=reporter["auth"], json={"target_user_id": target["id"], "reason": "unsafe"})
    assert created.status_code == 200

    reports = client.get("/admin/reports", headers=admin["auth"])
    assert reports.status_code == 200
    assert any(row["reason"] == "unsafe" and row["target_name"] == "ReportTarget" for row in reports.json())


def test_blocks_are_idempotent_listed_and_removable(client, user_factory):
    user = user_factory("BlockOwner")
    target = user_factory("BlockTarget")

    assert client.post("/blocks", headers=user["auth"], json={"blocked_user_id": 99999999}).status_code == 404
    created = client.post("/blocks", headers=user["auth"], json={"blocked_user_id": target["id"]})
    duplicate = client.post("/blocks", headers=user["auth"], json={"blocked_user_id": target["id"]})
    assert created.status_code == 200
    assert duplicate.status_code == 200
    assert duplicate.json()["id"] == created.json()["id"]

    listed = client.get("/blocks", headers=user["auth"])
    assert listed.status_code == 200
    # Имя в списке видно ТОЛЬКО знакомым (волна 120): эти двое вместе не ездили, поэтому
    # вместо имени — «Пользователь». Иначе перебором «заблокировать 1, 2, 3…» выгружался
    # справочник «номер → имя» всего района. Сама блокировка по-прежнему проходит.
    assert any(row["blocked_user_id"] == target["id"] and row["name"] == "Пользователь" for row in listed.json())

    assert client.delete(f"/blocks/{target['id']}", headers=user["auth"]).status_code == 200
    assert all(row["blocked_user_id"] != target["id"] for row in client.get("/blocks", headers=user["auth"]).json())


def test_reportable_users_are_limited_to_trip_counterparties(client, user_factory):
    driver, passenger, _ride, _booking = _trip(client, user_factory)
    stranger = user_factory("NoSharedTrip")

    passenger_rows = client.get("/reportable-users", headers=passenger["auth"])
    assert passenger_rows.status_code == 200
    assert any(row["id"] == driver["id"] for row in passenger_rows.json())
    assert all(row["id"] != stranger["id"] for row in passenger_rows.json())

    driver_rows = client.get("/reportable-users", headers=driver["auth"])
    assert driver_rows.status_code == 200
    assert any(row["id"] == passenger["id"] for row in driver_rows.json())

    empty = client.get("/reportable-users", headers=stranger["auth"])
    assert empty.status_code == 200
    assert empty.json() == []
