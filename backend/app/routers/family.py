"""Семейный контроль: доверенные контакты, шаринг поездки близкому,
статусы поездки (сел/доехал/завершил) с SMS-уведомлением, оценки после поездки."""
import re
import secrets
from typing import List

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..models import (
    Booking, BookingStatus, DriverProfile, InstantOrder, Rating, Ride, TripShare, TrustedContact, User,
)
from ..security import current_user
from ..services import booking_and_ride_for_user, send_text, user_rating

router = APIRouter(tags=["family"])

MAX_TRUSTED_CONTACTS = 10          # разумный потолок «своих» → анти-SMS-бомбинг (каждый SOS/статус шлёт SMS всем)
_PHONE_RE = re.compile(r"^\+?\d{10,15}$")   # телефон-получатель SMS: 10–15 цифр, опц. ведущий +


def _ensure_share_token(session: Session, share: TripShare) -> str:
    """Токен live-ссылки (B7c): ≥16 случайных байт. Строки до миграции w2_livelink живут
    с token=NULL — догенерируем при первом обращении (лениво, без бэкфилла)."""
    if not share.token:
        share.token = secrets.token_urlsafe(16)
        session.add(share)
        session.commit()
        session.refresh(share)
    return share.token


def _live_link(token: str) -> str:
    return f"{settings.public_base_url.rstrip('/')}/t/{token}"


class ContactIn(BaseModel):
    name: str = Field(..., max_length=120)
    relation: str = Field("", max_length=120)
    phone: str = Field("", max_length=32)
    notify_by_default: bool = True


