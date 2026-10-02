"""Поломки без теста из раздела 5.2 независимого ревью — два правила, для которых в
существующем наборе не нашлось теста, точно ловящего именно эту поломку (а не более
широкий случай «чужой вообще»)."""
from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, DriverProfile, Ride, RideStatus, TrustedContact, UserRole
from app.timeutil import utcnow


def _live_booking(pax_id: int, drv_id: int) -> int:
    with Session(engine) as s:
        s.add(DriverProfile(user_id=drv_id))
        ride = Ride(driver_id=drv_id, from_city="Сибай", to_city="Уфа", depart_at=utcnow(),
                   seats_total=3, seats_left=2, price=500, status=RideStatus.active)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=pax_id, seats=1, price=500,
                   status=BookingStatus.onboard)
        s.add(b)
        s.commit()
        s.refresh(b)
        return b.id


def test_зимний_протокол_не_зовёт_контакт_которому_не_расшарили(client, user_factory, monkeypatch):
    """Пассажир завёл доверенный контакт, но НЕ расшарил ЭТУ конкретную поездку — звать его
    нельзя: шаринг и есть сигнал «меня ждут», просто наличие контакта в адресной книге — нет."""
    sent = []
    monkeypatch.setattr("app.routers.safety.push_bilingual", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.safety.send_text", lambda phone, text: sent.append((phone, text)))
    drv = user_factory("GapDrv1", role=UserRole.driver)
    pax = user_factory("GapPax1")
    bid = _live_booking(pax["id"], drv["id"])
    client.post("/trusted-contacts", headers=pax["auth"],
                json={"name": "Незашаренная мама", "relation": "мама", "phone": "+79990001111"})

    from datetime import timedelta
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.winter_check_sent_at = utcnow() - timedelta(minutes=45)
        s.add(b)
        s.commit()
    r = client.post(f"/bookings/{bid}/winter-check", headers=pax["auth"])
    assert r.json()["state"] == "no_share", (
        "контакт заведён, но поездка ему не расшарена — звать некого, это не то же самое, "
        "что «звать всех, кто вообще есть в адресной книге»"
    )
    assert not any(p == "+79990001111" for p, _ in sent), (
        "незашаренный контакт получил SMS — зимний протокол позвонил не тому, кому доверились"
    )


def test_доехал_может_отметить_только_пассажир_не_водитель(client, user_factory):
    """Волна 160: кнопку «я доехала» жмёт ТОЛЬКО пассажир (тот, кого ждут дома) — водитель
    той же поездки, хоть он и участник, не может погасить тревогу за неё."""
    drv = user_factory("GapDrv2", role=UserRole.driver)
    pax = user_factory("GapPax2")
    bid = _live_booking(pax["id"], drv["id"])

    r = client.post(f"/bookings/{bid}/winter-check/ok", headers=drv["auth"])
    assert r.status_code == 403, (
        "водитель смог отметить «пассажир доехала» вместо самой пассажирки"
    )
