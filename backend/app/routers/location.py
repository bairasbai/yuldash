"""WebSocket live-позиции участников активной поездки (водитель <-> пассажир).

Приватность: поток координат разрешён ТОЛЬКО участнику брони и ТОЛЬКО когда поездка
активна (confirmed/onboard). Координаты НЕ пишем в БД — только ретранслируем другому
участнику (поток живёт лишь во время поездки). Namespace ключа = -booking_id, чтобы
не смешиваться с чатом (общий ConnectionManager + Redis; subscriber кастует int —
отрицательный ключ валиден)."""
import json
import time

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from sqlmodel import Session

from .. import trip_wake
from ..antifraud import TrackGuard
from ..db import engine
from ..livepos import livepos_set
from ..models import Booking, BookingStatus, InstantOrder, InstantOrderStatus, ParcelDelivery, Ride
from ..security import authenticate_ws
from ..ws_guard import watch_ws_access
from ..logs import log
from ..services import MAP_FEED_KEY, haversine_km, manager
from ..visibility import trip_is_live

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

# Пока поездка не началась, ответ «ещё нет» запоминается на соединение и базу не дёргает чаще раза
# в столько секунд. Раньше база спрашивалась на каждый кадр (раз в 5–10 с, часами), см. `_Пора`.
PRE_TRIP_RECHECK_SEC = 30
_monotonic = time.monotonic   # отдельным именем — чтобы тест мог двигать часы, не трогая asyncio


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
    except WebSocketDisconnect:
        return
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


def _заблокированы(session: Session, a: int, b: int) -> bool:
    from ..services import is_blocked
    return is_blocked(session, a, b)


def _пишем_без_пересылки(session: Session, b, r, я) -> bool:
    """Блокировка между двумя случилась «в пути»: канал водителя не закрыть, а только писать точку.

    Зачем (решение Александра 17.10.2026). Пассажирка «в пути» блокирует водителя — значит, что-то
    не так, а она всё ещё сидит в его машине. Закрой мы канал водителя, вместе с её картой замерла
    бы и последняя точка машины для ссылки близким (`livepos`, «Поделиться поездкой») и для SOS —
    близкие теряли бы машину ровно тогда, когда тревожно. То же, если блокирует водитель:
    пассажирка физически в этой машине, её близким машина нужна так же.

    «Только запись» = точку водителя пишем в кэш, но по живому каналу не пересылаем ни одному из
    двоих, и водитель не принимает точку пассажирки. Кэш читают ссылка близким пассажирки и SOS —
    и это честно не «никому»: пассажирка, которую заблокировал водитель, может сама открыть свою
    ссылку и видит место машины в ответе на свой SOS, до конца поездки. Принято сознательно
    (вопрос Q16, вариант «а» по умолчанию лида): пока она в машине, место машины — её собственное
    место. Водителю, которого заблокировала пассажирка, ничего нового не открывается.

    Только для блокировки ПОСЛЕ посадки (независимая проверка PR170, P1). Иначе блокировка
    «накануне» оживала бы «в пути»: водитель заблокировал, бронь не отменил, потом поездка стала
    «в пути» (его «Сел», а до PR174 — её «Я села» из дома) — и заблокированная следила бы за ним
    по своей ссылке. Решает самая свежая блокировка пары, в любую сторону; совпадение по времени —
    в пользу закрытия. Посадка без времени (`onboard_at` пуст) — старый пассажирский «Я сел» до
    PR174, такие брони есть только на стыке выкладки. Тот «Я сел» был возможен не раньше
    `DONE_EARLY_GRACE` до выезда: блокировка до этого момента точно была до посадки (канал закрыт),
    после — кто был раньше, не узнать, и решаем в пользу близких: будто села до блокировки.

    Звать, когда блокировка между ними уже установлена: без блокировок — False. До посадки и
    после конца поездки — False: канал закрывается как раньше.
    """
    if not (b is not None and r is not None and я is not None and я == r.driver_id
            and b.status == BookingStatus.onboard):
        return False
    from sqlalchemy import and_, or_
    from sqlmodel import select

    from ..models import Block
    from .bookings import DONE_EARLY_GRACE
    когда = session.exec(select(Block.created_at).where(or_(
        and_(Block.user_id == b.passenger_id, Block.blocked_user_id == r.driver_id),
        and_(Block.user_id == r.driver_id, Block.blocked_user_id == b.passenger_id),
    ))).all()
    if not когда:
        return False
    посадка = b.onboard_at
    if посадка is None:
        if r.depart_at is None:
            return True
        посадка = r.depart_at - DONE_EARLY_GRACE   # раньше этого старый «Я сел» не ставил «в пути»
    return посадка < max(когда)


def _вторая_сторона(я: int, первый, второй):
    """Кто на другом конце канала. None — если меня среди сторон уже нет."""
    if первый is not None and я == первый:
        return второй
    if второй is not None and я == второй:
        return первый
    return None


