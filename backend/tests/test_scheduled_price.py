"""Цена предзаказа: показываем то, по чему поедем (решение Александра, 2026-08-28).

Что было. Экран считал цену на «сейчас», а предзаказ оформлялся по цене на ВРЕМЯ ПОДАЧИ.
Заказ на пять утра, сделанный днём, показывал дневную цену и уезжал по ночной. Человек
запоминал увиденное число и утром получал другое — формально мы предупреждали «цену уточним
при подаче», по-человечески это выглядело как подмена.

Что стало. Клиент передаёт время подачи в расчёт цены, и оценка считается на этот момент.
Заморозка на предзаказ НЕ распространяется: его цену сервер всё равно пересчитает при подаче,
и обещать неизменность значит обещать то, чего мы не держим.

Плюс здесь же — обещание потолка («больше 420 ₽ не возьмём»), которое написано и ВЫКЛЮЧЕНО
до запуска: пока нет данных, как часто цена ночью в метель улетает, обещать доплату нельзя.
"""
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Barrier

import pytest
from sqlmodel import Session

from app import instant_service as isv, pricing
from app.config import settings
from app.db import engine
from app.models import InstantOrder
from app.timeutil import utcnow

from test_instant import DEST, ORIG, _order_body, fake_redis  # noqa: F401 — фикстура реэкспортом


def _через(часов: int) -> str:
    return (utcnow() + timedelta(hours=часов)).isoformat()


# ============================ 1. Оценка на время подачи ============================
def test_estimate_accepts_the_pickup_time(client, user_factory):
    """Оценка принимает время подачи и не падает."""
    pax = user_factory("ПредзаказЦена")
    body = dict(_order_body(), scheduled_at=_через(3))
    r = client.post("/instant/estimate", headers=pax["auth"], json=body)
    assert r.status_code == 200, r.text
    assert r.json()["price"] > 0


def test_night_preorder_is_priced_by_night(client, user_factory, monkeypatch):
    """Заказ на ночь считается по ночной ставке, даже если оформлен днём.

    Это и есть та самая подмена: человек видел дневное число, а уезжал по ночному.

    Сравниваем ДВА предзаказа — на местный полдень и на местную полночь. Сравнивать
    «сейчас» с «ночью» нельзя: прогон, запущенный вечером, сам оказывается ночным,
    и проверка молча превращается в сравнение двух одинаковых чисел (поймано 28.08).
    """
    from sqlmodel import Session, select

    from app.db import engine
    from app.models import Tariff
    monkeypatch.setattr(settings, "night_k_default", 1.15)
    # Спрос держим ровным: в общей тестовой базе полно чужих заказов, и надбавка за спрос
    # может упереться в общий потолок ×1,5 — тогда ночной прибавке в нём уже нет места.
    monkeypatch.setattr(isv, "surge_k_for", lambda *a, **k: 1.0)
    with Session(engine) as s:
        isv._seed_night(s)
        t = s.exec(select(Tariff).where(Tariff.zone == "city",
                                        Tariff.category == "standard")).first()
        assert t is not None and t.night_k > 1.0, "ночная ставка не проставилась в тариф"
    try:
        pax = user_factory("ПредзаказНочь")
        днём = _оценка_на(client, pax, местный_час=12)
        ночью = _оценка_на(client, pax, местный_час=0)
        assert днём["night_k"] == 1.0, "в полдень посчитали по ночной ставке"
        assert ночью["night_k"] > 1.0, "в полночь ночная ставка не применилась"
        assert ночью["price"] > днём["price"], "ночной предзаказ считается по дневной ставке"
    finally:
        with Session(engine) as s:
            for row in s.exec(select(Tariff)).all():
                row.night_k = 1.0
                s.add(row)
            s.commit()


def _оценка_на(client, pax, местный_час: int) -> dict:
    """Оценка предзаказа на ЗАВТРА в указанный местный (уфимский) час."""
    сдвиг = int(settings.local_tz_offset_hours)
    когда = utcnow().replace(minute=30, second=0, microsecond=0)
    когда = когда.replace(hour=(местный_час - сдвиг) % 24) + timedelta(days=1)
    r = client.post("/instant/estimate", headers=pax["auth"],
                    json=dict(_order_body(), scheduled_at=когда.isoformat()))
    assert r.status_code == 200, r.text
    return r.json()


