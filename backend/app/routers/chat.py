"""Чат по броне: REST (история/отправка), WebSocket (реальное время),
инбокс диалогов, лента уведомлений."""
import json
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..db import engine, get_session
from ..models import Booking, Message, Ride, User
from ..security import authenticate_ws, current_user
from ..services import booking_and_ride_for_user, is_blocked, manager, send_push, user_bookings

router = APIRouter(tags=["chat"])


class MessageIn(BaseModel):
    text: str = Field("", max_length=4000)
    voice_url: Optional[str] = None
    transcript: Optional[str] = Field(None, max_length=4000)


@router.websocket("/ws/bookings/{booking_id}")
async def websocket_endpoint(websocket: WebSocket, booking_id: int):
    """WebSocket чат брони. Токен — первым сообщением {"type":"auth","token":...}
    (в URL не передаём: query-string утекает в логи nginx/прокси). Для совместимости
    принимаем и ?token=. Доступ — ТОЛЬКО участнику брони (пассажир или водитель)."""
    await websocket.accept()
    token = websocket.query_params.get("token")
    if not token:
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
            payload = json.loads(data)
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
                    # Push другой стороне (она может быть офлайн / не в чате).
                    sender = session.get(User, user_id)
                    send_push(session, other_id, (sender.name if sender else None) or "Новое сообщение", (msg.text or "Сообщение")[:120])
    except WebSocketDisconnect:
        manager.disconnect(booking_id, websocket)


@router.post("/bookings/{booking_id}/messages", response_model=Message)
def send_message(booking_id: int, body: MessageIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    other_party = ride.driver_id if user.id == booking.passenger_id else booking.passenger_id
    if is_blocked(session, user.id, other_party):
        raise HTTPException(403, "Переписка недоступна")
    msg = Message(booking_id=booking_id, sender_id=user.id, **body.model_dump())
    session.add(msg)
    session.commit()
    session.refresh(msg)
    # Push другой стороне брони (кто не отправитель).
    other_id = ride.driver_id if user.id == booking.passenger_id else booking.passenger_id
    send_push(session, other_id, user.name or "Новое сообщение", (msg.text or "Голосовое сообщение")[:120])
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
    route: str
    last_message: str


@router.get("/conversations", response_model=List[ConversationOut])
def conversations(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Инбокс: брони пользователя (как пассажир и как водитель), где есть сообщения."""
    out: list = []
    for b in user_bookings(session, user):
        last = session.exec(
            select(Message).where(Message.booking_id == b.id).order_by(Message.id.desc())
        ).first()
        if last is None:
            continue
        ride = session.get(Ride, b.ride_id)
        peer = session.get(User, ride.driver_id) if (ride and b.passenger_id == user.id) else session.get(User, b.passenger_id)
        out.append(ConversationOut(
            booking_id=b.id,
            peer_name=(peer.name if peer and peer.name else "Собеседник"),
            route=(f"{ride.from_city} → {ride.to_city}" if ride else ""),
            last_message=(last.text if last.text else "Голосовое"),
        ))
    return out


@router.get("/notifications")
def notifications(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Лента событий: входящие сообщения по броням пользователя (как пассажир и водитель)."""
    booking_ids = [b.id for b in user_bookings(session, user)]
    out: list = []
    if booking_ids:
        msgs = session.exec(
            select(Message).where(Message.booking_id.in_(booking_ids), Message.sender_id != user.id).order_by(Message.id.desc())
        ).all()
        for m in msgs[:15]:
            out.append({"type": "message", "title": "Новое сообщение", "text": (m.text if m.text else "Голосовое сообщение")})
    return out
