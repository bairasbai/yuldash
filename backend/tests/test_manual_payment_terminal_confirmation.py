"""QA-B07-004 / DESIGN031: manual confirmation must report its actual effect.

Actual HTTP creates manual donation/support/boost invoices, queues them, rejects and
confirms them with a normal admin token. No handler/activation/notification is replaced.
One clearly marked historical booking-invoice input is seeded to observe real earn;
the booking itself is created/completed over HTTP. That is not a current public route
to create a manual booking invoice. SQLite sequential results cannot prove PG locks.
No specific terminal rejection HTTP code is invented: a success must really succeed.
"""
from datetime import timedelta, timezone
import json

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import Booking, LedgerEntry, Notification, Payment, Ride, UserRole
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def isolated_manual_configuration(monkeypatch):
    assert settings.env == "dev" and settings.sms_provider == "mock"
    assert settings.ride_service_fee_percent == 0
    assert not any((settings.redis_url, settings.firebase_credentials, settings.telegram_bot_token,
                    settings.yandex_geocoder_key, settings.vapid_private_key,
                    settings.yookassa_shop_id, settings.yookassa_secret_key))
    # Configuration only. All actual product functions and DB effects stay intact.
    monkeypatch.setattr(settings, "payments_provider", "sbp_manual")
    monkeypatch.setattr(settings, "sbp_phone", "+70000000000")
    monkeypatch.setattr(settings, "sbp_bank", "QA_SYNTH_BANK")
    monkeypatch.setattr(settings, "sbp_name", "QA_SYNTH_RECEIVER")


def _publish(client, driver, minutes=120):
    depart = (utcnow() + timedelta(minutes=minutes)).replace(tzinfo=timezone.utc).isoformat()
    response = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": depart,
        "seats_total": 3, "price": 1000,
    })
    assert response.status_code == 200, response.text
    return response.json()["id"]


def _input(client, user_factory, kind):
    admin = user_factory("QA_MANUAL_ADMIN", role=UserRole.admin)
    driver = user_factory("QA_MANUAL_DRIVER", role=UserRole.driver)
    owner, ride_id, booking_id = driver, None, None
    if kind == "donate":
        created = client.post("/donate", headers=owner["auth"], json={"amount": 77})
        amount_kop = 7700
    elif kind == "support":
        created = client.post("/support/donate", headers=owner["auth"], json={"amount_kop": 8800})
        amount_kop = 8800
    elif kind == "boost":
        ride_id = _publish(client, driver)
        created = client.post("/boost/create", headers=owner["auth"], json={"ride_id": ride_id, "tier": "quick"})
        amount_kop = 2000  # existing quick tariff, not an invoice amount recalculation
    else:
        assert kind == "legacy_booking"
        owner = user_factory("QA_MANUAL_LEGACY_PASSENGER")
        ride_id = _publish(client, driver, minutes=10)
        booked = client.post("/bookings", headers=owner["auth"], json={
            "ride_id": ride_id, "seats": 1, "pay_method": "sbp", "pay_amount": 400,
        })
        assert booked.status_code == 200, booked.text
        booking_id = booked.json()["id"]
        assert client.post(f"/bookings/{booking_id}/confirm", headers=driver["auth"]).status_code == 200
        done = client.post(f"/bookings/{booking_id}/driver-status", headers=driver["auth"], json={"status": "done"})
        assert done.status_code == 200 and done.json()["status"] == "done", done.text
        amount_kop = 40000
        # Historical input only: current /bookings/pay creates provider invoices.
        # Actual manual queue/admin handlers still accept such an existing untagged row.
        with Session(engine) as session:
            payment = Payment(user_id=owner["id"], purpose="booking", booking_id=booking_id,
                              amount_kop=amount_kop, method="sbp")
            session.add(payment)
            session.commit()
            session.refresh(payment)
            payment_id = payment.id
        created = None
    if created is not None:
        assert created.status_code == 200, created.text
        assert created.json()["status"] == "pending" and created.json()["method"] == "sbp_manual"
        payment_id = created.json()["payment_id"]
        assert created.json()["amount"] * 100 == amount_kop
    context = {"kind": kind, "admin": admin, "owner": owner, "driver": driver,
               "payment_id": payment_id, "ride_id": ride_id, "booking_id": booking_id,
               "amount_kop": amount_kop}
    assert payment_id in _queue(client, admin)
    snapshot = _snapshot(context)
    assert snapshot["payment"]["status"] == "pending" and snapshot["payment"]["amount_kop"] == amount_kop
    assert snapshot["ledger"] == [] and snapshot["earned_kop"] == 0
    if booking_id:
        assert snapshot["booking"]["paid"] is False
    if kind == "boost":
        assert snapshot["ride"]["boosted_until"] is None
    return context


def _queue(client, admin):
    response = client.get("/admin/payments/pending", headers=admin["auth"])
    assert response.status_code == 200, response.text
    return [row["payment_id"] for row in response.json()]


