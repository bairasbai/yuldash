"""Ядро системы «Справедливость» (Trust, Safety & Fairness) — доменная логика инцидентов.

Дополняет анонимные жалобы (Report) двусторонним разбором: обе стороны слышимы, соразмерная
лестница наказаний (§2), затухание страйков (§4), щит рейтинга (снятие оценки-мести из среднего).
Роутеры остаются тонкими. Reliability/бампинг (нужны хуки в поездки) — отдельная фаза.
"""
from datetime import timedelta
from typing import Optional

from fastapi import HTTPException
from sqlalchemy import or_
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from .config import settings
from .models import Booking, BookingStatus, DriverProfile, Incident, Rating, Ride, SafetyProfile
from .services import user_rating
from .timeutil import utcnow

# Коды типов инцидентов. Клиент локализует по коду.
INCIDENT_TYPES: set[str] = {
    # A. Попутки и такси
    "passenger_no_show", "driver_no_show", "non_payment", "rude", "unsafe",
    "harassment", "route_detour", "overcharge", "rules_violation",
    # B. Курьер / посылки
    "parcel_damage", "parcel_lost", "parcel_delay", "recipient_absent", "wrong_contents",
}

# Тяжёлые типы — мгновенный разбор человеком (уведомляем админа сразу, без ожидания объяснения).
SEVERE_TYPES: set[str] = {"harassment", "unsafe", "non_payment", "parcel_lost", "wrong_contents"}

# Статусы инцидента, считающиеся «активным спором».
ACTIVE_INCIDENT_STATUSES = ("open", "awaiting_response", "under_review", "appealed")


def clamp(text: Optional[str], limit: int) -> str:
    return (text or "").strip()[:limit]


# ----------------------------- SafetyProfile -----------------------------
def get_or_create_safety_profile(session: Session, user_id: int, *, lock: bool = False) -> SafetyProfile:
    """Профиль справедливости пользователя. Ленивое создание (1:1 с User), защищено от гонки
    уникальным индексом user_id. lock=True → with_for_update для путей мутации страйков."""
    def _fetch():
        q = select(SafetyProfile).where(SafetyProfile.user_id == user_id)
        if lock:
            q = q.with_for_update()
        return session.exec(q).first()
    prof = _fetch()
    if prof:
        return prof
    prof = SafetyProfile(user_id=user_id)
    session.add(prof)
    try:
        session.commit()
        session.refresh(prof)
    except IntegrityError:                       # другой запрос создал параллельно
        session.rollback()
        prof = _fetch()
    return prof


def recompute_standing(profile: SafetyProfile, now=None) -> SafetyProfile:
    """Пересчитать standing по лестнице §2 + затухание страйков. suspended (пока пауза активна) →
    limited (страйков ≥ порога) → warned (есть страйк/замечание) → good."""
    now = now or utcnow()
    # Затухание: без новых наказаний дольше окна — обнуляем страйки И замечания (никакого клейма навсегда).
    if profile.last_strike_at and (now - profile.last_strike_at) >= timedelta(days=settings.safety_strike_decay_days):
        profile.strikes = 0
        profile.warnings = 0
    if profile.suspended_until and profile.suspended_until > now:
        profile.standing = "suspended"
    elif profile.strikes >= settings.safety_strikes_to_limit:
        profile.standing = "limited"
    elif profile.strikes >= 1 or profile.warnings >= 1:
        profile.standing = "warned"
    else:
        profile.standing = "good"
    profile.updated_at = now
    return profile


def refresh_standing(session: Session, user_id: int) -> SafetyProfile:
    """Ленивый пересчёт при чтении (затухание / истёкшая пауза) + сохранение при изменении."""
    prof = get_or_create_safety_profile(session, user_id)
    before = (prof.standing, prof.strikes, prof.warnings)
    recompute_standing(prof)
    if before != (prof.standing, prof.strikes, prof.warnings):
        session.add(prof)
        session.commit()
        session.refresh(prof)
    return prof


def is_suspended(profile: SafetyProfile, now=None) -> bool:
    now = now or utcnow()
    return bool(profile.suspended_until and profile.suspended_until > now)


def ensure_active(session: Session, user_id: int) -> None:
    """Гейт лестницы (§2): приостановленный аккаунт не совершает активных действий (в фазе 3 —
    подача новой жалобы). Ленивый пересчёт снимает истёкшую паузу сам."""
    prof = refresh_standing(session, user_id)
    if is_suspended(prof):
        raise HTTPException(403, "Аккаунт на паузе до разбора. Загляни в Центр справедливости — там причина и срок.")


