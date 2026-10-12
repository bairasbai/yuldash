"""leaf-1.1 · backend/app/compensation.py — единственный источник правды о компенсациях.

Модуль существует, чтобы чек (цена для пассажира), комиссия платформы (debt.py) и потолок
промокода (promo_ride.py) считали компенсацию водителю ОДИНАКОВО — одной функцией, а не тремя
копиями, которые когда-то разъехались (см. docstring compensation.py). Значит у самой функции
не может быть ни одной лазейки: любая её ошибка мгновенно бьёт по ТРЁМ разным местам.

R1 — None-заказ → 0 (ничего не компенсируем несуществующему заказу).
R2 — сумма = честная сумма трёх полей, копейки, без смешения с другими полями заказа.
R3 — одно подставное/испорченное поле не может быть ОТРИЦАТЕЛЬНЫМ и утащить сумму ниже нуля:
     иначе цена пассажира (compensation прибавляется к цене) становится МЕНЬШЕ, комиссия
     платформы (цена МИНУС компенсация) — БОЛЬШЕ, а потолок промокода — шире. Три денежных
     бага одной отрицательной копейкой в одном поле.
R4 — рубли считаются полом (// 100), а не округлением и не домножением на 10.
"""
from types import SimpleNamespace

from app import compensation


def test_r1_none_order_is_zero():
    assert compensation.compensation_kop(None) == 0
    assert compensation.compensation_rub(None) == 0


def test_r2_sums_exactly_the_three_fields_in_kopecks():
    order = SimpleNamespace(pickup_fee_kop=150, options_fee_kop=300, weather_fee_kop=50,
                            price_final=99999)   # постороннее денежное поле — не должно попасть в сумму
    assert compensation.compensation_kop(order) == 500


def test_r2_missing_fields_default_to_zero_not_crash():
    # Старый объект / частично заполненная запись — геттер по умолчанию 0, не исключение.
    assert compensation.compensation_kop(SimpleNamespace()) == 0


def test_r3_a_single_negative_field_cannot_drag_the_total_below_zero():
    """Подтверждённая ошибка (E1): испорченное/отрицательное поле не должно ОТНИМАТЬ от суммы.

    До исправления `sum(int(getattr(order, f, 0) or 0) for f in COMPENSATION_FIELDS)` спокойно
    складывал отрицательное число — testing weather_fee_kop=-500 при pickup_fee_kop=200 давало
    compensation_kop == -300, что по цепочке: 1) УМЕНЬШАЕТ цену пассажира в instant_service.py
    (компенсация туда ПРИБАВЛЯЕТСЯ); 2) УВЕЛИЧИВАЕТ базу комиссии в debt.order_commission_kop
    (база = цена МИНУС компенсация); 3) РАСШИРЯЕТ потолок скидки в promo_ride.py. Один
    испорченный столбец бьёт по трём денежным местам разом — ровно то, от чего модуль должен
    защищать по своему же докстрингу.
    """
    испорченный = SimpleNamespace(pickup_fee_kop=200, options_fee_kop=0, weather_fee_kop=-500)
    assert compensation.compensation_kop(испорченный) == 200, (
        "отрицательное поле компенсации утянуло сумму ниже честных 200 коп — "
        "цена/комиссия/потолок промокода посчитают по заниженной или отрицательной компенсации"
    )
    assert compensation.compensation_rub(испорченный) == 2


def test_r4_rubles_floor_not_round_or_rescale():
    order = SimpleNamespace(pickup_fee_kop=149, options_fee_kop=0, weather_fee_kop=0)  # 1.49 ₽
    assert compensation.compensation_rub(order) == 1          # пол, не округление до 2


def test_r4_exact_hundred_kopecks_is_one_ruble_no_drift():
    order = SimpleNamespace(pickup_fee_kop=100, options_fee_kop=0, weather_fee_kop=0)
    assert compensation.compensation_rub(order) == 1