def _snapshot(context):
    with Session(engine) as session:
        payment = session.get(Payment, context["payment_id"])
        ride = session.get(Ride, context["ride_id"]) if context["ride_id"] else None
        booking = session.get(Booking, context["booking_id"]) if context["booking_id"] else None
        entries = session.exec(select(LedgerEntry).where(LedgerEntry.driver_id == context["driver"]["id"])
                               .order_by(LedgerEntry.id)).all()
        notes = session.exec(select(Notification).where(Notification.user_id == context["owner"]["id"])
                             .order_by(Notification.id)).all()
        return {"payment": {"id": payment.id, "payer": payment.user_id, "purpose": payment.purpose,
                            "status": payment.status, "method": payment.method,
                            "provider_id": payment.provider_id, "amount_kop": payment.amount_kop,
                            "settled_at": payment.settled_at.isoformat() if payment.settled_at else None},
                "ride": {"boosted_until": ride.boosted_until.isoformat() if ride.boosted_until else None,
                         "boost_tier": ride.boost_tier} if ride else None,
                "booking": {"paid": booking.paid, "payment_method": booking.payment_method} if booking else None,
                "ledger": [{"id": entry.id, "kind": entry.kind.value, "amount_kop": entry.amount_kop,
                            "booking_id": entry.booking_id, "driver": entry.driver_id} for entry in entries],
                "earned_kop": sum(entry.amount_kop for entry in entries if entry.kind.value == "earn"),
                "balance_kop": sum(entry.amount_kop for entry in entries),
                "notification_ids": [note.id for note in notes]}


def _summary(client, admin):
    response = client.get("/admin/payments/summary", headers=admin["auth"])
    assert response.status_code == 200, response.text
    return response.json()


def _action(client, actor, payment_id, action):
    return client.post(f"/admin/payments/{payment_id}/{action}", headers=actor["auth"])


def _observe(client, context, responses, before, **extra):
    status = client.get(f'/payments/{context["payment_id"]}/status', headers=context["owner"]["auth"])
    assert status.status_code == 200, status.text
    balance = client.get("/wallet/balance", headers=context["driver"]["auth"])
    assert balance.status_code == 200, balance.text
    observed = {"kind": context["kind"], "before": before, "after": _snapshot(context),
                "responses": [{"http": response.status_code, "body": response.json()} for response in responses],
                "owner_status": status.json(), "driver_balance_kop": balance.json()["balance_kop"],
                "in_manual_queue": context["payment_id"] in _queue(client, context["admin"]), **extra}
    if context["booking_id"]:
        observed["receipts"] = {}
        for role, actor in (("driver", context["driver"]), ("passenger", context["owner"])):
            receipt = client.get(f'/trips/{context["booking_id"]}/receipt', headers=actor["auth"])
            assert receipt.status_code == 200, receipt.text
            observed["receipts"][role] = {"amount": receipt.json()["amount"], "paid": receipt.json()["paid"]}
    print("QA_MANUAL_TERMINAL " + json.dumps(observed, ensure_ascii=False, sort_keys=True))
    return observed


def _truthful_confirm(response, snapshot, payment_id):
    # Current Android confirmPayment uses .map { }, treating ANY 2xx as confirmation.
    # Do not prescribe 409; but a success must match persisted succeeded and activation.
    if 200 <= response.status_code < 300:
        assert response.json()["payment_id"] == payment_id
        assert response.json()["status"] == snapshot["payment"]["status"] == "succeeded", \
            "successful admin confirmation must report the persisted terminal state"
    else:
        assert response.status_code < 500, response.text


@pytest.mark.parametrize("kind", ["donate", "support", "boost", "legacy_booking"])
def test_rejected_manual_invoice_cannot_report_false_confirmation(client, user_factory, kind):
    context = _input(client, user_factory, kind)
    summary = _summary(client, context["admin"])
    rejected = _action(client, context["admin"], context["payment_id"], "reject")
    assert rejected.status_code == 200 and rejected.json()["status"] == "canceled", rejected.text
    canceled = _snapshot(context)
    assert canceled["payment"]["status"] == "canceled" and canceled["earned_kop"] == 0
    duplicate_reject = _action(client, context["admin"], context["payment_id"], "reject")
    confirm = _action(client, context["admin"], context["payment_id"], "confirm")
    duplicate_confirm = _action(client, context["admin"], context["payment_id"], "confirm")
    observed = _observe(client, context, (rejected, duplicate_reject, confirm, duplicate_confirm), canceled)
    assert observed["after"] == canceled, "terminal rejected row/effects must not be resurrected or rewritten"
    assert observed["owner_status"]["status"] == "canceled" and not observed["in_manual_queue"]
    assert observed["driver_balance_kop"] == observed["after"]["earned_kop"] == 0
    assert _summary(client, context["admin"]) == summary
    _truthful_confirm(confirm, observed["after"], context["payment_id"])
    _truthful_confirm(duplicate_confirm, observed["after"], context["payment_id"])


