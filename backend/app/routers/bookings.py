"""Брони: бронирование (с защитой от овербукинга и блокировок), подтверждение,
отмена, список своих, список броней водителя для оценки пассажиров."""
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlmodel import Session, select

from ..db import get_session
from ..models import Booking, BookingStatus, DriverProfile, Ride, RideStatus, User
from ..security import current_user, gen_otp
from ..services import booking_and_ride_for_user, geocode_city, is_blocked, notify_map_changed, push_notification, user_rating

router = APIRouter(tags=["bookings"])


def _ensure_route_coords(session: Session, ride: Ride) -> None:
    """Lazy-backfill для старых поездок: детали брони не должны показывать пустую карту.

    Новые поездки геокодятся при публикации, но на проде есть брони из старых версий.
    При первом открытии деталей добиваем координаты концов маршрута и сохраняем.
    """
    changed = False
    if ride.from_lat is None or ride.from_lng is None:
        frm = geocode_city(ride.from_city)
        if frm:
            ride.from_lat, ride.from_lng = frm
            changed = True
    if ride.to_lat is None or ride.to_lng is None:
        to = geocode_city(ride.to_city)
        if to:
            ride.to_lat, ride.to_lng = to
            changed = True
    if changed:
        session.add(ride)
        session.commit()
        session.refresh(ride)


class BookIn(BaseModel):
    ride_id: int
    seats: int = 1


class BookingDetailsOut(BaseModel):
    booking_id: int
    ride_id: int
    role: str
    status: str
    contact_unlocked: bool
    from_city: str
    to_city: str
    depart_at: str
    seats: int
    price: int
    driver_name: str
    driver_verified: bool
    driver_phone: str = ""
    driver_car: str = ""
    pickup: str = ""
    pickup_lat: Optional[float] = None
    pickup_lng: Optional[float] = None
    from_lat: Optional[float] = None
    from_lng: Optional[float] = None
    to_lat: Optional[float] = None
    to_lng: Optional[float] = None


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
    # Защита от дубля: один пассажир не бронирует одну поездку повторно (двойной тап / повторный заход).
    # Идемпотентно — возвращаем существующую активную бронь, мест не списываем заново.
    existing = session.exec(
        select(Booking).where(
            Booking.ride_id == ride.id,
            Booking.passenger_id == user.id,
            Booking.status.in_([BookingStatus.pending, BookingStatus.confirmed, BookingStatus.onboard]),
        )
    ).first()
    if existing:
        return existing
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
    notify_map_changed()   # места убыли → если 0, поездка уходит с карты live
    # Уведомление + push водителю о новой брони.
    pax_name = user.name or "Пассажир"
    route = f"{ride.from_city} → {ride.to_city}"
    push_notification(
        session, ride.driver_id, "booking",
        "Новая бронь", "Яңы бронь",
        f"{pax_name}: {route}, мест {body.seats}", f"{pax_name}: {route}, {body.seats} урын",
        ref_kind="booking", ref_id=booking.id,
    )
    return booking


