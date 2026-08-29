# -*- coding: utf-8 -*-
"""Усталость общая: доставка считается в то же рабочее время, что и такси (2026-08-29).

БЫЛО. Восьмичасовой лимит смены и сорокачасовая неделя считались ТОЛЬКО по такси. Доставки
не попадали в счётчик никуда. Водитель отрабатывал смену таксистом, упирался в лимит — и
весь вечер возил посылки, формально отдыхая. Гейт отдыха стоял на такси и молчал на приёме
курьерских заказов.

Руль не спрашивает, человек в машине или коробка. Ночная трасса Сибай–Акъяр одинаковая
в обоих случаях, и встречный свет слепит одинаково.

СТАЛО. Время считается по ОБЪЕДИНЕНИЮ отрезков работы — такси и доставок вместе:
  • час с пассажиром, потом час с посылкой = два часа;
  • час, когда он вёз и то и другое одновременно = один час (он не устал вдвое от того,
    что в багажнике коробка).
Сумма ошиблась бы во втором случае, максимум — в первом. Объединение отвечает верно в обоих.

И вторая граница, про деньги пассажира: пока на руках живой заказ такси, доставку взять
нельзя. Крюк за коробкой оплачивает пассажир — он платит за время в пути и ждёт машину,
которая едет к нему.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import workday
from app.config import settings
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, ParcelDelivery, UserRole
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def _modes_on():
    было = settings.taxi_enabled, settings.courier_enabled
    settings.taxi_enabled, settings.courier_enabled = True, True
    yield
    settings.taxi_enabled, settings.courier_enabled = было


def _посылка(courier_id: int, sender_id: int, принял, вручил=None, status="delivered") -> int:
    """Готовая доставка в истории: принята и (обычно) вручена — прямой посев."""
    with Session(engine) as s:
        p = ParcelDelivery(
            sender_id=sender_id, courier_id=courier_id, status=status,
            from_city="Сибай", to_city="Акъяр", description="коробка",
            delivery_type="courier", accepted_at=принял, delivered_at=вручил,
        )
        s.add(p)
        s.commit()
        s.refresh(p)
        return p.id


def _поездка(driver_id: int, passenger_id: int, принял, завершил=None, status=S.done) -> int:
    with Session(engine) as s:
        o = InstantOrder(
            passenger_id=passenger_id, driver_id=driver_id, status=status,
            from_lat=52.7, from_lng=58.6, to_lat=52.8, to_lng=58.7,
            price_estimate=300, accepted_at=принял, done_at=завершил,
        )
        s.add(o)
        s.commit()
        s.refresh(o)
        return o.id


# ==================== 1. Доставка попадает в рабочее время ====================
def test_delivering_parcels_counts_as_being_at_the_wheel(client, user_factory):
    """Два часа с посылками — это два часа работы, а не ноль."""
    drv = user_factory("ВозилПосылки", role=UserRole.driver)
    отправитель = user_factory("ОтправительЧасы")
    now = utcnow()
    _посылка(drv["id"], отправитель["id"], now - timedelta(hours=2), now)

    with Session(engine) as s:
        секунд = workday.work_seconds_today(s, drv["id"], now)
    assert секунд >= 2 * 3600 - 60, f"доставка не засчиталась в рабочее время: {секунд} сек"


def test_taxi_then_delivery_add_up(client, user_factory):
    """Час с пассажиром, потом час с посылкой — это два часа, а не один."""
    drv = user_factory("ТаксиПотомКурьер", role=UserRole.driver)
    pax = user_factory("ПассажирСложение")
    now = utcnow()
    _поездка(drv["id"], pax["id"], now - timedelta(hours=4), now - timedelta(hours=3))
    _посылка(drv["id"], pax["id"], now - timedelta(hours=2), now - timedelta(hours=1))

    with Session(engine) as s:
        секунд = workday.work_seconds_today(s, drv["id"], now)
    assert секунд >= 2 * 3600 - 60, f"два разных часа схлопнулись в один: {секунд} сек"


def test_carrying_both_at_once_is_still_one_hour(client, user_factory):
    """Вёз пассажира и посылку одновременно — это один час, а не два.

    Складывать отрезки значило бы наказывать ровно за то, ради чего он совмещает режимы.
    Он не устал вдвое от того, что в багажнике коробка.
    """
    drv = user_factory("ВёзОбоих", role=UserRole.driver)
    pax = user_factory("ПассажирОдновременно")
    now = utcnow()
    начало, конец = now - timedelta(hours=1), now
    _поездка(drv["id"], pax["id"], начало, конец)
    _посылка(drv["id"], pax["id"], начало, конец)

    with Session(engine) as s:
        секунд = workday.work_seconds_today(s, drv["id"], now)
    assert 3600 - 60 <= секунд <= 3600 + 60, f"один час посчитали дважды: {секунд} сек"


def test_a_parcel_still_in_transit_counts_up_to_now(client, user_factory):
    """Посылка ещё в пути — время считается до сих пор, а не пропадает."""
    drv = user_factory("ВезётСейчас", role=UserRole.driver)
    отправитель = user_factory("ОтправительВПути")
    now = utcnow()
    _посылка(drv["id"], отправитель["id"], now - timedelta(hours=3), None, status="in_transit")

    with Session(engine) as s:
        секунд = workday.work_seconds_today(s, drv["id"], now)
    assert секунд >= 3 * 3600 - 60, "незакрытая доставка не считается — лимит опять обойти"


# ==================== 2. Гейт отдыха закрывает оба режима ====================
def test_a_tired_driver_cannot_take_a_parcel_either(client, user_factory):
    """Смена выработана — доставку тоже не берём. Раньше гейт стоял только на такси."""
    from test_work_hours import _block, _driver_online

    drv = _driver_online(client, user_factory, "УсталИПошёлВозить")
    now = utcnow()
    _block(drv["id"], now)

    with Session(engine) as s:
        try:
            workday.guard_rested(s, drv["id"], now)
            assert False, "уставшего водителя пустили брать доставку"
        except Exception as e:  # noqa: BLE001 — это HTTPException 403
            assert getattr(e, "status_code", None) == 403


def test_the_old_name_of_the_guard_still_works(client, user_factory):
    """`guard_taxi_rested` зовут из нескольких мест — старое имя не должно отвалиться."""
    assert workday.guard_taxi_rested is workday.guard_rested


# ==================== 3. Граница между режимами ====================
def test_you_cannot_grab_a_parcel_while_a_passenger_waits(client, user_factory):
    """Живой заказ такси на руках — доставку не берём.

    Крюк за коробкой оплачивает ПАССАЖИР: он платит за время в пути и ждёт машину, которая
    едет к нему. Это не про вместимость багажника, а про то, за что человек отдал деньги.
    """
    from test_courier_c4 import _make_courier, _order

    courier = _make_courier(client, user_factory, name="КурьерСПассажиром")
    sender = user_factory("ОтправительЖдёт")
    pid = _order(client, sender).json()["id"]

    pax = user_factory("ПассажирВМашине")
    _поездка(courier["id"], pax["id"], utcnow() - timedelta(minutes=5), None, status=S.onboard)

    r = client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    assert r.status_code == 409, f"взял доставку с пассажиром в машине: {r.status_code}"
    assert "пассажир" in r.text.lower() or "юлаусы" in r.text.lower()


def test_a_box_from_a_previous_delivery_does_not_block_anything(client, user_factory):
    """Коробка в багажнике с прошлой доставки никому не мешает — вторую брать можно."""
    from test_courier_c4 import _make_courier, _order

    courier = _make_courier(client, user_factory, name="КурьерСКоробкой")
    sender = user_factory("ОтправительВторой")
    первая = _order(client, sender).json()["id"]
    assert client.post(f"/parcels/{первая}/accept", headers=courier["auth"]).status_code == 200

    вторая = _order(client, sender).json()["id"]
    r = client.post(f"/parcels/{вторая}/accept", headers=courier["auth"])
    assert r.status_code == 200, f"вторую доставку не дали взять: {r.text}"


def test_after_the_trip_is_over_deliveries_are_open_again(client, user_factory):
    """Высадил пассажира — доставки снова доступны. Граница временная, а не вечная."""
    from test_courier_c4 import _make_courier, _order

    courier = _make_courier(client, user_factory, name="КурьерОсвободился")
    sender = user_factory("ОтправительПосле")
    pid = _order(client, sender).json()["id"]

    pax = user_factory("ПассажирВышел")
    oid = _поездка(courier["id"], pax["id"], utcnow() - timedelta(minutes=30), None, status=S.onboard)
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 409

    with Session(engine) as s:            # поездка завершена
        o = s.get(InstantOrder, oid)
        o.status, o.done_at = S.done, utcnow()
        s.add(o)
        s.commit()

    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
