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
import json
from datetime import datetime, timedelta
from enum import Enum
from typing import Optional

from sqlalchemy import update
from sqlmodel import Session, select

from sqlalchemy import func

from .config import settings
from .errors import herr
from . import car_class as cc
from . import compensation as comp_mod
from . import class_rollout
from . import pricing
from .livepos import livepos_get
from . import promo_ride
from .models import (
    Booking, BookingStatus, DriverCancel, DriverProfile, InstantOrder,
    InstantOrderStatus as S, OfferDecline, Tariff, TripShare, TrustedContact, User,
)
from .services import (member_since, pick_lang, sms_lang_of,
    blocked_user_ids, haversine_km, may_send_family_sms, push_bilingual, send_push,
                       passenger_rating, send_text)
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
# Статусы, на которых пассажир может поменять адрес. До accept водителя нет — меняем свободно;
# на offered карточка уже висит у водителя, там ручка сначала отзовёт предложение.
_DESTINATION_CHANGEABLE = (
    S.created, S.searching, S.offered, S.accepted, S.arriving, S.onboard,
)

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
        _remember_track_point(r, driver_id)
        r.geoadd(PRESENCE_KEY, (lng, lat, f"driver:{driver_id}"))
        r.set(f"presence:hb:{driver_id}", "1", ex=settings.presence_ttl_sec)
        return True
    except Exception:  # noqa: BLE001 — presence не должен ронять запрос
        return False


def _remember_track_point(r, driver_id: int) -> None:
    """Запомнить, где водитель был МИНУТУ назад (для признака «едет в эту сторону»).

    Почему не «прошлый пинг». Пинги идут раз в 10–15 секунд: на скорости 40 км/ч между ними
    150 метров — это на уровне погрешности GPS, и признак получался бы случайным. Поэтому
    точку обновляем не чаще раза в `pickup_enroute_window_sec`: сравнение идёт с позицией
    минутной давности, где движение уже видно уверенно.

    Якорь анти-фрода (`af:pt:`) для этого не годится: он переписывается каждым пингом и всегда
    равен текущей позиции — разница выходила бы нулевой всегда.
    """
    key = f"presence:prev:{driver_id}"
    window = max(int(settings.pickup_enroute_window_sec), 1)
    try:
        ttl = r.ttl(key)
        # ttl > 0 и точка ещё «свежая» → окно не закрылось, ничего не трогаем.
        if ttl is not None and ttl > window:
            return
        pos = driver_position(driver_id)
        if pos is not None:
            r.set(key, f"{pos[0]},{pos[1]}", ex=window * 2)
    except Exception:  # noqa: BLE001 — след для скидки не должен ронять heartbeat
        return


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
        r.delete(f"presence:hb:{driver_id}", f"presence:prev:{driver_id}")
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


def nearby_drivers(lat: float, lng: float, limit: int = 8,
                   radius_km: Optional[float] = None,
                   session: Optional[Session] = None) -> list[dict]:
    """Свободные машины «на линии» рядом с пассажиром — АНОНИМНЫЕ позиции + ≈ETA до подачи.

    Только РЕАЛЬНЫЕ данные из presence (Redis GEO), без выдуманного: показываем машины,
    которые действительно на линии рядом. Личность водителя НЕ раскрываем (ни id, ни имя,
    ни телефон) — только точка на карте и оценка «≈N мин до тебя» (та же средняя скорость,
    что в оценке заказа). Нет Redis → пустой список (честно «не знаю», UI просто без машинок).

    `radius_km` — насколько далеко смотреть. По умолчанию городской радиус (карта): показывать
    на экране машину за пятнадцать километров бессмысленно. Расчёт цены зовёт с бо́льшим
    радиусом — тем же, до какого matcher реально рассылает офферы (RADII_KM): цену должен
    определять тот, кто действительно может приехать, а не тот, кто помещается в экран.

    С `session` добавляем ещё и класс кузова (`car_class`): на карте метка тогда выглядит
    как та машина, которая реально приедет, — эконом жёлтый, бизнес серебристый, минивэн
    крупнее. Это «какая машина», а не «кто за рулём»: анонимность точки не меняется.
    """
    r = _redis()
    if r is None:
        return []
    try:
        found = r.geosearch(PRESENCE_KEY, longitude=lng, latitude=lat,
                            radius=float(radius_km if radius_km else settings.surge_radius_km),
                            unit="km",
                            withcoord=True, withdist=True, sort="ASC", count=limit * 2)
    except Exception:  # noqa: BLE001 — сбой GEO → просто без машинок, не падаем
        return []
    живые: list[tuple[int, float, list]] = []
    for m in found:
        # withdist+withcoord → [member, dist_km, [lng, lat]]
        try:
            member, dist_km, coord = m[0], float(m[1]), m[2]
            did = _member_driver_id(member)
        except (ValueError, IndexError, TypeError, AttributeError):
            continue
        if presence_alive(r, did):
            живые.append((did, dist_km, coord))
        if len(живые) >= limit:
            break
    # Классы кузова — ОДНИМ запросом на весь список: карта опрашивает эту ручку раз в 15 секунд,
    # и поход в базу за каждой машиной превратил бы её в десяток запросов на каждый опрос.
    классы: dict[int, str] = {}
    if session is not None and живые:
        классы = {
            p.user_id: cc.class_to_category(p.car_class)
            for p in session.exec(
                select(DriverProfile).where(DriverProfile.user_id.in_([d for d, _, _ in живые]))
            ).all()
        }
    out: list[dict] = []
    for did, dist_km, coord in живые:
        eta = max(1, round(dist_km / settings.instant_avg_speed_kmh * 60))
        # dist_km — по прямой, как его считает Redis GEO. Превращать его в дорожные
        # километры — дело того, кто считает деньги (см. pickup_road_km), а не карты машинок.
        # Наружу это поле не уходит: публичная ручка отдаёт только точку, ETA и класс.
        точка = {"lat": float(coord[1]), "lng": float(coord[0]), "eta_min": eta,
                 "dist_km": round(dist_km, 2)}
        # Без класса клиент рисует прежнюю общую машинку — старые клиенты этого поля не знают.
        if did in классы:
            точка["category"] = классы[did]
        out.append(точка)
    return out


def nearby_pickup_eta_by_category(session: Session, lat: float, lng: float,
                                  limit: int = 40) -> dict[str, int]:
    """Через сколько подъедет ближайшая машина КАЖДОГО класса — минуты по категориям заказа.

    Зачем отдельно от `nearby_drivers`. Тот отвечает на вопрос «есть ли вообще кто-то рядом»
    и не смотрит на классы. Витрина же ставит четыре тарифа в ряд, и одна общая цифра на
    всех четырёх — это обещание подачи за тот класс, машин которого рядом может не быть
    вовсе: бизнес-седан один на город, а карточка обещает те же две минуты, что и эконом.

    Класс берём ТОТ ЖЕ, по которому работает подбор: доступные машине ∩ включённые
    водителем (`cc.effective_classes`). Иначе витрина обещала бы подачу от машины, которой
    оффер по этому классу даже не придёт.

    Личность не раскрываем: наружу уходит только «в этом классе ближайшая за N минут» —
    ни id, ни имени, ни координат конкретного водителя.
    """
    r = _redis()
    if r is None:
        return {}
    try:
        # Радиус — самый широкий круг ПОДБОРА (RADII_KM), а не радиус суржа. Витрина обязана
        # показывать ту же картину, что и поиск машины: иначе на карточке «нет машин», а заказ
        # спокойно находит водителя в двенадцати километрах — и человек не понял, почему ждал.
        found = r.geosearch(PRESENCE_KEY, longitude=lng, latitude=lat,
                            radius=RADII_KM[-1], unit="km",
                            withdist=True, sort="ASC", count=limit)
    except Exception:  # noqa: BLE001 — сбой GEO → просто без минут, экран работает дальше
        return {}
    # sort="ASC" → первый встреченный в классе и есть ближайший, сортировать заново не нужно.
    pairs: list[tuple[int, float]] = []
    for m in found:
        try:
            did, dist_km = _member_driver_id(m[0]), float(m[1])
        except (ValueError, IndexError, TypeError, AttributeError):
            continue
        if presence_alive(r, did):
            pairs.append((did, dist_km))
    if not pairs:
        return {}
    # Профили — ОДНИМ запросом. Оценка цены пересчитывается на каждое движение точки по карте;
    # поход в базу за каждым найденным водителем превратил бы это в десятки запросов на жест.
    profiles = {
        p.user_id: p
        for p in session.exec(
            select(DriverProfile).where(DriverProfile.user_id.in_([d for d, _ in pairs]))
        ).all()
    }
    out: dict[str, int] = {}
    for did, dist_km in pairs:
        p = profiles.get(did)
        if p is None:
            continue
        avail = cc.available_or_legacy(getattr(p, "car_classes_available", ""), p.car_class)
        for cls in cc.effective_classes(avail, getattr(p, "car_classes_enabled", "")):
            cat = cc.class_to_category(cls)
            if cat not in out:
                out[cat] = max(1, round(dist_km / settings.instant_avg_speed_kmh * 60))
    return out


# ============================ Способ расчёта (деньги мимо платформы) ============================
# ЗАПИСЬ ДОГОВОРЁННОСТИ, а не платёж: приложение денег не касается и комиссию с них не берёт.
# Поле нужно, чтобы обе стороны ехали с одинаковым пониманием, чем всё кончится на высадке.
#
# card и corporate заведены заранее и ВЫКЛЮЧЕНЫ: принимать карты без договора с банком,
# кассы и чеков нельзя. Когда договор будет — включаются флагом, переделывать нечего.
PAY_CASH = "cash"
PAY_SBP = "sbp"
PAY_NEGOTIATE = "negotiate"
PAY_CARD = "card"
PAY_CORPORATE = "corporate"

# Способы, доступные пассажиру прямо сейчас.
PAY_METHODS_OPEN: tuple[str, ...] = (PAY_CASH, PAY_SBP, PAY_NEGOTIATE)
# Заготовленные, но выключенные — витрина показывает их строкой «скоро».
PAY_METHODS_SOON: tuple[str, ...] = (PAY_CARD, PAY_CORPORATE)


def pay_method_open(method: str) -> bool:
    """Можно ли выбрать этот способ сегодня. Карты и корпоративный счёт — пока нет."""
    return (method or "") in PAY_METHODS_OPEN


def pay_method_label(method: str) -> tuple[str, str]:
    """Как способ называется на двух языках. Пусто → «договоримся»: это наше умолчание."""
    return {
        PAY_CASH: ("Наличными", "Наличный менән"),
        PAY_SBP: ("Переводом по СБП", "СБП аша күсереү"),
        PAY_NEGOTIATE: ("Договоримся на месте", "Урында килешәбеҙ"),
        PAY_CARD: ("Картой в приложении", "Ҡушымтала карта менән"),
        PAY_CORPORATE: ("Корпоративный счёт", "Корпоратив иҫәп"),
    }.get(method or "", ("Договоримся на месте", "Урында килешәбеҙ"))


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


def demand_zones(session: Session, city: Optional[str] = None,
                 driver_id: Optional[int] = None) -> dict:
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
    # Где сейчас сам водитель — чтобы подписать зоны расстоянием (см. ниже).
    водитель_тут = driver_position(driver_id) if driver_id else None
    zones = []
    if buckets:
        max_req = max(buckets.values())
        for (zlat, zlng), cnt in buckets.items():
            # Такси выключено в этой зоне (глобально/город) → не показываем (честно + приватно).
            if not taxi_mod.availability(session, zlat, zlng)["enabled"]:
                continue
            zone = {
                "lat": zlat, "lng": zlng,
                "weight": round(cnt / max_req, 3),
                "requests": cnt,
            }
            # Далеко ли зона — считаем на сервере по ЖИВОЙ позиции водителя (presence).
            # Иначе клиенту пришлось бы отдельно просить геолокацию ради одной подписи,
            # а телефон водителя и так шлёт координаты, пока он на линии.
            # Позиции нет (только вышел, нет Redis) — поля просто не будет: «Зона 1»
            # честнее, чем выдуманные километры.
            if водитель_тут is not None:
                zone["dist_km"] = round(
                    haversine_km(водитель_тут[0], водитель_тут[1], zlat, zlng), 1)
            zones.append(zone)
        # Сортируем по спросу, а не по близости: водитель сам решает, стоит ли ехать дальше
        # за большим числом заказов. Расстояние — подсказка, а не приказ.
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


def billable_km(dist_km: float) -> float:
    """Оплачиваемая длина: на межгороде километр за порогом дешевеет («сходящийся километр»).

    Зачем. Линейный счёт держит одну и ту же ставку и на 50 км, и на 350 — так не работает
    ни одно реальное междугороднее такси. Дальний заказ выходит дороже рынка, водитель его
    не увидит (заказа просто не будет), и мы решим, что «межгород не нужен».

    ВЫКЛЮЧЕНО по умолчанию: механизм есть, цифры не сверены с реальными ценами по маршрутам
    (docs/tasks.md, «Долги по ценообразованию»). Порог 0 или скидка 0 → длина как есть.
    """
    edge = float(settings.intercity_taper_from_km)
    pct = float(settings.intercity_taper_percent)
    if edge <= 0 or pct <= 0 or dist_km <= edge:
        return dist_km
    # Потолок 90%: даже на тысяче километров километр не может стать бесплатным.
    return edge + (dist_km - edge) * (1.0 - min(pct, 90.0) / 100.0)


def _tariff_price(t: Tariff, dist_km: float, eta_min: float, surge: float) -> int:
    """Цена по тарифу: max(min_price, (base + per_km·dist + per_min·eta) · k · surge),
    округление до 10 ₽. Tariff.k — статичный АВАРИЙНЫЙ множитель (по умолчанию 1.0,
    правится в БД); динамический сурж — отдельным surge (двойного счёта нет).
    `dist_km` проходит через `billable_km`: на длинных перегонах километр может дешеветь."""
    raw = t.base + t.per_km * billable_km(dist_km) + t.per_min * eta_min
    return max(t.min_price, round_to_10(raw * t.k * surge))


# ============================ Дальняя подача: строка счёта, не множитель ============================
# Разбор Яндекс Go 2026-08-23. У них дорога водителя К пассажиру — ОТДЕЛЬНАЯ строка счёта:
# «Водитель едет издалека — 990 ₽», внутри бесплатны первые 12 минут и 5 км. У нас это был
# множитель ≤ ×1,12 ко всей цене: на поездке за 170 ₽ — плюс двадцать рублей за двадцать
# километров порожняка. Водитель на такой заказ не поедет, и он умрёт в «рядом никого».
#
# Хуже было в главном для нас случае: если рядом действительно НИКОГО нет, ближайшей машины
# не существует, множитель равен 1,0 — надбавки не было ровно там, где она нужнее всего.
# Между сёлами Башкирии 20–40 км, это обычный заказ, а не край.
#
# Почему строкой, а не наценкой. Это не «дороже, потому что спрос» — это бензин, который
# водитель сожжёт по дороге к тебе. Поэтому: (1) она не входит в потолок ×1,5 (потолок про
# наценку, и обещание остаётся в силе), (2) комиссию с неё не берём (см. debt.order_commission_kop),
# (3) человек видит её отдельной строкой со словами «эти деньги идут водителю за дорогу к тебе».
def pickup_road_km(straight_km: float) -> float:
    """Прямая до водителя → примерная длина по дорогам. Тот же коэффициент, что в оценке
    маршрута (`instant_road_k`): деньги нельзя считать по одной геометрии, а километры
    показывать по другой."""
    return max(float(straight_km or 0.0), 0.0) * settings.instant_road_k


