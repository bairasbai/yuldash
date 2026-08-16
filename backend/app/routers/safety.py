"""Безопасность: SOS (с SMS доверенным контактам), жалобы (§9 Качество), блокировки."""
from datetime import datetime, timedelta

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select
from typing import List, Literal, Optional

from ..db import get_session
from ..errors import herr
from ..config import settings
from ..logs import admin_action, log
from ..middleware import user_over_limit
from ..models import (
    Block, Booking, BookingStatus, DriverProfile, InstantOrder, Report, Ride, SosEvent,
    TripShare, TrustedContact, User, UserRole,
)
from ..safety_logic import have_met
from ..security import current_user
from ..services import (booking_and_ride_for_user, notify_admin_telegram, pick_lang, sms_lang_of,
                        push_notification, send_push, send_text)
from ..timeutil import utcnow
from .. import quality

router = APIRouter(tags=["safety"])

# F12 «Зимний протокол»: авто-проверка «доехал?».
# Если участник не подтвердил «всё в порядке» за это время после пуша — эскалация доверенным контактам.
# Порог живёт в `winter_escalate` — одно число на ручку и на робота (волна 114).
from ..winter_escalate import ESCALATE_AFTER_MIN as WINTER_ESCALATE_AFTER_MIN  # noqa: E402

# Анти-спам SMS: SOS-событие пишем ВСЕГДА (жизнь дороже), но рассылку доверенным контактам
# глушим, если за последний час их уже оповещали > N раз — иначе мэш-кнопка = поток SMS и расходы.
SOS_SMS_PER_HOUR = 6


def _send_sos_sms(phones: list, text: str) -> None:
    """Рассылка SOS-SMS доверенным контактам — в фоне (после ответа), чтобы не держать
    коннект БД и не заставлять паникующего ждать sms.ru по ~10с на контакт."""
    for ph in phones:
        if ph:
            send_text(ph, text)


def _sos_text(session: Session, user_id: int, ru: str, ba: str) -> str:
    """Текст беды на языке того, кто её отправляет (аудит 2026-08-08, волна 121).

    Спокойные сообщения близким давно двуязычные (волна 95), а единственное срочное — «SOS,
    место, машина» — уходило всегда по-русски. Получалось наоборот: про «сел в машину» мама
    читала по-башкирски, а про беду — на чужом языке, и в тот момент, когда разбираться
    некогда.

    Язык берём у того, кто завёл контакт: про язык его мамы мы ничего не знаем, а он знает.
    """
    return pick_lang(sms_lang_of(session, user_id), ru, ba)


class SosIn(BaseModel):
    category: Literal["medical", "breakdown", "other"] = "other"   # закрытый список (было: любая строка в БД/админу)
    booking_id: Optional[int] = None
    order_id: Optional[int] = None      # контекст такси-заказа (B7b-2): админ видит, из какой поездки SOS
    note: str = Field("", max_length=2000)
    # Где человек. Мягкая кнопка «застрял на трассе» слала близким ссылку на карту, а красный
    # SOS — нет: родные получали «нужна срочная помощь» и не знали, куда ехать (аудит 2026-08-06).
    # Необязательны: GPS мог не схватиться — тогда шлём хотя бы сам сигнал.
    lat: Optional[float] = Field(None, ge=-90, le=90)
    lng: Optional[float] = Field(None, ge=-180, le=180)


def _car_of(session: Session, driver_id: "int | None") -> str:
    """Описание машины: «белая Лада Гранта А123БВ102». Пусто, если анкета не заполнена.

    Черта, по которой мы делим данные водителя при SOS (решение 2026-08-06):
    ЧТО ВИДНО СНАРУЖИ МАШИНЫ — можно отдать близким; ЧТО ЗАПИСАНО В АНКЕТЕ (имя, телефон) —
    только дежурному. Госномер висит на машине ровно затем, чтобы посторонний мог её опознать,
    и это первое, что спросит полиция. Имя и телефон родным действовать не помогают.
    """
    if not driver_id:
        return ""
    prof = session.exec(select(DriverProfile).where(DriverProfile.user_id == driver_id)).first()
    if not prof:
        return ""
    return " ".join(x for x in (prof.car_color, prof.car_model, prof.car_plate) if x).strip()


def _ride_context_line(session: Session, ride: "Ride | None") -> str:
    """Строка «с кем и на чём человек уехал» для SOS из попутки — админу в Telegram.

    Отдаётся ТОЛЬКО админу и только в момент сигнала: это тот случай, когда данные второй
    стороны важнее её приватности. В stdout не пишем (§8), в само событие не кладём.
    """
    if ride is None:
        return ""
    driver = session.get(User, ride.driver_id)
    car = _car_of(session, ride.driver_id)
    return (f"Попутка: #{ride.id} {ride.from_city or '?'} → {ride.to_city or '?'}\n"
            f"Водитель: {(driver.name if driver else None) or '—'}, тел {(driver.phone if driver else None) or '—'}"
            f"{(', ' + car) if car else ''}\n")


