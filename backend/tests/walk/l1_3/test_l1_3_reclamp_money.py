"""leaf-1.3 — деньги: потолок скидки после пересчёта цены (B-2, найдено независимым ревью).

Правило: потолок «скидка не больше доли цены» и инвариант «водитель получает price − C» держатся
только в момент заказа. Цена заказа может УПАСТЬ ещё в четырёх местах (app/instant_service.py):
смена адреса (`apply_destination`), остановки (`apply_waypoints`), «искать и в соседнем классе»
(`add_fallback_category`), досрочное завершение (`finish_early`) — и раньше НИГДЕ из них скидку
не ужимали. `promo_ride.reclamp()` существовал, но его звала только активация
предзаказа — ни на один из этих четырёх путей, ни на сам `reclamp()` в репозитории не было ни
одного теста (`git grep reclamp backend/tests` был пуст).

Пример из ревью: заказ 600 ₽ → скидка 300 ₽. Пассажир меняет адрес → 150 ₽. Без починки к оплате
0 ₽, а водителю в кошелёк ушло бы ≈295 ₽ вместо положенных ≈70 ₽ — платформа переплачивает за счёт
одного и того же промокода, второй раз получить который нельзя, но переплата от этого не меньше.

R14 — `reclamp()` сам по себе: только уменьшает скидку, никогда не увеличивает, не трогает
пустую/нулевую скидку.
R15 — каждый из четырёх путей пересчёта цены действительно зовёт `reclamp()` и скидка на выходе
не превышает новый потолок доли.
"""
from datetime import timedelta

from sqlmodel import Session, select

from app import instant_service as isv
from app import promo_ride
from app.config import settings
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, PromoCode, PromoRedemption
from app.timeutil import utcnow

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


def _order_with_discount(session: Session, passenger_id: int, price_estimate: int,
                         discount_kop: int, **kw) -> InstantOrder:
    """Заказ, на котором УЖЕ списана скидка (как после настоящего `promo_ride.consume`)."""
    kw.setdefault("status", S.onboard)
    kw.setdefault("created_at", utcnow() - timedelta(hours=1))
    order = InstantOrder(passenger_id=passenger_id, from_lat=ORIG[0], from_lng=ORIG[1],
                         to_lat=DEST[0], to_lng=DEST[1], price_estimate=price_estimate,
                         promo_discount_kop=discount_kop, **kw)
    session.add(order)
    session.commit()
    session.refresh(order)
    return order


# ============================ R14: сам reclamp — только вниз ============================
def test_reclamp_shrinks_discount_when_price_drops(user_factory):
    pax = user_factory("ReclampДешевле")
    with Session(engine) as s:
        order = _order_with_discount(s, pax["id"], price_estimate=600, discount_kop=30000)
        order.price_estimate = 150   # цена упала в 4 раза (смена адреса и т.п.)
        s.add(order)
        s.commit()

        capped = promo_ride.reclamp(s, order)
        expected = int(150 * settings.promo_ride_max_price_share) * 100
        assert capped == expected
        s.refresh(order)
        assert order.promo_discount_kop == expected


def test_reclamp_never_increases_discount(user_factory):
    """Цена ВЫРОСЛА — потолок доли стал больше, но обещанную сумму задним числом не поднимаем."""
    pax = user_factory("ReclampДороже")
    with Session(engine) as s:
        order = _order_with_discount(s, pax["id"], price_estimate=100, discount_kop=5000)
        order.price_estimate = 600   # цена выросла — потолок доли вырос бы до 30000
        s.add(order)
        s.commit()

        capped = promo_ride.reclamp(s, order)
        assert capped == 5000, "reclamp не должен ПОДНИМАТЬ скидку выше уже обещанной"
        s.refresh(order)
        assert order.promo_discount_kop == 5000


def test_reclamp_noop_without_discount(user_factory):
    pax = user_factory("ReclampБезСкидки")
    with Session(engine) as s:
        order = _order_with_discount(s, pax["id"], price_estimate=600, discount_kop=0)
        assert promo_ride.reclamp(s, order) == 0
        s.refresh(order)
        assert order.promo_discount_kop == 0


# ============================ R15: каждый путь пересчёта зовёт reclamp ============================
def test_apply_destination_shrinks_discount_on_cheaper_address(user_factory):
    pax = user_factory("АдресДешевле")
    with Session(engine) as s:
        order = _order_with_discount(s, pax["id"], price_estimate=600, discount_kop=30000)
        quote = {"price": 150, "distance_km": 1.0, "eta_min": 5.0}
        updated = isv.apply_destination(s, order, (ORIG[0] + 0.01, ORIG[1] + 0.01), "Новый адрес", quote)
        expected = int(150 * settings.promo_ride_max_price_share) * 100
        assert updated.promo_discount_kop == expected, (
            "смена адреса подешевила поездку, а скидка осталась от старой, дорогой цены"
        )


def test_apply_waypoints_shrinks_discount_on_cheaper_route(user_factory):
    pax = user_factory("МаршрутДешевле")
    with Session(engine) as s:
        order = _order_with_discount(s, pax["id"], price_estimate=600, discount_kop=30000)
        quote = {"price": 150, "distance_km": 1.0, "eta_min": 5.0}
        updated = isv.apply_waypoints(s, order, [], quote)
        expected = int(150 * settings.promo_ride_max_price_share) * 100
        assert updated.promo_discount_kop == expected


def test_add_fallback_category_shrinks_discount_on_cheaper_class(monkeypatch, user_factory):
    monkeypatch.setattr(settings, "car_class_min_drivers", 0)
    pax = user_factory("КлассДешевле")
    with Session(engine) as s:
        order = _order_with_discount(s, pax["id"], price_estimate=600, discount_kop=30000,
                                     status=S.searching, category="comfort",
                                     distance_km=10.0, eta_min=15.0)
        alts = isv.fallback_options(s, order)
        assert alts and alts[0]["category"] == "standard" and alts[0]["price"] < 600, (
            "опора: соседний класс должен быть дешевле — иначе пример не показателен"
        )
        isv.add_fallback_category(s, order, "standard")
        s.refresh(order)
        expected = int(order.price_estimate * settings.promo_ride_max_price_share) * 100
        assert order.promo_discount_kop == min(30000, expected)
        assert order.promo_discount_kop < 30000, "цена упала, а скидка должна была уменьшиться вместе с ней"


def test_finish_early_shrinks_discount_on_actual_price(user_factory):
    pax = user_factory("ДосрочноДешевле")
    with Session(engine) as s:
        order = _order_with_discount(
            s, pax["id"], price_estimate=600, discount_kop=30000, status=S.onboard,
            onboard_at=utcnow() - timedelta(minutes=1), category="standard",
        )
        finished = isv.finish_early(s, order, "other")
        assert finished.status == S.done
        assert finished.price_final is not None and finished.price_final < 600, (
            "опора: цена по факту должна оказаться ниже обещанной — иначе пример не показателен"
        )
        expected = int(finished.price_final * settings.promo_ride_max_price_share) * 100
        assert finished.promo_discount_kop == min(30000, expected)
        assert finished.promo_discount_kop < 30000
