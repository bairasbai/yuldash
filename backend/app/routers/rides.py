"""Поездки: публикация (вкл. регулярные серии), поиск, ценовой ориентир,
ближайшие по маршруту+гео, карточка поездки."""
from datetime import timedelta
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import text
from sqlmodel import Session, select

from ..db import get_session
from ..models import Ride, RideCategory, RideStatus, User
from ..schemas import RideIn, RideOut
from ..security import current_user, current_user_optional
from ..services import (
    CITY_COORDS, blocked_user_ids, boost_then_depart_order, cache_get_json, cache_set_json, drivers_bundle,
    geocode_city, haversine_km, notify_map_changed, public_ride_payload, public_rides_payload, ride_out, ride_out_with, rides_out,
)

router = APIRouter(tags=["rides"])


def _hide_blocked(items, user, session):
    """Прячем из выдачи поездки заблокированных водителей (в обе стороны). Аноним → без фильтра.
    items — список RideOut (свежие) или dict (из кеша/near); оба содержат driver_id."""
    if user is None:
        return items
    blocked = blocked_user_ids(session, user.id)
    if not blocked:
        return items
    return [r for r in items if (r["driver_id"] if isinstance(r, dict) else r.driver_id) not in blocked]


@router.post("/rides", response_model=Ride)
def create_ride(body: RideIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    # Санити-границы (анти-мусор в ленте): мест 1..8, цена 0..100000 ₽. Клампим, а не падаем.
    body.seats_total = max(1, min(8, body.seats_total))
    body.price = max(0, min(100_000, body.price))
    # Геокодим концы маршрута (для радиус-поиска: PostGIS на проде / haversine иначе).
    frm = geocode_city(body.from_city) or (None, None)
    to = geocode_city(body.to_city) or (None, None)
    geo = {"from_lat": frm[0], "from_lng": frm[1], "to_lat": to[0], "to_lng": to[1]}
    ride = Ride(driver_id=user.id, seats_left=body.seats_total, **body.model_dump(), **geo)
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
            session.add(Ride(driver_id=user.id, seats_left=body.seats_total, **{**body.model_dump(), "depart_at": dt}, **geo))
            made += 1
    session.commit()
    session.refresh(ride)
    notify_map_changed()   # новая поездка → пины на карте у всех обновятся live (не дожидаясь 25с-опроса)
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
    user: Optional[User] = Depends(current_user_optional),   # есть токен → прячем заблокированных
    session: Session = Depends(get_session),
):
    # Горячий путь: дефолтный вызов без фильтров (его шлют ВСЕ на карте/вкладке поездок).
    # Кешируем в Redis на 20с → снимаем нагрузку с БД при наплыве. Фильтрованные запросы (реже) — мимо кеша.
    # Кеш хранит ПОЛНЫЙ список; фильтр заблокированных — поверх, per-user (кеш не портим).
    no_filter = (
        not any([from_city, to_city, category, pets_allowed, child_seat, women_only, baggage])
        and limit is None
    )
    if no_filter:
        cached = cache_get_json("rides:active:v1")
        if cached is not None:
            return _hide_blocked(public_rides_payload(cached), user, session)

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
    q = q.order_by(*boost_then_depart_order())   # поднятые (Boost) — первыми
    if limit is not None:
        q = q.offset(max(0, offset)).limit(max(1, min(limit, 200)))   # потолок 200/страница
    rides = session.exec(q).all()
    out = rides_out(rides, session)
    public_out = public_rides_payload(out)
    if no_filter:
        cache_set_json("rides:active:v1", [r.model_dump(mode="json") for r in public_out], 20)
    return _hide_blocked(public_out, user, session)


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
    limit: Optional[int] = None,        # пагинация «показать ещё» (опц., None = все)
    offset: int = 0,
    user: Optional[User] = Depends(current_user_optional),   # есть токен → прячем заблокированных
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
    # PostGIS-префильтр по радиусу (только postgres + есть координаты): индекс GiST → быстро на больших
    # объёмах. Фолбэк (sqlite/без PostGIS/ошибка) — Python-haversine ниже даёт тот же результат.
    if lat is not None and lng is not None and radius_km is not None and session.bind.dialect.name == "postgresql":
        try:
            # NULL-координатные поездки НЕ выкидываем (старые/негеокоженные) — их
            # отфильтрует Python-haversine по CITY_COORDS ниже. ST_DWithin (с GiST-индексом)
            # отсекает далёкие среди геокоженных.
            ids = [row[0] for row in session.execute(text(
                "SELECT id FROM ride WHERE from_lat IS NULL OR "
                "ST_DWithin(ST_MakePoint(from_lng, from_lat)::geography, "
                "ST_MakePoint(:lng, :lat)::geography, :r)"
            ), {"lng": lng, "lat": lat, "r": radius_km * 1000.0}).all()]
            q = q.where(Ride.id.in_(ids)) if ids else q.where(Ride.id.is_(None))
        except Exception as e:  # noqa: BLE001 — нет PostGIS/ошибка → Python-фолбэк
            print(f"[GEO] PostGIS prefilter skipped: {e}")
    rides = session.exec(q.order_by(*boost_then_depart_order())).all()  # Boost первыми, затем по времени выезда ↑
    users, profiles, rating_agg = drivers_bundle(session, {r.driver_id for r in rides})
    items: list = []
    for r in rides:
        dist = None
        if lat is not None and lng is not None:
            # реальные геокодированные координаты концов → иначе известный город → иначе нет дистанции
            c = (r.from_lat, r.from_lng) if r.from_lat is not None and r.from_lng is not None else CITY_COORDS.get(r.from_city)
            if c:
                dist = round(haversine_km(lat, lng, c[0], c[1]), 1)
        if radius_km is not None and dist is not None and dist > radius_km:
            continue
        out = public_ride_payload(ride_out_with(r, users, profiles, rating_agg)).model_dump()
        out["distance_km"] = dist
        items.append(out)
    items = _hide_blocked(items, user, session)   # прячем заблокированных до подсчёта total/пагинации
    total = len(items)
    if limit is not None:
        items = items[max(0, offset):max(0, offset) + max(1, min(limit, 200))]
    return {"count": total, "items": items}   # count = всего (чтобы клиент знал, есть ли «ещё»)


@router.get("/driver/rides", response_model=List[RideOut])
def my_driver_rides(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Свои активные поездки водителя — для выбора, какую поднять (Boost).
    `boosted` в RideOut показывает, что уже поднята."""
    rides = session.exec(
        select(Ride).where(Ride.driver_id == user.id, Ride.status == RideStatus.active)
        .order_by(*boost_then_depart_order())
    ).all()
    return rides_out(rides, session)


@router.get("/rides/{ride_id}", response_model=RideOut)
def get_ride(ride_id: int, session: Session = Depends(get_session)):
    ride = session.get(Ride, ride_id)
    if not ride:
        raise HTTPException(404, "Поездка не найдена")
    return public_ride_payload(ride_out(ride, session))
