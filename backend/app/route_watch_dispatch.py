"""Durable dispatch for subscriptions to rides."""
from collections.abc import Iterator
from dataclasses import dataclass
from datetime import datetime, timedelta
from hashlib import sha256
import json
from typing import Literal, Sequence

from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.dialects.sqlite import insert as sqlite_insert
from sqlmodel import Session, select, update

from .models import Notification, Ride, RideStatus, RouteWatch, User
from .route_watch_models import RouteWatchEvent, RouteWatchNotice
from .timeutil import RIDE_PAST_GRACE_HOURS, local_date, utcnow


NOTICE_INTERVAL = timedelta(hours=24)
RETRY_DELAY = timedelta(minutes=1)
RIDE_WATCH_KINDS = ("rides", "both")


@dataclass(frozen=True)
class GroupKey:
    kind: Literal["recurrence", "series"]
    value: str


def _key(*parts: str | int) -> str:
    payload = json.dumps(parts, ensure_ascii=False, separators=(",", ":"))
    return sha256(payload.encode("utf-8")).hexdigest()


def enqueue_route_watch(
    session: Session,
    ride: Ride,
    *,
    source_key: str,
    operation_scope: str = "ride.create",
    group_key: GroupKey | None = None,
    siblings: Sequence[Ride] = (),
) -> list[int]:
    """Записывает точный набор рейсов в транзакции вызвавшей доменной операции."""
    if siblings and group_key is None:
        raise ValueError("Related rides require a dispatch group")
    session.flush()
    rows = (ride, *siblings)
    if any(row.id is None or row.driver_id != ride.driver_id for row in rows):
        raise ValueError("All route-watch events must refer to rides of the same driver")
    group = _key(ride.driver_id, group_key.kind, group_key.value) if group_key is not None else None
    now = utcnow()
    insert = pg_insert if session.get_bind().dialect.name == "postgresql" else sqlite_insert
    table = RouteWatchEvent.__table__
    statement = insert(table).values([
        {
            "ride_id": row.id,
            "terms_version": row.terms_version,
            "source_key": _key(row.driver_id, operation_scope, source_key, row.id, row.terms_version),
            "group_key": group,
            "created_at": now,
            "next_attempt_at": now,
        }
        for row in rows
    ]).on_conflict_do_nothing().returning(table.c.id)
    return list(session.execute(statement).scalars())


@dataclass(frozen=True)
class EventGroup:
    event_id: int
    group_key: str | None

    def pending(self):
        clause = (RouteWatchEvent.group_key == self.group_key if self.group_key is not None
                  else RouteWatchEvent.id == self.event_id)
        return select(RouteWatchEvent).where(clause, RouteWatchEvent.finished_at.is_(None))


@dataclass(frozen=True)
class DispatchNotice:
    watch_id: int
    ride_ids: tuple[int, ...]


def _rides(session: Session, ride_ids: Sequence[int], now: datetime) -> list[Ride]:
    from .safety_logic import suspended_user_ids

    if not ride_ids:
        return []
    query = select(Ride).where(
        Ride.id.in_(ride_ids), Ride.status == RideStatus.active,
        Ride.depart_at >= now - timedelta(hours=RIDE_PAST_GRACE_HOURS),
    ).order_by(Ride.depart_at, Ride.id)
    paused = suspended_user_ids(session)
    if paused:
        query = query.where(Ride.driver_id.not_in(paused))
    return list(session.exec(query))


def _watches(now: datetime):
    return select(RouteWatch).where(
        RouteWatch.expires_at > now, RouteWatch.watch_kind.in_(RIDE_WATCH_KINDS),
    )


def _matching_rides(session: Session, watch: RouteWatch, rides: Sequence[Ride],
                    entry: dict, blocked: set[int], fits: dict) -> list[tuple[Ride, object]]:
    from .services import route_watch_fit
    from .visibility import may_be_notified

    matched = []
    can_see_trusted = None
    if watch.user_id in blocked:
        return matched
    for ride in rides:
        if watch.user_id == ride.driver_id:
            continue
        if watch.watch_date is not None and local_date(watch.watch_date) != local_date(ride.depart_at):
            continue
        key = (watch.from_city, watch.to_city, watch.direction, ride.id)
        if key not in fits:
            fits[key] = route_watch_fit(session, watch, ride, entry)
        fit = fits[key]
        if not fit.ok:
            continue
        if ride.only_trusted:
            if can_see_trusted is None:
                can_see_trusted = may_be_notified(session, watch.user_id, ride.driver_id, True, blocked)
            if not can_see_trusted:
                continue
        matched.append((ride, fit))
    return matched


