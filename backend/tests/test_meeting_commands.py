"""RS-03 Б: три команды смены места — предложение/ответ/отзыв с CAS по версии.

Матрица (план §7): pending применяет сразу; confirmed — предложение (действует прежняя);
ответ «да»/«нет» и отзыв повышают версию ровно один раз; повтор того же proposal_id без
побочек; устаревшая версия — 409 `meeting_point_stale`; onboard — только высадка; закрытая
бронь — 409 `meeting_point_closed`; пуши — только у победившего перехода, по одному;
предел смен — 409; отмена/неявка/завершение снимают предложение в своей транзакции.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session

import app.services as svc
from app.db import engine
from app.geo import snapshot_cached
from app.models import Booking, Ride, UserRole
from app.ride_stops import stop_point
from app.timeutil import utcnow

STOPS = "Ургаза | Бурибай"
SIBAY = (52.716, 58.664)


def _when(days: int = 400) -> str:
    return (utcnow() + timedelta(days=days)).replace(microsecond=0).isoformat() + "+00:00"


@pytest.fixture
def pushes(monkeypatch) -> list:
    got: list = []
    monkeypatch.setattr(svc, "send_push",
                        lambda session, uid, title, body, **k: got.append((uid, title, body)))
    return got


def _make(client, user_factory, *, seats=3):
    drv = user_factory("MC Driver", role=UserRole.driver)
    pax = user_factory("MC Pax")
    body = {"from_city": "Сибай", "to_city": "Акъяр", "depart_at": _when(), "seats_total": seats,
            "price": 700, "comment": "mc", "waypoints": STOPS, "pickup": "У автовокзала",
            "pickup_lat": SIBAY[0], "pickup_lng": SIBAY[1]}
    ride = client.post("/rides", headers=drv["auth"], json=body).json()
    with Session(engine) as s:
        sp = stop_point(s.get(Ride, ride["id"]), "Ургаза", snapshot_cached())
        assert sp is not None
    b = client.post("/bookings", headers=pax["auth"],
                    json={"ride_id": ride["id"], "seats": 1, "pickup_stop": "Ургаза"})
    assert b.status_code == 200, b.text
    global UPOINT
    UPOINT = (sp.lat, sp.lng)
    return drv, pax, ride, b.json()["id"], (sp.lat, sp.lng)


UPOINT = (52.716, 58.664)   # заполняется из _make (точка Ургазы)


def _propose(client, pax, bid, *, kind="pickup", base=0, lat=None, lng=None, note="у школы", pid=None):
    if lat is None:
        lat, lng = UPOINT
    body = {"kind": kind, "point": {"lat": lat, "lng": lng, "note": note},
            "base_version": base}
    if pid:
        body["proposal_id"] = pid
    return client.post(f"/bookings/{bid}/meeting-point", headers=pax["auth"], json=body)


def _answer(client, drv, bid, pid, *, base, accept):
    return client.post(f"/bookings/{bid}/meeting-point/answer", headers=drv["auth"],
                       json={"proposal_id": pid, "base_version": base, "accept": accept})


def _withdraw(client, pax, bid, pid, *, base):
    return client.post(f"/bookings/{bid}/meeting-point/withdraw", headers=pax["auth"],
                       json={"proposal_id": pid, "base_version": base})


def _version(bid: int) -> int:
    with Session(engine) as s:
        return s.get(Booking, bid).meet_version


def test_pending_applies_immediately_no_push(client, user_factory, pushes):
    drv, pax, ride, bid, _ = _make(client, user_factory)
    pushes.clear()   # «Новая бронь» уже ушла — дальше только команды
    r = _propose(client, pax, bid, base=0)
    assert r.status_code == 200, r.text
    assert r.json()["pickup_note"] == "у школы" and r.json()["meeting_version"] == 1
    assert r.json()["meeting_proposal"] is None
    assert pushes == [], "прямое применение в pending — без пуша"


def test_confirmed_proposes_answer_yes_applies(client, user_factory, pushes):
    drv, pax, ride, bid, _ = _make(client, user_factory)
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    r = _propose(client, pax, bid, base=0, pid="p-1")
    assert r.status_code == 200, r.text
    assert r.json()["meeting_proposal"]["proposal_id"] == "p-1"
    assert r.json()["pickup_note"] == "", "действует прежняя точка"
    assert pushes[-1][1] == "Новое предложение", "водитель узнал о предложении"
    # Повтор того же предложения — без побочек и без второго пуша.
    before = len(pushes)
    again = _propose(client, pax, bid, base=_version(bid), pid="p-1")
    assert again.status_code == 200 and again.json()["already"] is True
    assert len(pushes) == before
    # Тот же id с другим содержимым — 409.
    other = _propose(client, pax, bid, base=_version(bid), note="у магазина", pid="p-1")
    assert other.status_code == 409 and other.json()["detail"]["code"] == "meeting_point_stale"
    # Водитель согласен — точка применилась, предложение снято, версия +1.
    v0 = _version(bid)
    ans = _answer(client, drv, bid, "p-1", base=v0, accept=True)
    assert ans.status_code == 200, ans.text
    assert ans.json()["pickup_note"] == "у школы" and ans.json()["meeting_proposal"] is None
    assert _version(bid) == v0 + 1
    assert pushes[-1][1] == "Ответ водителя"


def test_answer_no_keeps_old_point(client, user_factory, pushes):
    drv, pax, ride, bid, _ = _make(client, user_factory)
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    first = _propose(client, pax, bid, base=0, pid="p-no")
    old = first.json()["meeting_version"]
    ans = _answer(client, drv, bid, "p-no", base=old, accept=False)
    assert ans.status_code == 200, ans.text
    assert ans.json()["pickup_note"] == "" and ans.json()["meeting_proposal"] is None
    assert _version(bid) == old + 1


def test_withdraw_removes_proposal_late_withdraw_409(client, user_factory, pushes):
    drv, pax, ride, bid, _ = _make(client, user_factory)
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    _propose(client, pax, bid, base=0, pid="p-w")
    # Чужой withdraw (другой id) — 409, предложение цело.
    bad = _withdraw(client, pax, bid, "p-other", base=_version(bid))
    assert bad.status_code == 409
    ok = _withdraw(client, pax, bid, "p-w", base=_version(bid))
    assert ok.status_code == 200 and ok.json()["meeting_proposal"] is None
    assert pushes[-1][1] == "Предложение отозвано"
    # Повторный withdraw уже пустого — already, без пуша.
    before = len(pushes)
    again = _withdraw(client, pax, bid, "p-w", base=_version(bid))
    assert again.status_code == 200 and again.json()["already"] is True
    assert len(pushes) == before


def test_stale_base_version_is_409(client, user_factory, pushes):
    drv, pax, ride, bid, _ = _make(client, user_factory)
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    _propose(client, pax, bid, base=0, pid="p-s")
    stale = _answer(client, drv, bid, "p-s", base=0, accept=True)   # версия уже уехала
    assert stale.status_code == 409 and stale.json()["detail"]["code"] == "meeting_point_stale"
    assert _version(bid) == 1, "проигравший не поднял версию"


def test_onboard_only_dropoff(client, user_factory, pushes):
    drv, pax, ride, bid, _ = _make(client, user_factory)
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = "onboard"
        s.add(b)
        s.commit()
    r = _propose(client, pax, bid, kind="pickup", base=_version(bid))
    assert r.status_code == 409 and r.json()["detail"]["code"] == "meeting_point_onboard"
    with Session(engine) as s:
        r_obj = s.get(Ride, ride["id"])
        assert r_obj.to_lat is not None
        end = (r_obj.to_lat, r_obj.to_lng)
    r = _propose(client, pax, bid, kind="dropoff", base=_version(bid), pid="p-d",
                 lat=end[0] + 0.001, lng=end[1] + 0.001)
    assert r.status_code == 200, r.text


def test_closed_booking_rejects_changes(client, user_factory, pushes):
    drv, pax, ride, bid, _ = _make(client, user_factory)
    client.post(f"/bookings/{bid}/cancel", headers=pax["auth"], json={"reason": "changed_mind"})
    r = _propose(client, pax, bid, base=_version(bid))
    assert r.status_code == 409 and r.json()["detail"]["code"] == "meeting_point_closed"


def test_changes_limit(client, user_factory, pushes):
    drv, pax, ride, bid, _ = _make(client, user_factory)
    for i in range(5):
        r = _propose(client, pax, bid, base=_version(bid), note=f"место {i}")
        assert r.status_code == 200, f"шаг {i}: {r.text}"
    r = _propose(client, pax, bid, base=_version(bid), note="шестое")
    assert r.status_code == 409 and r.json()["detail"]["code"] == "meeting_point_changes_limit"


def test_cancel_clears_active_proposal(client, user_factory, pushes):
    drv, pax, ride, bid, _ = _make(client, user_factory)
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    _propose(client, pax, bid, base=0, pid="p-c")
    client.post(f"/bookings/{bid}/cancel", headers=pax["auth"], json={"reason": "changed_mind"})
    with Session(engine) as s:
        b = s.get(Booking, bid)
        assert b.meet_proposal == "", "отмена сняла предложение"
        assert b.meet_version == 2, "и подняла версию в своей транзакции"


def test_pickup_rechecks_boarding_after_moderation(client, user_factory, monkeypatch):
    import app.routers.meeting as commands
    drv, pax, ride, bid, _ = _make(client, user_factory)
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    relock = commands._relock

    def board_before_relock(session, booking_id, ride_id):
        with Session(engine) as other:
            booking = other.get(Booking, booking_id)
            booking.status = "onboard"
            other.add(booking)
            other.commit()
        return relock(session, booking_id, ride_id)

    monkeypatch.setattr(commands, "_relock", board_before_relock)
    response = _propose(client, pax, bid, base=0, pid="late-pickup")
    assert response.status_code == 409, response.text
    assert response.json()["detail"]["code"] == "meeting_point_onboard"
    with Session(engine) as session:
        assert session.get(Booking, bid).meet_proposal == ""


def test_transition_lock_refreshes_proposal_before_cleanup(client, user_factory):
    from app.meeting_points import clear_proposal_on_transition
    from app.routers.bookings import _lock_meeting_transition
    drv, pax, ride, bid, _ = _make(client, user_factory)
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    with Session(engine) as stale:
        booking = stale.get(Booking, bid)
        row = stale.get(Ride, ride["id"])
        assert booking.meet_proposal == ""
        assert _propose(client, pax, bid, base=0, pid="after-read").status_code == 200
        booking, row = _lock_meeting_transition(stale, booking, row)
        assert booking.meet_proposal and booking.meet_version == 1
        assert "meet_proposal" in clear_proposal_on_transition(booking, only_kind="pickup")


def test_pending_same_point_replay_preserves_version_and_limit(client, user_factory, pushes):
    drv, pax, ride, bid, _ = _make(client, user_factory)
    pushes.clear()
    first = _propose(client, pax, bid, base=0, note="у школы")
    assert first.status_code == 200
    # Потерянный ответ: клиент повторяет исходную версию, а не знает новую.
    retry = _propose(client, pax, bid, base=0, note="у школы")
    assert retry.status_code == 200 and retry.json()["already"] is True
    assert retry.json()["meeting_version"] == 1
    with Session(engine) as session:
        booking = session.get(Booking, bid)
        assert booking.meet_changes == 1
        booking.meet_changes = 5
        session.add(booking)
        session.commit()
    # Лимит исчерпан, но повтор уже сохранённой точки не является изменением.
    retry = _propose(client, pax, bid, base=0, note="у школы")
    assert retry.status_code == 200 and retry.json()["already"] is True
    assert retry.json()["meeting_version"] == 1
    with Session(engine) as session:
        booking = session.get(Booking, bid)
        assert booking.meet_changes == 5 and booking.meet_version == 1
    assert pushes == []


@pytest.mark.parametrize("lat,lng", [(91, 58.664), (-91, 58.664), (52.716, 418.664), (52.716, -181)])
def test_propose_out_of_range_preserves_point_and_version(client, user_factory, lat, lng):
    drv, pax, ride, bid, _ = _make(client, user_factory)
    response = _propose(client, pax, bid, base=0, lat=lat, lng=lng)
    assert response.status_code == 400, response.text
    assert response.json()["detail"]["code"] == "meeting_point_bad"
    with Session(engine) as session:
        booking = session.get(Booking, bid)
        assert booking.meet_version == 0 and booking.meet_changes == 0 and booking.meet_lat is None


@pytest.mark.parametrize("action", ["cancel", "complete", "family"])
def test_all_terminal_paths_clear_proposal_once(client, user_factory, action):
    drv, pax, ride, bid, _ = _make(client, user_factory)
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    assert _propose(client, pax, bid, base=0, pid="terminal-path").status_code == 200
    pending_id = None
    if action != "family":
        other = user_factory("MC Pending Terminal")
        pending = client.post("/bookings", headers=other["auth"], json={"ride_id": ride["id"], "seats": 1})
        assert pending.status_code == 200, pending.text
        pending_id = pending.json()["id"]
    with Session(engine) as session:
        row = session.get(Ride, ride["id"])
        row.depart_at = utcnow() - timedelta(hours=2)
        booking = session.get(Booking, bid)
        if action == "family":
            booking.status = "onboard"
            booking.onboard_at = utcnow() - timedelta(minutes=10)
        seats_before = row.seats_left
        price_before = booking.price
        session.add(row)
        session.add(booking)
        session.commit()
    path = f"/bookings/{bid}/trip-status" if action == "family" else f"/rides/{ride['id']}/{action}"
    auth = pax["auth"] if action == "family" else drv["auth"]
    kwargs = {"json": {"status": "done"}} if action == "family" else {}
    for _ in range(2):
        response = client.post(path, headers=auth, **kwargs)
        assert response.status_code == 200, response.text
        with Session(engine) as session:
            booking = session.get(Booking, bid)
            assert booking.status == ("cancelled" if action == "cancel" else "done")
            assert booking.meet_proposal == "" and booking.meet_version == 2
            assert booking.price == price_before
            if pending_id is not None:
                pending = session.get(Booking, pending_id)
                assert pending.status == "cancelled" and pending.meet_proposal == "" and pending.meet_version == 0
            assert session.get(Ride, ride["id"]).seats_left == seats_before
