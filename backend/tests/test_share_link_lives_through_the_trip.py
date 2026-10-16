"""Ссылка «поделиться с близким» доживает до самой поездки.

Приёмка волны 1 (16.10.2026). Срок ссылки считался от момента, когда ею поделились: сутки.
Попутку бронируют заранее — в пятницу на воскресное утро, — и делятся сразу, пока помнят.
В воскресенье мама открывала ссылку и видела «Ссылка не найдена»: срок истёк ещё в субботу.
Нажать «поделиться» ещё раз не помогало — сервер возвращал ту же, уже мёртвую ссылку.

Теперь сутки отсчитываются от выезда (или от момента, когда поделились, если выезд уже был),
а повторное «поделиться» оживляет ту же ссылку — ту, что у мамы уже есть. Вечной ссылка
по-прежнему не становится.
"""
from __future__ import annotations

from datetime import timedelta

from sqlmodel import Session

import app.routers.share as share_mod
from app.config import settings
from app.db import engine
from app.models import Ride, TripShare, UserRole
from app.timeutil import utcnow


def _trip_shared_in_advance(client, user_factory, tag: str, days_ahead: int = 2):
    drv = user_factory(f"{tag}Drv", role=UserRole.driver)
    pax = user_factory(f"{tag}Pax")
    local = (utcnow() + timedelta(hours=settings.local_tz_offset_hours, days=days_ahead)).replace(microsecond=0)
    ride = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Уфа", "seats_total": 3, "price": 700,
        "depart_at": local.isoformat(),
    }).json()
    b = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 1}).json()
    assert client.post(f"/bookings/{b['id']}/confirm", headers=drv["auth"]).status_code == 200
    c = client.post("/trusted-contacts", headers=pax["auth"],
                    json={"name": "Мама", "relation": "мама", "phone": "+79170000521"}).json()
    r = client.post(f"/bookings/{b['id']}/share", headers=pax["auth"], json={"contact_id": c["id"]})
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        depart = s.get(Ride, ride["id"]).depart_at
    return pax, b["id"], c["id"], r.json(), depart


def test_ссылка_жива_в_день_поездки(client, user_factory, monkeypatch):
    _pax, _bid, _cid, share, depart = _trip_shared_in_advance(client, user_factory, "ShareEarly")
    monkeypatch.setattr(share_mod, "utcnow", lambda: depart + timedelta(hours=1))

    r = client.get(f"/t/{share['token']}/state.json")

    assert r.status_code == 200, (
        f"поделились за двое суток — в день поездки мама видит «Ссылка не найдена»: {r.status_code}")


def test_повторное_поделиться_оживляет_ту_же_ссылку(client, user_factory):
    pax, bid, cid, share, _depart = _trip_shared_in_advance(client, user_factory, "ShareRenew", days_ahead=0)
    with Session(engine) as s:                                  # срок истёк (старая строка, до правки)
        row = s.get(TripShare, share["id"])
        row.expires_at = utcnow() - timedelta(minutes=5)
        s.add(row)
        s.commit()

    r = client.post(f"/bookings/{bid}/share", headers=pax["auth"], json={"contact_id": cid})
    assert r.status_code == 200, r.text
    assert r.json()["token"] == share["token"], "ссылка сменилась — у мамы осталась старая, мёртвая"

    st = client.get(f"/t/{share['token']}/state.json")
    assert st.status_code == 200, f"повторное «поделиться» вернуло мёртвую ссылку: {st.status_code}"


def test_ссылка_не_вечная(client, user_factory, monkeypatch):
    """Контроль: через сутки после выезда ссылка гаснет, как и раньше гасла через сутки."""
    _pax, _bid, _cid, share, depart = _trip_shared_in_advance(client, user_factory, "ShareEnd")
    monkeypatch.setattr(share_mod, "utcnow", lambda: depart + timedelta(hours=25))

    r = client.get(f"/t/{share['token']}/state.json")

    assert r.status_code == 404, f"ссылка жива через сутки после выезда — срок перестал работать: {r.status_code}"
