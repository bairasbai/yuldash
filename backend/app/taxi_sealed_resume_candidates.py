"""Uncalled read-only discovery of sealed retirement candidates; no authority."""
from dataclasses import dataclass
from datetime import datetime
from enum import Enum

from sqlalchemy import Boolean, DateTime, Integer, String, select, type_coerce
from sqlalchemy.engine import Engine
from sqlalchemy.exc import SQLAlchemyError

from . import taxi_marked_offer_card as card
from . import taxi_retirement_agreement_seal as seal
from . import taxi_route_resume as resume
from . import taxi_sealed_resume_claim as claims
from .models import InstantOrderStatus as S

_C = resume._COL
_MAX_PAGE = 64
_MAX_ID = (1 << 31) - 1
_TIME_NAMES = tuple(col.key for col in resume._COLUMNS if isinstance(col.type, DateTime))
_BOOL_NAMES = tuple(col.key for col in resume._COLUMNS if isinstance(col.type, Boolean))


class CandidateKind(Enum):
    CLAIM_DUE = "claim_due"
    TIMEOUT_DUE = "timeout_due"


@dataclass(frozen=True, slots=True, repr=False)
class SealedResumeCandidate:
    order_id: int
    marker_id: str
    kind: CandidateKind


@dataclass(frozen=True, slots=True, repr=False)
class SealedResumePage:
    candidates: tuple[SealedResumeCandidate, ...]
    next_after_id: int | None


def _sqlite_date(value):
    if value is None:
        return None
    # Bypass eager DBAPI DateTime decoding for these SQLite columns. One
    # corrupt row must not poison the whole bounded scan or strand its cursor.
    if type(value) is not str or len(value) != 26:
        raise ValueError("Noncanonical SQLite route resume clock")
    decoded = datetime.fromisoformat(value)
    if decoded.tzinfo is not None or decoded.isoformat(sep=" ", timespec="microseconds") != value:
        raise ValueError("Noncanonical SQLite route resume clock")
    return decoded


def _sqlite_bool(value):
    # SQLite Boolean result processing maps any nonzero integer to True,
    # while the real compare-and-write can only bind canonical 0/1.
    if type(value) is not int or value not in (0, 1):
        raise ValueError("Noncanonical SQLite route resume boolean")
    return bool(value)


def _sqlite_column(column):
    if column.key in _TIME_NAMES:
        return type_coerce(column, String())
    if column.key in _BOOL_NAMES:
        return type_coerce(column, Integer())
    return column


def _classify(state, now, upper, key):
    if (not resume._valid(state) or state[resume._SEAL] is None
            or not all(type(state[name]) is int and 0 < state[name] <= _MAX_ID for name in (
                "id", "passenger_id", "route_resume_previous_driver_id",
            ))
            or state["passenger_id"] == state["route_resume_previous_driver_id"]
            or not resume._utc(state["created_at"])):
        return None
    retired = state["route_resume_retired_at"]
    deadline = state["route_resume_deadline_at"]
    if not state["created_at"] <= state["searching_at"] <= retired <= now:
        return None
    if state["status"] == S.searching:
        ready = state["route_resume_ready_at"]
        if not resume._searching(state) or state["offered_at"] > retired:
            return None
        # An untouched retirement is ready exactly at retirement. A
        # claim, even an elapsed one, has a separately advanced lease.
        if (state["route_resume_claim_id"] is None and ready != retired
                or state["route_resume_claim_id"] is not None and ready <= retired):
            return None
    elif (not resume._successor_offer(state) or state["search_round"] < 2
          or not retired <= state["offered_at"] <= now
          or state["current_offer_driver_id"] in (
              state["route_resume_previous_driver_id"], state["passenger_id"],
          ) or state["current_offer_driver_id"] > _MAX_ID):
        return None
    if now >= deadline:
        kind = CandidateKind.TIMEOUT_DUE
    elif state["status"] == S.searching and state["route_resume_ready_at"] <= now and upper < deadline:
        kind = CandidateKind.CLAIM_DUE
    else:
        # No refresh, new offer, or handling of an elapsed offer lease
        # before the original absolute timeout is implied here.
        return None
    if not seal._matches_stored_agreement(
        key, seal.RetirementAgreementSeal(state[resume._SEAL]),
        owner_id=state["passenger_id"], order_id=state["id"], current=state,
    ):
        return None
    return SealedResumeCandidate(state["id"], state["route_resume_id"], kind)


def scan_sealed_resume_candidates(
    engine: Engine, key: bytes, *, after_id: int = 0, limit: int = _MAX_PAGE,
) -> SealedResumePage | None:
    """Inspect one ID-keyset page; None is invalid/unknown, not an empty page.

    The limit bounds inspected SQL results, not storage work or global
    fairness. Restart at zero on a future sweep; rows made due behind the
    cursor are not in this sweep. The next cursor advances past inspected
    malformed/non-due rows, not merely the returned hints. A scan owns
    no claim, timeout, or admission; re-read on the authoritative writer.
    """
    if (not isinstance(engine, Engine)
            or type(after_id) is not int or not 0 <= after_id <= _MAX_ID
            or type(limit) is not int or not 1 <= limit <= _MAX_PAGE
            or type(key) is not bytes or len(key) < 16
            or engine.dialect.name not in ("sqlite", "postgresql")
            or (engine.dialect.name == "postgresql" and engine.dialect.driver != "psycopg2")):
        return None
    sqlite = engine.dialect.name == "sqlite"
    try:
        with engine.connect() as conn:
            stamp_type = String() if sqlite else DateTime()
            columns = tuple(map(_sqlite_column, resume._COLUMNS)) if sqlite else resume._COLUMNS
            rows = conn.execute(select(
                *columns, type_coerce(resume._clock(conn), stamp_type),
                type_coerce(claims._upper_clock(conn), stamp_type),
            ).where(
                _C.id > after_id, _C.id <= _MAX_ID,
                _C.status.in_((S.searching, S.offered)),
                _C.route_resume_id.is_not(None), _C[resume._SEAL].is_not(None),
                _C.route_resume_retired_at.is_not(None), _C.route_resume_deadline_at.is_not(None),
                *claims._initial_search_guards(), ~card._HAS_PAYMENT, ~card._HAS_LEDGER,
            ).order_by(_C.id).limit(limit)).all()
        hints = []
        for row in rows:
            # A clock failure means unknown; a row-format failure separately
            # hides only that row while the cursor still passes its actual ID.
            now, upper = row[-2:]
            if sqlite:
                now, upper = _sqlite_date(now), _sqlite_date(upper)
            if not resume._utc(now) or not resume._utc(upper) or upper < now:
                return None
            state = dict(zip(resume._FIELDS, row[:-2], strict=True))
            try:
                if sqlite:
                    state.update((field, _sqlite_date(state[field])) for field in _TIME_NAMES)
                    state.update((field, _sqlite_bool(state[field])) for field in _BOOL_NAMES)
                candidate = _classify(state, now, upper, key)
            except (TypeError, ValueError, OverflowError):
                continue
            if candidate is not None:
                hints.append(candidate)
        return SealedResumePage(tuple(hints), rows[-1][0] if len(rows) == limit else None)
    except (SQLAlchemyError, TypeError, ValueError, OverflowError):
        return None
