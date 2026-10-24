"""WebSocket live-позиции участников активной поездки (водитель <-> пассажир).

Приватность: поток координат разрешён ТОЛЬКО участнику брони и ТОЛЬКО когда поездка
активна (confirmed/onboard). Координаты НЕ пишем в БД — только ретранслируем другому
участнику (поток живёт лишь во время поездки). Namespace ключа = -booking_id, чтобы
не смешиваться с чатом (общий ConnectionManager + Redis; subscriber кастует int —
отрицательный ключ валиден)."""
import asyncio
import json
import math
import time
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from sqlmodel import Session

from .. import livepos            # модулем: подмена в тестах цепляет вызов
from .. import trip_wake
from ..antifraud import TrackGuard
from ..db import engine
from ..livepos import livepos_set, ride_driver_set
from ..models import Booking, BookingStatus, InstantOrder, InstantOrderStatus, ParcelDelivery, Ride
from ..security import authenticate_ws
from ..ws_guard import watch_ws_access
from ..logs import log
from ..services import MAP_FEED_KEY, haversine_km, manager
from ..visibility import LIVE_GEO_LEAD_HOURS, pre_boarding_until, trip_is_live

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
_wall = time.time             # стенные часы закрытия окна остановки — тоже отдельным именем для тестов

# Канал пассажира B (18.10.2026): как часто его сокет сам смотрит, где машина, если водитель открыл
# поездку ДРУГОЙ брони рейса и точку в канал B не шлёт. Кадры водителя идут раз в 5–10 с.
CAR_FEED_SEC = 5.0
# Канал водителя ЭТОЙ брони жив, если её точка свежее этого: тогда пассажир и так получает её
# пересылкой, второй поток не нужен.
OWN_CHANNEL_FRESH_SEC = 15.0


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
    в пользу закрытия. Так она заблокировала его накануне, а он её — уже в машине: близкие машину
    не теряют.

    Одно исключение: блокировка ВОДИТЕЛЯ до посадки закрывает канал, какой бы свежей ни была её
    ответная. Иначе: он заблокировал, отметил «Сел» (промах или нарочно), она блокирует в ответ —
    и видит его машину по своей ссылке, не садясь (проба M ревьюера). Новых таких броней нет:
    «Сел» по заблокированному им пассажиру сервер не принимает (`bookings.
    _driver_blocked_passenger_refusal`, P2-2). Но они остаются у старых броней и у отмеченных
    «Сел» до выкладки этой правки — для них и стоит проверка.

    Посадка без времени (`onboard_at` пуст) — старый пассажирский «Я сел» до PR174, такие брони
    есть только на стыке выкладки. Тот «Я сел» был возможен не раньше `DONE_EARLY_GRACE` до
    выезда: блокировка до этого момента точно была до посадки (канал закрыт), после — кто был
    раньше, не узнать, и решаем в пользу близких: будто села до блокировки.

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
    блоки = session.exec(select(Block.user_id, Block.created_at).where(or_(
        and_(Block.user_id == b.passenger_id, Block.blocked_user_id == r.driver_id),
        and_(Block.user_id == r.driver_id, Block.blocked_user_id == b.passenger_id),
    ))).all()
    if not блоки:
        return False
    # `Ride.depart_at` в модели обязателен. Раньше этого момента старый «Я сел» не ставил «в пути».
    посадка = b.onboard_at if b.onboard_at is not None else r.depart_at - DONE_EARLY_GRACE
    if any(кто == r.driver_id and когда <= посадка for кто, когда in блоки):
        return False   # его блокировка до посадки — канал закрыт, её ответная не открывает
    return посадка < max(когда for _кто, когда in блоки)   # строго «<»: совпадение — канал закрыт


def _вторая_сторона(я: int, первый, второй):
    """Кто на другом конце канала. None — если меня среди сторон уже нет."""
    if первый is not None and я == первый:
        return второй
    if второй is not None and я == второй:
        return первый
    return None


