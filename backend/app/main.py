"""Юлдаш API — Фаза 1 (см. docs/backend.md).
Вход по SMS-коду (OTP), поездки, заявки, брони, матчинг, статус водителя.
SMS пока мок: код пишется в лог и (в dev) возвращается в ответе.
"""
from contextlib import asynccontextmanager
from datetime import datetime, timedelta
from typing import List, Optional

from fastapi import Depends, FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from sqlmodel import Session, select

from .config import settings
from .db import get_session, init_db
from .models import (
    Booking, BookingStatus, DriverProfile, OtpCode, Ride, RideCategory,
    RideRequest, RideStatus, User,
)
from .security import current_user, gen_otp, make_token


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    yield


app = FastAPI(title="Yuldash API", version="0.1.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"],
)


@app.get("/health")
def health():
    return {"status": "ok", "env": settings.env}


# ----------------------------- Авторизация (телефон + OTP) -----------------------------
class PhoneIn(BaseModel):
    phone: str


class VerifyIn(BaseModel):
    phone: str
    code: str
    name: str = ""


@app.post("/auth/request-code")
def request_code(body: PhoneIn, session: Session = Depends(get_session)):
    code = gen_otp()
    session.add(OtpCode(
        phone=body.phone, code=code,
        expires_at=datetime.utcnow() + timedelta(seconds=settings.otp_ttl_sec),
    ))
    session.commit()
    print(f"[OTP] {body.phone} -> {code}")  # TODO: реальный SMS-провайдер
    resp = {"sent": True}
    if settings.env == "dev":
        resp["dev_code"] = code  # в dev возвращаем код, чтобы тестировать без SMS
    return resp


@app.post("/auth/verify")
def verify(body: VerifyIn, session: Session = Depends(get_session)):
    otp = session.exec(
        select(OtpCode).where(OtpCode.phone == body.phone).order_by(OtpCode.id.desc())
    ).first()
    if not otp or otp.code != body.code or otp.expires_at < datetime.utcnow():
        raise HTTPException(400, "Неверный или просроченный код")
    user = session.exec(select(User).where(User.phone == body.phone)).first()
    if not user:
        user = User(phone=body.phone, name=body.name or "Пользователь", verified=True)
        session.add(user)
        session.commit()
        session.refresh(user)
    return {"access_token": make_token(user.id), "token_type": "bearer", "user": user}


@app.get("/me")
def me(user: User = Depends(current_user)):
    return user


# ----------------------------- Поездки -----------------------------
class RideIn(BaseModel):
    from_city: str
    to_city: str
    depart_at: datetime
    seats_total: int = 3
    price: int = 0
    category: RideCategory = RideCategory.regular
    comment: str = ""


@app.post("/rides", response_model=Ride)
def create_ride(body: RideIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    ride = Ride(driver_id=user.id, seats_left=body.seats_total, **body.model_dump())
    session.add(ride)
    session.commit()
    session.refresh(ride)
    return ride


@app.get("/rides", response_model=List[Ride])
def search_rides(
    from_city: Optional[str] = None,
    to_city: Optional[str] = None,
    category: Optional[RideCategory] = None,
    session: Session = Depends(get_session),
):
    q = select(Ride).where(Ride.status == RideStatus.active)
    if from_city:
        q = q.where(Ride.from_city.contains(from_city))
    if to_city:
        q = q.where(Ride.to_city.contains(to_city))
    if category:
        q = q.where(Ride.category == category)
    return session.exec(q.order_by(Ride.depart_at)).all()


@app.get("/rides/{ride_id}", response_model=Ride)
def get_ride(ride_id: int, session: Session = Depends(get_session)):
    ride = session.get(Ride, ride_id)
    if not ride:
        raise HTTPException(404, "Поездка не найдена")
    return ride


# ----------------------------- Заявки -----------------------------
class RequestIn(BaseModel):
    from_city: str
    to_city: str
    desired_at: Optional[datetime] = None
    seats: int = 1
    max_price: Optional[int] = None
    category: RideCategory = RideCategory.regular
    with_kids: bool = False
    baggage: bool = False
    comment: str = ""
    for_relative_name: Optional[str] = None


@app.post("/requests", response_model=RideRequest)
def create_request(body: RequestIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    req = RideRequest(passenger_id=user.id, **body.model_dump())
    session.add(req)
    session.commit()
    session.refresh(req)
    return req


@app.get("/requests/mine", response_model=List[RideRequest])
def my_requests(user: User = Depends(current_user), session: Session = Depends(get_session)):
    return session.exec(select(RideRequest).where(RideRequest.passenger_id == user.id)).all()


# ----------------------------- Матчинг (см. backend.md §4) -----------------------------
@app.get("/match/rides", response_model=List[Ride])
def match_rides(request_id: int, session: Session = Depends(get_session)):
    req = session.get(RideRequest, request_id)
    if not req:
        raise HTTPException(404, "Заявка не найдена")
    q = select(Ride).where(
        Ride.status == RideStatus.active,
        Ride.from_city.contains(req.from_city),
        Ride.to_city.contains(req.to_city),
        Ride.seats_left >= req.seats,
        Ride.category == req.category,
    )
    return session.exec(q.order_by(Ride.depart_at)).all()


# ----------------------------- Брони -----------------------------
class BookIn(BaseModel):
    ride_id: int
    seats: int = 1


@app.post("/bookings", response_model=Booking)
def book(body: BookIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    ride = session.get(Ride, body.ride_id)
    if not ride or ride.status != RideStatus.active:
        raise HTTPException(400, "Поездка недоступна")
    if ride.seats_left < body.seats:
        raise HTTPException(400, "Не хватает мест")
    booking = Booking(
        ride_id=ride.id, passenger_id=user.id, seats=body.seats,
        price=ride.price * body.seats, boarding_code=gen_otp(),
    )
    ride.seats_left -= body.seats
    session.add(booking)
    session.add(ride)
    session.commit()
    session.refresh(booking)
    return booking


@app.post("/bookings/{booking_id}/confirm", response_model=Booking)
def confirm_booking(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    booking = session.get(Booking, booking_id)
    if not booking:
        raise HTTPException(404, "Бронь не найдена")
    booking.status = BookingStatus.confirmed
    session.add(booking)
    session.commit()
    session.refresh(booking)
    return booking


@app.get("/bookings/mine", response_model=List[Booking])
def my_bookings(user: User = Depends(current_user), session: Session = Depends(get_session)):
    return session.exec(select(Booking).where(Booking.passenger_id == user.id)).all()


# ----------------------------- Водитель -----------------------------
class OnlineIn(BaseModel):
    online: bool


@app.post("/driver/online", response_model=DriverProfile)
def driver_online(body: OnlineIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
    if not dp:
        dp = DriverProfile(user_id=user.id, online=body.online)
    else:
        dp.online = body.online
    session.add(dp)
    session.commit()
    session.refresh(dp)
    return dp
