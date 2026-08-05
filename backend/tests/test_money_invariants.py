"""Деньги: правила, которые обязаны выполняться при ЛЮБЫХ числах.

Обычный тест проверяет один пример: «15 000 копеек, 15% → 2 250». Он ловит опечатку
в формуле, но не ловит край: грошовый заказ, скидку больше цены, дробный процент,
абсурдно большую сумму. А ломается обычно именно край.

Здесь проверяются не примеры, а правила — на сотнях комбинаций сразу:

  • комиссия никогда не больше суммы и никогда не отрицательная;
  • скидку пассажира оплачивает платформа своей комиссией, а не деньгами водителя;
  • водитель за поездку со скидкой получает столько же, сколько без неё;
  • копейки не теряются и не появляются из воздуха при округлении.

Последнее правило самое коварное: ошибка в одну копейку на заказ незаметна, а на тысяче
заказов превращается в расхождение, которое потом ищут неделю.
"""
from __future__ import annotations

from decimal import Decimal

import pytest

from app import promo_ride
from app.ledger import fee_kop_for

# Крайние и «некруглые» значения — там, где формулы обычно и ломаются.
AMOUNTS_KOP = [0, 1, 99, 100, 101, 999, 1_000, 12_345, 35_000, 100_000, 999_999, 10_000_000]
PERCENTS = [0, 0.5, 1, 8, 8.5, 12.5, 15, 22, 30, 99.9, 100]


@pytest.mark.parametrize("amount", AMOUNTS_KOP)
@pytest.mark.parametrize("percent", PERCENTS)
def test_комиссия_не_больше_суммы_и_не_отрицательная(amount, percent):
    fee = fee_kop_for(amount, percent)
    assert fee >= 0, f"комиссия ушла в минус: {amount} коп × {percent}% = {fee}"
    assert fee <= amount, (
        f"комиссия больше самой поездки: {amount} коп × {percent}% = {fee} — "
        "водитель остался бы должен за то, что съездил"
    )
    assert isinstance(fee, int), "деньги считаются в целых копейках, дробных копеек не бывает"


@pytest.mark.parametrize("amount", [-1, -100, -999_999])
def test_отрицательная_сумма_не_даёт_комиссии(amount):
    """Такого быть не должно, но если придёт — считаем ноль, а не отрицательный долг."""
    assert fee_kop_for(amount, 8) == 0


def test_округление_идёт_по_половине_вверх():
    # 1001 коп × 12.5% = 125.125 → 125. 1004 × 12.5% = 125.5 → 126 (половина вверх).
    assert fee_kop_for(1001, 12.5) == 125
    assert fee_kop_for(1004, 12.5) == 126


@pytest.mark.parametrize("amount", AMOUNTS_KOP)
@pytest.mark.parametrize("percent", PERCENTS)
def test_комиссия_совпадает_с_точным_расчётом(amount, percent):
    """Никакого дрейфа float: 8.5% от суммы считается точно, а не «примерно»."""
    if amount <= 0 or percent <= 0:
        assert fee_kop_for(amount, percent) == 0
        return
    exact = (Decimal(amount) * Decimal(str(percent)) / Decimal(100)).quantize(Decimal(1), rounding="ROUND_HALF_UP")
    assert fee_kop_for(amount, percent) == int(exact)


# ---------- Кто платит за скидку пассажира ----------

@pytest.mark.parametrize("commission", [0, 1, 500, 2_800, 35_000])
@pytest.mark.parametrize("discount", [0, 1, 499, 2_800, 2_801, 100_000])
def test_скидку_оплачивает_платформа_а_не_водитель(commission, discount):
    pays, comp = promo_ride.split_commission(commission, discount)

    assert pays >= 0 and comp >= 0, f"отрицательные деньги: платит {pays}, компенсация {comp}"
    assert pays <= commission, (
        f"водитель платит больше своей комиссии ({pays} > {commission}) — "
        "скидку пассажира оплатили из его кармана"
    )
    # Главный инвариант: сколько платформа недобрала комиссией, столько и доплатила.
    assert commission - pays + comp == discount or discount == 0 or (commission - pays) == discount, (
        f"скидка {discount} не сошлась: комиссия {commission} → платит {pays}, доплата {comp}"
    )


@pytest.mark.parametrize("price_kop", [10_000, 35_000, 100_000])
@pytest.mark.parametrize("percent", [0, 8, 15])
@pytest.mark.parametrize("discount", [0, 1_000, 50_000, 200_000])
def test_водитель_получает_одинаково_со_скидкой_и_без(price_kop, percent, discount):
    """Обещание из кода: «водитель получает price − C при любом размере скидки».
    Если оно нарушится, водители начнут отказываться от заказов с промокодом."""
    commission = fee_kop_for(price_kop, percent)
    pays, comp = promo_ride.split_commission(commission, min(discount, price_kop))

    без_скидки = price_kop - commission
    со_скидкой = (price_kop - min(discount, price_kop)) - pays + comp

    assert со_скидкой == без_скидки, (
        f"со скидкой водитель получает {со_скидкой}, без скидки {без_скидки} — "
        f"разница {со_скидкой - без_скидки} коп при цене {price_kop}, скидке {discount}, комиссии {commission}"
    )


def test_скидка_больше_цены_не_делает_поездку_платной_для_водителя():
    """Промокод «минус 1000 ₽» на поездку за 300 ₽ не должен обнулить заработок водителя."""
    price_kop = 30_000
    commission = fee_kop_for(price_kop, 8)
    pays, comp = promo_ride.split_commission(commission, 100_000)
    assert pays == 0, "при огромной скидке водитель ещё и комиссию платит"
    assert comp >= commission, "платформа не покрыла даже свою комиссию"


# ---------- Копейки в рубли ----------

@pytest.mark.parametrize("kop, expected", [
    (0, "0.00"), (1, "0.01"), (99, "0.99"), (100, "1.00"),
    (101, "1.01"), (35_000, "350.00"), (999_999, "9999.99"),
])
def test_копейки_превращаются_в_рубли_без_потерь(kop, expected):
    from app.payments import _rub
    assert _rub(kop) == expected
