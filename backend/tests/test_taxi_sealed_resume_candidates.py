"""Real file SQLite startup hints, not claims, timeout authority, or dispatch."""
from datetime import timedelta
from uuid import uuid4

import pytest
from sqlalchemy import event, insert
from sqlalchemy.exc import OperationalError

from app import taxi_retirement_agreement_seal as seal
from app import taxi_route_resume as resume
from app import taxi_sealed_resume_candidates as selector
from app import taxi_sealed_resume_claim as claim
from app.models import InstantOrderStatus as S, LedgerEntry, LedgerKind, Payment
from test_taxi_offered_waypoint_preview import _mutate, _row
from test_taxi_offered_waypoint_retire import _clock, _wait_db, healthy, route, testdb  # noqa: F401
import test_taxi_sealed_resume_claim as claim_fixtures
from test_taxi_sealed_resume_claim import _params
from test_taxi_sealed_resume_offer import _claim, _store

retired = claim_fixtures.retired
near_deadline = claim_fixtures.near_deadline

CLAIM, TIMEOUT = selector.CandidateKind.CLAIM_DUE, selector.CandidateKind.TIMEOUT_DUE


def _scan(data, **kwargs):
    db, _, _, _, args, _ = data
    return selector.scan_sealed_resume_candidates(db, **({"key": args["key"]} | kwargs))


def _hints(page):
    return [(c.order_id, c.marker_id, c.kind) for c in page.candidates]


def test_actual_retirement_one_read_hint_only_and_real_lease_takeover(retired):
    db, _, oid, _, _, expected = retired
    marker = resume._state(expected)["route_resume_id"]
    saved = _row(db, oid)
    sql = []

    def observe(conn, cursor, statement, parameters, context, many):
        sql.append(statement.strip())

    event.listen(db, "before_cursor_execute", observe)
    try:
        page = _scan(retired)
    finally:
        event.remove(db, "before_cursor_execute", observe)
    assert _hints(page) == [(oid, marker, CLAIM)] and page.next_after_id is None
    assert len(sql) == 1 and sql[0].startswith("SELECT") and "LIMIT" in sql[0] and "FOR UPDATE" not in sql[0]
    assert _row(db, oid) == saved and repr(page).find(marker) == -1 and repr(page.candidates[0]).find(marker) == -1

    # Earlier hint becomes stale as an independent *real* claimer owns it.
    with db.connect() as witness:
        raw = witness.connection.driver_connection
        physical = []

        def track(conn, cursor, statement, parameters, context, many):
            if statement == "BEGIN IMMEDIATE":
                physical.append(conn.connection.driver_connection)

        event.listen(db, "before_cursor_execute", track)
        try:
            active = _claim(retired, lease_seconds=1)
        finally:
            event.remove(db, "before_cursor_execute", track)
    assert len(physical) == 1 and physical[0] is not raw
    assert _hints(_scan(retired)) == []
    not_owned = claim.claim_sealed_retirement(db, **_params(retired, expected=expected))
    assert not_owned is None
    active_state = resume._state(active._observation)
    _wait_db(db, active_state["route_resume_ready_at"])
    assert _hints(_scan(retired)) == _hints(page)  # advisory takeover, not minted capability
    assert resume.observe(db, oid) == active._observation


@pytest.mark.parametrize("as_offer", (False, True))
def test_original_absolute_timeout_only_and_separate_authoritative_writer(retired, near_deadline, as_offer):
    db, _, oid, _, _, _ = retired
    data = (*retired[:-1], near_deadline)
    original = resume._state(near_deadline)
    deadline = original["route_resume_deadline_at"]
    assert deadline - original["route_resume_retired_at"] == timedelta(seconds=120)
    if as_offer:
        expected = _store(data, _claim(data), offer_seconds=120)
        assert expected is not None
        assert _hints(_scan(retired)) == []  # not a claim or a new offer
    else:
        expected = near_deadline
        assert _hints(_scan(retired)) == [(oid, original["route_resume_id"], CLAIM)]
    _wait_db(db, deadline)
    assert _hints(_scan(retired)) == [(oid, original["route_resume_id"], TIMEOUT)]
    assert resume.observe(db, oid) == expected
    # Only the existing authoritative writer can change the row.
    assert resume.store_timeout(db, expected) is not None
    assert _row(db, oid).status is S.cancelled and _hints(_scan(retired)) == []


def test_elapsed_successor_offer_lease_does_not_trigger_claim_or_absolute_timeout(retired):
    db, _, oid, _, _, _ = retired
    expected = _store(retired, _claim(retired), offer_seconds=1)
    assert expected is not None
    state = resume._state(expected)
    _wait_db(db, state["offer_expires_at"])
    with db.connect() as c:
        assert _clock(c) < state["route_resume_deadline_at"]
    assert _hints(_scan(retired)) == [] and resume.observe(db, oid) == expected


@pytest.mark.parametrize("case", ("legacy", "partial", "nonce", "bad_ready", "no_claim_for_lease", "claim_at_retirement", "far_deadline",
                                  "fare", "raw_seal", "paid", "assigned", "old_driver_is_owner", "wrong_key"))