def pickup_fee_rub(t: Optional[Tariff], straight_km: Optional[float]) -> int:
    """Компенсация за дорогу к пассажиру, ₽. Ноль — когда платить не за что.

    Ноль возвращаем в трёх случаях, и все три означают «не бери с человека денег»:
    тариф не настроен (`pickup_per_km = 0` — база, куда миграция ещё не дошла), расстояние
    неизвестно (рядом никого — тогда сумму зафиксируем при accept), подача внутри бесплатных
    километров. Округляем до 10 ₽, как и всю остальную цену.
    """
    if t is None or straight_km is None:
        return 0
    per_km = float(getattr(t, "pickup_per_km", 0.0) or 0.0)
    max_rub = int(getattr(t, "pickup_max_rub", 0) or 0)
    if per_km <= 0 or max_rub <= 0:
        return 0
    free_km = max(float(getattr(t, "pickup_free_km", 0.0) or 0.0), 0.0)
    billable = pickup_road_km(straight_km) - free_km
    if billable <= 0:
        return 0
    return min(round_to_10(billable * per_km), max_rub)


def _nearest_straight_km(nearest: list) -> Optional[float]:
    """Расстояние до ближайшей машины по прямой, км. None = машин рядом нет.

    Читаем `dist_km`, а если его нет — восстанавливаем из ETA. Так сделано не для красоты:
    ETA и так считается ИЗ этого расстояния, а список машин приходит из разных мест (Redis,
    подменённая в тестах функция, будущий источник). Падать на отсутствующем поле в ручке,
    которая пересчитывается на каждое движение пальца по карте, нельзя.
    """
    if not nearest:
        return None
    row = nearest[0] or {}
    raw = row.get("dist_km")
    if raw is None:
        eta = row.get("eta_min")
        if eta is None:
            return None
        raw = float(eta) / 60.0 * max(settings.instant_avg_speed_kmh, 1.0)
    try:
        return max(float(raw), 0.0)
    except (TypeError, ValueError):
        return None


def pickup_enabled(t: Optional[Tariff]) -> bool:
    """Настроена ли строка подачи в этом тарифе. Не настроена — ведём себя как раньше."""
    return bool(t is not None
                and float(getattr(t, "pickup_per_km", 0.0) or 0.0) > 0
                and int(getattr(t, "pickup_max_rub", 0) or 0) > 0)


def pickup_note(fee_rub: int, road_km: float, pending: bool, max_rub: int,
                enroute: bool = False, full_fee: int = 0) -> Optional[dict]:
    """Что написать человеку про эту строку (RU + черновой BA). Нечего сказать → None.

    Три разные ситуации — три разных текста. Знаем машину и водителю по пути: называем сумму
    и говорим, почему дешевле (иначе выгода незаметна). Знаем машину, но специально ехать —
    называем сумму. Не знаем машину — честно говорим потолок и что цифра появится, когда
    водитель согласится: обещать точную цену, которой у нас нет, значит соврать в первом же заказе.
    """
    if pending and max_rub > 0:
        return {
            "ru": f"Рядом свободных машин нет. Если машина поедет издалека, добавится "
                  f"до {max_rub} ₽ — точную сумму покажем, когда водитель согласится. "
                  f"Отменить в первые {settings.cancel_free_minutes} мин можно бесплатно.",
            "ba": f"Яҡында буш машина юҡ. Машина алыҫтан килһә, {max_rub} һумға тиклем "
                  f"өҫтәлә — водитель ризалашҡас, теүәл һумды күрһәтербеҙ. "
                  f"Тәүге {settings.cancel_free_minutes} минутта бушлай кире алып була.",
        }
    if fee_rub <= 0:
        return None
    km = int(round(road_km))
    if enroute and full_fee > fee_rub:
        return {
            "ru": f"Водителю и так по пути в эту сторону, поэтому за дорогу к тебе "
                  f"{fee_rub} ₽ вместо {full_fee} ₽. Специально ради заказа он бы ехал "
                  f"{km} км.",
            "ba": f"Водителгә барыбер был яҡҡа юл, шуға һиңә тиклем юл өсөн {full_fee} "
                  f"һум урынына {fee_rub} һум. Махсус заказ өсөн ул {km} км барыр ине.",
        }
    return {
        "ru": f"Машина едет издалека, около {km} км. Эти {fee_rub} ₽ идут водителю "
              f"за дорогу к тебе — мы с них комиссию не берём.",
        "ba": f"Машина алыҫтан килә, яҡынса {km} км. Был {fee_rub} һум һиңә тиклем юл өсөн "
              f"водителгә бара — беҙ унан комиссия алмайбыҙ.",
    }


def driver_position(driver_id: int) -> Optional[tuple[float, float]]:
    """Живая позиция водителя (lat, lng) из presence. None = не знаем.

    ⚠️ Только для расчётов на сервере. Наружу координаты конкретного водителя не отдаём
    никогда — на карте машины анонимны (см. `nearby_drivers`), и это правило старше цены.
    """
    r = _redis()
    if r is None:
        return None
    try:
        pos = r.geopos(PRESENCE_KEY, f"driver:{int(driver_id)}")
    except Exception:  # noqa: BLE001 — сбой GEO не должен мешать водителю принять заказ
        return None
    if not pos or not pos[0]:
        return None
    try:
        return float(pos[0][1]), float(pos[0][0])      # geopos отдаёт (lng, lat)
    except (TypeError, ValueError, IndexError):
        return None


def driver_straight_km(driver_id: int, lat: float, lng: float) -> Optional[float]:
    """Сколько по прямой от живой позиции водителя до точки подачи. None = позиции нет.

    Нужно в момент accept: заказ создавался, когда рядом не было никого, и настоящую дорогу
    к пассажиру мы узнаём только от того, кто согласился ехать. Нет Redis или водитель молчит
    → None, и строка остаётся нулевой: брать деньги «на всякий случай» нельзя.
    """
    pos = driver_position(driver_id)
    if pos is None:
        return None
    return haversine_km(lat, lng, pos[0], pos[1])


def nearest_driver_for_pricing(lat: float, lng: float) -> Optional[tuple[int, float]]:
    """(id ближайшего живого водителя, расстояние по прямой) — ТОЛЬКО для расчёта цены.

    Зачем отдельно от `nearby_drivers`. Тот сознательно не отдаёт личность: карта машин
    анонимна, чтобы по ней нельзя было следить за конкретным человеком. Цене же нужен именно
    id — по нему проверяется, не по пути ли водителю (его зона работы, его прошлая точка).
    Результат никогда не сериализуется в ответ: наружу уходят только рубли и километры.
    """
    r = _redis()
    if r is None:
        return None
    try:
        found = r.geosearch(PRESENCE_KEY, longitude=lng, latitude=lat,
                            radius=float(max(RADII_KM)), unit="km",
                            withdist=True, sort="ASC", count=8)
    except Exception:  # noqa: BLE001 — сбой GEO → считаем как «рядом никого»
        return None
    for row in found:
        try:
            member, dist_km = (row[0], float(row[1])) if isinstance(row, (list, tuple)) else (row, 0.0)
            did = _member_driver_id(member)
        except (ValueError, IndexError, TypeError, AttributeError):
            continue
        if presence_alive(r, did):
            return did, max(dist_km, 0.0)
    return None


def _approaching(driver_id: int, lat: float, lng: float,
                 now_pos: Optional[tuple[float, float]] = None) -> bool:
    """Водитель приблизился к точке подачи с прошлого пинга? (то есть реально едет сюда)

    Сравниваем позицию на прошлом heartbeat (`presence:prev:{id}`, пишется в
    `presence_heartbeat`) с текущей. Считаем не направление стрелкой, а простую вещь:
    расстояние до точки подачи стало меньше хотя бы на `pickup_enroute_min_km`. Стрелка
    на повороте врёт, а «стало ближе» — нет.

    Ничего не знаем → False. Это важно: сомнение здесь стоит водителю денег, поэтому
    по умолчанию скидки НЕТ.
    """
    r = _redis()
    if r is None:
        return False
    now_pos = now_pos or driver_position(driver_id)
    if now_pos is None:
        return False
    try:
        raw = r.get(f"presence:prev:{int(driver_id)}")
    except Exception:  # noqa: BLE001
        return False
    if not raw:
        return False
    try:
        text = raw.decode() if isinstance(raw, bytes) else raw
        p_lat, p_lng = text.split(",")
        was = haversine_km(lat, lng, float(p_lat), float(p_lng))
    except (ValueError, TypeError, AttributeError):
        return False
    now = haversine_km(lat, lng, now_pos[0], now_pos[1])
    return (was - now) >= max(float(settings.pickup_enroute_min_km), 0.0)


def pickup_enroute(session: Session, driver_id: Optional[int],
                   lat: float, lng: float) -> bool:
    """Водителю и так по пути в эту сторону? Тогда подача для пассажира дешевле.

    Идея Александра (2026-08-23): компенсация платит за бензин, который водитель сожжёт РАДИ
    этого заказа. Если он всё равно возвращается в своё село, топливо тратится в любом случае,
    и брать с пассажира полную цену нечестно.

    Признаём «по пути» по двум твёрдым признакам, а не по догадке:

    1. **Он объявил эту зону своей.** Точка подачи внутри его рабочей зоны (своё село или
       свой район — он выбрал её сам), а сам он сейчас за её пределами. Значит домой он
       поедет так и так; наш заказ просто оказался по дороге.
    2. **Он реально приближается.** С прошлого пинга расстояние до точки подачи сократилось.

    Любое сомнение — False. Ошибка в эту сторону стоит водителю его же денег, а он про неё
    даже не узнает: цена посчитана до того, как он увидел заказ. Поэтому неизвестный НП,
    невыбранная зона, молчащий Redis — всё это «скидки нет».
    """
    if driver_id is None or float(settings.pickup_enroute_discount_percent) <= 0:
        return False
    from . import geo
    profile = session.exec(
        select(DriverProfile).where(DriverProfile.user_id == int(driver_id))
    ).first()
    now_pos = driver_position(driver_id)

    base_kind = (getattr(profile, "work_zone", "") or "").strip() if profile else ""
    if profile is not None and base_kind in ("city", "district") and now_pos is not None:
        pickup_area = geo.area_at(session, lat, lng)
        driver_area = geo.area_at(session, now_pos[0], now_pos[1])
        # `same_area` намеренно не режет неизвестную точку (fail-open) — для подбора это
        # правильно, для денег нет: «не знаю» не может означать «плачу меньше».
        if pickup_area.known and driver_area.known:
            city = getattr(profile, "work_city", "") or ""
            district = getattr(profile, "work_district", "") or ""
            pickup_home = geo.same_area(base_kind, city, district, pickup_area)
            driver_home = geo.same_area(base_kind, city, district, driver_area)
            if pickup_home and not driver_home:
                return True
    return _approaching(driver_id, lat, lng, now_pos)


# ============================ Зимняя дорога: строка, а не множитель ============================
# Решение Александра Q7 (2026-08-23). Погода — это КОМПЕНСАЦИЯ, а не наценка: зимой у водителя
# реально растёт расход и износ, а не «спрос вырос». Значит она живёт отдельной строкой в
# рублях, вне потолка ×1,5, и комиссия с неё не берётся.
#
# Источник — Open-Meteo (тот же, что в предупреждениях о погоде): бесплатно, без ключа, уже
# работает. Старый множитель питался от Яндекс.Погоды, ключа к которой у нас нет, — то есть
# погода в цене «была» и не работала ни дня.

# Погода, при которой дорога реально тяжелее и расход выше. Туман, ветер и гроза сюда НЕ
# входят: они опасны и о них мы предупреждаем, но бензина от них больше не жжётся.
WINTER_ROAD_KINDS: frozenset[str] = frozenset({"ice", "blizzard", "snow", "frost"})

_WINTER_TITLE = {
    "ice": ("Гололёд на дороге", "Юлда быҙлауыҡ"),
    "blizzard": ("Метель по пути", "Юлда буран"),
    "snow": ("Сильный снегопад", "Көслө ҡар яуа"),
    "frost": ("Сильный мороз", "Ҡаты һыуыҡ"),
}


def winter_road_kind(session: Session, frm: tuple, to: tuple, when=None) -> str:
    """Какая тяжёлая погода на маршруте (самая важная), либо пустая строка.

    Ходим в тот же `weather_warn`, что показывает предупреждения: один источник, один кэш,
    одни пороги. Разъедься они — человек увидел бы «метель» в предупреждении и не увидел бы
    её в цене, или наоборот.
    """
    try:
        from . import weather_warn
        # ТОЛЬКО из кэша: цена пересчитывается на каждое движение пальца по карте, и поход
        # наружу с таймаутом в шесть секунд на этом пути недопустим. Кэш греет карточка
        # погоды — она грузится на том же экране и по тому же маршруту.
        route = weather_warn.route_weather([frm, to], when, cached_only=True)
    except Exception:  # noqa: BLE001 — погода не должна ронять расчёт цены
        return ""
    if not route.available:
        return ""
    for w in route.warnings:                 # порядок = порядок важности
        if w.kind in WINTER_ROAD_KINDS:
            return w.kind
    return ""


def winter_road_fee_rub(dist_km: float, ride_price: int, kind: str) -> int:
    """Компенсация за зимнюю дорогу, ₽. Пусто/выключено → 0.

    Считаем по километрам, а не процентом: расход растёт на километр, а не на рубль.
    Потолок — доля от цены поездки, чтобы на коротком дешёвом заказе строка не выглядела
    больше самой поездки.
    """
    if not kind or kind not in WINTER_ROAD_KINDS:
        return 0
    rate = max(float(settings.winter_road_rub_per_km), 0.0)
    if rate <= 0 or ride_price <= 0:
        return 0
    raw = max(float(dist_km), 0.0) * rate
    cap = ride_price * max(min(float(settings.winter_road_max_share), 1.0), 0.0)
    return max(round_to_10(min(raw, cap)), 0)


def winter_road_note(kind: str, fee_rub: int) -> Optional[dict]:
    """Что написать человеку про эту строку (RU + черновой BA). Нечего сказать → None."""
    if fee_rub <= 0 or kind not in _WINTER_TITLE:
        return None
    ru, ba = _WINTER_TITLE[kind]
    return {
        "ru": f"{ru}. Зимой расход и износ выше, поэтому {fee_rub} ₽ идут водителю "
              f"за тяжёлую дорогу — мы с них комиссию не берём.",
        "ba": f"{ba}. Ҡышын сығым да, туҙыу ҙа юғарыраҡ, шуға {fee_rub} һум ауыр юл өсөн "
              f"водителгә бара — беҙ унан комиссия алмайбыҙ.",
    }


