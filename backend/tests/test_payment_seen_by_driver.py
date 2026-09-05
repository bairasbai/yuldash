"""Водитель должен видеть, чем с ним рассчитаются, — а не узнавать на высадке.

История. Пассажир может сменить способ расчёта прямо в поездке: про наличные вспоминают,
уже сидя в машине. Водителю уходило уведомление, и на этом всё. За рулём пуш пропускают,
а посмотреть глазами было негде — способ он видел один раз, в предложении заказа. Приезжают:
один достаёт телефон, другой ждёт наличные.

Теперь помним, когда способ поменяли и подтвердил ли водитель, что видел. Не подтвердил
за минуту — пассажиру честно говорим, что водитель ещё не в курсе. Ровно та же механика,
что со сменой адреса: заставить человека посмотреть в телефон за рулём нельзя, но можно
не врать второму, что всё улажено.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, User, UserRole
from app.timeutil import utcnow


@pytest.fixture
def session(client):
    """Своя сессия к тестовой базе — как в остальных тестах проекта.

    Фикстуры с именем `session` в `conftest.py` нет, зато она есть у установленного
    рядом плагина `pytest-locust`. Без этой строки pytest брал ЧУЖУЮ: та тянет `locust`,
    импорт которого на Windows встаёт намертво — и весь прогон висел до таймаута,
    а сами проверки не выполнялись ни разу.

    Зависимость от `client` не декоративная: именно подъём приложения заводит таблицы.
    Без неё файл, запущенный в одиночку, падал на «no such table: instantorder».

    За собой убираем. База у прогона одна на всех, а надбавка к цене считается по числу
    заказов «в поиске» рядом с точкой подачи. Забытый здесь заказ поднимал цену чужому
    тесту про подачу за восемь километров — тот ждал ровную цену, а получал ×1,1.
    """
    with Session(engine) as s:
        try:
            yield s
        finally:
            for obj in reversed(_созданное):
                try:
                    s.delete(obj)
                except Exception:
                    pass
            _созданное.clear()
            s.commit()


_UNSET = object()
_uid = {"n": 0}
_созданное: list = []   # что завели за тест — то и уберём


def _user(session, role: UserRole) -> int:
    """Настоящая строка пользователя.

    У заказа внешние ключи на людей, поэтому выдуманные `passenger_id=1` / `driver_id=2`
    база просто не принимала — все четыре проверки падали на вставке.
    """
    _uid["n"] += 1
    i = _uid["n"]
    u = User(phone=f"pay-seen-{i}", name="Тест", telegram_id=f"payseen{i}",
             verified=True, role=role)
    session.add(u)
    session.commit()
    session.refresh(u)
    _созданное.append(u)
    return u.id


def _order(session, **kw) -> InstantOrder:
    """Заказ в пути с водителем — то состояние, ради которого всё и делается."""
    passenger_id = kw.pop("passenger_id", _UNSET)
    if passenger_id is _UNSET:
        passenger_id = _user(session, UserRole.passenger)
    driver_id = kw.pop("driver_id", _UNSET)
    if driver_id is _UNSET:
        driver_id = _user(session, UserRole.driver)
    order = InstantOrder(
        passenger_id=passenger_id,
        driver_id=driver_id,
        from_lat=52.59, from_lng=58.31, to_lat=52.60, to_lng=58.32,
        status=kw.pop("status", "onboard"),
        payment_method=kw.pop("payment_method", "cash"),
        **kw,
    )
    session.add(order)
    session.commit()
    session.refresh(order)
    _созданное.append(order)
    return order


def test_смена_до_водителя_не_считается_непросмотренной(session):
    """Пока водителя нет, «видел / не видел» не про кого — иначе первый же выбор способа
    выглядел бы как непрочитанное сообщение."""
    order = _order(session, driver_id=None, status="searching")
    assert isv.payment_ack_overdue(order) is False
    order.payment_changed_at = utcnow() - timedelta(minutes=5)
    session.add(order)
    session.commit()
    assert isv.payment_ack_overdue(order) is False, "без водителя тревожить пассажира не о чем"


def test_минуту_молчания_ждём_и_только_потом_тревожим(session):
    """Водитель может смотреть на дорогу, а не в телефон. Минута — не повод для паники."""
    order = _order(session)
    order.payment_changed_at = utcnow() - timedelta(seconds=30)
    session.add(order)
    session.commit()
    assert isv.payment_ack_overdue(order) is False

    order.payment_changed_at = utcnow() - timedelta(seconds=90)
    session.add(order)
    session.commit()
    assert isv.payment_ack_overdue(order) is True, "полторы минуты молчания — пора сказать пассажиру"


def test_подтвердил_значит_вопрос_закрыт(session):
    """Нажал «Понял» — тревога снимается, даже если прошло много времени."""
    order = _order(session)
    order.payment_changed_at = utcnow() - timedelta(minutes=10)
    order.payment_ack_at = utcnow()
    session.add(order)
    session.commit()
    assert isv.payment_ack_overdue(order) is False


def test_выдача_говорит_про_непросмотренную_смену(session):
    """Флаг нужен обоим экранам: водителю — подсветить строку, пассажиру — предупредить."""
    order = _order(session)
    # Витрина отдаётся только участнику заказа (проверка приватности внутри order_payload),
    # поэтому смотрим глазами водителя — того, кому подсветка и адресована.
    водитель = session.get(User, order.driver_id)

    тело = isv.order_payload(session, order, водитель)
    assert тело["payment_changed"] is False, "смены не было — и подсвечивать нечего"

    order.payment_changed_at = utcnow()
    session.add(order)
    session.commit()
    тело = isv.order_payload(session, order, водитель)
    assert тело["payment_changed"] is True

    order.payment_ack_at = utcnow()
    session.add(order)
    session.commit()
    тело = isv.order_payload(session, order, водитель)
    assert тело["payment_changed"] is False, "водитель увидел — подсветка гаснет"
