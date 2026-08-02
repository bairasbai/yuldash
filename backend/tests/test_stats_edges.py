"""F18 «Мой Юлдаш» — тесты личной статистики (GET /me/stats).

Проверяем: агрегаты (км/поездки/₽/CO₂) считаются; новый юзер = чистые нули без
ложного звания; приватность (каждый видит только свою статистику, аноним → 401).
"""
from app.config import settings
from app.models import Booking, BookingStatus, Ride, RideStatus, UserRole
from app.routers.stats import _rank_for
from app.services import haversine_km


def _mk_ride(session, driver_id, frm, to, f_lat, f_lng, t_lat, t_lng, status=RideStatus.done):
    from app.timeutil import utcnow
    r = Ride(
        driver_id=driver_id, from_city=frm, to_city=to,
        from_lat=f_lat, from_lng=f_lng, to_lat=t_lat, to_lng=t_lng,
        depart_at=utcnow(), seats_total=3, seats_left=3, price=300, status=status,
    )
    session.add(r)
    session.commit()
    session.refresh(r)
    return r


def test_stats_new_user_is_zeros_without_false_rank(client, user_factory):
    """Новый пользователь: всё по нулям, звание — стартовый уровень 0 (без «наград»)."""
    u = user_factory("StatsNewbie")
    r = client.get("/me/stats", headers=u["auth"])
    assert r.status_code == 200
    d = r.json()
    assert d["trips"] == 0
    assert d["km"] == 0
    assert d["saved_rub"] == 0
    assert d["co2_saved_kg"] == 0
    # Ложного звания быть не должно: уровень 0, до следующего ещё поездки.
    assert d["rank"]["level"] == 0
    assert d["rank"]["title_ru"] == "Новичок"
    assert d["rank"]["to_next"] >= 1
    assert d["rank"]["next_title_ru"] is not None


def test_stats_aggregates_passenger_and_driver(client, user_factory):
    """Поездки как пассажир (завершённые брони) + как водитель (завершённые поездки)
    складываются; км, ₽ и CO₂ считаются из коэффициентов config."""
    from sqlmodel import Session
    from app.db import engine

    driver = user_factory("StatsDriver", role=UserRole.driver)
    p = user_factory("StatsPassenger")

    # Дистанции считаем сами (haversine по тем же координатам) → ждём точное совпадение.
    km_pass = haversine_km(54.735, 55.958, 52.716, 58.664)   # Уфа→Сибай
    km_drive = haversine_km(52.591, 58.317, 52.716, 58.664)  # Баймак→Сибай
    with Session(engine) as s:
        ride_p = _mk_ride(s, driver["id"], "Уфа", "Сибай", 54.735, 55.958, 52.716, 58.664)
        # Завершённая бронь пассажира на эту поездку.
        s.add(Booking(ride_id=ride_p.id, passenger_id=p["id"], seats=1, price=300,
                      status=BookingStatus.done))
        # Пассажир заодно сам водитель другой завершённой поездки.
        _mk_ride(s, p["id"], "Баймак", "Сибай", 52.591, 58.317, 52.716, 58.664)
        s.commit()

    d = client.get("/me/stats", headers=p["auth"]).json()
    assert d["trips"] == 2   # 1 бронь пассажира + 1 своя поездка водителя
    expected_km = round(km_pass + km_drive, 1)
    assert d["km"] == expected_km
    assert d["saved_rub"] == round(expected_km * settings.stats_taxi_rub_per_km)
    assert d["co2_saved_kg"] == round(expected_km * settings.stats_co2_grams_per_km / 1000.0, 1)
    assert d["rank"]["level"] >= 1   # ≥1 поездки → уже не новичок


def test_stats_ignores_unfinished_trips(client, user_factory):
    """Незавершённые (pending/active) не попадают в агрегаты — считаем только состоявшееся."""
    from sqlmodel import Session
    from app.db import engine

    driver = user_factory("StatsDriver2", role=UserRole.driver)
    p = user_factory("StatsPassenger2")
    with Session(engine) as s:
        ride = _mk_ride(s, driver["id"], "Уфа", "Сибай", 54.735, 55.958, 52.716, 58.664,
                        status=RideStatus.active)
        s.add(Booking(ride_id=ride.id, passenger_id=p["id"], seats=1, price=300,
                      status=BookingStatus.pending))
        s.commit()
    d = client.get("/me/stats", headers=p["auth"]).json()
    assert d["trips"] == 0
    assert d["km"] == 0


def test_stats_privacy_only_own_data(client, user_factory):
    """Приватность: чужая статистика недоступна. B (новый) видит свои нули, не поездки A."""
    from sqlmodel import Session
    from app.db import engine

    a = user_factory("StatsOwnerA", role=UserRole.driver)
    b = user_factory("StatsOtherB")
    with Session(engine) as s:
        _mk_ride(s, a["id"], "Уфа", "Сибай", 54.735, 55.958, 52.716, 58.664)
        s.commit()

    da = client.get("/me/stats", headers=a["auth"]).json()
    db = client.get("/me/stats", headers=b["auth"]).json()
    assert da["trips"] == 1        # A видит свою поездку
    assert db["trips"] == 0        # B не видит поездку A
    assert db["km"] == 0


def test_stats_requires_auth(client):
    """Без токена — 401 (нельзя запросить чужую/обезличенную статистику)."""
    assert client.get("/me/stats").status_code == 401


def test_rank_thresholds():
    """Звания растут по числу поездок; 0 поездок = уровень 0, максимум без next."""
    assert _rank_for(0)["level"] == 0
    assert _rank_for(0)["to_next"] == 1
    assert _rank_for(1)["level"] == 1
    assert _rank_for(9)["level"] == 1
    assert _rank_for(10)["level"] == 2
    assert _rank_for(30)["level"] == 3
    top = _rank_for(1000)
    assert top["level"] == 4
    assert top["next_at"] is None
    assert top["to_next"] == 0
