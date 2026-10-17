"""Посадка — это запись в брони, а не только сообщение маме.

Найдено сквозной проверкой волны 1 (13.10.2026, `tests/journeys/`). Пассажир нажимал «Я сел»,
сервер передавал это только близким, с которыми поделились поездкой. Если близких в списке не
было — сервер не записывал вообще ничего, бронь до самого конца оставалась «подтверждена»:
* водитель не видел, что человек уже в машине;
* переоткрытое приложение снова звало «Я сел» (Android и PWA ждут от сервера `onboard`);
* у мамы по ссылке до конца поездки висело «Машина едет к точке посадки».

С 17.10.2026 посадку отмечает ВОДИТЕЛЬ («Сел», решение Александра): пассажирский «Я сел»
можно было нажать из дома, чтобы уйти от «Не пришёл». Правила записи посадки те же — теперь
их держит отметка водителя. Подробно про отметку и её отмену — `test_driver_marks_boarding.py`.
"""
from datetime import timedelta

from sqlmodel import Session, select

from app.cleanup import RIDE_CLOSE_GRACE_HOURS, close_past_rides
from app.db import engine
from app.models import Booking, BookingStatus, Ride, RideStatus, UserRole
from app.timeutil import utcnow


def _trip(client, user_factory, *, confirm=True, depart_in=timedelta(minutes=15)):
    drv = user_factory("Boarding Driver", role=UserRole.driver)
    pax = user_factory("Boarding Passenger")
    ride = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Уфа",
        "depart_at": (utcnow() + depart_in).isoformat() + "+00:00",
        "seats_total": 2, "price": 500}).json()
    b = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 1}).json()
    if confirm:
        assert client.post(f"/bookings/{b['id']}/confirm", headers=drv["auth"]).status_code == 200
    return drv, pax, ride, b["id"]


def _status(client, who, bid):
    return client.get(f"/bookings/{bid}/role", headers=who["auth"]).json()["status"]


def _board(client, drv, bid):
    return client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": "boarded"})


def test_driver_mark_without_trusted_contacts_puts_booking_onboard(client, user_factory):
    """Главный случай: близких нет — посадка всё равно записана, и её видят обе стороны."""
    drv, pax, _ride, bid = _trip(client, user_factory)
    r = _board(client, drv, bid)
    assert r.status_code == 200 and r.json()["status"] == "onboard", r.text
    assert _status(client, pax, bid) == "onboard"
    assert _status(client, drv, bid) == "onboard"
    details = client.get(f"/bookings/{bid}/details", headers=pax["auth"]).json()
    assert details["status"] == "onboard" and details["contact_unlocked"] is True


def test_arrived_from_the_passenger_no_longer_means_onboard(client, user_factory):
    """«Доехал» пассажира бронь не двигает: иначе это была бы та же посадка другой дверью
    (старый клиент из очереди без сети шлёт «sat», получает 409, а следом «arrived»).
    С приёмки PR174 «Доехал» до отметки водителя — 409: близким не уходит «доехал до места»."""
    _drv, pax, _ride, bid = _trip(client, user_factory)
    r = client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"], json={"status": "arrived"})
    assert r.status_code == 409 and r.json()["detail"]["ba"].strip(), r.text
    assert _status(client, pax, bid) == "confirmed"


def test_boarding_does_not_skip_driver_confirmation(client, user_factory):
    """Посадку до подтверждения брони не изобретаем: pending остаётся pending."""
    drv, pax, _ride, bid = _trip(client, user_factory, confirm=False)
    assert _board(client, drv, bid).status_code == 409
    assert _status(client, pax, bid) == "pending"


def test_boarding_days_before_departure_is_refused(client, user_factory):
    """Отметка за два дня — не посадка: пауза водителя (она не трогает «человек в машине»)
    оставила бы пассажира «в пути» на отменённом рейсе. Окно то же, что было у «Я сел»."""
    drv, pax, _ride, bid = _trip(client, user_factory, depart_in=timedelta(days=2))
    r = _board(client, drv, bid)
    assert r.status_code == 409, r.text
    assert "30 минут" in r.json()["detail"]["ru"] and r.json()["detail"]["ba"].strip()
    assert _status(client, pax, bid) == "confirmed"
    assert _status(client, drv, bid) == "confirmed"
    # Отменить бронь пассажир по-прежнему может (в Android кнопка есть только у confirmed).
    assert client.post(f"/bookings/{bid}/cancel", headers=pax["auth"], json={}).json()["status"] == "cancelled"


def test_boarding_never_reopens_a_finished_or_cancelled_booking(client, user_factory):
    """Запоздавшая отметка (очередь без сети) не возвращает «в пути» завершённую/отменённую бронь."""
    drv, pax, _ride, bid = _trip(client, user_factory)
    assert client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"],
                       json={"status": "done"}).status_code == 200
    assert _board(client, drv, bid).status_code == 409
    assert _status(client, pax, bid) == "done"

    drv2, pax2, _ride2, bid2 = _trip(client, user_factory)
    assert client.post(f"/bookings/{bid2}/cancel", headers=pax2["auth"], json={}).status_code == 200
    assert _board(client, drv2, bid2).status_code == 409
    assert _status(client, pax2, bid2) == "cancelled"


def test_onboard_booking_closes_as_done_with_its_past_ride(client, user_factory):
    """Ночная уборка: рейс, где пассажир сидел в машине, — состоявшийся (done), а не expired,
    и его бронь закрывается done, а не висит «в пути» вечно."""
    drv, pax, ride, bid = _trip(client, user_factory)
    with Session(engine) as s:   # «прошло 16 часов» — вручную, через API время не отмотать
        r = s.get(Ride, ride["id"])
        r.depart_at = utcnow() - timedelta(hours=RIDE_CLOSE_GRACE_HOURS + 10)
        s.add(r)
        # Посадку ставим прямо: проверяем уборку, а не ручку (её проверяют тесты выше).
        b = s.get(Booking, bid)
        b.status = BookingStatus.onboard
        s.add(b)
        s.commit()
    close_past_rides()
    with Session(engine) as s:
        assert s.get(Ride, ride["id"]).status == RideStatus.done
        assert s.exec(select(Booking).where(Booking.id == bid)).one().status == BookingStatus.done
