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
from .db import engine, get_session, init_db
from .models import (
    Block, Booking, BookingStatus, DriverProfile, Message, OtpCode, Report,
    Ride, RideCategory, RideRequest, RideStatus, SosEvent, TripShare,
    TrustedContact, User, UserRole,
)
from .security import current_user, gen_otp, make_token


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    with Session(engine) as session:
        _seed_demo(session)
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


def _send_sms(phone: str, code: str) -> None:
    """Отправка OTP. `smsru` — реально через sms.ru; иначе мок (код в лог).
    Если sms.ru НЕ отправил (напр. нет одобренного отправителя) — код падает в лог,
    чтобы вход работал на период настройки отправителя."""
    if settings.sms_provider == "smsru" and settings.sms_ru_api_id:
        try:
            import httpx
            params = {"api_id": settings.sms_ru_api_id, "to": phone, "msg": f"Yuldash: kod {code}", "json": 1}
            if settings.sms_from:
                params["from"] = settings.sms_from
            data = httpx.get("https://sms.ru/sms/send", params=params, timeout=10).json()
            sms = (data.get("sms") or {}).get(phone, {})
            ok = sms.get("status_code") == 100
            print(f"[SMS] {phone}: smsru sent={ok} ({sms.get('status_code')} {str(sms.get('status_text', ''))[:80]})")
            if not ok:
                print(f"[OTP] {phone} -> {code}")  # фоллбэк: SMS не ушла → код в лог
        except Exception as e:  # noqa: BLE001
            print(f"[SMS] {phone}: smsru error {e}")
            print(f"[OTP] {phone} -> {code}")  # фоллбэк при ошибке сети
    else:
        print(f"[OTP] {phone} -> {code}")  # мок/dev — код в логе


@app.post("/auth/request-code")
def request_code(body: PhoneIn, session: Session = Depends(get_session)):
    code = gen_otp()
    session.add(OtpCode(
        phone=body.phone, code=code,
        expires_at=datetime.utcnow() + timedelta(seconds=settings.otp_ttl_sec),
    ))
    session.commit()
    _send_sms(body.phone, code)
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


class RideOut(BaseModel):
    """Поездка + витрина водителя (имя/рейтинг/авто) — чтобы приложение рисовало карточку."""
    id: int
    driver_id: int
    from_city: str
    to_city: str
    depart_at: datetime
    seats_total: int
    seats_left: int
    price: int
    category: RideCategory
    comment: str
    status: RideStatus
    driver_name: str
    driver_rating: float
    driver_verified: bool
    driver_car: str


def _ride_out(ride: Ride, session: Session) -> RideOut:
    drv = session.get(User, ride.driver_id)
    prof = session.exec(
        select(DriverProfile).where(DriverProfile.user_id == ride.driver_id)
    ).first()
    car = f"{prof.car_make} {prof.car_model}".strip() if prof else ""
    return RideOut(
        **ride.model_dump(exclude={"created_at"}),
        driver_name=(drv.name if drv else "Водитель"),
        driver_rating=(prof.rating if prof else 5.0),
        driver_verified=(drv.verified if drv else False),
        driver_car=car,
    )


def _seed_demo(session: Session) -> None:
    """Демо-поездки в пустой БД — чтобы экран «Ближайшие поездки» был живым."""
    if session.exec(select(Ride)).first():
        return
    demo = [
        ("Ильдар", 4.8, True, "Lada", "Vesta", "Баймаҡ", "Сибай", 350, 3),
        ("Айгуль", 4.9, True, "Kia", "Rio", "Темясово", "Уфа", 1400, 3),
        ("Рустам", 4.6, False, "Renault", "Logan", "Сибай", "Баймаҡ", 300, 2),
        ("Гүзәл", 5.0, True, "Hyundai", "Solaris", "Учалы", "Магнитогорск", 800, 4),
    ]
    base = datetime.utcnow() + timedelta(hours=3)
    for i, (name, rating, verified, make, model, frm, to, price, seats) in enumerate(demo):
        u = User(phone=f"+7000000000{i}", name=name, role=UserRole.driver, verified=verified)
        session.add(u)
        session.commit()
        session.refresh(u)
        session.add(DriverProfile(
            user_id=u.id, rating=rating, trips_count=42,
            car_make=make, car_model=model, seats=seats,
        ))
        session.add(Ride(
            driver_id=u.id, from_city=frm, to_city=to,
            depart_at=base + timedelta(hours=i * 6),
            seats_total=seats, seats_left=seats, price=price,
            category=RideCategory.regular,
        ))
    session.commit()


