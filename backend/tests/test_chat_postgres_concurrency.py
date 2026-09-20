"""Real PostgreSQL concurrent REST retries; isolated test database only."""
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier, Lock, local

import pytest
from fastapi import HTTPException
from sqlalchemy import text
from sqlmodel import Session, select

from app.db import engine
from app.models import ChatMessageRequest, Message, User
from app.routers import chat
from test_location_privacy import _confirmed_booking

pytestmark = pytest.mark.skipif(engine.dialect.name != 'postgresql', reason='requires isolated PostgreSQL')


@pytest.mark.parametrize('different_payload', [False, True], ids=['same-payload', 'different-payload'])
def test_concurrent_retries_commit_one_message(client, user_factory, monkeypatch, different_payload):
    _, passenger, booking_id = _confirmed_booking(client, user_factory, 'PgChatRetry')
    barrier = Barrier(2)
    state = local()
    events = []
    event_lock = Lock()
    real_replay = chat._replay_message

    def replay_after_both_missed(*args):
        result = real_replay(*args)
        if not getattr(state, 'checked', False):
            state.checked = True
            assert result is None, 'Both independent initial SELECTs must miss before either insert'
            barrier.wait(timeout=10)
        return result

    def record(kind):
        def callback(*args, **kwargs):
            with event_lock:
                events.append(kind)
        return callback

    monkeypatch.setattr(chat, '_replay_message', replay_after_both_missed)
    monkeypatch.setattr(chat, 'notify_chat_message', record('live'))
    monkeypatch.setattr(chat, 'push_notification', record('push'))

    def worker(payload):
        with Session(engine) as session:
            session.execute(text("SET lock_timeout = '4s'"))
            session.execute(text("SET statement_timeout = '8s'"))
            pid = session.execute(text('SELECT pg_backend_pid()')).scalar_one()
            user = session.get(User, passenger['id'])
            try:
                message = chat.send_message(booking_id, chat.MessageIn(text=payload), user=user,
                                            session=session, idempotency_key='pg-shared-key')
                return pid, 200, message.id
            except HTTPException as error:
                return pid, error.status_code, None

    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(worker, payload) for payload in
                   ('fixture first', 'fixture second' if different_payload else 'fixture first')]
        results = [future.result(timeout=20) for future in futures]

    print(f'PG retry different_payload={different_payload}: pids={[row[0] for row in results]}, statuses={[row[1] for row in results]}')
    assert len({row[0] for row in results}) == 2, 'Must use two distinct PostgreSQL server connections'
    assert sorted(row[1] for row in results) == ([200, 409] if different_payload else [200, 200])
    accepted_ids = {row[2] for row in results if row[1] == 200}
    assert len(accepted_ids) == 1
    assert sorted(events) == ['live', 'push'], 'Only the winning transaction may notify'
    with Session(engine) as session:
        messages = session.exec(select(Message).where(Message.booking_id == booking_id)).all()
        receipts = session.exec(select(ChatMessageRequest).where(ChatMessageRequest.booking_id == booking_id)).all()
        assert len(messages) == len(receipts) == 1
        assert messages[0].id == receipts[0].message_id == next(iter(accepted_ids))
        assert messages[0].text in ('fixture first', 'fixture second')
