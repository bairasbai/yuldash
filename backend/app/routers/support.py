"""Поддержка внутри приложения: тикеты + тред сообщений (замена ссылки в Telegram).

Пользователь заводит обращение и переписывается с поддержкой прямо в приложении.
Приватность (как в соседних роутерах): тикет и его тред видит ТОЛЬКО автор (по user_id);
чужой тикет отдаём как 404 (не раскрываем даже факт существования). Админ-ручки —
только роли admin (проверка как в safety.py: `if user.role != UserRole.admin: 403`).

Ответ поддержки уходит пользователю через единую точку `services.push_notification`
(строка в Центре уведомлений + FCM, best-effort — как в чате/бронях).
"""
from datetime import timedelta
from typing import List

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..db import get_session
from ..logs import admin_action
from ..errors import herr
from ..middleware import user_over_limit
from ..models import (
    SupportMessage, SupportSender, SupportTicket, SupportTicketStatus, User, UserRole,
)
from ..security import current_user
from ..services import notify_admin_telegram, push_notification
from ..timeutil import utcnow

router = APIRouter(tags=["support"])


# ------------------------------ схемы ------------------------------
class TicketMessageOut(BaseModel):
    id: int
    sender: str            # user | admin
    body: str
    created_at: str


class TicketListItem(BaseModel):
    id: int
    subject: str
    status: str            # open | closed
    last_message: str      # превью последнего сообщения
    last_sender: str       # user | admin — чей был последний ход
    unread: bool           # ждёт ответа пользователя (последним написала поддержка)
    created_at: str
    updated_at: str


class TicketsOut(BaseModel):
    unread: int            # сколько тикетов ждут пользователя (бейдж «Поддержка»)
    items: List[TicketListItem]


class TicketThreadOut(BaseModel):
    id: int
    subject: str
    status: str
    created_at: str
    updated_at: str
    messages: List[TicketMessageOut]


class CreateTicketIn(BaseModel):
    subject: str = Field("", max_length=200)
    body: str = Field(..., min_length=1, max_length=4000)


class MessageIn(BaseModel):
    body: str = Field(..., min_length=1, max_length=4000)


# ------------------------------ helpers ------------------------------
def _iso(dt) -> str:
    return dt.isoformat() if dt else ""


def _own_ticket(session: Session, ticket_id: int, user_id: int) -> SupportTicket:
    """Тикет ТОЛЬКО автора. Чужой/несуществующий → 404 (не раскрываем существование)."""
    t = session.get(SupportTicket, ticket_id)
    if not t or t.user_id != user_id:
        raise herr(404, "Обращение не найдено", "Мөрәжәғәт табылманы")
    return t


def _last_messages(session: Session, ticket_ids: List[int]) -> dict:
    """Последнее сообщение каждого тикета одним запросом (анти-N+1): все сообщения по этим
    тикетам по убыванию id, первое встреченное на ticket_id = последнее."""
    if not ticket_ids:
        return {}
    last: dict = {}
    for m in session.exec(
        select(SupportMessage).where(SupportMessage.ticket_id.in_(ticket_ids))
        .order_by(SupportMessage.id.desc())
    ).all():
        last.setdefault(m.ticket_id, m)
    return last


def _msg_out(m: SupportMessage) -> TicketMessageOut:
    sender = m.sender.value if hasattr(m.sender, "value") else m.sender
    return TicketMessageOut(id=m.id, sender=sender, body=m.body, created_at=_iso(m.created_at))


# Анти-спам админ-канала: не чаще одного сигнала на тикет за это окно (человек может писать
# несколько сообщений подряд — будить админа на каждое не нужно).
_ADMIN_PING_COOLDOWN_MIN = 10
_last_admin_ping: dict = {}