def _notice_content(matched) -> tuple[str, str, str, str]:
    from .services import route_watch_body

    ride, fit = matched[0]
    ru, ba = route_watch_body(ride, fit)
    if len(matched) == 1:
        return "Появилась поездка", "Сәфәр барлыҡҡа килде", ru, ba
    count = len(matched)
    return ("Появились поездки", "Сәфәрҙәр барлыҡҡа килде",
            f"{ru} · Сохранённых рейсов: {count}", f"{ba} · Һаҡланған рейстар: {count}")


def _notified_pairs(session: Session, ride_ids: Sequence[int]) -> set[tuple[int, int]]:
    if not ride_ids:
        return set()
    return {tuple(row) for row in session.exec(select(
        RouteWatchNotice.watch_id, RouteWatchNotice.ride_id,
    ).where(RouteWatchNotice.ride_id.in_(ride_ids)))}


def _announce_watch(bind, group: EventGroup, watch_id: int, now: datetime, entry: dict) -> DispatchNotice | None:
    from .services import blocked_user_ids

    with Session(bind) as session:
        if bind.dialect.name == "sqlite":
            # SQLite игнорирует FOR UPDATE. Войти в write transaction до чтения квоты.
            session.execute(update(RouteWatch).where(RouteWatch.id == watch_id).values(
                last_notified_at=RouteWatch.last_notified_at,
            ))
        watch = session.exec(_watches(now).where(RouteWatch.id == watch_id).with_for_update()).first()
        if watch is None or (watch.last_notified_at is not None and watch.last_notified_at + NOTICE_INTERVAL > now):
            return None
        rides = _rides(session, [event.ride_id for event in session.exec(group.pending())], now)
        if not rides:
            return None
        seen = session.exec(select(RouteWatchNotice.ride_id).where(
            RouteWatchNotice.watch_id == watch_id, RouteWatchNotice.ride_id.in_([ride.id for ride in rides]),
        )).all()
        blocked = blocked_user_ids(session, rides[0].driver_id)
        eligible = [ride for ride in rides if ride.id not in seen]
        matched = _matching_rides(session, watch, eligible, entry, blocked, {})
        if not matched:
            return None
        last = watch.last_notified_at
        same_quota = RouteWatch.last_notified_at.is_(None) if last is None else RouteWatch.last_notified_at == last
        changed = session.execute(update(RouteWatch).where(
            RouteWatch.id == watch_id, same_quota,
        ).values(last_notified_at=now).execution_options(synchronize_session=False))
        if changed.rowcount != 1:
            return None
        title_ru, title_ba, body_ru, body_ba = _notice_content(matched)
        first = matched[0][0]
        notification = Notification(
            user_id=watch.user_id, type="route_watch", title_ru=title_ru, title_ba=title_ba,
            body_ru=body_ru, body_ba=body_ba, ref_kind="ride", ref_id=first.id, created_at=now,
        )
        session.add(notification)
        session.flush()
        ride_ids = tuple(ride.id for ride, _ in matched)
        session.add_all([RouteWatchNotice(
            watch_id=watch_id, ride_id=ride_id, notification_id=notification.id, created_at=now,
        ) for ride_id in ride_ids])
        session.commit()
        return DispatchNotice(watch_id, ride_ids)