@router.get("/bookings/{booking_id}/details", response_model=BookingDetailsOut)
def booking_details(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Приватные детали брони для экрана «Детали поездки».

    Публичная карточка поездки не должна раскрывать телефон и точную точку встречи.
    Здесь эти данные доступны только участникам брони и только после подтверждения
    водителем (confirmed/onboard/done).
    """
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    _ensure_route_coords(session, ride)
    driver = session.get(User, ride.driver_id)
    profile = session.exec(select(DriverProfile).where(DriverProfile.user_id == ride.driver_id)).first()
    driver_car = f"{profile.car_make} {profile.car_model}".strip() if profile else ""
    unlocked = booking.status in (BookingStatus.confirmed, BookingStatus.onboard, BookingStatus.done)
    return {
        "booking_id": booking.id,
        "ride_id": ride.id,
        "role": "driver" if ride.driver_id == user.id else "passenger",
        "status": booking.status.value if hasattr(booking.status, "value") else booking.status,
        "contact_unlocked": unlocked,
        "from_city": ride.from_city,
        "to_city": ride.to_city,
        "depart_at": ride.depart_at.isoformat() if ride.depart_at else "",
        "seats": booking.seats,
        "price": booking.price,
        "driver_name": (driver.name if driver and driver.name else "Водитель"),
        "driver_verified": bool(driver.verified) if driver else False,
        "driver_phone": (driver.phone if (unlocked and driver) else ""),
        "driver_car": driver_car,
        "pickup": (ride.pickup if unlocked else ""),
        "pickup_lat": (ride.pickup_lat if unlocked else None),
        "pickup_lng": (ride.pickup_lng if unlocked else None),
        # Координаты концов маршрута не раскрывают точную встречу: это город/маршрут,
        # нужен Android-клиенту для настоящей карты вместо мок-превью.
        "from_lat": ride.from_lat,
        "from_lng": ride.from_lng,
        "to_lat": ride.to_lat,
        "to_lng": ride.to_lng,
    }


@router.get("/bookings/{booking_id}/boarding-code")
def boarding_code(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Код посадки брони — виден ТОЛЬКО участникам (пассажир/водитель). Пассажир называет код,
    водитель сверяет → подтверждение «та самая машина/человек» (доверие «между своими»)."""
    booking, _ride = booking_and_ride_for_user(session, booking_id, user)
    return {"code": booking.boarding_code or ""}


@router.get("/bookings/{booking_id}/role")
def booking_role(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Роль текущего юзера в брони — водитель/пассажир. Экран активной поездки показывает
    нужные кнопки статуса (водитель: «выехал/подъезжаю»; пассажир: «сел/доехал/завершить»)."""
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    # + статус и подфаза → экран активной поездки опрашивает это и показывает пассажиру live-баннер
    # «водитель выехал/подъезжает» (раньше это приходило только пушем, в UI не обновлялось).
    return {
        "role": "driver" if ride.driver_id == user.id else "passenger",
        "status": booking.status,
        "driver_phase": booking.driver_phase,
    }


class DriverStatusIn(BaseModel):
    status: str  # departed | arriving | done


@router.post("/bookings/{booking_id}/driver-status")
def driver_status(booking_id: int, body: DriverStatusIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель отмечает «выехал/подъезжаю» → push пассажиру (закрывает тревогу ожидания).
    «done» — водитель завершает поездку: закрываем бронь (раньше закрыть мог ТОЛЬКО пассажир →
    если он забывал нажать «Завершить», бронь висела активной, а места поездки не освобождались)."""
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    if ride.driver_id != user.id:
        raise HTTPException(403, "Только водитель")
    if body.status not in {"departed", "arriving", "done"}:
        raise HTTPException(400, "Недопустимый статус")
    if body.status == "done":
        # Идемпотентно: уже завершённую/отменённую бронь не трогаем.
        if booking.status not in (BookingStatus.done, BookingStatus.cancelled):
            booking.status = BookingStatus.done
            booking.driver_phase = ""        # поездка кончилась — фазу сбрасываем
            session.add(booking)
            session.commit()
        route = f"{ride.from_city} → {ride.to_city}"
        push_notification(
            session, booking.passenger_id, "ride",
            "Поездка завершена", "Сәфәр тамамланды",
            route, route,
            ref_kind="booking", ref_id=booking.id,
        )
        return {"ok": True, "status": "done"}
    # «выехал/подъезжает» бессмысленны на мёртвой броне — иначе push «Водитель выехал» по отменённой/завершённой.
    if booking.status in (BookingStatus.cancelled, BookingStatus.done):
        raise HTTPException(409, "Поездка не активна")
    booking.driver_phase = body.status       # сохраняем «выехал/подъезжает» → пассажир увидит live, не только пушем
    session.add(booking)
    session.commit()
    title_ru = {"departed": "Водитель выехал", "arriving": "Водитель подъезжает"}[body.status]
    title_ba = {"departed": "Водитель юлға сыҡты", "arriving": "Водитель яҡынлаша"}[body.status]
    route = f"{ride.from_city} → {ride.to_city}"
    push_notification(
        session, booking.passenger_id, "ride",
        title_ru, title_ba,
        route, route,
        ref_kind="booking", ref_id=booking.id,
    )
    return {"ok": True, "driver_phase": body.status}


@router.post("/bookings/{booking_id}/confirm", response_model=Booking)
def confirm_booking(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    if ride.driver_id != user.id:
        raise HTTPException(403, "Подтвердить бронь может только водитель")
    if booking.status == BookingStatus.confirmed:
        return booking                       # идемпотентно (повторный тап) — без побочек
    # Подтверждать можно ТОЛЬКО ожидающую бронь: нельзя откатить onboard→confirmed или воскресить cancelled/done.
    if booking.status != BookingStatus.pending:
        raise HTTPException(400, "Эту бронь уже нельзя подтвердить")
    booking.status = BookingStatus.confirmed
    session.add(booking)
    session.commit()
    session.refresh(booking)
    # Уведомление пассажиру: бронь подтверждена водителем.
    route = f"{ride.from_city} → {ride.to_city}"
    push_notification(
        session, booking.passenger_id, "booking",
        "Бронь подтверждена", "Бронь раҫланды",
        route, route,
        ref_kind="booking", ref_id=booking.id,
    )
    return booking


@router.post("/bookings/{booking_id}/cancel", response_model=Booking)
def cancel_booking(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отмена поездки пассажиром или водителем. Места возвращаются в поездку."""
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    if booking.status not in (BookingStatus.cancelled, BookingStatus.done):
        # Блокируем строки брони и поездки → две одновременные отмены не вернут места ДВАЖДЫ.
        # Бронь перечитываем под локом и ПЕРЕПРОВЕРЯЕМ статус: первая отмена уже могла отработать.
        booking = session.exec(select(Booking).where(Booking.id == booking_id).with_for_update()).first()
        if booking.status in (BookingStatus.cancelled, BookingStatus.done):
            return booking                     # другая параллельная отмена опередила — места уже возвращены
        ride = session.exec(select(Ride).where(Ride.id == booking.ride_id).with_for_update()).first()
        booking.status = BookingStatus.cancelled
        ride.seats_left = min(ride.seats_total, ride.seats_left + booking.seats)  # вернуть освобождённые места
        session.add(booking)
        session.add(ride)
        session.commit()
        session.refresh(booking)
        notify_map_changed()   # места вернулись → поездка снова видна на карте live
        # Уведомление ДРУГОЙ стороне: кто не отменял (пассажир отменил → водителю, и наоборот).
        other_id = ride.driver_id if user.id == booking.passenger_id else booking.passenger_id
        route = f"{ride.from_city} → {ride.to_city}"
        push_notification(
            session, other_id, "booking",
            "Бронь отменена", "Бронь ҡалдырылды",
            route, route,
            ref_kind="booking", ref_id=booking.id,
        )
    return booking


@router.get("/bookings/mine")
def my_bookings(
    limit: Optional[int] = None,        # пагинация (опц., None = все — обратносовместимо)
    offset: int = 0,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    q = select(Booking).where(Booking.passenger_id == user.id).order_by(Booking.id.desc())
    if limit is not None:
        q = q.offset(max(0, offset)).limit(max(1, min(limit, 200)))
    bookings = session.exec(q).all()
    # Джойн сводки поездки (маршрут/водитель/время), чтобы экран «Мои поездки» показывал реальные
    # карточки, а не заглушку. Батч-выборка против N+1. Поле id сохранено → старый клиент не ломается.
    ride_ids = {b.ride_id for b in bookings}
    rides = (
        {r.id: r for r in session.exec(select(Ride).where(Ride.id.in_(ride_ids))).all()}
        if ride_ids else {}
    )
    driver_ids = {r.driver_id for r in rides.values()}
    drivers = (
        {u.id: u for u in session.exec(select(User).where(User.id.in_(driver_ids))).all()}
        if driver_ids else {}
    )
    out = []
    for b in bookings:
        r = rides.get(b.ride_id)
        drv = drivers.get(r.driver_id) if r else None
        out.append({
            "id": b.id,
            "ride_id": b.ride_id,
            "seats": b.seats,
            "price": b.price,
            "status": b.status.value if hasattr(b.status, "value") else b.status,
            "boarding_code": b.boarding_code,
            "from_city": r.from_city if r else "",
            "to_city": r.to_city if r else "",
            "depart_at": r.depart_at.isoformat() if r and r.depart_at else "",
            "driver_name": (drv.name if drv and drv.name else ""),
            "driver_verified": bool(drv.verified) if drv else False,
        })
    return out


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
