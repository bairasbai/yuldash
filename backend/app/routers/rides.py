"""Поездки: публикация (вкл. регулярные серии), поиск, ценовой ориентир,
ближайшие по маршруту+гео, карточка поездки."""
import re
from collections import Counter
from datetime import date as date_type, datetime, timedelta
from functools import lru_cache
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field
from sqlalchemy import and_, or_, text
from sqlmodel import Session, select

from ..antifraud import moderate_open_text
from ..config import settings
from ..db import get_session
from ..errors import herr
from ..features import guard_delivery_ride
from ..flood import TOO_MANY_RIDES, guard_open_items
from ..geo import bare_name, by_exact_names, lookup, name_variants, settlements_snapshot
from ..logs import log
from ..models import (Booking, BookingStatus, DriverProfile, MedicalPartner, Ride, RideCategory,
                      RideStatus, User, UserRole)
from .. import workday as workday_mod
from ..safety_logic import (ensure_active, guard_women_only_publish)
from ..schemas import RideIn, RideOut
from .. import stop_search
from ..security import current_user, current_user_optional
from ..timeutil import client_dt_to_utc, utcnow
# Планка «завершить можно только начавшуюся поездку» — одна на все три двери
# к этому переходу (bookings.py, family.py и эта ручка).
from .bookings import DONE_EARLY_GRACE

# Сколько времени до выезда бронь должна провисеть, чтобы «не подтвердил» считалось виной
# водителя. Бронь за пять минут до отправления он мог физически не увидеть; полчаса и больше —
# уже его ответственность: пуш о новой брони приходит сразу (волна 18).
_CONFIRM_GRACE = timedelta(minutes=30)
from ..workday import local_now
from .. import trip_wake
from ..visibility import (FEED_MAX, hide_blocked, hide_health_hint, hide_suspended,
                          hide_trusted_only, is_ride_insider, may_see_ride_by_id, visible_rides)
from ..services import (
    CITY_COORDS, apply_boost_credit, boost_then_depart_order, cache_get_json, cache_set_json,
    drivers_bundle, front_seats_taken, stash_boost_credit,
    geocode_city, haversine_km, notify_map_changed, notify_route_watchers, public_ride_payload,
    public_rides_payload, push_notification, record_pickup_choice, ride_out, ride_out_with,
    rides_out,
)

router = APIRouter(tags=["rides"])

# Окно, в котором повторная публикация с тем же содержимым считается тем же самым тапом.
# Столько же, сколько у посылок (routers/parcels.py): одно правило — одно число.
_DUPLICATE_WINDOW_SEC = 60

RIDE_PAST_GRACE_HOURS = 2   # сколько часов после depart_at поездка ещё видна в выдаче (поздняя бронь / уехал впритык)

# Перф (2026-07): дефолтный потолок выдачи /rides и /rides/near. Раньше limit=None
# означал «отдать ВСЁ» — на 30k+ поездок (11k активных) каждый запрос сериализовал
# ~11 000 карточек (~400мс CPU), а 20 параллельных клиентов клали сервер в таймауты.
# Клиенту столько не нужно: выдача отсортирована (Boost → ближайший выезд),
# «показать ещё» ходит с limit/offset. Явный limit по-прежнему капится 200/страница.
DEFAULT_FEED_LIMIT = FEED_MAX   # общий потолок витрин (волна 89): один на ленту, клинику, посылки, курьеров



def _city_match(column, session, city: str, snapshot: Optional[dict] = None):
    """Условие «этот город» с учётом ОБОИХ написаний — русского и башкирского.

    Приложение подставляет название на языке человека: башкироязычный водитель публикует
    «Баймаҡ», русскоязычная пассажирка ищет «Баймак». Сравнение строк как есть их не сводило,
    и половина жителей района не видела поездки другой половины (аудит 2026-08-08, волна 92).
    """
    variants = name_variants(session, city, snapshot)
    if not variants:
        return None
    # autoescape: «%» и «_» в имени — буквы, а не шаблон LIKE (иначе запрос «%» находил всё подряд).
    conds = [column.contains(v, autoescape=True) for v in variants]
    return conds[0] if len(conds) == 1 else or_(*conds)


def _route_condition(fv: list, tv: list, stops_from: bool, stops_to: bool, *, pairs: bool):
    """Широкое SQL-условие «Откуда → Куда» с остановками (18.10.2026).

    Общая лента (`pairs=True`): `(F∧T) ∨ (WF∧T) ∨ (F∧WT) ∨ WW`, где F/T — начало/конец маршрута,
    WF/WT — подстрока в остановках, WW — «откуда…куда» в остановках именно в этом порядке. В
    «Ближайших» (`pairs=False`) — без попарных LIKE: `(F ∨ WF) ∧ (T ∨ WT)`, порядок проверяет
    `route_fit`. Запрос с цифрами по остановкам не ищем. None — фильтра нет."""
    F = stop_search.column_match(Ride.from_city, fv) if fv else None
    T = stop_search.column_match(Ride.to_city, tv) if tv else None
    WF = stop_search.column_match(Ride.waypoints, fv) if fv and stops_from else None
    WT = stop_search.column_match(Ride.waypoints, tv) if tv and stops_to else None
    if fv and tv:
        if pairs:
            parts = [and_(F, T)]
            if WF is not None:
                parts.append(and_(WF, T))
            if WT is not None:
                parts.append(and_(F, WT))
            if WF is not None and WT is not None:
                parts.append(stop_search.pair_match(Ride.waypoints, fv, tv))
            return or_(*parts)
        return and_(or_(*[c for c in (F, WF) if c is not None]), or_(*[c for c in (T, WT) if c is not None]))
    if fv:
        return or_(*[c for c in (F, WF) if c is not None])
    if tv:
        return or_(*[c for c in (T, WT) if c is not None])
    return None


def _guard_route_cities(from_city: str, to_city: str) -> None:
    """Маршрут должен быть похож на маршрут: два РАЗНЫХ места, и у каждого есть название.

    Проверка стояла в расписании водителя и не стояла при публикации поездки, хотя ошибается
    человек одинаково: выбрал один и тот же город в обоих полях или вставил в поле смайлики.
    Первое отправляет пассажира «из Сибая в Сибай», второе засоряет ленту всему району
    (аудит 2026-08-08, волна 108).
    """
    from ..geo import bare_name, fold   # локальный импорт: geo тянет services на верхнем уровне
    frm = bare_name((from_city or "").strip())
    to = bare_name((to_city or "").strip())
    if not re.search(r"[^\W\d_]", frm, re.UNICODE) or not re.search(r"[^\W\d_]", to, re.UNICODE):
        raise herr(400, "Название города — буквами, пожалуйста",
                   "Ҡала исемен хәрефтәр менән яҙ, зинһар")
    if fold(frm) == fold(to):
        raise herr(400, "Откуда и куда — разные места. Проверь маршрут",
                   "Ҡайҙан һәм ҡайҙа — төрлө урын. Юлды ҡара")

def _clean_publish_stops(waypoints: str) -> str:
    """Остановки при публикации (вторая проверка безопасности, P2-3 и P3, 18.10.2026).

    Каждая остановка — название села: буквы (русские, башкирские, латиница), пробел, дефис, точка,
    скобки; до 60 знаков; без цифр. Номер дома, телефон или адрес так не пройдут — а остановку видит
    весь район и выбирает пассажир. Списка «запретных слов» нет нарочно: «Улу-Теляк», «Кваркено»,
    «Домашкино» — честные сёла. Остановок не больше четырёх, как в приложении и на сайте.
    Возвращает нормализованную строку «А | Б» (пустые части выброшены)."""
    from ..ride_stops import MAX_STOP_LEN, MAX_STOPS, split_raw, stop_text_ok
    parts = split_raw(waypoints)
    if len(parts) > MAX_STOPS:
        raise HTTPException(400, {
            "ru": f"Остановок по пути — не больше {MAX_STOPS}. Оставь главные села, остальное — в комментарии.",
            "ba": f"Юл ыңғайындағы туҡталыштар {MAX_STOPS}-тән артмаһын. Төп ауылдарҙы ҡалдыр, ҡалғанын — аңлатмаға.",
            "code": "stops_too_many"})
    for p in parts:
        if not stop_text_ok(p):
            raise HTTPException(400, {
                "ru": (f"Остановка «{p[:MAX_STOP_LEN]}» — напиши только название села или города, буквами, "
                       f"без цифр и адреса (до {MAX_STOP_LEN} знаков). Точное место договоритесь в чате."),
                "ba": (f"«{p[:MAX_STOP_LEN]}» туҡталышы — ауыл йәки ҡала исемен генә хәрефтәр менән яҙ, "
                       f"һандарһыҙ һәм адресһыҙ ({MAX_STOP_LEN} билдәгә тиклем). Теүәл урынды чатта килешегеҙ."),
                "code": "stop_invalid"})
    return " | ".join(parts)


