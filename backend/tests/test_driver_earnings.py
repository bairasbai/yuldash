"""История заработка водителя (GET /driver/earnings): сумма за период, разбивка по дням,
изоляция по водителю, пустой период = нули."""
from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow


def _make_done_order(driver_id: int, passenger_id: int, price_rub: int, done_at) -> None:
    with Session(engine) as s:
        s.add(InstantOrder(
            passenger_id=passenger_id, driver_id=driver_id,
            from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6,
            from_text="А", to_text="Б", status=S.done,
            price_estimate=price_rub, price_final=price_rub, done_at=done_at,
        ))
        s.commit()


def test_empty_period_is_zero(client, user_factory):
    driver = user_factory(role=UserRole.driver)
    r = client.get("/driver/earnings?period=week", headers=driver["auth"])
    assert r.status_code == 200
    body = r.json()
    assert body["total"] == 0 and body["trips"] == 0 and body["by_day"] == []


def test_week_sum_and_breakdown(client, user_factory):
    driver = user_factory(role=UserRole.driver)
    pax = user_factory()
    now = utcnow()
    # два заказа сегодня + один вчера → сумма 600, 2 дня в разбивке
    _make_done_order(driver["id"], pax["id"], 200, now)
    _make_done_order(driver["id"], pax["id"], 100, now)
    _make_done_order(driver["id"], pax["id"], 300, now - timedelta(days=1))
    # заказ 40 дней назад — вне недели/месяца
    _make_done_order(driver["id"], pax["id"], 999, now - timedelta(days=40))

    week = client.get("/driver/earnings?period=week", headers=driver["auth"]).json()
    assert week["total"] == 600
    assert week["trips"] == 3
    assert len(week["by_day"]) == 2
    # суммы по дням в порядке возрастания даты: вчера(300) → сегодня(300)
    assert [d["sum"] for d in week["by_day"]] == [300, 300]
    assert week["by_day"][-1]["trips"] == 2   # сегодня 2 поездки

    month = client.get("/driver/earnings?period=month", headers=driver["auth"]).json()
    assert month["total"] == 600   # 40-дневный всё ещё вне 30 дней

    allp = client.get("/driver/earnings?period=all", headers=driver["auth"]).json()
    assert allp["total"] == 600 + 999
    assert allp["trips"] == 4


def test_earnings_isolated_per_driver(client, user_factory):
    d1 = user_factory(role=UserRole.driver)
    d2 = user_factory(role=UserRole.driver)
    pax = user_factory()
    _make_done_order(d1["id"], pax["id"], 500, utcnow())
    _make_done_order(d2["id"], pax["id"], 700, utcnow())
    assert client.get("/driver/earnings?period=all", headers=d1["auth"]).json()["total"] == 500
    assert client.get("/driver/earnings?period=all", headers=d2["auth"]).json()["total"] == 700
