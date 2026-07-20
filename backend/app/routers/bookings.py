"""Брони: бронирование (с защитой от овербукинга и блокировок), подтверждение,
отмена, список своих, список броней водителя для оценки пассажиров.

Плюс хуки системы «Справедливость»: причина отмены (+флаг поздней), неявка,
отметка оплаты наличными, фото посылки. См. docs/trust-safety.md §6."""
from datetime import timedelta
from typing import Optional

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..db import get_session
from ..models import Booking, BookingStatus, DriverProfile, Incident, Ride, RideStatus, User
from ..safety_logic import CANCEL_REASONS, clamp, detect_bump, ensure_active, is_late_cancel, is_own_media_url
from ..security import current_user, gen_otp
from ..services import (
    booking_and_ride_for_user, geocode_city, is_blocked, notify_admin_telegram,
    notify_map_changed, send_push, user_rating,
)
from ..timeutil import utcnow
from .incidents import _incident_out, create_incident

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
    ensure_active(session, user.id)   # приостановленный аккаунт не бронирует (лестница §2)
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
    # Push водителю о новой брони.
    send_push(session, ride.driver_id, "Новая бронь", f"{user.name or 'Пассажир'}: {ride.from_city} → {ride.to_city}, мест {body.seats}")
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
        send_push(session, booking.passenger_id, "Поездка завершена", f"{ride.from_city} → {ride.to_city}")
        return {"ok": True, "status": "done"}
    # «выехал/подъезжает» бессмысленны на мёртвой броне — иначе push «Водитель выехал» по отменённой/завершённой.
    if booking.status in (BookingStatus.cancelled, BookingStatus.done):
        raise HTTPException(409, "Поездка не активна")
    booking.driver_phase = body.status       # сохраняем «выехал/подъезжает» → пассажир увидит live, не только пушем
    session.add(booking)
    session.commit()
    title = {"departed": "Водитель выехал", "arriving": "Водитель подъезжает"}[body.status]
    send_push(session, booking.passenger_id, title, f"{ride.from_city} → {ride.to_city}")
    return {"ok": True, "driver_phase": body.status}