def active_incidents_count(session: Session, user_id: int) -> int:
    return len(list(session.exec(select(Incident.id).where(
        or_(Incident.reporter_id == user_id, Incident.respondent_id == user_id),
        Incident.status.in_(ACTIVE_INCIDENT_STATUSES),
    )).all()))


def incidents_last_hour(session: Session, reporter_id: int) -> int:
    edge = utcnow() - timedelta(hours=1)
    return len(list(session.exec(select(Incident.id).where(
        Incident.reporter_id == reporter_id, Incident.created_at >= edge,
    )).all()))


def completed_trips_for(session: Session, user_id: int) -> int:
    """Число завершённых поездок (как пассажир или водитель) — для витрины доверия."""
    my_ride_ids = list(session.exec(select(Ride.id).where(Ride.driver_id == user_id)).all())
    conds = [Booking.passenger_id == user_id]
    if my_ride_ids:
        conds.append(Booking.ride_id.in_(my_ride_ids))
    return len(list(session.exec(
        select(Booking.id).where(or_(*conds), Booking.status == BookingStatus.done)
    ).all()))


# ----------------------------- Надёжность (0..100) -----------------------------
def _participant_terminal_bookings(session: Session, user_id: int, limit: int) -> list[Booking]:
    """Терминальные брони (завершённые/отменённые/неявка), где пользователь — пассажир ИЛИ водитель."""
    my_ride_ids = list(session.exec(select(Ride.id).where(Ride.driver_id == user_id)).all())
    conds = [Booking.passenger_id == user_id]
    if my_ride_ids:
        conds.append(Booking.ride_id.in_(my_ride_ids))
    return list(session.exec(
        select(Booking)
        .where(
            or_(*conds),
            or_(
                Booking.status.in_([BookingStatus.done, BookingStatus.cancelled]),
                Booking.no_show == True,  # noqa: E712
            ),
        )
        .order_by(Booking.id.desc())
        .limit(limit)
    ).all())


def is_late_cancel(booking: Booking, ride: Optional[Ride], when=None) -> bool:
    """Поздняя отмена: в окне до выезда (или после), либо водитель уже выехал/подъезжает.
    Ранняя отмена — без последствий (§1)."""
    if booking.driver_phase in ("departed", "arriving"):
        return True
    if not ride or not ride.depart_at:
        return False
    when = when or booking.cancelled_at or utcnow()
    threshold = ride.depart_at - timedelta(minutes=settings.safety_late_cancel_before_depart_min)
    return when >= threshold


def reliability_for(session: Session, user_id: int) -> int:
    """«Надёжность» 0..100 — добрый аналог «Активности»: completed / (completed + failed) по
    последним N терминальным броням. Новичок — нейтральные 100%. Неявку засчитываем ТОЛЬКО по
    ПОДТВЕРЖДЁННОМУ админом инциденту (защита оболганного: сырой донос Надёжность не роняет)."""
    window = settings.safety_reliability_window
    bookings = _participant_terminal_bookings(session, user_id, window)
    if not bookings:
        return 100
    booking_ids = [b.id for b in bookings]
    noshow_ids: set[int] = set()
    for inc in session.exec(select(Incident).where(
        Incident.booking_id.in_(booking_ids),
        Incident.respondent_id == user_id,
        Incident.type.in_(["passenger_no_show", "driver_no_show"]),
        Incident.status == "resolved",
        Incident.fault.in_(["respondent", "both"]),
    )).all():
        if inc.booking_id is not None:
            noshow_ids.add(inc.booking_id)
    ride_ids = {b.ride_id for b in bookings}
    rides = {r.id: r for r in session.exec(select(Ride).where(Ride.id.in_(ride_ids))).all()} if ride_ids else {}
    completed = 0
    failed_weight = 0
    for b in bookings:
        if b.status == BookingStatus.done and not b.no_show:
            completed += 1
        elif b.id in noshow_ids:
            failed_weight += 1                 # подтверждённая неявка (инцидент, вина на этом юзере)
        elif not b.no_show and b.cancelled_by == user_id and is_late_cancel(b, rides.get(b.ride_id)):
            failed_weight += 1                 # обычная поздняя отмена этим юзером
    denom = completed + failed_weight
    return 100 if denom == 0 else round(100 * completed / denom)


