"""Центр уведомлений: типизированная лента событий пользователя (бронь/поездка/система/сообщение).

Данные пишутся в таблицу `Notification` в тех же местах, где шлётся push
(`services.push_notification`). Здесь — только чтение своей ленты и пометка прочитанным.
Приватность: юзер видит и меняет ТОЛЬКО свои уведомления (`user_id == self`).
"""
from typing import List, Optional

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy import func
from sqlmodel import Session, select

from ..db import get_session
from ..models import Notification, User
from ..security import current_user
from ..timeutil import utcnow

router = APIRouter(tags=["notifications"])


class NotificationOut(BaseModel):
    id: int
    type: str                    # booking / ride / system / message
    title_ru: str
    title_ba: str
    body_ru: str
    body_ba: str
    ref_kind: str = ""           # booking / request / "" — как трактовать ref_id (deep-link)
    ref_id: Optional[int] = None
    read: bool
    created_at: str


class NotificationsOut(BaseModel):
    unread: int                  # бейдж на иконке
    items: List[NotificationOut]


@router.get("/notifications", response_model=NotificationsOut)
def notifications(
    limit: int = 50,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """Лента уведомлений текущего юзера: непрочитанные сверху, затем новые.

    Возвращает `unread` (число для бейджа) + `items`. Только СВОИ уведомления.
    """
    limit = max(1, min(limit, 200))
    rows = session.exec(
        select(Notification)
        .where(Notification.user_id == user.id)
        # непрочитанные (read_at IS NULL) сверху, внутри группы — новые первыми
        .order_by(Notification.read_at.is_(None).desc(), Notification.id.desc())
        .limit(limit)
    ).all()
    # Счётчик по ВСЕЙ таблице (не по обрезанной limit-странице — иначе занизит при >limit непрочитанных).
    unread = session.exec(
        select(func.count()).select_from(Notification).where(
            Notification.user_id == user.id, Notification.read_at.is_(None))
    ).one()
    items = [
        NotificationOut(
            id=r.id,
            type=r.type,
            title_ru=r.title_ru,
            title_ba=r.title_ba,
            body_ru=r.body_ru,
            body_ba=r.body_ba,
            ref_kind=r.ref_kind,
            ref_id=r.ref_id,
            read=r.read_at is not None,
            created_at=r.created_at.isoformat() if r.created_at else "",
        )
        for r in rows
    ]
    return NotificationsOut(unread=unread, items=items)


class ReadIn(BaseModel):
    id: Optional[int] = None     # пометить одно уведомление
    all: bool = False            # пометить все прочитанными


@router.post("/notifications/read")
def mark_read(
    body: ReadIn,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """Пометить уведомление(я) прочитанным. Тело: {"id": N} — одно, {"all": true} — все.

    Меняет ТОЛЬКО свои строки (фильтр по user_id) → чужие уведомления недоступны.
    Идемпотентно: уже прочитанные не трогаем. Возвращает актуальный `unread` для бейджа.
    """
    now = utcnow()
    q = select(Notification).where(
        Notification.user_id == user.id,
        Notification.read_at.is_(None),
    )
    if not body.all:
        q = q.where(Notification.id == body.id)
    for n in session.exec(q).all():
        n.read_at = now
        session.add(n)
    session.commit()
    unread = session.exec(
        select(func.count()).select_from(Notification).where(
            Notification.user_id == user.id, Notification.read_at.is_(None))
    ).one()
    return {"ok": True, "unread": unread}
