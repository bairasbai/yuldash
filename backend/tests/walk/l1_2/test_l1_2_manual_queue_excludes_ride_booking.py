"""leaf-1.2 — F6 (независимое ревью Opus 5.5, 2026-10-02): ручная СБП-очередь/подтверждение
не должны пропускать "сирот" за поездку.

Первая версия этой правки СЛЕПО исключала purpose in (ride, booking) из обеих ручек — и
сломала существующий, намеренно поддерживаемый путь: test_manual_payment_terminal_confirmation.py
(кейс legacy_booking) заводит историческую строку Payment(purpose="booking", method="sbp")
напрямую в БД и ожидает, что ручная очередь её ВИДИТ и ПОДТВЕРЖДАЕТ — так и должно остаться
для уже существующих данных.

Корень находки был в другом: для ЗАКАЗА (в отличие от брони) момент создания строки Payment
оставлял её на миг в состоянии method='card'/'sbp' (НЕ 'yookassa'), и именно это переходное
состояние (а не сам purpose) совпадало с фильтром ручной очереди. Исправление — как у брони:
method='yookassa' выставляется СРАЗУ при создании строки, а не отдельным commit позже. Тогда
НОВАЯ строка ни на миг не выглядит как ручная СБП-заявка; СТАРЫЕ исторические строки (до этой
правки) остаются как есть и по-прежнему видны админу — намеренно, не задним числом.
"""
from app.models import UserRole
from app.routers import wallet as wallet_router
from app.timeutil import utcnow

from test_ledger import _make_done_order


def test_fresh_order_payment_is_tagged_yookassa_before_any_external_call(client, user_factory, monkeypatch):
    """Строка платежа за ЗАКАЗ (не бронь — у брони method='yookassa' и так был с самого начала,
    мутация её не задевает) обязана прийти в _start_yookassa УЖЕ со method='yookassa' — то есть
    эта отметка стоит на САМОМ ПЕРВОМ commit, а не отдельным шагом после него."""
    driver = user_factory("L12FreshDriver", role=UserRole.driver)
    passenger = user_factory("L12FreshPassenger")
    order_id = _make_done_order(driver["id"], passenger["id"], price_rub=240)

    seen_methods = []
    real_start = wallet_router._start_yookassa

    def spying_start_yookassa(session, payment, description, phone):
        seen_methods.append(payment.method)
        return real_start(session, payment, description, phone)

    monkeypatch.setattr(wallet_router, "_start_yookassa", spying_start_yookassa)

    response = client.post(f"/instant/orders/{order_id}/pay", headers=passenger["auth"], json={"method": "card"})
    assert response.status_code == 200, response.text
    assert seen_methods == ["yookassa"], (
        f"строка дошла до _start_yookassa с method={seen_methods} — значит был миг, когда она "
        "ещё не 'yookassa' и могла попасть в ручную СБП-очередь как чужая заявка"
    )


def test_legacy_manual_booking_payment_is_still_visible_and_confirmable(client, user_factory):
    """Контрольный тест на НЕ-регресс: исторические строки (до этой правки) остаются видны
    и подтверждаемы — ручная очередь не должна была превратиться в вещь, которая прячет
    существующие данные только потому, что они за бронь."""
    from sqlmodel import Session

    from app.db import engine
    from app.models import Booking, BookingStatus, Payment, Ride

    driver = user_factory("L12LegacyDriver", role=UserRole.driver)
    passenger = user_factory("L12LegacyPassenger")
    admin = user_factory("L12LegacyAdmin", role=UserRole.admin)

    with Session(engine) as session:
        ride = Ride(driver_id=driver["id"], from_city="Баймак", to_city="Сибай",
                   depart_at=utcnow(), price=400)
        session.add(ride); session.commit(); session.refresh(ride)
        booking = Booking(ride_id=ride.id, passenger_id=passenger["id"], seats=1,
                          price=400, status=BookingStatus.done)
        session.add(booking); session.commit(); session.refresh(booking)
        payment = Payment(user_id=passenger["id"], purpose="booking", booking_id=booking.id,
                          amount_kop=40000, method="sbp")
        session.add(payment); session.commit(); session.refresh(payment)
        payment_id, booking_id = payment.id, booking.id

    queue = client.get("/admin/payments/pending", headers=admin["auth"])
    assert queue.status_code == 200
    assert payment_id in [row["payment_id"] for row in queue.json()], (
        "историческая ручная строка за бронь пропала из очереди — это настоящий регресс "
        "для уже существующих данных, не защита от новой дыры"
    )

    confirm = client.post(f"/admin/payments/{payment_id}/confirm", headers=admin["auth"])
    assert confirm.status_code == 200 and confirm.json()["status"] == "succeeded", confirm.text
    with Session(engine) as session:
        assert session.get(Booking, booking_id).paid is True