@router.post("/trusted-contacts", response_model=TrustedContact)
def add_contact(body: ContactIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    # Телефон доверенного — это адрес SMS за счёт платформы. Валидируем формат и держим потолок,
    # иначе через сотни «контактов» с чужими номерами можно устроить SMS-бомбинг (SOS/трип-статус шлют всем).
    phone = (body.phone or "").strip()
    if phone and not _PHONE_RE.match(phone.replace(" ", "").replace("-", "")):
        raise HTTPException(400, "Неверный номер телефона")
    count = len(session.exec(select(TrustedContact).where(TrustedContact.user_id == user.id)).all())
    if count >= MAX_TRUSTED_CONTACTS:
        raise HTTPException(400, f"Больше {MAX_TRUSTED_CONTACTS} доверенных контактов не добавить")
    data = body.model_dump()
    data["phone"] = phone
    contact = TrustedContact(user_id=user.id, **data)
    session.add(contact)
    session.commit()
    session.refresh(contact)
    return contact


@router.get("/trusted-contacts", response_model=List[TrustedContact])
def list_contacts(user: User = Depends(current_user), session: Session = Depends(get_session)):
    return session.exec(select(TrustedContact).where(TrustedContact.user_id == user.id)).all()


class ShareIn(BaseModel):
    contact_id: int


@router.post("/bookings/{booking_id}/share", response_model=TripShare)
def share_trip(booking_id: int, body: ShareIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    booking, _ = booking_and_ride_for_user(session, booking_id, user)
    if booking.passenger_id != user.id:
        raise HTTPException(403, "Расшарить поездку может только пассажир")
    contact = session.get(TrustedContact, body.contact_id)
    if not contact or contact.user_id != user.id:
        raise HTTPException(404, "Контакт не найден")
    # QA-DEDUP-SHARETRIP: повторный share тем же контактом не плодит дубли (иначе дубли SMS-статусов).
    existing = session.exec(
        select(TripShare).where(TripShare.booking_id == booking_id, TripShare.contact_id == body.contact_id)
    ).first()
    if existing:
        _ensure_share_token(session, existing)   # строка до w2_livelink → догенерировать токен
        return existing
    share = TripShare(booking_id=booking_id, contact_id=body.contact_id, token=secrets.token_urlsafe(16))
    session.add(share)
    session.commit()
    session.refresh(share)
    # Близкий сразу получает live-ссылку (B7c): живая карта поездки в браузере, без приложения.
    if contact.phone:
        who = user.name or user.phone
        send_text(contact.phone, f"Юлдаш: {who} едет с попутчиком. Следи за поездкой: {_live_link(share.token)}")
    return share


# ---- Шаринг такси-заказа близкому (B7b-2) ----
@router.post("/instant/orders/{order_id}/share", response_model=TripShare)
def share_instant_trip(order_id: int, body: ShareIn, user: User = Depends(current_user),
                       session: Session = Depends(get_session)):
    """«Поделиться поездкой» из такси-заказа — по образцу booking-шаринга: только пассажир,
    только свой контакт, дедуп. Близкий сразу получает SMS с маршрутом; дальше статусы
    (сел/доехал/отмена) шлёт сервер сам на переходах заказа (см. instant_service)."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise HTTPException(404, "Заказ не найден")
    if order.passenger_id != user.id:
        raise HTTPException(403, "Расшарить поездку может только пассажир")
    contact = session.get(TrustedContact, body.contact_id)
    if not contact or contact.user_id != user.id:
        raise HTTPException(404, "Контакт не найден")
    existing = session.exec(
        select(TripShare).where(TripShare.order_id == order_id, TripShare.contact_id == body.contact_id)
    ).first()
    if existing:
        _ensure_share_token(session, existing)   # строка до w2_livelink → догенерировать токен
        return existing   # дедуп: повторный share тем же контактом не плодит дубли SMS
    share = TripShare(order_id=order_id, contact_id=body.contact_id, token=secrets.token_urlsafe(16))
    session.add(share)
    session.commit()
    session.refresh(share)
    # Близкий сразу в курсе: кто едет и куда (телефон водителя не шлём — минимум перс.данных).
    # Live-ссылка (B7c): живая карта поездки в браузере, без приложения.
    if contact.phone:
        who = user.name or user.phone
        route = f"{order.from_text or 'точка А'} → {order.to_text or 'точка Б'}"
        send_text(contact.phone, f"Юлдаш: {who} едет на такси ({route}). Следи за поездкой: {_live_link(share.token)}")
    return share


@router.get("/instant/orders/{order_id}/shares", response_model=List[TripShare])
def list_instant_shares(order_id: int, user: User = Depends(current_user),
                        session: Session = Depends(get_session)):
    """Мои шаринги этого заказа (пассажиру — показать «уже поделился с …»)."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise HTTPException(404, "Заказ не найден")
    if order.passenger_id != user.id:
        raise HTTPException(403, "Доступно только пассажиру заказа")
    contact_ids = [c.id for c in session.exec(select(TrustedContact).where(TrustedContact.user_id == user.id)).all()]
    if not contact_ids:
        return []
    return session.exec(
        select(TripShare).where(TripShare.order_id == order_id, TripShare.contact_id.in_(contact_ids))
    ).all()


def _revoke_share(session: Session, share_id: int, user: User, *, booking_id: int = None, order_id: int = None):
    """Отозвать шаринг (B7c): строка удаляется → live-токен «сгорает» (/t/{token} → 404),
    SMS-статусы этому контакту прекращаются. Только пассажир и только свой контакт."""
    share = session.get(TripShare, share_id)
    if not share or (booking_id is not None and share.booking_id != booking_id) \
            or (order_id is not None and share.order_id != order_id):
        raise HTTPException(404, "Шаринг не найден")
    contact = session.get(TrustedContact, share.contact_id)
    if not contact or contact.user_id != user.id:
        raise HTTPException(403, "Отозвать может только владелец шаринга")
    session.delete(share)
    session.commit()
    return {"ok": True}


@router.delete("/instant/orders/{order_id}/share/{share_id}")
def revoke_instant_share(order_id: int, share_id: int, user: User = Depends(current_user),
                         session: Session = Depends(get_session)):
    order = session.get(InstantOrder, order_id)
    if not order:
        raise HTTPException(404, "Заказ не найден")
    if order.passenger_id != user.id:
        raise HTTPException(403, "Доступно только пассажиру заказа")
    return _revoke_share(session, share_id, user, order_id=order_id)


@router.delete("/bookings/{booking_id}/share/{share_id}")
def revoke_booking_share(booking_id: int, share_id: int, user: User = Depends(current_user),
                         session: Session = Depends(get_session)):
    booking, _ = booking_and_ride_for_user(session, booking_id, user)
    if booking.passenger_id != user.id:
        raise HTTPException(403, "Доступно только пассажиру брони")
    return _revoke_share(session, share_id, user, booking_id=booking_id)


class TripStatusIn(BaseModel):
    status: str  # sat / arrived / done


@router.post("/bookings/{booking_id}/trip-status", response_model=List[TripShare])
def set_trip_status(booking_id: int, body: TripStatusIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    booking, _ = booking_and_ride_for_user(session, booking_id, user)
    if booking.passenger_id != user.id:
        raise HTTPException(403, "Статус семейного контроля меняет только пассажир")
    if body.status not in {"sat", "arrived", "done"}:
        raise HTTPException(400, "Недопустимый статус поездки")
    # «Завершил поездку» → реально закрываем бронь на сервере (раньше статус уходил только близким,
    # а бронь висела активной). Идемпотентно: повторный done/отменённую не трогаем.
    if body.status == "done" and booking.status not in (BookingStatus.done, BookingStatus.cancelled):
        booking.status = BookingStatus.done
        session.add(booking)
        session.commit()
    contact_ids = [c.id for c in session.exec(select(TrustedContact).where(TrustedContact.user_id == user.id)).all()]
    if not contact_ids:
        return []
    shares = session.exec(select(TripShare).where(TripShare.booking_id == booking_id, TripShare.contact_id.in_(contact_ids))).all()
    # SMS шлём ТОЛЬКО тем, у кого статус реально сменился — иначе повторный вызов того же
    # статуса (sat→sat) = бесконечные SMS за счёт платформы (у SOS троттл есть, тут не было).
    changed_ids = [s.contact_id for s in shares if s.last_status != body.status]
    for share in shares:
        share.last_status = body.status
        session.add(share)
    session.commit()
    for share in shares:
        session.refresh(share)  # после commit объекты «обнуляются» — перечитываем
    # Реально уведомляем близких по SMS о статусе поездки.
    status_text = {"sat": "сел в машину", "arrived": "доехал до места", "done": "завершил поездку"}.get(body.status, body.status)
    who = user.name or user.phone
    for cid in changed_ids:
        c = session.get(TrustedContact, cid)
        if c and c.phone:
            send_text(c.phone, f"Юлдаш: {who} {status_text}.")
    return shares


class RateIn(BaseModel):
    stars: int


@router.post("/bookings/{booking_id}/rate")
def rate_booking(booking_id: int, body: RateIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Оценить вторую сторону поездки (1..5). Пассажир оценивает водителя, водитель — пассажира. Одна оценка на бронь от каждого."""
    b = session.get(Booking, booking_id)
    if not b:
        raise HTTPException(status_code=404, detail="Бронь не найдена")
    ride = session.get(Ride, b.ride_id)
    if user.id == b.passenger_id and ride:
        ratee_id = ride.driver_id          # пассажир → водитель
    elif ride and user.id == ride.driver_id:
        ratee_id = b.passenger_id          # водитель → пассажир
    else:
        raise HTTPException(status_code=403, detail="Нельзя оценить эту поездку")
    # Оценить можно только ЗАВЕРШЁННУЮ поездку — иначе можно забронировать и сразу накрутить
    # рейтинг водителю, не съездив (репутация «между своими» = продукт). Проверка ПОСЛЕ участника:
    # чужой получает 403, а участник недозавершённой — 409.
    if b.status != BookingStatus.done:
        raise HTTPException(status_code=409, detail="Оценить можно только завершённую поездку")
    stars = max(1, min(5, body.stars))
    existing = session.exec(
        select(Rating).where(Rating.booking_id == booking_id, Rating.rater_id == user.id)
    ).first()
    if existing:
        existing.stars = stars
        session.add(existing)
    else:
        session.add(Rating(booking_id=booking_id, rater_id=user.id, ratee_id=ratee_id, stars=stars))
    session.commit()
    avg, cnt = user_rating(session, ratee_id)
    # Оценили водителя → обновим витринный рейтинг в профиле.
    prof = session.exec(select(DriverProfile).where(DriverProfile.user_id == ratee_id)).first()
    if prof and cnt > 0:
        prof.rating = round(avg, 1)
        session.add(prof)
        session.commit()
    # 🟡 Лестница качества (§9): рейтинг просел → мягкий пуш-совет (дедуп 1/нед).
    if cnt > 0:
        from .. import quality
        quality.maybe_low_rating_advice(session, ratee_id, avg)
    return {"ratee_id": ratee_id, "rating": round(avg, 1), "count": cnt}
