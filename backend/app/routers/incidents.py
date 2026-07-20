"""Система «Справедливость»: инциденты (споры/жалобы), standing, доверие, политика.

Обе стороны слышимы (due process), мир по умолчанию, соразмерная лестница,
прозрачное объяснение, возврат рейтинга. См. docs/trust-safety.md §6.

Приватность: телефон второй стороны участникам НЕ отдаём — только админу в
/admin/incidents. Тяжёлые типы (SEVERE) сразу уведомляют админа в Telegram.
"""
from datetime import datetime
from typing import List, Optional

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..models import Booking, Incident, Ride, User, UserRole
from ..safety_logic import (
    INCIDENT_TYPES, SEVERE_TYPES, active_incidents_count, apply_incident_resolution,
    clamp, completed_trips_for, csv_from_urls, ensure_active, incidents_last_hour, is_suspended,
    refresh_standing, reliability_for, urls_from_csv,
)
from ..security import current_user
from ..services import booking_and_ride_for_user, notify_admin_telegram, send_push, user_rating
from ..timeutil import utcnow

router = APIRouter(tags=["incidents"])


# ----------------------------- Схемы -----------------------------
class IncidentIn(BaseModel):
    respondent_id: int
    type: str
    description: str = Field("", max_length=2000)
    booking_id: Optional[int] = None
    evidence_urls: Optional[List[str]] = None


class RespondIn(BaseModel):
    statement: str = Field("", max_length=2000)
    evidence_urls: Optional[List[str]] = None


class AppealIn(BaseModel):
    text: str = Field("", max_length=2000)


class ResolveIn(BaseModel):
    resolution: str = Field("", max_length=40)   # dismissed/warning/strike/compensation/rating_adjust/suspend/ban/mutual_resolved
    fault: str = Field("", max_length=20)         # none/reporter/respondent/both/unclear
    note: str = Field("", max_length=2000)
    compensation_kop: int = 0
    strike: bool = False
    suspend_days: Optional[int] = None
    exclude_rating: bool = False
    shield: bool = False


class IncidentOut(BaseModel):
    id: int
    booking_id: Optional[int]
    type: str
    severe: bool
    suspected_bump: bool
    status: str
    reporter_role: str
    description: str
    evidence_urls: List[str]
    respondent_statement: str
    respondent_evidence_urls: List[str]
    responded_at: Optional[datetime]
    resolution: str
    fault: str
    resolution_note: str
    compensation_kop: int
    appeal_text: str
    appeal_status: str
    created_at: datetime
    updated_at: datetime
    resolved_at: Optional[datetime]
    my_role: str                 # reporter / respondent / admin
    other_name: str              # имя второй стороны (без телефона — приватность)
    booking_route: Optional[str] = None


class AdminIncidentOut(BaseModel):
    id: int
    booking_id: Optional[int]
    type: str
    severe: bool
    suspected_bump: bool
    status: str
    reporter_role: str
    reporter_id: int
    reporter_name: str
    reporter_phone: str
    respondent_id: int
    respondent_name: str
    respondent_phone: str
    description: str
    evidence_urls: List[str]
    respondent_statement: str
    respondent_evidence_urls: List[str]
    responded_at: Optional[datetime]
    resolution: str
    fault: str
    resolution_note: str
    compensation_kop: int
    appeal_text: str
    appeal_status: str
    created_at: datetime
    updated_at: datetime
    resolved_at: Optional[datetime]
    booking_route: Optional[str] = None


# ----------------------------- Хелперы -----------------------------
def _route_for(session: Session, booking_id: Optional[int]) -> Optional[str]:
    if not booking_id:
        return None
    b = session.get(Booking, booking_id)
    if not b:
        return None
    r = session.get(Ride, b.ride_id)
    return f"{r.from_city}→{r.to_city}" if r else None


def _name(u: Optional[User]) -> str:
    return (u.name if u and u.name else "Пользователь")


def _incident_out(session: Session, inc: Incident, viewer: User) -> IncidentOut:
    if viewer.id == inc.reporter_id:
        my_role, other_id = "reporter", inc.respondent_id
    elif viewer.id == inc.respondent_id:
        my_role, other_id = "respondent", inc.reporter_id
    else:
        my_role, other_id = "admin", inc.respondent_id
    other = session.get(User, other_id)
    return IncidentOut(
        id=inc.id, booking_id=inc.booking_id, type=inc.type, severe=inc.type in SEVERE_TYPES,
        suspected_bump=inc.suspected_bump,
        status=inc.status, reporter_role=inc.reporter_role,
        description=inc.description, evidence_urls=urls_from_csv(inc.evidence_urls),
        respondent_statement=inc.respondent_statement,
        respondent_evidence_urls=urls_from_csv(inc.respondent_evidence_urls),
        responded_at=inc.responded_at,
        resolution=inc.resolution, fault=inc.fault, resolution_note=inc.resolution_note,
        compensation_kop=inc.compensation_kop, appeal_text=inc.appeal_text,
        appeal_status=inc.appeal_status,
        created_at=inc.created_at, updated_at=inc.updated_at, resolved_at=inc.resolved_at,
        my_role=my_role, other_name=_name(other), booking_route=_route_for(session, inc.booking_id),
    )