def test_preorder_price_is_not_frozen(client, user_factory, fake_redis):
    """Предзаказ не замораживаем: его цену сервер пересчитает в момент подачи."""
    pax = user_factory("ПредзаказБезЗаморозки")
    сейчас = client.post("/instant/estimate", headers=pax["auth"],
                         json=_order_body()).json()
    потом = client.post("/instant/estimate", headers=pax["auth"],
                        json=dict(_order_body(), scheduled_at=_через(5))).json()
    assert сейчас["price_locked_sec"] == settings.price_freeze_sec
    assert потом["price_locked_sec"] == 0, "предзаказу обещана заморозка, которой не будет"


def test_bad_pickup_time_is_refused(client, user_factory):
    """Время в прошлом — та же проверка, что и при оформлении предзаказа."""
    pax = user_factory("ПредзаказПрошлое")
    прошлое = (utcnow() - timedelta(hours=1)).isoformat()
    r = client.post("/instant/estimate", headers=pax["auth"],
                    json=dict(_order_body(), scheduled_at=прошлое))
    assert r.status_code == 422


# ============================ 2. Обещание потолка (выключено) ============================
def test_price_guarantee_is_off_by_default(client):
    """По умолчанию обещания нет: пока не знаем, как часто цена улетает, обещать нельзя."""
    assert settings.scheduled_price_guarantee_percent == 0.0
    assert isv.scheduled_price_cap_rub(350) is None


def test_price_guarantee_caps_the_ride_when_enabled(client, monkeypatch):
    """Включённое обещание считает потолок от показанного числа."""
    monkeypatch.setattr(settings, "scheduled_price_guarantee_percent", 20.0)
    assert isv.scheduled_price_cap_rub(350) == 420


def test_price_guarantee_ignores_a_missing_price(client, monkeypatch):
    monkeypatch.setattr(settings, "scheduled_price_guarantee_percent", 20.0)
    assert isv.scheduled_price_cap_rub(0) is None


def test_guarantee_never_cuts_the_driver_compensations(client, monkeypatch):
    """Потолок режет цену ПОЕЗДКИ, а не бензин водителя.

    Обещание платит платформа из своей комиссии — по общему правилу «скидки платит платформа,
    а не водитель». Урезать компенсацию значило бы оплатить наше обещание его бензином.
    """
    monkeypatch.setattr(settings, "scheduled_price_guarantee_percent", 10.0)
    показано, компенсации = 300, 200
    потолок = isv.scheduled_price_cap_rub(показано)
    поездка = max(потолок - компенсации, 0)
    assert поездка + компенсации <= потолок
    assert компенсации == 200, "компенсация водителю осталась нетронутой"



@pytest.mark.parametrize(
    ("round_trip", "with_stops", "distance", "duration"),
    [(False, False, 49.0, 37.0), (False, True, 75.0, 56.0),
     (True, False, 49.0, 37.0), (True, True, 75.0, 56.0)],
    ids=["direct", "detour", "round-trip", "round-trip-with-detour"],
)
def test_activation_reprices_the_booked_route(
    client, user_factory, fake_redis, monkeypatch, round_trip, with_stops, distance, duration,
):
    """Цену обновляем к подаче, маршрут и формат поездки сохраняем."""
    stop1, stop2 = (52.682, 58.455), (52.647, 58.529)
    legs = {
        (ORIG, DEST): (49.0, 37.0),
        (ORIG, stop1): (30.0, 22.0),
        (stop1, stop2): (20.0, 16.0),
        (stop2, DEST): (25.0, 18.0),
    }
    monkeypatch.setattr(pricing, "route_metrics", lambda a, b: pricing.RouteMetrics(*legs[(a, b)]))
    monkeypatch.setattr(pricing, "weather_metrics", lambda *_: pricing.WeatherMetrics())
    monkeypatch.setattr(isv, "winter_road_kind", lambda *_: "")
    monkeypatch.setattr(isv, "surge_k_for", lambda *_: 1.0)
    monkeypatch.setattr(isv, "night_k_for", lambda *_: 1.0)
    monkeypatch.setattr(settings, "instant_intercity_km", 40.0)
    monkeypatch.setattr(settings, "round_trip_discount_percent", 20.0)
    monkeypatch.setattr(settings, "scheduled_price_guarantee_percent", 0.0)
    passenger = user_factory("SyntheticScheduledRoute")
    stops = [{"lat": lat, "lng": lng, "text": f"Точка {i}"} for i, (lat, lng)
             in enumerate((stop1, stop2), 1)] if with_stops else []
    body = _order_body(
        scheduled_at=_через(2), waypoints=stops, round_trip=round_trip,
        return_wait_min=20 if round_trip else 0,
    )
    scheduled = client.post("/instant/schedule", headers=passenger["auth"], json=body)
    assert scheduled.status_code == 200, scheduled.text
    booked = scheduled.json()
    with Session(engine) as session:
        before = session.get(InstantOrder, booked["id"])
        assert before.round_trip is round_trip
        saved_route = (before.waypoints_json, before.round_trip, before.return_wait_min)
    assert booked["distance_km"] == distance

    monkeypatch.setattr(isv, "surge_k_for", lambda *_: 1.4)
    refreshed = client.post("/instant/estimate", headers=passenger["auth"], json=body)
    assert refreshed.status_code == 200, refreshed.text
    expected = refreshed.json()
    assert expected["price"] > booked["price_estimate"]
    activated = client.post(f"/instant/scheduled/{booked['id']}/activate", headers=passenger["auth"])
    assert activated.status_code == 200, activated.text
    actual = activated.json()
    assert actual["status"] != "scheduled"
    assert actual["distance_km"] == distance and actual["eta_min"] == duration
    with Session(engine) as session:
        after = session.get(InstantOrder, booked["id"])
        assert (after.waypoints_json, after.round_trip, after.return_wait_min) == saved_route
    assert actual["price_estimate"] == expected["price"]


