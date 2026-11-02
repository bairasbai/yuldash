# -*- coding: utf-8 -*-
"""RS-03 Б: гонки команд смены места — на настоящих замках PostgreSQL.

Матрица плана §7: proposal↔proposal одной версии — ровно один победитель, версия +1;
answer↔cancel — терминал не воскресает (точка не применяется к отменённой);
старый withdraw↔новый proposal — поздний отзыв не стирает новое предложение;
answer (pickup)↔посадка — место посадки после посадки не меняется.
На SQLite замков нет — детерминированные варианты живут в test_meeting_commands.py;
здесь — настоящий CAS по meet_version + статусу + прежнему JSON.
"""
from __future__ import annotations

import json
import threading
from datetime import timedelta

import pytest
from sqlmodel import Session

from app.db import engine
from app.geo import snapshot_cached
from app.models import Booking, BookingStatus, Ride, UserRole
from app.ride_stops import stop_point
from app.timeutil import utcnow

pytestmark = [
    pytest.mark.skipif(engine.dialect.name != "postgresql", reason="гонка на замках — только PostgreSQL"),
    pytest.mark.timeout(90),
]

STOPS = "Ургаза | Бурибай"
SIBAY = (52.716, 58.664)


def _mk_ride(client, drv):
    body = {"from_city": "Сибай", "to_city": "Акъяр",
            "depart_at": (utcnow() + timedelta(days=300)).isoformat() + "+00:00",
            "seats_total": 3, "price": 700, "comment": "pg-meet-race", "waypoints": STOPS,
            "pickup": "У автовокзала", "pickup_lat": SIBAY[0], "pickup_lng": SIBAY[1]}
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _mk_confirmed(client, user_factory, tag):
    drv = user_factory(f"PGR {tag} Driver", role=UserRole.driver)
    pax = user_factory(f"PGR {tag} Pax")
    ride = _mk_ride(client, drv)
    with Session(engine) as s:
        sp = stop_point(s.get(Ride, ride["id"]), "Ургаза", snapshot_cached())
        assert sp is not None
        meet = (sp.lat + 0.001, sp.lng + 0.001)
    b = client.post("/bookings", headers=pax["auth"],
                    json={"ride_id": ride["id"], "seats": 1, "pickup_stop": "Ургаза",
                          "meeting": {"lat": meet[0], "lng": meet[1], "note": "у школы"}})
    assert b.status_code == 200, b.text
    bid = b.json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    return drv, pax, ride, bid, meet


def _booking(bid: int) -> Booking:
    with Session(engine) as s:
        return s.get(Booking, bid)


def _propose(client, pax, bid, pid, base, note="у магазина", point=(52.716, 58.664)):
    return client.post(f"/bookings/{bid}/meeting-point", headers=pax["auth"],
                       json={"kind": "pickup", "point": {"lat": point[0], "lng": point[1], "note": note},
                             "base_version": base, "proposal_id": pid})


def _driver_id(ride):
    with Session(engine) as session:
        return session.get(Ride, ride["id"]).driver_id


@pytest.fixture
def meeting_pushes(monkeypatch):
    """Считаем команды уведомления после commit, без внешней отправки из потоков."""
    import app.routers.meeting as commands
    events = []
    lock = threading.Lock()

    def capture(session, uid, title_ru, title_ba, body_ru, body_ba, booking_id):
        with lock:
            events.append((uid, title_ru, booking_id))

    monkeypatch.setattr(commands, "_push", capture)
    return events


def _race(first, second, barrier):
    workers = (first, second)
    started = []
    try:
        for worker in workers:
            worker.start()
            started.append(worker)
        for worker in started:
            worker.join(20)
    finally:
        barrier.abort()
        for worker in started:
            worker.join(5)
    assert all(not worker.is_alive() for worker in started), "race workers did not finish"


