"""Uncalled storage-only SQL offer publication lab, not production admission.

No driver reservation, eligibility, Redis or dispatch is performed. Applied does
not mean committed or delivered. Caller must roll back its whole business unit on
any failure and must not write stale ORM owner/history fields afterwards.
"""
import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from uuid import uuid4

from sqlalchemy import func, select, update
from sqlalchemy.engine import Connection

from .models import InstantOrder, InstantOrderStatus as S
from .taxi_tried_history_reader import inspect_tried_candidate
from .taxi_tried_history_write import OwnershipMismatch, remember_tried_history


class CandidateExcluded(RuntimeError):
    """The candidate is already excluded by this active SQL history cycle."""


class OfferLimitReached(RuntimeError):
    """The captured search round has reached the caller's explicit offer limit."""


@dataclass(frozen=True, slots=True)
class SqlOfferPublication:
    id: int
    driver_id: int
    revision: int
    lifecycle: str
    token: str
    offered_at: datetime
    offer_expires_at: datetime
    search_round: int
    history_expiry: datetime


def publish_sql_offer_lab(
    connection: Connection,
    order_id: int,
    expected_revision: int,
    expected_lifecycle: str,
    expected_searching_at: datetime,
    expected_search_round: int,
    expected_offer_token: str | None,
    driver_id: int,
    offer_ttl_seconds: int,
    max_offers: int,
) -> SqlOfferPublication:
    """Compose owner guard, actual history reader, offer UPDATE and history writer.

    Only our enclosing savepoint is released/rolled back. The caller owns the outer
    transaction. Different orders may still publish to the same driver: this lab
    has no cross-order reservation or production authorization policy.
    """
    if not isinstance(connection, Connection):
        raise TypeError("An active PostgreSQL Connection is required")
    if any(type(value) is not int or not 1 <= value <= 2**31 - 1 for value in (order_id, driver_id)):
        raise ValueError("order_id and driver_id must be positive INTEGER values")
    if type(expected_revision) is not int or not 0 <= expected_revision <= 2**63 - 1:
        raise ValueError("expected_revision must be a nonnegative BIGINT value")
    if not isinstance(expected_lifecycle, str) or re.fullmatch(r"[0-9a-f]{32}", expected_lifecycle) is None:
        raise ValueError("expected_lifecycle must be 32 lowercase hexadecimal characters")
    if expected_offer_token is not None and (
        not isinstance(expected_offer_token, str)
        or re.fullmatch(r"[0-9a-f]{32}", expected_offer_token) is None
    ):
        raise ValueError("expected_offer_token must be None or 32 lowercase hexadecimal characters")
    if not isinstance(expected_searching_at, datetime) or expected_searching_at.tzinfo is not None:
        raise ValueError("expected_searching_at must be a nonNULL naive UTC datetime")
    if type(expected_search_round) is not int or not 0 <= expected_search_round <= 2**31 - 2:
        raise ValueError("expected_search_round must allow an INTEGER increment")
    if type(max_offers) is not int or not 1 <= max_offers <= 2**31 - 1:
        raise ValueError("max_offers must be a positive INTEGER value")
    if type(offer_ttl_seconds) is not int or offer_ttl_seconds <= 0:
        raise ValueError("offer_ttl_seconds must be a positive integer")
    if connection.dialect.name != "postgresql" or connection.dialect.driver != "psycopg2":
        raise ValueError("Offer publication lab supports PostgreSQL psycopg2 only")
    transaction = connection.get_transaction()
    if connection.closed or connection.invalidated or transaction is None or not transaction.is_active:
        raise ValueError("An active caller transaction is required")
    if getattr(connection.connection.driver_connection, "autocommit", None) is not False:
        raise ValueError("Actual DBAPI autocommit must be disabled")

    orders = InstantOrder.__table__
    with connection.begin_nested():
        owner = connection.execute(select(
            orders.c.status, orders.c.search_revision, orders.c.tried_lifecycle,
            orders.c.searching_at, orders.c.search_round, orders.c.offer_token,
            orders.c.tried_history_version, orders.c.tried_history_expires_at,
            orders.c.driver_id, orders.c.current_offer_driver_id, orders.c.offer_expires_at,
        ).where(orders.c.id == order_id).with_for_update()).first()
        expected = (S.searching, expected_revision, expected_lifecycle,
                    expected_searching_at, expected_search_round, expected_offer_token)
        if (owner is None or tuple(owner[:6]) != expected or owner[6] != 1
                or not isinstance(owner[7], datetime) or owner[7].tzinfo is not None
                or any(value is not None for value in owner[8:])):
            raise OwnershipMismatch("Offer owner does not match the clean captured searching cycle")
        if expected_search_round >= max_offers:
            raise OfferLimitReached("Search offer limit reached")
        observation = inspect_tried_candidate(
            connection, order_id, expected_revision, expected_lifecycle, driver_id,
        )
        if observation.excluded:
            raise CandidateExcluded("Candidate belongs to the active tried set")

        clock = connection.execute(select(func.clock_timestamp())).scalar_one()
        if not isinstance(clock, datetime) or clock.tzinfo is None:
            raise ValueError("PostgreSQL clock_timestamp must return an aware timestamp")
        offered_at = clock.astimezone(timezone.utc).replace(tzinfo=None)
        try:
            offer_expires_at = offered_at + timedelta(seconds=offer_ttl_seconds)
        except OverflowError as error:
            raise ValueError("offer_ttl_seconds exceeds datetime range") from error
        token = uuid4().hex
        result = connection.execute(update(orders).where(
            orders.c.id == order_id, orders.c.status == S.searching,
            orders.c.search_revision == expected_revision,
            orders.c.tried_lifecycle == expected_lifecycle,
            orders.c.searching_at == expected_searching_at,
            orders.c.search_round == expected_search_round,
            orders.c.offer_token == expected_offer_token, orders.c.tried_history_version == 1,
            orders.c.driver_id.is_(None), orders.c.current_offer_driver_id.is_(None),
            orders.c.offer_expires_at.is_(None),
        ).values(
            status=S.offered, offered_at=offered_at, offer_token=token,
            current_offer_driver_id=driver_id, offer_expires_at=offer_expires_at,
            search_round=expected_search_round + 1,
        ))
        if result.rowcount != 1:
            raise OwnershipMismatch("Offer owner changed during publication")
        remember_tried_history(connection, order_id, expected_revision, expected_lifecycle, S.offered, driver_id)
        # Capture the actual writer's expiry, not offer-clock + history TTL.
        record = connection.execute(select(
            orders.c.id, orders.c.current_offer_driver_id, orders.c.search_revision,
            orders.c.tried_lifecycle, orders.c.offer_token, orders.c.offered_at,
            orders.c.offer_expires_at, orders.c.search_round, orders.c.tried_history_expires_at,
        ).where(orders.c.id == order_id)).one()
        return SqlOfferPublication(*record)
