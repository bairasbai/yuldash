"""Чат по броне: REST (история/отправка), WebSocket (реальное время),
инбокс диалогов, лента уведомлений."""
import json
from datetime import datetime
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field
from sqlmodel import Session, select
from starlette.concurrency import run_in_threadpool

from ..db import engine, get_session
from ..models import Booking, BookingStatus, Message, Ride, User
from ..security import authenticate_ws, current_user
from ..services import booking_and_ride_for_user, is_blocked, manager, public_media_url, send_push_bi, user_bookings

router = APIRouter(tags=["chat"])


class MessageIn(BaseModel):
    text: str = Field("", max_length=4000)
    voice_url: Optional[str] = None
    transcript: Optional[str] = Field(None, max_length=4000)


@router.websocket("/ws/bookings/{booking_id}")
async def websocket_endpoint(websocket: WebSocket, booking_id: int):
    """WebSocket чат брони. Токен — ТОЛЬКО первым сообщением {"type":"auth","token":...}.
    Через query-string не принимаем: query-string утекает в логи nginx/прокси, а токен =
    доступ к чату. Доступ — ТОЛЬКО участнику брони (пассажир или водитель)."""
    await websocket.accept()
    token = None
    try:
        first = json.loads(await websocket.receive_text())
        if first.get("type") == "auth":
            token = first.get("token")
    except Exception:
        token = None
    # Аутентификация: декод JWT + проверка существования юзера и ревокации (logout).
    # authenticate_ws (в отличие от простого декода) honors `tokens_valid_from`, иначе
    # отозванный logout'ом токен открывал бы чат до истечения JWT.
    # Авторизация на ресурс (закрывает IDOR): юзер — участник ИМЕННО этой брони.
    # passenger_id/driver_id фиксируем как int → используем после закрытия сессии.
    with Session(engine) as s:
        try:
            user_id = authenticate_ws(token or "", s).id
        except Exception:
            await websocket.close(code=1008, reason="Invalid token")
            return
        booking = s.get(Booking, booking_id)
        ride = s.get(Ride, booking.ride_id) if booking else None
        if not booking or not ride or (booking.passenger_id != user_id and ride.driver_id != user_id):
            await websocket.close(code=1008, reason="Forbidden")
            return
        passenger_id, driver_id = booking.passenger_id, ride.driver_id

    other_id = driver_id if user_id == passenger_id else passenger_id
    manager.register(booking_id, websocket)
    try:
        while True:
            data = await websocket.receive_text()
            try:
                payload = json.loads(data)
            except (json.JSONDecodeError, ValueError):
                continue   # битый (не-JSON) кадр — игнорируем, соединение НЕ роняем
            if payload.get("type") == "message":
                # `with` → коннект возвращается в пул сразу (без утечки сессий на каждое сообщение).
                with Session(engine) as session:
                    # Блокировка (как в REST send_message): заблокированный не пишет — тихо игнор.
                    if is_blocked(session, user_id, other_id):
                        continue
                    msg = Message(booking_id=booking_id, sender_id=user_id, text=(payload.get("text") or "")[:4000])
                    session.add(msg)
                    session.commit()
                    session.refresh(msg)
                    await manager.broadcast(booking_id, {
                        "type": "message",
                        "id": msg.id,
                        "sender_id": msg.sender_id,
                        "text": msg.text,
                        "timestamp": msg.created_at.isoformat()
                    })
                    # Push другой стороне (она может быть офлайн / не в чате). send_push_bi — блокирующий
                    # сетевой вызов к FCM; в async-WS гоним через threadpool, иначе залипший запрос к
                    # Google морозит event-loop и ВСЕ WS-соединения воркера.
                    sender = session.get(User, user_id)
                    sender_name = (sender.name if sender else None)
                    await run_in_threadpool(
                        send_push_bi, session, other_id,
                        sender_name or "Новое сообщение", sender_name or "Яңы хәбәр",
                        (msg.text or "Сообщение")[:120], (msg.text or "Тауыш хәбәре")[:120],
                    )
    except WebSocketDisconnect:
        pass
    finally:
        manager.disconnect(booking_id, websocket)   # снятие регистрации при ЛЮБОМ выходе — нет утечки сокета


