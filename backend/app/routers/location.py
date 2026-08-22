"""WebSocket live-позиции участников активной поездки (водитель <-> пассажир).

Приватность: поток координат разрешён ТОЛЬКО участнику брони и ТОЛЬКО когда поездка
активна (confirmed/onboard). Координаты НЕ пишем в БД — только ретранслируем другому
участнику (поток живёт лишь во время поездки). Namespace ключа = -booking_id, чтобы
не смешиваться с чатом (общий ConnectionManager + Redis; subscriber кастует int —
отрицательный ключ валиден)."""
import json

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from sqlmodel import Session

from ..antifraud import TrackGuard
from ..db import engine
from ..livepos import livepos_set
from ..models import Booking, BookingStatus, InstantOrder, InstantOrderStatus, ParcelDelivery, Ride
from ..security import authenticate_ws
from ..ws_guard import watch_ws_access
from ..services import MAP_FEED_KEY, manager

router = APIRouter(tags=["location"])

# Namespace ключей ConnectionManager для такси-трека (B7a-3): не пересекается ни с чатом
# (booking_id > 0), ни с трек-каналами брони (-bid*2 / -bid*2-1 — малые по модулю),
# ни с MAP_FEED_KEY (=2_000_000_000).
INSTANT_LOC_BASE = 1_000_000_000
# Live-позиция такси-заказа живёт ТОЛЬКО пока водитель назначен и заказ активен.
INSTANT_LOC_ACTIVE = (InstantOrderStatus.accepted, InstantOrderStatus.arriving, InstantOrderStatus.onboard)
# Namespace трека посылки (курьер → отправитель): не пересекается с трипом/инстантом/картой.
PARCEL_LOC_BASE = 3_000_000_000
# Live-позиция курьера живёт ТОЛЬКО пока посылка у курьера в работе (взял / везёт).
PARCEL_LOC_ACTIVE = ("accepted", "in_transit")


@router.websocket("/ws/map")
async def map_feed(websocket: WebSocket):
    """Лёгкий сигнальный канал карты: сервер шлёт {"type":"refresh"}, когда что-то меняется
    (новая поездка/заявка, бронь, отмена, завершение) → клиент перетягивает /rides/near + /requests/near
    мгновенно, не дожидаясь 25-сек опроса. Токен — первым сообщением (как в чате); анонимам остаётся polling.
    В пинге НЕТ данных (только сигнал) → приватность не задета."""
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
            authenticate_ws(token or "", s)   # существование + ревокация + блок (как REST)
        except Exception:
            await websocket.close(code=1008, reason="Invalid token")
            return
    manager.register(MAP_FEED_KEY, websocket)
    # Живой страж, как у остальных шести каналов (волна 180). Данных этот канал не отдаёт —
    # только сигнал «обнови список», — но открытое окно живёт часами: человек вышел из аккаунта
    # или его заблокировали, а подписка продолжает висеть до закрытия приложения. Проверки
    # участника тут нет по смыслу (карта общая), поэтому страж следит только за токеном.
    страж = await watch_ws_access(websocket, token,
                                  on_close=lambda: manager.disconnect(MAP_FEED_KEY, websocket))
    try:
        while True:
            await websocket.receive_text()   # клиент осмысленного не шлёт; держим соединение до закрытия
    except WebSocketDisconnect:
        pass
    finally:
        страж.cancel()
        manager.disconnect(MAP_FEED_KEY, websocket)


def _канал_мой(session: Session, я: int, второй, *, живо: bool) -> bool:
    """Живой канал координат остаётся открытым, пока верно ВСЁ (аудит 2026-08-08, волна 159).

    Сторож канала спрашивал только «поездка ещё едет?». Этого мало по двум причинам, обе
    доказаны пробой.

    **Блокировка.** Бронь подтверждена, выезд не начался, женщина ждёт дома. Водитель ведёт себя
    навязчиво — она жмёт «Заблокировать». Чат закрывается ему мгновенно (403), а её точка
    продолжает уходить к нему на карту: слово `is_blocked` в этом файле не встречалось ни разу,
    хотя в чате оно стоит на каждой двери. «Заблокировать» — единственная кнопка, которой человек
    закрывается от человека, и она обещала больше, чем делала.

    **Смена исполнителя.** Курьер взял посылку, открыл канал, потом снялся («заболел»). Посылку
    берёт другой курьер. Сторож видит «посылка едет» и пропускает — а точка СНЯТОГО курьера
    уходит отправителю как «где сейчас моя посылка». В чате той же посылки сверка «курьер всё
    ещё я?» стояла, в канале координат её не было.

    `второй` — вторая сторона поездки прямо сейчас. None означает, что меня из неё убрали
    (сняли курьера, отвязали водителя) — канал больше не мой.
    """
    if not живо or второй is None or второй == я:
        return False
    from ..services import is_blocked
    return not is_blocked(session, я, второй)


