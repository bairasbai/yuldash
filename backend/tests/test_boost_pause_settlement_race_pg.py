"""Регрессии Boost на разных PostgreSQL-соединениях; SQLite не доказывает замки."""
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Barrier, Event, local
from time import monotonic, sleep

import pytest
from sqlalchemy import event, text
from sqlmodel import Session, select

from app import safety_logic, services
from app.db import engine
from app.models import Payment, Ride, RideStatus, UserRole
from app.routers import payments, rides

from test_boost_pause_settlement_20261024 import (
    activate, boost_clock, cascade, make_payment, make_ride, put_credit, put_pause, snapshot,
)  # noqa: F401 — boost_clock используется как pytest-фикстура

pytestmark = [
    pytest.mark.skipif(engine.dialect.name != "postgresql", reason="нужны настоящие замки PostgreSQL"),
    pytest.mark.timeout(60),
]


@pytest.fixture(autouse=True)
def bounded_database_waits():
    def begin(connection):
        connection.exec_driver_sql("SET LOCAL lock_timeout = '6s'")
        connection.exec_driver_sql("SET LOCAL statement_timeout = '12s'")
    event.listen(engine, "begin", begin)
    try:
        yield
    finally:
        event.remove(engine, "begin", begin)


def _wait(signal):
    assert signal.wait(10), "запрос не дошёл до проверяемой границы"


def _wait_for_database_lock(pid):
    """Не отпускаем первый запрос, пока второй действительно не ждёт замка в PostgreSQL."""
    deadline = monotonic() + 5
    with Session(engine) as session:
        while monotonic() < deadline:
            waiting = session.execute(text(
                "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = :pid AND NOT granted)"
            ), {"pid": pid}).scalar_one()
            if waiting:
                return
            sleep(0.02)
    pytest.fail("второй запрос не дошёл до ожидания замка PostgreSQL")


def _boost(ride_id, until):
    with Session(engine) as session:
        ride = session.get(Ride, ride_id)
        ride.boosted_until = until
        session.add(ride)
        session.commit()


def test_two_distinct_paid_invoices_add_both_periods_from_stale_ride(client, user_factory, boost_clock):
    now, _ = boost_clock
    driver = user_factory("Два разных оплаченных счёта", role=UserRole.driver)
    ride_id = make_ride(client, driver, now)
    _boost(ride_id, now + timedelta(hours=4))
    payment_ids = [make_payment(driver["id"], ride_id) for _ in range(2)]
    ready = Barrier(2)

    def settle(payment_id):
        with Session(engine) as session:
            payment = session.get(Payment, payment_id)
            stale_ride = session.get(Ride, ride_id)  # держим старую копию в identity map до активации
            assert stale_ride.boosted_until == now + timedelta(hours=4)
            pid = session.connection().exec_driver_sql("SELECT pg_backend_pid()").scalar_one()
            ready.wait(10)
            payments._activate_payment(session, payment)
            return pid

    with ThreadPoolExecutor(max_workers=2) as workers:
        futures = [workers.submit(settle, pid) for pid in payment_ids]
        assert len({f.result(20) for f in futures}) == 2
    assert snapshot(driver["id"], ride_id) == (
        0, None, RideStatus.active, now + timedelta(hours=4 + 2 * payments.BOOST_PLANS["quick"][2]),
    )


