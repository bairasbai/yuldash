"""Поездки: публикация (вкл. регулярные серии), поиск, ценовой ориентир,
ближайшие по маршруту+гео, карточка поездки."""
from datetime import date as date_type, datetime, timedelta
from functools import lru_cache
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import text
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..errors import herr
from ..flood import TOO_MANY_RIDES, guard_open_items
from ..geo import bare_name
from ..logs import log
from ..models import Booking, BookingStatus, DriverProfile, MedicalPartner, Ride, RideCategory, RideStatus, User, UserRole
from .. import workday as workday_mod
from ..safety_logic import ensure_active
from ..schemas import RideIn, RideOut
from ..security import current_user, current_user_optional
from ..timeutil import client_dt_to_utc, utcnow
from ..workday import local_now
from ..trust_service import INSIDER_LEVEL, trust_level
from ..services import (
    CITY_COORDS, blocked_user_ids, boost_then_depart_order, cache_get_json, cache_set_json, drivers_bundle,
    geocode_city, haversine_km, notify_map_changed, notify_route_watchers, public_ride_payload,
    public_rides_payload, push_notification, record_pickup_choice, ride_out, ride_out_with,
    rides_out, send_push,
)

router = APIRouter(tags=["rides"])

RIDE_PAST_GRACE_HOURS = 2   # сколько часов после depart_at поездка ещё видна в выдаче (поздняя бронь / уехал впритык)

# Перф (2026-07): дефолтный потолок выдачи /rides и /rides/near. Раньше limit=None
# означал «отдать ВСЁ» — на 30k+ поездок (11k активных) каждый запрос сериализовал
# ~11 000 карточек (~400мс CPU), а 20 параллельных клиентов клали сервер в таймауты.
# Клиенту столько не нужно: выдача отсортирована (Boost → ближайший выезд),
# «показать ещё» ходит с limit/offset. Явный limit по-прежнему капится 200/страница.
DEFAULT_FEED_LIMIT = 200


def _date_bounds(date: Optional[date_type]):
    """F4: границы суток для фильтра «когда едем» (date=YYYY-MM-DD → [00:00, +1день))."""
    if date is None:
        return None
    start = datetime(date.year, date.month, date.day)
    return start, start + timedelta(days=1)


def _hide_blocked(items, user, session):
    """Прячем из выдачи поездки заблокированных водителей (в обе стороны). Аноним → без фильтра.
    items — список RideOut (свежие) или dict (из кеша/near); оба содержат driver_id."""
    if user is None:
        return items
    blocked = blocked_user_ids(session, user.id)
    if not blocked:
        return items
    return [r for r in items if (r["driver_id"] if isinstance(r, dict) else r.driver_id) not in blocked]


def _hide_trusted_only(items, user, session):
    """Прячем поездки «только для своих» (only_trusted) от всех, кто НЕ L3.
    Аноним и L0–L2 их не видят; свой водитель видит СВОЮ поездку всегда.
    items — RideOut (свежие) или dict (кеш/near); оба содержат only_trusted + driver_id."""
    def _f(r, key):
        return r[key] if isinstance(r, dict) else getattr(r, key)
    # Вычисляем уровень зрителя один раз (не в цикле).
    level = trust_level(session, user) if user is not None else 0
    if level >= INSIDER_LEVEL:
        return items
    uid = user.id if user is not None else None
    return [r for r in items if not _f(r, "only_trusted") or _f(r, "driver_id") == uid]


