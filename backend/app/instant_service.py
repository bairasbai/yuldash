"""«Быстрый заказ» (такси-режим, Фаза 2) — сервисный слой.

Три части, все переиспользуют существующую инфраструктуру Юлдаша:
  1. Presence — координаты водителя «на линии» в Redis GEO (эфемерно, в БД не пишем).
  2. Тариф — сервер САМ считает цену (haversine × road_k), клиенту не верим.
  3. Заказ — машина состояний + matcher (подбор ближайшего водителя, офферы).

Приватность: координаты не логируем; телефоны сторон раскрываются ТОЛЬКО после accept.
Без Redis всё работает мягко: presence пустой → matcher никого не находит и отдаёт
пассажиру «рядом никого» (заказ → expired), но сервис не падает.

Прод-примечание: matcher в v1 синхронный (на запросе), таймаут оффера разрешается лениво
(reconcile при чтении заказа/поллинге оффера). Для честного фонового таймаута — arq-воркер
на том же Redis (см. docs) — не обязателен для работы, вынесен как следующий шаг.
"""
import re
from datetime import timedelta
from enum import Enum
from typing import Optional

from sqlalchemy import update
from sqlmodel import Session, select

from sqlalchemy import func

from .config import settings
from .errors import herr
from . import car_class as cc
from . import class_rollout
from . import pricing
from . import promo_ride
from .models import (
    Booking, BookingStatus, DriverProfile, InstantOrder, InstantOrderStatus as S, OfferDecline,
    Tariff, TripShare, TrustedContact, User,
)
from .services import (pick_lang, sms_lang_of,
    blocked_user_ids, haversine_km, may_send_family_sms, push_bilingual, send_push,
                       send_text, user_rating)
from .timeutil import utcnow

PRESENCE_KEY = "presence"                    # Redis GEO-множество координат водителей «на линии»
RADII_KM = (3, 7, 15)                        # город → пригород → межгород РБ (расширяем при пустом круге)


class Actor(str, Enum):
    passenger = "passenger"
    driver = "driver"
    system = "system"


# Из терминальных состояний выхода нет.
TERMINAL = (S.done, S.cancelled, S.expired)
# Разрешённые ПЕРЕХОДЫ водителя: (из, actor=driver) -> куда.
ALLOWED = {
    (S.offered, Actor.driver): S.accepted,
    (S.accepted, Actor.driver): S.arriving,
    (S.arriving, Actor.driver): S.onboard,
    (S.onboard, Actor.driver): S.done,
}
# После accept телефоны/контакты раскрыты.
UNLOCKED = (S.accepted, S.arriving, S.onboard, S.done)
# Кто когда может отменить (scheduled — предзаказ ещё до поиска, отменяется пассажиром).
PASSENGER_CANCELLABLE = (S.scheduled, S.created, S.searching, S.offered, S.accepted, S.arriving)
DRIVER_CANCELLABLE = (S.accepted, S.arriving, S.onboard)


# ============================ Presence (Redis GEO) ============================
# Тесты подменяют клиента (_redis_override = fakeredis); прод — общий _cache_client из services.
_redis_override = None


def _redis():
    if _redis_override is not None:
        return _redis_override
    from .services import _cache_client
    return _cache_client()


def presence_heartbeat(driver_id: int, lat: float, lng: float) -> bool:
    """Водитель «на линии» шлёт координаты (~раз в 10-15с). Пишем в GEO + ключ-heartbeat с TTL.
    Пропал heartbeat → TTL сам чистит, водитель становится невидим. Без Redis — no-op (False).
    Анти-фрод (B8-3): телепорт (скорость > 200 км/ч от прошлой точки) → точку НЕ публикуем,
    только копим счётчик подозрительности (3+/час → флаг админу). Запрос не роняем."""
    r = _redis()
    if r is None:
        return False
    from . import antifraud as af   # локальный импорт — без циклов на старте
    if not af.teleport_filter(r, driver_id, lat, lng):
        return False   # GPS-спуфинг/телепорт: точка игнорируется, водитель не двигается в GEO
    try:
        r.geoadd(PRESENCE_KEY, (lng, lat, f"driver:{driver_id}"))
        r.set(f"presence:hb:{driver_id}", "1", ex=settings.presence_ttl_sec)
        return True
    except Exception:  # noqa: BLE001 — presence не должен ронять запрос
        return False


def presence_alive(r, driver_id: int) -> bool:
    """heartbeat ещё жив? (защита от «залипших» в GEO координат без свежего пинга)."""
    try:
        return bool(r.exists(f"presence:hb:{driver_id}"))
    except Exception:  # noqa: BLE001
        return False


def presence_offline(driver_id: int) -> None:
    """Убрать водителя из presence (снял тумблер «на линии» / завершил смену)."""
    r = _redis()
    if r is None:
        return
    try:
        r.zrem(PRESENCE_KEY, f"driver:{driver_id}")
        r.delete(f"presence:hb:{driver_id}")
    except Exception:  # noqa: BLE001
        pass


def driver_go_offline(session, profile) -> None:
    """ЕДИНСТВЕННАЯ дверь «снять водителя с линии»: флаг в базе + чистка presence.

    Снять с линии можно двумя путями: человек сам щёлкает тумблер и система снимает его
    принудительно (просроченные документы, `doc_check.py`). Чистку координат из Redis звал
    только первый — у GEO-множества нет срока жизни, поэтому последнее местоположение
    водителя, снятого за документы, лежало там вечно. Заказы ему не шли (гейт по базе), так
    что поломки видно не было — а точка человека оставалась. Это ровно §8 «чувствительное
    не храним дольше нужного».

    Коммит — на вызывающем: снятие с линии обычно часть большей операции.
    Сторож двери — `test_driver_offline_goes_through_one_door`.
    """
    profile.online = False
    session.add(profile)
    presence_offline(profile.user_id)


def nearby_drivers(lat: float, lng: float, limit: int = 8) -> list[dict]:
    """Свободные машины «на линии» рядом с пассажиром — АНОНИМНЫЕ позиции + ≈ETA до подачи.

    Только РЕАЛЬНЫЕ данные из presence (Redis GEO), без выдуманного: показываем машины,
    которые действительно на линии рядом. Личность водителя НЕ раскрываем (ни id, ни имя,
    ни телефон) — только точка на карте и оценка «≈N мин до тебя» (та же средняя скорость,
    что в оценке заказа). Нет Redis → пустой список (честно «не знаю», UI просто без машинок)."""
    r = _redis()
    if r is None:
        return []
    try:
        found = r.geosearch(PRESENCE_KEY, longitude=lng, latitude=lat,
                            radius=settings.surge_radius_km, unit="km",
                            withcoord=True, withdist=True, sort="ASC", count=limit * 2)
    except Exception:  # noqa: BLE001 — сбой GEO → просто без машинок, не падаем
        return []
    out: list[dict] = []
    for m in found:
        # withdist+withcoord → [member, dist_km, [lng, lat]]
        try:
            member, dist_km, coord = m[0], float(m[1]), m[2]
            did = _member_driver_id(member)
        except (ValueError, IndexError, TypeError, AttributeError):
            continue
        if not presence_alive(r, did):
            continue
        eta = max(1, round(dist_km / settings.instant_avg_speed_kmh * 60))
        out.append({"lat": float(coord[1]), "lng": float(coord[0]), "eta_min": eta})
        if len(out) >= limit:
            break
    return out


# ============================ Сурж (честная наценка, волна 2 §5) ============================
# Ступени спрос/предложение → k. Потолок — surge_max_k (обещание пользователям: не выше ×1.5).
SURGE_STEPS = ((3.0, 1.5), (2.0, 1.3), (1.5, 1.2), (1.0, 1.1))


def _surge_supply(r, lat: float, lng: float) -> int:
    """Живые водители «на линии» в радиусе города заказа (Redis GEO + heartbeat)."""
    try:
        found = r.geosearch(PRESENCE_KEY, longitude=lng, latitude=lat,
                            radius=settings.surge_radius_km, unit="km")
    except Exception:  # noqa: BLE001 — сбой GEO → предложение неизвестно
        return 0
    alive = 0
    for member in found:
        try:
            did = _member_driver_id(member)
        except (ValueError, IndexError, AttributeError):
            continue
        if presence_alive(r, did):
            alive += 1
    return alive


def _surge_demand(session: Session, lat: float, lng: float) -> int:
    """Неудовлетворённый спрос: searching/created заказы за окно surge_window_min
    в радиусе surge_radius_km от точки подачи."""
    since = utcnow() - timedelta(minutes=settings.surge_window_min)
    rows = session.exec(
        select(InstantOrder.from_lat, InstantOrder.from_lng).where(
            InstantOrder.status.in_([S.created, S.searching]),
            InstantOrder.created_at >= since,
        )
    ).all()
    return sum(1 for flat, flng in rows
               if haversine_km(lat, lng, flat, flng) <= settings.surge_radius_km)


def surge_k_for(session: Session, lat: float, lng: float) -> float:
    """Динамический сурж-коэффициент для точки подачи. Честный: только при РЕАЛЬНОМ
    спросе (заказов больше, чем машин рядом), с потолком surge_max_k. Ступени:
    ratio <1 → 1.0; ≥1 → 1.1; ≥1.5 → 1.2; ≥2 → 1.3; ≥3 → 1.5.
    Без Redis (предложение неизвестно) → 1.0: не наживаемся на слепоте, не падаем."""
    if not settings.surge_enabled:
        return 1.0
    r = _redis()
    if r is None:
        return 1.0
    demand = _surge_demand(session, lat, lng)
    if demand <= 0:
        return 1.0
    ratio = demand / max(_surge_supply(r, lat, lng), 1)
    for threshold, k in SURGE_STEPS:
        if ratio >= threshold:
            return min(k, settings.surge_max_k)
    return 1.0


def surge_note(k: float) -> Optional[dict]:
    """Прозрачное объяснение наценки ДО заказа (RU + черновой BA). k=1.0 → None."""
    if k <= 1.0:
        return None
    pct = int(round((k - 1.0) * 100))
    return {
        "ru": f"Сейчас заказов больше обычного — цена выше на {pct}%. Вызвать или подождать?",
        "ba": f"Хәҙер заказдар ғәҙәттәгенән күберәк — хаҡ {pct}%-ҡа юғарыраҡ. Саҡырырғамы, әллә көтөргәме?",
    }


# ============================ Ночной тариф (аудит 2026-07-26) ============================
# В −30 в 5 утра по дневной цене никто не поедет — водитель просто не выйдет на линию, и
# заказ умрёт в «рядом никого». Надбавка живёт в БД (Tariff.night_k/from/to), а не в коде:
# по умолчанию night_k=1.0 → ничего не меняется, пока Александр не настроит окно.
def local_hour(now=None) -> int:
    """Час по МЕСТНОМУ времени (Уфа = UTC+5, settings.local_tz_offset_hours).
    Сервер живёт в UTC: в 5 утра по Уфе на нём ещё полночь — по UTC-часу ночное окно
    срабатывало бы не тогда, когда людям холодно."""
    now = now or utcnow()
    return (now + timedelta(hours=settings.local_tz_offset_hours)).hour


def in_night_window(hour: int, frm: int, to: int) -> bool:
    """Час попадает в окно [frm, to)? Ночное окно почти всегда ПЕРЕХОДИТ ЧЕРЕЗ ПОЛНОЧЬ
    (22→6), поэтому наивное `frm <= hour < to` тут даёт пустое множество — разбираем оба случая."""
    if frm == to:
        return False                    # окно нулевой длины — надбавки нет
    if frm < to:
        return frm <= hour < to         # окно внутри суток (напр. «утренний» 5→7)
    return hour >= frm or hour < to     # через полночь


def night_k_for(t: Optional[Tariff], now=None) -> float:
    """Ночной коэффициент тарифа ПРЯМО СЕЙЧАС (1.0 = не настроен / сейчас день)."""
    if t is None or not t.night_k or t.night_k <= 1.0:
        return 1.0
    return t.night_k if in_night_window(local_hour(now), t.night_from_hour, t.night_to_hour) else 1.0


