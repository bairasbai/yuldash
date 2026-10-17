"""Семейный контроль: доверенные контакты, шаринг поездки близкому,
статусы поездки (сел/доехал/завершил) с SMS-уведомлением, оценки после поездки."""
import re
import secrets
from datetime import datetime, timedelta
from typing import List

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import update
from sqlmodel import Session, select

from .. import trip_wake
from ..config import settings
from ..db import get_session
from ..errors import herr
from ..routers.parcels import _FINAL_STATUSES as PARCEL_FINAL_STATUSES
from ..models import (
    Booking, BookingStatus, DriverProfile, InstantOrder, ParcelDelivery, Ride,
    TripShare, TrustedContact, User,
)
from ..rating_service import apply_rating, guard_rating_window
from ..security import current_user
from ..services import (pick_lang, sms_lang_of,
    booking_and_ride_for_user, may_send_family_sms, push_bilingual, send_text)
from ..timeutil import utcnow
# Планку «завершить можно только начавшуюся поездку» держим одну на оба пути к переходу
# (водительский в bookings.py и пассажирский здесь) — иначе они разъедутся при первой же правке.
from .bookings import DONE_EARLY_GRACE, boarding_undo_left

# Live-ссылка живёт 24ч (приватность): дольше любой реальной поездки, но не бессрочно —
# если поездка «зависнет» в живом статусе, ссылка перестанет отдавать гео (см. _resolve_share).
_SHARE_TTL = timedelta(hours=24)
# Трекинг-ссылка посылки живёт дольше (G1): межгород-доставка «по пути» едет и сутки, и двое.
# 72ч — щедро для реальной доставки, но не вечно (после — токен «сгорает», гео не отдаётся).
_PARCEL_SHARE_TTL = timedelta(hours=72)

router = APIRouter(tags=["family"])

MAX_TRUSTED_CONTACTS = 10          # разумный потолок «своих» → анти-SMS-бомбинг (каждый SOS/статус шлёт SMS всем)
_PHONE_RE = re.compile(r"^\+?\d{10,15}$")   # телефон-получатель SMS: 10–15 цифр, опц. ведущий +


def _ensure_share_token(session: Session, share: TripShare) -> str:
    """Токен live-ссылки (B7c): ≥16 случайных байт. Строки до миграции w2_livelink живут
    с token=NULL — догенерируем при первом обращении (лениво, без бэкфилла)."""
    if not share.token:
        share.token = secrets.token_urlsafe(16)
        if share.expires_at is None:
            share.expires_at = utcnow() + _SHARE_TTL   # P3: легаси-строки без TTL иначе жили бы вечно
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
        raise herr(400, "Неверный номер телефона", "Телефон номеры дөрөҫ түгел")
    # Тот же номер второй раз — то же самое добавление (аудит 2026-08-08, волна 135).
    #
    # В деревне связь рвётся: человек жмёт «Добавить», ответа не видит, жмёт ещё раз. Раньше
    # в списке появлялись два одинаковых контакта — и маме приходили ДВА SMS на каждое
    # событие поездки, а при беде — два сигнала SOS. Тревожное сообщение, пришедшее дважды,
    # пугает сильнее и выглядит как ошибка приложения.
    if phone:
        уже = session.exec(select(TrustedContact).where(
            TrustedContact.user_id == user.id, TrustedContact.phone == phone,
        ).limit(1)).first()
        if уже:
            # Обновляем ВСЁ, что человек прислал, а не только имя (волна 160). Ручки «изменить
            # контакт» на сервере нет, поэтому повторное добавление того же номера — единственный
            # способ поменять настройку. Раньше менялось только имя, а пометка «тревожить только
            # при беде» молча выбрасывалась: человек просил не дёргать пожилую маму SMS по каждому
            # шагу поездки, а её продолжали дёргать, и другого пути это исправить в приложении нет.
            изменилось = False
            новое_имя = (body.name or "").strip()
            if новое_имя and новое_имя != уже.name:
                уже.name = новое_имя
                изменилось = True
            новая_связь = (getattr(body, "relation", "") or "").strip()
            if новая_связь and новая_связь != (уже.relation or ""):
                уже.relation = новая_связь
                изменилось = True
            if bool(body.notify_by_default) != bool(уже.notify_by_default):
                уже.notify_by_default = bool(body.notify_by_default)
                изменилось = True
            if изменилось:
                session.add(уже)
                session.commit()
                session.refresh(уже)
            return уже
    count = len(session.exec(select(TrustedContact).where(TrustedContact.user_id == user.id)).all())
    if count >= MAX_TRUSTED_CONTACTS:
        raise herr(400, f"Больше {MAX_TRUSTED_CONTACTS} доверенных близких не добавить",
                   f"{MAX_TRUSTED_CONTACTS} яҡын кешенән артыҡ өҫтәп булмай")
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


