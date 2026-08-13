"""Оценка поездки ведёт себя одинаково — и в попутке, и в такси.

Человек делает одно и то же: ставит звёзды и пишет пару слов. А ручки писались в разное время
и разошлись (проверено запросами, аудит 2026-08-13, волна 57):

  • такси МОЛЧА ТЕРЯЛО отзыв: схема запроса принимала текст и метки, приложение их слало,
    а сервер выбрасывал — в базе оставались одни звёзды. Ни ошибки, ни следа;
  • текст отзыва по попутке не проходил модерацию: публикации он ждал, но счётчик помеченных
    текстов оставался пустым — админ не узнавал, что в отзывах пишут телефоны и оскорбления,
    пока сам не открыл бы очередь;
  • срока на оценку не было вовсе: поездку 400-дневной давности можно было оценить сегодня.
    Это уже не отзыв о поездке, а способ достать человека спустя год.

Теперь обе двери зовут одну функцию — иначе они снова разъедутся при первой же правке.
"""
from __future__ import annotations

from datetime import timedelta

from sqlmodel import Session, select

from app.db import engine
from app.models import (Booking, BookingStatus, InstantOrder, InstantOrderStatus as S, Rating,
                        Ride, RideStatus, TextFlag, UserRole)
from app.rating_service import RATING_WINDOW_DAYS
from app.timeutil import utcnow

WITH_PHONE = "Хамит, звони мимо приложения 89991112233"


def _done_booking(session: Session, pax_id: int, drv_id: int, days_ago: int = 1) -> int:
    ride = Ride(driver_id=drv_id, from_city="Сибай", to_city="Уфа",
                depart_at=utcnow() - timedelta(days=days_ago), seats_total=3, seats_left=2,
                price=500, status=RideStatus.active)
    session.add(ride)
    session.commit()
    session.refresh(ride)
    b = Booking(ride_id=ride.id, passenger_id=pax_id, seats=1, price=500,
                status=BookingStatus.done, boarding_code="123456")
    session.add(b)
    session.commit()
    session.refresh(b)
    return b.id


def _done_order(session: Session, pax_id: int, drv_id: int, hours_ago: int = 1) -> int:
    o = InstantOrder(passenger_id=pax_id, driver_id=drv_id, status=S.done,
                     from_lat=54.7, from_lng=55.9, to_lat=54.8, to_lng=56.0,
                     from_text="Сибай", to_text="Уфа", price_estimate=300,
                     created_at=utcnow() - timedelta(hours=hours_ago + 2),
                     done_at=utcnow() - timedelta(hours=hours_ago))
    session.add(o)
    session.commit()
    session.refresh(o)
    return o.id


def test_отзыв_о_такси_больше_не_пропадает(client, user_factory):
    pax = user_factory("RateTaxiPax", role=UserRole.passenger)
    drv = user_factory("RateTaxiDrv", role=UserRole.driver)
    with Session(engine) as s:
        oid = _done_order(s, pax["id"], drv["id"])

    r = client.post(f"/instant/orders/{oid}/rate", headers=pax["auth"],
                    json={"stars": 4, "text": "Спасибо, довёз аккуратно", "tags": "polite"})
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        row = s.exec(select(Rating).where(Rating.order_id == oid)).first()
        assert row is not None
        assert row.text == "Спасибо, довёз аккуратно"
        assert row.tags == "polite"
        assert row.text_published is False        # публикуется только после модерации


def test_телефон_в_отзыве_попадает_к_админу(client, user_factory):
    """Отзыв виден в публичном профиле — это такое же открытое поле, как комментарий."""
    pax = user_factory("RateFlagPax", role=UserRole.passenger)
    drv = user_factory("RateFlagDrv", role=UserRole.driver)
    with Session(engine) as s:
        bid = _done_booking(s, pax["id"], drv["id"])
        before = len(s.exec(select(TextFlag.id).where(TextFlag.user_id == pax["id"])).all())

    assert client.post(f"/bookings/{bid}/rate", headers=pax["auth"],
                       json={"stars": 1, "text": WITH_PHONE}).status_code == 200

    with Session(engine) as s:
        after = len(s.exec(select(TextFlag.id).where(TextFlag.user_id == pax["id"])).all())
        assert after > before
        row = s.exec(select(Rating).where(Rating.booking_id == bid)).first()
        assert row.text == WITH_PHONE             # текст не режем и оценку не рвём
        assert row.text_published is False


def test_то_же_правило_в_такси(client, user_factory):
    """Вторая дверь: телефон в отзыве о такси тоже доходит до админа."""
    pax = user_factory("RateFlagTaxiPax", role=UserRole.passenger)
    drv = user_factory("RateFlagTaxiDrv", role=UserRole.driver)
    with Session(engine) as s:
        oid = _done_order(s, pax["id"], drv["id"])
        before = len(s.exec(select(TextFlag.id).where(TextFlag.user_id == pax["id"])).all())

    assert client.post(f"/instant/orders/{oid}/rate", headers=pax["auth"],
                       json={"stars": 1, "text": WITH_PHONE}).status_code == 200

    with Session(engine) as s:
        after = len(s.exec(select(TextFlag.id).where(TextFlag.user_id == pax["id"])).all())
        assert after > before


def test_поездку_годичной_давности_оценить_нельзя(client, user_factory):
    pax = user_factory("RateOldPax", role=UserRole.passenger)
    drv = user_factory("RateOldDrv", role=UserRole.driver)
    with Session(engine) as s:
        bid = _done_booking(s, pax["id"], drv["id"], days_ago=RATING_WINDOW_DAYS + 10)

    r = client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 1})
    assert r.status_code == 409
    detail = r.json()["detail"]
    assert detail["ru"] and detail["ba"] and detail["ru"] != detail["ba"]


def test_вчерашнюю_поездку_оценить_можно(client, user_factory):
    """Страховка от перестраховки: человек заходит в приложение не каждый день."""
    pax = user_factory("RateFreshPax", role=UserRole.passenger)
    drv = user_factory("RateFreshDrv", role=UserRole.driver)
    with Session(engine) as s:
        bid = _done_booking(s, pax["id"], drv["id"], days_ago=RATING_WINDOW_DAYS - 5)

    assert client.post(f"/bookings/{bid}/rate", headers=pax["auth"],
                       json={"stars": 5}).status_code == 200


def test_правка_оценки_метки_не_теряет(client, user_factory):
    """Первый тап уходит со звёздами, метки прилетают следующим запросом — не затираем."""
    pax = user_factory("RateEditPax", role=UserRole.passenger)
    drv = user_factory("RateEditDrv", role=UserRole.driver)
    with Session(engine) as s:
        bid = _done_booking(s, pax["id"], drv["id"])

    client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 5, "tags": "polite"})
    client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 4})

    with Session(engine) as s:
        row = s.exec(select(Rating).where(Rating.booking_id == bid)).first()
        assert row.stars == 4
        assert row.tags == "polite"
