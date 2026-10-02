"""leaf-1.2 — N1 (повторное независимое ревью Opus 5.5, 2026-10-02): отмена своего безнала
не должна затирать уже поставленный `refund_due`.

Сценарий из ревью: вебхук держит строку Payment и ждёт блокировку заказа, которую на миг взяла
«оплата налом»; «нал» коммитит первым; вебхук ставит `refund_due` (деньги пришли, применить
уже некуда — см. `_handle_unclaimed_payment`, уже отправлены тикет и Telegram); а следом UPDATE
отмены от «нала» — если он безусловный — перезаписывает статус обратно в `canceled`, хотя
тикет/уведомление про возврат уже ушли человеку и админу.

Раньше `_cancel_own_pending_cashless` делал SELECT (с фильтром status=='pending') и затем
БЕЗУСЛОВНО перезаписывал статус найденных объектов. Фильтр в SELECT не защищает от строки,
сменившей статус ПОСЛЕ него, но ДО commit. Теперь — один атомарный `UPDATE ... WHERE
status='pending'`: условие переоценивается СУБД в момент самого обновления, а не раньше.

Этот тест проверяет ключевое свойство, которое и закрывает гонку: функция в принципе не трогает
строку, у которой статус уже не `pending`, — вне зависимости от того, когда именно он изменился.
"""
from sqlmodel import Session, select

from app.db import engine
from app.models import Payment, UserRole
from app.routers.wallet import _cancel_own_pending_cashless

from test_ledger import _make_done_order


def test_cancel_own_pending_does_not_touch_a_refund_due_row(client, user_factory):
    driver = user_factory("L12N1Driver", role=UserRole.driver)
    passenger = user_factory("L12N1Passenger")
    order_id = _make_done_order(driver["id"], passenger["id"], price_rub=210)

    with Session(engine) as session:
        payment = Payment(user_id=passenger["id"], purpose="ride", order_id=order_id,
                          amount_kop=21000, method="yookassa", provider_id="qa-n1-refund-due",
                          status="refund_due")
        session.add(payment)
        session.commit()
        session.refresh(payment)
        payment_id = payment.id

    with Session(engine) as session:
        _cancel_own_pending_cashless(session, passenger["id"], order_id=order_id)

    with Session(engine) as session:
        after = session.get(Payment, payment_id)
    assert after.status == "refund_due", (
        f"отмена своего безнала перезаписала refund_due в {after.status!r} — "
        "деньги уже помечены к возврату (тикет/Telegram отправлены), статус нельзя затирать"
    )


def test_cancel_own_pending_still_cancels_a_genuinely_pending_row(client, user_factory):
    """Контроль не-регресса: обычный случай (висящий pending-счёт) по-прежнему отменяется."""
    driver = user_factory("L12N1Driver2", role=UserRole.driver)
    passenger = user_factory("L12N1Passenger2")
    order_id = _make_done_order(driver["id"], passenger["id"], price_rub=180)

    with Session(engine) as session:
        payment = Payment(user_id=passenger["id"], purpose="ride", order_id=order_id,
                          amount_kop=18000, method="yookassa", provider_id="qa-n1-pending",
                          status="pending")
        session.add(payment)
        session.commit()
        session.refresh(payment)
        payment_id = payment.id

    with Session(engine) as session:
        _cancel_own_pending_cashless(session, passenger["id"], order_id=order_id)

    with Session(engine) as session:
        after = session.get(Payment, payment_id)
    assert after.status == "canceled"