@pytest.mark.parametrize("first", ("payment", "cancel"))
def test_paid_activation_and_cancel_keep_all_hours(client, user_factory, boost_clock, monkeypatch, first):
    now, _ = boost_clock
    driver = user_factory("Одновременная оплата и снятие", role=UserRole.driver)
    ride_id = make_ride(client, driver, now)
    _boost(ride_id, now + timedelta(hours=6))
    put_credit(driver["id"], 3600, now + timedelta(days=2))
    payment_id = make_payment(driver["id"], ride_id)
    held, release = Event(), Event()
    state = local()
    original_now = payments.utcnow
    original_stash = rides.stash_boost_credit

    def payment_now():
        if getattr(state, "payment_worker", False):
            state.calls += 1
            if first == "payment" and state.calls == 2:  # уже под Ride-lock, перед записью часов
                held.set()
                _wait(release)
        return original_now()

    def stash(*args, **kwargs):
        moved = original_stash(*args, **kwargs)
        if first == "cancel":
            held.set()
            _wait(release)
        return moved

    monkeypatch.setattr(payments, "utcnow", payment_now)
    monkeypatch.setattr(rides, "stash_boost_credit", stash)

    def settle():
        state.payment_worker, state.calls = True, 0
        activate(payment_id)

    def cancel():
        response = client.post(f"/rides/{ride_id}/cancel", headers=driver["auth"])
        assert response.status_code == 200, response.text

    with ThreadPoolExecutor(max_workers=2) as workers:
        started = workers.submit(settle if first == "payment" else cancel)
        try:
            _wait(held)
            other = workers.submit(cancel if first == "payment" else settle)
        finally:
            release.set()
        started.result(20)
        other.result(20)
    assert snapshot(driver["id"], ride_id) == (
        int((7 + payments.BOOST_PLANS["quick"][2]) * 3600),
        now + timedelta(days=services.BOOST_CARRY_DAYS), RideStatus.cancelled, None,
    )


@pytest.mark.parametrize("first", ("payment", "pause"))
def test_late_activation_and_pause_extend_same_credit(client, user_factory, boost_clock, monkeypatch, first):
    now, _ = boost_clock
    driver = user_factory("Поздняя оплата одновременно с паузой", role=UserRole.driver)
    ride_id = make_ride(client, driver, now)
    payment_id = make_payment(driver["id"], ride_id)
    response = client.post(f"/rides/{ride_id}/cancel", headers=driver["auth"])
    assert response.status_code == 200, response.text
    put_credit(driver["id"], 3600, now + timedelta(days=2))
    end = now + timedelta(days=7)
    held, release = Event(), Event()
    real_add, real_extend = payments.add_boost_credit, services.extend_boost_credit_until

    def add(*args, **kwargs):
        moved = real_add(*args, **kwargs)
        if first == "payment":
            held.set()
            _wait(release)
        return moved

    def extend(*args, **kwargs):
        changed = real_extend(*args, **kwargs)
        if first == "pause":
            held.set()
            _wait(release)
        return changed

    monkeypatch.setattr(payments, "add_boost_credit", add)
    monkeypatch.setattr(services, "extend_boost_credit_until", extend)
    if first == "pause":
        put_pause(driver["id"], end)
    with ThreadPoolExecutor(max_workers=2) as workers:
        started = workers.submit(activate, payment_id) if first == "payment" else workers.submit(cascade, driver["id"])
        try:
            _wait(held)
            if first == "payment":
                put_pause(driver["id"], end)
                other = workers.submit(cascade, driver["id"])
            else:
                other = workers.submit(activate, payment_id)
        finally:
            release.set()
        started.result(20)
        other.result(20)
    assert snapshot(driver["id"], ride_id) == (
        3600 + int(payments.BOOST_PLANS["quick"][2] * 3600),
        end + timedelta(days=services.BOOST_CARRY_DAYS), RideStatus.cancelled, None,
    )