def total_k(t: Optional[Tariff], surge: float, now=None,
            pickup: float = 1.0, weather: float = 1.0) -> float:
    """Итоговый множитель = спрос × ночь × погода × дальняя подача, с единым потолком.

    Дополнительные параметры имеют нейтральные значения по умолчанию: старые вызовы и тесты
    сохраняют прежнее поведение. Пробки сюда не входят второй раз — они уже учтены в eta_min
    дорожного маршрута и компоненте Tariff.per_min.
    """
    return pricing.combine_market_factors(
        surge, night_k_for(t, now), pickup, weather,
        max_k=settings.surge_max_k,
    )


def night_note(k: float) -> Optional[dict]:
    """Честное объяснение ночной цены ДО заказа (RU + черновой BA). k<=1.0 → None."""
    if k <= 1.0:
        return None
    pct = int(round((k - 1.0) * 100))
    return {
        "ru": f"Ночной тариф: сейчас дороже на {pct}% — в это время машин на линии мало.",
        "ba": f"Төнгө тариф: хәҙер {pct}%-ҡа ҡиммәтерәк — был ваҡытта линияла машина аҙ.",
    }


# ============================ Карта спроса (тепловые зоны «где сейчас ищут») ============================
# Округление координат зоны, знаков после запятой. 2 знака ≈ сетка ~1 км (на широте РБ ~55°N:
# 0.01° широты ≈ 1.1 км, 0.01° долготы ≈ 0.64 км). ПРИВАТНОСТЬ: точка конкретного пассажира
# обобщается до ячейки сетки — личность и точный адрес не раскрываются.
DEMAND_GRID_DIGITS = 2


def _active_search_points(session: Session) -> list[tuple]:
    """Активные поиски за окно surge_window_min — ТОТ ЖЕ источник, что и спрос для суржа
    (_surge_demand): заказы в статусах created/searching. Переиспользуем, не дублируем сбор."""
    since = utcnow() - timedelta(minutes=settings.surge_window_min)
    rows = session.exec(
        select(InstantOrder.from_lat, InstantOrder.from_lng).where(
            InstantOrder.status.in_([S.created, S.searching]),
            InstantOrder.created_at >= since,
        )
    ).all()
    return [(flat, flng) for flat, flng in rows if flat is not None and flng is not None]


def demand_zones(session: Session, city: Optional[str] = None) -> dict:
    """АНОНИМНЫЕ тепловые зоны спроса для водителя: «где сейчас ищут такси».
    Только агрегаты — активные поиски огрубляются до сетки ~1 км и группируются в зоны
    (без личности, телефонов и конкретных заказов). weight нормируется 0..1 (относительно
    самой горячей зоны), requests — сколько активных поисков в зоне.

    city (опц.) — фильтр по ближайшему НП точки поиска (name_ru/name_ba, casefold).
    Приватность/честность: зоны, где такси сейчас ВЫКЛЮЧЕНО (глобально или в этом городе),
    в ответ не попадают → выключенный город отдаёт пустой zones."""
    from . import geo, taxi as taxi_mod
    want = (city or "").strip().casefold() or None
    buckets: dict = {}
    for lat, lng in _active_search_points(session):
        if want is not None:
            st = geo.nearest_settlement(session, lat, lng)
            names = set()
            if st is not None:
                names.add(st.name_ru.casefold())
                if st.name_ba:
                    names.add(st.name_ba.casefold())
            if want not in names:
                continue
        key = (round(lat, DEMAND_GRID_DIGITS), round(lng, DEMAND_GRID_DIGITS))
        buckets[key] = buckets.get(key, 0) + 1
    zones = []
    if buckets:
        max_req = max(buckets.values())
        for (zlat, zlng), cnt in buckets.items():
            # Такси выключено в этой зоне (глобально/город) → не показываем (честно + приватно).
            if not taxi_mod.availability(session, zlat, zlng)["enabled"]:
                continue
            zones.append({
                "lat": zlat, "lng": zlng,
                "weight": round(cnt / max_req, 3),
                "requests": cnt,
            })
        zones.sort(key=lambda z: -z["requests"])
    return {"zones": zones, "updated_at": utcnow().isoformat()}


# ============================ Тариф (сервер считает сам) ============================
def round_to_10(x: float) -> int:
    return int(round(x / 10.0)) * 10


def zone_for_km(dist_km: float) -> str:
    return "intercity" if dist_km > settings.instant_intercity_km else "city"


def active_tariff(session: Session, zone: str, category: str) -> Optional[Tariff]:
    t = session.exec(
        select(Tariff).where(Tariff.zone == zone, Tariff.category == category, Tariff.active == True)  # noqa: E712
    ).first()
    if t:
        return t
    # фолбэк: любой активный тариф этой зоны (категория не настроена)
    return session.exec(
        select(Tariff).where(Tariff.zone == zone, Tariff.active == True)  # noqa: E712
    ).first()


def _tariff_price(t: Tariff, dist_km: float, eta_min: float, surge: float) -> int:
    """Цена по тарифу: max(min_price, (base + per_km·dist + per_min·eta) · k · surge),
    округление до 10 ₽. Tariff.k — статичный АВАРИЙНЫЙ множитель (по умолчанию 1.0,
    правится в БД); динамический сурж — отдельным surge (двойного счёта нет)."""
    raw = t.base + t.per_km * dist_km + t.per_min * eta_min
    return max(t.min_price, round_to_10(raw * t.k * surge))


def _price_factors(route: pricing.RouteMetrics, surge: float, pickup: float,
                   weather: pricing.WeatherMetrics, night: float, dynamic: float) -> list[dict]:
    """Serializable, bilingual explanation of every signal used for the upfront fare."""
    factors: list[dict] = []
    if route.source == "yandex":
        factors.append({
            "code": "route", "kind": "base", "k": 1.0, "active": True,
            "title_ru": "Маршрут по дорогам", "title_ba": "Юлдар буйлап маршрут",
            "description_ru": "Дистанция и время рассчитаны по реальному дорожному маршруту.",
            "description_ba": "Аралыҡ һәм ваҡыт ысын юл маршруты буйынса иҫәпләнде.",
        })
        if route.traffic_type in ("realtime", "forecast"):
            factors.append({
                "code": "traffic", "kind": "duration", "k": route.traffic_k, "active": True,
                "title_ru": "Пробки учтены", "title_ba": "Тығындар иҫәпкә алынды",
                "description_ru": "Текущее или прогнозное движение уже включено во время поездки.",
                "description_ba": "Хәҙерге йәки фаразланған хәрәкәт сәфәр ваҡытына инде.",
            })
    else:
        factors.append({
            "code": "route", "kind": "base", "k": 1.0, "active": True,
            "title_ru": "Маршрут оценочный", "title_ba": "Маршрут яҡынса",
            "description_ru": "Сервис маршрутов недоступен: использована безопасная оценка по расстоянию.",
            "description_ba": "Маршрут сервисы асыҡ түгел: аралыҡ буйынса яҡынса иҫәп ҡулланылды.",
        })
    if surge > 1.0:
        factors.append({
            "code": "demand", "kind": "multiplier", "k": surge, "active": True,
            "title_ru": "Высокий спрос", "title_ba": "Ихтыяж юғары",
            "description_ru": "Активных заказов сейчас больше, чем свободных машин рядом.",
            "description_ba": "Хәҙер әүҙем заказдар яҡындағы буш машиналарҙан күберәк.",
        })
    if pickup > 1.0:
        factors.append({
            "code": "pickup", "kind": "multiplier", "k": pickup, "active": True,
            "title_ru": "Дальняя подача", "title_ba": "Алыҫтан килеү",
            "description_ru": "Ближайшей свободной машине нужно дольше ехать до точки подачи.",
            "description_ba": "Иң яҡын буш машинаға килеп алыу нөктәһенә оҙағыраҡ барырға.",
        })
    if weather.available and weather.k > 1.0:
        factors.append({
            "code": "weather", "kind": "multiplier", "k": weather.k, "active": True,
            "title_ru": "Сложная погода", "title_ba": "Ҡатмарлы һауа торошо",
            "description_ru": "Осадки, сильный ветер или мороз учтены с небольшим ограниченным коэффициентом.",
            "description_ba": "Яуым-төшөм, көслө ел йәки һыуыҡ бәләкәй сикләнгән коэффициент менән иҫәпләнде.",
        })
    if night > 1.0:
        factors.append({
            "code": "night", "kind": "multiplier", "k": night, "active": True,
            "title_ru": "Ночной тариф", "title_ba": "Төнгө тариф",
            "description_ru": "Ночью машин на линии меньше; окно и коэффициент заданы в тарифе.",
            "description_ba": "Төндә линияла машина аҙыраҡ; ваҡыт һәм коэффициент тариф менән билдәләнә.",
        })
    raw = surge * pickup * weather.k * night
    if raw > settings.surge_max_k:
        factors.append({
            "code": "cap", "kind": "cap", "k": dynamic, "active": True,
            "title_ru": "Наценка ограничена", "title_ba": "Өҫтәмә хаҡ сикләнгән",
            "description_ru": f"Все коэффициенты вместе ограничены ×{settings.surge_max_k:g}.",
            "description_ba": f"Бөтә коэффициенттар бергә ×{settings.surge_max_k:g} менән сикләнгән.",
        })
    if route.has_tolls:
        factors.append({
            "code": "tolls", "kind": "notice", "k": 1.0, "active": True,
            "title_ru": "На маршруте платная дорога", "title_ba": "Маршрутта түләүле юл бар",
            "description_ru": "Стоимость проезда по платной дороге в тариф не включена.",
            "description_ba": "Түләүле юл хаҡы тарифҡа инмәгән.",
        })
    return factors


def estimate(session: Session, frm: tuple, to: tuple, category: str = "standard",
             when=None) -> dict:
    """Server-owned upfront fare v2.

    `when` — момент, НА КОТОРЫЙ считаем поездку (по умолчанию сейчас). Нужен предзаказу:
    раньше цена всегда бралась на время нажатия, и заказ «на пять утра», оформленный днём,
    считался по дневной ставке (аудит 2026-08-08, волна 163). Ночная надбавка существует именно
    затем, чтобы кто-то поехал в мороз в пять утра, — по дневной цене никто не берёт такой заказ,
    и человек остаётся на морозе с подтверждённым заказом, за который никто не едет. Обратный
    случай не лучше: заказ на полдень, оформленный ночью, брал ночную наценку с человека,
    который едет днём.

    Спрос и погода остаются «на сейчас» намеренно: предсказать пробки и метель на завтра нельзя,
    а врать точной цифрой хуже, чем показать честную оценку по тарифу.

    base = max(min_price, (base + per_km·road_distance + per_min·traffic_eta) · Tariff.k)
    final = base · min(demand × night × weather × pickup, surge_max_k), rounded to 10 ₽.

    Route/weather providers are optional and failure-safe. The response includes a bilingual
    factor breakdown; no price or coefficient is accepted from the client.
    """
    route = pricing.route_metrics(frm, to)
    dist_km = max(route.distance_km, 0.5)
    eta_min = max(route.duration_min, 0.1)
    zone = zone_for_km(dist_km)
    t = active_tariff(session, zone, category)
    if not t:
        # Человек нажал «Заказать», а тарифов в базе нет — это наша недонастройка, но текст
        # читает пассажир, и он должен быть на его языке (аудит 2026-08-08, волна 94).
        raise herr(503, "Такси пока не считает цену. Попробуй позже или поезжай попуткой 🚗",
                   "Такси хәҙергә хаҡты иҫәпләмәй. Һуңғараҡ ҡабатла йәки юлдаш менән бар 🚗")

    now = when or utcnow()
    surge = surge_k_for(session, frm[0], frm[1])
    nearest = nearby_drivers(frm[0], frm[1], limit=1)
    pickup_eta = int(nearest[0]["eta_min"]) if nearest else None
    pickup = pricing.pickup_k_for(pickup_eta)
    weather = pricing.weather_metrics(frm[0], frm[1])
    nk = night_k_for(t, now)
    dynamic = total_k(t, surge, now, pickup=pickup, weather=weather.k)
    base_price = _tariff_price(t, dist_km, eta_min, 1.0)
    price = _tariff_price(t, dist_km, eta_min, dynamic)

    # Классы для витрины. Закрытые (не набралось водителей) отдаём с open=false — клиент
    # покажет их строкой «скоро» с кнопкой «сообщить, когда появится», а не активной кнопкой.
    # Так мы ещё и меряем спрос до того, как искать машины.
    place = class_rollout.place_at(session, frm[0], frm[1])
    opened = class_rollout.open_categories(session, place)
    options = []
    # Тарифы забираем ОДНИМ запросом, а не по одному на класс.
    #
    # Оценка цены — самая частая операция в приложении: она пересчитывается каждый раз, когда
    # пассажир двигает точку подачи или назначения по карте. Здесь на каждый из четырёх классов
    # уходил свой запрос к базе, то есть четыре похода вместо одного — на каждое движение пальца.
    # Классов ровно четыре и растут они редко, зона одна: весь набор влезает в один SELECT,
    # дальше обычный поиск по словарю.
    tariffs = {
        t.category: t
        for t in session.exec(
            select(Tariff).where(Tariff.zone == zone, Tariff.active == True)  # noqa: E712
        ).all()
    }
    for cat in cc.ORDER_CATEGORIES:
        ct = tariffs.get(cat)
        if ct:
            ct_dynamic = total_k(ct, surge, now, pickup=pickup, weather=weather.k)
            options.append({
                "category": cat,
                "price": _tariff_price(ct, dist_km, eta_min, ct_dynamic),
                "base_price": _tariff_price(ct, dist_km, eta_min, 1.0),
                "dynamic_k": ct_dynamic,
                "open": cat in opened,
            })

    return {
        "price": price,
        "base_price": base_price,
        "distance_km": round(dist_km, 2),
        "eta_min": round(eta_min, 1),
        "pickup_eta_min": pickup_eta,
        "zone": zone,
        "category": category,
        "tariff_id": t.id,
        # Backward-compatible field: demand/supply only. The full product is dynamic_k.
        "surge_k": surge,
        "surge_note": surge_note(surge),
        "night": nk > 1.0,
        "night_k": nk,
        "night_note": night_note(nk),
        "pickup_k": pickup,
        "weather_k": weather.k,
        "weather_code": weather.code,
        "dynamic_k": dynamic,
        "pricing_cap_k": settings.surge_max_k,
        "pricing_version": "v2",
        "route_source": route.source,
        "traffic_type": route.traffic_type,
        "traffic_k": route.traffic_k,
        "has_tolls": route.has_tolls,
        "price_factors": _price_factors(route, surge, pickup, weather, nk, dynamic),
        "options": options,
    }


