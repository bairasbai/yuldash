"""F8 «Бейджи и стаж своего»: вычисляемые бейджи доверия водителя (агрегаты, без новых таблиц).

Проверяем контракт RideOut:
  - driver_trips  — сколько поездок водитель РЕАЛЬНО завершил (distinct done-броней),
  - driver_since  — месяц регистрации "YYYY-MM" (бейдж «С нами с …»),
  - новый водитель НЕ получает ложных бейджей (trips=0).

«Земляк» и «Отвечает быстро» осознанно НЕ реализованы:
  - «Земляк» требует города пользователя, а в модели User/DriverProfile города нет → честно пропущено.
  - «Отвечает быстро» требует времени подтверждения брони, а confirmed_at не хранится → честно пропущено.
"""
from datetime import datetime

from app.models import UserRole


def _publish(client, drv, frm="Баймак", to="Сибай", seats=3, price=300, **extra):
    body = {"from_city": frm, "to_city": to, "depart_at": "2030-01-01T10:00:00",
            "seats_total": seats, "price": price, **extra}
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _book(client, pax, ride_id, seats=1):
    r = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": seats})
    assert r.status_code == 200, r.text
    return r.json()


def _finish(client, drv, booking_id):
    """Водитель завершает поездку → booking.status=done (ride остаётся active)."""
    r = client.post(f"/bookings/{booking_id}/driver-status", headers=drv["auth"], json={"status": "done"})
    assert r.status_code == 200, r.text


def _ride_from(client, from_city):
    rides = client.get("/rides", params={"from_city": from_city}).json()
    assert rides, f"нет поездок из {from_city}"
    return rides[0]


# ----------------------------- контракт полей -----------------------------
def test_ride_out_has_badge_fields(client, user_factory):
    drv = user_factory("BadgeShape", role=UserRole.driver)
    _publish(client, drv, frm="ФормаГрад", to="Сибай")
    ride = _ride_from(client, "ФормаГрад")
    assert "driver_trips" in ride and "driver_since" in ride
    assert ride["driver_trips"] == 0                     # ещё ничего не завершено


# ----------------------------- «N поездок» -----------------------------
def test_trips_badge_counts_completed_trips(client, user_factory):
    drv = user_factory("TripsDrv", role=UserRole.driver)
    pax = user_factory("TripsPax")
    ride = _publish(client, drv, frm="СтажГрад", to="Сибай")
    booking = _book(client, pax, ride["id"])
    # до завершения — 0 (бронь есть, но поездка не завершена)
    assert _ride_from(client, "СтажГрад")["driver_trips"] == 0
    _finish(client, drv, booking["id"])
    assert _ride_from(client, "СтажГрад")["driver_trips"] == 1


def test_trips_badge_distinct_by_ride_not_passengers(client, user_factory):
    """Двое пассажиров в ОДНОЙ поездке → это 1 поездка, не 2 (distinct по ride)."""
    drv = user_factory("DistinctDrv", role=UserRole.driver)
    p1 = user_factory("DistinctP1")
    p2 = user_factory("DistinctP2")
    ride = _publish(client, drv, frm="ДистинктГрад", to="Сибай", seats=4)
    b1 = _book(client, p1, ride["id"])
    b2 = _book(client, p2, ride["id"])
    _finish(client, drv, b1["id"])
    _finish(client, drv, b2["id"])
    assert _ride_from(client, "ДистинктГрад")["driver_trips"] == 1


def test_trips_badge_two_rides_counts_two(client, user_factory):
    drv = user_factory("TwoDrv", role=UserRole.driver)
    pax = user_factory("TwoPax")
    r1 = _publish(client, drv, frm="ДваГрад", to="Сибай")
    b1 = _book(client, pax, r1["id"])
    _finish(client, drv, b1["id"])
    r2 = _publish(client, drv, frm="ДваГрад", to="Уфа")
    b2 = _book(client, pax, r2["id"])
    _finish(client, drv, b2["id"])
    assert _ride_from(client, "ДваГрад")["driver_trips"] == 2


# ----------------------------- «С нами с …» -----------------------------
def test_member_since_is_registration_month(client, user_factory):
    drv = user_factory("SinceDrv", role=UserRole.driver)
    _publish(client, drv, frm="СНамиГрад", to="Сибай")
    since = _ride_from(client, "СНамиГрад")["driver_since"]
    assert since == datetime.utcnow().strftime("%Y-%m")   # только что зарегистрирован → текущий месяц


# ----------------------------- новый водитель без ложных бейджей -----------------------------
def test_new_driver_no_false_trip_badge(client, user_factory):
    """Свежий водитель без завершённых поездок: trips=0 (бейдж «N поездок» не покажется),
    а driver_since — правдивый (не выдумка)."""
    drv = user_factory("FreshDrv", role=UserRole.driver)
    _publish(client, drv, frm="НовичокГрад", to="Сибай")
    ride = _ride_from(client, "НовичокГрад")
    assert ride["driver_trips"] == 0
    assert ride["driver_since"]            # непустой — честный факт регистрации