def _date_bounds(date: Optional[date_type]):
    """F4: границы суток для фильтра «когда едем» (date=YYYY-MM-DD → [00:00, +1день)).

    Сутки — МЕСТНЫЕ, башкирские. Раньше границы считались в UTC, и получалось смещение
    на пять часов: рейс «15 августа, 4 утра» лежит в базе как 14 августа 23:00 UTC, и по
    запросу «покажи на 15-е» он не находился — зато находился на 14-е (аудит 2026-08-08,
    волна 79).

    Бьёт это ровно по самому частому сельскому рейсу. Из района в Уфу выезжают в четыре-пять
    утра, чтобы успеть к открытию больниц и учреждений. Такие поездки были невидимы для тех,
    кто выбирал в календаре правильный день, а водитель не понимал, почему нет заявок.

    Сдвиг берём из настроек (`local_tz_offset_hours`), как в долгах, сводке и учёте смены.
    """
    if date is None:
        return None
    start = datetime(date.year, date.month, date.day) - timedelta(hours=settings.local_tz_offset_hours)
    return start, start + timedelta(days=1)


def _recent_twin_ride(session: Session, driver_id: int, body: RideIn):
    """Только что опубликованная ПОЛНОСТЬЮ такая же поездка — значит это тот же самый тап.

    Сравниваем ВЕСЬ смысл объявления, а не «маршрут и время»: первая версия гарда сверяла
    подмножество полей и схлопнула две разные поездки — с остановками и без (поймано тестом
    `test_ride_waypoints_flows_through`). Совпасть должно всё: изменил хоть остановку, хоть
    комментарий — это новая поездка, и терять её нельзя.
    """
    fields = body.model_dump(exclude={"pickup_point_id"})
    recent = session.exec(
        select(Ride).where(
            Ride.driver_id == driver_id,
            Ride.status == RideStatus.active,
            Ride.created_at >= utcnow() - timedelta(seconds=_DUPLICATE_WINDOW_SEC),
        ).order_by(Ride.id.desc()).limit(20)
    ).all()
    for r in recent:
        if all(getattr(r, k, None) == v for k, v in fields.items()):
            return r
    return None


SEATS_MAX = 8   # минивэн; больше в попутку не берут
SEATS_CHANGED = ("места", "урындар")   # пункт «что изменилось» в уведомлении о правке рейса


def seat_cap(back_max_two: bool, front_seat_available: bool) -> int:
    """Сколько пассажирских мест можно предложить при таких опциях.

    «Сзади максимум двое» — обещание пассажирам, что третьего на заднем ряду не будет.
    Тогда мест не больше 3: двое сзади и одно спереди. А если спереди едет свой человек
    (переднее не предлагается) — только 2, иначе обещание нарушится уже при третьей брони.
    Без этой опции потолок обычный — 8 (минивэн)."""
    if not back_max_two:
        return SEATS_MAX
    return 2 + (1 if front_seat_available else 0)


