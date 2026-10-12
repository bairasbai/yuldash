"""leaf-1.2 — F1 (независимое ревью Opus 5.5, 2026-10-02): второй оплаченный счёт по уже
оплаченному заказу/брони не должен молча становиться succeeded.

Что было. `_activate_payment` вызывал `ledger.settle_instant_order`/`settle_booking` и
игнорировал её ответ. Ответ "already" означает ровно одно: заказ/бронь уже оплачены ДРУГИМ
платежом (или наличными), пока этот шёл к провайдеру. Несмотря на это, платёж всё равно
помечался `_mark_succeeded` — деньги пассажира списывались у провайдера взаправду, а след
оставался только как молчаливое расхождение в `ledger.reconcile`: ни тикета, ни пуша,
ни сигнала админу. Это ровно тот случай, ради которого написан `_handle_unclaimed_payment`
(раньше подключённый только к пути «вебхук против локально отменённого платежа»).

Сценарий: два pending-счёта на один заказ (как могло остаться от гонки до блокировки заказа,
или просто от двух разных попыток оплаты в разные дни), оба успешно прошли у провайдера.
Первый активируется нормально. Второй обязан получить `refund_due` + тикет поддержки —
а НЕ второй `succeeded` без следа.
"""
from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, BookingStatus, InstantOrder, LedgerEntry, Payment, Ride, SupportTicket, UserRole
from app.routers.payments import _activate_payment
from app.timeutil import utcnow

from test_ledger import _make_done_order, _make_done_booking


def _activate(payment_id: int) -> None:
    with Session(engine) as session:
        payment = session.get(Payment, payment_id)
        _activate_payment(session, payment)


def test_second_succeeded_payment_on_an_already_paid_order_becomes_refund_due(client, user_factory):
    driver = user_factory("L12F1OrderDriver", role=UserRole.driver)
    passenger = user_factory("L12F1OrderPassenger")
    order_id = _make_done_order(driver["id"], passenger["id"], price_rub=240)

    with Session(engine) as session:
        p1 = Payment(user_id=passenger["id"], purpose="ride", order_id=order_id,
                     amount_kop=24000, method="yookassa", provider_id="qa-f1-order-p1", status="pending")
        p2 = Payment(user_id=passenger["id"], purpose="ride", order_id=order_id,
                     amount_kop=24000, method="yookassa", provider_id="qa-f1-order-p2", status="pending")
        session.add(p1); session.add(p2); session.commit()
        session.refresh(p1); session.refresh(p2)
        p1_id, p2_id = p1.id, p2.id

    # Оба "оплачены" у провайдера почти одновременно — обработчик (вебхук/поллинг) доходит
    # до P1 первым, затем до P2.
    _activate(p1_id)
    _activate(p2_id)

    with Session(engine) as session:
        p1 = session.get(Payment, p1_id)
        p2 = session.get(Payment, p2_id)
        order = session.get(InstantOrder, order_id)
        entries = session.exec(select(LedgerEntry).where(LedgerEntry.driver_id == driver["id"])).all()
        tickets = session.exec(select(SupportTicket).where(SupportTicket.user_id == passenger["id"])).all()

    assert p1.status == "succeeded"
    assert p2.status == "refund_due", (
        f"второй платёж за уже оплаченный заказ молча стал {p2.status!r} вместо refund_due — "
        "деньги пассажира списаны у провайдера, а след только в ledger.reconcile"
    )
    assert order.paid is True
    kinds = sorted(e.kind.value for e in entries)
    assert kinds == ["earn", "fee"], f"начисление задвоилось: {kinds}"
    assert len(tickets) == 1, "человеку должен открыться ровно один тикет на возврат лишней оплаты"


