"""Зимняя дорога — строка счёта, а не множитель (решение Q7 от 2026-08-23).

Что защищаем:
- ГЛАВНОЕ: старый погодный множитель выключен. Строка и множитель вместе — это двойная
  оплата одной метели;
- формулу: рубли за километр, потолок долей от поездки, только тяжёлая погода;
- что туман, ветер и гроза денег НЕ добавляют: они опасны, мы о них предупреждаем,
  но бензина от них больше не жжётся;
- что расчёт цены НЕ ходит в интернет за погодой (иначе человек смотрит на спиннер);
- что «не знаем погоду» = «не берём денег».
"""
import pytest

from app import instant_service as isv
from app import pricing
from app import weather_warn
from app.config import settings


# ============================ Сторож против двойной оплаты ============================
def test_old_weather_multiplier_is_off_so_we_never_charge_twice():
    """СТОРОЖ. Погода теперь строка счёта. Если кто-то вернёт множитель, не убрав строку,
    мы возьмём за одну метель дважды — и обе цифры по отдельности будут выглядеть разумно.

    Заодно фиксируем причину, по которой множитель и не работал: он питался от Яндекс.Погоды,
    а ключа к ней у нас нет."""
    assert settings.taxi_weather_max_k == 1.0
    assert not (settings.yandex_weather_key or "").strip(), \
        "появился ключ Яндекс.Погоды — проверь, не ожил ли погодный множитель"
    fact = {"condition": "snow", "wind_gust": 25.0, "temp": -30}
    assert pricing._weather_metrics_from_fact(fact).k == 1.0


# ============================ Формула ============================
@pytest.mark.parametrize("kind", ["ice", "blizzard", "snow", "frost"])
def test_hard_weather_costs_rubles_per_km(kind):
    """1,5 ₽ за километр тяжёлой дороги. Город 10 км → 15 ₽, трасса 420 км → упрёмся в потолок."""
    assert isv.winter_road_fee_rub(10.0, 260, kind) == 20    # 15 ₽ → округление до 10
    # Межгород: 420 × 1,5 = 630 ₽, потолок 15% от 10 850 ₽ = 1 627 — не режет.
    assert isv.winter_road_fee_rub(420.0, 10850, kind) == 630


def test_fog_wind_and_thunder_do_not_cost_money():
    """Они опасны, и мы о них предупреждаем. Но расход от них не растёт — значит и денег нет."""
    for kind in ("fog", "wind", "thunder", ""):
        assert isv.winter_road_fee_rub(100.0, 1000, kind) == 0


def test_cap_keeps_the_line_smaller_than_the_ride():
    """На коротком дешёвом заказе строка не может выглядеть больше самой поездки."""
    # 100 км по городскому тарифу — редкость, но потолок обязан держать: 15% от 200 ₽ = 30 ₽.
    assert isv.winter_road_fee_rub(100.0, 200, "ice") == 30


def test_disabled_rate_means_no_line(monkeypatch):
    """Ставка 0 в конфиге — строки нет вообще (рычаг выключения без пересборки)."""
    monkeypatch.setattr(settings, "winter_road_rub_per_km", 0.0)
    assert isv.winter_road_fee_rub(420.0, 10850, "blizzard") == 0


def test_note_is_bilingual_and_names_the_weather():
    """«Гололёд +45 ₽» объясняет, «погода +45 ₽» — нет."""
    note = isv.winter_road_note("ice", 45)
    assert note and "Гололёд" in note["ru"] and note["ba"]
    assert "45" in note["ru"]
    assert isv.winter_road_note("ice", 0) is None      # нечего сказать — молчим


# ============================ Источник погоды ============================
def test_pricing_never_goes_online_for_weather(monkeypatch):
    """Цена пересчитывается на каждое движение пальца по карте. Поход наружу с таймаутом
    в шесть секунд на этом пути означал бы спиннер вместо цены."""
    called = {"n": 0}

    def _boom(*args, **kwargs):
        called["n"] += 1
        raise AssertionError("расчёт цены полез в интернет за погодой")

    monkeypatch.setattr(weather_warn, "_fetch", _boom)
    # Кэш пуст → «не знаю» → строки нет. И ни одного похода наружу.
    assert isv.winter_road_kind(None, (52.591, 58.317), (52.716, 58.664)) == ""
    assert called["n"] == 0


def test_unknown_weather_means_no_charge(monkeypatch):
    """«Не знаем» не может значить «берём деньги»: сомнение здесь стоит пассажиру."""
    monkeypatch.setattr(weather_warn, "route_weather",
                        lambda *a, **kw: weather_warn.RouteWeather(available=False))
    assert isv.winter_road_kind(None, (52.591, 58.317), (52.716, 58.664)) == ""


def test_worst_weather_on_the_route_wins(monkeypatch):
    """На выезде чисто, под Сибаем метель — платим за метель, а не за «ясно»."""
    route = weather_warn.RouteWeather(available=True, warnings=[
        weather_warn.Warning("fog", "туман", "томан"),
        weather_warn.Warning("blizzard", "метель", "буран", severe=True),
    ])
    monkeypatch.setattr(weather_warn, "route_weather", lambda *a, **kw: route)
    # Туман денег не стоит и в очереди стоит первым — но выбирается платная метель.
    assert isv.winter_road_kind(None, (52.591, 58.317), (52.716, 58.664)) == "blizzard"


# ============================ Спрос: далеко ли зона ============================
def test_demand_zone_carries_distance_from_the_driver(monkeypatch):
    """Водителю нужно знать не «Зона 2», а «зона в ≈4 км» — он решает, ехать ли туда.

    Расстояние считает СЕРВЕР по живой позиции водителя (presence): телефон и так шлёт
    координаты, пока он на линии, и просить у него геолокацию второй раз ради одной
    подписи незачем. Наружу уходит только расстояние, не его точка."""
    from app import instant_service as isv2

    zone = (52.60, 58.32)
    monkeypatch.setattr(isv2, "_active_search_points", lambda session: [zone])
    monkeypatch.setattr(isv2, "driver_position", lambda did: (52.591, 58.317))
    from app import taxi as taxi_mod
    monkeypatch.setattr(taxi_mod, "availability", lambda *a, **kw: {"enabled": True})

    out = isv2.demand_zones(None, driver_id=7)
    assert out["zones"], "зона должна попасть в ответ"
    z = out["zones"][0]
    assert 0.5 <= z["dist_km"] <= 3.0, z
    # Личности и точки водителя в ответе нет — только агрегат зоны.
    assert set(z) == {"lat", "lng", "weight", "requests", "dist_km"}


def test_demand_without_driver_position_has_no_invented_distance(monkeypatch):
    """Позиции нет (только вышел на линию, нет Redis) → поля нет вовсе.
    «Зона 1» честнее выдуманных километров."""
    from app import instant_service as isv2

    monkeypatch.setattr(isv2, "_active_search_points", lambda session: [(52.60, 58.32)])
    monkeypatch.setattr(isv2, "driver_position", lambda did: None)
    from app import taxi as taxi_mod
    monkeypatch.setattr(taxi_mod, "availability", lambda *a, **kw: {"enabled": True})

    z = isv2.demand_zones(None, driver_id=7)["zones"][0]
    assert "dist_km" not in z
