"""«Быстрый заказ» (такси-режим, Фаза 2): presence, оценка цены, заказ + машина состояний.

Отдельный поток от плановых поездок (Ride/Booking) — тот не трогаем.
Приватность: координаты не логируем; телефоны сторон — только после accept.
"""
from typing import Literal, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..db import get_session
from ..models import DriverProfile, InstantOrder, InstantOrderStatus as S, Settlement, User
from ..security import current_user
from .. import debt as debt_mod
from .. import geo as geo_mod
from .. import instant_service as isv
from .. import taxi as taxi_mod

router = APIRouter(tags=["instant"])


def _guard_taxi_not_blocked(session: Session, driver_id: int) -> None:
    """Долг по комиссии просрочен / выше порога → водитель НЕ может возить такси.
    ПОПУТКА (плановые Ride/Booking) этим не затрагивается — там своего долга нет."""
    if debt_mod.taxi_block_reason(session, driver_id) is not None:
        raise HTTPException(403, debt_mod.TAXI_BLOCKED_MSG)


def _guard_taxi_available(session: Session, lat: float | None = None, lng: float | None = None) -> None:
    """Гейт (a), волна 2: такси выключено глобально (taxi_enabled=False) или в этом городе
    (список TaxiCity) → 403 «Такси скоро». Применяется к ПАССАЖИРСКИМ ручкам (estimate,
    создание заказа) и к водительским. ПОПУТКА (rides/bookings) не затрагивается."""
    av = taxi_mod.availability(session, lat, lng)
    if not av["enabled"]:
        raise HTTPException(403, av["message"]["ru"])


def _guard_taxi_driver(session: Session, driver_id: int, lat: float | None = None, lng: float | None = None) -> None:
    """Водительские ручки такси: (a) флаг/город + (b) одобренная заявка таксиста (580-ФЗ) + долг."""
    _guard_taxi_available(session, lat, lng)
    if not taxi_mod.is_approved_taxi_driver(session, driver_id):
        raise HTTPException(403, taxi_mod.TAXI_NOT_APPROVED_MSG)
    _guard_taxi_not_blocked(session, driver_id)


# ------------------------------ схемы ------------------------------
class EstimateIn(BaseModel):
    from_lat: float = Field(..., ge=-90, le=90)
    from_lng: float = Field(..., ge=-180, le=180)
    to_lat: float = Field(..., ge=-90, le=90)
    to_lng: float = Field(..., ge=-180, le=180)
    from_text: str = Field("", max_length=200)
    to_text: str = Field("", max_length=200)
    category: str = Field("standard", max_length=40)
    # ВНИМАНИЕ: поля цены здесь НЕТ намеренно — сервер считает сам, клиенту не верим.


class OrderIn(EstimateIn):
    pass


class PresenceIn(BaseModel):
    lat: float = Field(..., ge=-90, le=90)
    lng: float = Field(..., ge=-180, le=180)


class CancelIn(BaseModel):
    reason: str = Field("", max_length=200)


class ZoneIn(BaseModel):
    work_zone: Literal["city", "intercity", "region"]
    work_city: Optional[str] = Field(None, max_length=100)
    work_direction_id: Optional[int] = None


# ------------------------------ зона работы (волна 2, география) ------------------------------
def _zone_payload(session: Session, dp: Optional[DriverProfile]) -> dict:
    direction = None
    if dp is not None and dp.work_direction_id is not None:
        s = session.get(Settlement, dp.work_direction_id)
        direction = geo_mod.settlement_payload(s) if s else None
    return {
        "work_zone": dp.work_zone if dp else None,
        "work_city": dp.work_city if dp else None,
        "work_direction_id": dp.work_direction_id if dp else None,
        "work_direction": direction,
    }


