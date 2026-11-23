"""Real authenticated legacy wait may not widen a marked TF04 search."""
from datetime import timedelta
from uuid import uuid4

import pytest
from sqlalchemy import event, select, update
from sqlmodel import Session

from app import instant_service as isv, taxi_route_resume as resume
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
from app.timeutil import utcnow

_O = InstantOrder.__table__


def _seed(owner, *, status=S.searching, **kw):
    now = utcnow()
    with Session(engine) as s:
        o = InstantOrder(passenger_id=owner, status=status, created_at=now - timedelta(minutes=2),
                         searching_at=now - timedelta(minutes=1), from_text="SYNTHETIC START",
                         to_text="SYNTHETIC END", **kw)
        s.add(o)
        s.commit()
        return o.id


def _snapshot(oid):
    with engine.connect() as c:
        return c.execute(select(_O).where(_O.c.id == oid)).one()


def _post(client, user, oid):
    return client.post(f"/instant/orders/{oid}/wait", headers=user["auth"])


def _marker(recipient):
    retired = utcnow() - timedelta(seconds=2)
    return dict(
        offered_at=retired - timedelta(seconds=2), search_round=1,
        route_resume_id=uuid4().hex, route_resume_previous_driver_id=recipient,
        route_resume_retired_at=retired, route_resume_ready_at=retired,
        route_resume_deadline_at=retired + timedelta(seconds=120),
    )


@pytest.mark.parametrize("status", (S.searching, S.expired))
def test_real_marked_order_does_not_join_normal_wait_queue(client, user_factory, status):
    actor, former, other = (user_factory(label) for label in ("WaitScopeOwner", "WaitOldOffer", "WaitStranger"))
    oid = _seed(actor["id"], status=status, **_marker(former["id"]))
    saved = _snapshot(oid)
    if status is S.searching:
        assert resume._valid(resume._state(resume.observe(engine, oid)))
    hidden = _post(client, other, oid)
    assert hidden.status_code == 403 and "code" not in hidden.json()["detail"]
    response = _post(client, actor, oid)
    assert response.status_code == 409
    detail = response.json()["detail"]
    assert detail["code"] == "taxi_route_resume_wait_unavailable"
    assert detail["ru"] and detail["ba"]
    assert saved.route_resume_id not in response.text
    assert _snapshot(oid) == saved


@pytest.mark.parametrize("field", resume._MARKERS)
def test_each_partial_marker_fails_closed(client, user_factory, field):
    actor = user_factory("PartialWait")
    malformed = {"route_resume_id": "", "route_resume_previous_driver_id": 0,
                 "route_resume_claim_id": ""}
    oid = _seed(actor["id"], **{field: malformed.get(field, utcnow())})
    saved = _snapshot(oid)
    response = _post(client, actor, oid)
    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "taxi_route_resume_wait_unavailable"
    assert _snapshot(oid) == saved


@pytest.mark.skipif(engine.dialect.name != "sqlite", reason="actual ordinary SQLite request/writer conns")
@pytest.mark.parametrize("interleave", ("new_resume", "accept", "owner", "different_round", "wait_updated"))
def test_real_sql_mutation_after_read_before_wait_cas(client, user_factory, interleave):
    actor, other = (user_factory(label) for label in ("WaitRaceOwner", "WaitRaceOther"))
    oid = _seed(actor["id"])
    entered, latest = [], []
    changes = {
        "new_resume": _marker(other["id"]),  # same owner/status, a fresh durable epoch
        "accept": dict(status=S.accepted, driver_id=other["id"], accepted_at=utcnow()),
        "owner": dict(passenger_id=other["id"]),
        "different_round": dict(search_round=2, searching_at=utcnow()),
        "wait_updated": dict(wait_until=utcnow() + timedelta(minutes=22)),
    }[interleave]

    def race(conn, cursor, statement, params, context, executemany):
        if statement.lstrip().upper().startswith("UPDATE INSTANTORDER SET WAIT_UNTIL") and not entered:
            entered.append(True)
            with engine.begin() as b:
                assert b.connection.driver_connection is not conn.connection.driver_connection
                assert b.execute(update(_O).where(_O.c.id == oid).values(**changes)).rowcount == 1
            latest.append(_snapshot(oid))

    event.listen(engine, "before_cursor_execute", race)
    try:
        response = _post(client, actor, oid)
    finally:
        event.remove(engine, "before_cursor_execute", race)
    assert entered == [True] and len(latest) == 1
    assert response.status_code == 409
    detail = response.json()["detail"]
    assert detail["code"] == "wait_snapshot_conflict" and detail["ru"] and detail["ba"]
    assert _snapshot(oid) == latest[0]