def _require_admin(user: User) -> None:
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")


def create_incident(
    session: Session,
    *,
    reporter: User,
    respondent_id: int,
    type: str,
    description: str = "",
    booking_id: Optional[int] = None,
    reporter_role: str = "",
    evidence_urls: Optional[List[str]] = None,
    background: Optional[BackgroundTasks] = None,
    rate_limit: bool = True,
    suspected_bump: bool = False,
) -> Incident:
    """Создать инцидент со всеми проверками/побочками. Общая точка для /incidents, no-show и авто-детекта бампинга."""
    if respondent_id == reporter.id:
        raise HTTPException(400, "Нельзя пожаловаться на себя")
    if type not in INCIDENT_TYPES:
        raise HTTPException(400, "Неизвестный тип инцидента")
    if not session.get(User, respondent_id):
        raise HTTPException(404, "Пользователь не найден")
    if rate_limit and incidents_last_hour(session, reporter.id) >= settings.safety_incidents_per_hour:
        raise HTTPException(429, "Слишком много обращений за час. Попробуй позже.")
    # Участие в брони + роль + счётность обвинённого именно по этой поездке.
    if booking_id is not None:
        booking, ride = booking_and_ride_for_user(session, booking_id, reporter)  # 403/404 если не участник
        if not reporter_role:
            reporter_role = "driver" if ride.driver_id == reporter.id else "passenger"
        if respondent_id not in (ride.driver_id, booking.passenger_id):
            raise HTTPException(400, "Обвинённый не участвует в этой поездке")

    severe = type in SEVERE_TYPES
    inc = Incident(
        booking_id=booking_id, reporter_id=reporter.id, respondent_id=respondent_id,
        type=type, reporter_role=reporter_role, description=clamp(description, 2000),
        evidence_urls=csv_from_urls(evidence_urls), suspected_bump=suspected_bump,
        # severe/бампинг → сразу на разбор человеком; иначе ждём объяснения обвинённого.
        status="under_review" if (severe or suspected_bump) else "awaiting_response",
    )
    session.add(inc)
    session.commit()
    session.refresh(inc)

    # Пуш обвинённому: приглашение объясниться (право на защиту, §1 принцип 1).
    send_push(session, respondent_id, "Открыт разбор по поездке",
              "По одной из поездок открыт спор. Опишите свою версию — это важно.")
    # Тяжёлые типы — немедленно уведомить админа (телефоны в Telegram допустимы: канал админа).
    if severe:
        reporter_u = session.get(User, reporter.id)
        respondent_u = session.get(User, respondent_id)
        msg = (
            f"🛡️ SEVERE-инцидент (Юлдаш)\n"
            f"Тип: {type}\n"
            f"Заявитель: {_name(reporter_u)} ({reporter_u.phone if reporter_u else '—'})\n"
            f"Обвинён: {_name(respondent_u)} ({respondent_u.phone if respondent_u else '—'})\n"
            f"Поездка: {_route_for(session, booking_id) or '—'}\n"
            f"Суть: {clamp(description, 300) or '—'}"
        )
        if background is not None:
            background.add_task(notify_admin_telegram, msg)
        else:
            notify_admin_telegram(msg)
    return inc


# ----------------------------- Инциденты: участники -----------------------------
@router.post("/incidents", response_model=IncidentOut)
def file_incident(body: IncidentIn, background: BackgroundTasks,
                  user: User = Depends(current_user), session: Session = Depends(get_session)):
    ensure_active(session, user.id)   # приостановленный аккаунт не подаёт новые жалобы (анти-абуз)
    inc = create_incident(
        session, reporter=user, respondent_id=body.respondent_id, type=body.type,
        description=body.description, booking_id=body.booking_id,
        evidence_urls=body.evidence_urls, background=background,
    )
    return _incident_out(session, inc, user)


@router.get("/incidents/mine", response_model=List[IncidentOut])
def my_incidents(user: User = Depends(current_user), session: Session = Depends(get_session)):
    rows = session.exec(
        select(Incident)
        .where((Incident.reporter_id == user.id) | (Incident.respondent_id == user.id))
        .order_by(Incident.id.desc())
    ).all()
    return [_incident_out(session, inc, user) for inc in rows]