# ----------------------------- Применение решения админа -----------------------------
def _escalation_days(session: Session, respondent_id: int, exclude_incident_id: Optional[int]) -> int:
    """Длина паузы по лестнице §2: 1-я → 3д, 2-я → 7д, 3-я и далее → 30д. Считаем прошлые
    приостановки этого пользователя (resolution suspend/ban)."""
    prior = [
        i for i in session.exec(select(Incident.id).where(
            Incident.respondent_id == respondent_id,
            Incident.resolution.in_(["suspend", "ban"]),
        )).all()
        if i != exclude_incident_id
    ]
    ladder = [settings.safety_suspend_1_days, settings.safety_suspend_2_days, settings.safety_suspend_3_days]
    return ladder[min(len(prior), len(ladder) - 1)]


def _exclude_linked_ratings(session: Session, incident: Incident) -> None:
    """Щит рейтинга: снять из среднего оценку-месть заявителя на обвинённого по спорной броне
    (использует Rating.excluded, фаза 1) и пересчитать витринный рейтинг обвинённого."""
    if not incident.booking_id:
        return
    ratings = list(session.exec(select(Rating).where(
        Rating.booking_id == incident.booking_id,
        Rating.rater_id == incident.reporter_id,
        Rating.ratee_id == incident.respondent_id,
    )).all())
    affected: set[int] = set()
    for r in ratings:
        if not r.excluded:
            r.excluded = True
            session.add(r)
            affected.add(r.ratee_id)
    session.flush()
    for ratee_id in affected:
        avg, cnt = user_rating(session, ratee_id)     # уже фильтрует excluded (фаза 1)
        prof = session.exec(select(DriverProfile).where(DriverProfile.user_id == ratee_id)).first()
        if prof:
            prof.rating = round(avg, 1) if cnt > 0 else 5.0   # все сняты → нейтральный сид
            session.add(prof)


def apply_incident_resolution(
    session: Session,
    incident: Incident,
    *,
    resolution: str = "",
    fault: str = "",
    note: str = "",
    compensation_kop: int = 0,
    strike: bool = False,
    suspend_days: Optional[int] = None,
    exclude_rating: bool = False,
    shield: bool = False,
    resolver_id: Optional[int] = None,
) -> tuple[Incident, SafetyProfile]:
    """Применить решение админа: последствия к SafetyProfile обвинённого по лестнице §2, снятие
    спорной оценки, щит рейтинга, запись объяснения. Коммитит сам."""
    now = utcnow()
    resolution = (resolution or "").strip() or "none"

    # Щит рейтинга РЕАЛЬНО защищает: снимает зеркальную оценку-месть заявителя из среднего.
    if exclude_rating or shield:
        _exclude_linked_ratings(session, incident)

    prof = get_or_create_safety_profile(session, incident.respondent_id, lock=True)  # лок: страйки без гонки
    if shield:
        prof.rating_shield = True

    added_strike = False
    if resolution == "warning":
        prof.warnings += 1
        prof.last_strike_at = now   # замечание тоже затухает по окну (§4)
    if strike or resolution == "strike":
        prof.strikes += 1
        prof.last_strike_at = now
        added_strike = True

    # Приостановка: явные дни от админа > ban > лестница (3-й страйк / resolution=suspend).
    days = suspend_days if (suspend_days and suspend_days > 0) else None
    if days is None:
        if resolution == "ban":
            days = 3650
        elif resolution == "suspend":
            days = _escalation_days(session, incident.respondent_id, incident.id)
        elif added_strike and prof.strikes >= settings.safety_strikes_to_suspend:
            days = _escalation_days(session, incident.respondent_id, incident.id)
    if days and days > 0:
        prof.suspended_until = now + timedelta(days=days)
        prof.suspend_reason = note or resolution
        if resolution not in ("ban",):   # для консистентного счёта эскалации
            resolution = "suspend"

    recompute_standing(prof, now)
    session.add(prof)

    incident.resolution = resolution
    incident.fault = (fault or "").strip()
    incident.resolution_note = clamp(note, 2000)
    incident.compensation_kop = max(0, int(compensation_kop or 0))
    incident.resolved_by = resolver_id
    incident.resolved_at = now
    incident.updated_at = now
    incident.status = "resolved"
    if incident.appeal_status == "requested":
        incident.appeal_status = "overturned" if incident.resolution in ("dismissed", "mutual_resolved") else "upheld"
    session.add(incident)
    session.commit()
    session.refresh(incident)
    session.refresh(prof)
    return incident, prof
