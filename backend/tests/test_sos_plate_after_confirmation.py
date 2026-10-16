"""Госномер близким в SOS — только когда он открыт пассажиру и в самом приложении.

Решение Александра 16.10.2026 (раньше — «Ждёт решения», пункт 3, N13). Приложение показывает
пассажиру госномер только после подтверждения брони водителем (`booking_contacts_open`,
`/bookings/{id}/details`). SOS же отдавал номер близким по любой своей брони, в том числе
«ожидает»: забронировал чужую поездку, вписал себя же «близким», нажал SOS — и номер чужой
машины пришёл SMS-кой, хотя водитель ещё никого не брал. До подтверждения близким уходят
цвет, марка и модель — по ним машину не вычислить, но и с пустыми руками родные не остаются.
Дежурному (Telegram) — по-прежнему всё: ему это нужно для помощи, и он сам — часть службы.
"""
from datetime import timedelta

from sqlmodel import Session

import app.routers.safety as safety
from app.db import engine
from app.models import Booking, BookingStatus, DriverProfile, Ride, RideStatus, UserRole
from app.timeutil import utcnow

PLATE = "К777РА102"
FAMILY = "+79170000411"


def _trip(driver_id: int, passenger_id: int, status: BookingStatus, *, make: str = "Лада",
          model: str = "Гранта"):
    with Session(engine) as s:
        s.add(DriverProfile(user_id=driver_id, car_make=make, car_model=model,
                            car_color="белая", car_plate=PLATE))
        ride = Ride(driver_id=driver_id, from_city="Баймак", to_city="Сибай",
                    depart_at=utcnow() + timedelta(hours=2),
                    seats_total=3, seats_left=2, price=300, status=RideStatus.active)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=passenger_id, seats=1, price=300, status=status)
        s.add(b)
        s.commit()
        s.refresh(b)
        return ride, b


def _press(client, who, booking_id, monkeypatch):
    """Нажать SOS по брони; вернуть (SMS близким, сообщения дежурному)."""
    client.post("/trusted-contacts", headers=who["auth"],
                json={"name": "Мама", "relation": "мама", "phone": FAMILY})
    sms, tg = [], []
    monkeypatch.setattr(safety, "_send_sos_sms", lambda phones, text: sms.append((list(phones), text)))
    monkeypatch.setattr(safety, "notify_admin_telegram", lambda m: tg.append(m))
    r = client.post("/sos", headers=who["auth"],
                    json={"category": "other", "note": "", "booking_id": booking_id})
    assert r.status_code == 200, r.text
    to_family = [t for phones, t in sms if FAMILY in phones]
    assert to_family, "близким SMS не ушло"
    return to_family[0], tg


def test_pending_booking_gives_family_the_car_but_not_the_plate(client, user_factory, monkeypatch):
    drv = user_factory("PlateDrv1", role=UserRole.driver)
    pax = user_factory("PlatePax1")
    _ride, b = _trip(drv["id"], pax["id"], BookingStatus.pending)

    text, tg = _press(client, pax, b.id, monkeypatch)

    assert PLATE not in text, f"госномер ушёл по неподтверждённой брони: {text}"
    assert "белая" in text and "Лада" in text and "Гранта" in text, f"нет цвета/марки/модели: {text}"
    assert tg and PLATE in tg[0], "дежурному госномер нужен всегда — он помогает, а не ищет"


def test_cancelled_never_confirmed_booking_gives_no_plate(client, user_factory, monkeypatch):
    drv = user_factory("PlateDrv2", role=UserRole.driver)
    pax = user_factory("PlatePax2")
    _ride, b = _trip(drv["id"], pax["id"], BookingStatus.cancelled)

    text, _tg = _press(client, pax, b.id, monkeypatch)

    assert PLATE not in text, f"госномер ушёл по отменённой брони: {text}"


def test_confirmed_booking_gives_family_the_plate(client, user_factory, monkeypatch):
    """Контроль: после подтверждения номер открыт и в приложении — значит, и родным."""
    drv = user_factory("PlateDrv3", role=UserRole.driver)
    pax = user_factory("PlatePax3")
    _ride, b = _trip(drv["id"], pax["id"], BookingStatus.confirmed)

    text, _tg = _press(client, pax, b.id, monkeypatch)

    assert PLATE in text, f"нет госномера после подтверждения — родным некого искать: {text}"
    assert "Лада Гранта" in text, f"марка потерялась: {text}"


def test_driver_family_gets_his_own_plate_even_before_confirmation(client, user_factory, monkeypatch):
    """Водитель жмёт SOS в своей поездке: машина — его собственная, прятать её от его же родных незачем."""
    drv = user_factory("PlateDrv4", role=UserRole.driver)
    pax = user_factory("PlatePax4")
    _ride, b = _trip(drv["id"], pax["id"], BookingStatus.pending)

    text, _tg = _press(client, drv, b.id, monkeypatch)

    assert PLATE in text, f"родным водителя не ушёл номер его же машины: {text}"


def test_make_is_not_doubled_when_model_already_names_it(client, user_factory, monkeypatch):
    """Анкеты заполняют по-разному: «Лада» + «Лада Гранта» не должно стать «Лада Лада Гранта»."""
    drv = user_factory("PlateDrv5", role=UserRole.driver)
    pax = user_factory("PlatePax5")
    _ride, b = _trip(drv["id"], pax["id"], BookingStatus.onboard, make="Лада", model="Лада Гранта")

    text, _tg = _press(client, pax, b.id, monkeypatch)

    assert "Лада Лада" not in text, f"марка задвоилась: {text}"
    assert "Лада Гранта" in text and PLATE in text, text
