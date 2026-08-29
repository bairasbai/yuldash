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

from app import instant_service as isv
from app.models import InstantOrder
from app.timeutil import utcnow


def _order(session, **kw) -> InstantOrder:
    """Заказ в пути с водителем — то состояние, ради которого всё и делается."""
    order = InstantOrder(
        passenger_id=kw.pop("passenger_id", 1),
        driver_id=kw.pop("driver_id", 2),
        from_lat=52.59, from_lng=58.31, to_lat=52.60, to_lng=58.32,
        status="onboard", payment_method="cash", **kw,
    )
    session.add(order)
    session.commit()
    session.refresh(order)
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
    тело = isv.order_payload(session, order, None)
    assert тело["payment_changed"] is False, "смены не было — и подсвечивать нечего"

    order.payment_changed_at = utcnow()
    session.add(order)
    session.commit()
    тело = isv.order_payload(session, order, None)
    assert тело["payment_changed"] is True

    order.payment_ack_at = utcnow()
    session.add(order)
    session.commit()
    тело = isv.order_payload(session, order, None)
    assert тело["payment_changed"] is False, "водитель увидел — подсветка гаснет"
