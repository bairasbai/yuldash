"""Inactive exact driver-accept history, separate from passenger route evidence.

The future caller must authenticate the driver, own the guarded acceptance in
the same physical transaction, and abort it if staging does not return STAGED.
Nothing here grants order access, establishes admission, or retries acceptance.
"""
import hmac
from datetime import datetime
from enum import Enum
from uuid import UUID

from psycopg2.extensions import TRANSACTION_STATUS_INTRANS
from sqlalchemy import func, literal, select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.dialects.sqlite import insert as sqlite_insert
from sqlalchemy.engine import Connection, Engine
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.pool import SingletonThreadPool, StaticPool

from . import taxi_route_resume as resume
from .models import InstantOrder, TaxiDriverAcceptOutcome, User
from .models import InstantOrderStatus as S
from .taxi_sealed_offer_card import SealedOfferCardProof

_ROW = TaxiDriverAcceptOutcome.__table__
_C = _ROW.c
_ORDER = InstantOrder.__table__.c
_USER = User.__table__.c
_FIELDS = (_C.driver_id, _C.order_id, _C.attempt_id, _C.binding, _C.expires_at)
_KEY = _FIELDS[:3]
_PURPOSE = b"taxi-driver-sealed-accept-outcome-v1\x00"


class StageResult(Enum):
    STAGED = "staged"
    UNKNOWN = "unknown"


class DriverAcceptEvidence(Enum):
    COMMITTED = "committed"
    UNKNOWN = "unknown"


def _binding(key, driver_id, order_id, attempt_id, proof):
    if (type(key) is not bytes or len(key) < 16
            or not all(type(i) is int and 0 < i <= 2147483647 for i in (driver_id, order_id))
            or type(attempt_id) is not str or len(attempt_id) != 36
            or type(proof) is not SealedOfferCardProof or type(proof._digest) is not bytes
            or len(proof._digest) != 32):
        return None
    try:
        attempt = UUID(attempt_id)
    except ValueError:
        return None
    if attempt.version != 4 or str(attempt) != attempt_id:
        return None
    return hmac.digest(key, _PURPOSE + driver_id.to_bytes(4, "big") + order_id.to_bytes(4, "big")
                       + attempt.bytes + proof._digest, "sha256")


def _supported(engine: Engine) -> bool:
    return ((engine.dialect.name == "postgresql" and engine.dialect.driver == "psycopg2")
            or (engine.dialect.name == "sqlite" and engine.url.database not in (None, "", ":memory:"))) and (
        not isinstance(engine.pool, (StaticPool, SingletonThreadPool))
    )


def _expiry_clock(conn: Connection):
    if conn.dialect.name == "sqlite":
        return func.strftime("%Y-%m-%d %H:%M:%f000", "now", "+0.001 seconds")
    return resume._clock(conn)


def _owned_physical_tx(conn: Connection) -> bool:
    if (not _supported(conn.engine) or not conn.in_transaction() or conn.in_nested_transaction()
            or conn.get_execution_options().get("isolation_level") == "AUTOCOMMIT"):
        return False
    raw = conn.connection.driver_connection
    if getattr(raw, "autocommit", None) is True:
        return False
    if conn.dialect.name == "sqlite":
        return raw.in_transaction and raw.isolation_level is not None and (
            conn.exec_driver_sql("PRAGMA foreign_keys").scalar_one_or_none() == 1
        )
    return raw.autocommit is False and raw.get_transaction_status() == TRANSACTION_STATUS_INTRANS


