"""The scheduled pass reads only the exact bank operation it has leased."""

from concurrent.futures import ThreadPoolExecutor
from configparser import ConfigParser
from dataclasses import replace
from datetime import timedelta
from functools import partial
from pathlib import Path
from threading import Event
from types import SimpleNamespace
from uuid import uuid4

import httpx
import pytest
from sqlmodel import Session, select

from app import rideshare_bank_queue as queue
from app import rideshare_bank_worker as worker
from app.config import Settings
from app.db import engine
from app.models import Booking
from app.owner_switches import PAID_SWITCH
from app.rideshare_bank_models import RideshareBankCheckout, RideshareBankOperation, RideshareBankPosting
from app.rideshare_bank_provider import YooKassaSafeDeal
from app.timeutil import utcnow
from test_rideshare_bank_command import card_path
from test_rideshare_bank_dispatch import bank_checkout as bank_checkout
from test_rideshare_bank_dispatch import send
from test_rideshare_bank_receipt import include_synthetic_receipt as include_synthetic_receipt


@pytest.fixture
def worker_checkout(bank_checkout):
    service = bank_checkout
    service.runtime = replace(service.runtime, provider_account_id=uuid4().hex)
    service.begin = partial(service.begin, selected_runtime=service.runtime)
    return service


def _run(service, provider=None):
    return queue.run_bank_reconciliation(engine, runtime=service.runtime, provider=provider or service.provider)


def _pending_payment(service):
    checkout_id = service.begin()
    assert send(service, checkout_id) == "succeeded"
    assert send(service, checkout_id) == "pending"
    return checkout_id, service.fake.requests[-1][2]


def test_reconciliation_updates_the_receipt_on_pause_without_starting_work(client, worker_checkout,
                                                                        include_synthetic_receipt):
    service = worker_checkout
    include_synthetic_receipt(service)
    checkout_id = service.begin()
    assert _run(service) == []
    assert service.fake.requests == []
    assert send(service, checkout_id) == "succeeded"
    assert _run(service) == []
    assert len(service.fake.requests) == 1
    assert send(service, checkout_id) == "pending"
    payment_id = service.fake.requests[-1][2]
    service.fake.finish_payment(payment_id)
    service.toggle(PAID_SWITCH, False)
    assert _run(service) == [queue.BankQueueResult(checkout_id, "succeeded")]
    assert _run(service) == []
    assert [call[0] for call in service.fake.requests] == ["POST", "POST", "GET"]
    receipt = client.get(card_path(service), headers=service.passenger["auth"]).json()
    assert receipt["payment_status"] == "succeeded" and receipt["paid_kop"] == 45_000


@pytest.mark.parametrize("detached", [False, True])
def test_existing_operation_reconciles_after_detachment_or_direct_payment(worker_checkout, detached):
    service = worker_checkout
    checkout_id, payment_id = _pending_payment(service)
    with Session(engine) as db:
        checkout = db.get(RideshareBankCheckout, checkout_id)
        if detached:
            checkout.closed_at = utcnow()
            checkout.booking_id = None
        else:
            booking = db.get(Booking, service.booking_id)
            booking.paid = True
            booking.payment_method = "cash"
        db.commit()
    service.fake.finish_payment(payment_id)
    assert _run(service) == [queue.BankQueueResult(checkout_id, "succeeded")]
    assert [call[0] for call in service.fake.requests] == ["POST", "POST", "GET"]
    with Session(engine) as db:
        assert db.get(RideshareBankPosting, payment_id).amount_kop == 45_000
        if not detached:
            assert db.get(RideshareBankCheckout, checkout_id).receipt_conflict
            assert db.get(Booking, service.booking_id).payment_method == "cash"


