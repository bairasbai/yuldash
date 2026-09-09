"""BE35: окончательно отменённый счёт провайдера не должен навсегда блокировать новый."""

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import CommissionDebt, InstantOrder, LedgerEntry, Payment, UserRole
from app.routers.payments import _sync_provider_status

from test_ledger import _make_done_booking, _make_done_order


def _provider_creator(calls: list[tuple[str, str]]):
    def create_payment(amount_kop, description, metadata, customer_phone="", idempotence_key=""):
        provider_id = f"yk_be35_{len(calls) + 1}"
        calls.append((provider_id, idempotence_key))
        return {
            "provider_id": provider_id,
            "confirmation_url": f"https://pay.example/{provider_id}",
            "status": "pending",
            "mock": False,
        }

    return create_payment


def _payment_rows(user_id: int, purpose: str) -> list[Payment]:
    with Session(engine) as session:
        return list(session.exec(select(Payment).where(
            Payment.user_id == user_id, Payment.purpose == purpose,
        ).order_by(Payment.id)).all())


def _set_fetch(monkeypatch, fetch) -> None:
    # Низкоуровневую функцию и ссылку общего helper патчим одним и тем же ответом провайдера.
    monkeypatch.setattr("app.payments.fetch_payment", fetch)
    monkeypatch.setattr("app.routers.payments.fetch_payment", fetch)


def test_polling_persists_authoritative_provider_canceled_without_activation(
    client, user_factory, monkeypatch,
):
    driver = user_factory("Be35PollDriver", role=UserRole.driver)
    passenger = user_factory("Be35PollPassenger")
    order_id = _make_done_order(driver["id"], passenger["id"], price_rub=240)
    with Session(engine) as session:
        payment = Payment(
            user_id=passenger["id"], purpose="ride", order_id=order_id,
            amount_kop=24_000, method="yookassa", provider_id="yk_be35_canceled",
        )
        session.add(payment)
        session.commit()
        session.refresh(payment)
        payment_id = payment.id
        ledger_before = len(session.exec(select(LedgerEntry)).all())

    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    _set_fetch(
        monkeypatch,
        lambda _pid: {"status": "canceled", "metadata": {}, "confirmation_url": ""},
    )
    response = client.get(f"/payments/{payment_id}/status", headers=passenger["auth"])
    assert response.status_code == 200
    assert response.json()["status"] == "canceled"
    with Session(engine) as session:
        assert session.get(Payment, payment_id).status == "canceled"
        assert session.get(InstantOrder, order_id).paid is False
        assert len(session.exec(select(LedgerEntry)).all()) == ledger_before


@pytest.mark.parametrize("kind", ["debt", "instant", "booking", "courier"])
def test_retry_after_provider_canceled_creates_a_new_payment_and_key(
    client, user_factory, monkeypatch, kind,
):
    driver = user_factory(f"Be35{kind}Driver", role=UserRole.driver)
    payer = driver if kind == "debt" else user_factory(f"Be35{kind}Passenger")
    if kind == "courier":
        from test_the_wallet_pays_for_what_is_already_paid import _курьер, _доставка
        monkeypatch.setattr(settings, "courier_enabled", True)
        payer, _admin = _курьер(client, user_factory, "Be35Courier")
        _доставка(payer["id"], driver["id"], 2_350)
        path, payload, purpose = "/courier/pay-commission", None, "courier_commission"
    elif kind == "debt":
        with Session(engine) as session:
            session.add(CommissionDebt(
                driver_id=driver["id"], amount_kop=2_350, week=f"BE35-{driver['id']}",
            ))
            session.commit()
        path, payload, purpose = "/driver/debt/paid", None, "taxi_debt"
    elif kind == "instant":
        target_id = _make_done_order(driver["id"], payer["id"], price_rub=235)
        path, payload, purpose = f"/instant/orders/{target_id}/pay", {"method": "card"}, "ride"
    else:
        target_id = _make_done_booking(driver["id"], payer["id"], price_rub=335)
        path, payload, purpose = f"/bookings/{target_id}/pay", {"method": "card"}, "booking"

    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    calls: list[tuple[str, str]] = []
    monkeypatch.setattr("app.routers.payments.create_payment", _provider_creator(calls))
    first = client.post(path, headers=payer["auth"], json=payload)
    assert first.status_code == 200, first.text
    first_id = first.json()["payment_id"]

    _set_fetch(
        monkeypatch,
        lambda provider_id: {
            "status": "canceled", "metadata": {},
            "confirmation_url": f"https://pay.example/{provider_id}",
        },
    )
    retry = client.post(path, headers=payer["auth"], json=payload)
    assert retry.status_code == 200, retry.text
    second_id = retry.json()["payment_id"]
    assert second_id != first_id
    assert retry.json()["confirmation_url"] == "https://pay.example/yk_be35_2"
    rows = _payment_rows(payer["id"], purpose)
    assert [(p.id, p.status, p.provider_id) for p in rows] == [
        (first_id, "canceled", "yk_be35_1"),
        (second_id, "pending", "yk_be35_2"),
    ]
    assert calls == [
        ("yk_be35_1", f"yuldash-payment-{first_id}"),
        ("yk_be35_2", f"yuldash-payment-{second_id}"),
    ]