class _Пора:
    """«Поездка началась, точку можно пересылать?» — решение на одно соединение.

    Правило не изменилось и живёт в одном месте — `visibility.trip_is_live`. Изменилось только,
    как часто его спрашивают у базы. «Ещё нет» запоминается на `PRE_TRIP_RECHECK_SEC`; раньше
    базу читали на каждый кадр, хотя экран поездки открывают за часы до выезда.

    Запомненное «ещё нет» может только опоздать с показом, но не показать раньше времени: «да»
    появляется лишь из свежего чтения базы. Чтобы «Выехал» не ждал перепроверки, ручка водителя
    толкает канал (`trip_wake.poke`), и следующий же кадр идёт в базу, не дожидаясь срока.
    Отмена, неявка и снятие поездки тоже толкают канал (приёмка PR155): по толчку уже начавшаяся
    поездка перечитывает статус брони, и если сделки больше нет — `кончилась`, канал закрывается
    сразу, а не через сторож (до 30 с точка водителя ещё уходила пассажиру отменённой брони).
    Начавшаяся поездка «назад в не начавшуюся» не откатывается, как и раньше.

    Блокировка закрывает канал — кроме канала водителя, когда блокировка случилась «в пути»
    (`_пишем_без_пересылки`): тот переходит в `только_запись` и остаётся в нём до конца
    соединения (снятая блокировка пересылку не возвращает — так безопаснее). Конец поездки
    закрывает и его, с «Trip ended».
    """

    def __init__(self, booking_id: int, я: "int | None" = None) -> None:
        self._id = booking_id
        self._я = я              # кто на этом конце канала; без него блокировка закрывает канал всегда
        self._да = False
        self.кончилась = False   # толчок показал: сделки нет или блокировка — канал закрыть
        self.причина = "Trip ended"   # с какой причиной закрыть: блокировка — как у сторожа
        self.только_запись = False   # блокировка «в пути»: точку пишем для ссылки и SOS, по каналу не пересылаем
        self._спросили_в = 0.0   # стенные часы НАЧАЛА последней проверки — по ним сверяем толчки
        self._не_раньше = 0.0    # монотонные часы: до этого момента базу повторно не спрашиваем

    def наступила(self) -> bool:
        if self.кончилась:
            return False
        толкнули = trip_wake.poked_since(self._id, self._спросили_в)
        if self._да and not толкнули:
            return True          # началась — пока никто не толкнул, базу не спрашиваем
        if not self._да and _monotonic() < self._не_раньше and not толкнули:
            return False
        # Время берём ДО чтения: толчок, пришедший во время чтения, не потеряется (trip_wake).
        self._спросили_в = time.time()
        with Session(engine) as s:
            b = s.get(Booking, self._id)
            r = s.get(Ride, b.ride_id) if b else None
            if b is not None and r is not None and _заблокированы(s, b.passenger_id, r.driver_id):
                if _пишем_без_пересылки(s, b, r, self._я):
                    # Заблокировали «в пути»: точку машины пишем дальше для ссылки близким и SOS,
                    # по каналу никому из двоих не пересылаем (`_пишем_без_пересылки`).
                    self.только_запись = True
                    self._да = True
                    return True
                # Кто-то из двоих нажал «Заблокировать» (ручка толкает канал): закрываем сразу и с той
                # же причиной, что сторож, — он закрыл бы этот канал через ≤ 30 с (приёмка PR157).
                # Канал, который после блокировки только писал точку, закрывает конец поездки.
                self.кончилась = True
                self.причина = "Trip ended" if self.только_запись else "Access ended"
                return False
            if self._да:
                # Толчок по идущей поездке («Подъезжаю», отмена, неявка, снятие): назад не откатываем,
                # но если сделки больше нет — канал закрывается сразу.
                self.кончилась = b is None or b.status not in (BookingStatus.confirmed, BookingStatus.onboard)
                return not self.кончилась
            self._да = trip_is_live(b, r)
        self._не_раньше = _monotonic() + PRE_TRIP_RECHECK_SEC
        return self._да


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
    except WebSocketDisconnect:
        return
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
            # Ожидание подтверждения допускает повтор, завершённая бронь — нет.
            # Проверяем это после участника, чтобы не раскрывать чужой статус.
            reason = "Trip ended" if booking.status in (
                BookingStatus.done, BookingStatus.cancelled,
            ) else "Trip not active"
            await websocket.close(code=1008, reason=reason)
            return
        driver_id = ride.driver_id

    role = "driver" if user_id == driver_id else "passenger"
    # Два ключа-НАПРАВЛЕНИЯ, чтобы НЕ возвращать отправителю его же позицию (эхо → стрелка попутчика
    # мигала бы на своей точке). Каждый слушает свой inbox, шлёт в inbox ДРУГОГО.
    # -bid*2 = inbox водителя (туда шлёт пассажир), -bid*2-1 = inbox пассажира (туда шлёт водитель).
    recv_key = (-booking_id * 2) if role == "driver" else (-booking_id * 2 - 1)
    send_key = (-booking_id * 2 - 1) if role == "driver" else (-booking_id * 2)
    пора = _Пора(booking_id, user_id)   # поездка началась? (`trip_is_live`) — пока нет, точки не пересылаем

    def _ещё_можно(s2: Session) -> bool:
        b = s2.get(Booking, booking_id)
        r = s2.get(Ride, b.ride_id) if b else None
        if _канал_мой(s2, user_id,
                      _вторая_сторона(user_id, b.passenger_id if b else None, r.driver_id if r else None),
                      живо=bool(b) and b.status in (BookingStatus.confirmed, BookingStatus.onboard)):
            return True
        if _пишем_без_пересылки(s2, b, r, user_id):
            # Толчок блокировки мог не дойти (чужой воркер без Redis) — сторож сам переводит канал
            # водителя в «только запись»: не закрывает, а снимает с приёма и пересылки.
            пора.только_запись = True
            manager.disconnect(recv_key, websocket)
            return True
        return False

    manager.register(recv_key, websocket)
    # Сторож открытого канала (волна 141): право читать проверяется по часам, а не по тому,
    # шлёт ли этот человек кадры. Тот, кто только СЛУШАЕТ чужие координаты, раньше
    # не перепроверялся никогда — вышел из аккаунта, а поток координат шёл дальше.
    страж = await watch_ws_access(
        websocket, token, _ещё_можно,
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
                # Договорились ≠ поехали: пока поездка не началась, точку никому не пересылаем и в кэш
                # для ссылки близкому не пишем (`trip_is_live`). Канал не рвём — экран поездки можно
                # открыть заранее, и точки пойдут сами, как только станет пора. База при этом
                # спрашивается не на каждый кадр, а раз в `PRE_TRIP_RECHECK_SEC` или по толчку «Выехал».
                if not пора.наступила():
                    if пора.кончилась:
                        await websocket.close(code=1008, reason=пора.причина)
                        break
                    continue
                if пора.только_запись:
                    # Блокировка «в пути» (`_пишем_без_пересылки`): водитель больше не принимает точку
                    # пассажирки — снимаем его с приёма на первом же кадре после блокировки.
                    manager.disconnect(recv_key, websocket)
                # Анти-телепорт (B8-3): скорость > 200 км/ч от прошлой точки → кадр игнорируем
                # (счётчик подозрительности копится), соединение честного юзера не рвём.
                if not guard.ok(float(lat), float(lng)):
                    continue
                if role == "driver":
                    # Live-ссылка близкому (B7c): последняя позиция машины → Redis (TTL ~2 мин),
                    # публичный /t/{token}/state.json читает её. В БД/лог координаты НЕ пишем.
                    livepos_set("booking", booking_id, lat, lng, payload.get("bearing"))
                if пора.только_запись:
                    continue   # записали для ссылки близким и SOS — по каналу никому из двоих не пересылаем
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
    except WebSocketDisconnect:
        return
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
            reason = "Order ended" if order.status in (
                InstantOrderStatus.done, InstantOrderStatus.cancelled, InstantOrderStatus.expired,
            ) else "Order not active"
            await websocket.close(code=1008, reason=reason)
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
    # След машины: копим локально, в базу сбрасываем редко. Кадры идут раз в ~12 секунд,
    # и писать в базу на каждый — тратить запись ради числа, нужного раз за поездку.
    prev_point: tuple | None = None
    pending_km = 0.0

    def _flush_driven() -> None:
        """Слить накопленное в заказ. Редко и обязательно при закрытии канала."""
        nonlocal pending_km
        if pending_km <= 0:
            return
        try:
            with Session(engine) as s3:
                o3 = s3.get(InstantOrder, order_id)
                # Копим ТОЛЬКО с пассажиром на борту: путь до посадки оплачен подачей.
                if o3 is not None and o3.status == InstantOrderStatus.onboard:
                    o3.driven_km = float(o3.driven_km or 0.0) + pending_km
                    s3.add(o3)
                    s3.commit()
        except Exception as e:   # noqa: BLE001 — след вторичен, канал координат важнее
            log.warning(f"[TRACK] след заказа {order_id} не записан: {type(e).__name__}")
        finally:
            pending_km = 0.0

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
                    # След: длина отрезка от прошлой точки. Анти-телепорт выше уже отсеял
                    # фейковые скачки — сюда приходит только правдоподобное движение.
                    if prev_point is not None:
                        pending_km += haversine_km(prev_point[0], prev_point[1],
                                                   float(lat), float(lng))
                    prev_point = (float(lat), float(lng))
                    if msgs % 15 == 0:
                        _flush_driven()
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
        # Сбросить след ДО отключения: связь в дороге рвётся постоянно, и без этого
        # терялись бы последние минуты пути — те самые, за которые водителю платят.
        if role == "driver":
            _flush_driven()
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
    except WebSocketDisconnect:
        return
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
            reason = "Parcel ended" if parcel.status in (
                "delivered", "canceled", "returned",
            ) else "Delivery not active"
            await websocket.close(code=1008, reason=reason)
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