def _notify_admin_new_ticket(ticket: SupportTicket, user: User, text: str, new: bool = True) -> None:
    """Сигнал админу об обращении в поддержку.

    Раньше уведомлений НЕ БЫЛО ВООБЩЕ (аудит 2026-07-26): человек писал в поддержку из
    приложения, сообщение падало в базу и лежало там, пока админ сам не откроет список.
    Для такси и доставки поддержка — последняя инстанция при любой проблеме, и тишина в
    ответ читается как «им всё равно»."""
    now = utcnow()
    last = _last_admin_ping.get(ticket.id)
    if last is not None and (now - last) < timedelta(minutes=_ADMIN_PING_COOLDOWN_MIN):
        return
    _last_admin_ping[ticket.id] = now
    try:
        notify_admin_telegram(
            ("🆘 Новое обращение в поддержку" if new else "💬 Новое сообщение в обращении")
            + f"\nТикет: #{ticket.id}\n"
            f"Тема: {ticket.subject or '—'}\n"
            f"От: {user.name or '—'} ({user.phone or '—'})\n"
            f"Текст: {(text or '')[:300]}"
        )
    except Exception:  # noqa: BLE001 — уведомление вторично, обращение уже сохранено
        pass


# Сколько НОВЫХ обращений один человек может открыть за час. Каждое падает Александру
# в Telegram и разбирается руками, а времени у него 5–10 минут в день: обращение — самое
# дешёвое действие для человека и самое дорогое для нас. Пробой волны 28: 50 обращений
# подряд, ни одного отказа, 50 сообщений в Telegram.
#
# Пять — с запасом на живую жизнь: человек пишет про поездку, потом вспоминает про оплату,
# потом про водителя. Упереться можно только специально. Ответы ВНУТРИ тикета не ограничиваем:
# они админа не дёргают, а обрывать разговор на полуслове — хуже спама.
MAX_TICKETS_PER_HOUR = 5


# ------------------------------ пользователь ------------------------------
@router.post("/support/tickets", response_model=TicketThreadOut)
def create_ticket(body: CreateTicketIn, user: User = Depends(current_user),
                  session: Session = Depends(get_session)):
    """Создать обращение с первым сообщением. subject опционален (клиент может не спрашивать)."""
    if user_over_limit("support_ticket", user.id, MAX_TICKETS_PER_HOUR, window_sec=3600):
        # Не «ошибка», а честная просьба: уже открытые обращения никуда не делись, ответим там.
        raise herr(429, "Слишком много обращений подряд. Ответим на уже открытые — напиши в них.",
                   "Артыҡ күп мөрәжәғәт. Асыҡ мөрәжәғәттәргә яуап бирәбеҙ — шунда яҙ.")
    ticket = SupportTicket(user_id=user.id, subject=body.subject.strip()[:200],
                           status=SupportTicketStatus.open)
    session.add(ticket)
    session.commit()
    session.refresh(ticket)
    msg = SupportMessage(ticket_id=ticket.id, sender=SupportSender.user, body=body.body.strip())
    session.add(msg)
    session.commit()
    session.refresh(msg)
    _notify_admin_new_ticket(ticket, user, body.body.strip())
    return TicketThreadOut(
        id=ticket.id, subject=ticket.subject, status=ticket.status.value,
        created_at=_iso(ticket.created_at), updated_at=_iso(ticket.updated_at),
        messages=[_msg_out(msg)],
    )


