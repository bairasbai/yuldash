"""Опции мест в машине (2026-10-14): «сзади максимум двое», «переднее свободно», «хочу переднее».

Что стережём:
* по умолчанию у новой поездки 4 места (1 спереди + 3 сзади), а не 3 как раньше;
* «сзади максимум двое» → мест не больше 3 (и 2, если переднее занято своим) — режет сервер;
* переднее место достаётся ОДНОЙ живой брони на поездку, даже когда двое жмут одновременно;
* отмена, отказ водителя и неявка переднее освобождают;
* водитель видит «просит переднее», пассажир — «переднее место» в своей брони.
"""
import os
import threading
from datetime import timedelta

import pytest
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, UserRole
from app.timeutil import utcnow

_N = {"i": 0}


def _ride(client, drv, depart_at="2030-01-01T10:00:00", **extra):
    # Уникальный комментарий: одинаковые объявления подряд схлопываются как двойной тап.
    _N["i"] += 1
    body = {"from_city": "Баймак", "to_city": "Сибай", "depart_at": depart_at,
            "price": 300, "comment": f"места-{_N['i']}", **extra}
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _book(client, pax, ride_id, **extra):
    return client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": 1, **extra})


def _public(client, ride_id):
    r = client.get(f"/rides/{ride_id}")
    assert r.status_code == 200, r.text
    return r.json()


# ----------------------------- водитель: опции и места -----------------------------

def test_new_ride_gets_four_seats_by_default(client, user_factory):
    """Обычная легковая — 1 спереди + 3 сзади. Раньше по умолчанию было 3 (решение Александра)."""
    drv = user_factory("SeatDefDrv", role=UserRole.driver)
    ride = _ride(client, drv)
    assert ride["seats_total"] == 4 and ride["seats_left"] == 4
    # Опции по умолчанию — «как было»: сзади без ограничения, переднее свободно.
    pub = _public(client, ride["id"])
    assert pub["back_max_two"] is False
    assert pub["front_seat_available"] is True
    assert pub["front_seat_taken"] is False


@pytest.mark.parametrize("front, cap", [(True, 3), (False, 2)])
def test_back_max_two_caps_the_seats(client, user_factory, front, cap):
    """«Сзади максимум двое»: мест не больше 3, а если переднее занято своим — не больше 2."""
    drv = user_factory("SeatCapDrv", role=UserRole.driver)
    ride = _ride(client, drv, seats_total=6, back_max_two=True, front_seat_available=front)
    assert ride["seats_total"] == cap and ride["seats_left"] == cap
    pub = _public(client, ride["id"])
    assert pub["back_max_two"] is True and pub["front_seat_available"] is front
    # Меньше потолка — не трогаем: водитель вправе взять одного.
    one = _ride(client, drv, seats_total=1, back_max_two=True, front_seat_available=front)
    assert one["seats_total"] == 1


def test_without_back_max_two_minivan_keeps_eight(client, user_factory):
    drv = user_factory("SeatVanDrv", role=UserRole.driver)
    assert _ride(client, drv, seats_total=8)["seats_total"] == 8


def test_edit_turns_options_on_and_trims_seats(client, user_factory):
    """Правка без броней: включил «сзади двое» на 4-местной → стало 3; закрыл переднее → 2."""
    drv = user_factory("SeatEditDrv", role=UserRole.driver)
    rid = _ride(client, drv)["id"]
    r = client.post(f"/rides/{rid}/edit", headers=drv["auth"], json={"back_max_two": True})
    assert r.status_code == 200, r.text
    assert r.json()["back_max_two"] is True and r.json()["seats_total"] == 3 and r.json()["seats_left"] == 3
    r = client.post(f"/rides/{rid}/edit", headers=drv["auth"], json={"front_seat_available": False})
    assert r.status_code == 200, r.text
    assert r.json()["front_seat_available"] is False and r.json()["seats_total"] == 2
    # Явная просьба больше потолка тоже режется до него.
    r = client.post(f"/rides/{rid}/edit", headers=drv["auth"], json={"seats_total": 5})
    assert r.status_code == 200 and r.json()["seats_total"] == 2


