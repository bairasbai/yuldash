"""Process committed route-watch events independently of the publication request."""
from datetime import datetime

from sqlalchemy import func
from sqlalchemy.exc import SQLAlchemyError
from sqlmodel import Session, select

from .db import engine
from .logs import log
from .route_watch_dispatch import dispatch_event, push_items
from .route_watch_models import RouteWatchEvent
from .services import send_push
from .timeutil import utcnow


BATCH_SIZE = 25


def run_due(bind=engine, *, now: datetime | None = None) -> int:
    push_time = now
    now = now or utcnow()
    group = func.coalesce(RouteWatchEvent.group_key, RouteWatchEvent.source_key)
    with Session(bind) as session:
        ids = list(session.exec(select(func.min(RouteWatchEvent.id)).where(
            RouteWatchEvent.finished_at.is_(None), RouteWatchEvent.next_attempt_at <= now,
        ).group_by(group).order_by(
            func.min(RouteWatchEvent.next_attempt_at), func.min(RouteWatchEvent.id),
        ).limit(BATCH_SIZE)))
    sent = 0
    for event_id in ids:
        try:
            notices = dispatch_event(bind, event_id, now=now)
            for user_id, title, body, data in push_items(bind, notices, now=push_time):
                # A oneshot process must wait for its own network calls to finish.
                with Session(bind) as session:
                    send_push(session, user_id, title, body, data=data)
            sent += len(notices)
        except SQLAlchemyError as exc:
            log.warning("[ROUTE_WATCH] dispatch deferred: %s", type(exc).__name__)
    return sent


if __name__ == "__main__":
    log.info("[ROUTE_WATCH] notices inserted: %d", run_due())
