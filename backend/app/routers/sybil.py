"""Админ-поверхность детекта Sybil/сговора: подозрительные пары на РУЧНОЙ разбор.
Без авто-наказаний — админ смотрит сигналы и, если это сговор, снимает накрученное готовыми
ручками (Rating.excluded / инцидент). Ложное срабатывание не должно бить по честным «между своими»."""
from fastapi import APIRouter, Depends, HTTPException
from sqlmodel import Session

from ..collusion import find_suspects
from ..db import get_session
from ..models import User, UserRole
from ..security import current_user

router = APIRouter(tags=["admin-sybil"])


@router.get("/admin/sybil/suspects")
def sybil_suspects(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Пары с признаками накрутки доверия сговором (взаимный реферал / взаимные 5★ /
    много броней между собой), отсортированы по числу сигналов. Только для администратора
    и только как сигнал — авто-действий нет."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для администратора")
    suspects = find_suspects(session)
    return {"count": len(suspects), "suspects": suspects}