@pytest.mark.parametrize("provider_result", ["pending", "network_error"])
def test_pending_or_unknown_provider_result_reuses_the_same_payment(
    client, user_factory, monkeypatch, provider_result,
):
    driver = user_factory(f"Be35KeepDriver{provider_result}", role=UserRole.driver)
    passenger = user_factory(f"Be35KeepPassenger{provider_result}")
    order_id = _make_done_order(driver["id"], passenger["id"], price_rub=250)
    path = f"/instant/orders/{order_id}/pay"
    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    calls: list[tuple[str, str]] = []
    monkeypatch.setattr("app.routers.payments.create_payment", _provider_creator(calls))
    first = client.post(path, headers=passenger["auth"], json={"method": "card"})
    first_id = first.json()["payment_id"]

    def fetch(provider_id):
        if provider_result == "network_error":
            raise TimeoutError("provider unavailable")
        return {"status": "pending", "metadata": {},
                "confirmation_url": f"https://pay.example/{provider_id}"}

    _set_fetch(monkeypatch, fetch)
    again = client.post(path, headers=passenger["auth"], json={"method": "card"})
    assert again.status_code == 200
    assert again.json()["payment_id"] == first_id
    assert len(calls) == 1
    rows = _payment_rows(passenger["id"], "ride")
    assert len(rows) == 1 and rows[0].status == "pending"


def test_succeeded_activates_once_and_late_canceled_cannot_downgrade(
    client, user_factory, monkeypatch,
):
    driver = user_factory("Be35SuccessDriver", role=UserRole.driver)
    passenger = user_factory("Be35SuccessPassenger")
    order_id = _make_done_order(driver["id"], passenger["id"], price_rub=260)
    with Session(engine) as session:
        payment = Payment(
            user_id=passenger["id"], purpose="ride", order_id=order_id,
            amount_kop=26_000, method="yookassa", provider_id="yk_be35_success",
        )
        session.add(payment)
        session.commit()
        session.refresh(payment)
        payment_id = payment.id
        before = len(session.exec(select(LedgerEntry).where(LedgerEntry.order_id == order_id)).all())

    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    provider_status = {"value": "succeeded"}
    monkeypatch.setattr(
        "app.routers.payments.fetch_payment",
        lambda _pid: {"status": provider_status["value"], "metadata": {}, "confirmation_url": ""},
    )
    first = client.get(f"/payments/{payment_id}/status", headers=passenger["auth"])
    assert first.json()["status"] == "succeeded"
    provider_status["value"] = "canceled"
    second = client.get(f"/payments/{payment_id}/status", headers=passenger["auth"])
    assert second.json()["status"] == "succeeded"
    with Session(engine) as session:
        payment = session.get(Payment, payment_id)
        order = session.get(InstantOrder, order_id)
        after = len(session.exec(select(LedgerEntry).where(LedgerEntry.order_id == order_id)).all())
        assert payment.status == "succeeded"
        assert order.paid is True
        assert after - before == 2  # earn + fee, ровно один комплект


def test_row_lock_refreshes_stale_identity_before_applying_provider_status(
    client, user_factory, monkeypatch,
):
    payer = user_factory("Be35StaleIdentity")
    with Session(engine) as setup:
        payment = Payment(
            user_id=payer["id"], purpose="support", amount_kop=1_000,
            method="yookassa", provider_id="yk_be35_stale",
        )
        setup.add(payment)
        setup.commit()
        setup.refresh(payment)
        payment_id = payment.id

    session_a = Session(engine)
    try:
        stale_pending = session_a.get(Payment, payment_id)
        assert stale_pending.status == "pending"
        with Session(engine) as session_b:
            current = session_b.get(Payment, payment_id)
            current.status = "succeeded"
            session_b.add(current)
            session_b.commit()

        _set_fetch(
            monkeypatch,
            lambda _pid: {"status": "canceled", "metadata": {}, "confirmation_url": ""},
        )
        _sync_provider_status(session_a, stale_pending)
    finally:
        session_a.close()

    with Session(engine) as verify:
        assert verify.get(Payment, payment_id).status == "succeeded"


@pytest.mark.parametrize("terminal_status", ["canceled", "succeeded"])
def test_direct_activation_refreshes_stale_identity(client, user_factory, monkeypatch, terminal_status):
    from app.routers.payments import _activate_payment
    payer = user_factory("Be35DirectStale" + terminal_status)
    with Session(engine) as setup:
        payment = Payment(user_id=payer["id"], purpose="courier_commission",
                          amount_kop=1_000, method="sbp")
        setup.add(payment)
        setup.commit()
        setup.refresh(payment)
        payment_id = payment.id
    marks = []
    monkeypatch.setattr("app.routers.payments._mark_succeeded", lambda p: marks.append(p.id))
    with Session(engine) as session_a:
        stale = session_a.get(Payment, payment_id)
        assert stale.status == "pending"
        with Session(engine) as session_b:
            payment = session_b.get(Payment, payment_id)
            payment.status = terminal_status
            session_b.add(payment)
            session_b.commit()
        _activate_payment(session_a, stale)
    assert marks == [], "A terminal payment must not enter activation again through a stale ORM object"
    with Session(engine) as verify:
        assert verify.get(Payment, payment_id).status == terminal_status