def test_edit_does_not_take_back_a_promise(client, user_factory):
    """При живых бронях обещанное не забираем: переднее, которое попросили, не закрыть,
    «сзади двое» не снять. Открыть переднее и пообещать «сзади двое» — можно."""
    drv = user_factory("SeatPromDrv", role=UserRole.driver)
    pax = user_factory("SeatPromPax")
    rid = _ride(client, drv, seats_total=3, back_max_two=True)["id"]
    assert _book(client, pax, rid, front_seat=True).status_code == 200
    r = client.post(f"/rides/{rid}/edit", headers=drv["auth"], json={"front_seat_available": False})
    assert r.status_code == 409 and r.json()["detail"]["ba"]
    r = client.post(f"/rides/{rid}/edit", headers=drv["auth"], json={"back_max_two": False})
    assert r.status_code == 409 and r.json()["detail"]["ba"]
    pub = _public(client, rid)
    assert pub["front_seat_available"] is True and pub["back_max_two"] is True

    # «Сзади двое» при живой брони включить можно — если забронированное помещается.
    rid2 = _ride(client, drv)["id"]
    pax2 = user_factory("SeatPromPax2")
    assert _book(client, pax2, rid2).status_code == 200
    r = client.post(f"/rides/{rid2}/edit", headers=drv["auth"], json={"back_max_two": True})
    assert r.status_code == 200, r.text
    assert r.json()["seats_total"] == 3 and r.json()["seats_left"] == 2


# ----------------------------- пассажир: переднее место -----------------------------

def test_front_seat_goes_to_one_booking_only(client, user_factory):
    drv = user_factory("FrontDrv", role=UserRole.driver)
    first, second = user_factory("FrontPax1"), user_factory("FrontPax2")
    # Свой город отправления: в общем прогоне у «Баймака» сотни поездок, и наша уехала бы
    # за пределы страницы ленты.
    rid = _ride(client, drv, from_city="Переднесельск")["id"]

    r = _book(client, first, rid, front_seat=True)
    assert r.status_code == 200, r.text
    assert r.json()["front_seat"] is True
    assert _public(client, rid)["front_seat_taken"] is True
    feed = [x for x in client.get("/rides", params={"from_city": "Переднесельск"}).json() if x["id"] == rid]
    assert feed and feed[0]["front_seat_taken"] is True

    r = _book(client, second, rid, front_seat=True)
    assert r.status_code == 409
    assert r.json()["detail"] == {"ru": "Переднее место уже занято", "ba": "Алғы урын инде алынған"}
    # Отказ мест не съел: свободно 3 из 4, и без переднего второй едет спокойно.
    assert _public(client, rid)["seats_left"] == 3
    r = _book(client, second, rid)
    assert r.status_code == 200 and r.json()["front_seat"] is False
    assert _public(client, rid)["seats_left"] == 2


def test_front_seat_not_offered_by_driver(client, user_factory):
    drv = user_factory("FrontOffDrv", role=UserRole.driver)
    pax = user_factory("FrontOffPax")
    rid = _ride(client, drv, front_seat_available=False)["id"]
    r = _book(client, pax, rid, front_seat=True)
    assert r.status_code == 409 and r.json()["detail"]["ba"]
    assert _public(client, rid)["seats_left"] == 4


def test_two_seat_booking_may_ask_for_the_front(client, user_factory):
    """Мама с сыном берут два места и просят переднее — это одна бронь, ей можно."""
    drv = user_factory("FrontTwoDrv", role=UserRole.driver)
    pax = user_factory("FrontTwoPax")
    rid = _ride(client, drv)["id"]
    r = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 2, "front_seat": True})
    assert r.status_code == 200, r.text
    assert r.json()["front_seat"] is True and r.json()["seats"] == 2


@pytest.mark.parametrize("how", ["passenger_cancels", "driver_declines", "no_show"])
def test_front_seat_is_freed(client, user_factory, how):
    drv = user_factory(f"FreeDrv-{how}", role=UserRole.driver)
    pax, next_pax = user_factory(f"FreePax-{how}"), user_factory(f"FreeNext-{how}")
    # Неявку отмечают по уже начавшейся поездке — выезд минуту назад.
    depart = (utcnow() - timedelta(minutes=1)).replace(microsecond=0).isoformat() + "+00:00"
    rid = _ride(client, drv, depart_at=depart)["id"]
    bid = _book(client, pax, rid, front_seat=True).json()["id"]
    if how == "passenger_cancels":
        r = client.post(f"/bookings/{bid}/cancel", headers=pax["auth"], json={})
    elif how == "driver_declines":
        r = client.post(f"/bookings/{bid}/cancel", headers=drv["auth"], json={})
    else:
        assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
        r = client.post(f"/bookings/{bid}/no-show", headers=drv["auth"])
    assert r.status_code == 200, r.text
    assert _public(client, rid)["front_seat_taken"] is False
    r = _book(client, next_pax, rid, front_seat=True)
    assert r.status_code == 200, r.text