def test_two_proposals_one_winner_pg(client, user_factory, meeting_pushes):
    """Два устройства пассажира шлют предложение с ОДНОЙ версии: CAS пускает ровно одно."""
    drv, pax, ride, bid, meet = _mk_confirmed(client, user_factory, "twoprop")
    ver = _booking(bid).meet_version
    barrier = threading.Barrier(2)
    results: dict[str, int] = {}
    lock = threading.Lock()

    def prop(pid):
        barrier.wait(timeout=10)
        r = _propose(client, pax, bid, pid, ver, point=meet)
        with lock:
            results[pid] = r.status_code

    t1 = threading.Thread(target=prop, args=("p-a",))
    t2 = threading.Thread(target=prop, args=("p-b",))
    _race(t1, t2, barrier)
    assert len(results) == 2, results
    assert sorted(results.values()) == [200, 409], results
    b = _booking(bid)
    assert b.meet_version == ver + 1, "версия поднялась ровно один раз"
    winner = json.loads(b.meet_proposal)["proposal_id"]
    assert winner in ("p-a", "p-b")
    assert meeting_pushes == [(_driver_id(ride), "Новое предложение", bid)]
    replay = _propose(client, pax, bid, winner, ver, point=meet)
    assert replay.status_code == 200 and replay.json()["already"] is True
    assert len(meeting_pushes) == 1


def test_answer_vs_cancel_terminal_does_not_resurrect_pg(client, user_factory, meeting_pushes):
    """Ответ водителя против отмены пассажира: кто первый — того и переход; мёртвое не оживает."""
    drv, pax, ride, bid, meet = _mk_confirmed(client, user_factory, "anxcancel")
    ver0 = _booking(bid).meet_version
    assert _propose(client, pax, bid, "p-x", ver0, point=meet).status_code == 200
    ver = _booking(bid).meet_version
    barrier = threading.Barrier(2)
    results: dict[str, int] = {}
    lock = threading.Lock()

    def answer():
        barrier.wait(timeout=10)
        r = client.post(f"/bookings/{bid}/meeting-point/answer", headers=drv["auth"],
                        json={"proposal_id": "p-x", "base_version": ver, "accept": True})
        with lock:
            results["answer"] = r.status_code

    def cancel():
        barrier.wait(timeout=10)
        r = client.post(f"/bookings/{bid}/cancel", headers=pax["auth"], json={"reason": "changed_mind"})
        with lock:
            results["cancel"] = r.status_code

    t1 = threading.Thread(target=answer)
    t2 = threading.Thread(target=cancel)
    _race(t1, t2, barrier)
    assert len(results) == 2, results
    # Два честных исхода гонки: ответ успел до отмены (обе 200 — оба перехода легальны подряд)
    # или отмена первой (ответу 409). Главное — финал один: бронь отменена, не воскресла.
    assert sorted(results.values()) in ([200, 200], [200, 409]), results
    b = _booking(bid)
    assert b.status == BookingStatus.cancelled and b.meet_proposal == "", \
        "терминал не воскресает, предложение снято"
    if results["answer"] == 409:
        assert b.meet_note != "у магазина", "отмена выиграла раньше — точка не применилась"
    applied = b.meet_note == "у магазина"
    expected = [(_driver_id(ride), "Новое предложение", bid)]
    if applied:
        expected.append((b.passenger_id, "Ответ водителя", bid))
    assert meeting_pushes == expected
    replay = client.post(f"/bookings/{bid}/meeting-point/answer", headers=drv["auth"],
                         json={"proposal_id": "p-x", "base_version": ver, "accept": True})
    assert replay.status_code == 200 and replay.json()["already"] is True
    assert meeting_pushes == expected


