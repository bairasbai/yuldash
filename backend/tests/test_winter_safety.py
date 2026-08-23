"""F12 «Зимний протокол безопасности»: помощь на трассе + авто-проверка «доехал?».

Проверяем: событие в SOS-ленту, рассылку доверенным, права участника, все ветки авто-проверки.
"""
from datetime import timedelta

from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, Ride, SosEvent, TripShare, TrustedContact
from app.timeutil import utcnow

import pytest

from test_flows import _trip


@pytest.fixture(autouse=True)
def _оператор_подключён(monkeypatch):
    """Мир этого файла: канал SMS РАБОТАЕТ, сообщения близким уходят по-настоящему.

    Раньше это подразумевалось молча — и потому не проверялось: на проде канал выключен,
    а тесты всё равно видели «уведомлено: 2», потому что сервер считал намерение, а не факт
    (волна 184). Что честный счёт бывает нулём при молчащем канале — проверяет
    `test_help_counted_is_help_sent.py`.
    """
    from app.config import settings as _s
    monkeypatch.setattr(_s, "sms_provider", "smsru")
    monkeypatch.setattr(_s, "sms_ru_api_id", "test-id")


def _add_contact(client, owner, phone="+79990001122"):
    r = client.post("/trusted-contacts", headers=owner["auth"], json={"name": "Мама", "phone": phone})
    assert r.status_code == 200, r.text
    return r.json()


def _backdate_depart(booking_id: int):
    """Поездка «уже началась» — сдвигаем depart_at в прошлое (тестовый _publish ставит 2030 год)."""
    with Session(engine) as s:
        b = s.get(Booking, booking_id)
        ride = s.get(Ride, b.ride_id)
        ride.depart_at = utcnow() - timedelta(hours=1)
        s.add(ride)
        s.commit()


# ----------------------------- (2) «Застрял на трассе» -----------------------------
def test_stuck_notifies_contacts_and_writes_sos_event(client, user_factory, monkeypatch):
    sent_sms = []
    monkeypatch.setattr("app.routers.safety.send_text", lambda phone, text: sent_sms.append((phone, text)))
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **k: None)

    _drv, pax, _ride, booking = _trip(client, user_factory)
    _add_contact(client, pax, phone="+79995550001")

    r = client.post(
        f"/bookings/{booking['id']}/stuck",
        headers=pax["auth"],
        json={"lat": 52.59, "lng": 58.31, "note": "Заглох, стою на обочине"},
    )
    assert r.status_code == 200, r.text
    event = r.json()
    assert event["category"] == "breakdown"
    assert event["booking_id"] == booking["id"]

    # SOS-событие реально записано в ленту
    with Session(engine) as s:
        rows = s.exec(select(SosEvent).where(SosEvent.id == event["id"])).all()
        assert len(rows) == 1
        assert "трассе" in rows[0].note

    # доверенному ушла SMS с ссылкой на место
    assert len(sent_sms) == 1
    assert sent_sms[0][0] == "+79995550001"
    assert "yandex.ru/maps" in sent_sms[0][1]


def test_stuck_is_participant_only(client, user_factory, monkeypatch):
    monkeypatch.setattr("app.routers.safety.send_text", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **k: None)

    _drv, _pax, _ride, booking = _trip(client, user_factory)
    outsider = user_factory("StuckOutsider")
    assert client.post(f"/bookings/{booking['id']}/stuck", headers=outsider["auth"], json={}).status_code == 403
    assert client.post("/bookings/99999999/stuck", headers=outsider["auth"], json={}).status_code == 404


def test_stuck_without_coords_still_records(client, user_factory, monkeypatch):
    sent_sms = []
    monkeypatch.setattr("app.routers.safety.send_text", lambda phone, text: sent_sms.append((phone, text)))
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **k: None)

    _drv, pax, _ride, booking = _trip(client, user_factory)
    _add_contact(client, pax, phone="+79995550002")
    r = client.post(f"/bookings/{booking['id']}/stuck", headers=pax["auth"], json={"note": "нет GPS"})
    assert r.status_code == 200
    assert len(sent_sms) == 1
    assert "yandex.ru/maps" not in sent_sms[0][1]   # без координат — без ссылки, но сигнал ушёл


# ----------------------------- (1) авто-проверка «доехал?» -----------------------------
def test_winter_check_sends_push_to_both_then_waits(client, user_factory, monkeypatch):
    pushes = []
    monkeypatch.setattr("app.routers.safety.push_bilingual",
                        lambda session, uid, t_ru, t_ba, b_ru, b_ba, data=None: pushes.append(uid))

    drv, pax, _ride, booking = _trip(client, user_factory)
    _backdate_depart(booking["id"])

    r = client.post(f"/bookings/{booking['id']}/winter-check", headers=pax["auth"])
    assert r.status_code == 200
    assert r.json()["state"] == "check_sent"
    assert set(pushes) == {pax["id"], drv["id"]}   # пуш обеим сторонам

    # повторный заход до порога — ещё ждём, без повторного пуша
    pushes.clear()
    r2 = client.post(f"/bookings/{booking['id']}/winter-check", headers=drv["auth"])
    assert r2.json()["state"] == "waiting"
    assert pushes == []


