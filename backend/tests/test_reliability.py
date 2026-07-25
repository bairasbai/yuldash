"""«Надёжность» 0..100 (Справедливость, фаза 4): completed / (completed + failed) по терминальным
броням. Ключевое — защита оболганного: СЫРАЯ неявка Надёжность НЕ роняет, только ПОДТВЕРЖДЁННЫЙ
админом инцидент. Плюс хук: отмена проставляет cancelled_by; публичная витрина /users/{id}/trust.
"""
from app.db import engine
from app.models import Booking, BookingStatus, Incident, Ride, UserRole
from app.safety_logic import reliability_for
from app.timeutil import utcnow
from sqlmodel import Session


def _ride(driver_id, depart_at=None):
    with Session(engine) as s:
        r = Ride(driver_id=driver_id, from_city="A", to_city="B", depart_at=depart_at or utcnow())
        s.add(r); s.commit(); s.refresh(r)
        return r.id


def _booking(ride_id, passenger_id, status=BookingStatus.done, no_show=False, cancelled_by=None, cancelled_at=None):
    with Session(engine) as s:
        b = Booking(ride_id=ride_id, passenger_id=passenger_id, status=status, no_show=no_show,
                    cancelled_by=cancelled_by, cancelled_at=cancelled_at)
        s.add(b); s.commit(); s.refresh(b)
        return b.id


def test_new_user_reliability_100(client, user_factory):
    u = user_factory("RelNew")
    with Session(engine) as s:
        assert reliability_for(s, u["id"]) == 100
    assert client.get("/me/standing", headers=u["auth"]).json()["reliability"] == 100


def test_all_completed_reliability_100(client, user_factory):
    pax = user_factory("RelC"); drv = user_factory("RelCD", role=UserRole.driver)
    _booking(_ride(drv["id"]), pax["id"], status=BookingStatus.done)
    with Session(engine) as s:
        assert reliability_for(s, pax["id"]) == 100


def test_late_cancel_lowers_reliability(client, user_factory):
    pax = user_factory("RelLC"); drv = user_factory("RelLCD", role=UserRole.driver)
    _booking(_ride(drv["id"]), pax["id"], status=BookingStatus.done)                 # 1 completed
    rid2 = _ride(drv["id"], depart_at=utcnow())                                        # выезд «сейчас»
    _booking(rid2, pax["id"], status=BookingStatus.cancelled, cancelled_by=pax["id"], cancelled_at=utcnow())
    with Session(engine) as s:
        assert reliability_for(s, pax["id"]) == 50   # 1 / (1 + 1 поздняя отмена)


def test_unconfirmed_no_show_does_not_lower(client, user_factory):
    """Сырая неявка без инцидента — нейтральна (защита оболганного, оружие мести не работает)."""
    pax = user_factory("RelNS"); drv = user_factory("RelNSD", role=UserRole.driver)
    _booking(_ride(drv["id"]), pax["id"], status=BookingStatus.done)
    _booking(_ride(drv["id"]), pax["id"], status=BookingStatus.cancelled, no_show=True, cancelled_by=drv["id"])
    with Session(engine) as s:
        assert reliability_for(s, pax["id"]) == 100


def test_confirmed_no_show_lowers(client, user_factory):
    """Неявка, ПОДТВЕРЖДЁННАЯ resolved-инцидентом (вина на пассажире) → Надёжность падает."""
    pax = user_factory("RelCNS"); drv = user_factory("RelCNSD", role=UserRole.driver)
    _booking(_ride(drv["id"]), pax["id"], status=BookingStatus.done)
    bid = _booking(_ride(drv["id"]), pax["id"], status=BookingStatus.cancelled, no_show=True, cancelled_by=drv["id"])
    with Session(engine) as s:
        s.add(Incident(booking_id=bid, reporter_id=drv["id"], respondent_id=pax["id"],
                       type="passenger_no_show", status="resolved", fault="respondent"))
        s.commit()
    with Session(engine) as s:
        assert reliability_for(s, pax["id"]) == 50   # 1 / (1 + 1 подтверждённая неявка)


def test_cancel_endpoint_sets_cancelled_by(client, user_factory):
    pax = user_factory("HookPax"); drv = user_factory("HookDrv", role=UserRole.driver)
    bid = _booking(_ride(drv["id"]), pax["id"], status=BookingStatus.confirmed)
    assert client.post(f"/bookings/{bid}/cancel", headers=pax["auth"], json={"reason": "plans_changed"}).status_code == 200
    with Session(engine) as s:
        assert s.get(Booking, bid).cancelled_by == pax["id"]


def test_trust_endpoint_shape_and_404(client, user_factory):
    u = user_factory("TrustU")
    r = client.get(f"/users/{u['id']}/trust", headers=u["auth"])
    assert r.status_code == 200
    d = r.json()
    assert {"rating", "rating_count", "trips", "verified", "reliability", "member_since"}.issubset(d.keys())
    assert d["reliability"] == 100 and d["trips"] == 0
    assert client.get("/users/999999/trust", headers=u["auth"]).status_code == 404
