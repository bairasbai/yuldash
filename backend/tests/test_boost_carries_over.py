"""Оплаченное поднятие не сгорает вместе с отменённой поездкой.

Водитель платит 50 ₽ за «День вверху», через три часа поездка срывается — и раньше деньги
просто оставались у нас: услуга не оказана, возврата нет, разговор с человеком неприятный.

Возврат через ЮKassa стоит комиссии платёжной системы и ручной работы, поэтому решили иначе:
остаток времени ложится водителю на счёт и сам применяется к следующей поездке. Автоматически —
он за рулём, ему не до выбора экранов. Срок жизни остатка 30 дней: бесконечный долг перед
водителем это бухгалтерия, которой у нас нет.
"""

from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.models import Ride, User
from app.services import apply_boost_credit, stash_boost_credit
from app.timeutil import utcnow


def _ride(boosted_until=None):
    return Ride(driver_id=1, from_city="Баймаҡ", to_city="Сибай", price=300,
                seats_total=3, seats_left=3, depart_at=utcnow() + timedelta(hours=5),
                boosted_until=boosted_until)


def _driver():
    return User(phone="+79990000001", name="Водитель")


def test_cancel_moves_the_remaining_time_to_the_driver():
    now = utcnow()
    driver, ride = _driver(), _ride(boosted_until=now + timedelta(hours=21))

    moved = stash_boost_credit(driver, ride, now)

    assert moved == 21 * 3600
    assert driver.boost_credit_sec == 21 * 3600
    assert driver.boost_credit_until > now
    assert ride.boosted_until is None      # на снятой поездке поднятие не действует


def test_cancel_without_boost_takes_nothing():
    now = utcnow()
    driver, ride = _driver(), _ride(boosted_until=None)

    assert stash_boost_credit(driver, ride, now) == 0
    assert driver.boost_credit_sec == 0


def test_expired_boost_is_not_carried():
    """Поднятие уже отработало своё — переносить нечего."""
    now = utcnow()
    driver, ride = _driver(), _ride(boosted_until=now - timedelta(minutes=1))

    assert stash_boost_credit(driver, ride, now) == 0
    assert driver.boost_credit_sec == 0


def test_two_cancellations_add_up():
    """Два оплаченных поднятия — два остатка, они складываются, а не затирают друг друга."""
    now = utcnow()
    driver = _driver()

    stash_boost_credit(driver, _ride(boosted_until=now + timedelta(hours=2)), now)
    stash_boost_credit(driver, _ride(boosted_until=now + timedelta(hours=3)), now)

    assert driver.boost_credit_sec == 5 * 3600


def test_stale_credit_is_not_resurrected_by_a_new_cancel():
    """Протухший остаток не оживает: 30 дней прошло — значит прошло."""
    now = utcnow()
    driver = _driver()
    driver.boost_credit_sec = 10 * 3600
    driver.boost_credit_until = now - timedelta(days=1)

    stash_boost_credit(driver, _ride(boosted_until=now + timedelta(hours=2)), now)

    assert driver.boost_credit_sec == 2 * 3600


def test_new_ride_takes_the_carried_boost_automatically():
    now = utcnow()
    driver, ride = _driver(), _ride()
    driver.boost_credit_sec = 4 * 3600
    driver.boost_credit_until = now + timedelta(days=10)

    used = apply_boost_credit(driver, ride, now)

    assert used == 4 * 3600
    assert ride.boosted_until is not None
    assert abs((ride.boosted_until - (now + timedelta(hours=4))).total_seconds()) < 2
    assert driver.boost_credit_sec == 0     # потрачен, второй раз не выдаётся
    assert driver.boost_credit_until is None


def test_carried_boost_extends_an_already_boosted_ride():
    """Поездку уже подняли за деньги — перенос добавляется сверху, а не затирает."""
    now = utcnow()
    driver, ride = _driver(), _ride(boosted_until=now + timedelta(hours=2))
    driver.boost_credit_sec = 3600
    driver.boost_credit_until = now + timedelta(days=10)

    apply_boost_credit(driver, ride, now)

    assert abs((ride.boosted_until - (now + timedelta(hours=3))).total_seconds()) < 2


def test_expired_credit_is_not_applied_and_gets_cleaned():
    now = utcnow()
    driver, ride = _driver(), _ride()
    driver.boost_credit_sec = 5 * 3600
    driver.boost_credit_until = now - timedelta(minutes=1)

    assert apply_boost_credit(driver, ride, now) == 0
    assert ride.boosted_until is None
    assert driver.boost_credit_sec == 0     # мусор не висит вечно


def test_full_path_cancel_then_publish(client, user_factory):
    """Живой путь: поднятая поездка снята → следующая выходит уже поднятой."""
    driver = user_factory("BoostCarryDriver")
    created = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Баймаҡ", "to_city": "Сибай", "price": 300, "seats_total": 3,
        "depart_at": (utcnow() + timedelta(hours=5)).isoformat(),
    })
    assert created.status_code == 200, created.text
    ride_id = created.json()["id"]

    with Session(engine) as s:          # имитируем оплаченное поднятие на 6 часов
        row = s.get(Ride, ride_id)
        row.boosted_until = utcnow() + timedelta(hours=6)
        s.add(row)
        s.commit()

    assert client.post(f"/rides/{ride_id}/cancel", headers=driver["auth"]).status_code == 200

    with Session(engine) as s:
        assert s.get(User, driver["id"]).boost_credit_sec > 5 * 3600

    second = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Сибай", "to_city": "Баймаҡ", "price": 300, "seats_total": 3,
        "depart_at": (utcnow() + timedelta(hours=8)).isoformat(),
    })
    assert second.status_code == 200, second.text

    with Session(engine) as s:
        assert s.get(Ride, second.json()["id"]).boosted_until is not None
        assert s.get(User, driver["id"]).boost_credit_sec == 0
