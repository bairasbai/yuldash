"""Renewing readiness after rest must reopen the current day's shift."""
from datetime import datetime, timedelta

import pytest
from sqlmodel import Session, select

from app import pretrip, workday
from app.config import settings
from app.db import engine
from app.models import PreTripCheck, TaxiWorkDay, UserRole


@pytest.mark.parametrize("hours_ago", [10, 8])
def test_same_day_expired_check_can_be_confirmed_again(client, user_factory, monkeypatch, hours_ago):
    now = datetime(2026, 9, 20, 14, 0)
    old = now - timedelta(hours=hours_ago)
    monkeypatch.setattr(pretrip, "utcnow", lambda: now)
    monkeypatch.setattr(workday, "utcnow", lambda: now)
    monkeypatch.setattr(settings, "rest_hours", 8)
    monkeypatch.setattr(settings, "pretrip_check_required", True)
    driver = user_factory("ReadinessRenewal", role=UserRole.driver)
    assert old.date() == now.date()
    assert workday.local_day(old) == workday.local_day(now)
    with Session(engine) as session:
        session.add(PreTripCheck(
            driver_id=driver["id"], day=workday.local_day(now),
            health_ok=True, car_ok=True, no_alcohol=True, created_at=old,
        ))
        session.commit()
        assert not pretrip.is_confirmed(session, driver["id"])

    response = client.post("/taxi/pretrip", headers=driver["auth"], json={
        "health_ok": True, "car_ok": True, "no_alcohol": True, "note": "new shift",
    })
    assert response.status_code == 200, response.text
    assert response.json()["confirmed"] is True
    assert client.get("/taxi/pretrip", headers=driver["auth"]).json()["confirmed"] is True
    with Session(engine) as session:
        pretrip.guard_pretrip(session, driver["id"])
        rows = session.exec(select(PreTripCheck).where(PreTripCheck.driver_id == driver["id"])).all()
        assert len(rows) == 1
        assert rows[0].note == "new shift"
        assert rows[0].created_at == now


@pytest.mark.parametrize("recent_presence", [False, True])
def test_repeated_confirmation_in_current_shift_keeps_original_timestamp(
    client, user_factory, monkeypatch, recent_presence,
):
    now = datetime(2026, 9, 20, 14, 0)
    old = now - timedelta(hours=10 if recent_presence else 2)
    monkeypatch.setattr(pretrip, "utcnow", lambda: now)
    monkeypatch.setattr(workday, "utcnow", lambda: now)
    monkeypatch.setattr(settings, "rest_hours", 8)
    driver = user_factory("ReadinessRepeat", role=UserRole.driver)
    day = workday.local_day(now)
    with Session(engine) as session:
        session.add(PreTripCheck(driver_id=driver["id"], day=day,
            health_ok=True, car_ok=True, no_alcohol=True, created_at=old))
        if recent_presence:
            session.add(TaxiWorkDay(driver_id=driver["id"], day=day,
                seconds_online=3600, last_heartbeat_at=now - timedelta(hours=1)))
        session.commit()
    response = client.post("/taxi/pretrip", headers=driver["auth"], json={
        "health_ok": True, "car_ok": True, "no_alcohol": True, "note": "updated note",
    })
    assert response.status_code == 200, response.text
    assert response.json()["confirmed"] is True
    assert response.json()["confirmed_at"] == old.isoformat()
    with Session(engine) as session:
        rows = session.exec(select(PreTripCheck).where(PreTripCheck.driver_id == driver["id"])).all()
        assert len(rows) == 1
        assert rows[0].note == "updated note"
