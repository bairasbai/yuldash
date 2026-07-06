"""Деньги v1 (Фаза 3, D3) — тесты. Деньги критичны → покрываем плотно:

  • комиссия считается верно (целые копейки, ROUND_HALF_UP);
  • оплата безналом начисляет водителю earn + fee (баланс = earn − fee);
  • ledger append-only, баланс = SUM;
  • оплатить можно ТОЛЬКО завершённую (done) поездку/бронь;
  • наличные помечают paid, но ledger НЕ двигают (деньги мимо нас);
  • идемпотентность: повторный webhook / повторный settle НЕ задваивает начисление;
  • сверка ловит расхождение ledger↔оплаты;
  • права (анти-IDOR): чужой заказ не оплатить, чужой ledger не увидеть; сверка — только админ.

DB тестов общая на сессию → для сверки (глобальная сумма) сравниваем ДЕЛЬТЫ, а балансы
проверяем на свежих (уникальных) водителях — так значения детерминированы.
"""
import pytest
from sqlmodel import Session, select

from app import ledger
from app.config import settings
from app.db import engine
from app.models import (
    Booking, BookingStatus, InstantOrder, InstantOrderStatus as S,
    LedgerEntry, LedgerKind, Payment, Ride, User, UserRole,
)
from app.timeutil import utcnow


# ------------------------------ helpers ------------------------------
def _bal(driver_id: int) -> int:
    """Баланс водителя (открываем свою сессию — тесту так удобнее)."""
    with Session(engine) as s:
        return ledger.driver_balance(s, driver_id)


def _make_done_order(driver_id: int, passenger_id: int, price_rub: int = 200, paid: bool = False) -> int:
    """Готовый завершённый быстрый заказ прямо в БД (минуем matcher — тестируем деньги)."""
    with Session(engine) as s:
        o = InstantOrder(
            passenger_id=passenger_id, driver_id=driver_id,
            from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6,
            from_text="А", to_text="Б", status=S.done,
            price_estimate=price_rub, price_final=price_rub, paid=paid,
            done_at=utcnow(),
        )
        s.add(o)
        s.commit()
        s.refresh(o)
        return o.id


def _set_order_status(order_id: int, status: S) -> None:
    with Session(engine) as s:
        o = s.get(InstantOrder, order_id)
        o.status = status
        s.add(o)
        s.commit()


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


# ============================ Комиссия ============================
def test_fee_kop_exact_integer_kopecks():
    """Комиссия — целые копейки, без float-дрейфа."""
    assert ledger.fee_kop_for(20000, 15.0) == 3000     # 200 ₽ · 15% = 30 ₽
    assert ledger.fee_kop_for(15000, 15.0) == 2250     # 150 ₽ · 15% = 22.50 ₽
    assert ledger.fee_kop_for(0, 15.0) == 0
    assert ledger.fee_kop_for(20000, 0) == 0           # нулевой процент → нет комиссии


def test_fee_kop_round_half_up():
    """Округление ROUND_HALF_UP, предсказуемо на «некруглых» процентах."""
    # 12345 коп · 12.5% = 1543.125 → 1543
    assert ledger.fee_kop_for(12345, 12.5) == 1543
    # 100 коп · 12.5% = 12.5 → 13 (half up, а не банковское 12)
    assert ledger.fee_kop_for(100, 12.5) == 13


def test_default_service_fee_is_8_percent():
    """Дефолтная комиссия платформы = 8% (втрое ниже Яндекса ~24–30%); правится без пересборки."""
    from app.config import settings
    assert settings.service_fee_percent == 8.0
    # По дефолту (percent=None → берёт из конфига): 20000 коп · 8% = 1600.
    assert ledger.fee_kop_for(20000) == 1600
    assert ledger.fee_kop_for(30000) == 2400


