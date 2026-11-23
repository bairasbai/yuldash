"""Inert server-private GPS comparison with repeat authority checks."""

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timezone
from enum import Enum
from types import SimpleNamespace

from fastapi import HTTPException
from jose import JWTError
from sqlalchemy import and_, or_
from sqlalchemy.engine import Engine
from sqlalchemy.orm import aliased
from sqlmodel import Session, select

from . import security
from .livepos import LIVEPOS_TTL_SEC
from .models import AuthSession, Block, InstantOrder, InstantOrderStatus as S, User, UserRole
from .taxi_onboard_position_intake import _PrivatePositionWitness, _SESSION_ID, _issued_at
from .taxi_onboard_waypoint_preview import (
    OnboardAssignment, OnboardPosition, ProposedStop, _RemainingRoute, _id, _utc_stamp, compare_remaining,
)
from .taxi_route_resume import _MARKERS
from .taxi_waypoint_consent import _STATE_COLUMNS, _STATE_FIELDS
from .timeutil import utcnow


class OnboardEtaState(Enum):
    NO_SAMPLE = "no_sample"
    NO_ETA = "no_eta"
    ESTIMATED = "estimated"


@dataclass(frozen=True)
class OnboardEtaResult:
    state: OnboardEtaState
    delta_min: float | None = None


@dataclass(frozen=True, repr=False)
class _OrderScope:
    assignment: OnboardAssignment
    fields: tuple
    observed: datetime


_NO_SAMPLE = OnboardEtaResult(OnboardEtaState.NO_SAMPLE)
_NO_ETA = OnboardEtaResult(OnboardEtaState.NO_ETA)
_PrivateRead = Callable[[OnboardAssignment], _PrivatePositionWitness | None]


def _signed_passenger(token):
    if type(token) is not str:
        return None
    try:
        claims = security._decode(token)
        uid = int(claims["sub"])
    except (JWTError, KeyError, TypeError, ValueError, OverflowError):
        return None
    sid, epoch, expiry = (claims.get(name) for name in ("sid", "auth_epoch", "exp"))
    issued = _issued_at(claims.get("iat"))
    if (not _id(uid) or type(sid) is not str or _SESSION_ID.fullmatch(sid) is None
            or type(epoch) is not int or not 0 <= epoch <= (1 << 63) - 1
            or type(expiry) is not int or issued is None or not issued < expiry):
        return None
    return uid, sid, claims, issued, expiry


def _source_fields_valid(sample):
    return (type(sample) is _PrivatePositionWitness and type(sample.position) is OnboardPosition
            and type(sample.source_session_id) is str and _SESSION_ID.fullmatch(sample.source_session_id) is not None
            and type(sample.auth_epoch) is int and 0 <= sample.auth_epoch <= (1 << 63) - 1
            and _issued_at(sample.access_issued_at) is not None and type(sample.access_expires_at) is int
            and sample.access_issued_at < sample.access_expires_at
            and _utc_stamp(sample.position.observed_at))