def stage_committed_acceptance(
    conn: Connection, key: bytes, *, driver_id: int, order_id: int, attempt_id: str,
    proof: SealedOfferCardProof, accepted_at: datetime, expires_at: datetime,
) -> StageResult:
    """Stage only after the caller's actual, verified assignment; never commit.

    A duplicate, mismatched row, unsafe checkout, or failure is UNKNOWN; it
    does not allow the caller to keep an unreceipted change, or extend a prior
    receipt. The comparison against the accepted row is a fence, not proof the
    caller originally made that assignment; no route calls this helper yet.
    The caller must own a top-level transaction, never a manually issued savepoint.
    """
    binding = _binding(key, driver_id, order_id, attempt_id, proof)
    if (binding is None or not resume._utc(accepted_at) or not resume._utc(expires_at)
            or expires_at <= accepted_at):
        return StageResult.UNKNOWN
    try:
        if not _owned_physical_tx(conn):
            return StageResult.UNKNOWN
        insert = pg_insert if conn.dialect.name == "postgresql" else sqlite_insert
        now = resume._clock(conn)
        values = (driver_id, order_id, attempt_id, binding, expires_at)
        source = select(*(literal(value, type_=col.type) for col, value in zip(_FIELDS, values, strict=True))).where(
            _ORDER.id == order_id, _ORDER.driver_id == driver_id, _ORDER.status == S.accepted,
            _ORDER.accepted_at == accepted_at, _ORDER.accepted_at <= now,
            literal(expires_at, type_=_C.expires_at.type) > _expiry_clock(conn),
            *(_ORDER[name].is_(None) for name in resume._MARKERS),
        )
        statement = (insert(_ROW).from_select(_FIELDS, source)
                     .on_conflict_do_nothing(index_elements=_KEY).returning(_C.order_id))
        if conn.execute(statement).scalar_one_or_none() != order_id:
            return StageResult.UNKNOWN
        # RETURNING can precede an AFTER trigger. Don't acknowledge missing,
        # changed or prematurely expired evidence inside the owning transaction.
        staged = conn.execute(select(_C.binding, _C.expires_at).where(
            _C.driver_id == driver_id, _C.order_id == order_id, _C.attempt_id == attempt_id,
            _C.expires_at == expires_at, _C.expires_at > _expiry_clock(conn),
        )).one_or_none()
        if (staged is not None and staged.expires_at == expires_at
                and isinstance(staged.binding, (bytes, memoryview))
                and hmac.compare_digest(staged.binding, binding)):
            return StageResult.STAGED
    except (SQLAlchemyError, ValueError, TypeError):
        pass
    return StageResult.UNKNOWN


def committed_acceptance(
    engine: Engine, key: bytes, *, driver_id: int, order_id: int, attempt_id: str, proof: SealedOfferCardProof,
) -> DriverAcceptEvidence:
    """Independent result for a future authenticated driver's exact attempt.

    History may outlive assignment, not the driver/order FKs or its expiry.
    No current order facts are returned. No record is not proof of failure.
    """
    binding = _binding(key, driver_id, order_id, attempt_id, proof)
    if binding is None or not _supported(engine):
        return DriverAcceptEvidence.UNKNOWN
    try:
        with engine.connect() as conn:
            return _committed_in(conn, driver_id=driver_id, order_id=order_id, attempt_id=attempt_id, binding=binding)
    except (SQLAlchemyError, ValueError, TypeError):
        return DriverAcceptEvidence.UNKNOWN


def _committed_in(conn, *, driver_id, order_id, attempt_id, binding, fences=()):
    """Internal exact lookup; additional checks share the receipt SELECT."""
    saved = conn.execute(select(_C.binding, _C.expires_at).where(
        _C.driver_id == driver_id, _C.order_id == order_id, _C.attempt_id == attempt_id,
        _C.expires_at > _expiry_clock(conn),
        select(_USER.id).where(_USER.id == driver_id).exists(),
        select(_ORDER.id).where(_ORDER.id == order_id).exists(),
        *fences,
    )).one_or_none()
    if (saved is not None and resume._utc(saved.expires_at) and isinstance(saved.binding, (bytes, memoryview))
            and hmac.compare_digest(saved.binding, binding)):
        return DriverAcceptEvidence.COMMITTED
    return DriverAcceptEvidence.UNKNOWN
