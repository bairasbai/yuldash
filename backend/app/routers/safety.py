"""Безопасность: SOS (с SMS доверенным контактам), жалобы (§9 Качество), блокировки."""
from datetime import datetime, timedelta

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select
from typing import List, Literal, Optional

from ..db import get_session
from ..models import Block, Booking, InstantOrder, Report, Ride, SosEvent, TrustedContact, User, UserRole
from ..security import current_user
from ..services import booking_and_ride_for_user, notify_admin_telegram, send_text
from ..timeutil import utcnow
from .. import quality

router = APIRouter(tags=["safety"])

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
    order_id: Optional[int] = None      # контекст такси-заказа (B7b-2): админ видит, из какой поездки SOS
    note: str = Field("", max_length=2000)


def _order_for_participant(session: Session, order_id: int, user: User) -> InstantOrder:
    """Такси-заказ, если пользователь — его участник (пассажир или назначенный водитель)."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise HTTPException(404, "Заказ не найден")
    if user.id != order.passenger_id and (order.driver_id is None or user.id != order.driver_id):
        raise HTTPException(403, "Ты не участник этого заказа")
    return order


@router.post("/sos", response_model=SosEvent)
def sos(body: SosIn, background: BackgroundTasks, user: User = Depends(current_user), session: Session = Depends(get_session)):
    if body.booking_id is not None:
        booking_and_ride_for_user(session, body.booking_id, user)
    order = _order_for_participant(session, body.order_id, user) if body.order_id is not None else None
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
    # Контекст такси-заказа (B7b-2): админу — маршрут и вторая сторона, чтобы среагировать по делу.
    order_line = ""
    if order is not None:
        order_line = (f"Такси-заказ: #{order.id} {order.from_text or '?'} → {order.to_text or '?'}"
                      f" (статус {order.status.value})\n")
    background.add_task(
        notify_admin_telegram,
        f"🆘 SOS (Юлдаш)\n"
        f"От: {user.name or '—'}\n"
        f"Тел: {user.phone or '—'}\n"
        f"Категория: {body.category}\n"
        f"{order_line}"
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


ReportCategory = Literal[
    "rude", "kicked_out", "dangerous_driving", "price_fraud", "dirty_car",
    "late", "safety_threat", "no_show", "damage", "unpaid", "other",
]


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
    target_user_id: int = 0


def _report_counterparty(session: Session, user: User, body: ReportIn) -> int:
    """Вторая сторона поездки/заказа. Проверяем: reporter — участник, цель — второй участник.
    Без привязки — прежнее поведение (target_user_id обязателен)."""
    if body.order_id is not None:
        order = session.get(InstantOrder, body.order_id)
        if not order:
            raise HTTPException(404, "Заказ не найден")
        if user.id == order.passenger_id:
            other = order.driver_id
        elif order.driver_id is not None and user.id == order.driver_id:
            other = order.passenger_id
        else:
            raise HTTPException(403, "Ты не участник этого заказа")
        if other is None:
            raise HTTPException(409, "У заказа нет второй стороны")
        return other
    if body.booking_id is not None:
        b = session.get(Booking, body.booking_id)
        ride = session.get(Ride, b.ride_id) if b else None
        if not b or not ride:
            raise HTTPException(404, "Бронь не найдена")
        if user.id == b.passenger_id:
            return ride.driver_id
        if user.id == ride.driver_id:
            return b.passenger_id
        raise HTTPException(403, "Ты не участник этой поездки")
    if body.target_user_id is None:
        raise HTTPException(400, "Укажи, на кого жалоба, или поездку")
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
            order_id=r.order_id, booking_id=r.booking_id, target_user_id=r.target_user_id,
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
            raise HTTPException(403, "«Не заплатил» отмечает водитель поездки")
        if order.status.value != "done":
            raise HTTPException(409, "Отметить можно только завершённую поездку")
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
            raise HTTPException(403, "«Не заплатил» отмечает водитель поездки")
        if (b.status.value if hasattr(b.status, "value") else b.status) != "done":
            raise HTTPException(409, "Отметить можно только завершённую поездку")
        dup = session.exec(select(Report).where(
            Report.booking_id == body.booking_id, Report.category == "unpaid",
        )).first()
        if dup:
            return dup
        b.unpaid_reported = True
        session.add(b)
    return None


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
        raise HTTPException(400, "Цель жалобы не совпадает со второй стороной поездки")
    if target_id == user.id:
        raise HTTPException(400, "Нельзя пожаловаться на себя")
    if not session.get(User, target_id):
        raise HTTPException(404, "Пользователь не найден")
    dup = _guard_unpaid_report(session, user, body)
    if dup is not None:   # дедуп: одна unpaid-жалоба на заказ — повторный тап идемпотентен
        return ReportCreatedOut(id=dup.id, category=dup.category,
                                status=dup.status, created_at=dup.created_at)
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
        raise HTTPException(404, "Жалоба не найдена")
    r.status = "resolved"
    r.resolution = body.resolution or r.resolution
    r.resolved_at = utcnow()
    session.add(r)
    session.commit()
    session.refresh(r)
    if r.category in quality.SEVERE_CATEGORIES:
        if body.keep_pause:
            # Оставить: «до разбора» → честная таймерная пауза (не вечная).
            quality.unpause_taxi(session, r.target_user_id)
            quality.pause_taxi(session, r.target_user_id,
                               hours=quality.settings.quality_pause_hours,
                               reason=quality.PAUSE_REASON_REPORTS)
        else:
            quality.maybe_release_review_pause(session, r.target_user_id)
    # 🔴 Лестница: накопленные resolved-жалобы за окно → авто-пауза (+пуш).
    quality.apply_ladder_after_resolve(session, r.target_user_id)
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
        raise HTTPException(404, "Жалоба не найдена")
    r.status = "rejected"
    if body is not None and body.resolution:
        r.resolution = body.resolution
    r.resolved_at = utcnow()
    session.add(r)
    session.commit()
    session.refresh(r)
    quality.maybe_release_review_pause(session, r.target_user_id)
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
        raise HTTPException(404, "Профиль водителя не найден")
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
        raise HTTPException(404, "Профиль водителя не найден")
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
