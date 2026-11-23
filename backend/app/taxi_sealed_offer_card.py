"""Uncalled private view of a genuinely sealed successor, not driver admission."""
from dataclasses import dataclass
import hmac
import json

from sqlalchemy import DateTime, select, type_coerce
from sqlalchemy.engine import Connection
from sqlalchemy.exc import SQLAlchemyError

from . import taxi_marked_offer_card as card, taxi_offered_waypoint_preview as preview
from . import taxi_retirement_agreement_seal as seal, taxi_route_resume as resume
from .models import InstantOrderStatus as S
from .taxi_route_agreement import _source_value
from .taxi_sealed_resume_claim import _upper_clock

_PURPOSE = b"taxi-sealed-successor-driver-card-v1\x00"


@dataclass(frozen=True, slots=True, repr=False)
class SealedOfferCardProof:
    """Private digest only, never a driver token or an authorization."""
    _digest: bytes


@dataclass(frozen=True, slots=True, repr=False)
class _ObservedSealedOffer:
    # Kept internal for a future locked exact SQL CAS; never return as a DTO.
    _values: tuple
    _proof: SealedOfferCardProof


def _read_sealed_offer(
    conn: Connection, key: bytes, *, owner_id: int, order_id: int, driver_id: int,
) -> _ObservedSealedOffer | None:
    """One scoped SELECT; locks/eligibility/admission are a future caller's job."""
    if (conn.dialect.name not in ("postgresql", "sqlite")
            or type(key) is not bytes or len(key) < 16
            or not all(map(preview._id, (owner_id, order_id, driver_id))) or owner_id == driver_id):
        return None
    fields, col = card._FIELDS, card._COL
    try:
        result = conn.execute(select(
            *(col[name] for name in fields), type_coerce(resume._clock(conn), DateTime()),
        ).where(
            col.id == order_id, col.passenger_id == owner_id,
            col.current_offer_driver_id == driver_id, col.status == S.offered,
            col.route_resume_id.is_not(None), col[resume._SEAL].is_not(None),
            _upper_clock(conn) < col.offer_expires_at,
            _upper_clock(conn) < col.route_resume_deadline_at,
            ~card._HAS_PAYMENT, ~card._HAS_LEDGER,
        )).one_or_none()
        if result is None:
            return None
        values, now = tuple(result[:-1]), result[-1]
        current = dict(zip(fields, values, strict=True))
        stored = current[resume._SEAL]
        if (not card._valid_current(current, now)
                or not seal.matches_retirement_agreement(
                    key, seal.RetirementAgreementSeal(stored), owner_id=owner_id, order_id=order_id,
                    current=current, now=now,
                )):
            return None
        material = tuple((name, {"blob": stored.hex()} if name == resume._SEAL else _source_value(value))
                         for name, value in zip(fields, values, strict=True))
        encoded = json.dumps(material, ensure_ascii=True, allow_nan=False, separators=(",", ":")).encode("ascii")
    except (SQLAlchemyError, TypeError, ValueError, OverflowError, RecursionError):
        return None
    return _ObservedSealedOffer(values, SealedOfferCardProof(hmac.digest(key, _PURPOSE + encoded, "sha256")))


def observe_sealed_offer_card(
    conn: Connection, key: bytes, *, owner_id: int, order_id: int, driver_id: int,
) -> SealedOfferCardProof | None:
    """No writes, receipt lookup, refresh, or statement that the driver may accept."""
    observed = _read_sealed_offer(conn, key, owner_id=owner_id, order_id=order_id, driver_id=driver_id)
    return observed._proof if observed is not None else None


def matches_sealed_offer_card(
    conn: Connection, key: bytes, proof: SealedOfferCardProof | None, *,
    owner_id: int, order_id: int, driver_id: int,
) -> bool:
    """Comparison only; future acceptance also needs owned/admitted final SQL."""
    if (type(proof) is not SealedOfferCardProof
            or type(proof._digest) is not bytes or len(proof._digest) != 32):
        return False
    current = observe_sealed_offer_card(conn, key, owner_id=owner_id, order_id=order_id, driver_id=driver_id)
    return current is not None and hmac.compare_digest(proof._digest, current._digest)
