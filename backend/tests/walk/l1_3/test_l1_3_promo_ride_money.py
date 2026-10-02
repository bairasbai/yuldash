"""leaf-1.3 — деньги: скидка по промокоду на поездку в такси (app/promo_ride.py).

Фокус группы «Деньги» применительно к этому модулю:
  R8  — скидку нельзя применить к ЧУЖОЙ поездке (consume() обязан сверять владельца заказа);
  R9  — оплата пассажира никогда не уходит в минус, даже если скидка больше цены;
  R10 — потолок «доля от цены» считается в целых рублях и округляется ВНИЗ (в пользу платформы);
  R11 — скидка не списывается дважды даже под реальной гонкой на PostgreSQL;
  R12 — отменённая/просроченная поездка возвращает скидку, а не сжигает её;
  R13 — водитель никогда не получает «отрицательную» комиссию (остаток уходит компенсацией).

R8 — это НАЙДЕННАЯ ошибка этого листа: `promo_ride.consume(session, user_id, order)` принимал
ЛЮБОЙ `order`, не проверяя, что `order.passenger_id == user_id`. Оба текущих вызова (создание
обычного заказа и предзаказа в routers/instant.py) безопасны, потому что сами же сначала
создают заказ с `passenger_id=user.id` и тут же вызывают `consume(user.id, order)` — но сама
функция этого не гарантировала. Прямой тест ниже (`test_consume_refuses_foreign_order`)
воспроизводит дыру напрямую: чужой промокод применяется к заказу постороннего пассажира.
Исправление — одна строка (сверка владельца в начале функции).
"""
import threading
import time
from datetime import timedelta

import pytest
from sqlalchemy import text
from sqlmodel import Session, select

from app import promo_ride
from app.config import settings
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, PromoCode, PromoRedemption

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


def _order(**kw):
    kw.setdefault("from_lat", ORIG[0])
    kw.setdefault("from_lng", ORIG[1])
    kw.setdefault("to_lat", DEST[0])
    kw.setdefault("to_lng", DEST[1])
    return InstantOrder(**kw)


def _give_discount(session: Session, user_id: int, code: str, rub: int) -> PromoCode:
    promo = PromoCode(code=code, kind=promo_ride.KIND, perk_value=rub, active=True)
    session.add(promo)
    session.commit()
    session.refresh(promo)
    session.add(PromoRedemption(promo_id=promo.id, user_id=user_id, discount_kop=rub * 100))
    session.commit()
    return promo


# ============================ R8: нельзя применить к чужой поездке ============================
def test_consume_refuses_foreign_order(user_factory):
    """Скидка пассажира А не должна ложиться на заказ пассажира Б, даже если кто-то (баг в коде,
    не злоумышленник из HTTP — текущие эндпоинты так не делают) вызовет consume(A, заказ_Б)."""
    owner = user_factory("ЧужПромоВладелец")
    stranger = user_factory("ЧужПромоПассажир")
    with Session(engine) as s:
        _give_discount(s, owner["id"], "FOREIGNRIDE", 100)
        foreign_order = _order(passenger_id=stranger["id"], price_estimate=500)
        s.add(foreign_order)
        s.commit()
        s.refresh(foreign_order)

        got = promo_ride.consume(s, owner["id"], foreign_order)
        s.refresh(foreign_order)
        assert got == 0, "скидка чужого человека ушла на поездку постороннего пассажира"
        assert foreign_order.promo_discount_kop == 0

    with Session(engine) as s:
        red = s.exec(select(PromoRedemption).where(PromoRedemption.user_id == owner["id"])).first()
        assert red.used_order_id is None, "скидка владельца не должна считаться потраченной на чужую поездку"
        assert red.discount_kop == 10000, "сама скидка должна остаться цела для своей будущей поездки"


def test_consume_still_works_for_own_order(user_factory):
    """Соседний тест: сверка владельца не должна сломать обычный (свой) путь списания."""
    pax = user_factory("СвойПромоПас")
    with Session(engine) as s:
        _give_discount(s, pax["id"], "OWNRIDE", 100)
        own_order = _order(passenger_id=pax["id"], price_estimate=500)
        s.add(own_order)
        s.commit()
        s.refresh(own_order)

        got = promo_ride.consume(s, pax["id"], own_order)
        assert got == 10000


# ============================ R9: оплата никогда не уходит в минус ============================
def test_payable_never_negative():
    order = _order(passenger_id=1, price_estimate=100, promo_discount_kop=999_999)
    assert promo_ride.payable_kop(order) == 0


