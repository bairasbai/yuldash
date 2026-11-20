"""Админ-поверхность детекта Sybil/сговора: подозрительные пары на РУЧНОЙ разбор.
Без авто-наказаний — админ смотрит сигналы и, если это сговор, снимает накрученное готовыми
ручками (Rating.excluded / инцидент). Ложное срабатывание не должно бить по честным «между своими»."""
from fastapi import APIRouter, Depends
from sqlmodel import Session

from ..collusion import SCAN_CAP, find_suspects
from ..db import get_session
from ..models import User
from ..security import require_telegram_admin

router = APIRouter(tags=["admin-sybil"])


@router.get("/admin/sybil/suspects")
def sybil_suspects(user: User = Depends(require_telegram_admin), session: Session = Depends(get_session)):
    """Пары с признаками накрутки доверия сговором (взаимный реферал / взаимные 5★ /
    много броней между собой), отсортированы по числу сигналов. Только для администратора
    и только как сигнал — авто-действий нет."""
    suspects = find_suspects(session)
    # scan_cap — сколько строк максимум сканируем на сигнал: усечение на большой БД не «тихое».
    return {"count": len(suspects), "suspects": suspects, "scan_cap": SCAN_CAP}
