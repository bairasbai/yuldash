"""Лента на главной считает только поездки, которые состоялись (решение Александра 23.10.2026).

Раньше «Сегодня N поездок», «За месяц», «За год» считали ВСЕ брони за период — вместе с
отменёнными, отклонёнными и теми, что так и не дождались водителя. «Хит недели» считал
опубликованные поездки, в том числе отменённые, а «N водителей за рулём» — всех, кто хоть раз
публиковал поездку. Человек видел цифры больше настоящих.

Теперь поездка «состоялась», если водитель отметил посадку (`onboard`) или поездка завершена
(`done`). Период — по времени выезда поездки. База в тестах общая на всю сессию, поэтому
сравниваем приросты, а не абсолютные числа.
"""
from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, Ride, RideStatus, User, UserRole
from app.timeutil import utcnow

from test_flows import _publish


def _feed(client, monkeypatch):
    monkeypatch.setattr("app.routers.discovery.cache_get_json", lambda _key: None)
    monkeypatch.setattr("app.routers.discovery.cache_set_json", lambda *_a, **_k: None)
    r = client.get("/feed")
    assert r.status_code == 200, r.text
    return r.json()


def _ride_departed_hour_ago(client, driver, frm, to):
    ride_id = _publish(client, driver, frm=frm, to=to, seats=4)["id"]
    with Session(engine) as s:
        ride = s.get(Ride, ride_id)
        ride.depart_at = utcnow() - timedelta(hours=1)
        s.add(ride)
        s.commit()
    return ride_id


def _add_bookings(ride_id, passengers, statuses):
    with Session(engine) as s:
        for pax, status in zip(passengers, statuses):
            s.add(Booking(ride_id=ride_id, passenger_id=pax["id"], seats=1, price=300, status=status))
        s.commit()


def test_feed_counts_only_boarded_and_done_trips(client, user_factory, monkeypatch):
    before = _feed(client, monkeypatch)

    driver = user_factory("ЛентаВодитель", role=UserRole.driver)
    paxes = [user_factory(f"ЛентаПассажир{i}") for i in range(5)]
    ride_id = _ride_departed_hour_ago(client, driver, "ЛентаГородА", "ЛентаГородБ")
    _add_bookings(ride_id, paxes, [
        BookingStatus.done, BookingStatus.onboard,          # состоялись
        BookingStatus.cancelled, BookingStatus.pending,     # не состоялись
        BookingStatus.confirmed,                            # ещё не сели
    ])

    # Второй водитель: его единственная бронь отменена — «за рулём» он не считается.
    idle_driver = user_factory("ЛентаВодительБезПоездок", role=UserRole.driver)
    idle_ride = _ride_departed_hour_ago(client, idle_driver, "ЛентаГородВ", "ЛентаГородГ")
    _add_bookings(idle_ride, [user_factory("ЛентаПассажирОтмена")], [BookingStatus.cancelled])

    after = _feed(client, monkeypatch)

    assert after["today"] - before["today"] == 2
    assert after["week"] - before["week"] == 2
    assert after["month"] - before["month"] == 2
    assert after["year"] - before["year"] == 2
    assert after["drivers"] - before["drivers"] == 1


def test_top_route_ignores_cancelled_rides(client, user_factory, monkeypatch):
    # Пятьдесят отменённых поездок по одному маршруту — это не «хит недели».
    driver = user_factory("ЛентаСпамер", role=UserRole.driver)
    template_id = _publish(client, driver, frm="ЛентаОтмена", to="ЛентаНикуда")["id"]
    with Session(engine) as s:
        template = s.get(Ride, template_id)
        template.status = RideStatus.cancelled
        s.add(template)
        for _ in range(50):
            clone = Ride.model_validate(template.model_dump(exclude={"id"}))
            clone.status = RideStatus.cancelled
            s.add(clone)
        s.commit()

    top = _feed(client, monkeypatch)["top_route"]

    assert top is None or (top["from_city"], top["to_city"]) != ("ЛентаОтмена", "ЛентаНикуда")


