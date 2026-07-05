"""Безопасность: SOS (с SMS доверенным контактам), жалобы, блокировки."""
from datetime import datetime, timedelta

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select
from typing import List, Literal, Optional

from ..db import get_session
from ..models import Block, Booking, BookingStatus, Report, Ride, SosEvent, TripShare, TrustedContact, User, UserRole
from ..security import current_user
from ..services import booking_and_ride_for_user, notify_admin_telegram, send_push, send_text
from ..timeutil import utcnow

router = APIRouter(tags=["safety"])

# F12 «Зимний протокол»: авто-проверка «доехал?».
# Если участник не подтвердил «всё в порядке» за это время после пуша — эскалация доверенным контактам.
WINTER_ESCALATE_AFTER_MIN = 30

# Анти-спам SMS: SOS-событие пишем ВСЕГДА (жизнь дороже), но рассылку доверенным контактам
# глушим, если за последний час их уже оповещали > N раз — иначе мэш-кнопка = поток SMS и расходы.
SOS_SMS_PER_HOUR = 6


def _send_sos_sms(phones: list, text: str) -> None:
    """Рассылка SOS-SMS доверенным контактам — в фоне (после ответа), чтобы не держать
    коннект БД и не заставлять паникующего ждать sms.ru по ~10с на контакт."""
    for ph in phones:
        if ph:
            send_text(ph, text)


class SosIn(BaseModel):
    category: Literal["medical", "breakdown", "other"] = "other"   # закрытый список (было: любая строка в БД/админу)
    booking_id: Optional[int] = None
    note: str = Field("", max_length=2000)


@router.post("/sos", response_model=SosEvent)
def sos(body: SosIn, background: BackgroundTasks, user: User = Depends(current_user), session: Session = Depends(get_session)):
    if body.booking_id is not None:
        booking_and_ride_for_user(session, body.booking_id, user)
    # Сколько SOS уже было за последний час (ДО записи нового) — для кепа SMS.
    recent = session.exec(
        select(SosEvent).where(
            SosEvent.user_id == user.id, SosEvent.created_at >= utcnow() - timedelta(hours=1)
        )
    ).all()
    event = SosEvent(user_id=user.id, **body.model_dump())
    session.add(event)
    session.commit()                 # событие фиксируем СИНХРОННО (жизнь дороже) — данные не теряются
    session.refresh(event)
    # Телефоны доверенных контактов собираем ПОКА сессия открыта, рассылку SMS — в фон (после ответа).
    notified = 0
    if len(recent) < SOS_SMS_PER_HOUR:
        contacts = session.exec(select(TrustedContact).where(TrustedContact.user_id == user.id)).all()
        who = user.name or user.phone
        phones = [c.phone for c in contacts if c.phone]
        notified = len(phones)
        background.add_task(_send_sos_sms, phones, f"SOS! {who} просит срочной помощи (Юлдаш). Свяжитесь скорее.")
    else:
        print(f"[SOS] user={user.id} SMS подавлены (кеп {SOS_SMS_PER_HOUR}/час), событие записано")
    # Уведомление админу в Telegram — тоже в фон (httpx-вызов не держит коннект БД и не тормозит ответ SOS).
    background.add_task(
        notify_admin_telegram,
        f"🆘 SOS (Юлдаш)\n"
        f"От: {user.name or '—'}\n"
        f"Тел: {user.phone or '—'}\n"
        f"Категория: {body.category}\n"
        f"Контактов уведомлено (SMS): {notified}\n"
        f"Детали: {body.note or '—'}"
    )
    print(f"[SOS] user={user.id} category={body.category} contacts_notified={notified}")
    return event


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


class ReportIn(BaseModel):
    target_user_id: int
    reason: str = Field("", max_length=1000)   # анти-раздувание таблицы: авторизованный не льёт мегабайты


class ReportOut(BaseModel):
    id: int
    reporter_name: str
    target_name: str
    target_phone: str
    reason: str
    created_at: datetime


@router.get("/admin/reports", response_model=List[ReportOut])
def admin_reports(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Все жалобы — для разбора админом (кто на кого, причина, когда)."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    reports = session.exec(select(Report).order_by(Report.id.desc()).limit(200)).all()
    if not reports:
        return []
    ids: set = set()
    for r in reports:
        ids.add(r.reporter_id)
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
        ))
    return out


