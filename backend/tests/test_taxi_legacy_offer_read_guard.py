"""Legacy reads cannot expose an offer that needs the sealed admission lane."""
from datetime import timedelta
from uuid import uuid4

import pytest
from sqlalchemy import delete, select, update
from sqlmodel import Session

from app import instant_service as isv, taxi_route_resume as resume
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow

_O = InstantOrder.__table__
_EXACT_LAT = 54.734821
_STOP = {"lat": 54.752139, "lng": 55.994812, "text": "SYNTHETIC_PRIVATE_STOP_812"}
_AGREEMENT = b"a" * 32


@pytest.fixture
def seeded(user_factory):
    driver = user_factory("LegacyCardDriver", role=UserRole.driver)
    previous = user_factory("LegacyPreviousDriver", role=UserRole.driver)
    passenger = user_factory("LegacyCardPassenger")
    ids = []

    def offer(**overrides):
        now = utcnow()
        data = dict(passenger_id=passenger["id"], current_offer_driver_id=driver["id"], status=S.offered,
                    from_lat=_EXACT_LAT, from_lng=55.971883, to_lat=54.761845, to_lng=55.990753,
                    from_text="SYNTHETIC_PICKUP_482", to_text="SYNTHETIC_DESTINATION_341", ride_price=340,
                    price_estimate=340, created_at=now-timedelta(minutes=1), searching_at=now-timedelta(seconds=30),
                    offered_at=now-timedelta(seconds=5), offer_expires_at=now+timedelta(minutes=3), search_round=2,
                    waypoints_json=isv.dump_waypoints([_STOP]))
        with Session(engine) as s:
            row = InstantOrder(**(data | overrides))
            s.add(row)
            s.flush()
            ids.append(row.id)
            s.commit()
        return ids[-1]

    try:
        yield driver, previous, passenger, offer
    finally:
        if ids:
            with engine.begin() as conn:
                conn.execute(delete(_O).where(_O.c.id.in_(ids)))


def _poll(client, who):
    r = client.get("/instant/driver/offer", headers=who["auth"])
    assert r.status_code == 200, r.text
    assert r.headers["cache-control"] == "no-store"
    return r


def _detail(client, who, oid):
    return client.get(f"/instant/orders/{oid}", headers=who["auth"])


def _snapshot(oid):
    with engine.connect() as conn:
        return conn.execute(select(_O).where(_O.c.id == oid)).one()


def _full_marker(previous, *, expired=False):
    retired = utcnow() - timedelta(seconds=121 if expired else 10)
    deadline = retired + timedelta(seconds=120)
    return dict(route_resume_id=uuid4().hex, route_resume_previous_driver_id=previous["id"],
                route_resume_retired_at=retired, route_resume_deadline_at=deadline,
                route_resume_agreement_seal=_AGREEMENT, offer_expires_at=deadline)


def _assert_no_raw(response, marker=None):
    assert "SYNTHETIC_PRIVATE_STOP_812" not in response.text
    assert str(_EXACT_LAT) not in response.text
    assert "route_resume" not in response.text
    assert _AGREEMENT.hex() not in response.text
    if marker:
        assert marker not in response.text


@pytest.mark.parametrize("kind", ("live_marked", "expired_marked", *resume._MARKERS))
def test_marked_offer_is_not_a_legacy_card_or_detail(client, seeded, kind):
    driver, previous, passenger, offer = seeded
    if kind.startswith(("live_", "expired_")):
        marker = _full_marker(previous, expired=kind == "expired_marked")
    else:
        unusual = {"route_resume_id": "", "route_resume_previous_driver_id": 0,
                   "route_resume_claim_id": "", resume._SEAL: _AGREEMENT}
        marker = {kind: unusual.get(kind, utcnow())}
    oid = offer(**marker)
    before = _snapshot(oid)
    poll = _poll(client, driver)
    assert poll.json() == {"offer": None}
    nominee = _detail(client, driver, oid)
    outsider = _detail(client, previous, oid)
    assert nominee.status_code == outsider.status_code == 403
    assert nominee.json() == outsider.json()
    for response in (poll, nominee, outsider):
        _assert_no_raw(response, marker.get("route_resume_id"))
    owner = _detail(client, passenger, oid)
    assert owner.status_code == 200 and owner.json()["id"] == oid
    assert owner.json()["from_lat"] == _EXACT_LAT
    assert "route_resume_id" not in owner.json() and "offer_card_precondition" not in owner.json()
    assert _snapshot(oid) == before


