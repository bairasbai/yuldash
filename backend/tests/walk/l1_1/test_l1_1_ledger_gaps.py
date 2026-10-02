"""leaf-1.1 · backend/app/ledger.py — закрывает щели, которых не было в старом наборе тестов.

Старый набор (test_ledger.py, test_wallet_never_goes_negative.py, …) проверяет наличные и
идемпотентность для БЫСТРЫХ ЗАКАЗОВ (instant) и вывод с РАЗНЫМИ ключами для ОДНОГО водителя.
Четыре соседних правила той же природы нигде отдельно не проверялись:

R1 — наличные за БРОНЬ (попутку) тоже не должны трогать ledger (settle_booking, не только
     settle_instant_order).
R2 — повторный settle_booking (безнал) не должен начислить earn/fee ВТОРОЙ раз.
R3 — вывод без ключа идемпотентности (пустая строка/только пробелы) обязан отказывать, а не
     проводить вывод «на доверии» — иначе дабл-тап/ретрай сети без ключа спишет дважды.
R4 — ключ идемпотентности выплаты именной ПО ВОДИТЕЛЮ (`payout:{driver_id}:{key}`), а не голый
     сырой ключ от клиента. Настоящий риск — не наша БД (там выборка и так фильтрует по
     driver_id), а ЮKassa: её Idempotence-Key ГЛОБАЛЬНЫЙ, без понятия «чей». Два разных водителя,
     приславших одинаковый сырой ключ (низкая энтропия клиентского генератора, совпадение,
     повтор чужого) — без неймспейса ушли бы к провайдеру с ОДНИМ И ТЕМ ЖЕ Idempotence-Key, и
     провайдер отдал бы ОБОИМ запросам один и тот же ответ (кэш по ключу на его стороне):
     второй водитель получил бы чужой provider_id/confirmation_url, а его реальная выплата
     «проглотилась» бы. Проверяем именно ТО, что уходит наружу (idempotence_key в create_payout),
     а не только наш внутренний учёт.
"""
from sqlmodel import Session, select

from app import ledger
from app.config import settings
from app.db import engine
from app.models import Booking, BookingStatus, LedgerEntry, LedgerKind, Ride, UserRole
from app.timeutil import utcnow


def _make_done_booking(driver_id: int, passenger_id: int, price_rub: int = 300) -> int:
    with Session(engine) as s:
        ride = Ride(driver_id=driver_id, from_city="Баймак", to_city="Сибай",
                    depart_at=utcnow(), price=price_rub)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=passenger_id, seats=1,
                    price=price_rub, status=BookingStatus.done)
        s.add(b)
        s.commit()
        s.refresh(b)
        return b.id


def _ledger_rows(driver_id: int, booking_id: int) -> list[LedgerEntry]:
    with Session(engine) as s:
        return list(s.exec(select(LedgerEntry).where(
            LedgerEntry.driver_id == driver_id, LedgerEntry.booking_id == booking_id)).all())


def test_r1_cash_booking_does_not_touch_ledger(client, user_factory):
    drv = user_factory("CashBookDrv", role=UserRole.driver)
    pax = user_factory("CashBookPax")
    bid = _make_done_booking(drv["id"], pax["id"], price_rub=250)

    with Session(engine) as s:
        status = ledger.settle_booking(s, bid, "cash", 25_000)

    assert status == "settled"
    with Session(engine) as s:
        b = s.get(Booking, bid)
        assert b.paid is True and b.payment_method == "cash"
    assert _ledger_rows(drv["id"], bid) == [], "наличные за бронь не должны создавать записи в кошельке"
    with Session(engine) as s:
        assert ledger.driver_balance(s, drv["id"]) == 0


