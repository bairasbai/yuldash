"""Private structural GPS intake witnesses; no live socket, Redis or other store."""
from datetime import timedelta, timezone

import pytest
from jose import jwt
from sqlalchemy import event
from sqlmodel import Session

from app import livepos, security, taxi_route_resume as resume
from app.config import settings
from app.models import AuthSession, Block, InstantOrderStatus as S, User
from app.taxi_onboard_position_intake import stage_private_position
from app.taxi_onboard_waypoint_preview import OnboardAssignment
from app.timeutil import utcnow
from test_taxi_offered_waypoint_preview import _mutate, _row, testdb as _testdb

testdb = _testdb


@pytest.fixture
def ride(request):
    db, owner, oid, driver, other, _, _ = request.getfixturevalue("testdb")
    now = utcnow()
    accepted = now - timedelta(minutes=5)
    boarded = now - timedelta(minutes=3)
    _mutate(db, oid, status=S.onboard, driver_id=driver, accepted_at=accepted, onboard_at=boarded,
            reassigns=1, current_offer_driver_id=None, offer_expires_at=None)
    with Session(db) as s:
        for uid, sid in ((driver, "a"*64), (owner, "b"*64), (other, "c"*64)):
            s.add(AuthSession(id=sid, user_id=uid, access_expires_at=now + timedelta(minutes=10)))
        s.commit()
    return db, OnboardAssignment(oid, owner, driver, accepted, boarded, 1), other


def _jwt(driver, **overrides):
    now = utcnow().replace(tzinfo=timezone.utc).timestamp()
    claims = dict(sub=str(driver), sid="a"*64, auth_epoch=0, iat=now-10, exp=int(now)+300)
    claims.update(overrides)
    if claims["sid"] is None:
        del claims["sid"]
    return jwt.encode(claims, settings.jwt_secret, algorithm="HS256")


def _stage(ride, store, token=None, **changes):
    db, assignment, _ = ride
    return stage_private_position(db, **(dict(
        order_id=assignment.order_id, access_token=_jwt(assignment.driver_id) if token is None else token,
        lat=0, lng=0, store_private=store,
    ) | changes))


def test_db_authorized_driver_yields_one_minimized_private_observation(ride, monkeypatch):
    db, assignment, _ = ride
    calls, statements = [], []
    before = _row(db, assignment.order_id)
    token = _jwt(assignment.driver_id)
    signed = security._decode(token)
    start = utcnow()

    def no_public(*_args):
        pytest.fail("Private adapter touched public livepos")

    monkeypatch.setattr(livepos, "_redis", no_public)
    monkeypatch.setattr(livepos, "livepos_set", no_public)
    monkeypatch.setattr(livepos, "livepos_get", no_public)

    def statement(_c, _cur, sql, *_args):
        statements.append(sql)

    def store(witness, ttl):
        assert db.pool.checkedout() == 0  # never hold a DB lease over the store boundary
        calls.append((witness, ttl))

    event.listen(db, "before_cursor_execute", statement)
    try:
        assert _stage(ride, store, token) is True
    finally:
        event.remove(db, "before_cursor_execute", statement)
    assert len(calls) == len(statements) == 1 and statements[0].startswith("SELECT")
    assert _row(db, assignment.order_id) == before
    witness, ttl = calls[0]
    assert ttl == livepos.LIVEPOS_TTL_SEC
    assert witness.position.assignment == assignment
    assert (witness.position.lat, witness.position.lng) == (0, 0)
    assert start <= witness.position.observed_at <= utcnow()
    assert (witness.source_session_id, witness.auth_epoch, witness.access_issued_at, witness.access_expires_at) == (
        signed["sid"], signed["auth_epoch"], signed["iat"], signed["exp"],
    )
    assert set(vars(witness)) == {"position", "source_session_id", "auth_epoch", "access_issued_at", "access_expires_at"}
    assert token not in repr(witness) and signed["sid"] not in repr(witness)
    assert "lat=" not in repr(witness.position) and "order_id=" not in repr(witness.position.assignment)


