"""Брони: бронирование (с защитой от овербукинга и блокировок), подтверждение,
отмена, список своих, список броней водителя для оценки пассажиров."""
import math
from datetime import timedelta
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import update
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from .. import livepos            # модулем, а не функцией: так подмена в тестах цепляет вызов
from .. import trip_wake
from ..config import settings
from ..flood import TOO_FAST_CREATING, guard_burst
from ..db import get_session
from ..errors import herr
from ..features import guard_delivery_ride
from ..models import Block, Booking, BookingStatus, DriverProfile, Message, PayMethod, Payment, Rating, Ride, RideStatus, User
from ..cancel_reason_text import cancel_reason_text
from ..geo import snapshot_cached
from ..ride_stops import VILLAGE_KINDS, canonical_stop, is_end, is_start, parse_stops, pickup_of, ride_has_stops_raw
from ..safety_logic import (CANCEL_REASONS, MSG_WOMEN_ONLY_RIDE, account_paused, ensure_active,
                            guard_women_only)
from ..security import current_user, gen_otp
from ..visibility import LIVE_GEO_LEAD_HOURS, booking_contacts_open
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

# Сколько часов после времени выезда поездку ещё можно забронировать: столько же, сколько она
# остаётся в выдаче (`rides.RIDE_PAST_GRACE_HOURS`). Держим одно число на оба правила — иначе
# человек увидит карточку в ленте и не сможет её забронировать (волна 151).
RIDE_PAST_GRACE_HOURS = 2

# «Забыл вещь» открывает чат заново. Сколько это можно делать (волна 151):
#   • не позже месяца после поездки — дальше это уже не поиск вещи, а способ написать человеку;
#   • не больше трёх раз — иначе даже в пределах месяца получается бесконечный стук.
# Кому по-настоящему не вернули вещь, поможет поддержка: там разбирает человек.
LOST_ITEM_WINDOW = timedelta(days=30)
LOST_ITEM_MAX_CALLS = 3

# На сколько раньше времени выезда разрешено сказать «поездка завершена». Допуск тот же, что у
# публикации («время выезда уже прошло» прощает 30 минут): часы на телефоне врут на минуты,
# и выехать пораньше — обычное дело. Всё, что раньше этого, — не поездка, а нажатая кнопка.
DONE_EARLY_GRACE = timedelta(minutes=30)

# Посадку отмечает ВОДИТЕЛЬ (решение Александра 17.10.2026). Раньше пассажир сам жал «Я сел» —
# и мог нажать из дома, чтобы водитель уже не отметил «Не пришёл», или нажать по ошибке без
# возврата. Окно то же, что было у «Я сел»: за DONE_EARLY_GRACE до выезда и позже. Свою отметку
# водитель отменяет в первые BOARDING_UNDO_WINDOW — промахнулся карточкой; дальше — разбор.
BOARDING_UNDO_WINDOW = timedelta(minutes=5)

# «Не пришёл» (решение Александра 18.10.2026, по образцу BlaBlaCar): водитель отмечает неявку не
# раньше чем через NO_SHOW_WAIT после времени выезда. Раньше — в любой момент, хоть за неделю до
# поездки, одним касанием вплотную к «Сел» и без возврата: промахнулся — бронь человека, который
# стоит у дороги, снята, место ушло в продажу. Срок знает ТОЛЬКО сервер: клиентам он приходит
# моментом `no_show_at` (`driver_bookings`, отказ `no_show_too_early`), своих 15 минут они не держат.
NO_SHOW_WAIT = timedelta(minutes=15)

# Пассажиры на промежуточных остановках (решение Александра 18.10.2026). Человека на остановке
# сажают позже выезда — иногда на час, — и 15 минут от выезда из начальной точки его не защищали:
# водитель доезжал до остановки, а «Не пришёл» уже горела. Поэтому срок считается от позднего из
# «выезд» и первого «Подъезжаю» по этой брони (`Booking.arriving_at`) — одно правило для всех
# поездок, — а на поездке с остановками без «Подъезжаю» неявки нет вовсе: пассажир должен получить
# «Водитель подъезжает» и успеть выйти. Отказ — 409 `no_show_needs_arriving`.


# Место посадки (решение Александра 18.10.2026): пассажир выбирает, где садится, — в начале маршрута
# (`Booking.pickup_stop = ""`) или на остановке по пути («Акъяр»); NULL — неизвестно (старая бронь,
# старое приложение). Правила по месту (`ride_stops.pickup_of`):
#   • начало и любая поездка без остановок — «Подъезжаю» с DONE_EARLY_GRACE до выезда, GPS к пину
#     800 м; «Не пришёл» — `max(выезд, «Подъезжаю») + NO_SHOW_WAIT`, «Подъезжаю» не требуется;
#   • остановка — «Подъезжаю» не раньше, чем машина могла доехать (`stop_fast_kmh` по прямой), GPS к
#     ЕЁ координатам (село/райцентр: отказ дальше `stop_refuse_km`, «подтверждено» ближе
#     `stop_verified_km`); «Не пришёл» — только после «Подъезжаю» и не раньше расчётного приезда
#     (`stop_expected_kmh`); живая точка — по окну остановки (`stop_live_window`);
#   • неизвестно — правила PR184: «Не пришёл» только после «Подъезжаю», без GPS-отказа.
# Остановку справочник не знает — без раннего предела и GPS-отказа, срок неявки — по оценке пути.
# Все сроки — нижняя граница (прямая короче дороги): пассажира они защищают, водителя не держат
# дольше нужного больше, чем на запас прямой.

# Сколько после срока «Не пришёл» пассажирке на остановке, которая так и не села, ещё видна машина
# (вторая проверка безопасности, P2-А): дальше точка до посадки не идёт.
LIVE_AFTER_NO_SHOW = timedelta(minutes=15)


def ride_has_stops(ride: Ride) -> bool:
    """Поездка с промежуточными остановками — для правил безопасности (`ride_stops.ride_has_stops_raw`):
    в `waypoints` есть название с буквой, не равное началу или концу маршрута. Пробелы, пустые
    разделители и «.»/«-»/«123» остановкой не считаются: иначе одна точка в поле выключала бы
    GPS-проверку «Подъезжаю» у всей поездки (приёмка PR184)."""
    return ride_has_stops_raw(ride)


def arriving_opens_at(ride: Ride, booking: Optional[Booking] = None, entry=None):
    """С какого момента водитель может нажать «Подъезжаю»: за DONE_EARLY_GRACE до выезда — та же
    планка, что у «Сел» (приёмка PR184). Раньше «Подъезжаю» принималось хоть за неделю: пассажиру
    срочно, мимо тихих часов, уходило «Водитель подъезжает», а на поездке с остановками ещё и
    запускался срок «Не пришёл». None — у поездки нет выезда, проверять нечем.

    Бронь на известной справочнику остановке — позже на время пути до неё по прямой на
    `stop_fast_kmh` (18.10.2026): у дома в начале маршрута «Подъезжаю» к селу за 40 км — вранье."""
    if not ride.depart_at:
        return None
    base = ride.depart_at - DONE_EARLY_GRACE
    if booking is None:
        return base
    p = pickup_of(ride, booking, entry)
    if p.kind == "stop" and p.point is not None and p.km:
        return base + timedelta(hours=p.km / settings.stop_fast_kmh)
    return base


def arriving_wait_left(ride: Ride, now=None, booking: Optional[Booking] = None, entry=None) -> int:
    """Сколько секунд ещё нельзя нажать «Подъезжаю»; 0 — уже можно. Вверх до целой секунды."""
    at = arriving_opens_at(ride, booking, entry)
    if at is None:
        return 0
    return max(0, math.ceil((at - (now or utcnow())).total_seconds()))


def departed_opens_at(ride: Ride):
    """С какого момента принимаем «Выехал» (вторая проверка безопасности, P2-Г): за
    LIVE_GEO_LEAD_HOURS до выезда — с этого же момента живая точка и так открывается по времени.
    Раньше «Выехал» принималось хоть за неделю и сразу открывало обеим сторонам живую точку —
    водитель и пассажирка видели друг друга от дома. None — у поездки нет выезда."""
    return ride.depart_at - timedelta(hours=LIVE_GEO_LEAD_HOURS) if ride.depart_at else None


def departed_wait_left(ride: Ride, now=None) -> int:
    at = departed_opens_at(ride)
    if at is None:
        return 0
    return max(0, math.ceil((at - (now or utcnow())).total_seconds()))


def no_show_needs_arriving(ride: Ride, booking: Optional[Booking], entry=None) -> bool:
    """«Не пришёл» доступна только после «Подъезжаю» по этой брони — у пассажира на остановке и у
    брони без места на поездке с остановками (PR184). Пассажиру начала маршрута «Подъезжаю» не нужно."""
    if booking is None or booking.arriving_at is not None:
        return False
    return pickup_of(ride, booking, entry).kind != "start"


def no_show_available_at(ride: Ride, booking: Optional[Booking] = None, entry=None):
    """Момент (наивный UTC, как всё время в базе), с которого водитель может отметить неявку:
    поздний из «выезд», первого «Подъезжаю» по брони и — у брони на остановке — расчётного приезда
    на неё (`stop_expected_kmh` по прямой или по оценке для неизвестной), + NO_SHOW_WAIT.

    None — считать не от чего: у поездки нет выезда и «Подъезжаю» не было, ИЛИ бронь ждёт
    «Подъезжаю» ([no_show_needs_arriving] — тогда неявки нет, а не «можно»)."""
    if no_show_needs_arriving(ride, booking, entry):
        return None
    marks = [t for t in (ride.depart_at, booking.arriving_at if booking else None) if t]
    if booking is not None and ride.depart_at:
        p = pickup_of(ride, booking, entry)
        if p.kind == "stop" and p.travel_km:
            marks.append(ride.depart_at + timedelta(hours=p.travel_km / settings.stop_expected_kmh))
    return max(marks) + NO_SHOW_WAIT if marks else None


