"""Read-only witnesses across real SQLite competing updates and fake routing."""

from dataclasses import replace
from datetime import timedelta

import pytest
from sqlalchemy import event
from sqlmodel import Session

from app import livepos, taxi_onboard_position_read as position_read
from app.models import AuthSession, Block, InstantOrder, InstantOrderStatus as S, User
from app.pricing import RouteMetrics
from app.taxi_onboard_waypoint_preview import ProposedStop
from app.timeutil import utcnow
from test_taxi_onboard_position_intake import _jwt, _row, _stage, ride as _ride, testdb as _testdb
from test_taxi_offered_waypoint_preview import _mutate
from test_taxi_onboard_waypoint_preview import _pair

ride = _ride
testdb = _testdb

_ETA = position_read.OnboardEtaState
_PRIVATE_SENTINEL = "synthetic-route-never-echo"


@pytest.fixture
def staged(request):
    data = request.getfixturevalue("ride")
    db, a, _ = data
    _mutate(db, a.order_id, to_lat=0.04, to_lng=0.04, waypoints_json="")
    recorded = []
    assert _stage(data, lambda witness, _: recorded.append(witness))
    return data, recorded[0]


def _compare(staged, read=None, route=None, **changes):
    (db, a, _), sample = staged
    return position_read.compare_private_position(db, **(dict(
        order_id=a.order_id, passenger_access_token=_jwt(a.passenger_id, sid="b"*64),
        read_private=read if read is not None else lambda *_: sample,
        requested=(ProposedStop(0.012, 0.01, "Synthetic stop"),),
        route_pair=route if route is not None else lambda *_: pytest.fail("must not call routing"),
    ) | changes))


def test_exact_current_scope_returns_delta_without_storage_mutation_or_public_cache(staged, monkeypatch):
    (db, a, _), sample = staged
    calls, statements, scopes = [], [], []
    before = _row(db, a.order_id)

    def no_public(*_):
        pytest.fail("generic public location cache used")

    monkeypatch.setattr(livepos, "livepos_get", no_public)
    monkeypatch.setattr(livepos, "_redis", no_public)

    def route(request):
        assert db.pool.checkedout() == 0
        calls.append((request.before, request.after))
        return _pair(request)

    def reader(assignment):
        assert db.pool.checkedout() == 0
        scopes.append(assignment)
        return sample

    def record(conn, cursor, sql, *extra):
        statements.append(sql)

    event.listen(db, "before_cursor_execute", record)
    try:
        result = _compare(staged, read=reader, route=route)
    finally:
        event.remove(db, "before_cursor_execute", record)
    assert result.state is _ETA.ESTIMATED and result.delta_min == 5
    assert _row(db, a.order_id) == before
    assert scopes == [a, a]
    assert len(statements) == 4 and all(sql.startswith("SELECT") for sql in statements)
    assert calls == [(((0, 0), (0.04, 0.04)), ((0, 0), (0.012, 0.01), (0.04, 0.04)))]
    assert sample.source_session_id not in repr(result)


@pytest.mark.parametrize("case", ("missing", "raw_pos", "legacy_dict", "store_error", "other_session",
                                  "other_generation", "expired_sample", "future_sample"))
def test_missing_or_unverifiable_sample_is_not_no_eta(staged, case, monkeypatch):
    (_, _a, _), s = staged
    wrong = {
        "missing": None,
        "raw_pos": s.position,
        "legacy_dict": {"lat": 0, "lng": 0},
        "other_session": replace(s, source_session_id="c"*64),
        "other_generation": replace(s, position=replace(s.position, assignment=replace(
            s.position.assignment, reassigns=s.position.assignment.reassigns+1,
        ))),
        "expired_sample": s,
        "future_sample": replace(s, position=replace(s.position, observed_at=utcnow()+timedelta(seconds=20))),
    }

    def read(_):
        if case == "store_error":
            raise OSError(_PRIVATE_SENTINEL)
        return wrong[case]

    if case == "expired_sample":
        monkeypatch.setattr(position_read, "utcnow", lambda: s.position.observed_at+timedelta(seconds=121))
    result = _compare(staged, read=read)
    assert result.state is _ETA.NO_SAMPLE and result.delta_min is None and _PRIVATE_SENTINEL not in repr(result)


