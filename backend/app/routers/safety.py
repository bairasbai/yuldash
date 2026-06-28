"""Безопасность: SOS (с SMS доверенным контактам), жалобы, блокировки."""
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select
from typing import Optional

from ..db import get_session
from ..models import Block, Report, SosEvent, TrustedContact, User
from ..security import current_user
from ..services import booking_and_ride_for_user, send_text
from ..timeutil import utcnow

router = APIRouter(tags=["safety"])

# Анти-спам SMS: SOS-событие пишем ВСЕГДА (жизнь дороже), но рассылку доверенным контактам
# глушим, если за последний час их уже оповещали > N раз — иначе мэш-кнопка = поток SMS и расходы.
SOS_SMS_PER_HOUR = 6


class SosIn(BaseModel):
    category: str = "other"      # medical / breakdown / other
    booking_id: Optional[int] = None
    note: str = Field("", max_length=2000)


@router.post("/sos", response_model=SosEvent)
def sos(body: SosIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    if body.booking_id is not None:
        booking_and_ride_for_user(session, body.booking_id, user)
    # Сколько SOS уже было за последний час (ДО записи нового) — для кепа SMS.
    recent = session.exec(
        select(SosEvent).where(
            SosEvent.user_id == user.id, SosEvent.created_at >= utcnow() - timedelta(hours=1)
        )
    ).all()
    event = SosEvent(user_id=user.id, **body.model_dump())
    session.add(event)
    session.commit()
    session.refresh(event)
    # Реально уведомляем доверенные контакты по SMS — но только пока не превышен почасовой кеп.
    notified = 0
    if len(recent) < SOS_SMS_PER_HOUR:
        contacts = session.exec(select(TrustedContact).where(TrustedContact.user_id == user.id)).all()
        who = user.name or user.phone
        for c in contacts:
            if c.phone:
                send_text(c.phone, f"SOS! {who} просит срочной помощи (Юлдаш). Свяжитесь скорее.")
                notified += 1
    else:
        print(f"[SOS] user={user.id} SMS подавлены (кеп {SOS_SMS_PER_HOUR}/час), событие записано")
    print(f"[SOS] user={user.id} category={body.category} contacts_notified={notified}")
    return event


class ReportIn(BaseModel):
    target_user_id: int
    reason: str = ""


@router.post("/reports", response_model=Report)
def create_report(body: ReportIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    if body.target_user_id == user.id:
        raise HTTPException(400, "Нельзя пожаловаться на себя")
    if not session.get(User, body.target_user_id):
        raise HTTPException(404, "Пользователь не найден")
    report = Report(reporter_id=user.id, **body.model_dump())
    session.add(report)
    session.commit()
    session.refresh(report)
    return report


class BlockIn(BaseModel):
    blocked_user_id: int


@router.post("/blocks", response_model=Block)
def create_block(body: BlockIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    if body.blocked_user_id == user.id:
        raise HTTPException(400, "Нельзя заблокировать себя")
    if not session.get(User, body.blocked_user_id):
        raise HTTPException(404, "Пользователь не найден")
    existing = session.exec(
        select(Block).where(Block.user_id == user.id, Block.blocked_user_id == body.blocked_user_id)
    ).first()
    if existing:
        return existing
    block = Block(user_id=user.id, **body.model_dump())
    session.add(block)
    session.commit()
    session.refresh(block)
    return block