def no_show_wait_left(ride: Ride, now=None, booking: Optional[Booking] = None, entry=None) -> int:
    """Сколько секунд водителю ещё ждать «Не пришёл»; 0 — срок вышел (или его нечем считать).
    Бронь, которая ждёт «Подъезжаю», проверяет [no_show_needs_arriving], не эта функция.

    Вверх до целой секунды: «0» значит ровно «можно», а не «меньше секунды осталось»."""
    at = no_show_available_at(ride, booking, entry)
    if at is None:
        return 0
    return max(0, math.ceil((at - (now or utcnow())).total_seconds()))


def stop_live_window(ride: Ride, booking: Optional[Booking], entry=None):
    """Окно живой точки для брони на ОСТАНОВКЕ: (открывается, закрывается|None) или None — бронь
    не на остановке (тогда обычное `visibility.trip_is_live`).

    Открывается в `depart − LIVE_GEO_LEAD_HOURS + путь/stop_fast_kmh` (раньше машина до остановки
    не доедет — водитель и пассажирка не видят друг друга от дома за часы до посадки) или по
    «Подъезжаю» этой брони — что раньше; «Выехал» его НЕ открывает (P2-Г). Закрывается до посадки
    через LIVE_AFTER_NO_SHOW после срока «Не пришёл» (P2-А); посадка открывает снова.

    Водитель не нажал «Подъезжаю» (у остановки срока «Не пришёл» тогда нет) — окно всё равно
    закрывается: через NO_SHOW_WAIT + LIVE_AFTER_NO_SHOW после расчётного приезда на остановку
    (`stop_expected_kmh`; у неизвестной справочнику — по оценке пути). Иначе проехал мимо, ничего не
    нажав, — и пассажирка часами видит машину, а водитель её точку (приёмка PR186, S1). Позднее
    «Подъезжаю» сдвигает срок неявки, а с ним и закрытие — окно открывается снова."""
    if booking is None or not getattr(booking, "pickup_stop", None) or ride is None or ride.depart_at is None:
        return None
    p = pickup_of(ride, booking, entry)
    if p.kind != "stop":
        return None
    km = p.travel_km or 0.0
    opens = ride.depart_at - timedelta(hours=LIVE_GEO_LEAD_HOURS) + timedelta(hours=km / settings.stop_fast_kmh)
    deadline = no_show_available_at(ride, booking, entry)
    if deadline is None:   # ждёт «Подъезжаю»: срок — от расчётного приезда, как у неявки
        deadline = max(ride.depart_at, ride.depart_at + timedelta(hours=km / settings.stop_expected_kmh)) \
            + NO_SHOW_WAIT
    return opens, deadline + LIVE_AFTER_NO_SHOW


def boarding_undo_left(booking: Booking, now=None) -> int:
    """Сколько секунд водитель ещё может отменить свою отметку «Сел»; 0 — уже нельзя.

    Секунды, а не время отметки: часы телефона врут на минуты, а обратный отсчёт от сервера —
    нет. Посадка без `onboard_at` поставлена старым «Я сел» пассажира — её водитель не отменяет."""
    if booking.status != BookingStatus.onboard or booking.onboard_at is None:
        return 0
    left = (booking.onboard_at + BOARDING_UNDO_WINDOW - (now or utcnow())).total_seconds()
    return max(0, int(left))


# «Сообщить о проблеме» у пассажира на экране завершённой поездки (приёмка PR174). Спор этот —
# про посадку: «водитель отметил „Сел“, а я не садился» или «водитель не приехал и закрыл
# поездку». Случается он в момент выезда, и пуш «Водитель отметил посадку» приходит тогда же.
# Шесть часов от позднего из «время выезда», «отметка „Сел“» и «поездку закрыли» (`done_at`)
# покрывают остаток дня поездки: упустил машину, искал другую, добрался домой и только тогда
# открыл приложение. Ранний «Завершить» водителя окно не сокращает, а долгая поездка, закрытая
# через восемь часов после посадки, его всё ещё оставляет (повторная приёмка PR174). Позже —
# «Нужна помощь?» (поддержка); сам сервер разбор по брони принимает и после окна.
PROBLEM_REPORT_WINDOW = timedelta(hours=6)


def problem_report_left(booking: Booking, ride: Ride, now=None) -> int:
    """Сколько секунд пассажиру ещё показывать «Сообщить о проблеме» на завершённой поездке."""
    if booking.status != BookingStatus.done:
        return 0
    anchor = max((t for t in (ride.depart_at, booking.onboard_at, booking.done_at) if t is not None), default=None)
    if anchor is None:
        return 0
    return max(0, int((anchor + PROBLEM_REPORT_WINDOW - (now or utcnow())).total_seconds()))


def _clean_pay_amount(amount: Optional[int]) -> Optional[int]:
    """Проверка суммы договорённости. None → None (не фиксировали). Мусор → 400."""
    if amount is None:
        return None
    if amount < 0 or amount > MAX_PAY_AMOUNT:
        # Сухое «некорректная сумма» человеку ничего не объясняет: он видел свою цифру
        # и не понимает, чем она плоха. Называем границу — тогда понятно, что исправить
        # (аудит 2026-08-08, волна 106).
        raise herr(400, f"Сумма должна быть от 0 до {MAX_PAY_AMOUNT} ₽ — проверь, сколько договорились",
                   f"Сумма 0-{MAX_PAY_AMOUNT} һум араһында булырға тейеш — күпмегә килешкәнде ҡара")
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
    # «Хочу переднее место». Одна живая такая бронь на поездку — держит индекс в базе.
    front_seat: bool = False
    # Где садится (18.10.2026): точное название начала маршрута (`from_city`) или остановки из
    # `ride.waypoints`. Не прислали или пусто — «неизвестно» (NULL, правила PR184). Своё название
    # вписать нельзя: незнакомое — 400. Потолок — как у остановки при публикации.
    pickup_stop: Optional[str] = Field(None, max_length=60)


# Отказ, когда переднее уже обещано другому пассажиру. Один текст на проверку и на барьер базы.
FRONT_SEAT_TAKEN = ("Переднее место уже занято", "Алғы урын инде алынған")


def _pickup_refusal(status: int, code: str, ru: str, ba: str) -> HTTPException:
    return HTTPException(status_code=status, detail={"ru": ru, "ba": ba, "code": code})


def _resolve_pickup_stop(ride: Ride, raw: Optional[str], entry) -> Optional[str]:
    """Что записать в `Booking.pickup_stop` (решения 18.10.2026, P2-5 и P2-В — умолчания в пользу
    защиты пассажира):

    • поездка без остановок — "" (начало), что бы ни прислали;
    • остановки в тексте есть, а допустимых нет (старая поездка с «ул. Ленина 5») — NULL;
    • не прислали или пусто — NULL: невнимательный пассажир остановки не попадает в правила начала;
    • начало маршрута — только его названием (любое написание: «Баймак»/«Баймаҡ») → "";
    • остановка из поездки → её написание из поездки;
    • конец маршрута — 400 `pickup_stop_is_end`; другое — 400 `pickup_stop_unknown`."""
    if not ride_has_stops_raw(ride, entry):
        return ""
    if not parse_stops(ride, entry):
        return None
    asked = (raw or "").strip()
    if not asked:
        return None
    if is_start(ride, asked, entry):
        return ""
    stop = canonical_stop(ride, asked, entry)
    if stop is not None:
        return stop
    if is_end(ride, asked, entry):
        raise _pickup_refusal(
            400, "pickup_stop_is_end",
            "Здесь поездка заканчивается — выбери, где садишься: начало маршрута или остановку по пути.",
            "Сәфәр бында тамамлана — ҡайҙа ултырғаныңды һайла: юлдың башы йәки юл ыңғайындағы туҡталыш.")
    raise _pickup_refusal(
        400, "pickup_stop_unknown",
        "Такой остановки нет в этой поездке — выбери из списка: начало маршрута или остановку по пути.",
        "Был сәфәрҙә бындай туҡталыш юҡ — исемлектән һайла: юлдың башы йәки юл ыңғайындағы туҡталыш.")


def _front_seat_is_taken(session: Session, ride_id: int) -> bool:
    return session.exec(select(Booking.id).where(
        Booking.ride_id == ride_id,
        Booking.front_seat == True,  # noqa: E712
        Booking.status.in_([BookingStatus.pending, BookingStatus.confirmed, BookingStatus.onboard]),
    )).first() is not None


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
    front_seat: bool = False          # пассажир просил переднее место — видят обе стороны
    # Где садится пассажир (18.10.2026): "" — начало маршрута, «Акъяр» — остановка, null —
    # неизвестно (или водителю уже не видно: бронь отменена / поездка давно закрыта).
    pickup_stop: Optional[str] = None
    ride_has_stops: bool = False      # у поездки есть остановки (null выше = «уточни в чате»)
    pickup: str = ""
    pickup_lat: Optional[float] = Field(None, ge=-90, le=90)
    pickup_lng: Optional[float] = Field(None, ge=-180, le=180)
    from_lat: Optional[float] = Field(None, ge=-90, le=90)
    from_lng: Optional[float] = Field(None, ge=-180, le=180)
    to_lat: Optional[float] = Field(None, ge=-90, le=90)
    to_lng: Optional[float] = Field(None, ge=-180, le=180)