def test_both_sides_see_the_front_seat(client, user_factory):
    drv = user_factory("SeeDrv", role=UserRole.driver)
    pax = user_factory("SeePax")
    rid = _ride(client, drv)["id"]
    bid = _book(client, pax, rid, front_seat=True).json()["id"]
    mine = [b for b in client.get("/driver/bookings", headers=drv["auth"]).json() if b["booking_id"] == bid]
    assert mine and mine[0]["front_seat"] is True                     # «просит переднее место»
    for who in (pax, drv):
        d = client.get(f"/bookings/{bid}/details", headers=who["auth"]).json()
        assert d["front_seat"] is True
    own = [b for b in client.get("/bookings/mine", headers=pax["auth"]).json() if b["id"] == bid]
    assert own and own[0]["front_seat"] is True


def test_repeat_with_front_seat_is_not_silently_dropped(client, user_factory):
    """Уже есть бронь без переднего, повтор с «хочу переднее» — не молчаливый возврат старой
    брони (просьба потерялась бы), а понятный отказ. Повтор без просьбы — та же бронь, как раньше."""
    drv = user_factory("RepeatDrv", role=UserRole.driver)
    pax = user_factory("RepeatPax")
    rid = _ride(client, drv)["id"]
    bid = _book(client, pax, rid).json()["id"]
    r = _book(client, pax, rid, front_seat=True)
    assert r.status_code == 409 and r.json()["detail"]["ba"]
    assert _book(client, pax, rid).json()["id"] == bid
    assert _public(client, rid)["front_seat_taken"] is False


def test_edit_with_same_seat_count_and_back_two_passes_with_bookings(client, user_factory):
    """Клиент шлёт форму целиком (то же число мест) + «сзади двое» при живой брони — это не
    правка мест, лишнее срезает потолок."""
    drv = user_factory("SameSeatsDrv", role=UserRole.driver)
    pax = user_factory("SameSeatsPax")
    rid = _ride(client, drv)["id"]
    assert _book(client, pax, rid).status_code == 200
    r = client.post(f"/rides/{rid}/edit", headers=drv["auth"], json={"seats_total": 4, "back_max_two": True})
    assert r.status_code == 200, r.text
    assert r.json()["seats_total"] == 3 and r.json()["seats_left"] == 2


def test_database_barrier_answers_like_the_check(client, user_factory, monkeypatch):
    """Предпроверку обошли (как при гонке на SQLite, где FOR UPDATE не работает): вторую бронь
    отсекает база, человек получает тот же отказ, а списанное место возвращается."""
    from app.routers import bookings as bookings_router
    monkeypatch.setattr(bookings_router, "_front_seat_is_taken", lambda *_a, **_k: False)
    drv = user_factory("BarrierDrv", role=UserRole.driver)
    p1, p2 = user_factory("BarrierPax1"), user_factory("BarrierPax2")
    rid = _ride(client, drv)["id"]
    assert _book(client, p1, rid, front_seat=True).status_code == 200
    r = _book(client, p2, rid, front_seat=True)
    assert r.status_code == 409
    assert r.json()["detail"] == {"ru": "Переднее место уже занято", "ba": "Алғы урын инде алынған"}
    assert _public(client, rid)["seats_left"] == 3


# ----------------------------- барьер базы и миграция -----------------------------

def test_database_itself_refuses_a_second_live_front_seat(client, user_factory):
    """Барьер в самой базе, мимо проверки в коде: вторая живая бронь с передним не ляжет,
    а отменённая рядом с живой — ляжет (индекс частичный)."""
    drv = user_factory("DbDrv", role=UserRole.driver)
    p1, p2, p3 = user_factory("DbPax1"), user_factory("DbPax2"), user_factory("DbPax3")
    rid = _ride(client, drv)["id"]
    with Session(engine) as s:
        s.add(Booking(ride_id=rid, passenger_id=p1["id"], front_seat=True, status=BookingStatus.confirmed))
        s.commit()
        s.add(Booking(ride_id=rid, passenger_id=p2["id"], front_seat=True, status=BookingStatus.cancelled))
        s.commit()
        s.add(Booking(ride_id=rid, passenger_id=p3["id"], front_seat=True, status=BookingStatus.pending))
        with pytest.raises(IntegrityError):
            s.commit()
        s.rollback()