def test_read_lease_prevents_a_competing_tick_after_the_poll_interval(worker_checkout, monkeypatch):
    service = worker_checkout
    checkout_id, payment_id = _pending_payment(service)
    service.fake.finish_payment(payment_id)
    clock = [utcnow()]
    monkeypatch.setattr(queue, "utcnow", lambda: clock[0])
    entered, release = Event(), Event()

    def handle(request):
        assert request.method == "GET"
        entered.set()
        assert release.wait(8)
        return service.fake.handle(request)

    with httpx.Client(transport=httpx.MockTransport(handle), trust_env=False) as client:
        provider = YooKassaSafeDeal(client, shop_id="synthetic-shop", secret_key="synthetic-secret", test_mode=True)
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(_run, service, provider)
            try:
                assert entered.wait(6)
                clock[0] += timedelta(seconds=70)
                assert _run(service) == []
            finally:
                release.set()
            assert future.result(timeout=8) == [queue.BankQueueResult(checkout_id, "succeeded")]
    assert [call[0] for call in service.fake.requests] == ["POST", "POST", "GET"]


def test_expired_read_claim_can_be_taken_over_and_late_finish_does_not_shorten_it(worker_checkout, monkeypatch):
    service = worker_checkout
    checkout_id, payment_id = _pending_payment(service)
    clock = [utcnow()]
    monkeypatch.setattr(queue, "utcnow", lambda: clock[0])
    abandoned = queue._claim_bank_read(engine, service.runtime, clock[0])
    assert abandoned is not None and abandoned.operation_id == payment_id
    clock[0] = abandoned.lease_until + timedelta(seconds=1)
    takeover = queue._claim_bank_read(engine, service.runtime, clock[0])
    assert takeover is not None and takeover.operation_id == payment_id and takeover.lease_until > abandoned.lease_until
    queue._finish_bank_read(engine, abandoned)
    with Session(engine) as db:
        assert db.get(RideshareBankCheckout, checkout_id).next_bank_check_at == takeover.lease_until
    assert _run(service) == []
    service.fake.finish_payment(payment_id)
    clock[0] = takeover.lease_until + timedelta(seconds=1)
    assert _run(service) == [queue.BankQueueResult(checkout_id, "succeeded")]
    assert [call[0] for call in service.fake.requests] == ["POST", "POST", "GET"]


def test_worker_never_replaces_the_claimed_operation_with_a_new_one(worker_checkout, monkeypatch):
    service = worker_checkout
    checkout_id, payment_id = _pending_payment(service)
    claim = queue._claim_bank_read

    def switch_operation(db, runtime, ready_before):
        pinned = claim(db, runtime, ready_before)
        if pinned is None:
            return None
        assert pinned.operation_id == payment_id
        with Session(db) as session:
            session.get(RideshareBankOperation, payment_id).state = "canceled"
            session.flush()
            session.add(RideshareBankOperation(checkout_id=checkout_id, resource="payments", state="pending",
                                               amount_kop=45_000, settlement_kop=45_000,
                                               dispatched_at=utcnow(), provider_id="synthetic-replacement-payment"))
            session.commit()
        return pinned

    monkeypatch.setattr(queue, "_claim_bank_read", switch_operation)
    assert _run(service) == [queue.BankQueueResult(checkout_id, None)]
    assert [call[0] for call in service.fake.requests] == ["POST", "POST"]
    with Session(engine) as db:
        assert db.exec(select(RideshareBankOperation.state).where(
            RideshareBankOperation.provider_id == "synthetic-replacement-payment",
        )).one() == "pending"


