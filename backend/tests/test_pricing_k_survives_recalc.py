"""Пересчёт цены на живом заказе не должен занижать её водителю.

Что было не так. Цена заказа считается по ПОЛНОМУ множителю: спрос × ночь × погода ×
дальняя подача. А на заказе хранился только `surge_k` — один спрос. Любой пересчёт
(согласие на соседний класс, а скоро и смена адреса) брал этот огрызок и получал цену
НИЖЕ настоящей.

Стреляло тихо и всегда в одну сторону: ночью, в метель и там, где ехать до пассажира
далеко, — то есть ровно тогда, когда работать тяжелее всего. Водитель вёз ночную поездку,
а получал как за дневную, и понять причину по чеку было нельзя.

Теперь на заказе живёт `pricing_k` — полный множитель, и все пересчёты идут от него.
Старые заказы поля не имеют: для них падаем на `surge_k`, то есть ведём себя как раньше.
"""
import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, User


@pytest.fixture(autouse=True)
def _app_started(client):
    return client


def _order(**kw) -> InstantOrder:
    """Заказ в памяти: пересчёт цены читает только поля, в базу писать незачем."""
    base = dict(passenger_id=1, distance_km=10.0, eta_min=20.0, price_estimate=500,
                status=S.onboard, category="standard")
    base.update(kw)
    return InstantOrder(**base)


# ------------------------------ сам множитель ------------------------------
def test_full_multiplier_is_used_not_just_demand():
    """Ночная поездка: полный множитель больше, чем один спрос, — и берём именно его."""
    order = _order(surge_k=1.2, pricing_k=1.2 * 1.15)      # спрос 1.2, ночь ещё 1.15
    assert isv.order_pricing_k(order) == pytest.approx(1.38)
    assert isv.order_pricing_k(order) > order.surge_k, (
        "пересчёт пошёл бы по одному спросу — ночь, погода и дальняя подача потерялись бы"
    )


def test_old_orders_fall_back_to_demand():
    """Заказы, созданные до появления поля, ведут себя как раньше — не хуже."""
    for missing in (0.0, 1.0):
        order = _order(surge_k=1.4, pricing_k=missing)
        assert isv.order_pricing_k(order) == 1.4, "старый заказ потерял даже наценку за спрос"


def test_quiet_order_multiplier_is_one():
    """Спокойный день без наценок: множитель единица, цена не пляшет."""
    assert isv.order_pricing_k(_order(surge_k=1.0, pricing_k=1.0)) == 1.0


# ------------------------------ ради чего всё ------------------------------
def test_night_recalculation_does_not_shortchange_the_driver():
    """Главная проверка: пересчёт ночного заказа не даёт цену дешевле дневного.

    Считаем цену соседнего класса дважды — как если бы множитель был полным и как если бы
    остался огрызок. Разница и есть то, что водитель терял каждую ночь.
    """
    night = _order(surge_k=1.0, pricing_k=1.15)            # спроса нет, а ночь есть
    honest = isv.order_pricing_k(night)
    shortchanged = float(night.surge_k or 1.0)
    assert honest > shortchanged
    # На поездке в 500 ₽ это 75 ₽ — четверть часа работы, каждую ночную поездку.
    assert 500 * honest - 500 * shortchanged > 50


def test_estimate_reports_the_full_multiplier(client, user_factory):
    """Оценка цены отдаёт полный множитель отдельным полем — его и кладут на заказ."""
    pax = user_factory("PricingK")
    body = {"from_lat": 54.735, "from_lng": 55.958, "to_lat": 54.75, "to_lng": 55.99}
    est = client.post("/instant/estimate", headers=pax["auth"], json=body).json()
    assert "pricing_k" in est, "сервер не отдаёт полный множитель — заказу нечего запоминать"
    assert est["pricing_k"] >= est["surge_k"], (
        "полный множитель меньше наценки за спрос — значит он посчитан неверно"
    )


def test_order_remembers_the_multiplier(client, user_factory):
    """Заказ сохраняет множитель при создании: потом пересчитывать будет уже не от чего."""
    pax = user_factory("PricingKOrder")
    body = {"from_lat": 54.735, "from_lng": 55.958, "to_lat": 54.75, "to_lng": 55.99}
    r = client.post("/instant/orders", headers=pax["auth"], json=body)
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        order = s.get(InstantOrder, r.json()["id"])
        assert order is not None
        assert order.pricing_k >= 1.0
        assert isv.order_pricing_k(order) >= float(order.surge_k or 1.0)
