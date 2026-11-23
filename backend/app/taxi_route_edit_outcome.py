"""Inactive committed-write evidence; no creation/admission/close or replay API."""
from datetime import datetime
from enum import Enum
import hmac
from uuid import UUID

from sqlalchemy import delete, literal, select, tuple_
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.dialects.sqlite import insert as sqlite_insert
from sqlalchemy.engine import Connection, Engine
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.pool import SingletonThreadPool, StaticPool

from .models import InstantOrder, TaxiRouteEditOutcome

_RECEIPT = TaxiRouteEditOutcome.__table__
_ORDER = InstantOrder.__table__
_C = _RECEIPT.c
_KEY = (_C.owner_id, _C.order_id, _C.attempt_id)


class OutcomeUnavailable(RuntimeError):
    """Caller must abort; the persisted history has not been established."""


class StageResult(Enum):
    STAGED = "staged"
    EXACT = "exact"
    UNKNOWN = "unknown"


class CommittedEvidence(Enum):
    COMMITTED = "committed"
    UNKNOWN = "unknown"


def _utc(value) -> bool:
    return type(value) is datetime and value.tzinfo is None


def _identity(owner_id, order_id, attempt_id, binding) -> bool:
    if (type(owner_id) is not int or not 0 < owner_id <= 2147483647
            or type(order_id) is not int or not 0 < order_id <= 2147483647
            or type(attempt_id) is not str or len(attempt_id) != 36
            or type(binding) is not bytes or len(binding) != 32):
        return False
    try:
        key = UUID(attempt_id)
    except ValueError:
        return False
    return key.version == 4 and str(key) == attempt_id


def _owned_order(owner_id, order_id):
    return (_ORDER.c.id == order_id, _ORDER.c.passenger_id == owner_id)


def _read(conn: Connection, owner_id: int, order_id: int, attempt_id: str, now: datetime):
    row = conn.execute(select(_C.binding, _C.expires_at).where(
        _C.owner_id == owner_id, _C.order_id == order_id, _C.attempt_id == attempt_id,
        now < _C.expires_at, select(_ORDER.c.id).where(*_owned_order(owner_id, order_id)).exists(),
    )).one_or_none()
    if row is None:
        return None
    binding, expires_at = row
    if type(binding) not in (bytes, memoryview) or len(binding) != 32 or not _utc(expires_at) or expires_at <= now:
        return None
    return binding


def _require_tx(conn: Connection):
    if (not conn.in_transaction() or conn.dialect.name not in ("postgresql", "sqlite")
            or conn.get_execution_options().get("isolation_level") == "AUTOCOMMIT"):
        raise OutcomeUnavailable("Expected a supported caller transaction")
    raw = conn.connection.driver_connection
    if getattr(raw, "autocommit", None) is True:
        raise OutcomeUnavailable("Expected a supported caller transaction")
    if conn.dialect.name == "sqlite":
        # SQLAlchemy's logical begin() alone is not a physical SQLite BEGIN.
        # An outermost SAVEPOINT can otherwise RELEASE and commit underneath it.
        if conn.in_nested_transaction() or not raw.in_transaction:
            raise OutcomeUnavailable("Expected a supported caller transaction")
        try:
            foreign_keys = conn.exec_driver_sql("PRAGMA foreign_keys").scalar_one_or_none()
        except SQLAlchemyError:
            raise OutcomeUnavailable("Cannot verify SQLite foreign keys") from None
        if foreign_keys != 1:
            raise OutcomeUnavailable("SQLite foreign keys must be enabled")


def stage_committed(conn: Connection, *, owner_id: int, order_id: int, attempt_id: str,
                    binding: bytes, expires_at: datetime, now: datetime) -> StageResult:
    """Stage once on the caller's transaction; do not commit or authorize a write.

    Only STAGED can proceed to a future write, which must additionally succeed
    in this exact transaction or roll back everything. EXACT is not a receipt
    to send and cannot authorize a second route write or extend retention.
    SQLite callers must establish a physical top-level transaction with FK
    enforcement already on. Nested or manually managed savepoints are not a
    supported staging boundary. This helper does not start or commit a transaction.
    """
    if not _identity(owner_id, order_id, attempt_id, binding) or not (
        _utc(now) and _utc(expires_at) and now < expires_at
    ):
        return StageResult.UNKNOWN
    _require_tx(conn)
    insert = pg_insert if conn.dialect.name == "postgresql" else sqlite_insert
    # Check persisted owner as part of the insert, not a stale caller assertion.
    # Explicit type for projection literals, especially bytea/timestamp on PG.
    values = (owner_id, order_id, attempt_id, binding, expires_at)
    columns = (*_KEY, _C.binding, _C.expires_at)
    source = select(*(literal(value, type_=col.type) for col, value in zip(columns, values, strict=True))).where(
        *_owned_order(owner_id, order_id),
    )
    try:
        statement = (insert(_RECEIPT).from_select(columns, source)
                     .on_conflict_do_nothing(index_elements=_KEY).returning(_C.order_id))
        if conn.execute(statement).scalar_one_or_none() is not None:
            return StageResult.STAGED
        old = _read(conn, owner_id, order_id, attempt_id, now)
        if old is not None and hmac.compare_digest(old, binding):
            return StageResult.EXACT
        return StageResult.UNKNOWN
    except (SQLAlchemyError, ValueError, TypeError):
        raise OutcomeUnavailable("Route edit outcome unavailable") from None


def committed_evidence(engine: Engine, *, owner_id: int, order_id: int, attempt_id: str,
                       binding: bytes, now: datetime) -> CommittedEvidence:
    """Independent committed observation only; absence is always unknown."""
    if not _identity(owner_id, order_id, attempt_id, binding) or not _utc(now):
        return CommittedEvidence.UNKNOWN
    if (engine.dialect.name not in ("postgresql", "sqlite")
            or isinstance(engine.pool, (StaticPool, SingletonThreadPool))
            or (engine.dialect.name == "sqlite" and engine.url.database in (None, "", ":memory:"))):
        return CommittedEvidence.UNKNOWN
    try:
        with engine.connect() as conn:
            old = _read(conn, owner_id, order_id, attempt_id, now)
        if old is not None and hmac.compare_digest(old, binding):
            return CommittedEvidence.COMMITTED
    except (SQLAlchemyError, ValueError, TypeError):
        pass
    return CommittedEvidence.UNKNOWN


def purge_expired(conn: Connection, *, cutoff: datetime, limit: int) -> int:
    """Independent bounded maintenance helper, not scheduled or called."""
    if not _utc(cutoff) or type(limit) is not int or not 1 <= limit <= 200:
        raise ValueError("Expected bounded expiry maintenance")
    _require_tx(conn)
    due = select(*_KEY).where(_C.expires_at <= cutoff).order_by(_C.expires_at, *_KEY).limit(limit)
    try:
        result = conn.execute(delete(_RECEIPT).where(_C.expires_at <= cutoff, tuple_(*_KEY).in_(due)))
        return result.rowcount
    except SQLAlchemyError:
        raise OutcomeUnavailable("Route edit outcome unavailable") from None
