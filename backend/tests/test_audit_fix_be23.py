# -*- coding: utf-8 -*-
"""BE23: повторный разбор не должен возвращать комиссию, которой платформа не получала."""

from sqlmodel import Session, select

from app import debt as debt_mod
from app.db import engine
from app.models import (
    CommissionDebt,
    DebtStatus,
    InstantOrder,
    InstantOrderStatus,
    LedgerEntry,
    LedgerKind,
    UserRole,
)
from app.timeutil import utcnow


def _долг_за_заказ(
    user_factory, имя: str, *, status: DebtStatus, note: str = "",
) -> tuple[int, int, int]:
    водитель = user_factory(имя, role=UserRole.driver)
    пассажир = user_factory(f"Пассажир {имя}")
    with Session(engine) as session:
        заказ = InstantOrder(
            passenger_id=пассажир["id"],
            driver_id=водитель["id"],
            from_lat=54.7,
            from_lng=55.9,
            to_lat=54.8,
            to_lng=56.0,
            status=InstantOrderStatus.done,
            price_estimate=500,
            price_final=500,
            paid=True,
            done_at=utcnow(),
        )
        session.add(заказ)
        session.commit()
        session.refresh(заказ)
        долг = CommissionDebt(
            driver_id=водитель["id"],
            order_id=заказ.id,
            amount_kop=4_000,
            week="2026-W37",
            status=status,
            note=note,
        )
        session.add(долг)
        session.commit()
        session.refresh(долг)
        return водитель["id"], заказ.id, долг.id


def _возвраты(order_id: int) -> list[LedgerEntry]:
    with Session(engine) as session:
        return session.exec(
            select(LedgerEntry).where(
                LedgerEntry.kind == LedgerKind.adj,
                LedgerEntry.ext_id == debt_mod.refund_ext_id(order_id=order_id),
            )
        ).all()


def test_повторное_списание_не_возвращает_никогда_не_полученную_комиссию(
    client, user_factory,
):
    _driver_id, order_id, debt_id = _долг_за_заказ(
        user_factory, "BE23 неоплаченный", status=DebtStatus.unpaid,
    )

    with Session(engine) as session:
        assert debt_mod.void_debt_for_order(session, order_id) == "voided"
        session.commit()
        повтор = debt_mod.void_debt_for_order(session, order_id)
        session.commit()
        долг = session.get(CommissionDebt, debt_id)
        assert долг.status == DebtStatus.paid
        assert долг.note.startswith(debt_mod.WRITTEN_OFF_PREFIX)

    assert повтор is None
    assert _возвраты(order_id) == [], (
        "повторный разбор вернул комиссию, которую водитель никогда не платил"
    )


def test_реально_оплаченную_комиссию_возвращаем_ровно_один_раз(client, user_factory):
    driver_id, order_id, _debt_id = _долг_за_заказ(
        user_factory, "BE23 оплаченный", status=DebtStatus.paid,
    )

    with Session(engine) as session:
        первый = debt_mod.void_debt_for_order(session, order_id)
        session.commit()
        второй = debt_mod.void_debt_for_order(session, order_id)
        session.commit()

    assert первый == "refunded"
    assert второй is None
    возвраты = _возвраты(order_id)
    assert len(возвраты) == 1
    assert возвраты[0].driver_id == driver_id
    assert возвраты[0].amount_kop == 4_000


def test_списание_сохраняет_старую_пометку_под_однозначной_причиной(client, user_factory):
    старая_пометка = "Водитель приложил подтверждение к спору"
    _driver_id, order_id, debt_id = _долг_за_заказ(
        user_factory,
        "BE23 старая пометка",
        status=DebtStatus.unpaid,
        note=старая_пометка,
    )

    with Session(engine) as session:
        assert debt_mod.void_debt_for_order(session, order_id) == "voided"
        session.commit()
        assert debt_mod.void_debt_for_order(session, order_id) is None
        session.commit()
        долг = session.get(CommissionDebt, debt_id)
        assert долг.note.startswith(debt_mod.WRITTEN_OFF_PREFIX)
        assert старая_пометка in долг.note

    assert _возвраты(order_id) == []
