"""Экран «Мой Юлдаш» обязан видеть быстрые заказы (такси).

Аудит 2026-08-06 нашёл асимметрию: статистика и значки считали попутки и посылки,
а такси — нет. Человек со ста заказами такси видел «0 поездок, звание Новичок»
и никогда не получал значок «Первая поездка».

Правило, которое сторожит этот файл:
  • такси идёт в «поездки» и «километры» — человек ездил с нами;
  • такси НЕ идёт в «сэкономлено ₽» и «CO₂» — поездка на такси не экономит
    относительно такси и никого не подвозит попутно. Цифры не фейкуем.
"""
from sqlmodel import Session

from app.db import engine
from app.models import InstantOrder, InstantOrderStatus, UserRole


def _order(*, passenger_id, driver_id=None, status, km=10.0):
    with Session(engine) as s:
        o = InstantOrder(
            passenger_id=passenger_id,
            driver_id=driver_id,
            status=status,
            distance_km=km,
            from_lat=53.0, from_lng=58.0, to_lat=53.1, to_lng=58.1,
        )
        s.add(o)
        s.commit()
        s.refresh(o)
        return o


def test_done_taxi_order_counts_as_trip_for_passenger(client, user_factory):
    u = user_factory("TaxiStatsPax")
    before = client.get("/me/stats", headers=u["auth"]).json()
    _order(passenger_id=u["id"], status=InstantOrderStatus.done, km=12.0)
    after = client.get("/me/stats", headers=u["auth"]).json()

    assert after["trips"] == before["trips"] + 1, "завершённое такси не попало в поездки"
    assert after["km"] == round(before["km"] + 12.0, 1), "километры такси не попали в счётчик"


def test_done_taxi_order_counts_as_trip_for_driver(client, user_factory):
    drv = user_factory("TaxiStatsDrv", role=UserRole.driver)
    pax = user_factory("TaxiStatsPax2")
    before = client.get("/me/stats", headers=drv["auth"]).json()
    _order(passenger_id=pax["id"], driver_id=drv["id"],
           status=InstantOrderStatus.done, km=8.0)
    after = client.get("/me/stats", headers=drv["auth"]).json()

    assert after["trips"] == before["trips"] + 1, "водитель такси не видит свою поездку"
    assert after["km"] == round(before["km"] + 8.0, 1)


def test_taxi_does_not_inflate_savings(client, user_factory):
    """Такси не экономит относительно такси — «сэкономлено» и CO₂ не растут."""
    u = user_factory("TaxiStatsSave")
    before = client.get("/me/stats", headers=u["auth"]).json()
    _order(passenger_id=u["id"], status=InstantOrderStatus.done, km=100.0)
    after = client.get("/me/stats", headers=u["auth"]).json()

    assert after["km"] > before["km"], "контроль: километры всё же должны вырасти"
    assert after["saved_rub"] == before["saved_rub"], "такси накрутило «сэкономлено»"
    assert after["co2_saved_kg"] == before["co2_saved_kg"], "такси накрутило CO₂"


def test_unfinished_taxi_order_does_not_count(client, user_factory):
    """Считаем только доехавших: заказ в поиске/отменённый — не поездка."""
    u = user_factory("TaxiStatsOpen")
    before = client.get("/me/stats", headers=u["auth"]).json()
    for st in (InstantOrderStatus.searching, InstantOrderStatus.cancelled,
               InstantOrderStatus.expired, InstantOrderStatus.onboard):
        _order(passenger_id=u["id"], status=st, km=50.0)
    after = client.get("/me/stats", headers=u["auth"]).json()

    assert after["trips"] == before["trips"], "незавершённое такси попало в поездки"
    assert after["km"] == before["km"], "километры незавершённого такси попали в счётчик"


def test_taxi_raises_rank(client, user_factory):
    """Звание растёт по общему числу поездок, иначе таксист навсегда «Новичок»."""
    u = user_factory("TaxiStatsRank")
    for _ in range(10):
        _order(passenger_id=u["id"], status=InstantOrderStatus.done, km=1.0)
    s = client.get("/me/stats", headers=u["auth"]).json()
    assert s["trips"] >= 10
    assert s["rank"]["level"] >= 2, f"звание не выросло: {s['rank']}"


def test_taxi_earns_first_trip_badge(client, user_factory):
    """Значок «Первая поездка» должен приходить и за такси."""
    u = user_factory("TaxiStatsBadge")
    before = client.get("/me/achievements", headers=u["auth"]).json()
    first_before = [b for b in before["achievements"] if b["code"] == "first_trip"][0]
    assert not first_before["earned"], "контроль: у нового пользователя значка быть не должно"

    _order(passenger_id=u["id"], status=InstantOrderStatus.done, km=5.0)
    after = client.get("/me/achievements", headers=u["auth"]).json()
    badge = [b for b in after["achievements"] if b["code"] == "first_trip"][0]
    assert badge["earned"], "за поездку на такси значок «Первая поездка» не пришёл"
    assert after["trips"] == before["trips"] + 1
