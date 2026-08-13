"""Брони: бронирование (с защитой от овербукинга и блокировок), подтверждение,
отмена, список своих, список броней водителя для оценки пассажиров."""
from datetime import timedelta
from typing import Optional

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from .. import livepos            # модулем, а не функцией: так подмена в тестах цепляет вызов
from ..config import settings
from ..db import get_session
from ..errors import herr
from ..models import Booking, BookingStatus, DriverProfile, Message, PayMethod, Rating, Ride, RideStatus, User
from ..safety_logic import (CANCEL_REASONS, MSG_WOMEN_ONLY_RIDE, account_paused, ensure_active,
                            guard_women_only)
from ..security import current_user, gen_otp
from ..services import booking_and_ride_for_user, geocode_city, haversine_km, is_blocked, notify_map_changed, push_notification, user_rating
from ..timeutil import utcnow
from ..trust_service import INSIDER_LEVEL, trust_level

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


MAX_PAY_AMOUNT = 100_000   # ₽ — здравый потолок для суммы договорённости (защита от опечатки/мусора)

# На сколько раньше времени выезда разрешено сказать «поездка завершена». Допуск тот же, что у
# публикации («время выезда уже прошло» прощает 30 минут): часы на телефоне врут на минуты,
# и выехать пораньше — обычное дело. Всё, что раньше этого, — не поездка, а нажатая кнопка.
DONE_EARLY_GRACE = timedelta(minutes=30)


def _clean_pay_amount(amount: Optional[int]) -> Optional[int]:
    """Проверка суммы договорённости. None → None (не фиксировали). Мусор → 400."""
    if amount is None:
        return None
    if amount < 0 or amount > MAX_PAY_AMOUNT:
        raise herr(400, "Некорректная сумма договорённости", "Килешеү суммаһы дөрөҫ түгел")
    return amount


class BookIn(BaseModel):
    ride_id: int
    seats: int = 1
    # Договорённость об оплате (НЕ платёж): как решили платить + сумма (опц.).
    # Способ по умолчанию — «договоримся»; сумма по умолчанию — из цены поездки.
    pay_method: Optional[PayMethod] = None
    pay_amount: Optional[int] = None
    # Едет несовершеннолетний. Отмечает тот, кто бронирует (сам подросток или родитель за него).
    # Взрослый обязателен: имя + телефон — это и есть запись согласия, и водителю есть кому
    # позвонить. Дефолты держат старые клиенты: не прислали — бронь обычная, как раньше.
    minor_passenger: bool = False
    minor_guardian_name: str = Field("", max_length=120)
    minor_guardian_phone: str = Field("", max_length=32)


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
    # Договорённость об оплате (запись, не платёж) — видна обеим сторонам.
    pay_method: str = "negotiate"
    pay_amount: Optional[int] = None
    driver_name: str
    driver_verified: bool
    driver_phone: str = ""
    driver_car: str = ""
    # Госномер и цвет — чтобы у подъезда было чем сверить машину с объявлением. Пусто до
    # подтверждения брони (ПДн). Дефолты держат старый клиент: он поля просто не читает.
    driver_plate: str = ""
    driver_car_color: str = ""
    # Несовершеннолетний пассажир. Флаг видят ОБЕ стороны (пассажир — что отметил, водитель —
    # кого везёт). Контакты взрослого — только водителю этой брони: это чужие ПДн.
    minor_passenger: bool = False
    minor_guardian_name: str = ""
    minor_guardian_phone: str = ""
    pickup: str = ""
    pickup_lat: Optional[float] = None
    pickup_lng: Optional[float] = None
    from_lat: Optional[float] = None
    from_lng: Optional[float] = None
    to_lat: Optional[float] = None
    to_lng: Optional[float] = None


