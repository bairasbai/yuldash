"""Круговой рейс: обратная дорога со скидкой (решение Александра, 2026-08-21).

Зачем. Дальняя поездка ломается о простую вещь: водитель везёт человека 450 км и столько же
едет обратно ПУСТЫМ. Это его день и его бензин, а платят за половину пути. При тарифе 24 ₽/км
он выходит примерно в ноль — то есть работает ради того, чтобы не потерять.

Поднять цену всем — значит наказать и тех, у кого обратный заказ есть. Рынок решил иначе:
межгородные службы Уфы дают до 30% скидки, если машину загружают в обе стороны
(taxi24online.ru, замер 21.08.2026). Пассажиру дешевле, водителю — гарантированный второй
конец без поиска клиента, платформе — вдвое больший чек.

Здесь проверяется, что скидка считается правильно, живёт только там, где есть порожняк,
и что водителю от неё действительно лучше, чем от пустого возврата.
"""
import pytest
from sqlmodel import Session, select

from app import instant_service as isv
from app.config import settings
from app.db import engine
from app.models import Tariff


@pytest.fixture(autouse=True)
def _app_started(client):
    """Схему и стартовые тарифы создаёт подъём приложения — как в проде."""
    return client


# ------------------------------ сколько стоит ------------------------------
def test_return_leg_is_discounted_not_free():
    """Туда полная цена, обратно — со скидкой. Не бесплатно и не за полную."""
    one_way = 10_000
    both = isv.round_trip_price(one_way, "intercity")
    expected_back = one_way * (1 - settings.round_trip_discount_percent / 100)
    assert both == isv.round_to_10(one_way + expected_back)
    assert both > one_way, "обратная дорога не может быть бесплатной — это те же километры"
    assert both < one_way * 2, "скидки нет — тогда и предлагать нечего"


def test_discount_size_comes_from_settings(monkeypatch):
    """Размер скидки — настройка, а не число в коде. Ноль выключает предложение целиком."""
    monkeypatch.setattr(settings, "round_trip_discount_percent", 50.0)
    assert isv.round_trip_price(10_000, "intercity") == 15_000

    monkeypatch.setattr(settings, "round_trip_discount_percent", 0.0)
    assert isv.round_trip_available("intercity") is False
    assert isv.round_trip_price(10_000, "intercity") == 10_000


# ------------------------------ только там, где есть порожняк ------------------------------
def test_city_rides_get_no_round_trip_offer():
    """В городе водитель находит следующий заказ за минуты — порожняка нет.

    Скидка там была бы просто подарком: мы бы отдали 30% за проблему, которой не существует.
    """
    assert isv.round_trip_available("city") is False
    assert isv.round_trip_price(500, "city") == 500


def test_intercity_is_where_the_offer_lives():
    assert isv.round_trip_available("intercity") is True


# ------------------------------ ради чего всё затевалось ------------------------------
# Уфа — Сибай: 450 км, 5 ч 44 мин. Расходы водителя (август 2026): бензин 65 ₽/л при
# расходе 7 л/100 км плюс износ ≈ 3,5 ₽/км.
KM, MIN = 450, 344
COST_PER_KM = 7.0 / 100 * 65 + 3.5
NET = 1 - 0.15 - 0.04          # комиссия 15% + налог самозанятого 4%


def _one_way_price() -> int:
    with Session(engine) as s:
        t = s.exec(select(Tariff).where(
            Tariff.zone == "intercity", Tariff.category == "standard")).first()
    return isv.round_to_10(t.base + t.per_km * KM + t.per_min * MIN)


def test_round_trip_pays_the_driver_far_better_than_an_empty_return():
    """Главное: круговой рейс должен быть заметно выгоднее водителю, иначе он бессмыслен.

    Пустой возврат: 900 км, деньги за 450. Круговой: те же 900 км, деньги за 450 + 315
    (обратные со скидкой 30%). Расходы одинаковые — разница вся идёт водителю.
    """
    one_way = _one_way_price()
    both = isv.round_trip_price(one_way, "intercity")
    costs = KM * 2 * COST_PER_KM                       # бензин и износ те же в обоих случаях

    empty_return = one_way * NET - costs
    round_trip = both * NET - costs

    assert round_trip > empty_return * 2, (
        f"круговой рейс даёт водителю {round_trip:.0f} ₽ против {empty_return:.0f} ₽ "
        f"с пустым возвратом — разница не стоит того, чтобы ради неё что-то менять"
    )


def test_passenger_pays_less_per_kilometre_on_a_round_trip():
    """И пассажиру должно быть выгодно — иначе он просто закажет две поездки отдельно."""
    one_way = _one_way_price()
    both = isv.round_trip_price(one_way, "intercity")
    assert both < one_way * 2, "два отдельных заказа выходят дешевле — предложение бессмысленно"
    saving = one_way * 2 - both
    assert saving > 1000, f"экономия всего {saving:.0f} ₽ — человек её не заметит"


def test_waiting_time_has_a_limit():
    """Ожидание не бесконечно: смена водителя не резиновая."""
    assert 0 < settings.round_trip_max_wait_hours <= 12
