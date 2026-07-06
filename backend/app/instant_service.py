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

from .config import settings
from .models import (
    DriverProfile, InstantOrder, InstantOrderStatus as S, Tariff, User,
)
from .services import blocked_user_ids, haversine_km, send_push
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
# Кто когда может отменить.
PASSENGER_CANCELLABLE = (S.created, S.searching, S.offered, S.accepted, S.arriving)
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
    Пропал heartbeat → TTL сам чистит, водитель становится невидим. Без Redis — no-op (False)."""
    r = _redis()
    if r is None:
        return False
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


def estimate(session: Session, frm: tuple, to: tuple, category: str = "standard") -> dict:
    """Оценка цены: сервер считает по своей формуле, ЦЕНЕ ИЗ КЛИЕНТА НЕ ВЕРИТ.
    price = max(min_price, base + per_km·dist + per_min·eta) · k, округление до 10 ₽."""
    dist_km = max(haversine_km(frm[0], frm[1], to[0], to[1]) * settings.instant_road_k, 0.5)
    zone = zone_for_km(dist_km)
    t = active_tariff(session, zone, category)
    if not t:
        raise HTTPException(503, "Тарифы не настроены")
    eta_min = dist_km / settings.instant_avg_speed_kmh * 60
    price = t.base + t.per_km * dist_km + t.per_min * eta_min
    price = max(t.min_price, round_to_10(price * t.k))
    return {
        "price": price,
        "distance_km": round(dist_km, 2),
        "eta_min": round(eta_min, 1),
        "zone": zone,
        "category": category,
        "tariff_id": t.id,
    }


def seed_tariffs(session: Session) -> None:
    """Базовые тарифы город/межгород в пустой БД. Нужны в проде (в отличие от seed_demo),
    поэтому сеются всегда при пустой таблице. Значения — стартовые, правятся в БД."""
    if session.exec(select(Tariff)).first():
        return
    # Стартовые цены — СИЛЬНО ниже конкурентов (правятся в БД без пересборки).
    session.add(Tariff(zone="city", category="standard", base=70, per_km=11.0, per_min=3.0, min_price=100, k=1.0))
    session.add(Tariff(zone="intercity", category="standard", base=80, per_km=9.0, per_min=2.0, min_price=150, k=1.0))
    session.commit()


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
    if target == S.done and order.price_final is None:
        values["price_final"] = order.price_estimate

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
    """Отмена заказа пассажиром или водителем. Идемпотентна, под замком."""
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
    result = session.execute(
        update(InstantOrder)
        .where(InstantOrder.id == order_id, InstantOrder.status == order.status)
        .values(status=S.cancelled, cancelled_at=utcnow(), cancel_by=actor.value,
                cancel_reason=(reason or "")[:200], current_offer_driver_id=None, offer_expires_at=None)
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


def eligible(session: Session, ids: list, order: InstantOrder) -> list:
    """Фильтр кандидатов: онлайн + верифицирован + не занят + не в блоке пассажира + не сам пассажир."""
    if not ids:
        return []
    users = {u.id: u for u in session.exec(select(User).where(User.id.in_(ids))).all()}
    profs = {p.user_id: p for p in session.exec(select(DriverProfile).where(DriverProfile.user_id.in_(ids))).all()}
    busy = busy_driver_ids(session, ids)
    blocked = blocked_user_ids(session, order.passenger_id)
    out = []
    for did in ids:
        u, p = users.get(did), profs.get(did)
        if not u or not p:
            continue
        if not p.online or not u.verified:
            continue
        if did in busy or did in blocked or did == order.passenger_id:
            continue
        out.append(did)
    return out


def _score(profs: dict, did: int, dist_km: float) -> float:
    p = profs.get(did)
    rating = p.rating if p else 5.0
    return settings.instant_w_dist / max(dist_km, 0.3) + settings.instant_w_rating * rating


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
    send_push(session, fresh.passenger_id, "Рядом никого",
              "Пока не нашли водителя. Попробуй ещё раз или оставь заявку.")
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
def _push_offer(session: Session, order: InstantOrder, driver_id: int) -> None:
    """Оффер водителю — data-payload (полноэкранная карточка с таймером на клиенте)."""
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
        },
    )


def _notify_transition(session: Session, order: InstantOrder, target: S) -> None:
    titles = {
        S.accepted: ("Водитель найден", "Водитель принял заказ и скоро выедет"),
        S.arriving: ("Водитель в пути", "Машина едет к тебе"),
        S.onboard: ("В пути", "Хорошей поездки!"),
        S.done: ("Поездка завершена", f"{order.from_text or ''} → {order.to_text or ''}".strip(" →")),
    }
    if target in titles:
        title, body = titles[target]
        send_push(session, order.passenger_id, title, body)


def _notify_cancel(session: Session, order: InstantOrder, actor: Actor) -> None:
    if actor == Actor.driver and order.passenger_id:
        send_push(session, order.passenger_id, "Заказ отменён", "Водитель отменил заказ. Ищем другого?")
    elif actor == Actor.passenger and order.driver_id:
        send_push(session, order.driver_id, "Заказ отменён", "Пассажир отменил заказ")


def order_payload(session: Session, order: InstantOrder, viewer: User) -> dict:
    """Витрина заказа. Приватность: телефоны и контакты сторон — ТОЛЬКО после accept."""
    role = "driver" if (order.driver_id == viewer.id
                        or order.current_offer_driver_id == viewer.id) else "passenger"
    unlocked = order.status in UNLOCKED
    driver = session.get(User, order.driver_id) if order.driver_id else None
    prof = (session.exec(select(DriverProfile).where(DriverProfile.user_id == order.driver_id)).first()
            if order.driver_id else None)
    passenger = session.get(User, order.passenger_id)
    car = f"{prof.car_make} {prof.car_model}".strip() if prof else ""
    return {
        "id": order.id,
        "status": order.status.value,
        "role": role,
        "from_lat": order.from_lat, "from_lng": order.from_lng,
        "to_lat": order.to_lat, "to_lng": order.to_lng,
        "from_text": order.from_text, "to_text": order.to_text,
        "category": order.category,
        "price_estimate": order.price_estimate,
        "price_final": order.price_final,
        "distance_km": order.distance_km,
        "eta_min": order.eta_min,
        "driver_id": order.driver_id,
        "offer_expires_at": order.offer_expires_at.isoformat() if order.offer_expires_at else None,
        "cancel_by": order.cancel_by,
        "cancel_reason": order.cancel_reason,
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
