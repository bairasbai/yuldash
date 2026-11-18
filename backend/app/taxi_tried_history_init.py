"""Uncalled first SQL-search lab for a newly created, pristine order.

A new order is a caller precondition, not something absence of history can prove.
UUID4 supplies practical uniqueness, not strict lifecycle never-reuse. This helper
is not a replacement for production admission, eligibility or matching rules.
"""
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from uuid import uuid4

from sqlalchemy import exists, func, select, update
from sqlalchemy.engine import Connection

from .models import InstantOrder, InstantOrderStatus, InstantTriedExclusion
from .taxi_tried_history_write import OwnershipMismatch


@dataclass(frozen=True, slots=True)
class FirstSqlSearch:
    id: int
    revision: int
    lifecycle: str
    expiry: datetime
    searching_at: datetime


def begin_first_sql_search(
    connection: Connection, order_id: int, expected_revision: int | None,
) -> FirstSqlSearch:
    """Apply pristine created→searching inside the caller's existing transaction.

    Releases only this helper's savepoint. Applied does not mean committed.
    Any failure requires caller rollback of the entire business unit.
    """
    if not isinstance(connection, Connection):
        raise TypeError("An active PostgreSQL Connection is required")
    if type(order_id) is not int or not 1 <= order_id <= 2**31 - 1:
        raise ValueError("order_id must be a positive INTEGER value")
    if expected_revision is not None and (
        type(expected_revision) is not int or not 0 <= expected_revision <= 2**63 - 1
    ):
        raise ValueError("expected_revision must be None or a nonnegative BIGINT value")
    if connection.dialect.name != "postgresql" or connection.dialect.driver != "psycopg2":
        raise ValueError("History initialization lab supports PostgreSQL psycopg2 only")
    transaction = connection.get_transaction()
    if connection.closed or connection.invalidated or transaction is None or not transaction.is_active:
        raise ValueError("An active caller transaction is required")
    if getattr(connection.connection.driver_connection, "autocommit", None) is not False:
        raise ValueError("Actual DBAPI autocommit must be disabled")

    orders = InstantOrder.__table__
    history = InstantTriedExclusion.__table__
    with connection.begin_nested():
        owner = connection.execute(select(
            orders.c.status, orders.c.search_revision, orders.c.tried_history_version,
            orders.c.tried_lifecycle, orders.c.tried_history_expires_at,
            orders.c.driver_id, orders.c.current_offer_driver_id,
            orders.c.offer_token, orders.c.offer_expires_at, orders.c.search_round,
        ).where(orders.c.id == order_id).with_for_update()).first()
        if owner is None:
            raise OwnershipMismatch("First-search owner is missing")
        status, revision, version, lifecycle, expiry, driver, recipient, token, deadline, round_number = owner
        if (status != InstantOrderStatus.created or revision != expected_revision
                or version is not None or lifecycle is not None or expiry is not None
                or any(value is not None for value in (driver, recipient, token, deadline))
                or round_number != 0
                or (revision is not None and not 0 <= revision < 2**63 - 1)):
            raise OwnershipMismatch("First-search owner is not pristine or revision is exhausted")
        has_history = exists(select(history.c.order_id).where(history.c.order_id == order_id))
        if connection.execute(select(has_history)).scalar_one():
            raise OwnershipMismatch("First-search owner already has history")

        lifecycle = uuid4().hex
        clock = connection.execute(select(func.clock_timestamp())).scalar_one()
        if not isinstance(clock, datetime) or clock.tzinfo is None:
            raise ValueError("PostgreSQL clock_timestamp must return an aware timestamp")
        now = clock.astimezone(timezone.utc).replace(tzinfo=None)
        expiry = now + timedelta(seconds=3600)
        next_revision = 1 if revision is None else revision + 1
        result = connection.execute(update(orders).where(
            orders.c.id == order_id, orders.c.status == InstantOrderStatus.created,
            orders.c.search_revision == expected_revision,
            orders.c.tried_history_version.is_(None), orders.c.tried_lifecycle.is_(None),
            orders.c.tried_history_expires_at.is_(None), orders.c.driver_id.is_(None),
            orders.c.current_offer_driver_id.is_(None), orders.c.offer_token.is_(None),
            orders.c.offer_expires_at.is_(None), orders.c.search_round == 0, ~has_history,
        ).values(
            status=InstantOrderStatus.searching, search_revision=next_revision,
            searching_at=now, search_round=0, tried_history_version=1,
            tried_lifecycle=lifecycle, tried_history_expires_at=expiry,
        ))
        if result.rowcount != 1:
            raise OwnershipMismatch("First-search owner changed during initialization")
        return FirstSqlSearch(order_id, next_revision, lifecycle, expiry, now)