# ============================ Начисление за поездку ============================
def test_pay_cashless_posts_earn_and_fee(client, user_factory):
    """Оплата картой завершённого заказа: earn (+вся сумма) и fee (−комиссия); баланс = earn − fee."""
    drv = user_factory("PayDrv", role=UserRole.driver)
    pax = user_factory("PayPax")
    oid = _make_done_order(drv["id"], pax["id"], price_rub=200)

    r = client.post(f"/instant/orders/{oid}/pay", headers=pax["auth"], json={"method": "card"})
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "succeeded"

    # Баланс = 20000 − 1600 (8%) = 18400 коп (свежий водитель → детерминированно).
    assert _bal(drv["id"]) == 18400
    with Session(engine) as s:
        rows = s.exec(select(LedgerEntry).where(LedgerEntry.driver_id == drv["id"])).all()
        kinds = sorted(e.kind.value for e in rows)
        assert kinds == ["earn", "fee"]
        earn = next(e for e in rows if e.kind == LedgerKind.earn)
        fee = next(e for e in rows if e.kind == LedgerKind.fee)
        assert earn.amount_kop == 20000 and earn.order_id == oid
        assert fee.amount_kop == -1600
        assert s.get(InstantOrder, oid).paid is True


def test_wallet_balance_and_ledger_endpoints(client, user_factory):
    """Эндпоинты кошелька: баланс и история по своему токену."""
    drv = user_factory("WalletDrv", role=UserRole.driver)
    pax = user_factory("WalletPax")
    oid = _make_done_order(drv["id"], pax["id"], price_rub=200)
    client.post(f"/instant/orders/{oid}/pay", headers=pax["auth"], json={"method": "card"})

    bal = client.get("/wallet/balance", headers=drv["auth"]).json()
    assert bal["balance_kop"] == 18400 and bal["balance_rub"] == 184
    entries = client.get("/wallet/ledger", headers=drv["auth"]).json()
    assert len(entries) == 2
    assert {e["kind"] for e in entries} == {"earn", "fee"}


# ============================ Оплата только done ============================
@pytest.mark.parametrize("status", [S.accepted, S.arriving, S.onboard, S.searching])
def test_pay_only_when_done(client, user_factory, status):
    """Незавершённый заказ оплатить нельзя (409)."""
    drv = user_factory("NotDoneDrv", role=UserRole.driver)
    pax = user_factory("NotDonePax")
    oid = _make_done_order(drv["id"], pax["id"])
    _set_order_status(oid, status)
    r = client.post(f"/instant/orders/{oid}/pay", headers=pax["auth"], json={"method": "card"})
    assert r.status_code == 409, r.text
    assert _bal(drv["id"]) == 0     # ничего не начислено


# ============================ Наличные ============================
def test_cash_marks_paid_no_ledger(client, user_factory):
    """Наличные: заказ помечается оплаченным, но деньги через нас НЕ идут → ledger пуст."""
    drv = user_factory("CashDrv", role=UserRole.driver)
    pax = user_factory("CashPax")
    oid = _make_done_order(drv["id"], pax["id"], price_rub=200)

    r = client.post(f"/instant/orders/{oid}/pay", headers=pax["auth"], json={"method": "cash"})
    assert r.status_code == 200 and r.json() == {"status": "paid", "method": "cash"}
    assert _bal(drv["id"]) == 0     # наличные — мимо нас
    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        assert o.paid is True and o.payment_method == "cash"


# ============================ Идемпотентность ============================
def test_settle_idempotent_no_double(client, user_factory):
    """Повторный settle того же заказа НЕ задваивает начисление (флаг paid + row-lock)."""
    drv = user_factory("IdemDrv", role=UserRole.driver)
    pax = user_factory("IdemPax")
    oid = _make_done_order(drv["id"], pax["id"], price_rub=200)
    with Session(engine) as s:
        assert ledger.settle_instant_order(s, oid, "card", 20000) == "settled"
    with Session(engine) as s:
        assert ledger.settle_instant_order(s, oid, "card", 20000) == "already"
    assert _bal(drv["id"]) == 18400
    with Session(engine) as s:
        rows = s.exec(select(LedgerEntry).where(LedgerEntry.driver_id == drv["id"])).all()
        assert len(rows) == 2                            # ровно earn+fee, не 4


