"""Uncalled atomic retirement proof; no route approval, admission, or dispatch."""
from dataclasses import asdict
from datetime import datetime
from enum import Enum
from uuid import uuid4

from sqlalchemy import DateTime, func, literal, select, type_coerce, update
from sqlalchemy.engine import Connection, Engine
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.pool import SingletonThreadPool, StaticPool

from . import taxi_route_edit_outcome as outcome, taxi_route_resume as resume
from .config import settings
from .models import InstantOrder, InstantOrderStatus as S, Tariff
from .taxi_offered_waypoint_agreement import OfferedWaypointAgreement, _matched_projection
from .taxi_offered_waypoint_preview import OfferedWaypointPreview, _FIELDS
from .instant_service import _DESTINATION_QUOTE_TARIFF_FIELDS

_ORDER = InstantOrder.__table__
_TARIFF = Tariff.__table__
_ORDER_COLS = tuple(_ORDER.c[name] for name in _FIELDS)
_TARIFF_COLS = tuple(_TARIFF.c[name] for name in _DESTINATION_QUOTE_TARIFF_FIELDS)


class OfferedRetirement(Enum):
    COMMITTED = "committed"
    UNKNOWN = "unknown"


class _Unresolved(Exception):
    """Rollback without claiming a previous attempt did not apply."""


def _locked_state(conn: Connection, owner_id: int, order_id: int) -> tuple:
    owner = (_ORDER.c.id == order_id, _ORDER.c.passenger_id == owner_id)
    if conn.dialect.name == "postgresql":
        # Order -> tariff. No speculative quote while waiting. A SHARE lock
        # protects non-key rate edits as well as tariff replacement/deletion.
        tariff_id = conn.scalar(select(_ORDER.c.tariff_id).where(*owner).with_for_update())
        if tariff_id is None or conn.scalar(select(_TARIFF.c.id).where(
            _TARIFF.c.id == tariff_id,
        ).with_for_update(read=True)) is None:
            raise _Unresolved
    row = conn.execute(select(*_ORDER_COLS, *_TARIFF_COLS).select_from(
        _ORDER.join(_TARIFF, _TARIFF.c.id == _ORDER.c.tariff_id),
    ).where(*owner)).one_or_none()
    if row is None:
        raise _Unresolved
    return tuple(row)


def _clock_bounds(conn: Connection):
    if conn.dialect.name == "sqlite":
        # Conservatively account for SQLite's native millisecond precision at
        # both validity edges. The retirement/deadline use one statement clock.
        return (
            func.strftime("%Y-%m-%d %H:%M:%f000", "now", "-0.001 seconds"),
            func.strftime("%Y-%m-%d %H:%M:%f000", "now", "+0.001 seconds"),
        )
    return resume._clock(conn), resume._clock(conn)


def _stage_then_retire(conn, *, key, owner_id, order_id, attempt_id, receipt_expires_at, agreement, preview):
    captured = _locked_state(conn, owner_id, order_id)
    now = conn.scalar(select(type_coerce(resume._clock(conn), DateTime())))
    projected = _matched_projection(
        key, agreement, owner_id=owner_id, order_id=order_id, preview=preview, current=captured, now=now,
    )
    source = dict(zip(_FIELDS, captured[:len(_FIELDS)], strict=True))
    if (projected is None or source["search_round"] >= min(settings.instant_max_offers, (1 << 31) - 1)):
        raise _Unresolved  # no automatic renewal of the previous search budget
    if outcome.stage_committed(
        conn, owner_id=owner_id, order_id=order_id, attempt_id=attempt_id,
        binding=agreement.binding, expires_at=receipt_expires_at, now=now,
    ) is not outcome.StageResult.STAGED:
        raise _Unresolved

    current_tariff = select(_TARIFF.c.id).where(
        _TARIFF.c.id == _ORDER.c.tariff_id,
        *(_col == value for _col, value in zip(_TARIFF_COLS, captured[len(_FIELDS):], strict=True)),
    ).exists()
    lower, upper = _clock_bounds(conn)
    stamp = resume._clock(conn)
    statement = update(_ORDER).where(
        *(col == value for col, value in zip(_ORDER_COLS, captured[:len(_FIELDS)], strict=True)),
        current_tariff,
        literal(agreement.observed_at, type_=DateTime()) <= lower,
        upper < literal(agreement.valid_until, type_=DateTime()),
        upper < literal(receipt_expires_at, type_=DateTime()),
        upper < _ORDER.c.offer_expires_at,
        # No backwards or equal route edit stamp, even on clock reversal.
        (_ORDER.c.destination_changed_at.is_(None) | (_ORDER.c.destination_changed_at < lower)),
    ).values(
        **asdict(projected), destination_changed_at=stamp,
        status=S.searching, current_offer_driver_id=None, offer_expires_at=None,
        route_resume_id=uuid4().hex, route_resume_previous_driver_id=source["current_offer_driver_id"],
        route_resume_retired_at=stamp, route_resume_ready_at=stamp,
        route_resume_deadline_at=resume._clock(conn, resume._MAX_SECONDS), route_resume_claim_id=None,
    ).returning(_ORDER.c.id)
    if conn.execute(statement).scalar_one_or_none() != order_id:
        raise _Unresolved


def retire_offered_waypoints(
    engine: Engine, *, key: bytes, owner_id: int, order_id: int, attempt_id: str,
    receipt_expires_at: datetime, agreement: OfferedWaypointAgreement | None,
    preview: OfferedWaypointPreview,
) -> OfferedRetirement:
    """Only a trusted admitted caller may try; none exists. No retry/notify/GEO."""
    if (type(agreement) is not OfferedWaypointAgreement or not outcome._utc(receipt_expires_at)
            or not outcome._identity(owner_id, order_id, attempt_id, agreement.binding)
            or engine.dialect.name not in ("sqlite", "postgresql")
            or (engine.dialect.name == "postgresql" and engine.dialect.driver != "psycopg2")
            or isinstance(engine.pool, (StaticPool, SingletonThreadPool))
            or (engine.dialect.name == "sqlite" and engine.url.database in (None, "", ":memory:"))):
        return OfferedRetirement.UNKNOWN
    try:
        with engine.connect() as conn:
            resume._require_writer_mode(conn)
            with conn.begin():
                resume._require_writer_mode(conn)
                if conn.dialect.name == "sqlite":
                    conn.exec_driver_sql("BEGIN IMMEDIATE")
                outcome._require_tx(conn)
                _stage_then_retire(conn, key=key, owner_id=owner_id, order_id=order_id, attempt_id=attempt_id,
                                   receipt_expires_at=receipt_expires_at, agreement=agreement, preview=preview)
    except (_Unresolved, outcome.OutcomeUnavailable, SQLAlchemyError, ValueError, TypeError, OverflowError):
        return OfferedRetirement.UNKNOWN
    return OfferedRetirement.COMMITTED