def driver_point_for_booking(session: Session, booking, ride, now=None):
    """Где машина — для брони B: её близким по ссылке, её SOS (только «в пути») и её каналу.

    Точку берём по ЛЮБОЙ подтверждённой брони рейса (`livepos.driver_points_for_ride`): водитель
    открыл поездку одного пассажира, а машина у него одна. Своя точка этой брони (её канал жив)
    важнее — это ровно то, что показывали раньше; иначе самая свежая из остальных. Но отдаём её
    только по правилам САМОЙ брони B (решение ведущего 18.10.2026, P2-А и P3 второй проверки
    безопасности):

    * бронь жива (подтверждена или «в пути») и её поездка идёт — `trip_is_live(B)`, а у брони на
      остановке — её окно (`bookings.stop_live_window`): позже обычного, не от «Выехал», и до посадки
      закрывается через 15 минут после срока «Не пришёл»;
    * блокировки — готовыми правилами канала: блокировка между ними закрывает точку, кроме случая
      «в пути» (`_пишем_без_пересылки` — её близкие машину не теряют); его блокировка до посадки
      сильнее её ответной.

    Машина у всех пассажиров рейса (24.10.2026, задача «машину видит только ОДИН пассажир»). Если
    водитель открыл поездку брони, чьё окно закрыто (Зарина в Бурибае), его точка лежит только в
    ключе рейса (`livepos.ride_driver_set`), и Айгуль с открытым окном не видела машину ни в
    приложении, ни у близких, ни в SOS. Теперь ключ рейса — последний запас, по тем же правилам
    брони B: своя точка B → самая свежая по другим броням рейса → ключ рейса, даже если он свежее
    (второе устройство водителя дома пишет в тот же ключ рейса — точки броней надёжнее).

    Не севший пассажир (проверка безопасности, находка 1): точки через ДРУГИЕ брони и ключ рейса
    подтверждённой брони — только до `visibility.pre_boarding_until` (срок «Не пришёл» + 15 минут, как
    у остановок). Иначе Бота, которая не вышла, часами видела бы, у каких домов машина высаживает
    других. Своя точка брони (её канал) — как раньше.

    Ничего не пишет и никому сам не отправляет."""
    if booking is None or ride is None:
        return None
    if booking.status not in (BookingStatus.confirmed, BookingStatus.onboard):
        return None
    if not trip_is_live(booking, ride, now):
        return None
    if _заблокированы(session, booking.passenger_id, ride.driver_id) and \
            not _пишем_без_пересылки(session, booking, ride, ride.driver_id):
        return None
    точки = livepos.driver_points_for_ride(session, ride, now, with_ride_point=True)
    своя = next((p for p in точки if p.get("booking_id") == booking.id), None)
    if своя is not None:
        return своя
    до = pre_boarding_until(booking, ride)
    if до is not None:
        from ..timeutil import utcnow   # лениво: тесты двигают `app.timeutil.utcnow`
        if (now or utcnow()) >= до:
            return None
    броней = next((p for p in точки if p.get("booking_id") is not None), None)
    return броней or next((p for p in точки if p.get("booking_id") is None), None)


def _рейс_идёт(depart_at, now=None) -> bool:
    """Рейс идёт по обычному правилу точки — с depart − LIVE_GEO_LEAD_HOURS (как у брони в начале
    маршрута). «Выехал» раньше этого срока не принимается (P2-Г), поэтому отдельно его не спрашиваем.
    Только для точки водителя по рейсу (`livepos.ride_driver_set`, A1): её читают проверка «Подъезжаю»
    и — с 24.10.2026 — `driver_point_for_booking` по правилам брони."""
    from ..timeutil import utcnow   # лениво, как в `_закрыть_в`: тесты двигают `app.timeutil.utcnow`
    return depart_at is not None and (now or utcnow()) >= depart_at - timedelta(hours=LIVE_GEO_LEAD_HOURS)