def test_pay_twice_returns_already_paid(client, user_factory):
    """Повторный вызов /pay уже оплаченного заказа → already_paid, без нового начисления."""
    drv = user_factory("TwiceDrv", role=UserRole.driver)
    pax = user_factory("TwicePax")
    oid = _make_done_order(drv["id"], pax["id"], price_rub=200)
    assert client.post(f"/instant/orders/{oid}/pay", headers=pax["auth"], json={"method": "card"}).json()["status"] == "succeeded"
    r2 = client.post(f"/instant/orders/{oid}/pay", headers=pax["auth"], json={"method": "card"})
    assert r2.json()["status"] == "already_paid"
    assert _bal(drv["id"]) == 18400


def test_webhook_repeat_does_not_double_ledger(client, user_factory, monkeypatch):
    """ЮKassa-webhook пришёл дважды → начисление ровно один раз (payment succeeded + settle already)."""
    old_provider = settings.payments_provider
    settings.payments_provider = "yookassa"
    try:
        drv = user_factory("HookDrv", role=UserRole.driver)
        pax = user_factory("HookPax")
        oid = _make_done_order(drv["id"], pax["id"], price_rub=200)
        with Session(engine) as s:
            p = Payment(user_id=pax["id"], purpose="ride", order_id=oid, method="card",
                        amount_kop=20000, provider_id="pid_ride_idem", status="pending")
            s.add(p)
            s.commit()

        monkeypatch.setattr("app.routers.payments.fetch_payment",
                            lambda _pid: {"status": "succeeded", "metadata": {}})
        for _ in range(2):
            assert client.post("/payments/yookassa/webhook",
                               json={"object": {"id": "pid_ride_idem"}}).status_code == 200

        assert _bal(drv["id"]) == 18400
        with Session(engine) as s:
            rows = s.exec(select(LedgerEntry).where(LedgerEntry.driver_id == drv["id"])).all()
            assert len(rows) == 2
            assert s.get(InstantOrder, oid).paid is True
    finally:
        settings.payments_provider = old_provider


# ============================ Append-only + баланс = SUM ============================
def test_ledger_append_only_balance_is_sum(client, user_factory):
    """Баланс = SUM(amount_kop); корректировка — новой записью adj, историю не трогаем."""
    drv = user_factory("SumDrv", role=UserRole.driver)
    with Session(engine) as s:
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.earn, amount_kop=20000, note="t"))
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.fee, amount_kop=-3000, note="t"))
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.payout, amount_kop=-10000, note="выплата"))
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.adj, amount_kop=500, note="правка"))
        s.commit()
    assert _bal(drv["id"]) == 20000 - 3000 - 10000 + 500  # 7500


# ============================ Сверка ============================
def test_reconcile_matched_and_mismatch(client, user_factory):
    """Сверка: совпадение → ok/diff растёт согласованно; орфан-earn без оплаты → diff ловит."""
    admin = user_factory("ReconAdmin", role=UserRole.admin)
    drv = user_factory("ReconDrv", role=UserRole.driver)
    pax = user_factory("ReconPax")

    before = client.get("/admin/ledger/reconcile", headers=admin["auth"], params={"days": 366}).json()

    # Матч: безналичная оплата 200 ₽ → earn 20000 + Payment 20000. earn и payments растут одинаково.
    oid = _make_done_order(drv["id"], pax["id"], price_rub=200)
    client.post(f"/instant/orders/{oid}/pay", headers=pax["auth"], json={"method": "card"})
    matched = client.get("/admin/ledger/reconcile", headers=admin["auth"], params={"days": 366}).json()
    assert matched["earn_kop"] - before["earn_kop"] == 20000
    assert matched["payments_kop"] - before["payments_kop"] == 20000
    assert matched["diff_kop"] == before["diff_kop"]     # сходимость не изменилась

    # Расхождение: orphan earn без оплаты → diff уезжает на эту сумму, ok=False.
    with Session(engine) as s:
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.earn, amount_kop=9999, note="orphan"))
        s.commit()
    after = client.get("/admin/ledger/reconcile", headers=admin["auth"], params={"days": 366}).json()
    assert after["diff_kop"] - matched["diff_kop"] == 9999
    assert after["ok"] is False


