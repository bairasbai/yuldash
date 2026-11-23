"""Uncalled sealed acceptance storage after trusted driver admission; not an API."""
from contextlib import contextmanager
from enum import Enum
import hmac

from sqlalchemy import DateTime, literal, select, type_coerce, update
from sqlalchemy.engine import Engine
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.pool import SingletonThreadPool, StaticPool

from . import taxi_marked_offer_card as card, taxi_route_resume as resume, taxi_sealed_offer_card as sealed
from .models import InstantOrderStatus as S, User
from .taxi_sealed_resume_claim import _upper_clock

_ORDER = resume._ORDER
_C = _ORDER.c
_USER = User.__table__.c
_COLUMNS = tuple(_C[name] for name in card._FIELDS)


class SealedAcceptance(Enum):
    ACCEPTED = "accepted"
    UNKNOWN = "unknown"


class _Unresolved(Exception):
    """No acknowledged result; abort any in-flight assignment."""


@contextmanager
def _owned_assignment(engine, *, driver_id, order_id):
    with engine.connect() as conn:
        resume._require_writer_mode(conn)
        with conn.begin():
            resume._require_writer_mode(conn)
            driver = select(_USER.id).where(_USER.id == driver_id)
            if conn.dialect.name == "sqlite":
                conn.exec_driver_sql("BEGIN IMMEDIATE")
            else:
                # Check actual isolation within the verified transaction: a
                # psycopg2 SHOW on an idle preflight checkout starts a txn.
                if conn.exec_driver_sql("SHOW transaction_isolation").scalar_one() != "read committed":
                    raise _Unresolved  # a previous snapshot could miss a competing assignment
                # Same lock order and mode as ordinary accept. The strong
                # Order lock also conflicts with new FK key-shares from
                # current Payment/Ledger inserts while this transaction owns it.
                driver = driver.with_for_update(key_share=True)
            if conn.scalar(driver) != driver_id:
                raise _Unresolved
            if conn.dialect.name == "postgresql" and conn.scalar(
                select(_C.id).where(_C.id == order_id).with_for_update(),
            ) != order_id:
                raise _Unresolved
            yield conn


def _same(values: tuple):
    return tuple(c == v for c, v in zip(_COLUMNS, values, strict=True))


def _other_active(order_id, driver_id):
    other = _ORDER.alias("other_active_sealed_accept")
    # Same authoritative driver's assigned-slot rule as ordinary accept.
    return select(other.c.id).where(
        other.c.id != order_id, other.c.driver_id == driver_id,
        other.c.status.in_((S.accepted, S.arriving, S.onboard)),
    ).exists()


def _accept_locked(conn, key, proof, *, owner_id, order_id, driver_id):
    observation = sealed._read_sealed_offer(conn, key, owner_id=owner_id, order_id=order_id, driver_id=driver_id)
    if observation is None or not hmac.compare_digest(proof._digest, observation._proof._digest):
        raise _Unresolved
    old = dict(zip(card._FIELDS, observation._values, strict=True))
    stamp = resume._clock(conn)
    upper = _upper_clock(conn)
    stable_fences = (
        ~_other_active(order_id, driver_id), ~card._HAS_PAYMENT, ~card._HAS_LEDGER,
        literal(old["offered_at"], type_=DateTime()) <= stamp,
        upper < literal(old["offer_expires_at"], type_=DateTime()),
        upper < literal(old["route_resume_deadline_at"], type_=DateTime()),
    )
    changes = dict(
        status=S.accepted, driver_id=driver_id, accepted_at=stamp,
        current_offer_driver_id=None, offer_expires_at=None,
        **dict.fromkeys(resume._MARKERS),
    )
    updated = conn.execute(update(_ORDER).where(
        *_same(observation._values), *stable_fences,
    ).values(**changes).returning(*_COLUMNS, type_coerce(stamp, DateTime()))).one_or_none()
    if updated is None:
        raise _Unresolved
    actual_at = updated[-1]
    if not resume._utc(actual_at) or not old["offered_at"] <= actual_at < min(
        old["offer_expires_at"], old["route_resume_deadline_at"],
    ):
        raise _Unresolved
    expected = old | changes | {"accepted_at": actual_at}
    values = tuple(expected[name] for name in card._FIELDS)
    if tuple(updated[:-1]) != values or conn.scalar(select(_C.id).where(
        *_same(values), *stable_fences,
    )) != order_id:
        # SQLite RETURNING doesn't include AFTER triggers. Recheck exact
        # permitted fields, both original clocks and financially empty row.
        raise _Unresolved


def accept_sealed_offer(
    engine: Engine, key: bytes, proof: sealed.SealedOfferCardProof | None, *,
    owner_id: int, order_id: int, driver_id: int,
) -> SealedAcceptance:
    """Only trusted authenticated and already admitted driver context may call.

    No caller exists. Standing, eligibility and transport authentication must
    be owned by a future caller; mere presence of a User or valid proof is
    not admission. UNKNOWN never authorizes a new attempt or proves absence.
    """
    if (type(proof) is not sealed.SealedOfferCardProof
            or type(proof._digest) is not bytes or len(proof._digest) != 32
            or type(key) is not bytes or len(key) < 16
            or not all(type(v) is int and 0 < v <= 2147483647 for v in (owner_id, order_id, driver_id))
            or owner_id == driver_id or engine.dialect.name not in ("sqlite", "postgresql")
            or (engine.dialect.name == "postgresql" and engine.dialect.driver != "psycopg2")
            or (engine.dialect.name == "sqlite"
                and (engine.url.database in (None, "", ":memory:")
                     or isinstance(engine.pool, (StaticPool, SingletonThreadPool))))):
        return SealedAcceptance.UNKNOWN
    try:
        with _owned_assignment(engine, driver_id=driver_id, order_id=order_id) as conn:
            _accept_locked(conn, key, proof, owner_id=owner_id, order_id=order_id, driver_id=driver_id)
    except (_Unresolved, SQLAlchemyError, ValueError, TypeError, OverflowError):
        return SealedAcceptance.UNKNOWN
    return SealedAcceptance.ACCEPTED
