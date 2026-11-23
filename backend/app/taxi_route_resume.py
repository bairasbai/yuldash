"""Inactive same-row route-retirement claim/offer/timeout primitives.

No producer, scheduler, request caller or side effects. Each function owns a
short, fresh SQL transaction. Claim does not authorize GEO or driver eligibility;
a future caller must supply an already selected and eligible driver.
"""
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta
import re
from uuid import uuid4

from psycopg2 import Error as PsycopgError
from psycopg2.extensions import TRANSACTION_STATUS_IDLE, connection as PsycopgConnection
from sqlalchemy import func, select, update
from sqlalchemy.engine import Connection, Engine
from sqlalchemy.pool import SingletonThreadPool, StaticPool

from .models import InstantOrder, InstantOrderStatus as Status

_ORDER = InstantOrder.__table__
_COL = _ORDER.c
_MARKERS = (
    "route_resume_id", "route_resume_previous_driver_id", "route_resume_retired_at",
    "route_resume_deadline_at", "route_resume_ready_at", "route_resume_claim_id",
)
_FIELDS = (
    "id", "passenger_id", "driver_id", "status", "created_at", "scheduled_at", "wait_until",
    "searching_at", "search_round", "reassigns", "retry_count", "offered_at",
    "current_offer_driver_id", "offer_expires_at", "expired_at", "cancelled_at", "accepted_at",
    "from_lat", "from_lng", "from_text", "to_lat", "to_lng", "to_text", "waypoints_json",
    "destination_changed_at", "destination_changes", "pending_asked_at", "pending_to_lat",
    "pending_to_lng", "pending_to_text", "pending_price", "pending_reason", "round_trip",
    "return_wait_min", "category", "fallback_categories", "options", "women_only",
    "distance_km", "eta_min", "tariff_id", "pricing_k", "surge_k", "ride_price",
    "ride_base_price", "price_estimate", "price_final", "pickup_fee_kop", "pickup_pending",
    "pickup_enroute", "pickup_km", "options_fee_kop", "weather_fee_kop", "weather_kind",
    "promo_discount_kop", "payment_method", "payment_changed_at", "paid",
    *_MARKERS,
)
_COLUMNS = tuple(_COL[field] for field in _FIELDS)
_TOKEN = re.compile(r"[a-f0-9]{32}")
_MAX_SECONDS = 120


@dataclass(frozen=True, repr=False)
class ResumeObservation:
    values: tuple


def observe(engine: Engine, order_id: int) -> ResumeObservation | None:
    """Fetch one explicitly marked order; this observes, it does not claim."""
    with engine.connect() as conn:
        row = conn.execute(select(*_COLUMNS).where(
            _COL.id == order_id, _COL.route_resume_id.is_not(None),
        )).one_or_none()
    return ResumeObservation(tuple(row)) if row is not None else None


def _state(observation: ResumeObservation) -> dict:
    return dict(zip(_FIELDS, observation.values, strict=True))


def _utc(value) -> bool:
    return isinstance(value, datetime) and value.tzinfo is None


def _token(value) -> bool:
    return type(value) is str and bool(_TOKEN.fullmatch(value))


def _valid(state: dict) -> bool:
    retired_at = state["route_resume_retired_at"]
    deadline = state["route_resume_deadline_at"]
    return (type(state["id"]) is int and state["id"] > 0
            and state["scheduled_at"] is None and state["wait_until"] is None
            and state["driver_id"] is None and state["accepted_at"] is None
            and state["cancelled_at"] is None and state["price_final"] is None
            and not state["paid"] and _token(state["route_resume_id"])
            and type(state["route_resume_previous_driver_id"]) is int
            and state["route_resume_previous_driver_id"] > 0
            and _utc(retired_at) and _utc(deadline)
            and timedelta(0) < deadline - retired_at <= timedelta(seconds=_MAX_SECONDS)
            and _utc(state["searching_at"]) and _utc(state["offered_at"])
            and state["searching_at"] <= retired_at
            and state["searching_at"] <= state["offered_at"] < deadline
            and type(state["search_round"]) is int and 0 < state["search_round"] < 2147483647)


def _searching(state: dict) -> bool:
    ready = state["route_resume_ready_at"]
    claim = state["route_resume_claim_id"]
    return (state["status"] == Status.searching
            and state["current_offer_driver_id"] is None and state["offer_expires_at"] is None
            and _utc(ready) and ready <= state["route_resume_deadline_at"]
            and (claim is None or _token(claim)))


def _successor_offer(state: dict) -> bool:
    expiry = state["offer_expires_at"]
    return (state["status"] == Status.offered
            and type(state["current_offer_driver_id"]) is int and state["current_offer_driver_id"] > 0
            and _utc(expiry) and state["offered_at"] < expiry <= state["route_resume_deadline_at"]
            and state["route_resume_ready_at"] is None and state["route_resume_claim_id"] is None)


def _require_writer_mode(conn: Connection) -> None:
    raw = conn.connection.driver_connection
    sqlalchemy_auto = conn.get_execution_options().get("isolation_level") == "AUTOCOMMIT"
    if conn.dialect.name == "sqlite":
        if raw.in_transaction:
            # The primitive owns this checkout, not the transaction already
            # on it. Closing an unsafe checkout avoids pooling a live lock.
            conn.invalidate()
            raise ValueError("Unsupported route resume transaction mode")
        unsupported = getattr(raw, "autocommit", None) is True or raw.isolation_level is None
    else:
        if not isinstance(raw, PsycopgConnection):
            raise ValueError("Unsupported route resume transaction mode")
        # psycopg2 reports the physical transaction status without executing
        # a query. SQLAlchemy's logical begin alone cannot establish ownership.
        try:
            idle = raw.get_transaction_status() == TRANSACTION_STATUS_IDLE
        except PsycopgError:
            idle = False
        if not idle:
            conn.invalidate()
            raise ValueError("Unsupported route resume transaction mode")
        # Engine-level autocommit can change the raw mode without an SA option.
        unsupported = raw.autocommit is not False
    if unsupported or sqlalchemy_auto:
        raise ValueError("Unsupported route resume transaction mode")