@router.post("/bookings", response_model=Booking)
def book(body: BookIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    ensure_active(session, user.id)   # пауза лестницы «Справедливости» (§2) реально блокирует бронь
    if body.seats < 1:
        raise herr(400, "Количество мест должно быть больше 0", "Урын һаны 0-дан күберәк булырға тейеш")
    # FOR UPDATE: блокируем строку поездки на время транзакции → нет овербукинга при гонке.
    ride = session.exec(select(Ride).where(Ride.id == body.ride_id).with_for_update()).first()
    if not ride or ride.status != RideStatus.active:
        raise herr(400, "Поездка недоступна", "Сәфәр хәҙер юҡ")
    if ride.driver_id == user.id:
        raise herr(400, "Нельзя бронировать собственную поездку", "Үҙ сәфәреңде бронларға ярамай")
    if is_blocked(session, user.id, ride.driver_id):
        raise herr(403, "Бронь недоступна", "Бронь мөмкин түгел")
    # Водитель на паузе за нарушения — бронь бессмысленна: подтвердить её ему закрыто (волна 9),
    # и человек просто ждал бы ответа, которого не будет. Из ленты такие поездки убраны, но
    # прямая ссылка (старый пуш, открытый экран, «поделился в чате») ленту обходит.
    # Пассажиру НЕ сообщаем, что водителя наказали, — это чужая история разбора.
    if account_paused(session, ride.driver_id):
        raise herr(409, "Этот водитель сейчас не выходит в рейс. Посмотри другие поездки — рядом есть ещё.",
                   "Был водитель хәҙер юлға сыҡмай. Башҡа сәфәрҙәрҙе ҡара — яҡында тағы бар.")
    # «Только для своих» (L3) закрывает поездку ЦЕЛИКОМ, а не только ленту. Фильтр стоял на всех
    # выдачах и на отклике по заявке (requests.py: «прямой id не обходит фильтр»), а бронь его не
    # знала: лента прячет, GET /rides/{id} даёт 404 — и тут же POST /bookings проходит с 200.
    # Чужой становился полноправным участником закрытой поездки (чат, детали, телефон водителя),
    # а id взять есть откуда: они последовательные, плюс пуш «карауль маршрут» (аудит 2026-08-07).
    if getattr(ride, "only_trusted", False) and trust_level(session, user) < INSIDER_LEVEL:
        raise herr(403, "Поездка только для своих", "Сәфәр тик үҙ кешеләр өсөн")
    # «Только женщины» — теперь правило, а не пожелание (решение Александра, 2026-08-08).
    # Стоит РЯДОМ с «только для своих» намеренно: обе отметки закрывают поездку целиком, и
    # обе должны стоять на самой броне, а не на выдаче. Лента может спрятать, но id
    # последовательные — фильтр в списке ничего не гарантирует.
    if getattr(ride, "women_only", False):
        guard_women_only(user, msg=MSG_WOMEN_ONLY_RIDE)
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
        raise herr(400, "Не хватает мест", "Урын етмәй")
    # Несовершеннолетний пассажир: взрослый обязателен и назван поимённо. Это не бюрократия —
    # без имени и телефона взрослого никто не отвечает за ребёнка в дороге, а водитель узнаёт
    # о подростке только когда тот сядет в машину. Проверяем ДО списания мест.
    guardian_name = (body.minor_guardian_name or "").strip()
    guardian_phone = (body.minor_guardian_phone or "").strip()
    if body.minor_passenger:
        if ride.no_minors:
            raise herr(
                409,
                "Водитель не берёт пассажиров младше 18 без сопровождения взрослого. Поищи другую поездку.",
                "Водитель 18-ҙән кесе юлсыларҙы оло кеше оҙатыуынан башҡа алмай. Башҡа сәфәр эҙлә.",
            )
        if not guardian_name or not guardian_phone:
            raise herr(
                400,
                "Для поездки пассажира младше 18 укажите взрослого: имя и телефон. Он отвечает за поездку, и водителю есть кому позвонить.",
                "18-ҙән кесе юлсы өсөн оло кешене күрһәтегеҙ: исеме һәм телефоны. Ул сәфәр өсөн яуаплы, водителгә шылтыратырға кем булыр.",
            )
    else:
        guardian_name = guardian_phone = ""   # не подросток — лишних ПДн не храним
    total_price = ride.price * body.seats
    # Договорённость об оплате: способ по умолчанию — «договоримся»; сумма — из цены поездки, если не задана.
    pay_method = body.pay_method or PayMethod.negotiate
    pay_amount = _clean_pay_amount(body.pay_amount)
    if pay_amount is None:
        pay_amount = total_price if total_price > 0 else None
    booking = Booking(
        ride_id=ride.id, passenger_id=user.id, seats=body.seats,
        price=total_price, boarding_code=gen_otp(),
        pay_method=pay_method, pay_amount=pay_amount,
        minor_passenger=bool(body.minor_passenger),
        minor_guardian_name=guardian_name, minor_guardian_phone=guardian_phone,
    )
    ride.seats_left -= body.seats
    session.add(booking)
    session.add(ride)
    session.commit()
    session.refresh(booking)
    notify_map_changed()   # места убыли → если 0, поездка уходит с карты live
    # Уведомление + push водителю о новой брони.
    #
    # У поездки «только женщины» напоминаем водителю, что решение за ним. Сервер эту отметку
    # проверить НЕ МОЖЕТ: пола пассажира у нас нет вообще — ни поля, ни вопроса при регистрации
    # (аудит 2026-08-08). Значит настоящий гейт здесь один — подтверждение брони водителем,
    # и он должен знать, что именно подтверждает. Строчка в пуше стоит дёшево, а женщина,
    # выбравшая такую поездку, рассчитывает именно на эту проверку.
    pax_name = user.name or "Пассажир"
    route = f"{ride.from_city} → {ride.to_city}"
    # Обе пометки нужны, и обе — про одно: решение «беру или нет» водитель принимает ЗАРАНЕЕ,
    # а не у машины, когда отказать уже некрасиво. Подросток — из волны про несовершеннолетних,
    # «только женщины» — из аудита безопасности; при слиянии веток 2026-08-12 сведены вместе.
    minor_ru = " · младше 18, со взрослым на связи" if booking.minor_passenger else ""
    minor_ba = " · 18-ҙән кесе, оло кеше бәйләнештә" if booking.minor_passenger else ""
    women_hint_ru = " · поездка «только женщины» — подтверди, если подходит" if ride.women_only else ""
    women_hint_ba = " · «тик ҡатын-ҡыҙ» сәфәре — тура килһә, раҫла" if ride.women_only else ""
    push_notification(
        session, ride.driver_id, "booking",
        "Новая бронь", "Яңы бронь",
        f"{pax_name}: {route}, мест {body.seats}{minor_ru}{women_hint_ru}",
        f"{pax_name}: {route}, {body.seats} урын{minor_ba}{women_hint_ba}",
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
    is_driver = ride.driver_id == user.id
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
        "pay_method": booking.pay_method.value if hasattr(booking.pay_method, "value") else booking.pay_method,
        "pay_amount": booking.pay_amount,
        "driver_name": (driver.name if driver and driver.name else "Водитель"),
        "driver_verified": bool(driver.verified) if driver else False,
        "driver_phone": (driver.phone if (unlocked and driver) else ""),
        "driver_car": driver_car,
        # Госномер — как в такси (instant_service), где это уже закрыто: «белая Гранта» у подъезда
        # ничего не доказывает. В попутке риск выше: у такси машину назначает сервер, а тут
        # пассажир идёт к машине, которую выбрал сам по объявлению. Разбор конкурентов
        # 2026-08-07: у BlaBlaCar приезжала ДРУГАЯ машина с другим человеком за рулём, и сверить
        # было нечем. Отдаём только участникам и только после подтверждения брони (ПДн).
        "driver_plate": ((profile.car_plate or "") if (unlocked and profile) else ""),
        "driver_car_color": ((profile.car_color or "") if (unlocked and profile) else ""),
        # Пометку видят оба; телефон взрослого — только водителю (и сразу, ещё до подтверждения:
        # именно на этих данных он и решает, берёт ли ответственность).
        "minor_passenger": bool(booking.minor_passenger),
        "minor_guardian_name": (booking.minor_guardian_name if is_driver else ""),
        "minor_guardian_phone": (booking.minor_guardian_phone if is_driver else ""),
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


class PayAgreementIn(BaseModel):
    pay_method: Optional[PayMethod] = None   # None = не менять способ
    pay_amount: Optional[int] = None         # None = не менять сумму


@router.post("/bookings/{booking_id}/pay-agreement")
def set_pay_agreement(booking_id: int, body: PayAgreementIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Зафиксировать/поправить договорённость об оплате. Это ЗАПИСЬ («как решили платить»),
    НЕ платёж — деньги через приложение не идут. Править может любая сторона брони
    (пассажир и водитель), запись видна обоим — опора в споре «мы же договаривались о 400»."""
    booking, _ride = booking_and_ride_for_user(session, booking_id, user)
    if body.pay_method is not None:
        booking.pay_method = body.pay_method
    if body.pay_amount is not None:
        booking.pay_amount = _clean_pay_amount(body.pay_amount)
    session.add(booking)
    session.commit()
    session.refresh(booking)
    return {
        "ok": True,
        "pay_method": booking.pay_method.value if hasattr(booking.pay_method, "value") else booking.pay_method,
        "pay_amount": booking.pay_amount,
    }


@router.get("/bookings/{booking_id}/boarding-code")
def boarding_code(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Код посадки брони — виден ТОЛЬКО участникам (пассажир/водитель). Пассажир называет код,
    водитель сверяет → подтверждение «та самая машина/человек» (доверие «между своими»)."""
    booking, _ride = booking_and_ride_for_user(session, booking_id, user)
    return {"code": booking.boarding_code or ""}


@router.get("/trips/{booking_id}/receipt")
def trip_receipt(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Квитанция завершённой поездки (по брони): маршрут, дата/время, сумма и способ оплаты,
    имя водителя. Только участник брони (пассажир/водитель) — анти-IDOR. Незавершённая → 409.

    Без лишних ПДн: телефон водителя тут НЕ отдаём (квитанция — это про поездку и деньги,
    контакт есть в деталях брони). Сумма — договорённость об оплате (pay_amount) либо цена брони."""
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    if booking.status != BookingStatus.done:
        raise herr(409, "Квитанция появится после завершения поездки",
                   "Квитанция сәфәр тамамланғандан һуң күренәсәк")
    driver = session.get(User, ride.driver_id)
    amount = booking.pay_amount if booking.pay_amount is not None else booking.price
    return {
        "booking_id": booking.id,
        "ride_id": ride.id,
        "role": "driver" if ride.driver_id == user.id else "passenger",
        "from_city": ride.from_city,
        "to_city": ride.to_city,
        "depart_at": ride.depart_at.isoformat() if ride.depart_at else "",
        "seats": booking.seats,
        "amount": amount,                       # ₽; договорённость (pay_amount) или цена брони
        "pay_method": booking.pay_method.value if hasattr(booking.pay_method, "value") else booking.pay_method,
        "paid": bool(booking.paid),
        "driver_name": (driver.name if driver and driver.name else "Водитель"),
        "driver_verified": bool(driver.verified) if driver else False,
    }


def _alone_with_driver(session: Session, booking: Booking, ride: Ride, user: User) -> bool:
    """Пассажир ехал не один, а теперь остался в машине один на один с водителем.

    Отдельная история на 849 голосов (r/india): девушка ехала с водителем и второй пассажиркой;
    как только ту высадили, водитель начал приставать. Она доплатила сверху и промолчала — лишь
    бы доехать без конфликта. Момент, когда салон пустеет, и есть точка, где человек становится
    уязвим, а SOS в этот момент как раз не нажимают: боятся «поднимать шум из-за слов».

    Считаем: у поездки БЫЛИ другие попутчики (не отменённые брони, кроме моей), а сейчас
    активных, кроме меня, нет. Тогда клиент тихо, БЕЗ пуша и звука, предложит поделиться
    поездкой с близким. Водителю это не видно и обвинением не является.

    Только для пассажира в активной броне: водителю флаг бессмыслен, в завершённой — поздно.
    """
    if ride.driver_id == user.id:
        return False
    if booking.status not in (BookingStatus.confirmed, BookingStatus.onboard):
        return False
    others = session.exec(
        select(Booking).where(
            Booking.ride_id == ride.id,
            Booking.id != booking.id,
            Booking.status != BookingStatus.cancelled,
        )
    ).all()
    if not others:
        return False       # изначально ехал(а) один(на) — это не перемена, напоминать не о чем
    return not any(o.status in (BookingStatus.confirmed, BookingStatus.onboard) for o in others)


@router.get("/bookings/{booking_id}/role")
def booking_role(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Роль текущего юзера в брони — водитель/пассажир. Экран активной поездки показывает
    нужные кнопки статуса (водитель: «выехал/подъезжаю»; пассажир: «сел/доехал/завершить»)."""
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    # + статус и подфаза → экран активной поездки опрашивает это и показывает пассажиру live-баннер
    # «водитель выехал/подъезжает» (раньше это приходило только пушем, в UI не обновлялось).
    return {
        "alone_with_driver": _alone_with_driver(session, booking, ride, user),
        "role": "driver" if ride.driver_id == user.id else "passenger",
        "status": booking.status,
        "driver_phase": booking.driver_phase,
        # true = сервер сам сверил «подъезжаю» с GPS водителя. Пассажир видит «подтверждено по GPS»
        # и знает, что машина правда рядом, а не «уже почти» на словах.
        "arrival_verified": bool(booking.arrival_verified),
    }


class DriverStatusIn(BaseModel):
    status: str  # departed | arriving | done


def _pickup_point(ride: Ride) -> Optional[tuple]:
    """Куда водитель должен подъехать — ТОЛЬКО точный пин точки сбора.

    Центр города сюда намеренно НЕ подставляем: Уфа больше 20 км в поперечнике, и водитель,
    честно забирающий пассажира в Черниковке, оказался бы «в 12 км от центра» и получил отказ.
    Нет пина — нет проверки; лучше пропустить обман, чем остановить честного.
    """
    if ride.pickup_lat is not None and ride.pickup_lng is not None:
        return (ride.pickup_lat, ride.pickup_lng)
    return None


def _verify_arrival(booking: Booking, ride: Ride) -> Optional[float]:
    """Сверить «подъезжаю» с живым GPS водителя. Возвращает расстояние в метрах или None.

    None = проверить нечем (выключено рубильником, нет пина подачи, Redis молчит или водитель
    ещё не прислал ни одного кадра). Тогда пропускаем как раньше — доверяем слову. Блокируем
    только когда ТОЧНО знаем, что водитель далеко: ложный отказ дороже пропущенного обмана.
    """
    if not settings.arrival_verify_enabled:
        return None
    target = _pickup_point(ride)
    if target is None:
        return None
    try:
        pos = livepos.livepos_get("booking", booking.id)
    except Exception:  # noqa: BLE001 — кэш позиции best-effort, он не вправе ронять статус
        return None
    if not pos or pos.get("lat") is None or pos.get("lng") is None:
        return None
    return haversine_km(float(pos["lat"]), float(pos["lng"]), target[0], target[1]) * 1000.0


@router.post("/bookings/{booking_id}/driver-status")
def driver_status(booking_id: int, body: DriverStatusIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель отмечает «выехал/подъезжаю» → push пассажиру (закрывает тревогу ожидания).
    «done» — водитель завершает поездку: закрываем бронь (раньше закрыть мог ТОЛЬКО пассажир →
    если он забывал нажать «Завершить», бронь висела активной, а места поездки не освобождались)."""
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    if ride.driver_id != user.id:
        raise herr(403, "Только водитель", "Тик йөрөтөүсе")
    if body.status not in {"departed", "arriving", "done"}:
        raise herr(400, "Недопустимый статус", "Ярамаған хәл")
    if body.status == "done":
        # Идемпотентно: уже завершённую/отменённую бронь не трогаем. (pending→done — легитимный
        # поток: пассажир не «подтверждает» отдельно, водитель завершает поездку напрямую.)
        if booking.status not in (BookingStatus.done, BookingStatus.cancelled):
            # Завершить можно только НАЧАВШУЮСЯ поездку. Раньше проверки не было вовсе, и цикл
            # «опубликовал на 2030 год → забронировал вторым аккаунтом → завершил» из трёх запросов
            # рисовал бейдж «N поездок» и открывал обеим сторонам оценку — без единого метра пути
            # (аудит 2026-08-07). Бейдж доверия и есть продукт «между своими», подделывать его
            # нельзя. Проверка стоит ВНУТРИ ветки «статус меняется»: повторный тап по уже
            # завершённой броне по-прежнему отвечает «ок», а не ошибкой.
            if ride.depart_at and utcnow() < ride.depart_at - DONE_EARLY_GRACE:
                raise herr(409, "Поездка ещё не началась — завершить можно после времени выезда",
                           "Сәфәр әле башланмаған — сығыу ваҡытынан һуң тамамлап була")
            booking.status = BookingStatus.done
            booking.driver_phase = ""        # поездка кончилась — фазу сбрасываем
            session.add(booking)
            session.commit()
            # B8-4: реферальный бонус пригласившему за раскатавшегося водителя (идемпотентно).
            from .referral import reward_driver_referral
            reward_driver_referral(session, ride.driver_id)
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
        raise herr(409, "Поездка не активна", "Сәфәр актив түгел")
    # Честное «подъезжаю»: слово водителя сверяем с его же GPS. Пассажир, увидев «подъезжает»,
    # выходит из дома — на морозе цена вранья высокая. Ловим только доказанный случай (см. _verify_arrival).
    if body.status == "arriving":
        meters = _verify_arrival(booking, ride)
        if meters is not None and meters > settings.arrival_verify_radius_m:
            km = meters / 1000.0
            raise herr(
                409,
                f"Ты ещё далеко от места подачи (≈{km:.1f} км). "
                "Нажми «Подъезжаю», когда будешь рядом — пассажир выйдет ровно к твоему приезду.",
                f"Һин алыу урынынан алыҫ әле (≈{km:.1f} км). "
                "«Яҡынлашам»ды яҡын килгәс баҫ — юлсы тап һин килгәнгә сыға.",
            )
        booking.arrival_verified = meters is not None
    else:
        booking.arrival_verified = False     # «выехал» — до места ещё ехать, подтверждать нечего
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
        raise herr(403, "Подтвердить бронь может только водитель", "Броньды тик водитель генә раҫлай ала")
    if booking.status == BookingStatus.confirmed:
        return booking                       # идемпотентно (повторный тап) — без побочек
    # Подтверждать можно ТОЛЬКО ожидающую бронь: нельзя откатить onboard→confirmed или воскресить cancelled/done.
    if booking.status != BookingStatus.pending:
        raise herr(400, "Эту бронь уже нельзя подтвердить", "Был бронде инде раҫлап булмай")
    # ⬇️ Пауза «Справедливости» (§2). Подтверждение — это ВСТУПЛЕНИЕ в сделку, а не «начатая
    # поездка»: публикация была до паузы, а обязательство везти человека возникает вот здесь.
    # Гейт стоял на публикации и на брони, а на подтверждении — нет, и отстранённый водитель
    # спокойно доводил дело до рейса (проверено запросом: 200, аудит 2026-08-08, волна 9).
    # Отмена брони отстранённому ОСТАЁТСЯ открытой — иначе пассажир висит в ожидании.
    ensure_active(session, user.id)
    # Та же вторая дверь для блокировки: бронировать заблокированного нельзя (см. create_booking),
    # а ПОДТВЕРДИТЬ бронь, поставленную до блокировки, было можно — и водитель ехал к человеку,
    # который от него закрылся (аудит 2026-08-08, волна 10). Текст глухой — про факт блокировки
    # второй стороне не сообщаем.
    if is_blocked(session, user.id, booking.passenger_id):
        raise herr(403, "Недоступно", "Мөмкин түгел")
    booking.status = BookingStatus.confirmed
    session.add(booking)
    session.commit()
    session.refresh(booking)
    # Уведомление пассажиру: бронь подтверждена водителем (F2: подтверждение открывает
    # телефон/точку сбора/live-гео — говорим об этом сразу).
    route = f"{ride.from_city} → {ride.to_city}"
    push_notification(
        session, booking.passenger_id, "booking",
        "Бронь подтверждена", "Бронь раҫланды",
        f"{route}: водитель подтвердил. Открыты телефон и точка сбора.",
        f"{route}: водитель раҫланы. Телефон һәм йыйылыу урыны асыҡ.",
        ref_kind="booking", ref_id=booking.id,
    )
    return booking


class CancelIn(BaseModel):
    reason: str = ""   # код причины отмены из пресетов UI (changed_mind/found_other/…); пусто = не указана


@router.post("/bookings/{booking_id}/cancel", response_model=Booking)
def cancel_booking(booking_id: int, body: Optional[CancelIn] = None,
                   user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отмена поездки пассажиром или водителем. Места возвращаются в поездку.
    B8-8 («увод мимо приложения»): отмена ПОСЛЕ открытия контакта (бронь подтверждена —
    телефон виден — или в чате уже переписывались) помечается contact_then_cancel —
    только сигнал в админ-пульс, честных не наказываем."""
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    if booking.status not in (BookingStatus.cancelled, BookingStatus.done):
        # Блокируем строки поездки и брони → две одновременные отмены не вернут места ДВАЖДЫ.
        # V4: порядок локов Ride → Booking — ЕДИНЫЙ с cancel_ride (иначе обратный порядок
        # cancel_ride(Ride→Booking) vs cancel_booking(Booking→Ride) даёт deadlock под нагрузкой).
        # populate_existing: БЕЗ него лок сторожит пустоту. Обе строки уже загружены выше
        # (booking_and_ride_for_user), а SQLAlchemy при повторной загрузке известного объекта
        # НЕ перезаписывает поля — под локом читались старые значения. На Postgres это хуже,
        # чем на SQLite: ожидание на локе гарантирует, что данные прочитаны до чужого коммита.
        # Итог был такой: места возвращались из устаревшего seats_left → в поездке «освобождалось»
        # больше мест, чем есть в машине (аудит 2026-08-07).
        ride = session.exec(select(Ride).where(Ride.id == booking.ride_id)
                            .with_for_update().execution_options(populate_existing=True)).first()
        # Бронь перечитываем под локом и ПЕРЕПРОВЕРЯЕМ статус: первая отмена уже могла отработать.
        booking = session.exec(select(Booking).where(Booking.id == booking_id)
                               .with_for_update().execution_options(populate_existing=True)).first()
        if booking.status in (BookingStatus.cancelled, BookingStatus.done):
            return booking                     # другая параллельная отмена опередила — места уже возвращены
        contact_opened = booking.status in (BookingStatus.confirmed, BookingStatus.onboard) or (
            session.exec(select(Message.id).where(Message.booking_id == booking_id).limit(1)).first()
            is not None
        )
        booking.status = BookingStatus.cancelled
        booking.cancelled_at = utcnow()
        booking.contact_then_cancel = contact_opened
        # Белый список кодов (порт из pr88): произвольная строка в БД не попадает, неизвестный
        # код (старый/будущий клиент) не теряем — сводим к "other".
        _raw_reason = ((body.reason or "").strip()[:80] or None) if body else None
        booking.cancel_reason = _raw_reason if _raw_reason in CANCEL_REASONS else ("other" if _raw_reason else None)
        booking.cancelled_by = user.id   # «Надёжность»: поздняя отмена бьёт по инициатору (safety_logic.reliability_for)
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


@router.post("/bookings/{booking_id}/no-show", response_model=Booking)
def mark_no_show(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель отмечает, что пассажир НЕ ЯВИЛСЯ (no-show): бронь → cancelled, места возвращаются,
    ставим no_show + cancel_reason='no_show'. Только водитель этой поездки и только по подтверждённой/в-пути
    брони. Идемпотентно. Сигнал доверия «между своими» (отдельно от обычной отмены)."""
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    if user.id != ride.driver_id:
        raise herr(403, "Отметить неявку может только водитель поездки", "Килмәүҙе тик сәфәр йөрөтөүсеһе генә билдәләй ала")
    if booking.status not in (BookingStatus.confirmed, BookingStatus.onboard):
        raise herr(409, "Неявку можно отметить только по подтверждённой брони", "Килмәүҙе тик раҫланған бронь буйынса ғына билдәләп була")
    # Порядок локов Ride → Booking — ЕДИНЫЙ с cancel_booking/cancel_ride (V4, без deadlock).
    # populate_existing — по той же причине, что в cancel_booking: обе строки уже загружены выше,
    # и без него «перепроверка под локом» смотрела бы на устаревшие значения (аудит 2026-08-07).
    ride = session.exec(select(Ride).where(Ride.id == booking.ride_id)
                        .with_for_update().execution_options(populate_existing=True)).first()
    booking = session.exec(select(Booking).where(Booking.id == booking_id)
                           .with_for_update().execution_options(populate_existing=True)).first()
    if booking.status not in (BookingStatus.confirmed, BookingStatus.onboard):
        return booking                         # параллельная отмена/неявка опередила — места уже возвращены
    booking.status = BookingStatus.cancelled
    booking.cancelled_at = utcnow()
    booking.no_show = True
    booking.cancel_reason = "no_show"
    booking.cancelled_by = user.id   # кто отметил (неявка бьёт по Надёжности пассажира только после инцидента-подтверждения)
    ride.seats_left = min(ride.seats_total, ride.seats_left + booking.seats)   # вернуть освобождённые места
    session.add(booking)
    session.add(ride)
    session.commit()
    session.refresh(booking)
    notify_map_changed()
    route = f"{ride.from_city} → {ride.to_city}"
    push_notification(
        session, booking.passenger_id, "booking",
        "Отмечена неявка", "Килмәгәнлек билдәләнде",
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
            "pay_method": b.pay_method.value if hasattr(b.pay_method, "value") else b.pay_method,
            "pay_amount": b.pay_amount,
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
    # Что водитель уже поставил по каждой брони. Без этого список после перезагрузки снова
    # показывал пустые звёзды по оценённым пассажирам — человек ставил оценку второй раз,
    # не понимая, засчиталась ли первая. Одним запросом пачкой (анти-N+1).
    my_stars = {
        r.booking_id: r.stars
        for r in session.exec(
            select(Rating).where(Rating.rater_id == user.id,
                                 Rating.booking_id.in_([b.id for b in bookings]))
        ).all()
    } if bookings else {}
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
            # 0 = ещё не оценивал. Иначе — сколько звёзд поставил (оценку можно изменить).
            "my_stars": my_stars.get(b.id, 0),
        })
    return out


@router.post("/bookings/{booking_id}/lost-item")
def booking_lost_item(booking_id: int, user: User = Depends(current_user),
                      session: Session = Depends(get_session)):
    """«Я забыл вещь в машине» — открывает чат брони на запись ещё на 48 часов.

    У такси такой выход был с 2026-07-26, а у попутки нет. Пока чат попутки оставался
    открытым навсегда, дыры не было — но 2026-08-06 мы его закрыли через сутки после
    поездки, и телефон, забытый на заднем сиденье, стало не вернуть: номер второй стороны
    после поездки не виден. Дыру создало само закрытие чата, поэтому выход обязан быть.

    Доступно обеим сторонам: водитель тоже находит вещи и ищет, чьи они."""
    from datetime import timedelta

    booking, ride = booking_and_ride_for_user(session, booking_id, user)   # 403/404 если не участник
    if booking.status != BookingStatus.done:
        raise herr(409, "Доступно после завершения поездки",
                   "Сәфәр тамамланғандан һуң мөмкин")
    booking.lost_item_until = utcnow() + timedelta(hours=48)
    session.add(booking)
    session.commit()
    other_id = ride.driver_id if user.id == booking.passenger_id else booking.passenger_id
    if other_id:
        push_notification(
            session, other_id, "message",
            "Забытая вещь", "Онотолған әйбер",
            "Вторая сторона ищет вещь из этой поездки — чат снова открыт на 48 часов.",
            "Сәфәрҙән әйбер эҙләйҙәр — чат 48 сәғәткә асыҡ.",
            ref_kind="booking", ref_id=booking.id,
        )
    return {"ok": True, "chat_open_until": booking.lost_item_until.isoformat()}