@router.get("/support/tickets", response_model=TicketsOut)
def my_tickets(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мои обращения (свежие сверху) с превью последнего сообщения и статусом. `unread` —
    сколько тредов ждут моего ответа (последней написала поддержка). Только СВОИ тикеты."""
    rows = session.exec(
        select(SupportTicket).where(SupportTicket.user_id == user.id)
        .order_by(SupportTicket.updated_at.desc(), SupportTicket.id.desc())
    ).all()
    last = _last_messages(session, [t.id for t in rows])
    items, unread = [], 0
    for t in rows:
        m = last.get(t.id)
        last_sender = (m.sender.value if m and hasattr(m.sender, "value") else (m.sender if m else "user"))
        is_unread = last_sender == SupportSender.admin.value
        if is_unread:
            unread += 1
        items.append(TicketListItem(
            id=t.id, subject=t.subject, status=t.status.value,
            last_message=(m.body if m else ""), last_sender=last_sender, unread=is_unread,
            created_at=_iso(t.created_at), updated_at=_iso(t.updated_at),
        ))
    return TicketsOut(unread=unread, items=items)


@router.get("/support/tickets/{ticket_id}", response_model=TicketThreadOut)
def get_ticket(ticket_id: int, user: User = Depends(current_user),
               session: Session = Depends(get_session)):
    """Тред обращения (только СВОЙ → иначе 404). Сообщения в хронологическом порядке."""
    t = _own_ticket(session, ticket_id, user.id)
    msgs = session.exec(
        select(SupportMessage).where(SupportMessage.ticket_id == t.id)
        .order_by(SupportMessage.id.asc())
    ).all()
    return TicketThreadOut(
        id=t.id, subject=t.subject, status=t.status.value,
        created_at=_iso(t.created_at), updated_at=_iso(t.updated_at),
        messages=[_msg_out(m) for m in msgs],
    )


@router.post("/support/tickets/{ticket_id}/messages", response_model=TicketThreadOut)
def add_message(ticket_id: int, body: MessageIn, user: User = Depends(current_user),
                session: Session = Depends(get_session)):
    """Добавить сообщение в СВОЙ тикет. Тикет закрыт → сообщение его ПЕРЕОТКРЫВАЕТ
    (status→open): для пользователя это «продолжить разговор», а не ошибка 409 — теплее
    и меньше трения. Возвращаем обновлённый тред."""
    t = _own_ticket(session, ticket_id, user.id)
    now = utcnow()
    msg = SupportMessage(ticket_id=t.id, sender=SupportSender.user, body=body.body.strip())
    session.add(msg)
    if t.status == SupportTicketStatus.closed:
        t.status = SupportTicketStatus.open   # переоткрытие: пользователь снова пишет
    t.updated_at = now
    session.add(t)
    session.commit()
    # Сигнал админу — только на сообщения ПОЛЬЗОВАТЕЛЯ (ответ админа будить его самого не должен).
    _notify_admin_new_ticket(t, user, body.body.strip(), new=False)
    return get_ticket(ticket_id, user, session)


@router.post("/support/tickets/{ticket_id}/close", response_model=TicketThreadOut)
def close_ticket(ticket_id: int, user: User = Depends(current_user),
                 session: Session = Depends(get_session)):
    """Закрыть СВОЁ обращение («вопрос решён»). Идемпотентно."""
    t = _own_ticket(session, ticket_id, user.id)
    if t.status != SupportTicketStatus.closed:
        t.status = SupportTicketStatus.closed
        t.updated_at = utcnow()
        session.add(t)
        session.commit()
    return get_ticket(ticket_id, user, session)


# ------------------------------ админ ------------------------------
def _guard_admin(user: User) -> None:
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")


class AdminTicketOut(BaseModel):
    id: int
    user_id: int
    user_name: str
    subject: str
    status: str
    last_message: str
    last_sender: str
    message_count: int
    created_at: str
    updated_at: str


@router.get("/admin/support/tickets", response_model=List[AdminTicketOut])
def admin_tickets(status: str = "open", user: User = Depends(current_user),
                  session: Session = Depends(get_session)):
    """Тикеты для поддержки. ?status=open (по умолчанию — только открытые) | all (все).
    Открытые сверху, внутри — свежие по последней активности. Только для админа."""
    _guard_admin(user)
    q = select(SupportTicket)
    if status != "all":
        q = q.where(SupportTicket.status == SupportTicketStatus.open)
    # open выше closed (булево-desc, как read_at в notifications.py), затем по свежести.
    rows = session.exec(
        q.order_by(
            (SupportTicket.status == SupportTicketStatus.open).desc(),
            SupportTicket.updated_at.desc(),
        ).limit(200)
    ).all()
    if not rows:
        return []
    ticket_ids = [t.id for t in rows]
    last = _last_messages(session, ticket_ids)
    # Счётчик сообщений на тикет — одним проходом (тикетов ≤200, тред короткий).
    counts: dict = {}
    for tid in session.exec(
        select(SupportMessage.ticket_id).where(SupportMessage.ticket_id.in_(ticket_ids))
    ).all():
        counts[tid] = counts.get(tid, 0) + 1
    users = {u.id: u for u in session.exec(select(User).where(User.id.in_([t.user_id for t in rows]))).all()}
    out: List[AdminTicketOut] = []
    for t in rows:
        m = last.get(t.id)
        last_sender = (m.sender.value if m and hasattr(m.sender, "value") else (m.sender if m else "user"))
        u = users.get(t.user_id)
        out.append(AdminTicketOut(
            id=t.id, user_id=t.user_id, user_name=(u.name if u and u.name else "Пользователь"),
            subject=t.subject, status=t.status.value,
            last_message=(m.body if m else ""), last_sender=last_sender,
            message_count=counts.get(t.id, 0),
            created_at=_iso(t.created_at), updated_at=_iso(t.updated_at),
        ))
    return out


@router.get("/admin/support/tickets/{ticket_id}", response_model=TicketThreadOut)
def admin_get_ticket(ticket_id: int, user: User = Depends(current_user),
                     session: Session = Depends(get_session)):
    """Тред любого тикета для разбора поддержкой. Только для админа."""
    _guard_admin(user)
    t = session.get(SupportTicket, ticket_id)
    if not t:
        raise herr(404, "Обращение не найдено", "Мөрәжәғәт табылманы")
    msgs = session.exec(
        select(SupportMessage).where(SupportMessage.ticket_id == t.id)
        .order_by(SupportMessage.id.asc())
    ).all()
    return TicketThreadOut(
        id=t.id, subject=t.subject, status=t.status.value,
        created_at=_iso(t.created_at), updated_at=_iso(t.updated_at),
        messages=[_msg_out(m) for m in msgs],
    )


@router.post("/admin/support/tickets/{ticket_id}/reply", response_model=TicketThreadOut)
def admin_reply(ticket_id: int, body: MessageIn, user: User = Depends(current_user),
                session: Session = Depends(get_session)):
    """Ответ поддержки (sender=admin). Ответ переоткрывает закрытый тикет (диалог продолжается)
    и уходит пользователю пушем + в Центр уведомлений (best-effort). Только для админа."""
    _guard_admin(user)
    t = session.get(SupportTicket, ticket_id)
    if not t:
        raise herr(404, "Обращение не найдено", "Мөрәжәғәт табылманы")
    now = utcnow()
    msg = SupportMessage(ticket_id=t.id, sender=SupportSender.admin, body=body.body.strip())
    session.add(msg)
    if t.status == SupportTicketStatus.closed:
        t.status = SupportTicketStatus.open
    t.updated_at = now
    session.add(t)
    session.commit()
    admin_action(user.id, "support.reply", ticket_id=ticket_id, target_user=t.user_id)
    # Уведомление пользователю: одна точка (лента + FCM). Ошибку глотает сама push_notification.
    preview = (msg.body or "")[:120]
    push_notification(
        session, t.user_id, "system",
        "Поддержка Юлдаш ответила", "Юлдаш ярҙам хеҙмәте яуап бирҙе",
        preview, preview,
        ref_kind="support", ref_id=t.id,
    )
    return admin_get_ticket(ticket_id, user, session)


@router.post("/admin/support/tickets/{ticket_id}/close", response_model=TicketThreadOut)
def admin_close(ticket_id: int, user: User = Depends(current_user),
                session: Session = Depends(get_session)):
    """Закрыть тикет силами поддержки. Идемпотентно. Только для админа."""
    _guard_admin(user)
    t = session.get(SupportTicket, ticket_id)
    if not t:
        raise herr(404, "Обращение не найдено", "Мөрәжәғәт табылманы")
    if t.status != SupportTicketStatus.closed:
        t.status = SupportTicketStatus.closed
        t.updated_at = utcnow()
        session.add(t)
        session.commit()
        admin_action(user.id, "support.close", ticket_id=ticket_id, target_user=t.user_id)
    return admin_get_ticket(ticket_id, user, session)