def _read_scope(engine, order_id, passenger, sample=None):
    uid, sid, claims, issued, expiry = passenger
    owner, driver = aliased(User), aliased(User)
    owner_session, driver_session = aliased(AuthSession), aliased(AuthSession)
    blocked = select(Block.id).where(or_(
        and_(Block.user_id == uid, Block.blocked_user_id == InstantOrder.driver_id),
        and_(Block.blocked_user_id == uid, Block.user_id == InstantOrder.driver_id),
    )).exists()
    columns = (owner.role, owner.auth_epoch, owner.tokens_valid_from)
    if sample is not None:
        columns += (driver.role, driver.auth_epoch, driver.tokens_valid_from)
    statement = select(*_STATE_COLUMNS, *columns).select_from(InstantOrder).join(
        owner, owner.id == InstantOrder.passenger_id,
    ).join(
        driver, driver.id == InstantOrder.driver_id,
    ).join(
        owner_session, and_(owner_session.user_id == owner.id, owner_session.id == sid),
    ).where(
        InstantOrder.id == order_id, InstantOrder.passenger_id == uid, InstantOrder.status == S.onboard,
        owner_session.revoked_at.is_(None), ~blocked,
        *(getattr(InstantOrder, name).is_(None) for name in _MARKERS),
    )
    if sample is not None:
        statement = statement.join(
            driver_session, and_(driver_session.user_id == driver.id, driver_session.id == sample.source_session_id),
        ).where(driver_session.revoked_at.is_(None))
    # Fresh scope as one query. No caller ORM state or transaction survives
    # across private cache/provider I/O.
    with Session(engine) as session:
        row = session.exec(statement).one_or_none()
    if row is None:
        return None
    count = len(_STATE_FIELDS)
    state = tuple(row[:count])
    values = dict(zip(_STATE_FIELDS, state, strict=True))
    role, epoch, cutoff = row[count:count + 3]
    auth = SimpleNamespace(role=role, auth_epoch=epoch, tokens_valid_from=cutoff)
    now = utcnow()
    stamp = now.replace(tzinfo=timezone.utc).timestamp()
    if (not issued <= stamp < expiry or security._token_revoked(claims, auth)
            or security._admin_requires_telegram(auth, claims)):
        return None
    try:
        assignment = OnboardAssignment(order_id, uid, values["driver_id"], values["accepted_at"],
                                       values["onboard_at"], values["reassigns"])
    except ValueError:
        return None
    if sample is not None:
        role, epoch, cutoff = row[count + 3:]
        auth = SimpleNamespace(role=role, auth_epoch=epoch, tokens_valid_from=cutoff)
        old = sample.position.observed_at
        old_stamp = old.replace(tzinfo=timezone.utc).timestamp()
        if (sample.position.assignment != assignment
                or not 0 <= (now - old).total_seconds() < LIVEPOS_TTL_SEC
                or not sample.access_issued_at <= old_stamp <= stamp < sample.access_expires_at
                # The minimized witness does not retain admin proof claims.
                or role == UserRole.admin
                or security._token_revoked({"auth_epoch": sample.auth_epoch, "iat": sample.access_issued_at}, auth)):
            return None
    return _OrderScope(assignment, state, now)


def compare_private_position(
    engine: Engine, *, order_id: int, passenger_access_token: str,
    read_private: _PrivateRead, requested: tuple[ProposedStop, ...], route: _RemainingRoute,
) -> OnboardEtaResult:
    """Compare without writing, and disclose no ETA after loss of scope."""
    passenger = _signed_passenger(passenger_access_token)
    if not _id(order_id) or passenger is None:
        return _NO_SAMPLE
    current = _read_scope(engine, order_id, passenger)
    if current is None:
        return _NO_SAMPLE
    def load(assignment):
        try:
            return read_private(assignment)
        except Exception:  # private store failures have no public diagnostics
            return None

    sample = load(current.assignment)
    if not _source_fields_valid(sample):
        return _NO_SAMPLE
    before = _read_scope(engine, order_id, passenger, sample)
    if before is None or before.fields != current.fields or before.observed < current.observed:
        return _NO_SAMPLE
    state = dict(zip(_STATE_FIELDS, before.fields, strict=True))

    def safe_route(*args):
        try:
            return route(*args)
        except Exception:  # provider errors may carry private location; do not reflect/log
            return None

    try:
        delta = compare_remaining(
            assignment=before.assignment, position=sample.position, now=before.observed,
            to_lat=state["to_lat"], to_lng=state["to_lng"], stored_waypoints=state["waypoints_json"],
            standing=state["stop_started_at"] is not None, requested=requested, route=safe_route,
        ).delta_min
    except HTTPException:
        delta = None
    # No second privileged cache access if the provider returned after access
    # had already ended. The final DB observation also covers the cache I/O.
    checkpoint = _read_scope(engine, order_id, passenger, sample)
    if checkpoint is None or checkpoint.fields != before.fields or checkpoint.observed < before.observed:
        return _NO_SAMPLE
    # Without a cross-system version primitive, a different/newer immutable
    # witness is not evidence of the exact sample used to compute the delta.
    latest = load(checkpoint.assignment)
    if not _source_fields_valid(latest) or latest != sample:
        return _NO_SAMPLE
    after = _read_scope(engine, order_id, passenger, sample)
    if after is None or after.fields != checkpoint.fields or after.observed < checkpoint.observed:
        return _NO_SAMPLE
    return _NO_ETA if delta is None else OnboardEtaResult(OnboardEtaState.ESTIMATED, delta)