def _is_point(lat, lng) -> bool:
    """Кадр — настоящая точка на Земле: конечные числа в пределах широты и долготы.

    `NaN`/`Infinity` проходят `isinstance(float)` (`json.loads` их принимает), а сравнения с NaN
    всегда ложны. Одна такая «точка» водителя ложилась в кэш, выключала анти-телепорт до конца
    соединения (якорь NaN) и роняла ссылку близких у ВСЕХ броней рейса — NaN не сериализуется
    в JSON (приёмка безопасности PR186, 23.10.2026). Отбрасываем на входе всех трёх каналов."""
    if isinstance(lat, bool) or isinstance(lng, bool) or not isinstance(lat, (int, float)) \
            or not isinstance(lng, (int, float)):
        return False
    return math.isfinite(lat) and math.isfinite(lng) and -90 <= lat <= 90 and -180 <= lng <= 180


def _bearing(value):
    """Направление машины из кадра → конечное число 0..360 или None. Поле уходит в кэш и близким как
    есть: NaN ронял их страницу у всех броней рейса, а строка на 100 КБ уходила в каждую пересылку
    (третья проверка безопасности PR186, 23.10.2026)."""
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        return None
    return value % 360


def _закрыть_в(b, r):
    """Стенные часы, когда окно точки брони на остановке закроется до посадки (или None)."""
    if b is None or r is None or b.status != BookingStatus.confirmed or not getattr(b, "pickup_stop", None):
        return None
    from ..timeutil import utcnow
    from .bookings import stop_live_window
    окно = stop_live_window(r, b)
    if окно is None or окно[1] is None:
        return None
    return _wall() + (окно[1] - utcnow()).total_seconds()


def _epoch(ts_iso) -> "int | None":
    """Метка точки из кэша (наивный UTC ISO) → секунды эпохи, как `ts` в кадрах приложения."""
    try:
        return int(datetime.fromisoformat(str(ts_iso)).replace(tzinfo=timezone.utc).timestamp())
    except (TypeError, ValueError):
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
    Начавшаяся поездка «назад в не начавшуюся» не откатывается, как и раньше, — кроме брони на
    остановке, которую «Отменить» после «Сел» вернул в «подтверждена» до её окна (S3).

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
        # Бронь на остановке (18.10.2026): окно закрывается до посадки через 15 минут после срока
        # «Не пришёл». Стенные часы этого момента: после него «да» больше не верим без чтения базы.
        self._закрыть_в: "float | None" = None

    def наступила(self) -> bool:
        if self.кончилась:
            return False
        толкнули = trip_wake.poked_since(self._id, self._спросили_в)
        окно_истекло = self._закрыть_в is not None and _wall() >= self._закрыть_в
        if self._да and not толкнули and not окно_истекло:
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
            if self._да and not окно_истекло:
                # Толчок по идущей поездке («Подъезжаю», отмена, неявка, снятие): назад не откатываем,
                # но если сделки больше нет — канал закрывается сразу.
                self.кончилась = b is None or b.status not in (BookingStatus.confirmed, BookingStatus.onboard)
                if self.кончилась:
                    return False
                if b.status == BookingStatus.confirmed and getattr(b, "pickup_stop", None) \
                        and not trip_is_live(b, r):
                    # Бронь на остановке снова «подтверждена» вне своего окна — «Сел» → «Отменить»
                    # (промахнулся карточкой). Канал открыла только отметка «Сел»; по правилу брони
                    # её окно ещё закрыто — откатываем, иначе точки шли бы обеим сторонам за час до
                    # посадки (приёмка PR186, S3). Окно откроется по времени или «Подъезжаю».
                    self._да = False
                    self._закрыть_в = None
                    self._не_раньше = _monotonic() + PRE_TRIP_RECHECK_SEC
                    return False
                self._закрыть_в = _закрыть_в(b, r)   # «Подъезжаю» сдвигает срок «Не пришёл»
                return True
            # Окно брони на остановке истекло (не села к сроку): спрашиваем правило заново — посадка
            # откроет снова, иначе точка не идёт.
            self._да = trip_is_live(b, r)
            self._закрыть_в = _закрыть_в(b, r) if self._да else None
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
        ride_id, depart_at = ride.id, ride.depart_at   # для точки водителя по рейсу (A1; наружу — с 24.10)

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
    # Канал пассажира сам узнаёт, где машина (18.10.2026, P3 второй проверки безопасности): водитель
    # мог открыть поездку ДРУГОЙ брони рейса, и в канал этой брони его точка не идёт (задача #44).
    # Решение «слать или нет» — по правилам ЭТОЙ брони и этого пассажира (`пора` — его собственный
    # `_Пора`, с толчками): отмена или блокировка — точка перестаёт идти сразу. Водителю ничего
    # нового не уходит: это его же точка, а точку пассажирки этот поток не трогает.
    подача = asyncio.create_task(_машина_пассажиру(websocket, booking_id, пора)) if role == "passenger" else None
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
                if not _is_point(lat, lng):
                    continue
                payload["bearing"] = _bearing(payload.get("bearing"))   # дальше — только число 0..360 или None
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
                    # Окно ЭТОЙ брони закрыто (пассажирка дальше по маршруту), а рейс уже идёт:
                    # точку водителя кладём только в ключ рейса. Его читают проверка «Подъезжаю» по
                    # другим броням (A1) и — с 24.10.2026 — `driver_point_for_booking`, только по
                    # правилам ДРУГОЙ брони (её окно, блокировки, срок до посадки). Пассажирке этой
                    # брони по каналу не пересылаем, в кэш этой брони не пишем.
                    if role == "driver" and _рейс_идёт(depart_at) and guard.ok(float(lat), float(lng)):
                        ride_driver_set(ride_id, lat, lng, payload.get("bearing"))
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
                    if _рейс_идёт(depart_at):
                        ride_driver_set(ride_id, lat, lng, payload.get("bearing"))   # «Подъезжаю» (A1) и другие брони рейса
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
        if подача is not None:
            подача.cancel()     # поток машины живёт ровно столько, сколько канал
        страж.cancel()          # сторож живёт ровно столько, сколько канал
        manager.disconnect(recv_key, websocket)   # снятие регистрации при любом выходе


