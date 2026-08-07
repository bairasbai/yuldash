"""Удаление аккаунта не должно ломать чужие данные и падать по внешним ключам
(аудит 2026-08-07).

Три находки, все — про одно: удаление шло «снести всё, что связано с человеком», а часть
связанного принадлежит НЕ ему.

1. **Деньги чужого водителя.** Список удаляемых заказов собирается по «я пассажир ИЛИ я
   водитель», а долг по комиссии и записи кошелька удалялись ПО ЭТОМУ СПИСКУ. То есть
   пассажир, удаляя свой аккаунт, стирал долг водителя за уже сделанную поездку. Ledger
   в проекте объявлен append-only («историю денег НЕ удаляем») — а тут удалялась.

2. **Падение на SOS.** `SosEvent.handled_by` — ссылка на админа, который принял сигнал.
   Она не разрывалась, и на Postgres удаление такого админа падало по внешнему ключу.
   На SQLite без `PRAGMA foreign_keys=ON` это было не видно — ровно та ловушка, из-за
   которой в проекте включили проверку ключей в тестах (`tests/conftest.py:65`).

3. **Падение на кошельке.** `LedgerEntry.booking_id` не разрывался перед удалением броней —
   то же самое падение, другой ключ.

Право на удаление (152-ФЗ) при этом сохраняется полностью: свои данные человека удаляются,
у чужих записей снимается только ссылка на удаляемый объект.
"""
from datetime import timedelta

from sqlmodel import Session, select

from app.account import delete_user_account
from app.timeutil import utcnow
from app.db import engine
from app.models import (
    Booking, BookingStatus, CommissionDebt, DebtStatus, InstantOrder, InstantOrderStatus as S,
    LedgerEntry, LedgerKind, Ride, SosEvent, User, UserRole,
)


def test_passenger_deletion_keeps_drivers_commission_debt(user_factory):
    """Пассажир удаляет аккаунт — долг водителя по комиссии обязан остаться.

    Иначе это бесплатный способ обнулить комиссию: договориться с пассажиром, чтобы тот
    удалил аккаунт, — и долг за поездку исчезнет вместе с ним.
    """
    pax = user_factory("Пассажир")
    drv = user_factory("Водитель", role=UserRole.driver)
    with Session(engine) as s:
        order = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"], status=S.done,
                             price_estimate=500, price_final=500)
        s.add(order)
        s.commit()
        s.refresh(order)
        s.add(CommissionDebt(driver_id=drv["id"], order_id=order.id,
                             amount_kop=4000, week="2026-W32", status=DebtStatus.unpaid))
        s.add(LedgerEntry(driver_id=drv["id"], order_id=order.id,
                          kind=LedgerKind.earn, amount_kop=50000))
        s.commit()

    with Session(engine) as s:
        delete_user_account(s, s.get(User, pax["id"]))

    with Session(engine) as s:
        debts = s.exec(select(CommissionDebt).where(CommissionDebt.driver_id == drv["id"])).all()
        entries = s.exec(select(LedgerEntry).where(LedgerEntry.driver_id == drv["id"])).all()
        assert len(debts) == 1, "долг водителя стёрт удалением ЧУЖОГО аккаунта"
        assert debts[0].amount_kop == 4000, "сумма долга изменилась"
        assert len(entries) == 1, "запись кошелька водителя стёрта — ledger объявлен append-only"
        # Ссылка на удалённый заказ снята, сама запись цела.
        assert debts[0].order_id is None
        assert entries[0].order_id is None


def test_driver_deletion_removes_own_debt(user_factory):
    """Обратная сторона: СВОЙ долг вместе со своим аккаунтом уходит — это его данные."""
    pax = user_factory("Пассажир2")
    drv = user_factory("Водитель2", role=UserRole.driver)
    with Session(engine) as s:
        order = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"], status=S.done,
                             price_estimate=500, price_final=500)
        s.add(order)
        s.commit()
        s.refresh(order)
        s.add(CommissionDebt(driver_id=drv["id"], order_id=order.id,
                             amount_kop=4000, week="2026-W32", status=DebtStatus.paid))
        s.commit()

    with Session(engine) as s:
        delete_user_account(s, s.get(User, drv["id"]))

    with Session(engine) as s:
        assert s.exec(select(CommissionDebt).where(CommissionDebt.driver_id == drv["id"])).all() == []


def test_deleting_admin_who_handled_sos_does_not_crash(user_factory):
    """`SosEvent.handled_by` держал ссылку на админа → на Postgres удаление падало."""
    admin = user_factory("Админ", role=UserRole.admin)
    victim = user_factory("Пострадавший")
    with Session(engine) as s:
        s.add(SosEvent(user_id=victim["id"], handled_by=admin["id"], lat=52.59, lng=58.31))
        s.commit()

    with Session(engine) as s:
        delete_user_account(s, s.get(User, admin["id"]))      # раньше — IntegrityError

    with Session(engine) as s:
        ev = s.exec(select(SosEvent).where(SosEvent.user_id == victim["id"])).first()
        assert ev is not None, "сигнал SOS пострадавшего удалён вместе с админом"
        assert ev.handled_by is None, "ссылка на удалённого админа осталась висеть"


def test_deleting_user_with_ledger_on_booking_does_not_crash(user_factory):
    """`LedgerEntry.booking_id` не разрывался перед удалением броней → падение по ключу."""
    pax = user_factory("Пассажир3")
    drv = user_factory("Водитель3", role=UserRole.driver)
    with Session(engine) as s:
        ride = Ride(driver_id=drv["id"], from_city="Баймак", to_city="Сибай", seats=4, price=300,
                    depart_at=utcnow() - timedelta(days=1))
        s.add(ride)
        s.commit()
        s.refresh(ride)
        booking = Booking(ride_id=ride.id, passenger_id=pax["id"], seats=1,
                          status=BookingStatus.done)
        s.add(booking)
        s.commit()
        s.refresh(booking)
        s.add(LedgerEntry(driver_id=drv["id"], booking_id=booking.id,
                          kind=LedgerKind.earn, amount_kop=30000))
        s.commit()

    with Session(engine) as s:
        delete_user_account(s, s.get(User, pax["id"]))        # раньше — IntegrityError

    with Session(engine) as s:
        entries = s.exec(select(LedgerEntry).where(LedgerEntry.driver_id == drv["id"])).all()
        assert len(entries) == 1, "заработок водителя стёрт удалением аккаунта пассажира"
        assert entries[0].booking_id is None