def test_late_withdraw_does_not_kill_new_proposal_pg(client, user_factory, meeting_pushes):
    """Отзыв СТАРОГО id против нового предложения: новое сохраняется (409 у позднего)."""
    drv, pax, ride, bid, meet = _mk_confirmed(client, user_factory, "latewd")
    ver = _booking(bid).meet_version
    assert _propose(client, pax, bid, "p-old", ver, point=meet).status_code == 200
    ver1 = _booking(bid).meet_version
    barrier = threading.Barrier(2)
    results: dict[str, int] = {}
    lock = threading.Lock()

    def withdraw_old():
        barrier.wait(timeout=10)
        r = client.post(f"/bookings/{bid}/meeting-point/withdraw", headers=pax["auth"],
                        json={"proposal_id": "p-old", "base_version": ver1})
        with lock:
            results["withdraw"] = r.status_code

    def propose_new():
        barrier.wait(timeout=10)
        r = _propose(client, pax, bid, "p-new", ver1, point=meet)
        with lock:
            results["propose"] = r.status_code

    t1 = threading.Thread(target=withdraw_old)
    t2 = threading.Thread(target=propose_new)
    _race(t1, t2, barrier)
    assert len(results) == 2, results
    assert sorted(results.values()) == [200, 409], results
    b = _booking(bid)
    if results["propose"] == 200:
        assert json.loads(b.meet_proposal)["proposal_id"] == "p-new", \
            "новое предложение пережило поздний отзыв старого"
    else:
        assert results["withdraw"] == 200 and b.meet_proposal == ""
    assert b.meet_version == ver1 + 1
    title = "Новое предложение" if results["propose"] == 200 else "Предложение отозвано"
    expected = [(_driver_id(ride), "Новое предложение", bid), (_driver_id(ride), title, bid)]
    assert meeting_pushes == expected
    if results["propose"] == 200:
        replay = _propose(client, pax, bid, "p-new", ver1, point=meet)
    else:
        replay = client.post(f"/bookings/{bid}/meeting-point/withdraw", headers=pax["auth"],
                             json={"proposal_id": "p-old", "base_version": ver1})
    assert replay.status_code == 200 and replay.json()["already"] is True
    assert meeting_pushes == expected


def test_answer_pickup_vs_boarded_pg(client, user_factory, meeting_pushes):
    """Ответ «да» по посадке против отметки «Сел»: место посадки после посадки не меняется."""
    drv, pax, ride, bid, meet = _mk_confirmed(client, user_factory, "ansboard")
    with Session(engine) as session:
        row = session.get(Ride, ride["id"])
        row.depart_at = utcnow() - timedelta(minutes=1)
        session.add(row)
        session.commit()
    ver = _booking(bid).meet_version
    assert _propose(client, pax, bid, "p-up", ver, point=meet).status_code == 200
    ver1 = _booking(bid).meet_version
    barrier = threading.Barrier(2)
    results: dict[str, int] = {}
    lock = threading.Lock()
    answer_applied = {}

    def answer():
        barrier.wait(timeout=10)
        r = client.post(f"/bookings/{bid}/meeting-point/answer", headers=drv["auth"],
                        json={"proposal_id": "p-up", "base_version": ver1, "accept": True})
        with lock:
            results["answer"] = r.status_code
            answer_applied["value"] = r.status_code == 200 and not r.json().get("already", False)

    def boarded():
        barrier.wait(timeout=10)
        r = client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": "boarded"})
        with lock:
            results["boarded"] = r.status_code

    t1 = threading.Thread(target=answer)
    t2 = threading.Thread(target=boarded)
    _race(t1, t2, barrier)
    assert len(results) == 2, results
    b = _booking(bid)
    assert results["boarded"] == 200, results
    assert results["answer"] in (200, 409), results
    assert b.status == BookingStatus.onboard and b.meet_proposal == ""
    # Ответ первым законно меняет точку ДО посадки; посадка первой оставляет прежнюю.
    expected = "у магазина" if answer_applied["value"] else "у школы"
    assert b.meet_note == expected
    assert b.meet_version == ver1 + 1
    expected_pushes = [(_driver_id(ride), "Новое предложение", bid)]
    if answer_applied["value"]:
        expected_pushes.append((b.passenger_id, "Ответ водителя", bid))
    assert meeting_pushes == expected_pushes
    replay = client.post(f"/bookings/{bid}/meeting-point/answer", headers=drv["auth"],
                         json={"proposal_id": "p-up", "base_version": ver1, "accept": True})
    assert replay.status_code == 200 and replay.json()["already"] is True
    assert meeting_pushes == expected_pushes