@router.post("/rides", response_model=Ride)
def create_ride(body: RideIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    ensure_active(session, user.id)   # пауза лестницы «Справедливости» (§2) блокирует публикацию
    guard_delivery_ride(body.category)
    # Отдых водителя (волна 2, §8): во время блока такси разрешена ОДНА попутка «домой»
    # (первая публикация проходит и помечает return_ride_used, вторая → мягкий 403).
    # ВНЕ блока попутка не ограничена вообще — guard мгновенно пропускает.
    workday_mod.guard_publish_ride(session, user.id)
    # Анти-флуд: одновременно активных поездок у одного водителя — не больше потолка. Раньше
    # потолка не было вовсе, и один человек забивал ленту небольшого района восемьюдесятью
    # публикациями подряд (аудит 2026-08-06). Закрыл поездку → место освободилось.
    guard_open_items(session, Ride.id, Ride.driver_id == user.id, Ride.status == RideStatus.active,
                     limit=settings.flood_active_rides_max,
                     ru=TOO_MANY_RIDES[0], ba=TOO_MANY_RIDES[1])
    # Потолка на ТЕМП здесь намеренно НЕТ (волна 49). У заявок и посылок он есть, потому что
    # каждая будит Александра сообщением в Telegram, а у поездки такого канала нет: её видит
    # только лента, и там уже стоит потолок выше. А цена ошибки была бы настоящей — «регулярная
    # поездка» создаёт сразу пять рейсов одним нажатием, и водитель, публикующий серию по
    # понедельникам и по пятницам, получил бы отказ на второй.
    # «Только женщины» ставит поездке женщина за рулём. Иначе отметка превращается в приманку:
    # мужчина за рулём зовёт в машину только женщин, и та едет, думая, что проверено
    # (решение Александра, 2026-08-08). Пол — по желанию, поэтому «не указан» получает не
    # отказ, а просьбу заполнить профиль (см. guard_women_only).
    if getattr(body, "women_only", False):
        # Не просто «женщина по профилю», а подтверждённая модератором (волна 168): иначе
        # отметка становится приманкой — мужчина ставит себе «женщина» и зовёт в машину
        # только женщин, а они едут, думая, что кто-то это проверил.
        guard_women_only_publish(session, user)
    # Санити-границы (анти-мусор в ленте): мест 1..8, цена 0..100000 ₽. Клампим, а не падаем.
    body.seats_total = max(1, min(SEATS_MAX, body.seats_total))
    # «Сзади максимум двое»: больше 3 мест (или 2, если переднее занято своим) не бывает.
    # Клампим, как и диапазон выше: форма уже показала это водителю подсказкой.
    body.seats_total = min(body.seats_total, seat_cap(body.back_max_two, body.front_seat_available))
    body.price = max(0, min(100_000, body.price))
    # Города: обрезаем пробелы и требуем непустые. Клампить тут нечего — «поездка из ниоткуда»
    # не чинится подстановкой. Проверка ввода 2026-08-05 показала, что строка из пробелов
    # проходила насквозь и в ленте у всех появлялась поездка с пустой строкой вместо города.
    body.from_city = (body.from_city or "").strip()
    body.to_city = (body.to_city or "").strip()
    if not body.from_city or not body.to_city:
        raise HTTPException(400, {"ru": "Укажи, откуда и куда едешь",
                                  "ba": "Ҡайҙан һәм ҡайҙа барғаныңды күрһәт"})
    # Время выезда → наивный UTC. Без этого уфимские 10:00 ложились в БД как 10:00 UTC и
    # поездка «уезжала» на 5 часов (разбор №2). Старые версии приложения шлют время без пояса —
    # трактуем его как местное, поэтому они чинятся без обновления на телефоне.
    # Преобразуем РОВНО ОДИН раз: второй прогон сдвинул бы время ещё на пояс, то есть на 10 часов.
    body.depart_at = client_dt_to_utc(body.depart_at)
    # Время выезда в прошлом. Такую поездку никто не сможет взять, но висеть в ленте она будет
    # до ночной уборки — и засоряет и поиск, и карту. Небольшой допуск оставлен нарочно:
    # часы на телефоне врут на минуты, а «выезжаю прямо сейчас» — обычный сценарий.
    if body.depart_at < utcnow() - timedelta(minutes=30):
        raise HTTPException(400, {"ru": "Время выезда уже прошло",
                                  "ba": "Сығыу ваҡыты үтеп киткән"})
    # Комментарий к объявлению — открытое поле: его видит весь район, как и комментарий заявки.
    # Заявка пассажира проверялась, объявление водителя — нет (аудит 2026-08-06), хотя это
    # ровно тот же текст с другой стороны. check_contact=False: у попуток обмен номерами —
    # норма и суть «между своими», комиссии тут нет. Ловим мат и фишинг («переведи предоплату»).
    # Города проверяем так же, как в расписании водителя: «Сибай → Сибай» — это опечатка,
    # а название без единой буквы («🚗🚗», «...») превращает ленту в мусор (волна 108).
    _guard_route_cities(body.from_city, body.to_city)
    moderate_open_text(body.comment, user.id, check_contact=False, place="ride_comment", session=session)
    # «Где встречаемся» — такое же открытое поле, как комментарий, и его тоже видит весь
    # район. Проверки тут не было вовсе. Правила те же, что у комментария попутки:
    # ловим мат и фишинг, телефон НЕ помечаем — обмен номерами между соседями это норма
    # и суть «между своими», комиссии в попутках нет.
    moderate_open_text(body.pickup, user.id, check_contact=False, place="pickup", session=session)
    # Остановки по пути — тоже открытое поле (их видит весь район, по ним выбирают, где садиться):
    # строгая проверка формы и та же модерация, что у «Где встречаемся» (18.10.2026).
    body.waypoints = _clean_publish_stops(body.waypoints)
    moderate_open_text(body.waypoints, user.id, check_contact=False, place="waypoints", session=session)
    # F22: клиника-назначение (опц.). Если указана — проверяем, что она есть и активна
    # (чтобы не осталось битой ссылки). Это ТОЛЬКО точка назначения, без мед.данных.
    if body.partner_id is not None:
        partner = session.get(MedicalPartner, body.partner_id)
        if not partner or not partner.active:
            raise herr(400, "Клиника не найдена", "Клиника табылманы")
    # Геокодим концы маршрута (для радиус-поиска: PostGIS на проде / haversine иначе).
    frm = geocode_city(body.from_city) or (None, None)
    to = geocode_city(body.to_city) or (None, None)
    geo = {"from_lat": frm[0], "from_lng": frm[1], "to_lat": to[0], "to_lng": to[1]}
    # F14: выбрана известная точка сбора из справочника → привязываем её координаты/название к поездке.
    point_id = body.pickup_point_id
    if point_id:
        from ..models import PickupPoint
        pt = session.get(PickupPoint, point_id)
        if pt is not None:
            if not body.pickup:
                body.pickup = pt.title_ru
            if body.pickup_lat is None:
                body.pickup_lat = pt.lat
            if body.pickup_lng is None:
                body.pickup_lng = pt.lng
    # Двойная публикация (аудит 2026-08-06). У посылок такой гард поставили 2026-08-03 с точным
    # доводом: «лаг сети или авто-ретрай запроса — и у человека две одинаковые заявки». У поездок
    # его не поставили, хотя ApiClient повторяет POST при обрыве связи, а ответ сервера может
    # опоздать дольше таймаута. Цена дубля тут выше, чем мусор в ленте: пассажиры бронируют
    # РАЗНЫЕ копии одного рейса, и водитель получает две брони на одну машину.
    # Дедуп по СОДЕРЖИМОМУ в коротком окне: тот же водитель, маршрут, время, цена и места —
    # это тот же самый тап. «Исправил и опубликовал заново» отличается хотя бы одним полем
    # и проходит как новая поездка. Лочим строку водителя: иначе два запроса пройдут SELECT
    # одновременно и оба вставят.
    session.exec(select(User).where(User.id == user.id).with_for_update()).first()
    twin = _recent_twin_ride(session, user.id, body)
    if twin is not None:
        return twin

    # (время выезда уже приведено к UTC выше, вместе с проверкой «не в прошлом»)
    # pickup_point_id — не колонка Ride (только сигнал привязки), исключаем из дампа.
    dump = body.model_dump(exclude={"pickup_point_id"})
    ride = Ride(driver_id=user.id, seats_left=body.seats_total, **dump, **geo)
    session.add(ride)
    # Регулярная поездка: сразу создаём ближайшие 4 рейса серии (реальные, бронируемые).
    if body.recurrence and body.recurrence != "none":
        step = {"weekly": timedelta(weeks=1)}.get(body.recurrence, timedelta(days=1))
        dt = body.depart_at
        made = 0
        guard = 0
        while made < 4 and guard < 40:
            guard += 1
            dt = dt + step
            # День недели считаем по МЕСТНОМУ времени, а не по UTC. Рейс на 04:00 по Уфе — это
            # 23:00 предыдущих суток по UTC, и наивная проверка ставила бы «по будням» рейс
            # в субботу и теряла понедельник. Утренние рейсы на Уфу — самый частый случай,
            # так что промах был бы не редким исключением, а нормой (регресс перевода на UTC,
            # найден независимой проверкой 2026-08-03).
            if body.recurrence == "weekdays" and local_now(dt).weekday() >= 5:   # пропускаем сб/вс
                continue
            session.add(Ride(driver_id=user.id, seats_left=body.seats_total, **{**dump, "depart_at": dt}, **geo))
            made += 1
    # Перенесённое с отменённой поездки поднятие достаётся этой — молча и сразу.
    if apply_boost_credit(user, ride, utcnow()):
        session.add(user)
        session.add(ride)
    session.commit()
    session.refresh(ride)
    # F14: пополняем справочник ориентиров реально выбранной точкой (usage_count / новый ориентир).
    record_pickup_choice(
        session, city=ride.from_city, point_id=point_id,
        title_ru=ride.pickup, lat=ride.pickup_lat, lng=ride.pickup_lng,
    )
    session.refresh(ride)   # record_pickup_choice коммитит → объект ride «протух», обновляем перед сериализацией
    notify_map_changed()   # новая поездка → пины на карте у всех обновятся live (не дожидаясь 25с-опроса)
    notify_route_watchers(session, ride)   # «карауль поездку»: оповещаем подходящих сторожей (push + запись)
    # Рассылка пишет уведомления и коммитит — после этого объект поездки «протухает», и в ответ
    # уходил ПУСТОЙ JSON. Ловилось только там, где на маршрут кто-то подписан: без подписчиков
    # рассылка ничего не пишет и не коммитит, поэтому в тестах и на пустой базе всё выглядело
    # исправно (аудит 2026-08-08, волна 78). Для водителя это выглядело как сбой публикации:
    # поездка на сервере создана, а приложению не с чем работать — и он жмёт «Опубликовать» снова.
    # Тот же рефреш строкой выше стоит после записи ориентира; здесь его просто забыли.
    session.refresh(ride)
    return ride


class RideEditIn(BaseModel):
    """F3: правка своей поездки. Все поля опциональны — меняется только присланное."""
    price: Optional[int] = Field(None, ge=0, le=100_000)
    comment: Optional[str] = Field(None, max_length=2000)
    depart_at: Optional[datetime] = None
    seats_total: Optional[int] = Field(None, ge=1, le=8)
    back_max_two: Optional[bool] = None          # «сзади максимум двое»
    front_seat_available: Optional[bool] = None  # «переднее место свободно»


@router.patch("/rides/{ride_id}", response_model=RideOut)
@router.post("/rides/{ride_id}/edit", response_model=RideOut)   # алиас: Android HttpURLConnection не умеет PATCH
def edit_ride(ride_id: int, body: RideEditIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """F3: водитель правит опубликованную поездку (опечатка в цене/времени была неисправимой).

    Правила честности перед пассажирами:
    - без живых броней — можно всё (цена/комментарий/время/места);
    - есть живые брони — только комментарий и цену ВНИЗ (условия «купленного» не ухудшаем);
      время/места при бронях менять нельзя (409) — отмени рейс (пассажиры получат push) и создай новый.
    Пассажиров с бронью уведомляем «Поездка обновлена» — пуш и запись в Центре уведомлений, на двух языках."""
    ride = session.exec(select(Ride).where(Ride.id == ride_id).with_for_update()).first()
    if not ride:
        raise herr(404, "Поездка не найдена", "Сәфәр табылманы")
    if ride.driver_id != user.id:
        raise herr(403, "Это не твоя поездка", "Был һинең сәфәрең түгел")
    if ride.status != RideStatus.active:
        raise herr(400, "Менять можно только активную поездку", "Тик актив сәфәрҙе генә үҙгәртеп була")
    live = session.exec(select(Booking).where(
        Booking.ride_id == ride_id,
        Booking.status.in_((BookingStatus.pending, BookingStatus.confirmed, BookingStatus.onboard)),
    )).all()
    booked_seats = sum(b.seats for b in live)
    changed: list[tuple[str, str]] = []   # что изменилось: (по-русски, по-башкирски)
    if body.price is not None and body.price != ride.price:
        if live and body.price > ride.price:
            raise herr(409, "С активными бронями цену можно только снижать", "Актив брондар менән хаҡты кәметергә генә була")
        ride.price = body.price
        changed.append(("цена", "хаҡ"))
        # Скидку доводим до тех, кому она сделана (волна 119). Раньше правка меняла только
        # цену объявления: водитель по-соседски снижал с 500 до 300, пассажирке приходил пуш
        # «цена изменена», а в её брони и в квитанции оставалось 500. У подъезда спор,
        # и единственная запись, заведённая ради таких споров, показывала старую цифру.
        for b in live:
            b.price = body.price * b.seats
            # И в договорённость об оплате: квитанция и карта берут именно её. Без этого
            # скидка доходила до цены брони, а списали бы прежнюю сумму (ревью 2026-10-13).
            if b.pay_amount is not None and b.pay_amount > b.price:
                b.pay_amount = b.price
            session.add(b)
    if body.comment is not None and body.comment != ride.comment:
        moderate_open_text(body.comment, user.id, check_contact=False, place="ride_comment", ref_id=ride.id, session=session)   # правка — тот же путь, что публикация
        ride.comment = body.comment
        changed.append(("комментарий", "аңлатма"))
    if body.depart_at is not None:
        body.depart_at = client_dt_to_utc(body.depart_at)
        # Та же проверка, что и при публикации: правкой нельзя увести поездку в прошлое.
        # Иначе она останется активной в ленте, но взять её уже никто не сможет.
        if body.depart_at < utcnow() - timedelta(minutes=30):
            raise herr(400, "Время выезда уже прошло", "Сығыу ваҡыты үтеп киткән")
    if body.depart_at is not None and body.depart_at != ride.depart_at:
        if live:
            raise herr(409, "С активными бронями время не меняют — отмените рейс и создайте новый", "Актив брондар менән ваҡытты үҙгәртеп булмай — рейсты кире алып, яңыһын төҙө")
        ride.depart_at = body.depart_at
        changed.append(("время", "ваҡыт"))
    # Опции мест. Обещание, на которое уже рассчитывают пассажиры, не забираем: переднее,
    # которое кто-то попросил, не закрываем, и «сзади двое» при живых бронях не снимаем.
    # Улучшить условия (открыть переднее, пообещать «сзади двое») можно всегда.
    new_front = ride.front_seat_available if body.front_seat_available is None else body.front_seat_available
    new_back = ride.back_max_two if body.back_max_two is None else body.back_max_two
    if new_front != ride.front_seat_available:
        if not new_front and any(b.front_seat for b in live):
            raise herr(409, "Переднее место уже обещано пассажиру — договорись с ним в чате",
                       "Алғы урын юлсыға вәғәҙә ителгән — уның менән чатта һөйләш")
        ride.front_seat_available = new_front
        changed.append(("переднее место", "алғы урын"))
    if new_back != ride.back_max_two:
        if not new_back and live:
            raise herr(409, "Пассажиры уже рассчитывают, что сзади будет двое — это не отменить",
                       "Юлсылар артта икәү генә булыр тип иҫәпләй — быны кире алып булмай")
        ride.back_max_two = new_back
        changed.append(("места сзади", "арттағы урындар"))
    cap = seat_cap(new_back, new_front)
    # Прислали то же число мест (клиент шлёт форму целиком) — это не правка мест: потолок ниже
    # срежет лишнее сам, и при бронях это не должно давать 409 (ревью Opus 2026-10-14).
    if (body.seats_total is not None and body.seats_total != ride.seats_total
            and min(body.seats_total, cap) != ride.seats_total):
        if live:
            raise herr(409, "С активными бронями число мест не меняют", "Актив брондар менән урын һанын үҙгәртеп булмай")
        ride.seats_total = min(body.seats_total, cap)
        ride.seats_left = ride.seats_total - booked_seats   # броней нет → просто новое число мест
        changed.append(SEATS_CHANGED)
    if ride.seats_total > cap:
        # Опцию включили, а мест в объявлении больше, чем она позволяет: срезаем до потолка.
        # Уже занятые места не трогаем — если их больше потолка, обещание не выполнить.
        if booked_seats > cap:
            raise herr(409, "Мест уже забронировано больше, чем поместится при этих условиях",
                       "Был шарттарҙа һыйғандан күберәк урын брондалған инде")
        ride.seats_total = cap
        ride.seats_left = cap - booked_seats
        if SEATS_CHANGED not in changed:
            changed.append(SEATS_CHANGED)
    if not changed:
        return public_ride_payload(ride_out(ride, session), full=True)   # нечего менять; свой рейс — свой текст
    session.add(ride)
    session.commit()
    session.refresh(ride)
    notify_map_changed()   # карточка на карте/в ленте обновится live
    # Уведомления — после commit. Через push_notification, как отмена рейса: пуш уходит на языке
    # человека, а запись остаётся в Центре уведомлений. Раньше был голый пуш только по-русски:
    # пассажирка с башкирским в приложении читала русский текст, а не дошёл пуш (телефон выключен,
    # нет сети) — о новой цене или времени она узнавала только у машины (аудит BlaBlaCar, 17.10.2026).
    route = f"{ride.from_city} → {ride.to_city}"
    what_ru = ", ".join(ru for ru, _ba in changed)
    what_ba = ", ".join(ba for _ru, ba in changed)
    for b in live:
        push_notification(
            session, b.passenger_id, "booking",
            "Поездка обновлена", "Сәфәр яңыртылды",
            f"{route}: изменено — {what_ru}. Загляни в детали.",
            f"{route}: үҙгәртелде — {what_ba}. Ентекләп ҡара.",
            ref_kind="booking", ref_id=b.id,
        )
    return public_ride_payload(ride_out(ride, session), full=True)  # свой рейс — свой текст


@router.get("/rides", response_model=List[RideOut])
def search_rides(
    from_city: Optional[str] = Query(None, max_length=stop_search.NAME_MAX),
    to_city: Optional[str] = Query(None, max_length=stop_search.NAME_MAX),
    category: Optional[RideCategory] = None,
    pets_allowed: Optional[bool] = None,
    child_seat: Optional[bool] = None,
    women_only: Optional[bool] = None,
    baggage: Optional[bool] = None,
    date: Optional[date_type] = None,   # F4: «когда едем» — только поездки этого дня (YYYY-MM-DD)
    limit: Optional[int] = None,        # пагинация (опц., обратносовместимо: None = все)
    offset: int = 0,
    user: Optional[User] = Depends(current_user_optional),   # есть токен → прячем заблокированных
    session: Session = Depends(get_session),
):
    # Горячий путь: дефолтный вызов без фильтров (его шлют ВСЕ на карте/вкладке поездок).
    # Кешируем в Redis на 20с → снимаем нагрузку с БД при наплыве. Фильтрованные запросы (реже) — мимо кеша.
    # Кеш хранит топ-DEFAULT_FEED_LIMIT (v2; v1 хранил всё и на 11k поездок стоил ~400мс/запрос);
    # фильтр заблокированных — поверх, per-user (кеш не портим).
    no_filter = (
        not any([from_city, to_city, category, pets_allowed, child_seat, women_only, baggage, date])
        and limit is None
    )
    if no_filter:
        cached = cache_get_json("rides:active:v2")
        if cached is not None:
            return visible_rides(public_rides_payload(cached), user, session)

    # Не показываем УЖЕ УЕХАВШИЕ поездки (аудит 2026-07-04: у поездки не было отсева по времени →
    # вчерашние висели в ленте). Грейс 2ч: поездка «только что уехала»/бронируют впритык — ещё видна.
    q = select(Ride).where(
        Ride.status == RideStatus.active,
        Ride.depart_at >= utcnow() - timedelta(hours=RIDE_PAST_GRACE_HOURS),
        # Мест не осталось — в ленте показывать нечего (аудит 2026-08-07). Соседняя выдача
        # «Ближайшие» (`/rides/near`, ниже) этот отсев делает с самого начала, а общая лента —
        # нет. Заметнее всего это на поездке, созданной из принятого отклика: она рождается
        # сразу занятой под одного пассажира и всё равно висела в списке. Человек открывает
        # карточку, жмёт «Забронировать» и получает отказ — выглядит как поломка приложения.
        Ride.seats_left > 0,
    )
    # «Откуда»/«Куда» — и по остановкам (18.10.2026, `stop_search`): широкое условие в SQL, точная
    # проверка порядка и допустимых остановок — `route_fit` после выборки.
    entry = settlements_snapshot(session)
    fv = stop_search.variants(session, from_city, entry)
    tv = stop_search.variants(session, to_city, entry)
    stops_from = stop_search.stop_searchable(from_city)
    stops_to = stop_search.stop_searchable(to_city)
    route_cond = _route_condition(fv, tv, stops_from, stops_to, pairs=True)
    if route_cond is not None:
        q = q.where(route_cond)
    if category:
        # Анониму фильтр «в больницу» не даём: затирать связку в ответе бесполезно, если
        # выборку можно получить самим запросом — человек и так знает, что просил (волна 22).
        if not (category == RideCategory.hospital and user is None):
            q = q.where(Ride.category == category)
    if pets_allowed:
        q = q.where(Ride.pets_allowed == True)  # noqa: E712
    if child_seat:
        q = q.where(Ride.child_seat == True)  # noqa: E712
    if women_only:
        # F9: фильтр «только женщины» показывает и поездки с флагом women_only,
        # И поездки, где сама водитель — женщина. Здесь сошлись две ветки, и нужны обе
        # половины: пол читаем у ЧЕЛОВЕКА (`User.gender`) — он туда переехал, и профиль
        # водителя больше не пишется; а подтверждение модератором (`gender_verified`)
        # обязательно — без него любой мужчина ставил себе «женщина» и попадал в эту выдачу,
        # а открывают её именно те, кому небезопасно ехать с незнакомым мужчиной.
        # Лучше пустой список, чем непроверенный водитель.
        # Обе половины требуют ПОДТВЕРЖДЕНИЯ модератором (волна 168). Раньше первая половина
        # («у поездки стоит отметка») пропускала без проверки — и мужчина, поставивший себе
        # «женщина» в профиле, попадал в эту самую выдачу вместе с отметкой. Комментарий рядом
        # объяснял, почему подтверждение обязательно, но стояло оно только на второй половине.
        q = (
            q.outerjoin(User, User.id == Ride.driver_id)
            .outerjoin(DriverProfile, DriverProfile.user_id == Ride.driver_id)
            .where(
                (User.gender == "female")
                & (DriverProfile.gender_verified == True)  # noqa: E712
            )
        )
    if baggage:
        q = q.where(Ride.baggage == True)  # noqa: E712
    bounds = _date_bounds(date)
    if bounds:
        q = q.where(Ride.depart_at >= bounds[0], Ride.depart_at < bounds[1])
    q = q.order_by(*boost_then_depart_order())   # поднятые (Boost) — первыми
    # Потолок всегда: явный limit капится 200/страница, без limit — DEFAULT_FEED_LIMIT.
    eff_limit = min(max(1, limit), 200) if limit is not None else DEFAULT_FEED_LIMIT
    fits: dict = {}
    if route_cond is not None:
        # По лёгким колонкам — как «Ближайшие»: точный `route_fit` до страницы, иначе подстрочные
        # совпадения в остановках съедали бы место на странице, а «Показать ещё» врал бы.
        light = session.execute(q.with_only_columns(
            Ride.id, Ride.from_city, Ride.to_city, Ride.waypoints)).all()
        page_ids: list = []
        for row in light:
            fit = stop_search.route_fit(row, fv or None, tv or None, entry,
                                        from_stops=stops_from, to_stops=stops_to)
            if fit.ok:
                fits[row.id] = fit
                page_ids.append(row.id)
        page_ids = page_ids[max(0, offset):max(0, offset) + eff_limit]
        by_id = {r.id: r for r in session.exec(select(Ride).where(Ride.id.in_(page_ids))).all()} if page_ids else {}
        rides = [by_id[i] for i in page_ids if i in by_id]
    else:
        q = q.offset(max(0, offset)).limit(eff_limit)
        rides = session.exec(q).all()
    out = rides_out(rides, session)
    if fits:
        out = [r.model_copy(update={"matched_stop": fits[r.id].matched_stop,
                                    "matched_from_start": fits[r.id].matched_from_start}) for r in out]
    public_out = public_rides_payload(out)
    if no_filter:
        cache_set_json("rides:active:v2", [r.model_dump(mode="json") for r in public_out], 20)
    return visible_rides(public_out, user, session)


@lru_cache(maxsize=512)
def _route_distance_km(from_city: Optional[str], to_city: Optional[str]) -> Optional[float]:
    """Расстояние между городами маршрута по прямой (haversine), км. Координаты берём
    из геокодера (известные города БашРТ — бесплатно из справочника, иначе Яндекс). Нет координат
    хотя бы одного конца → None (не завышаем, честно «не знаем»).
    lru_cache: подсказка цены дёргается при вводе (debounce), одинаковый маршрут не геокодим повторно."""
    if not from_city or not to_city:
        return None
    f = geocode_city(from_city)
    t = geocode_city(to_city)
    if not f or not t:
        return None
    return round(haversine_km(f[0], f[1], t[0], t[1]), 1)


@router.get("/rides/price_hint")
def price_hint(
    from_city: Optional[str] = None,
    to_city: Optional[str] = None,
    session: Session = Depends(get_session),
):
    """Ориентир цены по маршруту: средняя цена прошлых поездок (price>0) + честная оценка
    бензина на весь маршрут по километражу. Подсказка водителю, не навязываем.

    Поля distance_km / fuel_estimate_kop = null, если координаты городов неизвестны
    (без краша) — коэффициенты бензина в config, уточнит Александр."""
    q = select(Ride.price).where(Ride.price > 0)
    if from_city:
        cond = _city_match(Ride.from_city, session, from_city)
        if cond is not None:
            q = q.where(cond)
    if to_city:
        cond = _city_match(Ride.to_city, session, to_city)
        if cond is not None:
            q = q.where(cond)
    prices = [p for p in session.exec(q).all() if p and p > 0]

    # Бензин на весь маршрут: км × (расход/100) × цена_литра → ₽ → копейки.
    distance_km = _route_distance_km(from_city, to_city)
    fuel_estimate_kop = None
    if distance_km is not None:
        fuel_rub = distance_km * (settings.fuel_consumption_l_per_100km / 100.0) * settings.fuel_price_rub_per_liter
        fuel_estimate_kop = round(fuel_rub * 100)

    avg = round(sum(prices) / len(prices)) if prices else 0
    return {
        "avg": avg,
        "count": len(prices),
        "distance_km": distance_km,
        "fuel_estimate_kop": fuel_estimate_kop,
    }


# G7 «Диагностика: почему мало откликов» — мягкие ДОБРЫЕ подсказки водителю по его поездке.
# Не наказание и не «диагноз», а советы уровня доброго соседа. Читаем реальные сигналы
# (нет фото / нет проверки / цена выше средней по маршруту / нет деталей). Read-only.
_TIP_PRICE_MIN_SAMPLE = 3      # меньше поездок по маршруту — про цену молчим (данных мало, шумно)
_TIP_PRICE_OVER_RATIO = 1.15   # цена выше средней в 1.15× → мягко предложить снизить


def _route_avg_price(session: Session, from_city: str, to_city: str) -> dict:
    """Средняя цена поездок по маршруту (price>0) + размер выборки — для совета по цене (G7).
    Только живые/состоявшиеся поездки за 90 дней и не больше 500 свежих: отменённые и древние
    цены не должны тащить «среднюю», а contains-скан всей таблицы всех времён — не расти вечно."""
    q = select(Ride.price).where(
        Ride.price > 0,
        Ride.status.in_([RideStatus.active, RideStatus.done]),
        Ride.created_at > utcnow() - timedelta(days=90),
    )
    if from_city:
        cond = _city_match(Ride.from_city, session, from_city)
        if cond is not None:
            q = q.where(cond)
    if to_city:
        cond = _city_match(Ride.to_city, session, to_city)
        if cond is not None:
            q = q.where(cond)
    prices = [p for p in session.exec(q.order_by(Ride.id.desc()).limit(500)).all() if p and p > 0]
    return {"avg": round(sum(prices) / len(prices)) if prices else 0, "count": len(prices)}


@router.get("/rides/{ride_id}/tips")
def ride_tips(ride_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """G7: мягкая диагностика поездки для ВОДИТЕЛЯ — «как получить больше заявок».
    Только своя поездка (иначе 404 — чужие не раскрываем). Возвращает добрые советы по
    реальным сигналам; пусто (`all_good`) = «всё выглядит хорошо, заявки скоро появятся».
    Read-only (ничего не пишем), двуязычно. BA — черновики (docs/tasks.md на проверку)."""
    ride = session.get(Ride, ride_id)
    if not ride or ride.driver_id != user.id:
        raise herr(404, "Поездка не найдена", "Сәфәр табылманы")

    tips: List[dict] = []
    if not (user.avatar_url or "").strip():
        tips.append({"code": "add_photo",
                     "ru": "Добавь фото профиля — попутчики охотнее едут с тем, кого видят",
                     "ba": "Профиль фотоһын өҫтә — юлдаштар үҙе күргән кеше менән теләберәк китә"})
    if not user.verified:
        tips.append({"code": "get_verified",
                     "ru": "Пройди проверку — бейдж «Проверен» повышает доверие и число заявок",
                     "ba": "Тикшереүҙе үт — «Тикшерелгән» билдәһе ышанысты һәм заявкаларҙы арттыра"})
    route = _route_avg_price(session, ride.from_city, ride.to_city)
    if (route["count"] >= _TIP_PRICE_MIN_SAMPLE and ride.price
            and route["avg"] and ride.price > route["avg"] * _TIP_PRICE_OVER_RATIO):
        tips.append({"code": "lower_price",
                     "ru": f"Твоя цена выше средней по маршруту (~{route['avg']} ₽). "
                           "Чуть ниже — и заявок станет больше",
                     "ba": f"Хаҡың был юл буйынса уртасанан юғарыраҡ (~{route['avg']} һ). "
                           "Бер аҙ түбәнерәк — заявкалар күберәк булыр"})
    if not (ride.comment or "").strip():
        tips.append({"code": "add_details",
                     "ru": "Добавь пару слов о поездке (где встреча, багаж) — так больше откликов",
                     "ba": "Сәфәр тураһында бер-ике һүҙ өҫтә (ҡайҙа осрашабыҙ, йөк) — шулай отклик күберәк"})

    return {
        "ride_id": ride.id,
        "tips": tips,
        "all_good": not tips,          # пусто → дружелюбная заглушка в UI, не пустой экран
        "route_avg_price": route["avg"],
        "route_sample": route["count"],
    }


# Условия пассажира из ленты «Ближайшие» (ключи — как в приложении) → условие на поездку.
# Незнакомый ключ пропускаем: новая версия приложения не должна ронять ленту старому серверу.
_RIDE_CONDITIONS = {
    "child": lambda: Ride.child_seat.is_(True),
    "pets": lambda: Ride.pets_allowed.is_(True),
    "baggage": lambda: Ride.baggage.is_(True),
    "ac": lambda: Ride.air_conditioner.is_(True),
    "nosmoke": lambda: Ride.smoking.is_(False),   # некурящий = поездки, где курить нельзя
    "quiet": lambda: Ride.quiet.is_(True),
}


_MAX_FILTER_CITIES = 12   # сколько городов в списке шторки «Откуда/Куда» (`/rides/cities`)
_MAX_QUERY_CITIES = 30    # сколько выбранных мест принимает фильтр: топ-12 + найденные поиском деревни


def _city_list(cities: Optional[str]) -> list[str]:
    """Места фильтра через запятую: не больше `_MAX_QUERY_CITIES`, каждое — не длиннее NAME_MAX."""
    return [c.strip()[:stop_search.NAME_MAX] for c in (cities or "").split(",") if c.strip()][:_MAX_QUERY_CITIES]


def _with_passenger_conditions(q, session: Session, conditions: Optional[str]):
    """Сузить выборку ленты по условиям пассажира — теми же правилами, что бейджи на карточке.

    «Только проверенные» здесь нет: их режет `rides_near` последними, чтобы посчитать, сколько
    подходящих поездок он спрятал (`hidden_unverified`)."""
    keys = {k.strip() for k in (conditions or "").split(",") if k.strip()}
    for key in keys & _RIDE_CONDITIONS.keys():
        q = q.where(_RIDE_CONDITIONS[key]())
    if "women" in keys:
        # Как основная лента `/rides?women_only=1` (волны 168/219): только подтверждённая модератором
        # женщина за рулём. Сырую отметку «только женщины» не берём: подтверждение могли снять, а
        # отметка в базе осталась — карточка такую поездку уже не помечает (приёмка PR147).
        q = q.where(Ride.driver_id.in_(
            select(User.id).join(DriverProfile, DriverProfile.user_id == User.id)
            .where(User.gender == "female", DriverProfile.gender_verified.is_(True))))
    return q


def _names_variants(session: Session, names: list[str], entry: dict) -> list[str]:
    """Все написания всех выбранных мест (без повторов) — для SQL и `route_fit`."""
    out: list[str] = []
    for n in names:
        for v in stop_search.variants(session, n, entry):
            if v not in out:
                out.append(v)
    return out


def _fit_all(r, routes: list, entry: dict):
    """`route_fit` по каждому заданному фильтру; подходит, если подходит всем. Места посадки —
    общие для всех; «где садится» (`matched_*`) — из общих мест, если человек назвал «Откуда»."""
    if not routes:
        return stop_search.route_fit(r, None, None, entry)
    fits = [stop_search.route_fit(r, fv or None, tv or None, entry, from_stops=sf, to_stops=st)
            for fv, tv, sf, st in routes]
    if not all(f.ok for f in fits):
        return stop_search.NO_FIT
    common = set(fits[0].pickups)
    for f in fits[1:]:
        common &= set(f.pickups)
    if not common:
        return stop_search.NO_FIT
    # «Где садится» считаем по ОБЩИМ местам посадки, а не по первому фильтру: у него вариантов
    # могло быть больше, чем осталось после остальных (P2-1, приёмка PR #188).
    return stop_search.make_fit(fits[0].stops, tuple(sorted(common)), named=any(fv for fv, *_rest in routes))


def _postgis_dialect(session: Session) -> bool:
    """База — PostgreSQL (префильтр PostGIS по радиусу). Отдельной функцией — тест подставляет."""
    return session.bind.dialect.name == "postgresql"


def _start_point(r, entry: dict):
    """«Начало» в публичной выдаче — ТОЛЬКО координаты начала маршрута или центр города из
    справочника; пин точки сбора — никогда (P2-4: это часто дом водителя)."""
    if r.from_lat is not None and r.from_lng is not None:
        return (r.from_lat, r.from_lng)
    c = CITY_COORDS.get(r.from_city)
    if c:
        return c
    cands = lookup(entry, r.from_city or "")
    return (cands[0].lat, cands[0].lng) if len(cands) == 1 else None


def _nearest_pickup(r, fit, lat: float, lng: float, entry: dict):
    """Расстояние от человека до ближайшего места посадки этой поездки и само место (-1 — начало,
    i — остановка): из позиций, где по его запросу можно сесть (`fit.pickups`), у которых есть
    координаты (начало — `_start_point`, остановка — справочник сёл)."""
    from ..ride_stops import stop_point
    best, best_pos = None, None
    for pos in fit.pickups:
        pt = _start_point(r, entry) if pos == -1 else stop_point(r, fit.stops[pos], entry)
        if pt is None:
            continue
        lat2, lng2 = (pt[0], pt[1]) if pos == -1 else (pt.lat, pt.lng)
        d = haversine_km(lat, lng, lat2, lng2)
        if best is None or d < best:
            best, best_pos = d, pos
    return (round(best, 1) if best is not None else None), best_pos


def _matched(fit, pickup, *, by_query: bool):
    """`matched_stop` / `matched_from_start` для карточки — ТО место посадки, до которого посчитано
    расстояние (P2-1, приёмка PR #188). Расстояние до остановки при предвыбранном начале маршрута
    отправляло человека из Ургазы в Сибай (41 км) и считалось бы неявкой по правилам «начала».

    * расстояние посчитано (`pickup`) — это место, и никакое другое;
    * координат нет: выбираем, только если человек назвал «Откуда» и подходящее место ровно одно;
    * иначе — ничего не выбираем (null / false), человек сам скажет, где сядет."""
    if pickup is not None:
        return (fit.stops[pickup], False) if pickup >= 0 else (None, True)
    if by_query and len(fit.pickups) == 1:
        return fit.matched_stop, fit.matched_from_start
    return None, False


@router.get("/rides/near")
def rides_near(
    from_city: Optional[str] = Query(None, max_length=stop_search.NAME_MAX),
    to_city: Optional[str] = Query(None, max_length=stop_search.NAME_MAX),
    lat: Optional[float] = None,
    lng: Optional[float] = None,
    radius_km: Optional[float] = None,
    date: Optional[date_type] = None,   # F4: «когда едем» — только поездки этого дня (YYYY-MM-DD)
    limit: Optional[int] = None,        # пагинация «показать ещё» (опц., None = все)
    offset: int = 0,
    conditions: Optional[str] = None,   # условия пассажира через запятую: child,pets,baggage,ac,nosmoke,quiet,women
    from_cities: Optional[str] = None,  # фильтр «Откуда»: несколько городов через запятую (любой из них)
    to_cities: Optional[str] = None,    # фильтр «Куда»: так же
    verified_only: bool = False,        # только проверенные водители
    user: Optional[User] = Depends(current_user_optional),   # есть токен → прячем заблокированных
    session: Session = Depends(get_session),
):
    """Ближайшие поездки по маршруту клиента, отсортированы по времени выезда (ранняя — первой).
    Если переданы координаты клиента (lat/lng) — добавляем дистанцию до точки выезда и (опц.) фильтр по радиусу.
    Сценарий: водитель отменил/сломался → клиент видит ближайшую по времени машину на своём маршруте и уезжает."""
    # Уже уехавшие (выезд раньше чем RIDE_PAST_GRACE_HOURS назад) — как в общей ленте `/rides`: ночная
    # уборка закрывает их не сразу, а бронь на них всё равно получит «поездка уже уехала» (сквозной
    # проход волны 1, 16.10.2026).
    q = select(Ride).where(Ride.status == RideStatus.active, Ride.seats_left > 0,
                           Ride.depart_at >= utcnow() - timedelta(hours=RIDE_PAST_GRACE_HOURS))
    # «Откуда»/«Куда» — место из поиска (`from_city`/`to_city`) и места из шторок (`from_cities`/
    # `to_cities`, «любое из»); заданы оба — поездка должна подойти обоим, как раньше. И по остановкам
    # (18.10.2026): в SQL широкое условие «столбец ИЛИ остановки» (без попарных LIKE), точный порядок
    # и допустимые остановки — `route_fit` ниже, до счёта `count` и разбивки на порции.
    entry = settlements_snapshot(session)
    routes = []   # [(fv, tv, stops_from, stops_to)] — каждое должно подойти
    for froms, tos in ((_city_list(from_city), _city_list(to_city)),
                       (_city_list(from_cities), _city_list(to_cities))):
        if not froms and not tos:
            continue
        fv, tv = _names_variants(session, froms, entry), _names_variants(session, tos, entry)
        st_f = bool(froms) and all(stop_search.stop_searchable(n) for n in froms)
        st_t = bool(tos) and all(stop_search.stop_searchable(n) for n in tos)
        cond = _route_condition(fv, tv, st_f, st_t, pairs=False)
        if cond is not None:
            q = q.where(cond)
            routes.append((fv, tv, st_f, st_t))
    bounds = _date_bounds(date)
    if bounds:
        q = q.where(Ride.depart_at >= bounds[0], Ride.depart_at < bounds[1])
    # Условия пассажира — ДО счёта `count` и разбивки на порции (DESIGN023, приёмка волны 1):
    # раньше их применял телефон к уже полученной порции, и подходящая поездка двенадцатой
    # в списке превращалась в «поездок нет», а «Показать ещё» при условии пряталась.
    q = _with_passenger_conditions(q, session, conditions)
    # PostGIS-префильтр по радиусу (только postgres + есть координаты): индекс GiST → быстро на больших
    # объёмах. Фолбэк (sqlite/без PostGIS/ошибка) — Python-haversine ниже даёт тот же результат.
    if lat is not None and lng is not None and radius_km is not None and _postgis_dialect(session):
        try:
            # NULL-координатные поездки НЕ выкидываем (старые/негеокоженные) — их
            # отфильтрует Python-haversine по CITY_COORDS ниже. ST_DWithin (с GiST-индексом)
            # отсекает далёкие среди геокоженных.
            # Поездки с остановками не выкидываем: ближе может оказаться остановка (18.10.2026) —
            # расстояние до неё посчитает Python ниже.
            ids = [row[0] for row in session.execute(text(
                "SELECT id FROM ride WHERE status = 'active' AND (from_lat IS NULL OR waypoints <> '' OR "
                "ST_DWithin(ST_MakePoint(from_lng, from_lat)::geography, "
                "ST_MakePoint(:lng, :lat)::geography, :r))"
            ), {"lng": lng, "lat": lat, "r": radius_km * 1000.0}).all()]
            q = q.where(Ride.id.in_(ids)) if ids else q.where(Ride.id.is_(None))
        except Exception as e:  # noqa: BLE001 — нет PostGIS/ошибка → Python-фолбэк
            session.rollback()  # снять aborted-транзакцию, иначе следующий запрос упадёт InFailedSqlTransaction
            # Только вид ошибки: в её тексте — SQL с координатами человека (`:lat`, `:lng`), а логи
            # координат не держат (§8).
            log.warning(f"[GEO] PostGIS prefilter skipped: {type(e).__name__}")
    # Перф (2026-07): фазу «дистанция + фильтры + счёт total» гоним по ЛЁГКИМ колонкам
    # (id/driver_id/only_trusted/координаты), а не по полным ORM-объектам: без from_city
    # запрос матчит ВСЕ активные (12k+), и гидрация полных моделей стоила ~350мс/запрос.
    # Полные строки и тяжёлая сериализация (drivers_bundle + RideOut) — только для страницы.
    # session.execute (не .exec): sqlmodel-select(Ride) — SelectOfScalar, его exec()
    # схлопнул бы Row до первой колонки; execute отдаёт полные Row с атрибутами.
    # `driver_ok` — проверен ли водитель: тем же подзапросом, каким раньше резал WHERE, но колонкой,
    # чтобы спрятанные поездки можно было посчитать без второго запроса.
    light_rows = session.execute(
        q.with_only_columns(Ride.id, Ride.driver_id, Ride.only_trusted,
                            Ride.from_lat, Ride.from_lng, Ride.from_city,
                            Ride.to_lat, Ride.to_lng, Ride.to_city, Ride.waypoints,
                            Ride.driver_id.in_(select(User.id).where(User.verified.is_(True))).label("driver_ok"))
        .order_by(*boost_then_depart_order())   # Boost первыми, затем по времени выезда ↑
    ).all()
    kept: list = []
    dist_by_id: dict = {}
    matched_by_id: dict = {}
    for r in light_rows:
        fit = _fit_all(r, routes, entry)
        if not fit.ok:
            continue   # совпало подстрокой, а маршрут не тот (порядок, недопустимая остановка)
        dist, pickup = None, None
        if lat is not None and lng is not None:
            dist, pickup = _nearest_pickup(r, fit, lat, lng, entry)
        if radius_km is not None and dist is not None and dist > radius_km:
            continue
        dist_by_id[r.id] = dist
        matched_by_id[r.id] = _matched(fit, pickup, by_query=any(fv for fv, *_ in routes))
        kept.append(r)
    # Прячем до подсчёта total/пагинации: заблокированных, водителей на паузе (бронь им всё
    # равно не подтвердить) и «только для своих» (видит лишь L3). Связку с клиникой снимаем
    # ниже, после сборки dict-ов — поэтому тут отдельные фильтры, а не visible_rides целиком.
    kept = hide_trusted_only(hide_suspended(hide_blocked(kept, user, session), user, session), user, session)
    # «Только проверенные» (17.10.2026 у пассажира включены по умолчанию) — последними, после всех
    # остальных фильтров: тогда `hidden_unverified` — ровно те поездки ЭТОГО поиска, которые спрятал
    # только этот фильтр. Пустая лента скажет «Есть N поездок непроверенных водителей — показать?»,
    # а не голое «поездок нет». Старое приложение лишнее поле просто не читает.
    hidden_unverified: Optional[int] = None
    if verified_only:
        shown = [r for r in kept if r.driver_ok]
        hidden_unverified = len(kept) - len(shown)
        kept = shown
    total = len(kept)
    eff_limit = min(max(1, limit), 200) if limit is not None else DEFAULT_FEED_LIMIT
    page_ids = [r.id for r in kept[max(0, offset):max(0, offset) + eff_limit]]
    by_id = (
        {r.id: r for r in session.exec(select(Ride).where(Ride.id.in_(page_ids))).all()}
        if page_ids else {}
    )
    page = [by_id[i] for i in page_ids if i in by_id]   # порядок страницы сохраняем
    users_map, profiles, rating_agg, trips_agg = drivers_bundle(session, {r.driver_id for r in page})
    front_taken = front_seats_taken(session, page_ids)
    items = []
    for r in page:
        out = public_ride_payload(ride_out_with(r, users_map, profiles, rating_agg, trips_agg, front_taken)).model_dump()
        out["distance_km"] = dist_by_id.get(r.id)
        out["matched_stop"], out["matched_from_start"] = matched_by_id.get(r.id, (None, False))
        items.append(out)
    # Связку с клиникой прячем ЗДЕСЬ, а не выше вместе с соседними фильтрами: там в руках
    # ORM-объекты `Ride`, страница потом перечитывается из базы заново — правка на них
    # бесследно терялась. Поймано пробой: лента уже молчала, а «рядом» всё ещё отдавало
    # `partner_id` анониму (аудит 2026-08-08, волна 22).
    items = hide_health_hint(items, user)
    out: dict = {"count": total, "items": items}   # count = всего (чтобы клиент знал, есть ли «ещё»)
    if hidden_unverified is not None:
        out["hidden_unverified"] = hidden_unverified   # только при verified_only: без фильтра прятать нечего
    return out


@router.get("/driver/rides", response_model=List[RideOut])
def my_driver_rides(
    status: Optional[str] = None,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """Поездки водителя. По умолчанию — активные (для Boost, поведение как раньше).
    `status`: active (по умолч.) / done / cancelled / all — для раздела «Архив» в кабинете.
    Архив (done/cancelled/all) сортируем по времени выезда ↓ (свежие сверху); активные —
    как раньше (сначала поднятые, потом по времени выезда)."""
    status = (status or "").strip().lower()
    q = select(Ride).where(Ride.driver_id == user.id)
    if status == "all":
        q = q.order_by(Ride.depart_at.desc())
    elif status == "done":
        q = q.where(Ride.status == RideStatus.done).order_by(Ride.depart_at.desc())
    elif status == "cancelled":
        q = q.where(Ride.status == RideStatus.cancelled).order_by(Ride.depart_at.desc())
    else:   # "" | "active" — прежнее поведение (не ломаем существующий вызов Boost)
        q = q.where(Ride.status == RideStatus.active).order_by(*boost_then_depart_order())
    rides = session.exec(q).all()
    return rides_out(rides, session)


def _ride_owned(session: Session, ride_id: int, user: User) -> Ride:
    """Поездка под row-lock + проверка владения (или админ — помощь по звонку, как везде)."""
    ride = session.exec(select(Ride).where(Ride.id == ride_id).with_for_update()).first()
    if not ride:
        raise herr(404, "Поездка не найдена", "Сәфәр табылманы")
    if ride.driver_id != user.id and user.role != UserRole.admin:
        raise herr(403, "Это не твоя поездка", "Был һинең сәфәрең түгел")
    return ride


def _live_bookings(session: Session, ride_id: int) -> list:
    """Живые брони поездки (не отменённые и не завершённые)."""
    return session.exec(select(Booking).where(
        Booking.ride_id == ride_id,
        Booking.status.notin_((BookingStatus.cancelled, BookingStatus.done)),  # type: ignore[attr-defined]
    )).all()


@router.post("/rides/{ride_id}/cancel", response_model=RideOut)
def cancel_ride(ride_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель снимает поездку (сломался/передумал). Каскад: все живые брони → cancelled,
    каждому пассажиру push «Поездка отменена» (он увидит альтернативы в /rides/near).
    Идемпотентно: повторная отмена — no-op. Завершённую отменить нельзя."""
    ride = _ride_owned(session, ride_id, user)
    if ride.status == RideStatus.cancelled:
        return public_ride_payload(ride_out(ride, session), full=True)   # идемпотентно (двойной тап); свой рейс — свой текст
    if ride.status == RideStatus.done:
        raise herr(400, "Поездка уже завершена", "Сәфәр инде тамамланған")
    affected = _live_bookings(session, ride_id)
    ride.status = RideStatus.cancelled
    session.add(ride)
    now = utcnow()
    # Оплаченное поднятие не сгорает вместе с поездкой: остаток переезжает водителю
    # и сам ляжет на его следующую поездку.
    if stash_boost_credit(user, ride, now):
        session.add(user)
    for b in affected:
        b.status = BookingStatus.cancelled
        # КТО отменил — обязательно. Без этой пометки «Надёжность» водителя не знала о его
        # сорванных рейсах вовсе: формула считает поздние отмены по `cancelled_by`, а здесь
        # поле оставалось пустым. Проверено запросом: водитель трижды снял поездку за 20 минут
        # до выезда — Надёжность 100, а пассажирка за одну позднюю отмену получила 0
        # (аудит 2026-08-08, волна 17). Человек, оставшийся на дороге, страдал дважды.
        #
        # Ранняя отмена по-прежнему без последствий: `is_late_cancel` смотрит на срок до выезда,
        # так что отмена за сутки Надёжность не тронет — наказывается не отказ, а внезапность.
        b.cancelled_at = now
        b.cancelled_by = user.id
        b.cancel_reason = "not_going"
        session.add(b)
    session.commit()                     # атомарно: поездка+брони одной транзакцией
    for b in affected:
        trip_wake.poke(b.id)             # каналы координат этих броней закрываются сразу
    session.refresh(ride)
    notify_map_changed()                 # пин уходит с карты live
    # Уведомления — ПОСЛЕ commit (сбой FCM не откатит отмену).
    #
    # Через push_notification, а не send_push: кроме пуша это оставляет запись в Центре
    # уведомлений. Пуш до человека может не дойти — телефон выключен, нет сети, уведомления
    # отключены, дешёвый телефон прибил приложение ради батареи. Тогда без записи он узнаёт
    # об отмене, только выйдя к дороге. Бронь и подтверждение след оставляли, а отмена —
    # самое важное сообщение в приложении — нет (аудит 2026-08-06).
    #
    # И на двух языках: раньше текст был только русский.
    route = f"{ride.from_city} → {ride.to_city}"
    for b in affected:
        push_notification(
            session, b.passenger_id, "booking",
            "Поездка отменена", "Сәфәр кире алынды",
            f"{route}: водитель отменил. Посмотри другие поездки рядом.",
            f"{route}: водитель кире алды. Яҡындағы башҡа сәфәрҙәрҙе ҡара.",
            ref_kind="booking", ref_id=b.id,
        )
    return public_ride_payload(ride_out(ride, session), full=True)  # свой рейс — свой текст


@router.post("/rides/{ride_id}/complete", response_model=RideOut)
def complete_ride(ride_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель завершает рейс целиком: поездка → done, подтверждённые/в-пути брони → done,
    ожидающие (pending) → cancelled (рейс кончился — «висящих» заявок не оставляем).
    Идемпотентно: повторное завершение — no-op."""
    ride = _ride_owned(session, ride_id, user)
    if ride.status == RideStatus.done:
        return public_ride_payload(ride_out(ride, session), full=True)   # идемпотентно; свой рейс — свой текст
    if ride.status == RideStatus.cancelled:
        raise herr(400, "Поездка отменена — завершать нечего", "Сәфәр кире алынған — тамамлар нәмә юҡ")
    # Третья дверь к «поездка состоялась» (независимая проверка аудита 2026-08-07). Планку
    # «завершить можно только начавшуюся» поставили на брони — водительскую (`bookings.py`)
    # и пассажирскую из семейного контроля (`family.py`), — а вот эта ручка закрывает СРАЗУ
    # ВСЕ брони рейса и на время не смотрела вовсе. Цикл «опубликовал на 2030 год →
    # забронировал вторым аккаунтом → подтвердил → завершил рейс» из пяти запросов рисовал
    # поездку в публичной карточке водителя и открывал оценку. Карточку отдают без входа:
    # человек видит «5 поездок, рейтинг 5.0» у того, кто не проехал ни метра.
    if ride.depart_at and utcnow() < ride.depart_at - DONE_EARLY_GRACE:
        raise herr(409, "Поездка ещё не началась — завершить можно после времени выезда",
                   "Сәфәр әле башланмаған — сығыу ваҡытынан һуң тамамлап була")
    affected = _live_bookings(session, ride_id)
    ride.status = RideStatus.done
    session.add(ride)
    completed: list[Booking] = []
    dropped: list[Booking] = []      # брони, которые водитель так и не подтвердил
    now = utcnow()
    for b in affected:
        if b.status == BookingStatus.pending:
            # Человек забронировал, ждал ответа — а рейс уехал без него. Раньше такая бронь
            # гасилась МОЛЧА: ни причины, ни времени, ни уведомления. Пассажир продолжал ждать
            # подтверждения, которого уже не будет (аудит 2026-08-08, волна 18).
            b.status = BookingStatus.cancelled
            b.cancelled_at = now
            b.cancel_reason = "driver_no_response"
            # Авторство ставим, только если у водителя реально было время заметить бронь.
            # Бронь за пять минут до выезда — не его вина; бронь, висевшая полчаса и дольше,
            # уже его ответственность, и Надёжность обязана это видеть (см. reliability_for).
            if b.created_at and ride.depart_at and b.created_at <= ride.depart_at - _CONFIRM_GRACE:
                b.cancelled_by = ride.driver_id
            dropped.append(b)
        else:
            b.status = BookingStatus.done
            b.done_at = now   # от неё — окно «Сообщить о проблеме» пассажира (bookings.problem_report_left)
            completed.append(b)
        session.add(b)
    session.commit()
    for b in affected:
        trip_wake.poke(b.id)             # каналы координат этих броней закрываются сразу
    session.refresh(ride)
    # Бонус пригласившему — четвёртая дверь к «поездка состоялась» (волна 127). Три другие
    # (бронь у водителя, семейный контроль, такси) начисляли его давно, а эта кнопка —
    # самая частая у водителя — нет. Сосед позвал человека в Юлдаш, тот раскатался,
    # а бонус не приходил никогда: просто потому, что рейсы он закрывал не той кнопкой.
    if completed:
        from .referral import reward_driver_referral
        reward_driver_referral(session, ride.driver_id)
    notify_map_changed()
    # Как и отмена (см. cancel_ride): запись в Центре уведомлений + два языка. Здесь это ещё
    # и приглашение оценить поездку — без следа оно живёт ровно до пропущенного пуша.
    route = f"{ride.from_city} → {ride.to_city}"
    for b in dropped:
        push_notification(
            session, b.passenger_id, "booking",
            "Бронь не подтвердили", "Бронь раҫланманы",
            f"{route}: рейс уехал, а бронь так и осталась без ответа. Посмотри другие поездки рядом.",
            f"{route}: рейс китте, ә бронь яуапһыҙ ҡалды. Яҡындағы башҡа сәфәрҙәрҙе ҡара.",
            ref_kind="booking", ref_id=b.id,
        )
    for b in completed:
        push_notification(
            session, b.passenger_id, "ride",
            "Поездка завершена", "Сәфәр тамамланды",
            f"{route}: спасибо, что ехали вместе! Оцени поездку.",
            f"{route}: бергә барғаныңа рәхмәт! Сәфәрҙе баһала.",
            ref_kind="booking_done", ref_id=b.id,
        )
    return public_ride_payload(ride_out(ride, session), full=True)  # свой рейс — свой текст


@router.get("/rides/cities")
def ride_cities(user: Optional[User] = Depends(current_user_optional),   # есть токен → прячем заблокированных
                session: Session = Depends(get_session)):
    """Города, откуда и куда сейчас есть поездки, — для шторок «Откуда» и «Куда» в ленте.

    Каждый город — на двух языках («Баймак» / «Баймаҡ»): приложение подставляет имя на языке
    человека, а ищет по любому написанию (`_city_match`). Чисел не отдаём — сколько у кого
    поездок, витрине знать незачем; порядок — от самых частых. Объявлен ДО `/rides/{ride_id}`,
    иначе «cities» читался бы как номер поездки.

    Прячем то же, что лента `/rides/near`: заблокированных, водителей на паузе и «только для своих».
    Иначе в шторке висел бы город, выбор которого даёт «поездок нет», а посторонний узнавал бы,
    что оттуда кто-то едет «для своих». Считаем по ближайшим FEED_MAX×10 поездкам — один запрос
    не тянет всю базу, а города дальних поездок всё равно уходят из топа.
    """
    rows = session.execute(
        select(Ride.id, Ride.driver_id, Ride.only_trusted, Ride.from_city, Ride.to_city, Ride.waypoints,
               Ride.from_lat, Ride.from_lng, Ride.to_lat, Ride.to_lng)
        .where(Ride.status == RideStatus.active, Ride.seats_left > 0,
               Ride.depart_at >= utcnow() - timedelta(hours=RIDE_PAST_GRACE_HOURS))   # уехавшие — как в ленте
        .order_by(Ride.depart_at).limit(FEED_MAX * 10)
    ).all()
    rows = hide_trusted_only(hide_suspended(hide_blocked(rows, user, session), user, session), user, session)

    # Остановки (18.10.2026) — тоже места, откуда и куда едут: «из Ургазы» находит поездку «Сибай →
    # Акъяр через Ургазу». В чипы — только остановки, которые узнал справочник сёл (свободный текст
    # «поворот на Юлук» — нет), и только из видимых этому человеку поездок (строки уже отфильтрованы).
    # Справочник — ОДНИМ снимком на весь ответ (и для остановок, и для городов ниже): список
    # открывается с картой у каждого (приёмка PR147, `test_no_query_per_item`).
    from ..ride_stops import parse_stops
    snap = settlements_snapshot(session)
    stops_of = [parse_stops(r, snap) if (r.waypoints or "").strip() else [] for r in rows]
    known_stops = by_exact_names(session, {s for stops in stops_of for s in stops}, snap)
    stop_names = [s for stops in stops_of for s in stops if known_stops.get(s) is not None]

    def _top(names) -> list[dict]:
        # Сначала считаем имена, потом ищем их в справочнике ОДНИМ снимком (by_exact_names): поиск
        # по одному имени сверяет снимок запросом в базу, и по разу на поездку или даже на разное
        # имя это сотни запросов на каждое открытие карты (приёмка PR147, два прохода).
        # Имя с запятой не берём: фильтр передаёт города через запятую и разрезал бы его на два.
        имена = Counter(n for n in (bare_name((raw or "").strip()) for raw in names) if n and "," not in n)
        найдено = by_exact_names(session, имена, snap)
        счёт: dict[tuple, int] = {}
        for name, n in имена.items():
            row = найдено[name]
            key = ((row.name_ru or name), (row.name_ba or row.name_ru or name)) if row else (name, name)
            счёт[key] = счёт.get(key, 0) + n
        лучшие = sorted(счёт.items(), key=lambda kv: (-kv[1], kv[0][0]))[:_MAX_FILTER_CITIES]
        return [{"ru": ru, "ba": ba} for (ru, ba), _ in лучшие]

    return {"from": _top([r.from_city for r in rows] + stop_names),
            "to": _top([r.to_city for r in rows] + stop_names)}


@router.get("/rides/{ride_id}", response_model=RideOut)
def get_ride(ride_id: int, user: Optional[User] = Depends(current_user_optional),
             session: Session = Depends(get_session)):
    ride = session.get(Ride, ride_id)
    if not ride:
        raise herr(404, "Поездка не найдена", "Сәфәр табылманы")
    # История (отменённые и завершённые) — только участникам. Номера идут подряд, и без этого
    # посторонний собирал перебором архив передвижений человека за месяцы (волна 119).
    if not may_see_ride_by_id(ride, user, session):
        raise herr(404, "Поездка не найдена", "Сәфәр табылманы")
    # Комментарий с ориентиром у дома и телефоном — только своим: водителю и тому, у кого есть
    # живая бронь. Постороннему и гостю он приходит с замазанным личным (волна 138).
    out = public_ride_payload(ride_out(ride, session), full=is_ride_insider(ride, user, session))
    # V5: те же фильтры, что в ленте — «только для своих» скрыта от не-L3, поездка в связке
    # блокировки не отдаётся по прямому id (иначе обход only_trusted/blocked + анонимный скрейпинг).
    visible = visible_rides([out], user, session)
    if not visible:
        raise herr(404, "Поездка не найдена", "Сәфәр табылманы")   # не раскрываем существование закрытой поездки
    return visible[0]
