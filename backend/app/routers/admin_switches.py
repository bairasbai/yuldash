"""Operator access to the login settings and their change history."""

from datetime import timezone

from fastapi import APIRouter, Depends
from fastapi.security import HTTPAuthorizationCredentials
from pydantic import BaseModel, Field, StrictBool
from sqlalchemy import func, or_
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..errors import herr
from ..logs import admin_action
from ..models import AppSetting, AppSettingChange, User, UserRole
from ..owner_switches import LOGIN_KEYS, login_settings, login_state, require_complete_login_settings
from ..security import bearer, lock_authenticated_user, normalize_phone, require_telegram_admin
from ..timeutil import utcnow


router = APIRouter(tags=["admin-switches"])


class SwitchChangeIn(BaseModel):
    value: StrictBool
    confirm: StrictBool = False
    note: str | None = Field(default=None, max_length=300)


def _switch_view(session: Session, row: AppSetting) -> dict:
    state = login_state(row.key, row)
    author_name = session.exec(select(User.name).where(User.id == row.updated_by)).first() if row.updated_by else None
    changes = session.exec(
        select(AppSettingChange, User.name).outerjoin(User, User.id == AppSettingChange.admin_id)
        .where(AppSettingChange.key == row.key)
        .order_by(AppSettingChange.created_at.desc(), AppSettingChange.id.desc()).limit(50)
    ).all()
    return {
        "stored": state.stored,
        "effective": state.effective,
        "blockers": list(state.blockers),
        "updated_at": row.updated_at.replace(tzinfo=timezone.utc) if row.updated_at else None,
        "updated_by_name": author_name,
        "history": [
            {
                "old_value": change.old_value, "new_value": change.new_value,
                "admin_name": name, "created_at": change.created_at.replace(tzinfo=timezone.utc),
                "note": change.note,
            }
            for change, name in changes
        ],
    }


def _accounts_losing_login(session: Session, telegram_effective: bool) -> int:
    # Administrator accounts already require Telegram. Only real SMS-login
    # accounts without an alternative can lose a method.
    query = select(func.count()).select_from(User).where(
        User.role != UserRole.admin, User.phone != "",
        ~User.phone.startswith("tg"), ~User.phone.startswith("released:"),
    )
    if telegram_effective:
        query = query.where(or_(User.telegram_id.is_(None), User.telegram_id == ""))
    if settings.review_phone and settings.review_code:
        query = query.where(User.phone != normalize_phone(settings.review_phone))
    return session.exec(query).one()


@router.get("/admin/switches")
def get_switches(admin: User = Depends(require_telegram_admin), session: Session = Depends(get_session)):
    rows = login_settings(session)
    require_complete_login_settings(rows)
    return {"switches": {key: _switch_view(session, rows[key]) for key in LOGIN_KEYS}}


@router.post("/admin/switches/{key}")
def set_switch(key: str, body: SwitchChangeIn, admin: User = Depends(require_telegram_admin),
               cred: HTTPAuthorizationCredentials = Depends(bearer), session: Session = Depends(get_session)):
    if key not in LOGIN_KEYS:
        raise herr(404, "Настройка не найдена.", "Көйләү табылманы.", code="switch_not_found")
    actor, _ = lock_authenticated_user(cred, session)
    require_telegram_admin(actor)
    rows = login_settings(session, lock="update")
    require_complete_login_settings(rows)
    row = rows[key]
    state = login_state(key, row)
    if key == "login.telegram" and not body.value:
        raise herr(409, "Telegram нужен для входа администратора.",
                   "Администратор инеү өсөн Telegram кәрәк.", code="switch_telegram_required")
    if body.value and state.blockers:
        raise herr(409, "Этот способ входа ещё не готов.", "Был инеү ысулы әле әҙер түгел.",
                   code="switch_blocked", blockers=list(state.blockers))
    if body.value == row.value:
        return _switch_view(session, row)
    if not body.value and state.effective:
        other_enabled = any(login_state(other, rows[other]).effective for other in LOGIN_KEYS if other != key)
        if not other_enabled:
            raise herr(409, "Оставь хотя бы один способ входа.", "Кәм тигәндә бер инеү ысулын ҡалдыр.",
                       code="switch_last_login_method")
        if key == "login.sms" and not body.confirm:
            affected = _accounts_losing_login(session, login_state("login.telegram", rows["login.telegram"]).effective)
            if affected:
                raise herr(409, "У части пользователей пропадёт доступ. Подтверди отключение SMS.",
                           "Ҡайһы бер ҡулланыусылар инә алмаясаҡ. SMS-ты һүндереүҙе раҫла.",
                           code="switch_needs_confirm", affected=affected)
    old_value = row.value
    when = utcnow()
    row.value, row.updated_by, row.updated_at = body.value, actor.id, when
    session.add(AppSettingChange(
        key=key, old_value=old_value, new_value=body.value, admin_id=actor.id,
        created_at=when, note=body.note.strip() or None if body.note else None,
    ))
    session.flush()
    result = _switch_view(session, row)
    actor_id = actor.id
    session.commit()
    admin_action(actor_id, "switch.update", target=key, previous=old_value, value=body.value)
    return result