def test_second_succeeded_payment_on_an_already_paid_booking_becomes_refund_due(client, user_factory):
    driver = user_factory("L12F1BookingDriver", role=UserRole.driver)
    passenger = user_factory("L12F1BookingPassenger")
    booking_id = _make_done_booking(driver["id"], passenger["id"], price_rub=340)

    with Session(engine) as session:
        p1 = Payment(user_id=passenger["id"], purpose="booking", booking_id=booking_id,
                     amount_kop=34000, method="yookassa", provider_id="qa-f1-booking-p1", status="pending")
        p2 = Payment(user_id=passenger["id"], purpose="booking", booking_id=booking_id,
                     amount_kop=34000, method="yookassa", provider_id="qa-f1-booking-p2", status="pending")
        session.add(p1); session.add(p2); session.commit()
        session.refresh(p1); session.refresh(p2)
        p1_id, p2_id = p1.id, p2.id

    _activate(p1_id)
    _activate(p2_id)

    with Session(engine) as session:
        p1 = session.get(Payment, p1_id)
        p2 = session.get(Payment, p2_id)
        booking = session.get(Booking, booking_id)
        entries = session.exec(select(LedgerEntry).where(LedgerEntry.driver_id == driver["id"])).all()
        tickets = session.exec(select(SupportTicket).where(SupportTicket.user_id == passenger["id"])).all()

    assert p1.status == "succeeded"
    assert p2.status == "refund_due", f"второй платёж за оплаченную бронь стал {p2.status!r} вместо refund_due"
    assert booking.paid is True
    # Попутка бесплатна (ride_service_fee_percent=0 и в оферте, и в тестовом окружении) —
    # поэтому здесь только earn, без fee. Важно не количество видов записи, а то, что их
    # РОВНО ОДИН комплект, а не два (не задвоилось).
    kinds = sorted(e.kind.value for e in entries)
    assert kinds == ["earn"], f"начисление задвоилось или пропало: {kinds}"
    assert entries[0].amount_kop == 34000
    assert len(tickets) == 1


def test_cash_after_card_already_settled_reports_already_paid_not_cash(client, user_factory):
    """wallet.py F1b: путь "наличными" тоже обязан заметить, что заказ уже оплачен картой —
    а не радостно отвечать "paid, method=cash" поверх уже списанных денег (человек тогда
    отдал бы водителю наличные ВТОРОЙ раз)."""
    driver = user_factory("L12F1CashDriver", role=UserRole.driver)
    passenger = user_factory("L12F1CashPassenger")
    order_id = _make_done_order(driver["id"], passenger["id"], price_rub=200)

    # Карта уже провела заказ (как будто это сделал параллельный платёж/вебхук).
    with Session(engine) as session:
        payment = Payment(user_id=passenger["id"], purpose="ride", order_id=order_id,
                          amount_kop=20000, method="yookassa", provider_id="qa-f1-cash-race", status="pending")
        session.add(payment); session.commit(); session.refresh(payment)
        payment_id = payment.id
    _activate(payment_id)
    with Session(engine) as session:
        assert session.get(InstantOrder, order_id).paid is True

    response = client.post(f"/instant/orders/{order_id}/pay", headers=passenger["auth"], json={"method": "cash"})

    assert response.status_code == 200, response.text
    assert response.json()["status"] == "already_paid", (
        f"ответ {response.json()} выдаёт себя за наличную оплату поверх уже списанной карты"
    )
    assert response.json()["method"] != "cash"
    with Session(engine) as session:
        entries = session.exec(select(LedgerEntry).where(LedgerEntry.driver_id == driver["id"])).all()
    kinds = sorted(e.kind.value for e in entries)
    assert kinds == ["earn", "fee"], f"повторный «нал» не должен ничего менять в ledger: {kinds}"


def test_cash_branch_itself_checks_the_settle_result_not_just_the_outer_paid_flag(
        client, user_factory, monkeypatch):
    """Та же защита (F1b), но проверена НАПРЯМУЮ на самой внутренней проверке
    `if result != "settled"` внутри ветки "нал", а не только на внешнем `if order.paid`
    (который теперь, после блокировки заказа, перехватывает этот конкретный сценарий раньше —
    см. test_cash_after_card_already_settled_reports_already_paid_not_cash). Подменяем ТОЛЬКО
    ledger.settle_instant_order, чтобы заставить именно ветку "нал" реально увидеть "already" —
    остальной код (эндпоинт, ответ, ledger) настоящий."""
    from app import ledger as ledger_module

    driver = user_factory("L12F1CashBranchDriver", role=UserRole.driver)
    passenger = user_factory("L12F1CashBranchPassenger")
    order_id = _make_done_order(driver["id"], passenger["id"], price_rub=220)

    monkeypatch.setattr(ledger_module, "settle_instant_order", lambda *a, **k: "already")

    response = client.post(f"/instant/orders/{order_id}/pay", headers=passenger["auth"], json={"method": "cash"})

    assert response.status_code == 200, response.text
    assert response.json()["status"] == "already_paid", (
        f"ветка «нал» проигнорировала результат settle_instant_order: {response.json()}"
    )
    assert response.json()["method"] != "cash"