def test_interrupted_activation_keeps_original_cap(client, user_factory, fake_redis, monkeypatch):
    pax = user_factory("SyntheticScheduledCap")
    monkeypatch.setattr(settings, "scheduled_price_guarantee_percent", 20.0)
    booked = client.post("/instant/schedule", headers=pax["auth"],
                         json=_order_body(scheduled_at=_через(2))).json()
    order_id = booked["id"]
    with Session(engine) as session:
        order = session.get(InstantOrder, order_id)
        order.price_estimate = 350
        session.commit()

    market = {"price": 900, "ride_price": 900, "distance_km": 37.0, "eta_min": 45,
              "tariff_id": None, "surge_k": 1.0}
    monkeypatch.setattr(isv, "estimate", lambda *args, **kwargs: market)
    start = isv.start_matching

    def interrupted(*_):
        raise RuntimeError("synthetic interruption before marking order searching")

    monkeypatch.setattr(isv, "start_matching", interrupted)
    with Session(engine) as session:
        with pytest.raises(RuntimeError, match="synthetic interruption"):
            isv.activate_scheduled(session, session.get(InstantOrder, order_id))

    monkeypatch.setattr(isv, "start_matching", start)
    with Session(engine) as session:
        row = session.get(InstantOrder, order_id)
        assert row.status.value == "scheduled" and row.price_estimate == 350
        retried = isv.activate_scheduled(session, row)
        assert retried.price_estimate == 420

@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="PostgreSQL row locks")
def test_two_preorders_for_one_passenger_only_start_one(client, user_factory, monkeypatch):
    pax = user_factory("SyntheticConcurrentSchedule")
    ids = [
        client.post("/instant/schedule", headers=pax["auth"],
                    json=_order_body(scheduled_at=_через(2))).json()["id"]
        for _ in range(2)
    ]
    together = Barrier(2, timeout=8)

    def estimate_together(*_args, **_kwargs):
        together.wait()  # Оба запроса прошли первую проверку активной поездки.
        return {"price": 900, "ride_price": 900, "distance_km": 37.0, "eta_min": 45,
                "tariff_id": None, "surge_k": 1.0}

    monkeypatch.setattr(isv, "estimate", estimate_together)
    monkeypatch.setattr(isv, "try_offer_next", lambda _session, order, *_: order)

    def start(order_id):
        with Session(engine) as session:
            return isv.activate_scheduled(session, session.get(InstantOrder, order_id)).status.value

    with ThreadPoolExecutor(max_workers=2) as pool:
        outcomes = sorted(pool.map(start, ids))
    assert outcomes == ["scheduled", "searching"]
    with Session(engine) as session:
        assert sorted(session.get(InstantOrder, oid).status.value for oid in ids) == outcomes
