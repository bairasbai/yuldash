"""«Водитель выехал» и «Водитель подъезжает» звонят и ночью.

Приёмка волны 1 (16.10.2026). Тихие часы (22:00–07:00 по Уфе, волна 105) глушат звук у всего,
что «подождёт до утра». Статус «выехал/подъезжает» уходил типом "ride" — тем же, что «оцените
поездку», — и попадал под тишину. А ранний рейс Баймак → Уфа в 5 утра — обычное дело:
пассажир спит до будильника, водитель уже у подъезда, телефон молчит.

Правило тихих часов само говорит: идущая поездка звонит всегда. Здесь проверяем, что это
правда для двух уведомлений, ради которых человек выходит к машине, — и что «оцените поездку»
ночью по-прежнему молчит.
"""
from __future__ import annotations

from datetime import datetime, timedelta

import pytest

import app.services as svc
from app.config import settings
from app.models import UserRole
from app.timeutil import utcnow

NIGHT = datetime(2026, 8, 14, 23, 40)   # 04:40 по Уфе — время раннего рейса


@pytest.fixture(autouse=True)
def тишина_включена(monkeypatch):
    monkeypatch.setattr(svc.settings, "quiet_hours_from", 22)
    monkeypatch.setattr(svc.settings, "quiet_hours_to", 7)


@pytest.fixture
def pushes(monkeypatch) -> list:
    got: list = []
    monkeypatch.setattr(svc, "send_push", lambda session, uid, title, body, **k: got.append((uid, title)))
    return got


def _confirmed_booking(client, user_factory, tag: str):
    drv = user_factory(f"{tag}Drv", role=UserRole.driver)
    pax = user_factory(f"{tag}Pax", role=UserRole.passenger)
    # Выезд через 10 минут: «Подъезжаю» раньше чем за 30 минут до выезда сервер не принимает (приёмка PR184).
    depart = (utcnow() + timedelta(hours=settings.local_tz_offset_hours, minutes=10)).replace(microsecond=0)
    ride = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Уфа", "seats_total": 3, "price": 700,
        "depart_at": depart.isoformat(),
    }).json()
    b = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 1}).json()
    assert client.post(f"/bookings/{b['id']}/confirm", headers=drv["auth"]).status_code == 200
    return drv, pax, b["id"]


@pytest.mark.parametrize("status,title", [("departed", "Водитель выехал"),
                                          ("arriving", "Водитель подъезжает")])
def test_водитель_в_пути_будит_пассажира_ночью(client, user_factory, pushes, monkeypatch, status, title):
    drv, pax, bid = _confirmed_booking(client, user_factory, f"Night{status}")
    pushes.clear()
    monkeypatch.setattr(svc, "utcnow", lambda: NIGHT)

    r = client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": status})
    assert r.status_code == 200, r.text

    assert (pax["id"], title) in pushes, (
        f"«{title}» в 04:40 пришло без звука — пассажир проспит машину: {pushes}")


def test_оценить_поездку_ночью_по_прежнему_молчит(client, user_factory, pushes, monkeypatch):
    """Контроль: исключение узкое — новости ночью не будят."""
    person = user_factory("NightRatePax")
    monkeypatch.setattr(svc, "utcnow", lambda: NIGHT)
    from sqlmodel import Session

    from app.db import engine
    with Session(engine) as s:
        svc.push_notification(s, person["id"], "ride", "Оцените поездку", "Баш", "т", "т",
                              ref_kind="ride", ref_id=person["id"])
    assert not pushes, f"«оцените поездку» разбудило ночью: {pushes}"
