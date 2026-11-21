"""Exercise bounded bank work through the dispatcher and real test databases."""

from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Barrier

import pytest
from sqlmodel import SQLModel, Session, create_engine, select

from app import rideshare_bank_dispatch, rideshare_bank_queue
from app.account import delete_user_account
from app.db import engine
from app.models import Booking, User
from app.owner_switches import PAID_SWITCH
from app.rideshare_bank_models import RideshareBankCheckout, RideshareBankOperation
from app.rideshare_bank_provider import BankResource
from app.rideshare_bank_queue import claim_due_bank_checkout, run_bank_queue
from app.timeutil import utcnow
from test_rideshare_bank_command import card_path, terms
from test_rideshare_bank_dispatch import bank_checkout as bank_checkout
from test_rideshare_bank_receipt import include_synthetic_receipt as include_synthetic_receipt
from test_rideshare_bank_settlement import authorize
from test_rideshare_bank_settlement import paid_checkout as paid_checkout


@pytest.fixture(autouse=True)
def queue_clock(client, monkeypatch):
    now = [utcnow() + timedelta(seconds=1)]
    monkeypatch.setattr(rideshare_bank_queue, "utcnow", lambda: now[0])
    monkeypatch.setattr(rideshare_bank_dispatch, "utcnow", lambda: now[0])
    with Session(engine) as session:
        prior = session.exec(select(RideshareBankCheckout.id, RideshareBankCheckout.next_bank_check_at)).all()
        for checkout_id, _ in prior:
            session.get(RideshareBankCheckout, checkout_id).next_bank_check_at = now[0] + timedelta(days=7)
        session.commit()
    yield now
    with Session(engine) as session:
        for checkout_id, previous_due in prior:
            session.get(RideshareBankCheckout, checkout_id).next_bank_check_at = previous_due
        session.commit()


def tick(service):
    return run_bank_queue(engine, runtime=service.runtime, provider=service.provider)


def test_queue_moves_one_step_at_a_time_from_api_confirmation_to_paid_receipt(
        client, bank_checkout, include_synthetic_receipt, queue_clock):
    service = bank_checkout
    include_synthetic_receipt(service)
    response = client.post(f"{card_path(service)}/confirm", headers=service.passenger["auth"], json=terms(client, service))
    assert response.status_code == 200
    opened, = tick(service)
    assert opened.state == "succeeded"
    assert tick(service) == []
    queue_clock[0] += timedelta(minutes=1)
    waiting, = tick(service)
    assert waiting.checkout_id == opened.checkout_id and waiting.state == "pending"
    service.fake.finish_payment()
    service.toggle(PAID_SWITCH, False)
    queue_clock[0] += timedelta(minutes=1)
    paid, = tick(service)
    assert paid.state == "succeeded" and tick(service) == []
    for actor in (service.passenger, service.driver):
        receipt = client.get(card_path(service), headers=actor["auth"]).json()
        assert (receipt["payment_status"], receipt["paid_kop"]) == ("succeeded", 45_000)
    assert [request[:2] for request in service.fake.requests] == [
        ("POST", "deals"), ("POST", "payments"), ("GET", "payments"),
    ]


@pytest.mark.parametrize("lookup", ["empty", "ambiguous", "expired"])
def test_queue_repeats_only_same_post_after_complete_empty_search(bank_checkout, queue_clock, lookup):
    service = bank_checkout
    checkout = service.begin()
    service.fake.lose_deal_response = True
    unknown, = tick(service)
    assert unknown.state == "needs_reconciliation"
    first_post, = service.fake.requests
    operation_id = first_post[2]
    if lookup == "ambiguous":
        original = service.fake.objects[service.fake.by_key[operation_id]]
        items = [original, {**original, "id": "synthetic-second-deal"}]
    else:
        items = []
    service.fake.lookup_pages["deals", None] = {"type": "list", "items": items}
    queue_clock[0] += timedelta(days=2) if lookup == "expired" else timedelta(minutes=1)
    result, = tick(service)
    assert service.fake.requests[1][:2] == ("LIST", "deals")
    if lookup == "empty":
        assert result.state == "succeeded" and service.fake.requests[2:] == [first_post]
    else:
        assert result.state == ("unconfirmed" if lookup == "ambiguous" else None)
        assert len(service.fake.requests) == 2
        with Session(engine) as session:
            assert session.get(RideshareBankOperation, operation_id).state == "needs_reconciliation"
            due = session.get(RideshareBankCheckout, checkout).next_bank_check_at
            assert due - queue_clock[0] == timedelta(minutes=15 if lookup == "expired" else 1)


