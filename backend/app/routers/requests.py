"""Заявки пассажира + матчинг заявки с поездками + отклики водителей (см. backend.md §4)."""
from datetime import datetime, timedelta
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..db import get_session
from ..models import (
    Booking, BookingStatus, DeviceToken, RequestResponse, Ride, RideCategory,
    RideRequest, RideStatus, User, UserRole,
)
from ..security import current_user, gen_otp
from ..services import is_blocked, notify_admin_telegram, send_push, user_rating
from ..timeutil import utcnow

router = APIRouter(tags=["requests"])


class RequestIn(BaseModel):
    from_city: str
    to_city: str
    desired_at: Optional[datetime] = None
    seats: int = 1
    max_price: Optional[int] = None
    category: RideCategory = RideCategory.regular
    with_kids: bool = False
    baggage: bool = False
    comment: str = Field("", max_length=2000)
    for_relative_name: Optional[str] = Field(None, max_length=120)
    voice_url: Optional[str] = None
    transcript: Optional[str] = Field(None, max_length=4000)


@router.post("/requests", response_model=RideRequest)
def create_request(body: RequestIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    req = RideRequest(passenger_id=user.id, **body.model_dump())
    session.add(req)
    session.commit()
    session.refresh(req)
    return req


class AdminRequestIn(BaseModel):
    phone: str
    name: str = Field("", max_length=120)
    from_city: str
    to_city: str
    desired_at: Optional[datetime] = None
    seats: int = 1
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


# ---------------- Заявки ↔ водители: лента, отклики, принятие ----------------

class RequestFeedOut(BaseModel):
    id: int
    passenger_name: str
    from_city: str
    to_city: str
    desired_at: Optional[datetime]
    seats: int
    comment: str
    responded: bool                          # уже откликался ли текущий водитель


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
    mine = {rr.request_id for rr in session.exec(
        select(RequestResponse).where(
            RequestResponse.driver_id == user.id,
            RequestResponse.request_id.in_([r.id for r in reqs]),
        )
    ).all()}
    out: list = []
    for r in reqs:
        if is_blocked(session, user.id, r.passenger_id):
            continue
        p = pax.get(r.passenger_id)
        out.append(RequestFeedOut(
            id=r.id, passenger_name=(p.name if p and p.name else "Пассажир"),
            from_city=r.from_city, to_city=r.to_city, desired_at=r.desired_at,
            seats=r.seats, comment=r.comment, responded=(r.id in mine),
        ))
    return out


class RespondIn(BaseModel):
    price: int = 0
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
    dup = session.exec(select(RequestResponse).where(
        RequestResponse.request_id == request_id, RequestResponse.driver_id == user.id)).first()
    if dup:
        return {"ok": True, "id": dup.id}     # идемпотентно — повторный отклик не плодим
    resp = RequestResponse(request_id=request_id, driver_id=user.id, price=body.price, comment=body.comment)
    session.add(resp)
    session.commit()
    session.refresh(resp)
    price_s = f", {body.price}₽" if body.price else ""
    send_push(session, req.passenger_id, "Отклик на заявку", f"{user.name or 'Водитель'}: {req.from_city} → {req.to_city}{price_s}")
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
    req = session.get(RideRequest, resp.request_id)
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
    session.commit()
    session.refresh(ride)
    booking = Booking(   # бронь на ПАССАЖИРА заявки (а не на того, кто принял — важно при admin-accept)
        ride_id=ride.id, passenger_id=req.passenger_id, seats=req.seats, price=resp.price * req.seats,
        status=BookingStatus.confirmed, boarding_code=gen_otp(),
    )
    session.add(booking)
    req.status = "matched"
    resp.status = "accepted"
    session.add(req)
    session.add(resp)
    session.commit()
    session.refresh(booking)
    pax = session.get(User, req.passenger_id)
    send_push(session, resp.driver_id, "Заявку приняли", f"{(pax.name if pax else 'Пассажир')}: {req.from_city} → {req.to_city}")
    return {"booking_id": booking.id}


@router.get("/match/rides", response_model=List[Ride])
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
    return session.exec(q.order_by(Ride.depart_at)).all()
