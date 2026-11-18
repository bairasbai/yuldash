"""Uncalled SQL history cleanup lab: bounded deletion count, not bounded latency.

Activation still requires every history writer to lock its owner, never reuse a
lifecycle, and reset all expired members before renewal. No scheduler or caller
is registered here; this helper does not activate SQL history or retention.
"""
import re
from datetime import datetime, timezone

from sqlalchemy import delete, func, or_, select, tuple_
from sqlalchemy.engine import Engine
from sqlalchemy.pool import SingletonThreadPool, StaticPool

from .models import InstantOrder, InstantTriedExclusion


def collect_tried_history_batch(engine: Engine, order_id: int, batch_limit: int = 500) -> int:
    """Delete at most one eligible batch in an independently owned PG transaction.

    An error rolls back this transaction and propagates. No automatic retry is
    attempted: a lost commit acknowledgement cannot provide an exactly-once count.
    """
    if not isinstance(engine, Engine):
        raise TypeError("A PostgreSQL Engine, not a caller Connection, is required")
    if isinstance(batch_limit, bool) or not isinstance(batch_limit, int) or not 1 <= batch_limit <= 500:
        raise ValueError("batch_limit must be an integer from 1 to 500")
    if engine.dialect.name != "postgresql":
        raise ValueError("History cleanup lab requires PostgreSQL")
    if isinstance(engine.pool, (StaticPool, SingletonThreadPool)):
        raise ValueError("Shared-connection pools cannot isolate the cleanup transaction")

    orders = InstantOrder.__table__
    history = InstantTriedExclusion.__table__
    with engine.begin() as connection:
        owner = connection.execute(select(
            orders.c.id, orders.c.tried_history_version,
            orders.c.tried_lifecycle, orders.c.tried_history_expires_at,
        ).where(orders.c.id == order_id).with_for_update()).first()
        if owner is None:
            return 0
        _, version, lifecycle, expiry = owner
        if (version != 1 or not isinstance(lifecycle, str)
                or re.fullmatch(r"[0-9a-f]{32}", lifecycle) is None
                or not isinstance(expiry, datetime) or expiry.tzinfo is not None):
            return 0

        # Wall clock AFTER acquiring the lock, not the transaction start timestamp.
        clock = connection.execute(select(func.clock_timestamp())).scalar_one()
        if not isinstance(clock, datetime) or clock.tzinfo is None:
            raise ValueError("PostgreSQL clock_timestamp must return an aware timestamp")
        cutoff = clock.astimezone(timezone.utc).replace(tzinfo=None)
        eligible = or_(history.c.lifecycle != lifecycle, expiry <= cutoff)
        keys = (history.c.order_id, history.c.lifecycle, history.c.driver_id)
        batch = (select(*keys)
                 .where(history.c.order_id == order_id, eligible)
                 .order_by(history.c.lifecycle, history.c.driver_id)
                 .limit(batch_limit))
        result = connection.execute(delete(history).where(
            history.c.order_id == order_id, eligible, tuple_(*keys).in_(batch),
        ))
        if result.rowcount is None or result.rowcount < 0:
            raise RuntimeError("PostgreSQL did not report a deletion count")
        return result.rowcount
