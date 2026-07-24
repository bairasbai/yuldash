"""G8 — Достижения-«пряники»: GET /me/achievements (тёплые бейджи из реальных данных).

Проверяем: новый юзер — только «Проверенный»; done-бронь даёт «Первая поездка»; 10 done-поездок
водителя → «10 поездок»; 5 доставленных посылок курьером → «Помог 5 посылкам»; стаж >1 года →
«Год с Юлдаш»; бейдж «Проверенный» отражает флаг. Цифры реальные (не фейк). Свои данные — свои.
"""
from datetime import timedelta

from app.db import engine
from app.models import Booking, BookingStatus, ParcelDelivery, Ride, RideStatus, User
from app.timeutil import utcnow
from sqlmodel import Session


def _get(client, user):
    r = client.get("/me/achievements", headers=user["auth"])
    assert r.status_code == 200, r.text
    return r.json()


def _earned(payload):
    return {b["code"] for b in payload["achievements"] if b["earned"]}


def test_new_user_only_verified(client, user_factory):
    """Дефолт (verified=True, 0 поездок, только создан) → только «Проверенный»."""
    u = user_factory("AchNew")
    earned = _earned(_get(client, u))
    assert "verified" in earned
    assert "first_trip" not in earned
    assert "year_with_yuldash" not in earned


def test_first_trip_after_done_booking(client, user_factory):
    u = user_factory("AchPax")
    drv = user_factory("AchRideOwner")
    with Session(engine) as s:
        ride = Ride(driver_id=drv["id"], from_city="A", to_city="B", depart_at=utcnow())
        s.add(ride)
        s.commit()
        s.refresh(ride)
        s.add(Booking(ride_id=ride.id, passenger_id=u["id"], status=BookingStatus.done))
        s.commit()
    out = _get(client, u)
    assert out["trips"] == 1
    assert "first_trip" in _earned(out)
    assert "trips_10" not in _earned(out)


def test_driver_ten_done_rides(client, user_factory):
    drv = user_factory("AchDrv10")
    with Session(engine) as s:
        for _ in range(10):
            s.add(Ride(driver_id=drv["id"], from_city="A", to_city="B",
                       depart_at=utcnow(), status=RideStatus.done))
        s.commit()
    out = _get(client, drv)
    assert out["trips"] == 10
    assert "trips_10" in _earned(out)
    assert "trips_50" not in _earned(out)


def test_parcel_helper_after_five_deliveries(client, user_factory):
    cour = user_factory("AchCourier")
    sender = user_factory("AchSender")
    with Session(engine) as s:
        for _ in range(5):
            s.add(ParcelDelivery(sender_id=sender["id"], courier_id=cour["id"], status="delivered"))
        s.commit()
    out = _get(client, cour)
    assert out["parcels_helped"] == 5
    assert "parcel_helper" in _earned(out)


def test_year_badge_from_created_at(client, user_factory):
    u = user_factory("AchYear")
    with Session(engine) as s:
        uu = s.get(User, u["id"])
        uu.created_at = utcnow() - timedelta(days=400)
        s.add(uu)
        s.commit()
    out = _get(client, u)
    assert out["days_with_yuldash"] >= 365
    assert "year_with_yuldash" in _earned(out)


def test_verified_badge_reflects_flag(client, user_factory):
    u = user_factory("AchUnver")
    with Session(engine) as s:
        uu = s.get(User, u["id"])
        uu.verified = False
        s.add(uu)
        s.commit()
    assert "verified" not in _earned(_get(client, u))


def test_progress_shown_for_unearned(client, user_factory):
    """Неполученный бейдж показывает прогресс (value/goal) — для полоски в UI."""
    u = user_factory("AchProg")
    badges = {b["code"]: b for b in _get(client, u)["achievements"]}
    assert badges["trips_10"]["earned"] is False
    assert badges["trips_10"]["goal"] == 10 and badges["trips_10"]["value"] == 0
