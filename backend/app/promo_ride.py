"""Промокод-скидка на поездку в такси (M2, kind="taxi_ride") — ОДИН шов на всю фичу.

Красная линия Юлдаша: платформа НЕ касается денег за поездку — пассажир платит водителю
напрямую (СБП «на доверии»). Поэтому «просто дать скидку» нельзя: меньше заплатит пассажир —
меньше получит ВОДИТЕЛЬ, а он ни в чём не виноват. Скидку оплачивает ПЛАТФОРМА из своего
дохода, и это единственный честный способ:

  • пассажир платит  price − discount;
  • комиссия платформы за эту поездку уменьшается на discount (вплоть до нуля);
  • скидка БОЛЬШЕ комиссии → остаток начисляется водителю в кошелёк (LedgerEntry kind=adj).

Итог по деньгам водителя ВСЕГДА один и тот же:
    (price − D) на руки − max(C − D, 0) комиссия + max(D − C, 0) кошелёк  =  price − C,
то есть ровно столько же, как если бы промокода не было (доказано числами в
tests/test_promo_taxi.py, не «на глаз»).

Деньги — только целые копейки (int). Скидка задаётся в РУБЛЯХ (PromoCode.perk_value) и в
копейки переводится умножением на 100; доля от цены режется через Decimal вниз, в пользу
платформы — никаких float/round.

Один промокод на аккаунт за всю жизнь (UNIQUE(user_id) в PromoRedemption) — значит и скидка
одноразовая. Поездка не состоялась (отмена / «рядом никого») → скидка возвращается человеку:
терять промокод из-за того, что водитель не приехал, — несправедливо.
"""
from datetime import datetime
from decimal import Decimal
from typing import Optional

from sqlalchemy import update
from sqlmodel import Session, select

from .config import settings
from .models import InstantOrder, InstantOrderStatus, PromoCode, PromoRedemption
from .timeutil import utcnow

# Вид промокода, дающий скидку на поездку в такси. perk_value такого кода — РУБЛИ скидки.
KIND = "taxi_ride"

# Заказ в этих статусах поездкой так и не стал — скидка на нём «висит зря» и возвращается.
# done сюда НЕ входит: там скидка реально сработала.
_DEAD = (InstantOrderStatus.cancelled, InstantOrderStatus.expired)


# ---------- окно действия кампании (общий хелпер: им же валидирует /promo/apply) ----------

def in_window(promo: PromoCode, now: Optional[datetime] = None) -> bool:
    """Промокод сейчас в силе по датам: valid_from ≤ now < valid_until (пустые границы = без них)."""
    now = now or utcnow()
    if promo.valid_from and promo.valid_from > now:
        return False
    if promo.valid_until and promo.valid_until <= now:
        return False
    return True


# ---------- размеры скидки (потолки — из конфига, не хардкод) ----------

def granted_kop(promo: PromoCode) -> int:
    """Сколько скидки выдаём при АКТИВАЦИИ кода, копейки. perk_value — рубли.

    Абсолютный потолок (promo_ride_max_discount_rub) — предохранитель кампании: опечатка
    «5000» вместо «500» в админке не должна раздать платформу по частям."""
    if promo is None or promo.kind != KIND:
        return 0
    rub = max(int(promo.perk_value or 0), 0)
    cap = max(int(settings.promo_ride_max_discount_rub), 0)
    return min(rub, cap) * 100


def cap_for_price(discount_kop: int, price_rub: int) -> int:
    """Скидка, применимая к КОНКРЕТНОЙ цене, копейки.

    Второй потолок — доля от поездки (promo_ride_max_price_share): платформа готова оплатить
    часть поездки, но не поездку целиком. Долю считаем Decimal и округляем ВНИЗ до целых
    рублей — цены у нас целые, а остаток округления должен быть в пользу платформы."""
    d = max(int(discount_kop or 0), 0)
    price = max(int(price_rub or 0), 0)
    if d <= 0 or price <= 0:
        return 0
    share = min(max(float(settings.promo_ride_max_price_share), 0.0), 1.0)
    share_rub = int(Decimal(price) * Decimal(str(share)))     # вниз, в пользу платформы
    return min(d, share_rub * 100)


def price_kop(order: InstantOrder) -> int:
    """Полная цена поездки в копейках (фактическая, иначе оценка). База комиссии — она же."""
    if order is None:
        return 0
    rub = order.price_final if order.price_final is not None else order.price_estimate
    return max(int(rub or 0), 0) * 100