@pytest.mark.parametrize("status", (S.searching, S.expired))
def test_legacy_ordinary_wait_and_repeat_public_shape_still_work(client, user_factory, monkeypatch, status):
    actor = user_factory("UnmarkedWait")
    oid = _seed(actor["id"], status=status)
    now = utcnow()
    ticks = iter((now, now + timedelta(seconds=12)))
    monkeypatch.setattr("app.routers.instant.utcnow", lambda: next(ticks))

    def no_reconcile(*args, **kwargs):
        pytest.fail("legacy wait should not dispatch or reconcile orders")
    monkeypatch.setattr(isv, "reconcile_offer", no_reconcile)
    monkeypatch.setattr(isv, "start_matching", no_reconcile)
    for taken in (now, now + timedelta(seconds=12)):
        response = _post(client, actor, oid)
        assert response.status_code == 200, response.text
        body = response.json()
        assert set(body) == {"ok", "wait_until", "wait_minutes", "order"}
        assert body["ok"] and body["order"]["id"] == oid
        assert str(body["order"]["status"]) == status.value
        saved = _snapshot(oid)
        assert saved.wait_until == taken + timedelta(minutes=body["wait_minutes"])
        assert all(name not in body["order"] for name in resume._MARKERS)


@pytest.mark.skipif(engine.dialect.name != "sqlite", reason="raw malformed SQLite timestamp")
def test_partial_malformed_resume_timestamp_is_checked_without_decoding(client, user_factory):
    actor = user_factory("MalformedWait")
    oid = _seed(actor["id"])
    with engine.begin() as c:
        c.exec_driver_sql("UPDATE instantorder SET route_resume_deadline_at='malformed' WHERE id = ?", (oid,))
    response = _post(client, actor, oid)
    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "taxi_route_resume_wait_unavailable"
    with engine.connect() as c:
        assert c.exec_driver_sql(
            "SELECT wait_until, route_resume_deadline_at FROM instantorder WHERE id = ?", (oid,),
        ).one() == (None, "malformed")


@pytest.mark.skipif(engine.dialect.name != "sqlite", reason="real SQLite commit/readback interleave")
def test_committed_wait_does_not_return_order_now_owned_by_someone_else(client, user_factory):
    owner, other = (user_factory(label) for label in ("ReadbackWaitOwner", "ReadbackWaitNewOwner"))
    oid = _seed(owner["id"])
    changed = []

    def witness(conn, cursor, statement, params, context, executemany):
        if (statement.startswith("SELECT instantorder.id, instantorder.passenger_id, instantorder.consent_request_key")
                and not changed):
            changed.append(True)
            with engine.begin() as outsider:
                assert outsider.connection.driver_connection is not conn.connection.driver_connection
                # Wait has already committed; this is a different temporal
                # boundary from a losing CAS or a guarantee of no effect.
                assert outsider.scalar(select(_O.c.wait_until).where(_O.c.id == oid)) is not None
                outsider.execute(update(_O).where(_O.c.id == oid).values(
                    passenger_id=other["id"], to_text="PRIVATE SYNTHETIC NEW OWNER",
                ))

    event.listen(engine, "before_cursor_execute", witness)
    try:
        response = _post(client, owner, oid)
    finally:
        event.remove(engine, "before_cursor_execute", witness)
    assert changed == [True]
    assert response.status_code == 409 and response.json()["detail"]["code"] == "wait_snapshot_conflict"
    assert "PRIVATE" not in response.text and "order" not in response.json()
    saved = _snapshot(oid)
    assert saved.passenger_id == other["id"] and saved.wait_until is not None
