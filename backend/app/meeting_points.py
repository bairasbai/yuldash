"""RS-03: точное место встречи/высадки брони — валидация, видимость, цель «Подъезжаю».

Пассажир по желанию уточняет, где именно его забрать/высадить: булавка на карте или
курируемый ориентир (PickupPoint, только is_seed) + короткое пояснение. Это личные данные:
координаты никогда не идут в лог/ошибку/пуш/аналитику; водителю открываются только при
confirmed/onboard, после done/cancelled скрываются сразу, через 30 дней от выезда стираются
(cleanup.scrub_meeting_points), при удалении аккаунта — с бронью.

Граница точки — населённый пункт посадки/высадки: «место встречи» не может уехать за пределы
села (3 км) или города (15 км) от опоры (остановка → справочник сёл, начало → пин/геокод
начала, конец → геокод конца). Опора неизвестна — коридор 25 км от прямой маршрута.
"""
from __future__ import annotations

import json
import math
from typing import Optional
from uuid import uuid4

from pydantic import BaseModel, Field
from datetime import datetime

from . import ride_stops
from .antifraud import moderate_open_text
from .config import settings
from .errors import herr
from .models import Booking, BookingStatus, PayMethod, PickupPoint, Ride

# Что возвращают эндпоинты брони (раньше — response_model=Booking, который теперь утёк бы
# новыми полями). Явный список: только эти поля плюс политика точек встречи ниже. Никаких
# model_dump()/__dict__ — новое поле Booking сюда само не попадёт (сторож в тестах).
_BOOKING_BASE_FIELDS = (
    "id", "ride_id", "passenger_id", "seats", "price", "status", "driver_phase",
    "minor_passenger", "minor_guardian_name", "minor_guardian_phone", "arrival_verified",
    "boarding_code", "paid", "payment_method", "unpaid_reported", "contact_then_cancel",
    "thanked", "rate_reminded", "confirmed_at", "onboard_at", "arriving_at",
    "pickup_stop", "dropoff_stop", "done_at", "cancelled_at", "cancel_reason", "no_show",
    "cancelled_by", "lost_item_until", "lost_item_calls", "pay_method", "pay_amount",
    "front_seat", "winter_check_sent_at", "winter_check_ack_at", "created_at",
)


class MeetingIn(BaseModel):
    """Точка встречи/высадки в брони или команде смены. Пустой объект = «не уточняю»."""
    lat: Optional[float] = None
    lng: Optional[float] = None
    note: str = Field("", max_length=120)
    landmark_id: Optional[int] = None
    clear: bool = False                 # снять ранее указанную точку


def _finite(v: float) -> bool:
    return math.isfinite(v)


def _anchor(ride: Ride, stop_name: str, kind: str, entry) -> tuple[Optional[tuple[float, float]], str]:
    """Опора точки и вид места ('village'/'city'/'' — для радиуса).

    Посадка: остановка брони (справочник) → иначе начало маршрута (пин сбора, геокод).
    Высадка: остановка высадки → иначе конец маршрута. Опора неизвестна — ('' вида),
    дальность считаем от прямой «откуда → куда» (stop_corridor_km)."""
    if stop_name:
        sp = ride_stops.stop_point(ride, stop_name, entry)
        if sp is not None:
            kind_v = "village" if sp.kind in ride_stops.VILLAGE_KINDS else "city"
            return (sp.lat, sp.lng), kind_v
        return None, ""
    if kind == "pickup":
        start = ride_stops.start_point(ride, entry)
        if start is not None:
            return start, _place_kind(ride.from_city, entry)
    else:
        if ride.to_lat is not None and ride.to_lng is not None:
            return (ride.to_lat, ride.to_lng), _place_kind(ride.to_city, entry)
    return None, ""


def _place_kind(city: str, entry) -> str:
    """Вид места по справочнику: село/райцентр — village, иначе city; неизвестно — ''."""
    cands = ride_stops._lookup_place(entry, city) if city else []
    if len(cands) != 1:
        return ""
    return "village" if cands[0].kind in ride_stops.VILLAGE_KINDS else "city"


