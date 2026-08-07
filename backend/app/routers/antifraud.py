"""Анти-фрод, админ-ручки (B8): баны устройств.

Банит и снимает ТОЛЬКО человек (админ) — автоматика лишь помечает.
Приватность: device_id наружу не публичен — эти ручки admin-only."""
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..db import get_session
from ..logs import admin_action
from ..models import DeviceBan, User, UserRole
from ..security import current_user
from .. import antifraud as af

router = APIRouter(tags=["antifraud"])


def _require_admin(user: User) -> None:
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")


class DeviceBanIn(BaseModel):
    """Бан по device_id ИЛИ по user_id (возьмём последнее устройство юзера) — минимум одно."""
    device_id: Optional[str] = Field(None, max_length=64)
    user_id: Optional[int] = None
    reason: str = Field("", max_length=300)


class DeviceBanOut(BaseModel):
    id: int
    device_id: str
    user_id: Optional[int] = None
    user_name: str = ""
    reason: str = ""
    created_at: str = ""


def _ban_out(session: Session, ban: DeviceBan) -> DeviceBanOut:
    u = session.get(User, ban.user_id) if ban.user_id else None
    return DeviceBanOut(
        id=ban.id, device_id=ban.device_id, user_id=ban.user_id,
        user_name=(u.name if u and u.name else ""),
        reason=ban.reason, created_at=ban.created_at.isoformat(),
    )


@router.post("/admin/bans/device", response_model=DeviceBanOut)
def admin_ban_device(body: DeviceBanIn, user: User = Depends(current_user),
                     session: Session = Depends(get_session)):
    """Забанить устройство: по device_id или по user_id (его последнее устройство).
    Идемпотентно. Забаненное устройство не регистрируется и не входит по СВОЕМУ device_id.
    Честно: X-Device-Id задаёт клиент → техничный обход сменой заголовка возможен; это барьер
    от «нового номера на том же телефоне», не от целевого атакующего (усиление — Play Integrity,
    бэклог). Блокируешь юзера — баним и его устройство этой же ручкой."""
    _require_admin(user)
    device_id = af.normalize_device_id(body.device_id)
    target_user_id = body.user_id
    if not device_id and body.user_id is not None:
        target = session.get(User, body.user_id)
        if not target:
            raise HTTPException(404, "Пользователь не найден")
        device_id = af.normalize_device_id(target.last_device_id)
        if not device_id:
            raise HTTPException(409, "У пользователя нет зафиксированного устройства")
    if not device_id:
        raise HTTPException(400, "Укажи device_id или user_id")
    if target_user_id is None:
        # Найдём владельца устройства (кому последнему принадлежало) — для карточки бана.
        owner = session.exec(select(User).where(User.last_device_id == device_id)).first()
        target_user_id = owner.id if owner else None
    ban = af.ban_device(session, device_id, body.reason, target_user_id)
    # Бан отрезает человека от сервиса — вопрос «кто это сделал» не должен остаться
    # без ответа. Сам device_id не пишем: это идентификатор телефона (§8).
    admin_action(user.id, "device.ban", target_user=target_user_id)
    return _ban_out(session, ban)


@router.delete("/admin/bans/device/{device_id}")
def admin_unban_device(device_id: str, user: User = Depends(current_user),
                       session: Session = Depends(get_session)):
    """Снять бан устройства (разобрались — человек невиновен)."""
    _require_admin(user)
    if not af.unban_device(session, device_id):
        raise HTTPException(404, "Бан не найден")
    admin_action(user.id, "device.unban")   # сам device_id не пишем: это идентификатор телефона
    return {"ok": True}


@router.get("/admin/bans", response_model=List[DeviceBanOut])
def admin_list_bans(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Список банов устройств (новые сверху)."""
    _require_admin(user)
    rows = session.exec(select(DeviceBan).order_by(DeviceBan.id.desc()).limit(500)).all()
    return [_ban_out(session, b) for b in rows]