@router.post("/bookings/{booking_id}/confirm", response_model=Booking)
def confirm_booking(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    ensure_active(session, user.id)   # приостановленный водитель не подтверждает брони
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
    return booking


class CancelIn(BaseModel):
    reason: Optional[str] = None   # plans_changed/found_other/price/driver_late/passenger_late/emergency/safety/other
    note: Optional[str] = Field(None, max_length=500)


@router.post("/bookings/{booking_id}/cancel", response_model=None)
def cancel_booking(booking_id: int, background: BackgroundTasks, body: Optional[CancelIn] = None,
                   user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отмена поездки пассажиром или водителем. Места возвращаются в поездку.

    Опц. тело `{reason, note}` записывает причину (§5.3). Возвращает `late:true`,
    если отмена поздняя. Без тела — как раньше. Если отменяет ВОДИТЕЛЬ по подтверждённой
    брони — детект «бампинга» (§1.1): тяжёлый вес в Надёжности + авто-инцидент при сигналах."""
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    late = False
    # Захватываем ДО мутации: кто отменяет и была ли бронь «подтверждённой договорённостью».
    is_driver_cancel = ride.driver_id == user.id
    was_committed = booking.status in (BookingStatus.confirmed, BookingStatus.onboard)
    if booking.status not in (BookingStatus.cancelled, BookingStatus.done):
        # Блокируем строку поездки (как в book) → две одновременные отмены сериализуются на локе.
        ride = session.exec(select(Ride).where(Ride.id == booking.ride_id).with_for_update()).first()
        # ПЕРЕЧИТЫВАЕМ бронь под этим локом: пока ждали лок, первая отмена уже могла пометить
        # cancelled → без перечитки второй запрос вернул бы места ПОВТОРНО (двойной возврат/овербукинг)
        # и создал бы второй авто-инцидент бампинга. Стало cancelled/done → пропускаем мутацию.
        session.refresh(booking)
    if booking.status not in (BookingStatus.cancelled, BookingStatus.done):
        late = is_late_cancel(booking, ride, when=utcnow())   # считаем ДО пометки cancelled
        booking.status = BookingStatus.cancelled
        booking.cancelled_by = user.id
        booking.cancelled_at = utcnow()
        if body is not None:
            reason = (body.reason or "").strip()
            if reason in CANCEL_REASONS:
                booking.cancel_reason = reason
            booking.cancel_note = clamp(body.note, 500)
        # Форс-мажор защищён от штрафа, НО не безлимитно: серийный самозаявленный «emergency» —
        # тихий обход наказания (бампер уходит без минуса Надёжности). Считаем ПРОШЛЫЕ emergency
        # этого юзера за окно; сверх лимита щит снимается (late/no_show как обычно) + сигнал админу.
        emergency_shielded = False
        if booking.cancel_reason == "emergency":
            # Строго ПРОШЛЫЕ emergency этого юзера (исключаем текущую бронь: autoflush иначе
            # засчитал бы её саму — она уже помечена emergency в этой транзакции).
            prior_emergencies = len(session.exec(
                select(Booking.id).where(
                    Booking.id != booking.id,
                    Booking.cancelled_by == user.id,
                    Booking.cancel_reason == "emergency",
                    Booking.cancelled_at >= utcnow() - timedelta(days=_EMERGENCY_WINDOW_DAYS),
                ).limit(_EMERGENCY_FREE_MAX + 1)
            ).all())
            emergency_shielded = prior_emergencies < _EMERGENCY_FREE_MAX
            if emergency_shielded:
                late = False   # щит от несправедливого штрафа (§1 п.10)
            elif background is not None:
                background.add_task(
                    notify_admin_telegram,
                    f"🚩 Частые «emergency»-отмены (Юлдаш): пользователь #{user.id}, "
                    f"{prior_emergencies}+ за {_EMERGENCY_WINDOW_DAYS}д — возможен обход штрафа.",
                )
        # Водитель бросил ПОДТВЕРЖДЁННУЮ бронь → тяжёлый минус Надёжности (§1.1, Рычаг 3);
        # защищённый (в лимите) emergency освобождаем, сверх лимита — наказываем как обычно.
        if is_driver_cancel and was_committed and not emergency_shielded:
            booking.no_show = True
        ride.seats_left = min(ride.seats_total, ride.seats_left + booking.seats)  # вернуть освобождённые места
        session.add(booking)
        session.add(ride)
        session.commit()
        session.refresh(booking)
        notify_map_changed()   # места вернулись → поездка снова видна на карте live
        # Детект фиктивной «не еду»: только когда водитель сбросил подтверждённого пассажира.
        if is_driver_cancel and was_committed:
            _handle_driver_bump(session, ride, booking, background)
    data = booking.model_dump(mode="json")
    data["late"] = late
    return data


def _handle_driver_bump(session: Session, ride: Ride, booking: Booking, background: BackgroundTasks) -> None:
    """§1.1 Рычаг 2: при сигналах подмены — авто-инцидент driver_no_show(suspected_bump),
    уведомление админа и пуш пассажиру с альтернативами. Реальный форс-мажор без сигналов — тихо."""
    signals = detect_bump(session, ride, booking, booking.cancel_reason)
    if not signals:
        return
    passenger = session.get(User, booking.passenger_id)
    if not passenger:
        return
    create_incident(
        session, reporter=passenger, respondent_id=ride.driver_id, type="driver_no_show",
        description="", booking_id=booking.id, reporter_role="passenger",
        background=background, rate_limit=False, suspected_bump=True,
    )
    background.add_task(
        notify_admin_telegram,
        f"🚩 Подозрение на «бампинг» (Юлдаш)\n"
        f"Поездка: {ride.from_city}→{ride.to_city}\n"
        f"Водитель отменил подтверждённую бронь #{booking.id}\n"
        f"Причина: {booking.cancel_reason or '—'}\n"
        f"Сигналы: {', '.join(signals)}"
    )
    # Пассажиру — тёплый пуш с обещанием альтернатив (клиент откроет «Ближайшие» на его маршрут).
    send_push(session, booking.passenger_id, "Водитель отменил поездку",
              "Не переживай — подобрали альтернативы на твой маршрут. Открой приложение.")


class NoShowIn(BaseModel):
    note: Optional[str] = Field(None, max_length=2000)


# Насколько раньше времени выезда уже можно отметить неявку (не наказываем за минуты до).
_NO_SHOW_EARLY_MIN = 15

# Форс-мажор бесплатен, но не безлимитно: сколько «emergency»-отмен за окно ещё защищены от
# штрафа (сверх — щит снимается, чтобы серийный бампер не уходил тихо). Позже — в конфиг.
_EMERGENCY_FREE_MAX = 3
_EMERGENCY_WINDOW_DAYS = 30


@router.post("/bookings/{booking_id}/no-show", response_model=None)
def report_no_show(booking_id: int, background: BackgroundTasks, body: Optional[NoShowIn] = None,
                   user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Неявка второй стороны. Водитель → passenger_no_show, пассажир → driver_no_show.
    Гейт: бронь confirmed/onboard, не раньше времени выезда − окно, одна на бронь."""
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    if booking.status not in (BookingStatus.confirmed, BookingStatus.onboard):
        raise HTTPException(409, "Неявку можно отметить только по подтверждённой поездке")
    if ride.depart_at and utcnow() < ride.depart_at - timedelta(minutes=_NO_SHOW_EARLY_MIN):
        raise HTTPException(409, "Ещё рано отмечать неявку")
    # Лочим бронь: две одновременные отметки (обе стороны разом / двойной тап) иначе обе проходят
    # гейт `no_show` до коммита → два инцидента (гонка). Перечитываем статус под локом.
    locked = session.exec(select(Booking).where(Booking.id == booking.id).with_for_update()).first()
    if locked is not None:
        booking = locked
    if booking.no_show:
        raise HTTPException(409, "Неявка уже отмечена")
    if ride.driver_id == user.id:
        inc_type, respondent_id = "passenger_no_show", booking.passenger_id
    else:
        inc_type, respondent_id = "driver_no_show", ride.driver_id
    booking.no_show = True
    session.add(booking)
    # НЕ коммитим флаг отдельно: create_incident коммитит сам → флаг+инцидент атомарно
    # (падение до его коммита не оставит no_show без инцидента — частичный коммит закрыт).
    inc = create_incident(
        session, reporter=user, respondent_id=respondent_id, type=inc_type,
        description=clamp(body.note if body else "", 2000), booking_id=booking_id, background=background,
    )
    return _incident_out(session, inc, user)


class PaymentIn(BaseModel):
    received: bool
    note: Optional[str] = Field(None, max_length=2000)


@router.post("/bookings/{booking_id}/payment", response_model=None)
def mark_payment(booking_id: int, body: PaymentIn, background: BackgroundTasks,
                 user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель отмечает получение наличных. received=false → payment_state=unpaid +
    мягкий инцидент non_payment (не списываем — наличные «на доверии», §1 п.3)."""
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    if ride.driver_id != user.id:
        raise HTTPException(403, "Оплату отмечает только водитель")
    if booking.status != BookingStatus.done:
        raise HTTPException(409, "Оплату можно отметить только по завершённой поездке")
    # Лочим бронь: двойной тап «не заплатил» иначе плодит второй non_payment-инцидент.
    locked = session.exec(select(Booking).where(Booking.id == booking.id).with_for_update()).first()
    if locked is not None:
        booking = locked
    booking.payment_state = "received" if body.received else "unpaid"
    session.add(booking)
    inc = None
    if not body.received:
        # Дедуп: уже есть non_payment по этой броне на пассажира — не плодим второй (повтор/ретрай).
        inc = session.exec(select(Incident).where(
            Incident.booking_id == booking_id,
            Incident.respondent_id == booking.passenger_id,
            Incident.type == "non_payment",
        )).first()
        if inc is None:
            # create_incident коммитит сам → флаг payment_state + инцидент атомарно (нет частичного коммита).
            inc = create_incident(
                session, reporter=user, respondent_id=booking.passenger_id, type="non_payment",
                description=clamp(body.note, 2000), booking_id=booking_id, background=background,
            )
        else:
            session.commit()   # существующий инцидент — просто фиксируем флаг payment_state
    else:
        session.commit()
    session.refresh(booking)
    data = booking.model_dump(mode="json")
    data["incident_id"] = inc.id if inc else None
    return data


class ParcelPhotoIn(BaseModel):
    phase: str                                   # pickup | delivery
    url: str = Field(..., max_length=500)


@router.post("/bookings/{booking_id}/parcel-photo", response_model=None)
def parcel_photo(booking_id: int, body: ParcelPhotoIn,
                 user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Фото-доказательство посылки (parcel): «до» при приёме курьером и «после» при вручении."""
    booking, _ride = booking_and_ride_for_user(session, booking_id, user)
    if body.phase not in ("pickup", "delivery"):
        raise HTTPException(400, "Фаза: pickup или delivery")
    url = (body.url or "").strip()[:500]
    if not url:
        raise HTTPException(400, "Пустой URL фото")
    # Allowlist: только НАШ файл (media/приватный эвиденс). Чужой URL при просмотре у оппонента
    # слил бы его IP — иначе фото посылки становится каналом деанона «между своими».
    if not is_own_media_url(url):
        raise HTTPException(400, "Фото нужно загрузить в приложение")
    if body.phase == "pickup":
        booking.parcel_pickup_photo = url
    else:
        booking.parcel_delivery_photo = url
    session.add(booking)
    session.commit()
    session.refresh(booking)
    return booking.model_dump(mode="json")


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
