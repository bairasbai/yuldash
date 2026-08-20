"""Оплаченное время сгорало, уехавшую поездку можно было забронировать, а чат — открывать вечно.

Три находки роя, все про границы, которых не было (аудит 2026-08-08, волна 151).

**Второй, более дешёвый Boost затирал уже оплаченный дорогой.** Человек купил «День вверху»
за 50 ₽ (24 часа), сразу докупил «Быстрое поднятие» за 20 ₽ (2 часа) — и остался с двумя часами
вместо суток. Заплатил 70 ₽ и потерял 22 часа, за которые уже заплатил. Рядом, в том же
обработчике, реклама и подписка бизнеса считают «период плюс остаток» — у поднятия поездки
этого просто забыли.

**Бронь поездки, которая уехала месяц назад.** Публикация в прошлое честно отбивается словами
«Время выезда уже прошло», а бронь — нет: проверялся только статус. Человек открывает вчерашнюю
карточку, старый пуш или ссылку из чата, видит «ждём подтверждения» и ждёт ответа, которого
не будет: ночная уборка тихо погасит бронь. Места при этом числятся занятыми, а водителю
приходит «Новая бронь» по рейсу месячной давности.

**«Забыл вещь» продлевалось бесконечно.** Кнопка открывает закрытый чат заново на 48 часов —
это правильный выход, иначе телефон с заднего сиденья не вернуть. Но нажимать её можно было
сколько угодно: пять нажатий по поездке 400-дневной давности дали пять «готово» и пять
уведомлений человеку. Раз в двое суток — и переписка держится открытой вечно. Та же «травля
кнопкой», от которой закрывали брони в волне 51.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, Payment, Ride, UserRole
from app.routers.bookings import LOST_ITEM_MAX_CALLS
from app.timeutil import utcnow

from test_api import _ride


def _оплатить_поднятие(client, админ, ride_id: int, владелец, tier: str) -> None:
    with Session(engine) as s:
        pay = Payment(user_id=владелец["id"], purpose="boost", ride_id=ride_id,
                      amount_kop=5000, status="pending", tier=tier)
        s.add(pay)
        s.commit()
        s.refresh(pay)
        pid = pay.id
    r = client.post(f"/admin/payments/{pid}/confirm", headers=админ["auth"])
    assert r.status_code == 200, r.text


def test_докупка_добавляет_время_а_не_обрезает(client, user_factory):
    """Главное: человек не должен терять оплаченные часы, доплатив ещё."""
    водитель = user_factory("БустВодитель", role=UserRole.driver)
    админ = user_factory("БустАдмин", role=UserRole.admin)
    ride_id = _ride(client, водитель, comment="буст")

    _оплатить_поднятие(client, админ, ride_id, водитель, "day")      # 24 часа
    with Session(engine) as s:
        после_первого = s.get(Ride, ride_id).boosted_until
    _оплатить_поднятие(client, админ, ride_id, водитель, "quick")    # +2 часа

    with Session(engine) as s:
        после_второго = s.get(Ride, ride_id).boosted_until
    assert после_второго > после_первого, (
        f"докупка обрезала оплаченное время ({после_первого} → {после_второго}): человек "
        "заплатил 70 ₽ и остался с двумя часами вместо суток"
    )


def test_докупка_не_понижает_уровень(client, user_factory):
    """Доплатив копейку, нельзя опуститься с дорогого места на дешёвое."""
    водитель = user_factory("БустВодитель2", role=UserRole.driver)
    админ = user_factory("БустАдмин2", role=UserRole.admin)
    ride_id = _ride(client, водитель, comment="буст2")

    _оплатить_поднятие(client, админ, ride_id, водитель, "urgent")   # самый дорогой
    _оплатить_поднятие(client, админ, ride_id, водитель, "quick")    # самый дешёвый

    with Session(engine) as s:
        уровень = s.get(Ride, ride_id).boost_tier
    assert уровень == "urgent", (
        f"после копеечной докупки уровень стал {уровень!r}: человек заплатил за заметное место "
        "и опустился ниже"
    )


def test_поездку_месячной_давности_не_забронировать(client, user_factory):
    """Иначе человек ждёт ответа, которого не будет, а места числятся занятыми."""
    водитель = user_factory("СтарыйРейсВодитель", role=UserRole.driver)
    пассажир = user_factory("СтарыйРейсПассажир")
    ride_id = _ride(client, водитель, comment="уехала давно")
    with Session(engine) as s:
        r = s.get(Ride, ride_id)
        r.depart_at = utcnow() - timedelta(days=30)
        s.add(r)
        s.commit()

    бронь = client.post("/bookings", headers=пассажир["auth"],
                        json={"ride_id": ride_id, "seats": 1})

    assert бронь.status_code == 400, (
        f"забронирован рейс, уехавший месяц назад (ответ {бронь.status_code}): человек будет "
        "ждать подтверждения, а водителю придёт «Новая бронь» по старому рейсу"
    )
    assert "ba" in бронь.text, "отказ не на двух языках"


def test_уехавшую_только_что_ещё_можно_догнать(client, user_factory):
    """Обратная сторона: садятся и впритык — пара часов запаса остаётся."""
    водитель = user_factory("ВпритыкВодитель", role=UserRole.driver)
    пассажир = user_factory("ВпритыкПассажир")
    ride_id = _ride(client, водитель, comment="только что выехали")
    with Session(engine) as s:
        r = s.get(Ride, ride_id)
        r.depart_at = utcnow() - timedelta(minutes=20)
        s.add(r)
        s.commit()

    бронь = client.post("/bookings", headers=пассажир["auth"],
                        json={"ride_id": ride_id, "seats": 1})

    assert бронь.status_code == 200, (
        f"поездку, выехавшую 20 минут назад, уже не забронировать: {бронь.text[:120]}. "
        "Человек видит её в ленте и не понимает, почему нельзя сесть"
    )


@pytest.fixture
def завершённая(client, user_factory):
    водитель = user_factory("ВещьВодитель", role=UserRole.driver)
    пассажирка = user_factory("ВещьПассажирка")
    ride_id = _ride(client, водитель, comment="забыл вещь")
    bid = client.post("/bookings", headers=пассажирка["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.done
        r = s.get(Ride, ride_id)
        r.depart_at = utcnow() - timedelta(hours=3)
        s.add(b)
        s.add(r)
        s.commit()
    return водитель, пассажирка, bid, ride_id


def test_забыл_вещь_нельзя_жать_бесконечно(client, завершённая):
    """Каждое нажатие открывает чат и дёргает человека уведомлением."""
    водитель, _, bid, _ = завершённая

    ответы = [client.post(f"/bookings/{bid}/lost-item", headers=водитель["auth"])
              for _ in range(LOST_ITEM_MAX_CALLS + 2)]

    коды = [r.status_code for r in ответы]
    прошло = sum(1 for c in коды if c == 200)
    assert прошло <= LOST_ITEM_MAX_CALLS, (
        f"кнопку удалось нажать {прошло} раз ({коды}): нажимая раз в двое суток, можно держать "
        "переписку с человеком открытой сколько угодно"
    )
    assert 409 in коды, f"лишние нажатия прошли без внятного отказа: {коды}"


def test_первое_нажатие_работает(client, завершённая):
    """Обратная сторона: телефон с заднего сиденья должно быть можно вернуть."""
    _, пассажирка, bid, _ = завершённая

    r = client.post(f"/bookings/{bid}/lost-item", headers=пассажирка["auth"])

    assert r.status_code == 200, f"человек не может сообщить о забытой вещи: {r.text[:120]}"
    assert r.json().get("chat_open_until"), "чат не открылся — вещь не вернуть"


def test_через_месяц_вещь_ищут_уже_в_поддержке(client, завершённая):
    """Через месяц это не поиск вещи, а способ написать человеку."""
    водитель, _, bid, ride_id = завершённая
    with Session(engine) as s:
        r = s.get(Ride, ride_id)
        r.depart_at = utcnow() - timedelta(days=400)
        s.add(r)
        s.commit()

    r = client.post(f"/bookings/{bid}/lost-item", headers=водитель["auth"])

    assert r.status_code == 409, (
        f"по поездке 400-дневной давности чат открылся заново ({r.status_code}): это уже "
        "не про вещь"
    )
    assert "поддержк" in r.text.lower(), f"человеку не сказали, куда идти дальше: {r.text[:150]}"
