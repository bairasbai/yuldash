"""Заявки пассажира + матчинг заявки с поездками + отклики водителей (см. backend.md §4)."""
from datetime import datetime, timedelta
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import or_, text
from sqlmodel import Session, select

from ..db import get_session
from ..models import (
    Block, Booking, BookingStatus, DeviceToken, RequestResponse, Ride, RideCategory,
    RideRequest, RideStatus, User, UserRole,
)
from ..schemas import RideOut
from ..security import current_user, gen_otp
from ..services import (
    CITY_COORDS, geocode_city, haversine_km, is_blocked, notify_admin_telegram,
    notify_map_changed, public_rides_payload, push_notification, rides_out, user_rating,
)
from ..timeutil import utcnow
from .. import workday as workday_mod
from ..trust_service import INSIDER_LEVEL, trust_level

router = APIRouter(tags=["requests"])


class RequestIn(BaseModel):
    from_city: str
    to_city: str
    desired_at: Optional[datetime] = None
    seats: int = Field(1, ge=1, le=8)                       # ≥1 место, разумный потолок (защита от мусора/минуса)
    max_price: Optional[int] = Field(None, ge=0, le=1_000_000)
    category: RideCategory = RideCategory.regular
    with_kids: bool = False
    baggage: bool = False
    women_only: bool = False        # предпочтения пассажира (условия поездки)
    child_seat: bool = False
    pets: bool = False
    wheelchair: bool = False
    non_smoking: bool = False
    air_conditioner: bool = False
    only_trusted: bool = False       # «только для своих» — заявку берут лишь водители L3
    comment: str = Field("", max_length=2000)
    for_relative_name: Optional[str] = Field(None, max_length=120)
    voice_url: Optional[str] = None
    transcript: Optional[str] = Field(None, max_length=4000)
    assisted: bool = False   # заявка из «помощь»-режима (пожилой/голос/за близкого) — НЕ храним, только уведомляем админа


