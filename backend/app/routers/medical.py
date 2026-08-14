"""F22 — B2B «медцентр-партнёр»: справочник клиник как точек назначения поездок «в больницу».

ДЕЛИКАТНОСТЬ (важно):
- Это ЛОГИСТИКА, а не медуслуга: помогаем «доехать до клиники», не лечим и ничего не обещаем.
- Партнёр (клиника) — публичная организация в справочнике (название/город/адрес/координаты/описание).
- НИКАКИХ мед.данных пациента: диагнозов, приёмов, услуг. Клиника — просто пункт на карте,
  к которому пассажиры из районов могут подъехать вместе (обычная попутка, у которой цель — клиника).
- Приватность как везде: список поездок к клинике отдаётся публичной витриной (без телефона и
  точной точки сбора — они раскрываются только участникам подтверждённой брони).
"""
from typing import List, Optional

from fastapi import APIRouter, Depends
from sqlmodel import Session, select

from ..db import get_session
from ..errors import herr
from ..models import MedicalPartner, Ride, RideStatus, User
from ..security import current_user
from ..visibility import FEED_MAX, visible_rides
from ..services import public_rides_payload, rides_out
from ..timeutil import utcnow
from datetime import timedelta

router = APIRouter(tags=["medical"])

# Грейс как в /rides: только что уехавшую поездку ещё показываем (поздняя посадка).
_PAST_GRACE_HOURS = 2


@router.get("/medical-partners", response_model=List[MedicalPartner])
def list_medical_partners(
    city: Optional[str] = None,
    session: Session = Depends(get_session),
):
    """Справочник клиник-партнёров (только активные). Опц. фильтр по городу.
    Публичные данные организации — доступно без входа (это витрина, не личные данные)."""
    q = select(MedicalPartner).where(MedicalPartner.active == True)  # noqa: E712
    if city:
        q = q.where(MedicalPartner.city.contains(city))
    return session.exec(q.order_by(MedicalPartner.city, MedicalPartner.name)).all()


@router.get("/medical-partners/{partner_id}", response_model=MedicalPartner)
def get_medical_partner(partner_id: int, session: Session = Depends(get_session)):
    partner = session.get(MedicalPartner, partner_id)
    if not partner or not partner.active:
        raise herr(404, "Клиника не найдена", "Клиника табылманы")
    return partner


@router.get("/medical-partners/{partner_id}/rides")
def rides_to_partner(partner_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Поездки «к этой клинике» — активные попутки, у которых клиника выбрана точкой назначения.
    ТРЕБУЕТ ВХОДА: сам факт «кто и когда едет в конкретную клинику» — чувствительный вывод о
    здоровье, анониму его не отдаём (152-ФЗ). Справочник клиник (без поездок) остаётся публичным.
    Данные поездок и так урезаны (без телефона/точной точки сбора) — приватность как в ленте."""
    partner = session.get(MedicalPartner, partner_id)
    if not partner or not partner.active:
        raise herr(404, "Клиника не найдена", "Клиника табылманы")
    q = select(Ride).where(
        Ride.partner_id == partner_id,
        Ride.status == RideStatus.active,
        Ride.seats_left > 0,
        Ride.depart_at >= utcnow() - timedelta(hours=_PAST_GRACE_HOURS),
    ).order_by(Ride.depart_at)
    rides = session.exec(q.limit(FEED_MAX)).all()   # потолок витрины, как в ленте (волна 89)
    # Те же правила видимости, что в ленте. Раньше эта витрина их не знала: пассажирка
    # заблокировала водителя, в ленте он исчез — а здесь спокойно предлагался снова
    # (аудит 2026-08-08, волна 71). Место, где человек едет в больницу, — последнее,
    # где можно свести его с тем, от кого он прятался.
    items = visible_rides(public_rides_payload(rides_out(rides, session)), user, session)
    return {
        "partner": partner,
        "count": len(items),
        "items": [it.model_dump() for it in items],
    }


# ----------------------------- Сид справочника -----------------------------
# Пара реальных примеров: республиканская клиника в Уфе + районные ЦРБ, куда чаще всего
# едут из сёл. Координаты приблизительные (пин на карте), описание — только про логистику.
_SEED_PARTNERS = [
    {
        "name": "РКБ им. Г. Г. Куватова",
        "city": "Уфа",
        "address": "ул. Достоевского, 132",
        "lat": 54.7261, "lng": 55.9475,
        "description": "Республиканская клиническая больница. Как доехать: центр Уфы, рядом остановка.",
    },
    {
        "name": "Баймакская ЦРБ",
        "city": "Баймак",
        "address": "ул. М. Горького, 5",
        "lat": 52.5913, "lng": 58.3170,
        "description": "Центральная районная больница Баймакского района.",
    },
    {
        "name": "Сибайская ЦГБ",
        "city": "Сибай",
        "address": "ул. Заки Валиди, 27",
        "lat": 52.7160, "lng": 58.6640,
        "description": "Центральная городская больница Сибая.",
    },
]


def seed_medical_partners(session: Session) -> None:
    """Идемпотентно: заполняем справочник примерами, только если он пуст.
    Вызывается из миграции (прод) и может зваться при демо-сидировании."""
    if session.exec(select(MedicalPartner)).first():
        return
    for p in _SEED_PARTNERS:
        session.add(MedicalPartner(**p))
    session.commit()