@pytest.mark.parametrize("case", ("wrong_user", "missing_token", "owner_revoked"))
def test_unauthorized_owner_never_loads_private_sample(staged, case):
    (db, a, _), _ = staged
    changes = {}
    if case == "wrong_user":
        changes["passenger_access_token"] = _jwt(a.driver_id)
    elif case == "missing_token":
        changes["passenger_access_token"] = ""
    else:
        with Session(db) as session:
            session.get(AuthSession, "b"*64).revoked_at = utcnow()
            session.commit()
    result = _compare(staged, read=lambda *_: pytest.fail("unauthorized actor accessed private store"), **changes)
    assert result.state is _ETA.NO_SAMPLE


@pytest.mark.parametrize("mode", ("provider_exception", "fallback", "mixed", "raw_yandex_pair", "different_basis"))
def test_valid_private_sample_but_unreliable_route_is_no_eta_without_echo(staged, mode):
    def route(request):
        if mode == "provider_exception":
            raise RuntimeError(_PRIVATE_SENTINEL)
        pair = _pair(request)
        if mode == "raw_yandex_pair":
            return pair.before.metrics, pair.after.metrics
        if mode == "different_basis":
            return replace(pair, after=replace(pair.after, basis=replace(pair.after.basis, revision="new")))
        return replace(pair, after=replace(pair.after, metrics=RouteMetrics(3, 8, source=mode)))

    result = _compare(staged, route=route)
    assert result.state is _ETA.NO_ETA and result.delta_min is None and _PRIVATE_SENTINEL not in repr(result)


@pytest.mark.parametrize("rival", (
    "passenger_logout", "driver_logout", "driver_epoch", "assigned_other", "same_driver_new_epoch",
    "terminal", "owner_changed", "blocked", "mark", "route_changed", "payment_changed",
    "new_source", "disappeared", "expired_clock", "backwards_clock",
))
def test_no_late_result_after_independent_change_during_routing(staged, rival, monkeypatch):
    (db, a, other), stored = staged
    cache = [stored]
    before_clock = utcnow()
    clock = [before_clock]
    monkeypatch.setattr(position_read, "utcnow", lambda: clock[0])
    writes = []

    def route(request):
        if not writes:
            if rival in ("new_source", "disappeared"):
                cache[0] = (replace(stored, position=replace(stored.position, lng=0.002))
                            if rival == "new_source" else None)
            elif rival in ("expired_clock", "backwards_clock"):
                clock[0] = (stored.position.observed_at + timedelta(seconds=120) if rival == "expired_clock"
                            else before_clock - timedelta(microseconds=1))
            else:
                assert db.pool.checkedout() == 0
                with Session(db) as writer:
                    row = writer.get(InstantOrder, a.order_id)
                    if rival in ("driver_logout", "passenger_logout"):
                        writer.get(AuthSession, ("a" if rival == "driver_logout" else "b")*64).revoked_at = utcnow()
                    elif rival == "driver_epoch":
                        writer.get(User, a.driver_id).auth_epoch += 1
                    elif rival == "assigned_other":
                        row.driver_id = other
                    elif rival == "same_driver_new_epoch":
                        row.reassigns += 1
                    elif rival == "terminal":
                        row.status = S.done
                    elif rival == "owner_changed":
                        row.passenger_id = other
                    elif rival == "blocked":
                        writer.add(Block(user_id=a.driver_id, blocked_user_id=a.passenger_id))
                    elif rival == "mark":
                        row.route_resume_claim_id = "a"*32
                    elif rival == "route_changed":
                        row.to_text = _PRIVATE_SENTINEL
                    elif rival == "payment_changed":
                        row.payment_changed_at = utcnow()
                    writer.commit()
            writes.append(True)
        return _pair(request)

    reads = []

    def read(assignment):
        reads.append(assignment)
        return cache[0]

    result = _compare(staged, read=read, route=route)
    assert reads == ([a, a] if rival in ("new_source", "disappeared") else [a])
    assert writes == [True] and result.state is _ETA.NO_SAMPLE and result.delta_min is None
    assert _PRIVATE_SENTINEL not in repr(result)


