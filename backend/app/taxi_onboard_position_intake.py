"""Inert adapter for a private onboard GPS witness; no live caller or store."""

import math
import re
from collections.abc import Callable
from dataclasses import dataclass
from datetime import timezone
from types import SimpleNamespace

from jose import JWTError
from sqlalchemy import and_, or_
from sqlalchemy.engine import Engine
from sqlalchemy.orm import aliased
from sqlmodel import Session, select

from . import security
from .livepos import LIVEPOS_TTL_SEC
from .models import AuthSession, Block, InstantOrder, InstantOrderStatus, User
from .taxi_onboard_waypoint_preview import OnboardAssignment, OnboardPosition, _Coordinate, _id
from .taxi_route_resume import _MARKERS
from .timeutil import utcnow

_SESSION_ID = re.compile(r"[0-9a-f]{64}")


@dataclass(frozen=True, repr=False)
class _PrivatePositionWitness:
    """Server-private only; never serialize into public position views."""

    position: OnboardPosition
    source_session_id: str
    auth_epoch: int
    access_issued_at: float
    access_expires_at: int


_PrivateStore = Callable[[_PrivatePositionWitness, int], None]


def _issued_at(value) -> float | None:
    if type(value) not in (int, float):
        return None
    try:
        numeric = float(value)
    except OverflowError:
        return None
    return numeric if math.isfinite(numeric) and numeric > 0 else None


def stage_private_position(
    engine: Engine, *, order_id: int, access_token: str, lat: float, lng: float,
    store_private: _PrivateStore,
) -> bool:
    """Submit one structurally valid frame after a fresh authority observation.

    The caller has no authority over observed time, passenger, driver or
    assignment generation. The injected store is exclusively server-private.
    This proves source authorization at observation, not device GPS attestation
    or a distributed DB/store commit. Future readers must recheck provenance.
    """
    if not _id(order_id) or type(access_token) is not str:
        return False
    try:
        _Coordinate(lat, lng)
        claims = security._decode(access_token)
        driver_id = int(claims["sub"])
    except (JWTError, KeyError, TypeError, ValueError, OverflowError):
        return False
    sid, epoch, expiry = (claims.get(name) for name in ("sid", "auth_epoch", "exp"))
    issued = _issued_at(claims.get("iat"))
    if (not _id(driver_id) or type(sid) is not str or _SESSION_ID.fullmatch(sid) is None
            or type(epoch) is not int or not 0 <= epoch <= (1 << 63) - 1
            or type(expiry) is not int or issued is None or not issued < expiry):
        return False

    owner = aliased(User)
    blocked = select(Block.id).where(or_(
        and_(Block.user_id == driver_id, Block.blocked_user_id == InstantOrder.passenger_id),
        and_(Block.blocked_user_id == driver_id, Block.user_id == InstantOrder.passenger_id),
    )).exists()
    # A single read statement observes the session, generation and block state
    # together. Do not accept an already-loaded ORM order or caller transaction.
    with Session(engine) as session:
        row = session.exec(select(
            User.role, User.auth_epoch, User.tokens_valid_from, InstantOrder.passenger_id,
            InstantOrder.accepted_at, InstantOrder.onboard_at, InstantOrder.reassigns,
        ).select_from(InstantOrder)
            .join(User, User.id == InstantOrder.driver_id)
            .join(owner, owner.id == InstantOrder.passenger_id)
            .join(AuthSession, AuthSession.user_id == User.id)
            .where(InstantOrder.id == order_id, InstantOrder.driver_id == driver_id,
                   InstantOrder.status == InstantOrderStatus.onboard, AuthSession.id == sid,
                   AuthSession.revoked_at.is_(None), ~blocked,
                   *(getattr(InstantOrder, name).is_(None) for name in _MARKERS))
        ).one_or_none()
    if row is None:
        return False
    auth = SimpleNamespace(role=row.role, auth_epoch=row.auth_epoch, tokens_valid_from=row.tokens_valid_from)
    if security._token_revoked(claims, auth) or security._admin_requires_telegram(auth, claims):
        return False
    observed = utcnow()
    if not issued <= observed.replace(tzinfo=timezone.utc).timestamp() < expiry:
        return False
    try:
        assignment = OnboardAssignment(order_id, row.passenger_id, driver_id, row.accepted_at,
                                       row.onboard_at, row.reassigns)
        position = OnboardPosition(assignment, observed, lat, lng)
    except ValueError:
        return False
    # No DB transaction covers the store call; a future read must match its
    # current assignment/session again. No default/public-store fallback.
    store_private(_PrivatePositionWitness(position, sid, epoch, issued, expiry), LIVEPOS_TTL_SEC)
    return True