def test_newer_marked_does_not_hide_older_ordinary_or_assigned_history(client, seeded):
    driver, previous, passenger, offer = seeded
    ordinary = offer()
    blocked = offer(**_full_marker(previous))
    assert blocked > ordinary
    listing = _poll(client, driver).json()["offer"]
    assert listing["id"] == ordinary and listing["offer_card_precondition"]
    assert listing["from_lat"] == round(_EXACT_LAT, 2)
    old = _detail(client, driver, ordinary)
    assert old.status_code == 200 and old.json()["from_lat"] == round(_EXACT_LAT, 2)
    assert "offer_card_precondition" not in old.json()
    assigned = offer(status=S.done, driver_id=driver["id"], current_offer_driver_id=None,
                     done_at=utcnow(), **_full_marker(previous))
    for actor in (driver, passenger):
        history = _detail(client, actor, assigned)
        assert history.status_code == 200 and history.json()["id"] == assigned
        assert history.json()["from_lat"] == _EXACT_LAT
        assert "route_resume_id" not in history.json()
    assert _detail(client, previous, assigned).status_code == 403


@pytest.mark.parametrize("surface", ("poll", "detail"))
@pytest.mark.parametrize("change", ("mark", "different_nominee", "terminal", "expire"))
def test_concurrent_commit_during_render_discards_stale_legacy_payload(
    client, seeded, monkeypatch, surface, change,
):
    driver, previous, _, offer = seeded
    oid = offer()
    original = isv.order_payload
    committed = []
    changes = {
        "mark": _full_marker(previous),
        "different_nominee": {"current_offer_driver_id": previous["id"]},
        "terminal": {"status": S.done, "driver_id": previous["id"], "done_at": utcnow()},
        "expire": {"offer_expires_at": utcnow()-timedelta(seconds=1)},
    }[change]

    def interleave(session, order, viewer, **kwargs):
        result = original(session, order, viewer, **kwargs)
        if order.id == oid and viewer.id == driver["id"] and not committed:
            with engine.begin() as other:
                assert other.connection.driver_connection is not session.connection().connection.driver_connection
                assert other.execute(update(_O).where(_O.c.id == oid).values(**changes)).rowcount == 1
            committed.append(True)
        return result

    monkeypatch.setattr(isv, "order_payload", interleave)
    response = _poll(client, driver) if surface == "poll" else _detail(client, driver, oid)
    assert committed == [True]
    if surface == "poll":
        assert response.json() == {"offer": None}
    else:
        assert response.status_code == 403
    _assert_no_raw(response, changes.get("route_resume_id"))
    latest = _snapshot(oid)
    assert all(getattr(latest, key) == value for key, value in changes.items())


@pytest.mark.parametrize("initial_role", ("owner", "assigned"))
def test_detail_race_to_nominee_cannot_send_previous_unlocked_view(client, seeded, monkeypatch, initial_role):
    driver, previous, passenger, offer = seeded
    actor = passenger if initial_role == "owner" else driver
    if initial_role == "owner":
        oid = offer()
        changes = {"passenger_id": previous["id"], "current_offer_driver_id": actor["id"]}
    else:
        oid = offer(status=S.accepted, driver_id=driver["id"], current_offer_driver_id=None, accepted_at=utcnow())
        changes = {"status": S.offered, "driver_id": None, "current_offer_driver_id": actor["id"]}
    original = isv.order_payload
    committed = []

    def interleave(session, order, viewer, **kwargs):
        result = original(session, order, viewer, **kwargs)
        if order.id == oid and viewer.id == actor["id"] and not committed:
            assert result["from_lat"] == _EXACT_LAT
            with engine.begin() as other:
                assert other.connection.driver_connection is not session.connection().connection.driver_connection
                assert other.execute(update(_O).where(_O.c.id == oid).values(**changes)).rowcount == 1
            committed.append(True)
        return result

    monkeypatch.setattr(isv, "order_payload", interleave)
    fresh = _detail(client, actor, oid)
    assert committed == [True] and fresh.status_code == 200
    assert fresh.json()["from_lat"] == round(_EXACT_LAT, 2)
    _assert_no_raw(fresh)