def _order_for_participant(session: Session, order_id: int, user: User) -> InstantOrder:
    """Такси-заказ, если пользователь — его участник (пассажир или назначенный водитель)."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if user.id != order.passenger_id and (order.driver_id is None or user.id != order.driver_id):
        raise herr(403, "Ты не участник этого заказа", "Һин был заказда ҡатнашмайһың")
    return order


@router.post("/sos", response_model=SosEvent)
def sos(body: SosIn, background: BackgroundTasks, user: User = Depends(current_user), session: Session = Depends(get_session)):
    ride = None
    if body.booking_id is not None:
        _, ride = booking_and_ride_for_user(session, body.booking_id, user)
    order = _order_for_participant(session, body.order_id, user) if body.order_id is not None else None
    # Сколько SOS уже было за последний час (ДО записи нового) — для кепа SMS.
    recent = session.exec(
        select(SosEvent).where(
            SosEvent.user_id == user.id, SosEvent.created_at >= utcnow() - timedelta(hours=1)
        )
    ).all()
    # Место кладём в ТЕКСТ события (как у «застрял»): отдельной колонки под координаты нет,
    # а заводить её ради ссылки — миграция ради ссылки. В stdout координаты НЕ пишем (152-ФЗ).
    link = _maps_link(body.lat, body.lng)
    where = f" Место: {link}" if link else ""
    fields = body.model_dump(exclude={"lat", "lng"})
    fields["note"] = (fields.get("note") or "").strip() + where
    event = SosEvent(user_id=user.id, **fields)
    session.add(event)
    session.commit()                 # событие фиксируем СИНХРОННО (жизнь дороже) — данные не теряются
    session.refresh(event)
    # Машина, в которой человек едет. Родные — самые быстрые помощники: они уже за рулём, пока
    # дежурный читает Telegram. «Уехала на попутке в Сибай» действовать не помогает, «белая Лада
    # А123БВ102» — помогает, и это же первое, что спросит полиция (решение 2026-08-06).
    # Имя и телефон водителя сюда НЕ идут: родным они не нужны, это уже данные из анкеты.
    car = _car_of(session, ride.driver_id if ride is not None else (order.driver_id if order else None))
    car_text = f" Машина: {car}." if car else ""
    # Телефоны доверенных контактов собираем ПОКА сессия открыта, рассылку SMS — в фон (после ответа).
    notified = 0
    if len(recent) < SOS_SMS_PER_HOUR:
        contacts = session.exec(select(TrustedContact).where(TrustedContact.user_id == user.id)).all()
        who = user.name or user.phone
        phones = [c.phone for c in contacts if c.phone]
        notified = len(phones)
        # Ссылка на карту — главное в этом SMS: без неё родные знают, что беда, но не знают куда ехать.
        background.add_task(
            _send_sos_sms, phones,
            _sos_text(
                session, user.id,
                f"SOS! {who} просит срочной помощи (Юлдаш). Свяжитесь скорее.{where}{car_text}",
                f"SOS! {who} ашығыс ярҙам һорай (Юлдаш). Тиҙерәк бәйләнешкә сыҡ.{where}{car_text}",
            ),
        )
    else:
        log.info(f"[SOS] user={user.id} SMS подавлены (кеп {SOS_SMS_PER_HOUR}/час), событие записано")
    # Уведомление админу в Telegram — тоже в фон (httpx-вызов не держит коннект БД и не тормозит ответ SOS).
    # Контекст такси-заказа (B7b-2): админу — маршрут и вторая сторона, чтобы среагировать по делу.
    order_line = ""
    if order is not None:
        order_line = (f"Такси-заказ: #{order.id} {order.from_text or '?'} → {order.to_text or '?'}"
                      f" (статус {order.status.value})\n")
    # То же самое для попутки (аудит 2026-08-06). Раньше booking_id только ПРОВЕРЯЛСЯ и
    # выбрасывался: из такси админ узнавал маршрут и вторую сторону, а из попутки — ничего,
    # хотя именно там человек садится в машину к незнакомцу. Первый вопрос спасателя —
    # «с кем и на чём уехали», ответ должен быть в самом сигнале, а не искаться потом руками.
    ride_line = _ride_context_line(session, ride)
    background.add_task(
        notify_admin_telegram,
        f"🆘 SOS (Юлдаш)\n"
        f"От: {user.name or '—'}\n"
        f"Тел: {user.phone or '—'}\n"
        f"Категория: {body.category}\n"
        f"{order_line}"
        f"{ride_line}"
        f"Контактов уведомлено (SMS): {notified}\n"
        f"Детали: {body.note or '—'}{where}"
    )
    # 🌙 SMS админу вдобавок к Telegram. Раньше весь ночной контур безопасности сводился к
    # ОДНОМУ сообщению в Telegram: админ спит — никто не узнает, что сигнал вообще был
    # (списка SOS не существовало, статус не менялся никогда). Аудит 2026-07-26.
    # Тот же часовой кеп, что и на SMS близким. SMS платные, а сорок нажатий подряд дают
    # сорок сообщений админу — и настоящий сигнал тонет среди них (аудит 2026-08-06).
    # Telegram намеренно НЕ капим: он бесплатный и там нужна полная картина, включая флуд.
    if settings.sos_sms_to_admin and len(recent) < SOS_SMS_PER_HOUR:
        admin_phones = [p.strip() for p in (settings.admin_phones or "").split(",") if p.strip()]
        if admin_phones:
            background.add_task(
                _send_sos_sms, admin_phones,
                f"SOS Юлдаш: {user.name or 'пользователь'}, {body.category}, "
                f"тел {user.phone or '—'}.{where} Открой админку.",
            )
    log.info(f"[SOS] user={user.id} category={body.category} contacts_notified={notified}")
    return event


# ----------------------------- Админ: лента SOS (аудит 2026-07-26) -----------------------------
class SosHandleIn(BaseModel):
    note: str = Field("", max_length=500)


@router.get("/admin/sos")
def admin_sos_list(status: str = "open", limit: int = 100,
                   user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Лента сигналов SOS: открытые сверху. Раньше её НЕ СУЩЕСТВОВАЛО — сигнал уходил одним
    сообщением в Telegram, поле status не менялось никем и никогда, и если сообщение не
    прочитали (ночь, шумный чат), следа о происшествии не оставалось нигде."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    q = select(SosEvent).order_by(SosEvent.created_at.desc()).limit(max(1, min(limit, 300)))
    if status in ("open", "handled"):
        q = q.where(SosEvent.status == status)
    rows = session.exec(q).all()
    out = []
    for e in rows:
        u = session.get(User, e.user_id)
        route = ""
        if e.order_id:
            o = session.get(InstantOrder, e.order_id)
            if o:
                route = f"{o.from_text or '?'} → {o.to_text or '?'}"
        elif e.booking_id:
            b = session.get(Booking, e.booking_id)
            r = session.get(Ride, b.ride_id) if b else None
            if r:
                route = f"{r.from_city} → {r.to_city}"
        out.append({
            "id": e.id, "status": e.status, "category": e.category, "note": e.note,
            "created_at": e.created_at, "handled_at": e.handled_at, "handled_note": e.handled_note,
            "user_id": e.user_id,
            "user_name": (u.name if u else "") or "—",
            "user_phone": (u.phone if u else "") or "—",   # админу телефон нужен, чтобы позвонить
            "booking_id": e.booking_id, "order_id": e.order_id, "route": route,
        })
    return out


@router.post("/admin/sos/{event_id}/handle")
def admin_sos_handle(event_id: int, body: SosHandleIn | None = None,
                     user: User = Depends(current_user), session: Session = Depends(get_session)):
    """«Принял» — сигнал взят в работу: кто, когда, что сделал. Без этой отметки нельзя было
    отличить разобранный SOS от потерянного, и авто-эскалация была невозможна."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    e = session.get(SosEvent, event_id)
    if not e:
        raise herr(404, "Событие не найдено", "Ваҡиға табылманы")
    if e.status == "handled":
        return {"ok": True, "already": True, "status": e.status}
    e.status = "handled"
    e.handled_at = utcnow()
    e.handled_by = user.id
    e.handled_note = ((body.note if body else "") or "").strip()[:500]
    session.add(e)
    session.commit()
    admin_action(user.id, "sos.handle", event_id=event_id, target_user=e.user_id)
    return {"ok": True, "status": e.status, "handled_at": e.handled_at}