def _машина_для(booking_id: int, пора: "_Пора"):
    """Один шаг потока машины для пассажира: точка, которую ему можно отправить сейчас, или None.

    Порядок важен: сперва правило этой брони и этого пассажира (`пора` — окно, блокировка, отмена),
    и только потом чтение точки. Канал водителя ЭТОЙ брони жив (её точка свежая) — не дублируем:
    пассажир и так получает её пересылкой."""
    if not пора.наступила() or пора.только_запись:
        return None
    with Session(engine) as s:
        b = s.get(Booking, booking_id)
        r = s.get(Ride, b.ride_id) if b else None
        if b is None or r is None:
            return None
        своя = livepos.livepos_get("booking", booking_id)
        if своя:
            возраст = livepos._age_sec(своя, livepos.utcnow())
            if возраст is not None and возраст <= OWN_CHANNEL_FRESH_SEC:
                return None
        return driver_point_for_booking(s, b, r)


async def _машина_пассажиру(websocket: WebSocket, booking_id: int, пора: "_Пора") -> None:
    """Поток машины в канал пассажира, пока канал открыт (см. `_машина_для`)."""
    последняя = None
    try:
        while True:
            await asyncio.sleep(CAR_FEED_SEC)
            поз = _машина_для(booking_id, пора)
            if пора.кончилась:
                await websocket.close(code=1008, reason=пора.причина)
                return
            if not поз or поз.get("ts") == последняя:
                continue
            последняя = поз.get("ts")
            await websocket.send_text(json.dumps({
                "type": "loc", "role": "driver",
                "lat": поз.get("lat"), "lng": поз.get("lng"), "bearing": поз.get("bearing"),
                "ts": _epoch(поз.get("ts")),
            }))
    except asyncio.CancelledError:
        raise
    except Exception as e:  # noqa: BLE001 — поток машины вторичен: канал и его сторож живут дальше
        log.warning(f"[TRIP] поток машины для брони {booking_id} остановлен: {type(e).__name__}")


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
                if not _is_point(lat, lng):
                    continue
                payload["bearing"] = _bearing(payload.get("bearing"))   # дальше — только число 0..360 или None
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
                if not _is_point(lat, lng):
                    continue
                payload["bearing"] = _bearing(payload.get("bearing"))   # дальше — только число 0..360 или None
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