def _вторая_сторона(я: int, первый, второй):
    """Кто на другом конце канала. None — если меня среди сторон уже нет."""
    if первый is not None and я == первый:
        return второй
    if второй is not None and я == второй:
        return первый
    return None


@router.websocket("/ws/trip/{booking_id}/location")
async def trip_location(websocket: WebSocket, booking_id: int):
    """Токен — первым сообщением {"type":"auth","token":...} (как в чате; query-string не берём).
    Дальше клиент шлёт {"type":"loc","lat":..,"lng":..,"bearing":..,"ts":..} ~раз в 5-10 c;
    сервер ретранслирует другому участнику с полем role (driver/passenger)."""
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
        booking = s.get(Booking, booking_id)
        ride = s.get(Ride, booking.ride_id) if booking else None
        # Авторизация на ресурс (анти-IDOR): только участник ИМЕННО этой брони.
        if not booking or not ride or (booking.passenger_id != user_id and ride.driver_id != user_id):
            await websocket.close(code=1008, reason="Forbidden")
            return
        # Приватность: точная live-позиция — только во время активной поездки.
        if booking.status not in (BookingStatus.confirmed, BookingStatus.onboard):
            await websocket.close(code=1008, reason="Trip not active")
            return
        driver_id = ride.driver_id

    role = "driver" if user_id == driver_id else "passenger"
    # Два ключа-НАПРАВЛЕНИЯ, чтобы НЕ возвращать отправителю его же позицию (эхо → стрелка попутчика
    # мигала бы на своей точке). Каждый слушает свой inbox, шлёт в inbox ДРУГОГО.
    # -bid*2 = inbox водителя (туда шлёт пассажир), -bid*2-1 = inbox пассажира (туда шлёт водитель).
    recv_key = (-booking_id * 2) if role == "driver" else (-booking_id * 2 - 1)
    send_key = (-booking_id * 2 - 1) if role == "driver" else (-booking_id * 2)
    manager.register(recv_key, websocket)
    # Сторож открытого канала (волна 141): право читать проверяется по часам, а не по тому,
    # шлёт ли этот человек кадры. Тот, кто только СЛУШАЕТ чужие координаты, раньше
    # не перепроверялся никогда — вышел из аккаунта, а поток координат шёл дальше.
    страж = await watch_ws_access(
        websocket, token,
        lambda s2: (lambda b, r: _канал_мой(
            s2, user_id,
            _вторая_сторона(user_id, b.passenger_id if b else None, r.driver_id if r else None),
            живо=bool(b) and b.status in (BookingStatus.confirmed, BookingStatus.onboard),
        ))(s2.get(Booking, booking_id),
           s2.get(Ride, s2.get(Booking, booking_id).ride_id) if s2.get(Booking, booking_id) else None),
        on_close=lambda: manager.disconnect(recv_key, websocket),
    )
    guard = TrackGuard(user_id)   # анти-телепорт (B8-3): фейковые скачки не ретранслируем
    msgs = 0
    try:
        while True:
            data = await websocket.receive_text()
            try:
                payload = json.loads(data)
            except (json.JSONDecodeError, ValueError):
                continue   # битый кадр — игнор, соединение не роняем
            if payload.get("type") == "loc":
                lat = payload.get("lat")
                lng = payload.get("lng")
                if not isinstance(lat, (int, float)) or not isinstance(lng, (int, float)):
                    continue
                # Перепроверка ~раз в ~2 мин: токен не отозван/не заблокирован И поездка ещё активна.
                # Иначе стрим лил бы гео в завершённую/отменённую бронь или у заблокированного — утечка.
                msgs += 1
                if msgs % 15 == 0:
                    with Session(engine) as s2:
                        try:
                            authenticate_ws(token or "", s2)
                        except Exception:
                            await websocket.close(code=1008, reason="Token revoked")
                            break
                        b2 = s2.get(Booking, booking_id)
                        if not b2 or b2.status not in (BookingStatus.confirmed, BookingStatus.onboard):
                            await websocket.close(code=1008, reason="Trip ended")
                            break
                # Анти-телепорт (B8-3): скорость > 200 км/ч от прошлой точки → кадр игнорируем
                # (счётчик подозрительности копится), соединение честного юзера не рвём.
                if not guard.ok(float(lat), float(lng)):
                    continue
                if role == "driver":
                    # Live-ссылка близкому (B7c): последняя позиция машины → Redis (TTL ~2 мин),
                    # публичный /t/{token}/state.json читает её. В БД/лог координаты НЕ пишем.
                    livepos_set("booking", booking_id, lat, lng, payload.get("bearing"))
                await manager.broadcast(send_key, {   # в inbox ДРУГОГО участника (не себе)
                    "type": "loc",
                    "role": role,
                    "lat": lat,
                    "lng": lng,
                    "bearing": payload.get("bearing"),
                    "ts": payload.get("ts"),
                })
    except WebSocketDisconnect:
        pass
    finally:
        страж.cancel()          # сторож живёт ровно столько, сколько канал
        manager.disconnect(recv_key, websocket)   # снятие регистрации при любом выходе