def car_freeing_up_near(session: Session, lat: float, lng: float,
                        now=None) -> Optional[dict]:
    """Скоро ли рядом освободится машина, которая сейчас везёт кого-то в эту сторону?

    Идея Александра «поделить подачу между соседями» — в том виде, в каком она работает.
    Разделить одну дорогу на двоих напрямую нельзя: машина берёт одного пассажира, и второй
    всё равно ждёт. А вот сказать второму правду можно: «в твоё село уже едет машина, через
    ~N минут она освободится прямо здесь — тогда за подачу платить будет почти не за что».

    Ищем живые заказы, которые ЗАКАНЧИВАЮТСЯ рядом с нашей точкой подачи: такая машина
    останется здесь. Заказы, которые отсюда УВОЗЯТ, не считаем — они уедут вместе с машиной.

    Возвращаем только минуты. Ни кто едет, ни откуда, ни по какому заказу — соседям в селе
    незачем вычислять друг друга по времени подачи.
    """
    now = now or utcnow()
    radius = max(float(settings.pickup_wait_radius_km), 0.1)
    rows = session.exec(
        select(InstantOrder).where(
            InstantOrder.status.in_([S.accepted, S.arriving, S.onboard]),
        ).limit(200)
    ).all()
    best: Optional[int] = None
    for o in rows:
        if haversine_km(lat, lng, float(o.to_lat or 0.0), float(o.to_lng or 0.0)) > radius:
            continue
        ride_min = max(float(o.eta_min or 0.0), 1.0)
        if o.status == S.onboard and o.onboard_at is not None:
            left = ride_min - (now - o.onboard_at).total_seconds() / 60.0
        else:
            # Ещё не посадил: к дороге с пассажиром добавляем время на саму подачу.
            left = ride_min + float(settings.pickup_wait_before_onboard_min)
        minutes = int(max(round(left), 1))
        if minutes <= int(settings.pickup_wait_max_min) and (best is None or minutes < best):
            best = minutes
    return {"minutes": best} if best is not None else None


def pickup_wait_hint(session: Session, lat: float, lng: float, fee_rub: int,
                     now=None) -> Optional[dict]:
    """Подсказка «подожди — подача будет дешевле». Нечего сказать → None.

    Показываем, только когда экономия ощутима: ради двадцати рублей просить человека ждать
    десять минут — это не забота, а навязчивость.
    """
    if fee_rub < int(settings.pickup_wait_min_save_rub):
        return None
    soon = car_freeing_up_near(session, lat, lng, now)
    if soon is None:
        return None
    minutes = soon["minutes"]
    return {
        "minutes": minutes,
        "save_rub": fee_rub,
        # Обещаем «намного дешевле», а не «бесплатно»: машина освободится РЯДОМ, но может
        # оказаться в паре километров — тогда подача будет не нулевой, а маленькой. Точную
        # цифру заранее знать неоткуда, и придумывать её ради красивой фразы нельзя.
        "ru": f"Сюда уже едет машина — примерно через {minutes} мин она освободится рядом. "
              f"Подождёшь — подача выйдет намного дешевле: сейчас за неё {fee_rub} ₽.",
        "ba": f"Бында инде машина килә — яҡынса {minutes} минуттан ул яҡында бушай. "
              f"Көтһәң, килеү күпкә арзаныраҡ була: хәҙер уның өсөн {fee_rub} һум.",
    }


def pickup_fee_after_enroute(fee_rub: int, enroute: bool) -> int:
    """Скидка «по пути» на строку подачи. Округляем до 10 ₽, как и всю остальную цену."""
    if fee_rub <= 0 or not enroute:
        return max(int(fee_rub), 0)
    percent = min(max(float(settings.pickup_enroute_discount_percent), 0.0), 100.0)
    return max(round_to_10(fee_rub * (1.0 - percent / 100.0)), 0)


def order_compensation_rub(order: InstantOrder) -> int:
    """Сколько в цене заказа — компенсации водителю, а не заработок платформы, ₽.

    Список полей — в `app/compensation.py`, ОДИН на всё приложение. Раньше он был переписан
    здесь, в расчёте комиссии и в промокоде — и разъехался: зимняя дорога попала в чек, но
    не в комиссию, и водителю писали «с неё не берём», забирая 15%."""
    return comp_mod.compensation_rub(order)


def set_ride_price(order: InstantOrder, ride_rub: int, base_rub: Optional[int] = None) -> int:
    """Записать новую цену ПОЕЗДКИ и пересобрать итог. Возвращает итог.

    Зачем отдельная функция. Цену поездки пересчитывают четыре разных места: смена адреса,
    новая остановка, добавленный класс, активация предзаказа. Каждое из них раньше просто
    писало `price_estimate = новая цена` — и вместе с этим стирало бы компенсацию за подачу.
    А водитель к пассажиру уже съездил: эти километры он проехал, и забирать их не за что.

    `base_rub` — та же поездка без наценки. Нужна чеку: только по разнице видно, сколько
    в цене наценки за спрос. Не передали — прежнее значение остаётся: выдумывать базу
    задним числом нельзя, из округлённой цены она не восстанавливается.
    """
    ride = max(int(ride_rub), 0)
    order.ride_price = ride
    if base_rub is not None:
        order.ride_base_price = max(int(base_rub), 0)
    order.price_estimate = ride + order_compensation_rub(order)
    return order.price_estimate


def order_ride_price(order: InstantOrder) -> int:
    """Цена поездки без компенсаций. Старые заказы поля не имеют — там вся сумма и есть поездка."""
    ride = int(getattr(order, "ride_price", 0) or 0)
    return ride if ride > 0 else int(order.price_estimate or 0)


def price_fields(est: dict) -> dict:
    """Поля цены для НОВОГО заказа из результата `estimate()`.

    Одним местом, потому что мест создания два — обычный заказ и предзаказ. Раньше они уже
    расходились по мелочи, и каждый раз это находилось не сразу: заказ на пять утра жил
    по своим правилам ровно до первой жалобы.
    """
    return {
        "price_estimate": int(est["price"]),
        "ride_price": int(est.get("ride_price", est["price"])),
        "ride_base_price": int(est.get("base_price", 0)) or int(est.get("ride_price", est["price"])),
        "pickup_fee_kop": int(est.get("pickup_fee", 0)) * 100,
        "pickup_km": float(est.get("pickup_km", 0.0)),
        "pickup_pending": bool(est.get("pickup_pending", False)),
        "pickup_enroute": bool(est.get("pickup_enroute", False)),
        "options_fee_kop": int(est.get("options_fee", 0)) * 100,
        "weather_fee_kop": int(est.get("weather_fee", 0)) * 100,
        "weather_kind": str(est.get("weather_kind", ""))[:16],
    }


def parse_waypoints(raw: str) -> list[dict]:
    """Остановки заказа. Битый JSON — пустой список: цена важнее, чем упасть."""
    if not raw:
        return []
    try:
        data = json.loads(raw)
    except (ValueError, TypeError):
        return []
    out = []
    for w in data if isinstance(data, list) else []:
        try:
            out.append({"lat": float(w["lat"]), "lng": float(w["lng"]),
                        "text": str(w.get("text", ""))[:200], "done": bool(w.get("done"))})
        except (KeyError, TypeError, ValueError):
            continue        # одна кривая точка не должна ронять весь маршрут
    return out


def dump_waypoints(points: list[dict]) -> str:
    """Обратно в строку. Пустой список — пустая строка, а не «[]»: так проще отличать."""
    return json.dumps(points, ensure_ascii=False) if points else ""


def route_through(frm: tuple, waypoints: list[dict], to: tuple):
    """Метрики маршрута A → остановки → B: сумма отрезков.

    Считаем по кускам, а не одним запросом с промежуточными точками: наш поставщик маршрутов
    берёт деньги за запрос, а не за длину, и кусков обычно два-три. Зато каждый отрезок
    кэшируется отдельно — двигая конечную точку, человек не пересчитывает начало пути.
    """
    legs = [frm] + [(w["lat"], w["lng"]) for w in waypoints] + [to]
    dist = dur = 0.0
    tolls = False
    source = "yandex"
    for a, b in zip(legs, legs[1:]):
        m = pricing.route_metrics(a, b)
        dist += m.distance_km
        dur += m.duration_min
        tolls = tolls or m.has_tolls
        if m.source != "yandex":
            source = m.source
    return pricing.RouteMetrics(distance_km=dist, duration_min=dur, source=source,
                                has_tolls=tolls)

def round_trip_available(zone: str) -> bool:
    """Круговой рейс предлагаем только на межгороде.

    В городе водитель находит следующий заказ за минуты — порожняка нет, и скидка была бы
    просто подарком. Проблема пустой дороги живёт на дальнем плече: 450 км туда с пассажиром
    и 450 обратно ни с кем."""
    return zone == "intercity" and settings.round_trip_discount_percent > 0


def round_trip_price(one_way: int, zone: str) -> int:
    """Цена «туда и обратно»: дорога туда полная, обратная — со скидкой.

    Почему обратная дешевле, а не бесплатна: водитель везёт ту же машину те же километры и
    жжёт тот же бензин. Скидка — не подарок, а плата за то, что второй конец достался ему
    без поиска пассажира, а нам — без пустого пробега.

    Зона не межгород → цена не меняется (см. `round_trip_available`)."""
    if not round_trip_available(zone) or one_way <= 0:
        return one_way
    back = one_way * (1 - settings.round_trip_discount_percent / 100.0)
    return round_to_10(one_way + back)
def _floor_at_zone_edge(session: Session, zone: str, category: str,
                        eta_min: float, dist_km: float, surge: float) -> int:
    """Пол цены для межгорода: столько же, сколько стоила бы поездка ровно до границы зон.

    Зачем (аудит 2026-08-08, волна 167). Тарифы у зон разные: город берёт больше за километр
    (короткие поездки), межгород — меньше (дальние). На стыке это давало разрыв в другую сторону:

      * 40.0 км по городскому тарифу — 690 ₽;
      * 40.1 км по межгородскому — 560 ₽.

    Сто метров пути делали поездку на 130 ₽ **дешевле**. Для человека это выглядит как ошибка
    приложения: сосед доехал дальше и заплатил меньше, объяснить это невозможно. Водителю тоже
    прямой убыток — согласиться везти на километр дальше значит потерять деньги.

    Чиним не цифрами в базе (их правит Александр и они меняются), а правилом: цена не может
    падать при росте расстояния. Межгородская поездка стоит минимум столько же, сколько стоила
    бы поездка ровно до границы по городскому тарифу. Дальше межгородский тариф растёт своим
    темпом — дешевле за километр, как и задумано.
    """
    if zone != "intercity":
        return 0
    край = float(settings.instant_intercity_km)
    if dist_km <= край:
        return 0
    городской = active_tariff(session, "city", category)
    if not городской:
        return 0
    # Время на границе оцениваем пропорционально: маршрут тот же, просто короче.
    eta_на_краю = eta_min * (край / dist_km) if dist_km > 0 else eta_min
    return _tariff_price(городской, край, eta_на_краю, surge)