class CallbackIn(BaseModel):
    note: str = Field("", max_length=500)


@router.post("/callback")
def request_callback(body: CallbackIn, user: User = Depends(current_user)):
    """Запрос «перезвоните мне» (помощь пожилым/без интернета). Уведомляет админа в Telegram
    с телефоном пользователя, чтобы реально перезвонили. Запись не храним — это поддержка."""
    notify_admin_telegram(
        f"📞 Запрос звонка (Юлдаш)\n"
        f"От: {user.name or '—'}\n"
        f"Тел: {user.phone}\n"
        f"Сообщение: {body.note or '—'}"
    )
    return {"ok": True}


ReportCategory = Literal[
    "rude", "kicked_out", "dangerous_driving", "price_fraud", "dirty_car",
    "late", "safety_threat", "no_show", "damage", "unpaid", "other",
]


# Потолок НОВЫХ жалоб на человека в час. Дедуп (`_dedup_report`) держит «один автор — одна
# жалоба по одному поводу», но повод включает ЦЕЛЬ: меняя target_user_id, один вошедший
# создаёт сколько угодно разных жалоб, а тяжёлая категория на каждой дёргает Telegram
# Александра (`quality.escalate_severe`). Ровно та причина, по которой /callback и /donate
# живут в строгом бюджете лимитера, — а /reports в него не попал (аудит 2026-08-08).
# Живой человек жалуется по итогу поездки, то есть единицы раз в день.
MAX_REPORTS_PER_HOUR = 10


class ReportIn(BaseModel):
    # target_user_id опционален при привязке к поездке (вторая сторона вычисляется сервером).
    target_user_id: Optional[int] = None
    reason: str = Field("", max_length=1000)   # свободные детали (анти-раздувание таблицы)
    category: ReportCategory = "other"         # закрытый перечень §9 (default — совместимость)
    order_id: Optional[int] = None             # привязка к быстрому заказу
    booking_id: Optional[int] = None           # привязка к брони попутки


class ReportCreatedOut(BaseModel):
    """Ответ автору жалобы — БЕЗ reporter_id в теле (анонимность: наружу автора не отдаём,
    даже самому себе не нужен — он и так знает)."""
    id: int
    category: str
    status: str
    created_at: datetime


class ReportOut(BaseModel):
    id: int
    reporter_name: str        # видит ТОЛЬКО админ (эта ручка admin-only)
    target_name: str
    target_phone: str
    reason: str
    created_at: datetime
    # Волна 2 §9 (старые поля выше не убираем — совместимость со старым админ-экраном).
    category: str = "other"
    status: str = "new"
    resolution: Optional[str] = None
    order_id: Optional[int] = None
    booking_id: Optional[int] = None
    parcel_id: Optional[int] = None   # C2: спор по доставке (category=parcel_dispute)
    target_user_id: Optional[int] = None


def _report_counterparty(session: Session, user: User, body: ReportIn) -> int:
    """Вторая сторона поездки/заказа. Проверяем: reporter — участник, цель — второй участник.
    Без привязки — прежнее поведение (target_user_id обязателен)."""
    if body.order_id is not None:
        order = session.get(InstantOrder, body.order_id)
        if not order:
            raise herr(404, "Заказ не найден", "Заказ табылманы")
        if user.id == order.passenger_id:
            other = order.driver_id
        elif order.driver_id is not None and user.id == order.driver_id:
            other = order.passenger_id
        else:
            raise herr(403, "Ты не участник этого заказа", "Һин был заказда ҡатнашмайһың")
        if other is None:
            raise herr(409, "У заказа нет второй стороны", "Заказдың икенсе яғы юҡ")
        return other
    if body.booking_id is not None:
        b = session.get(Booking, body.booking_id)
        ride = session.get(Ride, b.ride_id) if b else None
        if not b or not ride:
            raise herr(404, "Бронь не найдена", "Бронь табылманы")
        if user.id == b.passenger_id:
            return ride.driver_id
        if user.id == ride.driver_id:
            return b.passenger_id
        raise herr(403, "Ты не участник этой поездки", "Һин был сәфәрҙә ҡатнашмайһың")
    if body.target_user_id is None:
        raise herr(400, "Укажи, на кого жалоба, или поездку", "Ялыу кемгә икәнен йәки сәфәрҙе күрһәт")
    return body.target_user_id


