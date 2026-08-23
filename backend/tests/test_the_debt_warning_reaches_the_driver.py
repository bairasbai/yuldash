"""Предупреждение о долге не доходило до водителя — оно роняло завершение заказа.

Правило про деньги правильное: комиссия за дальнюю поездку гасится сразу, а долг у порога
блокировки предупреждает о себе заранее. Оба разговора начинаются в один момент — когда
водитель нажал «Завершил».

**Проба:** Ильдар отвёз человека Сибай → Уфа. Комиссия крупная, значит приложение должно
сказать «оплати сегодня». Вместо этого завершение заказа падало: функция уведомления звала
отправку пуша, которой в её файле не было (`push_notification` без импорта). Ошибка ждала
ровно того случая, ради которого правило и написано — крупного долга.

Мелкие заказы этого не показывали: уведомитель успевал вернуть «нечего сообщать» до вызова
пуша. Поэтому весь набор тестов был зелёным, а первый же дальний рейс на живых деньгах
встретил бы водителя ошибкой.

Здесь проверяется человеческий путь целиком: заказ завершается, деньги начисляются,
и водитель получает то самое сообщение, ради которого всё затевалось.
"""
from __future__ import annotations

from datetime import timedelta

import fakeredis
import pytest
from sqlmodel import Session, select

from app import debt as debt_mod
from app import instant_service as isv
from app.config import settings
from app.db import engine
from app.models import (
    CommissionDebt, DebtStatus, InstantOrder, InstantOrderStatus, Notification, User, UserRole,
)
from app.timeutil import utcnow

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


_номер = {'n': 0}


def _долг(driver_id: int, *, amount_kop: int, срочный: bool) -> CommissionDebt:
    """Долг за настоящий завершённый заказ — у долга внешний ключ на заказ."""
    now = utcnow()
    _номер['n'] += 1
    n = _номер['n']
    with Session(engine) as s:
        pax = User(phone=f"tg-warn-pax-{driver_id}-{n}", name="Пассажир",
                   telegram_id=f"warnpax{driver_id}x{n}", verified=True)
        s.add(pax)
        s.commit()
        s.refresh(pax)
        order = InstantOrder(passenger_id=pax.id, driver_id=driver_id,
                             status=InstantOrderStatus.done, price_estimate=500, price_final=500)
        s.add(order)
        s.commit()
        s.refresh(order)
        d = CommissionDebt(
            driver_id=driver_id, order_id=order.id, amount_kop=amount_kop,
            status=DebtStatus.unpaid, created_at=now,
            due_at=now + (timedelta(hours=3) if срочный else timedelta(days=7)),
        )
        s.add(d)
        s.commit()
        s.refresh(d)
        return d


def _письма(user_id: int) -> list[Notification]:
    with Session(engine) as s:
        return list(s.exec(select(Notification).where(Notification.user_id == user_id)).all())


def test_срочный_долг_доходит_до_водителя(client, user_factory):
    """Главное: сообщение «оплати сегодня» уходит, а не роняет код на полпути."""
    водитель = user_factory("ИльдарСрочныйДолг", role=UserRole.driver)
    долг = _долг(водитель["id"], amount_kop=45000, срочный=True)

    with Session(engine) as s:
        сказали = isv.notify_pay_now_debt(s, долг)

    assert сказали is True, "водителю не сказали про срочный долг"
    тексты = " ".join((n.body_ru or "") for n in _письма(водитель["id"]))
    assert "450" in тексты, f"в сообщении нет суммы долга: {тексты[:200]}"


def test_завершение_дальней_поездки_не_ломается_на_уведомлении(
    client, user_factory, fake_redis, monkeypatch
):
    """Живой путь: водитель нажал «Завершил» — заказ закрылся, водитель предупреждён."""
    # Порог срочного долга опускаем, чтобы обычный тестовый заказ вёл себя как дальний рейс.
    monkeypatch.setattr(settings, "debt_now_threshold_kop", 1)

    водитель = user_factory("ИльдарДальнийРейс", role=UserRole.driver)
    assert client.post("/driver/online", headers=водитель["auth"],
                       json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=водитель["auth"],
                       json={"lat": ORIG[0], "lng": ORIG[1]}).status_code == 200
    пассажир = user_factory("ПассажирДальнийРейс")
    заказ = client.post("/instant/orders", headers=пассажир["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1],
        "from_text": "Сибай", "to_text": "Уфа",
    }).json()
    oid = заказ["id"]
    for шаг in ("accept", "arrived", "onboard"):
        assert client.post(f"/instant/orders/{oid}/{шаг}",
                           headers=водитель["auth"]).status_code == 200

    ответ = client.post(f"/instant/orders/{oid}/done", headers=водитель["auth"])

    assert ответ.status_code == 200, (
        f"водитель нажал «Завершил» и получил ошибку: {ответ.text[:200]}. "
        "Поездка сделана, деньги у него в руках, а приложение говорит, что что-то сломалось"
    )
    assert ответ.json()["status"] == "done"
    деньги = [n for n in _письма(водитель["id"]) if n.type == "money"]
    assert деньги, "заказ закрылся, но про долг водителю никто не сказал"


def test_предупреждение_перед_блокировкой_доходит(client, user_factory):
    """Второй разговор: долг подошёл к порогу — сказать надо ДО того, как такси закроется."""
    водитель = user_factory("ИльдарПорогБлизко", role=UserRole.driver)
    линия = int(settings.debt_block_threshold_kop * settings.debt_warn_ratio)
    _долг(водитель["id"], amount_kop=линия - 5000, срочный=False)
    последний = _долг(водитель["id"], amount_kop=10000, срочный=False)

    with Session(engine) as s:
        сказали = isv.notify_debt_near_block(s, последний)

    assert сказали is True, (
        "долг перешагнул линию предупреждения, а водитель узнает об этом только "
        "закрытым такси посреди рабочего дня"
    )
    assert _письма(водитель["id"]), "предупреждение не дошло"


def test_обычный_долг_не_поднимает_тревогу(client, user_factory):
    """Обратная сторона: за мелкую комиссию дёргать человека нельзя — перестанет читать."""
    водитель = user_factory("ИльдарМелкийДолг", role=UserRole.driver)
    долг = _долг(водитель["id"], amount_kop=4000, срочный=False)

    with Session(engine) as s:
        assert isv.notify_pay_now_debt(s, долг) is False

    assert not _письма(водитель["id"]), "водителя дёрнули из-за 40 ₽ недельного долга"


def test_без_долга_молчим(client, user_factory):
    """Заказ бесплатный или комиссии нет — говорить не о чем."""
    водитель = user_factory("ИльдарБезДолга", role=UserRole.driver)

    with Session(engine) as s:
        assert isv.notify_pay_now_debt(s, None) is False
        assert isv.notify_debt_near_block(s, None) is False

    assert not _письма(водитель["id"])


def test_порог_срочности_читается_из_записи_а_не_из_конфига(client, user_factory, monkeypatch):
    """Контроль: обещание «неделя» не переписывается задним числом при смене порога."""
    водитель = user_factory("ИльдарСтароеОбещание", role=UserRole.driver)
    долг = _долг(водитель["id"], amount_kop=50000, срочный=False)
    monkeypatch.setattr(settings, "debt_now_threshold_kop", 1)

    with Session(engine) as s:
        assert isv.notify_pay_now_debt(s, долг) is False, (
            "порог подняли сегодня — и вчерашний недельный долг вдруг стал срочным"
        )
    assert debt_mod.is_pay_now(долг) is False
