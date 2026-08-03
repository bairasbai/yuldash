"""Чат по броне: REST (история/отправка), WebSocket (реальное время),
инбокс диалогов, лента уведомлений. Плюс зеркальные чаты такси-заказа и посылки."""
import json
from datetime import datetime
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field
from sqlmodel import Session, select
from starlette.concurrency import run_in_threadpool

from ..antifraud import phishing_flag
from ..db import engine, get_session
from ..errors import herr
from ..models import (
    Booking, BookingStatus, InstantOrder, InstantOrderStatus, Message, ParcelDelivery, Ride,
    User, UserRole,
)
from ..security import authenticate_ws, current_user
from ..services import (
    booking_and_ride_for_user, is_blocked, manager, notify_chat_message,
    public_media_url, push_notification, send_push, user_bookings,
)
from ..timeutil import utcnow

router = APIRouter(tags=["chat"])

# ---- Чат такси-заказа (B7b-1) ----
# Namespace ключей ConnectionManager: booking-чат живёт на booking_id (>0), трек-каналы — на
# отрицательных, /ws/map — на 2_000_000_000. Чат заказа кладём на 1_500_000_000 + order_id:
# не пересекается ни с чем (booking_id до этих величин не дорастёт).
INSTANT_CHAT_KEY_BASE = 1_500_000_000
# Писать можно ТОЛЬКО в активном заказе (после accept и до done/отмены).
ORDER_CHAT_WRITABLE = (InstantOrderStatus.accepted, InstantOrderStatus.arriving, InstantOrderStatus.onboard)
# Читать историю можно и после завершения/отмены (read-only) — для споров/чека/забытых вещей.
ORDER_CHAT_READABLE = ORDER_CHAT_WRITABLE + (InstantOrderStatus.done, InstantOrderStatus.cancelled)


# ---- Чат посылки: отправитель ↔ назначенный курьер ----
# Зачем: половина вопросов доставки — одна фраза («оставь у соседей», «я на работе до шести»,
# «звони, домофон не работает»). До этого по посылке можно было только ПОЗВОНИТЬ: тяжело для
# одной фразы и не оставляет следа, если потом спор. Чат — зеркало такси-чата, ничего своего.
# Namespace ключей ConnectionManager (см. карту выше + location.py): booking-чат — booking_id (>0),
# трек брони — отрицательные, INSTANT_LOC_BASE = 1e9, чат заказа = 1.5e9, /ws/map = 2e9,
# трек посылки = 3e9. Чат посылки кладём на 4e9 — не пересекается ни с чем.
PARCEL_CHAT_KEY_BASE = 4_000_000_000
# Писать можно, пока посылка В РАБОТЕ: курьер её взял и ещё не закрыл (в т.ч. везёт обратно —
# именно тогда договориться нужнее всего).
PARCEL_CHAT_WRITABLE = ("accepted", "in_transit", "returning")
# Читать историю можно и после закрытия (delivered/returned/canceled) — по ней разбирают спор.
PARCEL_CHAT_READABLE = PARCEL_CHAT_WRITABLE + ("delivered", "returned", "canceled")


def _order_chat_key(order_id: int) -> int:
    return INSTANT_CHAT_KEY_BASE + order_id


def _parcel_chat_key(parcel_id: int) -> int:
    return PARCEL_CHAT_KEY_BASE + parcel_id