@contextmanager
def _writer(engine: Engine, order_id: int):
    if (engine.dialect.name not in ("postgresql", "sqlite")
            or (engine.dialect.name == "postgresql" and engine.dialect.driver != "psycopg2")):
        raise ValueError("Unsupported route resume database")
    if engine.dialect.name == "sqlite" and (
        engine.url.database in (None, "", ":memory:")
        or isinstance(engine.pool, (StaticPool, SingletonThreadPool))
    ):
        raise ValueError("Route resume needs an independently connected SQLite file database")
    with engine.connect() as conn:
        _require_writer_mode(conn)
        with conn.begin():
            # A begin listener can change the raw mode or open a physical
            # SQLite transaction; verify again before any primitive SQL.
            _require_writer_mode(conn)
            if conn.dialect.name == "sqlite":
                conn.exec_driver_sql("BEGIN IMMEDIATE")
            else:
                # Acquire the physical owner row before the clock-bearing DML.
                # An UPDATE predicate alone could be evaluated before lock wait.
                conn.execute(select(_COL.id).where(_COL.id == order_id).with_for_update()).all()
            yield conn


def _clock(conn: Connection, seconds: int = 0):
    if conn.dialect.name == "postgresql":
        # All occurrences within the same final statement use one UTC clock.
        at = func.timezone("UTC", func.statement_timestamp())
        return at + timedelta(seconds=seconds) if seconds else at
    shifts = (f"+{seconds} seconds",) if seconds else ()
    return func.strftime("%Y-%m-%d %H:%M:%f000", "now", *shifts)


def _bounded_until(conn: Connection, seconds: int):
    limit = _clock(conn, seconds)
    if conn.dialect.name == "postgresql":
        return func.least(_COL.route_resume_deadline_at, limit)
    return func.min(_COL.route_resume_deadline_at, limit)


def _matches(observation: ResumeObservation) -> tuple:
    return tuple(column == value for column, value in zip(_COLUMNS, observation.values, strict=True))


def _duration(seconds: int) -> None:
    if type(seconds) is not int or not 1 <= seconds <= _MAX_SECONDS:
        raise ValueError("Route resume duration must be an integer 1..120")


def _perform(conn: Connection, statement) -> ResumeObservation | None:
    rows = conn.execute(statement.returning(*_COLUMNS)).all()
    return ResumeObservation(tuple(rows[0])) if rows else None


def claim(engine: Engine, expected: ResumeObservation, *, lease_seconds: int) -> ResumeObservation | None:
    """Claim one exact, due, existing marker; no attempts or deadline changes."""
    _duration(lease_seconds)
    state = _state(expected)
    if not _valid(state) or not _searching(state):
        return None
    with _writer(engine, state["id"]) as conn:
        now = _clock(conn)
        return _perform(conn, update(_ORDER).where(
            *_matches(expected),
            _COL.route_resume_retired_at <= now, _COL.route_resume_ready_at <= now,
            now < _COL.route_resume_deadline_at,
        ).values(route_resume_claim_id=uuid4().hex,
                 route_resume_ready_at=_bounded_until(conn, lease_seconds)))


def store_offer(engine: Engine, claimed: ResumeObservation, *, driver_id: int,
                offer_seconds: int) -> ResumeObservation | None:
    """Store one frozen outcome; retain marker/deadline across the live offer."""
    _duration(offer_seconds)
    state = _state(claimed)
    if (not _valid(state) or not _searching(state) or not _token(state["route_resume_claim_id"])
            or type(driver_id) is not int or driver_id <= 0
            or driver_id == state["route_resume_previous_driver_id"]):
        return None
    with _writer(engine, state["id"]) as conn:
        now = _clock(conn)
        return _perform(conn, update(_ORDER).where(
            *_matches(claimed), _COL.route_resume_retired_at <= now,
            now < _COL.route_resume_ready_at, now < _COL.route_resume_deadline_at,
            _COL.route_resume_previous_driver_id != driver_id,
        ).values(status=Status.offered, current_offer_driver_id=driver_id, offered_at=now,
                 offer_expires_at=_bounded_until(conn, offer_seconds), search_round=_COL.search_round + 1,
                 route_resume_ready_at=None, route_resume_claim_id=None))


def store_timeout(engine: Engine, expected: ResumeObservation) -> ResumeObservation | None:
    """Stop only this marker at the deadline; no matcher/notification/fee side effects."""
    state = _state(expected)
    if not _valid(state) or not (_searching(state) or _successor_offer(state)):
        return None
    with _writer(engine, state["id"]) as conn:
        now = _clock(conn)
        return _perform(conn, update(_ORDER).where(
            *_matches(expected), now >= _COL.route_resume_deadline_at,
        ).values(status=Status.cancelled, cancelled_at=now, cancel_by="system",
                 cancel_reason="route_resume_timeout", current_offer_driver_id=None, offer_expires_at=None,
                 **dict.fromkeys(_MARKERS)))
