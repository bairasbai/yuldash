"""Реферал «позови своего»: свой код, кто пригласил, бонусы (1 бонус = 1 бесплатное поднятие).
Виральность в тесной общине: пригласил соседа → оба получают бонус."""
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..db import get_session
from ..models import User
from ..security import current_user, gen_referral_code

router = APIRouter(tags=["referral"])

# Потолок реферальных бонусов на пользователя (анти-накрутка: два аккаунта взаимно редимят
# через новые регистрации → иначе бесконечные бесплатные поднятия). Хватает на реальную виральность.
MAX_REFERRAL_CREDITS = 20


def _ensure_code(session: Session, user: User) -> str:
    """Лениво генерим уникальный код, если ещё нет (старые юзеры — без бэкфилл-миграции)."""
    if user.referral_code:
        return user.referral_code
    for _ in range(10):
        code = gen_referral_code()
        if not session.exec(select(User).where(User.referral_code == code)).first():
            user.referral_code = code
            session.add(user)
            session.commit()
            session.refresh(user)
            return code
    return user.referral_code


@router.get("/referral/me")
def referral_me(user: User = Depends(current_user), session: Session = Depends(get_session)):
    code = _ensure_code(session, user)
    invited = len(session.exec(select(User.id).where(User.referred_by == user.id)).all())
    return {
        "code": code,
        "invited": invited,                       # сколько привёл (бейдж «Позвал N»)
        "credits": user.referral_credits,         # бонусов = бесплатных поднятий
        "redeemed": user.referred_by is not None,  # уже ввёл чей-то код
    }


class RedeemIn(BaseModel):
    code: str = Field(..., max_length=12)


@router.post("/referral/redeem")
def referral_redeem(body: RedeemIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    if user.referred_by is not None:
        raise HTTPException(400, "Код уже введён")
    code = body.code.strip().upper()
    if not code:
        raise HTTPException(400, "Нужен код")
    referrer = session.exec(select(User).where(User.referral_code == code)).first()
    if not referrer or referrer.id == user.id:
        raise HTTPException(400, "Код не найден")
    # Награда обоим: по 1 бонусу (бесплатное поднятие поездки), но с потолком на пользователя
    # (анти-накрутка взаимными редимами через новые аккаунты).
    user.referred_by = referrer.id
    user.referral_credits = min(user.referral_credits + 1, MAX_REFERRAL_CREDITS)
    referrer.referral_credits = min(referrer.referral_credits + 1, MAX_REFERRAL_CREDITS)
    session.add(user)
    session.add(referrer)
    session.commit()
    return {"ok": True, "credits": user.referral_credits}