def test_malformed_noisy_or_legacy_rows_never_get_hint(retired, case):
    db, owner, oid, driver, _, expected = retired
    state = resume._state(expected)
    updates = {
        "legacy": {name: None for name in resume._MARKERS},
        "partial": {resume._SEAL: None},
        "nonce": {"route_resume_claim_id": ""},
        "bad_ready": {"route_resume_ready_at": state["route_resume_retired_at"] - timedelta(seconds=1)},
        "no_claim_for_lease": {"route_resume_ready_at": state["route_resume_retired_at"] + timedelta(seconds=1)},
        "claim_at_retirement": {"route_resume_claim_id": uuid4().hex},
        "far_deadline": {"route_resume_deadline_at": state["route_resume_deadline_at"] + timedelta(seconds=1)},
        "fare": {"price_estimate": state["price_estimate"] + 1},
        "paid": {"paid": True},
        "assigned": {"driver_id": driver},
        "old_driver_is_owner": {"route_resume_previous_driver_id": owner},
    }
    if case == "raw_seal":
        with db.begin() as conn:
            conn.exec_driver_sql("UPDATE instantorder SET route_resume_agreement_seal=? WHERE id=?", ("z"*32, oid))
    elif case in updates:
        _mutate(db, oid, **updates[case])
    before = _row(db, oid)
    result = _scan(retired, **({"key": b"x"*32} if case == "wrong_key" else {}))
    assert _hints(result) == [] and _row(db, oid) == before


@pytest.mark.parametrize("financial", ("payment", "ledger"))
def test_scanner_ignores_settlement_but_owns_no_money_change(retired, financial):
    db, owner, oid, recipient, _, _ = retired
    with db.begin() as conn:
        if financial == "payment":
            statement = insert(Payment).values(user_id=owner, order_id=oid, purpose="ride")
        else:
            statement = insert(LedgerEntry).values(driver_id=recipient, order_id=oid, kind=LedgerKind.earn)
        assert conn.execute(statement).rowcount == 1
    before = _row(db, oid)
    assert _hints(_scan(retired)) == [] and _row(db, oid) == before


def test_keyset_advances_over_malformed_inspected_rows_without_skipping_next_id(retired):
    db, _, oid, _, args, _ = retired
    row = dict(_row(db, oid)._mapping)
    ids = (oid + 1, oid + 9, oid + 10)
    markers = {}
    with db.begin() as conn:
        for next_id in ids:
            # Synthetic additional copies are independently sealed to their own
            # identity; no application seal is minted from a current row.
            new = row | {"id": next_id, "route_resume_id": uuid4().hex}
            markers[next_id] = new["route_resume_id"]
            new[resume._SEAL] = seal._digest(args["key"], new)
            conn.execute(resume._ORDER.insert().values(**new))
        conn.exec_driver_sql("UPDATE instantorder SET route_resume_retired_at=? WHERE id=?", ("broken-clock", oid))
        conn.exec_driver_sql("UPDATE instantorder SET route_resume_ready_at=? WHERE id=?", ("broken-clock", ids[0]))
    first = _scan(retired, limit=2)
    assert _hints(first) == [] and first.next_after_id == ids[0]
    second = _scan(retired, after_id=first.next_after_id, limit=2)
    assert _hints(second) == [(i, markers[i], CLAIM) for i in ids[1:]]
    assert second.next_after_id == ids[-1]  # full page; never needs an uninspected limit+1
    last = _scan(retired, after_id=second.next_after_id, limit=2)
    assert _hints(last) == [] and last.next_after_id is None


def test_invalid_bounds_or_read_failure_do_not_become_empty_success(retired):
    db, _, _, _, _, _ = retired
    seen = []

    def fail_read(conn, cursor, statement, parameters, context, many):
        seen.append(True)
        raise OperationalError("synthetic read", (), RuntimeError("synthetic-only"))

    event.listen(db, "before_cursor_execute", fail_read)
    try:
        for invalid in ({"limit": True}, {"limit": 65}, {"limit": 0}, {"after_id": -1},
                        {"after_id": 1 << 31}, {"after_id": 0.0}, {"key": b"x"}):
            assert _scan(retired, **invalid) is None
        assert selector.scan_sealed_resume_candidates(None, b"x"*32) is None
        assert seen == []
        assert _scan(retired) is None and seen == [True]
    finally:
        event.remove(db, "before_cursor_execute", fail_read)


@pytest.mark.parametrize("column", ("women_only", "pickup_enroute"))
def test_raw_sqlite_non_boolean_does_not_masquerade_as_signed_true(request, column):
    from app.taxi_offered_waypoint_retire import OfferedRetirement, retire_offered_waypoints
    from test_taxi_offered_waypoint_retire import _args

    request.getfixturevalue("route")
    data = request.getfixturevalue("healthy")
    db, owner, oid, _, other, *_ = data
    _mutate(db, oid, **{column: True})
    args = _args(data)
    assert retire_offered_waypoints(db, **args) is OfferedRetirement.COMMITTED
    good = resume.observe(db, oid)
    marked = (db, owner, oid, other, args, good)
    assert resume._state(good)[column] is True and len(_hints(_scan(marked))) == 1

    with db.begin() as conn:
        conn.exec_driver_sql(f"UPDATE instantorder SET {column}=? WHERE id=?", (2, oid))
        assert conn.exec_driver_sql(f"SELECT {column} FROM instantorder WHERE id=?", (oid,)).scalar_one() == 2
    observed = resume.observe(db, oid)
    assert resume._state(observed)[column] is True  # legacy typed ORM projection loses raw truth shape
    assert claim.claim_sealed_retirement(db, **_params(marked, expected=observed)) is None
    skipped = _scan(marked, limit=1)
    assert _hints(skipped) == [] and skipped.next_after_id == oid