def test_winter_check_too_early_before_departure(client, user_factory, monkeypatch):
    monkeypatch.setattr("app.routers.safety.push_bilingual", lambda *a, **k: None)
    _drv, pax, _ride, booking = _trip(client, user_factory)   # depart_at = 2030 (в будущем)
    r = client.post(f"/bookings/{booking['id']}/winter-check", headers=pax["auth"])
    assert r.json()["state"] == "too_early"


def test_winter_check_escalates_to_contact_after_timeout_with_share(client, user_factory, monkeypatch):
    sent_sms = []
    monkeypatch.setattr("app.routers.safety.push_bilingual", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.safety.send_text", lambda phone, text: sent_sms.append((phone, text)))
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **k: None)

    _drv, pax, _ride, booking = _trip(client, user_factory)
    _backdate_depart(booking["id"])
    contact = _add_contact(client, pax, phone="+79995550003")
    assert client.post(f"/bookings/{booking['id']}/share", headers=pax["auth"], json={"contact_id": contact["id"]}).status_code == 200

    # первый заход — пуш ушёл
    assert client.post(f"/bookings/{booking['id']}/winter-check", headers=pax["auth"]).json()["state"] == "check_sent"
    # состарим отметку об отправке за порог эскалации
    with Session(engine) as s:
        b = s.get(Booking, booking["id"])
        b.winter_check_sent_at = utcnow() - timedelta(minutes=45)
        s.add(b)
        s.commit()

    r = client.post(f"/bookings/{booking['id']}/winter-check", headers=pax["auth"])
    body = r.json()
    assert body["state"] == "escalated"
    assert body["contacts_notified"] == 1
    assert len(sent_sms) == 1 and sent_sms[0][0] == "+79995550003"
    # эскалация записана как SOS-событие
    with Session(engine) as s:
        assert s.get(SosEvent, body["sos_event_id"]) is not None


def test_winter_check_no_share_no_escalation(client, user_factory, monkeypatch):
    sent_sms = []
    monkeypatch.setattr("app.routers.safety.push_bilingual", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.safety.send_text", lambda phone, text: sent_sms.append((phone, text)))

    _drv, pax, _ride, booking = _trip(client, user_factory)
    _backdate_depart(booking["id"])
    client.post(f"/bookings/{booking['id']}/winter-check", headers=pax["auth"])   # check_sent, без шаринга
    with Session(engine) as s:
        b = s.get(Booking, booking["id"])
        b.winter_check_sent_at = utcnow() - timedelta(minutes=45)
        s.add(b)
        s.commit()
    assert client.post(f"/bookings/{booking['id']}/winter-check", headers=pax["auth"]).json()["state"] == "no_share"
    assert sent_sms == []


def test_winter_check_ack_stops_escalation(client, user_factory, monkeypatch):
    sent_sms = []
    monkeypatch.setattr("app.routers.safety.push_bilingual", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.safety.send_text", lambda phone, text: sent_sms.append((phone, text)))

    _drv, pax, _ride, booking = _trip(client, user_factory)
    _backdate_depart(booking["id"])
    contact = _add_contact(client, pax, phone="+79995550004")
    client.post(f"/bookings/{booking['id']}/share", headers=pax["auth"], json={"contact_id": contact["id"]})
    client.post(f"/bookings/{booking['id']}/winter-check", headers=pax["auth"])   # check_sent

    # участник ответил «всё в порядке»
    assert client.post(f"/bookings/{booking['id']}/winter-check/ok", headers=pax["auth"]).json() == {"ok": True}
    with Session(engine) as s:
        b = s.get(Booking, booking["id"])
        b.winter_check_sent_at = utcnow() - timedelta(minutes=45)
        s.add(b)
        s.commit()
    # даже после порога — эскалации нет (ack гасит)
    assert client.post(f"/bookings/{booking['id']}/winter-check", headers=pax["auth"]).json()["state"] == "ok"
    assert sent_sms == []


def test_winter_check_participant_only(client, user_factory, monkeypatch):
    monkeypatch.setattr("app.routers.safety.push_bilingual", lambda *a, **k: None)
    _drv, _pax, _ride, booking = _trip(client, user_factory)
    outsider = user_factory("WinterOutsider")
    assert client.post(f"/bookings/{booking['id']}/winter-check", headers=outsider["auth"]).status_code == 403
    assert client.post(f"/bookings/{booking['id']}/winter-check/ok", headers=outsider["auth"]).status_code == 403