@pytest.mark.parametrize("kind", ["donate", "support", "boost", "legacy_booking"])
def test_pending_manual_confirmation_and_duplicates_match_real_effect(client, user_factory, kind):
    context = _input(client, user_factory, kind)
    before = _snapshot(context)
    first = _action(client, context["admin"], context["payment_id"], "confirm")
    succeeded = _snapshot(context)
    repeated = _action(client, context["admin"], context["payment_id"], "confirm")
    after_paid_reject = _action(client, context["admin"], context["payment_id"], "reject")
    observed = _observe(client, context, (first, repeated, after_paid_reject), before)
    assert first.status_code == repeated.status_code == 200
    # Android treats any successful reject response as an actual rejection.
    assert not (200 <= after_paid_reject.status_code < 300), "a settled payment cannot report a successful rejection"
    _truthful_confirm(first, succeeded, context["payment_id"])
    _truthful_confirm(repeated, observed["after"], context["payment_id"])
    assert succeeded == observed["after"], "duplicate/late rejection must not change confirmed status/effects"
    assert observed["owner_status"]["status"] == "succeeded" and not observed["in_manual_queue"]
    assert observed["after"]["payment"]["settled_at"] is not None
    if kind == "legacy_booking":
        assert observed["after"]["booking"]["paid"] is True
        assert len(observed["after"]["ledger"]) == 1
        assert observed["after"]["ledger"][0]["kind"] == "earn"
        assert observed["after"]["earned_kop"] == observed["driver_balance_kop"] == 40000
        assert observed["receipts"] == {"driver": {"amount": 400, "paid": True},
                                         "passenger": {"amount": 400, "paid": True}}
    else:
        assert observed["after"]["ledger"] == [] and observed["driver_balance_kop"] == 0
        if kind == "boost":
            assert before["ride"]["boosted_until"] is None
            assert observed["after"]["ride"]["boosted_until"] is not None
            assert observed["after"]["ride"]["boost_tier"] == "quick"


@pytest.mark.parametrize("actor", ["owner", "outsider"])
@pytest.mark.parametrize("action", ["confirm", "reject"])
def test_manual_invoice_owner_and_unrelated_user_cannot_act_as_admin(client, user_factory, actor, action):
    context = _input(client, user_factory, "donate")
    user = context["owner"] if actor == "owner" else user_factory("QA_MANUAL_OUTSIDER")
    before = _snapshot(context)
    response = _action(client, user, context["payment_id"], action)
    observed = _observe(client, context, (response,), before, actor=actor, action=action)
    assert response.status_code == 403 and observed["after"] == before
    assert observed["in_manual_queue"]
    assert client.get("/admin/payments/pending", headers=user["auth"]).status_code == 403
    assert client.get("/admin/payments/summary", headers=user["auth"]).status_code == 403
    if actor == "outsider":
        assert client.get(f'/payments/{context["payment_id"]}/status', headers=user["auth"]).status_code == 404


@pytest.mark.parametrize("provider_tag", ["provider_id", "method"])
def test_provider_identified_invoice_stays_out_of_manual_queue_and_confirmation(client, user_factory, provider_tag):
    context = _input(client, user_factory, "boost")
    # Stored provider-metadata control, not a claimed actual provider invoice/network test.
    with Session(engine) as session:
        payment = session.get(Payment, context["payment_id"])
        if provider_tag == "provider_id":
            payment.provider_id = "qa-synthetic-provider-pending"
        else:
            payment.method = "yookassa"  # unknown outbound result, provider_id not yet present
        session.add(payment)
        session.commit()
    before = _snapshot(context)
    assert context["payment_id"] not in _queue(client, context["admin"])
    response = _action(client, context["admin"], context["payment_id"], "confirm")
    observed = _observe(client, context, (response,), before, provider_tag=provider_tag)
    assert not (200 <= response.status_code < 300), "only actual provider may confirm its pending invoice"
    assert response.status_code < 500 and observed["after"] == before
    assert observed["owner_status"]["status"] == "pending" and not observed["in_manual_queue"]


@pytest.mark.parametrize("action", ["confirm", "reject"])
def test_missing_manual_invoice_does_not_change_existing_invoice(client, user_factory, action):
    context = _input(client, user_factory, "donate")
    before = _snapshot(context)
    response = _action(client, context["admin"], 1_000_000_000, action)
    observed = _observe(client, context, (response,), before, missing_action=action)
    assert response.status_code == 404 and observed["after"] == before and observed["in_manual_queue"]


def test_admin_confirmation_leaves_unrelated_manual_invoice_pending(client, user_factory):
    target = _input(client, user_factory, "boost")
    unrelated = _input(client, user_factory, "donate")
    target_before = _snapshot(target)
    other_before = _snapshot(unrelated)
    response = _action(client, target["admin"], target["payment_id"], "confirm")
    observed = _observe(client, target, (response,), target_before, unrelated_before=other_before,
                        unrelated_after=_snapshot(unrelated))
    assert response.status_code == 200
    _truthful_confirm(response, observed["after"], target["payment_id"])
    assert observed["after"]["ride"]["boosted_until"] is not None
    assert _snapshot(unrelated) == other_before and unrelated["payment_id"] in _queue(client, target["admin"])
