"""«Я сел» — это посадка, а не только сообщение маме.

Найдено сквозной проверкой волны 1 (13.10.2026, `tests/journeys/`). Пассажир нажимал «Я сел»,
сервер передавал это только близким, с которыми поделились поездкой. Если близких в списке не
было — сервер не записывал вообще ничего, бронь до самого конца оставалась «подтверждена»:
* водитель не видел, что человек уже в машине;
* переоткрытое приложение снова звало «Я сел» (Android и PWA ждут от сервера `onboard`);
* у мамы по ссылке до конца поездки висело «Машина едет к точке посадки».

Статус `onboard` у брони существовал, его знали все клиенты, ссылка близкого, чат и живая
позиция — но ни одна ручка попутки его не ставила.
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


def test_sat_without_trusted_contacts_puts_booking_onboard(client, user_factory):
    """Главный случай: близких нет — посадка всё равно записана, и её видят обе стороны."""
    drv, pax, _ride, bid = _trip(client, user_factory)
    r = client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"], json={"status": "sat"})
    assert r.status_code == 200 and r.json() == []
    assert _status(client, pax, bid) == "onboard"
    assert _status(client, drv, bid) == "onboard"
    details = client.get(f"/bookings/{bid}/details", headers=pax["auth"]).json()
    assert details["status"] == "onboard" and details["contact_unlocked"] is True


def test_arrived_also_means_onboard(client, user_factory):
    """«Доехал» без отдельного «Я сел» — человек тем более был в машине."""
    _drv, pax, _ride, bid = _trip(client, user_factory)
    assert client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"],
                       json={"status": "arrived"}).status_code == 200
    assert _status(client, pax, bid) == "onboard"


def test_sat_does_not_skip_driver_confirmation(client, user_factory):
    """Посадку до подтверждения водителем не изобретаем: pending остаётся pending."""
    _drv, pax, _ride, bid = _trip(client, user_factory, confirm=False)
    assert client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"],
                       json={"status": "sat"}).status_code == 200
    assert _status(client, pax, bid) == "pending"


def test_sat_days_before_departure_is_not_boarding(client, user_factory):
    """Кнопку «Я сел» видно сразу после подтверждения. Нажатие за два дня — не посадка:
    иначе пауза водителя (она не трогает «человек в машине») оставила бы пассажира «в пути»
    на отменённом рейсе. Близким сообщение уходит, как и раньше."""
    drv, pax, _ride, bid = _trip(client, user_factory, depart_in=timedelta(days=2))
    contact = client.post("/trusted-contacts", headers=pax["auth"], json={"name": "Мама", "phone": ""})
    assert contact.status_code == 200, contact.text
    share = client.post(f"/bookings/{bid}/share", headers=pax["auth"], json={"contact_id": contact.json()["id"]})
    assert share.status_code == 200, share.text
    r = client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"], json={"status": "sat"})
    assert r.status_code == 200 and [s["last_status"] for s in r.json()] == ["sat"]
    assert _status(client, pax, bid) == "confirmed"
    assert _status(client, drv, bid) == "confirmed"
    # Отменить бронь пассажир по-прежнему может (в Android кнопка есть только у confirmed).
    assert client.post(f"/bookings/{bid}/cancel", headers=pax["auth"], json={}).json()["status"] == "cancelled"


def test_sat_never_reopens_a_finished_or_cancelled_booking(client, user_factory):
    """Запоздавшее «Я сел» (очередь без сети) не возвращает «в пути» завершённую/отменённую бронь."""
    drv, pax, _ride, bid = _trip(client, user_factory)
    assert client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"],
                       json={"status": "done"}).status_code == 200
    assert client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"],
                       json={"status": "sat"}).status_code == 200
    assert _status(client, pax, bid) == "done"

    _drv2, pax2, _ride2, bid2 = _trip(client, user_factory)
    assert client.post(f"/bookings/{bid2}/cancel", headers=pax2["auth"], json={}).status_code == 200
    assert client.post(f"/bookings/{bid2}/trip-status", headers=pax2["auth"],
                       json={"status": "sat"}).status_code == 200
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
