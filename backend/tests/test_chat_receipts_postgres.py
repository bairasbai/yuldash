"""Real PostgreSQL receipt writes and access ordering on a disposable database."""
import asyncio
from concurrent.futures import ThreadPoolExecutor
from datetime import timezone
from threading import Event

import anyio
import pytest
from sqlalchemy import text
from sqlmodel import Session

from app.chat_receipt_models import ChatMessageReceipt
from app.chat_receipts import ReceiptBatch
from app.config import settings
from app.db import engine
from app.models import User
from app.routers import chat, safety
from app.timeutil import utcnow
from test_location_privacy import _confirmed_booking
from test_rest_chat_local_delivery import send


pytestmark = pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL")


@pytest.mark.parametrize("read_first", [False, True])
def test_delivery_and_read_races_keep_the_strongest_receipt(client, user_factory, monkeypatch, read_first):
    monkeypatch.setattr(settings, "chat_receipts_since", utcnow().replace(tzinfo=timezone.utc))
    driver, passenger, bid = _confirmed_booking(client, user_factory, "ReceiptRace")
    mid = send(client, bid, driver, "Одна отметка для двух устройств")["id"]
    first_inside = Event()
    second_started = Event()
    release = Event()
    apply = chat.apply_receipt_batch

    def ordered_apply(session, booking_id, user_id, peer_id, since, batch):
        if bool(batch.read_ids) == read_first:
            first_inside.set()
            assert release.wait(5), "test must release the first transaction"
        return apply(session, booking_id, user_id, peer_id, since, batch)

    monkeypatch.setattr(chat, "apply_receipt_batch", ordered_apply)

    def worker(read):
        with Session(engine) as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            pid = session.execute(text("SELECT pg_backend_pid()")).scalar_one()
            actor = session.get(User, passenger["id"])
            batch = ReceiptBatch(read_ids=[mid]) if read else ReceiptBatch(delivered_ids=[mid])
            if read != read_first:
                second_started.set()
            return pid, chat._apply_booking_receipts(bid, batch, actor, session)

    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(worker, read_first)
        try:
            assert first_inside.wait(5)
            second = pool.submit(worker, not read_first)
            assert second_started.wait(5)
            assert not second.done()
        finally:
            release.set()
        (first_pid, (_, changed1)), (second_pid, (_, changed2)) = first.result(10), second.result(10)

    assert first_pid != second_pid
    assert len(changed1) == 1
    assert len(changed2) == (0 if read_first else 1)
    with Session(engine) as session:
        receipt = session.get(ChatMessageReceipt, (mid, passenger["id"]))
        assert receipt is not None and receipt.delivered and receipt.read



def test_final_socket_enqueue_is_serialized_with_block(client, user_factory, monkeypatch):
    monkeypatch.setattr(settings, "chat_receipts_since", utcnow().replace(tzinfo=timezone.utc))
    driver, passenger, bid = _confirmed_booking(client, user_factory, "ReceiptBlockRace")
    mid = send(client, bid, driver, "Буду на остановке")["id"]
    with Session(engine) as session:
        actor = session.get(User, passenger["id"])
        _, changed = chat._apply_booking_receipts(bid, ReceiptBatch(read_ids=[mid]), actor, session)

    at_enqueue = Event()
    block_attempted = Event()
    release = Event()
    order = []
    lock = safety.lock_receipt_pair

    async def wait_at_enqueue(_websocket, items):
        assert items[0]["message_id"] == mid
        at_enqueue.set()
        assert await asyncio.to_thread(release.wait, 5)
        order.append("enqueued")

    def before_block_lock(session, actor, peer):
        block_attempted.set()
        return lock(session, actor, peer)

    async def deliver():
        await anyio.to_thread.run_sync(chat._send_locked_receipt_frame, object(), driver["token"], bid, changed)

    def block():
        response = client.post("/blocks", headers=driver["auth"], json={"blocked_user_id": passenger["id"]})
        assert response.status_code == 200, response.text
        order.append("block committed")

    monkeypatch.setattr(chat, "_enqueue_receipt_frame", wait_at_enqueue)
    monkeypatch.setattr(safety, "lock_receipt_pair", before_block_lock)

    with ThreadPoolExecutor(max_workers=2) as pool:
        sending = pool.submit(asyncio.run, deliver())
        try:
            assert at_enqueue.wait(5)
            blocking = pool.submit(block)
            assert block_attempted.wait(5)
            assert not blocking.done()
        finally:
            release.set()
        sending.result(10)
        blocking.result(10)

    assert order == ["enqueued", "block committed"]
    asyncio.run(deliver())
    assert order == ["enqueued", "block committed"]