@router.post("/bookings/{booking_id}/messages", response_model=Message)
def send_message(booking_id: int, body: MessageIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    other_party = ride.driver_id if user.id == booking.passenger_id else booking.passenger_id
    if is_blocked(session, user.id, other_party):
        raise HTTPException(403, "Переписка недоступна")
    # voice_url — ТОЛЬКО наш медиа-URL (из /upload-voice). Иначе участник подсунул бы внешнюю ссылку,
    # и приложение собеседника её подгрузило бы (утечка IP / трекинг / чужой контент).
    if body.voice_url and not body.voice_url.startswith(public_media_url("")):
        raise HTTPException(422, "Недопустимая ссылка на медиа")
    msg = Message(booking_id=booking_id, sender_id=user.id, **body.model_dump())
    session.add(msg)
    session.commit()
    session.refresh(msg)
    # Push другой стороне брони (кто не отправитель).
    other_id = ride.driver_id if user.id == booking.passenger_id else booking.passenger_id
    send_push_bi(
        session, other_id,
        user.name or "Новое сообщение", user.name or "Яңы хәбәр",
        (msg.text or "Голосовое сообщение")[:120], (msg.text or "Тауыш хәбәре")[:120],
    )
    return msg


@router.get("/bookings/{booking_id}/messages", response_model=List[Message])
def list_messages(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    booking_and_ride_for_user(session, booking_id, user)
    rows = session.exec(select(Message).where(Message.booking_id == booking_id).order_by(Message.id)).all()
    # Скрытые «у себя» этим юзером не показываем (на сервере остаются — для спора/SOS).
    return [m for m in rows if user.id not in _hidden_ids(m)]


def _hidden_ids(m: Message) -> set:
    return {int(x) for x in (m.hidden_user_ids or "").split(",") if x.strip().isdigit()}


def _msg_in_booking(session: Session, booking_id: int, message_id: int) -> Message:
    msg = session.get(Message, message_id)
    if not msg or msg.booking_id != booking_id:
        raise HTTPException(404, "Сообщение не найдено")
    return msg


class MessageEditIn(BaseModel):
    text: str = Field(..., max_length=4000)


@router.post("/bookings/{booking_id}/messages/{message_id}/edit", response_model=Message)
def edit_message(booking_id: int, message_id: int, body: MessageEditIn,
                 user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Редактировать СВОЁ текстовое сообщение → пометка edited."""
    booking_and_ride_for_user(session, booking_id, user)
    msg = _msg_in_booking(session, booking_id, message_id)
    if msg.sender_id != user.id:
        raise HTTPException(403, "Редактировать можно только своё сообщение")
    if msg.deleted:
        raise HTTPException(400, "Сообщение удалено")
    if msg.voice_url:
        raise HTTPException(400, "Голосовое нельзя редактировать")
    text = body.text.strip()
    if not text:
        raise HTTPException(400, "Пустое сообщение")
    msg.text = text
    msg.edited = True
    session.add(msg)
    session.commit()
    session.refresh(msg)
    return msg


@router.delete("/bookings/{booking_id}/messages/{message_id}", response_model=Message)
def delete_message(booking_id: int, message_id: int, scope: str = "all",
                   user: User = Depends(current_user), session: Session = Depends(get_session)):
    """scope=all — удалить у всех (только своё; текст очищается, видна пометка).
    scope=me — скрыть только у себя (на сервере остаётся)."""
    booking_and_ride_for_user(session, booking_id, user)
    msg = _msg_in_booking(session, booking_id, message_id)
    if scope == "me":
        ids = _hidden_ids(msg)
        ids.add(user.id)
        msg.hidden_user_ids = ",".join(str(i) for i in sorted(ids))
    else:  # all
        if msg.sender_id != user.id:
            raise HTTPException(403, "Удалить у всех можно только своё сообщение")
        msg.deleted = True
        msg.text = ""
        msg.voice_url = None
        msg.transcript = None
    session.add(msg)
    session.commit()
    session.refresh(msg)
    return msg


class ConversationOut(BaseModel):
    booking_id: int
    peer_name: str
    peer_avatar: str = ""
    route: str
    last_message: str
    depart_at: Optional[datetime] = None   # время выезда — различать треды одного маршрута в инбоксе


def _message_preview(m: Message) -> str:
    if m.deleted:
        return "Сообщение удалено"
    if m.text:
        return m.text
    if m.voice_url:
        return "Голосовое"
    return "Сообщение"


@router.get("/conversations", response_model=List[ConversationOut])
def conversations(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Инбокс: активные брони пользователя и брони с историей сообщений."""
    bookings = user_bookings(session, user)
    if not bookings:
        return []
    booking_ids = [b.id for b in bookings]
    # Последнее сообщение каждой брони — ОДНИМ запросом (анти-N+1): все сообщения по этим броням
    # по убыванию id, первое встреченное на booking_id = последнее.
    last_by_booking: dict = {}
    for m in session.exec(
        select(Message).where(Message.booking_id.in_(booking_ids)).order_by(Message.id.desc())
    ).all():
        if user.id in _hidden_ids(m):
            continue
        last_by_booking.setdefault(m.booking_id, m)
    # Поездки и собеседники — пачкой по id (вместо session.get в цикле).
    rides_by_id = {r.id: r for r in session.exec(select(Ride).where(Ride.id.in_({b.ride_id for b in bookings}))).all()}
    peer_ids = {
        (rides_by_id[b.ride_id].driver_id if (b.ride_id in rides_by_id and b.passenger_id == user.id) else b.passenger_id)
        for b in bookings
    }
    peers_by_id = {u.id: u for u in session.exec(select(User).where(User.id.in_(peer_ids))).all()} if peer_ids else {}
    out: list = []
    for b in bookings:
        last = last_by_booking.get(b.id)
        active_without_messages = b.status in (BookingStatus.pending, BookingStatus.confirmed, BookingStatus.onboard)
        if last is None and not active_without_messages:
            continue
        ride = rides_by_id.get(b.ride_id)
        peer_id = ride.driver_id if (ride and b.passenger_id == user.id) else b.passenger_id
        peer = peers_by_id.get(peer_id)
        out.append(ConversationOut(
            booking_id=b.id,
            peer_name=(peer.name if peer and peer.name else "Собеседник"),
            peer_avatar=(peer.avatar_url if peer else ""),
            route=(f"{ride.from_city} → {ride.to_city}" if ride else ""),
            last_message=("Чат открыт" if last is None else _message_preview(last)),
            depart_at=(ride.depart_at if ride else None),
        ))
    return out


@router.get("/notifications")
def notifications(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Лента событий: входящие сообщения по броням пользователя (как пассажир и водитель)."""
    booking_ids = [b.id for b in user_bookings(session, user)]
    out: list = []
    if booking_ids:
        msgs = session.exec(
            select(Message).where(Message.booking_id.in_(booking_ids), Message.sender_id != user.id)
            .order_by(Message.id.desc()).limit(15)   # тянем из БД только последние 15, не всю переписку
        ).all()
        for m in msgs:
            out.append({"type": "message", "title": "Новое сообщение", "text": (m.text if m.text else "Голосовое сообщение")})
    return out
