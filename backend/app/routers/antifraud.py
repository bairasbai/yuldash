"""Анти-фрод, админ-ручки (B8): баны устройств.

Банит и снимает ТОЛЬКО человек (админ) — автоматика лишь помечает.
Приватность: device_id наружу не публичен — эти ручки admin-only."""
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..db import get_session
from ..errors import herr
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
            raise herr(404, "Пользователь не найден", "Ҡулланыусы табылманы")
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
        raise herr(404, "Бан не найден", "Бан табылманы")
    admin_action(user.id, "device.unban")   # сам device_id не пишем: это идентификатор телефона
    return {"ok": True}


@router.get("/admin/bans", response_model=List[DeviceBanOut])
def admin_list_bans(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Список банов устройств (новые сверху)."""
    _require_admin(user)
    rows = session.exec(select(DeviceBan).order_by(DeviceBan.id.desc()).limit(500)).all()
    return [_ban_out(session, b) for b in rows]


# ------------------------------ помеченные тексты (модерация) ------------------------------
# Пометки ставились и раньше, но ложились только в счётчик Redis: в пульсе админ видел ЧИСЛО
# помеченных за сегодня и не мог посмотреть, КТО и ЗА ЧТО. Помечать и не показывать —
# работа впустую. Здесь журнал: кто · какая метка · в каком поле · id записи · когда.
#
# Приватность: сам текст НЕ отдаём и в журнале его нет (§8) — только ссылка на запись.
# Админ открывает объект в своём экране и видит текст в контексте.

# Человеческие названия мест — чтобы админ не гадал, что такое "incident_reply".
_PLACE_LABELS = {
    "ride_comment": "Комментарий к поездке",
    "pickup": "Где встречаемся",
    "request": "Заявка пассажира",
    "response": "Отклик водителя",
    "review": "Отзыв",
    "order_comment": "Комментарий к заказу такси",
    "parcel": "Описание посылки",
    "name": "Имя профиля",
    "incident": "Описание спора",
    "incident_reply": "Объяснение по спору",
}


class TextFlagOut(BaseModel):
    id: int
    user_id: int
    user_name: str
    user_phone: str          # админу нужен контакт, чтобы связаться; ручка admin-only
    kind: str                # warn (фишинг) / contact (телефон, увод) / abuse (мат)
    place: str               # машинный код места
    place_label: str         # то же по-человечески
    ref_id: Optional[int]    # id записи — по нему админ открывает сам объект
    created_at: str
    user_flags_total: int    # сколько всего пометок у этого человека (разовое ≠ система)


@router.get("/admin/text-flags", response_model=List[TextFlagOut])
def admin_text_flags(
    kind: Optional[str] = None,
    limit: int = 200,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """Помеченные тексты, новые сверху. `kind` фильтрует по виду метки.

    `user_flags_total` считаем одним запросом на всю выдачу (а не в цикле): у человека с
    сотней пометок иначе была бы сотня запросов, и экран админа тормозил бы ровно на тех,
    ради кого он и открыт.
    """
    _require_admin(user)
    from sqlalchemy import func
    from ..models import TextFlag

    q = select(TextFlag).order_by(TextFlag.id.desc()).limit(max(1, min(500, limit)))
    if kind:
        q = q.where(TextFlag.kind == kind)
    rows = session.exec(q).all()
    if not rows:
        return []
    uids = {r.user_id for r in rows}
    users = {u.id: u for u in session.exec(select(User).where(User.id.in_(uids))).all()}
    totals = dict(session.exec(
        select(TextFlag.user_id, func.count(TextFlag.id)).where(TextFlag.user_id.in_(uids))
        .group_by(TextFlag.user_id)
    ).all())
    out: List[TextFlagOut] = []
    for r in rows:
        u = users.get(r.user_id)
        out.append(TextFlagOut(
            id=r.id, user_id=r.user_id,
            user_name=(u.name if u and u.name else "Пользователь"),
            user_phone=(u.phone if u else ""),
            kind=r.kind, place=r.place,
            place_label=_PLACE_LABELS.get(r.place, r.place),
            ref_id=r.ref_id,
            created_at=r.created_at.isoformat() if r.created_at else "",
            user_flags_total=int(totals.get(r.user_id, 0)),
        ))
    return out
