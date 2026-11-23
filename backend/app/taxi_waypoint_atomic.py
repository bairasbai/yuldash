"""Uncalled TF04 transaction for already admitted pre-boarding waypoint edits.

The trusted caller must eventually own authorization, admission identity, input
provenance and retention. This is not an API or a mint-on-confirm fallback.
An absent/unresolved attempt never establishes that the route did not change.
"""
from dataclasses import asdict
from datetime import datetime, timedelta
from enum import Enum

from sqlalchemy import DateTime, func, literal, select, type_coerce, update
from sqlalchemy.engine import Connection, Engine
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.pool import SingletonThreadPool, StaticPool

from . import taxi_route_edit_outcome as outcome
from .models import InstantOrder
from .taxi_route_agreement import WaypointWriteAgreement, match_waypoint_write
from .taxi_waypoint_consent import _STATE_FIELDS

_ORDER = InstantOrder.__table__
_COLS = tuple(_ORDER.c[name] for name in _STATE_FIELDS)
_ROUTE_STAMP = _STATE_FIELDS.index("destination_changed_at")
_ONE_US = timedelta(microseconds=1)


class WaypointCommit(Enum):
    COMMITTED = "committed"
    UNKNOWN = "unknown"


class _Unresolved(Exception):
    """Abort the owned transaction without asserting durable negative evidence."""


def _db_clock(conn: Connection, shift: str | None = None):
    if conn.dialect.name == "postgresql":
        return func.timezone("UTC", func.statement_timestamp())
    # SQLite's native clock has millisecond precision. A conservative 1 ms
    # guard at either edge never treats the truncated/rounded sample as finer.
    args = (shift,) if shift is not None else ()
    return func.strftime("%Y-%m-%d %H:%M:%f000", "now", *args)


def _sqlite_pristine(conn: Connection) -> None:
    raw = conn.connection.driver_connection
    if raw.in_transaction:
        # This checkout is owned by the primitive. Do not pool an already
        # active physical transaction: DBAPI rollback can be a no-op in
        # unsupported modes. Closing this connection releases its lock.
        conn.invalidate()
        raise _Unresolved
    if (getattr(raw, "autocommit", None) is True or raw.isolation_level is None
            or conn.get_execution_options().get("isolation_level") == "AUTOCOMMIT"):
        raise _Unresolved


def _stage_then_cas(conn, *, key, owner_id, order_id, attempt_id, receipt_expires_at,
                    agreement, requested, quote):
    sql = select(*_COLS).where(_ORDER.c.id == order_id, _ORDER.c.passenger_id == owner_id)
    if conn.dialect.name == "postgresql":
        sql = sql.with_for_update()
    row = conn.execute(sql).one_or_none()
    if row is None:
        raise _Unresolved
    captured = tuple(row)
    now = conn.scalar(select(type_coerce(_db_clock(conn), DateTime())))
    projection = match_waypoint_write(
        key, agreement, owner_id=owner_id, order_id=order_id,
        source=captured, requested=requested, quote=quote, now=now,
    )
    if projection is None or type(now) is not datetime:
        raise _Unresolved
    previous = captured[_ROUTE_STAMP]
    stamp = now if previous is None or previous < now else previous + _ONE_US
    if outcome.stage_committed(
        conn, owner_id=owner_id, order_id=order_id, attempt_id=attempt_id,
        binding=agreement.binding, expires_at=receipt_expires_at, now=now,
    ) is not outcome.StageResult.STAGED:
        # Prior exact evidence may be read separately. Neither a duplicate
        # nor a conflict on this key allows a second write or lifetime extension.
        raise _Unresolved
    lower, upper = _db_clock(conn, "-0.001 seconds"), _db_clock(conn, "+0.001 seconds")
    sql = update(_ORDER).where(
        *(column == old for column, old in zip(_COLS, captured, strict=True)),
        literal(agreement.issued_at, type_=DateTime()) <= lower,
        upper < literal(agreement.valid_until, type_=DateTime()),
        upper < literal(receipt_expires_at, type_=DateTime()),
    ).values(**asdict(projection), destination_changed_at=stamp)
    if conn.execute(sql.returning(_ORDER.c.id)).scalar_one_or_none() != order_id:
        raise _Unresolved


def commit_preboard_waypoints(
    engine: Engine, *, key: bytes, owner_id: int, order_id: int, attempt_id: str,
    receipt_expires_at: datetime, agreement: WaypointWriteAgreement | None,
    requested: list[dict], quote: dict,
) -> WaypointCommit:
    """Own one route/outcome transaction; indicate success only after its commit.

    Inputs must come from a future trusted server-side admission and saved
    agreement/quote. Returns UNKNOWN for all uncertainty, even when no mutation
    was possible in this invocation: a previous attempt might have committed.
    No intentional replay, post-commit I/O, or external effect under lock.
    """
    if (type(agreement) is not WaypointWriteAgreement or not outcome._utc(receipt_expires_at)
            or not outcome._identity(owner_id, order_id, attempt_id, agreement.binding)
            or engine.dialect.name not in ("sqlite", "postgresql")
            or isinstance(engine.pool, (StaticPool, SingletonThreadPool))
            or (engine.dialect.name == "sqlite" and engine.url.database in (None, "", ":memory:"))):
        return WaypointCommit.UNKNOWN
    try:
        with engine.connect() as conn:
            sqlite = conn.dialect.name == "sqlite"
            if sqlite:
                _sqlite_pristine(conn)
            with conn.begin():
                if sqlite:
                    # A transaction begin hook may itself open a physical
                    # transaction; do not borrow or accidentally commit it.
                    _sqlite_pristine(conn)
                    conn.exec_driver_sql("BEGIN IMMEDIATE")
                outcome._require_tx(conn)
                _stage_then_cas(
                    conn, key=key, owner_id=owner_id, order_id=order_id, attempt_id=attempt_id,
                    receipt_expires_at=receipt_expires_at, agreement=agreement,
                    requested=requested, quote=quote,
                )
    except (_Unresolved, outcome.OutcomeUnavailable, SQLAlchemyError,
            TypeError, ValueError, OverflowError):
        return WaypointCommit.UNKNOWN
    return WaypointCommit.COMMITTED