@router.websocket("/ws/instant/{order_id}/location")
async def instant_location(websocket: WebSocket, order_id: int):
    """Live-позиция «Быстрого заказа» (такси, B7a-3) — зеркало /ws/trip/{id}/location.
    Водитель шлёт {"type":"loc",...}, пассажир видит движущуюся машину. Только участники
    ЭТОГО заказа (пассажир + НАЗНАЧЕННЫЙ водитель) и только пока заказ активен
    (accepted/arriving/onboard). Координаты не храним — чистая ретрансляция.
    Трек брони (попутка) не затронут: свой namespace ключей (INSTANT_LOC_BASE)."""
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
        order = s.get(InstantOrder, order_id)
        # Анти-IDOR: участник именно этого заказа. Водитель — только НАЗНАЧЕННЫЙ (после accept);
        # кандидат с оффером участником ещё не является (телефоны/гео до accept закрыты).
        if not order or user_id not in (order.passenger_id, order.driver_id):
            await websocket.close(code=1008, reason="Forbidden")
            return
        if order.status not in INSTANT_LOC_ACTIVE:
            await websocket.close(code=1008, reason="Order not active")
            return
        driver_id = order.driver_id

    role = "driver" if user_id == driver_id else "passenger"
    # Направленные ключи (как в трек-канале брони) — без self-эхо: каждый слушает СВОЙ inbox,
    # шлёт в inbox другого. base+oid*2 = inbox водителя, base+oid*2+1 = inbox пассажира.
    recv_key = -(INSTANT_LOC_BASE + order_id * 2) if role == "driver" else -(INSTANT_LOC_BASE + order_id * 2 + 1)
    send_key = -(INSTANT_LOC_BASE + order_id * 2 + 1) if role == "driver" else -(INSTANT_LOC_BASE + order_id * 2)
    manager.register(recv_key, websocket)
    # Сторож открытого канала (волна 141): право читать проверяется по часам, а не по тому,
    # шлёт ли этот человек кадры. Тот, кто только СЛУШАЕТ чужие координаты, раньше
    # не перепроверялся никогда — вышел из аккаунта, а поток координат шёл дальше.
    страж = await watch_ws_access(
        websocket, token,
        lambda s2: (lambda o: _канал_мой(
            s2, user_id,
            _вторая_сторона(user_id, o.passenger_id if o else None, o.driver_id if o else None),
            живо=bool(o) and o.status in INSTANT_LOC_ACTIVE,
        ))(s2.get(InstantOrder, order_id)),
        on_close=lambda: manager.disconnect(recv_key, websocket),
    )
    guard = TrackGuard(user_id)   # анти-телепорт (B8-3): фейковые скачки не ретранслируем
    msgs = 0
    try:
        while True:
            data = await websocket.receive_text()
            try:
                payload = json.loads(data)
            except (json.JSONDecodeError, ValueError):
                continue   # битый кадр — игнор, соединение не роняем
            if payload.get("type") == "loc":
                lat = payload.get("lat")
                lng = payload.get("lng")
                if not isinstance(lat, (int, float)) or not isinstance(lng, (int, float)):
                    continue
                # Перепроверка ~раз в ~15 кадров: токен жив И заказ ещё активен —
                # иначе стрим лил бы гео в завершённый/отменённый заказ (приватность).
                msgs += 1
                if msgs % 15 == 0:
                    with Session(engine) as s2:
                        try:
                            authenticate_ws(token or "", s2)
                        except Exception:
                            await websocket.close(code=1008, reason="Token revoked")
                            break
                        o2 = s2.get(InstantOrder, order_id)
                        if not o2 or o2.status not in INSTANT_LOC_ACTIVE:
                            await websocket.close(code=1008, reason="Order ended")
                            break
                # Анти-телепорт (B8-3): фейковый скачок игнорируем, соединение не рвём.
                if not guard.ok(float(lat), float(lng)):
                    continue
                if role == "driver":
                    # Live-ссылка близкому (B7c): позиция машины → Redis-кэш (см. трек брони выше).
                    livepos_set("order", order_id, lat, lng, payload.get("bearing"))
                await manager.broadcast(send_key, {   # в inbox ДРУГОГО участника (не себе)
                    "type": "loc",
                    "role": role,
                    "lat": lat,
                    "lng": lng,
                    "bearing": payload.get("bearing"),
                    "ts": payload.get("ts"),
                })
    except WebSocketDisconnect:
        pass
    finally:
        страж.cancel()          # сторож живёт ровно столько, сколько канал
        manager.disconnect(recv_key, websocket)


