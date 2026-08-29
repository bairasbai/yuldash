"""Волна 197: «оцени поездку» напоминают и после такси, и после доставки.

Рейтинг у нас — это доверие между своими, и человек часто закрывает приложение, не поставив
звёзды. Ровно для этого есть фоновое напоминание: раз в N минут оно находит завершённые
поездки, по которым ещё не напоминали, и пишет тем, кто не оценил.

Работало оно только по броням попутки. У такси и доставки такого напоминания не было
вообще — а оценить поездку можно лишь 60 дней (`rating_service.RATING_WINDOW_DAYS`),
после чего звёзды потеряны навсегда. Для таксиста это прямо деньги: с волны 194 его
рабочий рейтинг считается только по поездкам, где он был за рулём.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import rate_reminder
from app.config import settings
from app.db import engine
from app.models import (
    Booking, BookingStatus, DriverProfile, InstantOrder, InstantOrderStatus as S,
    ParcelDelivery, Rating, Ride, UserRole,
)
from app.timeutil import utcnow

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


@pytest.fixture
def reminder_on(monkeypatch):
    monkeypatch.setattr(settings, "rate_reminder_enabled", True, raising=False)
    yield


@pytest.fixture
def pushes(monkeypatch):
    """Что человек реально увидит: запись в Центр уведомлений + пуш.

    Собираем ВСЕ четыре текста, а не только заголовок. Мутационный проход поймал: проверка
    двуязычия смотрела на заголовок, а пустой башкирский в теле сообщения проходила молча —
    ровно та дыра, что была в волне 193."""
    sent: list = []
    monkeypatch.setattr(
        rate_reminder, "push_notification",
        lambda session, uid, ntype, title_ru, title_ba, body_ru, body_ba, **kw:
            sent.append((uid, title_ru, title_ba, body_ru, body_ba)))
    return sent


def _ensure_profile(s: Session, user_id: int) -> None:
    if s.exec(select(DriverProfile).where(DriverProfile.user_id == user_id)).first() is None:
        s.add(DriverProfile(user_id=user_id))
        s.commit()


def _done_booking(s: Session, driver_id: int, passenger_id: int) -> Booking:
    _ensure_profile(s, driver_id)
    ride = Ride(driver_id=driver_id, from_city="Баймак", to_city="Сибай", seats=4,
                price=300, depart_at=utcnow() - timedelta(hours=2))
    s.add(ride)
    s.commit()
    s.refresh(ride)
    b = Booking(ride_id=ride.id, passenger_id=passenger_id, seats=1, price=300,
                status=BookingStatus.done)
    s.add(b)
    s.commit()
    s.refresh(b)
    return b


def _done_order(s: Session, driver_id: int, passenger_id: int) -> InstantOrder:
    _ensure_profile(s, driver_id)
    o = InstantOrder(passenger_id=passenger_id, driver_id=driver_id,
                     from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                     status=S.done, price_estimate=200, price_final=200, done_at=utcnow())
    s.add(o)
    s.commit()
    s.refresh(o)
    return o


def _delivered_parcel(s: Session, sender_id: int, courier_id: int) -> ParcelDelivery:
    p = ParcelDelivery(sender_id=sender_id, courier_id=courier_id, from_city="Баймак",
                       to_city="Сибай", status="delivered")
    s.add(p)
    s.commit()
    s.refresh(p)
    return p


def _reminded_users(reminded: list, *свои) -> set:
    """Кому напомнили ИЗ ЛЮДЕЙ ЭТОГО ТЕСТА.

    База у тестов общая, и завершённые поездки соседних тестов живут в ней же: без фильтра
    проверка считала бы чужих. Один и тот же фильтр во всех проверках файла — иначе часть
    из них молча меряет не то."""
    мои = {u["id"] for u in свои}
    return {uid for _ref, uid in reminded if uid in мои}


# ==================== 1. Попутка — опора, работала и раньше ====================
def test_pooling_both_sides_get_reminded(client, user_factory, reminder_on, pushes):
    drv = user_factory("PoolRemDrv", role=UserRole.driver)
    pax = user_factory("PoolRemPax")
    with Session(engine) as s:
        _done_booking(s, drv["id"], pax["id"])
        reminded = rate_reminder.rate_reminder_once(s)

    assert _reminded_users(reminded, drv, pax) == {drv["id"], pax["id"]}
    assert ("Оцени поездку", "Сәфәрҙе баһала") in {(p[1], p[2]) for p in pushes}
    assert all(p[2] and p[4] for p in pushes), (
        "напоминание обязано быть на двух языках — и заголовок, и текст"
    )


# ==================== 2. Такси ====================
def test_taxi_both_sides_get_reminded(client, user_factory, reminder_on, pushes):
    """Заказ такси завершён, никто не оценил — напомнить обоим."""
    drv = user_factory("TaxiRemDrv", role=UserRole.driver)
    pax = user_factory("TaxiRemPax")
    with Session(engine) as s:
        _done_order(s, drv["id"], pax["id"])
        reminded = rate_reminder.rate_reminder_once(s)

    assert _reminded_users(reminded, drv, pax) == {drv["id"], pax["id"]}, (
        "после такси не напомнили никому — звёзды теряются через 60 дней"
    )
    assert all(p[2] and p[4] for p in pushes), (
        "напоминание обязано быть на двух языках — и заголовок, и текст"
    )


def test_taxi_person_who_already_rated_is_not_bothered(client, user_factory, reminder_on, pushes):
    """Кто оценил — тому не пишем. Напоминание не должно превращаться в спам."""
    drv = user_factory("TaxiRatedDrv", role=UserRole.driver)
    pax = user_factory("TaxiRatedPax")
    with Session(engine) as s:
        o = _done_order(s, drv["id"], pax["id"])
        s.add(Rating(order_id=o.id, rater_id=pax["id"], ratee_id=drv["id"], stars=5))
        s.commit()
        reminded = rate_reminder.rate_reminder_once(s)

    assert _reminded_users(reminded, drv, pax) == {drv["id"]}, "пассажир уже поставил звёзды"


def test_taxi_reminder_goes_out_once(client, user_factory, reminder_on, pushes):
    """Второй проход не повторяет: флаг ставится после отправки."""
    drv = user_factory("TaxiOnceDrv", role=UserRole.driver)
    pax = user_factory("TaxiOncePax")
    with Session(engine) as s:
        _done_order(s, drv["id"], pax["id"])
        first = rate_reminder.rate_reminder_once(s)
        second = rate_reminder.rate_reminder_once(s)

    assert _reminded_users(first, drv, pax) == {drv["id"], pax["id"]}
    assert _reminded_users(second, drv, pax) == set(), "напомнили дважды — это уже спам"


def test_taxi_order_without_driver_is_skipped(client, user_factory, reminder_on, pushes):
    """Заказ закрылся без водителя (никто не взял) — оценивать некого."""
    pax = user_factory("TaxiNoDrvPax")
    with Session(engine) as s:
        o = InstantOrder(passenger_id=pax["id"], driver_id=None,
                         from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                         status=S.done, price_estimate=200, done_at=utcnow())
        s.add(o)
        s.commit()
        reminded = rate_reminder.rate_reminder_once(s)

    assert _reminded_users(reminded, pax) == set(), (
        "заказ никто не взял — «оцени поездку» человеку, который так и не уехал"
    )


# ==================== 3. Доставка ====================
def test_parcel_both_sides_get_reminded(client, user_factory, reminder_on, pushes):
    """Посылка вручена — напоминаем отправителю и курьеру."""
    sender = user_factory("ParcelRemSender")
    courier = user_factory("ParcelRemCourier", role=UserRole.driver)
    with Session(engine) as s:
        _delivered_parcel(s, sender["id"], courier["id"])
        reminded = rate_reminder.rate_reminder_once(s)

    assert _reminded_users(reminded, sender, courier) == {sender["id"], courier["id"]}, (
        "после доставки не напомнили никому"
    )
    assert all(p[2] and p[4] for p in pushes), (
        "напоминание обязано быть на двух языках — и заголовок, и текст"
    )


def test_parcel_not_yet_delivered_is_not_reminded(client, user_factory, reminder_on, pushes):
    """Посылка ещё в пути — оценивать нечего."""
    sender = user_factory("ParcelInTransitSender")
    courier = user_factory("ParcelInTransitCourier", role=UserRole.driver)
    with Session(engine) as s:
        p = _delivered_parcel(s, sender["id"], courier["id"])
        p.status = "in_transit"
        s.add(p)
        s.commit()
        reminded = rate_reminder.rate_reminder_once(s)

    assert _reminded_users(reminded, sender, courier) == set()


# ==================== 4. Общие правила ====================
def test_disabled_reminder_touches_nothing(client, user_factory, monkeypatch, pushes):
    monkeypatch.setattr(settings, "rate_reminder_enabled", False, raising=False)
    drv = user_factory("OffDrv", role=UserRole.driver)
    pax = user_factory("OffPax")
    with Session(engine) as s:
        _done_order(s, drv["id"], pax["id"])
        assert rate_reminder.rate_reminder_once(s) == []
    assert pushes == []


def test_dry_run_shows_but_changes_nothing(client, user_factory, reminder_on, pushes):
    """Сухой прогон показывает, кому ушло бы, и не тратит право напомнить."""
    drv = user_factory("DryDrv", role=UserRole.driver)
    pax = user_factory("DryPax")
    with Session(engine) as s:
        _done_order(s, drv["id"], pax["id"])
        dry = rate_reminder.rate_reminder_once(s, dry_run=True)
        после_сухого = list(pushes)
        real = rate_reminder.rate_reminder_once(s)

    assert _reminded_users(dry, drv, pax) == {drv["id"], pax["id"]}
    assert после_сухого == [], "сухой прогон слал пуши — а он только показывает"
    assert _reminded_users(real, drv, pax) == {drv["id"], pax["id"]}, (
        "сухой прогон съел право напомнить"
    )
    assert {drv["id"], pax["id"]} <= {p[0] for p in pushes}, "реальный проход не написал людям"


def test_old_rides_are_not_reminded(client, user_factory, reminder_on, pushes):
    """Поездка годичной давности: оценить её уже нельзя (окно 60 дней) — молчим."""
    drv = user_factory("OldDrv", role=UserRole.driver)
    pax = user_factory("OldPax")
    with Session(engine) as s:
        o = _done_order(s, drv["id"], pax["id"])
        old = utcnow() - timedelta(days=rate_reminder.REMIND_MAX_AGE_DAYS + 5)
        o.created_at = old
        o.done_at = old
        s.add(o)
        s.commit()
        reminded = rate_reminder.rate_reminder_once(s)

    assert _reminded_users(reminded, drv, pax) == set()
