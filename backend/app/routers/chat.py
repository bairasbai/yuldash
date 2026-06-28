"""Чат по броне: REST (история/отправка), WebSocket (реальное время),
инбокс диалогов, лента уведомлений."""
import json
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..db import engine, get_session
from ..models import Booking, Message, Ride, User
from ..security import current_user, verify_token
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
    try:
        user_id = verify_token(token or "")
    except Exception:
        await websocket.close(code=1008, reason="Invalid token")
        return
    # Авторизация на ресурс (закрывает IDOR): юзер должен быть участником ИМЕННО этой брони.
    with Session(engine) as s:
        booking = s.get(Booking, booking_id)
        ride = s.get(Ride, booking.ride_id) if booking else None
        if not booking or not ride or (booking.passenger_id != user_id and ride.driver_id != user_id):
            await websocket.close(code=1008, reason="Forbidden")
            return

    await manager.register(booking_id, websocket)
    try:
        while True:
            data = await websocket.receive_text()
            payload = json.loads(data)
            if payload.get("type") == "message":
                session = next(get_session())
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
                other_id = ride.driver_id if user_id == booking.passenger_id else booking.passenger_id
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
    return session.exec(select(Message).where(Message.booking_id == booking_id).order_by(Message.id)).all()


class ConversationOut(BaseModel):
    booking_id: int
    peer_name: str
    route: str
    last_message: str


@router.get("/conversations", response_model=List[ConversationOut])
def conversations(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Инбокс: брони пользователя (как пассажир и как водитель), где есть сообщения.
    Батч-загрузка (3 запроса вместо 3N): сообщения, поездки и собеседники — пачкой по id."""
    bookings = user_bookings(session, user)
    booking_ids = [b.id for b in bookings]
    if not booking_ids:
        return []
    # Последнее сообщение каждой брони: одним запросом по убыванию id, берём первое на booking_id.
    msgs = session.exec(
        select(Message).where(Message.booking_id.in_(booking_ids)).order_by(Message.id.desc())
    ).all()
    last_by_booking: dict = {}
    for m in msgs:
        last_by_booking.setdefault(m.booking_id, m)
    ride_ids = {b.ride_id for b in bookings}
    rides_by_id = {r.id: r for r in session.exec(select(Ride).where(Ride.id.in_(ride_ids))).all()}
    # Собеседники: для пассажира — водитель, для водителя — пассажир.
    peer_ids: set = set()
    for b in bookings:
        ride = rides_by_id.get(b.ride_id)
        peer_ids.add(ride.driver_id if (ride and b.passenger_id == user.id) else b.passenger_id)
    users_by_id = {u.id: u for u in session.exec(select(User).where(User.id.in_(peer_ids))).all()}
    out: list = []
    for b in bookings:
        last = last_by_booking.get(b.id)
        if last is None:
            continue
        ride = rides_by_id.get(b.ride_id)
        peer = users_by_id.get(ride.driver_id) if (ride and b.passenger_id == user.id) else users_by_id.get(b.passenger_id)
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