@pytest.mark.parametrize("rival", ("passenger_logout", "driver_logout", "terminal"))
def test_late_privacy_change_inside_final_private_read_still_disallows_output(staged, rival):
    (db, a, _), sample = staged
    reads = []
    writes = []

    def read(assignment):
        reads.append(assignment)
        if len(reads) == 2:
            assert db.pool.checkedout() == 0
            with Session(db) as writer:
                if rival == "terminal":
                    writer.get(InstantOrder, a.order_id).status = S.done
                else:
                    writer.get(AuthSession, ("a" if rival == "driver_logout" else "b")*64).revoked_at = utcnow()
                writer.commit()
            writes.append(True)
        return sample

    result = _compare(
        staged, read=read, route=_pair,
    )
    assert reads == [a, a] and writes == [True]
    assert result.state is _ETA.NO_SAMPLE and result.delta_min is None


def test_real_cache_hit_and_two_fresh_legs_both_yandex_prove_no_delta(staged, monkeypatch):
    from app import instant_service, pricing

    cached_duration = [420]
    monotonic = [10.0]
    hits = []

    class FakeResponse:
        def raise_for_status(self):
            pass

        def json(self):
            return {"traffic_type": "realtime", "route": {"legs": [{
                "status": "OK", "steps": [{"length": 3000, "duration": cached_duration[0]}],
            }]}}

    def fake_http(_url, *, params, timeout):
        hits.append(params["waypoints"])
        return FakeResponse()

    monkeypatch.setattr(pricing.httpx, "get", fake_http)
    monkeypatch.setattr(pricing, "_cache", {})
    monkeypatch.setattr(pricing, "monotonic", lambda: monotonic[0])
    monkeypatch.setattr(pricing.settings, "taxi_external_pricing_enabled", True)
    monkeypatch.setattr(pricing.settings, "yandex_router_key", "synthetic-key-never-sent")
    monkeypatch.setattr(pricing.settings, "taxi_route_cache_sec", 300)

    sample = staged[1].position
    start, dest = (sample.lat, sample.lng), (0.04, 0.04)
    seeded = pricing.route_metrics(start, dest)
    assert seeded.source == "yandex" and seeded.duration_min == 7
    assert len(hits) == 1
    monotonic[0] += 80.0
    cached_duration[0] = 900

    def route(*paths):
        # Three arguments reproduce exact #373's legacy callback; the new
        # pair callback returns the same underlying cache + fresh raw metrics.
        def legacy(frm, stops, to):
            return instant_service.route_through(frm, [{"lat": lat, "lng": lng} for lat, lng in stops], to)

        if len(paths) == 3:
            return legacy(*paths)
        request, = paths
        old = legacy(request.before[0], request.before[1:-1], request.before[-1])
        new = legacy(request.after[0], request.after[1:-1], request.after[-1])
        assert old.source == new.source == "yandex"
        assert (old.duration_min, new.duration_min) == (7, 30)
        return old, new

    result = _compare(staged, route=route)
    # Original callback would publish +23, though traffic conditions differ.
    assert len(hits) == 3  # one seed and two fresh legs, no old-route refresh
    assert result.state is _ETA.NO_ETA and result.delta_min is None


@pytest.mark.parametrize("fix_delay,elapsed,expected", (
    (0.25, 1.50, _ETA.NO_SAMPLE),
    (1.50, 1.50, _ETA.NO_SAMPLE),
    (1.51, 1.50, _ETA.ESTIMATED),
    (5.00, 6.00, _ETA.NO_SAMPLE),
))
def test_one_regular_same_session_fix_during_pair_discards_otherwise_valid_delta(
    staged, monkeypatch, fix_delay, elapsed, expected,
):
    _data, initial = staged
    start = initial.position.observed_at + timedelta(seconds=5 - fix_delay)
    clock = [start]
    fixes = [initial]
    monkeypatch.setattr(position_read, "utcnow", lambda: clock[0])
    # For this deterministic schedule the original sample's age at t=0 is
    # 5s minus time remaining to the next regular source frame.
    emitted = initial.position.observed_at + timedelta(seconds=5)
    reads = []

    def read(assignment):
        reads.append(assignment)
        return fixes[0]

    def route(request):
        clock[0] += timedelta(seconds=elapsed)
        if emitted <= clock[0]:
            fixes[0] = replace(fixes[0], position=replace(fixes[0].position, observed_at=emitted))
        return _pair(request)

    result = _compare(staged, read=read, route=route)
    assert len(reads) == 2
    assert result.state is expected and result.delta_min == (5 if expected is _ETA.ESTIMATED else None)