@router.post("/rides", response_model=Ride)
def create_ride(body: RideIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    ensure_active(session, user.id)   # пауза лестницы «Справедливости» (§2) блокирует публикацию
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
    # Санити-границы (анти-мусор в ленте): мест 1..8, цена 0..100000 ₽. Клампим, а не падаем.
    body.seats_total = max(1, min(8, body.seats_total))
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
    # F22: клиника-назначение (опц.). Если указана — проверяем, что она есть и активна
    # (чтобы не осталось битой ссылки). Это ТОЛЬКО точка назначения, без мед.данных.
    if body.partner_id is not None:
        partner = session.get(MedicalPartner, body.partner_id)
        if not partner or not partner.active:
            raise HTTPException(400, "Клиника не найдена")
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
    return ride


class RideEditIn(BaseModel):
    """F3: правка своей поездки. Все поля опциональны — меняется только присланное."""
    price: Optional[int] = Field(None, ge=0, le=100_000)
    comment: Optional[str] = Field(None, max_length=2000)
    depart_at: Optional[datetime] = None
    seats_total: Optional[int] = Field(None, ge=1, le=8)


@router.patch("/rides/{ride_id}", response_model=RideOut)
@router.post("/rides/{ride_id}/edit", response_model=RideOut)   # алиас: Android HttpURLConnection не умеет PATCH
def edit_ride(ride_id: int, body: RideEditIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """F3: водитель правит опубликованную поездку (опечатка в цене/времени была неисправимой).

    Правила честности перед пассажирами:
    - без живых броней — можно всё (цена/комментарий/время/места);
    - есть живые брони — только комментарий и цену ВНИЗ (условия «купленного» не ухудшаем);
      время/места при бронях менять нельзя (409) — отмени рейс (пассажиры получат push) и создай новый.
    Пассажиров с бронью уведомляем push «Поездка обновлена»."""
    ride = session.exec(select(Ride).where(Ride.id == ride_id).with_for_update()).first()
    if not ride:
        raise herr(404, "Поездка не найдена", "Сәфәр табылманы")
    if ride.driver_id != user.id:
        raise HTTPException(403, "Это не ваша поездка")
    if ride.status != RideStatus.active:
        raise herr(400, "Менять можно только активную поездку", "Тик актив сәфәрҙе генә үҙгәртеп була")
    live = session.exec(select(Booking).where(
        Booking.ride_id == ride_id,
        Booking.status.in_((BookingStatus.pending, BookingStatus.confirmed, BookingStatus.onboard)),
    )).all()
    booked_seats = sum(b.seats for b in live)
    changed: list[str] = []
    if body.price is not None and body.price != ride.price:
        if live and body.price > ride.price:
            raise herr(409, "С активными бронями цену можно только снижать", "Актив брондар менән хаҡты кәметергә генә була")
        ride.price = body.price
        changed.append("цена")
    if body.comment is not None and body.comment != ride.comment:
        ride.comment = body.comment
        changed.append("комментарий")
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
        changed.append("время")
    if body.seats_total is not None and body.seats_total != ride.seats_total:
        if live:
            raise herr(409, "С активными бронями число мест не меняют", "Актив брондар менән урын һанын үҙгәртеп булмай")
        ride.seats_total = body.seats_total
        ride.seats_left = body.seats_total - booked_seats   # броней нет → просто новое число мест
        changed.append("места")
    if not changed:
        return public_ride_payload(ride_out(ride, session))   # нечего менять — no-op
    session.add(ride)
    session.commit()
    session.refresh(ride)
    notify_map_changed()   # карточка на карте/в ленте обновится live
    for b in live:         # пуши после commit
        send_push(session, b.passenger_id, "Поездка обновлена",
                  f"{ride.from_city} → {ride.to_city}: изменено — {', '.join(changed)}. Загляни в детали.")
    return public_ride_payload(ride_out(ride, session))


@router.get("/rides", response_model=List[RideOut])
def search_rides(
    from_city: Optional[str] = None,
    to_city: Optional[str] = None,
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
            out = _hide_blocked(public_rides_payload(cached), user, session)
            return _hide_trusted_only(out, user, session)

    # Не показываем УЖЕ УЕХАВШИЕ поездки (аудит 2026-07-04: у поездки не было отсева по времени →
    # вчерашние висели в ленте). Грейс 2ч: поездка «только что уехала»/бронируют впритык — ещё видна.
    q = select(Ride).where(
        Ride.status == RideStatus.active,
        Ride.depart_at >= utcnow() - timedelta(hours=RIDE_PAST_GRACE_HOURS),
    )
    if from_city:
        q = q.where(Ride.from_city.contains(bare_name(from_city)))
    if to_city:
        q = q.where(Ride.to_city.contains(bare_name(to_city)))
    if category:
        q = q.where(Ride.category == category)
    if pets_allowed:
        q = q.where(Ride.pets_allowed == True)  # noqa: E712
    if child_seat:
        q = q.where(Ride.child_seat == True)  # noqa: E712
    if women_only:
        # F9: фильтр «только женщины» показывает и поездки с флагом women_only,
        # И поездки, где сама водитель — женщина (opt-in gender=female). OUTER JOIN,
        # чтобы поездки без профиля водителя не выпадали из общей проверки.
        q = q.outerjoin(DriverProfile, DriverProfile.user_id == Ride.driver_id).where(
            (Ride.women_only == True) | (DriverProfile.gender == "female")  # noqa: E712
        )
    if baggage:
        q = q.where(Ride.baggage == True)  # noqa: E712
    bounds = _date_bounds(date)
    if bounds:
        q = q.where(Ride.depart_at >= bounds[0], Ride.depart_at < bounds[1])
    q = q.order_by(*boost_then_depart_order())   # поднятые (Boost) — первыми
    # Потолок всегда: явный limit капится 200/страница, без limit — DEFAULT_FEED_LIMIT.
    eff_limit = min(max(1, limit), 200) if limit is not None else DEFAULT_FEED_LIMIT
    q = q.offset(max(0, offset)).limit(eff_limit)
    rides = session.exec(q).all()
    out = rides_out(rides, session)
    public_out = public_rides_payload(out)
    if no_filter:
        cache_set_json("rides:active:v2", [r.model_dump(mode="json") for r in public_out], 20)
    out = _hide_blocked(public_out, user, session)
    return _hide_trusted_only(out, user, session)


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
        q = q.where(Ride.from_city.contains(bare_name(from_city)))
    if to_city:
        q = q.where(Ride.to_city.contains(bare_name(to_city)))
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
        q = q.where(Ride.from_city.contains(bare_name(from_city)))
    if to_city:
        q = q.where(Ride.to_city.contains(bare_name(to_city)))
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


@router.get("/rides/near")
def rides_near(
    from_city: Optional[str] = None,
    to_city: Optional[str] = None,
    lat: Optional[float] = None,
    lng: Optional[float] = None,
    radius_km: Optional[float] = None,
    date: Optional[date_type] = None,   # F4: «когда едем» — только поездки этого дня (YYYY-MM-DD)
    limit: Optional[int] = None,        # пагинация «показать ещё» (опц., None = все)
    offset: int = 0,
    user: Optional[User] = Depends(current_user_optional),   # есть токен → прячем заблокированных
    session: Session = Depends(get_session),
):
    """Ближайшие поездки по маршруту клиента, отсортированы по времени выезда (ранняя — первой).
    Если переданы координаты клиента (lat/lng) — добавляем дистанцию до точки выезда и (опц.) фильтр по радиусу.
    Сценарий: водитель отменил/сломался → клиент видит ближайшую по времени машину на своём маршруте и уезжает."""
    q = select(Ride).where(Ride.status == RideStatus.active, Ride.seats_left > 0)
    if from_city:
        q = q.where(Ride.from_city.contains(bare_name(from_city)))
    if to_city:
        q = q.where(Ride.to_city.contains(bare_name(to_city)))
    bounds = _date_bounds(date)
    if bounds:
        q = q.where(Ride.depart_at >= bounds[0], Ride.depart_at < bounds[1])
    # PostGIS-префильтр по радиусу (только postgres + есть координаты): индекс GiST → быстро на больших
    # объёмах. Фолбэк (sqlite/без PostGIS/ошибка) — Python-haversine ниже даёт тот же результат.
    if lat is not None and lng is not None and radius_km is not None and session.bind.dialect.name == "postgresql":
        try:
            # NULL-координатные поездки НЕ выкидываем (старые/негеокоженные) — их
            # отфильтрует Python-haversine по CITY_COORDS ниже. ST_DWithin (с GiST-индексом)
            # отсекает далёкие среди геокоженных.
            ids = [row[0] for row in session.execute(text(
                "SELECT id FROM ride WHERE status = 'active' AND (from_lat IS NULL OR "
                "ST_DWithin(ST_MakePoint(from_lng, from_lat)::geography, "
                "ST_MakePoint(:lng, :lat)::geography, :r))"
            ), {"lng": lng, "lat": lat, "r": radius_km * 1000.0}).all()]
            q = q.where(Ride.id.in_(ids)) if ids else q.where(Ride.id.is_(None))
        except Exception as e:  # noqa: BLE001 — нет PostGIS/ошибка → Python-фолбэк
            session.rollback()  # снять aborted-транзакцию, иначе следующий запрос упадёт InFailedSqlTransaction
            log.warning(f"[GEO] PostGIS prefilter skipped: {e}")
    # Перф (2026-07): фазу «дистанция + фильтры + счёт total» гоним по ЛЁГКИМ колонкам
    # (id/driver_id/only_trusted/координаты), а не по полным ORM-объектам: без from_city
    # запрос матчит ВСЕ активные (12k+), и гидрация полных моделей стоила ~350мс/запрос.
    # Полные строки и тяжёлая сериализация (drivers_bundle + RideOut) — только для страницы.
    # session.execute (не .exec): sqlmodel-select(Ride) — SelectOfScalar, его exec()
    # схлопнул бы Row до первой колонки; execute отдаёт полные Row с атрибутами.
    light_rows = session.execute(
        q.with_only_columns(Ride.id, Ride.driver_id, Ride.only_trusted,
                            Ride.from_lat, Ride.from_lng, Ride.from_city)
        .order_by(*boost_then_depart_order())   # Boost первыми, затем по времени выезда ↑
    ).all()
    kept: list = []
    dist_by_id: dict = {}
    for r in light_rows:
        dist = None
        if lat is not None and lng is not None:
            # реальные геокодированные координаты концов → иначе известный город → иначе нет дистанции
            c = (r.from_lat, r.from_lng) if r.from_lat is not None and r.from_lng is not None else CITY_COORDS.get(r.from_city)
            if c:
                dist = round(haversine_km(lat, lng, c[0], c[1]), 1)
        if radius_km is not None and dist is not None and dist > radius_km:
            continue
        dist_by_id[r.id] = dist
        kept.append(r)
    kept = _hide_blocked(kept, user, session)   # прячем заблокированных до подсчёта total/пагинации
    kept = _hide_trusted_only(kept, user, session)   # «только для своих» видит лишь L3
    total = len(kept)
    eff_limit = min(max(1, limit), 200) if limit is not None else DEFAULT_FEED_LIMIT
    page_ids = [r.id for r in kept[max(0, offset):max(0, offset) + eff_limit]]
    by_id = (
        {r.id: r for r in session.exec(select(Ride).where(Ride.id.in_(page_ids))).all()}
        if page_ids else {}
    )
    page = [by_id[i] for i in page_ids if i in by_id]   # порядок страницы сохраняем
    users_map, profiles, rating_agg, trips_agg = drivers_bundle(session, {r.driver_id for r in page})
    items = []
    for r in page:
        out = public_ride_payload(ride_out_with(r, users_map, profiles, rating_agg, trips_agg)).model_dump()
        out["distance_km"] = dist_by_id.get(r.id)
        items.append(out)
    return {"count": total, "items": items}   # count = всего (чтобы клиент знал, есть ли «ещё»)


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
        raise HTTPException(403, "Это не ваша поездка")
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
        return public_ride_payload(ride_out(ride, session))   # идемпотентно (двойной тап)
    if ride.status == RideStatus.done:
        raise HTTPException(400, "Поездка уже завершена")
    affected = _live_bookings(session, ride_id)
    ride.status = RideStatus.cancelled
    session.add(ride)
    for b in affected:
        b.status = BookingStatus.cancelled
        session.add(b)
    session.commit()                     # атомарно: поездка+брони одной транзакцией
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
    return public_ride_payload(ride_out(ride, session))


@router.post("/rides/{ride_id}/complete", response_model=RideOut)
def complete_ride(ride_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель завершает рейс целиком: поездка → done, подтверждённые/в-пути брони → done,
    ожидающие (pending) → cancelled (рейс кончился — «висящих» заявок не оставляем).
    Идемпотентно: повторное завершение — no-op."""
    ride = _ride_owned(session, ride_id, user)
    if ride.status == RideStatus.done:
        return public_ride_payload(ride_out(ride, session))   # идемпотентно
    if ride.status == RideStatus.cancelled:
        raise herr(400, "Поездка отменена — завершать нечего", "Сәфәр кире алынған — тамамлар нәмә юҡ")
    affected = _live_bookings(session, ride_id)
    ride.status = RideStatus.done
    session.add(ride)
    done_ids: list[int] = []
    for b in affected:
        b.status = BookingStatus.done if b.status != BookingStatus.pending else BookingStatus.cancelled
        session.add(b)
        if b.status == BookingStatus.done:
            done_ids.append(b.passenger_id)
    session.commit()
    session.refresh(ride)
    notify_map_changed()
    # Как и отмена (см. cancel_ride): запись в Центре уведомлений + два языка. Здесь это ещё
    # и приглашение оценить поездку — без следа оно живёт ровно до пропущенного пуша.
    route = f"{ride.from_city} → {ride.to_city}"
    for pid in done_ids:
        push_notification(
            session, pid, "ride",
            "Поездка завершена", "Сәфәр тамамланды",
            f"{route}: спасибо, что ехали вместе! Оцени поездку.",
            f"{route}: бергә барғаныңа рәхмәт! Сәфәрҙе баһала.",
            ref_kind="ride", ref_id=ride.id,
        )
    return public_ride_payload(ride_out(ride, session))


@router.get("/rides/{ride_id}", response_model=RideOut)
def get_ride(ride_id: int, user: Optional[User] = Depends(current_user_optional),
             session: Session = Depends(get_session)):
    ride = session.get(Ride, ride_id)
    if not ride:
        raise herr(404, "Поездка не найдена", "Сәфәр табылманы")
    out = public_ride_payload(ride_out(ride, session))
    # V5: те же фильтры, что в ленте — «только для своих» скрыта от не-L3, поездка в связке
    # блокировки не отдаётся по прямому id (иначе обход only_trusted/blocked + анонимный скрейпинг).
    visible = _hide_trusted_only(_hide_blocked([out], user, session), user, session)
    if not visible:
        raise herr(404, "Поездка не найдена", "Сәфәр табылманы")   # не раскрываем существование закрытой поездки
    return visible[0]