@router.get("/incidents/{incident_id}", response_model=IncidentOut)
def get_incident(incident_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    inc = session.get(Incident, incident_id)
    if not inc:
        raise HTTPException(404, "Спор не найден")
    if user.id not in (inc.reporter_id, inc.respondent_id) and user.role != UserRole.admin:
        raise HTTPException(403, "Нет доступа к этому спору")
    return _incident_out(session, inc, user)


@router.post("/incidents/{incident_id}/respond", response_model=IncidentOut)
def respond_incident(incident_id: int, body: RespondIn,
                     user: User = Depends(current_user), session: Session = Depends(get_session)):
    inc = session.get(Incident, incident_id)
    if not inc:
        raise HTTPException(404, "Спор не найден")
    if user.id != inc.respondent_id:
        raise HTTPException(403, "Объясниться может только вторая сторона")
    if inc.status in ("resolved", "closed"):
        raise HTTPException(409, "Спор уже закрыт")
    inc.respondent_statement = clamp(body.statement, 2000)
    inc.respondent_evidence_urls = csv_from_urls(body.evidence_urls)
    inc.responded_at = utcnow()
    inc.status = "under_review"
    session.add(inc)
    session.commit()
    session.refresh(inc)
    # Заявителю — что вторая сторона ответила (прозрачность).
    send_push(session, inc.reporter_id, "Ответ по спору", "Вторая сторона описала свою версию.")
    return _incident_out(session, inc, user)


@router.post("/incidents/{incident_id}/appeal", response_model=IncidentOut)
def appeal_incident(incident_id: int, body: AppealIn,
                    user: User = Depends(current_user), session: Session = Depends(get_session)):
    inc = session.get(Incident, incident_id)
    if not inc:
        raise HTTPException(404, "Спор не найден")
    if user.id not in (inc.reporter_id, inc.respondent_id):
        raise HTTPException(403, "Обжаловать может только участник спора")
    inc.appeal_text = clamp(body.text, 2000)
    inc.appeal_status = "requested"
    inc.status = "appealed"
    inc.updated_at = utcnow()
    session.add(inc)
    session.commit()
    session.refresh(inc)
    notify_admin_telegram(f"⚖️ Апелляция по спору #{inc.id} (Юлдаш). Тип: {inc.type}. Нужен разбор человеком.")
    return _incident_out(session, inc, user)


@router.post("/incidents/{incident_id}/withdraw", response_model=IncidentOut)
def withdraw_incident(incident_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """«Мы решили миром» — заявитель закрывает спор без последствий (§4)."""
    inc = session.get(Incident, incident_id)
    if not inc:
        raise HTTPException(404, "Спор не найден")
    if user.id != inc.reporter_id:
        raise HTTPException(403, "Закрыть спор миром может только заявитель")
    if inc.status == "closed":
        return _incident_out(session, inc, user)
    inc.resolution = "mutual_resolved"
    inc.fault = "none"
    inc.status = "closed"
    inc.resolved_at = utcnow()
    inc.updated_at = inc.resolved_at
    session.add(inc)
    session.commit()
    session.refresh(inc)
    for uid in (inc.reporter_id, inc.respondent_id):
        send_push(session, uid, "Спор закрыт миром", "Спасибо, что договорились по-соседски 🤝")
    return _incident_out(session, inc, user)


# ----------------------------- Инциденты: админ -----------------------------
@router.get("/admin/incidents", response_model=List[AdminIncidentOut])
def admin_incidents(status: Optional[str] = None, user: User = Depends(current_user), session: Session = Depends(get_session)):
    _require_admin(user)
    q = select(Incident).order_by(Incident.id.desc())
    if status:
        q = q.where(Incident.status == status)
    rows = session.exec(q.limit(300)).all()
    ids: set = set()
    for r in rows:
        ids.add(r.reporter_id)
        ids.add(r.respondent_id)
    users = {u.id: u for u in session.exec(select(User).where(User.id.in_(ids))).all()} if ids else {}
    out: List[AdminIncidentOut] = []
    for inc in rows:
        rep = users.get(inc.reporter_id)
        resp = users.get(inc.respondent_id)
        out.append(AdminIncidentOut(
            id=inc.id, booking_id=inc.booking_id, type=inc.type, severe=inc.type in SEVERE_TYPES,
            suspected_bump=inc.suspected_bump,
            status=inc.status, reporter_role=inc.reporter_role,
            reporter_id=inc.reporter_id, reporter_name=_name(rep), reporter_phone=(rep.phone if rep else ""),
            respondent_id=inc.respondent_id, respondent_name=_name(resp), respondent_phone=(resp.phone if resp else ""),
            description=inc.description, evidence_urls=urls_from_csv(inc.evidence_urls),
            respondent_statement=inc.respondent_statement,
            respondent_evidence_urls=urls_from_csv(inc.respondent_evidence_urls),
            responded_at=inc.responded_at, resolution=inc.resolution, fault=inc.fault,
            resolution_note=inc.resolution_note, compensation_kop=inc.compensation_kop,
            appeal_text=inc.appeal_text, appeal_status=inc.appeal_status,
            created_at=inc.created_at, updated_at=inc.updated_at, resolved_at=inc.resolved_at,
            booking_route=_route_for(session, inc.booking_id),
        ))
    return out


@router.post("/admin/incidents/{incident_id}/resolve", response_model=IncidentOut)
def resolve_incident(incident_id: int, body: ResolveIn,
                     user: User = Depends(current_user), session: Session = Depends(get_session)):
    _require_admin(user)
    inc = session.get(Incident, incident_id)
    if not inc:
        raise HTTPException(404, "Спор не найден")
    # Идемпотентность: уже решённый/закрытый спор повторно не «дорешать» (двойной тап админа /
    # ретрай сети иначе добавлял страйк второй раз). Апелляция переводит статус обратно в under_review.
    if inc.status in ("resolved", "closed"):
        raise HTTPException(409, "Спор уже решён")
    inc, _prof = apply_incident_resolution(
        session, inc, resolution=body.resolution, fault=body.fault, note=body.note,
        compensation_kop=body.compensation_kop, strike=body.strike, suspend_days=body.suspend_days,
        exclude_rating=body.exclude_rating, shield=body.shield, resolver_id=user.id,
    )
    # Прозрачность: обе стороны получают решение с человеческим объяснением (§6 принцип 6).
    note = inc.resolution_note or "Решение принято."
    for uid in (inc.reporter_id, inc.respondent_id):
        send_push(session, uid, "Решение по спору", note)
    return _incident_out(session, inc, user)


# ----------------------------- Standing / доверие / политика -----------------------------
class StandingOut(BaseModel):
    standing: str
    strikes: int
    warnings: int
    reliability: int
    suspended_until: Optional[datetime]
    suspend_reason: str
    rating_shield: bool
    active_incidents: int
    can_act: bool


@router.get("/me/standing", response_model=StandingOut)
def my_standing(user: User = Depends(current_user), session: Session = Depends(get_session)):
    prof = refresh_standing(session, user.id)
    return StandingOut(
        standing=prof.standing, strikes=prof.strikes, warnings=prof.warnings,
        reliability=reliability_for(session, user.id),
        suspended_until=prof.suspended_until, suspend_reason=prof.suspend_reason,
        rating_shield=prof.rating_shield,
        active_incidents=active_incidents_count(session, user.id),
        can_act=not is_suspended(prof),
    )


class TrustOut(BaseModel):
    rating: float
    rating_count: int
    trips: int
    verified: bool
    reliability: int
    member_since: Optional[datetime]


@router.get("/users/{user_id}/trust", response_model=TrustOut)
def user_trust(user_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Публичный снимок доверия (для карточки водителя/пассажира). Без телефона/приватного."""
    target = session.get(User, user_id)
    if not target:
        raise HTTPException(404, "Пользователь не найден")
    avg, cnt = user_rating(session, user_id)
    return TrustOut(
        rating=round(avg, 1) if cnt > 0 else 0.0, rating_count=cnt,
        trips=completed_trips_for(session, user_id), verified=bool(target.verified),
        reliability=reliability_for(session, user_id), member_since=target.created_at,
    )


@router.get("/safety/policy")
def safety_policy():
    """Пороги системы «Справедливость» — клиент показывает их из сервера, не хардкодит."""
    return {
        "free_cancel_min": settings.safety_free_cancel_min,
        "late_cancel_before_depart_min": settings.safety_late_cancel_before_depart_min,
        "wait_timer_min": settings.safety_wait_timer_min,
        "strikes_to_limit": settings.safety_strikes_to_limit,
        "strikes_to_suspend": settings.safety_strikes_to_suspend,
        "suspend_1_days": settings.safety_suspend_1_days,
        "suspend_2_days": settings.safety_suspend_2_days,
        "suspend_3_days": settings.safety_suspend_3_days,
        "strike_decay_days": settings.safety_strike_decay_days,
        "min_rating": settings.safety_min_rating,
        "reliability_window": settings.safety_reliability_window,
    }

