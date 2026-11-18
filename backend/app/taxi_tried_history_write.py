"""Uncalled SQL history write lab sharing an existing caller business transaction.

This primitive neither initializes SQL mode nor authorizes an offer. Every caller
must roll back its whole business unit on any failure, including OwnershipMismatch.
A local savepoint protects this primitive's changes, not catch-and-commit misuse.
"""
import re
from datetime import datetime, timedelta, timezone

from sqlalchemy import delete, func, select, update
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.engine import Connection

from .models import InstantOrder, InstantOrderStatus, InstantTriedExclusion


class OwnershipMismatch(RuntimeError):
    """The locked owner does not match the caller's initialized SQL history cycle."""


def remember_tried_history(
    connection: Connection,
    order_id: int,
    expected_revision: int,
    expected_lifecycle: str,
    expected_status: InstantOrderStatus | str,
    driver_id: int,
) -> None:
    """Reset expired current members, add one driver, renew the shared expiry.

    Success releases only our savepoint; it does not commit the caller transaction.
    Errors roll back our savepoint and propagate. Caller must then roll back the
    entire business transaction; no retry or publication guarantee is provided.
    """
    if not isinstance(connection, Connection):
        raise TypeError("An active PostgreSQL Connection is required")
    # IDs use PostgreSQL INTEGER; the revision column is BIGINT.
    if any(type(value) is not int or not 1 <= value <= 2**31 - 1 for value in (order_id, driver_id)):
        raise ValueError("order_id and driver_id must be positive INTEGER values")
    if type(expected_revision) is not int or not 0 <= expected_revision <= 2**63 - 1:
        raise ValueError("expected_revision must be a nonnegative BIGINT value")
    if not isinstance(expected_lifecycle, str) or re.fullmatch(r"[0-9a-f]{32}", expected_lifecycle) is None:
        raise ValueError("expected_lifecycle must be 32 lowercase hexadecimal characters")
    if not isinstance(expected_status, str) or expected_status not in (InstantOrderStatus.searching, InstantOrderStatus.offered):
        raise ValueError("expected_status must be searching or offered")
    if connection.dialect.name != "postgresql" or connection.dialect.driver != "psycopg2":
        raise ValueError("History write lab supports PostgreSQL psycopg2 only")
    transaction = connection.get_transaction()
    if connection.closed or connection.invalidated or transaction is None or not transaction.is_active:
        raise ValueError("An active caller transaction is required")
    raw = connection.connection.driver_connection
    if getattr(raw, "autocommit", None) is not False:
        raise ValueError("Actual DBAPI autocommit must be disabled")

    orders = InstantOrder.__table__
    history = InstantTriedExclusion.__table__
    with connection.begin_nested():
        owner = connection.execute(select(
            orders.c.status, orders.c.search_revision, orders.c.tried_lifecycle,
            orders.c.tried_history_version, orders.c.tried_history_expires_at,
            orders.c.current_offer_driver_id,
        ).where(orders.c.id == order_id).with_for_update()).first()
        if owner is None:
            raise OwnershipMismatch("History owner is missing")
        status, revision, lifecycle, version, expiry, recipient = owner
        if (status != expected_status or revision != expected_revision
                or lifecycle != expected_lifecycle or version != 1
                or not isinstance(expiry, datetime) or expiry.tzinfo is not None
                or (status == InstantOrderStatus.offered and recipient != driver_id)):
            raise OwnershipMismatch("History owner does not match the expected cycle")

        clock = connection.execute(select(func.clock_timestamp())).scalar_one()
        if not isinstance(clock, datetime) or clock.tzinfo is None:
            raise ValueError("PostgreSQL clock_timestamp must return an aware timestamp")
        now = clock.astimezone(timezone.utc).replace(tzinfo=None)
        if expiry <= now:
            # Full current-cycle reset, including leftovers after a partial GC batch.
            connection.execute(delete(history).where(
                history.c.order_id == order_id, history.c.lifecycle == expected_lifecycle,
            ))
        connection.execute(insert(history).values(
            order_id=order_id, lifecycle=expected_lifecycle, driver_id=driver_id,
        ).on_conflict_do_nothing(index_elements=[
            history.c.order_id, history.c.lifecycle, history.c.driver_id,
        ]))
        renewed = max(expiry, now + timedelta(seconds=3600))
        result = connection.execute(update(orders).where(orders.c.id == order_id)
                                    .values(tried_history_expires_at=renewed))
        if result.rowcount != 1:
            raise OwnershipMismatch("History owner disappeared during renewal")
