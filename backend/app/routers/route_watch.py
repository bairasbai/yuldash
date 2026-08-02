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

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..db import get_session
from ..models import RouteWatch, User
from ..security import current_user
from ..timeutil import utcnow

router = APIRouter(tags=["route-watch"])

WATCH_TTL_DAYS = 14              # подписка живёт 14 дней, затем протухает (не матчится, прячется)
MAX_WATCHES_PER_USER = 20       # разумный потолок на пользователя (анти-мусор)


class RouteWatchIn(BaseModel):
    from_city: str = Field(..., min_length=1, max_length=120)
    to_city: str = Field(..., min_length=1, max_length=120)
    watch_date: Optional[datetime] = None                      # опц.: интересует конкретный день
    direction: str = Field("forward", pattern="^(forward|both)$")   # forward | both (туда-обратно)
    # G3 — что караулим: rides (жду поездки — дефолт) / requests (я водитель, жду заявки по
    # направлению) / both. Дефолт rides сохраняет прежнее поведение старого клиента.
    watch_kind: str = Field("rides", pattern="^(rides|requests|both)$")


class RouteWatchOut(BaseModel):
    id: int
    from_city: str
    to_city: str
    watch_date: Optional[datetime] = None
    direction: str
    watch_kind: str
    created_at: datetime
    expires_at: datetime


@router.post("/route-watch", response_model=RouteWatchOut)
def create_route_watch(
    body: RouteWatchIn,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """Создать подписку на маршрут. Дубль (тот же маршрут+направление, ещё живой) — обновляем,
    а не плодим строки: продлеваем срок и сбрасываем анти-спам, чтобы юзер снова получал пуши."""
    frm, to = body.from_city.strip(), body.to_city.strip()
    if not frm or not to:
        raise HTTPException(400, "Укажи города маршрута")
    now = utcnow()
    expires = now + timedelta(days=WATCH_TTL_DAYS)

    # Дубль: тот же (нормализованный) маршрут + направление + живой → продлеваем существующий.
    existing = session.exec(
        select(RouteWatch).where(RouteWatch.user_id == user.id, RouteWatch.expires_at > now)
    ).all()
    for w in existing:
        if (w.from_city.strip().casefold() == frm.casefold()
                and w.to_city.strip().casefold() == to.casefold()
                and w.direction == body.direction
                and w.watch_kind == body.watch_kind):   # G3: rides/requests — разные интенты, не сливаем
            w.watch_date = body.watch_date
            w.expires_at = expires
            w.last_notified_at = None
            session.add(w)
            session.commit()
            session.refresh(w)
            return w

    if len(existing) >= MAX_WATCHES_PER_USER:
        raise HTTPException(400, "Слишком много подписок — удали ненужные")

    watch = RouteWatch(
        user_id=user.id,
        from_city=frm,
        to_city=to,
        watch_date=body.watch_date,
        direction=body.direction,
        watch_kind=body.watch_kind,
        created_at=now,
        expires_at=expires,
    )
    session.add(watch)
    session.commit()
    session.refresh(watch)
    return watch


@router.get("/route-watch", response_model=List[RouteWatchOut])
def my_route_watches(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мои активные подписки (непротухшие), свежие — первыми."""
    now = utcnow()
    watches = session.exec(
        select(RouteWatch)
        .where(RouteWatch.user_id == user.id, RouteWatch.expires_at > now)
        .order_by(RouteWatch.created_at.desc())
    ).all()
    return watches


@router.delete("/route-watch/{watch_id}")
def delete_route_watch(
    watch_id: int,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """Удалить свою подписку. Чужую — 404 (не раскрываем существование)."""
    watch = session.get(RouteWatch, watch_id)
    if not watch or watch.user_id != user.id:
        raise HTTPException(404, "Подписка не найдена")
    session.delete(watch)
    session.commit()
    return {"ok": True}
