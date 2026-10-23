# -*- coding: utf-8 -*-
"""Повторы СТАРОГО ключа SOS защищены так же, как повторы обычного (ревью PR190, второй круг, 23.10.2026).

Тот же ключ и то же тело позже 15 минут — новый сигнал (см. test_sos_retry_window). Первая версия
писала такой сигнал БЕЗ ключа, и у повторов старого ключа защиты не оставалось: подряд и параллельно
выходило по событию, по сообщению дежурному и по SMS близким на каждый повтор. Теперь старая строка
отдаёт ключ новому событию в той же транзакции: ручной повтор нового — то же событие без второй
рассылки, проигравший параллельный повтор упирается в уникальный индекс и возвращает свежее событие.
"""
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Barrier

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import SosEvent, TrustedContact
from app.routers import safety
from app.timeutil import utcnow

KEY = "synthetic_stale_sos_key_00001"
BODY = {"category": "other", "note": "Synthetic stale emergency", "lat": 54.1, "lng": 58.2}
STUCK = {"note": "Synthetic stale breakdown", "lat": 54.1, "lng": 58.2}


@pytest.fixture
def emergency(user_factory, monkeypatch):
    user = user_factory("Synthetic stale SOS")
    calls = {"telegram": [], "sms": []}
    monkeypatch.setattr(safety, "notify_admin_telegram", lambda text: calls["telegram"].append(text))
    monkeypatch.setattr(safety, "_send_sos_sms", lambda phones, text: calls["sms"].append(phones))
    monkeypatch.setattr(safety, "sms_will_reach", lambda phones: len(phones))
    monkeypatch.setattr(settings, "sos_sms_to_admin", False)
    with Session(engine) as session:
        session.add(TrustedContact(user_id=user["id"], name="Synthetic", phone="synthetic-contact"))
        session.commit()
    return user, calls


def _post(client, user, kind):
    headers = {**user["auth"], "Idempotency-Key": KEY}
    if kind == "sos":
        return client.post("/sos", headers=headers, json=BODY)
    return client.post("/bookings/999999/stuck", headers=headers, json=STUCK)


def _events(user):
    with Session(engine) as session:
        return session.exec(select(SosEvent).where(SosEvent.user_id == user["id"]).order_by(SosEvent.id)).all()


def _age_all(user, minutes):
    with Session(engine) as session:
        for event in session.exec(select(SosEvent).where(SosEvent.user_id == user["id"])).all():
            event.created_at = utcnow() - timedelta(minutes=minutes)
            session.add(event)
        session.commit()


@pytest.mark.parametrize("kind", ["sos", "stuck"])
def test_sequential_retries_of_a_stale_key_are_one_new_signal(client, emergency, kind):
    user, calls = emergency
    assert _post(client, user, kind).status_code == 200
    _age_all(user, 16)
    new = _post(client, user, kind)          # старый ключ — новый сигнал (ответ на него мог потеряться)…
    retry = _post(client, user, kind)        # …и ручной повтор через секунду
    assert new.status_code == retry.status_code == 200
    assert new.json()["id"] == retry.json()["id"], "повтор того же намерения создал ещё один сигнал"
    events = _events(user)
    assert len(events) == 2                                      # исходный и один новый
    assert events[0].idempotency_key is None and events[1].idempotency_key is not None
    assert len(calls["telegram"]) == 2 and len(calls["sms"]) == 2   # рассылка нового — один раз


@pytest.mark.parametrize("kind", ["sos", "stuck"])
def test_parallel_retries_of_a_stale_key_fan_out_once(client, emergency, kind):
    user, calls = emergency
    assert _post(client, user, kind).status_code == 200
    _age_all(user, 16)
    gate = Barrier(2)

    def go(_):
        gate.wait(timeout=10)
        response = _post(client, user, kind)
        return response.status_code, response.json()["id"]

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(go, range(2)))
    assert [code for code, _id in results] == [200, 200]
    assert results[0][1] == results[1][1]
    assert len(_events(user)) == 2
    assert len(calls["telegram"]) == 2 and len(calls["sms"]) == 2   # исходный + один новый
