"""Uncalled single-candidate SQL history reader, not a matcher or reservation.

The observation is true only at its captured time under the owner lock. It does
not establish authorization, eligibility, driver availability or future validity.
"""
import re
from dataclasses import dataclass
from datetime import datetime, timezone

from sqlalchemy import exists, func, select
from sqlalchemy.engine import Connection

from .models import InstantOrder, InstantOrderStatus, InstantTriedExclusion
from .taxi_tried_history_write import OwnershipMismatch


@dataclass(frozen=True, slots=True)
class TriedCandidateObservation:
    id: int
    revision: int
    lifecycle: str
    driver_id: int
    expiry: datetime
    observed_at: datetime
    excluded: bool


def inspect_tried_candidate(
    connection: Connection,
    order_id: int,
    expected_revision: int,
    expected_lifecycle: str,
    driver_id: int,
) -> TriedCandidateObservation:
    """Inspect one PK membership using a savepoint in the caller's transaction.

    Success retains the owner lock until the caller finishes its transaction.
    Failure rolls back only this savepoint and propagates, including serialization
    errors. Preexisting caller locks are unaffected. Caller must roll back its
    whole business unit on failure; False is never an error fallback.
    """
    if not isinstance(connection, Connection):
        raise TypeError("An active PostgreSQL Connection is required")
    if any(type(value) is not int or not 1 <= value <= 2**31 - 1 for value in (order_id, driver_id)):
        raise ValueError("order_id and driver_id must be positive INTEGER values")
    if type(expected_revision) is not int or not 0 <= expected_revision <= 2**63 - 1:
        raise ValueError("expected_revision must be a nonnegative BIGINT value")
    if not isinstance(expected_lifecycle, str) or re.fullmatch(r"[0-9a-f]{32}", expected_lifecycle) is None:
        raise ValueError("expected_lifecycle must be 32 lowercase hexadecimal characters")
    if connection.dialect.name != "postgresql" or connection.dialect.driver != "psycopg2":
        raise ValueError("History reader lab supports PostgreSQL psycopg2 only")
    transaction = connection.get_transaction()
    if connection.closed or connection.invalidated or transaction is None or not transaction.is_active:
        raise ValueError("An active caller transaction is required")
    if getattr(connection.connection.driver_connection, "autocommit", None) is not False:
        raise ValueError("Actual DBAPI autocommit must be disabled")

    orders = InstantOrder.__table__
    history = InstantTriedExclusion.__table__
    with connection.begin_nested():
        owner = connection.execute(select(
            orders.c.status, orders.c.search_revision, orders.c.tried_lifecycle,
            orders.c.tried_history_version, orders.c.tried_history_expires_at,
        ).where(orders.c.id == order_id).with_for_update()).first()
        if owner is None:
            raise OwnershipMismatch("History owner is missing")
        status, revision, lifecycle, version, expiry = owner
        if (status != InstantOrderStatus.searching or revision != expected_revision
                or lifecycle != expected_lifecycle or version != 1
                or not isinstance(expiry, datetime) or expiry.tzinfo is not None):
            raise OwnershipMismatch("History owner does not match the expected searching cycle")

        clock = connection.execute(select(func.clock_timestamp())).scalar_one()
        if not isinstance(clock, datetime) or clock.tzinfo is None:
            raise ValueError("PostgreSQL clock_timestamp must return an aware timestamp")
        observed_at = clock.astimezone(timezone.utc).replace(tzinfo=None)
        excluded = False
        if expiry > observed_at:
            excluded = bool(connection.execute(select(exists(select(history.c.driver_id).where(
                history.c.order_id == order_id, history.c.lifecycle == lifecycle,
                history.c.driver_id == driver_id,
            )))).scalar_one())
        return TriedCandidateObservation(order_id, revision, lifecycle, driver_id,
                                         expiry, observed_at, excluded)
