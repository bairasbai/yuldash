"""Доверие «между своими»: уровень L0..L3, инвайт-коды в круг доверия, реестр согласий (152-ФЗ).

Приватность (152-ФЗ): свой уровень, свои инвайты и свои согласия видит ТОЛЬКО их владелец.
Ни один эндпоинт не отдаёт чужой уровень/коды/согласия (защита от IDOR)."""
from typing import List

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import update
from sqlmodel import Session, select

from ..db import get_session
from ..errors import herr
from ..models import InviteCode, Trust, User
from ..security import current_user, gen_referral_code
from ..timeutil import utcnow
from ..trust_service import (
    CONSENT_KINDS, INSIDER_LEVEL, INVITE_CODE_USES, MAX_INVITES_PER_USER,
    MIN_INVITER_LEVEL, list_consents, record_consent, trust_level, trust_summary,
)

router = APIRouter(tags=["trust"])


# ----------------------------- Уровень доверия -----------------------------

@router.get("/me/trust")
def my_trust(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мой уровень доверия + что даёт следующий. Только про СЕБЯ (чужой уровень не отдаём)."""
    return trust_summary(session, user)


# ----------------------------- Инвайт-коды -----------------------------

class InviteOut(BaseModel):
    code: str
    uses_left: int
    created_at: str


def _invite_out(inv: InviteCode) -> InviteOut:
    return InviteOut(code=inv.code, uses_left=inv.uses_left, created_at=inv.created_at.isoformat())


@router.post("/invites", response_model=InviteOut)
def create_invite(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Создать пригласительный код в круг «своих». Может только проверенный участник (L2+).
    Запас кодов на пользователя ограничен (анти-абьюз)."""
    if trust_level(session, user) < MIN_INVITER_LEVEL:
        raise herr(403, "Приглашать в круг своих может только проверенный участник", "Ышаныс түңәрәгенә тик тикшерелгән ҡатнашыусы саҡыра ала")
    mine = session.exec(select(InviteCode).where(InviteCode.owner_id == user.id)).all()
    if len(mine) >= MAX_INVITES_PER_USER:
        raise herr(400, "Закончились приглашения", "Саҡырыуҙар бөттө")
    # Уникальный код (не путается с реферальным — своя таблица, свой namespace).
    code = ""
    for _ in range(10):
        cand = gen_referral_code()
        if not session.exec(select(InviteCode).where(InviteCode.code == cand)).first():
            code = cand
            break
    if not code:
        raise herr(500, "Не удалось создать код, попробуй ещё раз", "Код булдырып булманы, тағы ҡабатла")
    inv = InviteCode(code=code, owner_id=user.id, uses_left=INVITE_CODE_USES)
    session.add(inv)
    session.commit()
    session.refresh(inv)
    return _invite_out(inv)


@router.get("/invites/mine", response_model=List[InviteOut])
def my_invites(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мои пригласительные коды. Приватность: отдаём ТОЛЬКО коды владельца."""
    invs = session.exec(
        select(InviteCode).where(InviteCode.owner_id == user.id).order_by(InviteCode.id.desc())
    ).all()
    return [_invite_out(i) for i in invs]


class RedeemIn(BaseModel):
    code: str = Field(..., max_length=12)


@router.post("/invites/redeem")
def redeem_invite(body: RedeemIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Активировать пригласительный код → стать «своим» (L3), записать, кто пригласил.
    Анти-абьюз: нельзя редимить свой код, код не бесконечен, повторно «своим» не станешь."""
    code = (body.code or "").strip().upper()
    if not code:
        raise herr(400, "Нужен код", "Код кәрәк")
    # Блокируем строку кода: два параллельных redeem одного кода не спишут use дважды (TOCTOU).
    inv = session.exec(
        select(InviteCode).where(InviteCode.code == code).with_for_update()
    ).first()
    if not inv:
        raise herr(404, "Код не найден", "Код табылманы")
    if inv.owner_id == user.id:
        raise herr(400, "Нельзя активировать собственный код", "Үҙ кодыңды активлаштырып булмай")
    if inv.uses_left <= 0:
        raise herr(400, "Код уже использован", "Код ҡулланылған инде")
    # V11: пригласивший мог быть разжалован (verified снят админом) ПОСЛЕ выпуска кода. Тогда код
    # больше не вводит в круг своих — иначе бывший проверенный продолжает плодить L3 в обход модерации.
    owner = session.get(User, inv.owner_id)
    if owner is None or trust_level(session, owner) < MIN_INVITER_LEVEL:
        raise herr(400, "Код больше не действителен", "Код инде ғәмәлдә түгел")
    row = session.exec(select(Trust).where(Trust.user_id == user.id)).first()
    if row and row.level >= INSIDER_LEVEL:
        raise herr(400, "Ты уже в кругу своих", "Һин инде үҙебеҙҙекеләр араһында")
    # Использование СПИСЫВАЕМ атомарно: условие «осталось больше нуля» живёт внутри UPDATE.
    #
    # Блокировка строки выше (`with_for_update`) закрывает гонку на PostgreSQL — но SQLite её
    # игнорирует, а на SQLite работают тесты, локальная разработка и демо-база. Там `uses_left -= 1`
    # оставалось обычной парой «прочитал — записал»: двое активируют последнее использование
    # одновременно, оба проходят проверку, счётчик уходит в минус и в круг «своих» попадает
    # лишний человек. Круг своих — это доступ к поездкам «только для проверенных»,
    # то есть обещание безопасности, а не украшение.
    # Важнее другое: саму защиту не проверял ни один тест — на SQLite блокировка пустая,
    # и её удаление при рефакторинге прошло бы незамеченным.
    claimed = session.execute(
        update(InviteCode)
        .where(InviteCode.code == inv.code, InviteCode.uses_left > 0)
        .values(uses_left=InviteCode.uses_left - 1)
    )
    if claimed.rowcount == 0:
        session.rollback()
        raise herr(400, "Код уже использован", "Код ҡулланылған инде")
    if row:
        row.level = INSIDER_LEVEL
        row.invited_by = inv.owner_id
        row.updated_at = utcnow()
    else:
        row = Trust(user_id=user.id, level=INSIDER_LEVEL, invited_by=inv.owner_id)
    session.add(row)
    session.commit()
    return {"ok": True, "level": INSIDER_LEVEL, "invited_by": inv.owner_id}


# ----------------------------- Реестр согласий (152-ФЗ) -----------------------------

class ConsentIn(BaseModel):
    kind: str = Field(..., max_length=32)   # offer / privacy / geo


class ConsentOut(BaseModel):
    kind: str
    granted_at: str


@router.get("/me/consents", response_model=List[ConsentOut])
def my_consents(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мои зафиксированные согласия (оферта/политика/геолокация). Только про себя."""
    return [ConsentOut(kind=c.kind, granted_at=c.granted_at.isoformat()) for c in list_consents(session, user.id)]


@router.post("/me/consents", response_model=ConsentOut)
def grant_consent(body: ConsentIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Зафиксировать согласие с таймстампом (152-ФЗ). Идемпотентно: время первого согласия не меняем."""
    kind = (body.kind or "").strip().lower()
    if kind not in CONSENT_KINDS:
        raise herr(400, "Неизвестный вид согласия", "Билдәһеҙ ризалыҡ төрө")
    c = record_consent(session, user.id, kind)
    return ConsentOut(kind=c.kind, granted_at=c.granted_at.isoformat())
