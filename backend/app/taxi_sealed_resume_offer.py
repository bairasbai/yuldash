"""Uncalled SQL storage of a sealed successor, not dispatch or admission."""
from sqlalchemy import DateTime, literal, select, type_coerce, update
from sqlalchemy.engine import Engine
from sqlalchemy.exc import SQLAlchemyError

from . import taxi_retirement_agreement_seal as seal, taxi_route_resume as resume
from . import taxi_sealed_resume_claim as claims
from .config import settings
from .models import InstantOrderStatus as S, User

_ORDER = resume._ORDER
_C = _ORDER.c
_USER = User.__table__.c
_MAX_ROUND = (1 << 31) - 2  # resume._valid must also accept the successor


def store_sealed_successor_offer(
    engine: Engine, key: bytes, *, owner_id: int, order_id: int, marker_id: str,
    claim: claims.SealedResumeClaim, driver_id: int, offer_seconds: int,
) -> resume.ResumeObservation | None:
    """Write one exact acknowledged claim's chosen successor, without delivery.

    The caller must already have selected and validated the driver; this
    primitive does not consider availability, eligibility, or pay. None
    includes unknown commit outcome and never authorizes a repeated effect.
    """
    resume._duration(offer_seconds)
    expected = claims._acknowledged(key, claim)
    if (expected is None
            or not all(type(v) is int and 0 < v <= 2147483647 for v in (owner_id, order_id, driver_id))
            or not resume._token(marker_id) or driver_id == owner_id):
        return None
    state = resume._state(expected)
    cap = min(settings.instant_max_offers, _MAX_ROUND)
    if ((state["id"], state["passenger_id"], state["route_resume_id"]) != (order_id, owner_id, marker_id)
            or not resume._valid(state) or not resume._searching(state)
            or not resume._token(state["route_resume_claim_id"])
            or state["search_round"] >= cap or state["route_resume_previous_driver_id"] == driver_id):
        return None
    try:
        with resume._writer(engine, order_id) as conn:
            phase = claims._initial_search_guards()
            observed = conn.execute(select(*resume._COLUMNS, type_coerce(resume._clock(conn), DateTime())).where(
                *resume._matches(expected), *phase,
            )).one_or_none()
            if observed is None:
                raise claims._Unresolved
            current = resume.ResumeObservation(tuple(observed[:-1]))
            now = observed[-1]
            fresh = resume._state(current)
            if (not resume._utc(now) or not resume._valid(fresh) or not resume._searching(fresh)
                    or not fresh["route_resume_retired_at"] <= now < fresh["route_resume_ready_at"]
                    or not seal.matches_retirement_agreement(
                        key, seal.RetirementAgreementSeal(fresh[resume._SEAL]), owner_id=owner_id,
                        order_id=order_id, current=fresh, now=now,
                    )):
                raise claims._Unresolved

            clock = resume._clock(conn)
            upper = claims._upper_clock(conn)
            expiry = resume._bounded_until(conn, offer_seconds)
            driver_exists = select(_USER.id).where(_USER.id == driver_id).exists()
            stored = conn.execute(update(_ORDER).where(
                *resume._matches(current), *phase, driver_exists,
                _C.search_round < cap, _C.route_resume_previous_driver_id != driver_id,
                _C.route_resume_retired_at <= clock,
                upper < _C.route_resume_ready_at, upper < _C.route_resume_deadline_at,
            ).values(
                status=S.offered, current_offer_driver_id=driver_id, offered_at=clock,
                offer_expires_at=expiry, search_round=_C.search_round + 1,
                route_resume_claim_id=None, route_resume_ready_at=None,
            ).returning(
                *resume._COLUMNS, type_coerce(clock, DateTime()), type_coerce(expiry, DateTime()),
            )).one_or_none()
            if stored is None:
                raise claims._Unresolved
            offered = resume.ResumeObservation(tuple(stored[:-2]))
            after = resume._state(offered)
            expected_after = fresh | {
                "status": S.offered, "current_offer_driver_id": driver_id,
                "offered_at": stored[-2], "offer_expires_at": stored[-1],
                "search_round": fresh["search_round"] + 1,
                "route_resume_claim_id": None, "route_resume_ready_at": None,
            }
            if (after != expected_after or not resume._valid(after) or not resume._successor_offer(after)
                    or conn.scalar(select(_C.id).where(
                        *resume._matches(offered), *phase, driver_exists,
                        _C.route_resume_retired_at <= clock,
                        upper < _C.offer_expires_at, upper < _C.route_resume_deadline_at,
                        upper < literal(fresh["route_resume_ready_at"], type_=DateTime()),
                    )) != order_id):
                raise claims._Unresolved
    except (claims._Unresolved, SQLAlchemyError, ValueError, TypeError, OverflowError):
        return None
    return offered