def validate_meeting(session, ride: Ride, kind: str,
                     body: Optional[MeetingIn], *, stop_name: str = "", user_id: Optional[int] = None,
                     booking_id: Optional[int] = None, entry=None) -> Optional[tuple]:
    """Проверка точки. Возврат: None (не задано/clear) или (lat, lng, note, landmark_id).

    kind: "pickup" | "dropoff"; stop_name — уже разобранная остановка посадки/высадки брони
    ("" — начало/конец маршрута). Ошибки — herr с кодом (RS-01): координаты в тексте не носим.
    Модерация пояснения идёт ДО любых изменений объектов (она коммитит сессию, план §3.3) —
    вызывающий после неё заново берёт строки под замок и повторяет проверки."""
    if body is None:
        return None
    entry = entry or ride_stops.snapshot_cached()
    # Пояснение — открытый текст: модерация до всего остального (она коммитит сессию).
    note = (body.note or "").strip()[:120]
    if note:
        moderate_open_text(note, user_id, check_contact=False, place="meeting_note",
                           ref_id=booking_id, session=session)
    if body.clear:
        return None
    # Ориентир — только курируемый (is_seed): чужие/пользовательские точки не принимаем.
    landmark = None
    if body.landmark_id is not None:
        landmark = session.get(PickupPoint, body.landmark_id)
        if landmark is None or not getattr(landmark, "is_seed", False):
            raise herr(400, "Такого ориентира нет — выбери из подсказок или поставь точку на карте",
                       "Бындай билгеле урын юҡ — тәҡдимдәрҙән һайла йәки картаға нөктә ҡуй",
                       code="meeting_landmark_unknown")
    if body.lat is None or body.lng is None:
        if landmark is not None and landmark.lat is not None and landmark.lng is not None:
            return (landmark.lat, landmark.lng, note, landmark.id)
        if not note and landmark is None:
            return None
        # Есть пояснение без точки — разрешаем (место словами); радиус не проверяем.
        return (None, None, note, landmark.id if landmark else None)
    if not (_finite(body.lat) and _finite(body.lng)) or (body.lat == 0.0 and body.lng == 0.0):
        raise herr(400, "Точка на карте не распознана — поставь её ещё раз",
                   "Картаһалағы нөктә уҡылманы — тағы ҡуй", code="meeting_point_bad")
    # Радиус от опоры: точка не может уехать за пределы места посадки/высадки.
    anchor, akind = _anchor(ride, stop_name, kind, entry)
    if anchor is None:
        # Опора неизвестна — коридор от прямой маршрута (как у тёзок-остановок).
        ends = ride_stops._route_coords(ride)
        if ends is not None:
            d = ride_stops._to_segment_km((body.lat, body.lng), ends[0], ends[1])
            if d > settings.stop_corridor_km:
                raise herr(400, "Точка далеко от маршрута — выбери место по пути или договорись в чате",
                           "Нөктә юлдан алыҫ — юл буйынса урын һайла йәки чатта һөйләш",
                           code="meeting_point_far")
        return (body.lat, body.lng, note, landmark.id if landmark else None)
    limit = settings.meeting_point_village_km if akind == "village" else settings.meeting_point_city_km
    d = ride_stops.haversine_km(anchor[0], anchor[1], body.lat, body.lng)
    if d > limit:
        place = stop_name or (ride.from_city if kind == "pickup" else ride.to_city)
        raise herr(400, f"Точка далеко от «{place}» — выбери место в самом селе или договорись в чате",
                   f"«{place}» эсенән алыҫ нөктә — ауылдың үҙ эсендә урын һайла йәки чатта һөйләш",
                   code="meeting_point_far")
    return (body.lat, body.lng, note, landmark.id if landmark else None)


def gps_target(ride: Ride, booking: Booking, entry=None) -> Optional[tuple[float, float]]:
    """Цель «Подъезжаю»: своя точка встречи брони, иначе None (прежний путь по остановке/пину)."""
    if not settings.arrival_verify_meeting_enabled:
        return None
    if booking is None or booking.meet_lat is None or booking.meet_lng is None:
        return None
    return (booking.meet_lat, booking.meet_lng)