@router.get("/instant/zone")
def get_zone(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Текущая зона работы таксиста (показ в кабинете рядом с тумблером «на линии»)."""
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
    return _zone_payload(session, dp)


@router.post("/instant/zone")
def set_zone(body: ZoneIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Выбор зоны: 🏙 мой город / 🛣 межгород (+опц. направление) / 🌍 соседний регион.
    Только водитель с одобренной заявкой таксиста (580-ФЗ). Влияет ТОЛЬКО на такси-matcher,
    попутка (Ride/Booking) не затрагивается."""
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
    if not dp:
        raise HTTPException(409, "Сначала стань водителем (профиль водителя не найден)")
    if not taxi_mod.is_approved_taxi_driver(session, user.id):
        raise HTTPException(403, taxi_mod.TAXI_NOT_APPROVED_MSG)
    direction_id = body.work_direction_id
    if body.work_zone == "city":
        direction_id = None                      # направление имеет смысл только для межгорода
    if direction_id is not None and session.get(Settlement, direction_id) is None:
        raise HTTPException(404, "Направление не найдено в справочнике")
    work_city = (body.work_city or "").strip() or None
    dp.work_zone = body.work_zone
    dp.work_city = work_city if body.work_zone == "city" else None
    dp.work_direction_id = direction_id
    session.add(dp)
    session.commit()
    session.refresh(dp)
    return _zone_payload(session, dp)


# ------------------------------ presence ------------------------------
@router.post("/instant/presence")
def presence(body: PresenceIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Heartbeat координат водителя «на линии» → Redis GEO. Только для онлайн-водителя.
    Координаты в БД/логи не пишем — только эфемерно в Redis (TTL сам чистит)."""
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
    if not dp or not dp.online:
        raise HTTPException(409, "Сначала включи «Я на линии»")
    _guard_taxi_driver(session, user.id, body.lat, body.lng)   # флаг/город + заявка таксиста + долг
    ok = isv.presence_heartbeat(user.id, body.lat, body.lng)
    return {"ok": ok, "ttl_sec": isv.settings.presence_ttl_sec}


# ------------------------------ оценка цены ------------------------------
@router.post("/instant/estimate")
def estimate(body: EstimateIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Оценка цены ДО заказа. Сервер считает сам (haversine × road_k) — цена из клиента игнорируется."""
    _guard_taxi_available(session, body.from_lat, body.from_lng)   # пассажиру — только гейт (a)
    return isv.estimate(session, (body.from_lat, body.from_lng), (body.to_lat, body.to_lng), body.category)


# ------------------------------ заказ ------------------------------
@router.post("/instant/orders")
def create_order(body: OrderIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Создать быстрый заказ: сервер считает цену → matcher ищет и предлагает ближайшему водителю.
    Нет свободных/нет Redis → заказ сразу expired («рядом никого»), но запрос не падает."""
    _guard_taxi_available(session, body.from_lat, body.from_lng)   # пассажиру — только гейт (a)
    est = isv.estimate(session, (body.from_lat, body.from_lng), (body.to_lat, body.to_lng), body.category)
    # Не даём плодить параллельные активные заказы одному пассажиру (двойной тап/спам).
    existing = session.exec(
        select(InstantOrder).where(
            InstantOrder.passenger_id == user.id,
            InstantOrder.status.in_([S.created, S.searching, S.offered, S.accepted, S.arriving, S.onboard]),
        )
    ).first()
    if existing:
        return isv.order_payload(session, existing, user)
    order = InstantOrder(
        passenger_id=user.id,
        from_lat=body.from_lat, from_lng=body.from_lng,
        to_lat=body.to_lat, to_lng=body.to_lng,
        from_text=body.from_text, to_text=body.to_text,
        category=body.category,
        price_estimate=est["price"], distance_km=est["distance_km"],
        eta_min=est["eta_min"], tariff_id=est["tariff_id"],
    )
    session.add(order)
    session.commit()
    session.refresh(order)
    order = isv.start_matching(session, order)   # created → searching → offered|expired
    return isv.order_payload(session, order, user)


def _order_for_view(session: Session, order_id: int, user: User) -> InstantOrder:
    order = session.get(InstantOrder, order_id)
    if not order:
        raise HTTPException(404, "Заказ не найден")
    if user.id not in (order.passenger_id, order.driver_id, order.current_offer_driver_id):
        raise HTTPException(403, "Нет доступа к заказу")
    return order


@router.get("/instant/orders/mine")
def my_orders(limit: int = 20, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Заказы пассажира (свежие сверху) — для экрана статуса/истории."""
    rows = session.exec(
        select(InstantOrder).where(InstantOrder.passenger_id == user.id)
        .order_by(InstantOrder.id.desc()).limit(max(1, min(limit, 100)))
    ).all()
    return [isv.order_payload(session, o, user) for o in rows]


@router.get("/instant/driver/offer")
def driver_offer(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Активный оффер для водителя (поллинг-фолбэк к пушу). Протухший — сам двигается дальше."""
    if not taxi_mod.is_approved_taxi_driver(session, user.id):
        return {"offer": None}   # гейт (b): нет одобренной заявки таксиста — офферов нет
    if debt_mod.taxi_block_reason(session, user.id) is not None:
        return {"offer": None}   # заблокирован долгом — офферы такси не показываем
    order = session.exec(
        select(InstantOrder).where(
            InstantOrder.current_offer_driver_id == user.id,
            InstantOrder.status == S.offered,
        ).order_by(InstantOrder.id.desc())
    ).first()
    if not order:
        return {"offer": None}
    order = isv.reconcile_offer(session, order)
    if order.status != S.offered or order.current_offer_driver_id != user.id:
        return {"offer": None}
    # Гейт (a) по точке подачи: такси выключено глобально/в этом городе → оффер не показываем.
    if not taxi_mod.availability(session, order.from_lat, order.from_lng)["enabled"]:
        return {"offer": None}
    return {"offer": isv.order_payload(session, order, user)}


@router.get("/instant/orders/{order_id}")
def get_order(order_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Детали заказа. По пути разрешаем протухший оффер (ленивый таймаут без фонового воркера)."""
    order = _order_for_view(session, order_id, user)
    order = isv.reconcile_offer(session, order)
    return isv.order_payload(session, order, user)


# --------- переходы водителя (accept/decline/arrived/onboard/done) ---------
@router.post("/instant/orders/{order_id}/accept")
def accept(order_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель принимает оффер. Гонка двух accept → второму 409 (row-lock + условный UPDATE)."""
    existing = session.get(InstantOrder, order_id)
    if not existing:
        raise HTTPException(404, "Заказ не найден")
    # Гейты водителя: (a) флаг/город по точке подачи + (b) заявка таксиста + долг.
    _guard_taxi_driver(session, user.id, existing.from_lat, existing.from_lng)
    order = isv.transition(session, order_id, isv.Actor.driver, S.accepted, user.id, idempotent=False)
    return isv.order_payload(session, order, user)


@router.post("/instant/orders/{order_id}/decline")
def decline(order_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель отклоняет оффер → matcher предлагает следующему. Идемпотентно."""
    order = isv.decline_offer(session, order_id, user.id)
    return isv.order_payload(session, order, user)


@router.post("/instant/orders/{order_id}/arrived")
def arrived(order_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель поехал к пассажиру: accepted → arriving."""
    order = isv.transition(session, order_id, isv.Actor.driver, S.arriving, user.id)
    return isv.order_payload(session, order, user)


@router.post("/instant/orders/{order_id}/onboard")
def onboard(order_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Пассажир сел: arriving → onboard."""
    order = isv.transition(session, order_id, isv.Actor.driver, S.onboard, user.id)
    return isv.order_payload(session, order, user)


@router.post("/instant/orders/{order_id}/done")
def done(order_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Поездка завершена: onboard → done (фиксируем price_final).
    Начисляем долг по комиссии (Модель А «на доверии»): 8% с завершённого такси-заказа —
    водитель получил деньги напрямую, комиссию должен платформе. Идемпотентно (на заказ — раз)."""
    order = isv.transition(session, order_id, isv.Actor.driver, S.done, user.id)
    if order.status == S.done:
        debt_mod.accrue_for_order(session, order)
    return isv.order_payload(session, order, user)


# --------- отмена (обе стороны) ---------
@router.post("/instant/orders/{order_id}/cancel")
def cancel(order_id: int, body: CancelIn | None = None, user: User = Depends(current_user),
           session: Session = Depends(get_session)):
    """Отмена заказа. Пассажир — до посадки; водитель — после accept. Причина опциональна."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise HTTPException(404, "Заказ не найден")
    if user.id == order.passenger_id:
        actor = isv.Actor.passenger
    elif user.id == order.driver_id:
        actor = isv.Actor.driver
    else:
        raise HTTPException(403, "Нет доступа к заказу")
    reason = body.reason if body else ""
    order = isv.cancel_order(session, order_id, actor, user.id, reason)
    return isv.order_payload(session, order, user)