def _reschedule(bind, group: EventGroup, now: datetime, entry: dict) -> None:
    from .services import blocked_user_ids

    with Session(bind) as session:
        if bind.dialect.name == "sqlite":
            session.execute(update(RouteWatchEvent).where(RouteWatchEvent.id == group.event_id).values(
                next_attempt_at=RouteWatchEvent.next_attempt_at,
            ))
        # Отдельная короткая транзакция повторно читает квоты и Notice после получения замка.
        # Поздний sibling получает собственную due-запись в транзакции публикации.
        events = session.exec(group.pending().order_by(RouteWatchEvent.id).with_for_update()).all()
        rides = _rides(session, [event.ride_id for event in events], now)
        next_attempts = {}
        if rides:
            blocked = blocked_user_ids(session, rides[0].driver_id)
            notified = _notified_pairs(session, [ride.id for ride in rides])
            fits = {}
            for watch in session.exec(_watches(now)):
                outstanding = [ride for ride in rides if (watch.id, ride.id) not in notified]
                matched = _matching_rides(session, watch, outstanding, entry, blocked, fits)
                due = now + RETRY_DELAY
                if watch.last_notified_at is not None:
                    due = max(due, watch.last_notified_at + NOTICE_INTERVAL)
                for ride, _ in matched:
                    next_attempts[ride.id] = min(next_attempts.get(ride.id, due), due)
        for event in events:
            when = next_attempts.get(event.ride_id)
            if when is None:
                event.finished_at = now
            else:
                event.next_attempt_at = when
            session.add(event)
        session.commit()


def dispatch_event(bind, event_id: int, *, now: datetime | None = None) -> list[DispatchNotice]:
    """Обрабатывает только сохранённые рейсы группы; сеть выполняется отдельно."""
    from .geo import settlements_snapshot
    from .services import blocked_user_ids
    from .stop_search import request_snapshot

    now = now or utcnow()
    with Session(bind) as session:
        event = session.get(RouteWatchEvent, event_id)
        if event is None or event.finished_at is not None or event.next_attempt_at > now:
            return []
        group = EventGroup(event.id, event.group_key)
        entry = request_snapshot(settlements_snapshot(session))
        rides = _rides(session, [row.ride_id for row in session.exec(group.pending())], now)
        watch_ids = []
        if rides:
            blocked = blocked_user_ids(session, rides[0].driver_id)
            seen = _notified_pairs(session, [ride.id for ride in rides])
            fits = {}
            for watch in session.exec(_watches(now).order_by(RouteWatch.id)):
                eligible = [ride for ride in rides if (watch.id, ride.id) not in seen]
                if _matching_rides(session, watch, eligible, entry, blocked, fits):
                    watch_ids.append(watch.id)
    notices = [notice for watch_id in watch_ids
               if (notice := _announce_watch(bind, group, watch_id, now, entry)) is not None]
    _reschedule(bind, group, now, entry)
    return notices


def _push_payload(bind, notice: DispatchNotice, entry: dict, now: datetime) -> tuple | None:
    from .services import _is_quiet_hour, blocked_user_ids

    if _is_quiet_hour(now):
        return None
    with Session(bind) as session:
        watch = session.exec(_watches(now).where(RouteWatch.id == notice.watch_id)).first()
        rides = _rides(session, notice.ride_ids, now)
        if watch is None or len(rides) != len(notice.ride_ids):
            return None
        blocked = blocked_user_ids(session, rides[0].driver_id)
        matched = _matching_rides(session, watch, rides, entry, blocked, {})
        if len(matched) != len(notice.ride_ids):
            return None
        title_ru, title_ba, body_ru, body_ba = _notice_content(matched)
        recipient = session.get(User, watch.user_id)
        if recipient is None:
            return None
        title, body = (title_ba, body_ba) if recipient.language == "ba" else (title_ru, body_ru)
        return watch.user_id, title, body, {"type": "ride", "id": str(matched[0][0].id)}


def push_items(bind, notices: Sequence[DispatchNotice], *, now: datetime | None = None) -> Iterator[tuple]:
    """Перечитывает видимость перед каждым получателем и каждым вызовом транспорта."""
    from .geo import settlements_snapshot
    from .services import _is_quiet_hour
    from .stop_search import request_snapshot

    if not notices or _is_quiet_hour(now or utcnow()):
        return
    with Session(bind) as session:
        entry = request_snapshot(settlements_snapshot(session))
    for notice in notices:
        payload = _push_payload(bind, notice, entry, now or utcnow())
        if payload is None:
            continue

        def still_current(notice=notice, payload=payload):
            return _push_payload(bind, notice, entry, now or utcnow()) == payload

        yield (*payload, still_current)