def _parcel_for_chat(session: Session, parcel_id: int, user_id: int, write: bool) -> ParcelDelivery:
    """Доступ к чату посылки: ТОЛЬКО участники (отправитель + НАЗНАЧЕННЫЙ курьер; пока курьера
    нет — чата нет). Окно статусов: писать — accepted/in_transit/returning, читать — плюс
    delivered/returned/canceled (read-only). 403 чужому, 409 вне окна. Зеркало _order_for_chat."""
    parcel = session.get(ParcelDelivery, parcel_id)
    if not parcel:
        raise herr(404, "Посылка не найдена", "Бандероль табылманы")
    is_sender = user_id == parcel.sender_id
    is_courier = parcel.courier_id is not None and user_id == parcel.courier_id
    if not (is_sender or is_courier):
        raise herr(403, "Нет доступа к переписке по этой доставке",
                   "Был доставка буйынса яҙышыуға рөхсәт юҡ")
    allowed = PARCEL_CHAT_WRITABLE if write else PARCEL_CHAT_READABLE
    if parcel.status not in allowed:
        # До accept курьера ещё нет — писать некому; после закрытия запись закрыта (история цела).
        if write and parcel.status in PARCEL_CHAT_READABLE:
            raise herr(409, "Доставка завершена — переписка только для чтения",
                       "Доставка тамамланған — яҙышыу тик уҡыу өсөн")
        raise herr(409, "Переписка откроется, когда курьер примет доставку",
                   "Курьер доставканы алғас, яҙышыу асыла")
    return parcel