def test_queue_reconciles_and_refunds_a_detached_booking_while_paused(paid_checkout, queue_clock):
    service, checkout_id, _ = paid_checkout
    refund = authorize(paid_checkout, BankResource.refund, 45_000)
    with Session(engine) as session:
        delete_user_account(session, session.get(User, service.passenger["id"]))
    service.toggle(PAID_SWITCH, False)
    before = len(service.fake.requests)
    assert tick(service)[0].state == "pending"
    service.fake.objects[service.fake.by_key[refund]]["status"] = "succeeded"
    queue_clock[0] += timedelta(minutes=1)
    assert tick(service)[0].state == "succeeded"
    assert [request[:2] for request in service.fake.requests[before:]] == [("POST", "refunds"), ("GET", "refunds")]
    with Session(engine) as session:
        assert session.get(RideshareBankCheckout, checkout_id).booking_id is None


def test_slow_post_and_second_worker_share_one_admitted_operation(bank_checkout, queue_clock):
    service = bank_checkout
    checkout_id = service.begin()
    recovered = []

    def second_worker():
        queue_clock[0] += timedelta(minutes=1)
        recovered.extend(tick(service))

    service.fake.on_first_deal = second_worker
    assert tick(service)[0].state is None
    assert recovered == [rideshare_bank_queue.BankQueueResult(checkout_id, "succeeded")]
    assert [request[0] for request in service.fake.requests] == ["POST", "LIST", "GET"]
    with Session(engine) as session:
        assert session.get(RideshareBankCheckout, checkout_id).next_bank_check_at == queue_clock[0] + timedelta(minutes=1)


def _seed_detached_checkouts(db, *, count=1):
    ids = []
    with Session(db) as session:
        for _ in range(count):
            checkout = RideshareBankCheckout(test_mode=True, amount_kop=100, payout_kop=100)
            session.add(checkout)
            session.flush()
            session.add(RideshareBankOperation(checkout_id=checkout.id, resource="deals",
                                              state="dispatched", dispatched_at=utcnow()))
            ids.append(checkout.id)
        session.commit()
    return ids


def test_unconfirmed_older_item_does_not_block_following_checkout(bank_checkout):
    service = bank_checkout
    older, = _seed_detached_checkouts(engine)
    with Session(engine) as session:
        operation = session.exec(select(RideshareBankOperation).where(RideshareBankOperation.checkout_id == older)).one()
        operation.request_ciphertext = b"synthetic prior request"
        session.commit()
    service.fake.lookup_pages["deals", None] = {"type": "invalid"}
    checkout = service.begin()
    assert [(result.checkout_id, result.state) for result in tick(service)] == [
        (older, "unconfirmed"), (checkout, "succeeded"),
    ]
    assert [request[0] for request in service.fake.requests] == ["LIST", "POST"]


def test_file_sqlite_workers_share_one_slot_and_recover_abandoned_claim(tmp_path, queue_clock):
    db = create_engine(f"sqlite:///{tmp_path / 'worker-claim.sqlite3'}")
    try:
        SQLModel.metadata.create_all(db)
        checkout, = _seed_detached_checkouts(db)
        barrier = Barrier(2)

        def claim():
            barrier.wait(timeout=5)
            return claim_due_bank_checkout(db, test_mode=True, ready_before=queue_clock[0])

        with ThreadPoolExecutor(max_workers=2) as workers:
            first, second = [workers.submit(claim) for _ in range(2)]
            assert {first.result(timeout=10), second.result(timeout=10)} == {None, checkout}
        assert claim_due_bank_checkout(db, test_mode=True, ready_before=queue_clock[0]) is None
        queue_clock[0] += timedelta(minutes=1)
        assert claim_due_bank_checkout(db, test_mode=True, ready_before=queue_clock[0]) == checkout
    finally:
        db.dispose()


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="SKIP LOCKED requires PostgreSQL")
def test_postgres_worker_skips_oldest_locked_checkout(queue_clock):
    oldest, available = _seed_detached_checkouts(engine, count=2)
    with ThreadPoolExecutor(max_workers=1) as workers:
        with Session(engine) as session:
            assert session.get(RideshareBankCheckout, oldest, with_for_update=True)
            next_checkout = workers.submit(claim_due_bank_checkout, engine, test_mode=True, ready_before=queue_clock[0])
            assert next_checkout.result(timeout=8) == available
    assert claim_due_bank_checkout(engine, test_mode=True, ready_before=queue_clock[0]) == oldest


def test_stale_retry_target_cannot_start_next_operation(bank_checkout):
    service = bank_checkout
    checkout_id = service.begin()
    assert tick(service)[0].state == "succeeded"
    _, _, deal, _ = service.fake.requests[0]
    assert rideshare_bank_dispatch.dispatch_bank_operation(
        engine, checkout_id, runtime=service.runtime, provider=service.provider, expected_operation_id=deal,
    ) is None
    with Session(engine) as session:
        assert session.get(Booking, service.booking_id).paid is False
        assert session.exec(select(RideshareBankOperation.resource).where(
            RideshareBankOperation.checkout_id == checkout_id,
        )).all() == ["deals"]
