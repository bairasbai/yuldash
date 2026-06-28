"""Поездки: публикация (вкл. регулярные серии), поиск, ценовой ориентир,
ближайшие по маршруту+гео, карточка поездки."""
from datetime import timedelta
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException
from sqlmodel import Session, select

from ..db import get_session
from ..models import Ride, RideCategory, RideStatus, User
from ..schemas import RideIn, RideOut
from ..security import current_user
from ..services import CITY_COORDS, drivers_bundle, haversine_km, ride_out_with, rides_out

router = APIRouter(tags=["rides"])


@router.post("/rides", response_model=Ride)
def create_ride(body: RideIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    ride = Ride(driver_id=user.id, seats_left=body.seats_total, **body.model_dump())
    session.add(ride)
    # Регулярная поездка: сразу создаём ближайшие 4 рейса серии (реальные, бронируемые).
    if body.recurrence and body.recurrence != "none":
        step = {"weekly": timedelta(weeks=1)}.get(body.recurrence, timedelta(days=1))
        dt = body.depart_at
        made = 0
        guard = 0
        while made < 4 and guard < 40:
            guard += 1
            dt = dt + step
            if body.recurrence == "weekdays" and dt.weekday() >= 5:   # пропускаем сб/вс
                continue
            session.add(Ride(driver_id=user.id, seats_left=body.seats_total, **{**body.model_dump(), "depart_at": dt}))
            made += 1
    session.commit()
    session.refresh(ride)
    return ride


@router.get("/rides", response_model=List[RideOut])
def search_rides(
    from_city: Optional[str] = None,
    to_city: Optional[str] = None,
    category: Optional[RideCategory] = None,
    pets_allowed: Optional[bool] = None,
    child_seat: Optional[bool] = None,
    women_only: Optional[bool] = None,
    baggage: Optional[bool] = None,
    limit: Optional[int] = None,        # пагинация (опц., обратносовместимо: None = все)
    offset: int = 0,
    session: Session = Depends(get_session),
):
    q = select(Ride).where(Ride.status == RideStatus.active)
    if from_city:
        q = q.where(Ride.from_city.contains(from_city))
    if to_city:
        q = q.where(Ride.to_city.contains(to_city))
    if category:
        q = q.where(Ride.category == category)
    if pets_allowed:
        q = q.where(Ride.pets_allowed == True)  # noqa: E712
    if child_seat:
        q = q.where(Ride.child_seat == True)  # noqa: E712
    if women_only:
        q = q.where(Ride.women_only == True)  # noqa: E712
    if baggage:
        q = q.where(Ride.baggage == True)  # noqa: E712
    q = q.order_by(Ride.depart_at)
    if limit is not None:
        q = q.offset(max(0, offset)).limit(max(1, min(limit, 200)))   # потолок 200/страница
    rides = session.exec(q).all()
    return rides_out(rides, session)


@router.get("/rides/price_hint")
def price_hint(
    from_city: Optional[str] = None,
    to_city: Optional[str] = None,
    session: Session = Depends(get_session),
):
    """Ориентир цены по маршруту: средняя цена поездок (price>0). Подсказка водителю, не навязываем."""
    q = select(Ride.price).where(Ride.price > 0)
    if from_city:
        q = q.where(Ride.from_city.contains(from_city))
    if to_city:
        q = q.where(Ride.to_city.contains(to_city))
    prices = [p for p in session.exec(q).all() if p and p > 0]
    if not prices:
        return {"avg": 0, "count": 0}
    return {"avg": round(sum(prices) / len(prices)), "count": len(prices)}


@router.get("/rides/near")
def rides_near(
    from_city: Optional[str] = None,
    to_city: Optional[str] = None,
    lat: Optional[float] = None,
    lng: Optional[float] = None,
    radius_km: Optional[float] = None,
    session: Session = Depends(get_session),
):
    """Ближайшие поездки по маршруту клиента, отсортированы по времени выезда (ранняя — первой).
    Если переданы координаты клиента (lat/lng) — добавляем дистанцию до точки выезда и (опц.) фильтр по радиусу.
    Сценарий: водитель отменил/сломался → клиент видит ближайшую по времени машину на своём маршруте и уезжает."""
    q = select(Ride).where(Ride.status == RideStatus.active, Ride.seats_left > 0)
    if from_city:
        q = q.where(Ride.from_city.contains(from_city))
    if to_city:
        q = q.where(Ride.to_city.contains(to_city))
    rides = session.exec(q.order_by(Ride.depart_at)).all()  # по времени выезда ↑
    users, profiles, rating_agg = drivers_bundle(session, {r.driver_id for r in rides})
    items: list = []
    for r in rides:
        dist = None
        if lat is not None and lng is not None:
            c = CITY_COORDS.get(r.from_city)
            if c:
                dist = round(haversine_km(lat, lng, c[0], c[1]), 1)
        if radius_km is not None and dist is not None and dist > radius_km:
            continue
        out = ride_out_with(r, users, profiles, rating_agg).model_dump()
        out["distance_km"] = dist
        items.append(out)
    return {"count": len(items), "items": items}


@router.get("/rides/{ride_id}", response_model=Ride)
def get_ride(ride_id: int, session: Session = Depends(get_session)):
    ride = session.get(Ride, ride_id)
    if not ride:
        raise HTTPException(404, "Поездка не найдена")
    return ride