@router.get("/admin/reports", response_model=List[ReportOut])
def admin_reports(status: Optional[str] = None, category: Optional[str] = None,
                  user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Жалобы для разбора админом (кто на кого, категория, статус). Автора жалобы видит
    ТОЛЬКО эта admin-ручка. Фильтры: ?status=new|reviewing|resolved|rejected, ?category=…"""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    q = select(Report)
    if status:
        q = q.where(Report.status == status)
    if category:
        q = q.where(Report.category == category)
    reports = session.exec(q.order_by(Report.id.desc()).limit(200)).all()
    if not reports:
        return []
    ids: set = set()
    for r in reports:
        ids.add(r.reporter_id)
        if r.target_user_id is not None:      # обвиняемый мог удалить аккаунт (обезличено)
            ids.add(r.target_user_id)
    users = {u.id: u for u in session.exec(select(User).where(User.id.in_(ids))).all()}
    out: list = []
    for r in reports:
        rep = users.get(r.reporter_id)
        tgt = users.get(r.target_user_id)
        out.append(ReportOut(
            id=r.id,
            reporter_name=(rep.name if rep and rep.name else "—"),
            target_name=(tgt.name if tgt and tgt.name else "—"),
            target_phone=(tgt.phone if tgt else ""),
            reason=r.reason, created_at=r.created_at,
            category=r.category, status=r.status, resolution=r.resolution,
            order_id=r.order_id, booking_id=r.booking_id,
            parcel_id=getattr(r, "parcel_id", None), target_user_id=r.target_user_id,
        ))
    return out


def _guard_unpaid_report(session: Session, user: User, body: ReportIn) -> Optional[Report]:
    """B8-7 «Пассажир не заплатил» одним тапом. Правила для category=unpaid с привязкой:
    жалуется ТОЛЬКО водитель, поездка ЗАВЕРШЕНА (done), одна жалоба на заказ/бронь (дедуп —
    повтор возвращает существующую). Возврат: существующая жалоба (дедуп) или None (создаём)."""
    if body.category != "unpaid" or (body.order_id is None and body.booking_id is None):
        return None
    if body.order_id is not None:
        order = session.get(InstantOrder, body.order_id)   # существование проверено в _report_counterparty
        if order.driver_id != user.id:
            raise herr(403, "«Не заплатил» отмечает водитель поездки", "«Түләмәне» тип сәфәр водителе билдәләй")
        if order.status.value != "done":
            raise herr(409, "Отметить можно только завершённую поездку", "Тик тамамланған сәфәрҙе билдәләп була")
        dup = session.exec(select(Report).where(
            Report.order_id == body.order_id, Report.category == "unpaid",
        )).first()
        if dup:
            return dup
        order.unpaid_reported = True   # пометка на заказе (для истории/админа)
        session.add(order)
    else:
        b = session.get(Booking, body.booking_id)
        ride = session.get(Ride, b.ride_id)
        if ride.driver_id != user.id:
            raise herr(403, "«Не заплатил» отмечает водитель поездки", "«Түләмәне» тип сәфәр водителе билдәләй")
        if (b.status.value if hasattr(b.status, "value") else b.status) != "done":
            raise herr(409, "Отметить можно только завершённую поездку", "Тик тамамланған сәфәрҙе билдәләп була")
        dup = session.exec(select(Report).where(
            Report.booking_id == body.booking_id, Report.category == "unpaid",
        )).first()
        if dup:
            return dup
        b.unpaid_reported = True
        session.add(b)
    return None



def _dedup_report(session: Session, user: User, body: ReportIn, target_id: int) -> Optional[Report]:
    """Одна жалоба от одного человека на одного человека по одному поводу.

    Зачем. Три resolved-жалобы за месяц автоматически ставят такси на паузу
    (`quality.apply_ladder_after_resolve`), а считаются СТРОКИ, без учёта того, кто их подал.
    Значит один человек, нажав «Пожаловаться» три раза, собирал всю лестницу в одиночку —
    и не обязательно со зла: на слабой связи люди жмут повторно, потому что «ничего
    не произошло» (аудит 2026-08-06).

    Повод — это (автор, цель, категория) плюс конкретная поездка, если она указана. По РАЗНЫМ
    поездкам и по разным категориям пожаловаться по-прежнему можно: это разные события.
    Без привязки к поездке ограничиваемся сутками — иначе человек не сможет пожаловаться
    на соседа второй раз спустя месяц.

    Повтор не отвергаем ошибкой, а возвращаем уже созданную жалобу: для человека это выглядит
    как «сработало», и он не жмёт снова. Тот же приём уже применён к «не заплатил» (B8-7)."""
    conds = [
        Report.reporter_id == user.id,
        Report.target_user_id == target_id,
        Report.category == body.category,
    ]
    if body.order_id is not None:
        conds.append(Report.order_id == body.order_id)
    elif body.booking_id is not None:
        conds.append(Report.booking_id == body.booking_id)
    else:
        conds.append(Report.created_at >= utcnow() - timedelta(days=1))
    return session.exec(select(Report).where(*conds).order_by(Report.id.desc())).first()


@router.post("/reports", response_model=ReportCreatedOut)
def create_report(body: ReportIn,
                  user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Пожаловаться (§9). Категория из перечня + опциональная привязка к поездке/заказу
    (тогда цель = вторая сторона, участие проверяется). Анонимно: цель получает пуш с
    категорией БЕЗ автора; тяжёлая категория → мгновенно админу + пауза такси до разбора.
    B8-7: category=unpaid с привязкой — кнопка «Пассажир не заплатил» (только водитель,
    только done, дедуп на заказ/бронь; страйк пассажиру через механику B3/B5)."""
    target_id = _report_counterparty(session, user, body)
    if body.target_user_id is not None and body.target_user_id != target_id:
        raise herr(400, "Цель жалобы не совпадает со второй стороной поездки", "Ялыу кемгә тигәне сәфәрҙең икенсе яғы менән тап килмәй")
    if target_id == user.id:
        raise herr(400, "Нельзя пожаловаться на себя", "Үҙеңә ялыу яҙып булмай")
    if not session.get(User, target_id):
        raise herr(404, "Пользователь не найден", "Ҡулланыусы табылманы")
    dup = _guard_unpaid_report(session, user, body)
    if dup is None:
        # Общий дедуп: один автор — одна жалоба по одному поводу (см. _dedup_report).
        dup = _dedup_report(session, user, body, target_id)
    if dup is not None:   # повторный тап идемпотентен: возвращаем уже созданную жалобу
        return ReportCreatedOut(id=dup.id, category=dup.category,
                                status=dup.status, created_at=dup.created_at)
    # Потолок считаем ЗДЕСЬ, а не в начале: повторный тап по той же жалобе выше вернулся
    # идемпотентно и бюджет не потратил. Ограничиваем только создание НОВОЙ жалобы.
    if user_over_limit("report_create", user.id, MAX_REPORTS_PER_HOUR, window_sec=3600):
        raise herr(429, "Слишком много жалоб подряд. Подожди немного.",
                   "Артыҡ күп зар. Бер аҙ көт.")
    report = Report(
        reporter_id=user.id, target_user_id=target_id, reason=body.reason,
        category=body.category, order_id=body.order_id, booking_id=body.booking_id,
    )
    session.add(report)
    session.commit()
    session.refresh(report)
    # ⛔ Тяжёлая — железно и сразу: пауза такси цели до разбора + Telegram админу (синхронно
    # ставим паузу, уведомления — как есть; notify внутри не роняет запрос).
    quality.escalate_severe(session, report, user)
    # Пуш цели — анонимный (категория БЕЗ автора). send_push без Firebase — мгновенный no-op.
    quality.notify_target_new_report(session, report)
    return ReportCreatedOut(id=report.id, category=report.category,
                            status=report.status, created_at=report.created_at)


class ResolveIn(BaseModel):
    resolution: str = Field("", max_length=1000)
    # Тяжёлая жалоба держит паузу «до разбора»: resolve решает — снять или оставить
    # (оставить = перевести в честную таймерную паузу quality_pause_hours).
    keep_pause: bool = False


@router.post("/admin/reports/{report_id}/resolve", response_model=ReportOut)
def admin_resolve_report(report_id: int, body: ResolveIn,
                         user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Разбор жалобы человеком: подтверждена (resolved). Дальше лестница §9: ≥N resolved за
    окно → авто-пауза такси цели. Тяжёлая: keep_pause=False снимает паузу разбора,
    True — оставляет (таймерная пауза quality_pause_hours)."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    r = session.get(Report, report_id)
    if not r:
        raise herr(404, "Жалоба не найдена", "Ялыу табылманы")
    r.status = "resolved"
    r.resolution = body.resolution or r.resolution
    r.resolved_at = utcnow()
    session.add(r)
    session.commit()
    session.refresh(r)
    if r.category in quality.SEVERE_CATEGORIES and r.target_user_id is not None:
        if body.keep_pause:
            # Оставить: «до разбора» → честная таймерная пауза (не вечная).
            quality.unpause_taxi(session, r.target_user_id)
            quality.pause_taxi(session, r.target_user_id,
                               hours=quality.settings.quality_pause_hours,
                               reason=quality.PAUSE_REASON_REPORTS)
        else:
            quality.maybe_release_review_pause(session, r.target_user_id)
    # 💸 «Пассажир не заплатил» подтверждена → снимаем с водителя комиссию за ЭТУ поездку.
    # Раньше жалоба ставила только пометку на заказе, а долг оставался: водителя кинули на
    # 300 ₽, и он ещё должен нам 24 ₽ сверху (аудит 2026-07-26). Одна такая история в
    # райцентре расходится по всей деревне и ломает доверие «между своими».
    if r.category == "unpaid" and r.order_id:
        from .. import debt as debt_mod
        try:
            if debt_mod.void_debt_for_order(session, r.order_id):
                session.commit()
                order = session.get(InstantOrder, r.order_id)
                if order and order.driver_id:
                    push_notification(
                        session, order.driver_id, "money",
                        "Комиссия за поездку списана", "Сәфәр комиссияһы алып ташланды",
                        "Жалоба «пассажир не заплатил» подтверждена — комиссию за эту "
                        "поездку с тебя сняли.",
                        "«Пассажир түләмәне» ялыуы раҫланды — был сәфәр өсөн комиссия "
                        "һинән алып ташланды.",
                        ref_kind="debt", ref_id=order.driver_id,
                    )
        except Exception as e:  # noqa: BLE001 — разбор жалобы важнее, чем побочка со списанием
            log.warning(f"[DEBT] списание долга по заказу {r.order_id}: {type(e).__name__}: {e}")
    # 🔴 Лестница: накопленные resolved-жалобы за окно → авто-пауза (+пуш).
    if r.target_user_id is not None:      # аккаунт обвиняемого удалён — наказывать некого
        quality.apply_ladder_after_resolve(session, r.target_user_id)
    quality.tell_report_decision(session, r, confirmed=True)   # автор узнаёт исход (волна 85)
    admin_action(user.id, "report.resolve", report_id=report_id,
                 target_user=r.target_user_id, category=r.category)
    return _admin_report_out(session, r)


@router.post("/admin/reports/{report_id}/reject", response_model=ReportOut)
def admin_reject_report(report_id: int, body: ResolveIn | None = None,
                        user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Жалоба отклонена (не подтвердилась). Тяжёлая: если других открытых тяжёлых на цель
    нет — пауза разбора снимается (отклонили → не наказываем)."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    r = session.get(Report, report_id)
    if not r:
        raise herr(404, "Жалоба не найдена", "Ялыу табылманы")
    r.status = "rejected"
    if body is not None and body.resolution:
        r.resolution = body.resolution
    r.resolved_at = utcnow()
    session.add(r)
    session.commit()
    session.refresh(r)
    quality.maybe_release_review_pause(session, r.target_user_id)
    quality.tell_report_decision(session, r, confirmed=False)   # обе стороны узнают исход (волна 85)
    admin_action(user.id, "report.reject", report_id=report_id, target_user=r.target_user_id)
    return _admin_report_out(session, r)


def _admin_report_out(session: Session, r: Report) -> ReportOut:
    rep = session.get(User, r.reporter_id)
    tgt = session.get(User, r.target_user_id)
    return ReportOut(
        id=r.id,
        reporter_name=(rep.name if rep and rep.name else "—"),
        target_name=(tgt.name if tgt and tgt.name else "—"),
        target_phone=(tgt.phone if tgt else ""),
        reason=r.reason, created_at=r.created_at,
        category=r.category, status=r.status, resolution=r.resolution,
        order_id=r.order_id, booking_id=r.booking_id, target_user_id=r.target_user_id,
    )


class QualityPauseIn(BaseModel):
    hours: int = Field(72, ge=1, le=24 * 365)


@router.post("/admin/quality/{user_id}/pause")
def admin_quality_pause(user_id: int, body: QualityPauseIn,
                        user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Админ вручную ставит/продлевает паузу такси (попутка работает)."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    prof = quality.pause_taxi(session, user_id, hours=body.hours, reason=quality.PAUSE_REASON_ADMIN)
    if prof is None:
        raise herr(404, "Профиль водителя не найден", "Водитель профиле табылманы")
    admin_action(user.id, "quality.pause", target_user=user_id, hours=body.hours)
    return {"ok": True, "taxi_paused_until": prof.taxi_paused_until.isoformat(),
            "reason": prof.taxi_pause_reason}


@router.post("/admin/quality/{user_id}/unpause")
def admin_quality_unpause(user_id: int,
                          user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Админ снимает паузу такси (разбор закончен / поставлено ошибочно)."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    prof = quality.unpause_taxi(session, user_id)
    if prof is None:
        raise herr(404, "Профиль водителя не найден", "Водитель профиле табылманы")
    admin_action(user.id, "quality.unpause", target_user=user_id)
    return {"ok": True, "taxi_paused_until": None, "reason": None}


@router.get("/me/restrictions")
def my_restrictions(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Право объяснения (§9): свои активные ограничения — что, категория, до когда,
    «попутка работает», «напиши в поддержку». БЕЗ раскрытия автора жалобы."""
    return quality.restrictions_payload(session, user)


class BlockIn(BaseModel):
    blocked_user_id: int


@router.post("/blocks", response_model=Block)
def create_block(body: BlockIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    if body.blocked_user_id == user.id:
        raise herr(400, "Нельзя заблокировать себя", "Үҙеңде блоклап булмай")
    # Закрыться человек вправе от кого угодно — в том числе заранее, от соседа, с которым
    # ещё не ездил. Ограничивать это нельзя: кнопка «Заблокировать» и есть его защита.
    if not session.get(User, body.blocked_user_id):
        raise herr(404, "Пользователь не найден", "Ҡулланыусы табылманы")
    existing = session.exec(
        select(Block).where(Block.user_id == user.id, Block.blocked_user_id == body.blocked_user_id)
    ).first()
    if existing:
        return existing
    block = Block(user_id=user.id, **body.model_dump())
    session.add(block)
    session.commit()
    session.refresh(block)
    return block


class BlockOut(BaseModel):
    blocked_user_id: int
    name: str


@router.get("/blocks", response_model=List[BlockOut])
def list_blocks(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Чёрный список текущего пользователя — кого он заблокировал (с именами)."""
    blocks = session.exec(select(Block).where(Block.user_id == user.id)).all()
    ids = {b.blocked_user_id for b in blocks}
    users = {u.id: u for u in session.exec(select(User).where(User.id.in_(ids))).all()} if ids else {}
    out = []
    for b in blocks:
        u = users.get(b.blocked_user_id)
        # Имя показываем ТОЛЬКО если эти двое действительно пересекались (волна 120).
        #
        # Раньше имя отдавалось всегда, а заблокировать можно любого по номеру. Значит цикл
        # «заблокировать 1, 2, 3…» плюс чтение своего же списка выгружал справочник
        # «номер → имя» всех, кто есть в приложении района. Незаметно: человек не узнаёт,
        # что его заблокировали (и это правильно, волна 107).
        #
        # Саму блокировку не ограничиваем: закрыться заранее — право человека. Отбираем
        # ровно то, ради чего перебор и затевался, — чужие имена.
        знакомы = u is not None and have_met(session, user.id, b.blocked_user_id)
        out.append(BlockOut(
            blocked_user_id=b.blocked_user_id,
            name=(u.name if (знакомы and u.name) else "Пользователь"),
        ))
    return out


@router.delete("/blocks/{blocked_user_id}")
def unblock(blocked_user_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Убрать пользователя из чёрного списка."""
    rows = session.exec(
        select(Block).where(Block.user_id == user.id, Block.blocked_user_id == blocked_user_id)
    ).all()
    for r in rows:
        session.delete(r)
    session.commit()
    return {"ok": True}


class ReportableUser(BaseModel):
    id: int
    name: str


@router.get("/reportable-users", response_model=List[ReportableUser])
def reportable_users(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Попутчики, на которых можно пожаловаться/заблокировать — с кем была поездка
    (как пассажир → водители; как водитель → пассажиры). Без глобального списка всех юзеров."""
    ids: set = set()
    # как пассажир → водители моих броней
    my_bookings = session.exec(select(Booking).where(Booking.passenger_id == user.id)).all()
    ride_ids = {b.ride_id for b in my_bookings}
    if ride_ids:
        for r in session.exec(select(Ride).where(Ride.id.in_(ride_ids))).all():
            if r.driver_id != user.id:
                ids.add(r.driver_id)
    # как водитель → пассажиры моих поездок
    my_ride_ids = {r.id for r in session.exec(select(Ride).where(Ride.driver_id == user.id)).all()}
    if my_ride_ids:
        for b in session.exec(select(Booking).where(Booking.ride_id.in_(my_ride_ids))).all():
            if b.passenger_id != user.id:
                ids.add(b.passenger_id)
    if not ids:
        return []
    users = session.exec(select(User).where(User.id.in_(ids))).all()
    return [ReportableUser(id=u.id, name=(u.name or "Пользователь")) for u in users]


# ============================ F12 «Зимний протокол безопасности» ============================
# РБ-фишка «между своими = заботимся»: зимой на трассе между сёлами связь рвётся, темнеет рано,
# мороз опасен. Две живые кнопки + авто-проверка «доехал?». Переиспользуем SosEvent/TripShare/
# TrustedContact/send_push/send_text — новых сущностей не плодим.

def _maps_link(lat: Optional[float], lng: Optional[float]) -> str:
    """Ссылка на точку в Яндекс.Картах для доверенного контакта (найти человека на трассе)."""
    if lat is None or lng is None:
        return ""
    return f"https://yandex.ru/maps/?ll={lng},{lat}&z=16&pt={lng},{lat}"


class StuckIn(BaseModel):
    # Координаты необязательны (GPS мог не схватиться) — тогда шлём хотя бы сигнал «нужна помощь».
    lat: Optional[float] = Field(None, ge=-90, le=90)
    lng: Optional[float] = Field(None, ge=-180, le=180)
    note: str = Field("", max_length=500)


@router.post("/bookings/{booking_id}/stuck")
def roadside_help(
    booking_id: int,
    body: StuckIn,
    background: BackgroundTasks,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """«Я застрял / нужна помощь на трассе» — уровень мягче паники SOS, но реальный: координаты
    уходят доверенным контактам, событие пишется в SOS-ленту админа. Доступно только участнику поездки."""
    booking_and_ride_for_user(session, booking_id, user)   # 403/404 если чужой/нет брони
    return _roadside(session, background, user, body, booking_id=booking_id)


@router.post("/instant/orders/{order_id}/stuck")
def roadside_help_order(
    order_id: int,
    body: StuckIn,
    background: BackgroundTasks,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """То же для ТАКСИ-заказа. Зимний протокол работал только для попуток, хотя именно в такси
    зимой четыре часа трассы Сибай–Уфа: застрявшему в такси идти было некуда, кроме красной
    кнопки SOS (аудит 2026-07-26). Доступно обеим сторонам заказа."""
    _order_for_participant(session, order_id, user)   # общий гейт участия (404/403), не дублируем
    return _roadside(session, background, user, body, order_id=order_id)


@router.post("/parcels/{parcel_id}/stuck")
def roadside_help_parcel(
    parcel_id: int,
    body: StuckIn,
    background: BackgroundTasks,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """То же для ДОСТАВКИ. Кнопка «застрял» была у попутки и у такси, а у курьера — нет,
    хотя он едет по той же зимней трассе и везёт чужую вещь (аудит 2026-08-06: та же
    забытая сторона, что и в других правилах — новое заводили для такси и не возвращались
    к доставке).

    Отправителю уходит уведомление: его посылка не движется, и он должен узнать это
    от нас, а не через неделю от получателя."""
    from ..models import ParcelDelivery
    parcel = session.get(ParcelDelivery, parcel_id)
    if not parcel:
        raise herr(404, "Посылка не найдена", "Бандероль табылманы")
    is_sender = user.id == parcel.sender_id
    is_courier = parcel.courier_id is not None and user.id == parcel.courier_id
    if not (is_sender or is_courier):
        raise herr(403, "Ты не участник этой доставки",
                   "Һин был доставканың ҡатнашыусыһы түгел")

    event = _roadside(session, background, user, body, parcel_id=parcel_id)

    # Отправителю — отдельно и по-человечески (курьеру самому себе не пишем).
    if is_courier and parcel.sender_id:
        from ..services import push_notification
        push_notification(
            session, parcel.sender_id, "parcel",
            "Курьер застрял в дороге", "Курьер юлда ҡалған",
            f"Доставка {parcel.from_city or '?'} → {parcel.to_city or '?'} задерживается. "
            "Мы уже знаем и разбираемся.",
            f"{parcel.from_city or '?'} → {parcel.to_city or '?'} доставкаһы тотҡарлана. "
            "Беҙ беләбеҙ, хәл итәбеҙ.",
            ref_kind="parcel", ref_id=parcel.id,
        )
    return event


def _roadside(session: Session, background: BackgroundTasks, user: User, body: "StuckIn",
              booking_id: Optional[int] = None, order_id: Optional[int] = None,
              parcel_id: Optional[int] = None) -> SosEvent:
    """Общая механика «застрял»: событие в ленту админа + SMS доверенным + Telegram.
    Одна реализация на попутку, такси и доставку — иначе они разойдутся при первой же правке.

    Про доставку. У SosEvent нет колонки под посылку (есть под бронь и под заказ), поэтому
    её номер и маршрут кладём в ТЕКСТ события: админ видит, о чём речь, и находит доставку
    по номеру. Заводить колонку ради ссылки в админке — отдельное решение и миграция;
    человеческая часть (сигнал ушёл, отправитель предупреждён) работает и так."""
    link = _maps_link(body.lat, body.lng)
    where = f" Место: {link}" if link else ""
    parcel_line = ""
    if parcel_id is not None:
        from ..models import ParcelDelivery
        parcel = session.get(ParcelDelivery, parcel_id)
        if parcel is not None:
            parcel_line = (f" Доставка #{parcel.id}: "
                           f"{parcel.from_city or '?'} → {parcel.to_city or '?'}.")
    # Событие в SOS-ленту админа фиксируем СИНХРОННО (не теряем сигнал о помощи).
    note = (f"Застрял на трассе (зимний протокол). {body.note}".strip()
            + parcel_line + where).strip()
    event = SosEvent(user_id=user.id, booking_id=booking_id, order_id=order_id,
                     category="breakdown", note=note)
    session.add(event)
    session.commit()
    session.refresh(event)
    # Телефоны доверенных собираем ПОКА сессия открыта; SMS/Telegram — в фон (не держим коннект,
    # не заставляем человека на морозе ждать sms.ru). Координаты в stdout НЕ пишем (152-ФЗ).
    # Кеп SMS — тот же, что у /sos (событие пишем всегда, глушим только рассылку): без него
    # мэш-кнопка «застрял» = безлимитный поток SMS доверенным за счёт платформы.
    recent = session.exec(
        select(SosEvent.id).where(
            SosEvent.user_id == user.id, SosEvent.created_at >= utcnow() - timedelta(hours=1),
        )
    ).all()
    phones = []
    if len(recent) <= SOS_SMS_PER_HOUR:   # <=: только что записанное событие уже в счёте
        contacts = session.exec(select(TrustedContact).where(TrustedContact.user_id == user.id)).all()
        phones = [c.phone for c in contacts if c.phone]
        who = user.name or user.phone
        msg = _sos_text(
            session, user.id,
            f"Юлдаш: {who} застрял на трассе, нужна помощь.{where}",
            f"Юлдаш: {who} юлда ҡалған, ярҙам кәрәк.{where}",
        ).strip()
        background.add_task(_send_sos_sms, phones, msg)
    else:
        log.info(f"[ROADSIDE] user={user.id} SMS подавлены (кеп {SOS_SMS_PER_HOUR}/час), событие записано")
    background.add_task(
        notify_admin_telegram,
        f"🛟 Помощь на трассе (Юлдаш)\n"
        f"От: {user.name or '—'}\n"
        f"Тел: {user.phone or '—'}\n"
        f"Контактов уведомлено: {len(phones)}\n"
        f"Детали: {body.note or '—'}{where}"
    )
    log.info(f"[ROADSIDE] user={user.id} booking={booking_id} order={order_id} "
             f"contacts_notified={len(phones)}")
    # Сколько человек реально предупреждено — это должен знать тот, кто нажал (волна 122).
    # Экран писал «близкие и поддержка получили твои координаты» ВСЕГДА, даже когда доверенных
    # контактов человек не заводил и SMS не ушло никому. Курьер на трассе в минус двадцать
    # читал это и переставал звонить сам.
    payload = event.model_dump()
    payload["contacts_notified"] = len(phones)
    return payload



# ============================ ❄️ Зимний протокол ============================
# Что это для человека. Зимой трасса Сибай–Уфа — четыре часа. Если он не отметил, что доехал,
# приложение спрашивает «всё в порядке?». Не ответил полчаса — зовём тех, кого он сам выбрал:
# им уходит SMS «позвони, проверь». Это не слежка и не тревога по каждому поводу, а один
# вопрос и один звонок близкого, если ответа нет.
#
# Почему одна реализация на три сценария. Механика жила только у попутки, хотя дорога у всех
# одна: пассажир такси едет те же четыре часа, курьер — тоже и вдобавок один (аудит 2026-08-06).
# Три копии разошлись бы при первой же правке — как уже разошлись чат, кнопка «застрял»
# и показ суммы отмены. Поэтому логика здесь одна, а сценарии дают ей три вещи:
# кого спрашивать, когда спрашивать рано и кому звонить, если ответа нет.

WINTER_TITLE = "Юлдаш"
WINTER_ASK_RU = "Всё в порядке? Отметь, что доехал(а)."
WINTER_ASK_BA = "Бөтәһе лә яҡшымы? Барып еткәнеңде билдәлә."


def _winter_contacts_for(session: Session, user_id: int) -> list:
    """Телефоны доверенных контактов человека — кому звонить, если он молчит."""
    return [c.phone for c in session.exec(
        select(TrustedContact).where(TrustedContact.user_id == user_id)
    ).all() if c.phone]


def _winter_run(
    session: Session,
    background: BackgroundTasks,
    *,
    obj,                      # Booking / InstantOrder / ParcelDelivery — у всех три поля ниже
    kind: str,                # booking | order | parcel — для текстов и события
    obj_id: int,
    closed: bool,             # поездка/доставка уже закрыта — спрашивать нечего
    too_early: bool,          # ещё не выехали — спрашивать рано
    ask_user_ids: list,       # кого спрашиваем «всё в порядке?»
    watch_user_id: int,       # чьи близкие получат звонок, если ответа нет
    contact_phones: list,     # уже собранные телефоны (пусто → эскалировать некому)
    also_notify_user_id=None,  # кого ещё предупредить в приложении (напр. отправителя посылки)
) -> dict:
    """Один шаг протокола. Идемпотентен: клиент зовёт его повторно, пока не получит ответ.

    Состояния: closed / too_early / ok (уже ответили) / check_sent (спросили) /
    waiting (ждём) / no_share (звать некого) / escalated (позвали близких)."""
    now = utcnow()
    if closed:
        return {"state": "closed"}
    if obj.winter_check_ack_at is not None:
        return {"state": "ok"}
    if obj.winter_check_sent_at is None:
        if too_early:
            return {"state": "too_early"}
        obj.winter_check_sent_at = now
        session.add(obj)
        session.commit()
        for uid in ask_user_ids:
            if uid:
                send_push(session, uid, WINTER_TITLE, WINTER_ASK_RU)
        return {"state": "check_sent"}

    waited_min = (now - obj.winter_check_sent_at).total_seconds() / 60.0
    if waited_min < WINTER_ESCALATE_AFTER_MIN:
        return {"state": "waiting", "waited_min": round(waited_min, 1)}
    if not contact_phones:
        # Звать некого: человек не добавил доверенных (или не расшарил поездку).
        # Молча выходим — придумывать за него, кому звонить, мы не вправе.
        return {"state": "no_share"}

    # Сам зов близких живёт в `winter_escalate` — ОДНОЙ точкой на всех (волна 114). Оттуда же
    # его зовёт ночной робот: раньше этот шаг делала только ручка из приложения, и человек,
    # у которого сел телефон, не получал помощи вовсе — а тому, кто цел и снова открыл
    # приложение, улетала тревога близким.
    from ..winter_escalate import escalate_now
    return escalate_now(
        session, kind=kind, obj_id=obj_id, watch_user_id=watch_user_id,
        contact_phones=contact_phones, also_notify_user_id=also_notify_user_id,
        background=background,
    )


def _winter_ack(session: Session, obj) -> dict:
    """«Я доехал» — гасит эскалацию. Идемпотентно: повторный тап ничего не портит."""
    if obj.winter_check_ack_at is None:
        obj.winter_check_ack_at = utcnow()
        session.add(obj)
        session.commit()
    return {"ok": True}


@router.post("/bookings/{booking_id}/winter-check")
def winter_check(
    booking_id: int,
    background: BackgroundTasks,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """Зимний протокол по ПОПУТКЕ. Спрашиваем обе стороны; звоним близким пассажира,
    которым он сам расшарил поездку (шаринг — и есть сигнал «меня ждут»)."""
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    shares = session.exec(select(TripShare).where(TripShare.booking_id == booking_id)).all()
    contact_ids = [sh.contact_id for sh in shares if sh.contact_id]
    phones = [c.phone for c in session.exec(
        select(TrustedContact).where(TrustedContact.id.in_(contact_ids))
    ).all() if c.phone] if contact_ids else []
    return _winter_run(
        session, background,
        obj=booking, kind="booking", obj_id=booking_id,
        closed=booking.status in (BookingStatus.done, BookingStatus.cancelled),
        too_early=bool(ride.depart_at and utcnow() < ride.depart_at),
        ask_user_ids=[booking.passenger_id, ride.driver_id],
        watch_user_id=booking.passenger_id,
        contact_phones=phones,
    )


@router.post("/bookings/{booking_id}/winter-check/ok")
def winter_check_ack(
    booking_id: int,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """Участник ответил «всё в порядке» — гасит эскалацию близким."""
    booking, _ = booking_and_ride_for_user(session, booking_id, user)
    return _winter_ack(session, booking)


@router.post("/instant/orders/{order_id}/winter-check")
def winter_check_order(
    order_id: int,
    background: BackgroundTasks,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """Зимний протокол по ТАКСИ-ЗАКАЗУ.

    Дорога та же: Сибай–Уфа зимой — четыре часа, и пассажир такси в этой машине один
    с незнакомым водителем. Спрашиваем обе стороны, звоним близким пассажира —
    тем, кому он расшарил поездку."""
    order = _order_for_participant(session, order_id, user)
    status = order.status.value if hasattr(order.status, "value") else order.status
    shares = session.exec(select(TripShare).where(TripShare.order_id == order_id)).all()
    contact_ids = [sh.contact_id for sh in shares if sh.contact_id]
    phones = [c.phone for c in session.exec(
        select(TrustedContact).where(TrustedContact.id.in_(contact_ids))
    ).all() if c.phone] if contact_ids else []
    return _winter_run(
        session, background,
        obj=order, kind="order", obj_id=order_id,
        closed=status in ("done", "cancelled", "expired"),
        # Рано, пока человек не сел в машину: до посадки «доехал?» бессмысленно.
        too_early=order.onboard_at is None,
        ask_user_ids=[order.passenger_id, order.driver_id],
        watch_user_id=order.passenger_id,
        contact_phones=phones,
    )


@router.post("/instant/orders/{order_id}/winter-check/ok")
def winter_check_order_ack(
    order_id: int,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    order = _order_for_participant(session, order_id, user)
    return _winter_ack(session, order)


@router.post("/parcels/{parcel_id}/winter-check")
def winter_check_parcel(
    parcel_id: int,
    background: BackgroundTasks,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """Зимний протокол по ДОСТАВКЕ.

    Курьер едет по той же зимней трассе и, в отличие от поездки, едет ОДИН — рядом нет
    пассажира, который заметит, что что-то не так. Поэтому спрашиваем курьера, а звоним
    его собственным доверенным контактам: трекинг-ссылка по посылке принадлежит получателю,
    а не человеку, который ждёт курьера, — по ней звать некого.

    Отправителю сообщаем отдельно: его посылка не движется, и узнать это он должен от нас."""
    from ..models import ParcelDelivery
    parcel = session.get(ParcelDelivery, parcel_id)
    if not parcel:
        raise herr(404, "Посылка не найдена", "Бандероль табылманы")
    is_sender = user.id == parcel.sender_id
    is_courier = parcel.courier_id is not None and user.id == parcel.courier_id
    if not (is_sender or is_courier):
        raise herr(403, "Ты не участник этой доставки",
                   "Һин был доставканың ҡатнашыусыһы түгел")
    return _winter_run(
        session, background,
        obj=parcel, kind="parcel", obj_id=parcel_id,
        closed=parcel.status in ("delivered", "canceled", "returned"),
        # Рано, пока курьер не забрал посылку: он ещё никуда не выехал.
        too_early=parcel.courier_id is None or parcel.status == "created",
        ask_user_ids=[parcel.courier_id],
        watch_user_id=parcel.courier_id,
        contact_phones=_winter_contacts_for(session, parcel.courier_id) if parcel.courier_id else [],
        also_notify_user_id=parcel.sender_id,
    )


@router.post("/parcels/{parcel_id}/winter-check/ok")
def winter_check_parcel_ack(
    parcel_id: int,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    from ..models import ParcelDelivery
    parcel = session.get(ParcelDelivery, parcel_id)
    if not parcel:
        raise herr(404, "Посылка не найдена", "Бандероль табылманы")
    if parcel.courier_id is None or user.id != parcel.courier_id:
        raise herr(403, "Отметить может только курьер этой доставки",
                   "Тик был доставканың курьеры ғына билдәләй ала")
    return _winter_ack(session, parcel)