# ---------- Видимость (А): единый DTO ----------

def _landmark_names(session, ids: set[int]) -> dict[int, tuple[str, str]]:
    """Названия ориентиров RU/BA одним запросом; без координат наружу не выходит."""
    ids = {i for i in ids if i}
    if not ids:
        return {}
    from sqlmodel import select
    rows = session.exec(select(PickupPoint).where(PickupPoint.id.in_(ids))).all()
    return {p.id: (p.title_ru, p.title_ba) for p in rows}


def _safe_proposal(raw: str) -> Optional[dict]:
    """Публичный вид активного предложения: без author_id и прочих служебных полей."""
    if not raw:
        return None
    try:
        p = json.loads(raw)
    except (ValueError, TypeError):
        return None
    point = p.get("point") or {}
    return {
        "proposal_id": p.get("proposal_id"),
        "kind": p.get("kind"),
        "created_at": p.get("created_at"),
        "point": {
            "lat": point.get("lat"), "lng": point.get("lng"),
            "note": point.get("note") or "", "landmark_id": point.get("landmark_id"),
            "clear": bool(point.get("clear")),
        },
    }


def booking_public_dto(session, booking: Booking, ride: Ride, viewer_id: int) -> dict:
    """Единый ответ брони (А): базовые поля + точки встречи по политике.

    Пассажир — свои точки/пояснения/предложение всегда (до зачистки). Водитель — при
    confirmed/onboard полностью; при pending и после закрытия (done/cancelled) — только
    булевы «место указано»/«есть предложение». Посторонний сюда не доходит (403 раньше)."""
    out = {name: getattr(booking, name) for name in _BOOKING_BASE_FIELDS}
    status = booking.status.value if hasattr(booking.status, "value") else str(booking.status)
    is_passenger = booking.passenger_id == viewer_id
    is_driver = ride.driver_id == viewer_id
    open_for_driver = is_driver and status in ("confirmed", "onboard")
    see_points = is_passenger or open_for_driver

    has_meet = booking.meet_lat is not None or booking.meet_note or booking.meet_landmark_id is not None
    has_alight = booking.alight_lat is not None or booking.alight_note or booking.alight_landmark_id is not None
    proposal = _safe_proposal(booking.meet_proposal)

    if see_points:
        names = _landmark_names(session, {booking.meet_landmark_id, booking.alight_landmark_id})
        m_ru, m_ba = names.get(booking.meet_landmark_id, ("", ""))
        a_ru, a_ba = names.get(booking.alight_landmark_id, ("", ""))
        out.update({
            "pickup_lat": booking.meet_lat, "pickup_lng": booking.meet_lng,
            "pickup_note": booking.meet_note, "pickup_landmark_ru": m_ru, "pickup_landmark_ba": m_ba,
            "pickup_source": _point_source(booking.meet_lat, booking.meet_note, booking.meet_landmark_id),
            "dropoff_lat": booking.alight_lat, "dropoff_lng": booking.alight_lng,
            "dropoff_note": booking.alight_note, "dropoff_landmark_ru": a_ru, "dropoff_landmark_ba": a_ba,
            "dropoff_source": _point_source(booking.alight_lat, booking.alight_note, booking.alight_landmark_id),
            "meeting_version": booking.meet_version,
            "meeting_proposal": proposal,
        })
    else:
        # До подтверждения и после закрытия — только факты «указано/есть предложение».
        out.update({
            "pickup_lat": None, "pickup_lng": None, "pickup_note": "",
            "pickup_landmark_ru": "", "pickup_landmark_ba": "", "pickup_source": "",
            "dropoff_lat": None, "dropoff_lng": None, "dropoff_note": "",
            "dropoff_landmark_ru": "", "dropoff_landmark_ba": "", "dropoff_source": "",
            "meeting_version": booking.meet_version if is_driver else 0,
            "meeting_proposal": ({"proposal_id": proposal["proposal_id"], "kind": proposal["kind"]}
                                 if (is_driver and proposal) else None),
        })
    out["meeting_point_set"] = bool(has_meet or has_alight)
    out["meeting_proposal_pending"] = proposal is not None
    if status in (BookingStatus.cancelled.value, BookingStatus.done.value):
        # Сделки больше нет: контакты взрослого не читаются повторами закрытой брони (PR150),
        # а водитель теряет и остановки, и точные точки сразу (RS-03 §3.6) — независимо от окна связи.
        out["minor_guardian_name"] = ""
        out["minor_guardian_phone"] = ""
        if is_driver:
            out["pickup_stop"] = None
            out["dropoff_stop"] = None
    return out