def discountable_rub(order: InstantOrder) -> int:
    """С какой суммы промокод вправе давать скидку, ₽ — цена МИНУС компенсации водителю.

    В цене с 2026-08-23 живёт строка «машина едет издалека» — это бензин водителя, а не
    выручка. Скидка по промокоду — наш подарок пассажиру за наш счёт, и считать её долей
    от чужого топлива неправильно: потолок «не больше N% от цены» тогда растёт ровно тогда,
    когда водителю и так тяжело.

    Поля читаем прямо с заказа: модуль заказов зависит от этого файла, обратный импорт
    замкнул бы круг.
    """
    if order is None:
        return 0
    rub = order.price_final if order.price_final is not None else order.price_estimate
    compensation_rub = (int(getattr(order, "pickup_fee_kop", 0) or 0)
                        + int(getattr(order, "options_fee_kop", 0) or 0)) // 100
    return max(int(rub or 0) - compensation_rub, 0)


def payable_kop(order: InstantOrder) -> int:
    """Сколько пассажир реально платит за поездку: цена минус зафиксированная скидка."""
    if order is None:
        return 0
    return max(price_kop(order) - max(int(order.promo_discount_kop or 0), 0), 0)


def split_commission(commission_kop: int, discount_kop: int) -> tuple[int, int]:
    """Как платформа оплачивает скидку: (комиссия к оплате водителем, компенсация водителю).

    Сначала гасим скидку СВОЕЙ комиссией (до нуля) — платформа отдаёт свой доход, а не чужие
    деньги. Остаток (скидка больше комиссии) доплачиваем водителю в кошелёк.
    Инвариант: водитель получает price − C при любом размере скидки (см. шапку модуля)."""
    c = max(int(commission_kop or 0), 0)
    d = max(int(discount_kop or 0), 0)
    return max(c - d, 0), max(d - c, 0)


# ---------- доступность скидки у пользователя ----------

def _redemption(session: Session, user_id: int, lock: bool = False) -> Optional[PromoRedemption]:
    q = select(PromoRedemption).where(PromoRedemption.user_id == user_id)
    if lock:
        q = q.with_for_update()
    return session.exec(q).first()


def available(session: Session, user_id: int, *, lock: bool = False):
    """(redemption, promo) с НЕпотраченной скидкой на такси — или (None, None).

    Скидка доступна, когда всё сразу: код вида taxi_ride, кампания включена и в сроке
    (выключили за абуз — ещё не потраченные скидки гаснут), сумма > 0, и скидка не занята
    ЖИВЫМ заказом. Заказ, который не состоялся, скидку не держит."""
    red = _redemption(session, user_id, lock=lock)
    if red is None or (red.discount_kop or 0) <= 0:
        return None, None
    promo = session.get(PromoCode, red.promo_id)
    if promo is None or promo.kind != KIND or not promo.active or not in_window(promo):
        return None, None
    if red.used_order_id is not None:
        used = session.get(InstantOrder, red.used_order_id)
        # Заказа нет (следов не осталось) → считаем потраченной: лучше не выдать вторую скидку,
        # чем выдать её дважды. Живой/состоявшийся заказ скидку держит.
        if used is None or used.status not in _DEAD:
            return None, None
    elif red.used_at is not None:
        # Ссылку на заказ сняли, а отметку о трате — нет. Так бывает ровно в одном месте:
        # водитель удалил аккаунт, и его заказы (вместе с чужой поездкой) исчезли (account.py).
        # Скидка тогда РЕАЛЬНО отработала, и возвращать её второй раз нельзя. Возврат при отмене
        # (release) снимает обе отметки сразу — там скидка честно цела.
        return None, None
    return red, promo


def note(discount_kop: int) -> Optional[dict]:
    """Честное объяснение скидки ДО заказа (RU + черновой BA), в стиле surge_note/night_note."""
    if discount_kop <= 0:
        return None
    rub = discount_kop // 100
    return {
        "ru": f"Скидка по промокоду −{rub} ₽. Её оплачивает Юлдаш — водитель получит своё полностью.",
        "ba": f"Промокод буйынса ташлама −{rub} һум. Уны Юлдаш түләй — водитель үҙенекен тулыһынса ала.",
    }