def seed_tariffs(session: Session) -> None:
    """Базовые тарифы город/межгород × Эконом/Комфорт. Нужны в проде (в отличие от
    seed_demo), поэтому сеются идемпотентно ПО СТРОКАМ: недостающая пара (zone, category)
    досеивается и в непустой БД (так прод получил Комфорт без ручного SQL).
    Значения — стартовые, правятся в БД без пересборки."""
    defaults = (
        # Эконом — СИЛЬНО ниже конкурентов.
        dict(zone="city", category="standard", base=70, per_km=11.0, per_min=3.0, min_price=100),
        dict(zone="intercity", category="standard", base=80, per_km=9.0, per_min=2.0, min_price=150),
        # Комфорт (§6): авто новее/чище, немного дороже.
        dict(zone="city", category="comfort", base=90, per_km=14.0, per_min=4.0, min_price=130),
        dict(zone="intercity", category="comfort", base=100, per_km=12.0, per_min=3.0, min_price=200),
        # ⚠️ СТАРТОВЫЕ ЦИФРЫ, УТОЧНИТ АЛЕКСАНДР (docs/taxi-classes-2026-08.md §8).
        # Бизнес — премиум-седан, очный допуск водителя; ориентир ×2 к Комфорту.
        dict(zone="city", category="business", base=150, per_km=25.0, per_min=7.0, min_price=300),
        dict(zone="intercity", category="business", base=200, per_km=22.0, per_min=5.0, min_price=500),
        # Минивэн — это про вместимость (6–8 мест), а не про люкс: между Комфортом и Бизнесом.
        dict(zone="city", category="minivan", base=120, per_km=18.0, per_min=5.0, min_price=200),
        dict(zone="intercity", category="minivan", base=150, per_km=16.0, per_min=4.0, min_price=350),
    )
    added = False
    for d in defaults:
        exists = session.exec(
            select(Tariff).where(Tariff.zone == d["zone"], Tariff.category == d["category"])
        ).first()
        if not exists:
            session.add(Tariff(**d, k=1.0))
            added = True
    if added:
        session.commit()


# ============================ Фолбэк класса: «в Комфорте никого» ============================
# Официальная механика Яндекса (инженерный блог, 22.01.2026): через 15 секунд поиска
# пассажиру показывают ДРУГИЕ тарифы с ценой и временем подачи, и он сам решает, добавлять
# ли их к поиску. Их же цифра: в каждом четвёртом случае ожидание дольше 30 секунд именно
# потому, что машин выбранного класса рядом нет.
#
# У нас 20 секунд, а не 15: расстояния сельские, подача дольше, и первые секунды честнее
# отдать выбранному классу. Молча класс не подменяем НИКОГДА — «заказал Комфорт, приехал
# Логан» это главный источник скандалов, там это считают нарушением водителя.

# Для Бизнеса альтернатива — только Комфорт. Человек, заказавший Бизнес, обычно едет на
# встречу или в аэропорт: Гранта вместо Мерседеса — не экономия, а испорченная поездка.
# Яндекс делает так же: премиуму показывает только премиальные альтернативы.
_FALLBACK_ALLOWED: dict = {
    "business": ("comfort",),
    "minivan": (),          # шестерым в седан не сесть — альтернативы нет в принципе
    "comfort": ("standard",),
    "standard": (),         # ниже Эконома ничего нет
}


def _category_price(session: Session, order: InstantOrder, category: str) -> Optional[int]:
    """Цена этого же маршрута по другой категории. Сурж берём ЗАФИКСИРОВАННЫЙ на заказе,
    чтобы альтернатива не «уехала» вверх, пока человек читает предложение."""
    zone = zone_for_km(order.distance_km or 0.0)
    t = active_tariff(session, zone, category)
    if not t:
        return None
    return _tariff_price(t, max(order.distance_km or 0.5, 0.5),
                         max(order.eta_min or 0.1, 0.1), order.surge_k or 1.0)


def fallback_options(session: Session, order: InstantOrder) -> list:
    """Что предложить пассажиру, если в его классе никого. Пустой список = предлагать нечего.

    Показываем только те классы, что открыты в этом месте (набор водителей) и по которым
    рядом реально кто-то есть — обещать «Эконом за 4 минуты», когда экономов тоже нет,
    значит соврать второй раз подряд.
    """
    base = order.category or "standard"
    already = {cc.class_to_category(c) for c in cc.parse_classes(order.fallback_categories or "")}
    place = class_rollout.place_at(session, order.from_lat, order.from_lng)
    opened = class_rollout.open_categories(session, place)
    out = []
    for cat in _FALLBACK_ALLOWED.get(base, ()):
        if cat == base or cat in already or cat not in opened:
            continue
        price = _category_price(session, order, cat)
        if price is None:
            continue
        out.append({"category": cat, "price": price,
                    "price_diff": price - (order.price_estimate or 0)})
    return out


def add_fallback_category(session: Session, order: InstantOrder, category: str) -> dict:
    """Пассажир согласился искать и в соседнем классе.

    Цена пересчитывается СРАЗУ на минимальную из согласованных и фиксируется — человек видел
    «Эконом 240 ₽» на экране и должен заплатить ровно 240, кто бы ни приехал. Это же снимает
    вопрос «а если приедет Комфорт»: приедет — повезёт, доплаты не будет.
    """
    cat = (category or "").strip().lower()
    allowed = set(_FALLBACK_ALLOWED.get(order.category or "standard", ()))
    if cat not in allowed:
        raise herr(400, "Этот класс нельзя добавить к поиску",
                   "Был класты эҙләүгә ҡушып булмай")
    place = class_rollout.place_at(session, order.from_lat, order.from_lng)
    if cat not in class_rollout.open_categories(session, place):
        raise herr(400, "Класс пока не работает в этом месте",
                   "Был класс был урында әлегә эшләмәй")
    cats = set(cc.parse_classes(order.fallback_categories or ""))
    cats.add(cc.category_to_class(cat))
    order.fallback_categories = cc.dump_classes(cats)
    price = _category_price(session, order, cat)
    if price is not None and price < (order.price_estimate or 0):
        order.price_estimate = price
    session.add(order)
    session.commit()
    session.refresh(order)
    return {"price": order.price_estimate,
            "categories": sorted(order_categories(order))}


# ============================ Отмены / ожидание / страйки (волна 2 §5, Модель А) ============================
# Деньги НЕ двигаем (Модель А «на доверии»): платная отмена / no-show только фиксируется на
# заказе (cancel_fee_kop) и даёт пассажиру страйк. ≥ strike_limit страйков за
# strike_window_days → пауза такси-заказов strike_pause_hours (гейт в POST /instant/orders).
# ПОПУТКА (Ride/Booking) не затрагивается.
NO_SHOW_REASON = "no_show"