@pytest.mark.parametrize("first", ("publish", "pause"))
def test_publication_and_pause_do_not_leave_future_active_ride(client, user_factory, boost_clock, monkeypatch, first):
    now, _ = boost_clock
    driver = user_factory("Публикация и настоящая пауза", role=UserRole.driver)
    old_id = make_ride(client, driver, now, comment="старый рейс")
    _boost(old_id, now + timedelta(hours=6))
    put_credit(driver["id"], 2 * 3600, now + timedelta(days=2))
    # Первый гейт уже загрузит этот профиль в identity map публикации.
    put_pause(driver["id"], now - timedelta(days=1))
    publish_ready, pause_ready, release = Event(), Event(), Event()
    pause_pid = []
    real_guard = rides.ensure_active
    real_publish_gate = rides.lock_driver_ride_publication
    real_pause_gate = safety_logic.lock_driver_ride_publication

    def guard(session, user_id, **kwargs):
        result = real_guard(session, user_id, **kwargs)
        if first == "publish" and kwargs.get("fresh"):
            publish_ready.set()  # уже прошёл проверку, но ещё не вставил новый рейс
            _wait(release)
        return result

    def publish_gate(session, user_id):
        if first == "pause":
            publish_ready.set()  # первоначальная проверка пройдена до записи паузы
            _wait(release)
        return real_publish_gate(session, user_id)

    def pause_gate(session, user_id):
        pause_pid.append(session.connection().exec_driver_sql("SELECT pg_backend_pid()").scalar_one())
        pause_ready.set()
        return real_pause_gate(session, user_id)

    monkeypatch.setattr(rides, "ensure_active", guard)
    monkeypatch.setattr(rides, "lock_driver_ride_publication", publish_gate)
    monkeypatch.setattr(safety_logic, "lock_driver_ride_publication", pause_gate)

    def publish():
        return client.post("/rides", headers=driver["auth"], json={
            "from_city": "Уфа", "to_city": "Сибай", "price": 500, "seats_total": 3,
            "depart_at": (now + timedelta(hours=10)).isoformat() + "+00:00",
        })

    with ThreadPoolExecutor(max_workers=2) as workers:
        publication = workers.submit(publish)
        try:
            _wait(publish_ready)
            end = now + timedelta(days=7)
            put_pause(driver["id"], end)
            paused = workers.submit(cascade, driver["id"])
            _wait(pause_ready)
            if first == "pause":
                paused.result(20)
            else:
                # Без общего барьера каскад успевал взять снимок без новой Ride и ждал только User.
                # Теперь он ждёт до снимка. В обоих вариантах отпускаем публикацию ровно после ожидания.
                _wait_for_database_lock(pause_pid[0])
        finally:
            release.set()
        result = publication.result(20)
        paused.result(20)
    assert result.status_code == (200 if first == "publish" else 403), result.text
    with Session(engine) as session:
        remaining = session.exec(select(Ride).where(
            Ride.driver_id == driver["id"], Ride.status == RideStatus.active, Ride.depart_at > now,
        )).all()
        assert remaining == [], "неподтверждённый снимок каскада пропустил новый рейс"
    assert snapshot(driver["id"], old_id) == (
        8 * 3600, end + timedelta(days=services.BOOST_CARRY_DAYS), RideStatus.cancelled, None,
    )


def test_initially_zero_credit_is_refreshed_after_waiting_for_cancel(client, user_factory, boost_clock, monkeypatch):
    now, _ = boost_clock
    driver = user_factory("Первый остаток во время публикации", role=UserRole.driver)
    old_id = make_ride(client, driver, now)
    _boost(old_id, now + timedelta(hours=6))
    held, publication_ready, release = Event(), Event(), Event()
    real_stash = rides.stash_boost_credit
    real_gate = rides.lock_driver_ride_publication

    def stash(*args, **kwargs):
        moved = real_stash(*args, **kwargs)
        held.set()
        _wait(release)
        return moved

    def gate(*args, **kwargs):
        publication_ready.set()  # current_user уже прочитал нулевой остаток
        return real_gate(*args, **kwargs)

    monkeypatch.setattr(rides, "stash_boost_credit", stash)
    monkeypatch.setattr(rides, "lock_driver_ride_publication", gate)
    with ThreadPoolExecutor(max_workers=2) as workers:
        cancelled = workers.submit(client.post, f"/rides/{old_id}/cancel", headers=driver["auth"])
        try:
            _wait(held)
            publication = workers.submit(make_ride, client, driver, now, comment="новая поездка")
            _wait(publication_ready)
        finally:
            release.set()
        assert cancelled.result(20).status_code == 200
        new_id = publication.result(20)
    assert snapshot(driver["id"], new_id) == (0, None, RideStatus.active, now + timedelta(hours=6))