@pytest.mark.parametrize("invalid", ("done", "accepted", "assigned_offered", "expired", "no_expiry"))
def test_stale_nominee_is_not_a_participant_or_poll_recipient(client, seeded, invalid):
    driver, other, passenger, offer = seeded
    overrides = {
        "done": dict(status=S.done, driver_id=other["id"], done_at=utcnow()),
        "accepted": dict(status=S.accepted, driver_id=other["id"], accepted_at=utcnow()),
        "assigned_offered": dict(driver_id=other["id"]),
        "expired": dict(offer_expires_at=utcnow()-timedelta(seconds=3)),
        "no_expiry": dict(offer_expires_at=None),
    }[invalid]
    oid = offer(**overrides)
    before = _snapshot(oid)
    nominee = _detail(client, driver, oid)
    assert nominee.status_code == 403
    _assert_no_raw(nominee)
    assert _snapshot(oid) == before
    assert _poll(client, driver).json() == {"offer": None}
    assert _detail(client, passenger, oid).status_code == 200
    if overrides.get("driver_id"):
        assert _detail(client, other, oid).status_code == 200


@pytest.mark.parametrize("motion", ("owner_accept", "owner_rotate", "assigned_arrive", "owner_churn"))
def test_detail_preserves_access_and_rerenders_authorized_phase_change(client, seeded, monkeypatch, motion):
    driver, other, passenger, offer = seeded
    actor = driver if motion == "assigned_arrive" else passenger
    if motion in ("assigned_arrive", "owner_churn"):
        oid = offer(status=S.accepted, driver_id=driver["id"], current_offer_driver_id=None, accepted_at=utcnow())
    else:
        oid = offer()
    changes = {
        "owner_accept": [dict(status=S.accepted, driver_id=driver["id"], current_offer_driver_id=None,
                              accepted_at=utcnow())],
        "owner_rotate": [dict(current_offer_driver_id=other["id"])],
        "assigned_arrive": [dict(status=S.arriving)],
        "owner_churn": [dict(status=S.arriving), dict(status=S.onboard)],
    }[motion]
    original = isv.order_payload
    renders = []

    def interleave(session, order, viewer, **kwargs):
        result = original(session, order, viewer, **kwargs)
        if order.id == oid and viewer.id == actor["id"]:
            index = len(renders)
            renders.append(result["status"])
            if index < len(changes):
                with engine.begin() as other_connection:
                    assert other_connection.connection.driver_connection is not session.connection().connection.driver_connection
                    assert other_connection.execute(update(_O).where(_O.c.id == oid)
                                                    .values(**changes[index])).rowcount == 1
        return result

    monkeypatch.setattr(isv, "order_payload", interleave)
    response = _detail(client, actor, oid)
    if motion == "owner_churn":
        assert response.status_code == 409 and renders == ["accepted", "arriving"]
        _assert_no_raw(response)
        response = _detail(client, actor, oid)
        assert response.status_code == 200 and response.json()["status"] == "onboard"
        return
    assert response.status_code == 200
    assert response.json()["from_lat"] == _EXACT_LAT
    if motion == "owner_rotate":
        assert renders == ["offered"] and response.json()["status"] == "offered"
    if motion == "owner_accept":
        assert renders == ["offered", "accepted"] and response.json()["status"] == "accepted"
    if motion == "assigned_arrive":
        assert renders == ["accepted", "arriving"] and response.json()["status"] == "arriving"