@router.post("/bookings", response_model=Booking)
def book(body: BookIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    ensure_active(session, user.id)   # пауза лестницы «Справедливости» (§2) реально блокирует бронь
    # Потолок на ТЕМП бронирования. Каждая бронь будит водителя пушем, и отмена будит второй
    # раз: цикл «забронировал → отменил» давал 80 уведомлений за минуту одному человеку —
    # травля кнопкой, ровно как 300 сообщений в чате (проверено запросом, аудит 2026-08-12,
    # волна 51). Отменённые брони из счёта не исчезают: строка остаётся со статусом cancelled.
    guard_burst(session, Booking.id, Booking.created_at, Booking.passenger_id == user.id,
                per_minute=settings.flood_create_per_minute,
                ru=TOO_FAST_CREATING[0], ba=TOO_FAST_CREATING[1])
    if body.seats < 1:
        raise herr(400, "Количество мест должно быть больше 0", "Урын һаны 0-дан күберәк булырға тейеш")
    # FOR UPDATE: блокируем строку поездки на время транзакции → нет овербукинга при гонке.
    ride = session.exec(select(Ride).where(Ride.id == body.ride_id).with_for_update()).first()
    if not ride or ride.status != RideStatus.active:
        # Тут скрывать нечего (в отличие от отказа по блокировке — см. test_block_stays_a_secret):
        # поездку отменили или она уже уехала. Человек смотрит на карточку, которую открыл
        # десять минут назад, и сухое «недоступна» оставляет его гадать (волна 107).
        raise herr(400, "Поездку уже отменили или она уехала — посмотри другие в ленте",
                   "Сәфәр кире алынған йәки киткән — таҫмала башҡаларын ҡара")
    # Поездка, которая уехала давно, бронироваться не должна (аудит 2026-08-08, волна 151).
    #
    # Публикация в прошлое честно отбивается словами «Время выезда уже прошло», а бронь — нет:
    # проверялся только статус. Проверено пробой: забронировал рейс, уехавший 30 дней назад,
    # и получил 200. Человек открывает вчерашнюю карточку, старый пуш или ссылку из чата,
    # видит «ждём подтверждения» — и ждёт ответа, которого не будет: ночная уборка тихо погасит
    # бронь, а места в этой поездке всё это время числятся занятыми. Водителю при этом приходит
    # «Новая бронь» по рейсу месячной давности.
    #
    # Окно `RIDE_PAST_GRACE_HOURS` оставляем: поездка остаётся в выдаче ещё пару часов после
    # времени выезда, потому что уехать могли впритык и человек садится «на ходу».
    if ride.depart_at is not None:
        поздно = utcnow() - ride.depart_at > timedelta(hours=RIDE_PAST_GRACE_HOURS)
        if поздно:
            raise herr(400, "Эта поездка уже уехала — посмотри другие в ленте",
                       "Был сәфәр китте инде — таҫмала башҡаларын ҡара")
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
    # Где садится — разбираем ДО списания мест и до повтора (18.10.2026): незнакомое место — 400,
    # мест не тронули.
    pickup_stop = _resolve_pickup_stop(ride, body.pickup_stop, snapshot_cached())
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
        # Повтор с просьбой о переднем, а бронь уже есть без него: молча вернуть старую — значит
        # потерять просьбу (ревью Opus 2026-10-14). Говорим прямо, что делать.
        if body.front_seat and not existing.front_seat:
            raise herr(409, "У тебя уже есть бронь без переднего места. Отмени её и забронируй заново с передним",
                       "Һинең алғы урынһыҙ бронең бар инде. Уны кире ал да алғы урын менән яңынан бронла")
        # Повтор с ДРУГИМ известным местом посадки: молча вернуть старую — водитель поедет не туда.
        # Старая бронь без места (NULL) или повтор без места — возвращаем как есть.
        if (existing.pickup_stop is not None and pickup_stop is not None
                and existing.pickup_stop != pickup_stop):
            raise _pickup_refusal(
                409, "pickup_stop_conflict",
                "У тебя уже есть бронь с другим местом посадки. Отмени её и забронируй заново — или договорись с водителем в чате.",
                "Һинең икенсе ултырыу урыны менән бронең бар инде. Уны кире ал да яңынан бронла — йәки водитель менән чатта килеш.")
        return existing
    guard_delivery_ride(ride.category)
    # Быстрая понятная проверка «мест нет» — чтобы не гонять человека через остальные шаги.
    # Настоящая защита ниже: между этой строкой и списанием мест может вклиниться чужая бронь.
    if ride.seats_left < body.seats:
        raise herr(400, "Не хватает мест", "Урын етмәй")
    # Переднее место: водитель его предлагает и оно не обещано другому. Это быстрая понятная
    # проверка; настоящий барьер — уникальный индекс броней ниже, при сохранении.
    if body.front_seat:
        if not ride.front_seat_available:
            raise herr(409, "Водитель не предлагает переднее место — забронируй без него",
                       "Йөрөтөүсе алғы урынды тәҡдим итмәй — уныһыҙ бронла")
        if _front_seat_is_taken(session, ride.id):
            raise herr(409, *FRONT_SEAT_TAKEN)
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
                "Для поездки пассажира младше 18 укажи взрослого: имя и телефон. Он отвечает за поездку, и водителю есть кому позвонить.",
                "18-ҙән кесе юлсы өсөн оло кешене күрһәт: исеме һәм телефоны. Ул сәфәр өсөн яуаплы, водителгә шылтыратырға кем булыр.",
            )
    else:
        guardian_name = guardian_phone = ""   # не подросток — лишних ПДн не храним
    total_price = ride.price * body.seats
    # Договорённость об оплате: способ по умолчанию — «договоримся»; сумма — из цены поездки, если не задана.
    pay_method = body.pay_method or PayMethod.negotiate
    pay_amount = _clean_pay_amount(body.pay_amount)
    if pay_amount is not None and pay_amount < total_price:
        # Сумму ниже цены объявления пассажир в одиночку не ставит (ревью 2026-10-13): водитель
        # в карточке «Подтвердить» и в пуше о брони её не видит и подтвердил бы вслепую — карта
        # списала бы «1 ₽». Торг идёт через заявку и отклик, там цену называет водитель сам.
        raise herr(403, "Сумма не может быть меньше цены поездки. Хочешь дешевле — договорись с водителем в чате",
                   "Сумма сәфәр хаҡынан кәм була алмай. Арзаныраҡ кәрәк булһа — йөрөтөүсе менән чатта килеш")
    if pay_amount is None:
        pay_amount = total_price if total_price > 0 else None
    booking = Booking(
        ride_id=ride.id, passenger_id=user.id, seats=body.seats,
        price=total_price, boarding_code=gen_otp(),
        pay_method=pay_method, pay_amount=pay_amount,
        minor_passenger=bool(body.minor_passenger),
        minor_guardian_name=guardian_name, minor_guardian_phone=guardian_phone,
        front_seat=bool(body.front_seat),
        pickup_stop=pickup_stop,
    )
    # Место занимаем АТОМАРНО: «мест хватает» и вычитание — одним запросом к базе.
    #
    # Овербукинг и раньше был закрыт — блокировкой строки поездки в начале функции (см. выше
    # `with_for_update`). Но только на PostgreSQL: SQLite такую блокировку ИГНОРИРУЕТ, а на
    # ней работают тесты, локальная разработка и демо-база эмулятора. Там `seats_left -=`
    # оставалось обычной парой «прочитал — записал», между которой помещается чужая бронь,
    # и число мест уходило в минус (поездка тогда пропадает из поиска `seats_left > 0`).
    #
    # Хуже другое: саму защиту не проверял ни один тест — на SQLite `with_for_update()`
    # пустая операция, поэтому её удаление при рефакторинге не уронило бы ничего.
    #
    # Условие внутри `UPDATE` работает на ОБЕИХ базах: вторую бронь отсекает сама база,
    # ноль изменённых строк → мест уже нет. Блокировку строки оставляем — она сериализует
    # остальную проверку. Тот же приём у статуса заказа такси (`instant_service.set_status`).
    reserved = session.execute(
        update(Ride)
        .where(Ride.id == ride.id, Ride.seats_left >= body.seats)
        .values(seats_left=Ride.seats_left - body.seats)
    )
    if reserved.rowcount == 0:
        session.rollback()
        raise herr(409, "Места только что разобрали", "Урындар хәҙер генә алынды")
    session.add(booking)
    try:
        session.commit()
    except IntegrityError:
        # Две заявки на переднее в одно мгновение: проверку выше обе прошли, а вторую
        # отсекла база (uq_booking_front_seat_live). Откат возвращает и списанные места.
        session.rollback()
        if body.front_seat:
            raise herr(409, *FRONT_SEAT_TAKEN)
        raise
    session.refresh(booking)
    session.refresh(ride)   # объект в памяти помнит прежнее число мест — перечитываем
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
    front_ru = " · просит переднее место" if booking.front_seat else ""
    front_ba = " · алғы урынды һорай" if booking.front_seat else ""
    # Садится на остановке — пометка БЕЗ названия села (P3): пуш «Новая бронь» не прячется на
    # экране блокировки, а название села пассажирки — не для чужих глаз у телефона водителя.
    # Само место водитель видит в приложении (`/driver/bookings`, детали брони).
    stop_ru = " · посадка по пути" if booking.pickup_stop else ""
    stop_ba = " · юл ыңғайында ултыра" if booking.pickup_stop else ""
    push_notification(
        session, ride.driver_id, "booking",
        "Новая бронь", "Яңы бронь",
        f"{pax_name}: {route}, мест {body.seats}{stop_ru}{front_ru}{minor_ru}{women_hint_ru}",
        f"{pax_name}: {route}, {body.seats} урын{stop_ba}{front_ba}{minor_ba}{women_hint_ba}",
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
    unlocked = booking_contacts_open(booking, ride)
    is_driver = ride.driver_id == user.id
    # Контакты взрослого за подростка — водителю, пока бронь ЖИВАЯ. Отменённая ничего не
    # открывает: см. комментарий у полей ниже.
    # Завершённая поездка тоже перестаёт открывать контакты взрослого (волна 160). Отменённую
    # закрыли раньше, а `done` — это статус навсегда: спустя год водитель открывал старую бронь
    # и видел телефон мамы. Она в приложении не зарегистрирована, согласия не давала и удалить
    # свой номер не может никак. После поездки держим то же окно, что и для телефона самого
    # пассажира: пока идёт разбор и связь ещё нужна — открыто, дальше закрыто.
    guardian_visible = (
        is_driver
        and booking.status != BookingStatus.cancelled
        and (booking.status != BookingStatus.done or unlocked)
    )
    # Место посадки: пассажиру — своё всегда; водителю — по правилу контактов взрослого (живая бронь
    # и недавно завершённая), как в `/driver/bookings` (P3).
    entry = snapshot_cached()
    has_stops = ride_has_stops_raw(ride, entry)
    pickup_stop = booking.pickup_stop if has_stops else ""
    if is_driver and not guardian_visible:
        pickup_stop = None
    # Бронь на остановке: место встречи — сама остановка, пин начала маршрута ей не отдаём (он про
    # другую точку, часто у дома водителя).
    on_stop = has_stops and bool(booking.pickup_stop)
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
        # Пометку видят оба; контакты взрослого — только водителю (и сразу, ещё до подтверждения:
        # именно на этих данных он и решает, берёт ли ответственность).
        #
        # Но ОТМЕНЁННАЯ бронь их больше не открывает (аудит 2026-08-12, волна 53). Решать уже
        # нечего, поездки не будет — а это телефон человека, который в приложении не
        # зарегистрирован, согласия не давал и убрать свои данные не может. Правило «сделки нет —
        # контактов нет» действовало для телефона самого пассажира (`unlocked`), а тут его
        # забыли: пассажир отменял бронь через минуту, и номер мамы оставался у водителя.
        "minor_passenger": bool(booking.minor_passenger),
        "minor_guardian_name": (booking.minor_guardian_name if guardian_visible else ""),
        "minor_guardian_phone": (booking.minor_guardian_phone if guardian_visible else ""),
        "front_seat": bool(booking.front_seat),
        "pickup_stop": pickup_stop,
        "ride_has_stops": has_stops,
        "pickup": ((booking.pickup_stop if on_stop else ride.pickup) if unlocked else ""),
        "pickup_lat": (ride.pickup_lat if unlocked and not on_stop else None),
        "pickup_lng": (ride.pickup_lng if unlocked and not on_stop else None),
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
    """Записать договорённость обеих сторон; этот запрос не проводит платёж.

    Сумма меняется до выставления счёта/оплаты. Повтор той же суммы и запись способа
    допустимы после них; уже созданный счёт и квитанцию нельзя менять задним числом.
    """
    # Сериализуем правку с созданием счёта. Payment здесь только читаем:
    # применение оплаты блокирует Payment → Booking, обратный порядок опасен.
    booking = session.get(Booking, booking_id, with_for_update=True, populate_existing=True)
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    amount = _clean_pay_amount(body.pay_amount) if body.pay_amount is not None else None
    current_amount = booking.pay_amount if booking.pay_amount is not None else booking.price
    if amount is not None and amount != current_amount:
        issued = session.exec(select(Payment.id).where(
            Payment.booking_id == booking.id, Payment.purpose == "booking",
            Payment.status.in_(["pending", "succeeded"]),
        )).first()
        if booking.paid or issued is not None:
            raise herr(409, "Счёт уже выставлен. Сумму изменить нельзя.",
                       "Иҫәп инде сығарылған. Сумманы үҙгәртеп булмай.")
        # Ниже цены поездки сумму опускает только водитель. Раньше любая сторона писала любую
        # цифру: пассажир после поездки ставил «договорились на 1 ₽», карта списывала 1 ₽ вместо
        # цены, бронь считалась оплаченной, водителю зачислялся 1 ₽ — и он нигде на это не
        # соглашался (ревью 2026-10-12, P2, спит до ЮKassa). Скидку даёт водитель — он её и
        # записывает. Поднять может любой: пассажир — чаевые, водитель — доплату за багаж (сумму
        # к списанию пассажир видит и подтверждает в банке). Свою надбавку пассажир может
        # откатить — но не ниже цены поездки и не ниже скидки, которую дал водитель.
        floor = min(booking.price or 0, current_amount or 0)
        if amount < floor and ride.driver_id != user.id:
            raise herr(403, "Снизить сумму может только водитель — попроси его поменять",
                       "Сумманы тик йөрөтөүсе генә кәметә ала — унан үҙгәртеүен һора")
    if body.pay_method is not None:
        booking.pay_method = body.pay_method
    if amount is not None:
        booking.pay_amount = amount
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
    passenger = session.get(User, booking.passenger_id)
    role = "driver" if ride.driver_id == user.id else "passenger"
    counterparty = passenger if role == "driver" else driver
    # Возвращаем только СВОЮ оценку. Чужая остаётся анонимной и влияет лишь на агрегат.
    # Это позволяет экрану после поездки честно восстановить уже отправленные звёзды.
    my_rating = session.exec(select(Rating).where(
        Rating.booking_id == booking.id,
        Rating.rater_id == user.id,
    )).first()
    amount = booking.pay_amount if booking.pay_amount is not None else booking.price
    return {
        "booking_id": booking.id,
        "ride_id": ride.id,
        "role": role,
        "from_city": ride.from_city,
        "to_city": ride.to_city,
        "depart_at": ride.depart_at.isoformat() if ride.depart_at else "",
        "seats": booking.seats,
        "amount": amount,                       # ₽; договорённость (pay_amount) или цена брони
        "pay_method": booking.pay_method.value if hasattr(booking.pay_method, "value") else booking.pay_method,
        "paid": bool(booking.paid),
        "driver_name": (driver.name if driver and driver.name else "Водитель"),
        "driver_verified": bool(driver.verified) if driver else False,
        "counterparty_name": (counterparty.name if counterparty and counterparty.name else
                              ("Пассажир" if role == "driver" else "Водитель")),
        "my_stars": int(my_rating.stars) if my_rating else 0,
        "my_rating_tags": (my_rating.tags or "") if my_rating else "",
        # Вторая сторона — для «Сообщить о проблеме» пассажира (разбор по брони, POST /incidents).
        "counterparty_id": counterparty.id if counterparty else 0,
        "problem_report_sec": problem_report_left(booking, ride) if role == "passenger" else 0,
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
    нужные кнопки статуса (водитель: «выехал/подъезжаю/сел/завершить»; пассажир — после отметки
    водителя «доехал/завершить», до неё — «Водитель отметит посадку»)."""
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    # + статус и подфаза → экран активной поездки опрашивает это и показывает пассажиру live-баннер
    # «водитель выехал/подъезжает» (раньше это приходило только пушем, в UI не обновлялось).
    is_driver = ride.driver_id == user.id
    return {
        "alone_with_driver": _alone_with_driver(session, booking, ride, user),
        "role": "driver" if is_driver else "passenger",
        "status": booking.status,
        "driver_phase": booking.driver_phase,
        # true = сервер сам сверил «подъезжаю» с GPS водителя. Пассажир видит «подтверждено по GPS»
        # и знает, что машина правда рядом, а не «уже почти» на словах.
        "arrival_verified": bool(booking.arrival_verified),
        # Вторая сторона брони: пассажиру — водитель, водителю — пассажир. Пассажир, который ждёт
        # отметки «Сел», открывает по ней «Сообщить о проблеме» (разбор POST /incidents).
        "peer_id": booking.passenger_id if is_driver else ride.driver_id,
        # Сколько секунд водитель ещё может отменить свою отметку «Сел» (пассажиру всегда 0).
        "boarding_undo_sec": boarding_undo_left(booking) if is_driver else 0,
        # Сколько ещё пассажиру показывать «Сообщить о проблеме» после завершения (водителю 0).
        "problem_report_sec": 0 if is_driver else problem_report_left(booking, ride),
    }


class DriverStatusIn(BaseModel):
    status: str  # departed | arriving | boarded | done


def _pickup_point(ride: Ride) -> Optional[tuple]:
    """Куда водитель должен подъехать — ТОЛЬКО точный пин точки сбора.

    Центр города сюда намеренно НЕ подставляем: Уфа больше 20 км в поперечнике, и водитель,
    честно забирающий пассажира в Черниковке, оказался бы «в 12 км от центра» и получил отказ.
    Нет пина — нет проверки; лучше пропустить обман, чем остановить честного.
    """
    if ride.pickup_lat is not None and ride.pickup_lng is not None:
        return (ride.pickup_lat, ride.pickup_lng)
    return None


def _verify_arrival(session: Session, booking: Booking, ride: Ride) -> Optional[float]:
    """Сверить «подъезжаю» с живым GPS водителя. Возвращает расстояние в метрах или None.

    None = проверить нечем (выключено рубильником, нет пина подачи, Redis молчит или водитель
    ещё не прислал ни одного кадра). Тогда пропускаем как раньше — доверяем слову. Блокируем
    только когда ТОЧНО знаем, что водитель далеко: ложный отказ дороже пропущенного обмана.

    Точки — все свежие точки водителя по ЛЮБОЙ подтверждённой брони этого рейса
    (`livepos.driver_points_for_ride`, 18.10.2026), а не только по этой: водитель открыл поездку
    брони A, а «Подъезжаю» жмёт по брони B — кэш B пуст, и раньше проверка молча пропускала.
    Сверяем БЛИЖАЙШУЮ к пину: второе устройство водителя дома (планшет с открытой бронью) не
    должно давать ложный отказ тому, кто правда у подъезда (приёмка PR0, P2). Плюс внутренняя
    точка по рейсу (`with_ride_point`, приёмка PR186, A1): водитель открыл бронь, чьё окно ещё
    закрыто, — в кэш брони точка не легла, а проверить его слово всё равно есть чем.
    """
    if not settings.arrival_verify_enabled:
        return None
    target = _pickup_point(ride)
    if target is None:
        return None
    return _nearest_m(livepos.driver_points_for_ride(session, ride, with_ride_point=True), target)


def _nearest_m(points: list, target: tuple) -> Optional[float]:
    """Расстояние в метрах от ближайшей из точек до цели; None — годных точек нет.

    Негодные точки пропускаем (приёмка PR0, P2): кадр `{"lat": NaN}` проходит проверку типа и
    анти-телепорт (сравнения с NaN ложны) и ложится в кэш, а «NaN > радиуса» — тоже ложь: без этой
    защиты такая точка давала «подтверждено по GPS». Пропуск, а не отказ: с одной негодной точкой
    проверить нечем (без галочки), а рядом с ней нормальная дальняя точка по-прежнему даёт отказ."""
    dists = []
    for p in points:
        try:
            lat, lng = float(p["lat"]), float(p["lng"])
        except (TypeError, ValueError, KeyError):
            continue
        if not (math.isfinite(lat) and math.isfinite(lng) and -90.0 <= lat <= 90.0 and -180.0 <= lng <= 180.0):
            continue
        dists.append(haversine_km(lat, lng, target[0], target[1]) * 1000.0)
    return min(dists) if dists else None


def _distance_to_stop_km(session: Session, ride: Ride, place) -> Optional[float]:
    """«Подъезжаю» к остановке пассажира: сколько км от машины до неё. None — проверить нечем или
    нельзя: выключено своим рубильником (`arrival_verify_stops_enabled`), справочник остановку не
    знает или она — город (центр не подставляем, P2-1), водитель не прислал свежей точки.

    Точки машины — все свежие по подтверждённым броням рейса (`livepos.driver_points_for_ride`, PR0):
    водитель открыл поездку пассажира начала, а «Подъезжаю» жмёт для пассажирки остановки. Сверяем
    БЛИЖАЙШУЮ к остановке — второе устройство водителя дома не даёт ложного отказа (приёмка PR0)."""
    if not settings.arrival_verify_stops_enabled:
        return None
    point = place.point
    if point is None or point.kind not in VILLAGE_KINDS:
        return None
    meters = _nearest_m(livepos.driver_points_for_ride(session, ride, with_ride_point=True), (point.lat, point.lng))
    return meters / 1000.0 if meters is not None else None


@router.post("/bookings/{booking_id}/driver-status")
def driver_status(booking_id: int, body: DriverStatusIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель отмечает «выехал/подъезжаю» → push пассажиру (закрывает тревогу ожидания).
    «boarded» — водитель отмечает, что пассажир сел (решение 17.10.2026, см. `_mark_boarded`).
    «done» — водитель завершает поездку: закрываем бронь (раньше закрыть мог ТОЛЬКО пассажир →
    если он забывал нажать «Завершить», бронь висела активной, а места поездки не освобождались)."""
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    if ride.driver_id != user.id:
        raise herr(403, "Только водитель", "Тик йөрөтөүсе")
    if body.status not in {"departed", "arriving", "boarded", "done"}:
        raise herr(400, "Недопустимый статус", "Ярамаған хәл")
    if body.status == "boarded":
        return _mark_boarded(session, booking, ride)
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
            booking.done_at = utcnow()       # окно «Сообщить о проблеме» пассажира считается и от неё
            booking.driver_phase = ""        # поездка кончилась — фазу сбрасываем
            session.add(booking)
            session.commit()
            trip_wake.poke(booking.id)       # канал координат закрывается сразу (см. cancel_booking)
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
    # «Выехал» — не раньше чем за LIVE_GEO_LEAD_HOURS до выезда (P2-Г): за сутки это не правда, а
    # кнопка, которая будит пассажира пушем «Водитель выехал». Отказ раньше любых записей.
    if body.status == "departed" and departed_wait_left(ride) > 0:
        raise _departed_too_early_refusal(ride)
    # Честное «подъезжаю»: слово водителя сверяем с его же GPS. Пассажир, увидев «подъезжает»,
    # выходит из дома — на морозе цена вранья высокая. Ловим только доказанный случай (см. _verify_arrival).
    #
    # Где сверять — по месту посадки этой брони (18.10.2026, `ride_stops.pickup_of`): пассажир начала
    # (и любая поездка без остановок) — с пином подачи; пассажир остановки — с координатами ЕГО
    # остановки из справочника сёл. Неизвестная справочнику остановка, город и бронь без места
    # (PR184) — без отказа и без «подтверждено по GPS».
    if body.status == "arriving":
        entry = snapshot_cached()
        # Раньше срока «Подъезжаю» не принимаем (приёмка PR184; у остановки — позже на путь до неё):
        # ни фазы, ни `arriving_at`, ни пуша — отказ раньше любых записей.
        if arriving_wait_left(ride, booking=booking, entry=entry) > 0:
            raise _arriving_too_early_refusal(ride, booking=booking, entry=entry)
        place = pickup_of(ride, booking, entry)
        verified = False
        if place.kind == "start":
            meters = _verify_arrival(session, booking, ride)
            if meters is not None and meters > settings.arrival_verify_radius_m:
                km = meters / 1000.0
                raise herr(
                    409,
                    f"Ты ещё далеко от места подачи (≈{km:.1f} км). "
                    "Нажми «Подъезжаю», когда будешь рядом — пассажир выйдет ровно к твоему приезду.",
                    f"Һин алыу урынынан алыҫ әле (≈{km:.1f} км). "
                    "«Яҡынлашам»ды яҡын килгәс баҫ — юлсы тап һин килгәнгә сыға.",
                )
            verified = meters is not None
        elif place.kind == "stop":
            km = _distance_to_stop_km(session, ride, place)
            if km is not None and km > settings.stop_refuse_km:
                raise _arriving_too_far_refusal(place.stop, km)
            verified = km is not None and km <= settings.stop_verified_km
        booking.arrival_verified = verified
        # Первое «Подъезжаю» по брони — от него считается «Не пришёл» (`no_show_available_at`).
        # Условный UPDATE: повтор, второй телефон или двойной тап в ту же секунду время не сдвигают.
        session.execute(update(Booking).where(
            Booking.id == booking.id, Booking.arriving_at.is_(None),
        ).values(arriving_at=utcnow()))
    else:
        booking.arrival_verified = False     # «выехал» — до места ещё ехать, подтверждать нечего
    # Уведомляем, только если фаза РЕАЛЬНО сменилась. Раньше пуш уходил на каждое нажатие:
    # тридцать раз «Водитель выехал» одному пассажиру, и всё это молча, кнопкой (проверено
    # запросом, аудит 2026-08-12, волна 52). У SMS близким такое правило уже стояло
    # («шлём только тем, у кого статус реально сменился»), а у пуша пассажиру — нет.
    phase_changed = booking.driver_phase != body.status
    booking.driver_phase = body.status       # сохраняем «выехал/подъезжает» → пассажир увидит live, не только пушем
    session.add(booking)
    session.commit()
    # Живой канал координат помнит «ещё не поехали» до 30 с (`location._Пора`). «Выехал» — ровно тот
    # момент, когда точка обязана пойти сразу, поэтому толкаем канал: его следующий кадр идёт в базу.
    trip_wake.poke(booking.id)
    if phase_changed:
        title_ru = {"departed": "Водитель выехал", "arriving": "Водитель подъезжает"}[body.status]
        title_ba = {"departed": "Водитель юлға сыҡты", "arriving": "Водитель яҡынлаша"}[body.status]
        route = f"{ride.from_city} → {ride.to_city}"
        # urgent: ранний рейс в 5 утра — обычное дело, а тихие часы глушили именно то уведомление,
        # ради которого пассажир выходит к машине (приёмка волны 1, 16.10.2026).
        push_notification(
            session, booking.passenger_id, "ride",
            title_ru, title_ba,
            route, route,
            ref_kind="booking", ref_id=booking.id,
            urgent=True,
        )
    # Срок «Не пришёл» после этой отметки — карточка «Едут с тобой» берёт его сразу, не перечитывая
    # список: после «Подъезжаю» подпись «Сначала нажми «Подъезжаю»» сменяется на «можно отметить с …».
    return {"ok": True, "driver_phase": body.status, **_no_show_fields(ride, booking)}


def _no_show_fields(ride: Ride, booking: Booking) -> dict:
    """Сроки «Не пришёл» и «Подъезжаю» для клиента — одинаково в строке `/driver/bookings` и в ответе
    «Подъезжаю».

    `no_show_at` — момент (наивный UTC, как `depart_at`), с которого кнопка доступна; null — считать
    не от чего или ждём «Подъезжаю». `no_show_wait_sec` — сколько секунд до него от этого ответа
    (как в отказе `no_show_too_early`): по нему клиент ждёт, даже если часы телефона спешат.
    `no_show_needs_arriving` — поездка с остановками, а «Подъезжаю» по этой брони ещё не было:
    вместо кнопки клиент пишет «Сначала нажми «Подъезжаю»». `arriving_opens_at` / `arriving_wait_sec` —
    то же для самой «Подъезжаю» (за DONE_EARLY_GRACE до выезда): до этого кнопка на карточке неактивна."""
    entry = snapshot_cached()
    at = no_show_available_at(ride, booking, entry)
    arriving_at = arriving_opens_at(ride, booking, entry)
    return {
        "no_show_at": at.isoformat() if at else None,
        "no_show_wait_sec": no_show_wait_left(ride, booking=booking, entry=entry),
        "no_show_needs_arriving": no_show_needs_arriving(ride, booking, entry),
        "arriving_opens_at": arriving_at.isoformat() if arriving_at else None,
        "arriving_wait_sec": arriving_wait_left(ride, booking=booking, entry=entry),
    }


def _local_hhmm(moment) -> str:
    """Наивный UTC → «ЧЧ:ММ» по местному времени (Уфа): человек сверяет со своими часами."""
    return (moment + timedelta(hours=settings.local_tz_offset_hours)).strftime("%H:%M")


def _arriving_too_early_refusal(ride: Ride, now=None, booking: Optional[Booking] = None, entry=None) -> HTTPException:
    """Отказ «Подъезжаю» раньше срока (приёмка PR184; для остановки — 18.10.2026).

    Пассажир по «Водитель подъезжает» выходит к дороге — за неделю или за день до поездки это
    вранье, и срочное (мимо тихих часов). `code` — машинная причина, `arriving_opens_at` — момент,
    с которого можно, `arriving_wait_sec` — сколько секунд осталось (часы телефона врут на минуты).

    Бронь на остановке, до которой ещё ехать: текст называет остановку и время по местным часам и
    подсказывает, что делать сейчас (P3): позвонить или написать; «Сел» отметить можно."""
    minutes = int(DONE_EARLY_GRACE.total_seconds() // 60)
    at = arriving_opens_at(ride, booking, entry)
    base = arriving_opens_at(ride)
    place = pickup_of(ride, booking, entry) if booking is not None else None
    if place is not None and place.kind == "stop" and at is not None and base is not None and at > base:
        hhmm = _local_hhmm(at)
        ru = (f"Рано: до остановки «{place.stop}» ещё ехать. «Подъезжаю» можно с {hhmm}. "
              "Позвони или напиши пассажиру; «Сел» отметить можно.")
        ba = (f"Иртә: «{place.stop}» туҡталышына барып етергә әле бар. «Яҡынлашам»ға {hhmm} үткәс баҫып була. "
              "Юлсыға шылтырат йәки яҙ; «Ултырҙы»ны билдәләп була.")
    else:
        ru = f"Рано: «Подъезжаю» можно нажать за {minutes} минут до выезда — пассажир выйдет к машине вовремя."
        ba = f"Иртә: «Яҡынлашам»ға сығыуға {minutes} минут ҡалғас ҡына баҫып була — юлсы машинаға ваҡытында сығыр."
    return HTTPException(status_code=409, detail={
        "ru": ru,
        "ba": ba,
        "code": "arriving_too_early",
        "arriving_opens_at": at.isoformat() if at else None,
        "arriving_wait_sec": arriving_wait_left(ride, now, booking, entry),
    })


def _arriving_too_far_refusal(stop: str, km: float) -> HTTPException:
    """«Подъезжаю» дальше `stop_refuse_km` от остановки пассажира (18.10.2026). Расстояние видит только
    водитель — это расстояние от него самого. Без записей: ни фазы, ни `arriving_at`, ни пуша."""
    return HTTPException(status_code=409, detail={
        "ru": (f"Ты ещё далеко от остановки «{stop}» (≈{km:.1f} км). "
               "Нажми «Подъезжаю», когда будешь рядом — пассажир выйдет ровно к твоему приезду."),
        "ba": (f"Һин «{stop}» туҡталышынан алыҫ әле (≈{km:.1f} км). "
               "«Яҡынлашам»ды яҡын килгәс баҫ — юлсы тап һин килгәнгә сыға."),
        "code": "arriving_too_far",
        "distance_km": round(km, 1),
    })


def _departed_too_early_refusal(ride: Ride, now=None) -> HTTPException:
    """«Выехал» раньше чем за LIVE_GEO_LEAD_HOURS до выезда (P2-Г, 18.10.2026)."""
    at = departed_opens_at(ride)
    return HTTPException(status_code=409, detail={
        "ru": "Рано: «Я выехал» можно нажать за час до выезда — иначе пассажир получит «Водитель выехал» раньше времени.",
        "ba": "Иртә: «Сыҡтым»ға сығыуға бер сәғәт ҡалғас ҡына баҫып була — юҡһа юлсы «Водитель юлға сыҡты» хәбәрен ваҡытынан алда алыр.",
        "code": "departed_too_early",
        "departed_opens_at": at.isoformat() if at else None,
        "departed_wait_sec": departed_wait_left(ride, now),
    })


def _driver_blocked_passenger_refusal() -> HTTPException:
    """«Сел» по карточке пассажира, которого водитель САМ заблокировал (решение лида по PR170, P2-2).

    Иначе блокировка до посадки оживала бы: водитель заблокировал, потом отметил «Сел» (промахнулся
    карточкой или нарочно), она блокирует его в ответ — самая свежая блокировка пары уже после
    посадки, канал водителя пишет точку для ссылки (`location._пишем_без_пересылки`), и она видит
    его машину по своей ссылке, не садясь. Свою блокировку водитель знает — отказ ничего не
    раскрывает. Блокировку со стороны пассажира здесь НЕ проверяем: отказ выдал бы её; её случай
    закрывает правило времени (блок до посадки — канал закрыт).
    `code` — машинная причина для клиента."""
    return HTTPException(status_code=409, detail={
        "ru": "Этот попутчик у тебя в блоке — посадку не отметить. Если всё же едет с тобой, сначала разблокируй.",
        "ba": "Был юлдаш һинең блокта — ултырыуҙы билдәләп булмай. Барыбер һинең менән барһа, башта блоктан сығар.",
        "code": "driver_blocked_passenger",
    })


def _boarding_out(booking: Booking) -> dict:
    """Ответ на «Сел» и «Отменить»: итоговый статус и сколько секунд ещё можно отменить."""
    status = booking.status.value if hasattr(booking.status, "value") else booking.status
    return {"ok": True, "status": status, "boarding_undo_sec": boarding_undo_left(booking)}


def _mark_boarded(session: Session, booking: Booking, ride: Ride) -> dict:
    """Водитель отметил «Сел»: подтверждённая бронь → «в пути» (onboard).

    Решение Александра 17.10.2026: посадку отмечает водитель, а не пассажир. Всё, что раньше
    запускал пассажирский «Я сел» (`family.py`, trip-status «sat»), теперь запускает эта отметка:
    бронь «в пути» (а с ней — запрет отмены пассажиром `onboard_no_cancel` и отказ неявки
    `passenger_onboard`), толчок живому каналу координат, уведомление пассажиру и SMS близким,
    с которыми он поделился поездкой. Ссылка близкого и SOS читают статус брони — и видят «в пути».

    Правила: только водитель этой поездки (проверено выше), только подтверждённая бронь, не раньше
    DONE_EARLY_GRACE до выезда — та же планка, что была у «Я сел». Повтор — тот же ответ без побочек.
    """
    if booking.status == BookingStatus.onboard:
        return _boarding_out(booking)              # повторный тап / повтор после потерянного ответа
    if booking.status != BookingStatus.confirmed:
        raise herr(409, "Посадку можно отметить только у подтверждённого пассажира",
                   "Ултырыуҙы тик раҫланған пассажирға ғына билдәләп була")
    if session.exec(select(Block.id).where(
            Block.user_id == ride.driver_id, Block.blocked_user_id == booking.passenger_id)).first() is not None:
        raise _driver_blocked_passenger_refusal()   # только СВОЙ блок водителя — её блок не выдаём
    if ride.depart_at and utcnow() < ride.depart_at - DONE_EARLY_GRACE:
        minutes = int(DONE_EARLY_GRACE.total_seconds() // 60)
        raise herr(409, f"Посадку можно отметить за {minutes} минут до выезда — не раньше",
                   f"Ултырыуҙы сығыуға {minutes} минут ҡалғас ҡына билдәләп була")
    # Условный UPDATE: параллельная отмена или неявка не превратится обратно в «в пути».
    moved = session.execute(update(Booking).where(
        Booking.id == booking.id, Booking.status == BookingStatus.confirmed,
    ).values(status=BookingStatus.onboard, onboard_at=utcnow())).rowcount
    session.commit()
    session.refresh(booking)
    if not moved:
        if booking.status == BookingStatus.onboard:
            return _boarding_out(booking)
        raise herr(409, "Бронь уже изменилась — обнови экран",
                   "Бронь үҙгәргән инде — экранды яңырт")
    # Пока поездка не началась, канал координат перепроверяет базу раз в 30 с (`trip_wake`):
    # «в пути» — ровно тот момент, когда точка обязана пойти сразу.
    trip_wake.poke(booking.id)
    route = f"{ride.from_city} → {ride.to_city}"
    # Пассажиру — сразу: если водитель промахнулся карточкой, человек у подъезда узнает об этом
    # из уведомления, а не когда машина уедет без него. urgent — как «Выехал / Подъезжаю»: ранний
    # рейс в 5 утра попадает в тихие часы, а это ровно то уведомление, после которого действуют.
    push_notification(
        session, booking.passenger_id, "ride",
        "Водитель отметил посадку", "Йөрөтөүсе ултырыуҙы билдәләне",
        f"{route}. Хорошей дороги! Ещё не сел — открой поездку и сообщи о проблеме.",
        f"{route}. Хәйерле юл! Әле ултырмаһаң — сәфәрҙе ас та проблема тураһында хәбәр ит.",
        ref_kind="booking", ref_id=booking.id,
        urgent=True,
    )
    # Близким — то же «сел в машину», что раньше уходило по «Я сел» (SMS только при смене статуса).
    from .family import notify_family_trip_status
    passenger = session.get(User, booking.passenger_id)
    if passenger is not None:
        notify_family_trip_status(session, booking.id, passenger, "sat")
        session.refresh(booking)
    return _boarding_out(booking)


@router.post("/bookings/{booking_id}/boarded/undo")
def undo_boarded(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель отменяет свою отметку «Сел» — промахнулся карточкой. Бронь снова подтверждена.

    Только водитель этой поездки и только в первые BOARDING_UNDO_WINDOW после отметки. Дальше
    отметка — его слово, а спор решает разбор («Сообщить о проблеме»), а не кнопка: иначе «Сел»
    → «Отменить» → «Не пришёл» снова открывал бы ту же дыру, которую закрывает решение 17.10.
    Посадку, поставленную старым «Я сел» пассажира (без `onboard_at`), водитель не отменяет.
    Повтор после успешной отмены отвечает тем же итогом."""
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    if ride.driver_id != user.id:
        raise herr(403, "Отменить посадку может только водитель поездки",
                   "Ултырыуҙы тик сәфәр йөрөтөүсеһе генә кире ала ала")
    if booking.status == BookingStatus.confirmed:
        return _boarding_out(booking)              # уже отменено (повтор) — итог тот же
    if booking.status != BookingStatus.onboard:
        raise herr(409, "Поездка уже не в пути — отменить посадку нельзя",
                   "Сәфәр юлда түгел инде — ултырыуҙы кире алып булмай")
    if boarding_undo_left(booking) <= 0:
        minutes = int(BOARDING_UNDO_WINDOW.total_seconds() // 60)
        raise herr(409, f"Отменить посадку можно в первые {minutes} минут. Если ошибся — сообщи о проблеме, разберёмся.",
                   f"Ултырыуҙы тәүге {minutes} минутта ғына кире алып була. Яңылышһаң — проблема тураһында хәбәр ит, тикшерербеҙ.")
    # Условный UPDATE по той же отметке: «Завершить» или вторая отмена в ту же секунду не откатятся.
    moved = session.execute(update(Booking).where(
        Booking.id == booking.id, Booking.status == BookingStatus.onboard,
        Booking.onboard_at == booking.onboard_at,
    ).values(status=BookingStatus.confirmed, onboard_at=None)).rowcount
    session.commit()
    session.refresh(booking)
    if not moved:
        if booking.status == BookingStatus.confirmed:
            return _boarding_out(booking)
        raise herr(409, "Поездка уже изменилась — обнови экран",
                   "Сәфәр үҙгәргән инде — экранды яңырт")
    trip_wake.poke(booking.id)
    # Пассажир уже получил «Водитель отметил посадку» — снимаем тревогу тем же каналом (urgent:
    # пришло в тихие часы первое — должно прийти и это).
    route = f"{ride.from_city} → {ride.to_city}"
    push_notification(
        session, booking.passenger_id, "ride",
        "Отметку посадки отменили", "Ултырыу билдәһе кире алынды",
        f"{route}. Водитель отметил по ошибке — отметит снова, когда сядешь.",
        f"{route}. Йөрөтөүсе яңылыш билдәләгән — ултырғас, яңынан билдәләр.",
        ref_kind="booking", ref_id=booking.id,
        urgent=True,
    )
    # Близким, которым ушло «сел в машину», — «отметку посадки отменили» (не чаще раза на бронь,
    # `family.notify_family_trip_status`, статус «unsat»).
    from .family import notify_family_trip_status
    passenger = session.get(User, booking.passenger_id)
    if passenger is not None:
        notify_family_trip_status(session, booking.id, passenger, "unsat")
    session.refresh(booking)
    return _boarding_out(booking)


def _booking_out(booking: Booking) -> dict:
    """Бронь в ответ на действие (подтвердить / отменить / неявка) — без контактов взрослого за
    подростка, если сделки больше нет. Правило то же, что в карточке брони (`guardian_visible`,
    волна 53): отменили — телефона мамы у водителя нет. Ответ на действие его и не должен нести:
    клиенты берут контакты только из `/details`, а повтор «Не пришёл» или «Отменить» по уже
    закрытой брони иначе перечитывал бы чужой номер сколько угодно (приёмка PR150).
    Копия, а не правка объекта сессии: пустые поля не должны записаться в базу. Поля читаем через
    getattr: после commit объект «протухает», и чтение подтягивает их из базы, а не берёт умолчания."""
    data = {name: getattr(booking, name) for name in Booking.model_fields}
    if booking.status in (BookingStatus.cancelled, BookingStatus.done):
        data["minor_guardian_name"] = ""
        data["minor_guardian_phone"] = ""
        # Место посадки — по тому же правилу (P3, 18.10.2026): сделки нет — села пассажирки у
        # водителя в ответе на действие нет. Своё место пассажир видит в `/bookings/mine`.
        data["pickup_stop"] = None
    return data


@router.post("/bookings/{booking_id}/confirm", response_model=Booking)
def confirm_booking(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    if ride.driver_id != user.id:
        raise herr(403, "Подтвердить бронь может только водитель", "Броньды тик водитель генә раҫлай ала")
    if booking.status == BookingStatus.confirmed:
        return _booking_out(booking)         # идемпотентно (повторный тап) — без побочек
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
    booking.confirmed_at = utcnow()   # доказательство встречи сторон (волна 158)
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
    return _booking_out(booking)


class CancelIn(BaseModel):
    # Код причины из пресетов UI (changed_mind/found_other/…); пусто = не указана.
    # Потолок — на случай, когда вместо кода приходит сочинение (волна 96).
    reason: str = Field("", max_length=200)


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
            return _booking_out(booking)       # другая параллельная отмена опередила — места уже возвращены
        # Та же неявка другой дверью: водитель отменяет бронь «в пути» с причиной «не пришёл».
        # Правило одно (решения 14.10 и 17.10.2026): посадка отмечена — спор решает разбор.
        if (booking.status == BookingStatus.onboard and user.id == ride.driver_id
                and body is not None and (body.reason or "").strip() == "no_show"):
            raise _no_show_onboard_refusal(booking)
        # Пассажир уже в машине (водитель отметил «Сел») — отмены нет (как в приложении и на сайте:
        # кнопки «в пути» нет).
        # Иначе прямой запрос возвращал место в продажу, пока человек сидит в машине, а у брони
        # пропадали чат, «поделиться с близким» и SOS по ней (приёмка PR155, 17.10.2026).
        if booking.status == BookingStatus.onboard and user.id == booking.passenger_id:
            raise _onboard_cancel_refusal()
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
        trip_wake.poke(booking.id)   # канал координат закрывается сразу, а не через сторож (≤ 30 с)
        notify_map_changed()   # места вернулись → поездка снова видна на карте live
        # Уведомление ДРУГОЙ стороне: кто не отменял (пассажир отменил → водителю, и наоборот).
        other_id = ride.driver_id if user.id == booking.passenger_id else booking.passenger_id
        route = f"{ride.from_city} → {ride.to_city}"
        # Раньше сюда уходил ГОЛЫЙ МАРШРУТ — «Бронь отменена · Уфа → Сибай», и всё. Человек,
        # который рассчитывал уехать, оставался без двух вещей сразу: без причины и без
        # понимания, что делать дальше (аудит сценариев 30.08). Причину пишем ту, что выбрал
        # отменивший (список CANCEL_REASONS), а хвост про другие поездки — только пассажиру:
        # водителю искать нечего, у него своя поездка на месте.
        отменил_водитель = user.id != booking.passenger_id
        причина_ru, причина_ba = cancel_reason_text(booking.cancel_reason)
        тело_ru = route + (f". {причина_ru}" if причина_ru else "")
        тело_ba = route + (f". {причина_ba}" if причина_ba else "")
        if отменил_водитель:
            тело_ru += " Посмотри другие поездки по этому маршруту — они есть."
            тело_ba += " Был юл буйынса башҡа сәфәрҙәрҙе ҡара — улар бар."
        push_notification(
            session, other_id, "booking",
            "Бронь отменена", "Бронь ҡалдырылды",
            тело_ru, тело_ba,
            ref_kind="booking", ref_id=booking.id,
        )
    return _booking_out(booking)


def _onboard_cancel_refusal() -> HTTPException:
    """Отказ отмены пассажиру «в пути»: что делать вместо отмены — на двух языках.

    «В пути» ставит водитель своей отметкой «Сел» (17.10.2026), и отказ видит прежде всего тот,
    кто НЕ садился: водитель отметил не того или уехал без него. Поэтому текст говорит, что
    случилось, и ведёт к разбору — «Сообщить о проблеме» есть на экране поездки (приёмка PR174)."""
    return HTTPException(status_code=409, detail={
        "ru": "Водитель отметил посадку. Не садился — сообщи о проблеме.",
        "ba": "Йөрөтөүсе ултырыуҙы билдәләне. Ултырмаған булһаң — проблема тураһында хәбәр ит.",
        "code": "onboard_no_cancel",
    })


def _no_show_onboard_refusal(booking: Booking) -> HTTPException:
    """Отказ «неявки» по брони «в пути»: что случилось и что делать дальше — на двух языках.
    `code` — машинная причина: по ней клиент сразу меняет «Не пришёл» на «Пассажир в машине».
    С 17.10.2026 посадку отмечает сам водитель: промахнулся — «Отменить» в первые минуты.
    `boarding_undo_sec` — сколько их осталось: карточка показывает «Отменить» с верным отсчётом,
    а не сразу разбор (приёмка PR174)."""
    minutes = int(BOARDING_UNDO_WINDOW.total_seconds() // 60)
    return HTTPException(status_code=409, detail={
        "ru": f"Пассажир уже отмечен в машине — неявку не отметить. Отметил по ошибке — нажми «Отменить» "
              f"в первые {minutes} минут, позже — сообщи о проблеме: разберёмся.",
        "ba": f"Пассажир машинала тип билдәләнгән инде — килмәүҙе билдәләп булмай. Яңылыш билдәләһәң — "
              f"тәүге {minutes} минутта «Кире алырға»ға баҫ, һуңыраҡ — проблема тураһында хәбәр ит: тикшерербеҙ.",
        "code": "passenger_onboard",
        "boarding_undo_sec": boarding_undo_left(booking),
    })


def _no_show_too_early_refusal(ride: Ride, booking: Optional[Booking] = None, now=None) -> HTTPException:
    """Отказ «Не пришёл» раньше срока: пассажир ещё может подойти (решение 18.10.2026).

    `code` — машинная причина, `no_show_wait_sec` — сколько секунд осталось (для клиента, как
    `boarding_undo_sec` в отказе `passenger_onboard`), `no_show_at` — момент, с которого можно.
    Клиент по ним не гадает про срок, а берёт его отсюда. Текст называет, от чего считаем: от
    выезда или от «Подъезжаю», если водитель нажал его позже выезда."""
    minutes = int(NO_SHOW_WAIT.total_seconds() // 60)
    at = no_show_available_at(ride, booking)
    arriving = booking.arriving_at if booking else None
    if arriving and (ride.depart_at is None or arriving > ride.depart_at):
        since_ru, since_ba = "после «Подъезжаю»", "«Яҡынлашам»дан һуң"
    else:
        since_ru, since_ba = "после времени выезда", "сығыу ваҡытынан"   # слово в слово как в PR182
    return HTTPException(status_code=409, detail={
        "ru": f"Пока рано: пассажир ещё может подойти. Неявку можно отметить через {minutes} минут {since_ru}.",
        "ba": f"Әле иртә: пассажир килеп етеүе мөмкин. Килмәүҙе {since_ba} {minutes} минут үткәс кенә билдәләп була.",
        "code": "no_show_too_early",
        "no_show_wait_sec": no_show_wait_left(ride, now, booking),
        "no_show_at": at.isoformat() if at else None,
    })


def _no_show_needs_arriving_refusal() -> HTTPException:
    """Отказ «Не пришёл» на поездке с остановками до «Подъезжаю» по этой брони (решение 18.10.2026).

    Пассажир на остановке ещё не знает, что машина рядом: «Водитель подъезжает» ему приходит только
    после «Подъезжаю». `code` — машинная причина: по ней клиент вместо кнопки пишет «Сначала нажми
    «Подъезжаю»». `no_show_at` — null: срока ещё нет, он появится после «Подъезжаю»."""
    minutes = int(NO_SHOW_WAIT.total_seconds() // 60)
    return HTTPException(status_code=409, detail={
        "ru": f"Сначала нажми «Подъезжаю» — пассажир получит уведомление. Неявку можно отметить через {minutes} минут после этого.",
        "ba": f"Башта «Яҡынлашам»ға баҫ — пассажир хәбәр алыр. Килмәүҙе унан һуң {minutes} минут үткәс кенә билдәләп була.",
        "code": "no_show_needs_arriving",
        "no_show_at": None,
    })


@router.post("/bookings/{booking_id}/no-show", response_model=Booking)
def mark_no_show(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель отмечает, что пассажир НЕ ЯВИЛСЯ (no-show): бронь → cancelled, места возвращаются,
    ставим no_show + cancel_reason='no_show'. Только водитель этой поездки и только по ПОДТВЕРЖДЁННОЙ
    брони. Идемпотентно. Сигнал доверия «между своими» (отдельно от обычной отмены).

    Не раньше чем через NO_SHOW_WAIT после позднего из «время выезда» и первого «Подъезжаю» по этой
    брони (решение 18.10.2026, образец BlaBlaCar): раньше — 409 `no_show_too_early` с
    `no_show_wait_sec`. На поездке с остановками без «Подъезжаю» — 409 `no_show_needs_arriving`:
    пассажира на остановке сажают позже выезда, и он должен получить «Водитель подъезжает».
    Проверки стоят ДО смены статуса и повторяются под замком, по свежим строкам поездки и брони.
    Время выезда при живых бронях не меняется (`rides.edit_ride` отвечает 409), так что перепроверка —
    защита на будущее и от «Подъезжаю», нажатого в ту же секунду, а не обычный путь. Повтор уже
    отмеченной неявки сроки не проверяет — итог тот же, красной ошибки после успеха нет.

    «В пути» (onboard) — отказ 409 (решение 14.10.2026, docs/decisions.md). С 17.10.2026 посадку
    отмечает сам водитель («Сел»): неявка снимает бронь и возвращает места в продажу, а «Сел» →
    «Не пришёл» перечеркнул бы его же слово. Промахнулся карточкой — «Отменить» в первые
    BOARDING_UNDO_WINDOW (`undo_boarded`), позже — двусторонний разбор (POST /incidents по брони,
    тип passenger_no_show): пассажир объясняется, решает админ, и только подтверждённая неявка
    бьёт по Надёжности. Сторожа — `test_no_show_refused_onboard.py`, `test_driver_marks_boarding.py`."""
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    if user.id != ride.driver_id:
        raise herr(403, "Отметить неявку может только водитель поездки", "Килмәүҙе тик сәфәр йөрөтөүсеһе генә билдәләй ала")
    if booking.status == BookingStatus.onboard:
        raise _no_show_onboard_refusal(booking)
    if booking.status == BookingStatus.cancelled and booking.no_show:
        # Повтор (двойное нажатие или повтор после потерянного ответа) — неявка уже отмечена, места
        # возвращены один раз. Отвечаем как confirm/cancel/complete: тем же итогом, а не красной
        # ошибкой после успеха (сквозной проход волны 1, 16.10.2026).
        return _booking_out(booking)
    if booking.status != BookingStatus.confirmed:
        raise herr(409, "Неявку можно отметить только по подтверждённой брони", "Килмәүҙе тик раҫланған бронь буйынса ғына билдәләп була")
    if no_show_needs_arriving(ride, booking):
        raise _no_show_needs_arriving_refusal()
    if no_show_wait_left(ride, booking=booking) > 0:
        raise _no_show_too_early_refusal(ride, booking)
    # Порядок локов Ride → Booking — ЕДИНЫЙ с cancel_booking/cancel_ride (V4, без deadlock).
    # populate_existing — по той же причине, что в cancel_booking: обе строки уже загружены выше,
    # и без него «перепроверка под локом» смотрела бы на устаревшие значения (аудит 2026-08-07).
    ride = session.exec(select(Ride).where(Ride.id == booking.ride_id)
                        .with_for_update().execution_options(populate_existing=True)).first()
    booking = session.exec(select(Booking).where(Booking.id == booking_id)
                           .with_for_update().execution_options(populate_existing=True)).first()
    if booking.status == BookingStatus.onboard:
        # Посадку отметили между первой проверкой и замком — то же правило, что выше.
        raise _no_show_onboard_refusal(booking)
    if booking.status != BookingStatus.confirmed:
        return _booking_out(booking)           # параллельная отмена/неявка опередила — места уже возвращены
    # Те же сроки по свежим строкам: защита, если строка поездки или брони сменилась между первой
    # проверкой и замком (например, «Подъезжаю» в ту же секунду сдвинул срок позже).
    if no_show_needs_arriving(ride, booking):
        raise _no_show_needs_arriving_refusal()
    if no_show_wait_left(ride, booking=booking) > 0:
        raise _no_show_too_early_refusal(ride, booking)
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
    trip_wake.poke(booking.id)   # канал координат закрывается сразу (см. cancel_booking)
    notify_map_changed()
    route = f"{ride.from_city} → {ride.to_city}"
    push_notification(
        session, booking.passenger_id, "booking",
        "Отмечена неявка", "Килмәгәнлек билдәләнде",
        route, route,
        ref_kind="booking", ref_id=booking.id,
    )
    return _booking_out(booking)


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
    entry = snapshot_cached()
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
            "front_seat": bool(b.front_seat),
            # Где садится (18.10.2026): "" — начало, «Акъяр» — остановка, null — не указано.
            "pickup_stop": (b.pickup_stop if r is not None and ride_has_stops_raw(r, entry) else ""),
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
    entry = snapshot_cached()
    out: list = []
    for b in bookings:
        ride = rides_by_id.get(b.ride_id)
        passenger = passengers_by_id.get(b.passenger_id)
        avg, cnt = user_rating(session, b.passenger_id)
        has_stops = ride is not None and ride_has_stops_raw(ride, entry)
        # Место посадки — только у живых и недавно завершённых броней, как контакты взрослого (P3):
        # отменённая бронь села пассажирки водителю больше не показывает.
        stop_visible = b.status != BookingStatus.cancelled and (
            b.status != BookingStatus.done or booking_contacts_open(b, ride))
        out.append({
            "booking_id": b.id,
            # Вторая сторона для «Сообщить о проблеме» (разбор POST /incidents ждёт respondent_id):
            # по брони «в пути» неявку не отметить, спорную посадку разбирает админ (14.10.2026).
            "passenger_id": b.passenger_id,
            "passenger_name": (passenger.name if passenger else "Пассажир"),
            "passenger_rating": (round(avg, 1) if cnt > 0 else None),
            "route": (f"{ride.from_city} → {ride.to_city}" if ride else ""),
            "status": b.status,
            # 0 = ещё не оценивал. Иначе — сколько звёзд поставил (оценку можно изменить).
            "my_stars": my_stars.get(b.id, 0),
            "front_seat": bool(b.front_seat),   # «просит переднее место» в карточке заявки
            # Где садится (18.10.2026): "" — начало маршрута, «Акъяр» — остановка, null — не указано
            # (клиент пишет «Место посадки не указано — уточни в чате») или уже не видно (отмена).
            "pickup_stop": ((b.pickup_stop if has_stops else "") if stop_visible else None),
            "ride_has_stops": has_stops,
            # Сколько секунд ещё можно отменить свою отметку «Сел» (0 — уже нельзя): «Отменить»
            # в карточке переживает перезапуск приложения и не зависит от часов телефона.
            "boarding_undo_sec": boarding_undo_left(b),
            # С какого момента «Не пришёл» доступна (наивный UTC, как `depart_at`): поздний из «выезд»
            # и первого «Подъезжаю» + ожидание (решения 18.10.2026). Клиент срок не хардкодит — до
            # этого момента вместо кнопки подпись, а раньше времени сервер всё равно ответит 409
            # `no_show_too_early`. null — считать не от чего. `no_show_needs_arriving` — поездка с
            # остановками ждёт «Подъезжаю» по этой брони: вместо кнопки «Сначала нажми «Подъезжаю»».
            **(_no_show_fields(ride, b) if ride else
               {"no_show_at": None, "no_show_wait_sec": 0, "no_show_needs_arriving": False,
                "arriving_opens_at": None, "arriving_wait_sec": 0}),
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
    # Кнопка открывает чат заново — и раньше её можно было жать бесконечно (волна 151).
    #
    # Проверено пробой: поездка была 400 дней назад, пять нажатий подряд — пять раз «готово»
    # и пять уведомлений человеку. Нажимая раз в двое суток, водитель держал бы переписку
    # с пассажиркой открытой сколько угодно, а каждое нажатие ещё и дёргало её уведомлением.
    # Ровно та «травля кнопкой», от которой закрывали брони в волне 51.
    #
    # Две границы. Первая: искать вещь имеет смысл, пока о ней помнят, — через месяц после
    # поездки это уже не поиск вещи, а способ написать человеку. Вторая: не больше трёх раз,
    # иначе даже в пределах месяца получается тот же бесконечный стук.
    if ride.depart_at is not None and utcnow() - ride.depart_at > LOST_ITEM_WINDOW:
        raise herr(409,
                   "Поездка была слишком давно. Если вещь так и не нашлась — напиши в поддержку.",
                   "Сәфәр бик күптән булған. Әйбер табылмаһа — ярҙам хеҙмәтенә яҙ.")
    if (booking.lost_item_calls or 0) >= LOST_ITEM_MAX_CALLS:
        raise herr(409,
                   "Чат уже открывали несколько раз. Дальше поможет поддержка.",
                   "Чат бер нисә тапҡыр асылған инде. Артабан ярҙам хеҙмәте кәрәк.")
    booking.lost_item_calls = (booking.lost_item_calls or 0) + 1
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
