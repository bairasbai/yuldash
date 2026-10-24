"""Оплата после снятия рейса и срок прежнего остатка на паузе — без новых тарифов."""
from datetime import timedelta

import pytest
from fastapi import HTTPException
from sqlalchemy import event, update
from sqlmodel import Session, select

from app import safety_logic, services
from app.db import engine
from app.models import Payment, Ride, RideStatus, SafetyProfile, User, UserRole
from app.routers import payments, rides
from app.timeutil import utcnow

pytestmark = pytest.mark.timeout(60)


@pytest.fixture
def boost_clock(monkeypatch):
    now = utcnow().replace(microsecond=0)
    for module in (payments, rides, safety_logic):
        monkeypatch.setattr(module, "utcnow", lambda: now)
    notes = []
    monkeypatch.setattr(services, "push_notification", lambda *a, **k: notes.append((a, k)))
    return now, notes


def make_ride(client, driver, now, *, comment=""):
    response = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "price": 500, "seats_total": 3,
        "depart_at": (now + timedelta(hours=8)).isoformat() + "+00:00", "comment": comment,
    })
    assert response.status_code == 200, response.text
    return response.json()["id"]


def put_credit(driver_id, seconds, until):
    with Session(engine) as session:
        driver = session.get(User, driver_id)
        driver.boost_credit_sec, driver.boost_credit_until = seconds, until
        session.add(driver)
        session.commit()


def put_pause(driver_id, until):
    with Session(engine) as session:
        profile = session.exec(select(SafetyProfile).where(SafetyProfile.user_id == driver_id)).first()
        if profile is None:
            profile = SafetyProfile(user_id=driver_id)
        profile.suspended_until, profile.standing = until, "suspended"
        session.add(profile)
        session.commit()


def make_payment(driver_id, ride_id, *, tier="quick"):
    with Session(engine) as session:
        payment = Payment(user_id=driver_id, ride_id=ride_id, purpose="boost", tier=tier,
                          amount_kop=payments.BOOST_PLANS[tier][1])
        session.add(payment)
        session.commit()
        session.refresh(payment)
        return payment.id


def activate(payment_id):
    with Session(engine) as session:
        payments._activate_payment(session, session.get(Payment, payment_id))


def cascade(driver_id):
    with Session(engine) as session:
        return safety_logic.cancel_upcoming_after_suspension(session, driver_id)


def snapshot(driver_id, ride_id=None):
    with Session(engine) as session:
        driver = session.get(User, driver_id)
        ride = session.get(Ride, ride_id) if ride_id else None
        return (driver.boost_credit_sec, driver.boost_credit_until,
                ride.status if ride else None, ride.boosted_until if ride else None)


@pytest.mark.parametrize("cancel_by_admin", (False, True))
@pytest.mark.parametrize("until_days", (2, 100))
def test_late_payment_after_cancel_keeps_paid_hours_once(client, user_factory, boost_clock, cancel_by_admin, until_days):
    now, notes = boost_clock
    driver = user_factory("Поздний Boost водитель", role=UserRole.driver)
    admin = user_factory("Поздний Boost дежурный", role=UserRole.admin)
    ride_id = make_ride(client, driver, now)
    put_credit(driver["id"], 3600, now + timedelta(days=until_days))
    payment_id = make_payment(driver["id"], ride_id)
    actor = admin if cancel_by_admin else driver
    response = client.post(f"/rides/{ride_id}/cancel", headers=actor["auth"])
    assert response.status_code == 200, response.text

    activate(payment_id)
    expected = 3600 + int(payments.BOOST_PLANS["quick"][2] * 3600)
    saved = snapshot(driver["id"], ride_id)
    assert saved == (expected, now + timedelta(days=max(until_days, services.BOOST_CARRY_DAYS)),
                     RideStatus.cancelled, None)
    activate(payment_id)
    assert snapshot(driver["id"], ride_id) == saved
    assert snapshot(admin["id"])[0] == 0
    assert len(notes) == 1, "повтор оплаты не шлёт второе уведомление"
    args, _ = notes[0]
    assert "следующей поездки" in args[5] and "поднято в ленте" not in args[5]
    assert args[6].strip() and args[6] != args[5]
    with Session(engine) as session:
        payment = session.get(Payment, payment_id)
        assert payment.status == "succeeded" and payment.settled_at == now