def test_r2_settle_booking_twice_cashless_does_not_double_post(client, user_factory):
    drv = user_factory("TwiceBookDrv", role=UserRole.driver)
    pax = user_factory("TwiceBookPax")
    bid = _make_done_booking(drv["id"], pax["id"], price_rub=300)

    with Session(engine) as s:
        first = ledger.settle_booking(s, bid, "card", 30_000)
    with Session(engine) as s:
        second = ledger.settle_booking(s, bid, "card", 30_000)

    assert first == "settled"
    assert second == "already"
    rows = _ledger_rows(drv["id"], bid)
    earn_rows = [r for r in rows if r.kind == LedgerKind.earn]
    assert len(earn_rows) == 1, f"повторный settle задвоил earn: {rows}"


def test_r3_payout_without_an_idempotency_key_is_rejected(client, user_factory, monkeypatch):
    monkeypatch.setattr(settings, "payout_min_kop", 1_00, raising=False)
    monkeypatch.setattr(settings, "payout_max_kop", 500_000, raising=False)
    drv = user_factory("NoKeyDrv", role=UserRole.driver)
    with Session(engine) as s:
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.earn, amount_kop=50_000,
                          note="заработок"))
        s.commit()

    with Session(engine) as s:
        for пустой_ключ in ("", "   "):
            try:
                ledger.request_payout(s, drv["id"], 10_000, payout_token="tok",
                                      idempotency_key=пустой_ключ)
                assert False, f"вывод без ключа идемпотентности ({пустой_ключ!r}) обязан отказать"
            except ledger.PayoutError as e:
                assert e.code == "idempotency"
        assert ledger.driver_balance(s, drv["id"]) == 50_000, "отказ не должен был тронуть баланс"


def test_r4_the_same_raw_key_from_two_different_drivers_reach_the_provider_differently(
        client, user_factory, monkeypatch):
    """Сырой ключ от двух разных водителей обязан прийти к ЮKassa РАЗНЫМ Idempotence-Key.

    Провайдер судит по этому ключу один-в-один: одинаковый ключ от разных водителей он бы
    счёл ОДНИМ И ТЕМ ЖЕ запросом и отдал бы обоим ответ первого — второй водитель не получил
    бы свою реальную выплату. Наша собственная БД эту дыру не покажет (там выборка и так
    фильтрует по driver_id) — проверять нужно ровно то, что уходит наружу.
    """
    monkeypatch.setattr(settings, "payout_min_kop", 1_00, raising=False)
    monkeypatch.setattr(settings, "payout_max_kop", 500_000, raising=False)
    a = user_factory("PayoutDriverA", role=UserRole.driver)
    b = user_factory("PayoutDriverB", role=UserRole.driver)
    общий_сырой_ключ = "общий-ключ-от-разных-людей"
    with Session(engine) as s:
        for кто in (a, b):
            s.add(LedgerEntry(driver_id=кто["id"], kind=LedgerKind.earn, amount_kop=20_000,
                              note="заработок"))
        s.commit()

    увиденные_ключи: list[str] = []

    def шпион_вместо_юkassa(amount_kop, payout_token, description, metadata, *,
                            idempotence_key="", customer_phone=""):
        увиденные_ключи.append(idempotence_key)
        return {"status": "succeeded", "provider_id": f"mock-{len(увиденные_ключи)}"}

    monkeypatch.setattr("app.payments.create_payout", шпион_вместо_юkassa)

    with Session(engine) as s:
        res_a = ledger.request_payout(s, a["id"], 15_000, payout_token="tok",
                                      idempotency_key=общий_сырой_ключ)
        res_b = ledger.request_payout(s, b["id"], 15_000, payout_token="tok",
                                      idempotency_key=общий_сырой_ключ)

    assert res_a["status"] == "ok" and res_b["status"] == "ok"
    assert len(увиденные_ключи) == 2
    assert увиденные_ключи[0] != увиденные_ключи[1], (
        f"оба водителя ушли к провайдеру с ОДНИМ ключом {увиденные_ключи[0]!r} — "
        "ключ идемпотентности обязан быть именным по водителю"
    )
    assert str(a["id"]) in увиденные_ключи[0] or str(a["id"]) in увиденные_ключи[1]
    assert str(b["id"]) in увиденные_ключи[0] or str(b["id"]) in увиденные_ключи[1]
