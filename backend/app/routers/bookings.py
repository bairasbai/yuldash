"""Брони: бронирование (с защитой от овербукинга и блокировок), подтверждение,
отмена, список своих, список броней водителя для оценки пассажиров."""
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlmodel import Session, select

from ..db import get_session
from ..models import Booking, BookingStatus, Ride, RideStatus, User
from ..security import current_user, gen_otp
from ..services import booking_and_ride_for_user, is_blocked, send_push, user_rating

router = APIRouter(tags=["bookings"])


class BookIn(BaseModel):
    ride_id: int
    seats: int = 1


@router.post("/bookings", response_model=Booking)
def book(body: BookIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    if body.seats < 1:
        raise HTTPException(400, "Количество мест должно быть больше 0")
    # FOR UPDATE: блокируем строку поездки на время транзакции → нет овербукинга при гонке.
    ride = session.exec(select(Ride).where(Ride.id == body.ride_id).with_for_update()).first()
    if not ride or ride.status != RideStatus.active:
        raise HTTPException(400, "Поездка недоступна")
    if ride.driver_id == user.id:
        raise HTTPException(400, "Нельзя бронировать собственную поездку")
    if is_blocked(session, user.id, ride.driver_id):
        raise HTTPException(403, "Бронь недоступна")
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
    # Push водителю о новой брони.
    send_push(session, ride.driver_id, "Новая бронь", f"{user.name or 'Пассажир'}: {ride.from_city} → {ride.to_city}, мест {body.seats}")
    return booking


@router.get("/bookings/{booking_id}/boarding-code")
def boarding_code(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Код посадки брони — виден ТОЛЬКО участникам (пассажир/водитель). Пассажир называет код,
    водитель сверяет → подтверждение «та самая машина/человек» (доверие «между своими»)."""
    booking, _ride = booking_and_ride_for_user(session, booking_id, user)
    return {"code": booking.boarding_code or ""}


@router.post("/bookings/{booking_id}/confirm", response_model=Booking)
def confirm_booking(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    if ride.driver_id != user.id:
        raise HTTPException(403, "Подтвердить бронь может только водитель")
    booking.status = BookingStatus.confirmed
    session.add(booking)
    session.commit()
    session.refresh(booking)
    return booking


@router.post("/bookings/{booking_id}/cancel", response_model=Booking)
def cancel_booking(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отмена поездки пассажиром или водителем. Места возвращаются в поездку."""
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    if booking.status not in (BookingStatus.cancelled, BookingStatus.done):
        # Блокируем строку поездки (как в book) → две одновременные отмены не затрут инкремент мест.
        ride = session.exec(select(Ride).where(Ride.id == booking.ride_id).with_for_update()).first()
        booking.status = BookingStatus.cancelled
        ride.seats_left = min(ride.seats_total, ride.seats_left + booking.seats)  # вернуть освобождённые места
        session.add(booking)
        session.add(ride)
        session.commit()
        session.refresh(booking)
    return booking


@router.get("/bookings/mine", response_model=List[Booking])
def my_bookings(
    limit: Optional[int] = None,        # пагинация (опц., None = все — обратносовместимо)
    offset: int = 0,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    q = select(Booking).where(Booking.passenger_id == user.id)
    if limit is not None:   # порядок добавляем только при пагинации (дефолт — как было)
        q = q.order_by(Booking.id.desc()).offset(max(0, offset)).limit(max(1, min(limit, 200)))
    return session.exec(q).all()


@router.get("/driver/bookings")
def driver_bookings(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Брони на поездки текущего водителя — чтобы оценить пассажиров после поездки."""
    my_rides = session.exec(select(Ride).where(Ride.driver_id == user.id)).all()
    if not my_rides:
        return []
    rides_by_id = {r.id: r for r in my_rides}
    bookings = session.exec(select(Booking).where(Booking.ride_id.in_(list(rides_by_id)))).all()
    # Пассажиры — одним запросом пачкой (анти-N+1), вместо session.get в цикле.
    passenger_ids = {b.passenger_id for b in bookings}
    passengers_by_id = {
        u.id: u for u in session.exec(select(User).where(User.id.in_(passenger_ids))).all()
    } if passenger_ids else {}
    out: list = []
    for b in bookings:
        ride = rides_by_id.get(b.ride_id)
        passenger = passengers_by_id.get(b.passenger_id)
        avg, cnt = user_rating(session, b.passenger_id)
        out.append({
            "booking_id": b.id,
            "passenger_name": (passenger.name if passenger else "Пассажир"),
            "passenger_rating": (round(avg, 1) if cnt > 0 else None),
            "route": (f"{ride.from_city} → {ride.to_city}" if ride else ""),
            "status": b.status,
        })
    return out
