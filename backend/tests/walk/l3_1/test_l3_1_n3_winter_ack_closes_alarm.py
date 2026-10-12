"""N3 (независимое ревью): «доехал(а)» должно закрывать уже отправленную тревогу зимнего
протокола, а не оставлять её висеть открытой на час эскалации.

Было: `winter_escalate.escalate_now` заводит `SosEvent(status="open")`. `_winter_ack`
(«я доехал(а)») ставил только `winter_check_ack_at` и это событие НЕ закрывал. Эскалатор
(`sos_escalate.escalate_unhandled`) не умеет отличать зимнюю тревогу от обычного SOS — видит
`status=="open"` и продолжает слать дежурному «⏰ SOS НЕ ПРИНЯТ» каждые 10 минут ЕЩЁ ЦЕЛЫЙ ЧАС
после того, как человек уже сам отметился и опасности нет.

Правка: `_winter_ack` закрывает (`status="handled"`) открытое событие ЭТОГО объекта по тому
же префиксу note, которым `escalate_now` защищается от повторной отправки.
"""
from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, BookingStatus, DriverProfile, Ride, RideStatus, SosEvent, UserRole
from app.timeutil import utcnow
from app import sos_escalate


def _booking_with_sent_winter_check(pax_id: int, drv_id: int) -> int:
    """Бронь, по которой зимний протокол УЖЕ спросил «доехал?» и УЖЕ позвал близких
    (имитируем прямую запись события — как это делает `winter_escalate.escalate_now`)."""
    with Session(engine) as s:
        ride = Ride(driver_id=drv_id, from_city="Сибай", to_city="Уфа",
                   depart_at=utcnow(), seats_total=3, seats_left=2, price=500,
                   status=RideStatus.active)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=pax_id, seats=1, price=500,
                   status=BookingStatus.onboard, winter_check_sent_at=utcnow())
        s.add(b)
        s.commit()
        s.refresh(b)
        bid = b.id
    from app.winter_escalate import _winter_note
    with Session(engine) as s:
        s.add(SosEvent(user_id=pax_id, booking_id=bid, category="other",
                       note=_winter_note("booking", bid)))
        s.commit()
    return bid


def test_доехал_закрывает_открытую_зимнюю_тревогу(client, user_factory):
    drv = user_factory("N3Drv1", role=UserRole.driver)
    pax = user_factory("N3Pax1")
    bid = _booking_with_sent_winter_check(pax["id"], drv["id"])

    with Session(engine) as s:
        alarm = s.exec(select(SosEvent).where(SosEvent.booking_id == bid)).first()
        assert alarm.status == "open", "контроль: тревога действительно открыта"

    r = client.post(f"/bookings/{bid}/winter-check/ok", headers=pax["auth"])
    assert r.status_code == 200, r.text

    with Session(engine) as s:
        alarm = s.exec(select(SosEvent).where(SosEvent.booking_id == bid)).first()
        assert alarm.status == "handled", (
            "человек отметился сам, а тревога осталась открытой — дежурный ещё час получит "
            "«SOS НЕ ПРИНЯТ» по уже решённому случаю"
        )


def test_закрытая_тревога_эскалатору_больше_не_попадается(client, user_factory):
    """Сквозная проверка: после «доехал(а)» `escalate_unhandled` (sos_escalate.py) эту
    тревогу больше не берёт, даже если формально прошёл порог ожидания."""
    from datetime import timedelta
    drv = user_factory("N3Drv2", role=UserRole.driver)
    pax = user_factory("N3Pax2")
    bid = _booking_with_sent_winter_check(pax["id"], drv["id"])
    client.post(f"/bookings/{bid}/winter-check/ok", headers=pax["auth"])

    with Session(engine) as s:
        alarm = s.exec(select(SosEvent).where(SosEvent.booking_id == bid)).first()
        alarm.created_at = utcnow() - timedelta(minutes=20)   # старше порога sos_escalate_after_min
        s.add(alarm)
        s.commit()
        escalated = sos_escalate.escalate_unhandled(s)
        assert alarm.id not in escalated, "закрытая тревога не должна попадать в повтор эскалации"


def test_доехал_без_тревоги_по_прежнему_идемпотентен(client, user_factory):
    """Контроль обратной стороны: если тревоги ещё не было (протокол не спрашивал), «доехал»
    просто ставит отметку и не падает."""
    drv = user_factory("N3Drv3", role=UserRole.driver)
    pax = user_factory("N3Pax3")
    with Session(engine) as s:
        ride = Ride(driver_id=drv["id"], from_city="Сибай", to_city="Уфа",
                   depart_at=utcnow(), seats_total=3, seats_left=2, price=500,
                   status=RideStatus.active)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=pax["id"], seats=1, price=500,
                   status=BookingStatus.onboard)
        s.add(b)
        s.commit()
        bid = b.id

    r1 = client.post(f"/bookings/{bid}/winter-check/ok", headers=pax["auth"])
    r2 = client.post(f"/bookings/{bid}/winter-check/ok", headers=pax["auth"])
    assert r1.status_code == 200 and r2.status_code == 200
