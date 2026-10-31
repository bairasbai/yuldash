"""RS-01 Г: публичная оплата попутки не может открыть банковский путь.

Только синтетические пользователи и локальная тестовая база. Провайдер заменён
ловушкой: никакого HTTP и настоящих банковских ключей.
"""
import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import Booking, BookingStatus, LedgerEntry, Payment, UserRole
from app.routers import wallet
from test_ledger import _make_done_booking, _make_done_order


def _financial_rows():
    with Session(engine) as session:
        return (
            [row.id for row in session.exec(select(Payment).order_by(Payment.id)).all()],
            [row.id for row in session.exec(select(LedgerEntry).order_by(LedgerEntry.id)).all()],
        )


@pytest.mark.parametrize("method", ["card", "sbp"])
@pytest.mark.parametrize("provider", ["mock", "sbp_manual", "yookassa"])
@pytest.mark.parametrize("already_paid", [False, True])
def test_booking_cashless_is_locked_before_replay_or_provider(
        client, user_factory, monkeypatch, method, provider, already_paid):
    driver = user_factory("RS01 водитель", role=UserRole.driver)
    passenger = user_factory("RS01 пассажир")
    booking_id = _make_done_booking(driver["id"], passenger["id"])
    with Session(engine) as session:
        booking = session.get(Booking, booking_id)
        booking.paid = already_paid
        booking.payment_method = "card" if already_paid else ""
        session.add(booking)
        session.commit()
        assert wallet.booking_cashless_allowed(session, booking) is False

    monkeypatch.setattr(settings, "payments_provider", provider)
    monkeypatch.setattr(settings, "yookassa_shop_id", "synthetic-shop")
    monkeypatch.setattr(settings, "yookassa_secret_key", "synthetic-not-a-key")
    monkeypatch.setattr(settings, "payouts_enabled", True)
    calls = []

    def forbidden(*args, **kwargs):
        calls.append("forbidden")
        pytest.fail("Запрет должен сработать до банковского пути")

    monkeypatch.setattr(wallet, "_pay_cashless", forbidden)
    monkeypatch.setattr(wallet, "_start_yookassa", forbidden)
    before = _financial_rows()
    response = client.post(f"/bookings/{booking_id}/pay", headers=passenger["auth"],
                           json={"method": method})
    assert response.status_code == 503
    detail = response.json()["detail"]
    assert detail["code"] == "booking_online_pay_off"
    assert detail["ru"] and detail["ba"]
    assert calls == []
    assert _financial_rows() == before
    with Session(engine) as session:
        assert session.get(Booking, booking_id).paid is already_paid


def test_cashless_refusal_preserves_ownership_check(client, user_factory):
    driver = user_factory("RS01 хозяин рейса", role=UserRole.driver)
    passenger = user_factory("RS01 хозяин брони")
    outsider = user_factory("RS01 посторонний")
    booking_id = _make_done_booking(driver["id"], passenger["id"])
    before = _financial_rows()
    response = client.post(f"/bookings/{booking_id}/pay", headers=outsider["auth"],
                           json={"method": "card"})
    assert response.status_code == 403
    assert _financial_rows() == before


def test_booking_cash_mark_is_still_idempotent(client, user_factory):
    driver = user_factory("RS01 наличные водитель", role=UserRole.driver)
    passenger = user_factory("RS01 наличные пассажир")
    booking_id = _make_done_booking(driver["id"], passenger["id"])
    before = _financial_rows()
    for expected in ("paid", "already_paid"):
        response = client.post(f"/bookings/{booking_id}/pay", headers=passenger["auth"],
                               json={"method": "cash"})
        assert response.status_code == 200
        assert response.json() == {"status": expected, "method": "cash"}
    assert _financial_rows() == before


def test_booking_cash_still_requires_completed_trip(client, user_factory):
    driver = user_factory("RS01 незавершённый водитель", role=UserRole.driver)
    passenger = user_factory("RS01 незавершённый пассажир")
    booking_id = _make_done_booking(driver["id"], passenger["id"])
    with Session(engine) as session:
        booking = session.get(Booking, booking_id)
        booking.status = BookingStatus.confirmed
        session.add(booking)
        session.commit()
    response = client.post(f"/bookings/{booking_id}/pay", headers=passenger["auth"],
                           json={"method": "cash"})
    assert response.status_code == 409


def test_instant_order_cashless_path_is_unchanged(client, user_factory, monkeypatch):
    driver = user_factory("RS01 такси водитель", role=UserRole.driver)
    passenger = user_factory("RS01 такси пассажир")
    order_id = _make_done_order(driver["id"], passenger["id"])
    calls = []

    def capture(*args, **kwargs):
        calls.append(kwargs)
        return {"status": "pending"}

    monkeypatch.setattr(wallet, "_pay_cashless", capture)
    response = client.post(f"/instant/orders/{order_id}/pay", headers=passenger["auth"],
                           json={"method": "card"})
    assert response.status_code == 200
    assert len(calls) == 1 and calls[0]["order_id"] == order_id
