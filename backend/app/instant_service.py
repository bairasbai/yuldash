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
from datetime import timedelta
from enum import Enum
from typing import Optional

from fastapi import HTTPException
from sqlalchemy import update
from sqlmodel import Session, select

from sqlalchemy import func

from .config import settings
from .models import (
    Booking, BookingStatus, DriverProfile, InstantOrder, InstantOrderStatus as S, Tariff,
    TripShare, TrustedContact, User,
)
from .services import blocked_user_ids, haversine_km, send_push, send_text, user_rating
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


def estimate(session: Session, frm: tuple, to: tuple, category: str = "standard") -> dict:
    """Оценка цены: сервер считает по своей формуле, ЦЕНЕ ИЗ КЛИЕНТА НЕ ВЕРИТ.
    price = max(min_price, (base + per_km·dist + per_min·eta) · k · surge_k), до 10 ₽.
    Сурж прозрачен ДО заказа: surge_k + surge_note{ru,ba}. options — цены обоих классов
    (Эконом/Комфорт) одним запросом, чтобы пассажир выбирал с открытыми глазами."""
    dist_km = max(haversine_km(frm[0], frm[1], to[0], to[1]) * settings.instant_road_k, 0.5)
    zone = zone_for_km(dist_km)
    t = active_tariff(session, zone, category)
    if not t:
        raise HTTPException(503, "Тарифы не настроены")
    eta_min = dist_km / settings.instant_avg_speed_kmh * 60
    surge = surge_k_for(session, frm[0], frm[1])
    price = _tariff_price(t, dist_km, eta_min, surge)
    options = []
    for cat in ("standard", "comfort"):
        ct = session.exec(
            select(Tariff).where(Tariff.zone == zone, Tariff.category == cat, Tariff.active == True)  # noqa: E712
        ).first()
        if ct:
            options.append({"category": cat, "price": _tariff_price(ct, dist_km, eta_min, surge)})
    return {
        "price": price,
        "distance_km": round(dist_km, 2),
        "eta_min": round(eta_min, 1),
        "zone": zone,
        "category": category,
        "tariff_id": t.id,
        "surge_k": surge,
        "surge_note": surge_note(surge),
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
    return billable * settings.wait_fee_rub_per_min * 100


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
        raise HTTPException(409, "«Пассажир не вышел» доступно после кнопки «Я на месте»")
    allowed_at = no_show_available_at(order)
    if allowed_at is not None and now < allowed_at:
        raise HTTPException(409, "Подожди ещё немного: бесплатное ожидание "
                                 f"{settings.wait_free_minutes} мин + {settings.no_show_extra_minutes} мин сверху")


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
        raise HTTPException(403, "Ты не водитель этого заказа")
    if actor == Actor.passenger and order.passenger_id != user_id:
        raise HTTPException(403, "Это не твой заказ")


def _guard_actor(order: InstantOrder, actor: Actor, user_id: int, target: S) -> None:
    if actor == Actor.driver:
        if target == S.accepted:
            # Принять может ТОЛЬКО тот водитель, кому сейчас отправлен оффер, и пока он не истёк.
            if order.current_offer_driver_id != user_id:
                raise HTTPException(403, "Оффер отправлен другому водителю")
            if order.offer_expires_at and order.offer_expires_at < utcnow():
                raise HTTPException(409, "Оффер истёк")
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
        raise HTTPException(404, "Заказ не найден")

    # Двойной тап (не гонка accept): уже в целевом статусе — вернуть как есть.
    if idempotent and order.status == target:
        _guard_owns(order, actor, user_id)
        return order

    source = order.status
    if ALLOWED.get((source, actor)) != target:
        raise HTTPException(409, f"Нельзя перейти {source.value}→{target.value}")
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
        raise HTTPException(409, "Заказ уже изменился")
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
        raise HTTPException(404, "Заказ не найден")
    _guard_owns(order, actor, user_id)
    if order.status in TERMINAL:
        return order   # уже терминальный — idempotent
    allowed = PASSENGER_CANCELLABLE if actor == Actor.passenger else DRIVER_CANCELLABLE
    if order.status not in allowed:
        raise HTTPException(409, f"Сейчас отменить нельзя ({order.status.value})")
    now = utcnow()
    values = dict(status=S.cancelled, cancelled_at=now, cancel_by=actor.value,
                  cancel_reason=(reason or "")[:200], current_offer_driver_id=None, offer_expires_at=None)
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
        raise HTTPException(409, "Заказ уже изменился")
    _cleanup_tried(order_id)
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
    """Водители, уже занятые активным заказом (accepted/arriving/onboard) — их не предлагаем."""
    if not ids:
        return set()
    rows = session.exec(
        select(InstantOrder.driver_id).where(
            InstantOrder.driver_id.in_(ids),
            InstantOrder.status.in_([S.accepted, S.arriving, S.onboard]),
        )
    ).all()
    return {r for r in rows if r is not None}


def _order_zone_ctx(session: Session, order: InstantOrder) -> tuple:
    """Контекст зоны заказа (считаем один раз на вызов matcher'а):
    (zone, город точки А — set имён RU/BA в casefold | None, Settlement точки Б | None)."""
    from . import geo
    zone = zone_for_km(order.distance_km)
    from_city = geo.nearest_settlement(session, order.from_lat, order.from_lng)
    from_names = None
    if from_city is not None:
        from_names = {from_city.name_ru.casefold()}
        if from_city.name_ba:
            from_names.add(from_city.name_ba.casefold())
    to_city = geo.nearest_settlement(session, order.to_lat, order.to_lng) if zone == "intercity" else None
    return zone, from_names, to_city


def _zone_ok(p: DriverProfile, zone: str, from_names, to_city) -> bool:
    """Зона работы водителя vs заказ (волна 2, география). NULL-зона = прежнее поведение
    (водитель рядом — значит его город; radius-поиск уже отфильтровал дальних).
    Город заказа неизвестен (нет НП ≤30 км) → fail-open, не режем подбор в глуши."""
    if not p.work_zone:
        return True
    if zone == "city":
        if p.work_zone == "city":
            if not p.work_city or from_names is None:
                return True
            return p.work_city.casefold() in from_names
        # intercity/region: городские заказы берут только БЕЗ закреплённого направления
        return p.work_direction_id is None
    # межгород-заказ: city-водители не получают; intercity/region — если направление
    # не задано или совпадает с городом точки Б.
    if p.work_zone == "city":
        return False
    if p.work_direction_id is None or to_city is None:
        return True
    return p.work_direction_id == to_city.id


def eligible(session: Session, ids: list, order: InstantOrder) -> list:
    """Фильтр кандидатов: онлайн + верифицирован + не занят + не в блоке пассажира +
    не сам пассажир + зона работы (волна 2) + класс машины (§6: comfort-заказ — только
    водителям car_class=comfort; standard — всем)."""
    if not ids:
        return []
    comfort_only = (order.category or "standard") == "comfort"
    users = {u.id: u for u in session.exec(select(User).where(User.id.in_(ids))).all()}
    profs = {p.user_id: p for p in session.exec(select(DriverProfile).where(DriverProfile.user_id.in_(ids))).all()}
    busy = busy_driver_ids(session, ids)
    blocked = blocked_user_ids(session, order.passenger_id)
    zone, from_names, to_city = _order_zone_ctx(session, order)
    out = []
    for did in ids:
        u, p = users.get(did), profs.get(did)
        if not u or not p:
            continue
        if not p.online or not u.verified:
            continue
        if did in busy or did in blocked or did == order.passenger_id:
            continue
        if not _zone_ok(p, zone, from_names, to_city):
            continue
        if comfort_only and (p.car_class or "economy") != "comfort":
            continue          # NULL = economy: комфорт-заказ обычной машине не предлагаем
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


def _expire_no_drivers(session: Session, order: InstantOrder) -> InstantOrder:
    """Никого рядом (или нет Redis) → заказ expired, пассажиру «рядом никого»."""
    session.execute(
        update(InstantOrder)
        .where(InstantOrder.id == order.id)
        .values(status=S.expired, expired_at=utcnow(), current_offer_driver_id=None, offer_expires_at=None)
    )
    session.commit()
    _cleanup_tried(order.id)
    fresh = session.get(InstantOrder, order.id)
    send_push(session, fresh.passenger_id,
              "Рядом никого · Яҡында водитель юҡ",
              "Пока не нашли водителя. Попробуй ещё раз или оставь заявку."
              " · Водитель табылманы әле. Тағы ҡабатлап ҡара йәки ғариза ҡалдыр.",
              data=_status_data(fresh, "expired"))
    return fresh


def try_offer_next(session: Session, order: InstantOrder) -> InstantOrder:
    """Найти следующего кандидата и отправить ему оффер. Никого/лимит → expired."""
    r = _redis()
    if r is None:
        return _expire_no_drivers(session, order)
    if order.search_round >= settings.instant_max_offers:
        return _expire_no_drivers(session, order)
    cands = candidates(r, session, order, exclude=_tried_set(r, order.id))
    if not cands:
        return _expire_no_drivers(session, order)
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


def activate_scheduled(session: Session, order: InstantOrder) -> InstantOrder:
    """Активация предзаказа «на время»: scheduled → обычный поиск водителя.
    Цену/сурж пересчитываем ЗАНОВО на момент активации (не фиксируем при бронировании —
    честно: рынок мог измениться), затем стандартный matcher (searching → offered|expired).
    Зовётся вручную (клиент по таймеру) или лениво при GET /instant/scheduled, когда время
    подошло. Идемпотентно: не-scheduled заказ возвращаем как есть."""
    if order.status != S.scheduled:
        return order
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
    return start_matching(session, order)


def start_matching(session: Session, order: InstantOrder) -> InstantOrder:
    """created → searching → (offered | expired). Зовётся при создании заказа."""
    session.execute(
        update(InstantOrder).where(InstantOrder.id == order.id)
        .values(status=S.searching, searching_at=utcnow())
    )
    session.commit()
    order = session.get(InstantOrder, order.id)
    return try_offer_next(session, order)


def advance_after_no_accept(session: Session, order: InstantOrder) -> InstantOrder:
    """Оффер отклонён/протух → назад в searching → следующий кандидат."""
    session.execute(
        update(InstantOrder).where(InstantOrder.id == order.id)
        .values(status=S.searching, current_offer_driver_id=None, offer_expires_at=None)
    )
    session.commit()
    order = session.get(InstantOrder, order.id)
    return try_offer_next(session, order)


def reconcile_offer(session: Session, order: InstantOrder) -> InstantOrder:
    """Ленивый таймаут: если оффер протух — двигаем к следующему кандидату.
    Так работает без фонового воркера (arq) — на чтении заказа/поллинге оффера."""
    if order.status == S.offered and order.offer_expires_at and order.offer_expires_at < utcnow():
        return advance_after_no_accept(session, order)
    return order


def decline_offer(session: Session, order_id: int, driver_id: int) -> InstantOrder:
    """Водитель отклонил оффер → следующий кандидат. Идемпотентно."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise HTTPException(404, "Заказ не найден")
    if order.status != S.offered or order.current_offer_driver_id != driver_id:
        # оффер уже не актуален (принят/протух/отдан другому) — не ошибка
        return order
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
    """Оффер водителю — data-ONLY payload (B7a-2): свёрнутое приложение получает
    onMessageReceived и рисует полноэкранную карточку «Новый заказ» само; блок notification
    убрала бы её (система показала бы обычную плашку в трее)."""
    p_rating, p_trips = passenger_stats(session, order.passenger_id)
    send_push(
        session, driver_id, "Новый заказ",
        f"{order.from_text or 'Точка А'} → {order.to_text or 'Точка Б'} · {order.price_estimate} ₽",
        data={
            "type": "instant_offer",
            "order_id": str(order.id),
            "price": str(order.price_estimate),
            "from": order.from_text or "",
            "to": order.to_text or "",
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


def _notify_order_shares(session: Session, order: InstantOrder, share_status: str) -> None:
    """SMS доверенным контактам, с кем пассажир поделился ЭТИМ заказом. Дедуп: у шаринга
    хранится last_status — повтор того же перехода SMS не шлёт (идемпотентные переходы)."""
    shares = session.exec(select(TripShare).where(TripShare.order_id == order.id)).all()
    if not shares:
        return
    passenger = session.get(User, order.passenger_id)
    who = (passenger.name if passenger and passenger.name else None) or "Твой близкий"
    text = _SHARE_STATUS_TEXT.get(share_status)
    for share in shares:
        if share.last_status == share_status or text is None:
            continue
        share.last_status = share_status
        session.add(share)
        contact = session.get(TrustedContact, share.contact_id)
        if contact and contact.phone:
            send_text(contact.phone, f"Юлдаш: {who} {text}.")
    session.commit()


def _status_data(order: InstantOrder, status: str) -> dict:
    """data-payload пуша о ходе заказа (B9b-2): по type=instant_status клиент
    открывает экран этого заказа (тап по пушу → сразу к делу)."""
    return {"type": "instant_status", "order_id": str(order.id), "status": status}


def _notify_transition(session: Session, order: InstantOrder, target: S) -> None:
    """Пуш пассажиру на каждом переходе заказа (B9b-2): двуязычно (RU · BA, черновики BA →
    docs/tasks.md) + data-payload type=instant_status. Дедуп не нужен: переходы одноразовые
    (машина состояний не повторяет target)."""
    titles = {
        S.accepted: ("Водитель найден 🚗 · Водитель табылды 🚗",
                     "Водитель принял заказ — уже едет к тебе"
                     " · Водитель заказды ҡабул итте — һиңә килә инде"),
        S.arriving: ("Машина на месте! · Машина килеп етте!",
                     f"Водитель ждёт. Бесплатное ожидание — {settings.wait_free_minutes} мин"
                     f" · Водитель көтә. Түләүһеҙ көтөү — {settings.wait_free_minutes} мин"),
        S.onboard: ("В пути · Юлда", "Хорошей поездки! · Хәйерле юл!"),
        S.done: ("Поездка завершена · Сәфәр тамамланды",
                 f"{order.from_text or ''} → {order.to_text or ''}".strip(" →")),
    }
    if target in titles:
        title, body = titles[target]
        send_push(session, order.passenger_id, title, body, data=_status_data(order, target.value))
    # Близким (шаринг B7b-2): сел в машину / доехал.
    if target == S.onboard:
        _notify_order_shares(session, order, "sat")
    elif target == S.done:
        _notify_order_shares(session, order, "done")


def _notify_cancel(session: Session, order: InstantOrder, actor: Actor) -> None:
    """Отмена (B9b-2): водитель отменил → пуш пассажиру; пассажир отменил → пуш водителю.
    Двуязычно + data type=instant_status (тап открывает заказ)."""
    if actor == Actor.driver and order.passenger_id:
        if order.no_show:
            send_push(session, order.passenger_id,
                      "Поездка не состоялась · Сәфәр булманы",
                      "Водитель ждал, но не дождался. Частые несостоявшиеся поездки ставят такси на паузу"
                      " · Водитель көттө, ләкин көтөп ала алманы. Йыш ҡабатланһа — такси паузаға ҡуйыла",
                      data=_status_data(order, "cancelled"))
        else:
            send_push(session, order.passenger_id,
                      "Заказ отменён · Заказ кире алынды",
                      "Водитель отменил заказ. Ищем другого?"
                      " · Водитель заказды кире алды. Башҡаһын эҙләйекме?",
                      data=_status_data(order, "cancelled"))
    elif actor == Actor.passenger and order.driver_id:
        send_push(session, order.driver_id,
                  "Заказ отменён · Заказ кире алынды",
                  "Пассажир отменил заказ · Пассажир заказды кире алды",
                  data=_status_data(order, "cancelled"))
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


def order_payload(session: Session, order: InstantOrder, viewer: User) -> dict:
    """Витрина заказа. Приватность: телефоны и контакты сторон — ТОЛЬКО после accept."""
    role = "driver" if (order.driver_id == viewer.id
                        or order.current_offer_driver_id == viewer.id) else "passenger"
    unlocked = order.status in UNLOCKED
    # Рейтинг/опыт пассажира (B7a-4) — только витрине ВОДИТЕЛЯ (оффер и активный заказ):
    # анонимный агрегат, чтобы решать по данным. Пассажиру про себя не считаем (лишние запросы).
    p_rating, p_trips = (passenger_stats(session, order.passenger_id)
                         if role == "driver" else (None, 0))
    driver = session.get(User, order.driver_id) if order.driver_id else None
    prof = (session.exec(select(DriverProfile).where(DriverProfile.user_id == order.driver_id)).first()
            if order.driver_id else None)
    passenger = session.get(User, order.passenger_id)
    car = f"{prof.car_make} {prof.car_model}".strip() if prof else ""
    # Приватность: точку ПОДАЧИ пассажира водителю до accept отдаём округлённой (~1 км) — как телефоны.
    # До принятия хватает приблизительной точки для оценки расстояния/ETA; точную открываем после accept
    # (unlocked). Пассажир свою точку видит точно; направление (to_) не прячем.
    blur_from = role == "driver" and not unlocked
    from_lat = round(order.from_lat, 2) if (blur_from and order.from_lat is not None) else order.from_lat
    from_lng = round(order.from_lng, 2) if (blur_from and order.from_lng is not None) else order.from_lng
    return {
        "id": order.id,
        "status": order.status.value,
        "role": role,
        "from_lat": from_lat, "from_lng": from_lng,
        "to_lat": order.to_lat, "to_lng": order.to_lng,
        "from_text": order.from_text, "to_text": order.to_text,
        "category": order.category,
        "price_estimate": order.price_estimate,
        "price_final": order.price_final,
        "surge_k": order.surge_k,
        "distance_km": order.distance_km,
        "eta_min": order.eta_min,
        # Предзаказ «на время»: null у обычного заказа; iso-время подачи у scheduled.
        "scheduled_at": order.scheduled_at.isoformat() if order.scheduled_at else None,
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
        # Раскрывается ТОЛЬКО после accept:
        "driver_name": (driver.name if (unlocked and driver) else ""),
        "driver_car": (car if unlocked else ""),
        "driver_verified": (bool(driver.verified) if (unlocked and driver) else False),
        "driver_rating": (prof.rating if (unlocked and prof) else 0.0),
        # Телефон водителя — только пассажиру после accept; телефон пассажира — только водителю.
        "driver_phone": (driver.phone if (unlocked and driver and role == "passenger") else ""),
        "passenger_name": (passenger.name if (unlocked and passenger and role == "driver") else ""),
        "passenger_phone": (passenger.phone if (unlocked and passenger and role == "driver") else ""),
    }