def preview(session: Session, user_id: int, price_rub: int) -> dict:
    """Блок скидки для ответа оценки цены. Скидки нет → честные нули (поле всегда на месте,
    чтобы клиенту не приходилось гадать, «не пришло» это или «не положено»)."""
    red, promo = available(session, user_id)
    disc = cap_for_price(red.discount_kop, price_rub) if red is not None else 0
    return {
        "promo_code": (promo.code if (promo is not None and disc > 0) else ""),
        "promo_discount_kop": disc,
        "price_with_discount": max(int(price_rub or 0) - disc // 100, 0),
        "promo_note": note(disc),
    }


# ---------- списание / возврат скидки ----------

def consume(session: Session, user_id: int, order: InstantOrder) -> int:
    """Списать скидку на заказ и зафиксировать её в нём. Возврат: скидка в копейках (0 — нет).

    Захват ресурса — под row-lock И атомарным CAS-UPDATE: Postgres в проде держит строку
    (`with_for_update`), SQLite её игнорирует, поэтому условие «скидка всё ещё свободна»
    встроено в WHERE и решается rowcount — проигравший гонку просто едет без скидки.
    Идемпотентно: заказ, на который скидка уже списана, второй раз её не тратит."""
    if order is None or order.id is None:
        return 0
    held = _redemption(session, user_id, lock=True)
    if held is not None and held.used_order_id == order.id:
        return max(int(order.promo_discount_kop or 0), 0)      # уже списана на ЭТОТ заказ
    red, _promo = available(session, user_id, lock=True)
    if red is None:
        return 0
    disc = cap_for_price(red.discount_kop, discountable_rub(order))
    if disc <= 0:
        return 0
    stale_id = red.used_order_id
    if stale_id is not None:
        # Скидка висит на не состоявшемся заказе — снимаем её оттуда. Условие по статусу
        # обязательно: пассажир мог нажать «подожду», и «мёртвый» заказ ожил бы (expired →
        # searching → done). Тогда скидка занята, и второй раз её тратить нельзя.
        freed = session.execute(
            update(InstantOrder)
            .where(InstantOrder.id == stale_id, InstantOrder.status.in_(_DEAD))
            .values(promo_discount_kop=0)
        )
        if freed.rowcount == 0:
            session.rollback()
            return 0
    still_free = (PromoRedemption.used_order_id.is_(None) if stale_id is None
                  else PromoRedemption.used_order_id == stale_id)
    taken = session.execute(
        update(PromoRedemption)
        .where(PromoRedemption.id == red.id, still_free)
        .values(used_order_id=order.id, used_at=utcnow())
    )
    if taken.rowcount == 0:                                    # гонку проиграли — едем без скидки
        session.rollback()
        return 0
    session.execute(
        update(InstantOrder).where(InstantOrder.id == order.id).values(promo_discount_kop=disc)
    )
    session.commit()
    return disc


def release(session: Session, order: InstantOrder) -> bool:
    """Поездка не состоялась → скидка возвращается пассажиру (не сгорает). Идемпотентно.

    Возврат True — скидка действительно снята с этого заказа."""
    if order is None or order.id is None or int(order.promo_discount_kop or 0) <= 0:
        return False
    red = session.exec(
        select(PromoRedemption).where(PromoRedemption.used_order_id == order.id).with_for_update()
    ).first()
    session.execute(
        update(InstantOrder).where(InstantOrder.id == order.id).values(promo_discount_kop=0)
    )
    if red is not None:
        session.execute(
            update(PromoRedemption).where(PromoRedemption.id == red.id)
            .values(used_order_id=None, used_at=None)
        )
    session.commit()
    return True


def release_ids(order_ids) -> int:
    """Вернуть скидку по списку заказов, закончившихся без поездки. Своя сессия.

    Возврат обещан в шапке модуля — «поездка не состоялась → скидка возвращается», — но
    звала `release` ровно одна дверь: отмена человеком. А заказ заканчивается без поездки
    ещё тремя путями: система закрыла зависший (`taxi_worker.close_stuck_orders`), кончилась
    очередь «подожду машину», ночная чистка закрыла забытый (`cleanup.close_stale_orders`).
    По ним промокод, который даётся раз в жизни аккаунта, сгорал за поездку, которой не было.

    Возвращает, сколько скидок реально снято (идемпотентно: повтор вернёт 0).
    """
    ids = [int(i) for i in (order_ids or []) if i is not None]
    if not ids:
        return 0
    from .db import engine
    freed = 0
    with Session(engine) as session:
        for oid in ids:
            order = session.get(InstantOrder, oid)
            if order is not None and release(session, order):
                freed += 1
    return freed


def reclamp(session: Session, order: InstantOrder) -> int:
    """Пересчитать потолок «доля от цены» после того, как цена заказа изменилась.

    Нужен предзаказу «на время»: цена пересчитывается заново в момент активации, и скидка,
    зафиксированная при бронировании, могла стать больше допустимой доли подешевевшей поездки.
    Скидку только УМЕНЬШАЕМ — обещанную сумму задним числом не поднимаем."""
    if order is None or order.id is None:
        return 0
    disc = max(int(order.promo_discount_kop or 0), 0)
    if disc <= 0:
        return 0
    capped = cap_for_price(disc, discountable_rub(order))
    if capped == disc:
        return disc
    session.execute(
        update(InstantOrder).where(InstantOrder.id == order.id).values(promo_discount_kop=capped)
    )
    session.commit()
    return capped
