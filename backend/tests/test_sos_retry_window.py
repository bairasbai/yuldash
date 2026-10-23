# -*- coding: utf-8 -*-
"""Повтором SOS считается только свежее событие (ревью PR190, 23.10.2026).

Клиент держит неотправленное намерение не дольше 15 минут (`MAX_AGE_MS` в
webapp/src/utils/sosIntent.ts), а потом заводит новый ключ. Тот же ключ и то же тело позже
окна — не потерянный ответ, а новая беда: «сигнал уже сохранён» молча оставил бы человека
без рассылки близким и дежурному. Внутри окна повтор по-прежнему возвращает то же событие
и ничего не рассылает второй раз.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import SosEvent, TrustedContact
from app.routers import safety
from app.timeutil import utcnow

KEY = "synthetic_sos_window_key_001"
BODY = {"category": "other", "note": "Synthetic window emergency", "lat": 54.1, "lng": 58.2}
STUCK = {"note": "Synthetic window breakdown", "lat": 54.1, "lng": 58.2}


@pytest.fixture
def emergency(user_factory, monkeypatch):
    user = user_factory("Synthetic window SOS")
    calls = {"telegram": [], "sms": []}
    monkeypatch.setattr(safety, "notify_admin_telegram", lambda text: calls["telegram"].append(text))
    monkeypatch.setattr(safety, "_send_sos_sms", lambda phones, text: calls["sms"].append((phones, text)))
    monkeypatch.setattr(safety, "sms_will_reach", lambda phones: len(phones))
    monkeypatch.setattr(settings, "sos_sms_to_admin", False)
    with Session(engine) as session:
        session.add(TrustedContact(user_id=user["id"], name="Synthetic contact", phone="synthetic-contact"))
        session.commit()
    return user, calls


def _post(client, user, kind):
    headers = {**user["auth"], "Idempotency-Key": KEY}
    if kind == "sos":
        return client.post("/sos", headers=headers, json=BODY)
    return client.post("/bookings/999999/stuck", headers=headers, json=STUCK)


def _rows(user):
    with Session(engine) as session:
        return session.exec(select(SosEvent).where(SosEvent.user_id == user["id"]).order_by(SosEvent.id)).all()


def _age(event_id, minutes):
    with Session(engine) as session:
        event = session.get(SosEvent, event_id)
        event.created_at = utcnow() - timedelta(minutes=minutes)
        session.add(event)
        session.commit()


@pytest.mark.parametrize("kind", ["sos", "stuck"])
def test_same_key_and_body_after_the_window_is_a_new_signal(client, emergency, kind):
    user, calls = emergency
    first = _post(client, user, kind)
    assert first.status_code == 200, first.text
    first_id = first.json()["id"]
    assert len(calls["telegram"]) == 1
    _age(first_id, 16)                                   # старше окна повтора

    again = _post(client, user, kind)
    assert again.status_code == 200, again.text
    assert again.json()["id"] != first_id                # новый сигнал, а не «уже сохранён»
    rows = _rows(user)
    assert [row.id for row in rows][0] == first_id and len(rows) == 2
    assert rows[1].idempotency_key is None               # ключ старой строки занят: новый — без ключа
    assert len(calls["telegram"]) == 2                   # и разослан целиком: дежурному ушёл второй сигнал
    assert len(calls["sms"]) == 2                        # близким — тоже


@pytest.mark.parametrize("kind", ["sos", "stuck"])
def test_same_key_and_body_inside_the_window_is_still_the_same_event(client, emergency, kind):
    user, calls = emergency
    first = _post(client, user, kind)
    assert first.status_code == 200, first.text
    first_id = first.json()["id"]
    _age(first_id, 14)                                   # ещё внутри окна повтора

    retry = _post(client, user, kind)
    assert retry.status_code == 200, retry.text
    assert retry.json()["id"] == first_id
    assert len(_rows(user)) == 1
    assert len(calls["telegram"]) == 1 and len(calls["sms"]) == 1   # второй раз не рассылали