@app.post("/rides", response_model=Ride)
def create_ride(body: RideIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    ride = Ride(driver_id=user.id, seats_left=body.seats_total, **body.model_dump())
    session.add(ride)
    session.commit()
    session.refresh(ride)
    return ride


@app.get("/rides", response_model=List[RideOut])
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
    rides = session.exec(q.order_by(Ride.depart_at)).all()
    return [_ride_out(r, session) for r in rides]


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


# ----------------------------- Чат (сообщения по брони) -----------------------------
class MessageIn(BaseModel):
    text: str = ""
    voice_url: Optional[str] = None
    transcript: Optional[str] = None


@app.post("/bookings/{booking_id}/messages", response_model=Message)
def send_message(booking_id: int, body: MessageIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    if not session.get(Booking, booking_id):
        raise HTTPException(404, "Бронь не найдена")
    msg = Message(booking_id=booking_id, sender_id=user.id, **body.model_dump())
    session.add(msg)
    session.commit()
    session.refresh(msg)
    return msg


@app.get("/bookings/{booking_id}/messages", response_model=List[Message])
def list_messages(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    return session.exec(select(Message).where(Message.booking_id == booking_id).order_by(Message.id)).all()


# ----------------------------- Семейный контроль -----------------------------
class ContactIn(BaseModel):
    name: str
    relation: str = ""
    phone: str = ""
    notify_by_default: bool = True


@app.post("/trusted-contacts", response_model=TrustedContact)
def add_contact(body: ContactIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    contact = TrustedContact(user_id=user.id, **body.model_dump())
    session.add(contact)
    session.commit()
    session.refresh(contact)
    return contact


@app.get("/trusted-contacts", response_model=List[TrustedContact])
def list_contacts(user: User = Depends(current_user), session: Session = Depends(get_session)):
    return session.exec(select(TrustedContact).where(TrustedContact.user_id == user.id)).all()


class ShareIn(BaseModel):
    contact_id: int


@app.post("/bookings/{booking_id}/share", response_model=TripShare)
def share_trip(booking_id: int, body: ShareIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    if not session.get(Booking, booking_id):
        raise HTTPException(404, "Бронь не найдена")
    share = TripShare(booking_id=booking_id, contact_id=body.contact_id)
    session.add(share)
    session.commit()
    session.refresh(share)
    return share


class TripStatusIn(BaseModel):
    status: str  # sat / arrived / done


@app.post("/bookings/{booking_id}/trip-status", response_model=List[TripShare])
def set_trip_status(booking_id: int, body: TripStatusIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    shares = session.exec(select(TripShare).where(TripShare.booking_id == booking_id)).all()
    for share in shares:
        share.last_status = body.status
        session.add(share)
    session.commit()
    for share in shares:
        session.refresh(share)  # после commit объекты «обнуляются» — перечитываем
    # TODO: тут — push/SMS близким («сел», «доехал»)
    return shares


# ----------------------------- Безопасность -----------------------------
class SosIn(BaseModel):
    category: str = "other"      # medical / breakdown / other
    booking_id: Optional[int] = None
    note: str = ""


@app.post("/sos", response_model=SosEvent)
def sos(body: SosIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    event = SosEvent(user_id=user.id, **body.model_dump())
    session.add(event)
    session.commit()
    session.refresh(event)
    # TODO: уведомить экстренные службы/поддержку/доверенные контакты
    return event


class ReportIn(BaseModel):
    target_user_id: int
    reason: str = ""


@app.post("/reports", response_model=Report)
def create_report(body: ReportIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    report = Report(reporter_id=user.id, **body.model_dump())
    session.add(report)
    session.commit()
    session.refresh(report)
    return report


class BlockIn(BaseModel):
    blocked_user_id: int


@app.post("/blocks", response_model=Block)
def create_block(body: BlockIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    block = Block(user_id=user.id, **body.model_dump())
    session.add(block)
    session.commit()
    session.refresh(block)
    return block
