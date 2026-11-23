"""Uncalled lease claim for one exact sealed retirement; no dispatch authority."""
from uuid import uuid4

from sqlalchemy import DateTime, func, select, type_coerce, update
from sqlalchemy.engine import Engine
from sqlalchemy.exc import SQLAlchemyError

from . import taxi_retirement_agreement_seal as seal, taxi_route_resume as resume

_ORDER = resume._ORDER
_C = _ORDER.c


def _upper_clock(conn):
    # Conservatively refuse the final SQLite millisecond at the absolute
    # deadline. Lease readiness itself uses the same stored SQL clock.
    if conn.dialect.name == "sqlite":
        return func.strftime("%Y-%m-%d %H:%M:%f000", "now", "+0.001 seconds")
    return resume._clock(conn)


def _initial_search_guards() -> tuple:
    # A valid seal proves agreed terms, not unrelated state or eligibility.
    # Keep this first slice in the producer's pre-assignment/search lane.
    return (
        *(_C[name].is_(None) for name in (
            "expired_at", "arriving_at", "onboard_at", "done_at", "pending_asked_at",
            "pending_to_lat", "pending_to_lng", "pending_price", "waiting_started_at",
            "stop_started_at", "destination_ack_at", "payment_changed_at", "payment_ack_at",
        )),
        *(_C[name].is_(False) for name in ("round_trip", "pickup_pending", "paid", "no_show")),
        *(_C[name] == 0 for name in (
            "reassigns", "retry_count", "return_wait_min", "waiting_fee_kop", "cancel_fee_kop", "driven_km",
        )),
        *(_C[name] == "" for name in ("pending_to_text", "pending_reason", "fallback_categories")),
    )


class _Unresolved(Exception):
    """A refused or unknowable claim; cannot leave an intermediate update."""


def claim_sealed_retirement(
    engine: Engine, key: bytes, *, owner_id: int, order_id: int, marker_id: str,
    expected: resume.ResumeObservation, lease_seconds: int,
) -> resume.ResumeObservation | None:
    """Claim exact sealed work, without permission to offer or assign.

    A non-None result follows acknowledged commit. None means no usable
    confirmation (which includes lost acknowledgement), not absence.
    No receipt, card, or quote can replace the persisted generation seal.
    """
    resume._duration(lease_seconds)
    if (type(expected) is not resume.ResumeObservation or type(expected.values) is not tuple
            or len(expected.values) != len(resume._FIELDS)
            or type(key) is not bytes or len(key) < 16
            or not all(type(value) is int and 0 < value <= 2147483647 for value in (owner_id, order_id))
            or not resume._token(marker_id)):
        return None
    state = resume._state(expected)
    identity = (state["id"], state["passenger_id"], state["route_resume_id"])
    if (type(identity[0]) is not int or type(identity[1]) is not int
            or identity != (order_id, owner_id, marker_id)
            or state[resume._SEAL] is None or not resume._valid(state) or not resume._searching(state)):
        return None
    try:
        with resume._writer(engine, order_id) as conn:
            phase = _initial_search_guards()
            observed = conn.execute(select(*resume._COLUMNS, type_coerce(resume._clock(conn), DateTime())).where(
                *resume._matches(expected), *phase,
            )).one_or_none()
            if observed is None:
                raise _Unresolved
            current = resume.ResumeObservation(tuple(observed[:-1]))
            now = observed[-1]
            fresh = resume._state(current)
            raw_seal = fresh[resume._SEAL]
            if (not resume._valid(fresh) or not resume._searching(fresh)
                    or fresh["route_resume_ready_at"] < fresh["route_resume_retired_at"]
                    or not resume._utc(now) or fresh["route_resume_ready_at"] > now
                    or not seal.matches_retirement_agreement(
                        key, seal.RetirementAgreementSeal(raw_seal), owner_id=owner_id, order_id=order_id,
                        current=fresh, now=now,
                    )):
                raise _Unresolved
            claim_id = uuid4().hex
            ready_at = resume._bounded_until(conn, lease_seconds)
            stored = conn.execute(update(_ORDER).where(
                *resume._matches(current), *phase,
                _C.route_resume_retired_at <= resume._clock(conn),
                _C.route_resume_ready_at <= resume._clock(conn),
                _upper_clock(conn) < _C.route_resume_deadline_at,
            ).values(route_resume_claim_id=claim_id, route_resume_ready_at=ready_at).returning(
                *resume._COLUMNS, type_coerce(ready_at, DateTime()),
            )).one_or_none()
            if stored is None:
                raise _Unresolved
            claim = resume.ResumeObservation(tuple(stored[:-1]))
            after = resume._state(claim)
            expected_after = fresh | {"route_resume_claim_id": claim_id, "route_resume_ready_at": stored[-1]}
            if (after != expected_after or not resume._searching(after)
                    or conn.scalar(select(_C.id).where(
                        *resume._matches(claim), *phase,
                        _C.route_resume_retired_at <= resume._clock(conn),
                        resume._clock(conn) < _C.route_resume_ready_at,
                        _upper_clock(conn) < _C.route_resume_deadline_at,
                    )) != order_id):
                raise _Unresolved
    except (_Unresolved, SQLAlchemyError, ValueError, TypeError, OverflowError):
        return None
    return claim
