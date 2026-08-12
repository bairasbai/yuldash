# -*- coding: utf-8 -*-
"""Сверка денег обязана краснеть на поломке — и только на ней.

Аудит 2026-08-12, волна 27. Сверка `ledger.reconcile` — единственный прибор, по которому видно,
что начисления водителям сходятся с оплатами. Обещание в коде: «diff ≠ 0 = сигнал бага».

Что нашли пробой: обычная ночная оплата ломала сверку без всякого бага. Человек нажимает
«оплатить» в 23:58, деньги доходят в 00:03 — платёж считался по дате НАЖАТИЯ, начисление
по дате прихода денег. Получалось −1000 ₽ вчера и +1000 ₽ сегодня при полном порядке.
Прибор, который краснеет сам по себе, перестают читать — и настоящую поломку он уже
никому не покажет.

Второй тест здесь не менее важен первого: проверяет, что прибор НЕ ослеп после починки.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session

from app.db import engine
from app.ledger import reconcile
from app.models import InstantOrder, InstantOrderStatus, Payment, UserRole
from app.timeutil import utcnow


@pytest.fixture
def yookassa_says_paid(monkeypatch):
    monkeypatch.setattr("app.config.settings.payments_provider", "yookassa")
    monkeypatch.setattr("app.routers.payments.fetch_payment",
                        lambda pid: {"status": "succeeded", "metadata": {}})


def _days():
    now = utcnow()
    day1_start = (now - timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
    return day1_start, day1_start + timedelta(days=1), day1_start + timedelta(days=2)


def _done_order_paid_at_night(client, user_factory, provider_id: str, price_rub: int = 1000):
    """Заказ завершён, «оплатить» нажали вчера в 23:58 — деньги придут уже сегодня."""
    pax = user_factory(name="Ночной пассажир")
    drv = user_factory(name="Ночной водитель", role=UserRole.driver)
    _, day1_end, _ = _days()
    with Session(engine) as s:
        o = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"],
                         status=InstantOrderStatus.done, price_final=price_rub,
                         from_lat=53.0, from_lng=58.0, to_lat=53.1, to_lng=58.1)
        s.add(o)
        s.commit()
        s.refresh(o)
        p = Payment(user_id=pax["id"], purpose="ride", order_id=o.id, amount_kop=price_rub * 100,
                    method="card", provider_id=provider_id, status="pending",
                    created_at=day1_end - timedelta(minutes=2))
        s.add(p)
        s.commit()
    return pax, drv


def test_ночная_оплата_не_ломает_сверку(client, user_factory, yookassa_says_paid):
    """Одна оплата, одно начисление, всё честно — значит расхождение НЕ сдвинулось ни в один день.

    Смотрим на ИЗМЕНЕНИЕ diff до и после нашей оплаты: база у тестов общая, и абсолютные суммы
    в окне складываются из чужих поездок. Нам важно ровно одно — наша ночная оплата не должна
    добавить расхождения ни вчера, ни сегодня.
    """
    day1_start, day1_end, day2_end = _days()
    _done_order_paid_at_night(client, user_factory, "pid_night_ok")
    with Session(engine) as s:
        before_yday = reconcile(s, day1_start, day1_end)["diff_kop"]
        before_today = reconcile(s, day1_end, day2_end)["diff_kop"]

    assert client.post("/payments/yookassa/webhook",
                       json={"object": {"id": "pid_night_ok"}}).status_code == 200

    with Session(engine) as s:
        yesterday = reconcile(s, day1_start, day1_end)
        today = reconcile(s, day1_end, day2_end)

    assert yesterday["diff_kop"] == before_yday, (
        f"вчерашняя сверка покраснела на здоровых деньгах: было {before_yday}, стало {yesterday['diff_kop']}")
    assert today["diff_kop"] == before_today, (
        f"сегодняшняя сверка покраснела на здоровых деньгах: было {before_today}, стало {today['diff_kop']}")


def test_сверка_всё_ещё_видит_настоящую_поломку(client, user_factory):
    """Прибор не ослеп: оплата прошла, а водителю не начислили — это обязано быть видно.

    Ровно тот случай, ради которого сверка и заведена: деньги у платформы, водитель без денег.
    """
    pax = user_factory(name="Пассажир без начисления")
    _day1, day1_end, day2_end = _days()
    # Смотрим на ИЗМЕНЕНИЕ diff, а не на абсолютное число: база у тестов общая, и соседний
    # тест мог оставить в этом окне свои честные деньги. Сторож должен ловить нашу поломку,
    # а не пересчитывать чужие поездки.
    with Session(engine) as s:
        before = reconcile(s, day1_end, day2_end)["diff_kop"]
        # Успешная безналичная оплата поездки БЕЗ записи в кошельке водителя.
        s.add(Payment(user_id=pax["id"], purpose="ride", amount_kop=77700, method="card",
                      status="succeeded", provider_id="pid_broken",
                      settled_at=day1_end + timedelta(hours=1)))
        s.commit()
        after = reconcile(s, day1_end, day2_end)["diff_kop"]

    assert after != before, "сверка проспала пропущенное начисление — прибор ослеп"
    assert after - before == -77700, (before, after)   # оплата есть, начисления нет → минус