@pytest.mark.parametrize("case", (
    "passenger", "other_driver", "foreign_sid", "missing_sid", "blank_token", "wrong_order", "offered",
    "arriving", "revoked", "wrong_epoch", "logout_cutoff", "expired", "future_iat", "unknown_sid",
    "blocked_by_passenger", "blocked_by_driver", "marked", "prior_driver", "corrupt_generation",
    "bad_lat", "bad_lng", "malformed_exp", "wrong_signature",
))
def test_unauthorized_or_unavailable_gps_never_reaches_store(ride, case):
    db, a, other = ride
    token, args = _jwt(a.driver_id), {}
    if case == "passenger":
        token = _jwt(a.passenger_id, sid="b"*64)
    elif case == "other_driver":
        token = _jwt(other, sid="c"*64)
    elif case == "foreign_sid":
        token = _jwt(a.driver_id, sid="b"*64)
    elif case in ("missing_sid", "unknown_sid"):
        token = _jwt(a.driver_id, sid=None if case == "missing_sid" else "d"*64)
    elif case == "blank_token":
        token = ""
    elif case == "wrong_signature":
        token = jwt.encode({"sub": str(a.driver_id)}, "synthetic-wrong-signature", algorithm="HS256")
    elif case == "malformed_exp":
        token = _jwt(a.driver_id, exp=None)
    elif case == "wrong_order":
        args["order_id"] = a.order_id + 40_000
    elif case in ("offered", "arriving"):
        _mutate(db, a.order_id, status=S(case))
    elif case == "prior_driver":
        _mutate(db, a.order_id, driver_id=other, reassigns=2)
    elif case == "corrupt_generation":
        _mutate(db, a.order_id, onboard_at=None)
    elif case in ("revoked", "wrong_epoch", "logout_cutoff", "blocked_by_passenger", "blocked_by_driver"):
        with Session(db) as writer:
            if case == "revoked":
                writer.get(AuthSession, "a"*64).revoked_at = utcnow()
            elif case == "wrong_epoch":
                writer.get(User, a.driver_id).auth_epoch += 1
            elif case == "logout_cutoff":
                writer.get(User, a.driver_id).tokens_valid_from = utcnow()
            else:
                source, peer = ((a.passenger_id, a.driver_id) if case == "blocked_by_passenger"
                                else (a.driver_id, a.passenger_id))
                writer.add(Block(user_id=source, blocked_user_id=peer))
            writer.commit()
    elif case in ("expired", "future_iat"):
        now = utcnow().replace(tzinfo=timezone.utc).timestamp()
        token = _jwt(a.driver_id, **({"iat": now-300, "exp": int(now)-1} if case == "expired"
                                     else {"iat": now+30}))
    elif case == "marked":
        _mutate(db, a.order_id, **{resume._SEAL: b"x"*32})
    elif case == "bad_lat":
        args["lat"] = float("nan")
    elif case == "bad_lng":
        args["lng"] = 181

    before = _row(db, a.order_id)
    never = lambda *_: pytest.fail("Unverified source reached the private store")
    assert _stage(ride, never, token, **args) is False
    assert _row(db, a.order_id) == before


def test_current_generation_is_read_fresh_on_every_call(ride):
    db, assignment, _ = ride
    captures = []
    assert _stage(ride, lambda witness, _: captures.append(witness))
    # Same driver/order/sid after a separate generation change is not the old
    # typed assignment. Stored samples must later be consumed by exact match.
    accepted = assignment.accepted_at + timedelta(seconds=1)
    boarded = assignment.onboard_at + timedelta(seconds=1)
    _mutate(db, assignment.order_id, reassigns=2, accepted_at=accepted, onboard_at=boarded)
    assert _stage(ride, lambda witness, _: captures.append(witness))
    assert captures[0].position.assignment == assignment
    assert captures[1].position.assignment == OnboardAssignment(
        assignment.order_id, assignment.passenger_id, assignment.driver_id, accepted, boarded, 2,
    )


@pytest.mark.parametrize("change", ("revoke_signed_session", "replace_assigned_driver"))
def test_late_db_authority_change_before_observation_closes_sample(ride, monkeypatch, change):
    db, a, other = ride
    real_decode = security._decode
    actual = []

    def interleave(token):
        claims = real_decode(token)
        # Separate committed write after signature verification but before
        # this intake can create its fresh authorization observation.
        with Session(db) as writer:
            if change == "revoke_signed_session":
                row = writer.get(AuthSession, "a"*64)
                row.revoked_at = utcnow()
            else:
                from app.models import InstantOrder
                row = writer.get(InstantOrder, a.order_id)
                row.driver_id = other
                row.reassigns += 1
            writer.commit()
        actual.append(True)
        return claims

    monkeypatch.setattr(security, "_decode", interleave)
    assert _stage(ride, lambda *_: pytest.fail("Lost authority must not stamp a sample")) is False
    assert actual == [True]