def _point_source(lat, note: str, landmark_id) -> str:
    """Чем задано место: булавкой/ориентиром/словами — клиенту для значка."""
    if lat is not None:
        return "pin"
    if landmark_id is not None:
        return "landmark"
    if note:
        return "note"
    return ""


def details_meeting_fields(session, booking: Booking, ride: Ride, viewer_id: int) -> dict:
    """Блок точек встречи для `/bookings/{id}/details` (RS-03 А).

    Имена НЕ совпадают с `pickup_*` деталей: там это пин сбора рейса. Политика строже окна
    контактов: водитель видит точки только при confirmed/onboard и теряет их сразу при
    done/cancelled (даже в 48-часовом окне связи); пассажир — свои до зачистки."""
    status = booking.status.value if hasattr(booking.status, "value") else str(booking.status)
    is_passenger = booking.passenger_id == viewer_id
    is_driver = ride.driver_id == viewer_id
    see = is_passenger or (is_driver and status in ("confirmed", "onboard"))
    proposal = _safe_proposal(booking.meet_proposal)
    if not see:
        return {
            "meet_lat": None, "meet_lng": None, "meet_note": "",
            "meet_landmark_ru": "", "meet_landmark_ba": "",
            "alight_lat": None, "alight_lng": None, "alight_note": "",
            "alight_landmark_ru": "", "alight_landmark_ba": "",
            "meeting_version": booking.meet_version if is_driver else 0,
            "meeting_proposal": ({"proposal_id": proposal["proposal_id"], "kind": proposal["kind"]}
                                 if (is_driver and proposal) else None),
        }
    names = _landmark_names(session, {booking.meet_landmark_id, booking.alight_landmark_id})
    m_ru, m_ba = names.get(booking.meet_landmark_id, ("", ""))
    a_ru, a_ba = names.get(booking.alight_landmark_id, ("", ""))
    return {
        "meet_lat": booking.meet_lat, "meet_lng": booking.meet_lng, "meet_note": booking.meet_note,
        "meet_landmark_ru": m_ru, "meet_landmark_ba": m_ba,
        "alight_lat": booking.alight_lat, "alight_lng": booking.alight_lng, "alight_note": booking.alight_note,
        "alight_landmark_ru": a_ru, "alight_landmark_ba": a_ba,
        "meeting_version": booking.meet_version,
        "meeting_proposal": proposal,
    }


def driver_row_meeting_fields(session, booking: Booking, ride: Optional[Ride]) -> dict:
    """Точки встречи в строке `/driver/bookings` (RS-03 А): водитель при confirmed/onboard видит
    точку и пояснение (для «Маршрута»), при pending — только «место указано», после закрытия — ничего."""
    status = booking.status.value if hasattr(booking.status, "value") else str(booking.status)
    has_meet = booking.meet_lat is not None or booking.meet_note or booking.meet_landmark_id is not None
    has_alight = booking.alight_lat is not None or booking.alight_note or booking.alight_landmark_id is not None
    open_now = status in ("confirmed", "onboard")
    out = {
        "meeting_point_set": bool(has_meet or has_alight),
        "meeting_proposal_pending": bool(booking.meet_proposal),
        "meeting_version": booking.meet_version,
    }
    if open_now:
        out["meeting_proposal"] = _safe_proposal(booking.meet_proposal)
        names = _landmark_names(session, {booking.meet_landmark_id, booking.alight_landmark_id})
        m_ru, m_ba = names.get(booking.meet_landmark_id, ("", ""))
        a_ru, a_ba = names.get(booking.alight_landmark_id, ("", ""))
        out.update({
            "meet_lat": booking.meet_lat, "meet_lng": booking.meet_lng, "meet_note": booking.meet_note,
            "meet_landmark_ru": m_ru, "meet_landmark_ba": m_ba,
            "alight_lat": booking.alight_lat, "alight_lng": booking.alight_lng,
            "alight_note": booking.alight_note, "alight_landmark_ru": a_ru, "alight_landmark_ba": a_ba,
        })
    return out