@router.websocket("/ws/parcel/{parcel_id}/location")
async def parcel_location(websocket: WebSocket, parcel_id: int):
    """Live-позиция доставки посылки (курьер → отправитель) — зеркало /ws/instant/{id}/location.
    Курьер шлёт {"type":"loc",...}, отправитель видит движущегося курьера. Только участники
    ИМЕННО этой посылки (отправитель + НАЗНАЧЕННЫЙ курьер) и только пока посылка в работе
    (accepted/in_transit). Координаты не храним — чистая ретрансляция. Свой namespace ключей."""
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
        parcel = s.get(ParcelDelivery, parcel_id)
        # Анти-IDOR: участник именно этой посылки. Курьер — только НАЗНАЧЕННЫЙ (после accept).
        if not parcel or user_id not in (parcel.sender_id, parcel.courier_id):
            await websocket.close(code=1008, reason="Forbidden")
            return
        if parcel.status not in PARCEL_LOC_ACTIVE:
            await websocket.close(code=1008, reason="Delivery not active")
            return
        courier_id = parcel.courier_id

    role = "courier" if user_id == courier_id else "sender"
    # Направленные ключи (без self-эхо): base+pid*2 = inbox курьера, base+pid*2+1 = inbox отправителя.
    recv_key = -(PARCEL_LOC_BASE + parcel_id * 2) if role == "courier" else -(PARCEL_LOC_BASE + parcel_id * 2 + 1)
    send_key = -(PARCEL_LOC_BASE + parcel_id * 2 + 1) if role == "courier" else -(PARCEL_LOC_BASE + parcel_id * 2)
    manager.register(recv_key, websocket)
    # Сторож открытого канала (волна 141): право читать проверяется по часам, а не по тому,
    # шлёт ли этот человек кадры. Тот, кто только СЛУШАЕТ чужие координаты, раньше
    # не перепроверялся никогда — вышел из аккаунта, а поток координат шёл дальше.
    страж = await watch_ws_access(
        websocket, token,
        lambda s2: (lambda pl: _канал_мой(
            s2, user_id,
            _вторая_сторона(user_id, pl.sender_id if pl else None, pl.courier_id if pl else None),
            живо=bool(pl) and pl.status in PARCEL_LOC_ACTIVE,
        ))(s2.get(ParcelDelivery, parcel_id)),
        on_close=lambda: manager.disconnect(recv_key, websocket),
    )
    guard = TrackGuard(user_id)   # анти-телепорт: фейковые скачки не ретранслируем
    msgs = 0
    try:
        while True:
            data = await websocket.receive_text()
            try:
                payload = json.loads(data)
            except (json.JSONDecodeError, ValueError):
                continue
            if payload.get("type") == "loc":
                lat = payload.get("lat")
                lng = payload.get("lng")
                if not isinstance(lat, (int, float)) or not isinstance(lng, (int, float)):
                    continue
                msgs += 1
                if msgs % 15 == 0:
                    with Session(engine) as s2:
                        try:
                            authenticate_ws(token or "", s2)
                        except Exception:
                            await websocket.close(code=1008, reason="Token revoked")
                            break
                        p2 = s2.get(ParcelDelivery, parcel_id)
                        if not p2 or p2.status not in PARCEL_LOC_ACTIVE:
                            await websocket.close(code=1008, reason="Delivery ended")
                            break
                if not guard.ok(float(lat), float(lng)):
                    continue
                if role == "courier":
                    livepos_set("parcel", parcel_id, lat, lng, payload.get("bearing"))
                await manager.broadcast(send_key, {
                    "type": "loc",
                    "role": role,
                    "lat": lat,
                    "lng": lng,
                    "bearing": payload.get("bearing"),
                    "ts": payload.get("ts"),
                })
    except WebSocketDisconnect:
        pass
    finally:
        страж.cancel()          # сторож живёт ровно столько, сколько канал
        manager.disconnect(recv_key, websocket)