@router.delete("/trusted-contacts/{contact_id}")
def delete_contact(contact_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Удалить доверенный контакт (близкий сменился/ошибся номером). Раньше ручки не было вовсе:
    веб-версия звала её и получала 404, Android удалял только локально (после перезапуска контакт
    возвращался). Сначала гасим шаринги контакта (FK + прекращение SMS-статусов), затем сам контакт."""
    contact = session.get(TrustedContact, contact_id)
    if not contact or contact.user_id != user.id:
        raise herr(404, "Контакт не найден", "Контакт табылманы")
    for share in session.exec(select(TripShare).where(TripShare.contact_id == contact_id)).all():
        session.delete(share)
    session.delete(contact)
    session.commit()
    return {"ok": True}


class ShareIn(BaseModel):
    contact_id: int


def _trip_share_expires(ride) -> datetime:
    """Когда гаснет ссылка близкому: сутки от выезда, а не от того, когда поделились.

    Приёмка волны 1 (16.10.2026). Попутку бронируют заранее — в пятницу на воскресенье — и
    делятся сразу. Сутки от «поделился» истекали в субботу, и в день поездки мама видела
    «Ссылка не найдена». Выезд уже был (поделились в пути) — отсчёт от «сейчас», как раньше.
    """
    now = utcnow()
    start = ride.depart_at if ride is not None and ride.depart_at and ride.depart_at > now else now
    return start + _SHARE_TTL


@router.post("/bookings/{booking_id}/share", response_model=TripShare)
def share_trip(booking_id: int, body: ShareIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    if booking.passenger_id != user.id:
        raise herr(403, "Расшарить поездку может только пассажир", "Сәфәрҙе тик пассажир уртаҡлаша ала")
    contact = session.get(TrustedContact, body.contact_id)
    if not contact or contact.user_id != user.id:
        raise herr(404, "Контакт не найден", "Контакт табылманы")
    # QA-DEDUP-SHARETRIP: повторный share тем же контактом не плодит дубли (иначе дубли SMS-статусов).
    existing = session.exec(
        select(TripShare).where(TripShare.booking_id == booking_id, TripShare.contact_id == body.contact_id)
    ).first()
    if existing:
        _ensure_share_token(session, existing)   # строка до w2_livelink → догенерировать токен
        # Повторное «поделиться» оживляет ТУ ЖЕ ссылку — ту, что у близкого уже есть. Раньше
        # возвращалась строка с истёкшим сроком: человек жал ещё раз, а ссылка оставалась мёртвой.
        # Срок только продлеваем, не укорачиваем. Отозванной ссылки здесь нет: отзыв удаляет строку.
        срок = _trip_share_expires(ride)
        if existing.expires_at is None or existing.expires_at < срок:
            existing.expires_at = срок
            session.add(existing)
            session.commit()
            session.refresh(existing)
        return existing
    share = TripShare(booking_id=booking_id, contact_id=body.contact_id,
                      token=secrets.token_urlsafe(16), expires_at=_trip_share_expires(ride))
    session.add(share)
    session.commit()
    session.refresh(share)
    # Близкий сразу получает live-ссылку (B7c): живая карта поездки в браузере, без приложения.
    if contact.phone:
        who = user.name or user.phone
        if not may_send_family_sms(session, user.id, "share_ride"):
            raise herr(429, "Слишком много сообщений близким за сутки. Попробуй завтра.",
                       "Бер тәүлеккә яҡындарға хәбәр артыҡ күп. Иртәгә ҡабатла.")
        lang = sms_lang_of(session, user.id)
        send_text(contact.phone, pick_lang(
            lang,
            f"Юлдаш: {who} едет с попутчиком. Следи за поездкой: {_live_link(share.token)}",
            f"Юлдаш: {who} юлдаш менән бара. Сәфәрҙе күҙәт: {_live_link(share.token)}",
        ))
        session.refresh(share)   # учёт SMS коммитил сессию — освежаем перед ответом
    return share


@router.get("/bookings/{booking_id}/shares", response_model=List[TripShare])
def list_booking_shares(booking_id: int, user: User = Depends(current_user),
                        session: Session = Depends(get_session)):
    """Кому открыта ЭТА поездка (пассажиру — «уже поделился с …» и кнопка отозвать).

    Аудит 2026-08-06. Отозвать доступ можно было и раньше (DELETE .../share/{id}), но список
    активных ссылок жил только в памяти экрана: свернул приложение — и отзывать стало нечего,
    хотя ссылка на живое местоположение продолжала работать до конца поездки. У такси такая
    ручка была с самого начала (`/instant/orders/{id}/shares`), у попутки — нет. Здесь то же
    правило: видишь, кому открыл, и можешь закрыть в любой момент.
    """
    booking, _ = booking_and_ride_for_user(session, booking_id, user)
    if booking.passenger_id != user.id:
        raise herr(403, "Смотреть можно только свою поездку", "Тик үҙ сәфәреңде генә ҡарарға була")
    contact_ids = [c.id for c in session.exec(select(TrustedContact).where(TrustedContact.user_id == user.id)).all()]
    if not contact_ids:
        return []
    return session.exec(
        select(TripShare).where(TripShare.booking_id == booking_id, TripShare.contact_id.in_(contact_ids))
    ).all()


# ---- Шаринг такси-заказа близкому (B7b-2) ----
@router.post("/instant/orders/{order_id}/share", response_model=TripShare)
def share_instant_trip(order_id: int, body: ShareIn, user: User = Depends(current_user),
                       session: Session = Depends(get_session)):
    """«Поделиться поездкой» из такси-заказа — по образцу booking-шаринга: только пассажир,
    только свой контакт, дедуп. Близкий сразу получает SMS с маршрутом; дальше статусы
    (сел/доехал/отмена) шлёт сервер сам на переходах заказа (см. instant_service)."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if order.passenger_id != user.id:
        raise herr(403, "Расшарить поездку может только пассажир", "Сәфәрҙе тик пассажир уртаҡлаша ала")
    contact = session.get(TrustedContact, body.contact_id)
    if not contact or contact.user_id != user.id:
        raise herr(404, "Контакт не найден", "Контакт табылманы")
    existing = session.exec(
        select(TripShare).where(TripShare.order_id == order_id, TripShare.contact_id == body.contact_id)
    ).first()
    if existing:
        _ensure_share_token(session, existing)   # строка до w2_livelink → догенерировать токен
        return existing   # дедуп: повторный share тем же контактом не плодит дубли SMS
    share = TripShare(order_id=order_id, contact_id=body.contact_id,
                      token=secrets.token_urlsafe(16), expires_at=utcnow() + _SHARE_TTL)
    session.add(share)
    session.commit()
    session.refresh(share)
    # Близкий сразу в курсе: кто едет и куда (телефон водителя не шлём — минимум перс.данных).
    # Live-ссылка (B7c): живая карта поездки в браузере, без приложения.
    if contact.phone:
        who = user.name or user.phone
        route = f"{order.from_text or 'точка А'} → {order.to_text or 'точка Б'}"
        if not may_send_family_sms(session, user.id, "share_taxi"):
            raise herr(429, "Слишком много сообщений близким за сутки. Попробуй завтра.",
                       "Бер тәүлеккә яҡындарға хәбәр артыҡ күп. Иртәгә ҡабатла.")
        lang = sms_lang_of(session, user.id)
        send_text(contact.phone, pick_lang(
            lang,
            f"Юлдаш: {who} едет на такси ({route}). Следи за поездкой: {_live_link(share.token)}",
            f"Юлдаш: {who} такси менән бара ({route}). Сәфәрҙе күҙәт: {_live_link(share.token)}",
        ))
        session.refresh(share)   # учёт SMS коммитил сессию — освежаем перед ответом
    return share


@router.get("/instant/orders/{order_id}/shares", response_model=List[TripShare])
def list_instant_shares(order_id: int, user: User = Depends(current_user),
                        session: Session = Depends(get_session)):
    """Мои шаринги этого заказа (пассажиру — показать «уже поделился с …»)."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if order.passenger_id != user.id:
        raise herr(403, "Доступно только пассажиру заказа", "Тик заказ пассажирына мөмкин")
    contact_ids = [c.id for c in session.exec(select(TrustedContact).where(TrustedContact.user_id == user.id)).all()]
    if not contact_ids:
        return []
    return session.exec(
        select(TripShare).where(TripShare.order_id == order_id, TripShare.contact_id.in_(contact_ids))
    ).all()


# ---- Трекинг-ссылка посылки получателю (G1) ----
@router.post("/parcels/{parcel_id}/track-link")
def parcel_track_link(parcel_id: int, user: User = Depends(current_user),
                      session: Session = Depends(get_session)):
    """G1: отправитель получает трекинг-ссылку для ПОЛУЧАТЕЛЯ. Получатель (без приложения)
    открывает /t/{token} в браузере: статус доставки, движущийся курьер на карте, маршрут —
    как «трекинг-ссылка» Яндекс Доставки, но без телефонов. Работает для любой доставки
    (по пути / курьер / купи-привези), на любом статусе (можно следить с момента создания).

    Только отправитель (иначе 404 — не раскрываем чужие посылки). Дедуп: одна ссылка на посылку
    (повтор возвращает тот же токен — ссылка у получателя не протухает). Если у посылки есть
    телефон получателя — сразу шлём ему SMS со ссылкой (best-effort). Завершённую/отменённую
    посылку не шарим (следить уже не за чем)."""
    parcel = session.get(ParcelDelivery, parcel_id)
    if not parcel or parcel.sender_id != user.id:
        raise herr(404, "Посылка не найдена", "Бандероль табылманы")
    # Тот же общий список финалов (волна 124): на возвращённую посылку выпускалась новая
    # ссылка слежения — с кодом вручения внутри, хотя вручать уже нечего.
    if parcel.status in PARCEL_FINAL_STATUSES:
        raise herr(409, "Посылка уже завершена", "Бандероль тамамланған инде")
    existing = session.exec(
        select(TripShare).where(TripShare.parcel_id == parcel_id).order_by(TripShare.id.desc())
    ).first()
    if existing:
        token = _ensure_share_token(session, existing)   # дедуп: та же ссылка получателю
        # Доставка затянулась дольше TTL (72ч), а посылка ещё живая → продлеваем срок ТОГО ЖЕ
        # токена: ссылка из SMS у получателя снова работает. Иначе тупик: старая сгорела,
        # а новую не выпустить — дедуп вечно возвращает мёртвую.
        if existing.expires_at and existing.expires_at <= utcnow():
            existing.expires_at = utcnow() + _PARCEL_SHARE_TTL
            session.add(existing)
            session.commit()
        return {"token": token, "url": _live_link(token), "sms_sent": False}
    # Анти-SMS-бомбинг: receiver_phone не верифицирован, SMS уходит за счёт платформы. Потолок
    # был свой и считался по строкам шаринга — он работал, но только здесь, и только пока строки
    # живы. Теперь счёт общий на все входы и ведётся у отправителя (`send_family_sms`): иначе
    # достаточно было перейти из посылок в «поделиться поездкой» (аудит 2026-08-12, волна 48).
    share = TripShare(parcel_id=parcel_id, token=secrets.token_urlsafe(16),
                      expires_at=utcnow() + _PARCEL_SHARE_TTL)
    session.add(share)
    session.commit()
    session.refresh(share)
    sms_sent = False
    phone = (parcel.receiver_phone or "").strip()
    if phone:   # получателю сразу: где посылка, без установки приложения (телефоны в ссылке не светятся)
        who = user.name or "Отправитель"
        route = f"{parcel.from_city or 'точка А'} → {parcel.to_city or 'точка Б'}"
        try:
            # Код вручения — В ЭТОМ ЖЕ SMS (разбор №2, 2026-08-03). Раньше уходила только ссылка,
            # а шестизначный код отправитель диктовал получателю отдельным звонком — лишний шаг
            # ровно там, где человек и так волнуется. Код идёт на номер, который назвал сам
            # отправитель: то же доверие, что и у ссылки (она открывает карту с точкой курьера).
            # Смысл кода при этом не теряется — он подтверждает, что курьер отдал ТОМУ человеку.
            code = (parcel.confirm_code or "").strip()
            code_part = f" Код для курьера: {code}." if code else ""
            if may_send_family_sms(session, user.id, "parcel"):
                code_part_ba = f" Курьер өсөн код: {code}." if code else ""
                lang = sms_lang_of(session, user.id)
                send_text(phone, pick_lang(
                    lang,
                    f"Юлдаш: {who} отправил тебе посылку ({route}).{code_part} "
                    f"Следи за доставкой: {_live_link(share.token)}",
                    f"Юлдаш: {who} һиңә бандероль ебәрҙе ({route}).{code_part_ba} "
                    f"Доставканы күҙәт: {_live_link(share.token)}",
                ))
                sms_sent = True
        except Exception:   # SMS-шлюз мигнул — ссылку всё равно вернём отправителю (отдаст сам)
            pass
    return {"token": share.token, "url": _live_link(share.token), "sms_sent": sms_sent}


@router.delete("/parcels/{parcel_id}/track-link")
def parcel_track_link_revoke(parcel_id: int, user: User = Depends(current_user),
                             session: Session = Depends(get_session)):
    """G1: отозвать трекинг-ссылку посылки (опечатка в номере → ссылка ушла чужому человеку,
    который иначе 72 часа видел бы точки А/Б и живую позицию курьера). Строки удаляются →
    токен «сгорает» (/t/{token} → 404). Только отправитель. Повторный POST выдаст НОВЫЙ токен."""
    parcel = session.get(ParcelDelivery, parcel_id)
    if not parcel or parcel.sender_id != user.id:
        raise herr(404, "Посылка не найдена", "Бандероль табылманы")
    rows = session.exec(select(TripShare).where(TripShare.parcel_id == parcel_id)).all()
    for share in rows:
        session.delete(share)
    session.commit()
    return {"ok": True, "revoked": len(rows)}


def _revoke_share(session: Session, share_id: int, user: User, *, booking_id: int = None, order_id: int = None):
    """Отозвать шаринг (B7c): строка удаляется → live-токен «сгорает» (/t/{token} → 404),
    SMS-статусы этому контакту прекращаются. Только пассажир и только свой контакт."""
    share = session.get(TripShare, share_id)
    if not share or (booking_id is not None and share.booking_id != booking_id) \
            or (order_id is not None and share.order_id != order_id):
        raise herr(404, "Шаринг не найден", "Уртаҡлашыу табылманы")
    contact = session.get(TrustedContact, share.contact_id)
    if not contact or contact.user_id != user.id:
        raise herr(403, "Отозвать может только владелец шаринга", "Тик уртаҡлашыу эйәһе кире ала")
    session.delete(share)
    session.commit()
    return {"ok": True}


@router.delete("/instant/orders/{order_id}/share/{share_id}")
def revoke_instant_share(order_id: int, share_id: int, user: User = Depends(current_user),
                         session: Session = Depends(get_session)):
    order = session.get(InstantOrder, order_id)
    if not order:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if order.passenger_id != user.id:
        raise herr(403, "Доступно только пассажиру заказа", "Тик заказ пассажирына мөмкин")
    return _revoke_share(session, share_id, user, order_id=order_id)


@router.delete("/bookings/{booking_id}/share/{share_id}")
def revoke_booking_share(booking_id: int, share_id: int, user: User = Depends(current_user),
                         session: Session = Depends(get_session)):
    booking, _ = booking_and_ride_for_user(session, booking_id, user)
    if booking.passenger_id != user.id:
        raise herr(403, "Доступно только пассажиру брони", "Тик бронь пассажирына мөмкин")
    return _revoke_share(session, share_id, user, booking_id=booking_id)


class TripStatusIn(BaseModel):
    status: str  # arrived / done; «sat» — только старые версии приложения, отвечаем 409


def _driver_marks_boarding_refusal() -> HTTPException:
    """«Я сел» из старой версии приложения (решение 17.10.2026): посадку отмечает водитель.

    `code` — машинная причина: по ней клиент понимает, что кнопку пора убрать, а не повторять."""
    return HTTPException(status_code=409, detail={
        "ru": "Посадку теперь отмечает водитель",
        "ba": "Ултырыуҙы хәҙер йөрөтөүсе билдәләй",
        "code": "driver_marks_boarding",
    })


def _boarding_undo_open_refusal(undo_left: int) -> HTTPException:
    """«Завершил» пассажира, пока водитель ещё может отменить свою отметку «Сел» (приёмка PR174).
    Минуты — вверх: «через 0 мин» не бывает. `boarding_undo_sec` — для клиента, если захочет
    показать отсчёт; `code` — машинная причина."""
    minutes = max(1, -(-undo_left // 60))
    return HTTPException(status_code=409, detail={
        "ru": f"Водитель только что отметил посадку — завершить поездку можно через {minutes} мин.",
        "ba": f"Йөрөтөүсе яңы ғына ултырыуҙы билдәләне — сәфәрҙе {minutes} минуттан һуң тамамлап була.",
        "code": "boarding_undo_open",
        "boarding_undo_sec": undo_left,
    })


@router.post("/bookings/{booking_id}/trip-status", response_model=List[TripShare])
def set_trip_status(booking_id: int, body: TripStatusIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    booking, _ride = booking_and_ride_for_user(session, booking_id, user)
    if booking.passenger_id != user.id:
        raise herr(403, "Статус семейного контроля меняет только пассажир", "Ғаилә күҙәтеү хәлен тик пассажир үҙгәртә")
    if body.status not in {"sat", "arrived", "done"}:
        raise herr(400, "Недопустимый статус поездки", "Ярамаған сәфәр хәле")
    # Посадку отмечает ВОДИТЕЛЬ (решение Александра 17.10.2026, `bookings._mark_boarded`).
    # Пассажирский «Я сел» позволял нажать из дома, чтобы водитель уже не отметил «Не пришёл»,
    # а нажатие по ошибке было не вернуть. Старые версии приложения кнопку ещё показывают —
    # отвечаем понятным отказом. «Доехал» бронь тоже больше не двигает: иначе это была бы та же
    # посадка другой дверью (очередь без сети у старого клиента отправляет «sat», затем «arrived»).
    if body.status == "sat":
        raise _driver_marks_boarding_refusal()
    # «Доехал» — только из машины: «в пути» ставит отметка водителя. Иначе пассажир, который не
    # садился, слал бы близким «доехал до места» (приёмка PR174).
    if body.status == "arrived" and booking.status != BookingStatus.onboard:
        raise herr(409, "«Доехал» можно отметить в пути — после того как водитель отметит посадку",
                   "«Барып еттем» тип юлда ғына билдәләп була — йөрөтөүсе ултырыуҙы билдәләгәс")
    # «Завершил поездку» → реально закрываем бронь на сервере (раньше статус уходил только близким,
    # а бронь висела активной). Идемпотентно: повторный done/отменённую не трогаем.
    if body.status == "done" and booking.status not in (BookingStatus.done, BookingStatus.cancelled):
        # Пассажир завершает только ПОСЛЕ «Сел» водителя (решение лида по PR #174, 17.10.2026).
        # Иначе та же неявка другой дверью: не пришёл, а после времени выезда сам «завершил»
        # подтверждённую бронь прямым запросом — и «Не пришёл» водителю уже не поставить.
        # Водитель забыл отметить посадку — бронь закрывает его «Завершить» или ночная уборка.
        if booking.status != BookingStatus.onboard:
            raise herr(409, "Поездку завершит водитель после посадки",
                       "Сәфәрҙе ултырғандан һуң йөрөтөүсе тамамлар")
        # …и после окна «Отменить» водителя (5 минут): иначе «Завершил» в первую же секунду
        # отнимал у водителя право исправить промах карточкой, и «Сел» по ошибке не вернуть.
        undo_left = boarding_undo_left(booking)
        if undo_left > 0:
            raise _boarding_undo_open_refusal(undo_left)
        # Завершить можно только НАЧАВШУЮСЯ поездку — та же планка, что у водительской ручки
        # (`bookings.py`, `DONE_EARLY_GRACE`). Это второй путь к тому же переходу: закрыв один
        # и оставив другой, мы бы просто перенесли дыру, а не убрали её (аудит 2026-08-07).
        # Реферальный бонус за водителя начисляется прямо отсюда, поэтому цена ошибки не только
        # в кривой статистике.
        ride = session.get(Ride, booking.ride_id)
        if ride and ride.depart_at and utcnow() < ride.depart_at - DONE_EARLY_GRACE:
            raise herr(409, "Поездка ещё не началась — завершить можно после времени выезда",
                       "Сәфәр әле башланмаған — сығыу ваҡытынан һуң тамамлап була")
        # Условный UPDATE: «Отменить» водителя в ту же секунду (граница окна) или неявка не
        # перепишутся в «завершена» — и наоборот, завершённую не откатит запоздавшая отмена.
        moved = session.execute(update(Booking).where(
            Booking.id == booking.id, Booking.status == BookingStatus.onboard,
        ).values(status=BookingStatus.done)).rowcount
        session.commit()
        session.refresh(booking)
        if not moved:
            if booking.status != BookingStatus.done:
                raise herr(409, "Поездка уже изменилась — обнови экран",
                           "Сәфәр үҙгәргән инде — экранды яңырт")
            return notify_family_trip_status(session, booking_id, user, body.status)
        trip_wake.poke(booking.id)   # канал координат закрывается сразу (bookings.cancel_booking)
        # B8-4: пассажир завершил поездку → проверяем реферальный бонус за водителя (идемпотентно).
        if ride:
            from .referral import reward_driver_referral
            reward_driver_referral(session, ride.driver_id)
    return notify_family_trip_status(session, booking_id, user, body.status)


def _family_step(last: str, status: str) -> tuple[str, bool]:
    """Новый `last_status` строки шаринга и слать ли SMS близкому.

    Посадку водитель может отменить в первые 5 минут (приёмка PR174). Близкий, получивший
    «сел в машину», должен узнать и об отмене — но цикл «Сел» → «Отменить» → «Сел» не должен
    жечь SMS за счёт платформы. Лестница на строке шаринга:
      sat → (Отменить) unsat — SMS «отметку посадки отменили», единственный раз на бронь;
      unsat → (Сел) sat_again — SMS «сел в машину»: близкий должен узнать, что теперь сел;
      sat_again → (Отменить) unsat_quiet — без SMS: отмена уже потрачена;
      unsat_quiet → (Сел) sat_again — без SMS: последнее, что близкий знает, и так «сел».
    Итого на всю посадку — не больше трёх SMS, сколько бы раз ни жали кнопки."""
    if status == "sat":
        if last in ("sat", "sat_again"):
            return last, False
        if last == "unsat_quiet":
            return "sat_again", False
        return ("sat_again" if last == "unsat" else "sat"), True
    if status == "unsat":
        if last == "sat":
            return "unsat", True
        return ("unsat_quiet" if last == "sat_again" else last), False
    return status, last != status


def notify_family_trip_status(session: Session, booking_id: int, passenger: User, status: str) -> List[TripShare]:
    """Статус поездки — близким пассажира, с кем он поделился этой бронью (SMS при смене статуса).

    Зовут оба пути: пассажир («Доехал», «Завершил») и водитель — его отметка «Сел» шлёт «sat»
    (`bookings._mark_boarded`, решение 17.10.2026). Имя, язык и счёт SMS — пассажира: это его
    близкие, и потолок SMS один на все его входы. Возвращает строки шаринга этой брони."""
    contact_ids = [c.id for c in session.exec(select(TrustedContact).where(TrustedContact.user_id == passenger.id)).all()]
    if not contact_ids:
        return []
    shares = session.exec(select(TripShare).where(TripShare.booking_id == booking_id, TripShare.contact_id.in_(contact_ids))).all()
    # SMS шлём ТОЛЬКО тем, у кого статус реально сменился — иначе повторный вызов того же
    # статуса (sat→sat) = бесконечные SMS за счёт платформы (у SOS троттл есть, тут не было).
    steps = {s.id: _family_step(s.last_status, status) for s in shares}
    changed_ids = [s.contact_id for s in shares if steps[s.id][1]]
    for share in shares:
        share.last_status = steps[share.id][0]
        session.add(share)
    session.commit()
    for share in shares:
        session.refresh(share)  # после commit объекты «обнуляются» — перечитываем
    # Реально уведомляем близких по SMS о статусе поездки.
    status_text = {"sat": "сел в машину", "arrived": "доехал до места", "done": "завершил поездку",
                   "unsat": "— отметку посадки отменили"}.get(status, status)
    # То же по-башкирски: SMS близким уходили только по-русски (волна 95). Язык берём у того,
    # кто завёл контакт: про язык его мамы мы ничего не знаем, а он знает.
    status_text_ba = {"sat": "машинаға ултырҙы", "arrived": "урынына етте",
                      "done": "сәфәрҙе тамамланы", "unsat": "— ултырыу билдәһе кире алынды"}.get(status, status_text)
    who = passenger.name or passenger.phone
    for cid in changed_ids:
        c = session.get(TrustedContact, cid)
        # «Только SOS» — не украшение списка, а обещание. В приложении у контакта с выключенным
        # `notify_by_default` так и написано: «Только SOS». Человек ставит эту пометку пожилой
        # маме — пусть её тревожат, только если беда. Сервер пометку не смотрел, и мама получала
        # SMS на каждый шаг поездки: «сел в машину», «доехал» (аудит 2026-08-08, волна 81).
        # Разовую ссылку слежения при шаринге контакт по-прежнему получает: ею поделились явно,
        # это не поток сообщений. И SOS приходит ВСЕМ контактам — там пометка не действует,
        # жизнь дороже настроек.
        if c and c.phone and c.notify_by_default:
            # Тихо: статус едет автоматом по ходу поездки, и красная ошибка «слишком много
            # сообщений» посреди дороги человеку не поможет. Потолок всё равно общий.
            if may_send_family_sms(session, passenger.id, "status"):
                lang = sms_lang_of(session, passenger.id)
                send_text(c.phone, pick_lang(
                    lang, f"Юлдаш: {who} {status_text}.", f"Юлдаш: {who} {status_text_ba}.",
                ))
    # Учёт SMS коммитит сессию — после него объекты «обнуляются». Перечитываем ПЕРЕД ответом,
    # иначе клиент получил бы пустые поля вместо статусов (поймал tests/test_flows.py).
    for share in shares:
        session.refresh(share)
    return shares


class RateIn(BaseModel):
    stars: int
    text: str = Field("", max_length=500)   # текстовый отзыв (опц.) — на модерацию, ≤500
    # Быстрые метки, CSV («polite,ontime»). Длину режем уже на входе, чтобы мегабайтная строка
    # не доехала до валидатора; сам список фильтрует safety_logic.clean_tags.
    tags: str = Field("", max_length=300)


@router.post("/bookings/{booking_id}/rate")
def rate_booking(booking_id: int, body: RateIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Оценить вторую сторону поездки (1..5) + опц. текстовый отзыв и быстрые метки.
    Пассажир оценивает водителя, водитель — пассажира. Одна оценка на бронь от каждого.
    Текст (если есть) появляется в публичном профиле только после модерации (`text_published`);
    метки — сразу: они из закрытого списка, оскорбить ими нельзя."""
    b = session.get(Booking, booking_id)
    if not b:
        raise herr(404, "Бронь не найдена", "Бронь табылманы")
    ride = session.get(Ride, b.ride_id)
    if user.id == b.passenger_id and ride:
        ratee_id = ride.driver_id          # пассажир → водитель
    elif ride and user.id == ride.driver_id:
        ratee_id = b.passenger_id          # водитель → пассажир
    else:
        raise herr(403, "Нельзя оценить эту поездку", "Был сәфәрҙе баһалап булмай")
    # Оценить можно только ЗАВЕРШЁННУЮ поездку — иначе можно забронировать и сразу накрутить
    # рейтинг водителю, не съездив (репутация «между своими» = продукт). Проверка ПОСЛЕ участника:
    # чужой получает 403, а участник недозавершённой — 409.
    if b.status != BookingStatus.done:
        raise herr(409, "Оценить можно только завершённую поездку",
                   "Тик тамамланған сәфәрҙе генә баһаларға була")
    # Срок на оценку. Считаем от времени выезда: у брони своего «завершено в» нет, а поездка —
    # это про день выезда. Оценка через год говорит уже не о поездке (волна 57).
    guard_rating_window(ride.depart_at if ride else None)
    avg, cnt = apply_rating(session, user, ratee_id, stars=body.stars, text=body.text,
                            tags=body.tags, booking_id=booking_id, place="review",
                            happened_at=(ride.depart_at if ride else None))
    return {"ratee_id": ratee_id, "rating": round(avg, 1), "count": cnt}


# ---- «Сказать рәхмәт» (чаевые водителю) ----
# Два слоя: бесплатное «рәхмәт» (тёплый жест, БЕЗ денег) — всегда; денежные чаевые «на доверии»
# (СБП водителя, opt-in) — только при settings.tips_money_enabled. Платформа денег НЕ касается.
class TipsSbpIn(BaseModel):
    sbp: str = Field("", max_length=40)   # СБП-телефон водителя; пусто = не принимаю денежные чаевые


@router.post("/me/tips-sbp")
def set_tips_sbp(body: TipsSbpIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель включает/выключает денежные чаевые: указывает свой СБП-телефон (или пусто — отключить).
    Только для водителя (есть DriverProfile). Реквизит показывается пассажиру ТОЛЬКО после завершённой
    поездки и при tips_money_enabled — личное отдаём по согласию (opt-in) и минимально."""
    prof = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
    if not prof:
        raise herr(403, "Только для водителя", "Тик водитель өсөн")
    sbp = (body.sbp or "").strip()
    if sbp and not _PHONE_RE.match(sbp.replace(" ", "").replace("-", "")):
        raise herr(400, "Неверный номер СБП", "СБП номеры дөрөҫ түгел")
    prof.tips_sbp = sbp
    session.add(prof)
    session.commit()
    return {"tips_sbp": prof.tips_sbp, "accepting": bool(prof.tips_sbp)}


def _booking_for_passenger_done(session: Session, booking_id: int, user: User) -> Booking:
    """Бронь ЭТОГО пассажира по ЗАВЕРШЁННОЙ поездке (для «рәхмәт»). Иначе 404/403/409.
    Проверка статуса ПОСЛЕ участника: чужой получает 403, участник недозавершённой — 409."""
    b = session.get(Booking, booking_id)
    if not b:
        raise herr(404, "Бронь не найдена", "Бронь табылманы")
    if b.passenger_id != user.id:
        raise herr(403, "Доступно только пассажиру поездки", "Тик сәфәр пассажирына мөмкин")
    if b.status != BookingStatus.done:
        raise herr(409, "Поблагодарить можно после завершения поездки", "Рәхмәт әйтеү сәфәр тамамланғандан һуң мөмкин")
    return b


@router.get("/bookings/{booking_id}/tip")
def booking_tip_info(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Пассажиру после поездки: чем поблагодарить водителя. Бесплатное «рәхмәт» — всегда;
    денежные чаевые (СБП водителя) — только если водитель их включил (opt-in) И tips_money_enabled.
    Телефон водителя (СБП) до включения флага наружу НЕ идёт."""
    b = _booking_for_passenger_done(session, booking_id, user)
    ride = session.get(Ride, b.ride_id)
    driver = session.get(User, ride.driver_id) if ride else None
    driver_name = (driver.name if driver else "") or "Водитель"
    money = None
    if settings.tips_money_enabled and ride:
        prof = session.exec(select(DriverProfile).where(DriverProfile.user_id == ride.driver_id)).first()
        if prof and (prof.tips_sbp or "").strip():
            money = {"sbp": prof.tips_sbp, "name": driver_name}
    return {"driver_name": driver_name, "already_thanked": bool(b.thanked), "money": money}


def _order_for_passenger_done(session: Session, order_id: int, user: User) -> InstantOrder:
    """Такси-заказ ЭТОГО пассажира, завершённый (для «рәхмәт»). Порядок проверок как у брони."""
    o = session.get(InstantOrder, order_id)
    if not o:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if o.passenger_id != user.id:
        raise herr(403, "Доступно только пассажиру заказа", "Тик заказ пассажирына мөмкин")
    if (o.status.value if hasattr(o.status, "value") else o.status) != "done":
        raise herr(409, "Поблагодарить можно после завершения поездки", "Рәхмәт әйтеү сәфәр тамамланғандан һуң мөмкин")
    return o


@router.get("/instant/orders/{order_id}/tip")
def order_tip_info(order_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """То же, что /bookings/{id}/tip, но для ТАКСИ-заказа: благодарность была только у попуток,
    хотя водителю такси её говорят чаще (помог с сумками, довёз в метель)."""
    o = _order_for_passenger_done(session, order_id, user)
    driver = session.get(User, o.driver_id) if o.driver_id else None
    driver_name = (driver.name if driver else "") or "Водитель"
    money = None
    if settings.tips_money_enabled and o.driver_id:
        prof = session.exec(select(DriverProfile).where(DriverProfile.user_id == o.driver_id)).first()
        if prof and (prof.tips_sbp or "").strip():
            money = {"sbp": prof.tips_sbp, "name": driver_name}
    return {"driver_name": driver_name, "already_thanked": bool(o.thanked), "money": money}


@router.post("/instant/orders/{order_id}/thanks")
def order_thanks(order_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """«Сказать рәхмәт» водителю такси (БЕЗ денег). Идемпотентно по order.thanked."""
    o = _order_for_passenger_done(session, order_id, user)
    if o.thanked:
        return {"ok": True, "already": True}
    o.thanked = True
    session.add(o)
    session.commit()
    if o.driver_id:
        try:  # без ПДн — просто тёплое спасибо
            push_bilingual(session, o.driver_id,
                           "Тебе сказали рәхмәт 💚", "Һиңә рәхмәт әйттеләр 💚",
                           "Пассажир поблагодарил за поездку.",
                           "Юлаусы сәфәр өсөн рәхмәт әйтте.")
        except Exception:
            pass
    return {"ok": True, "already": False}


@router.post("/bookings/{booking_id}/thanks")
def booking_thanks(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Пассажир жмёт «Сказать рәхмәт» — тёплый жест водителю (БЕЗ денег). Идемпотентно
    (дедуп по booking.thanked): повтор второй пуш не шлёт. Пуш водителю best-effort."""
    b = _booking_for_passenger_done(session, booking_id, user)
    if b.thanked:
        return {"ok": True, "already": True}
    b.thanked = True
    session.add(b)
    session.commit()
    ride = session.get(Ride, b.ride_id)
    if ride:
        try:  # без ПДн — просто тёплое спасибо
            push_bilingual(session, ride.driver_id,
                           "Тебе сказали рәхмәт 💚", "Һиңә рәхмәт әйттеләр 💚",
                           "Пассажир поблагодарил за поездку.",
                           "Юлаусы сәфәр өсөн рәхмәт әйтте.")
        except Exception:
            pass
    return {"ok": True, "already": False}
