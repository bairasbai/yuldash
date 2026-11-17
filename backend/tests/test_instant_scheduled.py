# -*- coding: utf-8 -*-
"""Предзаказ такси «на время» (MVP): создание, список только свои, отмена,
активация (ручная и ленивая при GET), запрет прошлого/слишком далёкого времени."""
from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
from app.timeutil import utcnow

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


def _body(when, frm=ORIG, to=DEST, **extra):
    return {"from_lat": frm[0], "from_lng": frm[1], "to_lat": to[0], "to_lng": to[1],
            "from_text": "Баймак", "to_text": "Сибай",
            "scheduled_at": when.isoformat(), **extra}


def _schedule(client, pax, when, **extra):
    return client.post("/instant/schedule", headers=pax["auth"], json=_body(when, **extra))


def test_create_scheduled_future(client, user_factory):
    pax = user_factory("SchedPax")
    r = _schedule(client, pax, utcnow() + timedelta(hours=3))
    assert r.status_code == 200, r.text
    o = r.json()
    assert o["status"] == "scheduled"
    assert o["scheduled_at"] is not None
    assert o["price_estimate"] > 0   # предварительная оценка показана


def test_reject_past_time(client, user_factory):
    pax = user_factory("SchedPast")
    r = _schedule(client, pax, utcnow() - timedelta(minutes=5))
    assert r.status_code == 422, r.text


def test_reject_too_far(client, user_factory):
    pax = user_factory("SchedFar")
    r = _schedule(client, pax, utcnow() + timedelta(days=30))
    assert r.status_code == 422, r.text


def test_list_only_mine(client, user_factory):
    a = user_factory("SchedA")
    b = user_factory("SchedB")
    when = utcnow() + timedelta(hours=5)
    a_response = _schedule(client, a, when)
    b_response = _schedule(client, b, when)
    assert a_response.status_code == 200, a_response.text
    assert b_response.status_code == 200, b_response.text
    ra, rb = a_response.json(), b_response.json()
    assert ra["id"] != rb["id"]
    # Проверяем обе стороны: чужой заказ запрещён, собственный не потерян.
    for passenger, own, other in ((a, ra, rb), (b, rb, ra)):
        response = client.get("/instant/scheduled", headers=passenger["auth"])
        assert response.status_code == 200, response.text
        view = response.json()
        ids = [order["id"] for order in view["scheduled"]]
        assert ids == [own["id"]]
        assert other["id"] not in ids
        assert view["activated"] == []


def test_cancel_scheduled(client, user_factory):
    pax = user_factory("SchedCancel")
    o = _schedule(client, pax, utcnow() + timedelta(hours=2)).json()
    r = client.post(f"/instant/scheduled/{o['id']}/cancel", headers=pax["auth"])
    assert r.status_code == 200 and r.json()["status"] == "cancelled"
    # больше не в списке будущих
    mine = client.get("/instant/scheduled", headers=pax["auth"]).json()
    assert o["id"] not in [x["id"] for x in mine["scheduled"]]


def test_manual_activate(client, user_factory):
    pax = user_factory("SchedActivate")
    o = _schedule(client, pax, utcnow() + timedelta(hours=1)).json()
    r = client.post(f"/instant/scheduled/{o['id']}/activate", headers=pax["auth"])
    assert r.status_code == 200, r.text
    # ушёл из scheduled в обычный поток (без Redis matcher никого не находит → expired)
    assert r.json()["status"] != "scheduled"
    # повторная активация — уже не scheduled → 409
    assert client.post(f"/instant/scheduled/{o['id']}/activate", headers=pax["auth"]).status_code == 409


def test_lazy_activation_when_due(client, user_factory):
    pax = user_factory("SchedLazy")
    o = _schedule(client, pax, utcnow() + timedelta(hours=1)).json()
    # эмулируем «время подошло»: сдвигаем scheduled_at в прошлое напрямую в БД
    with Session(engine) as s:
        row = s.get(InstantOrder, o["id"])
        row.scheduled_at = utcnow() - timedelta(minutes=1)
        s.add(row)
        s.commit()
    resp = client.get("/instant/scheduled", headers=pax["auth"]).json()
    # активировался лениво при GET: попал в activated, ушёл из scheduled
    assert o["id"] in [x["id"] for x in resp["activated"]]
    assert o["id"] not in [x["id"] for x in resp["scheduled"]]
    with Session(engine) as s:
        assert s.get(InstantOrder, o["id"]).status != S.scheduled
