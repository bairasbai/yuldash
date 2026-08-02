"""F17 — Постоянные (регулярные) маршруты водителя.

Водитель заводит расписание «езжу Баймаҡ→Уфа по пятницам в 8:00»: маршрут + дни
недели + время. Показывается в его профиле и в поиске. Пассажиры могут «следить»
за направлением — подписку хранит route-watch (F13), эта таблица её НЕ дублирует.

`POST   /driver/schedule`        — создать своё расписание (auth).
`GET    /driver/schedule`        — мои расписания (auth).
`GET    /drivers/{id}/schedule`  — публичные (active) регулярные маршруты водителя (без auth).
`DELETE /driver/schedule/{id}`   — удалить своё (auth; чужое → 403).
"""
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..db import get_session
from ..models import DriverSchedule, User
from ..security import current_user

router = APIRouter(tags=["driver-schedule"])

# ISO: 1=Понедельник … 7=Воскресенье
_WEEKDAY_MIN, _WEEKDAY_MAX = 1, 7


def _normalize_weekdays(raw: str) -> str:
    """CSV дней недели → нормализованный CSV (уникальные, по возрастанию, 1..7).

    Принимает "5", "1,3,5", " 1 , 5 ". Отвергает пусто/мусор/вне диапазона → 400.
    """
    if raw is None:
        raise HTTPException(400, "Укажи хотя бы один день недели")
    parts = [p.strip() for p in str(raw).split(",") if p.strip()]
    if not parts:
        raise HTTPException(400, "Укажи хотя бы один день недели")
    days: set[int] = set()
    for p in parts:
        try:
            d = int(p)
        except ValueError:
            raise HTTPException(400, "День недели должен быть числом 1–7 (Пн–Вс)")
        if d < _WEEKDAY_MIN or d > _WEEKDAY_MAX:
            raise HTTPException(400, "День недели вне диапазона 1–7 (Пн–Вс)")
        days.add(d)
    return ",".join(str(d) for d in sorted(days))


def _normalize_time(raw: str) -> str:
    """Время выезда "HH:MM" (24ч). Отвергает мусор/вне 00:00–23:59 → 400."""
    value = (raw or "").strip()
    if not value:
        raise HTTPException(400, "Укажи время выезда (например 08:00)")
    parts = value.split(":")
    if len(parts) != 2 or not parts[0].isdigit() or not parts[1].isdigit():
        raise HTTPException(400, "Время в формате ЧЧ:ММ, например 08:00")
    hh, mm = int(parts[0]), int(parts[1])
    if hh < 0 or hh > 23 or mm < 0 or mm > 59:
        raise HTTPException(400, "Время в формате ЧЧ:ММ, например 08:00")
    return f"{hh:02d}:{mm:02d}"


class ScheduleIn(BaseModel):
    from_city: str = Field(..., min_length=1, max_length=80)
    to_city: str = Field(..., min_length=1, max_length=80)
    weekdays: str = Field(..., description="CSV дней ISO 1=Пн..7=Вс, напр. '1,3,5'")
    time: str = Field(..., description="Время выезда ЧЧ:ММ, напр. '08:00'")
    comment: str = Field("", max_length=200)


class PublicScheduleOut(BaseModel):
    id: int
    driver_id: int
    from_city: str
    to_city: str
    weekdays: str
    time: str
    comment: str = ""


@router.post("/driver/schedule", response_model=DriverSchedule)
def create_schedule(body: ScheduleIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Создать регулярный маршрут водителя."""
    from_city = body.from_city.strip()
    to_city = body.to_city.strip()
    if not from_city or not to_city:
        raise HTTPException(400, "Укажи откуда и куда")
    if from_city.lower() == to_city.lower():
        raise HTTPException(400, "Города отправления и назначения совпадают")
    weekdays = _normalize_weekdays(body.weekdays)
    tm = _normalize_time(body.time)
    # Идемпотентность ПОСЛЕДОВАТЕЛЬНОГО двойного тапа / ретрая: вернём существующее вместо дубля.
    # Дедуп на уровне приложения (без миграции) — покрывает обычный кейс; РОВНО одновременные
    # идентичные POST теоретически создадут дубль (расписание косметическое, не деньги/безопасность).
    existing = session.exec(select(DriverSchedule).where(
        DriverSchedule.driver_id == user.id,
        DriverSchedule.from_city == from_city,
        DriverSchedule.to_city == to_city,
        DriverSchedule.weekdays == weekdays,
        DriverSchedule.time == tm,
        DriverSchedule.active == True,  # noqa: E712
    )).first()
    if existing is not None:
        return existing
    sched = DriverSchedule(
        driver_id=user.id, from_city=from_city, to_city=to_city,
        weekdays=weekdays, time=tm, comment=(body.comment or "").strip()[:200], active=True,
    )
    session.add(sched)
    session.commit()
    session.refresh(sched)
    return sched


@router.get("/driver/schedule", response_model=List[DriverSchedule])
def my_schedules(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мои расписания (все, включая скрытые), новые сверху."""
    return session.exec(
        select(DriverSchedule)
        .where(DriverSchedule.driver_id == user.id)
        .order_by(DriverSchedule.created_at.desc())
    ).all()


@router.get("/drivers/{driver_id}/schedule", response_model=List[PublicScheduleOut])
def public_schedules(driver_id: int, session: Session = Depends(get_session)):
    """Публичные регулярные маршруты водителя (только active) — профиль/поиск, без auth."""
    rows = session.exec(
        select(DriverSchedule)
        .where(DriverSchedule.driver_id == driver_id)
        .where(DriverSchedule.active == True)  # noqa: E712
        .order_by(DriverSchedule.time)
    ).all()
    return [
        PublicScheduleOut(
            id=r.id, driver_id=r.driver_id, from_city=r.from_city, to_city=r.to_city,
            weekdays=r.weekdays, time=r.time, comment=r.comment,
        )
        for r in rows
    ]


@router.delete("/driver/schedule/{schedule_id}")
def delete_schedule(schedule_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Удалить своё расписание. Чужое — 403 (нельзя редактировать/удалять)."""
    sched: Optional[DriverSchedule] = session.get(DriverSchedule, schedule_id)
    if not sched:
        raise HTTPException(404, "Расписание не найдено")
    if sched.driver_id != user.id:
        raise HTTPException(403, "Можно удалять только свои расписания")
    session.delete(sched)
    session.commit()
    return {"ok": True, "id": schedule_id}