@router.post("/requests", response_model=RideRequest)
def create_request(body: RequestIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    # Геокодим концы маршрута (для карты водителя и радиус-поиска заявок) — как у POST /rides.
    frm = geocode_city(body.from_city) or (None, None)
    to = geocode_city(body.to_city) or (None, None)
    req = RideRequest(
        passenger_id=user.id,
        **body.model_dump(exclude={"assisted"}),
        from_lat=frm[0], from_lng=frm[1], to_lat=to[0], to_lng=to[1],
    )
    session.add(req)
    session.commit()
    session.refresh(req)
    notify_map_changed()   # новая заявка → оранжевый маркер появится на карте live
    # Срочно/помощь — сразу уведомляем админа, чтобы не упустить время (пожилому может быть нужно срочно).
    if body.assisted or req.category == RideCategory.urgent:
        notify_admin_telegram(
            f"🆕 Заявка — нужна помощь{' (СРОЧНО)' if req.category == RideCategory.urgent else ''}\n"
            f"От: {user.name or user.phone}\n{req.from_city} → {req.to_city}\n"
            f"{req.comment or '—'}\n→ Кабинет админа → Отклики по заявке #{req.id}"
        )
    return req


@router.get("/requests/near")
def requests_near(
    from_city: Optional[str] = None,
    to_city: Optional[str] = None,
    lat: Optional[float] = None,
    lng: Optional[float] = None,
    radius_km: Optional[float] = None,
    limit: Optional[int] = None,
    offset: int = 0,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """Активные заявки пассажиров рядом — водитель видит, кто ищет попутку на его маршруте (зеркало /rides/near).
    Координаты клиента (lat/lng) → дистанция до точки отправления заявки + опц. фильтр радиуса.
    Приватность: отдаём только город/точку отправления + имя, без телефона/точного адреса."""
    q = select(RideRequest).where(RideRequest.status == "active")
    if from_city:
        q = q.where(RideRequest.from_city.contains(from_city))
    if to_city:
        q = q.where(RideRequest.to_city.contains(to_city))
    # PostGIS-префильтр по радиусу (postgres + координаты) — как у /rides/near; иначе Python-haversine ниже.
    if lat is not None and lng is not None and radius_km is not None and session.bind.dialect.name == "postgresql":
        try:
            ids = [row[0] for row in session.execute(text(
                "SELECT id FROM riderequest WHERE from_lat IS NULL OR "
                "ST_DWithin(ST_MakePoint(from_lng, from_lat)::geography, "
                "ST_MakePoint(:lng, :lat)::geography, :r)"
            ), {"lng": lng, "lat": lat, "r": radius_km * 1000.0}).all()]
            q = q.where(RideRequest.id.in_(ids)) if ids else q.where(RideRequest.id.is_(None))
        except Exception as e:  # noqa: BLE001 — нет PostGIS/ошибка → Python-фолбэк ниже
            print(f"[GEO] requests PostGIS prefilter skipped: {e}")
    reqs = session.exec(q.order_by(RideRequest.id.desc())).all()
    pax = {u.id: u for u in session.exec(select(User).where(User.id.in_({r.passenger_id for r in reqs}))).all()} if reqs else {}
    is_insider = trust_level(session, user) >= INSIDER_LEVEL   # заявки «только для своих» видит лишь L3
    items: list = []
    for r in reqs:
        if user is not None and is_blocked(session, user.id, r.passenger_id):
            continue
        if getattr(r, "only_trusted", False) and not is_insider and r.passenger_id != user.id:
            continue
        dist = None
        if lat is not None and lng is not None:
            c = (r.from_lat, r.from_lng) if r.from_lat is not None and r.from_lng is not None else CITY_COORDS.get(r.from_city)
            if c:
                dist = round(haversine_km(lat, lng, c[0], c[1]), 1)
        if radius_km is not None and dist is not None and dist > radius_km:
            continue
        passenger = pax.get(r.passenger_id)
        items.append({
            "id": r.id,
            "passenger_name": (passenger.name if passenger else "") or "Пассажир",
            "from_city": r.from_city,
            "to_city": r.to_city,
            # Приватность (152-ФЗ): отдаём ОКРУГЛённую точку (~1 км), не точный адрес пассажира.
            # Достаточно для маркера/радиуса; точную точку встречи стороны согласуют в чате.
            "from_lat": round(r.from_lat, 2) if r.from_lat is not None else None,
            "from_lng": round(r.from_lng, 2) if r.from_lng is not None else None,
            "desired_at": r.desired_at,
            "seats": r.seats,
            "comment": r.comment,
            "distance_km": dist,
        })
    total = len(items)
    if limit is not None:
        items = items[max(0, offset):max(0, offset) + max(1, min(limit, 200))]
    return {"count": total, "items": items}


class AdminRequestIn(BaseModel):
    phone: str
    name: str = Field("", max_length=120)
    from_city: str
    to_city: str
    desired_at: Optional[datetime] = None
    seats: int = Field(1, ge=1, le=8)
    comment: str = Field("", max_length=2000)


@router.post("/admin/request-for-phone", response_model=RideRequest)
def admin_request_for_phone(body: AdminRequestIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Админ создаёт заявку ЗА пользователя по телефону (после звонка «перезвоните мне»).
    Находит/создаёт юзера по номеру → заводит заявку → водители видят её как обычную."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для администратора")
    phone = body.phone.strip()
    if not phone:
        raise HTTPException(400, "Нужен телефон")
    target = session.exec(select(User).where(User.phone == phone)).first()
    if not target:
        target = User(phone=phone, name=body.name or "Пользователь", verified=False)
        session.add(target)
        session.commit()
        session.refresh(target)
    req = RideRequest(
        passenger_id=target.id, from_city=body.from_city, to_city=body.to_city,
        desired_at=body.desired_at, seats=body.seats, comment=body.comment,
        for_relative_name=(body.name or None),
    )
    session.add(req)
    session.commit()
    session.refresh(req)
    return req


@router.get("/requests/mine", response_model=List[RideRequest])
def my_requests(
    limit: Optional[int] = None,        # пагинация (опц., None = все — обратносовместимо)
    offset: int = 0,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    q = select(RideRequest).where(RideRequest.passenger_id == user.id)
    if limit is not None:   # порядок добавляем только при пагинации (дефолт — как было)
        q = q.order_by(RideRequest.id.desc()).offset(max(0, offset)).limit(max(1, min(limit, 200)))
    return session.exec(q).all()


class RequestEditIn(BaseModel):
    """F3: правка своей активной заявки. Все поля опциональны — меняется только присланное."""
    from_city: Optional[str] = Field(None, max_length=120)
    to_city: Optional[str] = Field(None, max_length=120)
    desired_at: Optional[datetime] = None
    seats: Optional[int] = Field(None, ge=1, le=8)
    max_price: Optional[int] = Field(None, ge=0, le=1_000_000)
    comment: Optional[str] = Field(None, max_length=2000)


@router.patch("/requests/{request_id}", response_model=RideRequest)
@router.post("/requests/{request_id}/edit", response_model=RideRequest)   # алиас: Android HttpURLConnection не умеет PATCH
def edit_request(request_id: int, body: RequestEditIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """F3: пассажир правит свою заявку (опечатка в маршруте/времени была неисправимой —
    только отмена и пересоздание). Править можно ТОЛЬКО активную (matched уже стала поездкой).
    Смена маршрута перегеокодит концы (карта водителя показывает верную точку)."""
    req = session.get(RideRequest, request_id)
    if not req:
        raise HTTPException(404, "Заявка не найдена")
    if req.passenger_id != user.id and user.role != UserRole.admin:
        raise HTTPException(403, "Можно править только свою заявку")
    if req.status != "active":
        raise HTTPException(400, "Править можно только активную заявку")
    changed = False
    for field in ("from_city", "to_city", "desired_at", "seats", "max_price", "comment"):
        val = getattr(body, field)
        if val is not None and val != getattr(req, field):
            setattr(req, field, val)
            changed = True
    if not changed:
        return req   # нечего менять — no-op
    # Маршрут мог смениться → перегеокодим концы (иначе маркер заявки останется в старом городе).
    if body.from_city is not None:
        frm = geocode_city(req.from_city) or (None, None)
        req.from_lat, req.from_lng = frm
    if body.to_city is not None:
        to = geocode_city(req.to_city) or (None, None)
        req.to_lat, req.to_lng = to
    session.add(req)
    session.commit()
    session.refresh(req)
    notify_map_changed()   # маркер/карточка заявки обновится live
    return req


@router.post("/requests/{request_id}/cancel", response_model=RideRequest)
def cancel_request(request_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Пассажир отменяет свою заявку → статус `cancelled`. Отменённая уходит из ленты
    водителей (/requests/feed и /requests/near отдают только active). Только владелец (или админ)."""
    req = session.get(RideRequest, request_id)
    if not req:
        raise HTTPException(404, "Заявка не найдена")
    if req.passenger_id != user.id and user.role != UserRole.admin:
        raise HTTPException(403, "Можно отменить только свою заявку")
    if req.status == "cancelled":
        return req   # идемпотентно — повторная отмена не ошибка (двойной тап/ретрай)
    if req.status != "active":
        # matched (уже создана поездка+бронь) отменяется через отмену брони, не тут.
        raise HTTPException(400, "Заявку уже нельзя отменить")
    req.status = "cancelled"
    session.add(req)
    session.commit()
    session.refresh(req)
    notify_map_changed()   # оранжевый маркер заявки уходит с карты live
    return req


# ---------------- Заявки ↔ водители: лента, отклики, принятие ----------------

def request_prefs(r) -> list:
    """Активные условия/предпочтения заявки → список ключей (клиент рисует чипы). Порядок стабилен."""
    out = []
    if getattr(r, "women_only", False): out.append("women")
    if getattr(r, "child_seat", False): out.append("child")
    if getattr(r, "pets", False): out.append("pets")
    if getattr(r, "wheelchair", False): out.append("wheelchair")
    if getattr(r, "baggage", False): out.append("baggage")
    if getattr(r, "non_smoking", False): out.append("nosmoke")
    if getattr(r, "air_conditioner", False): out.append("ac")
    return out


class RequestFeedOut(BaseModel):
    id: int
    passenger_name: str
    passenger_avatar: str = ""
    from_city: str
    to_city: str
    desired_at: Optional[datetime]
    seats: int
    comment: str
    responded: bool                          # уже откликался ли текущий водитель
    my_response_id: Optional[int] = None      # id своего отклика (чтобы можно было отозвать); null если не откликался
    prefs: list = []                          # условия заявки (women/child/pets/wheelchair/baggage/nosmoke/ac)


@router.get("/requests/feed", response_model=List[RequestFeedOut])
def requests_feed(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Активные заявки пассажиров — для водителей (откликнуться). Без своих и заблокированных."""
    reqs = session.exec(
        select(RideRequest)
        .where(RideRequest.status == "active", RideRequest.passenger_id != user.id)
        .order_by(RideRequest.id.desc()).limit(200)
    ).all()
    if not reqs:
        return []
    pax = {u.id: u for u in session.exec(select(User).where(User.id.in_({r.passenger_id for r in reqs}))).all()}
    mine = {rr.request_id: rr.id for rr in session.exec(
        select(RequestResponse).where(
            RequestResponse.driver_id == user.id,
            RequestResponse.request_id.in_([r.id for r in reqs]),
        )
    ).all()}
    # Блокировки текущего водителя — ОДНИМ запросом (анти-N+1 вместо is_blocked в цикле по 200 заявкам).
    blk = session.exec(select(Block).where(or_(Block.user_id == user.id, Block.blocked_user_id == user.id))).all()
    blocked_ids = {(b.blocked_user_id if b.user_id == user.id else b.user_id) for b in blk}
    is_insider = trust_level(session, user) >= INSIDER_LEVEL   # заявки «только для своих» видит лишь L3
    out: list = []
    for r in reqs:
        if r.passenger_id in blocked_ids:
            continue
        if getattr(r, "only_trusted", False) and not is_insider:
            continue
        p = pax.get(r.passenger_id)
        out.append(RequestFeedOut(
            id=r.id, passenger_name=(p.name if p and p.name else "Пассажир"),
            passenger_avatar=(p.avatar_url if p else ""),
            from_city=r.from_city, to_city=r.to_city, desired_at=r.desired_at,
            seats=r.seats, comment=r.comment, responded=(r.id in mine),
            my_response_id=mine.get(r.id),
            prefs=request_prefs(r),
        ))
    return out


class RespondIn(BaseModel):
    price: int = Field(0, ge=0, le=1_000_000)              # цена ≥0, потолок — защита от отрицательной/мусорной суммы в брони
    comment: str = Field("", max_length=500)


@router.post("/requests/{request_id}/respond")
def respond_to_request(request_id: int, body: RespondIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель откликается на заявку. Уведомляет пассажира (push); если у пассажира нет
    устройства (заявка создана админом, без приложения) — уведомляет админа в Telegram."""
    req = session.get(RideRequest, request_id)
    if not req or req.status != "active":
        raise HTTPException(404, "Заявка не найдена или закрыта")
    if req.passenger_id == user.id:
        raise HTTPException(400, "Нельзя откликнуться на свою заявку")
    if is_blocked(session, user.id, req.passenger_id):
        raise HTTPException(403, "Недоступно")
    # Отдых водителя (§8): во время блока такси новые обязательства не берём — домой
    # везёт «один попутчик» из СВОЕЙ публикации (POST /rides), а не отклики на заявки.
    workday_mod.guard_respond_request(session, user.id)
    if getattr(req, "only_trusted", False) and trust_level(session, user) < INSIDER_LEVEL:
        raise HTTPException(403, "Заявка только для своих")   # IDOR-защита: прямой id не обходит фильтр
    dup = session.exec(select(RequestResponse).where(
        RequestResponse.request_id == request_id, RequestResponse.driver_id == user.id)).first()
    if dup:
        return {"ok": True, "id": dup.id}     # идемпотентно — повторный отклик не плодим
    resp = RequestResponse(request_id=request_id, driver_id=user.id, price=body.price, comment=body.comment)
    session.add(resp)
    session.commit()
    session.refresh(resp)
    price_s = f", {body.price}₽" if body.price else ""
    drv_name = user.name or "Водитель"
    route = f"{req.from_city} → {req.to_city}"
    push_notification(
        session, req.passenger_id, "ride",
        "Отклик на заявку", "Заявкаға яуап",
        f"{drv_name}: {route}{price_s}", f"{drv_name}: {route}{price_s}",
        ref_kind="request", ref_id=req.id,
    )
    has_device = session.exec(select(DeviceToken).where(DeviceToken.user_id == req.passenger_id)).first() is not None
    if not has_device:   # пассажир без приложения (напр. создан админом по звонку) → зовём админа перезвонить
        p = session.get(User, req.passenger_id)
        notify_admin_telegram(
            f"🚗 Отклик на заявку #{req.id}\n"
            f"Пассажир: {(p.name if p else '—')} ({p.phone if p else '—'})\n"
            f"Водитель: {user.name or '—'}\n{req.from_city} → {req.to_city}{price_s}"
        )
    return {"ok": True, "id": resp.id}


class ResponseOut(BaseModel):
    id: int
    driver_id: int
    driver_name: str
    driver_avatar: str = ""
    driver_rating: Optional[float]
    price: int
    comment: str
    status: str


@router.get("/requests/{request_id}/responses", response_model=List[ResponseOut])
def request_responses(request_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отклики на МОЮ заявку — пассажир выбирает водителя."""
    req = session.get(RideRequest, request_id)
    if not req:
        raise HTTPException(404, "Заявка не найдена")
    if req.passenger_id != user.id and user.role != UserRole.admin:   # админ видит любые (помощь по звонку)
        raise HTTPException(403, "Нет доступа")
    resps = session.exec(
        select(RequestResponse).where(RequestResponse.request_id == request_id).order_by(RequestResponse.id.desc())
    ).all()
    drivers = {u.id: u for u in session.exec(select(User).where(User.id.in_({r.driver_id for r in resps}))).all()} if resps else {}
    out: list = []
    for r in resps:
        d = drivers.get(r.driver_id)
        avg, cnt = user_rating(session, r.driver_id)
        out.append(ResponseOut(
            id=r.id, driver_id=r.driver_id, driver_name=(d.name if d and d.name else "Водитель"),
            driver_avatar=(d.avatar_url if d else ""),
            driver_rating=(round(avg, 1) if cnt > 0 else None), price=r.price, comment=r.comment, status=r.status,
        ))
    return out


@router.post("/responses/{response_id}/accept")
def accept_response(response_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Пассажир принимает отклик → создаётся Ride+Booking (поездка с чатом/кодом посадки),
    заявка закрывается, водителю — push. Возвращает booking_id для перехода в активную поездку."""
    resp = session.get(RequestResponse, response_id)
    if not resp:
        raise HTTPException(404, "Отклик не найден")
    # with_for_update на заявке: два параллельных accept разных откликов не пройдут оба
    # проверку status=="active" (иначе — две Ride+Booking на одну заявку, два водителя за одним пассажиром).
    req = session.exec(
        select(RideRequest).where(RideRequest.id == resp.request_id).with_for_update()
    ).first()
    if not req:
        raise HTTPException(404, "Заявка не найдена")
    if req.passenger_id != user.id and user.role != UserRole.admin:   # админ принимает ЗА юзера (без интернета)
        raise HTTPException(403, "Нет доступа")
    if req.status != "active":
        raise HTTPException(400, "Заявка уже закрыта")
    depart = req.desired_at or (utcnow() + timedelta(hours=1))
    ride = Ride(
        driver_id=resp.driver_id, from_city=req.from_city, to_city=req.to_city, depart_at=depart,
        seats_total=req.seats, seats_left=0, price=resp.price, category=req.category, status=RideStatus.active,
    )
    session.add(ride)
    session.flush()   # flush выдаёт ride.id БЕЗ commit → Ride+Booking+статусы фиксируем ОДНОЙ транзакцией.
    booking = Booking(   # бронь на ПАССАЖИРА заявки (а не на того, кто принял — важно при admin-accept)
        ride_id=ride.id, passenger_id=req.passenger_id, seats=req.seats, price=resp.price * req.seats,
        status=BookingStatus.confirmed, boarding_code=gen_otp(),
    )
    session.add(booking)
    req.status = "matched"
    resp.status = "accepted"
    session.add(req)
    session.add(resp)
    session.commit()   # атомарно: сбой не оставит «осиротевшую» поездку без брони и не даст принять отклик повторно
    session.refresh(booking)
    notify_map_changed()   # заявка исполнена (matched) → её маркер уходит, новая поездка появляется — live
    pax = session.get(User, req.passenger_id)
    pax_name = pax.name if pax else "Пассажир"
    route = f"{req.from_city} → {req.to_city}"
    push_notification(
        session, resp.driver_id, "ride",
        "Заявку приняли", "Заявка ҡабул ителде",
        f"{pax_name}: {route}", f"{pax_name}: {route}",
        ref_kind="booking", ref_id=booking.id,
    )
    return {"booking_id": booking.id}


@router.delete("/responses/{response_id}")
def withdraw_response(response_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель отзывает свой отклик на заявку — пока пассажир его не принял.
    Только автор отклика; после accept (status=accepted) отозвать нельзя → 409
    (поездка уже создана, за ней пассажир). Удаляем строку отклика."""
    resp = session.get(RequestResponse, response_id)
    if not resp:
        raise HTTPException(404, "Отклик не найден")
    if resp.driver_id != user.id:   # только свой отклик (даже админ чужой не трогает — это личное действие водителя)
        raise HTTPException(403, "Можно отозвать только свой отклик")
    if resp.status == "accepted":
        raise HTTPException(409, "Отклик уже принят — отозвать нельзя")
    session.delete(resp)
    session.commit()
    return {"ok": True}


@router.get("/match/rides", response_model=List[RideOut])
def match_rides(request_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    req = session.get(RideRequest, request_id)
    if not req:
        raise HTTPException(404, "Заявка не найдена")
    if req.passenger_id != user.id:
        raise HTTPException(403, "Нет доступа к этой заявке")
    q = select(Ride).where(
        Ride.status == RideStatus.active,
        Ride.from_city.contains(req.from_city),
        Ride.to_city.contains(req.to_city),
        Ride.seats_left >= req.seats,
        Ride.category == req.category,
    )
    rides = session.exec(q.order_by(Ride.depart_at)).all()
    if trust_level(session, user) < INSIDER_LEVEL:   # «только для своих» видит лишь L3
        rides = [r for r in rides if not r.only_trusted]
    # Через public-payload: точная точка сбора (pickup/координаты) раскрывается только участнику
    # подтверждённой брони, а не всем, кто ищет попутку по заявке (приватность до брони).
    return public_rides_payload(rides_out(rides, session))