def new_proposal_id() -> str:
    return uuid4().hex


def clear_proposal_on_transition(booking: Booking, *, only_kind: Optional[str] = None) -> dict:
    """Значения UPDATE при смене статуса брони (RS-03 Б): отмена/неявка/завершение снимают
    активное предложение (+meet_version в той же транзакции); посадка (onboard) снимает
    только предложение ПОСАДКИ (only_kind="pickup"), высадку сохраняет. Нечего снимать — {}."""
    if not booking.meet_proposal:
        return {}
    if only_kind is not None:
        try:
            import json as _json
            kind = (_json.loads(booking.meet_proposal) or {}).get("kind")
        except (ValueError, TypeError):
            kind = None
        if kind != only_kind:
            return {}
    return {"meet_proposal": "", "meet_version": booking.meet_version + 1}


def same_point(stored: tuple, validated: Optional[tuple]) -> bool:
    """Точка в повторе — та же, что в брони? stored — (lat, lng, note, landmark_id) из брони,
    validated — выход validate_meeting (None, если точки в запросе не было или clear).
    «Та же» — по координатам/пояснению/ориентиру; clear по пустой брони — та же пустота."""
    if validated is None:
        return stored[0] is None and not stored[2] and stored[3] is None
    lat, lng, note, landmark_id = validated
    return (stored[0] == lat and stored[1] == lng
            and (stored[2] or "") == (note or "") and stored[3] == landmark_id)


class BookingPublicOut(BaseModel):
    """Явная схема ответа брони (RS-03 А): ключи = выходу booking_public_dto.

    Заменяет response_model=Booking: ORM-модель после добавления meet_*/alight_* утекала бы
    координатами в каждый ответ. Новое поле Booking сюда само не попадёт — политика точек
    только в booking_public_dto."""
    id: Optional[int] = None
    ride_id: int
    passenger_id: int
    seats: int
    price: int
    status: BookingStatus
    driver_phase: str = ""
    minor_passenger: bool = False
    minor_guardian_name: str = ""
    minor_guardian_phone: str = ""
    arrival_verified: bool = False
    boarding_code: str = ""
    paid: bool = False
    payment_method: str = ""
    unpaid_reported: bool = False
    contact_then_cancel: bool = False
    thanked: bool = False
    rate_reminded: bool = False
    confirmed_at: Optional[datetime] = None
    onboard_at: Optional[datetime] = None
    arriving_at: Optional[datetime] = None
    pickup_stop: Optional[str] = None
    dropoff_stop: Optional[str] = None
    done_at: Optional[datetime] = None
    cancelled_at: Optional[datetime] = None
    cancel_reason: Optional[str] = None
    no_show: bool = False
    cancelled_by: Optional[int] = None
    lost_item_until: Optional[datetime] = None
    lost_item_calls: int = 0
    pay_method: PayMethod = PayMethod.negotiate
    pay_amount: Optional[int] = None
    front_seat: bool = False
    winter_check_sent_at: Optional[datetime] = None
    winter_check_ack_at: Optional[datetime] = None
    created_at: Optional[datetime] = None
    # Точки встречи/высадки — по политике зрителя (план §3.6)
    pickup_lat: Optional[float] = None
    pickup_lng: Optional[float] = None
    pickup_note: str = ""
    pickup_landmark_ru: str = ""
    pickup_landmark_ba: str = ""
    pickup_source: str = ""
    dropoff_lat: Optional[float] = None
    dropoff_lng: Optional[float] = None
    dropoff_note: str = ""
    dropoff_landmark_ru: str = ""
    dropoff_landmark_ba: str = ""
    dropoff_source: str = ""
    meeting_version: int = 0
    meeting_proposal: Optional[dict] = None
    meeting_point_set: bool = False
    meeting_proposal_pending: bool = False