def test_reconcile_admin_only(client, user_factory):
    """Сверку видит только админ (чужие деньги не для всех)."""
    pax = user_factory("NotAdmin")
    assert client.get("/admin/ledger/reconcile", headers=pax["auth"]).status_code == 403


# ============================ Права / IDOR ============================
def test_cannot_pay_others_order(client, user_factory):
    """Анти-IDOR: чужой заказ оплатить нельзя (403), начисления нет."""
    drv = user_factory("IdorDrv", role=UserRole.driver)
    owner = user_factory("IdorOwner")
    attacker = user_factory("IdorAttacker")
    oid = _make_done_order(drv["id"], owner["id"], price_rub=200)
    r = client.post(f"/instant/orders/{oid}/pay", headers=attacker["auth"], json={"method": "card"})
    assert r.status_code == 403, r.text
    assert _bal(drv["id"]) == 0


def test_wallet_ledger_only_own(client, user_factory):
    """Кошелёк отдаёт ТОЛЬКО свои записи — чужой ledger по своему токену не виден."""
    drv_a = user_factory("OwnA", role=UserRole.driver)
    drv_b = user_factory("OwnB", role=UserRole.driver)
    with Session(engine) as s:
        s.add(LedgerEntry(driver_id=drv_a["id"], kind=LedgerKind.earn, amount_kop=11111, note="a"))
        s.add(LedgerEntry(driver_id=drv_b["id"], kind=LedgerKind.earn, amount_kop=22222, note="b"))
        s.commit()
    a = client.get("/wallet/ledger", headers=drv_a["auth"]).json()
    assert all(e["amount_kop"] == 11111 for e in a) and len(a) == 1
    assert client.get("/wallet/balance", headers=drv_b["auth"]).json()["balance_kop"] == 22222


# ============================ Оплата брони плановой поездки ============================
def test_pay_done_booking_cashless(client, user_factory):
    """Оплата завершённой брони начисляет водителю поездки (earn − комиссия)."""
    drv = user_factory("BookDrv", role=UserRole.driver)
    pax = user_factory("BookPax")
    bid = _make_done_booking(drv["id"], pax["id"], price_rub=300)
    r = client.post(f"/bookings/{bid}/pay", headers=pax["auth"], json={"method": "card"})
    assert r.status_code == 200 and r.json()["status"] == "succeeded"
    # 300 ₽ = 30000 коп; комиссия 8% = 2400 → баланс 27600.
    assert _bal(drv["id"]) == 27600
    with Session(engine) as s:
        assert s.get(Booking, bid).paid is True
        row = s.exec(select(LedgerEntry).where(LedgerEntry.booking_id == bid, LedgerEntry.kind == LedgerKind.earn)).first()
        assert row.amount_kop == 30000


def test_pay_booking_only_when_done(client, user_factory):
    """Незавершённую бронь оплатить нельзя."""
    drv = user_factory("BookNDDrv", role=UserRole.driver)
    pax = user_factory("BookNDPax")
    with Session(engine) as s:
        ride = Ride(driver_id=drv["id"], from_city="A", to_city="B", depart_at=utcnow(), price=300)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=pax["id"], price=300, status=BookingStatus.confirmed)
        s.add(b)
        s.commit()
        s.refresh(b)
        bid = b.id
    assert client.post(f"/bookings/{bid}/pay", headers=pax["auth"], json={"method": "card"}).status_code == 409