def waiting_fee_kop(started, now) -> int:
    """Платное ожидание: первые wait_free_minutes бесплатно, дальше wait_fee_rub_per_min ₽
    за каждую ПОЛНУЮ минуту (неполная минута — в пользу пассажира). Целые копейки."""
    whole_min = int(max((now - started).total_seconds(), 0.0) // 60)
    billable = max(0, whole_min - settings.wait_free_minutes)
    # Потолок (волна 163): без него счётчик тикал бесконечно. Водитель нажал «я на месте»
    # и ушёл по делам — за три часа набегало 875 ₽, больше двух поездок. Пассажир при этом
    # не может ни остановить счётчик, ни доказать, что машины у подъезда не было.
    return min(billable * settings.wait_fee_rub_per_min, settings.wait_fee_cap_rub) * 100


def _order_base_fee_kop(session: Session, order: InstantOrder) -> int:
    """Штраф = подача (Tariff.base) этого заказа, копейки. Тариф не найден → 0 (не штрафуем вслепую)."""
    t = session.get(Tariff, order.tariff_id) if order.tariff_id else None
    return int(t.base) * 100 if t and t.base > 0 else 0


def passenger_cancel_fee_kop(session: Session, order: InstantOrder, now=None) -> int:
    """Штраф пассажира за отмену (Модель А: только фиксируем). Бесплатно, если:
    водитель ещё не назначен, ИЛИ прошло ≤ cancel_free_minutes от принятия, ИЛИ водитель
    ещё не нажал «Я на месте». Иначе — подача (tariff.base). Показываем ДО отмены."""
    now = now or utcnow()
    if order.driver_id is None or order.accepted_at is None:
        return 0
    if now - order.accepted_at <= timedelta(minutes=settings.cancel_free_minutes):
        return 0
    if order.waiting_started_at is None:
        return 0
    return _order_base_fee_kop(session, order)


def no_show_available_at(order: InstantOrder):
    """С какого момента водителю доступна кнопка «Пассажир не вышел»:
    «Я на месте» + бесплатное ожидание + no_show_extra_minutes. До «Я на месте» — None."""
    if order.waiting_started_at is None:
        return None
    return order.waiting_started_at + timedelta(
        minutes=settings.wait_free_minutes + settings.no_show_extra_minutes)


def _guard_no_show(order: InstantOrder, now) -> None:
    """No-show отмечается только когда водитель на месте («Я на месте») и честно отждал
    бесплатное окно + запас — защита пассажира от поспешной кнопки."""
    if order.status != S.arriving or order.waiting_started_at is None:
        raise herr(409, "«Пассажир не вышел» доступно после кнопки «Я на месте»",
                   "«Юлаусы сыҡманы» — «Мин урында» төймәһенән һуң мөмкин")
    allowed_at = no_show_available_at(order)
    if allowed_at is not None and now < allowed_at:
        raise herr(409,
                   f"Подожди ещё немного: бесплатное ожидание {settings.wait_free_minutes} мин "
                   f"+ {settings.no_show_extra_minutes} мин сверху",
                   f"Бер аҙ көт: түләүһеҙ көтөү {settings.wait_free_minutes} мин "
                   f"+ өҫтәмә {settings.no_show_extra_minutes} мин")


def order_strike_times(session: Session, passenger_id: int, since) -> list:
    """Метки времени страйков по ЗАКАЗАМ (платная отмена пассажира / no-show) за окно.
    Волна 2 §9: общий счётчик с resolved-жалобами (см. quality.passenger_pause_until)."""
    rows = session.exec(
        select(InstantOrder).where(
            InstantOrder.passenger_id == passenger_id,
            InstantOrder.status == S.cancelled,
            InstantOrder.cancelled_at >= since,
        )
    ).all()
    return [o.cancelled_at for o in rows
            if o.no_show or (o.cancel_fee_kop > 0 and o.cancel_by == Actor.passenger.value)]


def driver_cancel_times(session: Session, driver_id: int, since) -> list:
    """Когда водитель бросал УЖЕ ПРИНЯТЫЕ заказы за окно (разбор №2, 2026-08-03).

    Считаем только отмены после `accepted_at`: отказ от оффера — это нормально и ничего не
    стоит пассажиру, а вот «принял, посмотрел адрес и отменил» отбрасывает человека в поиск
    с нуля, и до сих пор это не стоило водителю ничего.

    `no_show` исключаем: там водитель как раз всё сделал по правилам — доехал, отждал, отметил.
    Наказывать за это значило бы учить водителей молча уезжать вместо честной отметки.
    """
    rows = session.exec(
        select(InstantOrder).where(
            InstantOrder.driver_id == driver_id,
            InstantOrder.status == S.cancelled,
            InstantOrder.cancel_by == Actor.driver.value,
            InstantOrder.cancelled_at >= since,
        )
    ).all()
    return [o.cancelled_at for o in rows if o.accepted_at is not None and not o.no_show]


def driver_pause_until(session: Session, driver_id: int, now=None):
    """Пауза ОФФЕРОВ водителю за брошенные заказы. Возврат: конец паузы или None."""
    now = now or utcnow()
    since = now - timedelta(days=settings.driver_cancel_window_days)
    strikes = driver_cancel_times(session, driver_id, since)
    if len(strikes) < settings.driver_cancel_limit:
        return None
    until = max(strikes) + timedelta(hours=settings.driver_cancel_pause_hours)
    return until if until > now else None


def driver_pause_message() -> str:
    """Текст паузы водителю: объясняем причину и срок, без обвинений."""
    h = settings.driver_cancel_pause_hours
    return (f"Заказы приходят с паузой {h} ч: несколько принятых заказов подряд были отменены. "
            f"Пассажир после такой отмены ищет машину заново. "
            f"Заказдар {h} сәғәт туҡтатылды: ҡабул ителгән заказдар бер нисә тапҡыр кире алынды.")


def strike_pause_until(session: Session, passenger_id: int, now=None):
    """Пауза такси-заказов за страйки. Страйк = платная отмена пассажира ИЛИ no-show.
    ≥ strike_limit страйков за strike_window_days → пауза strike_pause_hours от последнего
    страйка. Возврат: datetime конца паузы или None (можно заказывать)."""
    now = now or utcnow()
    since = now - timedelta(days=settings.strike_window_days)
    strikes = order_strike_times(session, passenger_id, since)
    if len(strikes) < settings.strike_limit:
        return None
    until = max(strikes) + timedelta(hours=settings.strike_pause_hours)
    return until if until > now else None


def strike_pause_message() -> str:
    """Тёплый текст паузы. RU + черновой BA одной строкой (detail показывается как есть)."""
    h = settings.strike_pause_hours
    return (f"Такси взяло паузу: за неделю накопилось несколько поздних отмен. "
            f"Попробуй снова через {h} ч — а попутка работает как обычно 💚"
            f" · Такси пауза алды: аҙнала бер нисә һуң кире алыу йыйылды. "
            f"{h} сәғәттән ҡабат ҡара — ә юлдаш ғәҙәттәгесә эшләй 💚")


# ============================ Машина состояний (под замком) ============================
def _guard_owns(order: InstantOrder, actor: Actor, user_id: int) -> None:
    """Владелец действия: водитель — назначенный на заказ, пассажир — создатель."""
    if actor == Actor.driver and order.driver_id != user_id:
        raise herr(403, "Ты не водитель этого заказа", "Һин был заказдың водителе түгел")
    if actor == Actor.passenger and order.passenger_id != user_id:
        raise herr(403, "Это не твой заказ", "Был һинең заказың түгел")


def _guard_actor(order: InstantOrder, actor: Actor, user_id: int, target: S) -> None:
    if actor == Actor.driver:
        if target == S.accepted:
            # Принять может ТОЛЬКО тот водитель, кому сейчас отправлен оффер, и пока он не истёк.
            if order.current_offer_driver_id != user_id:
                raise herr(403, "Заказ предложен другому водителю", "Заказ башҡа водителгә тәҡдим ителгән")
            if order.offer_expires_at and order.offer_expires_at < utcnow():
                raise herr(409, "Время на ответ истекло", "Яуап биреү ваҡыты үтте")
        else:
            _guard_owns(order, actor, user_id)


def transition(session: Session, order_id: int, actor: Actor, target: S,
               user_id: int, idempotent: bool = True) -> InstantOrder:
    """Один переход состояния заказа. Гонки безопасны на любой БД:
    — row-lock (`with_for_update`, паттерн из bookings.py) для Postgres в проде;
    — атомарный условный UPDATE `WHERE status = <ожидаемый>` — корректно и на SQLite
      (тесты), где FOR UPDATE игнорируется: проигравший гонку получает rowcount 0 → 409.

    accept НЕ идемпотентен (idempotent=False): два одновременных accept → второму 409.
    Остальные переходы идемпотентны (двойной тап не ломает)."""
    order = session.exec(
        select(InstantOrder).where(InstantOrder.id == order_id).with_for_update()
    ).first()
    if not order:
        raise herr(404, "Заказ не найден", "Заказ табылманы")

    # Двойной тап (не гонка accept): уже в целевом статусе — вернуть как есть.
    if idempotent and order.status == target:
        _guard_owns(order, actor, user_id)
        return order

    source = order.status
    if ALLOWED.get((source, actor)) != target:
        raise herr(409, "Этот шаг сейчас недоступен", "Был аҙым хәҙер мөмкин түгел")
    _guard_actor(order, actor, user_id, target)

    now = utcnow()
    values = {"status": target, f"{target.value}_at": now}
    if target == S.accepted:
        values.update(driver_id=user_id, current_offer_driver_id=None, offer_expires_at=None)
    if target == S.arriving:
        # «Я на месте»: подача завершена → пошло ожидание (5 мин бесплатно, дальше платно).
        values["waiting_started_at"] = now
    if target == S.onboard and order.waiting_started_at is not None:
        # Пассажир сел → фиксируем платное ожидание (целые копейки, задним числом не меняем).
        values["waiting_fee_kop"] = waiting_fee_kop(order.waiting_started_at, now)
    if target == S.done and order.price_final is None:
        # Сурж уже в price_estimate (зафиксирован при создании); ожидание — целыми ₽ сверху.
        values["price_final"] = order.price_estimate + order.waiting_fee_kop // 100

    # Атомарно: сдвигаем статус ТОЛЬКО если он всё ещё source. Иначе гонку проиграли.
    result = session.execute(
        update(InstantOrder)
        .where(InstantOrder.id == order_id, InstantOrder.status == source)
        .values(**values)
    )
    session.commit()
    if result.rowcount == 0:
        raise herr(409, "Заказ уже изменился", "Заказ үҙгәргән инде")
    if target == S.accepted:
        _cleanup_tried(order_id)
    fresh = session.get(InstantOrder, order_id)
    _notify_transition(session, fresh, target)
    return fresh


def cancel_order(session: Session, order_id: int, actor: Actor, user_id: int, reason: str = "") -> InstantOrder:
    """Отмена заказа пассажиром или водителем. Идемпотентна, под замком.

    Деньги (Модель А — только фиксируем, ничего не списываем):
    — пассажир отменяет поздно (>cancel_free_minutes от принятия И водитель уже «на месте»)
      → cancel_fee_kop = подача; это страйк;
    — водитель отменяет с reason="no_show" (пассажир не вышел, тайминг честно выдержан)
      → no_show=true + cancel_fee_kop = подача; это страйк пассажира;
    — обычная отмена водителем штрафа пассажиру НЕ даёт."""
    order = session.exec(
        select(InstantOrder).where(InstantOrder.id == order_id).with_for_update()
    ).first()
    if not order:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    _guard_owns(order, actor, user_id)
    # Очередь «подожду машину» держит заказ в `expired` с проставленным `wait_until`: статус
    # терминальный только на словах — фоновый воркер каждые пару минут перезапускает по нему
    # поиск (taxi_worker.retry_waiting_orders). Из-за раннего выхода ниже отмена такого заказа
    # молча отвечала 200 и не делала НИЧЕГО: человек нажал «Отмена», приложение сказало «ок»,
    # а через две минуты к подъезду всё равно приезжал водитель. Пассажира там нет, водитель
    # ждёт и жмёт «пассажир не вышел» → страйк и сутки без такси невиновному, водителю —
    # пустой пробег (аудит 2026-08-07).
    in_wait_queue = (actor == Actor.passenger and order.status == S.expired
                     and order.wait_until is not None)
    if order.status in TERMINAL and not in_wait_queue:
        return order   # уже терминальный — idempotent
    allowed = PASSENGER_CANCELLABLE if actor == Actor.passenger else DRIVER_CANCELLABLE
    if order.status not in allowed and not in_wait_queue:
        raise herr(409, "Сейчас отменить нельзя", "Хәҙер кире алып булмай")
    now = utcnow()
    # wait_until снимаем при ЛЮБОЙ отмене: отменённый заказ не может оставаться в очереди.
    values = dict(status=S.cancelled, cancelled_at=now, cancel_by=actor.value,
                  cancel_reason=(reason or "")[:200], current_offer_driver_id=None,
                  offer_expires_at=None, wait_until=None)
    # Анти-фрод (B8-8, «увод мимо приложения»): отмена ПОСЛЕ accept = телефоны/чат уже
    # открылись (contact-then-cancel). Только помечаем (счётчик в админ-пульсе) — не наказываем.
    if order.accepted_at is not None:
        values["contact_then_cancel"] = True
    if actor == Actor.passenger:
        fee = passenger_cancel_fee_kop(session, order, now)
        if fee > 0:
            values["cancel_fee_kop"] = fee
    elif (reason or "").strip() == NO_SHOW_REASON:
        _guard_no_show(order, now)
        values["no_show"] = True
        values["cancel_fee_kop"] = _order_base_fee_kop(session, order)
    result = session.execute(
        update(InstantOrder)
        .where(InstantOrder.id == order_id, InstantOrder.status == order.status)
        .values(**values)
    )
    session.commit()
    if result.rowcount == 0:
        raise herr(409, "Заказ уже изменился", "Заказ үҙгәргән инде")
    _cleanup_tried(order_id)
    fresh = session.get(InstantOrder, order_id)
    # Поездки не было → скидка по промокоду возвращается пассажиру. Один код даётся на всю жизнь
    # аккаунта, и сжечь его из-за того, что водитель не приехал, было бы нечестно.
    promo_ride.release(session, fresh)
    fresh = session.get(InstantOrder, order_id)
    _notify_cancel(session, fresh, actor)
    return fresh


# ============================ Matcher (подбор + офферы) ============================
def _tried_key(order_id: int) -> str:
    return f"instant:order:{order_id}:tried"


def _tried_set(r, order_id: int) -> set:
    try:
        return {int(x) for x in r.smembers(_tried_key(order_id))}
    except Exception:  # noqa: BLE001
        return set()


def _cleanup_tried(order_id: int) -> None:
    r = _redis()
    if r is None:
        return
    try:
        r.delete(_tried_key(order_id))
    except Exception:  # noqa: BLE001
        pass


def _member_driver_id(member: str) -> int:
    return int(member.split(":")[1])


def busy_driver_ids(session: Session, ids: list) -> set:
    """Водители, которых matcher НЕ предлагает: заняты активным заказом (accepted/arriving/onboard)
    ИЛИ уже держат открытый оффер (offered) на другой заказ. Второе — корень «дубля назначения»:
    без него matcher мог предложить одного водителя двум заказам разом, и accept обоих создавал
    двойное назначение."""
    if not ids:
        return set()
    busy = set()
    active = session.exec(
        select(InstantOrder.driver_id).where(
            InstantOrder.driver_id.in_(ids),
            InstantOrder.status.in_([S.accepted, S.arriving, S.onboard]),
        )
    ).all()
    busy |= {r for r in active if r is not None}
    offered = session.exec(
        select(InstantOrder.current_offer_driver_id).where(
            InstantOrder.current_offer_driver_id.in_(ids),
            InstantOrder.status == S.offered,
        )
    ).all()
    busy |= {r for r in offered if r is not None}
    return busy


def _order_zone_ctx(session: Session, order: InstantOrder) -> tuple:
    """Где заказ: (район/регион точки А, район/регион точки Б). Считаем один раз на вызов
    matcher'а — точки резолвим по всему справочнику, включая деревни."""
    from . import geo
    return (geo.area_at(session, order.from_lat, order.from_lng),
            geo.area_at(session, order.to_lat, order.to_lng))


def _zone_ok(session: Session, p: DriverProfile, a, b) -> bool:
    """Зона работы водителя vs заказ. Правила общие с курьером — geo.zone_allows.
    Зона не выбрана → берём всё (прежнее поведение). Точку, которую справочник не узнал,
    не режем: в глуши молчащий подбор хуже лишнего оффера."""
    from . import geo
    return geo.zone_allows(
        session,
        zone=p.work_zone, work_city=p.work_city, work_district=p.work_district,
        intercity=bool(p.work_intercity), regions=bool(p.work_regions),
        direction_id=p.work_direction_id, a=a, b=b,
        local_km=settings.instant_intercity_km,   # тот же порог, что у тарифа город/межгород
    )


def order_categories(order: InstantOrder) -> set:
    """Категории, по которым ищем водителя: выбранная пассажиром + те, что он САМ согласился
    добавить, когда в выбранной никого не оказалось (см. docs/taxi-classes-2026-08.md §5).
    Молча класс не подменяем никогда — «заказал Комфорт, приехал Логан» это главный источник
    скандалов у Яндекса, там это считают нарушением водителя, а не механикой."""
    cats = {(order.category or "standard")}
    cats |= {cc.class_to_category(c) for c in cc.parse_classes(getattr(order, "fallback_categories", ""))}
    return cats


def _verified_female_driver(u, p) -> bool:
    """Правило «женщина за рулём» одной точкой — импорт локальный: safety_logic тянет services."""
    from .safety_logic import is_verified_female_driver
    return is_verified_female_driver(u, p)


def eligible(session: Session, ids: list, order: InstantOrder) -> list:
    """Фильтр кандидатов: онлайн + верифицирован + не занят + не в блоке пассажира +
    не сам пассажир + зона работы + класс машины + опции салона.

    Класс: машина проходит классификатор независимо по каждому классу, из доступных водитель
    включает нужные сам (car_classes_enabled). Оплата — по тарифу ЗАКАЗА, не по классу машины.
    Опции: фильтр ЖЁСТКИЙ — заказ с детским креслом машине без кресла не предлагаем вообще."""
    if not ids:
        return []
    from .safety_logic import suspended_user_ids   # локальный импорт: safety_logic тянет services

    wanted_cats = order_categories(order)
    users = {u.id: u for u in session.exec(select(User).where(User.id.in_(ids))).all()}
    profs = {p.user_id: p for p in session.exec(select(DriverProfile).where(DriverProfile.user_id.in_(ids))).all()}
    busy = busy_driver_ids(session, ids)
    blocked = blocked_user_ids(session, order.passenger_id)
    # Пауза «Справедливости» (§2). Гейт на ручках закрывает ДЕЙСТВИЯ («выйти на линию», «принять
    # заказ»), но водитель мог быть на линии УЖЕ, когда разбор его отстранил: presence живёт
    # своим сроком, и матчер продолжал считать его годным (проверено: `eligible` возвращал
    # отстранённого; аудит 2026-08-12, волна 47). Ценой был не только зря потраченный круг
    # подбора — в пуше оффера едет АДРЕС ПОДАЧИ пассажира, а отстраняют в том числе за
    # домогательство. Один запрос на весь круг, как в ленте поездок, а не проверка на каждого.
    paused = suspended_user_ids(session)
    # …и остальные наказания водителя — тоже ОДНИМ запросом на круг. Их всего четыре: пауза §2
    # (выше), пауза качества за брошенные заказы (ниже, в цикле), ОТДЫХ (§8) и ДОЛГ. Последние
    # два подбор не знал вовсе: гейт стоит на выходе на линию и на приёме заказа, а водитель
    # мог быть на линии УЖЕ — устал на восьмом часу или получил просрочку по комиссии прямо
    # в смену. Проверено запросом: подбор возвращал обоих (аудит 2026-08-13, волна 60).
    #
    # Цена была не только в потерянном круге: в пуше оффера едет адрес подачи пассажира, и
    # система предлагала работу тому, кому сама же её запретила из-за усталости.
    from . import debt as debt_mod            # локальные импорты: без циклов на старте
    from . import workday as workday_mod
    resting = workday_mod.resting_driver_ids(session, ids)
    in_debt = debt_mod.blocked_driver_ids(session, ids)
    area_a, area_b = _order_zone_ctx(session, order)
    out = []
    for did in ids:
        u, p = users.get(did), profs.get(did)
        if not u or not p:
            continue
        if not p.online or not u.verified:
            continue
        if did in busy or did in blocked or did == order.passenger_id or did in paused:
            continue
        if did in resting or did in in_debt:
            continue          # отдых (§8) и долг: те же гейты, что на выходе на линию
        if not _zone_ok(session, p, area_a, area_b):
            continue
        avail = cc.available_or_legacy(getattr(p, "car_classes_available", ""), p.car_class)
        if not any(cc.driver_takes(cat, avail, getattr(p, "car_classes_enabled", ""))
                   for cat in wanted_cats):
            continue          # машина не того класса — или водитель этот класс не берёт
        if not cc.covers_options(getattr(p, "car_options", ""), getattr(order, "options", "")):
            continue          # нет детского кресла/места под коляску — заказ не предлагаем
        # Выбор «только женщина за рулём» — жёсткий, подмены быть не может. Здесь сошлись две
        # ветки, и обе половины нужны: пол читаем у ЧЕЛОВЕКА (`u.gender`), потому что он
        # переехал на User и старое поле профиля больше не пишется; но требуем ещё и
        # ПОДТВЕРЖДЕНИЕ модератором по фото прав — до 2026-08-07 водитель ставил пол себе сам,
        # и мужчина получал женские заказы, просто отметив галочку. Ночной заказ женщины
        # в райцентре — последнее место, где можно верить на слово.
        if getattr(order, "women_only", False) and not _verified_female_driver(u, p):
            continue
        if driver_pause_until(session, did) is not None:
            continue          # бросал принятые заказы — пауза офферов (разбор №2)
        out.append(did)
    return out


def _score(profs: dict, did: int, dist_km: float) -> float:
    p = profs.get(did)
    rating = p.rating if p else 5.0
    score = settings.instant_w_dist / max(dist_km, 0.3) + settings.instant_w_rating * rating
    # 🟠 Лестница качества (§9): просевший рейтинг → штраф к score, водитель РЕЖЕ получает
    # заказы (не блок — вернуть место можно хорошими поездками).
    if rating < settings.matcher_low_rating:
        score -= settings.matcher_penalty_low_rating
    return score


def rank(session: Session, ids: list, dist: dict) -> list:
    """Скоринг: ближе подача и выше рейтинг → раньше в очереди офферов."""
    profs = {p.user_id: p for p in session.exec(select(DriverProfile).where(DriverProfile.user_id.in_(ids))).all()}
    return sorted(ids, key=lambda did: -_score(profs, did, dist.get(did, 999.0)))


def candidates(r, session: Session, order: InstantOrder, exclude: set) -> list:
    """Расширяем радиус 3→7→15 км; первый непустой круг подходящих кандидатов → ранжируем."""
    for radius in RADII_KM:
        try:
            found = r.geosearch(
                PRESENCE_KEY, longitude=order.from_lng, latitude=order.from_lat,
                radius=radius, unit="km", withdist=True, sort="ASC",
            )
        except Exception:  # noqa: BLE001 — Redis/GEO сбой → круг пуст, не падаем
            found = []
        dist, ids = {}, []
        for row in found:
            member = row[0] if isinstance(row, (list, tuple)) else row
            try:
                did = _member_driver_id(member)
            except (ValueError, IndexError, AttributeError):
                continue
            if did in exclude or not presence_alive(r, did):
                continue
            dist[did] = row[1] if isinstance(row, (list, tuple)) and len(row) > 1 else 0.0
            ids.append(did)
        elig = eligible(session, ids, order)
        if elig:
            return rank(session, elig, dist)
    return []


def _expire_no_drivers(session: Session, order: InstantOrder, notify: bool = True) -> InstantOrder:
    """Никого рядом (или нет Redis) → заказ expired, пассажиру «рядом никого».

    notify=False — тихий прогон фонового воркера (очередь «рядом никого», taxi_worker):
    пассажир УЖЕ нажал «подождать», он в курсе; повторять ему «рядом никого» каждые
    две минуты — спам, из-за которого выключают уведомления."""
    session.execute(
        update(InstantOrder)
        .where(InstantOrder.id == order.id)
        .values(status=S.expired, expired_at=utcnow(), current_offer_driver_id=None, offer_expires_at=None)
    )
    session.commit()
    _cleanup_tried(order.id)
    fresh = session.get(InstantOrder, order.id)
    if notify:
        push_bilingual(session, fresh.passenger_id,
                       "Рядом никого", "Яҡында водитель юҡ",
                       "Пока не нашли водителя. Попробуй ещё раз или оставь заявку.",
                       "Водитель табылманы әле. Тағы ҡабатлап ҡара йәки ғариза ҡалдыр.",
                       data=_status_data(fresh, "expired"))
    return fresh


def cancel_for_suspended_passenger(session: Session, order: InstantOrder) -> InstantOrder | None:
    """Пассажир на паузе (§2) → заказ не ищет машину. Отменяем и честно говорим почему.
    Не на паузе → None, вызывающий продолжает обычным путём.

    Одна точка на два входа: активация предзаказа «на время» и любой круг подбора. Гейт на
    ручке «вызвать машину» закрывает только МОМЕНТ заказа, а между заказом и поездкой человека
    успевает отстранить разбор — и заказ в очереди «подожду машину» фоновый воркер перезапускал
    как ни в чём не бывало (проверено: воркер вернул заказ в поиск; аудит 2026-08-12, волна 47).

    Отменяем, а не подвешиваем: заказ без машины — это человек, который ждёт зря. Актор —
    пассажирская сторона; штрафа тут не возникает по построению (он считается только при поздней
    отмене уже назначенного водителя).

    Паузы у пассажира ДВЕ, и это оказалось важно. Первая — «Справедливость» (§2, разбор жалобы).
    Вторая — страйки за платные отмены и неявки (§5/§9): ручка «вызвать машину» отвечает на неё
    403 отдельной строкой. Волна 47 закрыла здесь только первую, и вторая продолжала ехать:
    заказ из очереди «подожду» воркер перезапускал, предзаказ активировался и искал машину
    (проверено запросом: поиск реально стартовал; аудит 2026-08-13, волна 59).

    Тексты разные, потому что это разные разговоры с человеком: в первом случае идёт разбор
    и есть куда посмотреть причину, во втором — временное ограничение, которое пройдёт само.
    """
    from .safety_logic import account_paused   # локальный импорт: safety_logic тянет services
    from . import quality as quality_mod
    if account_paused(session, order.passenger_id):
        ru = ("Аккаунт на паузе до разбора — машину вызвать не получится. "
              "Причина и срок в Центре справедливости.")
        ba = ("Иҫәп тикшереүгә тиклем паузада — машина саҡырып булмай. "
              "Сәбәбе һәм ваҡыты Ғәҙеллек үҙәгендә.")
    elif quality_mod.passenger_pause_until(session, order.passenger_id) is not None:
        ru = ("Заказы такси сейчас на паузе из-за отмен и неявок. Это временно — "
              "попутка работает как обычно 💚")
        ba = ("Кире алыуҙар һәм килмәүҙәр арҡаһында такси заказдары паузала. Был ваҡытлыса — "
              "юлдаш ғәҙәттәгесә эшләй 💚")
    else:
        return None
    cancelled = cancel_order(session, order.id, Actor.passenger, order.passenger_id,
                             "passenger_suspended")
    from .services import push_notification
    push_notification(
        session, order.passenger_id, "taxi",
        "Заказ отменён", "Заказ кире алынды",
        ru, ba,
        ref_kind="instant", ref_id=order.id,
    )
    return cancelled


def try_offer_next(session: Session, order: InstantOrder, notify: bool = True) -> InstantOrder:
    """Найти следующего кандидата и отправить ему оффер. Никого/лимит → expired."""
    stopped = cancel_for_suspended_passenger(session, order)
    if stopped is not None:
        return stopped
    r = _redis()
    if r is None:
        return _expire_no_drivers(session, order, notify)
    if order.search_round >= settings.instant_max_offers:
        return _expire_no_drivers(session, order, notify)
    cands = candidates(r, session, order, exclude=_tried_set(r, order.id))
    if not cands:
        return _expire_no_drivers(session, order, notify)
    did = cands[0]
    try:
        r.sadd(_tried_key(order.id), did)
        r.expire(_tried_key(order.id), 3600)
    except Exception:  # noqa: BLE001
        pass
    now = utcnow()
    session.execute(
        update(InstantOrder)
        .where(InstantOrder.id == order.id)
        .values(status=S.offered, offered_at=now,
                current_offer_driver_id=did,
                offer_expires_at=now + timedelta(seconds=settings.instant_offer_ttl_sec),
                search_round=order.search_round + 1)
    )
    session.commit()
    fresh = session.get(InstantOrder, order.id)
    _push_offer(session, fresh, did)
    return fresh


# Заказ «живой»: человек прямо сейчас едет или ждёт машину. Предзаказ (scheduled) сюда НЕ
# входит — он ещё не поездка, а запись в календаре. Один список на все места, где спрашивают
# «человек сейчас занят?»: подбор, воркер, создание заказа. Разные копии этого списка уже
# однажды разошлись (волна 76), поэтому он тут один.
LIVE_ORDER_STATUSES = (S.created, S.searching, S.offered, S.accepted, S.arriving, S.onboard)


def activate_scheduled(session: Session, order: InstantOrder) -> InstantOrder:
    """Активация предзаказа «на время»: scheduled → обычный поиск водителя.
    Цену/сурж пересчитываем ЗАНОВО на момент активации (не фиксируем при бронировании —
    честно: рынок мог измениться), затем стандартный matcher (searching → offered|expired).
    Зовётся вручную (клиент по таймеру) или лениво при GET /instant/scheduled, когда время
    подошло. Идемпотентно: не-scheduled заказ возвращаем как есть."""
    if order.status != S.scheduled:
        return order
    # ⬇️ Пауза «Справедливости» (§2) на ПАССАЖИРЕ. Гейт стоит на создании предзаказа, но между
    # созданием и временем поездки человека могли отстранить разбором — и предзаказ всё равно
    # ехал: активацию зовут ТРИ пути (кнопка клиента, ленивый GET /instant/scheduled и фоновый
    # воркер), поэтому проверка на ручке была бы бесполезна. Ставим её здесь, в одной точке.
    # Правило и текст — общие с обычным подбором (`cancel_for_suspended_passenger`).
    stopped = cancel_for_suspended_passenger(session, order)
    if stopped is not None:
        return stopped
    # ⬇️ «Один живой заказ на человека» — то же правило, что и при обычном заказе, но здесь оно
    # решает не про удобство, а про чужое время.
    #
    # Правило стояло только на создании (POST /instant/orders возвращает уже идущий заказ вместо
    # второго). Предзаказ его обходил: на одно и то же время можно было оформить сколько угодно,
    # и в час X все они уходили в поиск разом. Проверено запросом (аудит 2026-08-08, волна 76):
    # двенадцать предзаказов активировались за один GET, и КАЖДЫЙ водитель на линии получил
    # оффер от одного и того же пассажира. Поедет он с одним — остальные потратят время
    # и бензин впустую, а те, кто уже принял заказ и не дождался, получат «брошенный принятый»
    # и паузу за отмену. Один человек наказывал водителей, формально ничего не нарушая.
    #
    # Не отменяем — откладываем: заказ человек сделал сам, и он актуален. Освободится (доехал
    # или отменил) — активируется следующим проходом. Заказы уходят по одному, а не пачкой.
    busy = session.exec(
        select(InstantOrder).where(
            InstantOrder.passenger_id == order.passenger_id,
            InstantOrder.id != order.id,
            InstantOrder.status.in_(LIVE_ORDER_STATUSES),
        ).limit(1)
    ).first()
    if busy is not None:
        return order   # остаётся scheduled — ждёт своей очереди
    est = estimate(session, (order.from_lat, order.from_lng),
                   (order.to_lat, order.to_lng), order.category or "standard")
    session.execute(
        update(InstantOrder).where(InstantOrder.id == order.id, InstantOrder.status == S.scheduled)
        .values(price_estimate=est["price"], distance_km=est["distance_km"],
                eta_min=est["eta_min"], tariff_id=est["tariff_id"], surge_k=est["surge_k"])
    )
    session.commit()
    order = session.get(InstantOrder, order.id)
    if order.status != S.scheduled:
        return order   # гонку проиграли (кто-то активировал/отменил параллельно) — не дублируем поиск
    # Цена пересчитана заново → скидка по промокоду могла превысить допустимую долю подешевевшей
    # поездки. Ужимаем (только вниз), чтобы потолок кампании не обходился через предзаказ.
    promo_ride.reclamp(session, order)
    order = session.get(InstantOrder, order.id)
    return start_matching(session, order)


# Из каких статусов поиск можно (пере)запустить: свежесозданный заказ, предзаказ в момент
# активации, очередь «рядом никого» (expired + wait_until) и уже идущий круг подбора.
_MATCHABLE = (S.created, S.scheduled, S.searching, S.expired)


def start_matching(session: Session, order: InstantOrder, notify: bool = True) -> InstantOrder:
    """created → searching → (offered | expired). Зовётся при создании заказа и при
    перезапуске поиска из очереди «рядом никого» (там notify=False — см. _expire_no_drivers).

    Статус пишем УСЛОВНО (CAS), как `transition` и `cancel_order`. Раньше UPDATE был
    безусловным, а зовут функцию фоновые задачи: воркер выбирает пачку до 200 строк и идёт
    по ней с Redis-походом и пушем на каждую — объект в руках устаревает на секунды. Всё,
    что за это время стало `cancelled`/`accepted`/`done`, откатывалось назад в поиск: человек
    отменил заказ, получил «ок», а через минуту к нему ехал водитель (аудит 2026-08-07).

    Счётчик офферов обнуляем на каждом круге: `instant_max_offers` — предохранитель «сколько
    раз предлагать заказ ЗА КРУГ». Он копился за всю жизнь заказа, и очередь «подожду машину»
    после 8 суммарных офферов замолкала навсегда — полоска «ищем машину» живая, а сервер уже
    никому не предлагает, даже если свободный водитель стоит в ста метрах."""
    result = session.execute(
        update(InstantOrder)
        .where(InstantOrder.id == order.id, InstantOrder.status.in_(_MATCHABLE))
        .values(status=S.searching, searching_at=utcnow(), search_round=0)
    )
    session.commit()
    order = session.get(InstantOrder, order.id)
    if result.rowcount == 0:
        return order        # заказ успели закрыть/принять — поиск не начинаем
    return try_offer_next(session, order, notify)


def advance_after_no_accept(session: Session, order: InstantOrder, notify: bool = True) -> InstantOrder:
    """Оффер отклонён/протух → назад в searching → следующий кандидат.

    Условие `status = offered` — то же CAS, что в `start_matching`: без него протухший оффер
    из фоновой пачки воскрешал заказ, который водитель уже принял (заказ отбирался у него
    посреди подачи) или который пассажир уже отменил."""
    result = session.execute(
        update(InstantOrder)
        .where(InstantOrder.id == order.id, InstantOrder.status == S.offered)
        .values(status=S.searching, current_offer_driver_id=None, offer_expires_at=None)
    )
    session.commit()
    order = session.get(InstantOrder, order.id)
    if result.rowcount == 0:
        return order        # заказ уже не в оффере (принят/отменён/закрыт) — не трогаем
    return try_offer_next(session, order, notify)


def reconcile_offer(session: Session, order: InstantOrder) -> InstantOrder:
    """Ленивый таймаут: если оффер протух — двигаем к следующему кандидату.
    Так работает без фонового воркера (arq) — на чтении заказа/поллинге оффера."""
    if order.status == S.offered and order.offer_expires_at and order.offer_expires_at < utcnow():
        return advance_after_no_accept(session, order)
    return order


def decline_offer(session: Session, order_id: int, driver_id: int, reason: str = "") -> InstantOrder:
    """Водитель отклонил оффер → следующий кандидат. Идемпотентно."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if order.status != S.offered or order.current_offer_driver_id != driver_id:
        # оффер уже не актуален (принят/протух/отдан другому) — не ошибка
        return order
    if reason:
        # Журнал причин (разбор №2). Пишем ДО перехода, но отдельным try: сорвавшаяся запись
        # статистики не должна мешать водителю отказаться — отказ важнее аналитики.
        try:
            session.add(OfferDecline(order_id=order_id, driver_id=driver_id, reason=reason[:32]))
            session.commit()
        except Exception:  # noqa: BLE001
            session.rollback()
    return advance_after_no_accept(session, order)


# ============================ Push / приватность ============================
def passenger_stats(session: Session, passenger_id: int) -> tuple:
    """Рейтинг и опыт пассажира для оффера (B7a-4): (средняя★ | None, поездок).
    Рейтинг — общий анонимный агрегат (Rating по ratee_id: такси + попутка).
    Поездки = завершённые такси-заказы + завершённые брони попутки.
    Телефон/имя этим НЕ раскрываются — приватность до accept не тронута."""
    avg, cnt = user_rating(session, passenger_id)
    done_orders = session.exec(
        select(func.count(InstantOrder.id)).where(
            InstantOrder.passenger_id == passenger_id, InstantOrder.status == S.done)
    ).one()
    done_bookings = session.exec(
        select(func.count(Booking.id)).where(
            Booking.passenger_id == passenger_id, Booking.status == BookingStatus.done)
    ).one()
    rating = round(avg, 1) if cnt > 0 else None
    return rating, int(done_orders or 0) + int(done_bookings or 0)


def _push_offer(session: Session, order: InstantOrder, driver_id: int) -> None:
    """Оффер водителю — data-only payload с серверным gross/fee/net.

    Клиент не вычисляет комиссию сам: push и GET /instant/driver/offer используют один
    Decimal-расчёт и одну ступень комиссии, зафиксированную на created_at заказа.
    """
    from . import debt as debt_mod

    p_rating, p_trips = passenger_stats(session, order.passenger_id)
    gross_kop = max(int(order.price_estimate), 0) * 100
    fee_percent = debt_mod.driver_fee_percent(
        session, driver_id, order.created_at or utcnow()
    )
    fee_kop = debt_mod.order_commission_kop(order, fee_percent)
    net_kop = max(gross_kop - fee_kop, 0)
    net_rub, net_coins = divmod(net_kop, 100)
    net_text = f"{net_rub} ₽" if net_coins == 0 else f"{net_rub},{net_coins:02d} ₽"

    # Адрес назначения режем ТАК ЖЕ, как в карточке заказа (волна 128). Карточка честно
    # прячет номер дома, пока водитель не согласился везти, — потому что отказаться можно
    # бесплатно и сколько угодно раз, и адреса ночных пассажирок иначе собирались бы отказами.
    # А пуш выдавал «Гагарина, 5к2» целиком, да ещё поверх погасшего экрана: его читает
    # не только водитель, но и любой, кто стоит рядом с телефоном на стоянке.
    to_shown = street_only(order.to_text) if order.to_text else ""
    # Адрес ПОДАЧИ режем здесь же (аудит 2026-08-08, волна 143). Правило было применено
    # наполовину: «куда» обрезали, а «откуда» уходило целиком — «ул. Ленина, 12, кв. 43,
    # подъезд 2». Карточка заказа отдаёт подачу полностью намеренно, но только тому, кто уже
    # СОГЛАСИЛСЯ везти. А оффер приходит кандидатам ДО согласия и предлагается по очереди:
    # женщина вызывает такси от дома ночью, и её адрес с номером квартиры всплывает на
    # погашенных экранах нескольких чужих телефонов подряд — включая стоянку, где рядом стоят
    # чужие люди. Отказ бесплатный, так что собрать адреса можно одними отказами.
    #
    # Водителю на этом шаге хватает улицы: он решает, ехать ли в тот конец. Точный адрес,
    # подъезд и комментарий он получит в карточке сразу после «Беру».
    from_shown = pickup_street_only(order.from_text) if order.from_text else ""
    send_push(
        session, driver_id, "Новый заказ",
        f"{from_shown or 'Точка А'} → {to_shown or 'Точка Б'} · чистыми {net_text}",
        data={
            "type": "instant_offer",
            "order_id": str(order.id),
            # Legacy price remains for older Android builds.
            "price": str(order.price_estimate),
            "gross_kop": str(gross_kop),
            "fee_kop": str(fee_kop),
            "net_kop": str(net_kop),
            "fee_percent": str(fee_percent),
            "from": from_shown,      # без номера дома и квартиры — см. выше
            "to": to_shown,          # без номера дома — см. выше
            "ttl_sec": str(settings.instant_offer_ttl_sec),
            # Рейтинг/опыт пассажира (B7a-4): "" = новичок без оценок.
            "passenger_rating": "" if p_rating is None else str(p_rating),
            "passenger_trips": str(p_trips),
        },
        data_only=True,
    )


# «Поделиться поездкой» (B7b-2): SMS близким на ключевых переходах заказа. В отличие от
# попутки (пассажир жмёт «сел/доехал» сам) статусы такси двигает сервер — он и уведомляет.
_SHARE_STATUS_TEXT = {
    "sat": "сел(а) в такси",
    "done": "доехал(а), поездка завершена",
    "cancelled": "поездка на такси отменилась",
}
# То же по-башкирски: SMS близким уходили только по-русски, и в башкироязычной семье мама
# получала тревожное сообщение на чужом языке (аудит 2026-08-08, волна 95). Язык берём
# у пассажира — про язык его мамы мы ничего не знаем, а он знает.
_SHARE_STATUS_TEXT_BA = {
    "sat": "таксиға ултырҙы",
    "done": "барып етте, сәфәр тамамланды",
    "cancelled": "такси сәфәре кире алынды",
}


def _notify_order_shares(session: Session, order: InstantOrder, share_status: str) -> None:
    """SMS доверенным контактам, с кем пассажир поделился ЭТИМ заказом. Дедуп: у шаринга
    хранится last_status — повтор того же перехода SMS не шлёт (идемпотентные переходы)."""
    shares = session.exec(select(TripShare).where(TripShare.order_id == order.id)).all()
    if not shares:
        return
    passenger = session.get(User, order.passenger_id)
    who = (passenger.name if passenger and passenger.name else None) or "Твой близкий"
    text = _SHARE_STATUS_TEXT.get(share_status)
    text_ba = _SHARE_STATUS_TEXT_BA.get(share_status) or text
    for share in shares:
        if share.last_status == share_status or text is None:
            continue
        share.last_status = share_status
        session.add(share)
        contact = session.get(TrustedContact, share.contact_id)
        # Пометка «Только SOS» действует и в такси (волна 121). В попутке её учли (волна 81),
        # а здесь нет: та же мама, которую человек пометил «тревожить только при беде»,
        # получала SMS на каждый шаг такси-заказа. Одно обещание — две двери, закрыта была одна.
        if contact and contact.phone and contact.notify_by_default:
            # Общий суточный потолок SMS близким (волна 48). Тихо: статус едет автоматом
            # по ходу заказа, ошибку тут показывать некому и незачем.
            if may_send_family_sms(session, order.passenger_id, "status"):
                lang = sms_lang_of(session, order.passenger_id)
                send_text(contact.phone, pick_lang(
                    lang, f"Юлдаш: {who} {text}.", f"Юлдаш: {who} {text_ba}.",
                ))
    session.commit()


def _status_data(order: InstantOrder, status: str) -> dict:
    """data-payload пуша о ходе заказа (B9b-2): по type=instant_status клиент
    открывает экран этого заказа (тап по пушу → сразу к делу)."""
    return {"type": "instant_status", "order_id": str(order.id), "status": status}


def _notify_transition(session: Session, order: InstantOrder, target: S) -> None:
    """Пуш пассажиру на каждом переходе заказа (B9b-2) + data-payload type=instant_status.
    Дедуп не нужен: переходы одноразовые (машина состояний не повторяет target).

    Тексты — ЧЕТЫРЕ отдельных поля, а не «RU · BA» одной строкой (аудит 2026-08-12, волна 37):
    человек с башкирским интерфейсом получал сначала русский, а свой язык через точку
    в середине. Записи в Центре уведомлений эти переходы не оставляют намеренно — человек
    смотрит в экран заказа, и лента засорилась бы каждым шагом."""
    route = f"{order.from_text or ''} → {order.to_text or ''}".strip(" →")
    wait = settings.wait_free_minutes
    titles = {
        S.accepted: ("Водитель найден 🚗", "Водитель табылды 🚗",
                     "Водитель принял заказ — уже едет к тебе",
                     "Водитель заказды ҡабул итте — һиңә килә инде"),
        S.arriving: ("Машина на месте!", "Машина килеп етте!",
                     f"Водитель ждёт. Бесплатное ожидание — {wait} мин",
                     f"Водитель көтә. Түләүһеҙ көтөү — {wait} мин"),
        S.onboard: ("В пути", "Юлда", "Хорошей поездки!", "Хәйерле юл!"),
        S.done: ("Поездка завершена", "Сәфәр тамамланды", route, route),
    }
    if target in titles:
        title_ru, title_ba, body_ru, body_ba = titles[target]
        push_bilingual(session, order.passenger_id, title_ru, title_ba, body_ru, body_ba,
                       data=_status_data(order, target.value))
    # Близким (шаринг B7b-2): сел в машину / доехал.
    if target == S.onboard:
        _notify_order_shares(session, order, "sat")
    elif target == S.done:
        _notify_order_shares(session, order, "done")


def _notify_cancel(session: Session, order: InstantOrder, actor: Actor) -> None:
    """Отмена (B9b-2): водитель отменил → пассажиру; пассажир отменил → водителю.

    Через push_notification, а не send_push: кроме пуша остаётся запись в Центре уведомлений.
    Пуш может не дойти (телефон выключен, нет сети, уведомления отключены, дешёвый телефон
    прибил приложение ради батареи) — и тогда человек стоит у подъезда и ждёт машину, которая
    уже отменилась, без единого следа в приложении (аудит 2026-08-06).

    Промежуточные статусы (еду, на месте, в пути) остаются обычным пушем: там человек смотрит
    в экран, а запись о каждом шаге только засорила бы ленту. След нужен именно у отмены."""
    from .services import push_notification

    if actor == Actor.driver and order.passenger_id:
        if order.no_show:
            push_notification(
                session, order.passenger_id, "ride",
                "Поездка не состоялась", "Сәфәр булманы",
                "Водитель ждал, но не дождался. Частые несостоявшиеся поездки ставят такси на паузу",
                "Водитель көттө, ләкин көтөп ала алманы. Йыш ҡабатланһа — такси паузаға ҡуйыла",
                ref_kind="instant", ref_id=order.id, data=_status_data(order, "cancelled"))
        else:
            push_notification(
                session, order.passenger_id, "ride",
                "Заказ отменён", "Заказ кире алынды",
                "Водитель отменил заказ. Ищем другого?",
                "Водитель заказды кире алды. Башҡаһын эҙләйекме?",
                ref_kind="instant", ref_id=order.id, data=_status_data(order, "cancelled"))
    elif actor == Actor.passenger and order.driver_id:
        push_notification(
            session, order.driver_id, "ride",
            "Заказ отменён", "Заказ кире алынды",
            "Пассажир отменил заказ", "Пассажир заказды кире алды",
            ref_kind="instant", ref_id=order.id, data=_status_data(order, "cancelled"))
    # Близким (шаринг B7b-2): честно сообщаем, что поездка не состоялась.
    _notify_order_shares(session, order, "cancelled")


def maybe_receipt_reminder(session: Session, driver_id: Optional[int], now=None) -> bool:
    """B7b-4: после done — мягкое напоминание водителю про чек в «Мой налог» (обязанность
    самозанятого). НЕ интеграция с ФНС — только пуш. Дедуп: не чаще 1/сутки на водителя
    (метка receipt_reminder_at на DriverProfile — паттерн low_rating_advice_at)."""
    if driver_id is None:
        return False
    prof = session.exec(select(DriverProfile).where(DriverProfile.user_id == driver_id)).first()
    if prof is None:
        return False
    now = now or utcnow()
    if prof.receipt_reminder_at is not None and now - prof.receipt_reminder_at < timedelta(days=1):
        return False
    prof.receipt_reminder_at = now
    session.add(prof)
    session.commit()
    send_push(
        session, driver_id, "Не забудь чек в «Мой налог» 🧾",
        "После поездки самозанятый выдаёт чек пассажиру — пара касаний в приложении «Мой налог»"
        " · Сәфәрҙән һуң үҙмәшғүл пассажирға чек бирә — «Мой налог» ҡушымтаһында бер-ике баҫыу",
    )
    return True


# Сколько ещё видны телефоны сторон после завершённой поездки. Ровно столько же живёт
# «забытая вещь» (48 ч, routers/instant.py) — это одна и та же потребность: доехали, а через
# час нашлась сумка на заднем сиденье.
CONTACTS_AFTER_DONE = timedelta(hours=48)


def phones_open(order: InstantOrder, now=None) -> bool:
    """Открыт ли ПРЯМО СЕЙЧАС телефон второй стороны.

    Раньше телефон определялся статусом: `accepted/arriving/onboard/done`. Но `done` заказ
    остаётся `done` навсегда, значит водитель вечно видел имя и номер пассажирки, которую вёз
    год назад, просто открыв старый заказ. Ручка «забыл вещь в машине» при этом прямо пишет,
    что «телефон второй стороны виден, только пока заказ активен», и ради этого открывает чат
    на 48 часов — окно было задумано, но в коде его не было (аудит 2026-08-07).

    Сейчас: пока поездка живая — открыт; после завершения — ещё 48 часов, и дольше, если
    заявлена забытая вещь (пока идёт поиск, связь нужна). Дальше закрыт: это и здравый смысл
    в приложении «между своими», и требование 152-ФЗ показывать личные данные ровно столько,
    сколько нужно для цели.
    """
    if order.status in (S.accepted, S.arriving, S.onboard):
        return True
    if order.status != S.done:
        return False
    now = now or utcnow()
    if order.lost_item_until is not None and order.lost_item_until > now:
        return True
    since = order.done_at or order.created_at
    return since is None or (now - since) <= CONTACTS_AFTER_DONE


def is_order_participant(order: InstantOrder, viewer_id: int) -> bool:
    """Имеет ли человек отношение к заказу: пассажир, назначенный водитель или тот,
    кому заказ предложен прямо сейчас. Те же три роли, что в `_order_for_view`."""
    return viewer_id in (order.passenger_id, order.driver_id, order.current_offer_driver_id)


_HOUSE_TAIL = re.compile(r"[\s,]+(?:д\.?\s*)?\d+[а-яА-Яa-zA-Z]?(?:\s*(?:к|корп|стр)\.?\s*\d+[а-яА-Яa-zA-Z]?)?\s*$")


def pickup_street_only(text: str) -> str:
    """Адрес подачи без номера дома, квартиры и подъезда — для тех, кто ещё не везёт.

    Отдельно от `street_only`, потому что задача другая. Там нужно было снять номер дома
    в конце строки («Пушкина, 12к2»). Здесь строка пишется человеком и выглядит иначе:
    «ул. Ленина, 12, кв. 43, подъезд 2» — номер дома стоит в середине, а за ним идёт то,
    что и приводит чужого человека прямо к двери (аудит 2026-08-08, волна 143).

    Режем по первому же числу или слову «кв./подъезд/этаж/домофон»: всё, что после, — это
    указания, как найти конкретную квартиру. Улица и город остаются: по ним водитель решает,
    ехать ли в тот конец.

    Ничего не осталось (адрес был из одних цифр) — отдаём исходное: пустое «откуда» хуже
    точного, водитель просто не поймёт заказ.
    """
    t = (text or "").strip()
    if not t:
        return t
    cut = _PICKUP_TAIL.split(t, 1)[0].strip(" ,.")
    return cut if cut else t


# Первое вхождение номера дома или слова про квартиру/подъезд — дальше режем всё.
_PICKUP_TAIL = re.compile(
    r"(?:[\s,]+\d|[\s,]*(?:кв|квартира|подъезд|под\.|этаж|эт\.|домофон|код))",
    re.IGNORECASE | re.UNICODE,
)


def street_only(text: str) -> str:
    """Адрес без номера дома: «Уфа, ул. Пушкина, 12к2» → «Уфа, ул. Пушкина».

    Нужен ровно там же, где округляются координаты назначения: прятать точку на карте и тут же
    писать номер дома текстом — значит не прятать ничего. Улица и город остаются: водителю их
    хватает, чтобы решить, брать ли заказ.

    Если номера дома в строке нет — возвращаем как есть. Если после отрезания ничего не
    осталось (адрес был из одного числа) — тоже отдаём исходное: пустое «куда» хуже точного.
    """
    t = (text or "").strip()
    if not t:
        return t
    cut = _HOUSE_TAIL.sub("", t).strip(" ,")
    return cut if cut else t


def order_payload(session: Session, order: InstantOrder, viewer: User, *,
                  actor_authorized: bool = False) -> dict:
    """Витрина заказа. Приватность: телефоны и контакты сторон — ТОЛЬКО после accept.

    Проверка «а ты вообще участник?» стоит ЗДЕСЬ, а не в каждой из 17 ручек такси,
    которые эту витрину отдают. Раньше её не было нигде, и роль вычислялась так: «водитель»,
    если ты назначен или тебе сейчас предложен заказ, иначе — «пассажир». Значит посторонний
    получал пассажирскую витрину чужого заказа: точную точку подачи (обычно — домашний адрес)
    и телефон водителя. Вход был через идемпотентный `/decline`, который чужому ничего не
    меняет, но исправно возвращает заказ (аудит 2026-08-07).

    `actor_authorized=True` — для случая, когда ручка проверила права ДО перехода, а переход
    снял с человека участие. Единственный такой случай — отказ от оффера: после него заказ
    уходит следующему кандидату, и отказавшийся перестаёт быть участником, но ответ на свой
    же запрос получить обязан.
    """
    if not (actor_authorized or is_order_participant(order, viewer.id)):
        raise herr(403, "Нет доступа к заказу", "Заказға рөхсәт юҡ")
    role = "driver" if (order.driver_id == viewer.id
                        or order.current_offer_driver_id == viewer.id) else "passenger"
    unlocked = order.status in UNLOCKED
    # Телефоны живут по своему, более короткому правилу — см. phones_open: имя, машина и
    # госномер остаются в истории поездки (по ним разбирают спор), а номер телефона — нет.
    phones = phones_open(order)
    # Рейтинг/опыт пассажира (B7a-4) — только витрине ВОДИТЕЛЯ (оффер и активный заказ):
    # анонимный агрегат, чтобы решать по данным. Пассажиру про себя не считаем (лишние запросы).
    p_rating, p_trips = (passenger_stats(session, order.passenger_id)
                         if role == "driver" else (None, 0))
    driver = session.get(User, order.driver_id) if order.driver_id else None
    prof = (session.exec(select(DriverProfile).where(DriverProfile.user_id == order.driver_id)).first()
            if order.driver_id else None)
    passenger = session.get(User, order.passenger_id)
    car = f"{prof.car_make} {prof.car_model}".strip() if prof else ""
    # Приватность: до accept водителю обе точки — округлённые (~1 км), как телефоны.
    #
    # Точку ПОДАЧИ прятали и раньше. Точку НАЗНАЧЕНИЯ — нет, и это была несогласованность:
    # адрес, куда человек едет, чаще всего его дом, и для женщины, возвращающейся ночью, он
    # важнее места посадки. Предложение можно отклонить бесплатно и получить следующее —
    # значит адреса можно было «собирать», отказываясь (аудит 2026-08-08, волна 10; решение
    # Александра — округлять и назначение).
    #
    # Водитель ничего не теряет: расстояние, время и цену считает сервер и кладёт в этот же
    # ответ (distance_km / eta_min / price_estimate), а район назначения из округлённой точки
    # виден. Точные координаты открываются вместе с телефоном — после accept (unlocked).
    blur = role == "driver" and not unlocked
    def _blur(v):
        return round(v, 2) if (blur and v is not None) else v
    from_lat, from_lng = _blur(order.from_lat), _blur(order.from_lng)
    to_lat, to_lng = _blur(order.to_lat), _blur(order.to_lng)

    # Водителю — серверный upfront net, тем же Decimal-расчётом, что фактический долг.
    # Пассажиру комиссию водителя не раскрываем: его источник правды — итоговая цена поездки.
    driver_gross_kop = driver_fee_kop = driver_net_kop = 0
    driver_fee_percent = 0.0
    if role == "driver":
        from . import debt as debt_mod
        price_rub = int(order.price_final if order.price_final is not None else order.price_estimate)
        driver_gross_kop = max(price_rub, 0) * 100
        # Фиксируем смысл upfront: ступень комиссии берём на момент создания заказа.
        driver_fee_percent = debt_mod.driver_fee_percent(
            session, viewer.id, order.created_at or utcnow()
        )
        driver_fee_kop = debt_mod.order_commission_kop(order, driver_fee_percent)
        driver_net_kop = max(driver_gross_kop - driver_fee_kop, 0)

    return {
        "id": order.id,
        "status": order.status.value,
        "role": role,
        "from_lat": from_lat, "from_lng": from_lng,
        "to_lat": to_lat, "to_lng": to_lng,
        "from_text": order.from_text,
        # Номер дома назначения — вместе с координатами: округлить точку и тут же написать
        # «Пушкина, 12» значит не спрятать ничего. Точку ПОДАЧИ намеренно не трогаем: там
        # адрес, подъезд и комментарий отдаются заранее, чтобы водитель нашёл человека.
        "to_text": street_only(order.to_text) if blur else order.to_text,
        "category": order.category,
        # «Только женщина за рулём»: экран должен объяснить, ПОЧЕМУ машину не нашли,
        # иначе человек решит, что приложение сломалось, а не что выбор сузил круг.
        "women_only": bool(getattr(order, "women_only", False)),
        "price_estimate": order.price_estimate,
        "price_final": order.price_final,
        # Промокод: скидку оплачивает платформа, но ЗНАТЬ о ней должны обе стороны — иначе
        # водитель попросит полную сумму, а пассажир будет уверен, что платит со скидкой.
        # passenger_price_kop — сколько человек реально отдаёт водителю (цена минус скидка).
        "promo_discount_kop": int(order.promo_discount_kop or 0),
        "passenger_price_kop": promo_ride.payable_kop(order),
        "surge_k": order.surge_k,
        "distance_km": order.distance_km,
        "eta_min": order.eta_min,
        # Серверный доход водителя: цена пассажиру → комиссия его ступени → чистыми.
        # Нули в пассажирской витрине; вычислений денег на клиенте нет.
        "driver_gross_kop": driver_gross_kop,
        "driver_fee_kop": driver_fee_kop,
        "driver_net_kop": driver_net_kop,
        "driver_fee_percent": driver_fee_percent,
        # Предзаказ «на время»: null у обычного заказа; iso-время подачи у scheduled.
        "scheduled_at": order.scheduled_at.isoformat() if order.scheduled_at else None,
        # Когда заказ создан и когда пошёл поиск. Без этих двух меток экран поиска не может
        # честно сказать «ищем уже 3:20»: после сворачивания приложения свой таймер обнуляется,
        # и цифра была бы враньём. created_at — сколько человек ждёт ВСЕГО (перезапуск поиска
        # из очереди «рядом никого» его не сбрасывает); searching_at — с какого момента идёт
        # текущий круг подбора (у предзаказа «на время» это активация, а не бронирование).
        "created_at": order.created_at.isoformat() if order.created_at else None,
        "searching_at": order.searching_at.isoformat() if order.searching_at else None,
        "driver_id": order.driver_id,
        "offer_expires_at": order.offer_expires_at.isoformat() if order.offer_expires_at else None,
        "cancel_by": order.cancel_by,
        "cancel_reason": order.cancel_reason,
        # B8-8: отмена после открытия телефона/чата — клиент показывает пассажиру мягкий
        # баннер «Договорились ехать? Заверши поездку в приложении…».
        "contact_then_cancel": order.contact_then_cancel,
        # Ожидание/отмены (волна 2 §5): всё для честных таймеров и предупреждений в UI.
        "waiting_started_at": order.waiting_started_at.isoformat() if order.waiting_started_at else None,
        "waiting_fee_kop": order.waiting_fee_kop,
        "cancel_fee_kop": order.cancel_fee_kop,
        "no_show": order.no_show,
        "wait_free_min": settings.wait_free_minutes,
        "wait_fee_rub_per_min": settings.wait_fee_rub_per_min,
        # Когда водителю станет доступна кнопка «Пассажир не вышел» (None до «Я на месте»).
        "no_show_at": (no_show_available_at(order).isoformat() if no_show_available_at(order) else None),
        # Сколько будет стоить отмена пассажиру ПРЯМО СЕЙЧАС (0 = бесплатно) — предупреждаем до тапа.
        "cancel_fee_now_kop": (passenger_cancel_fee_kop(session, order)
                               if order.status in (S.accepted, S.arriving) else 0),
        # Пассажир глазами водителя (B7a-4): агрегат анонимен, доступен уже в оффере
        # (телефон/имя — по-прежнему только после accept). None = новичок без оценок.
        "passenger_rating": p_rating,
        "passenger_trips": p_trips,
        # Забытые вещи: пока не истекло — чат заказа снова открыт на запись (chat.py).
        "lost_item_until": (order.lost_item_until.isoformat() if order.lost_item_until else None),
        "thanked": bool(getattr(order, "thanked", False)),
        # Очередь «рядом никого»: до какого времени ждём машину (null = не ждём).
        "wait_until": (order.wait_until.isoformat() if order.wait_until else None),
        # Как найти пассажира — водителю ВМЕСТЕ с оффером: чат до accept недоступен, а
        # «Ленина 12» в селе это пять домов без табличек (аудит 2026-07-26).
        "comment": (order.comment if role == "driver" else order.comment),
        "entrance": (order.entrance if role == "driver" else order.entrance),
        # Раскрывается ТОЛЬКО после accept:
        "driver_name": (driver.name if (unlocked and driver) else ""),
        "driver_car": (car if unlocked else ""),
        # Госномер: поле было в базе, но в заказ не попадало — у подъезда две белые «Лады»,
        # и сверить нечем (кода посадки у такси тоже нет). Отдаём вместе с остальной карточкой.
        "driver_plate": ((prof.car_plate or "") if (unlocked and prof) else ""),
        "driver_verified": (bool(driver.verified) if (unlocked and driver) else False),
        "driver_rating": (prof.rating if (unlocked and prof) else 0.0),
        # Телефон водителя — только пассажиру после accept; телефон пассажира — только водителю.
        # И только пока телефон вообще открыт (`phones`): после поездки окно закрывается.
        "driver_phone": (driver.phone if (phones and driver and role == "passenger") else ""),
        # Заказ ДЛЯ ДРУГОГО: водителю показываем имя и телефон ТОГО, КОГО ВЕЗЁМ (сын из Уфы
        # вызывает такси маме в Баймаке — звонить надо маме, а не заказчику в другой город).
        "passenger_name": ((order.for_name or (passenger.name if passenger else ""))
                           if (unlocked and role == "driver") else ""),
        "passenger_phone": ((order.for_phone or (passenger.phone if passenger else ""))
                            if (phones and role == "driver") else ""),
        "for_other": bool(order.for_phone or order.for_name),
        # id пассажира — только водителю и только после accept (как имя и телефон). Нужен, чтобы
        # водитель мог открыть РАЗБОР по этой поездке: спор требует указать вторую сторону,
        # а без id ему было бы не на кого пожаловаться (аудит 2026-07-26).
        "passenger_id": (order.passenger_id if (unlocked and role == "driver") else None),
    }
