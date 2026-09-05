"""Заморозка цены: показали сумму — по ней и повезём, пока человек думает.

Экран показывал 450 ₽, человек смотрел на карту, советовался, нажимал «Заказать» через
полминуты — и сервер считал цену заново. Подрос спрос, пошёл снег, уехала ближайшая машина —
человек платил 495 ₽, ни о чём не предупреждённый.

Правило (решение Александра, 2026-08-28): заморозка работает ТОЛЬКО в пользу человека.
Подорожало — платит старую цену. Подешевело — новую. Проверяем обе стороны, потому что
вторая — та, ради которой это вообще не похоже на агрегатор.
"""
import fakeredis
import pytest

from app import instant_service as isv
from app import price_freeze
from app.config import settings

from test_instant import _order_body, fake_redis  # noqa: F401 — фикстура реэкспортом

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


@pytest.fixture
def rds():
    return fakeredis.FakeStrictRedis(decode_responses=True)


def _est(price: int) -> dict:
    return {"price": price, "ride_price": price, "base_price": price,
            "pickup_fee": 0, "options_fee": 0, "weather_fee": 0, "weather_kind": "",
            "distance_km": 12.0, "eta_min": 18.0, "tariff_id": 1,
            "surge_k": 1.0, "pricing_k": 1.0, "round_trip": False}


def _remember(rds, est):
    return price_freeze.remember(rds, 1, ORIG, DEST, "standard", "", False, est)


def _apply(rds, est):
    return price_freeze.apply(rds, 1, ORIG, DEST, "standard", "", False, est)


# ============================ 1. Обе стороны правила ============================
def test_price_that_grew_is_held(rds):
    """Цена выросла, пока человек думал — берём ту, что он видел."""
    _remember(rds, _est(450))
    out = _apply(rds, _est(495))
    assert out["price"] == 450 and out.get("price_was_frozen") is True


def test_price_that_fell_is_taken_fresh(rds):
    """Цена упала — берём новую. Заморозка не работает против человека."""
    _remember(rds, _est(450))
    out = _apply(rds, _est(400))
    assert out["price"] == 400 and "price_was_frozen" not in out


def test_the_whole_breakdown_is_frozen_together(rds):
    """Замораживается весь расчёт, а не одна сумма.

    Иначе в чеке окажется старый итог и новая расшифровка — строки не сложатся в сумму,
    и человек справедливо решит, что его обсчитали.
    """
    old = _est(450)
    old.update(pickup_fee=200, ride_price=250, weather_fee=0)
    _remember(rds, old)
    new = _est(600)
    new.update(pickup_fee=350, ride_price=250)
    out = _apply(rds, new)
    assert (out["price"], out["pickup_fee"], out["ride_price"]) == (450, 200, 250)


# ============================ 2. Границы заморозки ============================
def test_another_class_is_another_price(rds):
    """Смотрел Эконом — заказал Бизнес: цена Эконома не переносится."""
    _remember(rds, _est(450))
    out = price_freeze.apply(rds, 1, ORIG, DEST, "business", "", False, _est(900))
    assert out["price"] == 900


def test_another_person_does_not_inherit_the_price(rds):
    """Заморозка привязана к человеку, а не к маршруту вообще."""
    _remember(rds, _est(450))
    out = price_freeze.apply(rds, 2, ORIG, DEST, "standard", "", False, _est(495))
    assert out["price"] == 495


def test_child_seat_is_not_smuggled_in_for_free(rds):
    """Смотрел цену без кресла, заказал с креслом — старая цена не подходит."""
    _remember(rds, _est(450))
    out = price_freeze.apply(rds, 1, ORIG, DEST, "standard", "child_seat", False, _est(600))
    assert out["price"] == 600


def test_freeze_expires(rds):
    """Через окно заморозки цена считается заново."""
    sec = _remember(rds, _est(450))
    assert sec == settings.price_freeze_sec
    rds.delete(*rds.keys("pricefreeze:*"))          # так выглядит истёкший ключ
    assert _apply(rds, _est(495))["price"] == 495


# ============================ 3. Сбои не мешают уехать ============================
def test_no_redis_no_freeze_but_no_crash(rds):
    assert price_freeze.remember(None, 1, ORIG, DEST, "standard", "", False, _est(450)) == 0
    assert price_freeze.apply(None, 1, ORIG, DEST, "standard", "", False, _est(495))["price"] == 495


def test_turned_off_by_config(rds, monkeypatch):
    """price_freeze_sec = 0 → заморозки нет, поведение как до 2026-08-28."""
    monkeypatch.setattr(settings, "price_freeze_sec", 0)
    assert _remember(rds, _est(450)) == 0
    assert _apply(rds, _est(495))["price"] == 495


def test_broken_snapshot_is_ignored(rds):
    """Мусор в Redis не должен ломать заказ."""
    for key in rds.keys("*"):
        rds.delete(key)
    _remember(rds, _est(450))
    key = rds.keys("pricefreeze:*")[0]
    rds.set(key, "не json")
    assert _apply(rds, _est(495))["price"] == 495


# ============================ 4. Живьём через ручки ============================
def test_estimate_tells_how_long_the_price_is_held(client, user_factory, fake_redis):
    """Оценка возвращает, на сколько секунд цена закреплена — экран обязан это сказать."""
    pax = user_factory("ЗаморозкаПас")
    body = client.post("/instant/estimate", headers=pax["auth"], json=_order_body()).json()
    assert body["price_locked_sec"] == settings.price_freeze_sec


def test_order_is_not_more_expensive_than_the_screen_showed(client, user_factory, fake_redis,
                                                            monkeypatch):
    """Полный путь: увидел цену → спрос вырос вдвое → заказ всё равно по старой цене."""
    pax = user_factory("ЗаморозкаЧестный")
    body = _order_body()
    shown = client.post("/instant/estimate", headers=pax["auth"], json=body).json()["price"]

    # Пока человек думал, спрос подскочил — цена без заморозки выросла бы.
    monkeypatch.setattr(isv, "surge_k_for", lambda *a, **k: settings.surge_max_k)
    order = client.post("/instant/orders", headers=pax["auth"], json=body)
    assert order.status_code == 200, order.text
    assert order.json()["price_estimate"] == shown, "цена выросла после того, как её показали"
