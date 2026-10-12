"""leaf-1.2 — F3 (независимое ревью Opus 5.5, 2026-10-02): у заказа не было защиты от суммы 0,
которая уже есть у брони (`_booking_amount_kop`).

`promo_ride.payable_kop` отдаёт 0, если скидка полностью покрывает цену (настройка
`promo_ride_max_price_share` допускает долю 1.0 — то есть скидка «вся цена»). Дальше это 0
уходило в `_pay_cashless`: на настоящей ЮKassa «0.00» получает отказ → счёт висит в pending
вечно; в mock-режиме заказ молча становится "оплачен" на 0 ₽, и это 0 попадает в ledger.earn.

Исправление — симметрично уже работающей защите у брони: 0 к оплате блокируется ДО выбора
способа (ни карта/СБП, ни «нал» — просто нечему быть «оплаченным»).
"""
from sqlmodel import Session

from app.db import engine
from app.models import InstantOrder, UserRole

from test_ledger import _make_done_order


def _zero_out_with_promo(order_id: int, price_rub: int) -> None:
    with Session(engine) as session:
        order = session.get(InstantOrder, order_id)
        order.promo_discount_kop = price_rub * 100   # скидка == вся цена → payable_kop == 0
        session.add(order)
        session.commit()


def test_card_payment_on_a_fully_discounted_order_is_rejected_not_sent_as_zero(client, user_factory):
    driver = user_factory("L12ZeroDriver", role=UserRole.driver)
    passenger = user_factory("L12ZeroPassenger")
    order_id = _make_done_order(driver["id"], passenger["id"], price_rub=300)
    _zero_out_with_promo(order_id, 300)

    response = client.post(f"/instant/orders/{order_id}/pay", headers=passenger["auth"], json={"method": "card"})

    assert response.status_code == 409, response.text
    with Session(engine) as session:
        assert session.get(InstantOrder, order_id).paid is False


def test_cash_payment_on_a_fully_discounted_order_is_also_rejected(client, user_factory):
    """Нал тоже блокируется — нечему быть "оплаченным из рук в руки", а paid=True на пустом
    месте замаскировало бы, что скидка покрыла всю цену."""
    driver = user_factory("L12ZeroCashDriver", role=UserRole.driver)
    passenger = user_factory("L12ZeroCashPassenger")
    order_id = _make_done_order(driver["id"], passenger["id"], price_rub=150)
    _zero_out_with_promo(order_id, 150)

    response = client.post(f"/instant/orders/{order_id}/pay", headers=passenger["auth"], json={"method": "cash"})

    assert response.status_code == 409, response.text
    with Session(engine) as session:
        assert session.get(InstantOrder, order_id).paid is False