def _order_for_chat(session: Session, order_id: int, user_id: int, write: bool) -> InstantOrder:
    """Доступ к чату заказа: ТОЛЬКО участники (пассажир + НАЗНАЧЕННЫЙ водитель — кандидат
    с оффером участником ещё не является). Окно статусов: писать — accepted..onboard,
    читать — плюс done/cancelled (read-only). 403 чужому, 409 вне окна."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise HTTPException(404, "Заказ не найден")
    is_passenger = user_id == order.passenger_id
    is_driver = order.driver_id is not None and user_id == order.driver_id
    if not (is_passenger or is_driver):
        raise HTTPException(403, "Нет доступа к чату этого заказа")
    allowed = ORDER_CHAT_WRITABLE if write else ORDER_CHAT_READABLE
    # Забытые вещи: участник завершённого заказа нажал «забыл вещь» → чат снова открыт на запись
    # до lost_item_until (48ч). Иначе связаться было НЕЧЕМ: телефон виден только пока заказ
    # активен, а чат после done — только на чтение. Телефон в машине = потерян навсегда.
    if write and order.status not in allowed:
        until = getattr(order, "lost_item_until", None)
        if until is not None and until > utcnow():
            return order
    if order.status not in allowed:
        # До accept — чата ещё нет; после done/отмены запись закрыта (история читается).
        raise HTTPException(409, "Поездка завершена — чат только для чтения"
                            if write and order.status in ORDER_CHAT_READABLE
                            else "Чат доступен после принятия заказа")
    return order


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
    msgs = 0
    try:
        while True:
            data = await websocket.receive_text()
            try:
                payload = json.loads(data)
            except (json.JSONDecodeError, ValueError):
                continue   # битый (не-JSON) кадр — игнорируем, соединение НЕ роняем
            if payload.get("type") == "message":
                # Периодическая перепроверка токена (паритет с гео-WS, порт из notification-fixes):
                # logout/ревокация должны рвать и ОТКРЫТЫЙ чат-сокет, иначе он живёт до разрыва сети.
                msgs += 1
                if msgs % 15 == 0:
                    with Session(engine) as s2:
                        try:
                            authenticate_ws(token or "", s2)
                        except Exception:
                            await websocket.close(code=1008, reason="Token revoked")
                            break
                # `with` → коннект возвращается в пул сразу (без утечки сессий на каждое сообщение).
                with Session(engine) as session:
                    # Блокировка (как в REST send_message): заблокированный не пишет — тихо игнор.
                    if is_blocked(session, user_id, other_id):
                        continue
                    sender = session.get(User, user_id)
                    text = (payload.get("text") or "")[:4000]
                    # B8-6: анти-фишинг (плашка получателю); B8-9: бейдж «Юлдаш ✓» у админа.
                    msg = Message(booking_id=booking_id, sender_id=user_id, text=text,
                                  flag=phishing_flag(text),
                                  from_admin=bool(sender and sender.role == UserRole.admin))
                    session.add(msg)
                    session.commit()
                    session.refresh(msg)
                    await manager.broadcast(booking_id, {
                        "type": "message",
                        "id": msg.id,
                        "sender_id": msg.sender_id,
                        "text": msg.text,
                        "flag": msg.flag,
                        "from_admin": msg.from_admin,
                        "timestamp": msg.created_at.isoformat()
                    })
                    # Push другой стороне (она может быть офлайн / не в чате). send_push — блокирующий
                    # сетевой вызов к FCM; в async-WS гоним через threadpool, иначе залипший запрос к
                    # Google морозит event-loop и ВСЕ WS-соединения воркера.
                    # data.type=chat → клиент кладёт пуш в канал «Сообщения» (иначе чат звенел бы
                    # в «Поездках» даже у заглушивших его) + extras для deep-link в нужный чат.
                    await run_in_threadpool(
                        send_push, session, other_id,
                        (sender.name if sender else None) or "Новое сообщение",
                        (msg.text or "Сообщение")[:120],
                        {"type": "chat", "id": booking_id},
                    )
    except WebSocketDisconnect:
        pass
    finally:
        manager.disconnect(booking_id, websocket)   # снятие регистрации при ЛЮБОМ выходе — нет утечки сокета


# ============================ Чат такси-заказа (B7b-1) ============================
@router.websocket("/ws/instant/{order_id}/chat")
async def instant_chat_ws(websocket: WebSocket, order_id: int):
    """WebSocket чата такси-заказа — зеркало booking-чата. Токен ТОЛЬКО первым сообщением
    {"type":"auth","token":...} (query-string утекает в логи прокси). Доступ: участники
    заказа, писать можно пока заказ активен (accepted..onboard)."""
    await websocket.accept()
    token = None
    try:
        first = json.loads(await websocket.receive_text())
        if first.get("type") == "auth":
            token = first.get("token")
    except Exception:
        token = None
    with Session(engine) as s:
        try:
            user_id = authenticate_ws(token or "", s).id
        except Exception:
            await websocket.close(code=1008, reason="Invalid token")
            return
        try:
            order = _order_for_chat(s, order_id, user_id, write=True)
        except HTTPException:
            await websocket.close(code=1008, reason="Forbidden")
            return
        passenger_id, driver_id = order.passenger_id, order.driver_id

    other_id = driver_id if user_id == passenger_id else passenger_id
    key = _order_chat_key(order_id)
    manager.register(key, websocket)
    msgs = 0
    try:
        while True:
            data = await websocket.receive_text()
            try:
                payload = json.loads(data)
            except (json.JSONDecodeError, ValueError):
                continue   # битый кадр — игнор, соединение не роняем (как в booking-чате)
            if payload.get("type") == "message":
                msgs += 1
                if msgs % 15 == 0:   # ревокация читается и в открытом сокете (как в booking-чате)
                    with Session(engine) as s2:
                        try:
                            authenticate_ws(token or "", s2)
                        except Exception:
                            await websocket.close(code=1008, reason="Token revoked")
                            break
                with Session(engine) as session:
                    if is_blocked(session, user_id, other_id):
                        continue
                    # Заказ мог завершиться, пока сокет висел: перепроверяем окно записи —
                    # после done/отмены новые сообщения не принимаем (read-only).
                    o2 = session.get(InstantOrder, order_id)
                    if not o2 or o2.status not in ORDER_CHAT_WRITABLE:
                        break
                    sender = session.get(User, user_id)
                    text = (payload.get("text") or "")[:4000]
                    # B8-6: анти-фишинг (плашка получателю); B8-9: бейдж «Юлдаш ✓» у админа.
                    msg = Message(order_id=order_id, sender_id=user_id, text=text,
                                  flag=phishing_flag(text),
                                  from_admin=bool(sender and sender.role == UserRole.admin))
                    session.add(msg)
                    session.commit()
                    session.refresh(msg)
                    await manager.broadcast(key, {
                        "type": "message",
                        "id": msg.id,
                        "sender_id": msg.sender_id,
                        "text": msg.text,
                        "flag": msg.flag,
                        "from_admin": msg.from_admin,
                        "timestamp": msg.created_at.isoformat(),
                    })
                    # Пуш второй стороне (может быть офлайн) — как в booking-чате; send_push
                    # блокирующий → через threadpool, чтобы не морозить event-loop.
                    await run_in_threadpool(
                        send_push, session, other_id,
                        (sender.name if sender else None) or "Новое сообщение",
                        (msg.text or "Сообщение")[:120],
                        {"type": "chat", "id": order_id},   # канал «Сообщения» + deep-link (см. booking-чат)
                    )
    except WebSocketDisconnect:
        pass
    finally:
        manager.disconnect(key, websocket)


@router.post("/instant/orders/{order_id}/messages", response_model=Message)
def send_order_message(order_id: int, body: MessageIn, user: User = Depends(current_user),
                       session: Session = Depends(get_session)):
    """REST-отправка в чат заказа (фолбэк, когда WS лежит). Голос/медиа — только наш URL."""
    order = _order_for_chat(session, order_id, user.id, write=True)
    other_id = order.driver_id if user.id == order.passenger_id else order.passenger_id
    if is_blocked(session, user.id, other_id):
        raise HTTPException(403, "Переписка недоступна")
    if body.voice_url and not body.voice_url.startswith(public_media_url("")):
        raise HTTPException(422, "Недопустимая ссылка на медиа")
    # B8-6: анти-фишинг (плашка получателю); B8-9: бейдж «Юлдаш ✓» у админа.
    msg = Message(order_id=order_id, sender_id=user.id, flag=phishing_flag(body.text),
                  from_admin=(user.role == UserRole.admin), **body.model_dump())
    session.add(msg)
    session.commit()
    session.refresh(msg)
    # Живая доставка открытым чатам (WS) — поля совместимы с клиентским ChatSocket.
    notify_chat_message(_order_chat_key(order_id), {
        "type": "message",
        "id": msg.id,
        "sender_id": msg.sender_id,
        "text": msg.text or "",
        "voice_url": msg.voice_url or "",
        "transcript": msg.transcript or "",
        "flag": msg.flag,
        "from_admin": msg.from_admin,
        "timestamp": msg.created_at.isoformat(),
    })
    send_push(session, other_id, user.name or "Новое сообщение", (msg.text or "Голосовое сообщение")[:120],
              {"type": "chat", "id": order_id})   # канал «Сообщения» + deep-link
    return msg


@router.get("/instant/orders/{order_id}/messages", response_model=List[Message])
def list_order_messages(order_id: int, limit: int = 500, user: User = Depends(current_user),
                        session: Session = Depends(get_session)):
    """История чата заказа. После done/отмены — read-only (читать можно, писать нет).
    Отдаём последние `limit` сообщений в хронологическом порядке (защита от гигантской истории)."""
    _order_for_chat(session, order_id, user.id, write=False)
    limit = max(1, min(limit, 1000))
    rows = session.exec(
        select(Message).where(Message.order_id == order_id).order_by(Message.id.desc()).limit(limit)
    ).all()
    rows = list(reversed(rows))
    return [m for m in rows if user.id not in _hidden_ids(m)]


# ============================ Чат посылки (отправитель ↔ курьер) ============================
@router.websocket("/ws/parcel/{parcel_id}/chat")
async def parcel_chat_ws(websocket: WebSocket, parcel_id: int):
    """WebSocket чата посылки — зеркало чата такси-заказа. Токен ТОЛЬКО первым сообщением
    {"type":"auth","token":...} (query-string утекает в логи прокси). Доступ: отправитель и
    назначенный курьер, писать можно, пока посылка в работе (accepted/in_transit/returning)."""
    await websocket.accept()
    token = None
    try:
        first = json.loads(await websocket.receive_text())
        if first.get("type") == "auth":
            token = first.get("token")
    except Exception:
        token = None
    with Session(engine) as s:
        try:
            user_id = authenticate_ws(token or "", s).id
        except Exception:
            await websocket.close(code=1008, reason="Invalid token")
            return
        try:
            parcel = _parcel_for_chat(s, parcel_id, user_id, write=True)
        except HTTPException:
            await websocket.close(code=1008, reason="Forbidden")
            return
        sender_id, courier_id = parcel.sender_id, parcel.courier_id

    other_id = courier_id if user_id == sender_id else sender_id
    key = _parcel_chat_key(parcel_id)
    manager.register(key, websocket)
    msgs = 0
    try:
        while True:
            data = await websocket.receive_text()
            try:
                payload = json.loads(data)
            except (json.JSONDecodeError, ValueError):
                continue   # битый кадр — игнор, соединение не роняем (как в чате заказа)
            if payload.get("type") == "message":
                msgs += 1
                if msgs % 15 == 0:   # ревокация читается и в открытом сокете (как в чате заказа)
                    with Session(engine) as s2:
                        try:
                            authenticate_ws(token or "", s2)
                        except Exception:
                            await websocket.close(code=1008, reason="Token revoked")
                            break
                with Session(engine) as session:
                    if is_blocked(session, user_id, other_id):
                        continue
                    # Доставка могла закрыться, пока сокет висел: перепроверяем окно записи —
                    # после вручения/возврата/отмены новые сообщения не принимаем (read-only).
                    p2 = session.get(ParcelDelivery, parcel_id)
                    if not p2 or p2.status not in PARCEL_CHAT_WRITABLE or p2.courier_id != courier_id:
                        break
                    sender = session.get(User, user_id)
                    text = (payload.get("text") or "")[:4000]
                    # B8-6: анти-фишинг (плашка получателю); B8-9: бейдж «Юлдаш ✓» у админа.
                    msg = Message(parcel_id=parcel_id, sender_id=user_id, text=text,
                                  flag=phishing_flag(text),
                                  from_admin=bool(sender and sender.role == UserRole.admin))
                    session.add(msg)
                    session.commit()
                    session.refresh(msg)
                    await manager.broadcast(key, {
                        "type": "message",
                        "id": msg.id,
                        "sender_id": msg.sender_id,
                        "text": msg.text,
                        "flag": msg.flag,
                        "from_admin": msg.from_admin,
                        "timestamp": msg.created_at.isoformat(),
                    })
                    # Пуш второй стороне (может быть офлайн) — как в чате заказа; send_push
                    # блокирующий → через threadpool, чтобы не морозить event-loop.
                    await run_in_threadpool(
                        send_push, session, other_id,
                        (sender.name if sender else None) or "Новое сообщение",
                        (msg.text or "Сообщение")[:120],
                        # parcel_chat (не parcel_status): клиент кладёт пуш в канал «Сообщения»,
                        # а не в «Поездки», и открывает переписку по этой доставке.
                        {"type": "parcel_chat", "id": parcel_id},
                    )
    except WebSocketDisconnect:
        pass
    finally:
        manager.disconnect(key, websocket)


@router.post("/parcels/{parcel_id}/messages", response_model=Message)
def send_parcel_message(parcel_id: int, body: MessageIn, user: User = Depends(current_user),
                        session: Session = Depends(get_session)):
    """REST-отправка в чат посылки (фолбэк, когда WS лежит). Голос/медиа — только наш URL."""
    parcel = _parcel_for_chat(session, parcel_id, user.id, write=True)
    other_id = parcel.courier_id if user.id == parcel.sender_id else parcel.sender_id
    if is_blocked(session, user.id, other_id):
        raise herr(403, "Переписка недоступна", "Яҙышыу мөмкин түгел")
    if body.voice_url and not body.voice_url.startswith(public_media_url("")):
        raise herr(422, "Недопустимая ссылка на медиа", "Ярамаған медиа һылтанмаһы")
    # B8-6: анти-фишинг (плашка получателю); B8-9: бейдж «Юлдаш ✓» у админа.
    msg = Message(parcel_id=parcel_id, sender_id=user.id, flag=phishing_flag(body.text),
                  from_admin=(user.role == UserRole.admin), **body.model_dump())
    session.add(msg)
    session.commit()
    session.refresh(msg)
    # Живая доставка открытым чатам (WS) — поля совместимы с клиентским ChatSocket.
    notify_chat_message(_parcel_chat_key(parcel_id), {
        "type": "message",
        "id": msg.id,
        "sender_id": msg.sender_id,
        "text": msg.text or "",
        "voice_url": msg.voice_url or "",
        "transcript": msg.transcript or "",
        "flag": msg.flag,
        "from_admin": msg.from_admin,
        "timestamp": msg.created_at.isoformat(),
    })
    send_push(session, other_id, user.name or "Новое сообщение",
              (msg.text or "Голосовое сообщение")[:120],
              {"type": "parcel_chat", "id": parcel_id})   # канал «Сообщения» + deep-link
    return msg


@router.get("/parcels/{parcel_id}/messages", response_model=List[Message])
def list_parcel_messages(parcel_id: int, limit: int = 500, user: User = Depends(current_user),
                         session: Session = Depends(get_session)):
    """История чата посылки. После вручения/возврата/отмены — read-only (читать можно, писать нет).
    Отдаём последние `limit` сообщений в хронологическом порядке (защита от гигантской истории)."""
    _parcel_for_chat(session, parcel_id, user.id, write=False)
    limit = max(1, min(limit, 1000))
    rows = session.exec(
        select(Message).where(Message.parcel_id == parcel_id).order_by(Message.id.desc()).limit(limit)
    ).all()
    rows = list(reversed(rows))
    return [m for m in rows if user.id not in _hidden_ids(m)]


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
    # B8-6: анти-фишинг (плашка получателю); B8-9: бейдж «Юлдаш ✓» у админа.
    msg = Message(booking_id=booking_id, sender_id=user.id, flag=phishing_flag(body.text),
                  from_admin=(user.role == UserRole.admin), **body.model_dump())
    session.add(msg)
    session.commit()
    session.refresh(msg)
    # Живая доставка собеседнику с открытым чатом (как в WS-хендлере) — иначе голос/фото/текст-фолбэк
    # виден только после переполла истории. Поля совместимы с клиентским ChatSocket (id/sender_id/text/timestamp).
    notify_chat_message(booking_id, {
        "type": "message",
        "id": msg.id,
        "sender_id": msg.sender_id,
        "text": msg.text or "",
        "voice_url": msg.voice_url or "",
        "transcript": msg.transcript or "",
        "flag": msg.flag,
        "from_admin": msg.from_admin,
        "timestamp": msg.created_at.isoformat(),
    })
    # Уведомление + push другой стороне брони (кто не отправитель). REST-путь (в отличие от WS)
    # используется, когда получатель НЕ в живом сокете (голос/фото/фолбэк) — тогда запись в Центр
    # уведомлений осмысленна. Живой WS-обмен (оба в чате) уведомление не плодит.
    other_id = ride.driver_id if user.id == booking.passenger_id else booking.passenger_id
    preview = (msg.text or "Голосовое сообщение")[:120]
    push_notification(
        session, other_id, "message",
        user.name or "Новое сообщение", user.name or "Яңы хәбәр",
        preview, preview,
        ref_kind="booking", ref_id=booking_id,
        data={"type": "chat", "id": booking_id},   # канал «Сообщения» + deep-link
    )
    return msg


@router.get("/bookings/{booking_id}/messages", response_model=List[Message])
def list_messages(booking_id: int, limit: int = 500,
                  user: User = Depends(current_user), session: Session = Depends(get_session)):
    booking_and_ride_for_user(session, booking_id, user)
    # Отдаём последние `limit` сообщений в хронологическом порядке (защита от гигантской истории).
    limit = max(1, min(limit, 1000))
    rows = session.exec(
        select(Message).where(Message.booking_id == booking_id).order_by(Message.id.desc()).limit(limit)
    ).all()
    rows = list(reversed(rows))
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
    msg.flag = phishing_flag(text)   # B8-6: обход через «отправил безобидное → отредактировал в фишинг» закрыт
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
    peer_verified: bool = False            # реальный статус проверки собеседника (не фейк «проверен» у всех)
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
            peer_verified=(bool(peer.verified) if peer else False),
            route=(f"{ride.from_city} → {ride.to_city}" if ride else ""),
            last_message=("Чат открыт" if last is None else _message_preview(last)),
            depart_at=(ride.depart_at if ride else None),
        ))
    return out

# `/notifications` переехал в routers/notifications.py (Центр уведомлений: типизированная
# лента из таблицы Notification с пометкой прочитанного). Здесь плацебо-версия удалена.
