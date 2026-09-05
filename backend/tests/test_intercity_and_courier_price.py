"""Межгород и цена курьера (аудит 2026-08-28).

Две области, где счёт считался «как получилось»:

  • **Межгород такси** — линейка: 24 ₽ за километр и на 50 км, и на 350. Реальные
    междугородние так не работают. Механизм «сходящегося километра» написан и ВЫКЛЮЧЕН —
    цифры не сверены с рынком, включит Александр. Тесты держат обе стороны: выключенный
    механизм ничего не меняет, включённый считает ровно так, как обещано.
  • **Курьер** — длина по линейке вместо дорог, зона принималась и игнорировалась,
    тариф был зашит в код, а пол комиссии 25 ₽ отменял обещанную курьеру лесенку.
"""
import pytest

from app import instant_service as isv
from app.config import settings
from app.routers import courier as cr

UFA = (54.735, 55.958)
STERLITAMAK = (53.630, 55.950)          # ~120 км — межгород


# ============================ 1. Сходящийся километр ============================
def test_taper_is_off_by_default():
    """По умолчанию выключен: цена считается линейно, как до 2026-08-28."""
    assert settings.intercity_taper_from_km == 0.0 or settings.intercity_taper_percent == 0.0
    assert isv.billable_km(350.0) == 350.0


def test_taper_leaves_short_trips_alone(monkeypatch):
    """Городская поездка не трогается: порог выше границы зон."""
    monkeypatch.setattr(settings, "intercity_taper_from_km", 50.0)
    monkeypatch.setattr(settings, "intercity_taper_percent", 40.0)
    assert isv.billable_km(12.0) == 12.0
    assert isv.billable_km(50.0) == 50.0


def test_taper_discounts_only_the_kilometres_past_the_edge(monkeypatch):
    """Первые километры по полной ставке, дальше дешевле — а не вся поездка со скидкой."""
    monkeypatch.setattr(settings, "intercity_taper_from_km", 50.0)
    monkeypatch.setattr(settings, "intercity_taper_percent", 40.0)
    # 350 км = 50 полных + 300 по 60% → 50 + 180 = 230 оплачиваемых
    assert isv.billable_km(350.0) == pytest.approx(230.0)


def test_taper_never_makes_a_kilometre_free(monkeypatch):
    """Скидку больше 90% не даём: на тысяче километров бензин всё равно жгут."""
    monkeypatch.setattr(settings, "intercity_taper_from_km", 50.0)
    monkeypatch.setattr(settings, "intercity_taper_percent", 300.0)
    assert isv.billable_km(1000.0) > 50.0


def test_longer_trip_never_costs_less(monkeypatch):
    """Главный инвариант: длиннее не может быть дешевле — иначе счёт выглядит обманом."""
    monkeypatch.setattr(settings, "intercity_taper_from_km", 50.0)
    monkeypatch.setattr(settings, "intercity_taper_percent", 40.0)
    prev = 0.0
    for km in (40, 50, 60, 100, 200, 350, 500):
        cur = isv.billable_km(float(km))
        assert cur >= prev, f"на {km} км оплачиваемая длина упала"
        prev = cur


def test_trip_cap_matches_the_shift_limit():
    """Потолок поездки не должен разрешать то, что запрещает лимит смены (8 часов)."""
    assert settings.max_trip_km <= 500.0, (
        "1 000 км — это сутки за рулём, а смена ограничена 8 часами: два наших правила "
        "противоречили друг другу"
    )


# ============================ 2. Цена курьера ============================
def test_courier_intercity_kilometre_is_cheaper_than_city():
    """Дальний перегон не считается по городскому километру.

    Зона принималась от клиента и на цену не влияла вообще: доставка за 120 км считалась
    так же, как через квартал.
    """
    assert settings.courier_per_km_intercity_kop < settings.courier_per_km_kop
    city = cr._price(UFA[0], UFA[1], UFA[0] + 0.05, UFA[1], "small", "bypath")
    far = cr._price(UFA[0], UFA[1], STERLITAMAK[0], STERLITAMAK[1], "small", "bypath")
    assert far["zone"] == "intercity" and city["zone"] == "city"
    # Километр на межгороде дешевле — сравниваем именно ставку, а не итог.
    assert far["breakdown"]["per_km_kop"] == settings.courier_per_km_intercity_kop
    assert city["breakdown"]["per_km_kop"] == settings.courier_per_km_kop


def test_courier_distance_comes_from_the_road_not_a_ruler():
    """Длину берём тем же расчётом, что и такси, а не прямой линией по карте."""
    from app import pricing
    got = cr._price(UFA[0], UFA[1], STERLITAMAK[0], STERLITAMAK[1], "small", "bypath")
    road = pricing.route_metrics(UFA, STERLITAMAK)
    assert got["distance_km"] == pytest.approx(round(road.distance_km, 2), abs=0.05)


def test_courier_same_point_costs_only_the_base():
    """Забрать и отдать в одном месте — только подача, без выдуманных полкилометра."""
    d = cr._price(UFA[0], UFA[1], UFA[0], UFA[1], "small", "bypath")
    assert d["distance_km"] == 0.0
    assert d["price_kop"] == settings.courier_base_kop


def test_courier_ladder_is_not_eaten_by_the_floor():
    """Пол комиссии больше не отменяет обещанную новичку ставку 3%.

    Было: пол 25 ₽ съедал лесенку на любой реальной доставке — курьеру обещали 3%,
    а брали 12–17%. Ставка 3% включалась только с доставок дороже 833 ₽.
    """
    price = 50000        # доставка 500 ₽
    got = cr.courier_commission_kop(price, settings.courier_fee_tier1_percent)
    assert got == round(price * settings.courier_fee_tier1_percent / 100), (
        "на доставке 500 ₽ новичок снова платит пол вместо обещанного процента"
    )
    # Точка, с которой обещание начинает работать. С полом 25 ₽ она была 833 ₽ — то есть
    # никогда. С полом 10 ₽ это ~333 ₽, и это честно написано в долгах (docs/tasks.md).
    порог = settings.courier_commission_min_kop * 100 / settings.courier_fee_tier1_percent
    assert порог <= 40000, f"лесенка включается только с доставок дороже {порог / 100:.0f} ₽"


def test_courier_floor_still_covers_our_cost():
    """Пол остаётся — он покрывает наш расход на карты (~2,7 ₽ на заказ)."""
    assert settings.courier_commission_min_kop >= 500
    assert cr.courier_commission_kop(10000, 3.0) == settings.courier_commission_min_kop


def test_courier_tariff_is_configurable_not_hardcoded(monkeypatch):
    """Цену доставки можно поправить настройкой, без пересборки приложения."""
    before = cr._price(UFA[0], UFA[1], UFA[0] + 0.05, UFA[1], "small", "bypath")["price_kop"]
    monkeypatch.setattr(settings, "courier_base_kop", settings.courier_base_kop + 5000)
    after = cr._price(UFA[0], UFA[1], UFA[0] + 0.05, UFA[1], "small", "bypath")["price_kop"]
    assert after - before == 5000
