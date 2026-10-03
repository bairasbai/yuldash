# -*- coding: utf-8 -*-
"""Сырой текст чужого исключения в логе может нести телефон (§8 CLAUDE.md, 152-ФЗ) — тот же
класс ошибки, что чинили в routers/safety.py (E1, независимое ревью leaf-3.1): SQLAlchemy
кладёт параметры запроса прямо в текст `IntegrityError`, а лог сервера читает не только
разработчик (диск, выгрузки, разбор инцидента).

`incident_escalate.escalate_silent_incidents` ловит сбой `push_notification` и раньше писал
`{e}` напрямую. push_notification умеет падать не только из-за БД (у её собственного try/except
на запись в БД нет защиты на втором шаге — отправку пуша/чтение User — то есть исключение
снаружи того try вполне реально), и его текст — чужой, непроверенный."""
from __future__ import annotations

import logging

from sqlmodel import Session

from app import incident_escalate
from app.db import engine
from app.models import Incident, UserRole

from test_api import _ride


ТЕЛЕФОН = "+79997776655"


def _молчащее_дело(client, user_factory) -> int:
    водитель = user_factory("ЛогВодитель", role=UserRole.driver)
    пассажирка = user_factory("ЛогПассажирка")
    ride_id = _ride(client, водитель, comment="проверка лога")
    bid = client.post("/bookings", headers=пассажирка["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    дело = client.post("/incidents", headers=пассажирка["auth"], json={
        "respondent_id": водитель["id"], "type": "rude", "booking_id": bid,
        "description": "грубил",
    }).json()
    with Session(engine) as s:
        inc = s.get(Incident, дело["id"])
        from datetime import timedelta
        from app.timeutil import utcnow
        inc.created_at = utcnow() - timedelta(days=incident_escalate.RESPONSE_DAYS + 1)
        s.add(inc)
        s.commit()
        return inc.id


def test_сбой_push_при_эскалации_не_пишет_телефон_в_лог(client, user_factory, monkeypatch, caplog):
    iid = _молчащее_дело(client, user_factory)

    def _падает(*args, **kwargs):
        raise RuntimeError(f"FCM отказал для абонента {ТЕЛЕФОН}")

    monkeypatch.setattr("app.services.push_notification", _падает)

    with caplog.at_level(logging.WARNING, logger="yuldash"):
        with Session(engine) as s:
            incident_escalate.escalate_silent_incidents(s)

    текст_лога = "\n".join(r.message for r in caplog.records)
    assert ТЕЛЕФОН not in текст_лога, f"телефон попал в лог сервера: {текст_лога!r}"
    assert "push failed" in текст_лога, (
        "сам факт сбоя должен остаться виден — не ошибка должна исчезнуть, а только номер"
    )


def test_дело_всё_равно_переходит_на_разбор_даже_если_push_упал(client, user_factory, monkeypatch):
    """Уведомление вторично: сбой push не должен держать дело в «ждём объяснения» навсегда."""
    iid = _молчащее_дело(client, user_factory)
    monkeypatch.setattr("app.services.push_notification",
                        lambda *a, **kw: (_ for _ in ()).throw(RuntimeError("boom")))

    with Session(engine) as s:
        incident_escalate.escalate_silent_incidents(s)
        inc = s.get(Incident, iid)
        assert inc.status == "under_review", "сбой уведомления не должен хоронить переход дела"