def test_late_payment_after_pause_uses_current_pause_end(client, user_factory, boost_clock):
    now, _ = boost_clock
    driver = user_factory("Оплата после паузы", role=UserRole.driver)
    ride_id = make_ride(client, driver, now)
    payment_id = make_payment(driver["id"], ride_id)
    end = now + timedelta(days=7)
    put_pause(driver["id"], end)
    assert cascade(driver["id"]) == 0  # рейс без броней тоже снят и сохранён
    activate(payment_id)
    assert snapshot(driver["id"], ride_id) == (
        int(payments.BOOST_PLANS["quick"][2] * 3600),
        end + timedelta(days=services.BOOST_CARRY_DAYS), RideStatus.cancelled, None,
    )


def test_late_payment_failure_keeps_status_and_credit_in_one_transaction(client, user_factory, boost_clock, monkeypatch):
    now, _ = boost_clock
    driver = user_factory("Оплата откатывается вместе с остатком", role=UserRole.driver)
    ride_id = make_ride(client, driver, now)
    payment_id = make_payment(driver["id"], ride_id)
    assert client.post(f"/rides/{ride_id}/cancel", headers=driver["auth"]).status_code == 200
    real_add = payments.add_boost_credit

    def failed_add(*args, **kwargs):
        real_add(*args, **kwargs)
        raise RuntimeError("тестовый сбой до общего commit")

    monkeypatch.setattr(payments, "add_boost_credit", failed_add)
    with pytest.raises(RuntimeError, match="тестовый сбой"):
        activate(payment_id)
    assert snapshot(driver["id"], ride_id) == (0, None, RideStatus.cancelled, None)
    with Session(engine) as session:
        payment = session.get(Payment, payment_id)
        assert payment.status == "pending" and payment.settled_at is None
    monkeypatch.setattr(payments, "add_boost_credit", real_add)
    activate(payment_id)
    assert snapshot(driver["id"], ride_id)[0] == int(payments.BOOST_PLANS["quick"][2] * 3600)


def test_active_paid_boost_still_adds_to_existing_period(client, user_factory, boost_clock):
    now, notes = boost_clock
    driver = user_factory("Обычная оплаченная поездка", role=UserRole.driver)
    ride_id = make_ride(client, driver, now)
    with Session(engine) as session:
        ride = session.get(Ride, ride_id)
        ride.boosted_until = now + timedelta(hours=4)
        session.add(ride)
        session.commit()
    payment_id = make_payment(driver["id"], ride_id)
    activate(payment_id)
    assert snapshot(driver["id"], ride_id) == (
        0, None, RideStatus.active, now + timedelta(hours=4 + payments.BOOST_PLANS["quick"][2]),
    )
    assert "объявление поднято в ленте" in notes[0][0][5]


@pytest.mark.parametrize("until_days", (-1, 2, 100))
def test_pause_extends_only_live_credit_without_any_ride(user_factory, boost_clock, until_days):
    now, _ = boost_clock
    driver = user_factory("Прежний остаток без рейсов", role=UserRole.driver)
    before = now + timedelta(days=until_days)
    put_credit(driver["id"], 7200, before)
    end = now + timedelta(days=7)
    put_pause(driver["id"], end)
    assert cascade(driver["id"]) == 0
    expected_until = before if until_days < 0 else max(before, end + timedelta(days=services.BOOST_CARRY_DAYS))
    assert snapshot(driver["id"])[:2] == (7200, expected_until)


def test_second_pause_extends_existing_credit_without_minting_seconds(user_factory, boost_clock):
    now, _ = boost_clock
    driver = user_factory("Продление паузы", role=UserRole.driver)
    put_credit(driver["id"], 7200, now + timedelta(days=1))
    longest = 0
    for days in (3, 20, 7):
        put_pause(driver["id"], now + timedelta(days=days))
        cascade(driver["id"])
        longest = max(longest, days)
        assert snapshot(driver["id"])[:2] == (7200, now + timedelta(days=longest + services.BOOST_CARRY_DAYS))


def test_fresh_pause_guard_ignores_stale_profile_and_does_not_commit(user_factory, boost_clock):
    now, _ = boost_clock
    driver = user_factory("Свежая пауза без commit", role=UserRole.driver)
    put_pause(driver["id"], now - timedelta(days=1))
    with Session(engine) as session:
        profile = session.exec(select(SafetyProfile).where(SafetyProfile.user_id == driver["id"])).one()
        old = profile.suspended_until
        session.execute(update(SafetyProfile).where(SafetyProfile.id == profile.id).values(
            suspended_until=now + timedelta(days=7)).execution_options(synchronize_session=False))
        assert profile.suspended_until == old
        commits = []
        event.listen(session, "after_commit", lambda _session: commits.append(True))
        with pytest.raises(HTTPException) as refusal:
            safety_logic.ensure_active(session, driver["id"], fresh=True)
        assert refusal.value.status_code == 403
        assert commits == [], "проверка не отпускает уже взятые замки"
        session.rollback()