# ============================ R10: целые рубли, округление ВНИЗ ============================
def test_share_cap_rounds_down_in_favor_of_platform(monkeypatch):
    monkeypatch.setattr(settings, "promo_ride_max_price_share", 0.33)
    # 203 ₽ * 0.33 = 66.99 → должно уйти ВНИЗ, до 66 ₽ (6600 коп), не до 67.
    assert promo_ride.cap_for_price(999_999, 203) == 6600


# ============================ R11: не списывается дважды (гонка, PostgreSQL) ============================
@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL")
def test_concurrent_consume_only_one_order_gets_discount(user_factory, monkeypatch):
    """Под РЕАЛЬНОЙ гонкой на Postgres два параллельных заказа одного пассажира не могут оба
    забрать одну и ту же (одноразовую) скидку — именно ту гонку чинит row-lock + CAS в consume().

    Барьера перед вызовом мало: после него поток ещё должен реально ОКАЗАТЬСЯ внутри
    критического участка (после чтения брони, до её захвата) ОДНОВРЕМЕННО со вторым — а
    планировщик (особенно на Windows) может прогнать один поток целиком раньше, чем вообще
    переключится на другой. Поэтому дополнительно притормаживаем ОБА потока прямо перед
    захватом (единственная точка, которую вызывает consume() у обоих заказов) — это даёт
    гарантированное окно гонки независимо от того, как их распределит ОС."""
    original_cap_for_price = promo_ride.cap_for_price

    def delayed_cap_for_price(discount_kop, price_rub):
        time.sleep(0.2)
        return original_cap_for_price(discount_kop, price_rub)

    monkeypatch.setattr(promo_ride, "cap_for_price", delayed_cap_for_price)

    pax = user_factory("PgPromoRidePax")
    with Session(engine) as s:
        _give_discount(s, pax["id"], "PGRIDE1", 100)
        order_ids = []
        for _ in range(2):
            o = _order(passenger_id=pax["id"], price_estimate=500)
            s.add(o)
            s.commit()
            s.refresh(o)
            order_ids.append(o.id)

    barrier = threading.Barrier(2)
    got, lock = [], threading.Lock()

    def grab(order_id):
        with Session(engine) as s:
            s.execute(text("SET lock_timeout = '4s'"))
            s.execute(text("SET statement_timeout = '8s'"))
            o = s.get(InstantOrder, order_id)
            barrier.wait(timeout=10)
            value = promo_ride.consume(s, pax["id"], o)
        with lock:
            got.append(value)

    threads = [threading.Thread(target=grab, args=(oid,)) for oid in order_ids]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert sorted(got) == [0, 10000], got
    with Session(engine) as s:
        total = sum(int(s.get(InstantOrder, oid).promo_discount_kop or 0) for oid in order_ids)
    assert total == 10000, "в базе скидка должна быть ровно на одном заказе"


# ============================ R12: отмена возвращает скидку ============================
def test_release_returns_discount_on_cancel(user_factory):
    pax = user_factory("ОтменаПромоПас")
    with Session(engine) as s:
        _give_discount(s, pax["id"], "CANCELRIDE", 50)
        order = _order(passenger_id=pax["id"], price_estimate=500)
        s.add(order)
        s.commit()
        s.refresh(order)
        assert promo_ride.consume(s, pax["id"], order) == 5000

        order.status = S.cancelled
        s.add(order)
        s.commit()

        assert promo_ride.release(s, order) is True
        s.refresh(order)
        assert order.promo_discount_kop == 0

    with Session(engine) as s:
        red = s.exec(select(PromoRedemption).where(PromoRedemption.user_id == pax["id"])).first()
        assert red.used_order_id is None
        assert red.discount_kop == 5000, "скидка вернулась человеку, а не сгорела"


# ============================ R13: комиссия водителя не уходит в минус ============================
def test_split_commission_never_goes_negative():
    # Скидка (100 ₽) БОЛЬШЕ комиссии (6 ₽) — комиссия гасится до нуля, остаток не «долг водителя».
    fee_due, comp = promo_ride.split_commission(600, 10000)
    assert fee_due == 0
    assert comp == 10000 - 600
    assert fee_due >= 0 and comp >= 0

    # Скидка МЕНЬШЕ комиссии — комиссия просто уменьшается, доплаты водителю нет.
    fee_due2, comp2 = promo_ride.split_commission(10000, 600)
    assert fee_due2 == 10000 - 600
    assert comp2 == 0