@router.post("/reports", response_model=Report)
def create_report(body: ReportIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    if body.target_user_id == user.id:
        raise HTTPException(400, "Нельзя пожаловаться на себя")
    if not session.get(User, body.target_user_id):
        raise HTTPException(404, "Пользователь не найден")
    report = Report(reporter_id=user.id, **body.model_dump())
    session.add(report)
    session.commit()
    session.refresh(report)
    return report


class BlockIn(BaseModel):
    blocked_user_id: int


@router.post("/blocks", response_model=Block)
def create_block(body: BlockIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    if body.blocked_user_id == user.id:
        raise HTTPException(400, "Нельзя заблокировать себя")
    if not session.get(User, body.blocked_user_id):
        raise HTTPException(404, "Пользователь не найден")
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
    return [
        BlockOut(
            blocked_user_id=b.blocked_user_id,
            name=(users[b.blocked_user_id].name if users.get(b.blocked_user_id) and users[b.blocked_user_id].name else "Пользователь"),
        )
        for b in blocks
    ]


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
    lat: Optional[float] = None
    lng: Optional[float] = None
    note: str = Field("", max_length=500)


@router.post("/bookings/{booking_id}/stuck", response_model=SosEvent)
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
    link = _maps_link(body.lat, body.lng)
    where = f" Место: {link}" if link else ""
    # Событие в SOS-ленту админа фиксируем СИНХРОННО (не теряем сигнал о помощи).
    note = (f"Застрял на трассе (зимний протокол). {body.note}".strip() + where).strip()
    event = SosEvent(user_id=user.id, booking_id=booking_id, category="breakdown", note=note)
    session.add(event)
    session.commit()
    session.refresh(event)
    # Телефоны доверенных собираем ПОКА сессия открыта; SMS/Telegram — в фон (не держим коннект,
    # не заставляем человека на морозе ждать sms.ru). Координаты в stdout НЕ пишем (152-ФЗ).
    contacts = session.exec(select(TrustedContact).where(TrustedContact.user_id == user.id)).all()
    phones = [c.phone for c in contacts if c.phone]
    who = user.name or user.phone
    msg = f"Юлдаш: {who} застрял на трассе, нужна помощь.{where}".strip()
    background.add_task(_send_sos_sms, phones, msg)
    background.add_task(
        notify_admin_telegram,
        f"🛟 Помощь на трассе (Юлдаш)\n"
        f"От: {user.name or '—'}\n"
        f"Тел: {user.phone or '—'}\n"
        f"Контактов уведомлено: {len(phones)}\n"
        f"Детали: {body.note or '—'}{where}"
    )
    print(f"[ROADSIDE] user={user.id} booking={booking_id} contacts_notified={len(phones)}")
    return event


@router.post("/bookings/{booking_id}/winter-check")
def winter_check(
    booking_id: int,
    background: BackgroundTasks,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """Авто-проверка «доехал?» (прагматично для v1: вызывается клиентом/по флагу, когда его ETA+буфер истёк).
    Идемпотентна и вызывается повторно:
      1) поездка ещё не закрыта и пуш ещё не слали → шлём обеим сторонам «всё в порядке?», помечаем sent_at;
      2) пуш уже был, ответа нет ≥30 мин, есть активный шаринг → уведомляем доверенный контакт + SOS-событие;
      3) поездка done/cancelled или участник уже нажал «всё хорошо» → ничего не делаем.
    Доступно только участнику поездки."""
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    now = utcnow()
    # (3a) Поездка закрыта — проверять нечего.
    if booking.status in (BookingStatus.done, BookingStatus.cancelled):
        return {"state": "closed"}
    # (3b) Уже подтвердили «всё в порядке» — эскалацию не запускаем.
    if booking.winter_check_ack_at is not None:
        return {"state": "ok"}
    # (1) Первый заход: шлём пуш «всё в порядке?» обеим сторонам.
    if booking.winter_check_sent_at is None:
        # Санити-гейт: поездка должна была реально начаться (клиент считает ETA сам, сервер страхует по depart_at).
        if ride.depart_at and now < ride.depart_at:
            return {"state": "too_early"}
        booking.winter_check_sent_at = now
        session.add(booking)
        session.commit()
        title = "Юлдаш"
        text = "Всё в порядке? Отметь, что доехал(а)."
        send_push(session, booking.passenger_id, title, text)
        send_push(session, ride.driver_id, title, text)
        return {"state": "check_sent"}
    # (2) Пуш уже был — ждём ответа. Эскалация только после порога и только при активном шаринге.
    waited_min = (now - booking.winter_check_sent_at).total_seconds() / 60.0
    if waited_min < WINTER_ESCALATE_AFTER_MIN:
        return {"state": "waiting", "waited_min": round(waited_min, 1)}
    # Активный шаринг поездки близкому (пассажир расшарил) — иначе некому эскалировать.
    shares = session.exec(select(TripShare).where(TripShare.booking_id == booking_id)).all()
    if not shares:
        return {"state": "no_share"}
    # Эскалация: SOS-событие от имени пассажира (его контакты) + SMS доверенным.
    escalate = SosEvent(
        user_id=booking.passenger_id, booking_id=booking_id, category="other",
        note=f"Зимний протокол: нет ответа {WINTER_ESCALATE_AFTER_MIN} мин после проверки «доехал?»",
    )
    session.add(escalate)
    session.commit()
    session.refresh(escalate)
    contact_ids = [s.contact_id for s in shares]
    phones = [
        c.phone
        for c in session.exec(select(TrustedContact).where(TrustedContact.id.in_(contact_ids))).all()
        if c.phone
    ]
    pax = session.get(User, booking.passenger_id)
    who = (pax.name or pax.phone) if pax else "попутчик"
    background.add_task(_send_sos_sms, phones, f"Юлдаш: {who} не отметил(а), что доехал(а). Позвони, проверь, всё ли хорошо.")
    background.add_task(
        notify_admin_telegram,
        f"❄️ Зимний протокол: нет ответа (Юлдаш)\nПоездка #{booking_id}\nКонтактов уведомлено: {len(phones)}"
    )
    return {"state": "escalated", "sos_event_id": escalate.id, "contacts_notified": len(phones)}


@router.post("/bookings/{booking_id}/winter-check/ok")
def winter_check_ack(
    booking_id: int,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """Участник ответил «всё в порядке» на проверку «доехал?» — гасит эскалацию доверенным."""
    booking, _ = booking_and_ride_for_user(session, booking_id, user)
    if booking.winter_check_ack_at is None:
        booking.winter_check_ack_at = utcnow()
        session.add(booking)
        session.commit()
    return {"ok": True}
