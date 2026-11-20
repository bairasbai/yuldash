"""Подписка на маршрут «карауль поездку» (F13) — retention-двигатель.

Пользователь подписывается на маршрут (Сибай→Уфа) и получает push + запись в ленте:
- watch_kind="rides" (дефолт, F13) — когда ВОДИТЕЛЬ публикует подходящую поездку
  (матчинг в rides.create_ride → services.notify_route_watchers);
- watch_kind="requests" (G3) — когда ПАССАЖИР создаёт заявку по этому направлению
  (матчинг в requests.create_request → services.notify_request_watchers). Это водительская
  сторона попуток: «есть пассажир на твоём маршруте», бесплатно (ценность, не платная услуга);
- "both" — и то, и то. Анти-спам 1/сутки на подписку, авто-протухание 14 дней.
"""
from datetime import datetime, timedelta
from typing import List, Optional

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy import update
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..errors import herr
from ..models import DeviceToken, RouteWatch, User, WebPushSubscription
from ..security import current_user
from ..stop_search import stop_searchable
from ..timeutil import client_dt_to_utc, local_date, utcnow

router = APIRouter(tags=["route-watch"])

WATCH_TTL_DAYS = 14              # подписка живёт 14 дней, затем протухает (не матчится, прячется)
MAX_WATCHES_PER_USER = 20       # разумный потолок на пользователя (анти-мусор)


class RouteWatchIn(BaseModel):
    from_city: str = Field(..., max_length=120)
    to_city: str = Field(..., max_length=120)
    watch_date: Optional[datetime] = None                      # опц.: интересует конкретный день
    direction: str = Field("forward", pattern="^(forward|both)$")   # forward | both (туда-обратно)
    # G3 — что караулим: rides (жду поездки — дефолт) / requests (я водитель, жду заявки по
    # направлению) / both. Дефолт rides сохраняет прежнее поведение старого клиента.
    watch_kind: str = Field("rides", pattern="^(rides|requests|both)$")


class RegisteredPushChannels(BaseModel):
    app: bool
    web: bool


class RouteWatchOut(BaseModel):
    id: int
    from_city: str
    to_city: str
    watch_date: Optional[datetime] = None
    direction: str
    watch_kind: str
    created_at: datetime
    expires_at: datetime
    last_notified_at: Optional[datetime]
    registered_push_channels: RegisteredPushChannels
    quiet_from: int
    quiet_to: int


def _registered_channels(session: Session, user_id: int) -> RegisteredPushChannels:
    app = session.exec(select(DeviceToken.id).where(DeviceToken.user_id == user_id).limit(1)).first()
    web = session.exec(
        select(WebPushSubscription.id).where(WebPushSubscription.user_id == user_id).limit(1)
    ).first()
    return RegisteredPushChannels(app=app is not None, web=web is not None)


def _watch_output(watch: RouteWatch, channels: RegisteredPushChannels) -> RouteWatchOut:
    return RouteWatchOut(
        **watch.model_dump(exclude={"user_id"}),
        registered_push_channels=channels,
        quiet_from=settings.quiet_hours_from,
        quiet_to=settings.quiet_hours_to,
    )


def _lock_subscriptions(session: Session, user_id: int) -> None:
    if session.get_bind().dialect.name == "sqlite":
        # SQLite ignores FOR UPDATE. A write statement acquires its writer lock before the list read.
        session.execute(update(RouteWatch).where(RouteWatch.id == -1).values(direction=RouteWatch.direction))
    else:
        session.exec(select(User.id).where(User.id == user_id).with_for_update()).one()


@router.post("/route-watch", response_model=RouteWatchOut)
def create_route_watch(
    body: RouteWatchIn,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """Создать подписку или вернуть активную подписку на те же места, направление, вид и день."""
    frm, to = body.from_city.strip(), body.to_city.strip()
    if any(not stop_searchable(place) or sum(ch.isalpha() for ch in place) < 2 for place in (frm, to)):
        raise herr(400, "Название места — буквами, без цифр", "Урын исеме хәрефтәр менән, һандарһыҙ",
                   code="watch_place_invalid")
    user_id = user.id
    date_utc = client_dt_to_utc(body.watch_date)
    day = local_date(date_utc)
    _lock_subscriptions(session, user_id)
    now = utcnow()
    expires = now + timedelta(days=WATCH_TTL_DAYS)

    existing = session.exec(
        select(RouteWatch).where(RouteWatch.user_id == user_id, RouteWatch.expires_at > now)
        .order_by(RouteWatch.id)
    ).all()
    for w in existing:
        if (w.from_city.strip().casefold() == frm.casefold()
                and w.to_city.strip().casefold() == to.casefold()
                and w.direction == body.direction
                and w.watch_kind == body.watch_kind
                and local_date(w.watch_date) == day):
            output = _watch_output(w, _registered_channels(session, user_id))
            session.rollback()
            return output

    if len(existing) >= MAX_WATCHES_PER_USER:
        raise herr(400, "Слишком много подписок — удали ненужные", "Яҙылыуҙар артыҡ күп — кәрәкмәгәнен бетер")

    watch = RouteWatch(
        user_id=user_id,
        from_city=frm,
        to_city=to,
        watch_date=date_utc,
        direction=body.direction,
        watch_kind=body.watch_kind,
        created_at=now,
        expires_at=expires,
    )
    session.add(watch)
    session.flush()
    output = _watch_output(watch, _registered_channels(session, user_id))
    session.commit()
    return output


@router.get("/route-watch", response_model=List[RouteWatchOut])
def my_route_watches(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мои активные подписки (непротухшие), свежие — первыми."""
    now = utcnow()
    watches = session.exec(
        select(RouteWatch)
        .where(RouteWatch.user_id == user.id, RouteWatch.expires_at > now)
        .order_by(RouteWatch.created_at.desc())
    ).all()
    if not watches:
        return []
    channels = _registered_channels(session, user.id)
    return [_watch_output(watch, channels) for watch in watches]


@router.delete("/route-watch/{watch_id}")
def delete_route_watch(
    watch_id: int,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """Удалить свою подписку. Чужую — 404 (не раскрываем существование)."""
    watch = session.get(RouteWatch, watch_id)
    if not watch or watch.user_id != user.id:
        raise herr(404, "Подписка не найдена", "Яҙылыу табылманы")
    session.delete(watch)
    session.commit()
    return {"ok": True}