def _price_factors(route: pricing.RouteMetrics, surge: float, pickup: float,
                   weather: pricing.WeatherMetrics, night: float, dynamic: float,
                   pickup_fee: int = 0, pickup_km: float = 0.0,
                   pickup_pending: bool = False, pickup_max_rub: int = 0,
                   pickup_enroute: bool = False, pickup_full_fee: int = 0,
                   options_fee: int = 0, weather_fee: int = 0,
                   weather_kind: str = "") -> list[dict]:
    """Serializable, bilingual explanation of every signal used for the upfront fare.

    Дальняя подача приходит сюда деньгами (`pickup_fee`), а не коэффициентом: с 2026-08-23
    это строка счёта, а не наценка. Аргумент `pickup` (множитель) остался ради старых
    вызовов и по умолчанию равен 1.0 — тогда строки про множитель просто не будет."""
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
    if pickup_fee > 0:
        km = int(round(pickup_km))
        enroute_saved = max(int(pickup_full_fee) - int(pickup_fee), 0) if pickup_enroute else 0
        factors.append({
            "code": "pickup_fee", "kind": "money", "k": 1.0, "amount_rub": int(pickup_fee),
            "active": True,
            "title_ru": (f"Водителю по пути, около {km} км" if enroute_saved
                         else f"Машина едет издалека, около {km} км"),
            "title_ba": (f"Водителгә юл ыңғайы, яҡынса {km} км" if enroute_saved
                         else f"Машина алыҫтан килә, яҡынса {km} км"),
            "description_ru": (
                f"Он и так едет в эту сторону, поэтому дорога к тебе стоит вдвое дешевле: "
                f"{pickup_fee} ₽ вместо {pickup_full_fee} ₽."
                if enroute_saved else
                "Эти деньги идут водителю за дорогу к тебе — комиссию с них не берём."
            ),
            "description_ba": (
                f"Ул барыбер был яҡҡа бара, шуға һиңә тиклем юл ике тапҡыр арзаныраҡ: "
                f"{pickup_full_fee} һум урынына {pickup_fee} һум."
                if enroute_saved else
                "Был аҡса һиңә тиклем юл өсөн водителгә бара — унан комиссия алмайбыҙ."
            ),
        })
    if weather_fee > 0 and weather_kind in _WINTER_TITLE:
        ru_title, ba_title = _WINTER_TITLE[weather_kind]
        factors.append({
            "code": "weather_fee", "kind": "money", "k": 1.0, "amount_rub": int(weather_fee),
            "active": True,
            "title_ru": ru_title, "title_ba": ba_title,
            "description_ru": "Зимой расход и износ выше. Эти деньги идут водителю за тяжёлую "
                              "дорогу — комиссию с них не берём.",
            "description_ba": "Ҡышын сығым да, туҙыу ҙа юғарыраҡ. Был аҡса ауыр юл өсөн "
                              "водителгә бара — унан комиссия алмайбыҙ.",
        })
    if options_fee > 0:
        # Названия опций сюда не тянем: они живут на клиенте (`InstantOptions`), и держать
        # второй перевод на сервере значит однажды разойтись с первым. Клиент подпишет строки
        # сам по `options_prices`, а здесь — общая сумма и объяснение, куда она идёт.
        factors.append({
            "code": "options_fee", "kind": "money", "k": 1.0, "amount_rub": int(options_fee),
            "active": True,
            "title_ru": "Опции в поездке",
            "title_ba": "Сәфәрҙәге өҫтәмәләр",
            "description_ru": "Детское кресло, животное или большой багаж. Эти деньги идут "
                              "водителю — он купил кресло и возит его с собой.",
            "description_ba": "Балалар ултырғысы, хайуан йәки ҙур багаж. Был аҡса водителгә "
                              "бара — ултырғысты ул һатып алған һәм үҙе менән йөрөтә.",
        })
    if pickup_pending and pickup_max_rub > 0 and pickup_fee <= 0:
        factors.append({
            "code": "pickup_pending", "kind": "notice", "k": 1.0, "amount_rub": 0, "active": True,
            "title_ru": "Рядом свободных машин нет",
            "title_ba": "Яҡында буш машина юҡ",
            "description_ru": f"Если машина поедет издалека, добавится до {pickup_max_rub} ₽. "
                              f"Точную сумму покажем, когда водитель согласится, — "
                              f"отменить в первые {settings.cancel_free_minutes} мин можно бесплатно.",
            "description_ba": f"Машина алыҫтан килһә, {pickup_max_rub} һумға тиклем өҫтәлә. "
                              f"Водитель ризалашҡас, теүәл һумды күрһәтербеҙ — тәүге "
                              f"{settings.cancel_free_minutes} минутта бушлай кире алып була.",
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
             round_trip: bool = False, waypoints: Optional[list] = None,
             when=None, options: Optional[list] = None) -> dict:
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
    # Остановки входят в маршрут: смысл остановки в том, что за неё платят. Без этого
    # водитель везёт лишние километры даром, а пассажир не понимает, почему цена та же.
    stops = waypoints or []
    route = route_through(frm, stops, to) if stops else pricing.route_metrics(frm, to)
    dist_km = max(route.distance_km, 0.5)
    # Потолок расстояния — ОДИН на все двери такси: оценка, заказ, предзаказ (волна 187).
    #
    # Что было. Гейт такси проверял только точку ПОДАЧИ («такси работает в этом городе»),
    # а точку назначения не смотрел никто. Палец соскользнул по карте — и заказ Баймак →
    # Владивосток уходил в работу: 7000 км, 83 450 ₽. Проверено пробой целиком: водитель
    # принял, «завершил», получил долг платформе 2 503 ₽ и тут же блокировку такси за долг.
    # То есть промах пассажира отнимал работу у водителя, который ничего не нарушил.
    #
    # Текст читает пассажир в момент, когда он уверен, что всё правильно, — значит он должен
    # подсказать, что делать: посмотреть точку на карте, а не гадать, почему «ошибка».
    if dist_km > max(float(settings.max_trip_km), 1.0):
        raise herr(
            422,
            "Это слишком далеко для такси. Проверь точку назначения на карте 🗺",
            "Был такси өсөн бик алыҫ. Барасаҡ урынды картала тикшер 🗺",
        )
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
    # Ищем в том же круге, до какого matcher рассылает офферы: если ближе никого нет, поедет
    # именно эта машина, и её дорога — часть настоящей цены. Круг карты (7 км) тут занизил бы
    # и подачу, и ETA, а пассажир увидел бы «рядом никого» там, где машина на самом деле есть.
    nearest = nearby_drivers(frm[0], frm[1], limit=1, radius_km=max(RADII_KM))
    pickup_eta = int(nearest[0]["eta_min"]) if nearest else None
    pickup = pricing.pickup_k_for(pickup_eta)
    weather = pricing.weather_metrics(frm[0], frm[1])
    nk = night_k_for(t, now)
    dynamic = total_k(t, surge, now, pickup=pickup, weather=weather.k)
    base_price = _tariff_price(t, dist_km, eta_min, 1.0)
    price = _tariff_price(t, dist_km, eta_min, dynamic)
    # На стыке зон цена не должна падать (волна 167): поездка длиннее не может стоить дешевле.
    пол = _floor_at_zone_edge(session, zone, category, eta_min, dist_km, dynamic)
    if пол > price:
        price = пол
        base_price = max(base_price,
                         _floor_at_zone_edge(session, zone, category, eta_min, dist_km, 1.0))
    # Круговой рейс считаем ПОСЛЕ пола, от итоговой односторонней цены: иначе обратная
    # дорога поедет по заниженной ставке, а на стыке зон это как раз та поездка, где
    # порожняк водителя длиннее всего.
    rt_available = round_trip_available(zone)
    rt_price = round_trip_price(price, zone)
    if round_trip and rt_available:
        price = rt_price

    # --- Дальняя подача: отдельная строка поверх цены поездки (см. pickup_fee_rub) ---
    # Считаем ПОСЛЕ всего, что относится к поездке (пол зоны, круговой рейс): это не часть
    # тарифа за дорогу пассажира, а компенсация бензина водителя. Ни скидка за круговой рейс,
    # ни наценка за спрос её не касаются — 20 км порожняка стоят одинаково в любую погоду.
    pickup_straight_km = _nearest_straight_km(nearest)
    pickup_full = pickup_fee_rub(t, pickup_straight_km)
    # Проверку «по пути» делаем ТОЛЬКО когда подача вообще чего-то стоит: она лезет в
    # справочник населённых пунктов, а оценка пересчитывается на каждое движение пальца
    # по карте. Машина рядом → строки нет → и спрашивать нечего.
    enroute = False
    if pickup_full > 0:
        found = nearest_driver_for_pricing(frm[0], frm[1])
        if found is not None:
            enroute = pickup_enroute(session, found[0], frm[0], frm[1])
    pickup_fee = pickup_fee_after_enroute(pickup_full, enroute)
    pickup_pending = bool(pickup_enabled(t) and pickup_straight_km is None)
    pickup_road = round(pickup_road_km(pickup_straight_km), 2) if pickup_straight_km is not None else 0.0

    # --- Опции салона: детское кресло, животное, большой багаж ---
    # Тоже компенсация, а не наценка: водитель купил кресло, возит его и ставит. Цена одна
    # для всех классов — кресло в Бизнесе не дороже, чем в Экономе, это то же самое кресло.
    options_csv = cc.dump_options(options or [])
    options_fee = cc.options_fee_rub(options_csv)

    ride_price = price
    # --- Зимняя дорога: тоже компенсация, вне потолка наценки и без комиссии ---
    # Потолок строки — доля от цены ПОЕЗДКИ, поэтому считаем её последней.
    winter_kind = winter_road_kind(session, frm, to, now)
    winter_fee = winter_road_fee_rub(dist_km, ride_price, winter_kind)
    price = ride_price + pickup_fee + options_fee + winter_fee

    # Классы для витрины. Закрытые (не набралось водителей) отдаём с open=false — клиент
    # покажет их строкой «скоро» с кнопкой «сообщить, когда появится», а не активной кнопкой.
    # Так мы ещё и меряем спрос до того, как искать машины.
    place = class_rollout.place_at(session, frm[0], frm[1])
    opened = class_rollout.open_categories(session, place)
    # Подача считается по классам: у каждой карточки в витрине своё число минут.
    # Пусто для класса = машин этого класса рядом нет; клиент тогда молчит, а не выдумывает.
    pickup_eta_by_cat = nearby_pickup_eta_by_category(session, frm[0], frm[1])
    options_out = []          # витрина классов (имя `options` занято параметром — опциями салона)
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
            cat_ride = _tariff_price(ct, dist_km, eta_min, ct_dynamic)
            # Подача у каждого класса своя: у Бизнеса и километр дороже, и порожняк дороже.
            # Считать её один раз по выбранному классу значит показать в витрине цену, которой
            # при переключении класса не будет.
            cat_pickup = pickup_fee_after_enroute(pickup_fee_rub(ct, pickup_straight_km), enroute)
            cat_winter = winter_road_fee_rub(dist_km, cat_ride, winter_kind)
            options_out.append({
                "category": cat,
                "price": ((round_trip_price(cat_ride, zone) if round_trip else cat_ride)
                          + cat_pickup + options_fee + cat_winter),
                "one_way_price": cat_ride + cat_pickup + options_fee + cat_winter,
                "ride_price": cat_ride,
                "pickup_fee": cat_pickup,
                "options_fee": options_fee,
                "weather_fee": cat_winter,
                "base_price": _tariff_price(ct, dist_km, eta_min, 1.0),
                "dynamic_k": ct_dynamic,
                "open": cat in opened,
                # Через сколько подъедет машина ИМЕННО этого класса. None → рядом таких нет.
                "pickup_eta_min": pickup_eta_by_cat.get(cat),
            })

    return {
        "price": price,
        "base_price": base_price,
        # Из чего сложилась цена: поездка + компенсация водителю за дорогу к пассажиру.
        # `price` — то, что человек платит; эти поля объясняют, откуда взялась сумма.
        "ride_price": ride_price,
        "pickup_fee": pickup_fee,
        "pickup_km": pickup_road,
        "pickup_pending": pickup_pending,
        "pickup_max_rub": int(getattr(t, "pickup_max_rub", 0) or 0),
        # «Водителю по пути»: сколько подача стоила бы без скидки — чтобы человек видел выгоду,
        # а не просто другое число.
        "pickup_enroute": enroute,
        "pickup_full_fee": pickup_full,
        # Опции салона деньгами + расшифровка по каждой: человек должен видеть, что именно
        # добавило 150 ₽, а не просто «опции».
        "weather_fee": winter_fee,
        "weather_kind": winter_kind,
        "weather_note": winter_road_note(winter_kind, winter_fee),
        "options_fee": options_fee,
        "options_prices": [
            {"code": code, "price": cc.option_price_rub(code)}
            for code in cc.parse_options(options_csv)
        ],
        # Прайс ВСЕХ опций — чтобы клиент подписал цену на каждой галочке, а не хранил
        # второй список цен у себя. Второй список однажды разойдётся с первым, и человек
        # увидит на экране одну цену, а в заказе другую.
        "option_catalog": [
            {"code": code, "price": cc.option_price_rub(code)} for code in cc.OPTIONS
        ],
        # «Сюда уже едет машина — подождёшь, и подача будет не нужна». Считаем только когда
        # подача вообще чего-то стоит: иначе нечего экономить и незачем просить ждать.
        "pickup_wait_hint": (pickup_wait_hint(session, frm[0], frm[1], pickup_fee, now)
                             if pickup_fee > 0 else None),
        "pickup_note": pickup_note(pickup_fee, pickup_road, pickup_pending,
                                   int(getattr(t, "pickup_max_rub", 0) or 0),
                                   enroute=enroute, full_fee=pickup_full),
        # Аддитивные поля (старый клиент их просто не читает).
        "round_trip_available": rt_available,
        "round_trip_price": (rt_price + pickup_fee) if rt_available else None,
        "round_trip_discount_percent": settings.round_trip_discount_percent if rt_available else 0,
        "round_trip_max_wait_hours": settings.round_trip_max_wait_hours,
        "round_trip": bool(round_trip and rt_available),
        # Сколько остановок учтено в цене — человек должен видеть, за что платит.
        "waypoints_count": len(stops),
        "distance_km": round(dist_km, 2),
        "eta_min": round(eta_min, 1),
        "pickup_eta_min": pickup_eta,
        "zone": zone,
        "category": category,
        "tariff_id": t.id,
        # Backward-compatible field: demand/supply only. The full product is dynamic_k.
        "surge_k": surge,
        # Полный множитель, по которому посчитана цена. Его и надо класть на заказ:
        # пересчитывать потом по одному лишь `surge_k` значит терять ночь, погоду и подачу.
        "pricing_k": dynamic,
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
        "price_factors": _price_factors(route, surge, pickup, weather, nk, dynamic,
                                        pickup_fee=pickup_fee, pickup_km=pickup_road,
                                        pickup_pending=pickup_pending,
                                        pickup_max_rub=int(getattr(t, "pickup_max_rub", 0) or 0),
                                        pickup_enroute=enroute, pickup_full_fee=pickup_full,
                                        options_fee=options_fee, weather_fee=winter_fee,
                                        weather_kind=winter_kind),
        "options": options_out,
    }


# ------------------------------ пересмотр тарифа 2026-08-21 ------------------------------
# Наш тариф был ниже рынка не «слегка», а в местах, где это бьёт по водителю:
#   • минута в городе — 3 ₽ против 7,5 ₽ у Яндекса в Уфе. Водитель стоял в пробке почти
#     бесплатно, хотя именно там уходит его время;
#   • межгород — 9 ₽/км против 15 ₽ у Максима. Дальняя поездка занимает у человека весь день,
#     а платили за неё в полтора раза меньше конкурента.
# Километр в городе (11 против 11,5) был в рынке — его почти не трогаем.
#
# Верхние классы правим ТОЛЬКО там, где иначе ломается порядок: минута Комфорта не может
# стоить дешевле минуты Эконома, а километр межгорода Комфорта — сравняться с Экономом.
# Своих данных по Комфорту/Бизнесу/Минивэну нет, это по-прежнему стартовые цифры.
#
# Обновляем ТОЛЬКО строки, которые всё ещё несут прежнее стартовое значение. Отличается —
# значит цену правили руками из админки, и наше «улучшение» затёрло бы чужое решение.
_RETARIFF_2026_08_21 = (
    # (zone, category, {поле: допустимые «было»}, {поле: «стало»})
    ("city", "standard", {"per_km": (11.0,), "per_min": (3.0,)}, {"per_km": 11.5, "per_min": 5.0}),
    ("city", "comfort", {"per_min": (4.0,)}, {"per_min": 6.0}),
    ("city", "minivan", {"per_min": (5.0,)}, {"per_min": 7.0}),
    ("city", "business", {"per_min": (7.0,)}, {"per_min": 9.0}),
    # --- межгород: к рынку ---
    # Замер 21.08.2026 показал, чего стоит дальняя поездка на самом деле. Действующая
    # межгородная служба из Уфы: Сибай 12 000 ₽ (420 км), Магнитогорск 10 000 (350),
    # Белорецк 8 000 (270), Стерлитамак 4 000 (140) — везде ровно 28–30 ₽/км
    # (taxi24online.ru/tarify-mezhgorod/ufa). Яндекс при высоком спросе — 15 468 ₽.
    #
    # Мы брали 9, потом 12. При бензине 64–65 ₽/л (Башстат, август 2026), расходе 7 л/100 км
    # и износе ~3,5 ₽/км водитель на Уфа→Сибай с пустым возвратом терял 2 249 ₽ СВОИХ денег
    # за 11,5 часов работы. Он сделает такую поездку один раз.
    #
    # 24 ₽/км → чек 11 570 ₽: на 3,6% дешевле рынка, а водитель наконец в плюсе.
    ("intercity", "standard", {"per_km": (9.0, 12.0)}, {"per_km": 24.0}),
    # Шаг между классами взят с рынка, а не из головы. У Яндекса на межгороде
    # Эконом → Комфорт всего +11%, Комфорт → Комфорт+ +5%: на пятичасовой трассе платят
    # за километры, а не за салон. Прежние наши 28% были вдвое круче рыночного.
    # Минивэн и Бизнес чуть шире шаг (+17% и +24%) — там платят за вместимость и класс,
    # но данных по конкурентам на эти классы НЕТ, цифры остаются оценкой.
    ("intercity", "comfort", {"per_km": (12.0, 15.0)}, {"per_km": 26.0}),
    ("intercity", "minivan", {"per_km": (16.0, 19.0)}, {"per_km": 30.0}),
    ("intercity", "business", {"per_km": (22.0, 26.0)}, {"per_km": 37.0}),
)


def _retariff(session: Session) -> int:
    """Поднять тариф в уже работающей базе. Возврат — сколько строк тронули."""
    changed = 0
    for zone, category, was, now in _RETARIFF_2026_08_21:
        row = session.exec(
            select(Tariff).where(Tariff.zone == zone, Tariff.category == category)
        ).first()
        if row is None:
            continue
        # Допуск, а не ==: значение хранится дробным, и точное сравнение здесь читалось бы
        # как «работает», пока однажды не перестанет.
        #
        # «Было» — НАБОР допустимых значений, а не одно. Волн повышения уже две, и база
        # могла остановиться на любой из них: свежая несёт стартовое, вчерашняя — значение
        # первой волны. Обе для нас «нетронутая рукой» строка. Значение вне набора трогать
        # нельзя: его поставил человек.
        ok = all(
            any(abs(float(getattr(row, f, 0.0)) - v) <= 0.001 for v in vals)
            for f, vals in was.items()
        )
        if not ok:
            continue                      # цену правили руками — не наше дело
        for f, v in now.items():
            setattr(row, f, v)
        session.add(row)
        changed += 1
    if changed:
        session.commit()
    return changed


def seed_tariffs(session: Session) -> None:
    """Базовые тарифы город/межгород × Эконом/Комфорт. Нужны в проде (в отличие от
    seed_demo), поэтому сеются идемпотентно ПО СТРОКАМ: недостающая пара (zone, category)
    досеивается и в непустой БД (так прод получил Комфорт без ручного SQL).
    Значения — стартовые, правятся в БД без пересборки."""
    defaults = (
        # Эконом — ниже конкурентов, но не в убыток водителю (пересмотр 2026-08-21, см. ниже).
        dict(zone="city", category="standard", base=70, per_km=11.5, per_min=5.0, min_price=100),
        dict(zone="intercity", category="standard", base=80, per_km=24.0, per_min=2.0, min_price=150),
        # Комфорт (§6): авто новее/чище, немного дороже.
        dict(zone="city", category="comfort", base=90, per_km=14.0, per_min=6.0, min_price=130),
        dict(zone="intercity", category="comfort", base=100, per_km=26.0, per_min=3.0, min_price=200),
        # ⚠️ СТАРТОВЫЕ ЦИФРЫ, УТОЧНИТ АЛЕКСАНДР (docs/taxi-classes-2026-08.md §8).
        # Бизнес — премиум-седан, очный допуск водителя; ориентир ×2 к Комфорту.
        dict(zone="city", category="business", base=150, per_km=25.0, per_min=9.0, min_price=300),
        dict(zone="intercity", category="business", base=200, per_km=37.0, per_min=5.0, min_price=500),
        # Минивэн — это про вместимость (6–8 мест), а не про люкс: между Комфортом и Бизнесом.
        dict(zone="city", category="minivan", base=120, per_km=18.0, per_min=7.0, min_price=200),
        dict(zone="intercity", category="minivan", base=150, per_km=30.0, per_min=4.0, min_price=350),
    )
    added = False
    for d in defaults:
        exists = session.exec(
            select(Tariff).where(Tariff.zone == d["zone"], Tariff.category == d["category"])
        ).first()
        if not exists:
            session.add(Tariff(**d, k=1.0, **_PICKUP_DEFAULTS[d["zone"]]))
            added = True
    if added:
        session.commit()
    # Свежая БД получила новые цифры из defaults выше; работающей нужен отдельный проход —
    # существующие строки досев не трогает (в этом и была его задача).
    _retariff(session)
    _seed_pickup(session)
    _seed_night(session)


# Правила строки «дальняя подача» по зонам (см. pickup_fee_rub). В городе водитель обычно
# в паре кварталов — три километра бесплатно закрывают обычную подачу и строка не появляется
# без нужды. На межгороде порожняк длиннее по своей природе: пять километров бесплатно,
# потолок выше. Ставка ≈ себестоимость километра, взята с запасом.
_PICKUP_DEFAULTS = {
    "city": dict(pickup_free_km=3.0, pickup_per_km=11.5, pickup_max_rub=400),
    "intercity": dict(pickup_free_km=5.0, pickup_per_km=12.0, pickup_max_rub=1200),
}


def _seed_night(session: Session) -> int:
    """Включить ночную надбавку там, где её ещё не настраивали. Возврат — сколько строк тронули.

    Трогаем ТОЛЬКО нетронутые (`night_k` ≤ 1.0 — надбавки нет): ненулевое значение поставил
    человек из админки, и наше «улучшение» затёрло бы его решение. Тот же приём, что
    в `_seed_pickup`.

    Зачем вообще. Механизм ночной надбавки был написан давно, но коэффициент так и остался
    1.0: ночь стоила столько же, сколько день, и в пять утра в мороз за заказом никто не ехал.
    Настройка `night_k_default` = 0 или 1.0 → ничего не включаем.
    """
    k = float(settings.night_k_default)
    if k <= 1.0:
        return 0
    changed = 0
    for row in session.exec(select(Tariff)).all():
        if float(getattr(row, "night_k", 0.0) or 0.0) > 1.0:
            continue
        row.night_k = k
        row.night_from_hour = int(settings.night_from_hour_default)
        row.night_to_hour = int(settings.night_to_hour_default)
        session.add(row)
        changed += 1
    if changed:
        session.commit()
    return changed


def _seed_pickup(session: Session) -> int:
    """Проставить правила дальней подачи там, где их ещё нет. Возврат — сколько строк тронули.

    Трогаем ТОЛЬКО нетронутые (`pickup_per_km` = 0): ненулевое значение поставил человек
    из админки, и наше «улучшение» затёрло бы его решение. Ту же работу делает миграция —
    здесь она для свежей и для локальной БД, где alembic не запускают.
    """
    changed = 0
    for row in session.exec(select(Tariff)).all():
        if float(getattr(row, "pickup_per_km", 0.0) or 0.0) > 0:
            continue
        preset = _PICKUP_DEFAULTS.get(row.zone)
        if not preset:
            continue
        for field, value in preset.items():
            setattr(row, field, value)
        session.add(row)
        changed += 1
    if changed:
        session.commit()
    return changed


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


# ------------------------------ смена адреса назначения ------------------------------
# Правила (решение Александра, допрос 2026-08-21):
#   • меняет ПАССАЖИР сам, без спроса — кроме двух случаев ниже;
#   • цена = уже проеденное + остаток до нового адреса. Не «новая поездка от текущей точки»:
#     крюк, который водитель успел сделать, тогда пропал бы бесплатно;
#   • цена может и УПАСТЬ, если новый адрес ближе — платим за проеденное, а не за обещанное;
#   • наценка и промокод остаются те, что были при заказе: человек менял адрес, а не
#     соглашался на новые условия;
#   • спрашиваем водителя, если поездка стала межгородной ИЛИ цена выросла втрое — это уже
#     не «поменял адрес», а другая работа.

# Насколько должна вырасти цена, чтобы спросить водителя. Втрое — простой признак, понятный
# без объяснений: «было 300, стало 900» человек оценивает сразу.
DESTINATION_ASK_DRIVER_RATIO = 3.0
# Ближе этого к финишу менять адрес поздно: это уже новая поездка, честнее заказать заново.
DESTINATION_LOCK_KM = 0.7
# Не чаще одной смены в эти секунды. Не про поведение человека, а про двойные нажатия
# и заедающую сеть.
DESTINATION_MIN_GAP_SEC = 15


def _driven_km_so_far(order: InstantOrder) -> float:
    """Сколько машина реально прошла с пассажиром, км.

    Основа — накопленный след (`driven_km`). Но связь в дороге рвётся, и след бывает дырявым:
    тогда берём БОЛЬШЕЕ из следа и прямой линии с поправкой на извилистость дорог. Занижать
    водителю из-за нашей же потерянной сети нельзя — он эти километры проехал.
    """
    tracked = float(order.driven_km or 0.0)
    pos = livepos_get("order", order.id) if order.id else None
    straight = 0.0
    if pos and order.from_lat and order.from_lng:
        straight = haversine_km(order.from_lat, order.from_lng,
                                float(pos["lat"]), float(pos["lng"])) * settings.instant_road_k
    return max(tracked, straight)


def _minutes_on_board(order: InstantOrder, now: datetime) -> float:
    """Сколько минут пассажир уже едет. Время — половина цены, терять его нельзя."""
    if order.onboard_at is None:
        return 0.0
    return max((now - order.onboard_at).total_seconds() / 60.0, 0.0)


def destination_quote(session: Session, order: InstantOrder, new_to: tuple,
                      now: Optional[datetime] = None,
                      waypoints_override: Optional[list] = None) -> dict:
    """Во что обойдётся смена адреса на `new_to`. НИЧЕГО не меняет — только считает.

    До посадки пассажира считаем как обычную поездку от точки А: водитель ещё едет за ним,
    проеденного «с пассажиром» нет, и подача уже заложена в тариф.
    """
    now = now or utcnow()
    on_board = order.status == S.onboard
    if on_board:
        pos = livepos_get("order", order.id) if order.id else None
        start = (float(pos["lat"]), float(pos["lng"])) if pos else (order.from_lat, order.from_lng)
        driven_km = _driven_km_so_far(order)
        driven_min = _minutes_on_board(order, now)
    else:
        start = (order.from_lat, order.from_lng)
        driven_km = driven_min = 0.0

    # Остановки, до которых ещё не доехали, остаются: человек едет в другое место, но
    # заехать за ребёнком ему всё ещё надо. Проеденные в остаток не считаем — они уже
    # в `driven_km`.
    source = waypoints_override if waypoints_override is not None else parse_waypoints(order.waypoints_json)
    ahead = [w for w in source if not w.get("done")]
    rest = route_through(start, ahead, new_to) if ahead else pricing.route_metrics(start, new_to)
    total_km = max(driven_km + rest.distance_km, 0.5)
    total_min = max(driven_min + rest.duration_min, 0.1)

    zone = zone_for_km(total_km)
    t = active_tariff(session, zone, order.category or "standard")
    if t is None:
        return {"ok": False, "reason": "no_tariff"}

    k = order_pricing_k(order)
    price = _tariff_price(t, total_km, total_min, k)
    base_price = _tariff_price(t, total_km, total_min, 1.0)   # та же поездка без наценки — для чека
    # Сравниваем ПОЕЗДКУ с поездкой. Компенсация за подачу в обеих суммах одна и та же
    # (водитель к пассажиру уже съездил), и втягивать её в проверку «цена выросла втрое»
    # значит сравнивать разное с разным.
    old_price = order_ride_price(order)
    old_zone = zone_for_km(float(order.distance_km or 0.0))

    # Спрашиваем водителя: другая зона или цена выросла втрое.
    zone_jump = zone != old_zone
    price_jump = old_price > 0 and price >= old_price * DESTINATION_ASK_DRIVER_RATIO
    return {
        "ok": True,
        "price": price,
        "base_price": base_price,
        "old_price": old_price,
        "driven_km": round(driven_km, 2),
        "rest_km": round(rest.distance_km, 2),
        "distance_km": round(total_km, 2),
        "eta_min": round(total_min, 1),
        "zone": zone,
        "tariff_id": t.id,
        "needs_driver_ok": bool(zone_jump or price_jump),
        "ask_reason": "zone" if zone_jump else ("price" if price_jump else ""),
    }


def apply_destination(session: Session, order: InstantOrder, new_to: tuple, to_text: str,
                      quote: dict, now: Optional[datetime] = None) -> InstantOrder:
    """Переписать адрес и цену заказа. Вызывать только с уже посчитанным `quote`.

    Цена может и упасть — так и задумано: платим за проеденное, а не за обещанное. Водителя
    от этого защищает право отказаться («Не смогу»), а не удержание денег за непроеденное.
    """
    now = now or utcnow()
    order.to_lat, order.to_lng = float(new_to[0]), float(new_to[1])
    if to_text:
        order.to_text = to_text[:200]
    # Меняется цена ПОЕЗДКИ; компенсация за подачу остаётся — эти километры водитель уже проехал.
    set_ride_price(order, int(quote["price"]), quote.get("base_price"))
    order.distance_km = float(quote["distance_km"])
    order.eta_min = float(quote["eta_min"])
    if quote.get("tariff_id"):
        order.tariff_id = int(quote["tariff_id"])
    order.destination_changed_at = now
    order.destination_changes = int(order.destination_changes or 0) + 1
    # Новый адрес — новое подтверждение. Прошлое «Понял» относилось к прошлому адресу.
    order.destination_ack_at = None
    _clear_pending_destination(order)
    session.add(order)
    session.commit()
    session.refresh(order)
    return order


def apply_waypoints(session: Session, order: InstantOrder, points: list[dict],
                    quote: dict, now: Optional[datetime] = None) -> InstantOrder:
    """Записать новый набор остановок и пересчитанную цену."""
    now = now or utcnow()
    order.waypoints_json = dump_waypoints(points)
    set_ride_price(order, int(quote["price"]), quote.get("base_price"))
    order.distance_km = float(quote["distance_km"])
    order.eta_min = float(quote["eta_min"])
    order.destination_changed_at = now
    # Маршрут изменился — прежнее «Понял» относилось к прежнему маршруту.
    order.destination_ack_at = None
    session.add(order)
    session.commit()
    session.refresh(order)
    return order


def toggle_stop(session: Session, order: InstantOrder,
                now: Optional[datetime] = None) -> InstantOrder:
    """«Стоим» ↔ «Поехали». Ожидание на остановке считается по общим правилам подачи.

    Тронулись — накопленное за эту стоянку уходит в общий счётчик ожидания заказа. Так
    человек видит одну понятную сумму, а не отдельный счёт за каждую остановку.
    """
    now = now or utcnow()
    if order.stop_started_at is None:
        order.stop_started_at = now
    else:
        add_waiting_fee(order, order.stop_started_at, now)
        order.stop_started_at = None
    session.add(order)
    session.commit()
    session.refresh(order)
    return order


def _clear_pending_destination(order: InstantOrder) -> None:
    """Стереть предложение, которое ждало водителя."""
    order.pending_to_lat = order.pending_to_lng = None
    order.pending_to_text = ""
    order.pending_price = None
    order.pending_asked_at = None
    order.pending_reason = ""


def offer_destination_to_driver(session: Session, order: InstantOrder, new_to: tuple,
                                to_text: str, quote: dict,
                                now: Optional[datetime] = None) -> InstantOrder:
    """Отложить смену до согласия водителя (межгород или тройной рост цены).

    Адрес пока НЕ меняется: пассажир видит «ждём ответа водителя», водитель — вопрос.
    Молча превратить трёхсотрублёвую поездку в двенадцатитысячную нельзя ни для кого из них.
    """
    now = now or utcnow()
    order.pending_to_lat, order.pending_to_lng = float(new_to[0]), float(new_to[1])
    order.pending_to_text = (to_text or "")[:200]
    order.pending_price = int(quote["price"])
    order.pending_asked_at = now
    order.pending_reason = quote.get("ask_reason") or "price"
    session.add(order)
    session.commit()
    session.refresh(order)
    return order


def notify_driver_destination(session: Session, order: InstantOrder, quote: dict,
                              pending: bool = False) -> bool:
    """Сказать водителю, что адрес изменился (или что его об этом спрашивают).

    Это ПЕРВОЕ уведомление, которое водитель вообще получает по ходу заказа: до сих пор пуши
    по заказу шли только пассажиру, а водитель узнавал новости, переспрашивая сервер раз в
    пять секунд — и только пока держал экран открытым. За рулём он его не держит.

    Без этого пуша смена адреса превращается в ловушку: пассажир поменял, машина едет по
    старому маршруту во внешнем навигаторе, и никто не понимает, что происходит.
    """
    from .services import push_notification   # локальный импорт: в шапке был бы цикл
    if not order.driver_id:
        return False
    price = int(quote.get("price") or 0)
    to_text = (order.pending_to_text if pending else order.to_text) or ""
    where = f" — {to_text}" if to_text else ""
    if pending:
        reason_ru = ("Поездка станет междугородной" if quote.get("ask_reason") == "zone"
                     else "Цена вырастет втрое")
        reason_ba = ("Сәфәр ҡалалар-ара була" if quote.get("ask_reason") == "zone"
                     else "Хаҡ өс тапҡырға арта")
        push_notification(
            session, order.driver_id, "ride",
            "Пассажир просит изменить маршрут", "Юлсы маршрутты үҙгәртеүҙе һорай",
            f"{reason_ru}{where}. Станет {price} ₽. Согласиться или отказаться — в заказе",
            f"{reason_ba}{where}. {price} һум була. Ризалашырға йәки баш тартырға — заказда",
            ref_kind="instant", ref_id=order.id,
        )
        return True
    push_notification(
        session, order.driver_id, "ride",
        "Адрес изменился", "Адрес үҙгәрҙе",
        f"Новый адрес{where}. Цена — {price} ₽. Открой заказ и подтверди, что видел",
        f"Яңы адрес{where}. Хаҡ — {price} һум. Заказды ас та күргәнеңде раҫла",
        ref_kind="instant", ref_id=order.id,
    )
    return True


def ack_destination(session: Session, order: InstantOrder,
                    now: Optional[datetime] = None) -> InstantOrder:
    """Водитель нажал «Понял». С этого момента пассажир перестаёт видеть «он ещё не видел»."""
    order.destination_ack_at = now or utcnow()
    session.add(order)
    session.commit()
    session.refresh(order)
    return order


def decline_pending_destination(session: Session, order: InstantOrder, reason: str = "",
                                now: Optional[datetime] = None) -> InstantOrder:
    """Водитель отказался от предложенной смены. Поездка идёт по СТАРОМУ адресу.

    Отказ от нового маршрута не должен рвать тот, на который человек соглашался: пассажир
    просто едет туда, куда заказывал изначально, по изначальной цене.
    """
    from .services import push_notification   # локальный импорт: в шапке был бы цикл
    _clear_pending_destination(order)
    session.add(order)
    session.commit()
    session.refresh(order)
    if order.passenger_id:
        push_notification(
            session, order.passenger_id, "ride",
            "Водитель не может изменить маршрут", "Йөрөтөүсе маршрутты үҙгәртә алмай",
            "Едем по прежнему адресу и за прежнюю цену.",
            "Элекке адрес буйынса һәм элекке хаҡҡа барабыҙ.",
            ref_kind="instant", ref_id=order.id,
        )
    return order


# Причины, по которым водитель может сойти с маршрута. Список закрытый: свободный текст тут
# никто не читает, а выбор из четырёх — это данные. Если половина отказов «далеко от зоны»,
# значит мы плохо спрашиваем зону при выходе на линию, и чинить надо там.
EARLY_FINISH_REASONS = ("shift_end", "out_of_zone", "no_fuel", "other")

EARLY_FINISH_TEXT = {
    "shift_end": ("у водителя заканчивается смена", "йөрөтөүсенең сменаһы бөтә"),
    "out_of_zone": ("новый адрес далеко от его зоны работы", "яңы адрес уның эш зонаһынан алыҫ"),
    "no_fuel": ("не хватит топлива до нового адреса", "яңы адреҫҡа тиклем яғыулыҡ етмәй"),
    "other": ("водитель не может ехать дальше", "йөрөтөүсе артабан бара алмай"),
}


def finish_early(session: Session, order: InstantOrder, reason: str = "other",
                 now: Optional[datetime] = None) -> InstantOrder:
    """Завершить поездку там, где стоит машина. Пассажир платит за проеденное.

    Это ЗАВЕРШЕНИЕ, а не отмена. Работа сделана: человека везли, километры накрутили, деньги
    за них причитаются, и комиссию с них водитель платит как обычно. Иначе появился бы способ
    возить бесплатно — «поменяй адрес, я откажусь, поездки как будто не было».
    """
    from .services import push_notification   # локальный импорт: в шапке был бы цикл
    now = now or utcnow()
    reason = reason if reason in EARLY_FINISH_REASONS else "other"

    # Цена по факту: сколько реально проехали и сколько это заняло.
    driven_km = max(_driven_km_so_far(order), 0.5)
    driven_min = max(_minutes_on_board(order, now), 0.1)
    t = session.get(Tariff, order.tariff_id) if order.tariff_id else None
    if t is None:
        t = active_tariff(session, zone_for_km(driven_km), order.category or "standard")
    price = (_tariff_price(t, driven_km, driven_min, order_pricing_k(order))
             if t is not None else int(order.price_estimate or 0))

    # Водитель мог завершить поездку, не нажав «Поехали» после остановки. Раньше это время
    # просто пропадало: человек стоял и ждал, а деньги не доставались никому.
    if order.stop_started_at is not None:
        add_waiting_fee(order, order.stop_started_at, now)
        order.stop_started_at = None

    order.status = S.done
    order.done_at = now
    # Поездку пересчитали по факту; компенсация за подачу остаётся — водитель к пассажиру
    # доехал полностью, независимо от того, где закончилась сама поездка.
    order.ride_price = int(price)
    if t is not None:
        order.ride_base_price = _tariff_price(t, driven_km, driven_min, 1.0)
    order.price_final = (int(price) + order_compensation_rub(order)
                         + int(order.waiting_fee_kop or 0) // 100)
    order.distance_km = round(driven_km, 2)
    order.early_finish_reason = reason
    _clear_pending_destination(order)
    session.add(order)
    session.commit()
    session.refresh(order)

    ru, ba = EARLY_FINISH_TEXT[reason]
    if order.passenger_id:
        push_notification(
            session, order.passenger_id, "ride",
            "Поездка завершена раньше", "Сәфәр иртәрәк тамамланды",
            f"Дальше не поехали: {ru}. К оплате {order.price_final} ₽ за проеденное — "
            "вызови новую машину, адрес уже подставлен.",
            f"Артабан барманыҡ: {ba}. Үтелгән юл өсөн {order.price_final} һум — "
            "яңы машина саҡыр, адрес ҡуйылған.",
            ref_kind="instant", ref_id=order.id,
        )
    return order


# Сколько ждём «Понял», прежде чем сказать пассажиру «позвони водителю». Минута — столько
# человек готов смотреть на экран, не понимая, дошло ли.
DESTINATION_ACK_WAIT_SEC = 60


def destination_ack_overdue(order: InstantOrder, now: Optional[datetime] = None) -> bool:
    """Пора ли предложить пассажиру позвонить: адрес сменили, а водитель молчит."""
    if order.destination_ack_at is not None or order.destination_changed_at is None:
        return False
    if not order.driver_id:
        return False        # водителя ещё нет — некому и подтверждать
    now = now or utcnow()
    return (now - order.destination_changed_at).total_seconds() >= DESTINATION_ACK_WAIT_SEC


# Столько ждём, пока водитель заметит смену способа расчёта. Та же минута, что и для
# адреса: раньше поднимать тревогу незачем — он может смотреть на дорогу, а не в телефон.
PAYMENT_ACK_WAIT_SEC = 60


def payment_ack_overdue(order: InstantOrder, now: Optional[datetime] = None) -> bool:
    """Пора ли сказать пассажиру, что водитель ещё не в курсе смены способа."""
    if order.payment_ack_at is not None or order.payment_changed_at is None:
        return False
    if not order.driver_id:
        return False        # водителя ещё нет — некому и подтверждать
    now = now or utcnow()
    return (now - order.payment_changed_at).total_seconds() >= PAYMENT_ACK_WAIT_SEC


def can_change_destination(order: InstantOrder, now: Optional[datetime] = None) -> Optional[str]:
    """Можно ли сейчас менять адрес. None = можно, иначе код причины отказа."""
    now = now or utcnow()
    if order.status not in _DESTINATION_CHANGEABLE:
        return "status"
    # Слишком часто — почти всегда двойной тап или подвисшая сеть.
    if order.destination_changed_at is not None:
        if (now - order.destination_changed_at).total_seconds() < DESTINATION_MIN_GAP_SEC:
            return "too_often"
    # Почти доехали: менять поздно, это уже другая поездка.
    if order.status == S.onboard and order.id:
        pos = livepos_get("order", order.id)
        if pos and order.to_lat and order.to_lng:
            left = haversine_km(float(pos["lat"]), float(pos["lng"]), order.to_lat, order.to_lng)
            if left <= DESTINATION_LOCK_KM:
                return "almost_there"
    return None


def order_pricing_k(order: InstantOrder) -> float:
    """Множитель, по которому считалась цена этого заказа. Единая точка для любых пересчётов.

    Старые заказы поля не имеют (или там 0/1) — падаем на `surge_k`: он хотя бы про спрос,
    и это ровно то поведение, что было до появления `pricing_k`. Хуже не станет."""
    k = float(getattr(order, "pricing_k", 0.0) or 0.0)
    if k > 1.0:
        return k
    return float(order.surge_k or 1.0)


def _category_price(session: Session, order: InstantOrder, category: str) -> Optional[int]:
    """Цена этого же маршрута по другой категории. Сурж берём ЗАФИКСИРОВАННЫЙ на заказе,
    чтобы альтернатива не «уехала» вверх, пока человек читает предложение."""
    zone = zone_for_km(order.distance_km or 0.0)
    t = active_tariff(session, zone, category)
    if not t:
        return None
    return _tariff_price(t, max(order.distance_km or 0.5, 0.5),
                         max(order.eta_min or 0.1, 0.1), order_pricing_k(order))


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
        # Показываем ИТОГ, а не цену поездки: человек сравнивает то, что заплатит.
        total = price + order_compensation_rub(order)
        out.append({"category": cat, "price": total,
                    "price_diff": total - (order.price_estimate or 0)})
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
    if price is not None and price < order_ride_price(order):
        # База считается по ТОМУ ЖЕ классу: иначе в чеке «наценка» окажется разницей между
        # ценой Эконома и базой Комфорта — числом, которого никогда не было.
        zone = zone_for_km(order.distance_km or 0.0)
        ct = active_tariff(session, zone, cat)
        base = (_tariff_price(ct, max(order.distance_km or 0.5, 0.5),
                              max(order.eta_min or 0.1, 0.1), 1.0) if ct else None)
        set_ride_price(order, price, base)
        # Цена уехала — значит оффер, который сейчас висит у водителя на экране, врёт
        # (аудит 2026-08-08, волна 167). Проба: заказ «Комфорт» за 300 ₽ разослан водителю,
        # пассажир соглашается искать и в «Эконом» — цена падает до 100 ₽, а оффер остаётся
        # прежним. Водитель смотрит на 300, нажимает «Принять» и везёт за 100.
        #
        # Правило простое: изменилась цена — старое предложение недействительно. Снимаем оффер,
        # заказ снова уходит в рассылку и водители видят настоящую сумму. Потерять секунды
        # на повторной рассылке не страшно; узнать после поездки, что заплатят втрое меньше
        # обещанного, — это причина уйти из сервиса навсегда.
        order.current_offer_driver_id = None
        order.offer_expires_at = None
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
    """Платное ожидание за ОДИН промежуток: первые wait_free_minutes бесплатно, дальше
    wait_fee_rub_per_min ₽ за каждую ПОЛНУЮ минуту (неполная — в пользу пассажира)."""
    whole_min = int(max((now - started).total_seconds(), 0.0) // 60)
    billable = max(0, whole_min - settings.wait_free_minutes)
    # Потолок (волна 163): без него счётчик тикал бесконечно. Водитель нажал «я на месте»
    # и ушёл по делам — за три часа набегало 875 ₽, больше двух поездок. Пассажир при этом
    # не может ни остановить счётчик, ни доказать, что машины у подъезда не было.
    return min(billable * settings.wait_fee_rub_per_min, settings.wait_fee_cap_rub) * 100


def capped_waiting_kop(current_kop: int, started, now) -> int:
    """Итог ожидания по заказу после добавления промежутка, с ОДНИМ потолком на заказ.

    Отдельной функцией, потому что дверей две: остановка в пути (`toggle_stop`) и завершение
    поездки. Разойдись они — потолок снова стал бы «на каждую остановку», как было до
    аудита 2026-08-28.
    """
    total = int(current_kop or 0) + (waiting_fee_kop(started, now) if started is not None else 0)
    return min(total, settings.wait_fee_cap_rub * 100)


def add_waiting_fee(order: InstantOrder, started, now) -> int:
    """Добавить промежуток ожидания к заказу и вернуть НОВЫЙ итог по заказу, копейки.

    Потолок обещан «за ожидание по одному ЗАКАЗУ», а применялся к каждому промежутку
    отдельно и потом складывался: три остановки по часу давали 900 ₽ вместо обещанных 300 ₽
    (аудит 2026-08-28). Теперь потолок один — на заказ, как и написано в настройках.
    """
    if started is None:
        return int(order.waiting_fee_kop or 0)
    order.waiting_fee_kop = capped_waiting_kop(order.waiting_fee_kop, started, now)
    return order.waiting_fee_kop


def _order_base_fee_kop(session: Session, order: InstantOrder) -> int:
    """Штраф = подача (Tariff.base) этого заказа, копейки. Тариф не найден → 0 (не штрафуем вслепую)."""
    t = session.get(Tariff, order.tariff_id) if order.tariff_id else None
    return int(t.base) * 100 if t and t.base > 0 else 0


def cancel_fee_parts_kop(session: Session, order: InstantOrder, now=None) -> dict:
    """Из чего сложилась платная отмена, копейками. Для чека человеку.

    Раньше он видел одну сумму и решал, что его обобрали. «60 ₽ бензин водителя + 25 ₽
    ожидание» — это то же число, но с ним не спорят (решение Александра, 2026-08-28).
    """
    now = now or utcnow()
    t = session.get(Tariff, order.tariff_id) if order.tariff_id else None
    подача = _order_base_fee_kop(session, order)
    дорога = int(getattr(order, "pickup_fee_kop", 0) or 0)
    потолок = int(getattr(t, "pickup_max_rub", 0) or 0) * 100 if t is not None else 0

    # Заказ уже закрыт → показываем ФАКТ, а не «сколько было бы прямо сейчас»
    # (аудит 2026-08-08, волна 205).
    #
    # Функция одна на две работы: ДО тапа она отвечает «во сколько обойдётся отмена в эту
    # секунду» — и обязана считаться на сейчас; в чеке ПОСЛЕ она объясняет уже списанное —
    # и обязана быть неподвижной. Раньше и там и там считалось на сейчас, а счётчик ожидания
    # у отменённого заказа никто не останавливает. Получалось:
    #
    #   отменил, списали 324 ₽ → через час в чеке 400 ₽ (упёрлось в потолок);
    #   отменил БЕСПЛАТНО, списали 0 ₽ → в чеке 70 ₽ подачи.
    #
    # Последнее хуже всего: человек не заплатил ничего и видит счёт. А расшифровку и завели
    # затем, чтобы одна сумма не читалась как «нас обобрали».
    #
    # Раскладываем по фактам: подача и дорога — числа неподвижные (тариф и километры уже
    # случились), ожидание — остаток. Ноль списанного даёт нули по всем строкам.
    if order.status in TERMINAL:
        списано = max(int(order.cancel_fee_kop or 0), 0)
        # Ноль отдельной веткой не обрабатываем: он честно проходит общим путём и даёт
        # нули по всем строкам. Лишняя ветка выглядела бы защитой, а проверить её нечем —
        # мутационный проход показал, что она ничего не меняет.
        подача = min(подача, списано)
        дорога = min(дорога, списано - подача)
        return {
            "base_kop": подача,
            "pickup_kop": дорога,
            "waiting_kop": списано - подача - дорога,
            "total_kop": списано,
            "capped": потолок > 0 and списано >= потолок,
            "cap_kop": потолок,
        }

    ожидание = capped_waiting_kop(order.waiting_fee_kop, order.waiting_started_at, now)
    итого = подача + дорога + ожидание
    урезано = потолок > 0 and итого > потолок
    return {
        "base_kop": подача,
        "pickup_kop": дорога,
        "waiting_kop": ожидание,
        "total_kop": min(итого, потолок) if потолок > 0 else итого,
        "capped": урезано,
        "cap_kop": потолок,
    }


def cancel_fee_with_pickup_kop(session: Session, order: InstantOrder, now=None) -> int:
    """Платная отмена = подача по тарифу + дорога водителя к пассажиру + его ожидание.

    Раньше здесь была только подача из тарифа (70 ₽), а компенсация за дальнюю дорогу
    просто исчезала. Водитель ехал 20 км в село, пассажир видел в цене строку «дорога
    водителя — 240 ₽», отменял у подъезда — и водитель оставался с 70 ₽ за 40 км
    порожняка. Это отключало наш главный принцип ровно там, где он нужнее всего.

    Ожидание считаем ЗДЕСЬ, а не берём готовым: счётчик ожидания закрывается только когда
    пассажир сел в машину, а в этих двух случаях он не сел ни разу. Водитель при этом отждал
    ровно столько же, сколько отждал бы в поездке.

    Опции салона и зимняя дорога сюда НЕ входят: кресло не пригодилось, а зимнюю дорогу
    он не проехал — платят за то, что действительно случилось.

    Потолок — потолок строки «дорога водителя» этого тарифа (400 ₽ город / 1 200 межгород):
    отмена не может стоить больше того числа, которое человек видел ДО заказа. Тариф без
    настроенной подачи потолка не даёт — там и брать особо нечего (решения Александра,
    2026-08-28).
    """
    now = now or utcnow()
    t = session.get(Tariff, order.tariff_id) if order.tariff_id else None
    итого = (_order_base_fee_kop(session, order)
             + int(getattr(order, "pickup_fee_kop", 0) or 0)
             + capped_waiting_kop(order.waiting_fee_kop, order.waiting_started_at, now))
    потолок = int(getattr(t, "pickup_max_rub", 0) or 0) * 100 if t is not None else 0
    return min(итого, потолок) if потолок > 0 else итого


def passenger_cancel_fee_kop(session: Session, order: InstantOrder, now=None) -> int:
    """Штраф пассажира за отмену (Модель А: только фиксируем). Бесплатно, если:
    водитель ещё не назначен, ИЛИ прошло ≤ cancel_free_minutes от принятия, ИЛИ водитель
    ещё не нажал «Я на месте». Иначе — подача + дорога водителя. Показываем ДО отмены."""
    now = now or utcnow()
    if order.driver_id is None or order.accepted_at is None:
        return 0
    if now - order.accepted_at <= timedelta(minutes=settings.cancel_free_minutes):
        return 0
    if order.waiting_started_at is None:
        return 0
    return cancel_fee_with_pickup_kop(session, order, now)


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

    События (`DriverCancel`) — основной источник. Раньше считали прямо по заказу
    (`status == cancelled AND cancel_by == 'driver'`), и пока брошенный заказ так и умирал,
    этого хватало. Теперь заказ возвращается в поиск и достаётся другому: `driver_id` и
    `cancelled_at` на нём перезаписываются, след первого исчезает — и водитель мог бы
    бросать заказы без единого следствия. Событие описывает поступок, а не заказ, и
    переживает любую дальнейшую судьбу заказа.

    Старые отмены (до появления событий) по-прежнему читаем с заказов — иначе у живых
    водителей история страйков обнулилась бы в день выката. Дедуп по номеру заказа.
    """
    события = session.exec(
        select(DriverCancel).where(
            DriverCancel.driver_id == driver_id,
            DriverCancel.at >= since,
            DriverCancel.no_show == False,      # noqa: E712 — SQL, не Python
        )
    ).all()
    времена = [e.at for e in события]
    учтённые = {e.order_id for e in события}
    # Хвост совместимости: заказы, брошенные до перехода на события.
    rows = session.exec(
        select(InstantOrder).where(
            InstantOrder.driver_id == driver_id,
            InstantOrder.status == S.cancelled,
            InstantOrder.cancel_by == Actor.driver.value,
            InstantOrder.cancelled_at >= since,
        )
    ).all()
    времена += [o.cancelled_at for o in rows
                if o.accepted_at is not None and not o.no_show and o.id not in учтённые]
    return времена


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


def _settle_pickup_fee(session: Session, order: InstantOrder, driver_id: int) -> dict:
    """Зафиксировать дальнюю подачу по НАСТОЯЩЕЙ позиции согласившегося водителя.

    Работает только для заказов, созданных в момент «рядом никого» (`pickup_pending`): там
    честной цифры не было, и пассажиру мы обещали показать её при согласии водителя. Во всех
    остальных случаях сумма уже зафиксирована при создании и трогать её нельзя — цена, которую
    человек видел, нажимая «Заказать», не должна меняться после.

    Позиции нет (Redis молчит, водитель не шлёт координаты) → строка остаётся нулевой.
    Брать деньги «наверное он далеко» нельзя: это ровно тот случай, когда человек не может
    ни проверить, ни поспорить.
    """
    if not bool(getattr(order, "pickup_pending", False)):
        return {}
    t = session.get(Tariff, order.tariff_id) if order.tariff_id else None
    if not pickup_enabled(t):
        return {"pickup_pending": False}
    straight_km = driver_straight_km(driver_id, order.from_lat, order.from_lng)
    full = pickup_fee_rub(t, straight_km)
    if full <= 0:
        return {"pickup_pending": False}
    # Скидку «по пути» считаем по ТОМУ водителю, который согласился, а не по абстрактной
    # ближайшей машине: заказ и создавался в момент, когда её не было.
    enroute = pickup_enroute(session, driver_id, order.from_lat, order.from_lng)
    fee = pickup_fee_after_enroute(full, enroute)
    if fee <= 0:
        return {"pickup_pending": False, "pickup_enroute": enroute}
    return {
        "pickup_pending": False,
        "pickup_enroute": enroute,
        "pickup_fee_kop": fee * 100,
        "pickup_km": round(pickup_road_km(straight_km), 2),
        "price_estimate": order_ride_price(order) + fee,
    }


def _notify_pickup_fee(session: Session, order: InstantOrder, before_kop: int) -> None:
    """Сказать пассажиру, что к цене добавилась дорога водителя. Молча дорожать нельзя.

    Пуш уходит ТОЛЬКО когда сумма реально появилась после accept. Человек в этот момент ещё
    внутри бесплатной отмены (`cancel_free_minutes`) — об этом в тексте и говорим, иначе
    «стало дороже» читается как «обманули», а не как «вот факты, решай».
    """
    from .services import push_notification   # локальный импорт: в шапке был бы цикл
    added_kop = int(order.pickup_fee_kop or 0) - int(before_kop or 0)
    if added_kop <= 0 or not order.passenger_id:
        return
    added = added_kop // 100
    km = int(round(float(order.pickup_km or 0.0)))
    push_notification(
        session, order.passenger_id, "ride",
        "Машина едет издалека", "Машина алыҫтан килә",
        f"До тебя примерно {km} км, поэтому к цене добавилось {added} ₽ — эти деньги "
        f"водителю за дорогу. Итого {order.price_estimate} ₽. Передумал — первые "
        f"{settings.cancel_free_minutes} мин отмена бесплатна.",
        f"Һиңә тиклем яҡынса {km} км, шуға хаҡҡа {added} һум өҫтәлде — был аҡса юл өсөн "
        f"водителгә. Барлығы {order.price_estimate} һум. Уйың үҙгәрһә — тәүге "
        f"{settings.cancel_free_minutes} минутта кире алыу бушлай.",
        ref_kind="instant", ref_id=order.id,
    )


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
    pickup_fee_before = int(getattr(order, "pickup_fee_kop", 0) or 0)
    if target == S.accepted:
        values.update(driver_id=user_id, current_offer_driver_id=None, offer_expires_at=None)
        values.update(_settle_pickup_fee(session, order, user_id))
    if target == S.arriving:
        # «Я на месте»: подача завершена → пошло ожидание (5 мин бесплатно, дальше платно).
        values["waiting_started_at"] = now
    if target == S.onboard and order.waiting_started_at is not None:
        # Пассажир сел → фиксируем платное ожидание (целые копейки, задним числом не меняем).
        values["waiting_fee_kop"] = waiting_fee_kop(order.waiting_started_at, now)
    if target == S.done:
        # Водитель мог завершить поездку, не нажав «Поехали» после остановки. Раньше это
        # время пропадало: человек стоял и ждал, а деньги не доставались никому. То же
        # самое чинится в `finish_early` — двери две, правило одно.
        ожидание_коп = int(order.waiting_fee_kop or 0)
        if order.stop_started_at is not None:
            ожидание_коп = capped_waiting_kop(ожидание_коп, order.stop_started_at, now)
            values["waiting_fee_kop"] = ожидание_коп
            values["stop_started_at"] = None
        if order.price_final is None:
            # Сурж уже в price_estimate (зафиксирован при создании); ожидание — целыми ₽ сверху.
            values["price_final"] = order.price_estimate + ожидание_коп // 100

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
    if target == S.accepted:
        # После «Водитель найден», а не вместо: сначала главная новость, потом деньги.
        _notify_pickup_fee(session, fresh, pickup_fee_before)
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
        # Пассажир не вышел — водитель сделал всё: доехал и отждал. Дорога к пассажиру
        # оплачивается так же, как при платной отмене (см. cancel_fee_with_pickup_kop).
        values["cancel_fee_kop"] = cancel_fee_with_pickup_kop(session, order, now)
    прежний_статус = order.status
    бросивший = order.driver_id
    result = session.execute(
        update(InstantOrder)
        .where(InstantOrder.id == order_id, InstantOrder.status == order.status)
        .values(**values)
    )
    session.commit()
    if result.rowcount == 0:
        raise herr(409, "Заказ уже изменился", "Заказ үҙгәргән инде")
    # Поступок водителя фиксируем ОТДЕЛЬНЫМ событием, до всякого переназначения: заказ может
    # уйти дальше и перезаписать свои поля новым водителем, а бросил его этот.
    if actor == Actor.driver and бросивший and order.accepted_at is not None:
        session.add(DriverCancel(
            driver_id=бросивший, order_id=order_id, at=now,
            no_show=bool(values.get("no_show")), reason=(reason or "")[:200],
        ))
        session.commit()
    # Водитель бросил принятый заказ — человек не должен начинать всё заново.
    if _reassignable(prev_status=прежний_статус, actor=actor, order=order,
                     no_show=bool(values.get("no_show"))):
        fresh = _reassign_after_driver_cancel(session, order_id, бросивший)
        if fresh is not None:
            return fresh        # заказ снова в поиске: промокод и цена остаются при нём
    _cleanup_tried(order_id)
    fresh = session.get(InstantOrder, order_id)
    # Поездки не было → скидка по промокоду возвращается пассажиру. Один код даётся на всю жизнь
    # аккаунта, и сжечь его из-за того, что водитель не приехал, было бы нечестно.
    promo_ride.release(session, fresh)
    fresh = session.get(InstantOrder, order_id)
    _notify_cancel(session, fresh, actor)
    return fresh


def _reassignable(prev_status, actor: Actor, order: InstantOrder, no_show: bool) -> bool:
    """Можно ли вернуть брошенный заказ в поиск, а не хоронить его.

    Три границы, и каждая — про человека, а не про технику:

    • только `accepted`/`arriving`. Из `onboard` возвращать нельзя: пассажир уже в машине,
      половина дороги позади, и «тот же заказ» от старой точки А — неправда. Такое высаживание
      посреди пути — отдельный разговор со своей ценой, а не работа матчера.
    • не `no_show`. Там пассажира на месте нет — искать ему машину бессмысленно и обидно
      для следующего водителя, который приедет к пустому подъезду.
    • не больше `taxi_reassign_limit` кругов. Заказ, который перекидывают по кругу, честнее
      закрыть и дать человеку решить заново, чем час держать его в поиске.
    """
    return (
        actor == Actor.driver
        and not no_show
        and prev_status in (S.accepted, S.arriving)
        and int(getattr(order, "reassigns", 0) or 0) < int(settings.taxi_reassign_limit)
    )


def _reassign_after_driver_cancel(session: Session, order_id: int,
                                  cancelled_driver_id: int | None) -> InstantOrder | None:
    """Брошенный заказ возвращается в поиск: те же адреса, та же цена, тот же промокод.

    Раньше этого пути не было. Водитель отменял — заказ умирал, пуш бодро спрашивал
    «Ищем другого?», а экран отвечал «попробуй заказать снова». Женщина с ребёнком у подъезда
    в мороз вбивала адреса заново, теряя и цену, и очередь.

    Что сбрасываем: назначенного водителя и все отметки подачи — они принадлежали тому,
    кто уехал. Что НЕ трогаем: маршрут, класс, опции салона, цену и промокод — это заказ
    того же человека, и дорожать на ровном месте он не должен.

    Бросившего добавляем в список «уже предлагали»: круг подбора не должен вернуть заказ
    ему же через минуту. Поэтому `_cleanup_tried` здесь НЕ зовём — наоборот, дополняем.

    Возврат None — заказ за это время успели тронуть (пассажир отменил сам, воркер закрыл);
    тогда обычный путь отмены отработает как раньше.
    """
    result = session.execute(
        update(InstantOrder)
        .where(InstantOrder.id == order_id, InstantOrder.status == S.cancelled)
        .values(status=S.searching, searching_at=utcnow(), search_round=0,
                reassigns=InstantOrder.reassigns + 1,
                driver_id=None, accepted_at=None, arriving_at=None,
                waiting_started_at=None, current_offer_driver_id=None, offer_expires_at=None,
                cancel_by="", cancel_reason="", cancelled_at=None, cancel_fee_kop=0)
    )
    session.commit()
    if result.rowcount == 0:
        return None
    order = session.get(InstantOrder, order_id)
    if cancelled_driver_id:
        try:
            r = _redis()
            r.sadd(_tried_key(order_id), int(cancelled_driver_id))
            r.expire(_tried_key(order_id), 3600)
        except Exception:  # noqa: BLE001 — Redis лёг: хуже, чем «повторно предложим», не будет
            pass
    _notify_reassign(session, order)
    return try_offer_next(session, order)


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


def _score(profs: dict, did: int, dist_km: float, priority: float = 0.0) -> float:
    p = profs.get(did)
    rating = p.rating if p else 5.0
    score = settings.instant_w_dist / max(dist_km, 0.3) + settings.instant_w_rating * rating
    # 🟠 Лестница качества (§9): просевший рейтинг → штраф к score, водитель РЕЖЕ получает
    # заказы (не блок — вернуть место можно хорошими поездками).
    if rating < settings.matcher_low_rating:
        score -= settings.matcher_penalty_low_rating
    # ⭐ Приоритет (app/priority.py): решает СПОРНЫЕ случаи — когда двое примерно рядом,
    # едет тот, кто лучше работает. Вес подобран так, чтобы он перебивал около полукилометра
    # и не больше: когда один явно ближе, едет он, и это честно перед пассажиром.
    return score + priority


def rank(session: Session, ids: list, dist: dict) -> list:
    """Скоринг: ближе подача, выше рейтинг и приоритет → раньше в очереди офферов."""
    from . import priority as prio
    profs = {p.user_id: p for p in session.exec(select(DriverProfile).where(DriverProfile.user_id.in_(ids))).all()}
    бонус = {did: prio.score_bonus(prio.taxi_points(session, did)["points"]) for did in ids}
    return sorted(ids, key=lambda did: -_score(profs, did, dist.get(did, 999.0), бонус.get(did, 0.0)))


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


def scheduled_price_cap_rub(shown_rub: int) -> Optional[int]:
    """Потолок цены предзаказа: больше этого с человека не берём. None — обещание выключено.

    Зачем. Цену предзаказа сервер пересчитывает в момент подачи (рынок за ночь мог измениться),
    и человек, оформивший заказ вечером, не знает, сколько отдаст утром. Обещание потолка
    закрывает этот страх: «≈350 ₽, больше 420 ₽ не возьмём», а разницу платит платформа
    из своей комиссии — по общему правилу «скидки платит платформа, а не водитель».

    ВЫКЛЮЧЕНО по умолчанию: пока нет данных, как часто цена реально улетает, обещание может
    оказаться дороже, чем мы потянем. Включается одной настройкой (решение Александра).
    """
    pct = float(settings.scheduled_price_guarantee_percent)
    if pct <= 0 or shown_rub <= 0:
        return None
    return round_to_10(shown_rub * (1.0 + pct / 100.0))


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
                   (order.to_lat, order.to_lng), order.category or "standard",
                   # Опции заказа несут деньги (кресло 150 ₽). Пересчёт без них обнулил бы
                   # кресло у предзаказа: человек выбрал его вечером, а к утру оно исчезло.
                   options=cc.parse_options(order.options))
    # Обещание потолка (по умолчанию выключено, см. scheduled_price_cap_rub). Урезаем ТОЛЬКО
    # цену самой поездки: компенсации — бензин водителя, и платформа не вправе их резать.
    потолок = scheduled_price_cap_rub(int(order.price_estimate or 0))
    if потолок is not None and int(est["price"]) > потолок:
        компенсации = (int(est.get("pickup_fee", 0)) + int(est.get("options_fee", 0))
                       + int(est.get("weather_fee", 0)))
        est = dict(est)
        est["ride_price"] = max(потолок - компенсации, 0)
        est["price"] = est["ride_price"] + компенсации
    session.execute(
        update(InstantOrder).where(InstantOrder.id == order.id, InstantOrder.status == S.scheduled)
        .values(price_estimate=est["price"], ride_price=est.get("ride_price", est["price"]),
                ride_base_price=est.get("base_price", 0) or est.get("ride_price", est["price"]),
                pickup_fee_kop=int(est.get("pickup_fee", 0)) * 100,
                pickup_km=float(est.get("pickup_km", 0.0)),
                pickup_pending=bool(est.get("pickup_pending", False)),
                options_fee_kop=int(est.get("options_fee", 0)) * 100,
                weather_fee_kop=int(est.get("weather_fee", 0)) * 100,
                weather_kind=str(est.get("weather_kind", ""))[:16],
                distance_km=est["distance_km"],
                eta_min=est["eta_min"], tariff_id=est["tariff_id"], surge_k=est["surge_k"],
                pricing_k=est.get("pricing_k", est["surge_k"]))
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
    Рейтинг — анонимный агрегат ПАССАЖИРСКИХ оценок (такси + попутка, только те поездки,
    в которых он ехал). Поездки — по тому же правилу: завершённые такси-заказы и брони.
    Телефон/имя этим НЕ раскрываются — приватность до accept не тронута.

    Раньше рейтинг тут был ОБЩИЙ, со всеми ролями человека сразу, и половина карточки
    противоречила второй: поездки считались по-пассажирски, а звёзды — как попало
    (волна 195). Спокойная пассажирка со старой машиной выглядела для водителя на 3.0
    вместо 5.0 — а по этому числу он решает, ехать ли за ней ночью."""
    avg, cnt = passenger_rating(session, passenger_id)
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


def _notify_reassign(session: Session, order: InstantOrder) -> None:
    """Пассажиру: «водитель отменил, но мы уже ищем другую машину».

    Тон важен не меньше факта. Человек ждал у подъезда и только что потерял машину —
    ему нужно услышать, что делать ничего не надо, всё уже идёт. Раньше пуш спрашивал
    «Ищем другого?», а поиска за этим вопросом не стояло: экран предлагал заказать заново.
    """
    from .services import push_notification

    if not order.passenger_id:
        return
    try:
        push_notification(
            session, order.passenger_id, "ride",
            "Ищем другую машину", "Башҡа машина эҙләйбеҙ",
            "Водитель отменил заказ. Уже ищем другую машину — адрес и цена те же, "
            "заказывать заново не нужно.",
            "Водитель заказды кире алды. Башҡа машина эҙләйбеҙ инде — адрес та, хаҡ та "
            "шул уҡ, ҡабаттан заказ итеү кәрәкмәй.",
            ref_kind="instant", ref_id=order.id, data=_status_data(order, "searching"))
    except Exception:  # noqa: BLE001 — уведомление не должно ломать поиск машины
        pass


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
                # Сюда доходят только те случаи, где заказ уже не вернуть в поиск: пассажир
                # был в машине, либо круги переназначения кончились. Обещать поиск нельзя —
                # ровно этим старый текст («Ищем другого?») и врал.
                "Водитель отменил заказ. Другую машину найти не вышло — попробуй заказать снова.",
                "Водитель заказды кире алды. Машина табылманы — ҡабаттан заказ итеп ҡара.",
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


def notify_pay_now_debt(session: Session, debt) -> bool:
    """Комиссия за дальнюю поездку гасится сразу — сказать об этом сразу, а не молча поставить
    короткий срок.

    Без этого пуша короткий срок превращается в ловушку: водитель закрыл поездку, поехал
    дальше, а через три часа такси у него закрыто «неизвестно за что». Обычный недельный долг
    пушем не тревожим — про него достаточно кабинета."""
    from . import debt as debt_mod            # локальный импорт: без циклов на старте
    from .services import push_notification  # там же и запись в Центр уведомлений
    if debt is None or not debt_mod.is_pay_now(debt):
        return False
    rub = debt.amount_kop // 100
    push_notification(
        session, debt.driver_id, "money",
        "Комиссия за дальнюю поездку 💳", "Алыҫ сәфәр өсөн комиссия 💳",
        f"{rub} ₽ — оплати сегодня. Деньги за поездку уже у тебя: переведи по СБП в кабинете"
        " и нажми «Я оплатил»",
        f"{rub} һ — бөгөн түлә. Сәфәр аҡсаһы ҡулыңда: кабинетта СБП аша күсер ҙә"
        " «Түләнем» тип баҫ",
        ref_kind="debt", ref_id=debt.id,
    )
    return True


def notify_debt_near_block(session: Session, debt) -> bool:
    """Долг подошёл к порогу блокировки — предупредить ДО того, как такси закроется.

    Три админских события про долг («подтверждён», «списан», «оплата не найдена») пуш имеют,
    а самое частое — долг дорос до порога и такси выключилось — не имело ни одного: водитель
    упирался в отказ посреди рабочего дня и выяснял причину задним числом.

    Шлём один раз, тем заказом, которым линия пересечена (см. `debt.crossed_warn_line`)."""
    from . import debt as debt_mod            # локальный импорт: без циклов на старте
    from .services import push_notification  # там же и запись в Центр уведомлений
    if debt is None or debt.driver_id is None:
        return False
    owed = debt_mod.crossed_warn_line(session, debt.driver_id, debt.amount_kop)
    if owed is None:
        return False
    rub = owed // 100
    limit_rub = settings.debt_block_threshold_kop // 100
    push_notification(
        session, debt.driver_id, "money",
        "Долг подходит к пределу", "Бурыс сиккә яҡынлаша",
        f"Неоплаченная комиссия — {rub} ₽ из {limit_rub} ₽. Дойдёт до предела — такси"
        " закроется. Переведи по СБП в кабинете и нажми «Я оплатил»",
        f"Түләнмәгән комиссия — {limit_rub} һумдан {rub} һум. Сиккә етһә, такси ябыла."
        " Кабинетта СБП аша күсер ҙә «Түләнем» тип баҫ",
        ref_kind="debt", ref_id=debt.id,
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
        # Сколько раз заказ уже возвращался в поиск после отмены водителем. Нужен экрану:
        # человек, у которого только что была принятая машина, увидит просто «ищем машину»
        # и решит, что приложение сбросилось. Одна строка «первый водитель отменил» снимает
        # вопрос и объясняет, почему он снова в очереди.
        "reassigns": int(getattr(order, "reassigns", 0) or 0),
        "from_lat": from_lat, "from_lng": from_lng,
        "to_lat": to_lat, "to_lng": to_lng,
        "from_text": order.from_text,
        # Номер дома назначения — вместе с координатами: округлить точку и тут же написать
        # «Пушкина, 12» значит не спрятать ничего. Точку ПОДАЧИ намеренно не трогаем: там
        # адрес, подъезд и комментарий отдаются заранее, чтобы водитель нашёл человека.
        "to_text": street_only(order.to_text) if blur else order.to_text,
        "category": order.category,
        # Чем рассчитываются. Видно ОБЕИМ сторонам: спор «я думал, ты переводом» случается
        # ровно потому, что до высадки об этом никто не говорил.
        "payment_method": order.payment_method or PAY_NEGOTIATE,
        # Способ сменили на ходу, и водитель этого ещё не подтвердил. Водителю по этому
        # флагу подсвечиваем строку, пассажиру — говорим, что водитель пока не в курсе.
        "payment_changed": (order.payment_changed_at is not None
                            and order.payment_ack_at is None),
        "payment_ack_overdue": payment_ack_overdue(order),
        # «Только женщина за рулём»: экран должен объяснить, ПОЧЕМУ машину не нашли,
        # иначе человек решит, что приложение сломалось, а не что выбор сузил круг.
        "women_only": bool(getattr(order, "women_only", False)),
        "price_estimate": order.price_estimate,
        "price_final": order.price_final,
        # Из чего сложилась сумма: поездка + дорога водителя к пассажиру. Пассажиру — чтобы
        # видеть, за что платит; водителю — чтобы видеть, что компенсация за подачу дошла
        # до него целиком (комиссия с неё не берётся).
        "ride_price": order_ride_price(order),
        "pickup_fee_kop": int(order.pickup_fee_kop or 0),
        "pickup_km": float(order.pickup_km or 0.0),
        "pickup_pending": bool(getattr(order, "pickup_pending", False)),
        "pickup_enroute": bool(getattr(order, "pickup_enroute", False)),
        "options_fee_kop": int(getattr(order, "options_fee_kop", 0) or 0),
        "weather_fee_kop": int(getattr(order, "weather_fee_kop", 0) or 0),
        "weather_kind": getattr(order, "weather_kind", "") or "",
        # --- смена адреса (аддитивно: старый клиент этих полей не читает) ---
        # Сколько раз меняли адрес — чтобы в чеке было видно, почему цена не та, что при заказе.
        "destination_changes": int(order.destination_changes or 0),
        # Водитель подтвердил, что видел новый адрес. Пока нет — пассажиру через минуту
        # покажем «он ещё не видел, позвони».
        "destination_ack": order.destination_ack_at is not None,
        "destination_ack_overdue": destination_ack_overdue(order),
        # Предложение, которое ждёт слова водителя (межгород / тройная цена).
        "pending_destination": ({
            "to_text": order.pending_to_text or "",
            "price": int(order.pending_price or 0),
            "reason": order.pending_reason or "",
        } if order.pending_to_lat is not None else None),
        # Поездку завершил водитель досрочно и почему — пассажир должен видеть причину словами.
        "early_finish_reason": order.early_finish_reason or "",
        # Остановки: пассажир видит свой маршрут, водитель — куда заезжать.
        "stops": parse_waypoints(order.waypoints_json),
        "standing": order.stop_started_at is not None,
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
        # Из чего сложилась бы платная отмена ПРЯМО СЕЙЧАС. Показываем ДО тапа и в чеке
        # после: одна сумма без объяснения читается как «нас обобрали».
        "cancel_fee_parts": cancel_fee_parts_kop(session, order),
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
        # ДОВЕРИЕ (волна 160). В попутке эти три вещи показываются давно, а в такси о водителе
        # знали только имя, рейтинг и марку. Человек садится в чужую машину, часто ночью и
        # часто в райцентре, где такси одно на весь город: лицо, стаж и «свой» говорят ему
        # больше, чем звёздочка. Отдаём ТОЛЬКО пассажиру и только после accept — в обратную
        # сторону фото не идёт: водителю оно не нужно для безопасности, зато открывает дорогу
        # к «за этой не поеду».
        "driver_avatar": ((driver.avatar_url or "")
                          if (unlocked and driver and role == "passenger") else ""),
        "driver_trips": (int(getattr(prof, "trips_count", 0) or 0)
                         if (unlocked and prof and role == "passenger") else 0),
        # Месяц МЕСТНЫЙ, через общую точку (волна 203). Серверный календарь на пять часов
        # позади уфимского: человек, зарегистрировавшийся первого сентября в 02:30,
        # показывался бы как «с августа». Это бейдж доверия — по нему решают, садиться
        # ли в машину.
        "driver_since": (member_since(driver.created_at)
                         if (unlocked and driver and role == "passenger") else ""),
        # Землячество — то, чего у федеральной службы быть не может. Берём рабочую географию
        # водителя: город, а если он работает по району — район.
        "driver_from": (((prof.work_city or prof.work_district or "").strip())
                        if (unlocked and prof and role == "passenger") else ""),
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