@pytest.mark.parametrize("outcome", ["empty", "incomplete", "ambiguous", "404", "timeout"])
def test_unconfirmed_read_never_sends_or_restarts_an_operation(worker_checkout, outcome):
    service = worker_checkout
    checkout_id = service.begin()
    service.fake.lose_deal_response = True
    assert send(service, checkout_id) == "needs_reconciliation"
    operation_id = service.fake.requests[0][2]
    provider_id = service.fake.by_key[operation_id]
    original = service.fake.objects[provider_id]
    reads = []

    def handle(request):
        assert request.method == "GET"
        reads.append(request.url.path)
        if outcome == "404":
            return httpx.Response(404)
        if outcome == "timeout":
            raise httpx.ReadTimeout("Synthetic read timeout")
        if outcome == "incomplete":
            return httpx.Response(200, json={"type": "list", "items": [original], "next_cursor": "again"})
        items = [original, {**original, "id": "synthetic-also-matched"}] if outcome == "ambiguous" else []
        return httpx.Response(200, json={"type": "list", "items": items})

    with httpx.Client(transport=httpx.MockTransport(handle), trust_env=False) as client:
        provider = YooKassaSafeDeal(client, shop_id="synthetic-shop", secret_key="synthetic-secret", test_mode=True)
        assert _run(service, provider) == [queue.BankQueueResult(checkout_id, "unconfirmed")]
    assert reads and all(path == "/v3/deals" for path in reads)
    assert len(service.fake.requests) == 1
    with Session(engine) as db:
        operation = db.get(RideshareBankOperation, operation_id)
        assert operation.provider_id is None and operation.state == "needs_reconciliation"


def test_read_pass_does_not_claim_a_different_configured_account(worker_checkout):
    service = worker_checkout
    checkout_id, _ = _pending_payment(service)
    with Session(engine) as db:
        db.get(RideshareBankCheckout, checkout_id).provider_account_id = "other-synthetic-account"
        db.commit()
    assert _run(service) == []
    assert len(service.fake.requests) == 2
    with Session(engine) as db:
        assert db.get(RideshareBankCheckout, checkout_id).next_bank_check_at is None


@pytest.mark.parametrize("enabled", [False, True])
def test_worker_with_incomplete_configuration_opens_no_transport_or_database(monkeypatch, enabled):
    monkeypatch.setattr(worker, "settings", Settings(_env_file=None, rideshare_bank_read_enabled=enabled,
                                                     rideshare_bank_shop_id="", rideshare_bank_secret="",
                                                     rideshare_bank_fernet_key=""))

    def forbidden(*args, **kwargs):
        pytest.fail("Unconfigured reconciliation tried to open a resource")

    monkeypatch.setattr(worker.httpx, "Client", forbidden)
    monkeypatch.setattr(worker, "run_bank_reconciliation", forbidden)
    monkeypatch.setattr(worker, "engine", SimpleNamespace(dispose=forbidden))
    worker.main()


def test_worker_releases_transport_and_engine_on_exception(monkeypatch):
    events = []

    class Transport:
        def __enter__(self):
            events.append("opened")
            return self

        def __exit__(self, *exc):
            events.append("closed")

    def fail(engine, *, runtime, provider):
        assert runtime == "runtime" and provider == "provider"
        raise RuntimeError("Synthetic failure")

    config = SimpleNamespace(runtime="runtime", provider=lambda client: "provider")
    monkeypatch.setattr(worker, "configure_rideshare_bank", lambda settings: config)
    monkeypatch.setattr(worker.httpx, "Client", lambda **kwargs: Transport())
    monkeypatch.setattr(worker, "run_bank_reconciliation", fail)
    monkeypatch.setattr(worker, "engine", SimpleNamespace(dispose=lambda: events.append("disposed")))
    with pytest.raises(RuntimeError, match="Synthetic failure"):
        worker.main()
    assert events == ["opened", "closed", "disposed"]


def test_service_stops_before_its_read_lease_expires():
    unit = Path(__file__).resolve().parents[1] / "deploy" / "yuldash-rideshare-bank-worker.service"
    configuration = ConfigParser(interpolation=None)
    assert configuration.read(unit, encoding="utf-8")
    service = configuration["Service"]
    assert service["ExecStart"].endswith(" -m app.rideshare_bank_worker")
    assert service.getint("TimeoutStartSec") + service.getint("TimeoutStopSec") < queue._READ_LEASE.total_seconds()
