"""«Сказать рәхмәт» (чаевые водителю): бесплатный жест + денежные чаевые «на доверии» (opt-in, за флагом).

Проверяем: водитель включает/выключает СБП (только водитель, валидный номер); денежная часть скрыта,
пока tips_money_enabled=False; показывается при флаге ON И opt-in водителя; бесплатное «рәхмәт» шлёт
пуш водителю и идемпотентно (дедуп); только пассажир завершённой поездки.
"""
from app.db import engine
from app.models import Booking, BookingStatus, DriverProfile, Ride, UserRole
from app.routers import family as family_router
from app.timeutil import utcnow
from sqlmodel import Session, select


def _driver_profile(uid):
    with Session(engine) as s:
        if not s.exec(select(DriverProfile).where(DriverProfile.user_id == uid)).first():
            s.add(DriverProfile(user_id=uid))
            s.commit()


def _done_booking(passenger_id, driver_id, status=BookingStatus.done):
    with Session(engine) as s:
        ride = Ride(driver_id=driver_id, from_city="A", to_city="B", depart_at=utcnow())
        s.add(ride)
        s.commit()
        s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=passenger_id, status=status)
        s.add(b)
        s.commit()
        s.refresh(b)
        return b.id


# ---------------------------- opt-in водителя ----------------------------
def test_set_and_clear_tips_sbp(client, user_factory):
    drv = user_factory("TipDrv", role=UserRole.driver)
    _driver_profile(drv["id"])
    r = client.post("/me/tips-sbp", headers=drv["auth"], json={"sbp": "+79991234567"})
    assert r.status_code == 200 and r.json()["accepting"] is True
    r2 = client.post("/me/tips-sbp", headers=drv["auth"], json={"sbp": ""})
    assert r2.status_code == 200 and r2.json()["accepting"] is False


def test_tips_sbp_requires_driver_profile(client, user_factory):
    pax = user_factory("TipNoProf")   # без DriverProfile
    assert client.post("/me/tips-sbp", headers=pax["auth"], json={"sbp": "+79991234567"}).status_code == 403


def test_tips_sbp_bad_number(client, user_factory):
    drv = user_factory("TipBad", role=UserRole.driver)
    _driver_profile(drv["id"])
    assert client.post("/me/tips-sbp", headers=drv["auth"], json={"sbp": "abc"}).status_code == 400


# ---------------------------- денежная часть за флагом ----------------------------
def test_money_hidden_when_flag_off(client, user_factory):
    """По умолчанию tips_money_enabled=False → реквизит СБП пассажиру НЕ отдаём (только бесплатное рәхмәт)."""
    drv = user_factory("MoffDrv", role=UserRole.driver)
    _driver_profile(drv["id"])
    pax = user_factory("MoffPax")
    client.post("/me/tips-sbp", headers=drv["auth"], json={"sbp": "+79990000001"})
    bid = _done_booking(pax["id"], drv["id"])
    r = client.get(f"/bookings/{bid}/tip", headers=pax["auth"])
    assert r.status_code == 200 and r.json()["money"] is None


def test_money_shown_when_flag_on_and_opted_in(client, user_factory, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "tips_money_enabled", True)
    drv = user_factory("MonDrv", role=UserRole.driver)
    _driver_profile(drv["id"])
    pax = user_factory("MonPax")
    client.post("/me/tips-sbp", headers=drv["auth"], json={"sbp": "+79990000002"})
    bid = _done_booking(pax["id"], drv["id"])
    money = client.get(f"/bookings/{bid}/tip", headers=pax["auth"]).json()["money"]
    assert money and money["sbp"] == "+79990000002"


def test_money_null_if_driver_not_opted_in(client, user_factory, monkeypatch):
    """Флаг ON, но водитель СБП не указал → money=null (opt-in обязателен)."""
    from app.config import settings
    monkeypatch.setattr(settings, "tips_money_enabled", True)
    drv = user_factory("NoOptDrv", role=UserRole.driver)
    _driver_profile(drv["id"])
    pax = user_factory("NoOptPax")
    bid = _done_booking(pax["id"], drv["id"])
    assert client.get(f"/bookings/{bid}/tip", headers=pax["auth"]).json()["money"] is None


# ---------------------------- бесплатное «рәхмәт» ----------------------------
def test_thanks_pushes_driver_and_dedups(client, user_factory, monkeypatch):
    sent = []
    monkeypatch.setattr(family_router, "send_push",
                        lambda session, uid, title, body, *a, **k: sent.append(uid))
    drv = user_factory("ThDrv", role=UserRole.driver)
    pax = user_factory("ThPax")
    bid = _done_booking(pax["id"], drv["id"])
    r = client.post(f"/bookings/{bid}/thanks", headers=pax["auth"])
    assert r.status_code == 200 and r.json()["already"] is False
    assert drv["id"] in sent
    r2 = client.post(f"/bookings/{bid}/thanks", headers=pax["auth"])   # дедуп
    assert r2.json()["already"] is True
    assert sent.count(drv["id"]) == 1                                  # второй пуш не ушёл
    assert client.get(f"/bookings/{bid}/tip", headers=pax["auth"]).json()["already_thanked"] is True


def test_thanks_only_passenger_and_done(client, user_factory):
    drv = user_factory("XDrv", role=UserRole.driver)
    pax = user_factory("XPax")
    stranger = user_factory("XStr")
    bid = _done_booking(pax["id"], drv["id"])
    assert client.post(f"/bookings/{bid}/thanks", headers=stranger["auth"]).status_code == 403
    bid_open = _done_booking(pax["id"], drv["id"], status=BookingStatus.confirmed)
    assert client.post(f"/bookings/{bid_open}/thanks", headers=pax["auth"]).status_code == 409