def test_popular_routes_ignore_cancelled_rides(client, user_factory, monkeypatch):
    """«Популярно · Здесь часто ездят» — тоже не из отменённых поездок (ревью Opus 23.10)."""
    driver = user_factory("ПопулярСпамер", role=UserRole.driver)
    template_id = _publish(client, driver, frm="ПопулярОтмена", to="ПопулярНикуда")["id"]
    with Session(engine) as s:
        template = s.get(Ride, template_id)
        template.status = RideStatus.cancelled
        s.add(template)
        for _ in range(60):
            clone = Ride.model_validate(template.model_dump(exclude={"id"}))
            clone.status = RideStatus.cancelled
            s.add(clone)
        s.commit()
    monkeypatch.setattr("app.routers.discovery.cache_get_json", lambda _key: None)
    monkeypatch.setattr("app.routers.discovery.cache_set_json", lambda *_a, **_k: None)

    routes = client.get("/popular-routes").json()

    assert ("ПопулярОтмена", "ПопулярНикуда") not in [(r["from_city"], r["to_city"]) for r in routes]


def test_popular_routes_count_distinct_drivers(monkeypatch):
    """Один водитель с десятками активных поездок не делает свой маршрут «популярным» (приёмка PR210):
    маршрут, где ездят трое разных водителей, выше маршрута одного водителя с 60 поездками.

    Своя чистая база: в общей тестовой топ-6 занят маршрутами сотен тестовых водителей."""
    from sqlmodel import SQLModel, create_engine

    from app.routers.discovery import popular_routes

    monkeypatch.setattr("app.routers.discovery.cache_get_json", lambda _key: None)
    monkeypatch.setattr("app.routers.discovery.cache_set_json", lambda *_a, **_k: None)
    own = create_engine("sqlite://")
    SQLModel.metadata.create_all(own)
    when = utcnow() + timedelta(days=1)
    with Session(own) as s:
        for driver_id in range(1, 6):
            s.add(User(id=driver_id, phone=f"+7900000000{driver_id}", role=UserRole.driver))
        s.commit()
        for _ in range(60):
            s.add(Ride(driver_id=1, from_city="Накрутка", to_city="Себе", depart_at=when))
        for driver_id in (2, 3, 4):
            s.add(Ride(driver_id=driver_id, from_city="Честный", to_city="Маршрут", depart_at=when))
        s.add(Ride(driver_id=5, from_city="Снятый", to_city="Маршрут", depart_at=when, status=RideStatus.cancelled))
        s.commit()

        routes = [(r["from_city"], r["to_city"], r["count"]) for r in popular_routes(session=s)]

    assert routes == [("Честный", "Маршрут", 3), ("Накрутка", "Себе", 1)]


def test_landing_counts_happened_trips_and_drivers_who_drove(client, user_factory, monkeypatch):
    """Лендинг yulbash.ru — те же честные числа, что главная приложения (ревью Opus 23.10)."""
    def stats():
        monkeypatch.setattr("app.routers.discovery.cache_get_json", lambda _key: None)
        monkeypatch.setattr("app.routers.discovery.cache_set_json", lambda *_a, **_k: None)
        rows = client.get("/landing-stats").json()
        return {r["ru"]: int(r["value"].rstrip("+")) for r in rows if r["value"].rstrip("+").isdigit()}

    # Порог «пре-запуска» — 15 опубликованных поездок; в общей тестовой базе их заведомо больше.
    for i in range(16):
        _publish(client, user_factory(f"ЛендингФон{i}", role=UserRole.driver))
    before = stats()

    driver = user_factory("ЛендингВодитель", role=UserRole.driver)
    paxes = [user_factory(f"ЛендингПассажир{i}") for i in range(3)]
    ride_id = _ride_departed_hour_ago(client, driver, "ЛендингА", "ЛендингБ")
    _add_bookings(ride_id, paxes, [BookingStatus.done, BookingStatus.cancelled, BookingStatus.pending])
    idle = user_factory("ЛендингБезПоездок", role=UserRole.driver)
    _ride_departed_hour_ago(client, idle, "ЛендингВ", "ЛендингГ")

    after = stats()
    assert after["поездок за месяц"] - before["поездок за месяц"] == 1
    assert after["водителей за месяц"] - before["водителей за месяц"] == 1