def test_migration_and_model_share_the_barrier_condition():
    from pathlib import Path

    from app.models import FRONT_SEAT_LIVE_WHERE
    text = (Path(__file__).resolve().parents[1] / "alembic" / "versions" / "bw_seat_options_20261014.py").read_text(encoding="utf-8")
    assert f'WHERE = "{FRONT_SEAT_LIVE_WHERE}"' in text
    for column in ("back_max_two", "front_seat_available", "front_seat"):
        assert f'"{column}"' in text, f"колонка {column} не названа в ревизии"


def test_migration_upgrades_an_old_database_and_back(tmp_path):
    """Старая база без новых колонок: ревизия добавляет их со значениями «как было» и индекс;
    повторный прогон — no-op; откат снимает всё."""
    import importlib.util
    from pathlib import Path

    from alembic.migration import MigrationContext
    from alembic.operations import Operations
    from sqlalchemy import create_engine, inspect, text

    path = Path(__file__).resolve().parents[1] / "alembic" / "versions" / "bw_seat_options_20261014.py"
    spec = importlib.util.spec_from_file_location("bw_seat_options_mig", path)
    mig = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mig)
    eng = create_engine(f"sqlite:///{(tmp_path / 'old.db').as_posix()}")
    with eng.begin() as c:
        c.execute(text("CREATE TABLE ride (id INTEGER PRIMARY KEY, seats_total INTEGER)"))
        c.execute(text("CREATE TABLE booking (id INTEGER PRIMARY KEY, ride_id INTEGER, status VARCHAR)"))
        c.execute(text("INSERT INTO ride VALUES (1, 3)"))
        c.execute(text("INSERT INTO booking VALUES (1, 1, 'confirmed'), (2, 1, 'pending')"))
        with Operations.context(MigrationContext.configure(c)):
            mig.upgrade()
            mig.upgrade()   # идемпотентно
        assert c.execute(text("SELECT back_max_two, front_seat_available FROM ride")).one() == (0, 1)
        assert c.execute(text("SELECT COUNT(*) FROM booking WHERE front_seat")).scalar_one() == 0
        assert mig.INDEX in {ix["name"] for ix in inspect(c).get_indexes("booking")}
        c.execute(text("UPDATE booking SET front_seat = 1 WHERE id = 1"))
        with pytest.raises(IntegrityError):
            c.execute(text("UPDATE booking SET front_seat = 1 WHERE id = 2"))
    with eng.begin() as c:
        with Operations.context(MigrationContext.configure(c)):
            mig.downgrade()
        cols = {x["name"] for x in inspect(c).get_columns("booking")}
        assert "front_seat" not in cols
        assert "back_max_two" not in {x["name"] for x in inspect(c).get_columns("ride")}


# ----------------------------- гонка на PostgreSQL -----------------------------

@pytest.mark.skipif(
    not os.environ.get("DATABASE_URL", "").startswith("postgres"),
    reason="Гонка двух нажатий проверяется на Postgres (FOR UPDATE + индекс). "
           "Запуск: DATABASE_URL=postgresql://... pytest tests/test_seat_options.py",
)
def test_front_seat_race_has_one_winner(client, user_factory):
    """Восемь человек жмут «Хочу переднее» одновременно — переднее получает ровно один,
    остальным понятный отказ, и ни одно место не потерялось."""
    drv = user_factory("RaceDrv", role=UserRole.driver)
    rid = _ride(client, drv, seats_total=8)["id"]
    paxs = [user_factory(f"RacePax{i}") for i in range(8)]
    results: list = []
    lock = threading.Lock()
    start = threading.Barrier(len(paxs))

    def attempt(p):
        start.wait()
        r = _book(client, p, rid, front_seat=True)
        with lock:
            results.append((r.status_code, r.json()))

    threads = [threading.Thread(target=attempt, args=(p,)) for p in paxs]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    codes = [c for c, _ in results]
    assert codes.count(200) == 1, f"переднее должно достаться одному, получили {codes}"
    assert codes.count(409) == 7, codes
    assert all(body["detail"]["ru"] == "Переднее место уже занято" for c, body in results if c == 409)
    assert _public(client, rid)["seats_left"] == 7   # списано ровно одно место
